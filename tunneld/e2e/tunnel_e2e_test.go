//go:build e2e

package e2e

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/client"
)

const clientIP = "203.0.113.77"

func phoneBackend() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "phone:"+r.URL.Path)
	})
}

// enrollRaw enrolls via a raw HTTP POST (Host header set) and returns the cert, key, and name.
func enrollRaw(t *testing.T, url string) (*x509.Certificate, *ecdsa.PrivateKey, string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	csrDER, _ := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{}, key)
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})
	req, _ := http.NewRequest("POST", url+"/enroll", bytes.NewReader(csrPEM))
	req.Host = "enroll.example.test"
	req.Header.Set("X-Real-Ip", clientIP)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("enroll %d: %s", resp.StatusCode, b)
	}
	var out struct {
		Name           string `json:"name"`
		CertificatePEM string `json:"certificate_pem"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	block, _ := pem.Decode([]byte(out.CertificatePEM))
	cert, _ := x509.ParseCertificate(block.Bytes)
	return cert, key, out.Name
}

// req sends a public request directly to a replica (X-Real-Ip injected, Host set).
func req(t *testing.T, replicaURL, host, method, path string, body []byte, headers map[string]string) (*http.Response, string) {
	t.Helper()
	var r *http.Request
	if body != nil {
		r, _ = http.NewRequest(method, replicaURL+path, bytes.NewReader(body))
	} else {
		r, _ = http.NewRequest(method, replicaURL+path, nil)
	}
	r.Host = host
	r.Header.Set("X-Real-Ip", clientIP)
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	return resp, string(b)
}

// connectPhone starts a client connection on replicaIdx serving backend; returns a cancel func.
func connectPhone(t *testing.T, c *cluster, replicaIdx int, name string, cert *x509.Certificate, key *ecdsa.PrivateKey, backend http.Handler) context.CancelFunc {
	t.Helper()
	cl := &client.Client{Headers: http.Header{"X-Real-Ip": {clientIP}}}
	wsURL := "ws" + strings.TrimPrefix(c.replicaURL[replicaIdx], "http") + "/connect"
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = cl.Serve(ctx, wsURL, name+".example.test", cert, key, backend) }()
	// Wait until a public request reaches the phone (route bound + ServeNode ready).
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		resp, body := req(t, c.replicaURL[replicaIdx], name+".example.test", "POST", "/mcp", []byte(`{}`), nil)
		if resp.StatusCode == 200 && strings.HasPrefix(body, "phone:") {
			return cancel
		}
		time.Sleep(200 * time.Millisecond)
	}
	cancel()
	t.Fatal("phone never became reachable")
	return cancel
}

func TestEnrollAndServeMcpCrossNode(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	// Public request to the OTHER replica → cross-node routing via Redis.
	resp, body := req(t, c.replicaURL[1], name+".example.test", "POST", "/mcp", []byte(`{}`), nil)
	if resp.StatusCode != 200 || !strings.HasPrefix(body, "phone:") {
		t.Fatalf("cross-node request = %d %q", resp.StatusCode, body)
	}
}

func TestAllowlistDeniesNonMcpPath(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	if resp, _ := req(t, c.replicaURL[0], name+".example.test", "GET", "/", nil, nil); resp.StatusCode != 404 {
		t.Errorf("GET / = %d, want 404", resp.StatusCode)
	}
	if resp, _ := req(t, c.replicaURL[0], name+".example.test", "GET", "/mcp", nil, nil); resp.StatusCode != 405 {
		t.Errorf("GET /mcp = %d, want 405", resp.StatusCode)
	}
}

func TestOAuthPathForwardedWithoutAuth(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	if resp, body := req(t, c.replicaURL[0], name+".example.test", "POST", "/register", []byte(`{}`), nil); resp.StatusCode != 200 || !strings.Contains(body, "/register") {
		t.Errorf("POST /register = %d %q", resp.StatusCode, body)
	}
}

func TestSharePathRegexE2E(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	good := "/s/" + strings.Repeat("a", 64)
	if resp, _ := req(t, c.replicaURL[0], name+".example.test", "GET", good, nil, nil); resp.StatusCode != 200 {
		t.Errorf("valid share = %d", resp.StatusCode)
	}
	if resp, _ := req(t, c.replicaURL[0], name+".example.test", "GET", "/s/short", nil, nil); resp.StatusCode != 404 {
		t.Errorf("bad share = %d, want 404", resp.StatusCode)
	}
}

func TestPublicMtlsHeaderRejected(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	resp, _ := req(t, c.replicaURL[0], name+".example.test", "POST", "/mcp", []byte(`{}`), map[string]string{"X-Forwarded-Tls-Client-Cert": "MIIB"})
	if resp.StatusCode != 400 {
		t.Errorf("mtls header = %d, want 400", resp.StatusCode)
	}
}

func TestBodyCapEnforced(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	big := bytes.Repeat([]byte("A"), 2*1024*1024) // 2MB > 1mb cap
	resp, _ := req(t, c.replicaURL[0], name+".example.test", "POST", "/mcp", big, nil)
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("body cap = %d, want 413", resp.StatusCode)
	}
}

func TestRateLimit429(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	got429 := false
	for i := 0; i < 30; i++ {
		resp, _ := req(t, c.replicaURL[0], name+".example.test", "POST", "/mcp", []byte(`{}`), nil)
		if resp.StatusCode == http.StatusTooManyRequests {
			got429 = true
			if resp.Header.Get("Retry-After") == "" {
				t.Error("429 missing Retry-After")
			}
			break
		}
	}
	if !got429 {
		t.Error("rate limit never tripped after 30 rapid requests")
	}
}

func TestBannedIP403(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	c.writeBans(t, "ip "+clientIP+"\n")
	// Wait for the ban watcher (~1s poll) to pick it up.
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		resp, _ := req(t, c.replicaURL[0], name+".example.test", "POST", "/mcp", []byte(`{}`), nil)
		if resp.StatusCode == http.StatusForbidden {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Error("banned IP never produced 403")
}

func TestBannedTunnelFingerprintRefusedAndEvicted(t *testing.T) {
	c := startCluster(t)
	cert, key, name := enrollRaw(t, c.replicaURL[0])
	cancel := connectPhone(t, c, 0, name, cert, key, phoneBackend())
	defer cancel()
	fp := "sha256:" + hex.EncodeToString(sha256Sum(cert.Raw))
	c.writeBans(t, "tunnel-fingerprint "+fp+"\n")
	// Live tunnel is evicted → requests eventually fail (404 no route or 502).
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		resp, _ := req(t, c.replicaURL[1], name+".example.test", "POST", "/mcp", []byte(`{}`), nil)
		if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusBadGateway {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Error("banned fingerprint tunnel was never evicted")
}

func sha256Sum(b []byte) []byte {
	s := sha256.Sum256(b)
	return s[:]
}
