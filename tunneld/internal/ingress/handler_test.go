package ingress

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/router"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
	"github.com/redis/go-redis/v9"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

const iName = "abcname2345"

type ih struct {
	t       *testing.T
	mr      *miniredis.Miniredis
	rdb     *redis.Client
	reg     *router.Registry
	ban     *ban.Engine
	buckets *limit.BucketRegistry
	rec     *tunneltest.Recorder
	h       *Handler
	node    string

	mu      sync.Mutex
	lastReq *wire.ReqEnvelope
}

func newIngress(t *testing.T, bps int64, tweak func(*config.ServeCmd)) *ih {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	cfg := config.ServeCmd{
		ClientIPHeader:      "X-Real-Ip",
		LimitRPS:            10,
		LimitRPM:            100,
		LimitConcurrent:     4,
		LimitBody:           "1mb",
		LimitResponse:       "10mb",
		LimitHeaders:        "16kb",
		LimitHeaderSingle:   "8kb",
		LimitRequestTimeout: 60 * time.Second,
	}
	if tweak != nil {
		tweak(&cfg)
	}
	if bps == 0 {
		bps = 100 * 1024 * 1024
	}
	reg := router.NewRegistry(rdb, 30*time.Second)
	banEng := ban.NewEngine()
	buckets := limit.NewBucketRegistry(bps)
	rec := &tunneltest.Recorder{}
	h, err := NewHandler(cfg, "nodeA", rdb, banEng, reg, buckets, rec, discardLog())
	if err != nil {
		t.Fatal(err)
	}
	return &ih{t: t, mr: mr, rdb: rdb, reg: reg, ban: banEng, buckets: buckets, rec: rec, h: h, node: "nodeA"}
}

func (x *ih) bind(name string) {
	if err := x.reg.Bind(context.Background(), name, x.node, "sha256:fp", "conn1"); err != nil {
		x.t.Fatal(err)
	}
}

func (x *ih) loadBans(content string) {
	dir := x.t.TempDir()
	f := dir + "/bans.txt"
	if err := writeFile(f, content); err != nil {
		x.t.Fatal(err)
	}
	if err := x.ban.Load([]string{f}, "", discardLog()); err != nil {
		x.t.Fatal(err)
	}
}

// startEcho subscribes to req:{node} (confirmed) and replies with a canned response, capturing the
// forwarded envelope for inspection.
func (x *ih) startEcho(status int, body string, respHdr http.Header) {
	x.t.Helper()
	ctx := context.Background()
	pubsub := x.rdb.Subscribe(ctx, "req:"+x.node)
	if _, err := pubsub.Receive(ctx); err != nil {
		x.t.Fatal(err)
	}
	x.t.Cleanup(func() { _ = pubsub.Close() })
	go func() {
		for msg := range pubsub.Channel() {
			req, err := wire.UnmarshalReq([]byte(msg.Payload))
			if err != nil {
				continue
			}
			x.mu.Lock()
			x.lastReq = req
			x.mu.Unlock()
			resp := &wire.RespEnvelope{ReqID: req.ReqID, Status: status, Header: respHdr, Body: []byte(body)}
			_ = x.rdb.Publish(ctx, "resp:"+req.ReqID, wire.MarshalResp(resp)).Err()
		}
	}()
}

func (x *ih) forwarded() *wire.ReqEnvelope {
	x.mu.Lock()
	defer x.mu.Unlock()
	return x.lastReq
}

type reqOpt func(*http.Request)

func (x *ih) do(method, host, path string, body []byte, opts ...reqOpt) *httptest.ResponseRecorder {
	x.t.Helper()
	var r *http.Request
	if body == nil {
		r = httptest.NewRequest(method, "http://"+host+path, nil)
	} else {
		r = httptest.NewRequest(method, "http://"+host+path, bytes.NewReader(body))
	}
	r.Header.Set("X-Real-Ip", "203.0.113.7")
	for _, o := range opts {
		o(r)
	}
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	return rr
}

func withHeader(k, v string) reqOpt { return func(r *http.Request) { r.Header.Set(k, v) } }
func withIP(ip string) reqOpt {
	return func(r *http.Request) {
		if ip == "" {
			r.Header.Del("X-Real-Ip")
		} else {
			r.Header.Set("X-Real-Ip", ip)
		}
	}
}

func host(name string) string { return name + ".example.test" }

func TestForwardsMcpPostWithAndWithoutAuth(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "app-response", nil)
	// With Authorization.
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`), withHeader("Authorization", "Bearer x"))
	if rr.Code != 200 || rr.Body.String() != "app-response" {
		t.Errorf("authed POST /mcp = %d %q", rr.Code, rr.Body.String())
	}
	// Token-less POST /mcp is forwarded unchanged (edge never inspects Authorization).
	rr2 := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr2.Code != 200 {
		t.Errorf("token-less POST /mcp = %d, want forwarded 200", rr2.Code)
	}
}

func TestGetMcpIs405AtEdge(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	rr := x.do("GET", host(iName), "/mcp", nil)
	if rr.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET /mcp = %d, want 405", rr.Code)
	}
	if rr.Header().Get("Allow") != "POST, DELETE" {
		t.Errorf("Allow header = %q", rr.Header().Get("Allow"))
	}
	if x.rec.Count("reject", "method_denied") != 1 {
		t.Error("method_denied not recorded")
	}
}

func TestOptionsForwarded(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(204, "", nil)
	rr := x.do("OPTIONS", host(iName), "/mcp", nil)
	if rr.Code != 204 {
		t.Errorf("OPTIONS /mcp = %d, want forwarded 204", rr.Code)
	}
}

func TestOAuthEndpointsNeedNoAuth(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	for _, p := range []struct{ m, path string }{
		{"POST", "/register"}, {"GET", "/authorize"}, {"POST", "/token"},
		{"GET", "/.well-known/oauth-protected-resource"},
	} {
		rr := x.do(p.m, host(iName), p.path, nil)
		if rr.Code != 200 {
			t.Errorf("%s %s = %d, want forwarded", p.m, p.path, rr.Code)
		}
	}
}

func TestSharePathRegex(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "file", nil)
	good := "/s/" + repeat("a", 64)
	if rr := x.do("GET", host(iName), good, nil); rr.Code != 200 {
		t.Errorf("valid share path = %d", rr.Code)
	}
	if rr := x.do("GET", host(iName), "/s/"+repeat("a", 63), nil); rr.Code != 404 {
		t.Errorf("bad share path = %d, want 404", rr.Code)
	}
}

func TestNonAllowlisted404(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	rr := x.do("GET", host(iName), "/", nil)
	if rr.Code != 404 || x.rec.Count("reject", "path_denied") != 1 {
		t.Errorf("GET / = %d, path_denied=%d", rr.Code, x.rec.Count("reject", "path_denied"))
	}
}

func TestBannedTunnel403AtIngress(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.loadBans("tunnel-name " + iName + "\n")
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Code != 403 || x.rec.Count("reject", "banned_tunnel_name") != 1 {
		t.Errorf("banned tunnel = %d", rr.Code)
	}
}

func TestBannedIP403First(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.loadBans("ip 203.0.113.7\n")
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Code != 403 || x.rec.Count("reject", "banned_ip") != 1 {
		t.Errorf("banned ip = %d", rr.Code)
	}
}

func TestSpoofedXFFIgnoredForIPKey(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.ClientIPHeader = "X-Forwarded-For" })
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	x.loadBans("ip 1.2.3.4\n") // the SPOOFED left-most entry
	// Key must use the right-most (proxy) hop 9.9.9.9 → NOT banned → forwarded.
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`),
		func(r *http.Request) { r.Header.Del("X-Real-Ip"); r.Header.Set("X-Forwarded-For", "1.2.3.4, 9.9.9.9") })
	if rr.Code == 403 {
		t.Error("must key on the right-most XFF entry, not the spoofed left one")
	}
}

func TestMissingClientIP400(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`), withIP(""))
	if rr.Code != 400 || x.rec.Count("reject", "missing_client_ip") != 1 {
		t.Errorf("missing client ip = %d", rr.Code)
	}
}

func TestPublicClientCertHeader400(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`), withHeader("X-Forwarded-Tls-Client-Cert", "MIIB..."))
	if rr.Code != 400 || x.rec.Count("reject", "public_mtls_header") != 1 {
		t.Errorf("mtls header = %d", rr.Code)
	}
}

func TestForwardedHeadersSanitized(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	x.do("POST", host(iName), "/mcp", []byte(`{}`),
		withHeader("X-Forwarded-Proto", "https"),
		withHeader("Connection", "keep-alive"),
		withHeader("X-Forwarded-Server", "evil"))
	env := x.forwarded()
	if env == nil {
		t.Fatal("no forwarded request captured")
	}
	if env.Header.Get("X-Forwarded-Proto") != "https" {
		t.Error("proxy X-Forwarded-Proto must be forwarded")
	}
	if env.Header.Get("Connection") != "" {
		t.Error("hop-by-hop Connection must be stripped")
	}
	if env.Header.Get("X-Forwarded-Server") != "" {
		t.Error("client X-Forwarded-Server must be dropped")
	}
}

func TestResponseHopByHopStripped(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "ok", http.Header{"Connection": {"close"}, "Content-Type": {"text/plain"}})
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Header().Get("Connection") != "" {
		t.Error("response hop-by-hop Connection must be stripped")
	}
	if rr.Header().Get("Content-Type") != "text/plain" {
		t.Error("normal response header must pass through")
	}
}

func TestBodyOverCap413(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitBody = "1kb" })
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	rr := x.do("POST", host(iName), "/mcp", repeatBytes('A', 2048))
	if rr.Code != http.StatusRequestEntityTooLarge || x.rec.Count("reject", "body_too_large") != 1 {
		t.Errorf("body over cap = %d", rr.Code)
	}
}

func TestChunkedBodyOverCap413(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitBody = "1kb" })
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	r := httptest.NewRequest("POST", "http://"+host(iName)+"/mcp", bytes.NewReader(repeatBytes('B', 4096)))
	r.Header.Set("X-Real-Ip", "203.0.113.7")
	r.ContentLength = -1 // unknown length → MaxBytesReader bounds actual bytes
	r.Header.Del("Content-Length")
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("chunked body over cap = %d, want 413", rr.Code)
	}
}

func TestDeclaredOversizeContentLength413WithoutRead(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitBody = "1kb" })
	x.bind(iName)
	tracker := &readTracker{}
	r := httptest.NewRequest("POST", "http://"+host(iName)+"/mcp", tracker)
	r.Header.Set("X-Real-Ip", "203.0.113.7")
	r.ContentLength = 1 << 20 // declared 1MB > 1kb cap
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("declared oversize CL = %d, want 413", rr.Code)
	}
	if tracker.read {
		t.Error("body must NOT be read on a declared-oversize Content-Length")
	}
}

func TestTotalAndSingleHeaderOverCap431(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitHeaderSingle = "1kb"; c.LimitHeaders = "2kb" })
	x.bind(iName)
	// Single header over 1kb.
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`), withHeader("X-Big", repeat("z", 2000)))
	if rr.Code != http.StatusRequestHeaderFieldsTooLarge || x.rec.Count("reject", "headers_too_large") == 0 {
		t.Errorf("single header over cap = %d", rr.Code)
	}
}

func TestRPSRPM429WithRetryAfter(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitRPS = 2 })
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	var last *httptest.ResponseRecorder
	for i := 0; i < 3; i++ {
		last = x.do("POST", host(iName), "/mcp", []byte(`{}`))
	}
	if last.Code != http.StatusTooManyRequests {
		t.Errorf("3rd request = %d, want 429", last.Code)
	}
	if last.Header().Get("Retry-After") == "" {
		t.Error("Retry-After missing")
	}
	if x.rec.Count("reject", "rate_rps") < 1 {
		t.Error("rate_rps not recorded")
	}
}

func TestConcurrency429(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitConcurrent = 1 })
	x.bind(iName)
	// Occupy the only slot with a never-answered request.
	x.mr.Set("conc:"+iName, "1")
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Code != http.StatusTooManyRequests || x.rec.Count("reject", "concurrency") != 1 {
		t.Errorf("concurrency = %d", rr.Code)
	}
}

func TestTimeout504(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitRequestTimeout = 250 * time.Millisecond })
	x.bind(iName)
	// No responder → RoundTrip times out.
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Code != http.StatusGatewayTimeout {
		t.Errorf("timeout = %d, want 504", rr.Code)
	}
	if x.rec.Count("timeout", "") != 1 || x.rec.Count("reject", "timeout") != 1 {
		t.Errorf("timeout writers: timeout=%d reject=%d", x.rec.Count("timeout", ""), x.rec.Count("reject", "timeout"))
	}
}

func TestOffline502(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	// Responder returns a tunnel_gone envelope (WS dropped mid-round-trip).
	ctx := context.Background()
	pubsub := x.rdb.Subscribe(ctx, "req:"+x.node)
	_, _ = pubsub.Receive(ctx)
	t.Cleanup(func() { _ = pubsub.Close() })
	go func() {
		for msg := range pubsub.Channel() {
			req, _ := wire.UnmarshalReq([]byte(msg.Payload))
			resp := &wire.RespEnvelope{ReqID: req.ReqID, Status: 502, ErrCode: "tunnel_gone"}
			_ = x.rdb.Publish(ctx, "resp:"+req.ReqID, wire.MarshalResp(resp)).Err()
		}
	}()
	rr := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr.Code != http.StatusBadGateway || x.rec.Count("reject", "tunnel_offline") != 1 {
		t.Errorf("offline = %d", rr.Code)
	}
}

func TestUnknownHost404(t *testing.T) {
	x := newIngress(t, 0, nil)
	rr := x.do("POST", host("nobody999"), "/mcp", []byte(`{}`))
	if rr.Code != 404 || x.rec.Count("reject", "unknown_host") != 1 {
		t.Errorf("unknown host = %d", rr.Code)
	}
}

func TestInflightAddSubPaired(t *testing.T) {
	x := newIngress(t, 0, nil)
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if x.rec.Count("inflight", "") != 2 {
		t.Errorf("expected inflight +1/-1 (2 calls), got %d", x.rec.Count("inflight", ""))
	}
}

func TestSlowBodyRead408ReleasesSlot(t *testing.T) {
	x := newIngress(t, 0, func(c *config.ServeCmd) { c.LimitRequestTimeout = 200 * time.Millisecond })
	x.bind(iName)
	x.startEcho(200, "ok", nil)
	// A body that blocks forever until closed → the end-to-end deadline fires → 408.
	bb := &blockingBody{done: make(chan struct{})}
	r := httptest.NewRequest("POST", "http://"+host(iName)+"/mcp", bb)
	r.Header.Set("X-Real-Ip", "203.0.113.7")
	r.ContentLength = -1
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	if rr.Code != http.StatusRequestTimeout || x.rec.Count("reject", "body_read_timeout") != 1 {
		t.Errorf("slow body = %d", rr.Code)
	}
	// The concurrency slot must have been released — a follow-up request Acquires and forwards.
	rr2 := x.do("POST", host(iName), "/mcp", []byte(`{}`))
	if rr2.Code == http.StatusTooManyRequests {
		t.Error("concurrency slot was not released after 408")
	}
}

// --- helpers ---

func repeat(s string, n int) string { return string(repeatBytes(s[0], n)) }

func repeatBytes(b byte, n int) []byte {
	out := make([]byte, n)
	for i := range out {
		out[i] = b
	}
	return out
}

func writeFile(path, content string) error {
	return os.WriteFile(path, []byte(content), 0o644)
}

type readTracker struct{ read bool }

func (r *readTracker) Read(p []byte) (int, error) { r.read = true; return 0, io.EOF }
func (r *readTracker) Close() error               { return nil }

type blockingBody struct {
	done chan struct{}
	once sync.Once
}

func (b *blockingBody) Read(p []byte) (int, error) {
	<-b.done
	return 0, errors.New("body closed")
}

func (b *blockingBody) Close() error {
	b.once.Do(func() { close(b.done) })
	return nil
}
