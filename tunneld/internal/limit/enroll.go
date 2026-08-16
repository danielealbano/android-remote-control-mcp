package limit

import (
	"context"
	"net/netip"
	"time"

	"github.com/redis/go-redis/v9"
)

// AllowEnroll enforces the enrollment quota: perHour AND perMinute per source IP (fixed wall-clock
// windows). It denies if EITHER sub-limit trips; the returned Retry-After is the larger of the two.
func AllowEnroll(ctx context.Context, rdb redis.UniversalClient, ip netip.Addr, perHour, perMinute int) (allowed bool, retryAfter time.Duration, err error) {
	okHour, raHour, err := Allow(ctx, rdb, "enroll_h", ip, perHour, time.Hour)
	if err != nil {
		return false, 0, err
	}
	okMin, raMin, err := Allow(ctx, rdb, "enroll_m", ip, perMinute, time.Minute)
	if err != nil {
		return false, 0, err
	}
	if okHour && okMin {
		return true, 0, nil
	}
	return false, max(raHour, raMin), nil
}
