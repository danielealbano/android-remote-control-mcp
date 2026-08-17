package limit

import (
	"context"
	"fmt"
	"net/netip"
	"time"

	"github.com/redis/go-redis/v9"
)

// nowFunc is the clock used to compute wall-clock-aligned window boundaries. It is a package var
// only so the window unit tests can FREEZE time (a burst that straddles a real second/minute
// boundary would non-deterministically roll the window key). Production always uses time.Now.
// The limit tests that override it never run in parallel.
var nowFunc = time.Now

// allowScript atomically increments the window bucket and sets its TTL on the first hit (so a key is
// never left un-TTL'd). Returns the post-increment count.
var allowScript = redis.NewScript(`
local c = redis.call('INCR', KEYS[1])
if c == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return c
`)

// Allow increments the wall-clock-aligned window bucket for (scope, ip) and reports allow/deny plus
// the Retry-After to the window boundary. Key: "rl:{scope}:{ip}:{windowStartUnix}"; the INCR and its
// PEXPIRE(window*2) are a single Lua script (atomic — no un-TTL'd key).
func Allow(ctx context.Context, rdb redis.UniversalClient, scope string, ip netip.Addr, limit int, window time.Duration) (allowed bool, retryAfter time.Duration, err error) {
	now := nowFunc()
	winStart := now.Truncate(window)
	key := fmt.Sprintf("rl:%s:%s:%d", scope, ip.String(), winStart.Unix())
	ttlMS := (window * 2).Milliseconds()

	count, err := allowScript.Run(ctx, rdb, []string{key}, ttlMS).Int64()
	if err != nil {
		return false, 0, err
	}
	if int(count) > limit {
		return false, winStart.Add(window).Sub(now), nil
	}
	return true, 0, nil
}
