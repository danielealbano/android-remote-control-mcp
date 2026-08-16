package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/alecthomas/kong"
)

// validCfg returns a ServeCmd that passes Validate; individual tests copy and mutate one field.
func validCfg(t *testing.T) ServeCmd {
	t.Helper()
	dir := t.TempDir()
	cert := filepath.Join(dir, "ca.pem")
	key := filepath.Join(dir, "ca-key.pem")
	if err := os.WriteFile(cert, []byte("ca-cert"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(key, []byte("ca-key"), 0o600); err != nil {
		t.Fatal(err)
	}
	return ServeCmd{
		Listen:              ":8080",
		InternalListen:      ":9090",
		TunnelDomain:        "example.test",
		EnrollHost:          "enroll.example.test",
		NamePrefix:          "",
		NameLength:          10,
		RedisURL:            "redis://localhost:6379/0",
		CACert:              cert,
		CAKey:               key,
		CertValidity:        87600 * time.Hour,
		ConnectAuthTimeout:  5 * time.Second,
		ClientIPHeader:      "X-Real-Ip",
		RouteTTL:            30 * time.Second,
		BanPoll:             10 * time.Second,
		LimitBandwidth:      "1mbit",
		LimitRPS:            10,
		LimitRPM:            100,
		LimitConcurrent:     4,
		LimitConnectPending: 64,
		LimitBody:           "1mb",
		LimitResponse:       "10mb",
		LimitHeaders:        "16kb",
		LimitHeaderSingle:   "8kb",
		LimitRequestTimeout: 60 * time.Second,
		LimitEnrollHour:     20,
		LimitEnrollMinute:   2,
		LimitEnrollBody:     "16kb",
		PingInterval:        30 * time.Second,
		ShutdownGrace:       15 * time.Second,
		Log:                 []string{"output=std;level=info"},
	}
}

func TestValidateAcceptsValidConfig(t *testing.T) {
	if err := validCfg(t).Validate(); err != nil {
		t.Fatalf("valid config rejected: %v", err)
	}
}

func TestValidateRejectsBadNameLength(t *testing.T) {
	for _, n := range []int{0, 5, 33, 64} {
		c := validCfg(t)
		c.NameLength = n
		if err := c.Validate(); err == nil {
			t.Errorf("name-length %d expected error", n)
		}
	}
}

func TestValidateRejectsOversizeLabel(t *testing.T) {
	c := validCfg(t)
	c.NamePrefix = string(make([]byte, 60)) // 60 + 10 > 63
	c.NameLength = 10
	if err := c.Validate(); err == nil {
		t.Error("prefix+name-length > 63 expected error")
	}
}

func TestValidateRejectsUnreadableCAPaths(t *testing.T) {
	c := validCfg(t)
	c.CACert = ""
	if err := c.Validate(); err == nil {
		t.Error("empty --ca-cert expected error")
	}
	c = validCfg(t)
	c.CAKey = filepath.Join(t.TempDir(), "does-not-exist.pem")
	if err := c.Validate(); err == nil {
		t.Error("missing --ca-key file expected error")
	}
}

func TestValidateRequiresClientIPHeader(t *testing.T) {
	c := validCfg(t)
	c.ClientIPHeader = ""
	if err := c.Validate(); err == nil {
		t.Error("empty --client-ip-header expected error (mandatory, no default)")
	}
}

func TestValidateRequiresBandwidthFloor(t *testing.T) {
	// Below the 32768 B/s floor must fail (128kbit=16000, 256kbit=32000 — decimal bits).
	for _, bw := range []string{"128kbit", "256kbit"} {
		c := validCfg(t)
		c.LimitBandwidth = bw
		if err := c.Validate(); err == nil {
			t.Errorf("--limit-bandwidth %s expected error (below floor)", bw)
		}
	}
	// At/above the floor must pass (300kbit=37500, 1mbit=125000).
	for _, bw := range []string{"300kbit", "1mbit"} {
		c := validCfg(t)
		c.LimitBandwidth = bw
		if err := c.Validate(); err != nil {
			t.Errorf("--limit-bandwidth %s expected pass, got %v", bw, err)
		}
	}
}

func TestValidateRejectsZeroIntegerLimits(t *testing.T) {
	mut := []func(*ServeCmd){
		func(c *ServeCmd) { c.LimitConcurrent = 0 },
		func(c *ServeCmd) { c.LimitConnectPending = 0 },
		func(c *ServeCmd) { c.LimitRPS = 0 },
		func(c *ServeCmd) { c.LimitRPM = 0 },
		func(c *ServeCmd) { c.LimitEnrollHour = 0 },
		func(c *ServeCmd) { c.LimitEnrollMinute = 0 },
	}
	for i, m := range mut {
		c := validCfg(t)
		m(&c)
		if err := c.Validate(); err == nil {
			t.Errorf("case %d: zero integer limit expected error", i)
		}
	}
}

func TestValidateRejectsCloudflareIncompatibleDurations(t *testing.T) {
	c := validCfg(t)
	c.PingInterval = 120 * time.Second
	if err := c.Validate(); err == nil {
		t.Error("--ping-interval 120s expected error (> 90s)")
	}
	c = validCfg(t)
	c.LimitRequestTimeout = 120 * time.Second
	if err := c.Validate(); err == nil {
		t.Error("--limit-request-timeout 120s expected error (>= 100s)")
	}
}

func TestValidateRejectsBadRedisLimitsLog(t *testing.T) {
	c := validCfg(t)
	c.RedisURL = "://not-a-url"
	if err := c.Validate(); err == nil {
		t.Error("unparseable --redis-url expected error")
	}
	c = validCfg(t)
	c.LimitBody = "bogus"
	if err := c.Validate(); err == nil {
		t.Error("bad --limit-body expected error")
	}
	c = validCfg(t)
	c.Log = []string{"output=std;level=nonsense"}
	if err := c.Validate(); err == nil {
		t.Error("bad --log spec expected error")
	}
}

// TestEnvTwinOverridesFlag exercises one env twin per kong type category (DefaultEnvars): int, size
// string, duration, repeatable []string, plain string — backing the "every flag has a twin" AC.
func TestEnvTwinOverridesFlag(t *testing.T) {
	dir := t.TempDir()
	cert := filepath.Join(dir, "ca.pem")
	key := filepath.Join(dir, "ca-key.pem")
	if err := os.WriteFile(cert, []byte("c"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(key, []byte("k"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TUNNELD_CA_CERT", cert)
	t.Setenv("TUNNELD_CA_KEY", key)
	t.Setenv("TUNNELD_CLIENT_IP_HEADER", "X-Real-Ip") // plain string twin
	t.Setenv("TUNNELD_LIMIT_RPS", "42")               // int twin
	t.Setenv("TUNNELD_LIMIT_BODY", "2mb")             // size string twin
	t.Setenv("TUNNELD_PING_INTERVAL", "45s")          // duration twin
	t.Setenv("TUNNELD_LOG", "output=std;level=warn")  // repeatable []string twin

	var cli struct {
		Serve ServeCmd `cmd:""`
	}
	parser, err := kong.New(&cli, kong.Name("tunneld"), kong.DefaultEnvars("TUNNELD"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parser.Parse([]string{"serve"}); err != nil {
		t.Fatalf("parse with env twins failed: %v", err)
	}
	if cli.Serve.ClientIPHeader != "X-Real-Ip" {
		t.Errorf("client-ip-header twin = %q", cli.Serve.ClientIPHeader)
	}
	if cli.Serve.LimitRPS != 42 {
		t.Errorf("limit-rps twin = %d", cli.Serve.LimitRPS)
	}
	if cli.Serve.LimitBody != "2mb" {
		t.Errorf("limit-body twin = %q", cli.Serve.LimitBody)
	}
	if cli.Serve.PingInterval != 45*time.Second {
		t.Errorf("ping-interval twin = %s", cli.Serve.PingInterval)
	}
	if len(cli.Serve.Log) != 1 || cli.Serve.Log[0] != "output=std;level=warn" {
		t.Errorf("log twin = %v", cli.Serve.Log)
	}
}
