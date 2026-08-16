package ingress

import (
	"net/http"
	"strings"
)

// mtlsHeaders is the fixed client-cert / mTLS-indicating header set the public side rejects (the app
// does not support client mTLS; defense-in-depth for the standing "reject mTLS headers" requirement).
var mtlsHeaders = []string{
	"X-Forwarded-Tls-Client-Cert",
	"X-Forwarded-Tls-Client-Cert-Info",
	"Ssl-Client-Cert",
	"X-Client-Cert",
	"X-Ssl-Client-Cert",
}

// hopByHop headers are stripped in both directions.
var hopByHop = map[string]struct{}{
	"Connection":          {},
	"Keep-Alive":          {},
	"Proxy-Authenticate":  {},
	"Proxy-Authorization": {},
	"Te":                  {},
	"Trailer":             {},
	"Transfer-Encoding":   {},
	"Upgrade":             {},
}

// Sanitize rejects any request carrying an mTLS-indicating header, then drops hop-by-hop and ALL
// client-supplied X-Forwarded-*/Forwarded headers, re-adding ONLY the proxy-set proto/host/for we
// trust (so the phone's Ktor server sees HTTPS). Returns the cleaned headers and rejected=true if an
// mTLS header was present.
func Sanitize(in http.Header) (out http.Header, rejected bool) {
	for _, h := range mtlsHeaders {
		if in.Get(h) != "" {
			return nil, true
		}
	}
	out = http.Header{}
	for k, vs := range in {
		ck := http.CanonicalHeaderKey(k)
		if _, hop := hopByHop[ck]; hop {
			continue
		}
		if ck == "Forwarded" || strings.HasPrefix(ck, "X-Forwarded-") {
			continue
		}
		for _, v := range vs {
			out.Add(ck, v)
		}
	}
	// Re-add the proxy-set forwarding headers (the proxy stripped client-supplied ones upstream).
	if v := in.Get("X-Forwarded-Proto"); v != "" {
		out.Set("X-Forwarded-Proto", v)
	}
	if v := in.Get("X-Forwarded-Host"); v != "" {
		out.Set("X-Forwarded-Host", v)
	}
	if v := in.Get("X-Forwarded-For"); v != "" {
		out.Set("X-Forwarded-For", v)
	}
	return out, false
}

// SanitizeResponse strips hop-by-hop headers from a phone response before returning it to the client.
func SanitizeResponse(in http.Header) http.Header {
	out := http.Header{}
	for k, vs := range in {
		ck := http.CanonicalHeaderKey(k)
		if _, hop := hopByHop[ck]; hop {
			continue
		}
		for _, v := range vs {
			out.Add(ck, v)
		}
	}
	return out
}
