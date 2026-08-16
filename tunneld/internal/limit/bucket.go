// Package limit provides Redis-backed per-source-IP request limits and per-tunnel concurrency
// (correct across replicas), enrollment quotas, and an in-process per-direction bandwidth token
// bucket. Every Redis key it creates carries a TTL set atomically with its INCR (single Lua).
package limit

import (
	"context"
	"errors"
	"sync"
	"time"
)

// ErrBurstExceeded is returned by WaitN when n exceeds the bucket burst — it can NEVER be satisfied
// (the bucket never accumulates past burst), so WaitN returns immediately instead of blocking
// forever. Callers MUST acquire large amounts in increments ≤ burst (≤ wire.ChunkSize per US6/US7).
var ErrBurstExceeded = errors.New("limit: WaitN n exceeds bucket burst")

// TokenBucket is a classic refill token bucket pacing bytes/sec. It is mutex-guarded because the
// per-tunnel up-bucket is shared by all concurrent Do goroutines (US6).
type TokenBucket struct {
	mu     sync.Mutex
	rate   int64 // bytes/sec (refill rate)
	burst  int64 // max accumulation (= rate, i.e. one second)
	tokens float64
	last   time.Time
	now    func() time.Time
	sleep  func(ctx context.Context, d time.Duration) error
}

// NewTokenBucket builds a bucket with burst = rate (one second of rate), using the real clock.
func NewTokenBucket(rate int64) *TokenBucket {
	return newTokenBucket(rate, time.Now, realSleep)
}

// newTokenBucket is the injectable core (tests pass a fake clock + fake sleep).
func newTokenBucket(rate int64, now func() time.Time, sleep func(context.Context, time.Duration) error) *TokenBucket {
	return &TokenBucket{
		rate:   rate,
		burst:  rate,
		tokens: float64(rate),
		last:   now(),
		now:    now,
		sleep:  sleep,
	}
}

// NewTunnelBandwidth returns an up-bucket and a down-bucket for one tunnel (per direction).
func NewTunnelBandwidth(bytesPerSec int64) (up, down *TokenBucket) {
	return NewTokenBucket(bytesPerSec), NewTokenBucket(bytesPerSec)
}

// WaitN blocks until n bytes are available or ctx is done. n > burst returns ErrBurstExceeded
// immediately (never an infinite block). The bucket mutex is NOT held across the sleep, so a
// paced/slow acquisition never stalls another goroutine's refill check on the same bucket.
func (b *TokenBucket) WaitN(ctx context.Context, n int) error {
	if int64(n) > b.burst {
		return ErrBurstExceeded
	}
	if n <= 0 {
		return nil
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		b.mu.Lock()
		now := b.now()
		if elapsed := now.Sub(b.last).Seconds(); elapsed > 0 {
			b.tokens += elapsed * float64(b.rate)
			if b.tokens > float64(b.burst) {
				b.tokens = float64(b.burst)
			}
			b.last = now
		}
		if b.tokens >= float64(n) {
			b.tokens -= float64(n)
			b.mu.Unlock()
			return nil
		}
		deficit := float64(n) - b.tokens
		wait := time.Duration(deficit / float64(b.rate) * float64(time.Second))
		b.mu.Unlock()

		if wait <= 0 {
			wait = time.Millisecond
		}
		if err := b.sleep(ctx, wait); err != nil {
			return err
		}
	}
}

func realSleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
