// Package server assembles and runs the tunneld process: Redis, CA, ban engine + watcher, routing
// registry, ONE bandwidth bucket registry (shared by the wsconn manager and ingress), the wsconn
// manager, the metrics/internal server, and the public server — with graceful shutdown.
package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/admin"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/caplog"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ingress"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/metrics"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/router"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/transport"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wsconn"
	"github.com/redis/go-redis/v9"
	"golang.org/x/sync/errgroup"
)

const (
	readHeaderTimeout = 10 * time.Second // gosec G112 / Slowloris on the header phase
	adminCounterTTL   = time.Hour        // tcnt:{name} retention
	flushInterval     = 5 * time.Second  // async tcnt flusher cadence
)

// Run constructs every component and runs the process until ctx is cancelled, then drains gracefully.
func Run(ctx context.Context, cfg config.ServeCmd, logger *slog.Logger, version string) error {
	redisOpts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		return err
	}
	rdb := redis.NewClient(redisOpts)
	defer func() { _ = rdb.Close() }()

	caObj, err := ca.Load(cfg.CACert, cfg.CAKey, cfg.CertValidity)
	if err != nil {
		return err
	}
	bps, err := config.ParseBitrate(cfg.LimitBandwidth)
	if err != nil {
		return err
	}
	headersLimit, err := config.ParseByteSize(cfg.LimitHeaders)
	if err != nil {
		return err
	}

	nodeID := newNodeID()
	banEng := ban.NewEngine()
	reg := router.NewRegistry(rdb, cfg.RouteTTL)
	buckets := limit.NewBucketRegistry(bps)

	m := metrics.NewMetrics()
	adminStore := admin.NewStore(rdb, adminCounterTTL)
	caplogger := caplog.New(logger)
	rec := metrics.NewPromRecorder(m, caplogger, adminStore)

	// drainCtx keeps the NODE-serving path (ServeNode, the per-message handlers, the async flusher,
	// and every Conn's lifetime) ALIVE after the parent ctx is cancelled — cancelled only AFTER
	// http.Server.Shutdown has drained in-flight ingress handlers within --shutdown-grace, so an
	// in-flight tunnel request completes instead of being abandoned to a 504 (US10 AC: "drain
	// in-flight up to --shutdown-grace"). It derives from Background, NOT ctx.
	drainCtx, drainCancel := context.WithCancel(context.Background())
	defer drainCancel()

	manager, err := wsconn.NewManager(drainCtx, cfg, nodeID, rdb, reg, banEng, caObj, buckets, rec, logger)
	if err != nil {
		return err
	}
	ingressH, err := ingress.NewHandler(cfg, nodeID, rdb, banEng, reg, buckets, rec, logger)
	if err != nil {
		return err
	}
	enrollH, err := ingress.NewEnrollHandler(cfg, caObj, rdb, banEng, rec, logger)
	if err != nil {
		return err
	}

	publicSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           NewMux(cfg, manager, ingressH, enrollH),
		MaxHeaderBytes:    int(2 * headersLimit), // strictly above the explicit US7 header check
		ReadHeaderTimeout: readHeaderTimeout,
		// ReadTimeout is deliberately NOT set — it would kill legitimately paced (slow) body uploads.
	}
	internalSrv := &http.Server{
		Addr:              cfg.InternalListen,
		Handler:           metrics.Handler(m.Registry(), rdb, adminStore, logger),
		ReadHeaderTimeout: readHeaderTimeout,
	}

	// STARTUP ORDER: initial ban Load SYNCHRONOUSLY, before any listener accepts — "ban-first" must
	// hold from the very first request (lenient: absent/unreadable files and bad lines skip-and-warn).
	if err := banEng.Load(cfg.BanFile, cfg.DBIPCountryLiteCSV, logger); err != nil {
		logger.Warn("initial ban load error (continuing; empty snapshot keeps ban-first panic-safe)", "err", err)
	}

	logger.Info("tunneld starting", "version", version, "node", nodeID, "listen", cfg.Listen, "internal", cfg.InternalListen)

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error { return serveHTTP(publicSrv) })
	g.Go(func() error { return serveHTTP(internalSrv) })
	// Node-serving path runs on drainCtx (survives parent-cancel until the grace window ends).
	g.Go(func() error {
		return transport.ServeNode(drainCtx, rdb, nodeID, cfg.LimitRequestTimeout, rec, manager.RouteLocal)
	})
	g.Go(func() error { return rec.RunFlusher(drainCtx, flushInterval) })
	g.Go(func() error {
		ban.Watch(gctx, banEng, cfg.BanFile, cfg.DBIPCountryLiteCSV, cfg.BanPoll, manager.EvictBanned, logger)
		return nil
	})
	g.Go(func() error {
		<-gctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownGrace)
		defer cancel()
		// Stop accepting + DRAIN in-flight ingress handlers first: the node path (ServeNode + Conns)
		// is still alive on drainCtx, so those handlers' RoundTrips can complete within the grace
		// window. Only then cancel the drain and tear down the WebSockets.
		_ = publicSrv.Shutdown(shutCtx)
		_ = internalSrv.Shutdown(shutCtx)
		drainCancel()
		manager.Shutdown()
		return nil
	})

	err = g.Wait()
	caplogger.Flush()
	if errors.Is(err, context.Canceled) || errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func serveHTTP(s *http.Server) error {
	if err := s.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func newNodeID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
