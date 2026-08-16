package ingress

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ban"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/clientip"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/limit"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
	"github.com/redis/go-redis/v9"
)

// EnrollHandler serves POST /enroll on the enroll host: ban check, enrollment quota, CSR → signed
// certificate. No Redis persistence of identity (only transient quota counters).
type EnrollHandler struct {
	ban *ban.Engine
	ca  *ca.CA
	rdb redis.UniversalClient
	cfg config.ServeCmd
	rec observ.Recorder
	log *slog.Logger

	bodyLimit int64
}

// NewEnrollHandler parses --limit-enroll-body and returns the handler.
func NewEnrollHandler(cfg config.ServeCmd, caObj *ca.CA, rdb redis.UniversalClient, banEng *ban.Engine,
	rec observ.Recorder, log *slog.Logger) (*EnrollHandler, error) {
	body, err := config.ParseByteSize(cfg.LimitEnrollBody)
	if err != nil {
		return nil, err
	}
	return &EnrollHandler{ban: banEng, ca: caObj, rdb: rdb, cfg: cfg, rec: rec, log: log, bodyLimit: body}, nil
}

type enrollResponse struct {
	Name           string `json:"name"`
	Hostname       string `json:"hostname"`
	ConnectURL     string `json:"connect_url"`
	CertificatePEM string `json:"certificate_pem"`
	ExpiresAt      int64  `json:"expires_at"`
}

func (h *EnrollHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), h.cfg.LimitRequestTimeout)
	defer cancel()
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(h.cfg.LimitRequestTimeout))

	ip, ok := clientip.TrustedIP(r, h.cfg.ClientIPHeader)
	if !ok {
		h.rec.Reject("missing_client_ip", "", "")
		http.Error(w, "missing client ip", http.StatusBadRequest)
		return
	}
	ipStr := ip.String()
	if src, banned := h.ban.Match(ip); banned {
		h.rec.Reject(src.Reason.String(), "", ipStr)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}

	allowed, retry, err := limit.AllowEnroll(ctx, h.rdb, ip, h.cfg.LimitEnrollHour, h.cfg.LimitEnrollMinute)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if !allowed {
		h.rec.Reject("enroll_rate", "", ipStr)
		secs := int(retry.Seconds())
		if secs < 1 {
			secs = 1
		}
		w.Header().Set("Retry-After", strconv.Itoa(secs))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusTooManyRequests)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"error":       "rate_limited",
			"message":     "you or your network enrolled too many times, retry in " + strconv.Itoa(secs) + " seconds",
			"retry_after": secs,
		})
		return
	}

	// Read the CSR body (bounded + deadline-guarded).
	limited := http.MaxBytesReader(w, r.Body, h.bodyLimit)
	type readRes struct {
		data []byte
		err  error
	}
	ch := make(chan readRes, 1)
	go func() {
		d, e := io.ReadAll(limited)
		ch <- readRes{d, e}
	}()
	var body []byte
	select {
	case <-ctx.Done():
		_ = r.Body.Close()
		h.rec.Reject("body_read_timeout", "", ipStr)
		http.Error(w, "request timeout", http.StatusRequestTimeout)
		return
	case res := <-ch:
		if res.err != nil {
			var mbe *http.MaxBytesError
			if errors.As(res.err, &mbe) {
				h.rec.Reject("enroll_body_too_large", "", ipStr)
				http.Error(w, "payload too large", http.StatusRequestEntityTooLarge)
				return
			}
			if errors.Is(res.err, context.DeadlineExceeded) || errors.Is(res.err, context.Canceled) {
				h.rec.Reject("body_read_timeout", "", ipStr)
				http.Error(w, "request timeout", http.StatusRequestTimeout)
				return
			}
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		body = res.data
	}

	block, _ := pem.Decode(body)
	if block == nil || block.Type != "CERTIFICATE REQUEST" {
		h.rec.Reject("enroll_malformed_csr", "", ipStr)
		http.Error(w, "malformed CSR", http.StatusBadRequest)
		return
	}

	name, err := ca.GenerateName(h.cfg.NamePrefix, h.cfg.NameLength)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	leafPEM, err := h.ca.SignCSR(block.Bytes, name)
	if err != nil {
		if errors.Is(err, ca.ErrUnsupportedKeyType) {
			h.rec.Reject("enroll_unsupported_key", "", ipStr)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "unsupported_key_type"})
			return
		}
		h.rec.Reject("enroll_malformed_csr", "", ipStr)
		http.Error(w, "malformed CSR", http.StatusBadRequest)
		return
	}

	hostname := name + "." + h.cfg.TunnelDomain
	h.rec.Enrollment()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(enrollResponse{
		Name:           name,
		Hostname:       hostname,
		ConnectURL:     "wss://" + hostname + "/connect",
		CertificatePEM: string(leafPEM),
		ExpiresAt:      time.Now().Add(h.cfg.CertValidity).Unix(),
	})
}
