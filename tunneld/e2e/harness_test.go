//go:build e2e

// Package e2e brings up the full stack (Redis + Traefik + two tunneld replicas) via testcontainers
// and drives it with the Go client (application-layer challenge-response) + a raw HTTP client. Run
// with `make test-e2e` (build tag `e2e`); skipped by default unit runs.
package e2e

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/testcontainers/testcontainers-go"
	tcnetwork "github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"
)

const tunneldImage = "tunneld:e2e"

type cluster struct {
	net        *testcontainers.DockerNetwork
	traefik    testcontainers.Container
	replicas   []testcontainers.Container
	traefikURL string   // http://host:port (Traefik web entrypoint)
	replicaURL []string // direct http://host:port per replica (mapped 8080)
	caDir      string
	banDir     string
}

// buildImage builds the tunneld image once (via the docker CLI; testcontainers then runs it).
func buildImage(t *testing.T) {
	t.Helper()
	root, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("docker", "build", "-t", tunneldImage, "-f", filepath.Join(root, "Dockerfile"), root)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("docker build failed: %v\n%s", err, out)
	}
}

// writeCA generates a throwaway CA into a temp dir, world-readable (0644) so the image's nonroot uid
// can read it. Returns the dir.
func writeCA(t *testing.T) string {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "e2e-ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign, BasicConstraintsValid: true, IsCA: true, MaxPathLenZero: true,
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	dir := t.TempDir()
	keyDER, _ := x509.MarshalECPrivateKey(key)
	if err := os.WriteFile(filepath.Join(dir, "ca.pem"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "ca-key.pem"), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func startCluster(t *testing.T) *cluster {
	t.Helper()
	buildImage(t)
	ctx := context.Background()

	net, err := tcnetwork.New(ctx)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = net.Remove(ctx) })

	caDir := writeCA(t)
	banDir := t.TempDir()
	banPath := filepath.Join(banDir, "bans.txt")
	if err := os.WriteFile(banPath, []byte(""), 0o644); err != nil {
		t.Fatal(err)
	}

	c := &cluster{net: net, caDir: caDir, banDir: banDir}

	// Redis.
	redis := c.start(t, testcontainers.ContainerRequest{
		Image:          "redis:7-alpine",
		Networks:       []string{net.Name},
		NetworkAliases: map[string][]string{net.Name: {"redis"}},
		Cmd:            []string{"redis-server", "--save", "", "--appendonly", "no"},
		WaitingFor:     wait.ForListeningPort("6379/tcp"),
	})
	_ = redis

	// Two tunneld replicas (direct mapped ports for deterministic cross-node routing).
	for i := 1; i <= 2; i++ {
		alias := fmt.Sprintf("tunneld-%d", i)
		rc := c.start(t, testcontainers.ContainerRequest{
			Image:          tunneldImage,
			Networks:       []string{net.Name},
			NetworkAliases: map[string][]string{net.Name: {alias}},
			ExposedPorts:   []string{"8080/tcp", "9090/tcp"},
			Env: map[string]string{
				"TUNNELD_LISTEN":           ":8080",
				"TUNNELD_INTERNAL_LISTEN":  ":9090",
				"TUNNELD_REDIS_URL":        "redis://redis:6379",
				"TUNNELD_TUNNEL_DOMAIN":    "example.test",
				"TUNNELD_ENROLL_HOST":      "enroll.example.test",
				"TUNNELD_CLIENT_IP_HEADER": "X-Real-Ip",
				"TUNNELD_CA_CERT":          "/ca/ca.pem",
				"TUNNELD_CA_KEY":           "/ca/ca-key.pem",
				"TUNNELD_BAN_FILE":         "/banfiles/bans.txt",
				"TUNNELD_BAN_POLL":         "1s",
			},
			// Bind-mount CA + banfiles (read-only in the container) so host-side ban-file updates
			// propagate with REAL mtimes — the ban watcher is mtime-based, and CopyToContainer would
			// set a stale mtime the watcher never sees.
			HostConfigModifier: func(hc *container.HostConfig) {
				hc.Binds = []string{caDir + ":/ca:ro", banDir + ":/banfiles:ro"}
			},
			WaitingFor: wait.ForHTTP("/healthz").WithPort("9090/tcp").WithStartupTimeout(60 * time.Second),
		})
		c.replicas = append(c.replicas, rc)
		c.replicaURL = append(c.replicaURL, mappedURL(t, ctx, rc, "8080"))
	}

	// Traefik (grey-cloud e2e config).
	traefikCfg, _ := filepath.Abs("testdata/traefik-e2e.yml")
	c.traefik = c.start(t, testcontainers.ContainerRequest{
		Image:          "traefik:v3.3",
		Networks:       []string{net.Name},
		NetworkAliases: map[string][]string{net.Name: {"traefik"}},
		ExposedPorts:   []string{"80/tcp"},
		Cmd: []string{
			"--providers.file.filename=/etc/traefik/dynamic.yml",
			"--entrypoints.web.address=:80",
		},
		Files: []testcontainers.ContainerFile{
			{HostFilePath: traefikCfg, ContainerFilePath: "/etc/traefik/dynamic.yml", FileMode: 0o644},
		},
		WaitingFor: wait.ForListeningPort("80/tcp").WithStartupTimeout(60 * time.Second),
	})
	c.traefikURL = mappedURL(t, ctx, c.traefik, "80")
	return c
}

func (c *cluster) start(t *testing.T, req testcontainers.ContainerRequest) testcontainers.Container {
	t.Helper()
	ctx := context.Background()
	ct, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{ContainerRequest: req, Started: true})
	if err != nil {
		t.Fatalf("start container %s: %v", req.Image, err)
	}
	t.Cleanup(func() { _ = ct.Terminate(context.Background()) })
	return ct
}

func mappedURL(t *testing.T, ctx context.Context, ct testcontainers.Container, port string) string {
	t.Helper()
	host, err := ct.Host(ctx)
	if err != nil {
		t.Fatal(err)
	}
	mp, err := ct.MappedPort(ctx, port+"/tcp")
	if err != nil {
		t.Fatal(err)
	}
	return fmt.Sprintf("http://%s:%s", host, mp.Port())
}

// writeBans rewrites the host-side ban file; the bind mount reflects it (with a real mtime) into
// both replicas, and the ban watcher (~1s poll) picks it up.
func (c *cluster) writeBans(t *testing.T, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(c.banDir, "bans.txt"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
