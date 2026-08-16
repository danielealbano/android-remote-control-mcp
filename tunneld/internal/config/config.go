// Package config defines the tunneld `serve` command's flag surface (kong) and its validation.
// Every flag has an automatic TUNNELD_* env twin via kong.DefaultEnvars("TUNNELD") in main.
package config

import (
	"fmt"
	"os"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/logging"
	"github.com/redis/go-redis/v9"
)

// bandwidthFloorBytesPerSec is the minimum accepted --limit-bandwidth in bytes/sec. It MUST equal
// wire.ChunkSize (US6.1, 32*1024): the bandwidth bucket's burst is one second of rate and every
// caller acquires tokens in ≤ChunkSize slices (US3.3), so a rate below one chunk would make every
// chunk acquisition error and silently break the whole data plane. wire is a later story, so the
// literal is duplicated here with this note rather than imported (no forward dependency).
const bandwidthFloorBytesPerSec int64 = 32 * 1024

// ServeCmd is the configuration surface of `tunneld serve`. kong derives the TUNNELD_* env twin for
// every flag from its name (main.go: kong.DefaultEnvars("TUNNELD")).
type ServeCmd struct {
	Listen         string `name:"listen" default:":8080" help:"Public HTTP listener (behind the proxy)."`
	InternalListen string `name:"internal-listen" default:":9090" help:"Metrics + /healthz + /admin listener; never proxied."`

	TunnelDomain string `name:"tunnel-domain" default:"example.test" help:"Base domain for <name>.<tunnel-domain> (one wildcard)."`
	EnrollHost   string `name:"enroll-host" default:"enroll.example.test" help:"Hostname carrying POST /enroll (name-independent)."`
	NamePrefix   string `name:"name-prefix" default:"" help:"Optional prefix on generated tunnel names."`
	NameLength   int    `name:"name-length" default:"10" help:"Random base32 chars in a generated name."`

	RedisURL string `name:"redis-url" default:"redis://localhost:6379/0" help:"Redis connection URL."`

	CACert       string        `name:"ca-cert" help:"Internal CA certificate (PEM). Required for serve."`
	CAKey        string        `name:"ca-key" help:"Internal CA private key (PEM). Required for serve."`
	CertValidity time.Duration `name:"cert-validity" default:"87600h" help:"Issued enrollment-cert lifetime."`

	ConnectAuthTimeout time.Duration `name:"connect-auth-timeout" default:"5s" help:"Max time to complete the /connect challenge-response before the WS is closed."`
	ClientIPHeader     string        `name:"client-ip-header" help:"MANDATORY (no default): header carrying the abuse-control IP. Cf-Connecting-Ip (Cloudflare orange) or X-Real-Ip (grey). Single value, or right-most token for X-Forwarded-For; NEVER the left-most."`

	RouteTTL time.Duration `name:"route-ttl" default:"30s" help:"Redis routing-entry TTL; the WS heartbeat refreshes it at route-ttl/3."`

	DBIPCountryLiteCSV string        `name:"dbip-country-lite-csv" default:"" help:"DB-IP Country Lite CSV for 'country' ban expansion (empty = geo off)."`
	BanFile            []string      `name:"ban-file" help:"Ban file(s); repeatable."`
	BanPoll            time.Duration `name:"ban-poll" default:"10s" help:"Ban/CSV mtime poll interval."`

	LimitBandwidth      string        `name:"limit-bandwidth" default:"1mbit" help:"Per-tunnel, per-direction rate; minimum 32768 B/s (~263kbit — DECIMAL bits, so 256kbit=32000 B/s is REJECTED)."`
	LimitRPS            int           `name:"limit-rps" default:"10" help:"Requests/sec per source IP."`
	LimitRPM            int           `name:"limit-rpm" default:"100" help:"Requests/min per source IP."`
	LimitConcurrent     int           `name:"limit-concurrent" default:"4" help:"In-flight requests per tunnel."`
	LimitConnectPending int           `name:"limit-connect-pending" default:"64" help:"Max concurrent pre-auth /connect handshakes per node."`
	LimitBody           string        `name:"limit-body" default:"1mb" help:"Max request body."`
	LimitResponse       string        `name:"limit-response" default:"10mb" help:"Max response size."`
	LimitHeaders        string        `name:"limit-headers" default:"16kb" help:"Max total request headers."`
	LimitHeaderSingle   string        `name:"limit-header-single" default:"8kb" help:"Max single request header."`
	LimitRequestTimeout time.Duration `name:"limit-request-timeout" default:"60s" help:"End-to-end request timeout."`
	LimitEnrollHour     int           `name:"limit-enroll-hour" default:"20" help:"Enrollments/hour per source IP."`
	LimitEnrollMinute   int           `name:"limit-enroll-minute" default:"2" help:"Enrollments/minute per source IP."`
	LimitEnrollBody     string        `name:"limit-enroll-body" default:"16kb" help:"Max enrollment (CSR) request body."`

	PingInterval  time.Duration `name:"ping-interval" default:"30s" help:"WS keepalive (native control ping) cadence; MUST stay under Cloudflare's 100s WS idle timeout."`
	ShutdownGrace time.Duration `name:"shutdown-grace" default:"15s" help:"Graceful-drain deadline on ctx cancel."`

	Log []string `name:"log" default:"output=std;level=info" help:"Repeatable composite log sink (output=std|/path;level=…;format=…;maxsize=…;maxfiles=…)."`
}

// Validate is invoked automatically by kong for the selected `serve` command. It enforces every
// cross-field and parseability invariant the plan requires before server.Run is reached.
func (c ServeCmd) Validate() error {
	if c.NameLength < 6 || c.NameLength > 32 {
		return fmt.Errorf("--name-length must be in [6,32], got %d", c.NameLength)
	}
	if len(c.NamePrefix)+c.NameLength > 63 {
		return fmt.Errorf("--name-prefix + --name-length must be ≤ 63 (DNS label limit), got %d", len(c.NamePrefix)+c.NameLength)
	}
	if err := checkPresent("--ca-cert", c.CACert); err != nil {
		return err
	}
	if err := checkPresent("--ca-key", c.CAKey); err != nil {
		return err
	}
	if c.ClientIPHeader == "" {
		return fmt.Errorf("--client-ip-header is mandatory (no default): set Cf-Connecting-Ip (Cloudflare orange) or X-Real-Ip (grey)")
	}
	if _, err := redis.ParseURL(c.RedisURL); err != nil {
		return fmt.Errorf("--redis-url is not parseable: %w", err)
	}
	if c.RouteTTL <= 0 {
		return fmt.Errorf("--route-ttl must be > 0, got %s", c.RouteTTL)
	}
	if c.ConnectAuthTimeout <= 0 {
		return fmt.Errorf("--connect-auth-timeout must be > 0, got %s", c.ConnectAuthTimeout)
	}
	if c.ShutdownGrace <= 0 {
		return fmt.Errorf("--shutdown-grace must be > 0, got %s", c.ShutdownGrace)
	}
	if c.PingInterval > 90*time.Second {
		return fmt.Errorf("--ping-interval must be ≤ 90s (under Cloudflare's 100s WS idle timeout), got %s", c.PingInterval)
	}
	if c.LimitRequestTimeout >= 100*time.Second {
		return fmt.Errorf("--limit-request-timeout must be < 100s (under Cloudflare's 524 timeout), got %s", c.LimitRequestTimeout)
	}
	for _, il := range []struct {
		name string
		v    int
	}{
		{"--limit-rps", c.LimitRPS},
		{"--limit-rpm", c.LimitRPM},
		{"--limit-concurrent", c.LimitConcurrent},
		{"--limit-connect-pending", c.LimitConnectPending},
		{"--limit-enroll-hour", c.LimitEnrollHour},
		{"--limit-enroll-minute", c.LimitEnrollMinute},
	} {
		if il.v < 1 {
			return fmt.Errorf("%s must be ≥ 1, got %d", il.name, il.v)
		}
	}
	bw, err := ParseBitrate(c.LimitBandwidth)
	if err != nil {
		return fmt.Errorf("--limit-bandwidth: %w", err)
	}
	if bw < bandwidthFloorBytesPerSec {
		return fmt.Errorf("--limit-bandwidth must be ≥ %d B/s (= wire.ChunkSize; note DECIMAL bits, 256kbit=32000 B/s fails), got %d B/s", bandwidthFloorBytesPerSec, bw)
	}
	for _, sz := range []struct {
		name string
		v    string
	}{
		{"--limit-body", c.LimitBody},
		{"--limit-response", c.LimitResponse},
		{"--limit-headers", c.LimitHeaders},
		{"--limit-header-single", c.LimitHeaderSingle},
		{"--limit-enroll-body", c.LimitEnrollBody},
	} {
		if _, err := ParseByteSize(sz.v); err != nil {
			return fmt.Errorf("%s: %w", sz.name, err)
		}
	}
	if err := logging.ParseSpecs(c.Log); err != nil {
		return fmt.Errorf("--log: %w", err)
	}
	return nil
}

// checkPresent ensures a mandatory path flag is non-empty and the file exists (fail-fast). The
// authoritative read + parse of the CA material happens in ca.Load (US4), which surfaces any
// permission/parse error at startup; here we only reject an empty or missing path early.
func checkPresent(flag, path string) error {
	if path == "" {
		return fmt.Errorf("%s is required for serve", flag)
	}
	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("%s is not accessible: %w", flag, err)
	}
	return nil
}
