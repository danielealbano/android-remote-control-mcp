package limit

import (
	"testing"
	"time"
)

func TestRegistryReturnsSamePairPerName(t *testing.T) {
	r := NewBucketRegistry(1000)
	up1, down1 := r.Pair("t")
	up2, down2 := r.Pair("t")
	if up1 != up2 || down1 != down2 {
		t.Error("Pair must return the SAME bucket instances for the same name")
	}
	upOther, _ := r.Pair("u")
	if upOther == up1 {
		t.Error("different names must get different buckets")
	}
}

func TestRegistryEvictsIdlePairs(t *testing.T) {
	clock := newFakeClock()
	r := newBucketRegistry(1000, 10*time.Minute, clock.Now)
	up1, _ := r.Pair("t")
	clock.Advance(11 * time.Minute) // idle past the eviction window
	up2, _ := r.Pair("t")
	if up1 == up2 {
		t.Error("an idle pair must be evicted; the next Pair must recreate a fresh bucket")
	}
}
