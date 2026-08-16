package limit

import (
	"testing"
)

func TestEnrollDeniesWhenEitherSubLimitTrips(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := ctxT(t)
	// perHour=20 (room), perMinute=2 (the binding limit).
	for i := 0; i < 2; i++ {
		ok, _, err := AllowEnroll(ctx, rdb, testIP, 20, 2)
		if err != nil || !ok {
			t.Fatalf("enroll %d: ok=%v err=%v", i+1, ok, err)
		}
	}
	ok, retry, err := AllowEnroll(ctx, rdb, testIP, 20, 2)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("3rd enrollment in a minute must be denied even with hourly room")
	}
	if retry <= 0 {
		t.Errorf("retry-after must be positive, got %s", retry)
	}
}
