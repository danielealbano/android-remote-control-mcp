package caplog

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func newBufLogger() (*Logger, *bytes.Buffer) {
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelWarn}))
	return newLogger(log, time.Minute, time.Now), &buf
}

func TestCaplogLogsFirstHitImmediately(t *testing.T) {
	l, buf := newBufLogger()
	l.Hit("tunnelA", "rate_rps", "1.1.1.1")
	if n := strings.Count(buf.String(), `msg="cap hit"`); n != 1 {
		t.Errorf("first hit immediate logs = %d, want 1", n)
	}
}

func TestCaplogDedupsWithinWindow(t *testing.T) {
	l, buf := newBufLogger()
	for i := 0; i < 1000; i++ {
		l.Hit("tunnelA", "rate_rps", "1.1.1.1")
	}
	l.Flush()
	if n := strings.Count(buf.String(), `msg="cap hit"`); n != 1 {
		t.Errorf("immediate logs = %d, want exactly 1", n)
	}
	if n := strings.Count(buf.String(), `msg="cap hit summary"`); n != 1 {
		t.Errorf("summary logs = %d, want ≤1 (expected 1)", n)
	}
}

func TestCaplogSummaryCountsHitsAndIPs(t *testing.T) {
	l, buf := newBufLogger()
	l.Hit("t", "r", "1.1.1.1")
	l.Hit("t", "r", "2.2.2.2")
	l.Hit("t", "r", "1.1.1.1")
	l.Flush()
	out := buf.String()
	if !strings.Contains(out, "count=3") {
		t.Errorf("summary count wrong: %s", out)
	}
	if !strings.Contains(out, "ips=2") {
		t.Errorf("summary distinct-ips wrong: %s", out)
	}
}
