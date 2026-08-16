package server

import (
	"net"
	"net/http"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
)

// connectHandler is the subset of *wsconn.Manager the mux needs (kept as an interface so routes.go
// carries no heavy dependency and stays test-friendly).
type connectHandler interface {
	HandleConnect(w http.ResponseWriter, r *http.Request)
}

// NewMux dispatches by Host:
//   - the enroll host: POST /enroll → enrollHandler (else 404);
//   - any other (per-tunnel) host: the ENTIRE /connect path → the WebSocket manager (which answers a
//     non-upgrade /connect with 426), everything else → the public ingress handler.
//
// /connect is reserved and NEVER reaches the allowlist.
func NewMux(cfg config.ServeCmd, manager connectHandler, ingressHandler, enrollHandler http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hostOnly(r.Host) == cfg.EnrollHost {
			if r.Method == http.MethodPost && r.URL.Path == "/enroll" {
				enrollHandler.ServeHTTP(w, r)
				return
			}
			http.NotFound(w, r)
			return
		}
		if r.URL.Path == "/connect" {
			manager.HandleConnect(w, r)
			return
		}
		ingressHandler.ServeHTTP(w, r)
	})
}

func hostOnly(host string) string {
	if h, _, err := net.SplitHostPort(host); err == nil {
		return h
	}
	return host
}
