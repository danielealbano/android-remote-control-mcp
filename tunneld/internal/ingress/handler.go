package ingress

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/clientip"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/router"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/transport"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
	"github.com/redis/go-redis/v9"
)

// Handler is the public ingress pipeline.
type Handler struct {
	ban     *ban.Engine
	router  *router.Registry
	buckets *limit.BucketRegistry
	rdb     redis.UniversalClient
	nodeID  string
	cfg     config.ServeCmd
	rec     observ.Recorder
	log     *slog.Logger

	bodyLimit         int64
	headersLimit      int64
	headerSingleLimit int64
}

// NewHandler parses the size caps and returns the ingress handler.
func NewHandler(cfg config.ServeCmd, nodeID string, rdb redis.UniversalClient, banEng *ban.Engine,
	reg *router.Registry, buckets *limit.BucketRegistry, rec observ.Recorder, log *slog.Logger) (*Handler, error) {
	body, err := config.ParseByteSize(cfg.LimitBody)
	if err != nil {
		return nil, err
	}
	headers, err := config.ParseByteSize(cfg.LimitHeaders)
	if err != nil {
		return nil, err
	}
	single, err := config.ParseByteSize(cfg.LimitHeaderSingle)
	if err != nil {
		return nil, err
	}
	return &Handler{
		ban: banEng, router: reg, buckets: buckets, rdb: rdb, nodeID: nodeID, cfg: cfg, rec: rec, log: log,
		bodyLimit: body, headersLimit: headers, headerSingleLimit: single,
	}, nil
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()

	// 1. Trusted source IP, then ban check FIRST.
	ip, ok := clientip.TrustedIP(r, h.cfg.ClientIPHeader)
	if !ok {
		h.rec.Reject("missing_client_ip", "", "")
		http.Error(w, "missing client ip", http.StatusBadRequest)
		return
	}
	ipStr := ip.String()
	if src, banned := h.ban.Match(ip); banned {
		h.rec.Reject(src.Reason.String(), "", ipStr)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}

	// 2. Reject mTLS-indicating headers; capture sanitized headers for forwarding.
	sanitized, rejected := Sanitize(r.Header)
	if rejected {
		h.rec.Reject("public_mtls_header", "", ipStr)
		http.Error(w, "client certificates not supported", http.StatusBadRequest)
		return
	}

	// 3. Host → name; resolve route; then tunnel-name/fingerprint ban.
	name := firstLabel(r.Host)
	node, fp, found, err := h.router.Lookup(r.Context(), name)
	if err != nil {
		h.log.Warn("route lookup failed", "name", name, "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !found {
		h.rec.Reject("unknown_host", "", ipStr)
		http.Error(w, "no such tunnel", http.StatusNotFound)
		return
	}
	if src, banned := h.ban.MatchTunnel(name, fp); banned {
		h.rec.Reject(src.Reason.String(), name, ipStr)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}

	// 4. Allowlist.
	route, decision := Match(r.Method, r.URL.Path)
	switch decision {
	case Edge405:
		h.rec.Reject("method_denied", name, ipStr)
		w.Header().Set("Allow", "POST, DELETE")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	case Deny404:
		h.rec.Reject("path_denied", name, ipStr)
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	// 5. NO auth check — deliberate (the app is the sole authenticator; its 401 + RFC 9728 header
	//    must reach OAuth clients).

	// 6. Size caps.
	if reason, tooBig := h.headersTooBig(r.Header); tooBig {
		h.rec.Reject("headers_too_large", name, ipStr)
		h.log.Debug("headers too large", "reason", reason)
		http.Error(w, "request header fields too large", http.StatusRequestHeaderFieldsTooLarge)
		return
	}
	if r.ContentLength > h.bodyLimit {
		h.rec.Reject("body_too_large", name, ipStr)
		http.Error(w, "payload too large", http.StatusRequestEntityTooLarge)
		return
	}

	// 7. Per-IP rps/rpm then per-tunnel concurrency.
	if allowed, retry, err := limit.Allow(r.Context(), h.rdb, "rps", ip, h.cfg.LimitRPS, time.Second); err != nil {
		h.serverError(w)
		return
	} else if !allowed {
		h.rateLimited(w, name, ipStr, "rate_rps", retry)
		return
	}
	if allowed, retry, err := limit.Allow(r.Context(), h.rdb, "rpm", ip, h.cfg.LimitRPM, time.Minute); err != nil {
		h.serverError(w)
		return
	} else if !allowed {
		h.rateLimited(w, name, ipStr, "rate_rpm", retry)
		return
	}
	release, acquired, err := limit.Acquire(r.Context(), h.rdb, name, h.cfg.LimitConcurrent, 2*h.cfg.LimitRequestTimeout)
	if err != nil {
		h.serverError(w)
		return
	}
	if !acquired {
		h.rec.Reject("concurrency", name, ipStr)
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	defer release()

	// 8. End-to-end deadline over the paced body read + RoundTrip.
	h.rec.InflightAdd(1)
	defer h.rec.InflightAdd(-1)

	reqCtx, cancel := context.WithTimeout(r.Context(), h.cfg.LimitRequestTimeout)
	defer cancel()

	// Best-effort socket read deadline (unsupported by httptest — the goroutine+ctx path below is the
	// portable enforcement).
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(h.cfg.LimitRequestTimeout))

	up, _ := h.buckets.Pair(name)
	limited := http.MaxBytesReader(w, r.Body, h.bodyLimit)
	bodyCh := make(chan bodyResult, 1)
	go func() {
		data, rerr := readPacedBody(reqCtx, limited, up)
		bodyCh <- bodyResult{data, rerr}
	}()

	var body []byte
	select {
	case <-reqCtx.Done():
		_ = r.Body.Close()
		h.rec.Reject("body_read_timeout", name, ipStr)
		h.writeStatus(w, http.StatusRequestTimeout)
		return
	case res := <-bodyCh:
		if res.err != nil {
			var mbe *http.MaxBytesError
			switch {
			case errors.As(res.err, &mbe):
				h.rec.Reject("body_too_large", name, ipStr)
				h.writeStatus(w, http.StatusRequestEntityTooLarge)
			case errors.Is(res.err, context.DeadlineExceeded), errors.Is(res.err, context.Canceled):
				h.rec.Reject("body_read_timeout", name, ipStr)
				h.writeStatus(w, http.StatusRequestTimeout)
			default:
				h.writeStatus(w, http.StatusBadRequest)
			}
			return
		}
		body = res.data
	}

	env := &wire.ReqEnvelope{
		ReqID:          wire.NewReqID(),
		Node:           node,
		TunnelName:     name,
		Method:         r.Method,
		Path:           r.URL.Path,
		RawQuery:       r.URL.RawQuery,
		Host:           r.Host,
		Header:         sanitized,
		Body:           body,
		ClientIP:       ipStr,
		ForwardedProto: r.Header.Get("X-Forwarded-Proto"),
		PacedByNode:    h.nodeID, // this node's up-bucket already paced the body read (US6.2 guard)
	}

	resp, err := transport.RoundTrip(reqCtx, h.rdb, node, env, h.cfg.LimitRequestTimeout)
	if err != nil {
		if errors.Is(err, transport.ErrTimeout) {
			h.rec.Timeout()
			h.rec.Reject("timeout", name, ipStr)
			h.writeStatus(w, http.StatusGatewayTimeout)
			return
		}
		h.rec.PublishError()
		h.writeStatus(w, http.StatusBadGateway)
		return
	}
	if resp.ErrCode == "tunnel_gone" {
		h.rec.Reject("tunnel_offline", name, ipStr)
		h.writeStatus(w, http.StatusBadGateway)
		return
	}
	// 9. Write status + sanitized headers + body (real response, or a node-recorded synthetic like
	//    response_too_large returned as-is).
	writeResp(w, resp)
	h.rec.Request(name, route.Class, resp.Status, time.Since(start))
}

type bodyResult struct {
	data []byte
	err  error
}

// readPacedBody reads body in ≤ChunkSize slices, pacing each read against the up-bucket (TCP
// backpressure). A ctx-cancel from WaitN surfaces as the read error (mapped to 408 by the caller).
func readPacedBody(ctx context.Context, body io.Reader, bucket *limit.TokenBucket) ([]byte, error) {
	var buf bytes.Buffer
	tmp := make([]byte, wire.ChunkSize)
	for {
		n, err := body.Read(tmp)
		if n > 0 {
			if werr := bucket.WaitN(ctx, n); werr != nil {
				return nil, werr
			}
			buf.Write(tmp[:n])
		}
		if errors.Is(err, io.EOF) {
			return buf.Bytes(), nil
		}
		if err != nil {
			return nil, err
		}
	}
}

func (h *Handler) headersTooBig(hdr http.Header) (string, bool) {
	var total int64
	for k, vs := range hdr {
		for _, v := range vs {
			line := int64(len(k) + len(v))
			if line > h.headerSingleLimit {
				return "single", true
			}
			total += line
		}
	}
	if total > h.headersLimit {
		return "total", true
	}
	return "", false
}

func (h *Handler) rateLimited(w http.ResponseWriter, name, ipStr, reason string, retry time.Duration) {
	h.rec.Reject(reason, name, ipStr)
	secs := int(retry.Seconds())
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	http.Error(w, "too many requests", http.StatusTooManyRequests)
}

func (h *Handler) serverError(w http.ResponseWriter) {
	http.Error(w, "internal error", http.StatusInternalServerError)
}

// writeStatus writes a synthetic status (no phone body). It does NOT record rec.Request: these
// paths never received a real phone response and are already recorded via rec.Reject/Timeout/
// PublishError (US7 step 8/9). rec.Request is reserved for a forwarded request that produced a real
// response (the writeResp path), so tunneld_http_requests_total and the per-tunnel tcnt counter are
// not inflated by requests that never reached the phone.
func (h *Handler) writeStatus(w http.ResponseWriter, code int) {
	http.Error(w, http.StatusText(code), code)
}

func writeResp(w http.ResponseWriter, resp *wire.RespEnvelope) {
	for k, vs := range SanitizeResponse(resp.Header) {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.Status)
	_, _ = w.Write(resp.Body) // #nosec G705 -- transparent tunnel passthrough of the phone's response bytes to an MCP JSON client (not an HTML browser); nothing to sanitize
}

// firstLabel returns the first DNS label of a Host header (stripping any port).
func firstLabel(host string) string {
	if i := strings.IndexByte(host, ':'); i >= 0 {
		host = host[:i]
	}
	host = strings.TrimSuffix(host, ".")
	if i := strings.IndexByte(host, '.'); i >= 0 {
		return host[:i]
	}
	return host
}
