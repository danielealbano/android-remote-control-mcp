package ban

import (
	"io"
	"log/slog"
	"net/netip"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func writeBan(t *testing.T, dir, name, content string) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func mustAddr(s string) netip.Addr { return netip.MustParseAddr(s) }

func TestFreshEnginePanicSafe(t *testing.T) {
	e := NewEngine()
	if _, ok := e.Match(mustAddr("1.2.3.4")); ok {
		t.Error("fresh engine must not match any IP")
	}
	if _, ok := e.MatchTunnel("x", "y"); ok {
		t.Error("fresh engine must not match any tunnel")
	}
}

func TestMatchSingleIPVia32(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "ip 9.9.9.9\n")
	e := NewEngine()
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("9.9.9.9")); !ok {
		t.Error("9.9.9.9 must match")
	}
	if _, ok := e.Match(mustAddr("9.9.9.8")); ok {
		t.Error("9.9.9.8 must NOT match (single ip is /32)")
	}
}

func TestMatchCIDRCoversRange(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "cidr 10.1.0.0/16\n")
	e := NewEngine()
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("10.1.42.7")); !ok {
		t.Error("address inside cidr must match")
	}
	if _, ok := e.Match(mustAddr("10.2.0.1")); ok {
		t.Error("address outside cidr must NOT match")
	}
}

func TestMatchReturnsReasonSource(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "# header comment\nip 9.9.9.9\n")
	e := NewEngine()
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	src, ok := e.Match(mustAddr("9.9.9.9"))
	if !ok {
		t.Fatal("expected match")
	}
	if src.Reason != ReasonIP {
		t.Errorf("reason = %q, want banned_ip", src.Reason)
	}
	if src.File != f || src.Line != 2 {
		t.Errorf("source = %s:%d, want %s:2", src.File, src.Line, f)
	}
}

func TestUnionAcrossMultipleFiles(t *testing.T) {
	dir := t.TempDir()
	a := writeBan(t, dir, "a.txt", "ip 1.1.1.1\n")
	b := writeBan(t, dir, "b.txt", "ip 2.2.2.2\n")
	e := NewEngine()
	if err := e.Load([]string{a, b}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("1.1.1.1")); !ok {
		t.Error("entry from file A must match")
	}
	if _, ok := e.Match(mustAddr("2.2.2.2")); !ok {
		t.Error("entry from file B must match")
	}
}

func TestReloadSwapsAtomically(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "ip 1.1.1.1\n")
	e := NewEngine()
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("1.1.1.1")); !ok {
		t.Fatal("1.1.1.1 must match before reload")
	}
	writeBan(t, dir, "bans.txt", "ip 2.2.2.2\n")
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("2.2.2.2")); !ok {
		t.Error("new entry must match after reload")
	}
	if _, ok := e.Match(mustAddr("1.1.1.1")); ok {
		t.Error("removed entry must NOT match after reload")
	}
}

func TestMatchTunnelNameAndFingerprint(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "tunnel-name abcdef2345\ntunnel-fingerprint sha256:deadbeef\n")
	e := NewEngine()
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if src, ok := e.MatchTunnel("abcdef2345", ""); !ok || src.Reason != ReasonTunnelName {
		t.Errorf("name ban: ok=%v reason=%q", ok, src.Reason)
	}
	if src, ok := e.MatchTunnel("other", "sha256:deadbeef"); !ok || src.Reason != ReasonTunnelFingerprint {
		t.Errorf("fingerprint ban: ok=%v reason=%q", ok, src.Reason)
	}
	if _, ok := e.MatchTunnel("nope", "nope"); ok {
		t.Error("unbanned tunnel must not match")
	}
}

func TestMissingBanFileSkipped(t *testing.T) {
	dir := t.TempDir()
	good := writeBan(t, dir, "good.txt", "ip 1.1.1.1\n")
	missing := filepath.Join(dir, "absent.txt")
	e := NewEngine()
	if err := e.Load([]string{good, missing}, "", discardLog()); err != nil {
		t.Fatalf("absent file must not fail load: %v", err)
	}
	if _, ok := e.Match(mustAddr("1.1.1.1")); !ok {
		t.Error("other file must still be enforced when one is absent")
	}
	writeBan(t, dir, "absent.txt", "ip 2.2.2.2\n")
	if err := e.Load([]string{good, missing}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("2.2.2.2")); !ok {
		t.Error("newly-created file's entries must load on next reload")
	}
}

func TestWatchFiresOnReloadOnMtimeChange(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "ip 1.1.1.1\n")
	e := NewEngine()
	var reloads int32
	w := &watcher{e: e, files: []string{f}, onReload: func(*Engine) { atomic.AddInt32(&reloads, 1) }, log: discardLog()}
	w.initial()
	if atomic.LoadInt32(&reloads) != 1 {
		t.Fatalf("initial load must fire onReload once, got %d", reloads)
	}
	writeBan(t, dir, "bans.txt", "ip 2.2.2.2\n")
	future := time.Now().Add(2 * time.Second)
	if err := os.Chtimes(f, future, future); err != nil {
		t.Fatal(err)
	}
	w.tick()
	if atomic.LoadInt32(&reloads) != 2 {
		t.Errorf("mtime change must fire onReload again, got %d", reloads)
	}
	if _, ok := e.Match(mustAddr("2.2.2.2")); !ok {
		t.Error("reloaded entry must match")
	}
}

func TestWatchLoadErrorKeepsPreviousSnapshot(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "ip 1.1.1.1\n")
	e := NewEngine()
	var reloads int32
	w := &watcher{e: e, files: []string{f}, onReload: func(*Engine) { atomic.AddInt32(&reloads, 1) }, log: discardLog()}
	w.initial()
	if atomic.LoadInt32(&reloads) != 1 {
		t.Fatalf("initial onReload count = %d", reloads)
	}
	// Replace the file with a directory → a hard read error on the next Load.
	if err := os.Remove(f); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(f, 0o755); err != nil {
		t.Fatal(err)
	}
	future := time.Now().Add(2 * time.Second)
	_ = os.Chtimes(f, future, future)
	w.tick()
	if atomic.LoadInt32(&reloads) != 1 {
		t.Errorf("load error must NOT fire onReload, count = %d", reloads)
	}
	if _, ok := e.Match(mustAddr("1.1.1.1")); !ok {
		t.Error("previous snapshot must be preserved on load error")
	}
}
