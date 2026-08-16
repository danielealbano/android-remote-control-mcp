package metrics

import (
	"context"
	"strconv"
	"sync"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/admin"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/caplog"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
)

// PromRecorder implements observ.Recorder by combining the metric registry, the cap-hit deduping
// logger, and the async per-tunnel counter flusher. It is the single object injected (US10) into the
// US6/US7/US8 handlers.
type PromRecorder struct {
	m      *Metrics
	caplog *caplog.Logger
	admin  *admin.Store

	mu  sync.Mutex
	agg map[string]*aggEntry
}

type aggEntry struct {
	requests int64
	bytesIn  int64
	bytesOut int64
}

var _ observ.Recorder = (*PromRecorder)(nil)

// NewPromRecorder builds the recorder.
func NewPromRecorder(m *Metrics, cl *caplog.Logger, store *admin.Store) *PromRecorder {
	return &PromRecorder{m: m, caplog: cl, admin: store, agg: map[string]*aggEntry{}}
}

// Reject bumps the reason counter AND emits a deduped cap-hit log.
func (p *PromRecorder) Reject(reason, tunnelName, clientIP string) {
	p.m.rejections.WithLabelValues(reason).Inc()
	p.caplog.Hit(tunnelName, reason, clientIP)
}

// Request bumps the request counter + duration histogram and accumulates the per-tunnel requests
// counter in-process (flushed async — never a synchronous Redis write on the data plane).
func (p *PromRecorder) Request(tunnelName, class string, code int, dur time.Duration) {
	p.m.httpRequests.WithLabelValues(class, strconv.Itoa(code)).Inc()
	p.m.httpDuration.Observe(dur.Seconds())
	p.accum(tunnelName, func(e *aggEntry) { e.requests++ })
}

// Bytes bumps the direction counter and accumulates the per-tunnel byte counters.
func (p *PromRecorder) Bytes(tunnelName, direction string, n int64) {
	p.m.bytesTotal.WithLabelValues(direction).Add(float64(n))
	p.accum(tunnelName, func(e *aggEntry) {
		if direction == "in" {
			e.bytesIn += n
		} else {
			e.bytesOut += n
		}
	})
}

func (p *PromRecorder) WSConnect() {
	p.m.wsConnects.Inc()
	p.m.tunnelsConnected.Inc()
}

func (p *PromRecorder) WSDisconnect(reason string) {
	p.m.wsDisconnects.WithLabelValues(reason).Inc()
	p.m.tunnelsConnected.Dec()
}

func (p *PromRecorder) Enrollment()           { p.m.enrollments.Inc() }
func (p *PromRecorder) InflightAdd(delta int) { p.m.httpInflight.Add(float64(delta)) }
func (p *PromRecorder) Timeout()              { p.m.requestTimeouts.Inc() }
func (p *PromRecorder) PublishError()         { p.m.pubsubPublishErrors.Inc() }

func (p *PromRecorder) accum(name string, f func(*aggEntry)) {
	if name == "" {
		return
	}
	p.mu.Lock()
	e := p.agg[name]
	if e == nil {
		e = &aggEntry{}
		p.agg[name] = e
	}
	f(e)
	p.mu.Unlock()
}

// RunFlusher periodically drains the accumulated deltas into the admin.Store (a real async write
// path). A final flush runs on ctx cancel.
func (p *PromRecorder) RunFlusher(ctx context.Context, every time.Duration) error {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			p.flush(context.Background())
			return ctx.Err()
		case <-ticker.C:
			p.flush(ctx)
		}
	}
}

// flush swaps in a fresh empty map (so flushed names are dropped) and applies the deltas.
func (p *PromRecorder) flush(ctx context.Context) {
	p.mu.Lock()
	drained := p.agg
	p.agg = map[string]*aggEntry{}
	p.mu.Unlock()

	for name, e := range drained {
		if e.requests != 0 {
			_ = p.admin.Incr(ctx, name, "requests", e.requests)
		}
		if e.bytesIn != 0 {
			_ = p.admin.Incr(ctx, name, "bytes_in", e.bytesIn)
		}
		if e.bytesOut != 0 {
			_ = p.admin.Incr(ctx, name, "bytes_out", e.bytesOut)
		}
	}
}
