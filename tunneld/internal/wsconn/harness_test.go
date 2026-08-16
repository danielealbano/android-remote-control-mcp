package wsconn

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/coder/websocket"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/router"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
	"github.com/redis/go-redis/v9"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

type harness struct {
	t        *testing.T
	mr       *miniredis.Miniredis
	rdb      *redis.Client
	caObj    *ca.CA
	reg      *router.Registry
	ban      *ban.Engine
	buckets  *limit.BucketRegistry
	rec      *tunneltest.Recorder
	mgr      *Manager
	srv      *httptest.Server
	cfg      config.ServeCmd
	clientIP string
	ctx      context.Context
}

func newHarness(t *testing.T, bps int64, tweak func(*config.ServeCmd)) *harness {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	caObj := genCA(t)
	cfg := config.ServeCmd{
		TunnelDomain:        "example.test",
		ClientIPHeader:      "X-Real-Ip",
		RouteTTL:            30 * time.Second,
		ConnectAuthTimeout:  time.Second,
		PingInterval:        500 * time.Millisecond,
		LimitRPM:            100,
		LimitConnectPending: 64,
		LimitConcurrent:     4,
		LimitResponse:       "10mb",
		NameLength:          10,
	}
	if tweak != nil {
		tweak(&cfg)
	}
	if bps == 0 {
		bps = 100 * 1024 * 1024
	}
	reg := router.NewRegistry(rdb, cfg.RouteTTL)
	banEng := ban.NewEngine()
	buckets := limit.NewBucketRegistry(bps)
	rec := &tunneltest.Recorder{}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	mgr, err := NewManager(ctx, cfg, "nodeA", rdb, reg, banEng, caObj, buckets, rec, discardLog())
	if err != nil {
		t.Fatal(err)
	}
	h := &harness{t: t, mr: mr, rdb: rdb, caObj: caObj, reg: reg, ban: banEng, buckets: buckets, rec: rec, mgr: mgr, cfg: cfg, clientIP: "203.0.113.50", ctx: ctx}
	h.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if h.clientIP != "" {
			r.Header.Set("X-Real-Ip", h.clientIP)
		}
		mgr.HandleConnect(w, r)
	}))
	t.Cleanup(h.srv.Close)
	return h
}

func (h *harness) wsURL() string { return "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/connect" }

func (h *harness) host(name string) string { return name + ".example.test" }

func genCA(t *testing.T) *ca.CA {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	cp := filepath.Join(dir, "ca.pem")
	kp := filepath.Join(dir, "ca-key.pem")
	keyDER, _ := x509.MarshalECPrivateKey(key)
	_ = os.WriteFile(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	_ = os.WriteFile(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600)
	caObj, err := ca.Load(cp, kp, 10*365*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return caObj
}

func (h *harness) issue(name string) (*x509.Certificate, *ecdsa.PrivateKey) {
	h.t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		h.t.Fatal(err)
	}
	csr, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{}, key)
	if err != nil {
		h.t.Fatal(err)
	}
	leafPEM, err := h.caObj.SignCSR(csr, name)
	if err != nil {
		h.t.Fatal(err)
	}
	block, _ := pem.Decode(leafPEM)
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		h.t.Fatal(err)
	}
	return cert, key
}

func (h *harness) loadBans(content string) {
	h.t.Helper()
	dir := h.t.TempDir()
	f := filepath.Join(dir, "bans.txt")
	if err := os.WriteFile(f, []byte(content), 0o644); err != nil {
		h.t.Fatal(err)
	}
	if err := h.ban.Load([]string{f}, "", discardLog()); err != nil {
		h.t.Fatal(err)
	}
}

func (h *harness) waitBound(name string) {
	h.t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, _, ok, _ := h.reg.Lookup(context.Background(), name); ok {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	h.t.Fatalf("tunnel %q never bound", name)
}

func (h *harness) connectPhone(name string, handler http.Handler) *tunneltest.FakePhone {
	h.t.Helper()
	cert, key := h.issue(name)
	p, err := tunneltest.Dial(context.Background(), h.wsURL(), h.host(name), cert, key, handler)
	if err != nil {
		h.t.Fatal(err)
	}
	h.waitBound(name)
	return p
}

// okHandler returns 200 with the given body.
func okHandler(body string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, body)
	})
}

// --- raw dialer (for auth-fault + custom-frame tests) ---

func rawDial(t *testing.T, url, host string) (*websocket.Conn, []byte) {
	t.Helper()
	ctx := context.Background()
	ws, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{Host: host})
	if err != nil {
		t.Fatal(err)
	}
	_, data, err := ws.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	typ, hdr, _, err := wire.DecodeFrame(data)
	if err != nil || typ != wire.CHALLENGE {
		t.Fatalf("expected CHALLENGE, got type=%d err=%v", typ, err)
	}
	var ch struct {
		Nonce []byte `json:"nonce"`
	}
	_ = json.Unmarshal(hdr, &ch)
	return ws, ch.Nonce
}

func signNonce(key *ecdsa.PrivateKey, nonce []byte) string {
	digest := sha256.Sum256(append([]byte(ca.ConnectAuthContext), nonce...))
	sig, _ := ecdsa.SignASN1(rand.Reader, key, digest[:])
	return base64.StdEncoding.EncodeToString(sig)
}

func sendAuth(t *testing.T, ws *websocket.Conn, certB64, sigB64 string) {
	t.Helper()
	auth, _ := json.Marshal(map[string]string{"cert": certB64, "signature": sigB64})
	if err := ws.Write(context.Background(), websocket.MessageBinary, wire.EncodeFrame(wire.AUTH, auth, nil)); err != nil {
		t.Fatal(err)
	}
}

// rawPhone is an authenticated raw connection the test drives frame-by-frame (ERROR frames,
// zero-length chunks, etc.).
type rawPhone struct {
	t  *testing.T
	ws *websocket.Conn
}

func (h *harness) rawPhoneConnect(name string) *rawPhone {
	h.t.Helper()
	cert, key := h.issue(name)
	ws, nonce := rawDial(h.t, h.wsURL(), h.host(name))
	sendAuth(h.t, ws, base64.StdEncoding.EncodeToString(cert.Raw), signNonce(key, nonce))
	h.waitBound(name)
	return &rawPhone{t: h.t, ws: ws}
}

func (p *rawPhone) read() (wire.FrameType, []byte, []byte) {
	p.t.Helper()
	_, data, err := p.ws.Read(context.Background())
	if err != nil {
		p.t.Fatalf("rawPhone read: %v", err)
	}
	typ, hdr, body, err := wire.DecodeFrame(data)
	if err != nil {
		p.t.Fatal(err)
	}
	return typ, hdr, body
}

func (p *rawPhone) write(typ wire.FrameType, hdr, body []byte) {
	p.t.Helper()
	if err := p.ws.Write(context.Background(), websocket.MessageBinary, wire.EncodeFrame(typ, hdr, body)); err != nil {
		p.t.Fatal(err)
	}
}

// drainRequest reads REQUEST_HEAD + chunks until REQUEST_END, returning the reqid and accumulated body.
func (p *rawPhone) drainRequest() (reqid string, body []byte) {
	p.t.Helper()
	for {
		typ, hdr, b := p.read()
		switch typ {
		case wire.REQUEST_HEAD:
			reqid = wire.FrameReqID(hdr)
		case wire.REQUEST_BODY_CHUNK:
			body = append(body, b...)
		case wire.REQUEST_END:
			return reqid, body
		}
	}
}
