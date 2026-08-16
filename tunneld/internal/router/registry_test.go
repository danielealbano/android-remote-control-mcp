package router

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newReg(t *testing.T) (*Registry, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return NewRegistry(rdb, 30*time.Second), mr
}

func TestBindThenLookup(t *testing.T) {
	reg, _ := newReg(t)
	ctx := context.Background()
	if err := reg.Bind(ctx, "abc", "nodeA", "sha256:fp1", "conn1"); err != nil {
		t.Fatal(err)
	}
	node, fp, ok, err := reg.Lookup(ctx, "abc")
	if err != nil || !ok {
		t.Fatalf("lookup: ok=%v err=%v", ok, err)
	}
	if node != "nodeA" || fp != "sha256:fp1" {
		t.Errorf("lookup = (%q,%q), want (nodeA, sha256:fp1)", node, fp)
	}
	if _, _, ok, _ := reg.Lookup(ctx, "missing"); ok {
		t.Error("unbound name must not resolve")
	}
}

func TestBindRejectsDifferentFingerprint(t *testing.T) {
	reg, _ := newReg(t)
	ctx := context.Background()
	_ = reg.Bind(ctx, "abc", "nodeA", "sha256:fp1", "conn1")
	if err := reg.Bind(ctx, "abc", "nodeB", "sha256:DIFFERENT", "conn2"); err != ErrNameHeldByOther {
		t.Errorf("different fingerprint bind: err = %v, want ErrNameHeldByOther", err)
	}
}

func TestHeartbeatRefreshesTTL(t *testing.T) {
	reg, mr := newReg(t)
	ctx := context.Background()
	_ = reg.Bind(ctx, "abc", "nodeA", "fp", "conn1")
	mr.FastForward(20 * time.Second) // TTL now ~10s
	res, err := reg.Heartbeat(ctx, "abc", "conn1")
	if err != nil || res != HeartbeatRefreshed {
		t.Fatalf("heartbeat: res=%v err=%v", res, err)
	}
	if ttl := mr.TTL("route:abc"); ttl < 25*time.Second {
		t.Errorf("TTL after refresh = %s, want ~30s", ttl)
	}
}

func TestUnbindRemovesRoute(t *testing.T) {
	reg, _ := newReg(t)
	ctx := context.Background()
	_ = reg.Bind(ctx, "abc", "nodeA", "fp", "conn1")
	if err := reg.Unbind(ctx, "abc", "conn1"); err != nil {
		t.Fatal(err)
	}
	if _, _, ok, _ := reg.Lookup(ctx, "abc"); ok {
		t.Error("route must be gone after unbind")
	}
}

func TestUnbindIsConnConditional(t *testing.T) {
	ctx := context.Background()
	for _, node2 := range []string{"nodeB", "nodeA"} { // another node AND the same node
		reg, _ := newReg(t)
		_ = reg.Bind(ctx, "abc", "nodeA", "fp", "conn1")
		// Same-fingerprint rebind onto conn2.
		if err := reg.Bind(ctx, "abc", node2, "fp", "conn2"); err != nil {
			t.Fatal(err)
		}
		// The stale conn1 tears down — must NOT clobber conn2's route.
		if err := reg.Unbind(ctx, "abc", "conn1"); err != nil {
			t.Fatal(err)
		}
		node, _, ok, _ := reg.Lookup(ctx, "abc")
		if !ok || node != node2 {
			t.Errorf("node2=%s: after stale unbind, route = (%q, ok=%v), want %s", node2, node, ok, node2)
		}
	}
}

func TestHeartbeatIsConnConditional(t *testing.T) {
	reg, mr := newReg(t)
	ctx := context.Background()
	_ = reg.Bind(ctx, "abc", "nodeA", "fp", "conn1")
	_ = reg.Bind(ctx, "abc", "nodeB", "fp", "conn2") // rebind to conn2
	ttlBefore := mr.TTL("route:abc")
	res, err := reg.Heartbeat(ctx, "abc", "conn1") // stale conn heartbeat
	if err != nil {
		t.Fatal(err)
	}
	if res != HeartbeatNotOwner {
		t.Errorf("stale heartbeat = %v, want HeartbeatNotOwner", res)
	}
	node, _, _, _ := reg.Lookup(ctx, "abc")
	if node != "nodeB" {
		t.Errorf("owner changed by stale heartbeat: %q", node)
	}
	if ttl := mr.TTL("route:abc"); ttl > ttlBefore {
		t.Error("stale heartbeat must not refresh TTL")
	}
}

func TestHeartbeatDistinguishesMissingFromNotOwner(t *testing.T) {
	reg, _ := newReg(t)
	ctx := context.Background()
	// Missing route.
	if res, _ := reg.Heartbeat(ctx, "gone", "conn1"); res != HeartbeatMissing {
		t.Errorf("missing route heartbeat = %v, want HeartbeatMissing", res)
	}
	// Held by another connID → not-owner.
	_ = reg.Bind(ctx, "abc", "nodeA", "fp", "conn2")
	if res, _ := reg.Heartbeat(ctx, "abc", "conn1"); res != HeartbeatNotOwner {
		t.Errorf("other-owner heartbeat = %v, want HeartbeatNotOwner", res)
	}
}
