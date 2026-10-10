# Lucky-Tool-Android relay

Self-hosted WebSocket relay for the `RELAY` tunnel provider. The phone opens an outbound WebSocket to
`wss://<this host>/device` (bearer `RELAY_DEVICE_TOKEN`); every HTTP request this server receives on any other path
is forwarded to the phone as a JSON frame and answered from the phone's reply. MCP clients therefore use
`https://<this host>/mcp` exactly as they would use the device's own URL.

## Run

```bash
RELAY_DEVICE_TOKEN='<at least 16 random characters>' node src/server.js
```

| Variable | Default | Meaning |
|---|---|---|
| `RELAY_DEVICE_TOKEN` | — (required, ≥ 16 chars) | Bearer the phone presents on `/device` |
| `PORT` | `8080` | Listen port (1–65535) |
| `REQUEST_TIMEOUT_MS` | `120000` | Wait for the phone's answer before `504` |
| `MAX_BODY_BYTES` | `16777216` | Request body limit (`413` above) |
| `MAX_RESPONSE_BYTES` | `8388608` | Response body limit (`502` above; the phone enforces the same 8 MiB) |
| `MAX_PENDING` | `32` | Requests in flight before new ones get `503` |
| `HEARTBEAT_INTERVAL_MS` | `25000` | Ping interval towards the phone |
| `HEARTBEAT_TIMEOUT_MS` | `60000` | Silence after which the phone socket is dropped |
| `TRUST_PROXY` | `true` | Keep incoming `X-Forwarded-Host`/`X-Forwarded-Proto` (set by your TLS proxy). Set `false` when the relay is reached directly, so clients cannot spoof them |

## Container

```bash
docker build -t lucky-tool-android-relay .
docker run --rm -p 8080:8080 -e RELAY_DEVICE_TOKEN='…' -e TRUST_PROXY=false lucky-tool-android-relay
```

Put it behind a TLS-terminating proxy (Coolify "Dockerfile" build pack with base directory `relay/`, port `8080`).
Point the platform health check at `/relay/health` — it is answered by the relay itself (`200` even when no phone is
connected; the body reports `deviceConnected`). `/health` is forwarded to the phone and returns `503` when none is
connected.

## HTTP semantics

- `503` no phone connected or `MAX_PENDING` reached · `504` phone did not answer in time · `413` body too large ·
  `502` phone dropped mid-request or answered with more than `MAX_RESPONSE_BYTES`
- One phone at a time: a new authenticated `/device` connection replaces the previous one (closed with code `4000`).
- Responses above 8 MiB (e.g. large `/s/` capability downloads) are not served through the relay.

## Test

```bash
npm ci
npm run lint
npm test
```
