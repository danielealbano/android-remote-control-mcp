# tunneld wire protocol

This document specifies the tunneld protocols precisely enough to implement a matching client from
scratch (the Go client in `tunneld/client` and the future Kotlin client MUST both conform). Golden
byte fixtures live in [`../internal/wire/testdata/`](../internal/wire/testdata/).

## 1. Enrollment

`POST https://<enroll-host>/enroll`

- Request body: a PEM-encoded PKCS#10 **CSR** (`-----BEGIN CERTIFICATE REQUEST-----`). The key MUST
  be **ECDSA P-256** — any other key type is rejected `400 {"error":"unsupported_key_type"}`.
- The server ignores ALL CSR subject/extension fields except the public key; it assigns a random
  tunnel name and signs a leaf certificate (CN = name, lifetime `--cert-validity`).
- Response `200 application/json`:
  ```json
  {
    "name": "<10 base32 chars>",
    "hostname": "<name>.<tunnel-domain>",
    "connect_url": "wss://<name>.<tunnel-domain>/connect",
    "certificate_pem": "-----BEGIN CERTIFICATE-----\n…",
    "expires_at": 1893456000
  }
  ```
- Abuse controls: source IP from `--client-ip-header` (ban-checked first → `403`); enrollment quota
  `--limit-enroll-hour` AND `--limit-enroll-minute` → `429` + `Retry-After` + a clear JSON message;
  body bounded by `--limit-enroll-body` → `413`. No identity is persisted in Redis (only transient
  quota counters).

## 2. `/connect` — application-layer challenge-response (NOT TLS mutual auth)

`GET wss://<name>.<tunnel-domain>/connect` (an ordinary WSS upgrade — Cloudflare-proxyable). A
non-WebSocket request to `/connect` is answered `426 Upgrade Required`.

Before the upgrade the server ban-checks the source IP (`403`), applies a per-IP connect-attempt
limit (`429`), and acquires a bounded pre-auth semaphore (`503` if full). After the upgrade:

1. **Server → phone `CHALLENGE`**: header `{"nonce":"<base64 of 32 random bytes>"}`, no body.
2. **Phone → server `AUTH`**: header
   `{"cert":"<base64 DER leaf cert>","signature":"<base64 ECDSA-P256 signature>"}`, no body.
   The signature is `ECDSA-P256-SHA256` over `SHA-256("tunneld-connect-v1" ‖ nonce)`.
3. The server verifies: the cert chains to the tunnel CA and is within its validity window; the
   signature verifies for the cert's public key over the context-prefixed nonce; and `CN == <name>`
   from the Host. Any failure (or no AUTH within `--connect-auth-timeout`) closes the WS with no bind.

**Security invariant — possession proof.** The certificate is public; presenting it alone is NOT
sufficient. Possession of the private key is proven by signing a server-chosen fresh nonce (the
app-layer equivalent of TLS `CertificateVerify`). A captured cert+signature CANNOT be replayed on a
new connection (each connection uses a fresh nonce).

**Fingerprint guard / revocation.** `route:{name}` records the cert fingerprint
(`"sha256:"+hex(sha256(cert.Raw))`); a `/connect` for a name already bound to a *different*
fingerprint is refused. A `tunnel-name`/`tunnel-fingerprint` ban is the ONLY revocation mechanism
(no CRL): it is enforced at `/connect` (after auth, before bind), at public ingress (on the resolved
route), and live via the ban-reload eviction hook.

## 3. WebSocket binary frames

Every WS binary message is one frame:

```
[ type : 1 byte ][ headerLen : 4 bytes big-endian ][ header : headerLen bytes of JSON ][ body : raw bytes ]
```

| type | name | header JSON | body |
|---|---|---|---|
| 1 | `CHALLENGE` | `{nonce}` | — |
| 2 | `AUTH` | `{cert, signature}` | — |
| 3 | `REQUEST_HEAD` | `{reqid, method, path, rawquery, host, header}` | — |
| 4 | `REQUEST_BODY_CHUNK` | `{reqid}` | ≤ `ChunkSize` |
| 5 | `REQUEST_END` | `{reqid}` | — |
| 6 | `RESPONSE_HEAD` | `{reqid, status, header}` | — |
| 7 | `RESPONSE_BODY_CHUNK` | `{reqid}` | ≤ `ChunkSize` |
| 8 | `RESPONSE_END` | `{reqid}` | — |
| 9 | `ERROR` | `{reqid, message}` | — |

- `ChunkSize` = **32768** bytes. Both peers set the WS read limit to `ChunkSize + 64 KiB`.
- The request and response paths are **symmetric**: `REQUEST_HEAD`/`RESPONSE_HEAD` carry headers and
  NO body; the body follows as `*_BODY_CHUNK` frames; `REQUEST_END`/`RESPONSE_END` is the dispatch
  trigger. The receiver MUST NOT dispatch until the `*_END` frame.
- **Empty body = ZERO body-chunk frames** — the canonical encoding in BOTH directions. Receivers MUST
  also tolerate a zero-length `*_BODY_CHUNK` frame (append nothing).
- EVERY `REQUEST_*`/`RESPONSE_*`/`ERROR` frame carries `reqid`, so up to `--limit-concurrent`
  in-flight requests multiplex over one WebSocket and are demultiplexed by `reqid`. The phone copies
  the request's `reqid` into every response frame. `CHALLENGE`/`AUTH` carry no `reqid`.
- `ERROR{reqid, message}` resolves that request as a synthetic `502` (the phone could not fulfil it).
  An `ERROR` with an unknown/stale `reqid` is dropped.
- Bodies are appended RAW (never base64 — base64 would add ~33% under the bandwidth cap). Response
  body chunks are bandwidth-paced (per-tunnel token bucket); request body chunks are paced on ingress.
- Keepalive uses the WebSocket library's native control **pings** (`--ping-interval`), not app frames.

## 4. Redis transport (frontend ⇄ WS-holding node)

- Channels: `req:{node}` (frontend → node), `resp:{reqid}` (node → frontend). Routing:
  `route:{name}` = `{node, fingerprint, connID}` with a heartbeat-refreshed TTL (`--route-ttl`).
- A frontend generates a `reqid`, SUBSCRIBES to `resp:{reqid}` **before** publishing the request to
  `req:{node}` (so a fast response is never missed); on `--limit-request-timeout` with no response →
  `504`.
- Envelope encoding: `[4-byte BE header-len][JSON of all fields except Body][raw Body]` — the same
  raw-body, length-prefixed scheme as the WS frames (golden fixtures: `req_envelope.bin`,
  `resp_envelope.bin`).
- **No permanent Redis state, ever.** Every key (routing, rate-limit windows, concurrency counters,
  per-tunnel counters) carries a TTL set atomically with its increment.

## 5. Liveness / heartbeat

- The WS-holding node refreshes `route:{name}` every `--route-ttl/3`. `Heartbeat` is owner-conditional
  on `connID` and returns `refreshed` / `not-owner` (superseded — the phone re-bound elsewhere) /
  `missing` (TTL lapsed → the node self-heals by re-binding). A dead WS (failed native ping) drops
  its routing entry.

## 6. Security invariants (summary)

- Possession proof is the app-layer signature over the server nonce; the certificate alone is public
  and NOT sufficient; a captured cert/signature cannot be replayed (fresh nonce).
- **Source-IP trust**: with orange-cloud, `Cf-Connecting-Ip` is trustworthy ONLY because the origin is
  reachable exclusively via Cloudflare (Traefik IPAllowList of Cloudflare ranges + optional
  Authenticated Origin Pulls) — "origin only reachable from Cloudflare" is a SECURITY invariant (the
  orange-cloud form of "never publish tunneld's port"). Grey-cloud uses `X-Real-Ip` with Traefik as
  the edge.
- Cloudflare Free constraints the protocol depends on: WS idle timeout 100 s → `--ping-interval` < 100 s
  (default 30 s); origin 524 timeout 100 s → `--limit-request-timeout` < 100 s (default 60 s).
