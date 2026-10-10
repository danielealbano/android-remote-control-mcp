<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 66 — WebSocket relay tunnel provider (`RELAY`) + self-hosted relay service

Adds a third `TunnelProviderType`. The device dials OUT to a relay the operator hosts (`wss://<relay>/device`,
bearer-authenticated); MCP clients call the relay's HTTPS URL. The relay forwards every HTTP request it receives as
a JSON frame over that socket, the app replays the frame against `http://127.0.0.1:<port>` and answers with a
response frame. No inbound port on the phone, no third-party tunnel account, the phone's changing IP is irrelevant.
Upstream's Cloudflare and ngrok providers stay untouched.

**Context not derivable from code or project docs:**

- Wire protocol (WebSocket TEXT frames, JSON). Relay → device: `{"type":"request","id":"<uuid>","method":"POST",
  "path":"/mcp?x=1","headers":{"<name>":["<value>", …]},"body":"<base64>"}` and `{"type":"ping"}`. Device → relay:
  `{"type":"response","id":"<uuid>","status":200,"headers":{…},"body":"<base64>"}` and `{"type":"pong"}`. `path` is
  origin-form only (`/path?query`); the device rejects anything else with a `400` frame. Header values are ALWAYS
  arrays; names arrive lowercase from Node and the device forwards whatever casing it receives. Hop-by-hop and
  engine-managed headers (`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Expect`, …) are dropped on
  both sides. Unknown frame types are ignored and logged, never fatal.
- Relay HTTP semantics: `GET /relay/health` is answered by the relay itself (always `200`, body reports
  `deviceConnected`); EVERY other path is forwarded (so `/health`, `/mcp`, OAuth routes all reach the app). `503`
  when no device is connected or when `MAX_PENDING` requests are already in flight, `504` when the device does not
  answer within `REQUEST_TIMEOUT_MS`, `413` over `MAX_BODY_BYTES`, `502` when the device drops mid-request or answers
  with more than `MAX_RESPONSE_BYTES`. Exactly ONE device socket at a time: a new authenticated `/device` connection
  replaces the previous one (closed with code `4000`); the replaced device waits the maximum backoff before redialling.
- Size and concurrency caps (both sides, defaults): request body 16 MiB, response body 8 MiB (OkHttp drops a
  WebSocket whose outgoing queue exceeds 16 MiB, and base64 inflates by 4/3), 8 concurrent replays on the device
  (`503` frame when exhausted), 32 pending requests on the relay. `/s/` capability links above 8 MiB are therefore
  NOT served through the relay — documented.
- `X-Forwarded-Host` / `X-Forwarded-Proto`: with `TRUST_PROXY=true` (default — Coolify's proxy sets them) the relay
  keeps incoming values and fills them only when absent; with `TRUST_PROXY=false` it overwrites them with what it
  observed. `RequestBaseUrl` builds OAuth metadata and share links from them.
- The app derives the public base URL from the relay URL: `wss://` → `https://`, `ws://` → `http://`, same authority.
  The relay URL is `scheme://host[:port]` ONLY (no path: `RequestBaseUrl` cannot carry a prefix, so OAuth metadata
  would break). The user enters that base URL; the app appends `/device`.
- `ws://` is accepted but flagged: the Tunnel screen shows a cleartext warning unless the host is loopback or
  RFC 1918 private (device token and every client bearer/OAuth token would travel unencrypted).
- Ktor's `OkHttp` engine ignores `WebSockets.pingIntervalMillis` and `maxFrameSize`: protocol pings come from
  OkHttp's own `pingInterval`, application-level pings from the relay. The device closes and redials a socket that
  stays silent for 90 s; it reconnects with exponential backoff 1 s → 30 s until `stop()`, and resets the backoff
  only after a session that stayed connected ≥ 10 s. Between retries the status stays `Error("…; reconnecting in
  Ns")` (no `Connecting` flip) so an outage at maximum backoff produces ONE server-log line, not two every 30 s.
- `HttpClient.webSocketSession()` runs the socket in the CLIENT's scope, not the caller's, and `HttpClient.close()`
  does not terminate live sessions: `stop()` therefore cancels the client (`HttpClient` is a `CoroutineScope`) and
  the Close frame is sent under `NonCancellable`. Verified against the Ktor 3.5.2 jars: `HttpRequestTimeoutException
  extends IOException` (its catch MUST precede `IOException`), `WebSocketException extends IllegalStateException`,
  `UnresolvedAddressException extends IllegalArgumentException`, `HttpTimeout` never applies request/socket timeouts
  to WebSocket requests, the only client-unsafe headers are `Transfer-Encoding` and `Upgrade`,
  `followRedirects` defaults to `true` (MUST be `false`: the app answers `/authorize` errors with a `302` that the
  browser must see, and following loopback redirects would be an unauthenticated SSRF primitive). No generic
  `catch (e: Exception)` anywhere: `IOException`, `IllegalArgumentException` and `IllegalStateException` (with
  `ensureActive()`, because `CancellationException` is one) cover every failure these APIs raise; anything else
  reaches the session scope's `CoroutineExceptionHandler`, which logs and publishes `Error`.
- `SettingsRepositoryImpl` is 596 detekt lines of code, 4 under `LargeClass`: the relay settings live in their own
  delegated slice (`RelaySettings` / `RelaySettingsImpl`, same shape as `EventChannelSettings`) and the class grows
  by exactly two lines.
- Relay runtime: Node 24 (current LTS, `node:24-alpine` pinned by digest), `ws` 8.22.0 (latest, 2026-09-26),
  tests with the built-in `node:test` runner, syntax lint with `node --check` (no dev dependencies). Container built
  from `relay/Dockerfile` by the PaaS (Coolify "Dockerfile" build pack, base directory `relay`). TARGET per the
  house grammar is declared in `.lifecycle/local/DESPLIEGUE.env` and is NOT copied here.
- Relay location: `relay/` in this repository is PROVISIONAL — the fork's `REGLAS.md` reserves that decision for the
  human. The folder is self-contained (no Gradle coupling) so moving it to its own repository later is a `git mv`.
  `REGLAS.md` lives on the unmerged governance branch (PR #2), not on `main`; its relay lines (location, BARATO/CI
  threads) are a follow-up once PR #2 is merged.
- Branch base: `main` (`cb87578`), which does not yet contain `REGLAS.md` or the fork note in `CLAUDE.md`.
- Quality gates for the app run in the fork's GitHub Actions (`gh pr checks`). Lint is pre-checked locally with the
  standalone `ktlint` 1.8.0 and `detekt-cli` 2.0.0-alpha.5 (default rules — measured: both report 0 findings on
  `main`, matching CI). `act` is not installed, so TOOLS.md's `gh act --validate` step is replaced by the CI run.

**Scope boundary — files this plan may touch (and NO others):**

Android app (`.../` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp`, `.../test/` =
`app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp`):
- `gradle/libs.versions.toml`, `app/build.gradle.kts`
- `.../data/model/TunnelProviderType.kt`, `.../data/model/ServerConfig.kt`
- `.../data/repository/RelaySettings.kt` (new), `.../data/repository/RelaySettingsImpl.kt` (new),
  `.../data/repository/SettingsRepository.kt`, `.../data/repository/SettingsRepositoryImpl.kt`, `.../di/AppModule.kt`
- `.../services/tunnel/RelayFrames.kt` (new), `.../services/tunnel/LocalRequestReplayer.kt` (new),
  `.../services/tunnel/RelayTarget.kt` (new), `.../services/tunnel/RelaySession.kt` (new),
  `.../services/tunnel/RelayTunnelProvider.kt` (new), `.../services/tunnel/TunnelManager.kt`
- `.../ui/viewmodels/MainViewModel.kt`, `.../ui/screens/settings/TunnelSettingsScreen.kt`,
  `app/src/main/res/values/strings.xml`
- `.../services/mcp/AdbConfigHandler.kt`, `.../services/mcp/AdbConfigReceiver.kt` (KDoc only)
- Tests: `.../test/data/model/ServerConfigTest.kt`, `.../test/data/repository/SettingsRepositoryImplTest.kt`,
  `.../test/data/repository/SettingsRepositoryLoggingTest.kt`, `.../test/data/repository/EventChannelSettingsTest.kt`,
  `.../test/data/repository/SettingsRepositoryServerRunningTest.kt`,
  `.../test/data/repository/SettingsRepositoryUpdateCheckTest.kt`,
  `app/src/testGms/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/GeofenceConfigRepositoryTest.kt`,
  `.../test/services/tunnel/RelayFrameCodecTest.kt` (new), `.../test/services/tunnel/LocalRequestReplayerTest.kt` (new),
  `.../test/services/tunnel/RelayTargetTest.kt` (new), `.../test/services/tunnel/RelayTestServer.kt` (new),
  `.../test/services/tunnel/RelayTunnelProviderTest.kt` (new), `.../test/services/tunnel/TunnelManagerTest.kt`,
  `.../test/ui/viewmodels/MainViewModelTest.kt`, `.../test/services/mcp/AdbConfigHandlerTest.kt`
- Docs: `docs/PROJECT.md`, `README.md`, `docs/ARCHITECTURE.md`, `CONTRIBUTING.md`

Relay service (all new): `relay/package.json`, `relay/package-lock.json`, `relay/.gitignore`, `relay/.dockerignore`,
`relay/Dockerfile`, `relay/README.md`, `relay/src/config.js`, `relay/src/frames.js`, `relay/src/relay.js`,
`relay/src/server.js`, `relay/test/helpers.js`, `relay/test/config.test.js`, `relay/test/frames.test.js`,
`relay/test/relay.test.js`

CI (new): `.github/workflows/relay.yml`

**Plan document handling:** `docs/plans/66_websocket_relay_tunnel_provider_20261009232240.md` is a tracked, PERMANENT
artifact committed as a standalone `docs: add plan 66` commit. It is the ONLY file outside the list above that this
plan's PR adds.

**Branch:** `feat/websocket-relay-provider` from the latest `main`. **PR title:** `Plan 66: WebSocket relay tunnel
provider and self-hosted relay`. No AI attribution anywhere (commits, PR, code).

---

## User Story 1 — Dependencies, provider type and relay settings

**Why:** The enum value, the two persisted settings and their validated repository access are what every later story
compiles against. `ktor-client-websockets` is Ktor's documented marker artifact (the plugin classes live in
`ktor-client-core`); `ktor-server-websockets` backs the in-process relay stand-in used by the provider tests.

**Acceptance criteria:**
- [ ] `TunnelProviderType.RELAY` exists; `ServerConfig.relayUrl` and `ServerConfig.relayDeviceToken` default to `""`.
- [ ] `SettingsRepository` exposes `updateRelayUrl`, `updateRelayDeviceToken`, `validateRelayUrl`,
      `validateRelayDeviceToken`, `isRelayUrlInsecure` (via `RelaySettings`).
- [ ] `validateRelayUrl` accepts `""`, `ws://host`, `wss://host:port`; rejects `https://`, no-host, user-info, path,
      query, fragment, unparsable input. `validateRelayDeviceToken` accepts `""` and ≥ 16 trimmed characters.
- [ ] `isRelayUrlInsecure` is `true` only for `ws://` outside loopback / RFC 1918 hosts.
- [ ] Values round-trip through DataStore (trimmed) and appear in `serverConfig`; changes are logged without values.
- [ ] `RelaySettingsImpl` is Hilt-bound and every `SettingsRepositoryImpl(...)` construction site passes it.

### Task 1.1 — Add the Ktor WebSocket artifacts

**Action 1 — modify** `gradle/libs.versions.toml`: insert after the `ktor-server-cors` line.

```toml
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version.ref = "ktor" }
```

**Action 2 — modify** `gradle/libs.versions.toml`: insert after the `ktor-client-okhttp` line.

```toml
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
```

**Action 3 — modify** `app/build.gradle.kts`: insert after `implementation(libs.ktor.client.content.negotiation)`.

```kotlin

    // Ktor Client WebSockets (relay tunnel provider — device-side socket to the self-hosted relay)
    implementation(libs.ktor.client.websockets)
```

**Action 4 — modify** `app/build.gradle.kts`: insert after `testImplementation(libs.ktor.server.test.host)`.

```kotlin
    testImplementation(libs.ktor.server.websockets)
```

**Definition of Done:**
- [ ] `io.ktor.client.plugins.websocket.*` and `io.ktor.server.websocket.*` resolve in main and test sources.

### Task 1.2 — Provider type and config fields

**Action 1 — modify** `.../data/model/TunnelProviderType.kt`: add the third value.

```kotlin
    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK,

    /** Self-hosted WebSocket relay — the device dials out to a relay you run; clients use the relay's HTTPS URL. */
    RELAY,
}
```

**Action 2 — modify** `.../data/model/ServerConfig.kt`: KDoc (replace the `tunnelProvider` line, add two after the
`cloudflareTunnelToken` line), fields after `cloudflareTunnelExtraArgs`, constant after `MAX_DEVICE_SLUG_LENGTH`.

```kotlin
 * @property tunnelProvider The tunnel provider type (Cloudflare, ngrok or the self-hosted WebSocket relay).
```

```kotlin
 * @property relayUrl Base URL of the self-hosted WebSocket relay (`ws://host[:port]` or `wss://host[:port]`, no
 *   path — the device appends `/device`). Empty means not configured.
 * @property relayDeviceToken Bearer token the device presents when opening the relay's `/device` socket
 *   (at least [MIN_RELAY_DEVICE_TOKEN_LENGTH] characters once trimmed).
```

```kotlin
    val cloudflareTunnelExtraArgs: String = "",
    val relayUrl: String = "",
    val relayDeviceToken: String = "",
```

```kotlin
        /** Minimum length of the relay device token (the relay enforces the same bound). */
        const val MIN_RELAY_DEVICE_TOKEN_LENGTH = 16
```

**Definition of Done:**
- [ ] `ServerConfig()` has both new fields empty; `TunnelProviderType.entries.size == 3`.

### Task 1.3 — Relay settings slice

**Action 1 — create** `.../data/repository/RelaySettings.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

/**
 * Settings slice for the self-hosted WebSocket relay tunnel provider; [SettingsRepository] delegates to it.
 */
interface RelaySettings {
    /**
     * Updates the WebSocket relay base URL (empty = not configured). Stored trimmed.
     *
     * @param url The new relay URL. Must pass [validateRelayUrl] first.
     */
    suspend fun updateRelayUrl(url: String)

    /**
     * Updates the bearer token the device presents on the relay's `/device` socket (empty clears it). Stored trimmed.
     *
     * @param token The new token. Must pass [validateRelayDeviceToken] first.
     */
    suspend fun updateRelayDeviceToken(token: String)

    /**
     * Validates a WebSocket relay base URL.
     *
     * An empty or blank value IS valid and means "not configured" → [Result.success] with `""`.
     * Otherwise the URL MUST be `ws://host[:port]` or `wss://host[:port]`: a host is required and user-info,
     * path, query and fragment are rejected (the public URL is derived from it and cannot carry a prefix).
     *
     * This is a pure validation function with no I/O; it is intentionally non-suspending.
     *
     * @return [Result.success] with the trimmed URL, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRelayUrl(url: String): Result<String>

    /**
     * Validates a relay device token: empty is valid (not configured), otherwise the trimmed value MUST have at
     * least [com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH]
     * characters. Pure; non-suspending.
     *
     * @return [Result.success] with the trimmed token, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRelayDeviceToken(token: String): Result<String>

    /**
     * `true` when [url] uses cleartext `ws://` towards a host that is neither loopback nor RFC 1918 private —
     * the device token and every client credential would travel unencrypted. Pure; `false` for unparsable input.
     */
    fun isRelayUrlInsecure(url: String): Boolean
}
```

**Action 2 — create** `.../data/repository/RelaySettingsImpl.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import java.net.URI
import javax.inject.Inject

/** Preferences key for the relay base URL; also read by `mapPreferencesToServerConfig` (SettingsRepositoryImpl.kt). */
internal val RELAY_URL_KEY = stringPreferencesKey("relay_url")

/** Preferences key for the relay device token; also read by `mapPreferencesToServerConfig`. */
internal val RELAY_DEVICE_TOKEN_KEY = stringPreferencesKey("relay_device_token")

private const val SECURE_RELAY_SCHEME = "wss"
private val RELAY_SCHEMES = setOf("ws", SECURE_RELAY_SCHEME)

/** Loopback and RFC 1918 literals (optionally bracketed IPv6 loopback) for which cleartext `ws://` is acceptable. */
private val PRIVATE_HOST_PATTERN =
    Regex(
        "^(localhost|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|\\[?::1]?|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
            "192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})$",
        RegexOption.IGNORE_CASE,
    )

/**
 * [RelaySettings] implementation backed by the shared Preferences DataStore.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 * @property settingsChangeLogger Coalescing logger that surfaces settings changes in the server log.
 */
class RelaySettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : RelaySettings {
        override suspend fun updateRelayUrl(url: String) {
            val trimmed = url.trim()
            dataStore.edit { prefs ->
                val old = prefs[RELAY_URL_KEY] ?: ""
                prefs[RELAY_URL_KEY] = trimmed
                settingsChangeLogger.submit("relay_url", old, trimmed) { o, n ->
                    "Relay URL changed ${o.ifEmpty { "(none)" }} → ${n.ifEmpty { "(none)" }}"
                }
            }
        }

        override suspend fun updateRelayDeviceToken(token: String) {
            val trimmed = token.trim()
            dataStore.edit { prefs ->
                val old = prefs[RELAY_DEVICE_TOKEN_KEY] ?: ""
                prefs[RELAY_DEVICE_TOKEN_KEY] = trimmed
                settingsChangeLogger.submit("relay_device_token", old, trimmed) { _, _ -> "Relay device token changed" }
            }
        }

        override fun validateRelayUrl(url: String): Result<String> {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) {
                return Result.success("")
            }
            return runCatching { URI(trimmed) }
                .fold(
                    onSuccess = { validateRelayUri(it, trimmed) },
                    onFailure = { Result.failure(IllegalArgumentException("Invalid relay URL: ${it.message}")) },
                )
        }

        override fun validateRelayDeviceToken(token: String): Result<String> {
            val trimmed = token.trim()
            return when {
                trimmed.isEmpty() -> Result.success("")
                trimmed.length < ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH ->
                    Result.failure(
                        IllegalArgumentException(
                            "Relay device token must be at least " +
                                "${ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH} characters",
                        ),
                    )
                else -> Result.success(trimmed)
            }
        }

        override fun isRelayUrlInsecure(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return uri.scheme.equals("ws", ignoreCase = true) && !PRIVATE_HOST_PATTERN.matches(host)
        }

        private fun validateRelayUri(
            uri: URI,
            original: String,
        ): Result<String> =
            when {
                uri.scheme?.lowercase() !in RELAY_SCHEMES ->
                    Result.failure(IllegalArgumentException("Relay URL must start with ws:// or wss://"))
                uri.host.isNullOrEmpty() ->
                    Result.failure(IllegalArgumentException("Relay URL must include a valid host"))
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                    Result.failure(IllegalArgumentException("Relay URL must not contain credentials, query or fragment"))
                uri.rawPath.trimEnd('/').isNotEmpty() ->
                    Result.failure(IllegalArgumentException("Relay URL must not contain a path (scheme://host[:port] only)"))
                else -> Result.success(original.trimEnd('/'))
            }
    }
```

**Action 3 — modify** `.../data/repository/SettingsRepository.kt`: extend the slice.

```kotlin
@Suppress("TooManyFunctions")
interface SettingsRepository :
    EventChannelSettings,
    RelaySettings {
```

**Action 4 — modify** `.../data/repository/SettingsRepositoryImpl.kt`: constructor + delegation (exactly these two
lines inside the class).

```kotlin
        eventChannelSettings: EventChannelSettings,
        relaySettings: RelaySettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        RelaySettings by relaySettings {
```

**Action 5 — modify** `.../data/repository/SettingsRepositoryImpl.kt`, top-level `mapPreferencesToServerConfig`: insert
after `cloudflareTunnelExtraArgs = prefs[CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY] ?: "",`.

```kotlin
        relayUrl = prefs[RELAY_URL_KEY] ?: "",
        relayDeviceToken = prefs[RELAY_DEVICE_TOKEN_KEY] ?: "",
```

**Action 6 — modify** `.../di/AppModule.kt`: imports (alphabetical, after `OAuthClientRepositoryImpl`) and binding
after `bindEventChannelSettings`.

```kotlin
import com.danielealbano.androidremotecontrolmcp.data.repository.RelaySettings
import com.danielealbano.androidremotecontrolmcp.data.repository.RelaySettingsImpl
```

```kotlin
    /** Binds the relay-tunnel settings slice that [SettingsRepositoryImpl] delegates to. */
    @Binds
    @Singleton
    abstract fun bindRelaySettings(impl: RelaySettingsImpl): RelaySettings
```

**Action 7 — modify** the six test construction sites, adding the delegate as the fourth argument:

- `.../test/data/repository/SettingsRepositoryImplTest.kt`, `SettingsRepositoryLoggingTest.kt`,
  `SettingsRepositoryServerRunningTest.kt`, `SettingsRepositoryUpdateCheckTest.kt`: after
  `EventChannelSettingsImpl(dataStore, changeLogger),` add
  ```kotlin
                RelaySettingsImpl(dataStore, changeLogger),
  ```
- `.../test/data/repository/EventChannelSettingsTest.kt` (`UrlValidation.repo`): after the `EventChannelSettingsImpl(...)`
  argument add
  ```kotlin
                RelaySettingsImpl(
                    mockk(relaxed = true),
                    SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L),
                ),
  ```
- `app/src/testGms/.../data/repository/GeofenceConfigRepositoryTest.kt`: replace the one-line construction with
  ```kotlin
            val settingsRepository =
                SettingsRepositoryImpl(
                    dataStore,
                    changeLogger,
                    EventChannelSettingsImpl(dataStore, changeLogger),
                    RelaySettingsImpl(dataStore, changeLogger),
                )
  ```

**Definition of Done:**
- [ ] `SettingsRepository` exposes the five `RelaySettings` members through delegation; Hilt resolves `RelaySettings`.

### Task 1.4 — Tests for US1

**File**: `.../test/data/model/ServerConfigTest.kt` (`DefaultValues`, after `default ngrokDomain is empty`;
`CompanionConstants`: one row)

| Test | Verifies |
|------|----------|
| `default relayUrl is empty` | `ServerConfig().relayUrl == ""` |
| `default relayDeviceToken is empty` | `ServerConfig().relayDeviceToken == ""` |
| `MIN_RELAY_DEVICE_TOKEN_LENGTH is 16` | constant value |

**File**: `.../test/data/repository/SettingsRepositoryImplTest.kt`

**Setup**: existing `repository` / `testScope`. One row goes into the EXISTING `UpdateTunnelProvider` class; the
others into NEW `@Nested` classes `UpdateRelayUrl`, `UpdateRelayDeviceToken`, `ValidateRelayUrl`,
`ValidateRelayDeviceToken`, `IsRelayUrlInsecure` placed after `UpdateNgrokDomain`.

| Test | Verifies |
|------|----------|
| `updates tunnel provider to RELAY` (`UpdateTunnelProvider`) | `updateTunnelProvider(RELAY)` → `getServerConfig().tunnelProvider == RELAY` |
| `updates relay url trimmed` | `updateRelayUrl("  wss://relay.example.com ")` → config `relayUrl == "wss://relay.example.com"` |
| `clears relay url with empty string` | set then `updateRelayUrl("")` → `""` |
| `updates relay device token trimmed` | `" 0123456789abcdef "` → `"0123456789abcdef"` |
| `empty url is valid and normalizes to empty` | `validateRelayUrl("  ")` → success `""` |
| `ws url with host is valid` | `ws://127.0.0.1:8081` → success, same string |
| `wss url with port is valid` | `wss://relay.example.com:8443` → success |
| `trailing slash is stripped` | `wss://relay.example.com/` → success `wss://relay.example.com` |
| `trimmed value is returned` | `" wss://relay.example.com "` → success `"wss://relay.example.com"` |
| `https scheme is rejected` | failure message contains `ws://` |
| `missing host is rejected` | `wss://` → failure |
| `user info is rejected` | `wss://user:pw@relay.example.com` → failure |
| `path is rejected` | `wss://relay.example.com/base` → failure message contains `path` |
| `query is rejected` | `wss://relay.example.com/?x=1` → failure |
| `fragment is rejected` | `wss://relay.example.com/#frag` → failure |
| `unparsable url is rejected` | `wss://relay example.com` → failure message starts with `Invalid relay URL` |
| `empty token is valid` | `validateRelayDeviceToken("")` → success `""` |
| `token of 16 characters is valid and trimmed` | `" 0123456789abcdef "` → success `0123456789abcdef` |
| `token shorter than 16 characters is rejected` | 15 chars → failure message contains `16` |
| `wss url is not insecure` | `wss://relay.example.com` → false |
| `ws url to public host is insecure` | `ws://relay.example.com` → true |
| `ws url to loopback and private hosts is not insecure` | `ws://127.0.0.1:8080`, `ws://localhost`, `ws://[::1]:8080`, `ws://192.168.1.5`, `ws://10.0.0.2`, `ws://172.16.0.9` → false |
| `unparsable url is not insecure` | `ws://a b` → false |

**File**: `.../test/data/repository/SettingsRepositoryLoggingTest.kt` (after `event channel endpoint logs old to new`)

| Test | Verifies |
|------|----------|
| `relay url logs old to new` | `updateRelayUrl("wss://r.example.com")` → single message `Relay URL changed (none) → wss://r.example.com` |
| `relay device token logs without value` | `updateRelayDeviceToken("super-secret-relay-token")` → `Relay device token changed`, message does not contain `super-secret` |

**Definition of Done:**
- [ ] Every validation branch above has a row; no test touches `RelaySettingsImpl` directly (always via the repository).

---
## User Story 2 — Relay tunnel provider

**Why:** The device-side half of the transport, split so each piece is unit-testable in-process and no class crosses
detekt's size thresholds: frame codec, replayer (local HTTP), target resolver, one-socket session pump, and the
provider that owns the client, the reconnect loop and the status.

**Acceptance criteria:**
- [ ] `start()` publishes `Error` for missing URL/token, token shorter than 16, bad scheme or a path; otherwise connects
      with `Authorization: Bearer <token>` to `<relayUrl>/device`, publishes `Connected([TunnelEndpoint(publicUrl,
      true)], RELAY)` and returns after the first outcome (connected or first failure).
- [ ] A `request` frame is replayed to `http://127.0.0.1:<localPort><path>` (origin-form only) with method, headers
      (hop-by-hop dropped) and body; the answer returns as a `response` frame with status, headers (`Content-Length` /
      `Transfer-Encoding` dropped) and base64 body. `3xx` answers pass through unfollowed. Failures become `400` /
      `502` / `503` / `504` frames with generic JSON messages, never exceptions; responses above 8 MiB become `502`.
- [ ] At most 8 replays run concurrently; the 9th gets a `503` frame immediately. `ping` → `pong`; malformed/unknown
      frames are ignored; the session stays up.
- [ ] Handshake rejection, socket drop, idle silence and relay errors lead to `Error("…; reconnecting in Ns")` then a
      retry with 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s…; a session ≥ 10 s long resets the backoff; close code `4000`
      waits 30 s. The status does not flip back to `Connecting` between retries.
- [ ] `stop()` cancels the loop, closes the socket (Close frame observed by the relay within 2 s), cancels and closes
      the client, sets `Disconnected`; idempotent. Cancelling a pending `start()` stops everything too. `start()`
      twice throws `IllegalStateException("Tunnel is already running")`.

### Task 2.1 — Frame codec

**Action 1 — create** `.../services/tunnel/RelayFrames.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire frames exchanged with the relay over the device WebSocket (text frames, JSON).
 *
 * Relay → device: [RelayRequestFrame] (`type=request`) and `{"type":"ping"}`.
 * Device → relay: [RelayResponseFrame] (`type=response`) and `{"type":"pong"}`.
 * Header values are always arrays; bodies are base64.
 */
@Serializable
internal data class RelayRequestFrame(
    val type: String = RelayFrameCodec.TYPE_REQUEST,
    val id: String,
    val method: String,
    val path: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

@Serializable
internal data class RelayResponseFrame(
    val type: String = RelayFrameCodec.TYPE_RESPONSE,
    val id: String,
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

@Serializable
internal data class RelayControlFrame(
    val type: String,
)

/** Decoded inbound frame. */
internal sealed class RelayInbound {
    data class Request(
        val frame: RelayRequestFrame,
    ) : RelayInbound()

    data object Ping : RelayInbound()

    data object Pong : RelayInbound()

    data class Unknown(
        val type: String,
    ) : RelayInbound()

    data class Malformed(
        val reason: String,
    ) : RelayInbound()
}

internal object RelayFrameCodec {
    const val TYPE_REQUEST = "request"
    const val TYPE_RESPONSE = "response"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Never throws: malformed input yields [RelayInbound.Malformed]. */
    fun decode(text: String): RelayInbound =
        try {
            decodeElement(json.parseToJsonElement(text))
        } catch (e: SerializationException) {
            RelayInbound.Malformed(e.message ?: "invalid JSON")
        } catch (e: IllegalArgumentException) {
            RelayInbound.Malformed(e.message ?: "invalid frame")
        }

    fun encode(frame: RelayResponseFrame): String = json.encodeToString(RelayResponseFrame.serializer(), frame)

    fun encodePong(): String = json.encodeToString(RelayControlFrame.serializer(), RelayControlFrame(TYPE_PONG))

    private fun decodeElement(element: JsonElement): RelayInbound {
        val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: ""
        return when (type) {
            TYPE_REQUEST -> RelayInbound.Request(json.decodeFromJsonElement(RelayRequestFrame.serializer(), element))
            TYPE_PING -> RelayInbound.Ping
            TYPE_PONG -> RelayInbound.Pong
            else -> RelayInbound.Unknown(type)
        }
    }
}
```

**Definition of Done:**
- [ ] `RelayFrameCodec.decode` never throws for arbitrary text.

### Task 2.2 — Local request replayer

**Action 1 — create** `.../services/tunnel/LocalRequestReplayer.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Base64

/**
 * Replays a relay request frame against the local MCP server (`http://127.0.0.1:<port>`) and packs the
 * answer into a response frame. Failures never propagate: they become 4xx/5xx response frames (generic JSON
 * messages — loopback details stay in logcat) so the relay can still answer its HTTP client.
 */
internal class LocalRequestReplayer(
    private val client: HttpClient,
    private val timeoutMs: Long = REPLAY_TIMEOUT_MS,
    private val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) {
    suspend fun replay(
        frame: RelayRequestFrame,
        localPort: Int,
    ): RelayResponseFrame =
        if (isOriginForm(frame.path)) {
            replayValidated(frame, localPort)
        } else {
            Logger.w(TAG, "Relay request rejected: path is not origin-form (${frame.method})")
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid request path")
        }

    /** Builds a JSON error frame; also used by the session for `503 device busy`. */
    fun errorFrame(
        id: String,
        status: HttpStatusCode,
        message: String,
    ): RelayResponseFrame =
        RelayResponseFrame(
            id = id,
            status = status.value,
            headers = mapOf("Content-Type" to listOf(ContentType.Application.Json.toString())),
            body = Base64.getEncoder().encodeToString("""{"error":${JsonPrimitive(message)}}""".toByteArray()),
        )

    private suspend fun replayValidated(
        frame: RelayRequestFrame,
        localPort: Int,
    ): RelayResponseFrame =
        try {
            val response =
                client.request("http://127.0.0.1:$localPort${frame.path}") {
                    applyFrame(frame)
                    timeout {
                        requestTimeoutMillis = timeoutMs
                        socketTimeoutMillis = timeoutMs
                    }
                }
            packResponse(frame.id, response)
        } catch (e: HttpRequestTimeoutException) {
            Logger.w(TAG, "Local replay timed out: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.GatewayTimeout, "local MCP server did not answer in time")
        } catch (e: IOException) {
            Logger.w(TAG, "Local replay failed: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadGateway, "local MCP server unreachable")
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "Local replay rejected: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid relayed request")
        } catch (e: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            Logger.w(TAG, "Local replay rejected: ${frame.method} ${sanitizedPath(frame.path)}", e)
            errorFrame(frame.id, HttpStatusCode.BadRequest, "invalid relayed request")
        }

    private suspend fun packResponse(
        id: String,
        response: HttpResponse,
    ): RelayResponseFrame {
        val channel = response.bodyAsChannel()
        val bytes = channel.readRemaining(maxResponseBytes + 1).readByteArray()
        if (bytes.size > maxResponseBytes) {
            channel.cancel(null)
            Logger.w(TAG, "Local response dropped: body exceeds $maxResponseBytes bytes")
            return errorFrame(id, HttpStatusCode.BadGateway, "response too large (limit $maxResponseBytes bytes)")
        }
        return RelayResponseFrame(
            id = id,
            status = response.status.value,
            headers = forwardableResponseHeaders(response.headers),
            body = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun HttpRequestBuilder.applyFrame(frame: RelayRequestFrame) {
        method = HttpMethod.parse(frame.method.uppercase())
        frame.headers.forEach { (name, values) ->
            if (name.lowercase() !in SKIPPED_REQUEST_HEADERS) {
                values.forEach { headers.append(name, it) }
            }
        }
        val bytes = Base64.getDecoder().decode(frame.body)
        if (bytes.isNotEmpty()) {
            setBody(ByteArrayContent(bytes, contentType = frame.contentType()))
        }
    }

    private fun RelayRequestFrame.contentType(): ContentType =
        headers.entries
            .firstOrNull { it.key.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream

    private fun forwardableResponseHeaders(headers: Headers): Map<String, List<String>> =
        headers
            .entries()
            .filterNot { it.key.lowercase() in SKIPPED_RESPONSE_HEADERS }
            .associate { it.key to it.value }

    companion object {
        private const val TAG = "MCP:RelayReplay"
        private const val CONTENT_TYPE_HEADER = "content-type"
        private const val CAPABILITY_LINK_PREFIX = "/s/"

        /** Per-request budget; shorter than the relay's 120 s so the device's own 504 frame reaches the client. */
        const val REPLAY_TIMEOUT_MS = 100_000L

        /** Largest response body relayed; above it the device answers 502 (OkHttp drops frames past 16 MiB). */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** Hop-by-hop or engine-managed request headers never copied onto the local request. */
        private val SKIPPED_REQUEST_HEADERS =
            setOf(
                "host",
                "content-length",
                "content-type",
                "transfer-encoding",
                "connection",
                "keep-alive",
                "upgrade",
                "te",
                "trailer",
                "expect",
                "proxy-authorization",
                "proxy-authenticate",
                "accept-encoding",
            )

        /** Headers the relay recomputes itself. */
        private val SKIPPED_RESPONSE_HEADERS =
            setOf(
                "content-length",
                "transfer-encoding",
                "connection",
                "keep-alive",
            )

        /** Origin-form only: a leading `/` and not `//` (which `URI` would read as an authority). */
        internal fun isOriginForm(path: String): Boolean = path.startsWith("/") && !path.startsWith("//")

        /** Log-safe path: no query string, capability links redacted. */
        internal fun sanitizedPath(path: String): String {
            val noQuery = path.substringBefore('?')
            return if (noQuery.startsWith(CAPABILITY_LINK_PREFIX)) "$CAPABILITY_LINK_PREFIX<redacted>" else noQuery
        }
    }
}
```

Context: `Content-Type` travels with the body through `ByteArrayContent` (the raw header is skipped so it is not sent
twice); an empty body is never attached (OkHttp rejects a body on `GET`/`HEAD`). The body is read through
`readRemaining(max + 1)` so an oversized response is detected without buffering it whole. `followRedirects = false`
lives on the client (Task 2.5), so `3xx` answers are packed as-is.

**Definition of Done:**
- [ ] Every failure path yields a frame whose body is `{"error":"…"}` JSON without host/port details.

### Task 2.3 — Relay target resolver

**Action 1 — create** `.../services/tunnel/RelayTarget.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import java.net.URI

/**
 * Resolved relay endpoints for one tunnel session.
 *
 * @property deviceUrl WebSocket URL the device dials (`<relayUrl>/device`).
 * @property publicUrl HTTP(S) base URL MCP clients use (`wss`→`https`, `ws`→`http`; same authority).
 * @property token Trimmed bearer presented on the device socket.
 */
internal data class RelayTarget(
    val deviceUrl: String,
    val publicUrl: String,
    val token: String,
) {
    companion object {
        const val DEVICE_PATH = "/device"
        private const val SECURE_SCHEME = "wss"
        private val WS_SCHEMES = setOf("ws", SECURE_SCHEME)

        /** Defensive re-check of what `RelaySettings` validation guarantees; providers never read settings. */
        fun resolve(config: ServerConfig): Result<RelayTarget> {
            val base = config.relayUrl.trim().trimEnd('/')
            val token = config.relayDeviceToken.trim()
            val uri = runCatching { URI(base) }.getOrNull()
            return when {
                base.isEmpty() -> failure("Relay URL is required")
                token.isEmpty() -> failure("Relay device token is required")
                token.length < ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH ->
                    failure("Relay device token must be at least ${ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH} characters")
                uri == null -> failure("Relay URL is not a valid URI")
                uri.scheme?.lowercase() !in WS_SCHEMES || uri.host.isNullOrEmpty() ->
                    failure("Relay URL must be ws:// or wss:// with a host")
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                    failure("Relay URL must not contain credentials, query or fragment")
                uri.rawPath.isNotEmpty() -> failure("Relay URL must not contain a path")
                else -> Result.success(RelayTarget(base + DEVICE_PATH, publicUrlFor(uri), token))
            }
        }

        private fun publicUrlFor(uri: URI): String {
            val scheme = if (uri.scheme.equals(SECURE_SCHEME, ignoreCase = true)) "https" else "http"
            return "$scheme://${uri.rawAuthority}"
        }

        private fun failure(message: String): Result<RelayTarget> = Result.failure(IllegalArgumentException(message))
    }
}
```

**Definition of Done:**
- [ ] `resolve(ServerConfig(relayUrl = "wss://r.example.com/", relayDeviceToken = "0123456789abcdef"))` →
      `deviceUrl == "wss://r.example.com/device"`, `publicUrl == "https://r.example.com"`.

### Task 2.4 — Session pump

**Action 1 — create** `.../services/tunnel/RelaySession.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Why a relay session ended; `replaced` is the relay's close code 4000 (another device took the slot). */
internal data class RelaySessionEnd(
    val reason: String,
    val replaced: Boolean,
)

/**
 * Pumps one open device socket: answers pings, replays request frames concurrently (bounded by [replaySlots])
 * and ends when the relay closes the socket or stays silent for [idleTimeoutMs]. In-flight replays are
 * cancelled when the socket goes away; the Close frame is sent even if the caller is being cancelled.
 */
internal class RelaySession(
    private val session: DefaultClientWebSocketSession,
    private val localPort: Int,
    private val replayer: LocalRequestReplayer,
    private val replaySlots: Semaphore,
    private val idleTimeoutMs: Long,
) {
    suspend fun pump(): RelaySessionEnd {
        val context = currentCoroutineContext()
        val replayScope = CoroutineScope(context + SupervisorJob(context[Job]))
        var end: RelaySessionEnd? = null
        try {
            while (end == null) {
                val received = withTimeoutOrNull(idleTimeoutMs) { session.incoming.receiveCatching() }
                val frame = received?.getOrNull()
                when {
                    received == null -> end = RelaySessionEnd("Relay silent for $idleTimeoutMs ms", replaced = false)
                    frame == null -> end = closedEnd()
                    frame is Frame.Text -> handleText(replayScope, frame.readText())
                }
            }
        } finally {
            replayScope.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "device closing"))
                }
            }
        }
        return end ?: RelaySessionEnd("Relay connection closed", replaced = false)
    }

    private suspend fun closedEnd(): RelaySessionEnd {
        val reason = withTimeoutOrNull(CLOSE_TIMEOUT_MS) { session.closeReason.await() }
        return when {
            reason == null -> RelaySessionEnd("Relay connection closed", replaced = false)
            reason.code == CLOSE_CODE_REPLACED ->
                RelaySessionEnd("Relay replaced this device with another connection", replaced = true)
            else -> RelaySessionEnd("Relay connection closed (code ${reason.code}: ${reason.message})", replaced = false)
        }
    }

    private fun handleText(
        replayScope: CoroutineScope,
        text: String,
    ) {
        when (val inbound = RelayFrameCodec.decode(text)) {
            is RelayInbound.Request -> replayScope.launch { answer(inbound.frame) }

            RelayInbound.Ping -> replayScope.launch { sendText(RelayFrameCodec.encodePong()) }

            RelayInbound.Pong -> Unit

            is RelayInbound.Unknown -> Logger.w(TAG, "Ignoring relay frame of unknown type '${inbound.type}'")

            is RelayInbound.Malformed -> Logger.w(TAG, "Ignoring malformed relay frame: ${inbound.reason}")
        }
    }

    private suspend fun answer(frame: RelayRequestFrame) {
        val response =
            if (replaySlots.tryAcquire()) {
                try {
                    replayer.replay(frame, localPort)
                } finally {
                    replaySlots.release()
                }
            } else {
                Logger.w(TAG, "Relay request refused: all replay slots are busy")
                replayer.errorFrame(frame.id, HttpStatusCode.ServiceUnavailable, "device busy")
            }
        sendText(RelayFrameCodec.encode(response))
    }

    private suspend fun sendText(text: String) {
        try {
            session.send(Frame.Text(text))
        } catch (e: ClosedSendChannelException) {
            Logger.w(TAG, "Relay socket closed before the frame could be sent", e)
        } catch (e: IOException) {
            Logger.w(TAG, "Failed to send frame to relay", e)
        }
    }

    companion object {
        private const val TAG = "MCP:RelaySession"
        private const val CLOSE_TIMEOUT_MS = 2_000L

        /** Relay close code: another authenticated device connection took this device's slot. */
        internal const val CLOSE_CODE_REPLACED: Short = 4000
    }
}
```

**Definition of Done:**
- [ ] A session whose relay closes with `4000` ends with `replaced == true`; any other close carries the code and
      message in `reason`.

### Task 2.5 — The provider

**Action 1 — create** `.../services/tunnel/RelayTunnelProvider.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Result of one relay session: how long it stayed connected (0 = handshake failed) and why it ended. */
private data class SessionOutcome(
    val connectedForMs: Long,
    val reason: String,
    val replaced: Boolean,
)

/**
 * Self-hosted WebSocket relay tunnel provider.
 *
 * The device opens an OUTBOUND WebSocket to `<relayUrl>/device` (bearer-authenticated); [RelaySession] pumps
 * it. The provider owns the HTTP client, the reconnect loop (exponential backoff 1 s … 30 s, reset after a
 * session of at least [MIN_STABLE_SESSION_MS]) and the published [status]; it redials a socket silent for
 * [idleTimeoutMs] and stops only on [stop] or when a pending [start] is cancelled.
 */
class RelayTunnelProvider
    @Inject
    constructor() : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val mutex = Mutex()
        private val exceptionHandler =
            CoroutineExceptionHandler { _, e ->
                Logger.e(TAG, "Relay tunnel loop failed", e)
                _status.value = TunnelStatus.Error("Relay tunnel failed: ${e.message}")
            }
        private val replaySlots = Semaphore(MAX_CONCURRENT_REPLAYS)
        private var sessionScope: CoroutineScope? = null
        private var client: HttpClient? = null
        private var connectionJob: Job? = null

        /** Overridable for tests; production builds an OkHttp client (no redirects, no silent retries). */
        internal var clientProvider: () -> HttpClient = ::createClient

        /** Overridable for tests. */
        internal var idleTimeoutMs: Long = IDLE_TIMEOUT_MS

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(connectionJob == null) { "Tunnel is already running" }
                val target =
                    RelayTarget.resolve(config).getOrElse { failure ->
                        _status.value = TunnelStatus.Error(failure.message ?: "Invalid relay configuration")
                        return
                    }
                _status.value = TunnelStatus.Connecting
                val httpClient = clientProvider()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
                client = httpClient
                sessionScope = scope
                connectionJob = scope.launch { connectionLoop(httpClient, target, localPort) }
            }
            try {
                _status.first { it !is TunnelStatus.Connecting }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stop() }
                throw e
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                val job = connectionJob ?: return
                job.cancelAndJoin()
                client?.cancel()
                client?.close()
                sessionScope?.cancel()
                connectionJob = null
                client = null
                sessionScope = null
                _status.value = TunnelStatus.Disconnected
                Logger.i(TAG, "Relay tunnel stopped")
            }
        }

        private suspend fun connectionLoop(
            httpClient: HttpClient,
            target: RelayTarget,
            localPort: Int,
        ) {
            val replayer = LocalRequestReplayer(httpClient)
            var failures = 0
            while (currentCoroutineContext().isActive) {
                val outcome = runSession(httpClient, target, localPort, replayer)
                failures = nextFailureCount(failures, outcome.connectedForMs)
                val delayMs = if (outcome.replaced) MAX_BACKOFF_MS else backoffMs(failures)
                _status.value = TunnelStatus.Error("${outcome.reason}; reconnecting in ${delayMs / MS_PER_SECOND}s")
                delay(delayMs)
            }
        }

        private suspend fun runSession(
            httpClient: HttpClient,
            target: RelayTarget,
            localPort: Int,
            replayer: LocalRequestReplayer,
        ): SessionOutcome {
            var connectedAt = 0L
            return try {
                val session =
                    httpClient.webSocketSession(target.deviceUrl) {
                        header(HttpHeaders.Authorization, "Bearer ${target.token}")
                    }
                connectedAt = System.currentTimeMillis()
                _status.value =
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint(url = target.publicUrl, valid = true)),
                        providerType = TunnelProviderType.RELAY,
                    )
                Logger.i(TAG, "Relay connected; public URL ${target.publicUrl}")
                val end = RelaySession(session, localPort, replayer, replaySlots, idleTimeoutMs).pump()
                SessionOutcome(connectedFor(connectedAt), end.reason, end.replaced)
            } catch (e: IOException) {
                failedOutcome(connectedAt, e)
            } catch (e: IllegalArgumentException) {
                failedOutcome(connectedAt, e)
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                failedOutcome(connectedAt, e)
            }
        }

        private fun failedOutcome(
            connectedAt: Long,
            e: Exception,
        ): SessionOutcome {
            Logger.w(TAG, "Relay session ended with error", e)
            return SessionOutcome(connectedFor(connectedAt), "Relay connection failed: ${e.message}", replaced = false)
        }

        private fun createClient(): HttpClient =
            HttpClient(OkHttp) {
                followRedirects = false
                engine {
                    config {
                        pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                        retryOnConnectionFailure(false)
                    }
                }
                install(WebSockets)
                install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
            }

        companion object {
            private const val TAG = "MCP:RelayTunnel"
            private const val MS_PER_SECOND = 1_000L
            private const val CONNECT_TIMEOUT_MS = 15_000L
            private const val INITIAL_BACKOFF_MS = 1_000L
            private const val MAX_BACKOFF_SHIFT = 5
            private const val WS_PING_INTERVAL_SECONDS = 30L
            private const val MAX_CONCURRENT_REPLAYS = 8

            /** Longest wait between redials; also used after the relay replaced this device (close code 4000). */
            internal const val MAX_BACKOFF_MS = 30_000L

            /** A session shorter than this does not reset the backoff (avoids connect/drop storms). */
            internal const val MIN_STABLE_SESSION_MS = 10_000L

            /** A socket that delivers nothing (not even a relay ping) for this long is closed and redialled. */
            internal const val IDLE_TIMEOUT_MS = 90_000L

            /** 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s… for the n-th consecutive failure (n ≥ 1). */
            internal fun backoffMs(failures: Int): Long {
                val shift = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
                return (INITIAL_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
            }

            /** Consecutive-failure counter: a stable session restarts the ladder at 1. */
            internal fun nextFailureCount(
                previous: Int,
                connectedForMs: Long,
            ): Int = if (connectedForMs >= MIN_STABLE_SESSION_MS) 1 else previous + 1

            private fun connectedFor(connectedAt: Long): Long =
                if (connectedAt == 0L) 0L else System.currentTimeMillis() - connectedAt
        }
    }
```

Context: `HttpTimeout` carries only the connect timeout (it applies to the WebSocket handshake); request/socket
timeouts are set per replay. `client.cancel()` is what actually tears the socket down — the session lives in the
client's scope. A `CancellationException` caught around `_status.first { … }` is a non-trivial catch (it stops the
provider before rethrowing), so detekt's `RethrowCaughtException` does not apply.

**Definition of Done:**
- [ ] No `@Suppress` annotation anywhere in the five new files.

### Task 2.6 — Tests for US2

**File**: `.../test/services/tunnel/RelayFrameCodecTest.kt`

| Test | Verifies |
|------|----------|
| `decode request frame yields all fields` | id/method/path/headers (array values)/body of a full `request` JSON |
| `decode request without headers and body uses defaults` | `headers == emptyMap()`, `body == ""` |
| `decode ping yields Ping` | `{"type":"ping"}` |
| `decode pong yields Pong` | `{"type":"pong"}` |
| `decode unknown type yields Unknown with the type` | `{"type":"hello"}` → `Unknown("hello")` |
| `decode non-object json yields Malformed` | `[1,2]` and `"x"` |
| `decode invalid json yields Malformed` | `not json` |
| `decode request missing required field yields Malformed` | request JSON without `path` |
| `encode response round-trips through Json` | decode `encode(frame)` with `Json` → equals input, `type == "response"` |
| `encodePong emits pong control frame` | `{"type":"pong"}` |

**File**: `.../test/services/tunnel/RelayTargetTest.kt`

| Test | Verifies |
|------|----------|
| `resolve appends device path and strips trailing slash` | `wss://r.example.com/` → `deviceUrl == "wss://r.example.com/device"` |
| `resolve maps wss to https public url` | `publicUrl == "https://r.example.com"` |
| `resolve maps ws to http public url with port` | `ws://127.0.0.1:1234` → `http://127.0.0.1:1234` |
| `resolve trims the token` | `" 0123456789abcdef "` → `token == "0123456789abcdef"` |
| `resolve fails on empty url` | message `Relay URL is required` |
| `resolve fails on blank token` | message `Relay device token is required` |
| `resolve fails on short token` | 15 chars → message contains `16` |
| `resolve fails on https scheme` | message contains `ws://` |
| `resolve fails without host` | `wss://` |
| `resolve fails on path` | `wss://r.example.com/base` → message contains `path` |
| `resolve fails on credentials query or fragment` | `wss://u:p@r.example.com`, `wss://r.example.com/?a=1`, `wss://r.example.com/#f` |

**File**: `.../test/services/tunnel/LocalRequestReplayerTest.kt`

**Setup**: `MockEngine` capturing the `HttpRequestData`; `HttpClient(engine) { followRedirects = false; install(HttpTimeout) }`;
`replayer = LocalRequestReplayer(client, timeoutMs = 1_000, maxResponseBytes = 64)`; frames built with
`RelayRequestFrame(id = "r1", method = "post", path = "/mcp?x=1", headers = …, body = base64)`; error frame bodies are
decoded from base64 and parsed as JSON.

| Test | Verifies |
|------|----------|
| `replay targets loopback with method and raw path` | URL `http://127.0.0.1:<port>/mcp?x=1`, `HttpMethod.Post` |
| `replay forwards allowed headers and drops hop-by-hop ones` | `X-Test`, `Authorization`, `Accept` forwarded; `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Expect`, `Accept-Encoding` absent |
| `replay decodes body and applies content type` | body bytes equal decoded base64; `request.body.contentType == application/json` |
| `replay omits body when frame body is empty` | `request.body is EmptyContent` (GET) |
| `replay unparsable content type falls back to octet-stream` | header `Content-Type: ???` → `application/octet-stream` |
| `replay packs status headers and base64 body` | `status 201`, `Mcp-Session-Id` kept, body base64 of response text |
| `replay drops content-length and transfer-encoding from response headers` | neither key present (case-insensitive) |
| `replay passes a redirect through without following it` | engine answers `302` + `Location` → frame `status 302`, `Location` kept, engine called once |
| `replay rejects a path without leading slash` | `path = "mcp"` → `400`, engine never called |
| `replay rejects a protocol-relative path` | `path = "//evil.example/x"` → `400`, engine never called |
| `replay maps io failure to 502 json frame` | engine throws `java.io.IOException` → `502`, `Content-Type: application/json`, body `{"error":"local MCP server unreachable"}` (no host/port) |
| `replay maps request timeout to 504` | engine throws `HttpRequestTimeoutException` → `504` |
| `replay maps invalid base64 body to 400` | `body = "%%%"` → `400` |
| `replay maps oversized response to 502` | 65-byte response with `maxResponseBytes = 64` → `502`, message contains `too large` |
| `replay accepts a response at the limit` | 64-byte response → `200`, body intact |
| `sanitizedPath strips the query and redacts capability links` | `/mcp?x=1` → `/mcp`; `/s/abc?x=1` → `/s/<redacted>` |

**File**: `.../test/services/tunnel/RelayTestServer.kt` — shared infrastructure, included in full:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal const val RELAY_TEST_TOKEN = "device-token-0123456789"

/**
 * In-process stand-in for the relay AND the local MCP server: a Netty server on an ephemeral port that
 * answers the bearer-gated `/device` WebSocket with [deviceHandler] and serves [localRoutes] over plain HTTP.
 * One server plays both roles so a test can pass the same port as `localPort` the provider replays to.
 * A `/device` handshake without the expected bearer is answered `401` before the upgrade; an accepted
 * handshake is delayed by [handshakeDelayMs] (0 = none) so cancellation mid-handshake can be exercised.
 * Real sockets (like `EventDispatcherImplTest`) so the production OkHttp WebSocket engine is what gets tested.
 */
internal class RelayTestServer(
    private val deviceHandler: suspend DefaultWebSocketServerSession.() -> Unit,
    private val localRoutes: Route.() -> Unit = {},
    private val handshakeDelayMs: Long = 0,
) {
    val handshakeAttempts = AtomicInteger(0)
    val openSessions = AtomicInteger(0)
    val authorizationHeaders = CopyOnWriteArrayList<String?>()

    var port: Int = 0
        private set

    private val server =
        embeddedServer(Netty, port = 0) {
            install(WebSockets)
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() == RelayTarget.DEVICE_PATH) {
                    handshakeAttempts.incrementAndGet()
                    val authorization = call.request.header(HttpHeaders.Authorization)
                    authorizationHeaders += authorization
                    if (authorization != "Bearer $RELAY_TEST_TOKEN") {
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                    } else if (handshakeDelayMs > 0) {
                        delay(handshakeDelayMs)
                    }
                }
            }
            routing {
                webSocket(RelayTarget.DEVICE_PATH) {
                    openSessions.incrementAndGet()
                    try {
                        deviceHandler()
                    } finally {
                        openSessions.decrementAndGet()
                    }
                }
                localRoutes()
            }
        }

    suspend fun start() {
        server.start(wait = false)
        port = server.engine.resolvedConnectors().first().port
    }

    fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    fun config(token: String = RELAY_TEST_TOKEN) =
        ServerConfig(
            relayUrl = "ws://127.0.0.1:$port",
            relayDeviceToken = token,
        )
}
```

**File**: `.../test/services/tunnel/RelayTunnelProviderTest.kt`

**Setup**: `runBlocking` (real sockets, real time — see the class KDoc above for the justification); `@Timeout(30,
SECONDS)` on the class; each test creates a `RelayTestServer`, calls `start()`, creates `RelayTunnelProvider()` and
stops both in `finally`. Waiting for a status uses `withTimeout(5_000) { provider.status.first { … } }`; waiting for a
counter uses a `withTimeout(5_000)` + `delay(50)` poll. Request/response exchange between the server-side device
handler and the test uses `CompletableDeferred<String>`. A device handler that must keep the socket open awaits
`incoming.receiveCatching()`.

| Test | Verifies |
|------|----------|
| `initial status is Disconnected` | fresh provider |
| `start with empty relay url sets error` | `ServerConfig()` → `Error("Relay URL is required")`, `handshakeAttempts == 0` |
| `start with empty token sets error` | token blank → `Error("Relay device token is required")` |
| `start with short token sets error` | 15-char token → `Error` containing `16` |
| `start with https url sets error` | `https://…` → `Error` containing `ws://` |
| `start connects with bearer and publishes http public url` | `Connected([TunnelEndpoint("http://127.0.0.1:<port>", true)], RELAY)`; server saw `Bearer device-token-0123456789` |
| `request frame is replayed to local server and answered` | handler sends `request` for `POST /mcp?x=1` (header `X-Test: 1`, JSON body); local route echoes `body + " via " + X-Test` and sets `Mcp-Session-Id: abc`; decoded response frame has `status 200`, header `Mcp-Session-Id == [abc]`, no `content-length` key (case-insensitive), decoded body matches |
| `redirect from local server is passed through` | local route answers `302` + `Location: /elsewhere` → frame `status 302` with `Location` |
| `ping frame is answered with pong` | handler sends `{"type":"ping"}`, receives `{"type":"pong"}` |
| `malformed and unknown frames are ignored and session stays alive` | handler sends `not json`, `{"type":"hello"}`, then ping → still receives pong |
| `request to unreachable local port yields 502 frame` | `localPort` = port of an already-closed `ServerSocket(0)` → `status 502` |
| `ninth concurrent request gets 503 while eight are in flight` | local route `/slow` awaits a `CompletableDeferred` before answering; handler sends 9 request frames; first frame back is `503` for the 9th id; completing the deferred yields the other eight `200`s |
| `handshake rejected with 401 reports error and retries` | wrong token → `Error` containing `401` and `reconnecting`; `handshakeAttempts >= 2` within 5 s |
| `relay closing the socket triggers reconnect` | handler closes immediately (`close(CloseReason(NORMAL, "bye"))`) → `Error` containing `Relay connection closed` and `reconnecting in 1s`; `handshakeAttempts >= 2` |
| `relay replacing the device waits the maximum backoff` | handler `close(CloseReason(4000, "replaced"))` → `Error` containing `replaced` and `reconnecting in 30s`; `handshakeAttempts` stays 1 for 2 s |
| `silent relay is redialled after idle timeout` | `provider.idleTimeoutMs = 300`; handler only awaits `incoming.receiveCatching()` → `Error` containing `silent`, then `handshakeAttempts >= 2` |
| `connection failure through injected client reports error` | `provider.clientProvider = { HttpClient(MockEngine { throw IOException("boom") }) { install(WebSockets) } }` → `Error` containing `boom` and `reconnecting`; no server needed |
| `start twice throws` | second `start` → `IllegalStateException("Tunnel is already running")` |
| `stop closes session and sets Disconnected` | after `Connected`, `stop()` → `Disconnected`; `openSessions` returns to 0 within 2 s |
| `stop during handshake leaves no open device socket` | `handshakeDelayMs = 1_000`; `launch { provider.start(...) }`, after 200 ms `provider.stop()` → `Disconnected`; after 2 s `openSessions == 0` and `handshakeAttempts == 1` |
| `cancelling a pending start stops the provider` | `handshakeDelayMs = 1_000`; `val job = launch { provider.start(...) }`; after 200 ms `job.cancelAndJoin()` → `Disconnected`; after 2 s `handshakeAttempts == 1` (no redial) |
| `stop when not running is no-op` | `Disconnected` stays, no exception; also idempotent after a real stop |
| `backoff ladder` | `backoffMs(1..7)` == `1000, 2000, 4000, 8000, 16000, 30000, 30000` |
| `failure count resets only after a stable session` | `nextFailureCount(4, 0) == 5`; `nextFailureCount(4, 9_999) == 5`; `nextFailureCount(4, 10_000) == 1` |

**Definition of Done:**
- [ ] Every acceptance criterion of US2 maps to at least one row above; timed waits allow ≥ 2× the expected delay.

---
## User Story 3 — Wire the provider into `TunnelManager`

**Why:** `TunnelManager` is the only factory switch; without the branch `RELAY` is unreachable and the `when` is
non-exhaustive.

**Acceptance criteria:**
- [ ] `tunnelProvider == RELAY` starts a fresh `RelayTunnelProvider` from its `Provider` factory.

### Task 3.1 — Factory and branch

**Action 1 — modify** `.../services/tunnel/TunnelManager.kt`

```kotlin
        private val ngrokTunnelProviderFactory: Provider<NgrokTunnelProvider>,
        private val relayTunnelProviderFactory: Provider<RelayTunnelProvider>,
        private val serverLogRepository: ServerLogRepository,
```

```kotlin
                        TunnelProviderType.NGROK -> ngrokTunnelProviderFactory.get()
                        TunnelProviderType.RELAY -> relayTunnelProviderFactory.get()
```

**Action 2 — modify** `.../test/services/tunnel/TunnelManagerTest.kt`: add the relay mock + factory and pass it.

```kotlin
    private val mockRelayProvider = mockk<RelayTunnelProvider>(relaxed = true)
```

```kotlin
    private val relayFactory =
        mockk<Provider<RelayTunnelProvider>> {
            every { get() } returns mockRelayProvider
        }
```

```kotlin
            ngrokTunnelProviderFactory = ngrokFactory,
            relayTunnelProviderFactory = relayFactory,
```

| Test (in `Start`) | Verifies |
|------|----------|
| `start with tunnel enabled and relay provider starts relay tunnel` | `coVerify { mockRelayProvider.start(8080, config) }` with `tunnelProvider = RELAY` (mirror of the ngrok case) |

**Definition of Done:**
- [ ] The `when` over `TunnelProviderType` in `TunnelManager` is exhaustive without an `else` branch.

---

## User Story 4 — Settings UI, ViewModel and strings

**Why:** The user enters the relay URL and device token on the Tunnel screen; both are validated as typed (error
under the field, invalid values are not persisted), a cleartext `ws://` URL to a public host shows a warning, and
the token is masked with a visibility toggle.

**Acceptance criteria:**
- [ ] Tunnel screen lists a third radio "WebSocket relay — Your own relay server"; selecting it reveals the two fields.
- [ ] Invalid URL / token → `isError` + supporting text from the validators; valid or empty → saved.
- [ ] `ws://` to a non-private host shows the cleartext warning as supporting text (not an error).
- [ ] ADB/external changes are reflected (inputs sync from `serverConfig`).
- [ ] `settings_tunnel_subtitle` reads "Cloudflare, ngrok, WebSocket relay".

### Task 4.1 — ViewModel inputs and updates

**Action 1 — modify** `.../ui/viewmodels/MainViewModel.kt`: state after `_cloudflareExtraArgsInput`.

```kotlin
        private val _relayUrlInput = MutableStateFlow("")
        val relayUrlInput: StateFlow<String> = _relayUrlInput.asStateFlow()

        private val _relayUrlError = MutableStateFlow<String?>(null)
        val relayUrlError: StateFlow<String?> = _relayUrlError.asStateFlow()

        private val _relayUrlInsecure = MutableStateFlow(false)
        val relayUrlInsecure: StateFlow<Boolean> = _relayUrlInsecure.asStateFlow()

        private val _relayDeviceTokenInput = MutableStateFlow("")
        val relayDeviceTokenInput: StateFlow<String> = _relayDeviceTokenInput.asStateFlow()

        private val _relayDeviceTokenError = MutableStateFlow<String?>(null)
        val relayDeviceTokenError: StateFlow<String?> = _relayDeviceTokenError.asStateFlow()
```

**Action 2 — modify** `.../ui/viewmodels/MainViewModel.kt`: sync in the `serverConfig.collect` block after
`_cloudflareExtraArgsInput.value = config.cloudflareTunnelExtraArgs`.

```kotlin
                    _relayUrlInput.value = config.relayUrl
                    _relayUrlError.value = null
                    _relayUrlInsecure.value = settingsRepository.isRelayUrlInsecure(config.relayUrl)
                    _relayDeviceTokenInput.value = config.relayDeviceToken
                    _relayDeviceTokenError.value = null
```

**Action 3 — modify** `.../ui/viewmodels/MainViewModel.kt`: after `updateCloudflareTunnelExtraArgs`.

```kotlin
        fun updateRelayUrl(url: String) {
            _relayUrlInput.value = url

            val result = settingsRepository.validateRelayUrl(url)
            if (result.isFailure) {
                _relayUrlError.value = result.exceptionOrNull()?.message
                return
            }

            _relayUrlError.value = null
            _relayUrlInsecure.value = settingsRepository.isRelayUrlInsecure(url)
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRelayUrl(url)
            }
        }

        fun updateRelayDeviceToken(token: String) {
            _relayDeviceTokenInput.value = token

            val result = settingsRepository.validateRelayDeviceToken(token)
            if (result.isFailure) {
                _relayDeviceTokenError.value = result.exceptionOrNull()?.message
                return
            }

            _relayDeviceTokenError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRelayDeviceToken(token)
            }
        }
```

**Definition of Done:**
- [ ] Both update functions mirror `updateCertificateHostname` (validate → error or save) exactly.

### Task 4.2 — Strings

**Action 1 — modify** `app/src/main/res/values/strings.xml`: subtitle.

```xml
    <string name="settings_tunnel_subtitle">Cloudflare, ngrok, WebSocket relay</string>
```

**Action 2 — modify** `app/src/main/res/values/strings.xml`: after `remote_access_ngrok_domain_hint`.

```xml
    <string name="remote_access_provider_relay">WebSocket relay</string>
    <string name="remote_access_provider_relay_desc">Your own relay server</string>
    <string name="remote_access_relay_url_label">Relay URL</string>
    <string name="remote_access_relay_url_hint">wss://relay.example.com</string>
    <string name="remote_access_relay_insecure_warning">Unencrypted ws:// URL: the device token and every client credential travel in cleartext. Use wss:// outside localhost or a private network.</string>
    <string name="remote_access_relay_token_label">Relay device token</string>
    <string name="remote_access_relay_help">The device opens an outbound WebSocket to the relay. MCP clients connect to the relay\'s HTTPS URL and every request is replayed to the local server.</string>
```

**Definition of Done:**
- [ ] Every new string is referenced from `TunnelSettingsScreen.kt`.

### Task 4.3 — Tunnel screen

**Action 1 — modify** `.../ui/screens/settings/TunnelSettingsScreen.kt`: collect the new state after
`cloudflareExtraArgsInput`.

```kotlin
    val relayUrlInput by viewModel.relayUrlInput.collectAsStateWithLifecycle()
    val relayUrlError by viewModel.relayUrlError.collectAsStateWithLifecycle()
    val relayUrlInsecure by viewModel.relayUrlInsecure.collectAsStateWithLifecycle()
    val relayDeviceTokenInput by viewModel.relayDeviceTokenInput.collectAsStateWithLifecycle()
    val relayDeviceTokenError by viewModel.relayDeviceTokenError.collectAsStateWithLifecycle()
```

**Action 2 — modify** both `when (provider)` blocks in the radio list: add the `RELAY` branch after `NGROK`.

```kotlin
                                            TunnelProviderType.RELAY -> {
                                                stringResource(R.string.remote_access_provider_relay)
                                            }
```

```kotlin
                                            TunnelProviderType.RELAY -> {
                                                stringResource(R.string.remote_access_provider_relay_desc)
                                            }
```

**Action 3 — modify**: insert after the ngrok `AnimatedVisibility` block (before `Spacer(16.dp)` + `TunnelStatusIndicator`).

```kotlin

                    // WebSocket relay fields
                    AnimatedVisibility(visible = serverConfig.tunnelProvider == TunnelProviderType.RELAY) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            RelayUrlField(
                                url = relayUrlInput,
                                urlError = relayUrlError,
                                insecure = relayUrlInsecure,
                                enabled = sectionEnabled,
                                onUrlChange = viewModel::updateRelayUrl,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            RelayTokenField(
                                token = relayDeviceTokenInput,
                                tokenError = relayDeviceTokenError,
                                enabled = sectionEnabled,
                                onTokenChange = viewModel::updateRelayDeviceToken,
                            )
                        }
                    }
```

**Action 4 — modify**: add the two composables after `NgrokConfigFields`.

```kotlin
@Composable
private fun RelayUrlField(
    url: String,
    urlError: String?,
    insecure: Boolean,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
) {
    val insecureWarning = stringResource(R.string.remote_access_relay_insecure_warning)
    val supporting = urlError ?: insecureWarning.takeIf { insecure }

    Column {
        Text(
            text = stringResource(R.string.remote_access_relay_url_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            enabled = enabled,
            isError = urlError != null,
            supportingText = supporting?.let { { Text(it) } },
            placeholder = {
                Text(text = stringResource(R.string.remote_access_relay_url_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RelayTokenField(
    token: String,
    tokenError: String?,
    enabled: Boolean,
    onTokenChange: (String) -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.remote_access_relay_token_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            enabled = enabled,
            isError = tokenError != null,
            supportingText = tokenError?.let { { Text(it) } },
            visualTransformation =
                if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector =
                            if (showToken) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                        contentDescription =
                            if (showToken) {
                                stringResource(R.string.config_token_hide)
                            } else {
                                stringResource(R.string.config_token_show)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_relay_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

**Definition of Done:**
- [ ] Both `when (provider)` expressions stay exhaustive; no new imports needed (all symbols already imported).

### Task 4.4 — ViewModel tests

**File**: `.../test/ui/viewmodels/MainViewModelTest.kt` (after `serverConfig collection sets ngrok input fields`)

| Test | Verifies |
|------|----------|
| `updateRelayUrl with valid url clears error and saves` | input `wss://relay.example.com`, `relayUrlError == null`, `coVerify { updateRelayUrl("wss://relay.example.com") }` |
| `updateRelayUrl with empty url saves empty` | `""` → `relayUrlError == null`, `coVerify { updateRelayUrl("") }` |
| `updateRelayUrl with invalid url sets error and does not save` | **Setup**: `every { settingsRepository.validateRelayUrl("https://x") } returns Result.failure(IllegalArgumentException("Relay URL must start with ws:// or wss://"))`; error message equal; `coVerify(exactly = 0) { updateRelayUrl(any()) }` |
| `updateRelayUrl flags an insecure url` | **Setup**: `every { settingsRepository.isRelayUrlInsecure("ws://relay.example.com") } returns true`; `relayUrlInsecure.value == true`, still saved |
| `updateRelayDeviceToken with valid token clears error and saves` | `"0123456789abcdef"` → `relayDeviceTokenError == null`, `coVerify { updateRelayDeviceToken("0123456789abcdef") }` |
| `updateRelayDeviceToken with short token sets error and does not save` | **Setup**: `every { settingsRepository.validateRelayDeviceToken("short") } returns Result.failure(IllegalArgumentException("Relay device token must be at least 16 characters"))`; `coVerify(exactly = 0)` |
| `serverConfig collection sets relay input fields` | config with `relayUrl`/`relayDeviceToken` before ViewModel creation → both inputs populated, both errors null (mirror of the ngrok test) |

**Definition of Done:**
- [ ] Tests green; no change to existing tests.

---

## User Story 5 — ADB configuration extras

**Why:** Headless setup parity: every tunnel setting is configurable via `AdbConfigReceiver.ACTION_CONFIGURE`.

**Acceptance criteria:**
- [ ] `--es relay_url <url>` validates then saves (empty clears); invalid is logged and ignored.
- [ ] `--es relay_device_token <token>` validates then saves; empty or too short is logged and ignored.
      `--es tunnel_provider RELAY` already works via the enum.

### Task 5.1 — Handler and receiver KDoc

**Action 1 — modify** `.../services/mcp/AdbConfigHandler.kt`: dispatch after `applyNgrokDomain(intent)`.

```kotlin
        applyRelayUrl(intent)
        applyRelayDeviceToken(intent)
```

**Action 2 — modify**: functions after `applyNgrokDomain`.

```kotlin
    private suspend fun applyRelayUrl(intent: Intent) {
        if (!intent.hasExtra(EXTRA_RELAY_URL)) return
        val value = intent.getStringExtra(EXTRA_RELAY_URL) ?: ""
        settingsRepository.validateRelayUrl(value).fold(
            onSuccess = {
                settingsRepository.updateRelayUrl(it)
                Log.i(TAG, if (it.isEmpty()) "Relay URL cleared" else "Relay URL updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid relay_url: ${it.message}") },
        )
    }

    private suspend fun applyRelayDeviceToken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_RELAY_DEVICE_TOKEN) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty relay_device_token")
            return
        }
        settingsRepository.validateRelayDeviceToken(value).fold(
            onSuccess = {
                settingsRepository.updateRelayDeviceToken(it)
                Log.i(TAG, "Relay device token updated (length=${it.length})")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid relay_device_token: ${it.message}") },
        )
    }
```

**Action 3 — modify**: constants after `EXTRA_NGROK_DOMAIN`.

```kotlin
        internal const val EXTRA_RELAY_URL = "relay_url"
        internal const val EXTRA_RELAY_DEVICE_TOKEN = "relay_device_token"
```

**Action 4 — modify** `.../services/mcp/AdbConfigReceiver.kt` (KDoc usage example): insert after the `ngrok_domain` line.

```kotlin
 *   --es relay_url "wss://relay.example.com" \
 *   --es relay_device_token "your-relay-device-token" \
```

**Definition of Done:**
- [ ] `handleConfigure` applies both extras after the ngrok ones; no new suppression anywhere.

### Task 5.2 — Handler tests

**File**: `.../test/services/mcp/AdbConfigHandlerTest.kt`

**Setup** (in `setUp`, after the `validatePublicUrlOverride` stub — the repository mock is `relaxUnitFun`, so every
non-Unit function MUST be stubbed):

```kotlin
        every { settingsRepository.validateRelayUrl(any()) } answers {
            val url = firstArg<String>()
            when {
                url.isBlank() -> Result.success("")
                url.startsWith("ws://") || url.startsWith("wss://") -> Result.success(url)
                else -> Result.failure(IllegalArgumentException("Relay URL must start with ws:// or wss://"))
            }
        }
        every { settingsRepository.validateRelayDeviceToken(any()) } answers {
            val token = firstArg<String>().trim()
            when {
                token.isEmpty() -> Result.success("")
                token.length < ServerConfig.MIN_RELAY_DEVICE_TOKEN_LENGTH ->
                    Result.failure(IllegalArgumentException("Relay device token must be at least 16 characters"))
                else -> Result.success(token)
            }
        }
```

| Test | Verifies |
|------|----------|
| `tunnel_provider RELAY is applied` | `coVerify { updateTunnelProvider(TunnelProviderType.RELAY) }` |
| `relay_url is applied` | `wss://relay.example.com` → `coVerify { updateRelayUrl("wss://relay.example.com") }` |
| `empty relay_url clears setting` | `""` → `coVerify { updateRelayUrl("") }` |
| `invalid relay_url is ignored` | `https://relay.example.com` → `coVerify(exactly = 0) { updateRelayUrl(any()) }` |
| `absent relay_url does not update setting` | intent with only `relay_device_token` → `exactly = 0` for `updateRelayUrl` |
| `relay_device_token is applied` | `0123456789abcdef` → `coVerify { updateRelayDeviceToken("0123456789abcdef") }` |
| `empty relay_device_token is ignored` | `exactly = 0` |
| `short relay_device_token is ignored` | `short` → `exactly = 0` |

**Definition of Done:**
- [ ] Tests green; `handleConfigure` keeps its existing `@Suppress("LongMethod")` (no new suppression).

---
## User Story 6 — Relay service (`relay/`)

**Why:** The server half of the transport. One process, one dependency (`ws`), runnable as a container behind the
operator's TLS proxy. Tests exercise real sockets on ephemeral ports with the built-in runner.

**Acceptance criteria:**
- [ ] `RELAY_DEVICE_TOKEN` (≥ 16 chars) is mandatory; `/device` upgrades without the exact bearer get `401`, other
      upgrade paths `404`; comparison is constant-time. `PORT` must be 1–65535.
- [ ] HTTP requests are forwarded as frames (origin-form path, hop-by-hop headers dropped, `X-Forwarded-*` per
      `TRUST_PROXY`) and answered from `response` frames (`HEAD`/`204`/`304` without body); `503` (no device or
      `MAX_PENDING` reached), `504`, `413` (also from `Content-Length` alone, connection closed), `502` (device
      dropped or response above `MAX_RESPONSE_BYTES`); `/relay/health` always `200`.
- [ ] One device at a time (replacement closes the old socket with `4000`); heartbeat ping every
      `HEARTBEAT_INTERVAL_MS`, terminate after `HEARTBEAT_TIMEOUT_MS` of silence.
- [ ] Image runs as `node`, exposes `8080`, base image pinned by digest.

### Task 6.1 — Package files

**Action 1 — create** `relay/package.json`

```json
{
  "name": "lucky-tool-android-relay",
  "version": "0.1.0",
  "private": true,
  "description": "WebSocket relay for Lucky-Tool-Android: the device dials out, MCP clients hit this server's HTTPS URL",
  "type": "module",
  "license": "MIT",
  "engines": {
    "node": ">=22"
  },
  "scripts": {
    "start": "node src/server.js",
    "lint": "node --check src/config.js && node --check src/frames.js && node --check src/relay.js && node --check src/server.js",
    "test": "node --test \"test/*.test.js\""
  },
  "dependencies": {
    "ws": "8.22.0"
  }
}
```

**Action 2 — create** `relay/package-lock.json`: generated artifact — run `npm install --package-lock-only` inside
`relay/` with npm 11.11.0 (Node 24.14.1); the result has `"lockfileVersion": 3` and a single dependency entry for
`ws@8.22.0`. Then `npm ci` locally to run the tests (`node_modules/` is ignored).

**Action 3 — create** `relay/.gitignore`

```gitignore
node_modules/
```

**Action 4 — create** `relay/.dockerignore`

```gitignore
node_modules
test
README.md
.gitignore
.dockerignore
```

**Definition of Done:**
- [ ] `npm ci` installs exactly one package (`ws`) and `npm run lint` passes.

### Task 6.2 — Source

**Action 1 — create** `relay/src/config.js`

```js
const DEFAULTS = Object.freeze({
  PORT: 8080,
  REQUEST_TIMEOUT_MS: 120_000,
  MAX_BODY_BYTES: 16 * 1024 * 1024,
  MAX_RESPONSE_BYTES: 8 * 1024 * 1024,
  MAX_PENDING: 32,
  HEARTBEAT_INTERVAL_MS: 25_000,
  HEARTBEAT_TIMEOUT_MS: 60_000,
  TRUST_PROXY: true,
});

const MIN_TOKEN_LENGTH = 16;
const MAX_PORT = 65535;
const TRUE_WORDS = new Set(['true', '1', 'yes']);
const FALSE_WORDS = new Set(['false', '0', 'no']);

function readPositiveInt(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isSafeInteger(value) || value <= 0 || String(value) !== raw.trim()) {
    throw new Error(`${name} must be a positive integer, got "${raw}"`);
  }
  return value;
}

function readPort(env) {
  const port = readPositiveInt(env, 'PORT', DEFAULTS.PORT);
  if (port > MAX_PORT) throw new Error(`PORT must be between 1 and ${MAX_PORT}, got "${port}"`);
  return port;
}

function readBoolean(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const word = raw.trim().toLowerCase();
  if (TRUE_WORDS.has(word)) return true;
  if (FALSE_WORDS.has(word)) return false;
  throw new Error(`${name} must be true or false, got "${raw}"`);
}

/**
 * Reads the relay configuration from environment variables.
 * RELAY_DEVICE_TOKEN is mandatory (>= 16 characters); everything else has a default.
 */
export function loadConfig(env = process.env) {
  const deviceToken = env.RELAY_DEVICE_TOKEN ?? '';
  if (deviceToken.length < MIN_TOKEN_LENGTH) {
    throw new Error(`RELAY_DEVICE_TOKEN must be set and at least ${MIN_TOKEN_LENGTH} characters long`);
  }
  return Object.freeze({
    port: readPort(env),
    deviceToken,
    requestTimeoutMs: readPositiveInt(env, 'REQUEST_TIMEOUT_MS', DEFAULTS.REQUEST_TIMEOUT_MS),
    maxBodyBytes: readPositiveInt(env, 'MAX_BODY_BYTES', DEFAULTS.MAX_BODY_BYTES),
    maxResponseBytes: readPositiveInt(env, 'MAX_RESPONSE_BYTES', DEFAULTS.MAX_RESPONSE_BYTES),
    maxPending: readPositiveInt(env, 'MAX_PENDING', DEFAULTS.MAX_PENDING),
    heartbeatIntervalMs: readPositiveInt(env, 'HEARTBEAT_INTERVAL_MS', DEFAULTS.HEARTBEAT_INTERVAL_MS),
    heartbeatTimeoutMs: readPositiveInt(env, 'HEARTBEAT_TIMEOUT_MS', DEFAULTS.HEARTBEAT_TIMEOUT_MS),
    trustProxy: readBoolean(env, 'TRUST_PROXY', DEFAULTS.TRUST_PROXY),
  });
}
```

**Action 2 — create** `relay/src/frames.js`

```js
export const FRAME_TYPES = Object.freeze({
  REQUEST: 'request',
  RESPONSE: 'response',
  PING: 'ping',
  PONG: 'pong',
});

const MIN_STATUS = 200;
const MAX_STATUS = 599;

/** Builds the text frame the device receives for one HTTP request (body is a Buffer). */
export function encodeRequestFrame({ id, method, path, headers, body }) {
  return JSON.stringify({
    type: FRAME_TYPES.REQUEST,
    id,
    method,
    path,
    headers,
    body: body.toString('base64'),
  });
}

export function encodePing() {
  return JSON.stringify({ type: FRAME_TYPES.PING });
}

/**
 * Parses a frame sent by the device. Returns `null` when the text is not a well-formed frame,
 * `{ type }` for pong/unknown types, and a normalized object for `response` frames
 * (`headers` is `{ name: [values] }`, `body` a Buffer, `status` 200–599).
 */
export function decodeDeviceFrame(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== 'object' || typeof parsed.type !== 'string') return null;
  if (parsed.type !== FRAME_TYPES.RESPONSE) return { type: parsed.type };
  return normalizeResponse(parsed);
}

function normalizeResponse(parsed) {
  if (typeof parsed.id !== 'string' || !Number.isInteger(parsed.status)) return null;
  if (parsed.status < MIN_STATUS || parsed.status > MAX_STATUS) return null;
  const rawHeaders = parsed.headers ?? {};
  if (typeof rawHeaders !== 'object' || Array.isArray(rawHeaders)) return null;
  const headers = {};
  for (const [name, values] of Object.entries(rawHeaders)) {
    if (!Array.isArray(values) || !values.every((value) => typeof value === 'string')) return null;
    headers[name] = values;
  }
  const body = typeof parsed.body === 'string' ? Buffer.from(parsed.body, 'base64') : Buffer.alloc(0);
  return { type: FRAME_TYPES.RESPONSE, id: parsed.id, status: parsed.status, headers, body };
}
```

**Action 3 — create** `relay/src/relay.js`

```js
import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { FRAME_TYPES, decodeDeviceFrame, encodePing, encodeRequestFrame } from './frames.js';

export const DEVICE_PATH = '/device';
export const HEALTH_PATH = '/relay/health';
export const CLOSE_REPLACED = 4000;

const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'expect',
  'host',
  'content-length',
]);
const NO_BODY_STATUSES = new Set([204, 304]);
const FRAME_OVERHEAD_BYTES = 64 * 1024;
const CLOSE_GOING_AWAY = 1001;

export class RelayError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'RelayError';
    this.status = status;
  }
}

/**
 * Builds the relay: an HTTP server that proxies every request (except HEALTH_PATH) to the single connected
 * device over its WebSocket, plus the bearer-gated DEVICE_PATH upgrade endpoint.
 * Returns `{ server, close, isDeviceConnected }`; the caller decides where `server` listens.
 */
export function createRelay(config, logger = console) {
  const pending = new Map();
  let device = null;

  const largestBody = Math.max(config.maxBodyBytes, config.maxResponseBytes);
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: Math.ceil((largestBody * 4) / 3) + FRAME_OVERHEAD_BYTES,
  });
  const server = createServer((req, res) => {
    handleHttp(req, res).catch((err) => {
      logger.error(`relay: unhandled error: ${err?.stack ?? err}`);
      if (!res.headersSent) sendJson(res, 500, { error: 'internal relay error' });
    });
  });
  server.on('upgrade', handleUpgrade);

  function isAuthorized(req) {
    const given = Buffer.from(req.headers.authorization ?? '', 'utf8');
    const expected = Buffer.from(`Bearer ${config.deviceToken}`, 'utf8');
    return given.length === expected.length && timingSafeEqual(given, expected);
  }

  function handleUpgrade(req, socket, head) {
    const { pathname } = new URL(req.url, 'http://relay.invalid');
    if (pathname !== DEVICE_PATH) {
      rejectUpgrade(socket, 404, 'Not Found');
      return;
    }
    if (!isAuthorized(req)) {
      logger.warn(`relay: rejected device handshake from ${req.socket.remoteAddress}`);
      rejectUpgrade(socket, 401, 'Unauthorized');
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => attachDevice(ws, req));
  }

  function rejectUpgrade(socket, status, text) {
    socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    socket.destroy();
  }

  function attachDevice(ws, req) {
    if (device !== null) {
      logger.info('relay: new device connection replaces the previous one');
      const previous = device;
      detachDevice(previous, 'device connection replaced');
      previous.socket.close(CLOSE_REPLACED, 'replaced by a new device connection');
    }
    const entry = { socket: ws, lastSeen: Date.now(), heartbeat: null };
    entry.heartbeat = setInterval(() => heartbeat(entry), config.heartbeatIntervalMs);
    device = entry;
    logger.info(`relay: device connected from ${req.socket.remoteAddress}`);
    ws.on('message', (data) => {
      entry.lastSeen = Date.now();
      handleDeviceMessage(data);
    });
    ws.on('pong', () => {
      entry.lastSeen = Date.now();
    });
    ws.on('close', (code) => {
      logger.info(`relay: device disconnected (code ${code})`);
      detachDevice(entry, 'device disconnected');
    });
    ws.on('error', (err) => {
      logger.warn(`relay: device socket error: ${err.message}`);
    });
  }

  function detachDevice(entry, reason) {
    clearInterval(entry.heartbeat);
    if (device === entry) {
      device = null;
      failAllPending(reason);
    }
  }

  function heartbeat(entry) {
    if (Date.now() - entry.lastSeen > config.heartbeatTimeoutMs) {
      logger.warn('relay: device unresponsive, terminating its socket');
      entry.socket.terminate();
      return;
    }
    if (entry.socket.readyState === entry.socket.OPEN) {
      entry.socket.send(encodePing());
    }
  }

  function handleDeviceMessage(data) {
    const frame = decodeDeviceFrame(data.toString('utf8'));
    if (frame === null) {
      logger.warn('relay: ignoring malformed device frame');
      return;
    }
    if (frame.type === FRAME_TYPES.PONG) return;
    if (frame.type !== FRAME_TYPES.RESPONSE) {
      logger.warn(`relay: ignoring device frame of type "${frame.type}"`);
      return;
    }
    const waiter = pending.get(frame.id);
    if (waiter === undefined) {
      logger.warn(`relay: response for unknown or expired request ${frame.id}`);
      return;
    }
    pending.delete(frame.id);
    clearTimeout(waiter.timer);
    waiter.resolve(frame);
  }

  function failAllPending(reason) {
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(new RelayError(502, reason));
    }
    pending.clear();
  }

  async function handleHttp(req, res) {
    const url = new URL(req.url, 'http://relay.invalid');
    if (url.pathname === HEALTH_PATH) {
      sendJson(res, 200, { status: 'ok', deviceConnected: device !== null });
      return;
    }
    try {
      if (device === null) throw new RelayError(503, 'device not connected');
      if (pending.size >= config.maxPending) throw new RelayError(503, 'too many pending requests');
      const body = await readBody(req, config.maxBodyBytes);
      const response = await forward(req, url, body);
      if (response.body.length > config.maxResponseBytes) throw new RelayError(502, 'device response too large');
      writeResponse(req, res, response);
    } catch (err) {
      if (err instanceof RelayError) {
        sendJson(res, err.status, { error: err.message }, err.status === 413);
        return;
      }
      throw err;
    }
  }

  function forward(req, url, body) {
    if (device === null) {
      return Promise.reject(new RelayError(503, 'device not connected'));
    }
    const id = randomUUID();
    const frame = encodeRequestFrame({
      id,
      method: req.method,
      path: `${url.pathname}${url.search}`,
      headers: forwardedHeaders(req),
      body,
    });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new RelayError(504, 'device did not answer in time'));
      }, config.requestTimeoutMs);
      pending.set(id, { resolve, reject, timer });
      device.socket.send(frame, (err) => {
        if (err && pending.delete(id)) {
          clearTimeout(timer);
          reject(new RelayError(502, `failed to send to device: ${err.message}`));
        }
      });
    });
  }

  function forwardedHeaders(req) {
    const headers = {};
    for (const [name, values] of Object.entries(req.headersDistinct)) {
      if (!HOP_BY_HOP_HEADERS.has(name)) headers[name] = values;
    }
    const observedHost = [req.headers.host ?? ''];
    const observedProto = [req.socket.encrypted ? 'https' : 'http'];
    if (config.trustProxy) {
      headers['x-forwarded-host'] ??= observedHost;
      headers['x-forwarded-proto'] ??= observedProto;
    } else {
      headers['x-forwarded-host'] = observedHost;
      headers['x-forwarded-proto'] = observedProto;
      delete headers['x-forwarded-for'];
    }
    headers['x-forwarded-for'] = [...(headers['x-forwarded-for'] ?? []), req.socket.remoteAddress ?? ''];
    return headers;
  }

  function writeResponse(req, res, frame) {
    for (const [name, values] of Object.entries(frame.headers)) {
      if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) res.setHeader(name, values);
    }
    res.statusCode = frame.status;
    if (req.method === 'HEAD' || NO_BODY_STATUSES.has(frame.status)) {
      res.end();
      return;
    }
    res.setHeader('content-length', frame.body.length);
    res.end(frame.body);
  }

  function close() {
    return new Promise((resolve) => {
      if (device !== null) {
        const current = device;
        detachDevice(current, 'relay shutting down');
        current.socket.close(CLOSE_GOING_AWAY, 'relay shutting down');
      }
      wss.close();
      server.close(() => resolve());
      server.closeAllConnections();
    });
  }

  return {
    server,
    close,
    isDeviceConnected: () => device !== null,
  };
}

function readBody(req, maxBytes) {
  const declared = Number.parseInt(req.headers['content-length'] ?? '', 10);
  if (Number.isFinite(declared) && declared > maxBytes) {
    req.resume();
    return Promise.reject(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let rejected = false;
    req.on('data', (chunk) => {
      if (rejected) return;
      total += chunk.length;
      if (total > maxBytes) {
        rejected = true;
        reject(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!rejected) resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => {
      if (!rejected) reject(new RelayError(400, `failed to read request body: ${err.message}`));
    });
  });
}

function sendJson(res, status, payload, closeConnection = false) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const headers = { 'content-type': 'application/json', 'content-length': body.length };
  if (closeConnection) headers.connection = 'close';
  res.writeHead(status, headers);
  res.end(body);
}
```

**Action 4 — create** `relay/src/server.js`

```js
import { loadConfig } from './config.js';
import { createRelay } from './relay.js';

const SHUTDOWN_GRACE_MS = 5_000;

const config = loadConfig();
const relay = createRelay(config, console);

relay.server.listen(config.port, () => {
  console.info(`relay: listening on :${config.port}`);
});

function shutdown(signal) {
  console.info(`relay: ${signal} received, shutting down`);
  relay.close().then(() => process.exit(0));
  setTimeout(() => process.exit(1), SHUTDOWN_GRACE_MS).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
```

**Definition of Done:**
- [ ] `RELAY_DEVICE_TOKEN=0123456789abcdef node src/server.js` listens on 8080 and `GET /relay/health` returns
      `{"status":"ok","deviceConnected":false}`.

### Task 6.3 — Tests

**File**: `relay/test/helpers.js` — shared infrastructure, included in full:

```js
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createRelay } from '../src/relay.js';

export const TEST_TOKEN = 'test-device-token-0123456789';

/** Heartbeat is slow by default so pings never interleave with test traffic; heartbeat tests override it. */
export const TEST_CONFIG = Object.freeze({
  port: 0,
  deviceToken: TEST_TOKEN,
  requestTimeoutMs: 300,
  maxBodyBytes: 1024,
  maxResponseBytes: 2048,
  maxPending: 2,
  heartbeatIntervalMs: 1_000,
  heartbeatTimeoutMs: 5_000,
  trustProxy: true,
});

const silentLogger = { info() {}, warn() {}, error() {} };

/** Starts a relay on an ephemeral loopback port. */
export async function startRelay(overrides = {}) {
  const relay = createRelay({ ...TEST_CONFIG, ...overrides }, silentLogger);
  relay.server.listen(0, '127.0.0.1');
  await once(relay.server, 'listening');
  const { port } = relay.server.address();
  return {
    relay,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    wsUrl: `ws://127.0.0.1:${port}/device`,
    stop: () => relay.close(),
  };
}

/**
 * Opens a device socket with the given bearer; resolves once the handshake completes.
 * By default it answers relay pings with pongs (like the app does); `autoPong: false` plays a dead device.
 */
export async function connectDevice(wsUrl, token = TEST_TOKEN, { autoPong = true } = {}) {
  const ws = new WebSocket(wsUrl, { headers: { authorization: `Bearer ${token}` } });
  await once(ws, 'open');
  if (autoPong) {
    ws.on('message', (data) => {
      if (JSON.parse(data.toString('utf8')).type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  }
  return ws;
}

/** Resolves with the next frame of the given type (default `request`); `null` accepts any type. */
export async function nextFrame(ws, type = 'request') {
  for (;;) {
    const [data] = await once(ws, 'message');
    const frame = JSON.parse(data.toString('utf8'));
    if (type === null || frame.type === type) return frame;
  }
}

/** Sends a response frame for the given request id (body is a string or Buffer). */
export function respond(ws, id, { status = 200, headers = {}, body = '' } = {}) {
  ws.send(JSON.stringify({
    type: 'response',
    id,
    status,
    headers,
    body: Buffer.from(body).toString('base64'),
  }));
}

/** Resolves with the HTTP status the server used to refuse a WebSocket upgrade. */
export function rejectedUpgradeStatus(wsUrl, headers) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl, { headers });
    ws.on('unexpected-response', (_req, res) => {
      res.resume();
      resolve(res.statusCode);
    });
    ws.on('open', () => reject(new Error('upgrade unexpectedly accepted')));
    ws.on('error', reject);
  });
}
```

**File**: `relay/test/config.test.js`

| Test | Verifies |
|------|----------|
| `loadConfig applies defaults` | only `RELAY_DEVICE_TOKEN` set → 8080, 120000, 16 MiB, 8 MiB, 32, 25000, 60000, `trustProxy true` |
| `loadConfig reads overrides` | all integer vars parsed; `TRUST_PROXY=false` → false |
| `loadConfig rejects missing token` | throws mentioning `RELAY_DEVICE_TOKEN` |
| `loadConfig rejects short token` | 15 chars throws |
| `loadConfig rejects non-integer values` | `PORT=abc`, `PORT=0`, `PORT=12.5` each throw |
| `loadConfig rejects out-of-range port` | `PORT=70000` throws |
| `loadConfig rejects invalid booleans` | `TRUST_PROXY=maybe` throws |

**File**: `relay/test/frames.test.js`

| Test | Verifies |
|------|----------|
| `encodeRequestFrame base64-encodes the body` | round-trip of a binary Buffer |
| `decodeDeviceFrame returns null for invalid json` | `not json` |
| `decodeDeviceFrame returns null for non-object or missing type` | `[1]`, `{"id":"x"}` |
| `decodeDeviceFrame returns type for pong and unknown` | `{type:'pong'}`, `{type:'hello'}` |
| `decodeDeviceFrame normalizes a response` | headers arrays kept, body decoded, status int |
| `decodeDeviceFrame rejects malformed responses` | missing id, non-integer status, status 101, status 600, header value not array/strings |
| `decodeDeviceFrame defaults missing body and headers` | `body.length === 0`, `headers` `{}` |

**File**: `relay/test/relay.test.js`

**Setup**: `startRelay()` in `beforeEach`, `stop()` in `afterEach`; global `fetch` for HTTP (a raw `net.connect`
request only for the absolute-form case); device via `connectDevice`.

| Test | Verifies |
|------|----------|
| `health reports device state` | `deviceConnected` false, then true after connect, `200` both times |
| `upgrade without bearer is refused with 401` | `rejectedUpgradeStatus(wsUrl, {})` → 401 |
| `upgrade with wrong bearer is refused with 401` | `authorization: Bearer nope` → 401 |
| `upgrade on another path is refused with 404` | `…/other` → 404 |
| `request without device returns 503` | `fetch(baseUrl + '/mcp', POST)` → 503 JSON `error` |
| `request is forwarded as a frame and the response frame is returned` | frame has `type request`, uuid `id`, `method POST`, `path /mcp?x=1`, header `x-test: ['1']`, body base64 of sent JSON, no `host`/`content-length`; `respond(ws, id, {status: 201, headers: {'Content-Type': ['application/json'], 'Mcp-Session-Id': ['abc'], 'Transfer-Encoding': ['chunked']}, body})` → fetch status 201, `content-type`, `mcp-session-id` present, `transfer-encoding` absent, body equal |
| `absolute-form request target is forwarded as origin-form` | raw `GET http://relay.invalid/mcp?x=1 HTTP/1.1` → frame `path === '/mcp?x=1'` |
| `trusted proxy headers are preserved` | request with `x-forwarded-host: public.example`, `x-forwarded-proto: https` → frame keeps them, `x-forwarded-for` appended |
| `untrusted proxy headers are overwritten` | `startRelay({ trustProxy: false })`; same request → frame `x-forwarded-host == ['127.0.0.1:<port>']`, `x-forwarded-proto == ['http']`, `x-forwarded-for` only the socket address |
| `missing proxy headers are filled in` | default relay, no `x-forwarded-*` sent → host/proto filled from the request |
| `head and 204 responses carry no body` | `HEAD /x` answered with body `ignored` → empty body, no `content-length`; `POST` answered `204` → empty |
| `device not answering returns 504` | no response within 300 ms → 504 |
| `body over the limit returns 413 and closes the connection` | 2048-byte body with `maxBodyBytes: 1024` → 413, response header `connection: close`; device receives no frame (next `nextFrame(ws, null)` within 200 ms rejects / times out) |
| `declared content-length over the limit returns 413 before reading` | raw request with `Content-Length: 4096` and no body → 413 |
| `oversized device response returns 502` | `respond` with 2049-byte body (`maxResponseBytes: 2048`) → 502 |
| `pending limit returns 503` | two unanswered requests in flight (`maxPending: 2`) → third `fetch` → 503 immediately; answering the first two yields their statuses |
| `malformed and unknown device frames are ignored` | device sends `nope`, `{"type":"hello"}`, response for unknown id, response with status 150; a subsequent request still round-trips |
| `new device replaces the previous one` | first socket gets close code 4000; pending request on it → 502; new socket receives the next request |
| `device disconnect fails in-flight requests with 502` | request pending, `ws.close()` → 502 |
| `relay pings the device` | `startRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 5_000 })`; first `nextFrame(ws, 'ping')` within 500 ms |
| `unresponsive device is terminated` | `startRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 120 })`, `connectDevice(…, { autoPong: false })` → `close` event within 1 s; health shows disconnected |
| `shutdown closes device socket with 1001` | `stop()` → device receives close code 1001 |

**Definition of Done:**
- [ ] Every status code and header rule in the US6 acceptance criteria has a row above.

### Task 6.4 — Container and README

**Action 1 — create** `relay/Dockerfile`

```dockerfile
# node:24-alpine, pinned by digest (resolved 2026-10-10 with `docker buildx imagetools inspect node:24-alpine`).
FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1

ENV NODE_ENV=production
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY src ./src

USER node
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 8080) + '/relay/health').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "src/server.js"]
```

**Action 2 — create** `relay/README.md`

````markdown
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
````

**Definition of Done:**
- [ ] Every environment variable read by `config.js` appears in the README table.

---

## User Story 7 — Relay CI

**Why:** The Android CI never touches `relay/`; the relay needs its own cheap gate (lint, tests, image build) on PRs and
`main`, scoped by path so Android-only changes do not trigger it.

**Acceptance criteria:**
- [ ] Workflow runs on `pull_request`/`push main` when `relay/**` or the workflow file changes; runs `npm ci`,
      `npm run lint`, `npm test`, `docker build`.

### Task 7.1 — Workflow

**Action 1 — create** `.github/workflows/relay.yml`

```yaml
name: Relay

on:
  push:
    branches: [main]
    paths:
      - 'relay/**'
      - '.github/workflows/relay.yml'
  pull_request:
    branches: [main]
    paths:
      - 'relay/**'
      - '.github/workflows/relay.yml'

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  test:
    name: Relay tests and image
    runs-on: ubuntu-latest
    timeout-minutes: 10
    defaults:
      run:
        working-directory: relay
    steps:
      - name: Checkout
        uses: actions/checkout@v7

      - name: Set up Node 24
        uses: actions/setup-node@v7
        with:
          node-version: '24'
          cache: npm
          cache-dependency-path: relay/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Lint
        run: npm run lint

      - name: Run tests
        run: npm test

      - name: Build container image
        run: docker build -t lucky-tool-android-relay:ci .
```

**Definition of Done:**
- [ ] The PR shows the `Relay tests and image` check next to the five Android checks (`Lint`, `Unit & Integration
      Tests`, `E2E Tests`, `Build Release - GMS`, `Build Release - FOSS`).

---
## User Story 8 — Documentation

**Why:** `PROJECT.md`/`ARCHITECTURE.md` are the implementing LLM's source of truth and `README.md`/`CONTRIBUTING.md`
the operator's; every enumeration of tunnel providers, the structure lists and the ADB extras table must name the relay.
Placed last because it links to `relay/README.md`.

**Acceptance criteria:**
- [ ] `grep -n "ngrok" docs/PROJECT.md README.md docs/ARCHITECTURE.md CONTRIBUTING.md` shows no provider enumeration
      without the relay (ngrok-only lines about ngrok itself are fine).
- [ ] Both Mermaid diagrams touched render with `mmdc`.

### Task 8.1 — `docs/PROJECT.md`

**Action 1 — modify** the two structure lines (append to the existing file lists):

```markdown
  - `services/tunnel/` — `TunnelProvider.kt`, `TunnelManager.kt`, `CloudflareTunnelProvider.kt`, `CloudflaredBinaryResolver.kt`, `AndroidCloudflareBinaryResolver.kt`, `NgrokTunnelProvider.kt`, `RelayTunnelProvider.kt`, `RelaySession.kt`, `RelayTarget.kt`, `RelayFrames.kt`, `LocalRequestReplayer.kt`
```

```markdown
  - `data/repository/` — `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`, `RelaySettings.kt`, `RelaySettingsImpl.kt`
```

**Action 2 — modify** the top-level folder list: insert after the `e2e-tests/` line and after the `.github/workflows/ci.yml` line.

```markdown
- `relay/` — Self-hosted WebSocket relay for the `RELAY` tunnel provider (Node 24 + `ws`, `node:test`, `Dockerfile`)
```

```markdown
- `.github/workflows/relay.yml` — Relay lint, tests and image build (runs only when `relay/**` changes)
```

**Action 3 — modify** the Screen Structure paragraph: replace `RemoteAccessSection (tunnel toggle, provider selection, ngrok config, tunnel status)`
with `RemoteAccessSection (tunnel toggle, provider selection, ngrok / relay config, tunnel status)`.

**Action 4 — modify** the `- **Remote access tunnels**:` bullet (HTTPS section):

```markdown
- **Remote access tunnels**: The app integrates with **Cloudflare Quick Tunnels** (no account required, random `*.trycloudflare.com` URL), **ngrok** (account required, optional custom domain) and a **self-hosted WebSocket relay** (your own server; the device connects outbound) to expose the local MCP server via a public HTTPS URL with valid certificates. See the Remote Access / Tunnel section below.
```

**Action 5 — modify** the Remote Access / Tunnel section: insert after the `- **ngrok**:` bullet.

```markdown
- **WebSocket relay**: The device opens an outbound WebSocket (`<relayUrl>/device`, bearer-authenticated with the relay device token, ≥ 16 characters) to a relay you host — the Node service in `relay/` (container image from `relay/Dockerfile`). The relay forwards each HTTP request it receives as a JSON frame (`{"type":"request",id,method,path,headers,body(base64)}`); the app replays it against `http://127.0.0.1:<port>` (at most 8 concurrently, 100 s each, responses up to 8 MiB) and answers with a `response` frame. The relay URL is `ws[s]://host[:port]` only (no path); the public URL is the same authority with `wss`→`https` (`ws`→`http`). The provider reconnects with exponential backoff (1 s … 30 s, reset after a session ≥ 10 s) and redials a socket silent for 90 s. No inbound port on the device, no third-party account. The relay answers `GET /relay/health` itself; everything else reaches the app. Cleartext `ws://` is allowed only with a warning (loopback/private hosts excepted).
```

**Action 6 — modify** the CORS paragraph: replace `Cloudflare/ngrok tunnels, \`public_url_override\`` with
`Cloudflare/ngrok tunnels, the WebSocket relay, \`public_url_override\``.

**Action 7 — modify** the defaults list: insert after `- **ngrok Domain**: Empty (auto-assigned when empty)`.

```markdown
- **Relay URL**: Empty (required when using the WebSocket relay; `ws://host[:port]` or `wss://host[:port]`, the app appends `/device`)
- **Relay Device Token**: Empty (required when using the WebSocket relay; at least 16 characters, presented as bearer on the relay's `/device` socket)
```

**Definition of Done:**
- [ ] `grep -c RelayTunnelProvider docs/PROJECT.md` ≥ 1 and the defaults list has both relay entries.

### Task 8.2 — `README.md`

**Action 1 — modify** the two feature bullets:

```markdown
- Remote access tunnels via Cloudflare Quick Tunnels, ngrok or a self-hosted WebSocket relay (public HTTPS URL)
```

```markdown
- Remote access tunnel configuration (Cloudflare / ngrok / WebSocket relay)
```

**Action 2 — modify** the settings table row:

```markdown
| Remote Access Tunnel | Disabled | Expose server via public HTTPS URL (Cloudflare Quick Tunnels, ngrok or a self-hosted WebSocket relay) |
```

**Action 3 — modify** "Using Remote Access Tunnels": insert after item 2.

```markdown
3. **WebSocket relay** (self-hosted, no account): Run the relay from [`relay/`](relay/README.md) on a server that terminates HTTPS (e.g. behind Coolify/Traefik), then enter its base URL (`wss://relay.example.com`, no path) and the relay device token (≥ 16 characters) in the app. The device connects outbound, so no port is opened on the phone; MCP clients use `https://relay.example.com/mcp`. Responses above 8 MiB are not relayed.
```

**Action 4 — modify** the ADB example: insert after `--es ngrok_domain "your-domain.ngrok-free.app" \`.

```bash
  --es relay_url "wss://relay.example.com" \
  --es relay_device_token "your-relay-device-token" \
```

**Action 5 — modify** the extras table: `tunnel_provider` row and two new rows after `ngrok_domain`.

```markdown
| `tunnel_provider` | string | `CLOUDFLARE`, `NGROK` or `RELAY` |
```

```markdown
| `relay_url` | string | WebSocket relay base URL (`ws://host[:port]` or `wss://host[:port]`; empty clears it) |
| `relay_device_token` | string | Bearer token the device presents to the relay (≥ 16 characters; empty is ignored) |
```

**Definition of Done:**
- [ ] README mentions `RELAY`, `relay_url` and `relay_device_token` exactly where the other providers' extras are listed.

### Task 8.3 — `docs/ARCHITECTURE.md` and `CONTRIBUTING.md`

**Action 1 — modify** `docs/ARCHITECTURE.md`, the `Tunnel` subgraph:

```mermaid
            subgraph Tunnel["TunnelManager (optional)"]
                CF["CloudflareTunnelProvider\n(process-based)"]
                Ngrok["NgrokTunnelProvider\n(in-process JNI)"]
                Relay["RelayTunnelProvider\n(outbound WebSocket)"]
                PubURL["Public HTTPS URL\n(*.trycloudflare.com / ngrok / relay)"]
                CF --> PubURL
                Ngrok --> PubURL
                Relay --> PubURL
            end
```

**Action 2 — modify** `docs/ARCHITECTURE.md`, startup step g:

```markdown
     g. If tunnel enabled: starts `TunnelManager` (connects to Cloudflare, ngrok or the WebSocket relay)
```

**Action 3 — modify** `CONTRIBUTING.md`, the architecture diagram edge:

```mermaid
            TunnelMgr -->|"Cloudflare / ngrok / relay"| PublicURL["Public HTTPS URL"]
```

**Definition of Done:**
- [ ] Both modified diagrams, extracted to `.mmd`, render to SVG with `npx -p @mermaid-js/mermaid-cli mmdc`.

---

## Manual QA Steps (not automated — Compose UI; run on the installed debug APK)

1. Settings → Tunnel: enable Remote Access; the provider list shows **WebSocket relay — Your own relay server** as
   the third radio; selecting it reveals **Relay URL** and **Relay device token**; the Tunnel row subtitle on the
   settings list reads "Cloudflare, ngrok, WebSocket relay".
2. Type `https://x` → red field with "Relay URL must start with ws:// or wss://"; type `ws://relay.example.com` → no
   error, cleartext warning shown; type `wss://relay.example.com` → no warning. Type a 10-character token → error
   mentioning 16; a 16+ character token → saved (eye icon toggles masking).
3. With the relay deployed and the server started: status shows **Connected**; the Connection Info card shows the
   relay's `https://…` URL; `curl https://<relay>/health` returns the app's health JSON; stopping the server shows
   **Disconnected** and `curl` returns `503`.

---

## Quality gates and delivery (run ONLY after all user stories are implemented)

1. Local pre-checks: `ktlint` 1.8.0 and `detekt-cli` 2.0.0-alpha.5 (default rules) over `app/src/main/kotlin`,
   `app/src/test/kotlin` and `app/src/testGms/kotlin` — zero findings; `cd relay && npm ci && npm run lint && npm test
   && docker build -t lucky-tool-android-relay .`; Mermaid render of both modified diagrams. `act` is not installed:
   `gh act --validate` is skipped and the workflow is validated by its first CI run.
2. Push the branch, open the PR (title above, body per `docs/TOOLS.md`, no AI attribution), then read the checks:
   `gh pr checks <n> --watch`. Required green: `Lint`, `Unit & Integration Tests`, `E2E Tests`, `Build Release - GMS`,
   `Build Release - FOSS`, `Relay tests and image`.
3. `code-reviewer` in plan-compliance mode over the whole diff; fix everything; re-run until clean.
4. Deliverable: the `app-gms-debug` artifact of the PR's CI run (`gh run download <run-id> -n app-gms-debug`) is the
   installable APK (`adb install -r <apk>`); the signed `edge` pre-release follows the merge to `main`.

---

## Review findings — `plan-reviewer`, 2026-10-10 (all addressed in the text above)

| ID | Finding | Resolution |
|----|---------|------------|
| C1 | Replay client followed redirects (OAuth 302 lost; loopback SSRF) | `followRedirects = false` on the production and test clients; tests `replay passes a redirect through…`, `redirect from local server is passed through` |
| C2 | `stop()` did not close the socket; close under cancellation | `client.cancel()` in `stop()`, Close frame under `NonCancellable` with timeout, per-start `sessionScope`; tests `stop closes session…`, `stop during handshake…`, `openSessions` counter in `RelayTestServer` |
| C3 | Relay test helpers: heartbeat shorter than request timeout, pings interleaving | heartbeat defaults 1 s / 5 s (overridden only in heartbeat tests), `connectDevice` auto-pongs, `nextFrame(ws, type)` filters |
| C4 | `@Suppress("TooGenericExceptionCaught")` | Removed: specific catches (`IOException`, `IllegalArgumentException`, `IllegalStateException` + `ensureActive()`); anything else reaches the scope's `CoroutineExceptionHandler` which publishes `Error` |
| C5 | `relay/` location reserved for the human by `REGLAS.md` | Declared PROVISIONAL in the context (self-contained folder, trivially movable); `REGLAS.md` is on the unmerged PR #2 branch, its relay lines are a follow-up; the human confirms the location on PR review |
| W1 | Unbounded responses tear the tunnel down; `maxFrameSize` no-op on OkHttp | 8 MiB response cap on both sides (`readRemaining(max + 1)`, `MAX_RESPONSE_BYTES`), `maxFrameSize` removed, documented in PROJECT/README, tests both sides |
| W2 | No concurrency limits | `Semaphore(8)` + `503` frame on the device; `MAX_PENDING` (32) + `503` on the relay; tests both sides |
| W3 | Orphaned loop when `start()` is cancelled | `CancellationException` around the wait → `stop()` under `NonCancellable`; test `cancelling a pending start…` |
| W4 | Close code 4000 unhandled; backoff reset storms | `RelaySession.closedEnd()` reads `closeReason`; 4000 → max backoff; backoff resets only after ≥ 10 s session; tests |
| W5 | Backoff untested | `backoffMs` / `nextFailureCount` internal companion functions + table tests |
| W6 | OkHttp silent POST retry | `retryOnConnectionFailure(false)` |
| W7 | Unvalidated `frame.path`; absolute-form forwarded raw | Device: origin-form check → `400`; relay: `url.pathname + url.search`; tests both sides |
| W8 | Client-spoofable `X-Forwarded-*` | `TRUST_PROXY` (default true; false overwrites); three tests; README |
| W9 | `ws://` accepted silently | `isRelayUrlInsecure` + UI warning (loopback/RFC 1918 excepted); tests |
| W10 | Query/capability tokens in logs | `sanitizedPath()`: no query, `/s/<redacted>`; test |
| W11 | 124-char line in `RelayTestServer` | wrapped |
| W12 | Gates embedded in task DoDs | DoDs rewritten as functional outcomes; gates only in the final section |
| W13 | Tasks without DoD | 5.1, 6.1 (now 8.1), 6.2 (8.2), 7.1 (6.1), 7.2 (6.2) have DoDs |
| W14 | Docs referenced `relay/` before it existed | Docs moved to US8 (after relay US6/US7) |
| W15 | Enumerations missed (PROJECT 521/666, folder list, CONTRIBUTING) | Actions added; `CONTRIBUTING.md` in scope |
| W16 | Wrong CI check names / count | `Build Release - GMS` / `Build Release - FOSS`; "five Android checks" |
| W17 | Untested criteria | Rows added: redirects, logging test (url old→new, token without value), unknown frame ignored, VM empty URL saves, start cancelled, close 4000, oversized response, path validation, X-Forwarded preservation, `PORT` range |
| W18 | UI criteria without automated tests | "Manual QA Steps" section |
| W19 | Actions without code | PROJECT structure lines given verbatim; lockfile declared generated with npm/Node versions and `lockfileVersion 3` |
| W20 | Path prefix breaks OAuth metadata | Paths rejected by `validateRelayUrl` and `RelayTarget`; documented |
| W21 | Verbosity / repetition | LargeClass rationale once (context); duplicated DoDs removed; SDK note shortened |
| W22 | Real sockets vs `testApplication` | Kept real sockets, justified in `RelayTestServer` KDoc (exercises the production OkHttp engine, like `EventDispatcherImplTest`); `clientProvider` seam used by the `MockEngine` test |
| I1 | `ktor-client-websockets` is a marker jar | US1 "why" corrected; both artifacts kept (documented convention / explicit test dependency) |
| I2 | Inaccurate DoDs (kapt, `:app:dependencies`, Android Lint) | Removed |
| I3 | `node --test` would run `helpers.js` | `node --test "test/*.test.js"` |
| I4 | Error frames leaked loopback details | Generic messages; details only in logcat; test asserts the body |
| I5 | Stale KDoc (`ServerConfig.tunnelProvider`, `AdbConfigReceiver` example) | Both updated (receiver in scope, KDoc only) |
| I6 | "silent for 0s" | Message in milliseconds |
| I7 | 1xx statuses, `expect`, body on 204/HEAD | Status 200–599; `expect` hop-by-hop; `HEAD`/`204`/`304` without body; tests |
| I8 | 413 kept consuming | `Content-Length` pre-check, `connection: close` on 413; tests |
| I9 | Header casing claim | Context corrected (lowercase from Node) |
| I10 | Equal timeouts | Device 100 s < relay 120 s |
| I11 | Scope never cancelled; close cause lost; idle path | Per-start `sessionScope` cancelled in `stop()`; close code/message in reason; Close frame with 2 s timeout |
| I12 | Image not pinned; no JS lint; `act` missing | Digest pinned; `npm run lint` (`node --check`) in CI; `act` note in gates |
| I13 | Branch base lacks REGLAS/CLAUDE note | Stated in context |
| I14 | Token not trimmed / no minimum | `validateRelayDeviceToken` (trim, ≥ 16, shared constant `MIN_RELAY_DEVICE_TOKEN_LENGTH`); provider re-checks |
| I15 | `testGms` not linted | Added to gate 1 |
| I16 | Log volume during outages | No `Connecting` flip between retries; identical `Error` deduped by `StateFlow` at max backoff |
| I17 | Double encoding in public URL | `"$scheme://${uri.rawAuthority}"` (no path allowed) |
| I18 | TARGET statement | Context: declared in `.lifecycle/local/DESPLIEGUE.env`, not copied (REGLAS) |
| I19 | Underscore hosts rejected | Accepted behavior; message says "valid host" |
| I20 | Test placement ambiguity | Task 1.4 setup names the existing vs new `@Nested` classes |
