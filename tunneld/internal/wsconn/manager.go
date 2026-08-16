// Package wsconn owns the reserved /connect path: ban-check, WebSocket accept, the application-layer
// challenge-response authentication (NO TLS mTLS), routing bind, and the binary-frame bridge between
// Redis-delivered requests and the phone (chunked, bandwidth-paced), with liveness via native pings.
package wsconn

import (
	"context"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/clientip"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/router"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
	"github.com/redis/go-redis/v9"
)

// WebSocket application close codes (4000-4999 range).
const (
	closeAuthFailed = websocket.StatusPolicyViolation
	closeBanned     = websocket.StatusCode(4403)
	closeConflict   = websocket.StatusCode(4409)
)

// challengeJSON is the CHALLENGE frame header ({"nonce": base64}).
type challengeJSON struct {
	Nonce []byte `json:"nonce"`
}

// authJSON is the AUTH frame header ({"cert": base64DER, "signature": base64}).
type authJSON struct {
	Cert      string `json:"cert"`
	Signature string `json:"signature"`
}

// Manager owns all /connect connections on this node.
type Manager struct {
	baseCtx       context.Context
	cfg           config.ServeCmd
	nodeID        string
	rdb           redis.UniversalClient
	registry      *router.Registry
	ban           *ban.Engine
	ca            *ca.CA
	caPool        *x509.CertPool
	buckets       *limit.BucketRegistry
	rec           observ.Recorder
	log           *slog.Logger
	responseLimit int64
	connectSem    chan struct{}
	conns         sync.Map // name -> *Conn
}

// NewManager constructs the manager; responseLimit is parsed from --limit-response.
func NewManager(baseCtx context.Context, cfg config.ServeCmd, nodeID string, rdb redis.UniversalClient,
	registry *router.Registry, banEng *ban.Engine, caObj *ca.CA, buckets *limit.BucketRegistry,
	rec observ.Recorder, log *slog.Logger) (*Manager, error) {
	respLimit, err := config.ParseByteSize(cfg.LimitResponse)
	if err != nil {
		return nil, err
	}
	return &Manager{
		baseCtx:       baseCtx,
		cfg:           cfg,
		nodeID:        nodeID,
		rdb:           rdb,
		registry:      registry,
		ban:           banEng,
		ca:            caObj,
		caPool:        caObj.Pool(),
		buckets:       buckets,
		rec:           rec,
		log:           log,
		responseLimit: respLimit,
		connectSem:    make(chan struct{}, cfg.LimitConnectPending),
	}, nil
}

// HandleConnect owns the entire reserved /connect path.
func (m *Manager) HandleConnect(w http.ResponseWriter, r *http.Request) {
	ip, ok := clientip.TrustedIP(r, m.cfg.ClientIPHeader)
	if !ok {
		m.rec.Reject("missing_client_ip", "", "")
		http.Error(w, "missing client ip", http.StatusBadRequest)
		return
	}
	if src, banned := m.ban.Match(ip); banned {
		m.rec.Reject(src.Reason.String(), "", ip.String())
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	allowed, _, err := limit.Allow(r.Context(), m.rdb, "connect", ip, m.cfg.LimitRPM, time.Minute)
	if err != nil {
		m.log.Warn("connect rate check failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !allowed {
		m.rec.Reject("rate_connect", "", ip.String())
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	// Pre-auth semaphore: bound concurrent unauthenticated handshakes.
	select {
	case m.connectSem <- struct{}{}:
	default:
		m.rec.Reject("connect_pending", "", ip.String())
		http.Error(w, "server busy", http.StatusServiceUnavailable)
		return
	}
	slotReleased := false
	releaseSlot := func() {
		if !slotReleased {
			slotReleased = true
			<-m.connectSem
		}
	}
	defer releaseSlot() // safety net; also released explicitly once the handshake resolves

	if !isWebSocketUpgrade(r) {
		http.Error(w, "upgrade required", http.StatusUpgradeRequired)
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{})
	if err != nil {
		m.log.Warn("ws accept failed", "err", err)
		return
	}
	// The library default read limit (32768) is under one full RESPONSE_BODY_CHUNK frame; raise it.
	c.SetReadLimit(int64(wire.ChunkSize) + 64*1024)

	hostName := hostLabel(r.Host)
	name, fp, err := m.authenticate(c, hostName)
	releaseSlot() // handshake resolved either way
	if err != nil {
		m.rec.Reject("connect_auth_failed", "", ip.String())
		_ = c.Close(closeAuthFailed, "auth failed")
		return
	}
	if src, banned := m.ban.MatchTunnel(name, fp); banned {
		m.rec.Reject(src.Reason.String(), name, ip.String())
		_ = c.Close(closeBanned, "banned tunnel")
		return
	}
	connID := randID()
	if err := m.registry.Bind(r.Context(), name, m.nodeID, fp, connID); err != nil {
		if errors.Is(err, router.ErrNameHeldByOther) {
			m.rec.Reject("fingerprint_conflict", name, ip.String())
			m.log.Warn("fingerprint conflict on /connect", "name", name)
			_ = c.Close(closeConflict, "fingerprint conflict")
			return
		}
		m.log.Warn("bind failed", "name", name, "err", err)
		_ = c.Close(websocket.StatusInternalError, "bind failed")
		return
	}

	up, down := m.buckets.Pair(name)
	connCtx, cancel := context.WithCancel(m.baseCtx)
	conn := &Conn{
		name: name, fp: fp, connID: connID,
		ws: c, mgr: m, up: up, down: down,
		ctx: connCtx, cancel: cancel,
	}
	m.conns.Store(name, conn) // a same-name Store overwrites a lingering stale Conn (new conn owns the name)
	m.rec.WSConnect()
	conn.serve()
}

// authenticate sends CHALLENGE, reads AUTH within --connect-auth-timeout, verifies the cert chain +
// possession + CN==host, and returns (name, fingerprint).
func (m *Manager) authenticate(c *websocket.Conn, hostName string) (name, fp string, err error) {
	ctx, cancel := context.WithTimeout(m.baseCtx, m.cfg.ConnectAuthTimeout)
	defer cancel()

	nonce := make([]byte, 32)
	if _, err := rand.Read(nonce); err != nil {
		return "", "", err
	}
	chHdr, _ := json.Marshal(challengeJSON{Nonce: nonce})
	if err := c.Write(ctx, websocket.MessageBinary, wire.EncodeFrame(wire.CHALLENGE, chHdr, nil)); err != nil {
		return "", "", err
	}
	_, data, err := c.Read(ctx)
	if err != nil {
		return "", "", err
	}
	typ, hdr, _, err := wire.DecodeFrame(data)
	if err != nil {
		return "", "", err
	}
	if typ != wire.AUTH {
		return "", "", errors.New("expected AUTH frame")
	}
	var auth authJSON
	if err := json.Unmarshal(hdr, &auth); err != nil {
		return "", "", err
	}
	cert, err := ca.ParseCertB64DER(auth.Cert)
	if err != nil {
		return "", "", err
	}
	name, fp, err = ca.VerifyEnrolledCert(cert, m.caPool)
	if err != nil {
		return "", "", err
	}
	sig, err := base64.StdEncoding.DecodeString(auth.Signature)
	if err != nil {
		return "", "", err
	}
	if err := ca.VerifyPossession(cert, nonce, sig); err != nil {
		return "", "", err
	}
	if name != hostName {
		return "", "", errors.New("certificate CN does not match Host")
	}
	return name, fp, nil
}

// RouteLocal finds the local Conn for req.TunnelName and dispatches the request (or a synthetic 502
// if the WS just dropped). ctx is ServeNode's per-message ctx (already WithTimeout).
func (m *Manager) RouteLocal(ctx context.Context, req *wire.ReqEnvelope) *wire.RespEnvelope {
	v, ok := m.conns.Load(req.TunnelName)
	if !ok {
		return &wire.RespEnvelope{ReqID: req.ReqID, Status: http.StatusBadGateway, Err: "tunnel offline", ErrCode: "tunnel_gone"}
	}
	return v.(*Conn).Do(ctx, req)
}

// Shutdown tears down every live Conn (closing the WS and unbinding its route) for graceful drain.
func (m *Manager) Shutdown() {
	m.conns.Range(func(_, v any) bool {
		v.(*Conn).teardown("shutdown")
		return true
	})
}

// EvictBanned is the ban-reload hook: it drops any live Conn whose (name, fingerprint) is now banned
// (required because there is no idle disconnect).
func (m *Manager) EvictBanned(e *ban.Engine) {
	m.conns.Range(func(_, v any) bool {
		c := v.(*Conn)
		if src, banned := e.MatchTunnel(c.name, c.fp); banned {
			c.teardown(src.Reason.String())
		}
		return true
	})
}

func isWebSocketUpgrade(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get("Upgrade"), "websocket") &&
		strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade")
}

// hostLabel extracts the tunnel <name> (first DNS label) from a Host header, stripping any port.
func hostLabel(host string) string {
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.TrimSuffix(host, ".")
	if i := strings.IndexByte(host, '.'); i >= 0 {
		return host[:i]
	}
	return host
}

func randID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func chunkBytes(b []byte, n int) [][]byte {
	var out [][]byte
	for len(b) > n {
		out = append(out, b[:n])
		b = b[n:]
	}
	if len(b) > 0 {
		out = append(out, b)
	}
	return out
}

func synthErr(reqid, code string) *wire.RespEnvelope {
	return &wire.RespEnvelope{ReqID: reqid, Status: http.StatusBadGateway, Err: "tunnel error: " + code, ErrCode: code}
}
