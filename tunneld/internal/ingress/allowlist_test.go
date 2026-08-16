package ingress

import (
	"strings"
	"testing"
)

func TestAllowlistMatch(t *testing.T) {
	hex64 := strings.Repeat("a", 64)
	cases := []struct {
		method, path string
		wantClass    string
		wantDec      Decision
	}{
		{"POST", "/mcp", "mcp", Forward},
		{"DELETE", "/mcp", "mcp", Forward},
		{"OPTIONS", "/mcp", "mcp", Forward},
		{"GET", "/mcp", "mcp", Edge405},
		{"PUT", "/mcp", "", Deny404},
		{"POST", "/register", "oauth", Forward},
		{"GET", "/register", "", Deny404}, // method mismatch → 404, not 405
		{"GET", "/authorize", "oauth", Forward},
		{"GET", "/authorize/status", "oauth", Forward},
		{"POST", "/token", "oauth", Forward},
		{"GET", "/.well-known/oauth-protected-resource", "oauth", Forward},
		{"GET", "/.well-known/oauth-protected-resource/mcp", "oauth", Forward},
		{"GET", "/.well-known/oauth-authorization-server", "oauth", Forward},
		{"GET", "/.well-known/oauth-authorization-server/x", "oauth", Forward},
		{"GET", "/.well-known/openid-configuration", "oauth", Forward},
		{"GET", "/s/" + hex64, "share", Forward},
		{"OPTIONS", "/s/" + hex64, "share", Forward},
		{"GET", "/s/" + strings.Repeat("a", 63), "", Deny404}, // wrong length
		{"GET", "/s/" + strings.Repeat("A", 64), "", Deny404}, // uppercase
		{"GET", "/s/../etc", "", Deny404},
		{"GET", "/", "", Deny404},
		{"POST", "/foo", "", Deny404},
		{"GET", "/health", "", Deny404}, // health never tunneled
	}
	for _, c := range cases {
		route, dec := Match(c.method, c.path)
		if dec != c.wantDec || route.Class != c.wantClass {
			t.Errorf("Match(%s %s) = (%q, %d), want (%q, %d)", c.method, c.path, route.Class, dec, c.wantClass, c.wantDec)
		}
	}
}
