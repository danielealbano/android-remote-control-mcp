package limit

import (
	"sync"
	"time"
)

const defaultBucketIdle = 10 * time.Minute

// BucketRegistry hands out THE SAME per-tunnel (up, down) bucket pair for a given name within this
// process, so the ingress paced body-reader (US7) and the WS chunk pacing (US6) draw from ONE budget
// when co-located on the same replica. Entries idle longer than the idle window are evicted (bounded
// memory across ephemeral tunnel names); a re-created bucket starts full (a one-off burst, not a
// leak).
//
// Cross-replica exactness is deliberately NOT attempted (user decision) — a distributed bucket would
// put a synchronous Redis call on the data plane per 32 KiB slice.
type BucketRegistry struct {
	mu   sync.Mutex
	m    map[string]*bucketEntry
	bps  int64
	idle time.Duration
	now  func() time.Time
}

type bucketEntry struct {
	up, down   *TokenBucket
	lastAccess time.Time
}

// NewBucketRegistry builds a registry minting buckets at bytesPerSec, using the real clock and the
// default idle-eviction window.
func NewBucketRegistry(bytesPerSec int64) *BucketRegistry {
	return newBucketRegistry(bytesPerSec, defaultBucketIdle, time.Now)
}

func newBucketRegistry(bytesPerSec int64, idle time.Duration, now func() time.Time) *BucketRegistry {
	return &BucketRegistry{
		m:    map[string]*bucketEntry{},
		bps:  bytesPerSec,
		idle: idle,
		now:  now,
	}
}

// Pair returns the SAME (up, down) bucket instances for name within this process, creating them on
// demand. It lazily evicts entries idle past the idle window on each call.
func (r *BucketRegistry) Pair(name string) (up, down *TokenBucket) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.now()
	for k, e := range r.m {
		if now.Sub(e.lastAccess) > r.idle {
			delete(r.m, k)
		}
	}
	e, ok := r.m[name]
	if !ok {
		u, d := NewTunnelBandwidth(r.bps)
		e = &bucketEntry{up: u, down: d}
		r.m[name] = e
	}
	e.lastAccess = now
	return e.up, e.down
}
