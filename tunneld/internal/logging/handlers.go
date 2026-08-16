package logging

import (
	"context"
	"io"
	"log/slog"
)

// fanoutHandler dispatches each record to every child that admits its level. Enabled reports true
// if ANY child would handle the record, so slog does not drop records the fan-out still needs.
type fanoutHandler struct {
	children []slog.Handler
}

func (h *fanoutHandler) Enabled(ctx context.Context, level slog.Level) bool {
	for _, c := range h.children {
		if c.Enabled(ctx, level) {
			return true
		}
	}
	return false
}

func (h *fanoutHandler) Handle(ctx context.Context, r slog.Record) error {
	var firstErr error
	for _, c := range h.children {
		if !c.Enabled(ctx, r.Level) {
			continue
		}
		if err := c.Handle(ctx, r.Clone()); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (h *fanoutHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	next := make([]slog.Handler, len(h.children))
	for i, c := range h.children {
		next[i] = c.WithAttrs(attrs)
	}
	return &fanoutHandler{children: next}
}

func (h *fanoutHandler) WithGroup(name string) slog.Handler {
	next := make([]slog.Handler, len(h.children))
	for i, c := range h.children {
		next[i] = c.WithGroup(name)
	}
	return &fanoutHandler{children: next}
}

// stdSplitHandler is a std sink: it routes each admitted record to EXACTLY one of two underlying
// handlers by severity — level < warn → stdout, level >= warn → stderr. A warn/error record never
// reaches stdout and an info/debug record never reaches stderr.
type stdSplitHandler struct {
	out slog.Handler // os.Stdout (or injected buffer)
	err slog.Handler // os.Stderr (or injected buffer)
}

func newStdSplit(stdout, stderr io.Writer, s spec) slog.Handler {
	return &stdSplitHandler{
		out: newHandler(stdout, s),
		err: newHandler(stderr, s),
	}
}

func (h *stdSplitHandler) Enabled(ctx context.Context, level slog.Level) bool {
	// out and err share the same level floor; either answers.
	return h.out.Enabled(ctx, level)
}

func (h *stdSplitHandler) Handle(ctx context.Context, r slog.Record) error {
	if r.Level >= slog.LevelWarn {
		return h.err.Handle(ctx, r)
	}
	return h.out.Handle(ctx, r)
}

func (h *stdSplitHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &stdSplitHandler{out: h.out.WithAttrs(attrs), err: h.err.WithAttrs(attrs)}
}

func (h *stdSplitHandler) WithGroup(name string) slog.Handler {
	return &stdSplitHandler{out: h.out.WithGroup(name), err: h.err.WithGroup(name)}
}

// newLeaf builds a single min-level handler (used for file sinks over a lumberjack writer).
func newLeaf(w io.Writer, s spec) slog.Handler {
	return newHandler(w, s)
}
