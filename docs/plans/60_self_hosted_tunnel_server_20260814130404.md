<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 60 — Self-Hosted Tunnel Server (`tunneld`)

## Purpose

A self-hosted, abuse-resistant HTTP tunnel that gives the Android MCP app a stable public
hostname for free. Phone opens an outbound WebSocket; the public web side is plain HTTP(S)
behind a TLS-terminating reverse proxy (Cloudflare orange-cloud reference); multiple frontends bridge
requests over Redis pub/sub. Identity is a CA-signed certificate the phone earns by enrollment (CSR →
signed by an internal CA); the phone authenticates each `/connect` at the APPLICATION layer over the
WebSocket (challenge-response proof-of-possession — NOT TLS mutual auth), so the tunnel works through
Cloudflare's proxy. Abuse is contained by layered caps (bandwidth, request rate, body/response/header
size, concurrency), a hot-reloadable ban engine (IP/CIDR/country/tunnel-name/fingerprint), and strict
ingress allowlisting to exactly the app's MCP + OAuth surface.

**Scope of THIS plan: server side only** — the Go `tunneld/` module (`tunneld` binary), the Go test
client, the wire-protocol spec, support scripts, the Docker/Compose deployment stack, testcontainers
e2e tests, and CI. The Android app integration (Kotlin WebSocket client, Android Keystore CSR
enrollment, settings UI, DataStore) is explicitly **out of scope** and will be a separate plan.

**Plan structure note:** Acceptance Criteria and Definition of Done are given per USER STORY
(aggregating all of that story's tasks), not per individual task — a deliberate, USER-APPROVED
structural choice for this plan (explicitly decided with the user during plan review; not an
oversight); each task lists its file(s) + actions, and the story's DoD is the gate for those tasks.

## Design Decisions (agreed with user)

**Topology & process**
- One binary `tunneld`, one subcommand `serve` (plus `version`). NO `web`/`tunnel`/`all` split — a
  single merged service plays every role; run N identical replicas. Redis pub/sub bridges replicas.
- Config via `kong` (github.com/alecthomas/kong); every flag has a `TUNNELD_*` env twin.
- Go module `github.com/danielealbano/android-remote-control-mcp/tunneld`, Go 1.26 toolchain.

**Hostnames (all are config values; repo uses placeholder `example.test`, real values live in a private `.env`)**
- Tunnels: `<name>.<tunnel-domain>` where `<name>` = 10 lowercase base32 chars (`[a-z2-7]`).
  `--name-prefix` defaults to empty (kept as a flag for optional future use); `--name-length`
  defaults to `10`.
- **`/connect` (phone WebSocket) lives on the per-tunnel host**: `wss://<name>.<tunnel-domain>/connect`.
  There is NO TLS mutual auth and therefore no per-SNI client-cert configuration — the client is
  authenticated at the application layer (see Identity). `/connect` is a RESERVED path (the app has no
  such route), so it never collides with forwarded public traffic on the same host; tunneld dispatches
  `/connect` to the WebSocket manager and everything else to the public ingress. tunneld additionally
  cross-checks that the Host's `<name>` equals the authenticated certificate's CN.
- **Enrollment** has its own host `<enroll-host>` (default `enroll.<tunnel-domain>`) because the phone
  has no name before enrollment: `POST https://<enroll-host>/enroll`.
- Everything sits under ONE wildcard `*.<tunnel-domain>` (covers the per-tunnel hosts and the enroll
  host). The previously-planned dedicated `tunnel.` host is DROPPED.
- Ops hostnames under a separate `<ops-domain>` (e.g. `grafana.<ops-domain>`).
- **Cloudflare orange-cloud is the reference deployment** (proxied `*.<tunnel-domain>`, available on
  all Cloudflare plans incl. Free). See Deployment for the orange-cloud requirements; grey-cloud
  (DNS-only, Traefik as the internet edge) is documented as the privacy-max alternative.

**Identity (CA + CSR enrollment; APPLICATION-LAYER challenge-response auth — NOT TLS mTLS)**
- Enrollment (unchanged): phone generates its EC (P-256) keypair and sends a CSR to `/enroll` over
  plain TLS. `tunneld` signs it with an internal tunnel CA, putting the assigned tunnel name in the
  certificate CN. Cert lifetime 10 years (`87600h`). Name = base32 of `crypto/rand` bytes (NOT a
  timestamp); no Redis uniqueness check (collision probability negligible).
- **`/connect` authentication is done at the application layer over the WebSocket — there is NO TLS
  client certificate.** This deliberate choice lets the `/connect` endpoint be proxied by Cloudflare
  (orange-cloud), which terminates TLS; Cloudflare-terminated mTLS cannot serve our per-phone-cert
  model (paid/Enterprise-gated). Flow:
  1. Phone opens `wss://<name>.<tunnel-domain>/connect` (ordinary WSS; Cloudflare-proxyable).
  2. tunneld ban-checks the source IP (see Header handling) FIRST.
  3. tunneld sends a fresh random **nonce** (32 bytes) as a `CHALLENGE` frame (per-connection,
     in-memory — no Redis).
  4. Phone replies with an `AUTH` frame: `{ cert: base64(DER), signature: ECDSA-P256-SHA256(context ‖
     nonce, privkey) }` where `context` is a fixed domain-separation label (e.g. `"tunneld-connect-v1"`).
  5. tunneld verifies: cert chains to the tunnel CA + not expired + CN present; the signature is valid
     for the cert's public key over `context ‖ nonce`; and CN == the Host's `<name>`. Any failure →
     close. A `--connect-auth-timeout` (default `5s`) bounds the pre-auth state.
  This is the app-layer equivalent of TLS `CertificateVerify`: possession of the private key is proven
  by signing a server-chosen fresh nonce, so sending the (public) certificate alone is NOT sufficient
  and a captured cert/signature cannot be replayed on a new connection.
- Runtime guard (unchanged intent): a `/connect` whose name is already bound to a **different**
  certificate fingerprint is rejected with a distinct error and logged loudly.
- Name generation skips a reserved-hostname set (kept generic because prefix/length are configurable);
  reserved labels include `enroll` and the ops labels.
- `--trusted-proxies` was considered and DROPPED — tunneld does not re-implement an upstream-CIDR
  allowlist; the trust boundary is enforced at the deployment layer (US13: Traefik `IPAllowList` of
  Cloudflare ranges for orange-cloud, and "never publish tunneld's port").

**No permanent Redis state — EVER.** Redis holds only: routing entries (`name → node`, heartbeat-
refreshed TTL), rate-limit/quota counters (TTL windows), per-tunnel live counters (TTL), and pub/sub
channels. The enrolled client certificate (held by the phone) is the ONLY persistent identity. There
is no database.

**Ingress allowlist (verified against the app's code — see references below)**
- Method+path allowlist, everything else rejected at the edge:
  - `POST /mcp`, `DELETE /mcp` — forwarded; NO edge Authorization check (**user decision**): the app
    itself enforces auth, and its token-less `401` carries the RFC 9728
    `WWW-Authenticate: Bearer resource_metadata="…"` discovery header that OAuth connectors
    (Claude.ai / `mcp-remote`) require to bootstrap — an edge `401` would swallow it and break the
    OAuth connect flow the tunnel exists to serve.
  - `GET /mcp` — answered `405` at the edge (SSE unsupported), never forwarded.
  - `OPTIONS` on any allowlisted path — forwarded (CORS preflight carries no Authorization).
  - Unauthenticated (no Authorization required): `POST /register`, `GET /authorize`,
    `GET /authorize/status`, `POST /token`, `GET /.well-known/oauth-protected-resource` (and
    `/{tail...}`), `GET /.well-known/oauth-authorization-server` (and `/{tail...}`),
    `GET /.well-known/openid-configuration`.
  - `GET /s/{token}` — unauthenticated; edge enforces the exact regex `^/s/[0-9a-f]{64}$`.
- `/connect` on a per-tunnel host is a RESERVED path handled by the WebSocket manager (phone side),
  NOT part of the public allowlist and never forwarded to the app.
- `/health` is NOT exposed through the tunnel.
- The app does not support client mTLS; any request carrying client-cert / mTLS-indicating headers
  (e.g. `X-Forwarded-Tls-Client-Cert`, `Ssl-Client-Cert`, `X-Client-Cert`) on the PUBLIC side is
  rejected with `400` (standing user requirement — reject these on purpose). This is independent of
  the tunnel's own application-layer `/connect` auth.

**Limits (defaults; all configurable, all under `--limit-*` / `TUNNELD_LIMIT_*`)**
- Bandwidth: `1mbit` per tunnel, per direction (two independent token buckets), enforced on the node
  holding the WebSocket AND on the public ingress body read (a paced reader draws from the SAME
  per-process bucket registry — TCP backpressure makes the client upload arrive at the paced rate
  instead of line-rate bursting into memory). Buckets are per-tunnel, per-PROCESS (user decision):
  a request landing on a replica that does not hold the WS paces against that process's own pair —
  worst-case aggregate ingress is replicas × rate, while true tunnel throughput stays exactly
  1 × rate (the WS leg on the owning node is the authoritative choke point). When the ingress
  replica IS the WS-owning node, the bytes are drawn from the shared bucket ONCE — at the paced
  ingress read; the WS write skips the duplicate drain (`ReqEnvelope.PacedByNode` guard, US6.2) so
  the same bytes are never paced twice against one bucket. Client-side EGRESS
  (writing the assembled response) is deliberately unpaced: it was already produced at the paced
  phone-leg rate; pacing it again would serialize two paced legs and double end-to-end time.
- Requests per source IP: `10`/second and `100`/minute (wall-clock-aligned fixed windows) → `429`
  with `Retry-After`.
- Request body `1mb`; response `10mb` (applies to ALL paths incl. `/s/`); total request headers
  `16kb` (single header `8kb`); request timeout `60s`.
- **Product decision (final)**: these caps deliberately exclude bulk transfers the app supports
  locally (e.g. `/s/` file shares above the response cap, or MCP tool calls with bodies above the
  body cap) — the tunnel is a free service for MCP control traffic, and the bandwidth cap × request
  timeout bounds a single request to a few MB regardless of the byte caps. Requests beyond the caps
  are refused with the standard `413`/`502` paths. There are NO per-path exceptions, NO ad-hoc
  higher caps, and NO special-case logic; operators may raise the uniform `--limit-*` values.
- Concurrency: `4` in-flight per tunnel → `429`.
- Enrollment per source IP: `20`/hour AND `2`/minute → `429` + `Retry-After` with a clear body.
- `/connect` attempts per source IP: DELIBERATELY reuses the `--limit-rpm` value with a 1-minute
  window (no separate knob — one less flag; the coupling with the public rpm limit is accepted).
- No idle disconnect. WebSocket keepalive pings are liveness only; a dead WS drops its routing entry.

**Ban & geo engine**
- One or more `--ban-file` (repeatable), hot-reloaded on mtime (poll `~10s`), entries UNIONed.
  Entry kinds: `ip`, `cidr`, `country XX`, `tunnel-name`, `tunnel-fingerprint`. `tunnel-name` and
  `tunnel-fingerprint` are the only revocation mechanism (no CRL). **`country` uses placeholder codes
  only in every repo artifact — no real country codes or names anywhere.**
- Geo: NO runtime mmdb. `country XX` entries are expanded at reload by reading a DB-IP Country Lite
  CSV (`--dbip-country-lite-csv`, optional, mtime-watched), converting the listed countries' start–end
  ranges to prefixes, and inserting them into the SAME longest-prefix-match table as `ip`/`cidr`
  entries → one lookup per request. If `country` entries exist but the CSV is missing/unreadable,
  those entries are skipped with a warning.
- The IP/CIDR/country table uses `github.com/gaissmai/bart` (ART-family LPM table over `netip`);
  reload builds a fresh table and swaps it in via an atomic pointer (lock-free hot path). Each entry's
  payload records its source/reason so metrics and logs know which layer fired.
- The ban check is the FIRST check on ALL ingress: public HTTP (source IP from the proxy-set
  forwarded header), `/enroll`, and `/connect`.

**Transport (Redis pub/sub)**
- Frontend node M receiving a public request: generate a request id, SUBSCRIBE to `resp:<reqid>`
  BEFORE publishing the request to the tunnel's request channel; on `--limit-request-timeout` with no
  response → `504`.
- The node N holding the WebSocket forwards the request to the phone over the WS, receives the
  response, and publishes it to `resp:<reqid>`.

**WebSocket wire protocol (phone ⇄ node N)** — binary frames (confirmed):
- Each WS binary message = `1-byte frame type` + `4-byte big-endian header length` + `header JSON`
  + `raw body bytes`. No base64 for request/response bodies (base64 would add ~33% under the
  bandwidth cap).
- Auth handshake frames (before any request/response traffic): `CHALLENGE` (server→phone; header JSON
  `{ "nonce": "<base64>" }`, no body) and `AUTH` (phone→server; header JSON
  `{ "cert": "<base64 DER>", "signature": "<base64>" }`, no body). These run once at connection start
  (see Identity); the certificate is carried here (base64 DER), NOT in a URL/query string.
- Request/response frames: `REQUEST_HEAD`, `REQUEST_BODY_CHUNK`, `REQUEST_END`, `RESPONSE_HEAD`, `RESPONSE_BODY_CHUNK`,
  `RESPONSE_END`, `ERROR`. Response bodies are chunked (32 KiB) so the bandwidth limiter can pace them.
- Keepalive uses the WebSocket library's native control pings, not app frames.
- A written protocol spec + golden-frame fixtures keep the Go and (future) Kotlin clients honest.
- WebSocket library: `github.com/coder/websocket` (context-first, MIT).

**Header handling**
- Public side strips client-supplied `X-Forwarded-*` / `Forwarded` and trusts only the proxy-set
  values; forwards proxy-set `X-Forwarded-Proto`/`Host`/`For` to the phone so its Ktor server sees
  HTTPS. Hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-*`, `TE`, `Trailer`,
  `Transfer-Encoding`, `Upgrade`) are stripped both directions.
- **Source-IP trust boundary (all abuse controls key on this).** The IP used for ban checks, per-IP
  rate limits, and enrollment quotas comes from the configured `--client-ip-header`, read as the
  RIGHT-MOST comma-separated token (a single value for `Cf-Connecting-Ip`/`X-Real-Ip`; the
  proxy-appended hop for `X-Forwarded-For`). tunneld MUST NEVER read the LEFT-MOST `X-Forwarded-For`
  entry (client-controlled). If the configured header is absent/empty on a request, reject `400`
  (fail-closed, reason `missing_client_ip`), never defaulted to a forgeable value.
- **`--client-ip-header` is MANDATORY and has NO default** — the operator MUST explicitly set it to
  match the deployment (`serve` refuses to start otherwise). This prevents an accidental wrong default
  from silently trusting the wrong header.
- **Cloudflare orange-cloud reference** → set `--client-ip-header = Cf-Connecting-Ip` (Cloudflare sets
  it to the real client IP). This is safe ONLY because the origin is reachable exclusively through
  Cloudflare (Deployment: Traefik IP-allowlist of Cloudflare ranges + Authenticated Origin Pulls) —
  Traefik does NOT strip forged `Cf-*` headers (they are not in its managed X-header set), so a
  directly-reachable origin would let anyone forge `Cf-Connecting-Ip`.
- **Grey-cloud alternative** (Traefik is the internet edge, `--client-ip-header = X-Real-Ip`): with NO
  `forwardedHeaders.trustedIPs`, Traefik deletes any client-supplied `X-Real-Ip`/`X-Forwarded-For` and
  repopulates them from the real TCP peer, so `X-Real-Ip` is the real client and cannot be spoofed
  (empirically verified).
- This is consistent with the dropped `--trusted-proxies` decision: tunneld trusts the proxy because
  the proxy is the only reachable upstream — it does not re-introduce an upstream-CIDR allowlist in
  tunneld. `--client-ip-header` is a single flag that selects the trusted header per deployment; the
  compose/e2e configs set it appropriately (Cf-Connecting-Ip for the orange reference; X-Real-Ip for
  the Cloudflare-less e2e harness).

**Observability**
- Prometheus metrics on the INTERNAL listener only (never proxied). Counters resetting on restart is
  fine (`rate()`/`increase()` + TSDB). NO per-tunnel metric labels (cardinality).
- Internal listener also serves `GET /healthz` (200 if Redis reachable, else 503; consumed by the
  e2e harness readiness wait and operator probes — the distroless runtime image has no shell, so
  Compose `healthcheck:` directives are deliberately NOT used) and `GET /admin/tunnels` (live top-N
  per-tunnel counters, from Redis).
- NO periodic per-tunnel log summaries. Instead, a cap-hit event is logged only when a defense layer
  fires, deduplicated: log the first hit per `(tunnel, reason)` immediately, then at most one summary
  line per minute per `(tunnel, reason)` — prevents attacker-driven log flooding.
- Logging = `slog` with a fan-out handler; sinks configured by a repeatable composite `--log`
  (`TUNNELD_LOG`). Every deployment writes BOTH a rotated file (lumberjack, 50 MB × 20 = 1 GB) AND
  stdout (Compose json-file driver capped at `max-size 50m` / `max-file 1`). `std` splits by severity
  (info+ → stdout, warn+ → stderr).

**Deployment**
- **Cloudflare (reference = orange-cloud / proxied `*.<tunnel-domain>`).** Verified viable on
  Cloudflare Free: proxied wildcard is available on all plans; WebSocket is supported on all plans.
  Hard constraints this imposes on tunneld config:
  - Cloudflare's WebSocket idle timeout is **100 s** on Free/Pro → `--ping-interval` MUST stay well
    under 100 s (default `30 s`) or idle tunnels are dropped every 100 s. tunneld `Validate()` enforces
    `--ping-interval ≤ 90s`.
  - Cloudflare's origin response (524) timeout is **100 s** → `--limit-request-timeout` MUST stay under
    100 s (default `60 s`). `Validate()` enforces `< 100s`.
  - Request body ≤ 100 MB (Cloudflare) — our `--limit-body` `1mb` is far under.
  - Edge certificate: `<name>.<tunnel-domain>` is two labels deep, which free Universal SSL does not
    cover; the operator uses Cloudflare **Advanced Certificate Manager** for the `*.<tunnel-domain>`
    edge cert (documented; a separate Cloudflare zone for `<tunnel-domain>` is the free alternative).
  - **Origin trust:** with orange-cloud, the origin (Traefik) MUST be reachable ONLY from Cloudflare —
    Traefik IP-allowlist middleware of Cloudflare's published IP ranges AND/OR Cloudflare Authenticated
    Origin Pulls (mTLS Cloudflare→origin) — so `Cf-Connecting-Ip` is trustworthy. This is the
    orange-cloud equivalent of "never publish tunneld's port."
  - Grey-cloud (DNS-only) is documented as the privacy-max alternative (`--client-ip-header=X-Real-Ip`,
    Traefik is the internet edge); no Cloudflare in the traffic path.
- `docker-compose.yml` (in `tunneld/deploy/`): `traefik`, `tunneld-1`/`tunneld-2` (two explicit replica services via a shared YAML anchor; scale by copying the block), `redis`, `prometheus`,
  `grafana`, `alertmanager`, `ntfy` (dedicated self-hosted instance), `ntfy-alertmanager` bridge, and
  a `fetcher` service. Grafana/Prometheus/Alertmanager sit behind the proxy's basic-auth middleware;
  `ntfy` is exposed with its own built-in auth (deny-by-default + tokens) so the ntfy Android app can
  subscribe. Traefik does plain TLS routing only (origin cert) — NO client-cert/mTLS config
  (authentication is app-layer over `/connect`).
- The `fetcher` service uses a STOCK image with a Compose `command:` that (a) installs packages
  (`apk add`), (b) writes the crontab (droplist daily, DB-IP daily — the DB-IP script no-ops the
  network when the current month's file already exists, giving one real download/month with daily
  self-heal), (c) runs both scripts ONCE at startup, then (d) runs `crond -f`. Scripts are mounted
  read-only from the repo; the ban-file directory is a shared volume. Handoff is atomic:
  write a temp file in the SAME mounted dir then `mv` (rename) — never `cp`. `tunneld` treats the
  droplist output as just another `--ban-file`.
- DB-IP Country Lite is CC-BY — the README MUST carry the attribution line.

**References the LLM MUST consult (do NOT re-derive)**
- Allowlist source of truth (app code):
  `app/.../mcp/McpServer.kt` (routing, auth-exclusion set),
  `app/.../mcp/McpStreamableHttpExtension.kt` (`/mcp` POST/GET/DELETE),
  `app/.../mcp/oauth/OAuthRoutes.kt` (OAuth endpoints),
  `app/.../mcp/Cors.kt` (permissive CORS / OPTIONS),
  `app/.../services/sharing/CapabilityToken.kt` (64-lowercase-hex `/s/` token),
  `app/.../services/sharing/EphemeralFileLinkService.kt` (`PATH_PREFIX = "/s/"` and the `/s/{token}` route registration).

---

## User Story 1: Go module scaffold, configuration (kong), and logging

Create the `tunneld/` Go module, the full configuration surface (kong flags + env twins), and the
`slog` fan-out logging with the repeatable composite `--log` sink. This is the foundation every later
story imports.

### Acceptance Criteria
- [x] `tunneld/go.mod` declares module `github.com/danielealbano/android-remote-control-mcp/tunneld`, Go 1.26.
- [x] `tunneld serve` and `tunneld version` parse; `serve` validates config then calls `server.Run` (a stub created here, fleshed out in US10 — so the module compiles from US1 with no forward dependency).
- [x] Every flag in the Config table has a working `TUNNELD_*` env twin.
- [x] `--log`/`TUNNELD_LOG` accepts repeated composite specs; `std` sinks split by severity (info+ → stdout, warn+ → stderr), file sinks are lumberjack-backed; default `output=std;level=info`.
- [x] `internal/observ` defines the `Recorder` interface + a `Nop` implementation (Task 1.5), with a compile-time assertion `var _ Recorder = Nop{}`, so US6/US7/US8 handlers depend on the interface, not the US9 metrics/caplog concrete types. `internal/tunneltest` provides the shared capturing `Recorder` fake (Task 1.5) used by US5/US6/US7/US8/US9/US10 tests.
- [x] `ParseByteSize`, `ParseBitrate`, `Validate()`, and the `slog` fan-out have unit-test tables (Task 1.6).
- [x] All US1 code + test tables are authored/committed in this story (the DoD is the gate; gate execution is in US16, per repo workflow).

### Task 1.1: Module + entrypoint + kong CLI
**File**: `tunneld/go.mod` — create
```
module github.com/danielealbano/android-remote-control-mcp/tunneld

go 1.26
```
`go.sum` MUST be generated (`go mod tidy`) and COMMITTED alongside every dependency addition — the
Dockerfile's `COPY go.mod go.sum ./` (US13.1) fails without it.
**File**: `tunneld/cmd/tunneld/main.go` — create
```go
package main

import (
	"context"
	"fmt"
	"os/signal"
	"syscall"

	"github.com/alecthomas/kong"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/logging"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/server"
)

var version = "dev" // overridden via -ldflags at build time

// runServe is a seam so cmd/tunneld tests can assert CLI dispatch without a real server.
var runServe = server.Run

type CLI struct {
	Serve   config.ServeCmd `cmd:"" help:"Run the tunnel server."`
	Version struct{}        `cmd:"" help:"Print version and exit."`
}

func main() {
	var cli CLI
	kctx := kong.Parse(&cli,
		kong.Name("tunneld"),
		kong.DefaultEnvars("TUNNELD"),
		kong.UsageOnError(),
	)
	switch kctx.Command() {
	case "version":
		fmt.Println("tunneld", version)
		return
	case "serve":
		logger, closeLogs, err := logging.New(cli.Serve.Log)
		kctx.FatalIfErrorf(err)
		defer func() { _ = closeLogs() }()
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		kctx.FatalIfErrorf(runServe(ctx, cli.Serve, logger, version))
	}
}
```
**File**: `tunneld/internal/server/server.go` — create a STUB in US1 so `main.go` compiles:
`func Run(ctx context.Context, cfg config.ServeCmd, logger *slog.Logger, version string) error { return errors.New("server.Run not yet implemented") }`. US10 Task 10.1 replaces the stub body with the real assembly. (This keeps US1 self-building — the `server` package exists from US1; US10 only fills it in, which is NOT a forward dependency.)
**Context**: `kong.DefaultEnvars("TUNNELD")` auto-derives the `TUNNELD_*` env twin for every flag from
its name — no per-flag `env:` tag needed. US1's tasks (`main.go`, `config`, `logging`, `server` stub,
`observ`) are authored together as one unit — `main.go` importing `config`/`logging` created in
Tasks 1.2/1.3 is within-story authoring order, NOT a cross-story forward dependency; the module builds
once all US1 tasks are committed (build EXECUTION happens in US16).

### Task 1.2: Config struct — the complete flag surface
**File**: `tunneld/internal/config/config.go` — create the `ServeCmd` struct with these fields (kong
tags: `help`, `default`, `placeholder`; time/size values are Go duration / byte-size strings):

| Field (flag) | Type | Default | Purpose |
|---|---|---|---|
| `--listen` | string | `:8080` | Public HTTP listener (behind proxy) |
| `--internal-listen` | string | `:9090` | Metrics + `/healthz` + `/admin`; never proxied |
| `--tunnel-domain` | string | `example.test` | Base domain for `<name>.<tunnel-domain>` (one wildcard) |
| `--enroll-host` | string | `enroll.example.test` | Hostname carrying `POST /enroll` (name-independent) |
| `--name-prefix` | string | `` (empty) | Optional prefix on generated names |
| `--name-length` | int | `10` | Random base32 chars in a name |
| `--redis-url` | string | `redis://localhost:6379/0` | Redis connection URL |
| `--ca-cert` | string (path) | *(required for serve)* | Internal CA certificate (PEM) |
| `--ca-key` | string (path) | *(required for serve)* | Internal CA private key (PEM) |
| `--cert-validity` | duration | `87600h` | Issued enrollment-cert lifetime |
| `--connect-auth-timeout` | duration | `5s` | Max time to complete the `/connect` challenge-response before the WS is closed |
| `--client-ip-header` | string | *(required for serve — NO default)* | Header for the abuse-control IP. Set `Cf-Connecting-Ip` (Cloudflare orange) or `X-Real-Ip` (grey/no-Cloudflare). Single value for those; right-most token for `X-Forwarded-For`. NEVER the left-most `X-Forwarded-For` |
| `--route-ttl` | duration | `30s` | Redis routing-entry TTL; the WS heartbeat refreshes it at `route-ttl/3` |
| `--dbip-country-lite-csv` | string (path) | `` (empty = geo off) | DB-IP Country Lite CSV for `country` expansion |
| `--ban-file` | []string | `[]` | Ban file(s); repeatable |
| `--ban-poll` | duration | `10s` | Ban/CSV mtime poll interval |
| `--limit-bandwidth` | string | `1mbit` | Per-tunnel, per-direction rate; minimum `32768` B/s = `wire.ChunkSize` (≈263 kbit — NOTE decimal bitrate units: `256kbit` is 32000 B/s and is REJECTED; enforced by `Validate()`, see US3.3) |
| `--limit-rps` | int | `10` | Requests/sec per source IP |
| `--limit-rpm` | int | `100` | Requests/min per source IP |
| `--limit-concurrent` | int | `4` | In-flight requests per tunnel |
| `--limit-connect-pending` | int | `64` | Max concurrent pre-auth `/connect` handshakes per node (in-process semaphore) |
| `--limit-body` | string | `1mb` | Max request body (chunked over the WS in ≤`ChunkSize` frames — no coupling between body size and any frame limit) |
| `--limit-response` | string | `10mb` | Max response size |
| `--limit-headers` | string | `16kb` | Max total request headers |
| `--limit-header-single` | string | `8kb` | Max single request header |
| `--limit-request-timeout` | duration | `60s` | End-to-end request timeout |
| `--limit-enroll-hour` | int | `20` | Enrollments/hour per source IP |
| `--limit-enroll-minute` | int | `2` | Enrollments/minute per source IP |
| `--limit-enroll-body` | string | `16kb` | Max enrollment (CSR) request body |
| `--ping-interval` | duration | `30s` | WS keepalive (native control ping) cadence; MUST stay under Cloudflare's 100 s WS idle timeout — distinct from `--route-ttl` |
| `--shutdown-grace` | duration | `15s` | Graceful-drain deadline on ctx cancel (`http.Server.Shutdown`); in-flight requests finish or are cut at this bound |
| `--log` | []string | `["output=std;level=info"]` | Repeatable composite log sink |

**Action**: also add `func (c ServeCmd) Validate() error` enforcing: `name-length` in `[6,32]`,
`len(name-prefix) + name-length ≤ 63` (DNS label limit), `ca-cert`/`ca-key` non-empty and readable,
`--client-ip-header` non-empty (MANDATORY, no default), `redis-url` parseable, `route-ttl` > 0,
`connect-auth-timeout` > 0, `shutdown-grace` > 0, `--ping-interval ≤ 90s` (stay
under Cloudflare's 100 s WS idle timeout), `--limit-request-timeout < 100s` (stay under Cloudflare's
524 timeout), the integer limits (`--limit-rps`, `--limit-rpm`, `--limit-concurrent`, `--limit-connect-pending`,
`--limit-enroll-hour`, `--limit-enroll-minute`) each `≥ 1` (a `0` concurrency limit would leave an
un-TTL'd Redis key — see US3 Task 3.2), `ParseBitrate(--limit-bandwidth) ≥ 32768` bytes/s (the literal `32768` MUST equal `wire.ChunkSize`
(US6.1), noted in a comment; the bucket `burst` is one second of rate and all callers acquire in
≤ ChunkSize slices per US3.3, so a lower rate would make every chunk acquisition error and silently
break the whole data plane. CAREFUL — `ParseBitrate` uses DECIMAL units: `256kbit` = 32000 B/s and
FAILS this floor; the minimum expressible round value is `~263kbit`), every `--limit-*`
size/duration/bitrate parses, and each `--log` spec parses (delegated to US1.3). kong invokes
`Validate()` automatically.

**Action**: add `internal/config/size.go` — `ParseByteSize(string) (int64, error)` using BINARY units
(`kb`=1024, `mb`=1024²): `1mb`=1048576, `10mb`=10485760, `16kb`=16384, `8kb`=8192; and
`ParseBitrate(string) (int64 bytesPerSec, error)` using DECIMAL bits (`mbit`=1e6 bits): `1mbit`=125000
bytes/sec. Bitrate uses decimal bits, byte-size uses binary bytes — keep them distinct functions.

### Task 1.3: slog fan-out + composite `--log`
**File**: `tunneld/internal/logging/logging.go` — create
```go
package logging

// New builds a *slog.Logger whose handler fans a record out to one sink per spec.
// spec grammar (semicolon-separated key=value; repeatable via multiple --log flags):
//   output=std | output=/path/to/file
//   level=debug|info|warn|error         (default info)
//   format=text|json                    (default: text for std, json for files)
//   maxsize=50m maxfiles=20             (file sinks only; lumberjack)
// std: each admitted record with level < warn -> stdout, level >= warn -> stderr (an EXACT split by
//   severity, NOT two overlapping min-level writers — a warn record goes ONLY to stderr, never stdout).
//   Files: lumberjack.Logger (min-level, single writer).
func New(specs []string) (logger *slog.Logger, closeAll func() error, err error)

// ParseSpecs validates the spec grammar WITHOUT side effects (no handlers built, no files opened) —
// this is what config.Validate() calls for the "--log spec parses" check.
func ParseSpecs(specs []string) error
```
**Context**: implement a `fanoutHandler` wrapping one child per spec. A child first applies its own
`level` as the admission floor (`Enabled`), then routes: a `std` child is a SPLIT handler that writes
each admitted record to stdout when `record.Level < slog.LevelWarn` and to stderr otherwise (exact
range, so `warn`/`error` never reach stdout); a file child writes every admitted record to its
`lumberjack.Logger` (`MaxSize` MB, `MaxBackups`, `Compress:false`). `closeAll` closes lumberjack
writers. Defaults when `specs` empty → single `output=std;level=info`.

### Task 1.4: Makefile targets
**File**: `tunneld/Makefile` — create targets: `build` (`go build -ldflags "-X main.version=$(VERSION)"
-o bin/tunneld ./cmd/tunneld`), `test` (`go test ./...`), `test-e2e` (`go test -tags=e2e -timeout
20m ./e2e/...`), `lint` (`golangci-lint run`), `tidy` (`go mod tidy`), `vet` (`go vet ./...`).
`VERSION ?= dev`.

### Task 1.5: Observability recorder interface (breaks the US7/US8/US6 → US9 forward dependency)
**File**: `tunneld/internal/observ/recorder.go` — create a dependency-free interface so the ingress
(US7), enroll (US8), and wsconn (US6) rejection/serve sites can record metrics + cap-hit logs WITHOUT
importing the Prometheus/caplog implementations (which are built in US9):
```go
type Recorder interface {
	Reject(reason, tunnelName, clientIP string)                  // tunneld_rejections_total{reason} + deduped cap-hit log
	Request(tunnelName, class string, code int, dur time.Duration) // tunneld_http_requests_total{class,code} + duration + tcnt:{name} requests++
	Bytes(tunnelName, direction string, n int64)                 // direction is "in" or "out" (NOT the up/down bucket names) → tunneld_bytes_total{direction} + tcnt:{name} bytes_in/out
	WSConnect(); WSDisconnect(reason string); Enrollment()
	InflightAdd(delta int)
	Timeout()       // tunneld_request_timeouts_total
	PublishError()  // tunneld_pubsub_publish_errors_total
}
```
`Request`/`Bytes` carry `tunnelName` precisely so the concrete `PromRecorder` (US9) can BOTH update
the (per-tunnel-label-free) Prometheus families AND write the per-tunnel `tcnt:{name}` Redis counters
that back `/admin/tunnels` — the callers (US6 manager for bytes, US7 handler for requests) already know
the name. Also provide `Nop` (a no-op `Recorder`) for unit tests, with a compile-time assertion
`var _ Recorder = Nop{}` (this is the mapping for the US1 recorder AC — no separate test needed).
US6/US7/US8 handlers take a `Recorder`; US9 supplies `PromRecorder` (metric registry + cap-hit logger
+ `admin.Store`) and `server.Run` (US10) injects it. `reason` values are the exact
`tunneld_rejections_total{reason}` label set defined in US9 Task 9.1.

**File**: `tunneld/internal/tunneltest/recorder.go` — the SHARED capturing `observ.Recorder` fake,
authored HERE (its first consumer is US5's transport tests; also reused by US6/US7/US8/US9/US10
tests). Full implementation:
```go
package tunneltest

import (
	"sync"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/observ"
)

type RecCall struct{ Kind, Reason, Tunnel, IP, Class, Direction string; Code int; N int64; Dur time.Duration }

// Recorder is a thread-safe capturing observ.Recorder for assertions.
type Recorder struct { mu sync.Mutex; Calls []RecCall }

var _ observ.Recorder = (*Recorder)(nil)

func (r *Recorder) add(c RecCall)                    { r.mu.Lock(); r.Calls = append(r.Calls, c); r.mu.Unlock() }
func (r *Recorder) Reject(reason, tunnel, ip string) { r.add(RecCall{Kind: "reject", Reason: reason, Tunnel: tunnel, IP: ip}) }
func (r *Recorder) Request(tunnel, class string, code int, d time.Duration) { r.add(RecCall{Kind: "request", Tunnel: tunnel, Class: class, Code: code, Dur: d}) }
func (r *Recorder) Bytes(tunnel, dir string, n int64) { r.add(RecCall{Kind: "bytes", Tunnel: tunnel, Direction: dir, N: n}) }
func (r *Recorder) WSConnect()                       { r.add(RecCall{Kind: "wsconnect"}) }
func (r *Recorder) WSDisconnect(reason string)       { r.add(RecCall{Kind: "wsdisconnect", Reason: reason}) }
func (r *Recorder) Enrollment()                      { r.add(RecCall{Kind: "enrollment"}) }
func (r *Recorder) InflightAdd(delta int)            { r.add(RecCall{Kind: "inflight", Code: delta}) }
func (r *Recorder) Timeout()                         { r.add(RecCall{Kind: "timeout"}) }
func (r *Recorder) PublishError()                    { r.add(RecCall{Kind: "publisherror"}) }

func (r *Recorder) Count(kind, reason string) int {
	r.mu.Lock(); defer r.mu.Unlock()
	n := 0
	for _, c := range r.Calls {
		if c.Kind == kind && (reason == "" || c.Reason == reason) { n++ }
	}
	return n
}
```

### Task 1.6: Unit tests
**File**: `tunneld/internal/config/size_test.go`, `tunneld/internal/config/config_test.go`, `tunneld/internal/logging/logging_test.go`, `tunneld/cmd/tunneld/main_test.go`

**Setup**: table-driven parse cases; `Validate()` on constructed `ServeCmd` values with temp CA files; the `slog` fan-out wired to in-memory buffers (and a temp file for the lumberjack sink); the `cmd/tunneld` dispatch test overrides the `runServe` seam with a fake that records its args (no real server).

| Test | Verifies |
|------|----------|
| `ParseByteSize valid units (binary)` | `1mb`→1048576, `10mb`→10485760, `16kb`→16384, `8kb`→8192 |
| `ParseByteSize rejects malformed` | Empty / bad suffix / negative → error |
| `ParseBitrate bits not bytes (decimal)` | `1mbit` → 125000 bytes/sec (1e6 bits/8); distinct from `ParseByteSize` |
| `Validate rejects bad name-length` | `<6` or `>32` → error |
| `Validate rejects oversize label` | `len(prefix)+name-length > 63` → error |
| `Validate rejects unreadable ca paths` | Missing `--ca-cert`/`--ca-key` → error |
| `Validate requires client-ip-header` | Empty `--client-ip-header` → error (mandatory, no default) |
| `Validate requires bandwidth floor` | `--limit-bandwidth 128kbit` AND `256kbit` (decimal → 32000 B/s, just under the 32768 floor) → error; `300kbit` (37500 B/s) and the default `1mbit` pass |
| `Validate rejects zero integer limits` | `--limit-concurrent 0`, `--limit-connect-pending 0` (and rps/rpm/enroll `<1`) → error (prevents un-TTL'd key / defeated semaphore) |
| `Validate rejects Cloudflare-incompatible durations` | `--ping-interval 120s` or `--limit-request-timeout 120s` → error (must stay under CF 100s) |
| `Validate rejects bad redis-url / limits / log` | Unparseable `--redis-url`, bad `--limit-*`, bad `--log` spec → error |
| `log fanout splits by severity` | `output=std`: `info` → stdout ONLY; `warn` → stderr ONLY (assert `warn` is ABSENT from the stdout buffer) |
| `log fanout writes file sink` | File spec creates a lumberjack-backed sink; record lands in the file |
| `log default when empty` | No `--log` → single `output=std;level=info` sink |
| `env twin overrides flag` | One env twin per flag CATEGORY overrides its default (kong `DefaultEnvars`): `TUNNELD_LIMIT_RPS` (int), `TUNNELD_LIMIT_BODY` (size string), `TUNNELD_PING_INTERVAL` (duration), `TUNNELD_LOG` (repeatable []string), `TUNNELD_CLIENT_IP_HEADER` (plain string) — representative coverage of every kong type used, backing the "every flag has a twin" AC |
| `cli dispatch + version` | `version` prints the version string; `serve` runs `Validate()` then calls the `runServe` seam (overridden by a fake in the test — asserts dispatch + args, no real server) |

### Definition of Done
- [x] `go.mod` + `go.sum` (committed), `main.go` (with the `server.Run` stub), config struct + `Validate()`, `ParseByteSize`/`ParseBitrate`, the `slog` fan-out, the Makefile, `internal/observ` (Recorder + Nop), and `internal/tunneltest` (capturing Recorder fake) all authored/committed.
- [x] The `--log` composite grammar (`output=std` and `output=/path;maxsize=…;maxfiles=…`) and severity split are specified and covered by the Task 1.6 test tables.
- [x] US1 test tables authored/committed for `size`, `config`, `logging`, and `cmd/tunneld` (the `runServe`-seam dispatch test) packages (execution deferred to US16, per repo workflow).

---

## User Story 2: Ban & geo enforcement engine

Build the longest-prefix-match ban engine (IP/CIDR/country/tunnel-name/tunnel-fingerprint) with
hot-reload and DB-IP CSV country expansion. Pure, network-free, fully unit-testable in isolation.

### Acceptance Criteria
- [x] Ban-file parser handles `ip`, `cidr`, `country XX`, `tunnel-name`, `tunnel-fingerprint`, `#` comments, blank lines; unknown/malformed lines are skipped with a warning (never fatal).
- [x] IP/CIDR/country entries live in one `bart` table keyed by prefix; `/32` and `/128` handled uniformly with CIDRs.
- [x] `country XX` entries expand from the DB-IP CSV via range→prefix conversion; missing/unreadable CSV → those entries skipped + warned, everything else still enforced.
- [x] Multiple ban files are UNIONed; each polled independently by mtime; reload builds a fresh table and swaps atomically (lock-free reads).
- [x] A configured `--ban-file` that is ABSENT or unreadable at (re)load is skipped with a warning — never fatal, never empties the engine; the mtime poll loads it when it appears (first deploy: the fetcher-produced `droplist.bans` does not exist until the fetcher's first run completes, and the operator's `bans.txt` may legitimately be absent).
- [x] `Match(ip)` returns whether banned + the reason/source; `MatchTunnel(name, fingerprint)` covers name/fingerprint bans.
- [x] Only placeholder country codes (`XX`, `YY`) appear anywhere in code, comments, and tests.

### Task 2.1: Ban entry model + parser
**File**: `tunneld/internal/ban/entry.go` — create `type Reason string` with consts
`banned_ip`,`banned_cidr`,`banned_country`,`banned_tunnel_name`,`banned_tunnel_fingerprint` and a
`func (r Reason) String() string { return string(r) }` (so ban rejection sites can pass
`source.Reason.String()` as the `tunneld_rejections_total{reason}` label); and
`Source{File string; Line int; Reason Reason; Detail string}`.
**File**: `tunneld/internal/ban/parse.go` — create `ParseLine(string) (kind, value, error)` and a
file parser returning `ipPrefixes []netip.Prefix (+Source)`, `countries []string`, `names set`,
`fingerprints set`.

### Task 2.2: DB-IP CSV country expansion
**File**: `tunneld/internal/ban/dbip.go` — create
```go
// ExpandCountries reads a DB-IP Country Lite CSV (columns: start_ip,end_ip,country_code) and returns,
// for the requested country codes, the covering []netip.Prefix (via netipx.IPRangeFrom(...).Prefixes()).
// Returns (nil, err) if csvPath == "" or unreadable; caller then warns-and-skips country entries.
func ExpandCountries(csvPath string, wanted map[string]struct{}) ([]netip.Prefix, error)
```
**Context**: use `go4.org/netipx` `IPRange` → `Prefixes()`. Stream the CSV with `encoding/csv`
(`FieldsPerRecord = 3`, `ReuseRecord = true`) — the file is tens of MB and parsed only on reload.

### Task 2.3: LPM table + atomic-swap engine
**File**: `tunneld/internal/ban/engine.go` — create
```go
type Engine struct{ current atomic.Pointer[snapshot] } // snapshot holds *bart.Table[Source] + name/fp sets

func NewEngine() *Engine // stores an EMPTY non-nil snapshot: Match/MatchTunnel before any Load return (Source{}, false) — NEVER a nil-pointer panic on the hot path
func (e *Engine) Match(ip netip.Addr) (Source, bool)                 // LPM lookup
func (e *Engine) MatchTunnel(name, fingerprint string) (Source, bool)
func (e *Engine) Load(files []string, csvPath string, log *slog.Logger) error // build + atomic swap
```
**Context**: `bart.Table[Source]` (`github.com/gaissmai/bart`) stores each prefix's `Source` payload
so the caller learns which layer/file fired. `Load` parses all files, expands countries, inserts
everything into a fresh `*bart.Table[Source]` + fresh name/fingerprint sets, then `current.Store`s a
new snapshot — readers holding the old pointer are unaffected.

### Task 2.4: mtime watcher
**File**: `tunneld/internal/ban/watch.go` — create `Watch(ctx, e *Engine, files []string, csvPath
string, poll time.Duration, onReload func(*Engine), log)`; polls the max mtime across all files + the
CSV; on change calls `e.Load(...)` and then, on a successful load, invokes `onReload(e)` (nil-safe).
Initial load happens once before the poll loop (and fires `onReload`). A load error keeps the previous
snapshot (never leaves the table empty) and does NOT fire `onReload`. `onReload` is how live
name/fingerprint revocation reaches the WS manager (US6 `EvictBanned`) — see US10 wiring.

### Task 2.5: Unit tests
**File**: `tunneld/internal/ban/engine_test.go`, `parse_test.go`, `dbip_test.go`

**Setup**: build engines from in-memory temp files; craft a tiny fixture CSV with placeholder country
codes `XX`/`YY` and a handful of ranges.

| Test | Verifies |
|------|----------|
| `parse handles all entry kinds` | `ip`,`cidr`,`country`,`tunnel-name`,`tunnel-fingerprint` parsed; comments/blanks ignored |
| `parse skips malformed lines` | Bad CIDR / unknown keyword → skipped, no error, warning path hit |
| `match single ip via /32` | A bare `ip` matches only that address |
| `match cidr covers range` | Address inside a `cidr` matches; outside does not |
| `match returns reason/source` | Matched `Source.Reason` + file/line correct |
| `country expands and matches` | Address in a `country XX` range matches with reason `banned_country` |
| `missing csv skips country only` | CSV absent → country entries skipped, ip/cidr still enforced |
| `union across multiple files` | Entry in file A and file B both match |
| `reload swaps atomically` | After file edit + `Load`, new entry matches, removed entry no longer matches |
| `watch fires onReload on mtime change` | `Watch` detects a changed file mtime, reloads, and invokes `onReload(engine)` |
| `watch load-error keeps previous snapshot` | A parse/read error during a `Watch` reload keeps the prior snapshot (never empties the table) and does NOT fire `onReload` |
| `match tunnel name and fingerprint` | `MatchTunnel` hits name and fingerprint sets |
| `missing ban file skipped` | A configured but nonexistent file → load succeeds with a warning (other files still enforced); creating the file → next poll loads its entries |
| `fresh engine is panic-safe` | `Match`/`MatchTunnel` on a `NewEngine()` BEFORE any `Load` → `(Source{}, false)`, no panic (empty non-nil initial snapshot) |

### Definition of Done
- [x] US2 test tables authored and committed (suite execution deferred to US16 per repo workflow).
- [x] No real country codes/names in any file (only `XX`/`YY`).
- [x] Reload never leaves the engine empty on parse error.

---

## User Story 3: Rate limiting, quotas, and bandwidth

Redis-backed per-source-IP request limits and per-tunnel concurrency (must be correct across
replicas), enrollment quotas, and an in-process per-direction bandwidth token bucket.

### Acceptance Criteria
- [x] Per-IP `rps` (fixed 1-second wall-clock window) and `rpm` (fixed 1-minute wall-clock window) via Redis atomic INCR+EXPIRE; over limit → decision carries `Retry-After` seconds to window end.
- [x] Enrollment quota: `20`/hour AND `2`/minute per source IP (fixed wall-clock windows); over → `Retry-After`.
- [x] Per-tunnel concurrency guard via Redis INCR/DECR with a safety TTL; `Acquire` fails over `--limit-concurrent`; `Release` always decrements (defer-safe).
- [x] Bandwidth: `TokenBucket` with rate = `ParseBitrate` bytes/sec, one bucket per direction per tunnel, in-process on the WS-holding node; `WaitN(ctx, n)` paces chunk writes.
- [x] All Redis keys have TTLs (no permanent state).

### Task 3.1: Redis fixed-window limiter
**File**: `tunneld/internal/limit/window.go` — create
```go
// Allow increments the wall-clock-aligned window bucket and reports allow/deny + Retry-After.
// key pattern: "rl:{scope}:{ip}:{windowStartUnix}"; INCR then EXPIRE(window*2) on first hit (Lua for atomicity).
func Allow(ctx context.Context, rdb redis.UniversalClient, scope string, ip netip.Addr, limit int, window time.Duration) (allowed bool, retryAfter time.Duration, err error)
```
**Context**: use a small `redis.Script` (INCR + conditional PEXPIRE) so the increment and expiry are
atomic. `retryAfter` = time to the next window boundary. Use `github.com/redis/go-redis/v9`.

### Task 3.2: Enrollment quota + concurrency guard
**File**: `tunneld/internal/limit/enroll.go` — `AllowEnroll(ctx, rdb, ip, perHour, perMinute)` combining
two `Allow` calls (deny if either denies; `Retry-After` = the larger).
**File**: `tunneld/internal/limit/concurrency.go` — `Acquire(ctx, rdb, name string, max int) (release func(), ok bool, err error)`.
**Constraint (TTL atomicity)**: the `INCR conc:{name}` and its safety `PEXPIRE` (e.g. `2×request-timeout`)
MUST be a SINGLE Lua script (INCR → if result > max then DECR and return denied; else PEXPIRE and
return allowed) so a crash can never leave an un-TTL'd counter — matching `window.go` and the
"every Redis key has a TTL" invariant (US16.3). `release` DECRs once (idempotent via `sync.Once`).

### Task 3.3: Bandwidth token bucket
**File**: `tunneld/internal/limit/bucket.go` — `TokenBucket{ mu sync.Mutex; rate, burst int64; ... }` with
`WaitN(ctx, n int) error` (classic refill on elapsed wall-clock; blocks until `n` bytes are
available or ctx done). `WaitN` MUST be internally mutex-guarded — the per-tunnel up-bucket is shared
by all concurrent `Do` goroutines (US6), so its mutable refill state needs its own lock. `burst` =
rate (1s). CONSTRAINT: the bucket never accumulates past `burst`, so `WaitN(n)` with `n > burst` can
NEVER be satisfied — `WaitN` MUST return a distinct error immediately for `n > burst` (never block
forever), and callers MUST acquire large amounts in increments ≤ `burst`: the wsconn manager (US6)
paces per-frame chunks (request AND response, each ≤ `wire.ChunkSize` = 32 KiB), and the ingress
paced body-reader (US7) reads in ≤ `ChunkSize` slices. Provide `NewTunnelBandwidth(bytesPerSec)`
returning an up-bucket and a down-bucket.
**File**: `tunneld/internal/limit/registry.go` — `BucketRegistry{ mu sync.Mutex; m map[string]*entry }`
with `Pair(name string) (up, down *TokenBucket)`: returns THE SAME pair for the same tunnel name
within this process (created on demand via `NewTunnelBandwidth`), so the ingress paced body-reader
(US7) and the WS chunk pacing (US6) draw from ONE budget when co-located on the same replica.
Entries idle longer than ~10 min are evicted (janitor goroutine or lazy sweep on `Pair`) — bounded
memory across ephemeral tunnel names; a re-created bucket starts full (a one-off burst, not a leak).
Cross-replica exactness was considered and REJECTED (user decision — see Design Decisions "Limits"):
a distributed bucket would put a synchronous Redis call per 32 KiB slice on the data plane.

### Task 3.4: Unit tests
**File**: `tunneld/internal/limit/*_test.go`

**Setup**: `miniredis` (`github.com/alicebob/miniredis/v2`) for Redis-backed tests; a fake clock for
the bucket (inject `now func() time.Time`).

| Test | Verifies |
|------|----------|
| `rps allows up to limit then denies` | 10 allowed in a second, 11th denied with Retry-After ≤1s |
| `rpm allows up to limit then denies` | 100/min boundary; Retry-After to minute end |
| `window resets on boundary` | After advancing miniredis time past window, count resets |
| `enroll denies when either sub-limit trips` | 3rd/minute denied even if hourly has room |
| `concurrency caps in-flight` | 4 acquired; 5th fails; release frees a slot |
| `concurrency release is idempotent` | Double release does not underflow |
| `every key has a TTL after first op` | miniredis `TTL(key) > 0` immediately after the window/enroll/concurrency INCR (proves the single-Lua INCR+PEXPIRE is atomic — no un-TTL'd key) |
| `bandwidth paces bytes` | WaitN blocks the expected duration for N > rate given fake clock |
| `waitn over burst errors immediately` | `WaitN(n)` with `n > burst` → distinct error at once (never an infinite block); acquiring the same total in ≤burst increments succeeds across fake-clock refills |
| `registry returns same pair per name` | Two `Pair("t")` calls → the IDENTICAL bucket instances; different names → different pairs |
| `registry evicts idle pairs` | Pair idle past the eviction window (fake clock) → dropped; next `Pair` recreates a full bucket |

### Definition of Done
- [x] US3 test tables authored and committed (miniredis + fake clock; execution in US16).
- [x] Every Redis key created carries a TTL, set atomically with its INCR/HINCRBY (asserted in the test tables).

---

## User Story 4: Internal CA, enrollment, and connect-auth crypto

The CA signing path plus the application-layer `/connect` authentication crypto: load CA material,
generate tunnel names, sign CSRs, parse the phone-sent certificate, verify it against the CA, and
verify the possession signature over the server nonce. Pure crypto; no HTTP yet.

### Acceptance Criteria
- [x] `--ca-cert`/`--ca-key` loaded into an in-memory signer at startup; bad material fails fast.
- [x] `GenerateName(prefix string, length int)` → `prefix + base32(crypto/rand)[:length]`, lowercase `[a-z2-7]`, skipping a reserved-hostname set.
- [x] `SignCSR(der []byte, name string)` validates the CSR signature, issues a leaf cert with CN=name, `--cert-validity` lifetime; returns PEM. Ignores all CSR-provided subject/extensions except the public key. REJECTS a CSR whose public key is not ECDSA P-256 (distinct error — US8 maps it to `400 unsupported_key_type`).
- [x] `ParseCertB64DER(b64)` decodes the base64-DER certificate the phone sends in the `AUTH` frame.
- [x] `VerifyEnrolledCert(cert, pool)` verifies the chain against the CA and the validity window, returns `(name=CN, fingerprint)`.
- [x] `VerifyPossession(cert, nonce, signature)` verifies the ECDSA-P256 signature over `ConnectAuthContext ‖ nonce` using the cert's public key (the app-layer proof-of-possession — see Identity).
- [x] `Fingerprint(cert)` = `"sha256:" + hex(sha256(cert.Raw))`.

### Task 4.1: CA signer
**File**: `tunneld/internal/ca/ca.go` — create
```go
type CA struct{ cert *x509.Certificate; key crypto.Signer; validity time.Duration }
func Load(certPath, keyPath string, validity time.Duration) (*CA, error)
func (c *CA) SignCSR(csrDER []byte, commonName string) (leafPEM []byte, err error)
func (c *CA) Pool() *x509.CertPool // CA-only pool for verification
```
**Context**: `SignCSR` MUST call `csr.CheckSignature()` and ignore any CSR-provided subject/extensions
except the public key — the server sets CN, serial (`crypto/rand`), `NotBefore/After`, `KeyUsage =
DigitalSignature`. `SignCSR` MUST REJECT any CSR whose public key is not ECDSA P-256 (comma-ok check
on `*ecdsa.PublicKey` + `Curve == elliptic.P256()`), returning a distinct sentinel error: only P-256
can ever complete the `/connect` possession proof, so signing any other key type would mint a cert
that can never authenticate.

### Task 4.2: Name generation + reserved set
**File**: `tunneld/internal/ca/name.go` — `GenerateName(prefix string, length int) (string, error)`
using `crypto/rand` → base32 (`base32.StdEncoding` lowercased, no padding). Retry (bounded, e.g. 8
attempts) if the label ∈ reserved set (`enroll`,`connect`,`tunnel`,`grafana`,`prometheus`,
`alertmanager`,`ntfy`,`www`,`api`,`admin`) or fails the host-label regex `^[a-z0-9-]{1,63}$`.

### Task 4.3: Cert parse/verify + possession + fingerprint
**File**: `tunneld/internal/ca/verify.go` — create
```go
const ConnectAuthContext = "tunneld-connect-v1" // domain-separation prefix signed with the nonce
func ParseCertB64DER(b64 string) (*x509.Certificate, error)                       // AUTH-frame cert
func VerifyEnrolledCert(cert *x509.Certificate, pool *x509.CertPool) (name, fingerprint string, err error)
func VerifyPossession(cert *x509.Certificate, nonce, signature []byte) error      // ECDSA over context‖nonce
func Fingerprint(cert *x509.Certificate) string
```
**Context**: the phone sends the certificate as **base64 of the DER** in the `AUTH` frame (NOT a
Traefik-forwarded PEM header — that path is gone with mTLS). `ParseCertB64DER` = `base64.StdEncoding`
decode → `x509.ParseCertificate`. `VerifyEnrolledCert` verifies with
`cert.Verify(x509.VerifyOptions{Roots: pool})` and checks the validity window; `name` = CN.
`VerifyPossession` computes `digest = sha256.Sum256([]byte(ConnectAuthContext) ‖ nonce)` and verifies
`signature` via `ecdsa.VerifyASN1` (P-256, ASN.1 DER signature — NOT the raw message); the
`cert.PublicKey.(*ecdsa.PublicKey)` extraction MUST use the comma-ok form and return an error for a
non-EC key (defense-in-depth vs a foreign/legacy cert — NEVER a panic); returns an error on any mismatch. (The phone signs the
same `sha256.Sum256(ConnectAuthContext ‖ nonce)`.) The
caller (US6) additionally checks `name == Host <name>`.

### Task 4.4: Unit tests
**File**: `tunneld/internal/ca/*_test.go`

**Setup**: generate a throwaway CA in-test; generate an EC (P-256) key + CSR; drive sign→parse→verify→sign-nonce round trips.

| Test | Verifies |
|------|----------|
| `load rejects bad ca material` | Missing/unreadable/corrupt cert or key, or a non-CA cert → `Load` errors at startup |
| `sign then verify round trips` | Signed leaf verifies against CA pool; CN == requested name |
| `sign rejects bad csr signature` | Tampered CSR → error |
| `sign ignores csr subject/extensions` | CSR-set CN/SAN discarded; server CN wins |
| `sign rejects non-P256 key` | CSR with an RSA (and a P-384) public key → distinct sentinel error, no cert issued |
| `possession rejects non-EC cert key` | Hand-built cert carrying an RSA public key → `VerifyPossession` returns an error (no panic) |
| `parse b64der round trips` | `ParseCertB64DER(base64(DER))` yields the same cert; garbage → error |
| `verify rejects unknown ca` | Cert from a different CA → error |
| `verify rejects expired cert` | NotAfter in the past → error |
| `verify rejects not-yet-valid cert` | NotBefore in the future → error |
| `possession accepts valid signature` | Signature over `context‖nonce` by the cert's key verifies |
| `possession rejects wrong key` | Signature by a different key → error |
| `possession rejects wrong/stale nonce` | Signature over a different nonce → error (no replay) |
| `fingerprint stable + sha256 prefixed` | Same cert → same `sha256:` hex |
| `generate name shape + reserved skip` | 10 chars `[a-z2-7]`; never a reserved label |

### Definition of Done
- [x] US4 test tables authored and committed (execution in US16).
- [x] `SignCSR` provably ignores attacker-controlled CSR subject/extensions.
- [x] Possession verification rejects wrong-key and wrong-nonce signatures (no replay).

---

## User Story 5: Redis routing and pub/sub transport

Cross-replica routing table (name→node, heartbeat TTL) and the request/response bridge over pub/sub,
including the wire envelopes carried between frontends and the WS-holding node.

### Acceptance Criteria
- [x] `Registry.Bind(ctx, name, nodeID, fingerprint, connID)` writes `route:{name}` (node + fingerprint + per-connection `connID`) with a TTL; `Heartbeat` refreshes it and `Unbind` removes it — BOTH owner-conditionally on the `connID` (they touch `route:{name}` ONLY while it still belongs to that exact connection — a node-only guard would break same-node reconnects). `Lookup(name)` returns the node AND the stored fingerprint (for the US7 ingress ban gate) or "no tunnel".
- [x] Fingerprint guard: `route:{name}` stores the cert fingerprint; a bind for `name` with a different fingerprint is rejected (distinct error, logged loudly).
- [x] The ingress handler resolves `name → node` via `Lookup` (US7 step 3); `RoundTrip(ctx, rdb, node, req, timeout)` then receives the resolved `node`, subscribes to `resp:{reqid}` BEFORE publishing to `req:{node}`, and returns the `RespEnvelope`, or `(nil, ErrTimeout)` on `--limit-request-timeout` (a publish failure returns a different non-nil error). `ServeNode` takes the per-message `timeout` (US10 passes `--limit-request-timeout`) and an `observ.Recorder`, and records `PublishError()` on a failed response-publish.
- [x] Node loop: subscribe to `req:{nodeID}`, invoke a handler, publish `RespEnvelope` to `resp:{reqid}`.
- [x] Envelopes serialize without base64 (JSON header + raw body, length-prefixed).

### Task 5.1: Wire envelopes (shared package)
**File**: `tunneld/internal/wire/envelope.go` — create
```go
type ReqEnvelope struct {
	ReqID, Node, TunnelName            string
	Method, Path, RawQuery, Host       string
	Header                             http.Header
	Body                               []byte
	ClientIP                           string
	ForwardedProto                     string
	PacedByNode                        string // nodeID of the frontend whose up-bucket already paced the body read (US7 step 8)
}
type RespEnvelope struct {
	ReqID   string
	Status  int
	Header  http.Header
	Body    []byte
	Err     string // human-readable synthetic-error message (empty for a real phone response)
	ErrCode string // machine discriminator: "" (real response), "response_too_large" (node already
	               // recorded it), "tunnel_gone" (WS dropped mid-round-trip — frontend records tunnel_offline),
	               // or "phone_error" (phone sent an ERROR frame for this reqid)
}
func MarshalReq(*ReqEnvelope) []byte   // 4-byte header-len + JSON(header) + raw body
func UnmarshalReq([]byte) (*ReqEnvelope, error)
func MarshalResp(*RespEnvelope) []byte
func UnmarshalResp([]byte) (*RespEnvelope, error)
```
**Context**: JSON-encode everything EXCEPT `Body`, which is appended raw after a 4-byte length prefix
— avoids base64 across Redis. This same package is reused by the WS codec (US6). AUTHORITY:
`ClientIP`/`ForwardedProto` are node/frontend-side metadata (logging, diagnostics) ONLY — the
phone-side adapter (`DecodeReqHeader`, US6.1) reconstructs the `http.Request` EXCLUSIVELY from
`Method/Path/RawQuery/Host/Header/Body`; the forwarded `X-Forwarded-*` values the app sees live in
`Header` (put there by `Sanitize`, US7.2) and are the single source of truth.

### Task 5.2: Routing registry
**File**: `tunneld/internal/router/registry.go` — create
```go
type Registry struct{ rdb redis.UniversalClient; ttl time.Duration }
func (r *Registry) Bind(ctx, name, nodeID, fingerprint, connID string) error // Lua guard on fingerprint; connID = per-connection crypto/rand identity
func (r *Registry) Heartbeat(ctx, name, connID string) (HeartbeatResult, error) // refreshed | not-owner | missing
func (r *Registry) Unbind(ctx, name, connID string) error
func (r *Registry) Lookup(ctx, name string) (nodeID, fingerprint string, ok bool, err error)
```
**Context**: `Bind` uses a Lua script: if `route:{name}` exists with a different fingerprint → return a
sentinel that maps to `ErrNameHeldByOther`; else set `{node, fingerprint}` with `PEXPIRE ttl`.
`ttl` = `--route-ttl` (default `30s`); the WS heartbeat (US6) refreshes at `--route-ttl / 3`
(`10s` default). `--route-ttl` is the single configured source — the heartbeat cadence is derived
from it, not the reverse. `Registry` takes `ttl` from config at construction.
`route:{name}` stores `{node, fingerprint, connID}`. `Unbind` and `Heartbeat` are OWNER-CONDITIONAL
single-Lua scripts keyed on the **connID** (a per-connection `crypto/rand` identity minted at
`Bind`), NOT on the node alone: they `DEL` / `PEXPIRE` `route:{name}` ONLY if its stored `connID`
still equals the passed one. Rationale: `Bind` permits a same-fingerprint rebind — onto a DIFFERENT
node (phone reconnects through another replica) or onto the SAME node (WS blip, re-dial lands on the
same replica while the stale conn lingers until dead-peer detection). A node-only guard would let the
stale conn's delayed teardown `Unbind` clobber the NEW conn's route in the same-node case; the
connID guard makes both cases safe. `Heartbeat` returns a THREE-state result (not an error):
`refreshed` (still owner), `not-owner` (route now points at a DIFFERENT node), or `missing` (no
`route:{name}` at all — e.g. the TTL lapsed during a Redis interruption longer than `--route-ttl`).
The caller (US6.3) treats `not-owner` as superseded but `missing` as a self-heal trigger: a LIVE WS
must never be left permanently unrouteable just because its route expired. `Lookup` also returns the stored `fingerprint`
(same route entry, no extra round-trip) — the public ingress (US7 step 3) needs it for its
`ban.MatchTunnel` gate.

### Task 5.3: Pub/sub round trip
**File**: `tunneld/internal/transport/transport.go` — create
```go
var ErrTimeout = errors.New("roundtrip timeout") // distinct sentinel the handler maps to 504 + rec.Timeout()
func RoundTrip(ctx, rdb, node string, req *wire.ReqEnvelope, timeout time.Duration) (*wire.RespEnvelope, error)
func ServeNode(ctx, rdb, nodeID string, timeout time.Duration, rec observ.Recorder, handle func(context.Context, *wire.ReqEnvelope) *wire.RespEnvelope) error
```
**Context**: `RoundTrip` must `Subscribe("resp:"+reqid)` and confirm the subscription is ready
(`PubSub.Receive` first, or `ping`) BEFORE `Publish("req:"+node, MarshalReq(req))` to avoid the race
where the response is published before we subscribe. The `resp:{reqid}` subscription MUST be closed
on EVERY exit path (`defer pubsub.Close()`) — success, timeout, and publish failure — so timed-out
round trips never leak pubsub goroutines/connections. On timeout it returns `(nil, ErrTimeout)` — the
distinct signal US7 step 8 maps to `504` + `rec.Timeout()` + `rec.Reject("timeout", …)` (NOT confused
with a phone-origin 504 or an `ErrCode`-carried error). A publish failure returns a non-nil error the
handler maps to `502` + `rec.PublishError()`. `ServeNode` runs `handle` per message in its own
goroutine — with a ctx derived `WithTimeout(timeout)` (its `timeout` param; US10 passes
`--limit-request-timeout`) so a phone that accepts a request
but never sends `RESPONSE_END` (while the WS stays up) still releases the node-side `Do` goroutine and
its `pending[reqid]` entry (no leak; matches the frontend 504) — and publishes the response; if THAT
response-publish fails it records `rec.PublishError()` (hence `ServeNode` takes the `observ.Recorder`).

### Task 5.4: Unit tests
**File**: `tunneld/internal/router/*_test.go`, `tunneld/internal/transport/*_test.go`, `tunneld/internal/wire/*_test.go`

**Setup**: miniredis; the shared `tunneltest.Recorder` (US1 Task 1.5) for `Recorder.Count` assertions; for envelopes, table-driven round-trip fixtures (these ARE the golden fixtures referenced by US6, US11, and the `PROTOCOL.md` spec in US15).

| Test | Verifies |
|------|----------|
| `envelope round trips with binary body` | Marshal→Unmarshal preserves headers + exact body bytes (incl. NUL) |
| `bind then lookup` | Bound name resolves to node AND the stored fingerprint |
| `bind rejects different fingerprint` | Second bind, different fp → `ErrNameHeldByOther` |
| `heartbeat refreshes ttl` | TTL extended after heartbeat (miniredis FastForward) |
| `unbind removes route` | Lookup after unbind → not found |
| `unbind is conn-conditional` | Bind conn c1, rebind (same fp) conn c2 — on ANOTHER node AND on the SAME node — `Unbind(name, c1.connID)` → `Lookup` still returns c2's route (a stale conn can never clobber the re-bound route, even same-node) |
| `heartbeat is conn-conditional` | After rebind to c2, `Heartbeat(name, c1.connID)` reports not-owner and leaves `route:{name}` (owner AND TTL) untouched |
| `heartbeat distinguishes missing from not-owner` | Route deleted/expired → `Heartbeat` reports `missing` (NOT `not-owner`); route held by another node → `not-owner` |
| `roundtrip returns response` | Publish/subscribe delivers the matching resp envelope |
| `roundtrip times out to ErrTimeout` | No responder → `RoundTrip` returns `(nil, ErrTimeout)` after `timeout` (the 504 mapping is US7's job, not the transport's) |
| `roundtrip closes subscription on timeout` | After `ErrTimeout` returns, no subscriber remains on `resp:{reqid}` (miniredis subscriber count) — no pubsub leak on the 504 path |
| `servenode records publish error` | Force the `resp:{reqid}` publish to fail inside `ServeNode` → `Recorder.Count("publisherror","")==1` |
| `roundtrip ignores foreign reqid` | Response for another reqid does not resolve this call |
| `subscribe before publish (no lost response)` | A responder that publishes to `resp:{reqid}` the instant the request lands still resolves the call — proves the caller subscribed BEFORE publishing (ordering enforced) |

### Definition of Done
- [x] US5 test tables authored and committed; envelope fixtures committed for cross-client reuse (execution in US16).
- [x] Subscribe-before-publish ordering is enforced and covered by a test.

---

## User Story 6: WebSocket tunnel protocol and connection manager

The `/connect` side: ban-check, accept the WebSocket, run the application-layer challenge-response
authentication (no TLS mTLS), bind routing, and bridge Redis-delivered requests to the phone over
binary frames (chunked, bandwidth-paced), with liveness via native WS pings.

### Acceptance Criteria
- [x] Binary frame codec: `[type:1][headerLen:4 BE][header JSON][body]`; types `CHALLENGE`, `AUTH`, `REQUEST_HEAD`, `REQUEST_BODY_CHUNK`, `REQUEST_END`, `RESPONSE_HEAD`, `RESPONSE_BODY_CHUNK`, `RESPONSE_END`, `ERROR`. The request and response paths are SYMMETRIC (user decision): `REQUEST_HEAD` carries method/path/headers and NO body; the body follows in `REQUEST_BODY_CHUNK` frames (≤ `ChunkSize` each); `REQUEST_END` is the dispatch trigger — the receiver MUST NOT dispatch the request until it arrives. An empty body sends ZERO body-chunk frames — the canonical encoding in BOTH directions (request AND response; receivers MUST also tolerate a zero-length chunk frame); `PROTOCOL.md` (US15.2) pins this so the future Kotlin client cannot drift. Chunking the request keeps the write lock released between frames so keepalive pings and other in-flight requests interleave even on slow phone links (a single large frame would occupy the socket for its whole transmission time — WebSocket cannot interleave control frames inside a data frame).
- [x] EVERY `REQUEST_*`/`RESPONSE_*`/`ERROR` frame header JSON carries the `reqid` (the request's `ReqID`), so up to `--limit-concurrent` in-flight requests multiplexed over ONE WebSocket can be demultiplexed: the read-pump routes each such frame to `pending[reqid]`, and the phone (and `tunneltest.FakePhone`) copies the request's `ReqID` into every `RESPONSE_HEAD`/`RESPONSE_BODY_CHUNK`/`RESPONSE_END` frame. (CHALLENGE/AUTH carry no reqid.)
- [x] `ERROR` frame semantics: header `{reqid, message}`, no body; the phone emits it when it cannot fulfil a specific request (e.g. its local backend errored); the read-pump resolves `pending[reqid]` with a synthetic `502` `RespEnvelope` (`Err=message`, `ErrCode="phone_error"`). An `ERROR` with an unknown/stale `reqid` is dropped.
- [x] `HandleConnect` owns the ENTIRE reserved `/connect` path: a request that is NOT a WebSocket upgrade → `426 Upgrade Required` (it never reaches the public allowlist).
- [x] On `/connect`, BEFORE the WS upgrade: ban check on the trusted source IP (`clientip.TrustedIP` on `--client-ip-header`; absent → `400` reason `missing_client_ip`, fail-closed) → `403` if banned; then a per-IP connect-attempt limit (`limit.Allow` scope `connect`, limit `--limit-rpm`, window `1m` — the explicit `window` param) → `429`; then acquire a pre-auth semaphore slot (bounded by `--limit-connect-pending`) → `503` if full (so unauthenticated floods never allocate a WebSocket).
- [x] After upgrade: send a `CHALLENGE` frame (fresh 32-byte `crypto/rand` nonce, in-memory), read the `AUTH` frame within `--connect-auth-timeout` (releasing the semaphore slot when the handshake resolves), then `ParseCertB64DER` → `VerifyEnrolledCert` (chain/validity, `name=CN`, fingerprint) → `VerifyPossession(cert, nonce, sig)` → require `name == Host <name>`. Any failure/timeout (bad cert, bad possession signature, CN≠Host, no AUTH in time) → close the WS with a distinct code, NO bind, + `rec.Reject("connect_auth_failed", "", ip.String())`.
- [x] `ban.MatchTunnel(name, fingerprint)` AFTER auth and BEFORE `Bind` → distinct WS close with reason `banned_tunnel_name`/`banned_tunnel_fingerprint` (the only revocation mechanism — a banned name/fingerprint MUST be refused) AND `rec.Reject("banned_tunnel_name"|"banned_tunnel_fingerprint", name, ip.String())`.
- [x] Every connect-edge rejection records via the injected `observ.Recorder` (`Reject`'s `clientIP` is a `string`): `rec.Reject("missing_client_ip", "", "")` (400 — no valid IP on this path), `rec.Reject(banSource.Reason.String(), "", ip.String())` (403 — the reason is the MATCHED `ban.Source.Reason`, i.e. `banned_ip`/`banned_cidr`/`banned_country`, NOT hardcoded), `rec.Reject("rate_connect", "", ip.String())` (429), `rec.Reject("connect_pending", "", ip.String())` (503) — so every registered `tunneld_rejections_total{reason}` connect-edge label has a writer.
- [x] `EvictBanned(engine)` is registered as the ban-reload hook (US2.4) and drops any LIVE `Conn` whose `(name, fingerprint)` becomes banned mid-session (required because there is no idle disconnect).
- [x] `Bind` routing with fingerprint, start heartbeat at `--route-ttl/3`, run native keepalive pings at `--ping-interval`; on close → `Unbind` and cancel in-flight.
- [x] Recorder wiring (so the WS-lifecycle/byte metrics have writers): on successful bind → `rec.WSConnect()` (and the `tunneld_tunnels_connected` gauge is derived from connect/disconnect); on `Conn` close → `rec.WSDisconnect(reason)` (reason incl. `banned_tunnel_name`/`banned_tunnel_fingerprint` for evictions, `client_close`, `dead_peer`, `superseded` (stale conn after a same-fingerprint re-bind on another node, US6.3), `shutdown`); each chunk written or received → `rec.Bytes(name, "in"|"out", n)` — recorded for EVERY chunk regardless of pacing (the US6.2 double-pacing guard skips ONLY the token drain, NEVER the byte accounting; otherwise co-located requests would undercount `direction="out"`). (The `Conn` knows its tunnel `name`; `"in"` = phone→client response bytes, `"out"` = client→phone request bytes — distinct from the up/down bandwidth-bucket names.)
- [x] Node handler (from US5 `ServeNode`) sends the request to the phone over WS via `Conn.Do`; the `Conn`'s single read-pump OWNS response handling — it applies the per-tunnel down-bandwidth bucket to each `RESPONSE_BODY_CHUNK`, enforces `--limit-response`, and reassembles the `RespEnvelope`; `Do` only awaits the assembled result.
- [x] Fingerprint guard surfaced: a `/connect` for a name already bound to a different fingerprint (`Registry.Bind` → `ErrNameHeldByOther`) → close with a distinct code + loud log + `rec.Reject("fingerprint_conflict", name, ip.String())`.
- [x] Response assembly enforces `--limit-response`; exceeding → abort to a synthetic `502` (`Err` set, `ErrCode="response_too_large"`) + `rec.Reject("response_too_large", name, "")` recorded ONCE at the node, never unbounded memory. A WS that drops mid-round-trip (`RouteLocal` finds no live `Conn`) returns `ErrCode="tunnel_gone"`.

### Task 6.1: Frame codec + http↔frame header adapters
**File**: `tunneld/internal/wire/frame.go` — create `FrameType` consts (`CHALLENGE`, `AUTH`,
`REQUEST_HEAD`, `REQUEST_BODY_CHUNK`, `REQUEST_END`, `RESPONSE_HEAD`, `RESPONSE_BODY_CHUNK`,
`RESPONSE_END`, `ERROR`) +
`EncodeFrame(t FrameType, header []byte, body []byte) []byte` and `DecodeFrame([]byte) (FrameType,
header, body []byte, err error)`. Chunk size const `ChunkSize = 32 * 1024`. Also declare the
header adapters used by the manager (`Conn.Do`/read-pump), the phone, and `tunneltest.FakePhone` — all
carry `reqid`:
```go
// REQUEST_HEAD header  = {reqid, method, path, rawquery, host, header}; body = EMPTY
// (the request body arrives via REQUEST_BODY_CHUNK frames; the receiver dispatches on REQUEST_END.
// ClientIP/ForwardedProto/PacedByNode are envelope-only metadata (US5.1) and are NOT put on the
// wire — the app-visible X-Forwarded-* values already live inside `header` via Sanitize, US7.2)
func EncodeReqHeader(r *ReqEnvelope) (header []byte)
func DecodeReqHeader(header, body []byte) (reqid string, req *http.Request) // called at REQUEST_END with the ACCUMULATED body
// RESPONSE_HEAD header = {reqid, status, header}; REQUEST_BODY_CHUNK/REQUEST_END/RESPONSE_BODY_CHUNK/RESPONSE_END header = {reqid}
func EncodeRespHeader(reqid string, code int, h http.Header) (header []byte)
func DecodeRespHeader(header []byte) (reqid string, code int, h http.Header)
func EncodeReqIDHeader(reqid string) (header []byte) // {reqid} header for REQUEST_BODY_CHUNK/REQUEST_END/RESPONSE_BODY_CHUNK/RESPONSE_END
func EncodeErrorHeader(reqid, message string) (header []byte)     // ERROR frame header {reqid, message}
func DecodeErrorHeader(header []byte) (reqid, message string)
func FrameReqID(header []byte) (reqid string)        // cheap reqid extraction for the read-pump demux (all reqid-carrying frames)
```

### Task 6.2: Connection manager
**File**: `tunneld/internal/clientip/clientip.go` — create the shared, dependency-free helper
`TrustedIP(r *http.Request, header string) (netip.Addr, bool)`: reads the configured
`--client-ip-header` and returns the parsed **RIGHT-MOST** comma-separated token (a single value for
`Cf-Connecting-Ip`/`X-Real-Ip`; the proxy-appended hop for `X-Forwarded-For`); returns `ok=false` if
the header is absent/empty/unparseable. MUST NOT return the left-most entry. This package has NO
tunnel deps, is created here (the first ingress edge that needs it — `/connect`), and is reused by
US7 (public handler) and US8 (`/enroll`).
**Unit tests** (`tunneld/internal/clientip/clientip_test.go`):

| Test | Verifies |
|------|----------|
| `single value parsed (Cf-Connecting-Ip)` | `"9.9.9.9"` → `9.9.9.9` |
| `right-most token for XFF` | `"1.2.3.4, 9.9.9.9"` → `9.9.9.9` (proxy hop), not `1.2.3.4` |
| `absent header not ok` | Missing/empty header → `ok=false` |
| `unparseable not ok` | Garbage value → `ok=false` |

**File**: `tunneld/internal/wsconn/manager.go` — create
```go
type Manager struct{ /* nodeID, registry, rdb redis.UniversalClient (for the connect-rate limiter + semaphore), ban, ca, caPool, buckets *limit.BucketRegistry (per-tunnel pair via Pair(name) at bind — the SAME instance the ingress paced reader uses on this process), rec observ.Recorder, cfg, log; conns sync.Map */ }
func (m *Manager) HandleConnect(w http.ResponseWriter, r *http.Request) // clientip→ban→connect-rate→upgrade→challenge/auth→tunnel-ban→bind→serve
func (m *Manager) RouteLocal(ctx context.Context, req *wire.ReqEnvelope) *wire.RespEnvelope // ctx is ServeNode's per-message ctx, already WithTimeout via its timeout param (--limit-request-timeout); finds local Conn → Do(ctx, req) (or synthetic 502)
func (m *Manager) EvictBanned(e *ban.Engine)                            // ban-reload hook: drop live Conns now name/fp-banned
type Conn struct{ /* name, fp, connID string (crypto/rand — the route-ownership identity, US5.2), ws, writeMu sync.Mutex, pending sync.Map[reqid]*inflight */ }
type inflight struct{ ch chan *wire.RespEnvelope /* buffered 1 */; head *wire.RespEnvelope; body bytes.Buffer }
func (c *Conn) Do(ctx, req *wire.ReqEnvelope) *wire.RespEnvelope // send frames, await assembled response
```
**Context — HandleConnect order**: (1) `clientip.TrustedIP` (absent → `400 missing_client_ip`); (2)
`ban.Match(ip)` → `403`; (3) per-IP connect-attempt limit (`limit.Allow` scope `connect`, limit
`--limit-rpm`, window `1m`) → `429`; (4) acquire a slot from the in-process pre-auth semaphore (bounded by
`--limit-connect-pending`) → `503` if full — steps 1-4 are BEFORE the WS upgrade so unauthenticated
floods never allocate a WebSocket; (5) upgrade via `coder/websocket`, then immediately
`ws.SetReadLimit(int64(wire.ChunkSize) + 64*1024)` — the library's DEFAULT read limit (32768 bytes)
is SMALLER than one full `RESPONSE_BODY_CHUNK` frame (`ChunkSize` body + type/len/header-JSON), so
without raising it the very first full chunk errors the read-pump; the server only legitimately
receives `AUTH`, `RESPONSE_*`, and `ERROR` frames, so `ChunkSize + 64 KiB` headroom bounds a malicious
peer while admitting every legal frame; (6) generate a 32-byte nonce,
send `CHALLENGE`, read `AUTH` within `--connect-auth-timeout` (release the semaphore slot when the
handshake resolves either way); (7) `ca.ParseCertB64DER` → `ca.VerifyEnrolledCert` →
`ca.VerifyPossession` → require CN == Host `<name>`; any failure/timeout → WS close, no bind; (8)
**`ban.MatchTunnel(name, fingerprint)` → distinct WS close with reason `banned_tunnel_name` /
`banned_tunnel_fingerprint`** (tunnel-name/fingerprint bans are the ONLY revocation mechanism — no
CRL; enforced HERE, at public ingress (US7 step 3), and live via `EvictBanned` — a banned
name/fingerprint MUST be refused at connect); (9) mint the `Conn`'s `connID` (`crypto/rand`) and
`Registry.Bind(name, nodeID, fingerprint, connID)` (fingerprint guard → distinct close on conflict);
(10) `m.conns.Store(name, conn)` (a same-name Store OVERWRITES a lingering stale `Conn` — correct:
the new conn owns the name), start heartbeat + serve. One `Conn` per phone. TEARDOWN of a `Conn` `c`
is conn-identity-conditional on BOTH sides: `m.conns.CompareAndDelete(name, c)` (never removes a
NEWER conn that replaced `c` after a same-node reconnect) and `Unbind(name, c.connID)` (US5.2 —
never deletes the newer conn's route).
**Request path (`Do`)**: registers an `inflight` (its `ch` buffered 1) in `pending` keyed by `ReqID`,
writes `REQUEST_HEAD` (headers only, no body — small, unpaced), then for EACH ≤ `wire.ChunkSize`
slice of the body: `up-bucket.WaitN(ctx, len(chunk))` FIRST (each chunk ≤ `ChunkSize` ≤ burst per
US3.3), then a `writeMu`-guarded `ws.Write` of the `REQUEST_BODY_CHUNK`; finally `REQUEST_END`.
DOUBLE-PACING GUARD (token drain ONLY — `rec.Bytes(name, "out", len(chunk))` is still recorded for
every chunk written): if `req.PacedByNode == m.nodeID`, the WaitN step is SKIPPED for every chunk —
the ingress paced reader (US7 step 8) already drew these exact bytes from THIS SAME bucket instance
(rationale: Design Decisions "Limits"); a foreign `PacedByNode` (request ingressed on another
replica) IS paced here — this node's bucket is the authoritative choke point for the WS leg.
`writeMu` is released between frames — that is the point of chunking the request: keepalive pings and
the frames of other in-flight `Do`s interleave even on a slow phone link. An empty body sends zero
chunks. `Do` then awaits `inflight.ch` or ctx. **Response path (read-pump)**: a single read-pump goroutine per `Conn`
OWNS the WS reads and response reassembly — it routes each `RESPONSE_*`/`ERROR` frame by
`wire.FrameReqID(header)` to the matching `inflight`: `RESPONSE_HEAD` records status/headers into
`inflight.head`; each `RESPONSE_BODY_CHUNK` first `down-bucket.WaitN(ctx, len(chunk))` (every chunk
≤ `ChunkSize` ≤ burst), then appends to `inflight.body`, aborting to the synthetic
`response_too_large` `502` when `--limit-response` is exceeded; `RESPONSE_END` (or `ERROR`) assembles
the final `RespEnvelope`, sends it on `inflight.ch`, and deletes the `pending` entry. Because up to
`--limit-concurrent` `Do` goroutines (one per in-flight request, from `ServeNode`) run concurrently
over ONE WebSocket, THREE shared resources MUST be made safe (not just the pending map):
(1) `pending` is a `sync.Map` (concurrent register/delete vs the read-pump's lookups);
(2) `coder/websocket` forbids overlapping `Write`s, so EVERY data-frame write goes through a single
`Conn.writeMu` (or a dedicated write-pump goroutine) — the per-request frames of different `Do`s never
interleave; (3) the shared per-tunnel up-bandwidth `TokenBucket.WaitN` is internally mutex-guarded
(US3.3). CRITICAL: the blocking `up-bucket.WaitN(ctx, n)` MUST NOT be held under `writeMu` (acquire
tokens first, then take `writeMu` only for the actual `ws.Write`) so a paced/slow request cannot stall
the read-pump or the other `Do`s. `reqid`-keyed demux makes concurrent multiplexing correct (a
stray/unknown `reqid` is dropped). The node's `ServeNode` handler (US5) calls `manager.RouteLocal(ctx, req)`
(threading the per-message `ctx`) which finds the `Conn` for `req.TunnelName` and calls `Do(ctx, req)`.
**Live revocation (`EvictBanned`)**: invoked by the ban watcher (US2.4) after every reload; iterates
`m.conns` and, for any live `Conn` whose `(name, fingerprint)` now matches the fresh engine snapshot,
closes the WS (reason `banned_tunnel_*`) + `Unbind` + fail pending — because "no idle disconnect"
means an already-connected banned tunnel would otherwise stay bound forever.

**Constraint**: requests for a tunnel can arrive at ANY replica, but the `Conn` lives only on the
node holding the WS. So `ServeNode` on node N only ever receives `req:{N}` messages, and the frontend
M already resolved `name → N` via `Lookup` before publishing to `req:{N}`. `RouteLocal` therefore
always finds the local `Conn` (or returns a synthetic `502` if the WS just dropped).

### Task 6.3: Heartbeat + liveness
**File**: `tunneld/internal/wsconn/heartbeat.go` — goroutine refreshing `Registry.Heartbeat` every
`--route-ttl / 3` (the routing heartbeat, distinct from the WS `--ping-interval` keepalive); rely on
`coder/websocket` read deadline + native `Ping` for dead-peer detection; on any WS error → close
`Conn`, `Unbind`, fail all pending with a `502`. If `Heartbeat` reports not-owner (US5.2 — the phone
re-bound through another node), the local `Conn` is stale: close it (WS disconnect reason
`superseded`), fail all pending with a `502`, and do NOT `Unbind` (the route belongs to the new node).
If `Heartbeat` reports MISSING (the route's TTL lapsed while the WS stayed healthy — e.g. a Redis
interruption > `--route-ttl`), SELF-HEAL: re-`Bind(name, nodeID, fingerprint, connID)` immediately (the live
WS legitimately owns the name; the fingerprint guard still applies — an `ErrNameHeldByOther` on
re-bind means another conn took the name meanwhile → treat as `superseded`). Without this, a live
tunnel would stay permanently unrouteable until its WS dropped for an unrelated reason.

### Task 6.4: Shared test helpers + unit tests
**Shared test infrastructure — authored IN FULL here: the single raw `coder/websocket` fake-phone
dialer (NOT the US11 Go client; reused by US6 and US10 tests). The capturing `observ.Recorder` fake
(`tunneltest.Recorder`) was authored in US1 Task 1.5 and is reused here.**

**File**: `tunneld/internal/tunneltest/fakephone.go` — full implementation:
```go
package tunneltest

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"

	"github.com/coder/websocket"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
)

// FakePhone is a raw coder/websocket client that completes the /connect CHALLENGE/AUTH handshake and
// bridges inbound REQUEST_* frames to a local http.Handler, streaming the response as RESPONSE_* frames.
type FakePhone struct {
	ws   *websocket.Conn
	done chan struct{}
}

// Dial connects to connectURL (Host set to hostName), authenticates with cert+key, and starts serving.
func Dial(ctx context.Context, connectURL, hostName string, cert *x509.Certificate, key crypto.Signer,
	handler http.Handler) (*FakePhone, error) {
	ws, _, err := websocket.Dial(ctx, connectURL, &websocket.DialOptions{Host: hostName})
	if err != nil {
		return nil, err
	}
	// Largest legal inbound frame = one REQUEST_BODY_CHUNK (ChunkSize body) or a REQUEST_HEAD whose
	// header JSON is bounded by the server's request-header cap; the library default (32768) is just
	// under a full chunk frame. Same constant on both sides of the protocol.
	ws.SetReadLimit(int64(wire.ChunkSize) + 64*1024)
	// 1) read CHALLENGE
	typ, hdr, _, err := readFrame(ctx, ws)
	if err != nil || typ != wire.CHALLENGE {
		ws.Close(websocket.StatusPolicyViolation, "no challenge")
		return nil, fmt.Errorf("expected CHALLENGE: %w", err)
	}
	var ch struct{ Nonce []byte `json:"nonce"` }
	if err := json.Unmarshal(hdr, &ch); err != nil {
		ws.Close(websocket.StatusPolicyViolation, "bad challenge")
		return nil, err
	}
	// 2) sign ConnectAuthContext‖nonce and send AUTH
	digest := sha256.Sum256(append([]byte(ca.ConnectAuthContext), ch.Nonce...))
	sig, err := ecdsa.SignASN1(rand.Reader, key.(*ecdsa.PrivateKey), digest[:])
	if err != nil {
		ws.Close(websocket.StatusInternalError, "sign")
		return nil, err
	}
	auth, _ := json.Marshal(map[string]string{
		"cert":      base64.StdEncoding.EncodeToString(cert.Raw),
		"signature": base64.StdEncoding.EncodeToString(sig),
	})
	if err := writeFrame(ctx, ws, wire.AUTH, auth, nil); err != nil {
		ws.Close(websocket.StatusInternalError, "auth")
		return nil, err
	}
	p := &FakePhone{ws: ws, done: make(chan struct{})}
	go p.serve(handler)
	return p, nil
}

// serve accumulates REQUEST_HEAD + REQUEST_BODY_CHUNKs per reqid, dispatches on REQUEST_END (the
// protocol's dispatch trigger) against an httptest.ResponseRecorder — inline, sequential dispatch is
// fine for a test fake — and writes the response back as RESPONSE_HEAD + chunked
// RESPONSE_BODY_CHUNK(32KiB) + RESPONSE_END.
func (p *FakePhone) serve(handler http.Handler) {
	defer close(p.done)
	ctx := context.Background()
	type partial struct{ hdr, body []byte }
	pending := map[string]*partial{}
	for {
		typ, hdr, body, err := readFrame(ctx, p.ws)
		if err != nil {
			return
		}
		switch typ {
		case wire.REQUEST_HEAD:
			pending[wire.FrameReqID(hdr)] = &partial{hdr: hdr}
		case wire.REQUEST_BODY_CHUNK:
			if pr := pending[wire.FrameReqID(hdr)]; pr != nil {
				pr.body = append(pr.body, body...)
			}
		case wire.REQUEST_END:
			reqid := wire.FrameReqID(hdr)
			pr := pending[reqid]
			if pr == nil {
				continue // unknown/stale reqid: drop
			}
			delete(pending, reqid)
			_, req := wire.DecodeReqHeader(pr.hdr, pr.body) // *http.Request from head + accumulated body
			rr := httptest.NewRecorder()
			handler.ServeHTTP(rr, req)
			_ = writeFrame(ctx, p.ws, wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, rr.Code, rr.Header()), nil)
			for _, chunk := range chunkBy(rr.Body.Bytes(), wire.ChunkSize) {
				_ = writeFrame(ctx, p.ws, wire.RESPONSE_BODY_CHUNK, wire.EncodeReqIDHeader(reqid), chunk)
			}
			_ = writeFrame(ctx, p.ws, wire.RESPONSE_END, wire.EncodeReqIDHeader(reqid), nil)
		default:
			continue
		}
	}
}

func (p *FakePhone) Close() error { err := p.ws.Close(websocket.StatusNormalClosure, ""); <-p.done; return err }
```
The three helpers, in full (same file; `wire.DecodeReqHeader`/`EncodeRespHeader` are the envelope↔http
adapters declared in US6.1):
```go
func readFrame(ctx context.Context, ws *websocket.Conn) (wire.FrameType, []byte, []byte, error) {
	_, data, err := ws.Read(ctx)
	if err != nil {
		return 0, nil, nil, err
	}
	return wire.DecodeFrame(data)
}

func writeFrame(ctx context.Context, ws *websocket.Conn, t wire.FrameType, header, body []byte) error {
	return ws.Write(ctx, websocket.MessageBinary, wire.EncodeFrame(t, header, body))
}

// chunkBy splits b into ≤n-byte pieces; an empty b yields NO pieces — the canonical encoding of an
// empty body is ZERO body-chunk frames in BOTH directions (symmetric; receivers MUST also tolerate
// a zero-length chunk frame).
func chunkBy(b []byte, n int) [][]byte {
	var out [][]byte
	for len(b) > n {
		out = append(out, b[:n])
		b = b[n:]
	}
	if len(b) > 0 {
		out = append(out, b)
	}
	return out
}
```

**File**: `tunneld/internal/wire/frame_test.go`, `tunneld/internal/wsconn/*_test.go`

**Setup**: in-process `net.Pipe`-backed WS via `coder/websocket` accept/dial; an in-test CA + enrolled EC key/cert; `tunneltest.FakePhone` completes the challenge-response then echoes requests as responses; miniredis for the registry; the shared `tunneltest.Recorder` capturing `Reject`/`WSConnect`/`WSDisconnect`/`Bytes` calls.

| Test | Verifies |
|------|----------|
| `frame encode/decode round trip` | All frame types incl. CHALLENGE/AUTH; header/body preserved incl. empty body and 32 KiB body |
| `connect non-upgrade 426` | A plain (non-WebSocket-upgrade) `GET`/`POST /connect` → `426 Upgrade Required`, handled by the manager, never the public allowlist |
| `connect missing client-ip 400` | Request without `--client-ip-header` → `400 missing_client_ip`, no upgrade (fail-closed) |
| `connect rejects banned ip` | Ban hit → `403` refused before WS upgrade |
| `connect binds after valid challenge-response` | Valid cert + valid nonce signature + CN==host → routing bound; request reaches the fake phone and response returns |
| `recorder ws-lifecycle + bytes wired` | Fake `observ.Recorder` captures `WSConnect()` on bind, `WSDisconnect(reason)` on close, and `Bytes(direction,n)` for a paced chunk |
| `connect rejects bad possession signature` | AUTH with a signature by a different key → WS close, no bind |
| `connect rejects CN != host name` | Cert CN differs from Host `<name>` → close, no bind |
| `connect rejects auth timeout` | No AUTH within `--connect-auth-timeout` → close, no bind |
| `connect refuses banned tunnel name/fp` | `MatchTunnel` hit after auth → distinct close, reason `banned_tunnel_name`/`banned_tunnel_fingerprint`, no bind |
| `evict drops live banned tunnel` | Ban-file reload adds the connected tunnel's name/fingerprint → `EvictBanned` closes the live `Conn` + unbinds |
| `connect per-ip rate limited` | Connect attempts over `--limit-rpm` for one IP → `429` before upgrade; `Recorder.Count("reject","rate_connect")==1` |
| `connect edge reject reasons recorded` | Each connect-edge rejection records the LITERAL reason: fake `Recorder` sees `missing_client_ip`/`connect_pending`/`connect_auth_failed`/`fingerprint_conflict` for the respective failure paths |
| `keepalive ping cadence` | Native WS control pings are sent at ~`--ping-interval` (assert ≥1 ping within the interval window) |
| `connect pre-auth semaphore full` | More than `--limit-connect-pending` in-flight pre-auth handshakes → `503` before upgrade |
| `concurrent requests demux by reqid` | 4 in-flight requests over one `Conn` get their correct responses (no cross-talk) — proves `reqid`-keyed demux |
| `ERROR frame resolves pending as 502` | Phone sends `ERROR{reqid,message}` → that `Do` resolves with a synthetic `502` (`ErrCode="phone_error"`, `Err=message`); unknown reqid dropped |
| `node deadline releases pending` | Phone that never sends `RESPONSE_END` (WS stays up) → `Do` returns on `--limit-request-timeout`; `pending[reqid]` removed, goroutine exits (no leak) |
| `concurrent writes serialized` | Many concurrent `Do`s writing frames over one WS never overlap (`writeMu`); no `coder/websocket` concurrent-write panic |
| `chunked response reassembles` | Multi-chunk response reassembled byte-exact |
| `large request body paced through` | `Do` with a multi-chunk body larger than the bucket `burst` (small test burst) completes — per-chunk `WaitN` pacing, `writeMu` released between frames, no stall-to-timeout |
| `request chunks reassemble on phone` | Multi-chunk request body reassembled byte-exact by `FakePhone` before dispatch (dispatch ONLY on `REQUEST_END`); an empty-body request (zero chunks) dispatches with an empty body |
| `zero-length chunk frame tolerated` | A zero-length `RESPONSE_BODY_CHUNK` mid-stream (read-pump) and a zero-length `REQUEST_BODY_CHUNK` (`FakePhone`) are ACCEPTED — append nothing, reassembly byte-exact (the AC's receiver-tolerance MUST, exercised at the reassembly layer) |
| `co-located request not double-paced` | Envelope with `PacedByNode` == this node's ID → `Do` writes chunks WITHOUT drawing up-bucket tokens (bucket level unchanged); a foreign `PacedByNode` → chunks paced normally |
| `response over cap aborts` | Response exceeding `--limit-response` → synthetic 502, memory bounded; `Recorder.Count("reject","response_too_large")==1` (recorded once at the node) |
| `ws drop fails pending + unbinds` | Closing the fake phone fails in-flight with 502 and removes the route |
| `heartbeat not-owner closes stale conn` | `Heartbeat` reporting not-owner (route re-bound elsewhere) → local `Conn` closed with reason `superseded`, pending failed with 502, route left untouched (no `Unbind`) |
| `lapsed route re-bound by heartbeat` | Expire `route:{name}` in miniredis while the WS stays up → next heartbeat re-`Bind`s (self-heal); the tunnel is routable again and the `Conn` is NOT closed |
| `same-node reconnect not clobbered` | Phone re-dials the SAME node while the stale `Conn` is still registered → new `Conn` binds and serves; the stale conn's later teardown neither deletes the new route (`connID` guard) nor removes the new `Conn` from `m.conns` (`CompareAndDelete`) — requests keep flowing |
| `fingerprint conflict rejected` | Second connect, same name, different cert fp → distinct close, route unchanged |

### Definition of Done
- [x] US6 test tables authored and committed (execution in US16).
- [x] Golden frame fixtures (from US5/US6) committed under `tunneld/internal/wire/testdata/`.

---

## User Story 7: Public HTTP ingress

The public side: parse the tunnel name from Host, enforce the allowlist, sanitize headers, apply
ban/rate/size/concurrency limits, and dispatch through Redis to the phone.

### Acceptance Criteria
- [x] Host `<name>.<tunnel-domain>` → tunnel name; unknown host / no tunnel → `404`. `/connect` on this host is handed to the WebSocket manager (US6), NOT the public pipeline.
- [x] After route resolution, `ban.MatchTunnel(name, fingerprint)` (fingerprint from `Lookup`) → `403` — a just-banned tunnel is refused at ingress without waiting for `EvictBanned` to remove the route.
- [x] Source IP from the MANDATORY `--client-ip-header` (no default; `Cf-Connecting-Ip` orange / `X-Real-Ip` grey; single value, or right-most token for `X-Forwarded-For`); a request without the header → `400` reason `missing_client_ip` (fail-closed); client-injected left-most `X-Forwarded-For` entries MUST NOT influence the ban/rate/quota key.
- [x] Ban check FIRST (trusted source IP), before anything else → `403`.
- [x] Allowlist enforced by exact method+path (and the `^/s/[0-9a-f]{64}$` regex); non-allowlisted → `404`; `GET /mcp` → `405` at edge; `OPTIONS` on allowlisted paths → forwarded.
- [x] NO edge Authorization check on ANY path (user decision — see the allowlist design section): token-less `POST`/`DELETE /mcp` is forwarded so the app's own `401` + RFC 9728 `WWW-Authenticate` discovery header reaches OAuth clients; the app is the sole authenticator.
- [x] Any request carrying a client-cert / mTLS-indicating header on the PUBLIC side → `400` (fixed header-name set; the app does not support client mTLS).
- [x] Client-supplied `X-Forwarded-*`/`Forwarded` stripped; proxy-set proto/host/for forwarded to the phone; hop-by-hop headers stripped both directions.
- [x] Size caps: request headers (`16kb` total / `8kb` single) → `431`; body (`1mb`) → `413` (declared oversize `Content-Length` → immediate `413` with NO body read; chunked/undeclared bodies bounded by actual-bytes-read); response (`10mb`) enforced in US6.
- [x] The request body is read through a PACED reader drawing from the per-process `BucketRegistry` up-bucket for the resolved tunnel (≤ `ChunkSize` slices) — client uploads arrive at the paced rate via TCP backpressure. Client-side egress is deliberately unpaced (already produced at the paced phone-leg rate).
- [x] Per-IP `rps`/`rpm` → `429`+`Retry-After`; per-tunnel concurrency → `429`.
- [x] Timeout `60s` → `504`; tunnel offline → `502`.

### Task 7.1: Allowlist
**File**: `tunneld/internal/ingress/allowlist.go` — create a static allowlist:
`map[string]route` where route = `{class string /* "mcp"|"oauth"|"share" — the tunneld_http_requests_total class label, owned here */; edgeHandled func or nil}` (NO auth field — the edge performs no
authentication on any path, per the user decision in Design Decisions). Encode exactly the
endpoint table from Design Decisions. `/s/{token}` matched via a precompiled `regexp` for the whole
path; `.well-known/...` prefixes matched explicitly (with `/{tail...}` suffix tolerance for the two
metadata routes). `Match(method, path) (route, decision)` where decision ∈
`{forward, edge405, deny404}`. The `edge405` response MUST carry `Allow: POST, DELETE` (RFC 9110
§15.5.6 requires `Allow` on a 405; it also matches what the app itself answers for `GET /mcp`).
DELIBERATE coarseness: `edge405` exists ONLY for `GET /mcp` (app parity for the one path clients
probe); any OTHER method mismatch on an allowlisted path (e.g. `DELETE /register`) is `deny404` —
the edge is an allowlist, not a per-path RFC-status mirror, and a 404 leaks less about the surface.
**Context**: source of truth is the app code cited in References — the reviewer will diff this
allowlist against those files.

### Task 7.2: Header sanitization
**File**: `tunneld/internal/ingress/headers.go` — `Sanitize(in http.Header) (out http.Header, rejected
bool)`: reject (`rejected=true`) if ANY header in the fixed client-cert / mTLS-indicating set is
present (`X-Forwarded-Tls-Client-Cert`, `X-Forwarded-Tls-Client-Cert-Info`, `Ssl-Client-Cert`,
`X-Client-Cert`, `X-Ssl-Client-Cert` — case-insensitive); drop hop-by-hop + all
`X-Forwarded-*`/`Forwarded`; re-add only the proxy-set proto/host/for we trust — the proto header is
hardcoded `X-Forwarded-Proto` (symmetric with host/for; not a configurable flag); copy the rest. A
reverse function strips hop-by-hop from responses. (Note: with orange-cloud, Cloudflare terminates
client TLS and never emits these; the check is defense-in-depth for the standing "reject mTLS
headers" requirement.)

### Task 7.3: The handler pipeline
**File**: `tunneld/internal/ingress/handler.go` — create `Handler` (deps: `ban`, `router`,
`limit.BucketRegistry` (US3.3 — the same instance injected into the wsconn manager),
`rdb redis.UniversalClient` (the `limit.Allow`/`Acquire` and `transport.RoundTrip` package functions
take it as an argument), `nodeID string` (this process's identity — step 8 stamps it into
`ReqEnvelope.PacedByNode` for the US6.2 double-pacing guard),
`transport`, `limit`, `clientip`, `cfg`, and an injected `observ.Recorder` from US1 Task 1.5 — the
concrete `PromRecorder` is provided in US9 and injected in US10, so there is NO forward dependency on
the metrics/caplog packages). Compose in ORDER (uses `clientip.TrustedIP` from the shared package
created in US6):
1. `clientip.TrustedIP(r, cfg.ClientIPHeader)` (the MANDATORY `--client-ip-header`);
   absent/unparseable → `400` + `rec.Reject("missing_client_ip", "", "")` (no valid IP exists on this
   path; fail-closed, never defaulted to a forgeable value). Then `ban.Match(ip)` → `403` +
   `rec.Reject(source.Reason.String(), "", ip.String())` — the tunnel name is passed `""` because Host
   is not parsed until step 3 (matching the `/connect` and `/enroll` ban steps); reason = the MATCHED
   `ban.Source.Reason` (`banned_ip`/`banned_cidr`/`banned_country`). `Recorder.Reject`'s `clientIP` is a
   `string`, so callers pass `ip.String()` (or `""` when no IP).
2. reject any client-cert / mTLS-indicating header (fixed set, via `Sanitize`) → `400` reason
   `public_mtls_header`.
3. parse Host → name; `router.Lookup` → `404` + `rec.Reject("unknown_host", "", ip.String())` if
   absent. Then `ban.MatchTunnel(name, fingerprint)` (the fingerprint comes back from `Lookup`, same
   route entry) → `403` + `rec.Reject("banned_tunnel_name"|"banned_tunnel_fingerprint", name,
   ip.String())` — closes the ≤`--ban-poll` window between a ban-file reload and `EvictBanned`
   removing the route, so a just-banned tunnel is refused at ingress deterministically.
4. allowlist `Match` → `404` + `rec.Reject("path_denied", name, ip.String())` / `405` +
   `rec.Reject("method_denied", name, ip.String())`; `OPTIONS` passes to forward.
5. NO auth check — deliberately (user decision): token-less `/mcp` MUST reach the app so its `401`
   carries the RFC 9728 `WWW-Authenticate` discovery header; the edge never answers `401` itself.
6. size caps: total request headers > `--limit-headers` OR any single header > `--limit-header-single`
   → `431` + `rec.Reject("headers_too_large", name, ip.String())`; request body > `--limit-body` →
   `413` + `rec.Reject("body_too_large", name, ip.String())`. Header measurement is done explicitly by
   summing `len(key)+len(value)` over `r.Header` (Go's `http.Server.MaxHeaderBytes` is ALSO set as a
   coarse backstop — in US10 it is set STRICTLY ABOVE `--limit-headers`, at `2×` the limit, so this
   explicit check always runs first and the `headers_too_large` reason stays attributable; the server
   backstop must never pre-empt it at the boundary. NOTE the ingestion property: the Go server
   enforces `MaxHeaderBytes` DURING header parsing — it stops reading the socket and answers `431`
   before the handler runs, so giant headers never buffer beyond `2 × --limit-headers`). Body
   bounding is TWO-layer: a request DECLARING `Content-Length > --limit-body` → immediate `413` +
   `rec.Reject("body_too_large", …)` WITHOUT reading any body byte; otherwise (including chunked
   transfer-encoding with NO declared length) the body is wrapped in `http.MaxBytesReader`, which
   bounds ACTUAL BYTES READ regardless of what any header claims — its overflow error maps to `413`.
7. per-IP `rps` → `429` + `rec.Reject("rate_rps", name, ip.String())`, `rpm` → `429` +
   `rec.Reject("rate_rpm", name, ip.String())`; then per-tunnel `Acquire` concurrency → `429` +
   `rec.Reject("concurrency", name, ip.String())`. (Each `rec.Reject` reason is the LITERAL registered
   `tunneld_rejections_total{reason}` label — no abbreviations.)
8. `rec.InflightAdd(+1)` (deferred `-1`). END-TO-END DEADLINE: derive
   `reqCtx, cancel := context.WithTimeout(r.Context(), --limit-request-timeout)` HERE and use `reqCtx`
   for EVERYTHING that follows — the paced body read AND `RoundTrip` share this ONE budget (the config
   table calls the flag the "End-to-end request timeout" and this is what makes that true). Without
   it, a client dribbling the body SLOWER than the paced rate is never throttled (pacing only slows
   fast uploads; `MaxBytesReader` bounds bytes, not time) and would hold its per-tunnel concurrency
   slot indefinitely — `--limit-concurrent` slow-body requests would DoS the tunnel. Deadline expiry
   DURING the body read (client too slow) → `408` + `rec.Reject("body_read_timeout", name,
   ip.String())` + slot released. Then: read the (`MaxBytesReader`-wrapped) body through a PACED
   reader: before each ≤ `wire.ChunkSize` read slice, `WaitN(reqCtx, n)` on the up-bucket from
   `BucketRegistry.Pair(name)` (mechanism + rationale: Design Decisions "Limits" — TCP backpressure
   paces the client upload; same bucket instance as the WS leg when this replica holds the WS). Set
   `ReqEnvelope.PacedByNode` = this
   process's `nodeID` so the WS leg skips the DUPLICATE drain when it holds the same bucket (US6.2
   double-pacing guard). Then build `wire.ReqEnvelope` (sanitized headers, proto),
   `transport.RoundTrip(reqCtx, …)` (its `timeout` param is still `--limit-request-timeout`, but the
   ALREADY-ELAPSED read time counts — `reqCtx`'s earlier deadline wins); a timeout → `504` +
   `rec.Timeout()` AND `rec.Reject("timeout", name, ip.String())`; a pub/sub publish error → `502` +
   `rec.PublishError()`; a returned envelope with `ErrCode="tunnel_gone"` (WS dropped mid-round-trip) →
   `502` + `rec.Reject("tunnel_offline", name, ip.String())`. The frontend records `tunnel_offline`
   ONLY for `ErrCode="tunnel_gone"` — it MUST NOT re-attribute an envelope the node already recorded
   (e.g. `ErrCode="response_too_large"`), which is returned to the client as-is. (No-route is already
   `404`+`unknown_host` at step 3.)
9. write status + sanitized response headers + body. Every rejection above calls `rec.Reject(reason,
   name, ip.String())` (the injected `observ.Recorder`; `ip` is the `netip.Addr` from
   `clientip.TrustedIP`, stringified) and a forwarded request calls `rec.Request(name, class, code,
   dur)` — `class` comes from the matched allowlist `route.class` (Task 7.1) — so metric/cap-hit +
   per-tunnel `tcnt` recording uses the interface, not a later-story
   concrete type. (`RoundTrip` returning `ErrTimeout` is the trigger for the `504`+`rec.Timeout()` path;
   node-side response-publish failures are recorded by `ServeNode` itself via its `observ.Recorder`.)

### Task 7.4: Unit tests
**File**: `tunneld/internal/ingress/*_test.go`

**Setup**: `httptest`; miniredis; a stub `router`+`transport` (in-memory) returning canned envelopes; a stub ban engine; the shared `tunneltest.Recorder` capturing `Reject`/`Request` calls.

| Test | Verifies |
|------|----------|
| `allowlist forwards mcp post with auth` | `POST /mcp` + Authorization → forwarded |
| `mcp post without auth forwarded` | Token-less `POST /mcp` → forwarded UNCHANGED (the app answers `401` with its RFC 9728 `WWW-Authenticate` discovery header; the edge never swallows it) |
| `get mcp is 405 at edge` | `GET /mcp` → 405 WITH `Allow: POST, DELETE` (RFC 9110), not forwarded; `Recorder.Count("reject","method_denied")==1` |
| `options forwarded` | `OPTIONS /mcp` (no auth) → forwarded |
| `oauth endpoints need no auth` | `/register`,`/authorize`,`/token`,`/.well-known/...` forwarded without Authorization |
| `share path regex enforced` | `/s/<64hex>` forwarded; wrong length/case/`../` → 404 |
| `non-allowlisted 404` | `GET /` or `POST /foo` → 404; `Recorder.Count("reject","path_denied")==1` |
| `banned tunnel 403 at ingress` | Resolved route's name (or fingerprint) matches a tunnel ban → 403 before forwarding; `Recorder.Count("reject","banned_tunnel_name")==1` |
| `banned ip 403 first` | Ban engine hit short-circuits before allowlist |
| `spoofed xff ignored for ip key` | With `X-Forwarded-For: <spoof>, <realproxyhop>` the key uses the RIGHT-MOST (proxy) entry, not the client-injected left one |
| `missing client-ip header 400` | Request without `--client-ip-header` → 400, reason `missing_client_ip` (fail-closed) |
| `public client-cert header 400` | Request carrying a client-cert header → 400; `Recorder.Count("reject","public_mtls_header")==1` |
| `forwarded headers sanitized` | Client `X-Forwarded-Proto` dropped; proxy value forwarded; request hop-by-hop stripped |
| `response hop-by-hop stripped` | The reverse sanitizer removes hop-by-hop headers from the phone's response before returning it |
| `body over cap 413` | 1 MB+ body → 413; `Recorder.Count("reject","body_too_large")==1` |
| `declared oversize content-length 413 without read` | `Content-Length` > `--limit-body` → immediate 413; the body reader is never consumed |
| `chunked body over cap 413` | Chunked transfer-encoding (no `Content-Length`) exceeding `--limit-body` → 413 at the `MaxBytesReader` bound (actual-bytes-read limit, not header-declared) |
| `ingress body read is paced` | Reading a multi-slice body drains the registry's up-bucket per ≤`ChunkSize` slice (tiny test bucket: token level / fake-clock elapsed asserted) — the paced reader is wired, uploads cannot line-rate burst |
| `slow body read 408 releases slot` | A body dribbled slower than the deadline (tiny test `--limit-request-timeout`) → `408` + `Recorder.Count("reject","body_read_timeout")==1`; the concurrency slot is RELEASED (a follow-up request Acquires successfully) — the slow-body DoS is bounded by the end-to-end deadline |
| `total headers over cap 431` | Summed header bytes > 16kb → 431; fake `Recorder` received `Reject("headers_too_large", …)` |
| `single header over cap 431` | One header > 8kb → 431 |
| `rps/rpm 429 with retry-after` | Over limit → 429 + Retry-After; `Recorder.Count("reject","rate_rps")` / `Count("reject","rate_rpm")` each `==1` for the respective trigger |
| `concurrency 429` | 5th concurrent for a tunnel → 429; `Recorder.Count("reject","concurrency")==1` |
| `timeout 504 records writers` | `RoundTrip`→`ErrTimeout` → 504; `Recorder.Count("timeout","")==1` AND `Count("reject","timeout")==1` |
| `offline 502 records tunnel_offline` | Envelope `ErrCode="tunnel_gone"` → 502; `Recorder.Count("reject","tunnel_offline")==1` |
| `publish error 502 records PublishError` | `RoundTrip` publish failure → 502; `Recorder.Count("publisherror","")==1` |
| `unknown host 404` | Host without a bound tunnel → 404; `Recorder.Count("reject","unknown_host")==1` |
| `inflight add/sub paired` | A forwarded request produces `InflightAdd(+1)` then `InflightAdd(-1)` (fake `Recorder` sees both, net 0) — the `tunneld_http_inflight` gauge has a wired writer |

### Definition of Done
- [x] US7 test tables authored and committed (execution in US16).
- [x] Allowlist matches the cited app routes exactly (reviewer will verify).

---

## User Story 8: Enrollment HTTP endpoint

`POST /enroll` on the enroll host: ban check, enrollment quota, CSR → signed certificate.

### Acceptance Criteria
- [x] `POST /enroll` (on `--enroll-host`) accepts a CSR (PEM in body), ban-checks the source IP (`403`), applies enrollment quota (`429`+`Retry-After`), generates a name, signs, and returns the leaf PEM + assigned name as JSON.
- [x] Body size capped at `--limit-enroll-body` (default `16kb`) via `http.MaxBytesReader` → `413`; malformed CSR → `400`; a CSR whose public key is not ECDSA P-256 (US4 `SignCSR` sentinel) → `400` with error `unsupported_key_type`. EACH of these records via the injected `Recorder`: `rec.Reject("enroll_body_too_large"|"enroll_malformed_csr"|"enroll_unsupported_key", "", ip.String())` — every registered enroll-side `tunneld_rejections_total{reason}` label has a writer.
- [x] `Retry-After` body is a clear JSON message the app can surface ("you or your network enrolled too many times, retry in N seconds").
- [x] No Redis persistence of the identity (only the transient quota counters).

### Task 8.1: Enroll handler
**File**: `tunneld/internal/ingress/enroll.go` — create `EnrollHandler` (deps include the injected
`observ.Recorder` AND `rdb redis.UniversalClient` — `limit.AllowEnroll` takes it as an argument)
using `clientip.TrustedIP` (same `--client-ip-header` source as the public handler;
absent → `400` + `rec.Reject("missing_client_ip", "", "")`) → `ban.Match` FIRST (banned → `403` +
`rec.Reject(source.Reason.String(), "", ip.String())`) → `limit.AllowEnroll` (over-quota → `429` +
`rec.Reject("enroll_rate", "", ip.String())`) → `ca.GenerateName` + `ca.SignCSR` (success →
`rec.Enrollment()`; the non-P256-key sentinel → `400` JSON error `unsupported_key_type` +
`rec.Reject("enroll_unsupported_key", "", ip.String())`; malformed CSR → `400` +
`rec.Reject("enroll_malformed_csr", "", ip.String())`); body bounded by `--limit-enroll-body`
(overflow → `413` + `rec.Reject("enroll_body_too_large", "", ip.String())`). The WHOLE handler runs
under `context.WithTimeout(r.Context(), --limit-request-timeout)` — a dribbled CSR body cannot hold
the connection open indefinitely; expiry during the read → `408` +
`rec.Reject("body_read_timeout", "", ip.String())`.
Response JSON: `{ "name": "...", "hostname": "<name>.<tunnel-domain>", "connect_url":
"wss://<name>.<tunnel-domain>/connect", "certificate_pem": "...", "expires_at": <unix> }`. (`hostname`
and `connect_url` are REQUIRED for the flow — the phone does not know its server-assigned name or the
tunnel domain otherwise; `expires_at` tells the phone when to re-enroll.)

### Task 8.2: Route wiring
**File**: `tunneld/internal/server/routes.go` — create the mux that dispatches by Host:
- `--enroll-host` → `POST /enroll` → `EnrollHandler` (else `404`).
- any other host under `*.<tunnel-domain>` (a per-tunnel host `<name>.<tunnel-domain>`): the ENTIRE
  `/connect` path → `wsconn.Manager.HandleConnect` (the manager itself answers a non-WebSocket-upgrade
  `/connect` with `426 Upgrade Required`); everything else → `ingress.Handler` (public pipeline).
  `/connect` is reserved and NEVER reaches the allowlist, regardless of method/upgrade.
(This mux is wired into `server.Run` in US10.)

### Task 8.3: Unit tests
**File**: `tunneld/internal/ingress/enroll_test.go`

**Setup**: in-test CA; generate a CSR; miniredis for quota.

| Test | Verifies |
|------|----------|
| `enroll signs valid csr` | Valid CSR → 200, returns a cert that verifies against the CA, CN == returned name |
| `enroll rejects malformed csr` | Garbage body → 400 |
| `enroll rejects non-P256 key csr` | Valid CSR with an RSA key → 400, JSON error `unsupported_key_type` |
| `enroll banned ip 403` | Ban hit → 403 before signing |
| `enroll quota 429` | 3rd/minute → 429 + Retry-After + clear JSON body |
| `enroll body cap` | Body > `--limit-enroll-body` → 413 |
| `enroll uses trusted client-ip` | Ban/quota key from `--client-ip-header`, not a spoofed `X-Forwarded-For` |
| `enroll missing client-ip 400` | Request without `--client-ip-header` → `400 missing_client_ip` (fail-closed) |
| `enroll rejections recorded` | Fake `Recorder` sees the LITERAL reasons `enroll_malformed_csr`/`enroll_body_too_large`/`enroll_unsupported_key` for the respective failure paths |
| `enroll response fields complete` | Success JSON contains ALL required fields: `name`, `hostname` == `<name>.<tunnel-domain>`, `connect_url` == `wss://<name>.<tunnel-domain>/connect`, `certificate_pem`, `expires_at` ≈ now + `--cert-validity` |

### Definition of Done
- [x] US8 test tables authored and committed (execution in US16).
- [x] Issued certs verify end-to-end with US6 `/connect`.

---

## User Story 9: Metrics, internal listener, admin endpoint, and cap-hit logging

Prometheus metrics + `/healthz` + `/admin/tunnels` on the internal listener, and the deduplicated
cap-hit event logger wired into all rejection points.

### Acceptance Criteria
- [x] Internal listener serves `GET /metrics` (Prometheus), `GET /healthz` (200 if Redis PING ok else 503), `GET /admin/tunnels` (JSON top-N by bytes/requests from Redis).
- [x] Metric families exactly as listed below; NO per-tunnel labels.
- [x] A `PromRecorder` implements the `observ.Recorder` interface (US1 Task 1.5) by combining the metric registry + the cap-hit deduping logger; the US6/US7/US8 handlers were built against that interface, so this story provides the concrete implementation (no forward dependency).
- [x] Rejection sites (via `Recorder.Reject`) increment `tunneld_rejections_total{reason=...}` and emit a cap-hit log via the deduping logger (first per `(tunnel,reason)` immediately, then ≤1 summary/min).
- [x] Per-tunnel live counters (bytes in/out, requests) stored in Redis with TTL, feeding `/admin/tunnels`.

### Task 9.1: Metric registry
**File**: `tunneld/internal/metrics/metrics.go` — register:
`tunneld_tunnels_connected` (gauge), `tunneld_enrollments_total`, `tunneld_ws_connects_total`,
`tunneld_ws_disconnects_total{reason}`, `tunneld_http_requests_total{class=mcp|oauth|share,code}`,
`tunneld_http_request_duration_seconds` (histogram), `tunneld_http_inflight` (gauge),
`tunneld_rejections_total{reason}` (reasons: `missing_client_ip`,`rate_rps`,`rate_rpm`,`concurrency`,
`rate_connect`,`connect_pending`,`connect_auth_failed`,`fingerprint_conflict`,`body_too_large`,
`response_too_large`,`headers_too_large`,`path_denied`,`method_denied`,`public_mtls_header`,
`banned_ip`,`banned_cidr`,`banned_country`,`banned_tunnel_name`,`banned_tunnel_fingerprint`,`enroll_rate`,
`enroll_malformed_csr`,`enroll_body_too_large`,`enroll_unsupported_key`,
`unknown_host`,`tunnel_offline`,`timeout`,`body_read_timeout`),
`tunneld_bytes_total{direction}`, `tunneld_pubsub_publish_errors_total`,
`tunneld_request_timeouts_total`.
**Context**: request `class` derives from the path (mcp/oauth/share); `/health` is never exposed so
no `health` class. `rate_connect` = per-IP `/connect` attempt limit (429); `connect_pending` = pre-auth
semaphore full (503); `banned_tunnel_*` = `MatchTunnel` refusal at connect. The `tunneld_ws_disconnects_total{reason}`
label also carries `banned_tunnel_name`/`banned_tunnel_fingerprint` for `EvictBanned` live teardowns
and `superseded` for stale conns closed after a same-fingerprint re-bind on another node (US6.3).

### Task 9.2: Internal HTTP server
**File**: `tunneld/internal/metrics/server.go` — declare `type AdminSource interface { TopN(ctx
context.Context, n int) ([]admin.TunnelStat, error) }` and `Serve(ctx, addr, reg *prometheus.Registry,
rdb, admin AdminSource, log)`
mounting `/metrics` via `promhttp.HandlerFor(reg, …)` — the metric families are registered into a
CUSTOM `prometheus.NewRegistry()` (the same `reg` held by `PromRecorder`, Task 9.4; the default
registry / bare `promhttp.Handler()` would expose NONE of them), `/healthz` (Redis `PING`),
`/admin/tunnels` (serialises `admin.TopN`). `TunnelStat` lives in the `admin` package (NOT `metrics`) — `metrics` already imports
`admin` for the `PromRecorder`'s `*admin.Store` (Task 9.4), and `admin` imports NOTHING from
`metrics`; placing the type anywhere else would create a compile-breaking import cycle.
**File**: `tunneld/internal/admin/tunnels.go` — `type TunnelStat struct{ Name string; BytesIn,
BytesOut, Requests int64 }`; `type Store struct{ rdb ... }` (satisfies
`metrics.AdminSource`) holding per-tunnel counters in Redis (`tcnt:{name}` hash:
`bytes_in`,`bytes_out`,`requests`). `func (s *Store) Incr(ctx, name, field string, n int64) error` is
the WRITE path — called by `PromRecorder`'s BACKGROUND FLUSHER (Task 9.4) with batched deltas, NEVER
synchronously per `Request`/`Bytes` event on the data plane — and MUST do the `HINCRBY` +
`PEXPIRE` in a SINGLE Lua script (never `HINCRBY` then a separate `EXPIRE`) so the key is always
TTL'd — same single-Lua TTL invariant as the US3.1/US3.2 limiters and US16.3. `func (s *Store)
TopN(ctx, n)` scans/sorts for the read side.
**Context**: `/admin/tunnels` is internal-only (never routed by the proxy). Cardinality stays out of
Prometheus; the admin endpoint is the per-tunnel view.

### Task 9.3: Cap-hit deduping logger
**File**: `tunneld/internal/caplog/caplog.go` — create
```go
type Logger struct{ /* map[key]*state, mu, window=1m, log *slog.Logger */ }
func (l *Logger) Hit(tunnel, reason, clientIP string, fields ...any) // first immediate; then ≤1 summary/min
```
**Context**: key = `tunnel|reason`. First hit logs immediately at WARN; subsequent hits within the
window increment a counter; a LAZY flush (on the next hit past the window, and at idle-key eviction —
NO per-key tickers/goroutines, so there is no ticker lifecycle to leak) emits one summary line
("reason hit N times in last 60s for tunnel from M IPs"). Bounded map: idle keys are evicted (their
pending summary emitted) opportunistically on `Hit`, keeping the map proportional to recently-active
`tunnel|reason` pairs.

### Task 9.4: PromRecorder (implements `observ.Recorder`)
**File**: `tunneld/internal/metrics/recorder.go` — create `PromRecorder{reg, caplog, admin *admin.Store,
agg *tunnelAgg}` implementing the US1 `observ.Recorder` interface: `Reject` bumps
`tunneld_rejections_total{reason}` AND calls the US9.3 cap-hit logger; `Request(name, class, code, dur)`
bumps `tunneld_http_requests_total{class,code}` + the duration histogram; `Bytes(name, dir, n)` bumps
`tunneld_bytes_total{direction}`. **The per-tunnel `tcnt:{name}` Redis counters are NOT written
synchronously on the data plane** — `Request`/`Bytes` only do a fast in-process accumulate into `agg`
(a mutex/sharded `map[name]{requests, bytesIn, bytesOut int64}`), so a slow/blocked Redis never stalls
the bandwidth-paced write loop and the ctx-less interface needs no per-call `ctx`. A background flusher
goroutine (`PromRecorder.RunFlusher(ctx, every)`, started in US10 with the base ctx, default `~5s`,
final flush on ctx cancel) drains the accumulated deltas and applies them via `admin.Store.Incr(ctx,
name, field, delta)`. Each flush DRAINS by swapping in a fresh empty map (under the agg mutex), so
flushed tunnel names are dropped and the accumulator never grows unboundedly across ephemeral tunnels. This is the SINGLE object injected (US10) into the US6/US7/US8 handlers and is
what (asynchronously) writes the `tcnt:{name}` counters backing `/admin/tunnels` — a real production
writer, not just test seeding. (Ordered after Tasks 9.2/9.3 because it depends on `admin.Store` and
the caplog `Logger`.) The REMAINING `Recorder` methods are implemented here too (all trivial metric
bumps): `WSConnect()` → `tunneld_ws_connects_total`++ AND `tunneld_tunnels_connected` gauge `+1`;
`WSDisconnect(reason)` → `tunneld_ws_disconnects_total{reason}`++ AND the gauge `-1` (the gauge is
DERIVED from connect/disconnect — no separate writer); `Enrollment()` → `tunneld_enrollments_total`++;
`InflightAdd(delta)` → `tunneld_http_inflight` gauge `Add(delta)`; `Timeout()` →
`tunneld_request_timeouts_total`++; `PublishError()` → `tunneld_pubsub_publish_errors_total`++.

### Task 9.5: Unit tests
**File**: `tunneld/internal/metrics/*_test.go`, `tunneld/internal/caplog/*_test.go`, `tunneld/internal/admin/*_test.go`

**Setup**: `httptest` + a `slog` handler capturing records into a buffer; miniredis.

| Test | Verifies |
|------|----------|
| `healthz 200 when redis up` | PING ok → 200 |
| `healthz 503 when redis down` | miniredis closed → 503 |
| `metrics endpoint exposes families` | `/metrics` contains registered names |
| `no per-tunnel metric labels` | Parse `/metrics`; assert NO metric carries a `tunnel`/`name` label (cardinality guard) |
| `rejection increments reason counter` | A denied request bumps `tunneld_rejections_total{reason}` |
| `caplog logs first hit immediately` | First `(tunnel,reason)` → one WARN line |
| `caplog dedups within window` | 1000 hits in a window → 1 immediate + ≤1 summary |
| `caplog summary counts hits + ips` | Summary reports count and distinct IPs |
| `admin topN sorts by bytes` | Counters in Redis → correct ordered JSON |
| `admin counter key has TTL` | miniredis `TTL(tcnt:{name}) > 0` after `Store.Incr` (single-Lua HINCRBY+PEXPIRE, no un-TTL'd key) |
| `PromRecorder flushes tcnt via Request/Bytes` | `Request(name,…)`/`Bytes(name,"in"/"out",…)` accumulate in-process; after one flusher cycle, `tcnt:{name}` `requests`/`bytes_in`/`bytes_out` reflect the totals (real async write path, NOT seeded) |

### Definition of Done
- [x] US9 test tables authored and committed (execution in US16).
- [x] No per-tunnel labels on any Prometheus metric (asserted in the test tables).

---

## User Story 10: Server assembly and graceful lifecycle

Wire every component together in `server.Run`, bind the public + internal listeners, run the node
pub/sub loop and ban watcher, and shut down cleanly.

### Acceptance Criteria
- [x] `server.Run(ctx, cfg, log, version)` constructs Redis client, CA, ban engine + watcher, routing registry, ONE `limit.BucketRegistry` (injected into BOTH the wsconn manager and the ingress handler — same instance), wsconn manager, metrics/internal server, and the public server; assigns a per-process `nodeID` (`crypto/rand`).
- [x] Public listener serves the Host-dispatch mux (US8.2); internal listener serves US9.
- [x] `ServeNode` loop runs, routing `req:{nodeID}` → local `Conn.Do`.
- [x] On ctx cancel: stop accepting, drain in-flight up to `--shutdown-grace`, close WSes, unbind routes, close Redis, flush logs.

### Task 10.1: Run + lifecycle
**File**: `tunneld/internal/server/server.go` — replace the US1 `Run` stub with the real assembly using
an `errgroup` for: public `http.Server`, internal server, `transport.ServeNode` (passed
`--limit-request-timeout` as its per-message `timeout` and the
`PromRecorder` for node-side `PublishError` recording), `PromRecorder.RunFlusher(ctx, ~5s)` (the async
`tcnt:{name}` flusher — keeps per-tunnel counters off the data plane), `ban.Watch`. STARTUP ORDER:
`Run` performs the INITIAL `engine.Load(...)` SYNCHRONOUSLY, before any listener starts accepting —
"ban-first" must hold from the very first request (per US2 semantics this load itself is lenient:
absent/unreadable files and bad lines are skip-and-warn, first-deploy compatible — the point here is
ORDERING, not strictness); `ban.Watch`'s own initial load then merely refreshes (idempotent atomic
swap).
`ban.Watch` is passed
`onReload = wsconnManager.EvictBanned` so a ban-file reload immediately tears down any live tunnel
whose name/fingerprint became banned. The `observ.Recorder` (US9 `PromRecorder`, built with the
metric registry + cap-hit logger + the `admin.Store`) is constructed here and injected into the wsconn
manager, public `ingress.Handler`, and `EnrollHandler`; the same `admin.Store` is the internal
server's `AdminSource`. The public `http.Server` sets `MaxHeaderBytes = 2 × --limit-headers` (strictly
above the explicit US7 step-6 check, so that check always runs first and the `headers_too_large`
reason stays attributable) AND `ReadHeaderTimeout: 10 * time.Second` (a constant, not a flag) — it
bounds slow-header (Slowloris) transmission on the public edge, which `--limit-request-timeout`
cannot (that ctx starts AFTER headers are parsed), and `gosec` G112 fails the US16 lint gate without
it. The internal `http.Server` sets the same `ReadHeaderTimeout`. `ReadTimeout` is deliberately NOT
set — it would kill legitimately paced (slow) body uploads; `/connect` is unaffected either way (a
hijacked WS conn escapes server timeouts). Graceful shutdown
via `http.Server.Shutdown(ctxGrace)` bounded by `--shutdown-grace` + explicit WS closes +
`Registry.Unbind` for every local conn.

### Task 10.2: Integration-style test (in-process, no containers)
**File**: `tunneld/internal/server/server_test.go`

**Setup**: miniredis; a real in-process `tunneld` server via `Run` on `127.0.0.1:0` serving the
enroll host and the per-tunnel wildcard host (override Host header in requests); the fake phone is the
shared `tunneltest.FakePhone` helper from US6.4 (the raw dialer that completes CHALLENGE/AUTH and
echoes a canned HTTP response — the US11 Go client MUST NOT be used here, it is a later story;
US11.2 depends backward on this server instead); an in-test CA. Because Traefik is absent in-process,
the test injects the trusted client-IP header (`--client-ip-header`, set to a test header) itself.
There is NO client-cert header (auth is app-layer over the WS).

| Test | Verifies |
|------|----------|
| `enroll then connect then request` | Full loop: enroll gets a cert → WS `/connect` binds → `POST /mcp` reaches the fake phone and the response returns |
| `request to unbound tunnel 404` | Public request before connect (no route) → 404 |
| `distinct nodeID per process` | Two `Run` instances get different `crypto/rand` `nodeID`s (routing uses the right one) |
| `graceful shutdown drains` | In-flight request completes during shutdown; new ones refused |
| `shutdown unbinds all routes` | After `Run` returns (ctx cancel), no `route:{name}` remains in miniredis |

### Definition of Done
- [x] US10 test tables authored and committed (single in-process node + miniredis; execution in US16).
- [x] Clean shutdown leaves no bound routes in Redis (covered by a test).

---

## User Story 11: Go tunnel client library

A reusable Go client implementing the phone side (enroll + connect + bridge to a local HTTP backend),
used by the in-process test above and the containerized e2e tests.

### Acceptance Criteria
- [x] `client.Enroll(ctx, enrollURL, key) (cert, name, error)` builds a CSR from a provided EC key and returns the signed cert + name.
- [x] `client.Connect(ctx, connectURL, cert, key, backend http.Handler|url)` dials the `/connect` WS, completes the challenge-response (receives `CHALLENGE`, replies `AUTH` with `{base64(DER cert), ECDSA-P256 sig over context‖nonce}`), then bridges each incoming request to a local backend, returning responses as chunked frames.
- [x] Reconnect with exponential backoff on drop (bounded).
- [x] Uses the SAME `wire` package as the server (no protocol drift).

### Task 11.1: Client
**File**: `tunneld/client/client.go` — create `Enroll`, `Connect` (with the CHALLENGE/AUTH handshake),
a `Bridge` that translates `wire.ReqEnvelope` frames → `http.Request` against a configurable backend
and streams the response back as `RESPONSE_*` frames (accumulating `REQUEST_BODY_CHUNK`s per reqid
and dispatching on `REQUEST_END`, exactly like `FakePhone`). Backoff via a small helper. Shares
`ca.ConnectAuthContext` so the signed message matches the server. After dialing, `Connect` MUST
`SetReadLimit(wire.ChunkSize + 64*1024)` — the same protocol constant as the server and `FakePhone`
(largest legal inbound frame is one body chunk or a header-cap-bounded `REQUEST_HEAD`;
`coder/websocket`'s 32768-byte default is just under a full chunk frame).
**Context**: for e2e, the "backend" is a fake phone HTTP server exposing the app's allowlisted routes
returning canned bodies. The client authenticates entirely at the application layer over the WS — no
TLS client cert — so it works identically through Cloudflare (orange) or directly.

### Task 11.2: Unit tests
**File**: `tunneld/client/client_test.go`

**Setup**: point the client at the in-process server from US10; a fake backend `http.Handler`.

| Test | Verifies |
|------|----------|
| `client enroll returns usable cert` | Enrolled cert verifies + connects |
| `client bridges request to backend` | Server-forwarded request hits the backend; response returns intact |
| `client reconnects after drop` | Forced WS close → client re-dials and resumes serving |

### Definition of Done
- [x] US11 test tables authored and committed (against the in-process server; execution in US16).
- [x] Client and server share `wire` with zero duplicated constants.

---

## User Story 12: Support scripts (droplist + DB-IP fetch)

The two fetcher scripts run by the Compose `fetcher` service. Pure shell/Python, atomic handoff.

### Acceptance Criteria
- [x] `fetch-droplist.sh` downloads the Spamhaus DROP feed(s), converts to `cidr <prefix>` ban-file lines, writes a temp file in the target dir and `mv`s it into place. Non-zero exit on download failure WITHOUT clobbering the existing file.
- [x] `fetch-dbip.sh` downloads the current-month DB-IP Country Lite CSV ONLY if the month's file is absent (URL is month-versioned), decompresses, and `mv`s it into place; no-op when already present.
- [x] Both scripts are idempotent and safe to run at container start and via cron.
- [x] No secrets embedded; URLs configurable via env with sane defaults.

### Task 12.1: Droplist script
Both fetch scripts start with a `#!/bin/sh` shebang, use `set -eu`, and contain NO pipelines —
every stage writes a temp file (`curl` downloads to a file first, then `jq` reads it), so plain
`set -e` catches every failure and `pipefail` (undefined in POSIX sh — shellcheck SC3040 would fail
the `make lint` gate) is never needed. They are COMMITTED with the executable bit (git mode
`100755`) — cron and `command.sh` invoke them directly from a read-only mount, so the mode must come
from git.
**File**: `tunneld/deploy/scripts/fetch-droplist.sh` — create: `#!/bin/sh`; `set -eu`; `curl -fsS` the
DROP JSON-lines feed INTO `$OUT_DIR/droplist.feed.tmp`; then `jq` (reading that file) extracts each
line's `cidr` field → `cidr <value>` lines into `$OUT_DIR/droplist.bans.tmp`; then `mv` to
`$OUT_DIR/droplist.bans` (and remove the feed temp). Env: `OUT_DIR`, `DROP_URL`
(default the official DROP endpoint).
**Context**: on any `curl` failure the script exits non-zero and leaves the previous `droplist.bans`
untouched (tunneld keeps enforcing the stale file).

### Task 12.2: DB-IP script
**File**: `tunneld/deploy/scripts/fetch-dbip.sh` — create: `#!/bin/sh`; `set -eu`; compute `YYYY-MM`; target
`$OUT_DIR/dbip-country-lite.csv`; if a sentinel `$OUT_DIR/dbip-country-lite.month` equals the current
month, exit 0 (no network); else `curl -fsS` the month-versioned `.csv.gz`, `gunzip`, `mv` into place,
write the sentinel. Env: `OUT_DIR`, `DBIP_URL_TEMPLATE`.

### Task 12.3: CA generation script
**File**: `tunneld/deploy/scripts/gen-ca.sh` — create (run ONCE by the operator; output dir is mounted
into tunneld at `/ca` — see the compose file and `.env.example`; `ca.Load` (US4) rejects non-CA
material, so the extensions below are mandatory):
```sh
#!/bin/sh
set -eu
OUT_DIR="${1:?usage: gen-ca.sh <out-dir>}"
umask 077
openssl ecparam -name prime256v1 -genkey -noout -out "$OUT_DIR/ca-key.pem"
openssl req -x509 -new -key "$OUT_DIR/ca-key.pem" -sha256 -days 3650 \
  -subj "/CN=tunneld-ca" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$OUT_DIR/ca.pem"
```

### Task 12.4: Script tests (bats or shell-based)
**File**: `tunneld/deploy/scripts/scripts_test.sh` — create a POSIX test harness invoked by
`make test-scripts` (added to `tunneld/Makefile`).

| Test | Verifies |
|------|----------|
| `droplist converts feed to cidr lines` | Given a stub feed file, output is `cidr ...` lines |
| `droplist failure preserves old file` | Simulated curl failure leaves the previous file intact |
| `dbip skips when month present` | Sentinel == current month → no download attempted |
| `dbip downloads when month missing` | Stub download path → file + sentinel written atomically |
| `gen-ca produces a signing CA` | `ca.pem` parses with `CA:TRUE` + `keyCertSign` (openssl x509 -text); key matches the cert |

**Setup**: stub `curl` via a shim on `PATH` returning fixture bytes; assert temp-then-`mv` (never partial).

### Definition of Done
- [x] Scripts authored with `#!/bin/sh` shebangs and committed executable (git mode `100755`); `shellcheck` wired into `make lint`; script test harness authored (all execution in US16).
- [x] Atomic `mv` handoff and never-clobber-on-failure covered by the script test table.

---

## User Story 13: Docker image and Compose deployment stack

The multi-stage `tunneld` image and the full `docker-compose.yml` with proxy, Redis, observability,
ntfy, and the fetcher service.

### Acceptance Criteria
- [x] Multi-stage `Dockerfile` builds a static `tunneld` into a distroless/minimal runtime image; `--version` injected via ldflags.
- [x] `docker-compose.yml` defines: `traefik`, `tunneld-1`/`tunneld-2` (two explicit replicas via a shared YAML anchor — deterministic Traefik LB URLs + Prometheus scrape names; scale by copying the block; internal-only ports), `redis`, `prometheus`, `grafana`, `alertmanager`, `ntfy`, `ntfy-alertmanager`, `fetcher`.
- [x] Traefik does plain TLS termination + host/path routing ONLY — NO `clientAuth`/`passTLSClientCert` (auth is app-layer over `/connect`). Routers: `<enroll-host>`, and the wildcard `*.<tunnel-domain>` (per-tunnel hosts). Ops routers under `*.<ops-domain>` use basic-auth middleware; ntfy exposed with its own auth.
- [x] **Orange-cloud reference**: an `IPAllowList` middleware restricts ingress to Cloudflare's published IP ranges (and/or Authenticated Origin Pulls) so `Cf-Connecting-Ip` is trustworthy; Traefik passes `Cf-Connecting-Ip` through to tunneld (`--client-ip-header=Cf-Connecting-Ip`). Documented in `dynamic.yml` with the edge-cert (ACM) and `.env` notes. Grey-cloud alternative documented (`--client-ip-header=X-Real-Ip`, no Cloudflare in path, no IPAllowList).
- [x] `fetcher`: stock image + `command:` that `apk add`s deps, writes the crontab (droplist daily, DB-IP daily), runs both scripts once, then `crond -f`; scripts mounted read-only; ban-file dir shared with `tunneld`.
- [x] Prometheus scrapes `tunneld` internal listeners; Alertmanager routes to the ntfy bridge; Grafana provisioned with a datasource + dashboard; starter alert rules present.
- [x] All real hostnames/tokens are placeholders in-repo; `.env.example` (compose interpolation + non-tunneld secrets) and `tunneld.env.example` (ONLY `TUNNELD_*` twins, `env_file:`-injected) document required values — tunneld NEVER receives the shared `.env` (least privilege: `CF_DNS_API_TOKEN`/ops credentials stay out of the public-facing process).

### Task 13.1: Dockerfile
**File**: `tunneld/Dockerfile` — create:
```dockerfile
FROM golang:1.26 AS build
ARG VERSION=dev
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags "-X main.version=${VERSION}" -o /tunneld ./cmd/tunneld

FROM gcr.io/distroless/static:nonroot
COPY --from=build /tunneld /tunneld
ENTRYPOINT ["/tunneld", "serve"]
```

### Task 13.2: Compose + Traefik config
**Constraint (source-IP integrity):** implements the Design Decisions "Header handling" trust
boundary (orange: `IPAllowList` of Cloudflare ranges + `Cf-Connecting-Ip`; grey: no
`forwardedHeaders.trustedIPs`, `X-Real-Ip`). The rationale lives in that section ONLY.
**Edge certificate**: `<name>.<tunnel-domain>` is two labels deep; the operator uses Cloudflare
Advanced Certificate Manager for the `*.<tunnel-domain>` edge cert (documented; separate-zone is the
free alternative). Traefik's origin cert is via Cloudflare DNS-01 (below).
Traefik's STATIC configuration is passed as compose `command:` flags (compose does `${VAR}`
substitution there; a static `traefik.yml` file cannot read env vars); the DYNAMIC configuration is
the file provider with sprig `env` templating.

**File**: `tunneld/deploy/docker-compose.yml` — create:
```yaml
x-logging: &caplog
  driver: json-file
  options: { max-size: "50m", max-file: "1" }

services:
  traefik:
    image: traefik:v3.3
    restart: unless-stopped
    command:
      - --providers.file.filename=/etc/traefik/dynamic.yml
      - --entrypoints.web.address=:80
      - --entrypoints.web.http.redirections.entrypoint.to=websecure
      - --entrypoints.web.http.redirections.entrypoint.scheme=https
      - --entrypoints.websecure.address=:443
      - --certificatesresolvers.cloudflare.acme.dnschallenge=true
      - --certificatesresolvers.cloudflare.acme.dnschallenge.provider=cloudflare
      - --certificatesresolvers.cloudflare.acme.email=${ACME_EMAIL}
      - --certificatesresolvers.cloudflare.acme.storage=/letsencrypt/acme.json
    environment:
      CF_DNS_API_TOKEN: ${CF_DNS_API_TOKEN}
      TUNNEL_DOMAIN: ${TUNNEL_DOMAIN}
      ENROLL_HOST: ${ENROLL_HOST}
      OPS_DOMAIN: ${OPS_DOMAIN}
      OPS_BASIC_AUTH: ${OPS_BASIC_AUTH}
      CLOUDFLARE_IP_RANGES: ${CLOUDFLARE_IP_RANGES}
    ports: ["80:80", "443:443"]
    volumes:
      - ./traefik/dynamic.yml:/etc/traefik/dynamic.yml:ro
      - letsencrypt:/letsencrypt
    logging: *caplog

  # TWO EXPLICIT replicas via a shared anchor — NOT `deploy: replicas`: Traefik's file-provider
  # service needs one URL per backend (a single `tunneld:8080` URL would pin pooled connections to
  # one replica — the same multi-A-record ambiguity the Prometheus dns_sd comment calls out), and
  # explicit names make LB + scraping deterministic. To scale: copy the block, add the URL in
  # dynamic.yml and the name in prometheus.yml.
  tunneld-1: &tunneld
    build: { context: .., args: { VERSION: "${TUNNELD_VERSION:-dev}" } }
    image: tunneld:local
    restart: unless-stopped
    depends_on: [redis]
    # Run as the OPERATOR's uid (see DEPLOY_UID in .env.example): the distroless default uid 65532
    # cannot read the operator-owned 0600 CA key or write the bind-mounted ./logs dir.
    user: "${DEPLOY_UID:?set to your uid — run: id -u}"
    # ONLY the TUNNELD_* env twins — NEVER `env_file: .env`: the shared .env carries unrelated
    # secrets (CF_DNS_API_TOKEN, ops/grafana credentials) that must not enter the process
    # terminating untrusted public traffic (least privilege).
    env_file:
      - { path: tunneld.env, required: false }   # required:false so `docker compose config -q`
        # passes on a bare checkout (only the .example exists); at RUNTIME a missing file is caught
        # fast by tunneld's own Validate() (mandatory --client-ip-header etc.)
    environment:
      # Topology (not secrets); domain values interpolated from the SINGLE source in .env —
      # `environment:` overrides `env_file:`, so these are never duplicated in tunneld.env.
      TUNNELD_REDIS_URL: redis://redis:6379
      TUNNELD_TUNNEL_DOMAIN: ${TUNNEL_DOMAIN}
      TUNNELD_ENROLL_HOST: ${ENROLL_HOST}
    volumes:
      - ./ca:/ca:ro
      - banfiles:/banfiles:ro
      - ./logs:/logs               # bind mount (operator-owned, writable by DEPLOY_UID) — a named
                                   # volume would be created root:root and the file sink would fail
    logging: *caplog
    # NO ports: — reachable ONLY on the compose network (Traefik + Prometheus). Never publish.

  tunneld-2: *tunneld

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: ["redis-server", "--save", "", "--appendonly", "no"]   # transient state only
    logging: *caplog

  prometheus:
    image: prom/prometheus:latest
    restart: unless-stopped
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro
      - prom-data:/prometheus
    logging: *caplog

  alertmanager:
    image: prom/alertmanager:latest
    restart: unless-stopped
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
    logging: *caplog

  grafana:
    image: grafana/grafana:latest
    restart: unless-stopped
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      GF_SERVER_ROOT_URL: https://grafana.${OPS_DOMAIN}
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - grafana-data:/var/lib/grafana
    logging: *caplog

  ntfy:
    image: binwiederhier/ntfy:latest
    restart: unless-stopped
    command: ["serve"]
    environment:
      NTFY_BASE_URL: https://ntfy.${OPS_DOMAIN}
    volumes:
      - ./ntfy/server.yml:/etc/ntfy/server.yml:ro
      - ntfy-data:/var/lib/ntfy
    logging: *caplog

  ntfy-alertmanager:
    image: xenrox/ntfy-alertmanager:latest
    restart: unless-stopped
    volumes:
      - ./ntfy-alertmanager/config.scfg:/etc/ntfy-alertmanager/config.scfg:ro   # the image's default config search path — a bare `/config` target would NOT be discovered
    logging: *caplog

  fetcher:
    image: alpine:3
    restart: unless-stopped
    command: ["/bin/sh", "/opt/fetcher/command.sh"]
    volumes:
      - ./fetcher/command.sh:/opt/fetcher/command.sh:ro
      - ./scripts:/scripts:ro           # US12 droplist/DB-IP scripts (tunneld/deploy/scripts/)
      - banfiles:/banfiles
    logging: *caplog

volumes:
  letsencrypt: {}
  banfiles: {}
  prom-data: {}
  grafana-data: {}
  ntfy-data: {}
```

**File**: `tunneld/deploy/traefik/dynamic.yml` — create (file provider; sprig `env` templating; NO
`tls.options.clientAuth`, NO `passTLSClientCert` — the tunnel does not use TLS client certs):
```yaml
http:
  middlewares:
    ops-auth:
      basicAuth:
        users: ['{{ env "OPS_BASIC_AUTH" }}']
    cf-only:
      # Orange-cloud reference: only Cloudflare edges may reach the tunnel/enroll routers.
      # Grey-cloud: remove this middleware from the two routers below.
      ipAllowList:
        sourceRange: {{ env "CLOUDFLARE_IP_RANGES" | splitList "," | toJson }}

  routers:
    enroll:
      rule: Host(`{{ env "ENROLL_HOST" }}`)
      # The tunnels HostRegexp below ALSO matches the enroll host; both routers point at the same
      # service+middleware (tunneld re-dispatches by Host), but the explicit priority makes the
      # overlap deterministic.
      priority: 100
      entryPoints: [websecure]
      middlewares: [cf-only]
      service: tunneld
      tls: &wildcard-tls
        certResolver: cloudflare
        domains:
          - main: '{{ env "TUNNEL_DOMAIN" }}'
            sans: ['*.{{ env "TUNNEL_DOMAIN" }}']
    tunnels:
      # The regex is DERIVED from TUNNEL_DOMAIN (dots escaped via sprig `replace`) — single source
      # of truth, no separate hand-escaped variable to drift.
      rule: HostRegexp(`^[a-z0-9-]+\.{{ env "TUNNEL_DOMAIN" | replace "." "\\." }}$`)
      entryPoints: [websecure]
      middlewares: [cf-only]
      service: tunneld
      tls: *wildcard-tls
    grafana:
      rule: Host(`grafana.{{ env "OPS_DOMAIN" }}`)
      entryPoints: [websecure]
      middlewares: [ops-auth]
      service: grafana
      tls: &ops-tls
        certResolver: cloudflare
        domains:
          - main: '{{ env "OPS_DOMAIN" }}'
            sans: ['*.{{ env "OPS_DOMAIN" }}']
    prometheus:
      rule: Host(`prometheus.{{ env "OPS_DOMAIN" }}`)
      entryPoints: [websecure]
      middlewares: [ops-auth]
      service: prometheus
      tls: *ops-tls
    alertmanager:
      rule: Host(`alertmanager.{{ env "OPS_DOMAIN" }}`)
      entryPoints: [websecure]
      middlewares: [ops-auth]
      service: alertmanager
      tls: *ops-tls
    ntfy:
      # ntfy uses its OWN auth (deny-all + tokens) so the phone app can subscribe — NO basicAuth.
      rule: Host(`ntfy.{{ env "OPS_DOMAIN" }}`)
      entryPoints: [websecure]
      service: ntfy
      tls: *ops-tls

  services:
    tunneld:      { loadBalancer: { servers: [{ url: "http://tunneld-1:8080" }, { url: "http://tunneld-2:8080" }] } }
    grafana:      { loadBalancer: { servers: [{ url: "http://grafana:3000" }] } }
    prometheus:   { loadBalancer: { servers: [{ url: "http://prometheus:9090" }] } }
    alertmanager: { loadBalancer: { servers: [{ url: "http://alertmanager:9093" }] } }
    ntfy:         { loadBalancer: { servers: [{ url: "http://ntfy:80" }] } }
```

**File**: `tunneld/deploy/.env.example` — create (placeholders only, NO real domains/secrets; read by
compose for `${VAR}` interpolation — NEVER injected wholesale into tunneld):
```bash
# Operator uid (run: id -u) — tunneld runs as this uid so it can read ./ca and write ./logs
DEPLOY_UID=1000

# Cloudflare DNS-01 + edge
ACME_EMAIL=ops@example.com
CF_DNS_API_TOKEN=changeme
CLOUDFLARE_IP_RANGES=173.245.48.0/20,103.21.244.0/22   # full list: cloudflare.com/ips

# Domains — SINGLE source of truth (placeholders; real values are private).
# Traefik derives the wildcard regex from TUNNEL_DOMAIN in dynamic.yml; tunneld receives these via
# compose interpolation (TUNNELD_TUNNEL_DOMAIN/TUNNELD_ENROLL_HOST) — never duplicate them elsewhere.
TUNNEL_DOMAIN=free.example.com
ENROLL_HOST=enroll.free.example.com
OPS_DOMAIN=adminfree.example.com

# Ops access
OPS_BASIC_AUTH=admin:$apr1$changeme                    # htpasswd format — SINGLE $: .env values are NOT re-interpolated by compose ($$-doubling applies only to values written directly in the YAML)
GRAFANA_ADMIN_PASSWORD=changeme
```

**File**: `tunneld/deploy/tunneld.env.example` — create (ONLY `TUNNELD_*` env twins — this is the file
`env_file:`-injected into the tunneld containers; domains/Redis come via the compose `environment:`
section, so they are NOT repeated here):
```bash
TUNNELD_CLIENT_IP_HEADER=Cf-Connecting-Ip              # X-Real-Ip for grey-cloud; tunneld's Validate() refuses to start without it
TUNNELD_CA_CERT=/ca/ca.pem
TUNNELD_CA_KEY=/ca/ca-key.pem
TUNNELD_BAN_FILE=/banfiles/bans.txt,/banfiles/droplist.bans
TUNNELD_DBIP_COUNTRY_LITE_CSV=/banfiles/dbip-country-lite.csv
TUNNELD_LOG=output=std;level=info,output=/logs/tunneld.log;level=info;maxsize=50m;maxfiles=20
```

### Task 13.3: Observability provisioning
**File**: `tunneld/deploy/prometheus/prometheus.yml` — create (one `dns_sd` name per explicit replica
service — matches the compose `tunneld-1`/`tunneld-2` split; a single shared service name would make
both scraping AND Traefik load-balancing ambiguous across replicas):
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 30s
rule_files:
  - /etc/prometheus/alerts.yml
alerting:
  alertmanagers:
    - static_configs:
        - targets: ["alertmanager:9093"]
scrape_configs:
  - job_name: tunneld
    dns_sd_configs:
      - names: [tunneld-1, tunneld-2]
        type: A
        port: 9090
```

**File**: `tunneld/deploy/prometheus/alerts.yml` — create:
```yaml
groups:
  - name: tunneld
    rules:
      - alert: HighRejectionRate
        expr: sum by (reason) (rate(tunneld_rejections_total[5m])) > 10
        for: 10m
        labels: { severity: warning }
      - alert: TunnelOfflineSpike
        expr: sum(rate(tunneld_rejections_total{reason="tunnel_offline"}[5m])) > 1
        for: 5m
        labels: { severity: warning }
      - alert: EnrollmentBurst
        expr: sum(increase(tunneld_enrollments_total[10m])) > 50
        labels: { severity: warning }
      - alert: SustainedRateLimiting
        expr: sum(rate(tunneld_rejections_total{reason=~"rate_rps|rate_rpm"}[15m])) > 5
        for: 15m
        labels: { severity: warning }
      - alert: NodeDown
        expr: up{job="tunneld"} == 0
        for: 2m
        labels: { severity: critical }
```

**File**: `tunneld/deploy/alertmanager/alertmanager.yml` — create:
```yaml
route:
  receiver: ntfy
  group_by: [alertname]
  group_interval: 5m
  repeat_interval: 4h
receivers:
  - name: ntfy
    webhook_configs:
      - url: http://ntfy-alertmanager:8080
```

**File**: `tunneld/deploy/ntfy-alertmanager/config.scfg` — create (bridge config; the write token is
created on first deploy — see ntfy note below):
```
base-url http://ntfy
ntfy {
    topic alerts
    access-token changeme-write-token
}
```

**File**: `tunneld/deploy/ntfy/server.yml` — create:
```yaml
auth-default-access: "deny-all"
auth-file: /var/lib/ntfy/auth.db
cache-file: /var/lib/ntfy/cache.db
```
Document (README): after first start, create a read user for the phone app (`ntfy user add`) and a
write token for the bridge (`ntfy token add`), then set it in `config.scfg`.

**File**: `tunneld/deploy/grafana/provisioning/datasources/prometheus.yml` — create:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

**File**: `tunneld/deploy/grafana/provisioning/dashboards/provider.yml` — create:
```yaml
apiVersion: 1
providers:
  - name: tunneld
    folder: tunneld
    type: file
    options: { path: /etc/grafana/provisioning/dashboards }
```
**File**: `tunneld/deploy/grafana/provisioning/dashboards/tunneld.json` — a valid dashboard JSON
(schema skeleton exported from Grafana) containing EXACTLY these aggregate panels (NO per-tunnel
cardinality):

| Panel | PromQL |
|-------|--------|
| Tunnels connected | `sum(tunneld_tunnels_connected)` |
| Requests by class | `sum by (class) (rate(tunneld_http_requests_total[5m]))` |
| Request duration p50/p95 | `histogram_quantile(0.5\|0.95, sum by (le) (rate(tunneld_http_request_duration_seconds_bucket[5m])))` |
| Rejections by reason | `sum by (reason) (rate(tunneld_rejections_total[5m]))` |
| Bandwidth by direction | `sum by (direction) (rate(tunneld_bytes_total[5m]))` |
| WS disconnects by reason | `sum by (reason) (rate(tunneld_ws_disconnects_total[5m]))` |
| Enrollments | `sum(increase(tunneld_enrollments_total[1h]))` |
| In-flight requests | `sum(tunneld_http_inflight)` |
| Timeouts / publish errors | `sum(rate(tunneld_request_timeouts_total[5m]))`, `sum(rate(tunneld_pubsub_publish_errors_total[5m]))` |

**File**: `tunneld/deploy/fetcher/command.sh` — create:
```sh
#!/bin/sh
set -eu
apk add --no-cache curl jq
crontab - <<'CRON'
30 3 * * * OUT_DIR=/banfiles /scripts/fetch-droplist.sh
40 3 * * * OUT_DIR=/banfiles /scripts/fetch-dbip.sh
CRON
OUT_DIR=/banfiles /scripts/fetch-droplist.sh || true
OUT_DIR=/banfiles /scripts/fetch-dbip.sh || true
exec crond -f
```
(The US12 scripts write to a temp file in the target dir then `mv` — atomic rename, never a partial
file under the tunneld pollers.)

### Task 13.4: Compose validation
**File**: `tunneld/Makefile` — add `compose-config` (`docker compose --env-file
deploy/.env.example -f deploy/docker-compose.yml config -q`) to `make lint` prerequisites where
Docker is available. `--env-file deploy/.env.example` is REQUIRED: on a bare checkout / in CI there
is no `.env`, and the `${DEPLOY_UID:?}` interpolation would otherwise fail the gate (the example
file provides placeholder values; `tunneld.env` is `required: false` in the compose file for the
same reason).

### Definition of Done
- [x] Dockerfile, `docker-compose.yml`, Traefik/observability configs, and `.env.example` authored (build + `docker compose config -q` executed in US16).
- [x] Traefik has NO client-cert/mTLS config; ops behind basic auth; ntfy self-authed; the orange-cloud `IPAllowList` (Cloudflare ranges) restricts the tunnel/enroll routers; grey-cloud alternative documented.
- [x] No real secrets/hostnames committed; `.env.example` AND `tunneld.env.example` complete.

---

## User Story 14: End-to-end tests (testcontainers-go)

Full-infrastructure e2e: Redis + Traefik + two `tunneld` replicas + a fake phone backend, exercising
enrollment, cross-replica routing, allowlist, and the caps.

### Acceptance Criteria
- [x] `//go:build e2e`-tagged tests spin up the stack via `testcontainers-go` and drive it with the Go client (US11, app-layer challenge-response) + a raw HTTP client for the public side.
- [x] Covers: enroll → connect on node A (challenge-response) → public request landing on node B routes across Redis → response; allowlist denials; a representative cap (body/rate); banned IP via a mounted ban file; client-cert/mTLS-header rejection on the public side.
- [x] Runs under `make test-e2e`; skipped by default unit runs.

### Task 14.1: E2E harness
**File**: `tunneld/e2e/harness_test.go` — bring up containers (reuse the built image), wire the
network, generate a CA + mount it (the in-test CA files are written world-readable, `0644` —
throwaway test material — so the image's nonroot uid can read them), mount a ban file, wait for
health.
**File**: `tunneld/e2e/testdata/traefik-e2e.yml` — a DEDICATED minimal grey-cloud Traefik dynamic
config for the e2e (the production `deploy/traefik/dynamic.yml` is NOT reused — it applies the
orange-cloud `cf-only` IPAllowList, which would `403` every e2e request from the docker-network
client): plain `web` entrypoint (no TLS/ACME — the e2e exercises tunneld behind a real proxy, not
certificates), NO `cf-only`, routers for the enroll host and the tunnel-host wildcard →
`tunneld-1`/`tunneld-2` services. The harness mounts THIS file into the e2e Traefik container.
**Context**: no real Cloudflare in the e2e — Traefik is the direct edge in the grey-cloud
configuration (Design Decisions "Header handling"): `--client-ip-header=X-Real-Ip`, Traefik sets it
to the real peer IP, no manual client-IP injection needed. The Go client authenticates over the WS
(challenge-response) — no TLS client cert involved. The `banned ip 403` scenario mounts a ban file
containing the client container's source IP (the one Traefik sets).

### Task 14.2: E2E scenarios
**File**: `tunneld/e2e/tunnel_e2e_test.go`

**Setup**: two `tunneld` replicas; the Go client connects to one; public requests are sent to the other replica's ingress to force cross-node routing.

| Test | Verifies |
|------|----------|
| `enroll and serve mcp cross-node` | Enroll → challenge-response connect on A → `POST /mcp` to B → response returns |
| `connect rejects bad possession` | WS AUTH with a signature by a non-enrolled key → connection refused, no tunnel |
| `allowlist denies non-mcp path` | `GET /` → 404; `GET /mcp` → 405 |
| `oauth path forwarded without auth` | `POST /register` → forwarded |
| `share path regex` | valid `/s/<64hex>` forwarded; bad → 404 |
| `public mtls header rejected` | Request with a client-cert / mTLS-indicating header → 400 |
| `body cap enforced` | 1 MB+ body → 413 |
| `rate limit 429` | Burst over rps → 429 + Retry-After |
| `banned ip 403` | Mounted ban file entry blocks the source |
| `banned tunnel-fingerprint refused + evicted` | Ban file with the tunnel's `tunnel-fingerprint`: a fresh connect is refused, and a live tunnel is dropped on ban-file reload |
| `tunnel offline 502/404` | Kill the WS → subsequent request errors correctly |

### Definition of Done
- [x] US14 e2e harness + all scenarios authored and committed (`make test-e2e` execution happens in US16 per repo workflow).
- [x] Scenarios include cross-node routing (request on a different replica than the WS).

---

## User Story 15: CI, linting, and documentation

CI workflow for the Go module, linter config, the protocol spec, and the README (with DB-IP
attribution).

### Acceptance Criteria
- [x] `.golangci.yml` for the module; `make lint` runs `golangci-lint` + `shellcheck` + `docker compose config`.
- [x] A GitHub Actions workflow builds, vets, lints, unit-tests the module, and builds the image; e2e is a separate opt-in job.
- [x] `tunneld/docs/PROTOCOL.md` fully specifies the WS frame format + Redis envelopes + enrollment, with the golden fixtures referenced so the future Kotlin client matches.
- [x] `tunneld/README.md` documents deployment, the "never publish tunneld's port" rule, and the DB-IP CC-BY attribution line. Country filtering is described ONLY as a generic operator-configurable GeoIP feature with placeholder codes — no country names/codes.

### Task 15.1: Lint config + CI
**File**: `tunneld/.golangci.yml` — enable a reasonable set (govet, staticcheck, errcheck, revive,
gosec, ineffassign, misspell).
**File**: `.github/workflows/tunnel-ci.yml` — jobs: `build-test` (go build/vet/lint/test), `image`
(docker build), `e2e` (opt-in via workflow_dispatch or a label). Path-filtered to `tunneld/**`.
**Context**: keep this separate from the existing Android `ci.yml`; do NOT modify the Android CI.

### Task 15.2: Protocol spec
**File**: `tunneld/docs/PROTOCOL.md` — document: enrollment request/response; the WS binary frame
layout (types incl. `CHALLENGE`/`AUTH`, header JSON schema, chunking, `ChunkSize`); the `/connect`
**application-layer challenge-response** (nonce → `{base64 DER cert, ECDSA-P256 sig over
`ConnectAuthContext ‖ nonce`}` → verify chain/CN/validity + possession + CN==host); the empty-body
canonical encoding (ZERO body-chunk frames, both directions; zero-length chunks tolerated); the Redis
channel names (`req:{node}`,`resp:{reqid}`,`route:{name}`) and envelope encoding; liveness/heartbeat;
and the fingerprint guard. Reference `tunneld/internal/wire/testdata/` golden fixtures.
**Security invariants to document**:
- Possession proof is the app-layer signature over the server nonce (equivalent to TLS
  `CertificateVerify`); the certificate alone is public and NOT sufficient; a captured cert/signature
  cannot be replayed on a new connection (fresh nonce).
- Source-IP trust: with orange-cloud, `Cf-Connecting-Ip` is trustworthy ONLY because the origin is
  reachable exclusively via Cloudflare (IPAllowList of Cloudflare ranges + optional Authenticated
  Origin Pulls) — "origin only reachable from Cloudflare" is a SECURITY invariant (the orange-cloud
  form of "never publish tunneld's port"). Grey-cloud uses `X-Real-Ip` with Traefik as the edge.
- Cloudflare Free constraints that the protocol depends on: WS idle timeout 100 s → ping < 100 s
  (`--ping-interval` 30 s); origin 524 timeout 100 s → request timeout < 100 s.

### Task 15.3: README
**File**: `tunneld/README.md` — deployment quickstart (FIRST steps, in order: (1) generate the
internal CA via `deploy/scripts/gen-ca.sh deploy/ca` — the output dir is what compose mounts at
`/ca`; (2) `mkdir -p deploy/logs` (bind-mounted, operator-owned — the tunneld file log sink writes
here); (3) copy `.env.example` → `.env` and `tunneld.env.example` → `tunneld.env`, set `DEPLOY_UID`
to `id -u` (tunneld runs as that uid so it can read the CA key and write the logs); then the
orange-cloud reference: proxied `*.<tunnel-domain>`, ACM edge cert, Cloudflare IPAllowList /
Authenticated Origin Pulls, `ping<100s`;
grey-cloud alternative), architecture (Mermaid — validate with `mmdc`), the endpoint allowlist, the
caps table, the ban-file format (placeholder country codes only), the DB-IP CC-BY attribution, and
the source-IP + origin-trust security invariants above. Privacy note: orange-cloud means Cloudflare
sees tunnel plaintext; grey-cloud keeps traffic origin-only. **Document explicitly**: the tunnel
performs NO authentication on forwarded requests — the app is the sole authenticator (its token-less
`401` carries the RFC 9728 OAuth-discovery header, which the tunnel deliberately never swallows).
Consequently a phone in OPEN mode (no bearer/OAuth) is reachable unauthenticated by ANYONE holding
the tunnel hostname: a tunnelled deployment MUST keep bearer or OAuth enabled on the app.

### Definition of Done
- [x] `.golangci.yml`, the CI workflow, `PROTOCOL.md`, and `README.md` authored (CI/lint execution happens in US16 and via the workflow on push).
- [x] `PROTOCOL.md` is complete enough to implement a matching client from scratch, and documents the app-layer possession proof + the origin-trust security invariants.
- [x] README contains the DB-IP attribution; NO country names/codes anywhere in the module.
- [x] All Mermaid diagrams validated with `mmdc`.

---

## User Story 16: Full ground-up verification

Re-verify the ENTIRE implementation from scratch against this plan and the app's real endpoint
surface — the final gate before the work is considered done.

### Acceptance Criteria
- [ ] `tunneld/`: `go build ./...`, `go vet ./...`, `golangci-lint run`, `go test ./...` all clean; `make test-e2e` passes; `make test-scripts` (the US12 droplist/DB-IP harness) passes; `shellcheck` + `docker compose config -q` pass.
- [ ] The ingress allowlist is re-diffed against the CURRENT app code (McpServer.kt, McpStreamableHttpExtension.kt, OAuthRoutes.kt, Cors.kt, CapabilityToken.kt, EphemeralFileLinkService.kt — the last confirms `PATH_PREFIX` is still `/s/`) — every allowlisted method+path still matches; no app route added/removed since planning is unaccounted for.
- [ ] Every default in the Config table matches this plan; every `--limit-*` flag has a working env twin.
- [ ] No permanent Redis state anywhere: every key set has a TTL (grep + review).
- [ ] Ban check is provably FIRST among HANDLER-level checks on all three ingress edges (public, `/enroll`, `/connect`), keyed on the trusted `--client-ip-header` IP (checked BEFORE the WS upgrade on `/connect`). (Exactly TWO refusals necessarily precede it: Go's `MaxHeaderBytes` `431` emitted during header parsing before ANY handler runs — US7 step 6 — and the mandatory client-IP extraction's fail-closed `400 missing_client_ip`, since an IP must exist before it can be ban-checked. NO other logic precedes the ban check.)
- [ ] Source IP comes ONLY from the configured `--client-ip-header` via the single `clientip.TrustedIP` helper (single value, or right-most token for `X-Forwarded-For`; never the left-most); grep confirms no other code path derives the ban/rate/quota IP; absent header → `400` `missing_client_ip`.
- [ ] There is NO TLS-mTLS anywhere (no `clientAuth`/`passTLSClientCert` in Traefik, no client-cert header parsing in tunneld). `/connect` auth is the app-layer challenge-response; possession verification rejects wrong-key/wrong-nonce (no replay); CN == Host `<name>` enforced.
- [ ] Revocation is enforced at ALL THREE points: `ban.MatchTunnel(name, fingerprint)` at `/connect` (after auth, before `Bind`), `MatchTunnel` on the resolved route at public ingress (US7 step 3), AND `EvictBanned` as the ban-reload hook (US10) dropping live banned tunnels mid-session; grep confirms all three are actually invoked (not just defined). `/connect` has a per-IP attempt limit + a bounded pre-auth semaphore before the WS upgrade.
- [ ] Header sanitization strips client `X-Forwarded-*`/hop-by-hop; public side rejects the fixed client-cert/mTLS-indicating header set (`400 public_mtls_header`).
- [ ] The edge performs NO authentication on forwarded requests (user decision): a token-less `POST /mcp` is forwarded and the app's `401` + RFC 9728 `WWW-Authenticate` header passes back unmodified; grep confirms the ingress never inspects the `Authorization` header.
- [ ] Orange-cloud origin-trust documented: `Cf-Connecting-Ip` is used only with the Cloudflare IPAllowList / Authenticated Origin Pulls restriction; `--ping-interval < 100s` and `--limit-request-timeout < 100s` (Cloudflare limits) enforced by `Validate()`.
- [ ] Every Redis counter's TTL is set atomically with its INCR/HINCRBY (single Lua), not in a separate call.
- [ ] No AI attribution anywhere (commits, comments, docs).
- [ ] No real country names/codes in ANY module artifact; DB-IP attribution present.
- [ ] `/health` is NOT reachable through the tunnel; `/healthz` exists only on the internal listener.

### Task 16.1: Automated gate sweep
**File**: (no new file) — run the full quality gate for the module and capture output to
`/tmp/tunnel-verify.log`: build, vet, lint, unit tests, e2e, `make test-scripts`, shellcheck, compose
config. Fix any
failure at the root cause.

### Task 16.2: Allowlist re-diff
**Action**: re-read the five cited app files and confirm `internal/ingress/allowlist.go` matches
exactly. Record the confirmation. If the app changed, reconcile and note it for the user.

### Task 16.3: Invariant review
**Action**: grep for Redis `Set`/`INCR`/`HSet`/`HINCRBY` calls and confirm each sets its TTL in the
SAME Lua script (no INCR-then-separate-EXPIRE); confirm ban-check ordering at each ingress edge (and
BEFORE the `/connect` WS upgrade) and that the ban/rate/quota IP comes only from `--client-ip-header`;
grep for any residual TLS-mTLS artifacts (`clientAuth`, `passTLSClientCert`, `X-Forwarded-Tls-Client-Cert`
parsing) and confirm NONE exist (auth is app-layer challenge-response); confirm no per-tunnel
Prometheus labels; confirm no country names/codes and no AI attribution across `tunneld/`.

### Definition of Done
- [ ] All gates green; `/tmp/tunnel-verify.log` shows clean runs.
- [ ] Allowlist re-diff recorded and matching.
- [ ] All invariants (TTLs, ban-first, no cardinality, no country codes, no AI attribution) confirmed.
- [ ] Any deviation from this plan is surfaced to the user (never silently "fixed" against the plan).

---

## Execution Order (summary)

1. Scaffold + config + logging → 2. Ban/geo engine → 3. Limits → 4. CA/enrollment logic →
5. Redis routing/transport → 6. WS protocol/connection → 7. Public ingress → 8. Enroll endpoint →
9. Metrics/admin/caplog → 10. Server assembly → 11. Go client → 12. Support scripts →
13. Docker/Compose → 14. E2E → 15. CI/docs → 16. Ground-up verification.

No task depends on a later task. Tests for each user story run within that story's DoD during
development conceptually, but per repo rules the FULL suite + linting run only after the entire plan
is implemented, followed by the `code-reviewer` in plan-compliance mode.
