package limit

import (
	"testing"
	"time"
)

func TestConcurrencyCapsInFlight(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := ctxT(t)
	const name = "tunnel-c"
	var releases []func()
	for i := 0; i < 4; i++ {
		rel, ok, err := Acquire(ctx, rdb, name, 4, time.Minute)
		if err != nil || !ok {
			t.Fatalf("acquire %d: ok=%v err=%v", i+1, ok, err)
		}
		releases = append(releases, rel)
	}
	if _, ok, err := Acquire(ctx, rdb, name, 4, time.Minute); err != nil || ok {
		t.Fatalf("5th acquire must fail: ok=%v err=%v", ok, err)
	}
	releases[0]() // free a slot
	rel, ok, err := Acquire(ctx, rdb, name, 4, time.Minute)
	if err != nil || !ok {
		t.Fatalf("acquire after release must succeed: ok=%v err=%v", ok, err)
	}
	rel()
	for _, r := range releases[1:] {
		r()
	}
}

func TestConcurrencyReleaseIdempotent(t *testing.T) {
	rdb, mr := newTestRedis(t)
	ctx := ctxT(t)
	const name = "tunnel-idem"
	rel, ok, err := Acquire(ctx, rdb, name, 1, time.Minute)
	if err != nil || !ok {
		t.Fatalf("acquire: ok=%v err=%v", ok, err)
	}
	rel()
	rel() // double release must not underflow (sync.Once)
	if mr.Exists("conc:" + name) {
		t.Error("counter must be deleted at zero, not left negative/underflowed")
	}
	// A fresh acquire against max=1 must still succeed (count is 0, not -1).
	rel2, ok, err := Acquire(ctx, rdb, name, 1, time.Minute)
	if err != nil || !ok {
		t.Fatalf("re-acquire after double release: ok=%v err=%v", ok, err)
	}
	rel2()
}
