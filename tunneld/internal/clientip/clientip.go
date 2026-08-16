// Package clientip extracts the trusted source IP for all abuse controls from the configured
// --client-ip-header, reading the RIGHT-MOST comma-separated token (a single value for
// Cf-Connecting-Ip/X-Real-Ip; the proxy-appended hop for X-Forwarded-For). It NEVER reads the
// left-most (client-controlled) X-Forwarded-For entry.
package clientip

import (
	"net/http"
	"net/netip"
	"strings"
)

// TrustedIP returns the parsed right-most token of the configured header, or ok=false when the
// header is absent/empty/unparseable (fail-closed at the caller).
func TrustedIP(r *http.Request, header string) (netip.Addr, bool) {
	v := r.Header.Get(header)
	if v == "" {
		return netip.Addr{}, false
	}
	parts := strings.Split(v, ",")
	last := strings.TrimSpace(parts[len(parts)-1])
	addr, err := netip.ParseAddr(last)
	if err != nil {
		return netip.Addr{}, false
	}
	return addr, true
}
