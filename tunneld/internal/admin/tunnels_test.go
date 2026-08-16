package admin

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newStore(t *testing.T) (*Store, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return NewStore(rdb, time.Hour), mr
}

func TestAdminTopNSortsByBytes(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	_ = s.Incr(ctx, "a", "bytes_in", 100)
	_ = s.Incr(ctx, "a", "bytes_out", 50) // a total 150
	_ = s.Incr(ctx, "a", "requests", 3)
	_ = s.Incr(ctx, "b", "bytes_in", 500) // b total 500
	stats, err := s.TopN(ctx, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(stats) != 2 || stats[0].Name != "b" || stats[1].Name != "a" {
		t.Fatalf("topN ordering wrong: %+v", stats)
	}
	if stats[1].Requests != 3 || stats[1].BytesIn != 100 || stats[1].BytesOut != 50 {
		t.Errorf("a counters wrong: %+v", stats[1])
	}
}

func TestAdminCounterKeyHasTTL(t *testing.T) {
	s, mr := newStore(t)
	_ = s.Incr(context.Background(), "x", "requests", 1)
	if ttl := mr.TTL("tcnt:x"); ttl <= 0 {
		t.Errorf("tcnt:x TTL = %s, want > 0 (single-Lua HINCRBY+PEXPIRE)", ttl)
	}
}
