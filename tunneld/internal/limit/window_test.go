package limit

import (
	"net/netip"
	"testing"
	"time"
)

var testIP = netip.MustParseAddr("203.0.113.7")

func TestRPSAllowsUpToLimitThenDenies(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := ctxT(t)
	for i := 0; i < 10; i++ {
		ok, _, err := Allow(ctx, rdb, "rps", testIP, 10, time.Second)
		if err != nil || !ok {
			t.Fatalf("call %d: ok=%v err=%v (want allowed)", i+1, ok, err)
		}
	}
	ok, retry, err := Allow(ctx, rdb, "rps", testIP, 10, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("11th call must be denied")
	}
	if retry <= 0 || retry > time.Second {
		t.Errorf("retry-after = %s, want (0, 1s]", retry)
	}
}

func TestRPMAllowsUpToLimitThenDenies(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := ctxT(t)
	for i := 0; i < 100; i++ {
		ok, _, err := Allow(ctx, rdb, "rpm", testIP, 100, time.Minute)
		if err != nil || !ok {
			t.Fatalf("call %d denied: ok=%v err=%v", i+1, ok, err)
		}
	}
	ok, retry, err := Allow(ctx, rdb, "rpm", testIP, 100, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("101st call must be denied")
	}
	if retry <= 0 || retry > time.Minute {
		t.Errorf("retry-after = %s, want (0, 1m]", retry)
	}
}

func TestWindowResetsOnBoundary(t *testing.T) {
	rdb, mr := newTestRedis(t)
	ctx := ctxT(t)
	for i := 0; i < 2; i++ {
		if ok, _, _ := Allow(ctx, rdb, "rps", testIP, 2, time.Second); !ok {
			t.Fatalf("call %d must be allowed", i+1)
		}
	}
	if ok, _, _ := Allow(ctx, rdb, "rps", testIP, 2, time.Second); ok {
		t.Fatal("3rd call must be denied within the window")
	}
	// Advance miniredis past the window*2 TTL so the window key expires.
	mr.FastForward(3 * time.Second)
	if ok, _, err := Allow(ctx, rdb, "rps", testIP, 2, time.Second); err != nil || !ok {
		t.Errorf("after window expiry the count must reset (ok=%v err=%v)", ok, err)
	}
}

func TestEveryKeyHasTTLAfterFirstOp(t *testing.T) {
	rdb, mr := newTestRedis(t)
	ctx := ctxT(t)
	if _, _, err := Allow(ctx, rdb, "rps", testIP, 10, time.Second); err != nil {
		t.Fatal(err)
	}
	if _, _, err := AllowEnroll(ctx, rdb, testIP, 20, 2); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Acquire(ctx, rdb, "tunnel-x", 4, time.Minute); err != nil {
		t.Fatal(err)
	}
	keys := mr.Keys()
	if len(keys) == 0 {
		t.Fatal("expected keys after limiter ops")
	}
	for _, k := range keys {
		if ttl := mr.TTL(k); ttl <= 0 {
			t.Errorf("key %q has no TTL (%s) — un-TTL'd Redis state", k, ttl)
		}
	}
}
