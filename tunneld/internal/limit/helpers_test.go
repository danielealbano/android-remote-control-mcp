package limit

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newTestRedis(t *testing.T) (*redis.Client, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return rdb, mr
}

func ctxT(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return ctx
}

// freezeClock pins the window limiter's clock so a burst never straddles a real window boundary
// (deterministic + instant). Restored on cleanup. Callers must not run in parallel.
func freezeClock(t *testing.T) {
	t.Helper()
	base := time.Unix(1_700_000_000, 0)
	nowFunc = func() time.Time { return base }
	t.Cleanup(func() { nowFunc = time.Now })
}

// fakeClock is a manually-advanced clock for bucket/registry tests.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{t: time.Unix(1_700_000_000, 0)} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// advancingSleep returns a sleep func that advances the fake clock by the requested duration (so the
// next bucket refill observes the elapsed time deterministically).
func (c *fakeClock) advancingSleep() func(context.Context, time.Duration) error {
	return func(ctx context.Context, d time.Duration) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		c.Advance(d)
		return nil
	}
}
