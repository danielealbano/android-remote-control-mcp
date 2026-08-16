package transport

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
	"github.com/redis/go-redis/v9"
)

func newTestRedis(t *testing.T) (*redis.Client, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return rdb, mr
}

// startResponder subscribes to req:{node} (confirmed before returning) and, per request, publishes
// whatever mkResp returns to resp:{channelReqID}. channelReqID lets a test publish to the correct
// channel with a mismatched payload reqid.
func startResponder(t *testing.T, ctx context.Context, rdb redis.UniversalClient, node string, mkResp func(*wire.ReqEnvelope) (channelReqID string, resp *wire.RespEnvelope)) {
	t.Helper()
	pubsub := rdb.Subscribe(ctx, "req:"+node)
	if _, err := pubsub.Receive(ctx); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pubsub.Close() })
	go func() {
		for msg := range pubsub.Channel() {
			req, err := wire.UnmarshalReq([]byte(msg.Payload))
			if err != nil {
				continue
			}
			ch, resp := mkResp(req)
			if resp != nil {
				_ = rdb.Publish(ctx, "resp:"+ch, wire.MarshalResp(resp)).Err()
			}
		}
	}()
}

func TestRoundtripReturnsResponse(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := context.Background()
	startResponder(t, ctx, rdb, "nodeA", func(req *wire.ReqEnvelope) (string, *wire.RespEnvelope) {
		return req.ReqID, &wire.RespEnvelope{ReqID: req.ReqID, Status: 200, Body: []byte("pong")}
	})
	resp, err := RoundTrip(ctx, rdb, "nodeA", &wire.ReqEnvelope{ReqID: "r1", Node: "nodeA"}, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if resp.Status != 200 || string(resp.Body) != "pong" {
		t.Errorf("resp = %+v", resp)
	}
}

func TestRoundtripTimesOutToErrTimeout(t *testing.T) {
	rdb, _ := newTestRedis(t)
	resp, err := RoundTrip(context.Background(), rdb, "deadnode", &wire.ReqEnvelope{ReqID: "r2"}, 200*time.Millisecond)
	if resp != nil || err != ErrTimeout {
		t.Errorf("got (%v, %v), want (nil, ErrTimeout)", resp, err)
	}
}

func TestRoundtripClosesSubscriptionOnTimeout(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := context.Background()
	_, _ = RoundTrip(ctx, rdb, "deadnode", &wire.ReqEnvelope{ReqID: "r3"}, 150*time.Millisecond)
	// No subscriber should remain on resp:r3.
	deadline := time.Now().Add(time.Second)
	for {
		res, err := rdb.PubSubNumSub(ctx, "resp:r3").Result()
		if err == nil && res["resp:r3"] == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("subscription leaked on timeout: %v (err %v)", res, err)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestServeNodeRecordsPublishError(t *testing.T) {
	rdb, mr := newTestRedis(t)
	rec := &tunneltest.Recorder{}
	mr.Close() // subsequent Publish fails
	serveOne(context.Background(), rdb, time.Second, rec,
		func(ctx context.Context, req *wire.ReqEnvelope) *wire.RespEnvelope {
			return &wire.RespEnvelope{ReqID: req.ReqID, Status: 200}
		},
		string(wire.MarshalReq(&wire.ReqEnvelope{ReqID: "r4"})),
	)
	if got := rec.Count("publisherror", ""); got != 1 {
		t.Errorf("publisherror count = %d, want 1", got)
	}
}

func TestRoundtripIgnoresForeignReqid(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := context.Background()
	// Publish to the CORRECT channel (resp:r5) but with a MISMATCHED payload reqid.
	startResponder(t, ctx, rdb, "nodeF", func(req *wire.ReqEnvelope) (string, *wire.RespEnvelope) {
		return req.ReqID, &wire.RespEnvelope{ReqID: "DIFFERENT", Status: 500}
	})
	resp, err := RoundTrip(ctx, rdb, "nodeF", &wire.ReqEnvelope{ReqID: "r5"}, 400*time.Millisecond)
	if resp != nil || err != ErrTimeout {
		t.Errorf("foreign reqid must be ignored (timeout expected), got (%v, %v)", resp, err)
	}
}

func TestSubscribeBeforePublish(t *testing.T) {
	rdb, _ := newTestRedis(t)
	ctx := context.Background()
	// The responder publishes the instant the request lands; if RoundTrip did not subscribe BEFORE
	// publishing the request, this response would be lost.
	startResponder(t, ctx, rdb, "nodeS", func(req *wire.ReqEnvelope) (string, *wire.RespEnvelope) {
		return req.ReqID, &wire.RespEnvelope{ReqID: req.ReqID, Status: 204}
	})
	resp, err := RoundTrip(ctx, rdb, "nodeS", &wire.ReqEnvelope{ReqID: "r6"}, 2*time.Second)
	if err != nil || resp.Status != 204 {
		t.Fatalf("subscribe-before-publish failed: resp=%v err=%v", resp, err)
	}
}
