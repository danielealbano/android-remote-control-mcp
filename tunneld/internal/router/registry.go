// Package router implements the cross-replica routing table (name→node, heartbeat TTL) over Redis.
// route:{name} stores {node, fingerprint, connID}; teardown/refresh are owner-conditional on the
// per-connection connID so same-node reconnects and cross-node re-binds are both safe.
package router

import (
	"context"
	"errors"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrNameHeldByOther is returned when a bind for name arrives with a fingerprint different from the
// one already stored (a name held by a different certificate).
var ErrNameHeldByOther = errors.New("router: name held by a different fingerprint")

// HeartbeatResult is the three-state outcome of Heartbeat.
type HeartbeatResult int

const (
	// HeartbeatRefreshed: still the owner, TTL refreshed.
	HeartbeatRefreshed HeartbeatResult = iota
	// HeartbeatNotOwner: route now points at a DIFFERENT connID (superseded).
	HeartbeatNotOwner
	// HeartbeatMissing: no route:{name} at all (TTL lapsed) — the caller self-heals by re-Binding.
	HeartbeatMissing
)

// Registry is the Redis-backed routing table.
type Registry struct {
	rdb redis.UniversalClient
	ttl time.Duration
}

// NewRegistry constructs a Registry; ttl is --route-ttl (the WS heartbeat refreshes at ttl/3).
func NewRegistry(rdb redis.UniversalClient, ttl time.Duration) *Registry {
	return &Registry{rdb: rdb, ttl: ttl}
}

// bindScript rejects a bind whose fingerprint differs from the stored one; otherwise sets
// {node, fingerprint, connID} and the TTL. A same-fingerprint rebind (any node/connID) overwrites.
var bindScript = redis.NewScript(`
local fp = redis.call('HGET', KEYS[1], 'fingerprint')
if fp and fp ~= ARGV[2] then
  return 'conflict'
end
redis.call('HSET', KEYS[1], 'node', ARGV[1], 'fingerprint', ARGV[2], 'connID', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 'ok'
`)

// heartbeatScript is owner-conditional on connID and reports refreshed | not-owner | missing.
var heartbeatScript = redis.NewScript(`
if redis.call('EXISTS', KEYS[1]) == 0 then
  return 'missing'
end
if redis.call('HGET', KEYS[1], 'connID') ~= ARGV[1] then
  return 'not-owner'
end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 'refreshed'
`)

// unbindScript deletes route:{name} ONLY if its stored connID still matches (never clobbers a route
// re-bound by a newer connection).
var unbindScript = redis.NewScript(`
if redis.call('HGET', KEYS[1], 'connID') == ARGV[1] then
  redis.call('DEL', KEYS[1])
end
return 'ok'
`)

func key(name string) string { return "route:" + name }

// Bind writes route:{name} with the node, fingerprint, and per-connection connID (fingerprint guard
// → ErrNameHeldByOther).
func (r *Registry) Bind(ctx context.Context, name, nodeID, fingerprint, connID string) error {
	res, err := bindScript.Run(ctx, r.rdb, []string{key(name)}, nodeID, fingerprint, connID, r.ttl.Milliseconds()).Text()
	if err != nil {
		return err
	}
	if res == "conflict" {
		return ErrNameHeldByOther
	}
	return nil
}

// Heartbeat refreshes route:{name} ONLY while it still belongs to connID, returning the three-state
// result.
func (r *Registry) Heartbeat(ctx context.Context, name, connID string) (HeartbeatResult, error) {
	res, err := heartbeatScript.Run(ctx, r.rdb, []string{key(name)}, connID, r.ttl.Milliseconds()).Text()
	if err != nil {
		return HeartbeatMissing, err
	}
	switch res {
	case "refreshed":
		return HeartbeatRefreshed, nil
	case "not-owner":
		return HeartbeatNotOwner, nil
	default:
		return HeartbeatMissing, nil
	}
}

// Unbind removes route:{name} ONLY while it still belongs to connID.
func (r *Registry) Unbind(ctx context.Context, name, connID string) error {
	return unbindScript.Run(ctx, r.rdb, []string{key(name)}, connID).Err()
}

// Lookup returns the node holding name AND the stored fingerprint (for the US7 ingress ban gate), or
// ok=false when no tunnel is bound.
func (r *Registry) Lookup(ctx context.Context, name string) (nodeID, fingerprint string, ok bool, err error) {
	vals, err := r.rdb.HMGet(ctx, key(name), "node", "fingerprint").Result()
	if err != nil {
		return "", "", false, err
	}
	node, _ := vals[0].(string)
	fp, _ := vals[1].(string)
	if node == "" {
		return "", "", false, nil
	}
	return node, fp, true, nil
}
