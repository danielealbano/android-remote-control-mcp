package metrics

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/admin"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/caplog"
	"github.com/redis/go-redis/v9"
)

func discardLog() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func setup(t *testing.T) (*Metrics, *PromRecorder, *admin.Store, *miniredis.Miniredis, *redis.Client) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	m := NewMetrics()
	store := admin.NewStore(rdb, time.Hour)
	rec := NewPromRecorder(m, caplog.New(discardLog()), store)
	return m, rec, store, mr, rdb
}

func TestHealthz200WhenRedisUp(t *testing.T) {
	m, _, store, _, rdb := setup(t)
	h := Handler(m.Registry(), rdb, store, discardLog())
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/healthz", nil))
	if rr.Code != http.StatusOK {
		t.Errorf("healthz = %d, want 200", rr.Code)
	}
}

func TestHealthz503WhenRedisDown(t *testing.T) {
	m, _, store, mr, rdb := setup(t)
	mr.Close()
	h := Handler(m.Registry(), rdb, store, discardLog())
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/healthz", nil))
	if rr.Code != http.StatusServiceUnavailable {
		t.Errorf("healthz (redis down) = %d, want 503", rr.Code)
	}
}

func TestMetricsEndpointExposesFamilies(t *testing.T) {
	m, rec, store, _, rdb := setup(t)
	rec.WSConnect()
	rec.Reject("rate_rps", "t", "1.1.1.1")
	h := Handler(m.Registry(), rdb, store, discardLog())
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/metrics", nil))
	body := rr.Body.String()
	for _, name := range []string{
		"tunneld_tunnels_connected", "tunneld_ws_connects_total",
		"tunneld_rejections_total", "tunneld_bytes_total", "tunneld_http_requests_total",
	} {
		if !strings.Contains(body, name) {
			t.Errorf("/metrics missing %s", name)
		}
	}
}

func TestNoPerTunnelMetricLabels(t *testing.T) {
	m, rec, store, _, rdb := setup(t)
	rec.Request("secret-tunnel-name", "mcp", 200, time.Millisecond)
	rec.Bytes("secret-tunnel-name", "in", 100)
	h := Handler(m.Registry(), rdb, store, discardLog())
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/metrics", nil))
	body := rr.Body.String()
	if strings.Contains(body, `tunnel="`) || strings.Contains(body, `name="`) {
		t.Error("metrics MUST NOT carry per-tunnel labels (cardinality guard)")
	}
	if strings.Contains(body, "secret-tunnel-name") {
		t.Error("tunnel name leaked into metrics")
	}
}

func TestRejectionIncrementsReasonCounter(t *testing.T) {
	m, rec, store, _, rdb := setup(t)
	rec.Reject("concurrency", "t", "1.1.1.1")
	h := Handler(m.Registry(), rdb, store, discardLog())
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/metrics", nil))
	if !strings.Contains(rr.Body.String(), `tunneld_rejections_total{reason="concurrency"} 1`) {
		t.Errorf("rejection counter not incremented:\n%s", rr.Body.String())
	}
}

func TestPromRecorderFlushesTcnt(t *testing.T) {
	_, rec, store, _, _ := setup(t)
	rec.Request("tunA", "mcp", 200, time.Millisecond)
	rec.Bytes("tunA", "in", 100)
	rec.Bytes("tunA", "out", 50)
	rec.flush(context.Background()) // the real async write path, driven synchronously in the test
	stats, err := store.TopN(context.Background(), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(stats) != 1 || stats[0].Name != "tunA" {
		t.Fatalf("expected tunA stats, got %+v", stats)
	}
	if stats[0].Requests != 1 || stats[0].BytesIn != 100 || stats[0].BytesOut != 50 {
		t.Errorf("flushed counters wrong: %+v", stats[0])
	}
}
