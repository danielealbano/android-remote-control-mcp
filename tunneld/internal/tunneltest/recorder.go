// Package tunneltest provides shared test fakes: the capturing observ.Recorder (here) and the raw
// coder/websocket FakePhone (US6.4). Authored here because US5's transport tests are the first
// consumer; reused by US6/US7/US8/US9/US10.
package tunneltest

import (
	"sync"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
)

// RecCall is one captured Recorder invocation.
type RecCall struct {
	Kind, Reason, Tunnel, IP, Class, Direction string
	Code                                       int
	N                                          int64
	Dur                                        time.Duration
}

// Recorder is a thread-safe capturing observ.Recorder for assertions.
type Recorder struct {
	mu    sync.Mutex
	Calls []RecCall
}

var _ observ.Recorder = (*Recorder)(nil)

func (r *Recorder) add(c RecCall) { r.mu.Lock(); r.Calls = append(r.Calls, c); r.mu.Unlock() }

func (r *Recorder) Reject(reason, tunnel, ip string) {
	r.add(RecCall{Kind: "reject", Reason: reason, Tunnel: tunnel, IP: ip})
}

func (r *Recorder) Request(tunnel, class string, code int, d time.Duration) {
	r.add(RecCall{Kind: "request", Tunnel: tunnel, Class: class, Code: code, Dur: d})
}

func (r *Recorder) Bytes(tunnel, dir string, n int64) {
	r.add(RecCall{Kind: "bytes", Tunnel: tunnel, Direction: dir, N: n})
}

func (r *Recorder) WSConnect()                 { r.add(RecCall{Kind: "wsconnect"}) }
func (r *Recorder) WSDisconnect(reason string) { r.add(RecCall{Kind: "wsdisconnect", Reason: reason}) }
func (r *Recorder) Enrollment()                { r.add(RecCall{Kind: "enrollment"}) }
func (r *Recorder) InflightAdd(delta int)      { r.add(RecCall{Kind: "inflight", Code: delta}) }
func (r *Recorder) Timeout()                   { r.add(RecCall{Kind: "timeout"}) }
func (r *Recorder) PublishError()              { r.add(RecCall{Kind: "publisherror"}) }

// Count returns how many captured calls match kind and (optionally) reason ("" matches any reason).
func (r *Recorder) Count(kind, reason string) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	n := 0
	for _, c := range r.Calls {
		if c.Kind == kind && (reason == "" || c.Reason == reason) {
			n++
		}
	}
	return n
}
