package limit

import (
	"context"
	"testing"
	"time"
)

func TestBandwidthPacesBytes(t *testing.T) {
	clock := newFakeClock()
	b := newTokenBucket(1000, clock.Now, clock.advancingSleep())
	ctx := context.Background()

	// First 1000 bytes come from the full initial burst — no wait.
	start := clock.Now()
	if err := b.WaitN(ctx, 1000); err != nil {
		t.Fatal(err)
	}
	if adv := clock.Now().Sub(start); adv != 0 {
		t.Errorf("first WaitN should not block, advanced %s", adv)
	}
	// The bucket is now empty; another 1000 bytes must pace ~1s at 1000 B/s.
	start = clock.Now()
	if err := b.WaitN(ctx, 1000); err != nil {
		t.Fatal(err)
	}
	adv := clock.Now().Sub(start)
	if adv < 900*time.Millisecond || adv > 1100*time.Millisecond {
		t.Errorf("second WaitN advanced %s, want ~1s", adv)
	}
}

func TestWaitNOverBurstErrorsImmediately(t *testing.T) {
	clock := newFakeClock()
	b := newTokenBucket(1000, clock.Now, clock.advancingSleep())
	ctx := context.Background()

	if err := b.WaitN(ctx, 1001); err != ErrBurstExceeded {
		t.Fatalf("WaitN(burst+1) = %v, want ErrBurstExceeded", err)
	}
	// Acquiring the same total in ≤burst increments succeeds across fake-clock refills.
	for i := 0; i < 3; i++ {
		if err := b.WaitN(ctx, 1000); err != nil {
			t.Fatalf("chunk %d: %v", i, err)
		}
	}
}

func TestWaitNRespectsContextCancel(t *testing.T) {
	// A cancelled ctx makes WaitN return the ctx error instead of blocking forever.
	b := NewTokenBucket(1000)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := b.WaitN(ctx, 1000); err == nil {
		t.Error("WaitN under a cancelled ctx must return an error, not block")
	}
}
