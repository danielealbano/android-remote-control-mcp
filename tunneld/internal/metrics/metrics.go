// Package metrics registers the Prometheus metric families into a CUSTOM registry (never the default
// one), plus the internal listener (/metrics, /healthz, /admin/tunnels) and the PromRecorder that
// implements observ.Recorder. NO per-tunnel metric labels (cardinality).
package metrics

import "github.com/prometheus/client_golang/prometheus"

// Metrics holds the registered families and their custom registry.
type Metrics struct {
	reg *prometheus.Registry

	tunnelsConnected    prometheus.Gauge
	enrollments         prometheus.Counter
	wsConnects          prometheus.Counter
	wsDisconnects       *prometheus.CounterVec // {reason}
	httpRequests        *prometheus.CounterVec // {class, code}
	httpDuration        prometheus.Histogram
	httpInflight        prometheus.Gauge
	rejections          *prometheus.CounterVec // {reason}
	bytesTotal          *prometheus.CounterVec // {direction}
	pubsubPublishErrors prometheus.Counter
	requestTimeouts     prometheus.Counter
}

// NewMetrics registers every family into a fresh registry.
func NewMetrics() *Metrics {
	reg := prometheus.NewRegistry()
	m := &Metrics{
		reg: reg,
		tunnelsConnected: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "tunneld_tunnels_connected", Help: "Currently connected tunnels.",
		}),
		enrollments: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "tunneld_enrollments_total", Help: "Total enrollments.",
		}),
		wsConnects: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "tunneld_ws_connects_total", Help: "Total WebSocket connects.",
		}),
		wsDisconnects: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tunneld_ws_disconnects_total", Help: "WebSocket disconnects by reason.",
		}, []string{"reason"}),
		httpRequests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tunneld_http_requests_total", Help: "Forwarded HTTP requests by class and code.",
		}, []string{"class", "code"}),
		httpDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name: "tunneld_http_request_duration_seconds", Help: "Forwarded request duration.",
			Buckets: prometheus.DefBuckets,
		}),
		httpInflight: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "tunneld_http_inflight", Help: "In-flight forwarded requests.",
		}),
		rejections: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tunneld_rejections_total", Help: "Rejections by reason.",
		}, []string{"reason"}),
		bytesTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "tunneld_bytes_total", Help: "Bridged bytes by direction.",
		}, []string{"direction"}),
		pubsubPublishErrors: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "tunneld_pubsub_publish_errors_total", Help: "Redis publish errors.",
		}),
		requestTimeouts: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "tunneld_request_timeouts_total", Help: "Request timeouts.",
		}),
	}
	reg.MustRegister(
		m.tunnelsConnected, m.enrollments, m.wsConnects, m.wsDisconnects,
		m.httpRequests, m.httpDuration, m.httpInflight, m.rejections,
		m.bytesTotal, m.pubsubPublishErrors, m.requestTimeouts,
	)
	return m
}

// Registry returns the custom registry (mounted at /metrics; the default registry exposes none of
// these families).
func (m *Metrics) Registry() *prometheus.Registry { return m.reg }
