// Package ingress implements the public HTTP side: name-from-Host resolution, the method+path
// allowlist, header sanitization, ban/rate/size/concurrency limits, and dispatch to the phone over
// Redis. The allowlist is the source-of-truth mirror of the app's MCP + OAuth + share surface.
package ingress

import (
	"net/http"
	"regexp"
	"strings"
)

// Decision is the allowlist verdict for a (method, path).
type Decision int

const (
	// Forward: pass the request to the phone.
	Forward Decision = iota
	// Edge405: answer 405 at the edge (only GET /mcp — app parity), with Allow: POST, DELETE.
	Edge405
	// Deny404: reject with 404 (non-allowlisted path or any other method mismatch).
	Deny404
)

// Route carries the metric class label for a matched allowlist entry.
type Route struct {
	Class string // "mcp" | "oauth" | "share"
}

// shareRe matches the exact 64-lowercase-hex capability-token share path.
var shareRe = regexp.MustCompile(`^/s/[0-9a-f]{64}$`)

// Match encodes exactly the app's allowlisted method+path surface. OPTIONS on any allowlisted path
// forwards (CORS preflight). Edge405 exists ONLY for GET /mcp; every other method mismatch is
// Deny404 (an allowlist, not a per-path RFC-status mirror).
func Match(method, path string) (Route, Decision) {
	isOptions := method == http.MethodOptions

	switch path {
	case "/mcp":
		switch method {
		case http.MethodPost, http.MethodDelete, http.MethodOptions:
			return Route{Class: "mcp"}, Forward
		case http.MethodGet:
			return Route{Class: "mcp"}, Edge405
		default:
			return Route{}, Deny404
		}
	case "/register", "/token":
		if method == http.MethodPost || isOptions {
			return Route{Class: "oauth"}, Forward
		}
		return Route{}, Deny404
	case "/authorize", "/authorize/status", "/.well-known/openid-configuration":
		if method == http.MethodGet || isOptions {
			return Route{Class: "oauth"}, Forward
		}
		return Route{}, Deny404
	}

	// .well-known metadata routes with /{tail...} suffix tolerance.
	if method == http.MethodGet || isOptions {
		if path == "/.well-known/oauth-protected-resource" || strings.HasPrefix(path, "/.well-known/oauth-protected-resource/") ||
			path == "/.well-known/oauth-authorization-server" || strings.HasPrefix(path, "/.well-known/oauth-authorization-server/") {
			return Route{Class: "oauth"}, Forward
		}
	}

	// /s/{64-hex} share path.
	if shareRe.MatchString(path) {
		if method == http.MethodGet || isOptions {
			return Route{Class: "share"}, Forward
		}
		return Route{}, Deny404
	}

	return Route{}, Deny404
}
