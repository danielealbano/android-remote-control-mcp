package client

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"io"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/server"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func freePort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := l.Addr().String()
	_ = l.Close()
	return a
}

func genCAFiles(t *testing.T) (string, string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign, BasicConstraintsValid: true, IsCA: true, MaxPathLenZero: true,
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	dir := t.TempDir()
	cp := filepath.Join(dir, "ca.pem")
	kp := filepath.Join(dir, "ca-key.pem")
	keyDER, _ := x509.MarshalECPrivateKey(key)
	_ = os.WriteFile(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	_ = os.WriteFile(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600)
	return cp, kp
}

func cfgFor(listen, internal, redisAddr, cp, kp string) config.ServeCmd {
	return config.ServeCmd{
		Listen: listen, InternalListen: internal,
		TunnelDomain: "example.test", EnrollHost: "enroll.example.test", NameLength: 10,
		RedisURL: "redis://" + redisAddr, CACert: cp, CAKey: kp, CertValidity: 87600 * time.Hour,
		ConnectAuthTimeout: 2 * time.Second, ClientIPHeader: "X-Real-Ip", RouteTTL: 30 * time.Second, BanPoll: 10 * time.Second,
		LimitBandwidth: "1mbit", LimitRPS: 100, LimitRPM: 1000, LimitConcurrent: 4, LimitConnectPending: 64,
		LimitBody: "1mb", LimitResponse: "10mb", LimitHeaders: "16kb", LimitHeaderSingle: "8kb",
		LimitRequestTimeout: 10 * time.Second, LimitEnrollHour: 100, LimitEnrollMinute: 100, LimitEnrollBody: "16kb",
		PingInterval: 30 * time.Second, ShutdownGrace: 5 * time.Second,
	}
}

func runServer(t *testing.T, cfg config.ServeCmd) (cancel context.CancelFunc, done chan error) {
	ctx, cancel := context.WithCancel(context.Background())
	done = make(chan error, 1)
	go func() { done <- server.Run(ctx, cfg, discardLog(), "test") }()
	waitHealthy(t, cfg.InternalListen)
	return cancel, done
}

func waitHealthy(t *testing.T, internal string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get("http://" + internal + "/healthz")
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == 200 {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("server not healthy")
}

func testClient() *Client {
	return &Client{Headers: http.Header{"X-Real-Ip": {"203.0.113.7"}}, EnrollHost: "enroll.example.test"}
}

// postMCP sends a public POST /mcp, retrying to absorb the ServeNode readiness window.
func postMCP(t *testing.T, publicAddr, name string) (int, string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var code int
	var body string
	for time.Now().Before(deadline) {
		req, _ := http.NewRequest("POST", "http://"+publicAddr+"/mcp", nil)
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
			return code, body
		}
		time.Sleep(50 * time.Millisecond)
	}
	return code, body
}

func TestClientEnrollReturnsUsableCert(t *testing.T) {
	mr := miniredis.RunT(t)
	cp, kp := genCAFiles(t)
	cfg := cfgFor(freePort(t), freePort(t), mr.Addr(), cp, kp)
	cancel, done := runServer(t, cfg)
	defer func() { cancel(); <-done }()

	cl := testClient()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	certPEM, name, err := cl.Enroll(context.Background(), "http://"+cfg.Listen+"/enroll", key)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(certPEM)
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	if cert.Subject.CommonName != name {
		t.Fatalf("CN %q != name %q", cert.Subject.CommonName, name)
	}

	sctx, scancel := context.WithCancel(context.Background())
	defer scancel()
	go func() { _ = cl.Serve(sctx, "ws://"+cfg.Listen+"/connect", name+".example.test", cert, key, ok200("pong")) }()

	code, body := postMCP(t, cfg.Listen, name)
	if code != 200 || body != "pong" {
		t.Fatalf("request = %d %q", code, body)
	}
}

func TestClientBridgesRequestToBackend(t *testing.T) {
	mr := miniredis.RunT(t)
	cp, kp := genCAFiles(t)
	cfg := cfgFor(freePort(t), freePort(t), mr.Addr(), cp, kp)
	cancel, done := runServer(t, cfg)
	defer func() { cancel(); <-done }()

	cl := testClient()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	certPEM, name, _ := cl.Enroll(context.Background(), "http://"+cfg.Listen+"/enroll", key)
	block, _ := pem.Decode(certPEM)
	cert, _ := x509.ParseCertificate(block.Bytes)

	var gotPath atomic.Value
	backend := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath.Store(r.URL.Path)
		_, _ = io.WriteString(w, "backend-ok")
	})
	sctx, scancel := context.WithCancel(context.Background())
	defer scancel()
	go func() { _ = cl.Serve(sctx, "ws://"+cfg.Listen+"/connect", name+".example.test", cert, key, backend) }()

	code, body := postMCP(t, cfg.Listen, name)
	if code != 200 || body != "backend-ok" {
		t.Fatalf("request = %d %q", code, body)
	}
	if p, _ := gotPath.Load().(string); p != "/mcp" {
		t.Errorf("backend saw path %q, want /mcp", p)
	}
}

func TestClientReconnectsAfterDrop(t *testing.T) {
	mr := miniredis.RunT(t)
	cp, kp := genCAFiles(t)
	publicAddr := freePort(t)
	cfg1 := cfgFor(publicAddr, freePort(t), mr.Addr(), cp, kp)
	cancel1, done1 := runServer(t, cfg1)

	cl := testClient()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	certPEM, name, err := cl.Enroll(context.Background(), "http://"+publicAddr+"/enroll", key)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(certPEM)
	cert, _ := x509.ParseCertificate(block.Bytes)

	sctx, scancel := context.WithCancel(context.Background())
	defer scancel()
	go func() { _ = cl.Serve(sctx, "ws://"+publicAddr+"/connect", name+".example.test", cert, key, ok200("v")) }()

	if code, _ := postMCP(t, publicAddr, name); code != 200 {
		t.Fatalf("request before drop = %d", code)
	}

	// Drop: stop server1 (same port freed), then start server2 on the SAME port + SAME redis + CA.
	cancel1()
	<-done1
	cfg2 := cfgFor(publicAddr, freePort(t), mr.Addr(), cp, kp)
	cancel2, done2 := runServer(t, cfg2)
	defer func() { cancel2(); <-done2 }()

	// The client's Serve loop must re-dial and re-bind on server2.
	if code, body := postMCP(t, publicAddr, name); code != 200 || body != "v" {
		t.Fatalf("request after reconnect = %d %q", code, body)
	}
}

func ok200(body string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, body) })
}
