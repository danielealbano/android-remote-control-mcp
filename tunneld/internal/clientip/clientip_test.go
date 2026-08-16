package clientip

import (
	"net/http"
	"testing"
)

func req(header, value string) *http.Request {
	r, _ := http.NewRequest(http.MethodGet, "/", nil)
	if header != "" {
		r.Header.Set(header, value)
	}
	return r
}

func TestTrustedIP(t *testing.T) {
	tests := []struct {
		name         string
		header, hval string
		lookup       string
		wantOK       bool
		wantAddr     string
	}{
		{"single value cf", "Cf-Connecting-Ip", "9.9.9.9", "Cf-Connecting-Ip", true, "9.9.9.9"},
		{"right-most xff", "X-Forwarded-For", "1.2.3.4, 9.9.9.9", "X-Forwarded-For", true, "9.9.9.9"},
		{"absent header", "", "", "X-Real-Ip", false, ""},
		{"unparseable", "X-Real-Ip", "not-an-ip", "X-Real-Ip", false, ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			addr, ok := TrustedIP(req(tc.header, tc.hval), tc.lookup)
			if ok != tc.wantOK {
				t.Fatalf("ok = %v, want %v", ok, tc.wantOK)
			}
			if ok && addr.String() != tc.wantAddr {
				t.Errorf("addr = %s, want %s", addr, tc.wantAddr)
			}
		})
	}
}
