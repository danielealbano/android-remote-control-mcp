package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
)

type recordHandler struct{ hit string }

func (h *recordHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) { w.WriteHeader(299) }

type recordConnect struct{ hit bool }

func (c *recordConnect) HandleConnect(w http.ResponseWriter, r *http.Request) {
	c.hit = true
	w.WriteHeader(298)
}

func TestMuxDispatch(t *testing.T) {
	cfg := config.ServeCmd{EnrollHost: "enroll.example.test"}
	conn := &recordConnect{}
	ingress := &recordHandler{}
	enroll := &recordHandler{}
	mux := NewMux(cfg, conn, ingress, enroll)

	call := func(method, host, path string) int {
		r := httptest.NewRequest(method, "http://"+host+path, nil)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, r)
		return rr.Code
	}

	// Enroll host: POST /enroll → enroll handler.
	if code := call("POST", "enroll.example.test", "/enroll"); code != 299 {
		t.Errorf("POST /enroll → %d, want enroll handler (299)", code)
	}
	// Enroll host, other path → 404.
	if code := call("GET", "enroll.example.test", "/x"); code != http.StatusNotFound {
		t.Errorf("enroll host other path → %d, want 404", code)
	}
	// Per-tunnel host: /connect → manager.
	if code := call("GET", "abc.example.test", "/connect"); code != 298 || !conn.hit {
		t.Errorf("/connect → %d hit=%v, want manager (298)", code, conn.hit)
	}
	// Per-tunnel host: other path → ingress.
	if code := call("POST", "abc.example.test", "/mcp"); code != 299 {
		t.Errorf("/mcp → %d, want ingress (299)", code)
	}
}
