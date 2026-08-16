package metrics

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/admin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
)

// AdminSource is the read side of the per-tunnel counters for /admin/tunnels.
type AdminSource interface {
	TopN(ctx context.Context, n int) ([]admin.TunnelStat, error)
}

// Handler builds the internal listener mux: /metrics (custom registry), /healthz (Redis PING), and
// /admin/tunnels (top-N JSON). This listener is NEVER proxied.
func Handler(reg *prometheus.Registry, rdb redis.UniversalClient, adminSrc AdminSource, log *slog.Logger) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(reg, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := rdb.Ping(r.Context()).Err(); err != nil {
			http.Error(w, "redis unavailable", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/admin/tunnels", func(w http.ResponseWriter, r *http.Request) {
		stats, err := adminSrc.TopN(r.Context(), 100)
		if err != nil {
			log.Warn("admin topN failed", "err", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(stats)
	})
	return mux
}
