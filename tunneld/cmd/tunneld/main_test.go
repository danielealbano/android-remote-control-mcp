package main

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
)

// captureStdout redirects os.Stdout for the duration of fn and returns what was written.
func captureStdout(t *testing.T, fn func()) string {
	t.Helper()
	orig := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = w
	done := make(chan string, 1)
	go func() {
		b, _ := io.ReadAll(r)
		done <- string(b)
	}()
	fn()
	_ = w.Close()
	os.Stdout = orig
	return <-done
}

func TestCLIVersion(t *testing.T) {
	origArgs := os.Args
	defer func() { os.Args = origArgs }()
	os.Args = []string{"tunneld", "version"}

	out := captureStdout(t, main)
	if !strings.Contains(out, "tunneld") || !strings.Contains(out, version) {
		t.Errorf("version output = %q, want to contain %q", out, version)
	}
}

func TestCLIServeDispatch(t *testing.T) {
	dir := t.TempDir()
	cert := filepath.Join(dir, "ca.pem")
	key := filepath.Join(dir, "ca-key.pem")
	if err := os.WriteFile(cert, []byte("c"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(key, []byte("k"), 0o600); err != nil {
		t.Fatal(err)
	}

	var (
		mu     sync.Mutex
		called bool
		gotCfg config.ServeCmd
	)
	origRun := runServe
	defer func() { runServe = origRun }()
	runServe = func(ctx context.Context, cfg config.ServeCmd, logger *slog.Logger, ver string) error {
		mu.Lock()
		called, gotCfg = true, cfg
		mu.Unlock()
		return nil
	}

	origArgs := os.Args
	defer func() { os.Args = origArgs }()
	os.Args = []string{
		"tunneld", "serve",
		"--ca-cert", cert,
		"--ca-key", key,
		"--client-ip-header", "X-Real-Ip",
	}

	main()

	mu.Lock()
	defer mu.Unlock()
	if !called {
		t.Fatal("serve did not dispatch to runServe seam")
	}
	if gotCfg.ClientIPHeader != "X-Real-Ip" {
		t.Errorf("dispatched cfg.ClientIPHeader = %q, want X-Real-Ip", gotCfg.ClientIPHeader)
	}
	if gotCfg.CACert != cert {
		t.Errorf("dispatched cfg.CACert = %q, want %q", gotCfg.CACert, cert)
	}
}
