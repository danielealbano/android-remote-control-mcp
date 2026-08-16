package server

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func bytesReaderT(b []byte) *bytes.Reader { return bytes.NewReader(b) }

func freePort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	return addr
}

func writeCA(t *testing.T) (certPath, keyPath string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test-ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign, BasicConstraintsValid: true, IsCA: true, MaxPathLenZero: true,
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	dir := t.TempDir()
	certPath = filepath.Join(dir, "ca.pem")
	keyPath = filepath.Join(dir, "ca-key.pem")
	keyDER, _ := x509.MarshalECPrivateKey(key)
	_ = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	_ = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600)
	return certPath, keyPath
}

type running struct {
	cfg      config.ServeCmd
	publicA  string
	internal string
	mr       *miniredis.Miniredis
	done     chan error
	cancel   context.CancelFunc
}

func startServer(t *testing.T) *running {
	t.Helper()
	mr := miniredis.RunT(t)
	certPath, keyPath := writeCA(t)
	cfg := config.ServeCmd{
		Listen:              freePort(t),
		InternalListen:      freePort(t),
		TunnelDomain:        "example.test",
		EnrollHost:          "enroll.example.test",
		NameLength:          10,
		RedisURL:            "redis://" + mr.Addr(),
		CACert:              certPath,
		CAKey:               keyPath,
		CertValidity:        87600 * time.Hour,
		ConnectAuthTimeout:  2 * time.Second,
		ClientIPHeader:      "X-Real-Ip",
		RouteTTL:            30 * time.Second,
		BanPoll:             10 * time.Second,
		LimitBandwidth:      "1mbit",
		LimitRPS:            100,
		LimitRPM:            1000,
		LimitConcurrent:     4,
		LimitConnectPending: 64,
		LimitBody:           "1mb",
		LimitResponse:       "10mb",
		LimitHeaders:        "16kb",
		LimitHeaderSingle:   "8kb",
		LimitRequestTimeout: 10 * time.Second,
		LimitEnrollHour:     100,
		LimitEnrollMinute:   100,
		LimitEnrollBody:     "16kb",
		PingInterval:        30 * time.Second,
		ShutdownGrace:       5 * time.Second,
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Run(ctx, cfg, discardLog(), "test") }()
	r := &running{cfg: cfg, publicA: cfg.Listen, internal: cfg.InternalListen, mr: mr, done: done, cancel: cancel}
	r.waitHealthy(t)
	return r
}

func (r *running) waitHealthy(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get("http://" + r.internal + "/healthz")
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == 200 {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("server never became healthy")
}

// stop cancels the server and waits for Run to return; returns (returned, runErr).
func (r *running) stop() (bool, error) {
	r.cancel()
	select {
	case err := <-r.done:
		return true, err
	case <-time.After(10 * time.Second):
		return false, nil
	}
}

// enroll performs an enrollment and returns the assigned name, cert, and key.
func (r *running) enroll(t *testing.T) (string, *x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	csrDER, _ := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{}, key)
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})

	req, _ := http.NewRequest("POST", "http://"+r.publicA+"/enroll", bytesReaderT(csrPEM))
	req.Host = r.cfg.EnrollHost
	req.Header.Set("X-Real-Ip", "203.0.113.7")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("enroll status %d: %s", resp.StatusCode, body)
	}
	var out struct {
		Name           string `json:"name"`
		CertificatePEM string `json:"certificate_pem"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	block, _ := pem.Decode([]byte(out.CertificatePEM))
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return out.Name, cert, key
}

func TestEnrollThenConnectThenRequest(t *testing.T) {
	r := startServer(t)
	defer func() { _, _ = r.stop() }()

	name, cert, key := r.enroll(t)
	phone, err := tunneltest.DialWithHeaders(context.Background(),
		"ws://"+r.publicA+"/connect", name+".example.test",
		http.Header{"X-Real-Ip": {"203.0.113.7"}}, cert, key,
		http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { _, _ = io.WriteString(w, "pong") }))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = phone.Close() }()

	// Wait until the route is bound.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if r.mr.Exists("route:" + name) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	// POST /mcp — retry to absorb the ServeNode subscription-readiness window.
	var body string
	var code int
	rd := time.Now().Add(5 * time.Second)
	for time.Now().Before(rd) {
		req, _ := http.NewRequest("POST", "http://"+r.publicA+"/mcp", bytesReaderT([]byte(`{}`)))
		req.Host = name + ".example.test"
		req.Header.Set("X-Real-Ip", "203.0.113.7")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			time.Sleep(50 * time.Millisecond)
			continue
		}
		b, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		code, body = resp.StatusCode, string(b)
		if code == 200 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if code != 200 || body != "pong" {
		t.Fatalf("POST /mcp = %d %q, want 200 pong", code, body)
	}
}

func TestRequestToUnboundTunnel404(t *testing.T) {
	r := startServer(t)
	defer func() { _, _ = r.stop() }()
	req, _ := http.NewRequest("POST", "http://"+r.publicA+"/mcp", bytesReaderT([]byte(`{}`)))
	req.Host = "neverbound99.example.test"
	req.Header.Set("X-Real-Ip", "203.0.113.7")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("unbound tunnel = %d, want 404", resp.StatusCode)
	}
}

func TestDistinctNodeIDPerProcess(t *testing.T) {
	a, b := newNodeID(), newNodeID()
	if a == b {
		t.Error("nodeIDs must be distinct per call (crypto/rand)")
	}
}

func TestShutdownUnbindsAllRoutes(t *testing.T) {
	r := startServer(t)
	name, cert, key := r.enroll(t)
	phone, err := tunneltest.DialWithHeaders(context.Background(),
		"ws://"+r.publicA+"/connect", name+".example.test",
		http.Header{"X-Real-Ip": {"203.0.113.7"}}, cert, key,
		http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {}))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = phone.Close() }()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if r.mr.Exists("route:" + name) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !r.mr.Exists("route:" + name) {
		t.Fatal("route never bound")
	}
	returned, err2 := r.stop()
	if !returned {
		t.Fatal("Run did not return after ctx cancel (graceful shutdown hung)")
	}
	if err2 != nil {
		t.Errorf("Run returned error: %v", err2)
	}
	if r.mr.Exists("route:" + name) {
		t.Error("route must be unbound after shutdown")
	}
}
