package ingress

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
	"github.com/redis/go-redis/v9"
)

func genCA(t *testing.T) *ca.CA {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test-ca"},
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
	caObj, err := ca.Load(cp, kp, 10*365*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return caObj
}

func csrPEM(t *testing.T, key crypto.Signer) []byte {
	t.Helper()
	der, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{Subject: pkix.Name{CommonName: "ignored"}}, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der})
}

type eh struct {
	t   *testing.T
	rec *tunneltest.Recorder
	ban *ban.Engine
	h   *EnrollHandler
}

func newEnroll(t *testing.T, tweak func(*config.ServeCmd)) *eh {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	cfg := config.ServeCmd{
		ClientIPHeader:      "X-Real-Ip",
		TunnelDomain:        "example.test",
		NameLength:          10,
		CertValidity:        87600 * time.Hour,
		LimitEnrollHour:     20,
		LimitEnrollMinute:   2,
		LimitEnrollBody:     "16kb",
		LimitRequestTimeout: 60 * time.Second,
	}
	if tweak != nil {
		tweak(&cfg)
	}
	banEng := ban.NewEngine()
	rec := &tunneltest.Recorder{}
	h, err := NewEnrollHandler(cfg, genCA(t), rdb, banEng, rec, discardLog())
	if err != nil {
		t.Fatal(err)
	}
	return &eh{t: t, rec: rec, ban: banEng, h: h}
}

func (x *eh) post(body []byte, ip string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "http://enroll.example.test/enroll", bytesReader(body))
	if ip != "" {
		r.Header.Set("X-Real-Ip", ip)
	}
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	return rr
}

func (x *eh) loadBans(content string) {
	dir := x.t.TempDir()
	f := filepath.Join(dir, "bans.txt")
	_ = os.WriteFile(f, []byte(content), 0o644)
	if err := x.ban.Load([]string{f}, "", discardLog()); err != nil {
		x.t.Fatal(err)
	}
}

func TestEnrollSignsValidCSR(t *testing.T) {
	x := newEnroll(t, nil)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	rr := x.post(csrPEM(t, key), "203.0.113.7")
	if rr.Code != 200 {
		t.Fatalf("status %d body %s", rr.Code, rr.Body.String())
	}
	var resp enrollResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode([]byte(resp.CertificatePEM))
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	if cert.Subject.CommonName != resp.Name {
		t.Errorf("cert CN %q != returned name %q", cert.Subject.CommonName, resp.Name)
	}
}

func TestEnrollResponseFieldsComplete(t *testing.T) {
	x := newEnroll(t, nil)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	rr := x.post(csrPEM(t, key), "203.0.113.7")
	var resp enrollResponse
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if resp.Name == "" || resp.CertificatePEM == "" {
		t.Fatal("missing name/cert")
	}
	if resp.Hostname != resp.Name+".example.test" {
		t.Errorf("hostname = %q", resp.Hostname)
	}
	if resp.ConnectURL != "wss://"+resp.Name+".example.test/connect" {
		t.Errorf("connect_url = %q", resp.ConnectURL)
	}
	if resp.ExpiresAt < time.Now().Add(87599*time.Hour).Unix() {
		t.Errorf("expires_at too small: %d", resp.ExpiresAt)
	}
}

func TestEnrollRejectsMalformedCSR(t *testing.T) {
	x := newEnroll(t, nil)
	rr := x.post([]byte("not a csr"), "203.0.113.7")
	if rr.Code != 400 || x.rec.Count("reject", "enroll_malformed_csr") != 1 {
		t.Errorf("malformed csr = %d", rr.Code)
	}
}

func TestEnrollRejectsNonP256KeyCSR(t *testing.T) {
	x := newEnroll(t, nil)
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	rr := x.post(csrPEM(t, rsaKey), "203.0.113.7")
	if rr.Code != 400 {
		t.Fatalf("status %d", rr.Code)
	}
	var body map[string]string
	_ = json.Unmarshal(rr.Body.Bytes(), &body)
	if body["error"] != "unsupported_key_type" {
		t.Errorf("error = %q, want unsupported_key_type", body["error"])
	}
	if x.rec.Count("reject", "enroll_unsupported_key") != 1 {
		t.Error("enroll_unsupported_key not recorded")
	}
}

func TestEnrollBannedIP403(t *testing.T) {
	x := newEnroll(t, nil)
	x.loadBans("ip 203.0.113.7\n")
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	rr := x.post(csrPEM(t, key), "203.0.113.7")
	if rr.Code != 403 {
		t.Errorf("banned ip = %d, want 403", rr.Code)
	}
}

func TestEnrollQuota429(t *testing.T) {
	x := newEnroll(t, nil) // perMinute=2
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	for i := 0; i < 2; i++ {
		if rr := x.post(csrPEM(t, key), "203.0.113.7"); rr.Code != 200 {
			t.Fatalf("enroll %d = %d", i, rr.Code)
		}
	}
	rr := x.post(csrPEM(t, key), "203.0.113.7")
	if rr.Code != http.StatusTooManyRequests {
		t.Fatalf("3rd = %d, want 429", rr.Code)
	}
	if rr.Header().Get("Retry-After") == "" {
		t.Error("Retry-After missing")
	}
	var body map[string]any
	_ = json.Unmarshal(rr.Body.Bytes(), &body)
	if body["message"] == nil {
		t.Error("clear JSON message missing")
	}
}

func TestEnrollBodyCap(t *testing.T) {
	x := newEnroll(t, func(c *config.ServeCmd) { c.LimitEnrollBody = "1kb" })
	rr := x.post(repeatBytes('A', 4096), "203.0.113.7")
	if rr.Code != http.StatusRequestEntityTooLarge || x.rec.Count("reject", "enroll_body_too_large") != 1 {
		t.Errorf("body cap = %d", rr.Code)
	}
}

func TestEnrollUsesTrustedClientIP(t *testing.T) {
	x := newEnroll(t, func(c *config.ServeCmd) { c.ClientIPHeader = "X-Forwarded-For" })
	x.loadBans("ip 1.2.3.4\n") // spoofed left-most
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	r := httptest.NewRequest("POST", "http://enroll.example.test/enroll", bytesReader(csrPEM(t, key)))
	r.Header.Set("X-Forwarded-For", "1.2.3.4, 9.9.9.9")
	rr := httptest.NewRecorder()
	x.h.ServeHTTP(rr, r)
	if rr.Code == 403 {
		t.Error("must key on the right-most XFF entry, not the spoofed left one")
	}
}

func TestEnrollMissingClientIP400(t *testing.T) {
	x := newEnroll(t, nil)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	rr := x.post(csrPEM(t, key), "")
	if rr.Code != 400 || x.rec.Count("reject", "missing_client_ip") != 1 {
		t.Errorf("missing client ip = %d", rr.Code)
	}
}
