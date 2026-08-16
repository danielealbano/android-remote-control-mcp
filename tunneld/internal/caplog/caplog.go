// Package caplog is the deduplicated cap-hit event logger: the first hit per (tunnel, reason) in a
// window is logged immediately; further hits accumulate and are summarized at most once per window.
// No per-key tickers/goroutines — flushing is lazy (on a later hit past the window, on idle-key
// eviction, or via Flush) so there is no ticker lifecycle to leak.
package caplog

import (
	"log/slog"
	"sync"
	"time"
)

// Logger is the deduping cap-hit logger.
type Logger struct {
	mu     sync.Mutex
	states map[string]*state
	window time.Duration
	log    *slog.Logger
	now    func() time.Time
}

type state struct {
	count       int
	ips         map[string]struct{}
	windowStart time.Time
}

// New builds a Logger with a 1-minute window.
func New(log *slog.Logger) *Logger {
	return newLogger(log, time.Minute, time.Now)
}

func newLogger(log *slog.Logger, window time.Duration, now func() time.Time) *Logger {
	return &Logger{states: map[string]*state{}, window: window, log: log, now: now}
}

// Hit records one cap hit. The first hit of each window logs immediately at WARN; subsequent hits
// accumulate; a summary is emitted at the window boundary (on the next hit past the window or when
// an idle key is swept).
func (l *Logger) Hit(tunnel, reason, clientIP string, fields ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	key := tunnel + "|" + reason

	st := l.states[key]
	switch {
	case st == nil:
		l.logImmediate(tunnel, reason, clientIP, fields)
		l.states[key] = newState(now, clientIP)
	case now.Sub(st.windowStart) >= l.window:
		l.emitSummary(tunnel, reason, st)
		l.logImmediate(tunnel, reason, clientIP, fields)
		*st = *newState(now, clientIP)
	default:
		st.count++
		if clientIP != "" {
			st.ips[clientIP] = struct{}{}
		}
	}
	l.sweep(now, key)
}

// Flush emits any pending summaries (e.g. at shutdown) and clears the map.
func (l *Logger) Flush() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for key, st := range l.states {
		tunnel, reason := splitKey(key)
		l.emitSummary(tunnel, reason, st)
		delete(l.states, key)
	}
}

func (l *Logger) sweep(now time.Time, except string) {
	for key, st := range l.states {
		if key == except {
			continue
		}
		if now.Sub(st.windowStart) >= l.window {
			tunnel, reason := splitKey(key)
			l.emitSummary(tunnel, reason, st)
			delete(l.states, key)
		}
	}
}

func (l *Logger) logImmediate(tunnel, reason, clientIP string, fields []any) {
	attrs := append([]any{"tunnel", tunnel, "reason", reason, "client_ip", clientIP}, fields...)
	l.log.Warn("cap hit", attrs...)
}

func (l *Logger) emitSummary(tunnel, reason string, st *state) {
	if st.count <= 1 {
		return // only the immediately-logged first hit; nothing to summarize
	}
	l.log.Warn("cap hit summary",
		"tunnel", tunnel, "reason", reason,
		"count", st.count, "ips", len(st.ips),
		"window", l.window.String())
}

func newState(now time.Time, clientIP string) *state {
	s := &state{count: 1, ips: map[string]struct{}{}, windowStart: now}
	if clientIP != "" {
		s.ips[clientIP] = struct{}{}
	}
	return s
}

func splitKey(key string) (tunnel, reason string) {
	for i := 0; i < len(key); i++ {
		if key[i] == '|' {
			return key[:i], key[i+1:]
		}
	}
	return key, ""
}
