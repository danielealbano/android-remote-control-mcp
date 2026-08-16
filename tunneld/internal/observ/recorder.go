// Package observ defines a dependency-free observability interface so the ingress (US7), enroll
// (US8), and wsconn (US6) rejection/serve sites can record metrics + cap-hit logs WITHOUT importing
// the Prometheus/caplog implementations (built in US9). US9's PromRecorder is the concrete
// implementation; US10 injects it.
package observ

import "time"

// Recorder captures metric + cap-hit events. The concrete PromRecorder (US9) both updates the
// (per-tunnel-label-free) Prometheus families AND writes the per-tunnel tcnt:{name} Redis counters
// that back /admin/tunnels — hence Request/Bytes carry tunnelName, which the callers already know.
type Recorder interface {
	// Reject bumps tunneld_rejections_total{reason} and emits a deduped cap-hit log. clientIP is a
	// string ("" when no valid IP exists on the path, e.g. missing_client_ip).
	Reject(reason, tunnelName, clientIP string)
	// Request bumps tunneld_http_requests_total{class,code} + the duration histogram and the
	// tcnt:{name} requests counter.
	Request(tunnelName, class string, code int, dur time.Duration)
	// Bytes bumps tunneld_bytes_total{direction} and tcnt:{name} bytes_in/out. direction is
	// "in" (phone→client) or "out" (client→phone) — NOT the up/down bandwidth-bucket names.
	Bytes(tunnelName, direction string, n int64)
	WSConnect()
	WSDisconnect(reason string)
	Enrollment()
	InflightAdd(delta int)
	Timeout()      // tunneld_request_timeouts_total
	PublishError() // tunneld_pubsub_publish_errors_total
}

// Nop is a no-op Recorder for unit tests / defaults.
type Nop struct{}

var _ Recorder = Nop{}

func (Nop) Reject(reason, tunnelName, clientIP string)                  {}
func (Nop) Request(tunnelName, class string, code int, d time.Duration) {}
func (Nop) Bytes(tunnelName, direction string, n int64)                 {}
func (Nop) WSConnect()                                                  {}
func (Nop) WSDisconnect(reason string)                                  {}
func (Nop) Enrollment()                                                 {}
func (Nop) InflightAdd(delta int)                                       {}
func (Nop) Timeout()                                                    {}
func (Nop) PublishError()                                               {}
