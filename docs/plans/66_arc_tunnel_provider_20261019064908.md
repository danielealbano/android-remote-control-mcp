<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 66 — Free E2E tunnel provider (android-remote-control-mcp-tunneld)

Branch: `feat/arc-tunnel-provider`, created from the latest `main`.

## Context

Add a third tunnel provider that speaks the `android-remote-control-mcp-tunneld` wire protocol v2
(`docs/PROTOCOL.md` of that repo, server release ≥ v0.5.2): attested two-phase enrollment, one outbound HTTP/2
mTLS control stream (`OPEN`/`PING`/`PONG`/`RENEW_NUDGE`), a fresh dial-back TLS connection per public connection
(`GET /api/v1/data` + `Upgrade: tunneld-dial`, then an opaque splice), and a WebPKI certificate for
`<name>.d.<base>` served by the phone itself. The tunnel server relays ciphertext only.

Reference implementation: `support/tunnel-app/` of that repo. Its protocol logic is ported here with this
project's standards (structured coroutines, interfaces for Android-bound components, kotlinx.serialization) and
the decisions below. Not ported: its `Server`, `Screen`, `Stats`, `MainActivity`, `TunnelRunner`.

## Decisions (user, 2026-10-18/19) — FIXED, reviewers MUST NOT change them

| # | Decision |
|---|---|
| D1 | Code prefix `ArcTunnel`, package `services/tunnel/arctunnel/`, enum `TunnelProviderType.ARC_TUNNEL`. ADB/E2E value `android-remote-control-mcp-tunneld`. UI label "Free E2E tunnel"; description: end-to-end encrypted, public certificate (Let's Encrypt, Google Trust Services, ZeroSSL), the tunnel cannot read the traffic (unlike Cloudflare / ngrok). The service's proper name is its repository name `android-remote-control-mcp-tunneld`, used wherever the docs refer to it; the bare word "tunneld" appears only in the protocol constant `tunneld-dial`, the `TUNNELD_*` env names and the image name. |
| D2 | The provider is listed FIRST and is the DEFAULT for fresh installs. Existing installs KEEP their configuration: a one-time migration stores `CLOUDFLARE` when a tunnel setting was ever written and no provider was stored. Cloudflare and ngrok are otherwise unchanged. |
| D3 | ONE "base address" setting (default `arc-tunnel.io`); hosts derived: `enroll.<base>`, `connect.<base>`, tunnel domain `d.<base>`. Self-hosters change the base. The production base is confirmed with the user BEFORE the quality gates run (US12). |
| D4 | Enrollment runs when the user turns Remote Access on with this provider selected (settings toggle or home button): a dialog ("takes about 10–30 seconds", Continue / Cancel; Cancel or an outside tap turns Remote Access off). A "Set up tunnel" button covers the enabled-but-not-enrolled state (changed base address, failed attempt). Once enrolled: a read-only field with the URL (`https://<name>.d.<base>`, no `/mcp`) and a refresh icon that confirms ("new name") and re-enrolls. A failed certificate issuance is RETRIED as issuance only (same name, PROTOCOL.md §3), never as a new enrollment. All of this only while the server is stopped (settings are editable only then). Enrollment runs in an application-scoped coroutine: leaving the screen does not cancel it. |
| D5 | Devices that cannot attest are not blocked client-side: the server refuses, the app shows the reason. |
| D6 | A separate TLS listener bound to `127.0.0.1` on a RANDOM port chosen when the tunnel starts (bind failure → another random port). When the listener restarts for a certificate swap it reuses the SAME port; a dial-back's local connect retries for up to 10 s. It serves the same MCP application and is independent of the user's HTTP/HTTPS setting; the HTTPS guard does not apply to this provider. TLS protocols as the normal HTTPS mode has them (no TLS 1.3 forcing). |
| D7 | The TLS key is generated ONCE per name and reused at every renewal; only re-enrollment (new name) creates a new one. The identity key is regenerated at every issuance (fresh attestation); the identity key two generations back is deleted after an issuance. |
| D8 | Certificate swap after a renewal: restart only the listener (the control connection stays up) at the first moment with no open dial-back session, waiting at most 24 h; or immediately when the certificate the listener SERVES expires within 5 minutes. |
| D9 | Control reconnect backoff 1, 2, 4, 8, 16 s, stays at 16 s, resets to 1 s once a control stream binds. Dial back FIRST, then connect to the local listener. A malformed control frame tears the stream down (reconnect), no recovery attempt. |
| D10 | Certificate Transparency check (on by default, user toggle): SSLMate Cert Spotter keyless API, triggered on 1 % of requests through the tunnel listener, at most once per 30 minutes, never two in parallel. Compare each issuance's `pubkey_sha256` with SHA-256 of the TLS public key; unknown key → notification + home banner suggesting a name rotation, dismissible; flagged keys are persisted so they are never re-alerted; the `after=<id>` cursor is persisted so each issuance is examined once; an undismissed alert survives a process restart (restored by the UI and by the provider). |
| D11 | OkHttp directly for the control stream, `/issue` and the CT request (Ktor's client cannot do duplex bodies). OkHttp 5.5.0 (Ktor 3.5.2 already resolves OkHttp 5.3.2). |
| D12 | Identity: private keys ONLY in AndroidKeyStore; name, aliases and chains in an app-private JSON file per base address. No extra signing of the metadata. |
| D13 | Both flavors (`gms`, `foss`). Server allowlist: release done; F-Droid and the local debug signer are the user's job and a prerequisite of the device test. |
| D14 | E2E: (a) the full stack in testcontainers replicated from the tunnel server repo's own e2e (Valkey, MinIO, Pebble + challtestsrv, the server from `ghcr.io/danielealbano/android-remote-control-mcp-tunneld:latest`, NEVER built locally, NEVER pinned, always pulled), attestation optional, ACME `tls-alpn-01` answered by the server (no DNS-01, no shim); (b) a device-gated test against PRODUCTION on the attached phone that SKIPS when no phone is attached. The debug-only `E2EConfigReceiver` gets extras for the edge override, the trusted CA PEM and a CT-check-off switch. |
| D15 | The e2e uses the `TUNNELD_S3_*` env names (server ≥ v0.5.2, released). |
| D16 | Home card: "Enable free E2E tunnel", "Enable Wi-Fi access", "Other tunnels" (the last selects Cloudflare and opens the tunnel settings without turning Remote Access on). |
| D17 | Concurrency per this project's standard: structured coroutines on a provider-owned scope; blocking socket/OkHttp I/O on `Dispatchers.IO`; cancellation closes sockets/calls; the service's 3 s tunnel-stop budget is met by a bounded join. No raw threads, no executors in app code. |
| D18 | Approved lint suppressions, exactly these: `@Suppress("TooGenericExceptionCaught")` in the control loop's renewal callback (any issuance failure is logged, cancellation rethrown); `@file:Suppress("FunctionNaming", "LongMethod")` on Compose screen files. Nothing else (the provider scope's `CoroutineExceptionHandler` and the staged-key cleanup use no catch at all). |
| D19 | In Settings → Tunnel the provider list is ALWAYS visible (also while Remote Access is off) so Cloudflare/ngrok can be chosen before turning Remote Access on; the provider-specific fields show for the selected provider. |
| D20 | Headless setups: the release ADB `CONFIGURE` broadcast accepts `--ez arc_tunnel_enroll true`, which enrolls (or resumes issuance) in the background; the README documents it. Implementation note (not a decision change): enrollment runs in a short foreground service so the process cannot be frozen half-way; Android 12+ refuses a foreground start from a background broadcast, so the README also documents the trampoline action `enroll-tunnel` (the same mechanism it already prescribes for starting the server), while the extra keeps working whenever the app is visible. |

## Conventions for this plan

- `arctunnel/` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/arctunnel/`; `A/` =
  `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`; `T/` = `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`;
  `TA/` = `T/services/tunnel/arctunnel/`; `E/` = `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/`.
- Reference sources (read-only, for comparison): `/home/daalbano/dev/android-remote-control-mcp-tunneld/support/tunnel-app/app/src/main/java/com/example/tunnelapp/` at tag `v0.5.2`.
- Every action carries its complete code. Every code line is ≤ 120 characters (detekt `MaxLineLength`).
- Log tag prefix `MCP:ArcTunnel*`. Never log tokens, nonces, keys or certificate chains.
- detekt runs with its default configuration: ≤ 11 functions per class and ≤ 11 top-level functions per file, ≤ 2 explicit
  `return`s and ≤ 2 `throw`s per function, one jump statement per loop, no magic numbers, function parameters ≤ 5,
  constructor parameters ≤ 6.
- Linting, formatting and tests run ONLY in User Story 12.

---

## [x] User Story 1 — Dependencies

Why: raw OkHttp (duplex bodies) in the app; `mockwebserver3` + `okhttp-tls` in unit tests; an OkHttp-backed Ktor client and BouncyCastle in the e2e module.

Acceptance criteria:
- [x] `gradle/libs.versions.toml` declares `okhttp = "5.5.0"` and the three OkHttp library aliases; both modules declare the dependencies below.

### [x] Task 1.1 — Version catalog and build files

[x] **Action 1.1.1** — modify `gradle/libs.versions.toml`:

```toml
# [versions] — after `bouncy-castle`
okhttp = "5.5.0"

# [libraries] — after `bouncy-castle-prov`
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver3 = { group = "com.squareup.okhttp3", name = "mockwebserver3", version.ref = "okhttp" }
okhttp-tls = { group = "com.squareup.okhttp3", name = "okhttp-tls", version.ref = "okhttp" }
```

[x] **Action 1.1.2** — modify `app/build.gradle.kts`:

```kotlin
    // Ktor Client (Event Channel dispatcher — no Logging plugin, it would expose auth token)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    // Raw OkHttp: the free E2E tunnel control stream is an HTTP/2 duplex request body (not expressible
    // through the Ktor client), plus its /issue and Certificate Transparency calls.
    implementation(libs.okhttp)
```
and after `testImplementation(libs.ktor.sse)`:
```kotlin
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
```

[x] **Action 1.1.3** — modify `e2e-tests/build.gradle.kts`, after `testImplementation(libs.ktor.sse)`:

```kotlin
    // Free E2E tunnel: OkHttp-backed Ktor client (SNI + DNS override to reach the tunnel edge), raw OkHttp for
    // the engine config, BouncyCastle for the test internal CA.
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.okhttp)
    testImplementation(libs.bouncy.castle.pkix)
    testImplementation(libs.bouncy.castle.prov)
```

Definition of Done:
- [x] Actions 1.1.1–1.1.3 applied; no other dependency changed.

---

## [x] User Story 2 — Hosts, provider type, settings, migration, ADB keys

Why: every later story derives the service hosts from one base address and reads `ServerConfig.tunnelProvider == ARC_TUNNEL` plus the four new settings; existing installs must keep Cloudflare (D2).

Acceptance criteria:
- [x] `ArcTunnelHosts("arc-tunnel.io")` yields `enroll.arc-tunnel.io`, `connect.arc-tunnel.io`, `d.arc-tunnel.io`.
- [x] `TunnelProviderType.ARC_TUNNEL` is first and the default; `fromAdbValue("android-remote-control-mcp-tunneld")` resolves it; `"CLOUDFLARE"`/`"NGROK"` still resolve; `"ARC_TUNNEL"` does not.
- [x] An upgraded install with `tunnel_enabled` stored and no provider stored reads `CLOUDFLARE` after migration; a fresh install reads `ARC_TUNNEL`.
- [x] ADB accepts `tunnel_provider`, `arc_tunnel_base_address`, `arc_tunnel_ct_check_enabled`; `SettingsRepositoryImpl` stays under detekt's `LargeClass` limit (the new settings are a delegated slice).

### [x] Task 2.1 — Model

[x] **Action 2.1.1** — modify `A/data/model/TunnelProviderType.kt` (replace the file):

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Available tunnel provider types for remote access.
 *
 * @property adbValue The value accepted by the `tunnel_provider` ADB / E2E extra.
 */
enum class TunnelProviderType(
    val adbValue: String,
) {
    /** Free end-to-end encrypted tunnel — no account, hardware-attested enrollment, device-held certificate. */
    ARC_TUNNEL("android-remote-control-mcp-tunneld"),

    /** Cloudflare Quick Tunnel — no account needed, random URL per session. */
    CLOUDFLARE("CLOUDFLARE"),

    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK("NGROK"),
    ;

    companion object {
        /** Resolves an ADB value; null when unknown. */
        fun fromAdbValue(value: String): TunnelProviderType? = entries.firstOrNull { it.adbValue == value }
    }
}
```

[x] **Action 2.1.2** — modify `A/data/model/ServerConfig.kt`:

```kotlin
// KDoc: replace the tunnelProvider line and add four properties after cloudflareTunnelToken
 * @property tunnelProvider The tunnel provider type (free E2E tunnel, Cloudflare or ngrok).
 …
 * @property arcTunnelBaseAddress Base address of the free E2E tunnel service (`enroll.`, `connect.` and the
 *   tunnel domain `d.` are derived from it).
 * @property arcTunnelCtCheckEnabled Whether the Certificate Transparency check of the tunnel certificate runs.
 * @property arcTunnelEdgeOverride Debug/E2E only: `host:port` the tunnel client dials instead of DNS (empty = none).
 * @property arcTunnelTrustedCaPem Debug/E2E only: PEM CA bundle trusted for the tunnel service (empty = system CAs).

// fields
    val tunnelProvider: TunnelProviderType = TunnelProviderType.ARC_TUNNEL,
    …
    val cloudflareTunnelExtraArgs: String = "",
    val arcTunnelBaseAddress: String = DEFAULT_ARC_TUNNEL_BASE_ADDRESS,
    val arcTunnelCtCheckEnabled: Boolean = true,
    val arcTunnelEdgeOverride: String = "",
    val arcTunnelTrustedCaPem: String = "",

// companion
        /** Default base address of the free E2E tunnel service. */
        const val DEFAULT_ARC_TUNNEL_BASE_ADDRESS = "arc-tunnel.io"
```

Definition of Done:
- [x] `TunnelProviderType` and `ServerConfig` as written.

### [x] Task 2.2 — Hosts and environment

[x] **Action 2.2.1** — create `arctunnel/ArcTunnelHosts.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.security.MessageDigest

/** Host names of a free E2E tunnel service, all derived from one base address. */
data class ArcTunnelHosts(
    val baseAddress: String,
) {
    val enrollHost: String = "enroll.$baseAddress"
    val controlHost: String = "connect.$baseAddress"
    val tunnelDomain: String = "d.$baseAddress"

    /** Stable per-environment key (store file name + keystore alias prefix). */
    val envKey: String =
        "e" +
            MessageDigest
                .getInstance("SHA-256")
                .digest("$enrollHost\n$controlHost\n$tunnelDomain".toByteArray())
                .take(ENV_KEY_BYTES)
                .joinToString("") { "%02x".format(it) }

    fun fqdn(name: String): String = "$name.$tunnelDomain"

    fun publicUrl(name: String): String = "https://${fqdn(name)}"

    companion object {
        private const val ENV_KEY_BYTES = 6
        private const val MAX_LENGTH = 253
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** Lowercase DNS name with at least two labels, no scheme, no port, no trailing dot. */
        fun isValidBaseAddress(value: String): Boolean {
            val labels = value.split('.')
            return value.length <= MAX_LENGTH && labels.size >= 2 && labels.all { LABEL.matches(it) }
        }
    }
}
```

[x] **Action 2.2.2** — create `arctunnel/ArcTunnelEnv.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig

/** Everything the tunnel client needs from [ServerConfig]: the base address plus the debug-only overrides. */
data class ArcTunnelEnv(
    val baseAddress: String,
    val edgeOverride: String = "",
    val trustedCaPem: String = "",
) {
    val hosts: ArcTunnelHosts = ArcTunnelHosts(baseAddress)

    companion object {
        /** The overrides are honoured in debug builds only (defence in depth: release never redirects the client). */
        fun from(
            config: ServerConfig,
            debug: Boolean = BuildConfig.DEBUG,
        ): ArcTunnelEnv =
            if (debug) {
                ArcTunnelEnv(config.arcTunnelBaseAddress, config.arcTunnelEdgeOverride, config.arcTunnelTrustedCaPem)
            } else {
                ArcTunnelEnv(config.arcTunnelBaseAddress)
            }
    }
}

/** `host:port` the client dials instead of resolving the service hosts (debug/E2E, see `ArcTunnelNet`). */
data class ArcTunnelEdgeOverride(
    val host: String,
    val port: Int,
) {
    companion object {
        private const val MAX_PORT = 65535

        fun parse(value: String): ArcTunnelEdgeOverride? {
            val sep = value.lastIndexOf(':')
            val port = if (sep > 0) value.substring(sep + 1).toIntOrNull() else null
            val valid = port != null && port in 1..MAX_PORT
            return if (valid) ArcTunnelEdgeOverride(value.substring(0, sep), requireNotNull(port)) else null
        }
    }
}
```

Definition of Done:
- [x] `ArcTunnelHosts`, `ArcTunnelEnv` and `ArcTunnelEdgeOverride` as written.

### [x] Task 2.3 — Settings repository and migration

detekt counts `SettingsRepositoryImpl` at 599 of the 600 lines `LargeClass` allows, so the new settings live in a delegated slice like `EventChannelSettings`; the class gains three lines (constructor parameter, delegation clause, migration call) and loses three (`getBuiltinLocationPermissions` collapsed onto the existing top-level helper) = 599, unchanged.

[x] **Action 2.3.1** — create `A/data/repository/ArcTunnelSettings.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

/** Settings of the free E2E tunnel provider; [SettingsRepository] exposes them through delegation. */
interface ArcTunnelSettings {
    /** Updates the base address of the free E2E tunnel service. */
    suspend fun updateArcTunnelBaseAddress(baseAddress: String)

    /** Updates the Certificate Transparency check toggle of the free E2E tunnel. */
    suspend fun updateArcTunnelCtCheckEnabled(enabled: Boolean)

    /**
     * Debug/E2E only: points the tunnel client at [edgeOverride] (`host:port`, empty = none) trusting
     * [trustedCaPem].
     */
    suspend fun updateArcTunnelE2EOverride(
        edgeOverride: String,
        trustedCaPem: String,
    )
}
```

[x] **Action 2.3.2** — create `A/data/repository/ArcTunnelSettingsImpl.kt` (the four keys are `internal` because `SettingsRepositoryImpl` reads them when mapping the configuration):

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import javax.inject.Inject

internal val ARC_TUNNEL_BASE_ADDRESS_KEY = stringPreferencesKey("arc_tunnel_base_address")
internal val ARC_TUNNEL_CT_CHECK_ENABLED_KEY = booleanPreferencesKey("arc_tunnel_ct_check_enabled")
internal val ARC_TUNNEL_EDGE_OVERRIDE_KEY = stringPreferencesKey("arc_tunnel_edge_override")
internal val ARC_TUNNEL_TRUSTED_CA_PEM_KEY = stringPreferencesKey("arc_tunnel_trusted_ca_pem")

/**
 * [ArcTunnelSettings] backed by the same Preferences DataStore as [SettingsRepositoryImpl] (which delegates
 * these members here); user-facing changes are coalesced-logged through [SettingsChangeLogger].
 */
class ArcTunnelSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : ArcTunnelSettings {
        override suspend fun updateArcTunnelBaseAddress(baseAddress: String) {
            dataStore.edit { prefs ->
                val old = prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] ?: ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS
                prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] = baseAddress
                settingsChangeLogger.submit("arc_tunnel_base_address", old, baseAddress) { o, n ->
                    "Free E2E tunnel base address changed $o → $n"
                }
            }
        }

        override suspend fun updateArcTunnelCtCheckEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] ?: true
                prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("arc_tunnel_ct_check_enabled", old.toString(), enabled.toString()) { _, n ->
                    "Free E2E tunnel certificate check ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }

        override suspend fun updateArcTunnelE2EOverride(
            edgeOverride: String,
            trustedCaPem: String,
        ) {
            dataStore.edit { prefs ->
                prefs[ARC_TUNNEL_EDGE_OVERRIDE_KEY] = edgeOverride
                prefs[ARC_TUNNEL_TRUSTED_CA_PEM_KEY] = trustedCaPem
            }
        }
    }
```

[x] **Action 2.3.3** — modify `A/data/repository/SettingsRepository.kt`:

```kotlin
// interface header
interface SettingsRepository :
    EventChannelSettings,
    ArcTunnelSettings {
```
and extend the KDoc of `ensureAuthModelMigrated` with: "Also runs the one-time tunnel-provider migration (an install that ever wrote a tunnel setting without choosing a provider keeps Cloudflare)."

[x] **Action 2.3.4** — modify `A/data/repository/SettingsRepositoryImpl.kt`:

```kotlin
// imports (add)
import androidx.datastore.preferences.core.MutablePreferences

// file-private keys, after CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY
private val TUNNEL_PROVIDER_MIGRATED_KEY = booleanPreferencesKey("tunnel_provider_migrated")

// file-private top-level, after generateTokenString()
/**
 * One-time tunnel-provider migration: an install that ever wrote a tunnel setting without choosing a
 * provider was using the old default (Cloudflare) and keeps it; a fresh install (no tunnel key at all)
 * gets the new default, the free E2E tunnel.
 */
private fun migrateTunnelProvider(prefs: MutablePreferences) {
    if (prefs[TUNNEL_PROVIDER_MIGRATED_KEY] == true) return
    val tunnelKeys =
        listOf(
            TUNNEL_ENABLED_KEY,
            NGROK_AUTHTOKEN_KEY,
            NGROK_DOMAIN_KEY,
            CLOUDFLARE_TUNNEL_MODE_KEY,
            CLOUDFLARE_TUNNEL_TOKEN_KEY,
            CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY,
        )
    if (tunnelKeys.any { prefs.contains(it) } && prefs[TUNNEL_PROVIDER_KEY] == null) {
        prefs[TUNNEL_PROVIDER_KEY] = TunnelProviderType.CLOUDFLARE.name
    }
    prefs[TUNNEL_PROVIDER_MIGRATED_KEY] = true
}

// mapPreferencesToServerConfig (top-level): default provider + new fields
    val tunnelProviderName = prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.ARC_TUNNEL.name
    …
        tunnelProvider =
            TunnelProviderType.entries.firstOrNull { it.name == tunnelProviderName }
                ?: TunnelProviderType.ARC_TUNNEL,
    …
        cloudflareTunnelExtraArgs = prefs[CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY] ?: "",
        arcTunnelBaseAddress = prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] ?: ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS,
        arcTunnelCtCheckEnabled = prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] ?: true,
        arcTunnelEdgeOverride = prefs[ARC_TUNNEL_EDGE_OVERRIDE_KEY] ?: "",
        arcTunnelTrustedCaPem = prefs[ARC_TUNNEL_TRUSTED_CA_PEM_KEY] ?: "",

// class header: constructor parameter + delegation
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
        eventChannelSettings: EventChannelSettings,
        arcTunnelSettings: ArcTunnelSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        ArcTunnelSettings by arcTunnelSettings {

// ensureAuthModelMigrated: inside the same dataStore.edit block, after the bearer-token block
                migrateTunnelProvider(prefs)

// updateTunnelProvider: the default argument becomes TunnelProviderType.ARC_TUNNEL.name

// getBuiltinLocationPermissions: REPLACE its 5-line body with the existing top-level helper (same behaviour;
// this keeps the class at 599 lines of code)
        override suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions> =
            getBuiltinLocationPermissionsInternal(dataStore.data.first())
```

[x] **Action 2.3.5** — modify `A/di/AppModule.kt`: in `RepositoryModule`, after `bindEventChannelSettings`, add (plus the two imports):

```kotlin
import com.danielealbano.androidremotecontrolmcp.data.repository.ArcTunnelSettings
import com.danielealbano.androidremotecontrolmcp.data.repository.ArcTunnelSettingsImpl

    /** Binds the free E2E tunnel settings slice delegated to by [SettingsRepositoryImpl]. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelSettings(impl: ArcTunnelSettingsImpl): ArcTunnelSettings
```

Definition of Done:
- [x] Slice interface + implementation, delegation, keys and the one-time migration as written; `SettingsRepositoryImpl` at 599 lines of code as measured by detekt (`LargeClass` allows 600).

### [x] Task 2.4 — ADB configuration keys

[x] **Action 2.4.1** — modify `A/services/mcp/AdbConfigHandler.kt`:

```kotlin
// import (add)
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts

// handleConfigure: first statement of the function body, before `Log.i(TAG, "Received ADB configuration broadcast")`:
// the one-time migrations run before this broadcast's writes, so a fresh install configured over ADB keeps
// the new default provider
        settingsRepository.ensureAuthModelMigrated()

// handleConfigure: after applyNgrokDomain(intent)
        applyArcTunnelBaseAddress(intent)
        applyArcTunnelCtCheckEnabled(intent)

// applyTunnelProvider: replace the body after `val value = … ?: return`
        val provider = TunnelProviderType.fromAdbValue(value)
        if (provider == null) {
            Log.w(
                TAG,
                "Ignoring invalid tunnel_provider '$value' " +
                    "(valid: ${TunnelProviderType.entries.joinToString { it.adbValue }})",
            )
            return
        }
        settingsRepository.updateTunnelProvider(provider)
        Log.i(TAG, "Tunnel provider updated to $provider")

// new functions, after applyNgrokDomain
    private suspend fun applyArcTunnelBaseAddress(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_ARC_TUNNEL_BASE_ADDRESS) ?: return
        if (!ArcTunnelHosts.isValidBaseAddress(value)) {
            Log.w(TAG, "Ignoring invalid arc_tunnel_base_address '$value'")
            return
        }
        settingsRepository.updateArcTunnelBaseAddress(value)
        Log.i(TAG, "Free E2E tunnel base address updated to '$value'")
    }

    private suspend fun applyArcTunnelCtCheckEnabled(intent: Intent) {
        if (!intent.hasExtra(EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED)) return
        val value = intent.getBooleanExtra(EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED, true)
        settingsRepository.updateArcTunnelCtCheckEnabled(value)
        Log.i(TAG, "Free E2E tunnel certificate check updated to $value")
    }

// companion
        internal const val EXTRA_ARC_TUNNEL_BASE_ADDRESS = "arc_tunnel_base_address"
        internal const val EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED = "arc_tunnel_ct_check_enabled"
```

Definition of Done:
- [x] ADB keys applied; `tunnel_provider` resolves ADB values only.

### [x] Task 2.5 — Tests

**File**: `TA/ArcTunnelHostsTest.kt`

| Test | Verifies |
|------|----------|
| `derives enroll connect and d hosts` | `arc-tunnel.io` → `enroll.arc-tunnel.io`, `connect.arc-tunnel.io`, `d.arc-tunnel.io`; `publicUrl("abc")` = `https://abc.d.arc-tunnel.io` |
| `envKey is stable and base-specific` | same base → same key; different base → different key; 13 chars |
| `isValidBaseAddress accepts and rejects` | accepts `arc-tunnel.io`, `t.example.org`, a 253-character name, a 63-character label; rejects `""`, `localhost`, `Arc-Tunnel.io`, `a..b`, `-a.b`, `https://x.y`, `x.y:443`, `x.y.`, a 254-character name, a 64-character label |
| `edge override parses host and port` | `"10.0.0.1:18443"` → (host, 18443); `"[::1]:443"` → (`[::1]`, 443); `"x"`, `"x:0"`, `"x:70000"` → null |
| `env from config honours overrides only in debug` | `from(config, debug = true)` carries them; `from(config, debug = false)` blanks them |

**File**: `T/data/model/TunnelProviderTypeTest.kt` (create)

| Test | Verifies |
|------|----------|
| `ARC_TUNNEL is the first entry` | `entries.first()` |
| `fromAdbValue resolves the hyphenated value` | → `ARC_TUNNEL` |
| `fromAdbValue resolves CLOUDFLARE and NGROK` | |
| `fromAdbValue rejects enum name ARC_TUNNEL and unknown values` | `"ARC_TUNNEL"`, `"x"` → null |

**File**: `T/data/model/ServerConfigTest.kt` (modify): retarget `default tunnelProvider is CLOUDFLARE` to `ARC_TUNNEL`; add `default arc tunnel settings` (base `arc-tunnel.io`, ct true, override "", pem "").

**File**: `T/data/repository/SettingsRepositoryImplTest.kt` (modify; the constructor call gains the fourth argument `ArcTunnelSettingsImpl(dataStore, changeLogger)`)

| Test | Verifies |
|------|----------|
| existing default-provider assertion (~line 109) | `ARC_TUNNEL` on a fresh store |
| `migration keeps Cloudflare for an install that touched tunnel settings` | pre-write `tunnel_enabled=true` only → after `ensureAuthModelMigrated()` the provider is `CLOUDFLARE` |
| `migration keeps an explicitly stored provider` | pre-write `tunnel_provider=NGROK` + `tunnel_enabled` → stays `NGROK` |
| `migration leaves a fresh install on the new default` | no tunnel keys → `ARC_TUNNEL` |
| `migration runs once` | flag set + provider cleared → still `ARC_TUNNEL` after a second call |
| `serverConfig maps the arc tunnel keys` | pre-written keys → the four `ServerConfig` fields |

**File**: `T/data/repository/SettingsRepositoryServerRunningTest.kt`, `T/data/repository/SettingsRepositoryLoggingTest.kt`, `T/data/repository/SettingsRepositoryUpdateCheckTest.kt` (modify): the constructor call gains the fourth argument `ArcTunnelSettingsImpl(dataStore, changeLogger)`; `T/data/repository/EventChannelSettingsTest.kt` (modify): the constructor call gains `mockk<ArcTunnelSettings>(relaxed = true)`.

**File**: `T/data/repository/ArcTunnelSettingsImplTest.kt` (create; in-memory `PreferenceDataStoreFactory` under `@TempDir` as the other repository tests, `SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L)`)

| Test | Verifies |
|------|----------|
| `updates arc tunnel base address` | round-trip; one log entry "Free E2E tunnel base address changed arc-tunnel.io → tunnel.example.org" |
| `updates arc tunnel ct check enabled` | round-trip false; log entry "… certificate check disabled" |
| `updates arc tunnel e2e override` | both values round-trip; no log entry |

**File**: `T/services/mcp/AdbConfigHandlerTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `tunnel_provider hyphenated value is applied` | `updateTunnelProvider(ARC_TUNNEL)` |
| `tunnel_provider enum name ARC_TUNNEL is ignored` | no repository call |
| `arc_tunnel_base_address valid value is applied` | `updateArcTunnelBaseAddress("tunnel.example.org")` |
| `arc_tunnel_base_address invalid value is ignored` | `"not a host"` |
| `arc_tunnel_ct_check_enabled is applied` | false |
| `handleConfigure runs the migration before any write` | `ensureAuthModelMigrated` verified before `updateTunnelEnabled` (order) |

Definition of Done:
- [x] One test per row; every existing Cloudflare/ngrok test still describes the same behaviour.

---

## [x] User Story 3 — Loopback TLS listener host

Why: the phone terminates the tunnel's TLS on its own loopback listener serving the same MCP application (D6); the provider needs a handle to start it on a random port, restart it on the same port, and hook every request.

Acceptance criteria:
- [x] `McpServer` implements `TunnelListenerHost`: random port when none is requested, retry on bind failure, fixed port when requested (bounded retries), per-request hook, idempotent stop (100 ms grace / 500 ms timeout), stopped together with the server.

### [x] Task 3.1 — Interface

[x] **Action 3.1.1** — create `A/services/tunnel/TunnelListenerHost.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import java.security.PrivateKey
import java.security.cert.X509Certificate

/** TLS material of the tunnel listener: the (keystore-backed) private key and the issued chain, leaf first. */
class TunnelTlsMaterial(
    val privateKey: PrivateKey,
    val chain: List<X509Certificate>,
)

/** A host able to serve the MCP application on a loopback TLS listener for a tunnel provider. */
interface TunnelListenerHost {
    /**
     * Starts the listener on `127.0.0.1`: on [port] when given, otherwise on a random free port. [onRequest]
     * runs once per HTTP request received through it. Returns the bound port.
     *
     * @throws IllegalStateException when the server is not running, a listener already runs or no port could be bound.
     */
    fun startTlsListener(
        material: TunnelTlsMaterial,
        port: Int?,
        onRequest: () -> Unit,
    ): Int

    /** Stops the listener; no-op when none runs. Bounded: 100 ms grace, 500 ms timeout. */
    fun stopTlsListener()
}
```

Definition of Done:
- [x] `TunnelListenerHost` and `TunnelTlsMaterial` as written.

### [x] Task 3.2 — McpServer

[x] **Action 3.2.1** — modify `A/mcp/McpServer.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelListenerHost
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelTlsMaterial
import io.ktor.server.application.ApplicationCallPipeline
import java.io.IOException
import java.security.SecureRandom

// class header: implement the interface
) : TunnelListenerHost {
    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @Volatile
    private var tunnelListener: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Test seam: the random loopback port source of the tunnel listener. */
    internal var tunnelPortChooser: () -> Int = ::randomPort

// stop(): after `server?.stop(gracePeriodMillis, timeoutMillis)`
        stopTlsListener()

// new members, after createHttpsServer()
    @Synchronized
    override fun startTlsListener(
        material: TunnelTlsMaterial,
        port: Int?,
        onRequest: () -> Unit,
    ): Int {
        check(running.get()) { "MCP server is not running" }
        check(tunnelListener == null) { "Tunnel listener is already running" }
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(TUNNEL_KEY_ALIAS, material.privateKey, TUNNEL_KEY_PASSWORD, material.chain.toTypedArray())
            }
        val attempts = if (port == null) TUNNEL_RANDOM_PORT_ATTEMPTS else TUNNEL_FIXED_PORT_ATTEMPTS
        var lastError: IOException? = null
        repeat(attempts) { attempt ->
            val candidate = port ?: tunnelPortChooser()
            val engine = createTunnelListener(keyStore, candidate, onRequest)
            try {
                engine.start(wait = false)
                tunnelListener = engine
                Log.i(TAG, "Tunnel listener started on 127.0.0.1:$candidate")
                return candidate
            } catch (e: IOException) {
                lastError = e
                engine.stop(0, 0)
                Log.w(TAG, "Tunnel listener port $candidate unavailable (attempt ${attempt + 1})")
                if (port != null && attempt < attempts - 1) Thread.sleep(TUNNEL_PORT_RETRY_MS)
            }
        }
        throw IllegalStateException("No loopback port for the tunnel listener after $attempts attempts", lastError)
    }

    @Synchronized
    override fun stopTlsListener() {
        tunnelListener?.stop(TUNNEL_STOP_GRACE_MS, TUNNEL_STOP_TIMEOUT_MS)
        tunnelListener = null
    }

    private fun randomPort(): Int = SecureRandom().nextInt(TUNNEL_PORT_MAX - TUNNEL_PORT_MIN + 1) + TUNNEL_PORT_MIN

    private fun createTunnelListener(
        keyStore: KeyStore,
        port: Int,
        onRequest: () -> Unit,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            factory = Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = TUNNEL_KEY_ALIAS,
                    keyStorePassword = { TUNNEL_KEY_PASSWORD },
                    privateKeyPassword = { TUNNEL_KEY_PASSWORD },
                ) {
                    host = LOOPBACK
                    this.port = port
                }
            },
            module = {
                configureApplication()
                intercept(ApplicationCallPipeline.Monitoring) { onRequest() }
            },
        )

// companion (add)
        private const val LOOPBACK = "127.0.0.1"
        private const val TUNNEL_KEY_ALIAS = "tunnel"
        private val TUNNEL_KEY_PASSWORD = CharArray(0)
        private const val TUNNEL_PORT_MIN = 49152
        private const val TUNNEL_PORT_MAX = 65535
        private const val TUNNEL_RANDOM_PORT_ATTEMPTS = 20
        private const val TUNNEL_FIXED_PORT_ATTEMPTS = 4
        private const val TUNNEL_PORT_RETRY_MS = 250L
        private const val TUNNEL_STOP_GRACE_MS = 100L
        private const val TUNNEL_STOP_TIMEOUT_MS = 500L
```

Context: a bind failure surfaces as `java.net.BindException` (JDK transport) or `io.netty.channel.unix.Errors.NativeIoException` (native transport), both `IOException`; Ktor rethrows the original. A fixed port is retried 4 × 250 ms (≤ 1 s) because the previous engine on that port may still be releasing it. On Android `KeyStore.getDefaultType()` is BKS, which keeps the opaque AndroidKeyStore key object in memory without encoding it (the reference app proves this on device); JVM tests use a real key. `tunnelPortChooser` is the test seam for the random-port retry.

Definition of Done:
- [x] `McpServer` implements the interface as written (random port, retries, hook, bounded stop, test seam).

### [x] Task 3.3 — Tests

**File**: `T/mcp/McpServerTunnelListenerTest.kt`

**Setup**: a JVM EC P-256 key + an `okhttp-tls` `HeldCertificate` chain → `TunnelTlsMaterial`; `McpServer(ServerConfig(port = <free port>, bindingAddress = LOCALHOST), null, mockk<Server>(relaxed = true), mockk(relaxed = true), OAuthServerDeps(mockk(relaxed = true) ×5), RecordingServerLogRepository())` (no existing test constructs `McpServer`; this is the first); `start()` the main server first (the listener requires `running`); requests through a trust-all `HttpsURLConnection`.

| Test | Verifies |
|------|----------|
| `startTlsListener binds a random loopback port and serves /health` | 200 over TLS on the returned port |
| `onRequest runs once per request` | counter == requests sent |
| `requested port is honoured` | `port = <free port>` → same value returned |
| `random ports differ across restarts` | start/stop ×3 → not all three equal |
| `random port bind failure is retried with another port` | `tunnelPortChooser` yields a port held by a `ServerSocket` first, then a free one → the free one is returned, no exception |
| `a held fixed port fails after the attempts` | a `ServerSocket` holds port P; `startTlsListener(material, P, {})` → `IllegalStateException` whose cause is an `IOException`, within 2 s |
| `startTlsListener while running throws` | `IllegalStateException` |
| `startTlsListener without a running server throws` | before `start()` |
| `stopTlsListener is idempotent` | twice |
| `stop() also stops the tunnel listener` | the listener port refuses connections afterwards |

Definition of Done:
- [x] One test per row.

---

## [x] User Story 4 — Protocol client core (`arctunnel/`)

Why: the phone side of PROTOCOL.md v2 as testable Kotlin with this project's standards (D17): a keystore interface so the JVM tests run the real enrollment logic against a fake, suspend retries with `delay`, coroutine-scoped dial-backs and splices, no raw threads. D7 (fixed TLS key) and D9 (backoff, dial-back first) applied.

Acceptance criteria:
- [x] The phone side of PROTOCOL.md v2 (enrollment, issuance, control stream, dial-back, splice, retries, watchdog) is implemented against the keystore interface and verified by JVM tests against the fake keystore.

### [x] Task 4.1 — Pure utilities: PEM, sockets, wire types, frames, CSRs, sessions

[x] **Action 4.1.1** — create `arctunnel/ArcTunnelPem.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object ArcTunnelPem {
    private const val LINE_LENGTH = 64
    private const val HEX_RADIX = 16
    private const val HEX_SHIFT = 4

    fun encode(
        type: String,
        der: ByteArray,
    ): String {
        val b64 =
            Base64
                .getEncoder()
                .encodeToString(der)
                .chunked(LINE_LENGTH)
                .joinToString("\n")
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    fun chainPem(chain: List<X509Certificate>): String = chain.joinToString("") { encode("CERTIFICATE", it.encoded) }

    fun parseChain(pem: String): List<X509Certificate> =
        if (pem.isBlank()) {
            emptyList()
        } else {
            CertificateFactory
                .getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .map { it as X509Certificate }
        }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.isNotEmpty() && hex.length % 2 == 0) { "bad hex" }
        return ByteArray(hex.length / 2) {
            val hi = Character.digit(hex[it * 2], HEX_RADIX)
            val lo = Character.digit(hex[it * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            ((hi shl HEX_SHIFT) + lo).toByte()
        }
    }

    /** Lowercase hex SHA-256 of the DER SubjectPublicKeyInfo — the form Cert Spotter reports as `pubkey_sha256`. */
    fun publicKeySha256(key: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }
}
```

[x] **Action 4.1.2** — create `arctunnel/ArcTunnelSockets.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.io.IOException
import java.net.Socket

object ArcTunnelSockets {
    private const val TAG = "MCP:ArcTunnelSockets"

    fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.d(TAG, "socket close failed", e)
        }
    }
}
```

[x] **Action 4.1.3** — create `arctunnel/ArcTunnelWire.kt` (JSON bodies of PROTOCOL.md §2–§4):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ArcTunnelJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

@Serializable
data class ArcTunnelNonceResponse(
    val nonce: String,
)

@Serializable
data class ArcTunnelEnrollRequest(
    val nonce: String,
    @SerialName("attestation_chain") val attestationChain: String,
    @SerialName("identity_csr") val identityCsr: String,
)

@Serializable
data class ArcTunnelEnrollResponse(
    val name: String,
    @SerialName("identity_cert") val identityCert: String,
    @SerialName("issue_nonce") val issueNonce: String,
)

@Serializable
data class ArcTunnelIssueRequest(
    val nonce: String,
    @SerialName("attestation_chain") val attestationChain: String,
    @SerialName("identity_csr") val identityCsr: String,
    @SerialName("tls_csr") val tlsCsr: String,
)

@Serializable
data class ArcTunnelIssueResponse(
    @SerialName("identity_cert") val identityCert: String,
    @SerialName("public_cert") val publicCert: String,
    val ca: String = "",
)

@Serializable
data class ArcTunnelApiErrorBody(
    val reason: String = "",
    val retryable: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long = 0,
)

@Serializable
data class ArcTunnelOpenPayload(
    val token: String = "",
    val addr: String = "",
    @SerialName("use_https") val useHttps: Boolean = false,
    @SerialName("use_mtls") val useMtls: Boolean = false,
) {
    fun isValid(): Boolean = token.isNotEmpty() && addr.isNotEmpty() && (useHttps || !useMtls)
}

@Serializable
data class ArcTunnelRenewNudgePayload(
    val nonce: String = "",
)

/** Structured server error (PROTOCOL.md §2–§3): `{reason, retryable, retry_after_seconds}` + HTTP status. */
class ArcTunnelApiException(
    val reason: String,
    val retryable: Boolean,
    val retryAfterSec: Long,
    val status: Int,
) : RuntimeException("tunnel service: $reason (status $status)")
```

[x] **Action 4.1.4** — create `arctunnel/ArcTunnelFrames.kt` (`[type:1][len:4 BE][json]`, 1 MiB cap):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okio.BufferedSource
import java.io.IOException

object ArcTunnelFrames {
    const val OPEN = 1
    const val PING = 2
    const val PONG = 3
    const val RENEW_NUDGE = 4
    private const val MAX_PAYLOAD = 1 shl 20
    private const val BYTE_MASK = 0xff

    val PONG_FRAME: ByteArray = byteArrayOf(PONG.toByte(), 0, 0, 0, 0)

    /** One frame as (type, payload), or null at a clean end of stream. */
    fun read(source: BufferedSource): Pair<Int, ByteArray>? {
        if (source.exhausted()) return null
        val type = source.readByte().toInt() and BYTE_MASK
        val length = source.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("control frame too large: $length")
        return type to (if (length == 0) ByteArray(0) else source.readByteArray(length.toLong()))
    }
}
```

[x] **Action 4.1.5** — create `arctunnel/ArcTunnelCsr.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** PKCS#10 CSRs signed through a [ContentSigner] over a keystore key: the private bytes never leave the keystore. */
object ArcTunnelCsr {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Identity CSR: the server ignores the subject and assigns the name. */
    fun identityCsr(
        key: PrivateKey,
        pub: PublicKey,
    ): String = build(key, pub, "phone", null)

    /** TLS CSR: CN and one DNS SAN both equal to `<name>.<tunnel-domain>` (PROTOCOL.md §3). */
    fun tlsCsr(
        key: PrivateKey,
        pub: PublicKey,
        fqdn: String,
    ): String = build(key, pub, fqdn, fqdn)

    private fun build(
        key: PrivateKey,
        pub: PublicKey,
        cn: String,
        sanDns: String?,
    ): String {
        val algId: AlgorithmIdentifier = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WITHECDSA")
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(key) }
        val buffer = ByteArrayOutputStream()
        val signer =
            object : ContentSigner {
                override fun getAlgorithmIdentifier(): AlgorithmIdentifier = algId

                override fun getOutputStream(): OutputStream = buffer

                override fun getSignature(): ByteArray {
                    signature.update(buffer.toByteArray())
                    return signature.sign()
                }
            }
        val builder = JcaPKCS10CertificationRequestBuilder(X500Name("CN=$cn"), pub)
        if (sanDns != null) {
            val extensions = ExtensionsGenerator()
            extensions.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, sanDns)),
            )
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
        }
        return ArcTunnelPem.encode("CERTIFICATE REQUEST", builder.build(signer).encoded)
    }
}
```

[x] **Action 4.1.6** — create `arctunnel/ArcTunnelSessions.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The sockets opened through one control stream; [closeAll] ends them when the stream ends. */
class ArcTunnelSessions(
    private val onCloseError: (IOException) -> Unit,
) {
    private val open = ConcurrentHashMap.newKeySet<Closeable>()

    @Volatile
    private var shut = false

    fun size(): Int = open.size

    /** Registers [c]; false (and [c] closed) when the registry is already shut. */
    fun add(c: Closeable): Boolean {
        open.add(c)
        if (!shut) return true
        if (open.remove(c)) close(c)
        return false
    }

    fun release(c: Closeable) {
        open.remove(c)
    }

    fun closeAll() {
        shut = true
        for (c in open.toList()) if (open.remove(c)) close(c)
    }

    private fun close(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
            onCloseError(e)
        }
    }
}
```

Definition of Done:
- [x] The six files as written.

### [x] Task 4.2 — Keystore interface and Android implementation

[x] **Action 4.2.1** — create `arctunnel/ArcTunnelKeyStore.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.security.PrivateKey
import java.security.PublicKey

/** Non-exportable EC P-256 keys of the tunnel client; only CSRs and attestation chains ever leave the store. */
interface ArcTunnelKeyStore {
    /** Generates a P-256 key under [alias]; with a [challenge] the key is hardware-attested over it. */
    fun generateEcKey(
        alias: String,
        challenge: ByteArray? = null,
    )

    fun hasKey(alias: String): Boolean

    fun privateKey(alias: String): PrivateKey

    fun publicKey(alias: String): PublicKey

    fun deleteKey(alias: String)

    fun deleteAll(prefix: String)

    /** PEM bundle of the attestation chain (leaf first) the server's seven-point verifier checks. */
    fun attestationChainPem(alias: String): String
}
```

[x] **Action 4.2.2** — create `arctunnel/AndroidArcTunnelKeyStore.kt` (the TLS key MUST authorize `DIGEST_NONE`: Conscrypt signs the TLS transcript pre-hashed):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

class AndroidArcTunnelKeyStore
    @Inject
    constructor() : ArcTunnelKeyStore {
        private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

        override fun generateEcKey(
            alias: String,
            challenge: ByteArray?,
        ) {
            keyStore().deleteEntry(alias)
            val builder =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
            if (challenge != null) builder.setAttestationChallenge(challenge)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(builder.build())
                generateKeyPair()
            }
        }

        override fun hasKey(alias: String): Boolean = keyStore().containsAlias(alias)

        override fun privateKey(alias: String): PrivateKey =
            checkNotNull(keyStore().getKey(alias, null) as? PrivateKey) { "no private key under alias $alias" }

        override fun publicKey(alias: String): PublicKey =
            checkNotNull(keyStore().getCertificate(alias) as? X509Certificate) { "no key under alias $alias" }.publicKey

        override fun deleteKey(alias: String) = keyStore().deleteEntry(alias)

        override fun deleteAll(prefix: String) {
            val ks = keyStore()
            ks
                .aliases()
                .toList()
                .filter { it.startsWith(prefix) }
                .forEach { ks.deleteEntry(it) }
        }

        override fun attestationChainPem(alias: String): String {
            val chain = checkNotNull(keyStore().getCertificateChain(alias)) { "no attestation chain for $alias" }
            return chain.joinToString("") { ArcTunnelPem.encode("CERTIFICATE", it.encoded) }
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
        }
    }
```

[x] **Action 4.2.3** — modify `A/di/AppModule.kt`: in `ServiceModule` (service bindings live there) add the binding and the two imports:

```kotlin
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.AndroidArcTunnelKeyStore
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelKeyStore

    /** Binds the AndroidKeyStore-backed key store of the free E2E tunnel client. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelKeyStore(impl: AndroidArcTunnelKeyStore): ArcTunnelKeyStore
```

Definition of Done:
- [x] Interface, Android implementation and Hilt binding as written.

### [x] Task 4.3 — Dial-back

[x] **Action 4.3.1** — create `arctunnel/ArcTunnelDialBack.kt` (PROTOCOL.md §4; ONE deadline over connect + TLS + upgrade, enforced by a sibling coroutine closing the socket since blocking socket I/O is not cancellable; the raw socket is registered in the stream's [ArcTunnelSessions] so a stream end closes an in-flight dial-back too; hostname verification is done by the JSSE itself via `endpointIdentificationAlgorithm`, which works on the JVM and on Android):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** [allowLocalTargets]: only the debug edge override may point a dial-back at a loopback/link-local address. */
class ArcTunnelDialBack(
    private val controlHost: String,
    private val mtls: SSLContext,
    private val serverTls: SSLContext,
    private val allowLocalTargets: Boolean = false,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /**
     * [socket] is what the splice reads/writes (the TLS socket when `use_https`); [raw] is the registered
     * TCP socket.
     */
    class Conn(
        val socket: Socket,
        val input: InputStream,
        val raw: Socket,
    )

    /** One OPEN's dial-back target. */
    data class Target(
        val token: String,
        val addr: String,
        val useHttps: Boolean,
        val useMtls: Boolean,
    )

    /**
     * Opens the dial-back; the raw socket is tracked by [sessions] for the stream's lifetime. Throws [IOException]
     * (refused, closed, timed out) or [IllegalArgumentException] (bad address).
     */
    suspend fun open(
        target: Target,
        sessions: ArcTunnelSessions,
    ): Conn =
        coroutineScope {
            val sep = target.addr.lastIndexOf(':')
            require(sep > 0) { "bad dial-back address ${target.addr}" }
            val hostPart = target.addr.substring(0, sep)
            val host = hostPart.removePrefix("[").removeSuffix("]")
            val port =
                requireNotNull(target.addr.substring(sep + 1).toIntOrNull()) { "bad dial-back port in ${target.addr}" }
            val raw = Socket()
            val state = AtomicInteger(OPENING)
            if (!sessions.add(raw)) throw IOException("control stream ended before the dial-back to ${target.addr}")
            val deadline =
                launch {
                    delay(timeoutMs)
                    if (state.compareAndSet(OPENING, TIMED_OUT)) ArcTunnelSockets.closeQuietly(raw)
                }
            val result = runCatching { withContext(Dispatchers.IO) { upgrade(raw, host, port, target) } }
            deadline.cancel()
            val conn = runCatching { finish(state, raw, target.addr, result) }
            conn.onFailure { sessions.release(raw) }
            conn.getOrThrow()
        }

    private fun finish(
        state: AtomicInteger,
        raw: Socket,
        addr: String,
        result: Result<Conn>,
    ): Conn {
        val opened = result.isSuccess && state.compareAndSet(OPENING, OPENED)
        if (opened) return result.getOrThrow()
        ArcTunnelSockets.closeQuietly(raw)
        val cause = result.exceptionOrNull()
        if (state.get() == TIMED_OUT) {
            throw SocketTimeoutException("dial-back to $addr took longer than ${timeoutMs}ms").apply {
                cause?.let { initCause(it) }
            }
        }
        throw cause ?: IOException("dial-back socket closed by its deadline")
    }

    private fun upgrade(
        raw: Socket,
        host: String,
        port: Int,
        target: Target,
    ): Conn {
        val address = InetAddress.getByName(host)
        require(allowLocalTargets || !isLocalTarget(address)) { "dial-back target $host is not allowed" }
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(address, port), timeoutMs.toInt())
        raw.soTimeout = timeoutMs.toInt()
        val socket = if (target.useHttps) tls(raw, port, if (target.useMtls) mtls else serverTls) else raw
        val request =
            "GET /api/v1/data HTTP/1.1\r\nHost: $controlHost\r\nConnection: Upgrade\r\n" +
                "Upgrade: tunneld-dial\r\nX-Dial-Token: ${target.token}\r\n\r\n"
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val input = BufferedInputStream(socket.getInputStream())
        val status = readStatus(input)
        if (status != SWITCHING_PROTOCOLS) throw IOException("dial-back refused: $status")
        socket.soTimeout = 0
        return Conn(socket, input, raw)
    }

    private fun tls(
        raw: Socket,
        port: Int,
        ctx: SSLContext,
    ): Socket {
        val socket = ctx.socketFactory.createSocket(raw, controlHost, port, true) as SSLSocket
        socket.sslParameters =
            socket.sslParameters.apply {
                serverNames = listOf(SNIHostName(controlHost))
                applicationProtocols = arrayOf("http/1.1")
                endpointIdentificationAlgorithm = "HTTPS"
            }
        socket.startHandshake()
        return socket
    }

    private fun readStatus(input: InputStream): Int {
        val statusLine = readLine(input)
        while (readLine(input).isNotEmpty()) {
            // drain the response headers
        }
        val parts = statusLine.split(' ')
        val status = if (parts.size >= 2 && parts[0].startsWith("HTTP/1.")) parts[1].toIntOrNull() else null
        return status ?: throw IOException("bad dial-back response: $statusLine")
    }

    /** A server must never make the phone connect to itself, the link or a multicast group. */
    private fun isLocalTarget(address: InetAddress): Boolean =
        listOf(
            address.isLoopbackAddress,
            address.isAnyLocalAddress,
            address.isLinkLocalAddress,
            address.isMulticastAddress,
        ).any { it }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var b = input.read()
        while (b >= 0 && b != '\n'.code) {
            if (sb.length >= MAX_LINE) throw IOException("dial-back response line too long")
            sb.append(b.toChar())
            b = input.read()
        }
        if (b < 0) throw IOException("dial-back connection closed during the upgrade")
        return sb.toString().removeSuffix("\r")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_LINE = 8192
        const val SWITCHING_PROTOCOLS = 101
        const val OPENING = 0
        const val OPENED = 1
        const val TIMED_OUT = 2
    }
}
```

Context: `finish` subsumes the reference client's `FAILED` state: a failure before the deadline fires leaves `state == OPENING` and the original cause is rethrown; a failure caused by the deadline's close becomes a `SocketTimeoutException`; a success that lost the race to the deadline is closed and reported as timed out. A failed open leaves nothing in [sessions]; a successful one leaves `raw` registered until `ArcTunnelOpenHandler` releases it.

Definition of Done:
- [x] `ArcTunnelDialBack` as written.

### [x] Task 4.4 — Identity + network layer (mutually dependent, one action), HTTP helper

[x] **Action 4.4.1** — create `arctunnel/ArcTunnelIdentity.kt` AND `arctunnel/ArcTunnelNet.kt` together (each references the other):

`arctunnel/ArcTunnelIdentity.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelTlsMaterial
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Atomic {alias, chain, key} snapshot of the live identity, read by the mTLS key manager per handshake. */
class ArcTunnelCred(
    val alias: String,
    val chain: List<X509Certificate>,
    val key: PrivateKey,
)

/**
 * An enrolled tunnel identity: the server-assigned name, the rotating identity key/cert, and the TLS key
 * (fixed per name) with its issued public chain. State is guarded by `lock`; issuances are serialized by
 * [issueMutex].
 */
class ArcTunnelIdentity(
    val net: ArcTunnelNet,
    val keys: ArcTunnelKeyStore,
    val name: String,
    val hosts: ArcTunnelHosts,
    initial: State,
) {
    data class State(
        val identityAlias: String,
        val prevIdentityAlias: String,
        val tlsAlias: String,
        val identityChain: List<X509Certificate>,
        val publicChain: List<X509Certificate>,
        val caId: String,
        val generation: Int,
    )

    private val lock = Any()
    val issueMutex = Mutex()
    private var state = initial

    fun state(): State = synchronized(lock) { state }

    fun identityCred(): ArcTunnelCred =
        synchronized(lock) {
            ArcTunnelCred(state.identityAlias, state.identityChain, keys.privateKey(state.identityAlias))
        }

    fun tlsMaterial(): TunnelTlsMaterial =
        synchronized(lock) {
            check(keys.hasKey(state.tlsAlias)) { "the TLS key of $name is missing from the keystore; get a new name" }
            TunnelTlsMaterial(keys.privateKey(state.tlsAlias), state.publicChain)
        }

    fun publicLeaf(): X509Certificate? = synchronized(lock) { state.publicChain.firstOrNull() }

    fun fqdn(): String = hosts.fqdn(name)

    fun publicUrl(): String = hosts.publicUrl(name)

    fun mtlsClient(): OkHttpClient = net.mtlsClient(this)

    /**
     * Installs a rotated identity + renewed public chain; returns the identity alias two generations back
     * (to delete).
     */
    fun install(
        nextIdentityAlias: String,
        identityChain: List<X509Certificate>,
        publicChain: List<X509Certificate>,
        caId: String,
        generation: Int,
    ): String =
        synchronized(lock) {
            val stale = state.prevIdentityAlias
            state =
                state.copy(
                    prevIdentityAlias = state.identityAlias,
                    identityAlias = nextIdentityAlias,
                    identityChain = identityChain,
                    publicChain = publicChain,
                    caId = caId,
                    generation = generation,
                )
            stale
        }
}
```

`arctunnel/ArcTunnelNet.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP clients and TLS contexts of the tunnel client. With [edgeOverride] every service host resolves to the
 * override address and carries its port in the URL (SNI and `Host` stay the real names); [trustedCaPem] (empty
 * = system CAs) is the trust anchor set. One shared [OkHttpClient] backs every derived client (one pool);
 * [close] evicts its idle connections (the dispatcher threads idle out by themselves), so a shared instance
 * stays usable afterwards.
 */
class ArcTunnelNet(
    trustedCaPem: String?,
    private val edgeOverride: ArcTunnelEdgeOverride?,
) {
    private val trust: X509TrustManager = trustManager(trustedCaPem?.takeIf { it.isNotBlank() }?.let { loadRoots(it) })
    private val dns: Dns =
        if (edgeOverride == null) Dns.SYSTEM else Dns { listOf(InetAddress.getByName(edgeOverride.host)) }
    private val base: OkHttpClient = OkHttpClient.Builder().dns(dns).build()

    fun url(
        host: String,
        path: String,
    ): String = if (edgeOverride == null) "https://$host$path" else "https://$host:${edgeOverride.port}$path"

    fun serverClient(): OkHttpClient = client(sslContext(null))

    fun mtlsClient(identity: ArcTunnelIdentity): OkHttpClient = client(mtlsContext(identity))

    /** The control-stream client: no read timeout (the server only PINGs every ≤ 30 s; the watchdog guards silence). */
    fun controlClient(identity: ArcTunnelIdentity): OkHttpClient =
        mtlsClient(identity).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Dial-backs to loopback/link-local targets are allowed only with the debug edge override. */
    fun dialBack(
        controlHost: String,
        identity: ArcTunnelIdentity,
    ): ArcTunnelDialBack = ArcTunnelDialBack(controlHost, mtlsContext(identity), sslContext(null), edgeOverride != null)

    /** Evicts the idle connections of the shared pool; the instance stays usable. */
    fun close() {
        base.connectionPool.evictAll()
    }

    private fun mtlsContext(identity: ArcTunnelIdentity): SSLContext = sslContext(DynamicIdentityKeyManager(identity))

    private fun sslContext(km: X509ExtendedKeyManager?): SSLContext =
        SSLContext.getInstance("TLS").apply { init(km?.let { arrayOf(it) }, arrayOf(trust), SecureRandom()) }

    private fun client(ctx: SSLContext): OkHttpClient =
        base
            .newBuilder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .build()

    private fun loadRoots(pem: String): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        CertificateFactory
            .getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            .forEachIndexed { i, c -> ks.setCertificateEntry("root$i", c as X509Certificate) }
        return ks
    }

    private fun trustManager(roots: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(roots)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Resolves the CURRENT identity cert+key atomically per handshake, so a renewal mid-handshake stays consistent. */
    class DynamicIdentityKeyManager(
        private val identity: ArcTunnelIdentity,
    ) : X509ExtendedKeyManager() {
        private val selected = ThreadLocal<ArcTunnelCred>()

        private fun pick(): String = identity.identityCred().also { selected.set(it) }.alias

        private fun cred(): ArcTunnelCred = selected.get() ?: identity.identityCred().also { selected.set(it) }

        override fun getClientAliases(
            k: String?,
            i: Array<Principal>?,
        ): Array<String> = arrayOf(pick())

        override fun chooseClientAlias(
            k: Array<out String>?,
            i: Array<Principal>?,
            s: Socket?,
        ): String = pick()

        override fun chooseEngineClientAlias(
            k: Array<out String>?,
            i: Array<Principal>?,
            e: SSLEngine?,
        ): String = pick()

        override fun getServerAliases(
            k: String?,
            i: Array<Principal>?,
        ): Array<String>? = null

        override fun chooseServerAlias(
            k: String?,
            i: Array<Principal>?,
            s: Socket?,
        ): String? = null

        override fun chooseEngineServerAlias(
            k: String?,
            i: Array<Principal>?,
            e: SSLEngine?,
        ): String? = null

        override fun getCertificateChain(a: String?): Array<X509Certificate> = cred().chain.toTypedArray()

        override fun getPrivateKey(a: String?) = cred().key
    }
}
```

Context: OkHttp applies its read timeout to an HTTP/2 stream only once the request body is closed or finished (`Http2Stream.doReadTimeout` in 5.5.0); the duplex control body stays open, so the default would not fire — `controlClient` still sets it to 0 so the behaviour does not depend on that detail.

[x] **Action 4.4.2** — create `arctunnel/ArcTunnelHttp.kt` (`/issue` allows 400 s per PROTOCOL.md §3; `/enroll` 300 s as the reference client's `Enroll.kt` does; `callTimeout` bounds the whole exchange; every call is cancelled with its coroutine):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** JSON GET/POST without blocking: calls are enqueued and cancelled together with the calling coroutine. */
object ArcTunnelHttp {
    private const val TAG = "MCP:ArcTunnelHttp"
    private const val ENROLL_TIMEOUT_S = 300L
    private const val ISSUE_TIMEOUT_S = 400L
    private const val MALFORMED = "malformed_response"
    private val JSON = "application/json".toMediaType()

    suspend fun <T> get(
        serializer: KSerializer<T>,
        client: OkHttpClient,
        url: String,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return decode(serializer, execute(client, request))
    }

    suspend fun <T> post(
        serializer: KSerializer<T>,
        client: OkHttpClient,
        url: String,
        body: String,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()
        return decode(serializer, execute(client, request))
    }

    fun enrollClient(client: OkHttpClient): OkHttpClient =
        client
            .newBuilder()
            .readTimeout(ENROLL_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(ENROLL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    fun issueClient(client: OkHttpClient): OkHttpClient =
        client
            .newBuilder()
            .readTimeout(ISSUE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(ISSUE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** Executes and returns the body of a 200; any other status is thrown as [ArcTunnelApiException]. */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
    ): String {
        val (code, body) = await(client.newCall(request))
        if (code != HttpURLConnection.HTTP_OK) fail(code, body)
        return body
    }

    /**
     * Suspends until [call] answered AND its body was read (both on OkHttp's thread, so `cancel()` aborts a
     * stalled body read too); cancelling the coroutine cancels the call. Returns the status and the body.
     */
    suspend fun await(call: Call): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resumeWith(runCatching { response.use { it.code to it.body.string() } })
                    }
                },
            )
        }

    fun fail(
        status: Int,
        body: String,
    ): Nothing {
        val error =
            try {
                ArcTunnelJson.decodeFromString(ArcTunnelApiErrorBody.serializer(), body)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "undecodable error body (status $status): ${e.javaClass.simpleName}")
                ArcTunnelApiErrorBody()
            }
        val reason = error.reason.ifEmpty { "http_$status" }
        throw ArcTunnelApiException(reason, error.retryable, error.retryAfterSeconds, status)
    }

    /** A malformed 200 body is reported WITHOUT its content (it may carry a nonce or a chain). */
    private fun <T> decode(
        serializer: KSerializer<T>,
        body: String,
    ): T =
        try {
            ArcTunnelJson.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "malformed answer from the tunnel service: ${e.javaClass.simpleName}")
            throw ArcTunnelApiException(MALFORMED, false, 0, HttpURLConnection.HTTP_OK)
        }
}
```

Definition of Done:
- [x] `ArcTunnelIdentity`, `ArcTunnelNet` and `ArcTunnelHttp` as written.

### [x] Task 4.5 — Retries and enroller

[x] **Action 4.5.1** — create `arctunnel/ArcTunnelEnrollRetry.kt` (PROTOCOL.md §2: ≤ 3 retries; refusal → `retry_after_seconds` ≥ 1 s; network → 2 s; key generation (`ProviderException`) → 3 s over the SAME nonce; non-retryable stops):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay
import java.io.IOException
import java.security.ProviderException

object ArcTunnelEnrollRetry {
    const val RETRIES = 3
    const val REFUSAL_WAIT_MS = 1_000L
    const val NETWORK_WAIT_MS = 2_000L
    const val KEYGEN_WAIT_MS = 3_000L
    private const val MS_PER_S = 1_000L

    /** The wait before the next attempt, or null when [t] is not retryable. */
    fun waitMs(t: Throwable): Long? =
        when (t) {
            is ArcTunnelApiException -> if (t.retryable) maxOf(t.retryAfterSec * MS_PER_S, REFUSAL_WAIT_MS) else null
            is IOException -> NETWORK_WAIT_MS
            is ProviderException -> KEYGEN_WAIT_MS
            else -> null
        }

    suspend fun <T> run(
        freshNonce: suspend () -> String,
        enroll: suspend (String) -> T,
        onRetry: (Int, Long, Throwable) -> Unit,
    ): T {
        var nonce: String? = null
        var retry = 0
        while (true) {
            val attempt = runCatching { enroll(nonce ?: freshNonce().also { nonce = it }) }
            val failure = attempt.exceptionOrNull() ?: return attempt.getOrThrow()
            val wait = waitMs(failure)
            if (wait == null || retry >= RETRIES) throw failure
            retry++
            onRetry(retry, wait, failure)
            delay(wait)
            if (failure !is ProviderException) nonce = null
        }
    }
}
```

Context: `runCatching` also captures `CancellationException`; `waitMs` returns null for it, so it is rethrown at once.

[x] **Action 4.5.2** — create `arctunnel/ArcTunnelIssueRetry.kt` (PROTOCOL.md §3: ≤ 3 retries, each over a FRESH nonce, waits `retry_after_seconds` else 5/10/15 s; a `ProviderException` re-generates over the SAME nonce ≤ 5 times, 2 s apart, without spending a retry):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay
import java.io.IOException
import java.security.ProviderException

object ArcTunnelIssueRetry {
    const val RETRIES = 3
    const val KEYGEN_RETRIES = 5
    const val KEYGEN_WAIT_MS = 2_000L
    private const val STEP_WAIT_MS = 5_000L
    private const val MS_PER_S = 1_000L

    fun waitMs(
        t: Throwable,
        retry: Int,
    ): Long? =
        when (t) {
            is ArcTunnelApiException -> apiWaitMs(t, retry)
            is IOException -> (retry + 1) * STEP_WAIT_MS
            else -> null
        }

    private fun apiWaitMs(
        t: ArcTunnelApiException,
        retry: Int,
    ): Long? =
        when {
            !t.retryable -> null
            t.retryAfterSec > 0 -> t.retryAfterSec * MS_PER_S
            else -> (retry + 1) * STEP_WAIT_MS
        }

    suspend fun run(
        nonce: String?,
        issue: suspend (String) -> Unit,
        freshNonce: suspend () -> String,
        onRetry: (Int, Long, Throwable) -> Unit,
    ) {
        var current: String? = nonce
        var retry = 0
        var keygen = 0
        while (true) {
            val attempt = runCatching { issue(current ?: freshNonce().also { current = it }) }
            val failure = attempt.exceptionOrNull() ?: return
            if (failure is ProviderException && keygen < KEYGEN_RETRIES) {
                keygen++
                onRetry(keygen, KEYGEN_WAIT_MS, failure)
                delay(KEYGEN_WAIT_MS)
            } else {
                val wait = waitMs(failure, retry)
                if (wait == null || retry >= RETRIES) throw failure
                onRetry(retry + 1, wait, failure)
                delay(wait)
                current = null
                retry++
            }
        }
    }
}
```

[x] **Action 4.5.3** — create `arctunnel/ArcTunnelEnroller.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Phase 1 enrollment, Phase 2 issuance and every renewal (PROTOCOL.md §2–§3) with one TLS key per name. */
class ArcTunnelEnroller(
    private val keys: ArcTunnelKeyStore,
) {
    /**
     * Phase 1: attested identity key + bootstrap identity cert; returns the identity (no public cert yet) +
     * issue nonce.
     */
    suspend fun enroll(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
    ): Pair<ArcTunnelIdentity, String> =
        ArcTunnelEnrollRetry.run(
            freshNonce = { freshNonce(net, hosts) },
            enroll = { nonce -> withContext(Dispatchers.IO) { phase1Once(net, hosts, nonce) } },
        ) { n, wait, t -> Log.w(TAG, "enrollment failed; retry $n in ${wait}ms", t) }

    /**
     * Phase 2 / renewal: [nonce] is the Phase-1 issue nonce or the RENEW_NUDGE nonce (null = fetch a fresh one).
     * Serialized per identity; retries per PROTOCOL.md §3.
     */
    suspend fun issue(
        identity: ArcTunnelIdentity,
        nonce: String?,
    ) {
        identity.issueMutex.withLock {
            ArcTunnelIssueRetry.run(
                nonce = nonce,
                issue = { n -> withContext(Dispatchers.IO) { issueOnce(identity, n) } },
                freshNonce = { freshNonce(identity.net, identity.hosts) },
            ) { n, wait, t -> Log.w(TAG, "issuance failed; retry $n in ${wait}ms", t) }
        }
    }

    suspend fun freshNonce(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
    ): String =
        withContext(Dispatchers.IO) {
            val url = net.url(hosts.enrollHost, NONCE_PATH)
            ArcTunnelHttp.get(ArcTunnelNonceResponse.serializer(), net.serverClient(), url).nonce
        }

    private suspend fun phase1Once(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
        nonce: String,
    ): Pair<ArcTunnelIdentity, String> {
        val identityAlias = "${aliasPrefix(hosts)}identity-0"
        keys.generateEcKey(identityAlias, ArcTunnelPem.hexToBytes(nonce))
        val request =
            ArcTunnelEnrollRequest(
                nonce = nonce,
                attestationChain = keys.attestationChainPem(identityAlias),
                identityCsr = ArcTunnelCsr.identityCsr(keys.privateKey(identityAlias), keys.publicKey(identityAlias)),
            )
        val response =
            ArcTunnelHttp.post(
                ArcTunnelEnrollResponse.serializer(),
                ArcTunnelHttp.enrollClient(net.serverClient()),
                net.url(hosts.enrollHost, ENROLL_PATH),
                ArcTunnelJson.encodeToString(ArcTunnelEnrollRequest.serializer(), request),
            )
        val state =
            ArcTunnelIdentity.State(
                identityAlias = identityAlias,
                prevIdentityAlias = "",
                tlsAlias = tlsAlias(hosts),
                identityChain = ArcTunnelPem.parseChain(response.identityCert),
                publicChain = emptyList(),
                caId = "",
                generation = 0,
            )
        return ArcTunnelIdentity(net, keys, response.name, hosts, state) to response.issueNonce
    }

    /** One `/issue` attempt: fresh attested identity key, the FIXED TLS key (created on first use), then install. */
    private suspend fun issueOnce(
        identity: ArcTunnelIdentity,
        nonce: String,
    ) {
        val base = identity.state()
        val generation = base.generation + 1
        val nextIdentityAlias = "${aliasPrefix(identity.hosts)}identity-$generation"
        // The TLS key is created with the first issuance of a name and never again; a later loss means
        // the name is unusable (the UI offers a new name).
        if (base.generation == 0) {
            if (!keys.hasKey(base.tlsAlias)) keys.generateEcKey(base.tlsAlias)
        } else {
            check(keys.hasKey(base.tlsAlias)) { "the TLS key of ${identity.name} is missing; get a new name" }
        }
        keys.generateEcKey(nextIdentityAlias, ArcTunnelPem.hexToBytes(nonce))
        val installed = runCatching { postIssueAndInstall(identity, base, nonce, nextIdentityAlias, generation) }
        // Any failure before install (HTTP error, network, malformed response/chain) drops the staged key.
        val stale =
            installed.getOrElse {
                keys.deleteKey(nextIdentityAlias)
                throw it
            }
        if (stale.isNotEmpty() && stale != nextIdentityAlias) keys.deleteKey(stale)
    }

    private suspend fun postIssueAndInstall(
        identity: ArcTunnelIdentity,
        base: ArcTunnelIdentity.State,
        nonce: String,
        nextIdentityAlias: String,
        generation: Int,
    ): String {
        val identityKey = keys.privateKey(nextIdentityAlias)
        val tlsKey = keys.privateKey(base.tlsAlias)
        val request =
            ArcTunnelIssueRequest(
                nonce = nonce,
                attestationChain = keys.attestationChainPem(nextIdentityAlias),
                identityCsr = ArcTunnelCsr.identityCsr(identityKey, keys.publicKey(nextIdentityAlias)),
                tlsCsr = ArcTunnelCsr.tlsCsr(tlsKey, keys.publicKey(base.tlsAlias), identity.fqdn()),
            )
        val response =
            ArcTunnelHttp.post(
                ArcTunnelIssueResponse.serializer(),
                ArcTunnelHttp.issueClient(identity.mtlsClient()),
                identity.net.url(identity.hosts.controlHost, ISSUE_PATH),
                ArcTunnelJson.encodeToString(ArcTunnelIssueRequest.serializer(), request),
            )
        return identity.install(
            nextIdentityAlias,
            ArcTunnelPem.parseChain(response.identityCert),
            ArcTunnelPem.parseChain(response.publicCert),
            response.ca.ifEmpty { base.caId },
            generation,
        )
    }

    companion object {
        private const val TAG = "MCP:ArcTunnelEnroller"
        private const val NONCE_PATH = "/api/v1/enroll/nonce"
        private const val ENROLL_PATH = "/api/v1/enroll"
        private const val ISSUE_PATH = "/api/v1/issue"

        fun aliasPrefix(hosts: ArcTunnelHosts): String = "arc-tunnel-${hosts.envKey}-"

        fun tlsAlias(hosts: ArcTunnelHosts): String = "${aliasPrefix(hosts)}tls"
    }
}
```

Definition of Done:
- [x] Both retry objects and the enroller as written.

### [x] Task 4.6 — Identity store

[x] **Action 4.6.1** — create `arctunnel/ArcTunnelIdentityStore.kt` (one JSON file per environment under `filesDir/arc-tunnel/`, atomic rename on save; also the CT cursor, the flagged keys and the undismissed-alert key of D10; an unreadable file is logged and treated as absent):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class ArcTunnelSavedIdentity(
    val name: String,
    @SerialName("base_address") val baseAddress: String,
    @SerialName("identity_alias") val identityAlias: String,
    @SerialName("prev_identity_alias") val prevIdentityAlias: String = "",
    @SerialName("tls_alias") val tlsAlias: String,
    @SerialName("identity_chain") val identityChain: String,
    @SerialName("public_chain") val publicChain: String = "",
    val ca: String = "",
    val generation: Int = 0,
    @SerialName("ct_last_id") val ctLastId: String = "",
    @SerialName("ct_flagged") val ctFlagged: List<String> = emptyList(),
    @SerialName("ct_alert_pubkey") val ctAlertPubkey: String = "",
    @SerialName("ct_last_check_ms") val ctLastCheckMs: Long = 0,
)

class ArcTunnelIdentityStore(
    root: File,
    private val hosts: ArcTunnelHosts,
    private val keys: ArcTunnelKeyStore,
) {
    private val file = File(root, "${hosts.envKey}.json")

    @Synchronized
    fun save(identity: ArcTunnelIdentity) {
        val s = identity.state()
        val previous = load()
        write(
            ArcTunnelSavedIdentity(
                name = identity.name,
                baseAddress = hosts.baseAddress,
                identityAlias = s.identityAlias,
                prevIdentityAlias = s.prevIdentityAlias,
                tlsAlias = s.tlsAlias,
                identityChain = ArcTunnelPem.chainPem(s.identityChain),
                publicChain = ArcTunnelPem.chainPem(s.publicChain),
                ca = s.caId,
                generation = s.generation,
                ctLastId = previous?.ctLastId.orEmpty(),
                ctFlagged = previous?.ctFlagged.orEmpty(),
                ctAlertPubkey = previous?.ctAlertPubkey.orEmpty(),
                ctLastCheckMs = previous?.ctLastCheckMs ?: 0L,
            ),
        )
    }

    /**
     * Merges one CT check into the CURRENT record (a dismissal that landed meanwhile is kept): the cursor and
     * the check time replace, [newFlagged] keys add, a non-null [newAlertPubkey] replaces the alert.
     */
    @Synchronized
    fun mergeCtCheck(
        lastId: String,
        newFlagged: List<String>,
        newAlertPubkey: String?,
        checkedAtMs: Long,
    ) {
        val current = load() ?: return
        write(
            current.copy(
                ctLastId = lastId,
                ctFlagged = (current.ctFlagged + newFlagged).distinct(),
                ctAlertPubkey = newAlertPubkey ?: current.ctAlertPubkey,
                ctLastCheckMs = checkedAtMs,
            ),
        )
    }

    /** Clears the persisted undismissed alert (the user dismissed it). */
    @Synchronized
    fun clearCtAlert() {
        val current = load() ?: return
        write(current.copy(ctAlertPubkey = ""))
    }

    /** The saved identity, or null when there is none or the file cannot be read (logged). */
    @Synchronized
    fun load(): ArcTunnelSavedIdentity? {
        if (!file.exists()) return null
        return try {
            ArcTunnelJson.decodeFromString(ArcTunnelSavedIdentity.serializer(), file.readText())
        } catch (e: IOException) {
            Log.w(TAG, "unreadable identity file ${file.name}; treated as not enrolled", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "corrupt identity file ${file.name}; treated as not enrolled: ${e.javaClass.simpleName}")
            null
        }
    }

    /** True while [identity] is still the saved one (a forget in between makes a late renewal a no-op). */
    @Synchronized
    fun holds(identity: ArcTunnelIdentity): Boolean = load()?.name == identity.name

    /** Deletes the file and every key it references; returns whether anything was saved. */
    @Synchronized
    fun forget(): Boolean {
        val saved = load()
        if (saved == null && !file.exists()) return false
        listOf(saved?.identityAlias, saved?.prevIdentityAlias, saved?.tlsAlias)
            .filterNotNull()
            .filter { it.isNotEmpty() }
            .forEach { keys.deleteKey(it) }
        check(!file.exists() || file.delete()) { "could not delete ${file.path}" }
        return true
    }

    private fun write(saved: ArcTunnelSavedIdentity) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(ArcTunnelJson.encodeToString(ArcTunnelSavedIdentity.serializer(), saved))
        check(tmp.renameTo(file)) { "could not move ${tmp.path} into place" }
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelStore"
    }
}
```

Definition of Done:
- [x] Store as written (atomic write, tolerant load, CT fields incl. the check time).

### [x] Task 4.7 — Backoff, local connect, splice, watchdog, events, OPEN handler, control loop

[x] **Action 4.7.1** — create `arctunnel/ArcTunnelBackoff.kt` (D9):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/** Control reconnect delays: 1, 2, 4, 8, 16 s, then 16 s; [reset] after a bound control stream. */
class ArcTunnelBackoff {
    private var current = INITIAL_MS

    /** Returns the delay to wait before the next attempt and advances the schedule. */
    fun next(): Long {
        val delay = current
        current = (current * 2).coerceAtMost(MAX_MS)
        return delay
    }

    fun reset() {
        current = INITIAL_MS
    }

    companion object {
        const val INITIAL_MS = 1_000L
        const val MAX_MS = 16_000L
    }
}
```

[x] **Action 4.7.2** — create `arctunnel/ArcTunnelLocalConnect.kt` (D6: a listener restarting for a certificate swap is retried for up to 10 s):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import kotlin.coroutines.coroutineContext

object ArcTunnelLocalConnect {
    const val WINDOW_MS = 10_000L
    private const val RETRY_MS = 50L
    private const val NANOS_PER_MS = 1_000_000L

    /** Connects to the loopback listener, retrying a refused connect for [windowMs]; never leaks a socket. */
    suspend fun open(
        port: Int,
        windowMs: Long = WINDOW_MS,
    ): Socket {
        val deadline = System.nanoTime() + windowMs * NANOS_PER_MS
        while (true) {
            // The loopback connect is instantaneous and runs non-cancellable so a cancellation can never
            // discard a connected socket; cancellation is honoured right after it instead.
            val attempt =
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Socket(InetAddress.getLoopbackAddress(), port).apply { configure() } }
                }
            val socket = attempt.getOrNull()
            if (socket != null) {
                closeIfCancelled(socket)
                return socket
            }
            val failure = requireNotNull(attempt.exceptionOrNull())
            if (failure !is ConnectException || System.nanoTime() >= deadline) throw failure
            delay(RETRY_MS)
        }
    }

    /** A failed option set must not leak the connected socket. */
    private fun Socket.configure() {
        try {
            tcpNoDelay = true
        } catch (e: SocketException) {
            ArcTunnelSockets.closeQuietly(this)
            throw e
        }
    }

    private suspend fun closeIfCancelled(socket: Socket) {
        if (!coroutineContext.isActive) ArcTunnelSockets.closeQuietly(socket)
        coroutineContext.ensureActive()
    }
}
```

[x] **Action 4.7.3** — create `arctunnel/ArcTunnelSplice.kt` (16 KiB buffers; either side ending closes both; two `Dispatchers.IO` copies under one `coroutineScope`):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object ArcTunnelSplice {
    private const val TAG = "MCP:ArcTunnelSplice"
    private const val BUFFER = 16384

    /**
     * Copies both directions until either side ends, then closes both sockets; returns when both copies ended.
     * The wait is a cancellable join: a cancellation closes both sockets, which unblocks the two copies.
     */
    suspend fun run(
        local: Socket,
        remote: ArcTunnelDialBack.Conn,
    ) = coroutineScope {
        val closed = AtomicBoolean(false)
        val closeBoth = {
            if (closed.compareAndSet(false, true)) {
                ArcTunnelSockets.closeQuietly(local)
                ArcTunnelSockets.closeQuietly(remote.socket)
            }
        }
        val up =
            launch(Dispatchers.IO) {
                copy({ local.getInputStream() }, { remote.socket.getOutputStream() }, "upstream")
                closeBoth()
            }
        val down =
            launch(Dispatchers.IO) {
                copy({ remote.input }, { local.getOutputStream() }, "downstream")
                closeBoth()
            }
        try {
            joinAll(up, down)
        } finally {
            closeBoth()
        }
    }

    private fun copy(
        source: () -> InputStream,
        sink: () -> OutputStream,
        direction: String,
    ) {
        try {
            val src = source()
            val dst = sink()
            val buf = ByteArray(BUFFER)
            var n = src.read(buf)
            while (n >= 0) {
                dst.write(buf, 0, n)
                dst.flush()
                n = src.read(buf)
            }
        } catch (e: IOException) {
            Log.d(TAG, "$direction copy ended", e)
        }
    }
}
```

[x] **Action 4.7.4** — create `arctunnel/ArcTunnelWatchdog.kt` (PROTOCOL.md §3: 90 s without ANY control frame → drop the CONNECTION; the clock is injectable for tests):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.Call
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ArcTunnelWatchdog {
    private const val TAG = "MCP:ArcTunnelWatchdog"
    const val POLL_MS = 5_000L
    val SILENCE_NANOS: Long = TimeUnit.SECONDS.toNanos(90)

    /** Polls until [call] is cancelled; on 90 s of silence sets [silent] and cancels the call. */
    suspend fun watch(
        call: Call,
        lastFrameAt: AtomicLong,
        silent: AtomicBoolean,
        now: () -> Long = System::nanoTime,
    ) {
        while (!call.isCanceled()) {
            delay(POLL_MS)
            if (now() - lastFrameAt.get() > SILENCE_NANOS) {
                Log.w(TAG, "no control frame for 90s; dropping the connection")
                silent.set(true)
                call.cancel()
            }
        }
    }
}
```

[x] **Action 4.7.5** — create `arctunnel/ArcTunnelControlEvents.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/** Callbacks of [ArcTunnelControl]; invoked on the control loop's coroutines (never the main thread). */
interface ArcTunnelControlEvents {
    fun onBound(node: String)

    fun onUnbound()

    /** One renewal at a time; a nudge arriving while one runs is ignored. */
    suspend fun onRenewNudge(nonce: String)

    fun onDialBackFailed(message: String)
}
```

[x] **Action 4.7.6** — create `arctunnel/ArcTunnelOpenHandler.kt` (one OPEN: dial back FIRST, then connect locally, then splice — D9):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ArcTunnelOpenHandler(
    private val dialBack: ArcTunnelDialBack,
    private val localPort: () -> Int,
    private val events: ArcTunnelControlEvents,
    private val localConnectWindowMs: Long = ArcTunnelLocalConnect.WINDOW_MS,
) {
    private val splices = AtomicInteger()

    /** Open dial-back splices right now (the idle signal of the certificate swap). */
    fun activeSplices(): Int = splices.get()

    /** Dial back FIRST, then connect locally, then splice; every registered socket is released at the end. */
    suspend fun handle(
        open: ArcTunnelOpenPayload,
        sessions: ArcTunnelSessions,
    ) {
        if (!open.isValid()) {
            Log.w(TAG, "invalid OPEN ignored: addr=${open.addr} use_https=${open.useHttps} use_mtls=${open.useMtls}")
            return
        }
        val remote = openRemote(open, sessions) ?: return
        var spliced = false
        try {
            val local = if (sessions.add(remote.socket)) openLocal(open.addr, remote, sessions) else null
            if (local != null) {
                spliced = true
                splice(local, remote, sessions)
            }
        } finally {
            // A cancellation before the splice (e.g. inside the local-connect window) must not leave the
            // dial-back open; a finished splice closed both sides itself.
            if (!spliced) closeRemote(remote)
            sessions.release(remote.socket)
            sessions.release(remote.raw)
        }
    }

    private suspend fun splice(
        local: Socket,
        remote: ArcTunnelDialBack.Conn,
        sessions: ArcTunnelSessions,
    ) {
        splices.incrementAndGet()
        try {
            ArcTunnelSplice.run(local, remote)
        } finally {
            splices.decrementAndGet()
            sessions.release(local)
        }
    }

    private suspend fun openRemote(
        open: ArcTunnelOpenPayload,
        sessions: ArcTunnelSessions,
    ): ArcTunnelDialBack.Conn? =
        try {
            dialBack.open(ArcTunnelDialBack.Target(open.token, open.addr, open.useHttps, open.useMtls), sessions)
        } catch (e: IOException) {
            events.onDialBackFailed("dial-back to ${open.addr} failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            events.onDialBackFailed("dial-back to ${open.addr} refused: ${e.message}")
            null
        }

    /** Null when the local connect failed or the registry was shut meanwhile; the remote sockets are closed then. */
    private suspend fun openLocal(
        addr: String,
        remote: ArcTunnelDialBack.Conn,
        sessions: ArcTunnelSessions,
    ): Socket? {
        val local =
            try {
                ArcTunnelLocalConnect.open(localPort(), localConnectWindowMs)
            } catch (e: IOException) {
                events.onDialBackFailed("local listener unreachable for a dial-back to $addr: ${e.message}")
                null
            }
        val added = local != null && sessions.add(local)
        if (!added) {
            closeRemote(remote)
            local?.let { ArcTunnelSockets.closeQuietly(it) }
        }
        return if (added) local else null
    }

    private fun closeRemote(remote: ArcTunnelDialBack.Conn) {
        ArcTunnelSockets.closeQuietly(remote.socket)
        ArcTunnelSockets.closeQuietly(remote.raw)
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelOpen"
    }
}
```

[x] **Action 4.7.7** — create `arctunnel/ArcTunnelControl.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The outbound mTLS HTTP/2 control connection (PROTOCOL.md §3): one duplex `POST /api/v1/control`, PING→PONG,
 * OPEN → [ArcTunnelOpenHandler], RENEW_NUDGE → [ArcTunnelControlEvents.onRenewNudge] (single flight). A torn
 * stream reconnects with [ArcTunnelBackoff]. The in-flight call is cancelled by [stop] and, through a sibling
 * "canceller" coroutine, by the cancellation of the scope running [run], so a blocked read never outlives
 * either. Children (OPEN handlers, renewals, the watchdog) run under a supervisor: one failing child is
 * reported to the scope's handler, the loop goes on.
 */
class ArcTunnelControl(
    client: OkHttpClient,
    private val net: ArcTunnelNet,
    private val controlHost: String,
    private val opens: ArcTunnelOpenHandler,
    private val events: ArcTunnelControlEvents,
) {
    private val running = AtomicBoolean(true)
    private val current = AtomicReference<Call?>(null)
    private val renewalInFlight = AtomicBoolean(false)
    private val node = AtomicReference("unknown")
    private val client =
        client
            .newBuilder()
            .addNetworkInterceptor { chain ->
                chain
                    .connection()
                    ?.route()
                    ?.socketAddress
                    ?.address
                    ?.hostAddress
                    ?.let { node.set(it) }
                chain.proceed(chain.request())
            }.build()

    fun stop() {
        running.set(false)
        current.get()?.cancel()
    }

    /** Serves control streams until [stop]; OPEN handlers, renewals and the watchdog run as children of this scope. */
    suspend fun run() =
        supervisorScope {
            val backoff = ArcTunnelBackoff()
            while (running.get() && isActive) {
                val wasBound = AtomicBoolean(false)
                try {
                    withContext(Dispatchers.IO) { serveControlOnce(this@supervisorScope, wasBound) }
                } catch (e: IOException) {
                    Log.w(TAG, "control connection ended", e)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "control stream torn down on a malformed frame", e)
                }
                if (!running.get() || !isActive) break
                if (wasBound.get()) backoff.reset()
                events.onUnbound()
                delay(backoff.next())
            }
        }

    /**
     * One control stream; [wasBound] is set as soon as the stream is bound, so an abrupt end still resets
     * the backoff.
     */
    private fun serveControlOnce(
        scope: CoroutineScope,
        wasBound: AtomicBoolean,
    ) {
        var sink: BufferedSink? = null
        val request =
            Request
                .Builder()
                .url(net.url(controlHost, CONTROL_PATH))
                .post(duplexBody { sink = it })
                .build()
        val call = client.newCall(request)
        current.set(call)
        if (!running.get()) call.cancel()
        // A cancelled scope (provider stop) cancels the blocking execute/read through the call: the sibling
        // below is cancelled with the scope and its finally runs on the way out. UNDISPATCHED: it reaches
        // awaitCancellation() before this function continues, so an already-cancelled scope still cancels.
        val canceller =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        val sessions = ArcTunnelSessions { Log.d(TAG, "dial-back session close failed", it) }
        val lastFrameAt = AtomicLong(System.nanoTime())
        val silent = AtomicBoolean(false)
        val watchdog = scope.launch { ArcTunnelWatchdog.watch(call, lastFrameAt, silent) }
        var response: Response? = null
        try {
            val r = call.execute()
            response = r
            val s = sink
            if (r.code == HttpURLConnection.HTTP_OK && s != null) {
                wasBound.set(true)
                events.onBound(node.get())
                readFrames(scope, r.body.source(), s, sessions, lastFrameAt)
            } else {
                Log.w(TAG, "control connect rejected: ${r.code}")
            }
        } finally {
            canceller.cancel()
            watchdog.cancel()
            sessions.closeAll()
            try {
                sink?.close()
            } catch (e: IOException) {
                Log.d(TAG, "control request body close failed", e)
            }
            response?.close()
            current.compareAndSet(call, null)
            if (silent.get()) client.connectionPool.evictAll()
        }
    }

    private fun readFrames(
        scope: CoroutineScope,
        source: BufferedSource,
        sink: BufferedSink,
        sessions: ArcTunnelSessions,
        lastFrameAt: AtomicLong,
    ) {
        var frame = ArcTunnelFrames.read(source)
        while (frame != null && running.get()) {
            lastFrameAt.set(System.nanoTime())
            dispatch(scope, frame.first, frame.second, sink, sessions)
            frame = if (running.get()) ArcTunnelFrames.read(source) else null
        }
    }

    /**
     * A malformed payload throws `SerializationException` (an `IllegalArgumentException`): the stream tears
     * down.
     */
    private fun dispatch(
        scope: CoroutineScope,
        type: Int,
        payload: ByteArray,
        sink: BufferedSink,
        sessions: ArcTunnelSessions,
    ) {
        when (type) {
            ArcTunnelFrames.PING -> {
                sink.write(ArcTunnelFrames.PONG_FRAME)
                sink.flush()
            }

            ArcTunnelFrames.OPEN -> {
                val open = ArcTunnelJson.decodeFromString(ArcTunnelOpenPayload.serializer(), String(payload))
                scope.launch { opens.handle(open, sessions) }
            }

            ArcTunnelFrames.RENEW_NUDGE -> {
                onNudge(scope, String(payload))
            }

            else -> {
                Log.d(TAG, "unknown control frame type $type ignored")
            }
        }
    }

    private fun onNudge(
        scope: CoroutineScope,
        payloadJson: String,
    ) {
        val nonce = ArcTunnelJson.decodeFromString(ArcTunnelRenewNudgePayload.serializer(), payloadJson).nonce
        when {
            nonce.isEmpty() -> Log.w(TAG, "RENEW_NUDGE without a nonce ignored")
            !renewalInFlight.compareAndSet(false, true) -> Log.i(TAG, "renewal in progress; nudge ignored")
            else -> scope.launch { renew(nonce) }
        }
    }

    private suspend fun renew(nonce: String) {
        try {
            events.onRenewNudge(nonce)
        } finally {
            renewalInFlight.set(false)
        }
    }

    private fun duplexBody(capture: (BufferedSink) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun isDuplex(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                capture(sink)
            }
        }

    companion object {
        private const val TAG = "MCP:ArcTunnelControl"
        private const val CONTROL_PATH = "/api/v1/control"
    }
}
```

Context: `dispatch` runs on the IO thread that reads the stream; OPEN and renewal children are launched on the `run()` supervisor scope and end when `sessions.closeAll()` closes their sockets at stream end (an in-flight dial-back socket is registered by `ArcTunnelDialBack.open`). The `client` passed in is `ArcTunnelNet.controlClient(identity)`. The scope running `run()` MUST carry a `CoroutineExceptionHandler` (the provider's does) for the supervised children.

Definition of Done:
- [x] The seven files as written.

### [x] Task 4.8 — Shared test infrastructure

[x] **Action 4.8.1** — create `TA/FakeArcTunnelKeyStore.kt` (JVM keys; the "attestation chain" is a self-signed cert of the key, which is all the client code needs):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/** In-memory P-256 keys for JVM tests; records every generation so tests can assert alias lifecycles. */
class FakeArcTunnelKeyStore : ArcTunnelKeyStore {
    private val keys = ConcurrentHashMap<String, KeyPair>()
    val generated = mutableListOf<Pair<String, ByteArray?>>()
    val deleted = mutableListOf<String>()

    override fun generateEcKey(
        alias: String,
        challenge: ByteArray?,
    ) {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        keys[alias] = generator.generateKeyPair()
        generated += alias to challenge
    }

    override fun hasKey(alias: String): Boolean = keys.containsKey(alias)

    override fun privateKey(alias: String): PrivateKey = checkNotNull(keys[alias]) { "no key $alias" }.private

    override fun publicKey(alias: String): PublicKey = checkNotNull(keys[alias]) { "no key $alias" }.public

    override fun deleteKey(alias: String) {
        keys.remove(alias)
        deleted += alias
    }

    override fun deleteAll(prefix: String) {
        keys.keys.filter { it.startsWith(prefix) }.forEach { deleteKey(it) }
    }

    override fun attestationChainPem(alias: String): String {
        val pair = checkNotNull(keys[alias]) { "no key $alias" }
        val cert =
            HeldCertificate
                .Builder()
                .keyPair(pair)
                .commonName(alias)
                .build()
                .certificate
        return ArcTunnelPem.encode("CERTIFICATE", cert.encoded)
    }
}
```

[x] **Action 4.8.2** — create `TA/ArcTunnelTestServer.kt` (a TLS `MockWebServer` whose certificate covers the enroll host, the control host and every tunnel name; `answer` drives one raw HTTP/2 stream per request; `hold` keeps a stream open until the client leaves):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketHandler
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Socket
import okio.buffer
import java.io.IOException
import java.net.InetAddress

object ArcTunnelTestServer {
    const val BASE = "example.test"
    const val ENROLL_HOST = "enroll.example.test"
    const val CONTROL_HOST = "connect.example.test"
    val hosts: ArcTunnelHosts = ArcTunnelHosts(BASE)
    val held: HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(CONTROL_HOST)
            .addSubjectAlternativeName(CONTROL_HOST)
            .addSubjectAlternativeName(ENROLL_HOST)
            .addSubjectAlternativeName("*.d.$BASE")
            .build()
    val certPem: String = ArcTunnelPem.encode("CERTIFICATE", held.certificate.encoded)
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

    fun start(): MockWebServer =
        MockWebServer().also {
            it.useHttps(serverCerts.sslSocketFactory())
            it.start(InetAddress.getLoopbackAddress(), 0)
        }

    fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .build()

    /** A net trusting the test certificate whose hosts resolve to the mock server on [port]. */
    fun net(port: Int): ArcTunnelNet = ArcTunnelNet(certPem, ArcTunnelEdgeOverride("127.0.0.1", port))

    fun answer(
        code: Int,
        onStream: (Socket) -> Unit,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .removeHeader("Content-Length")
            .socketHandler(
                object : SocketHandler {
                    override fun handle(socket: Socket) = onStream(socket)
                },
            ).build()

    /** A bound control stream that sends nothing and stays open until the client ends it. */
    fun hold(): MockResponse =
        answer(200) { s ->
            try {
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("held control stream ended: $e")
            }
        }

    fun json(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build()

    fun frame(
        type: Int,
        payload: String = "",
    ): ByteArray {
        val p = payload.toByteArray()
        return Buffer()
            .writeByte(type)
            .writeInt(p.size)
            .write(p)
            .readByteArray()
    }
}
```

[x] **Action 4.8.3** — create `TA/ArcTunnelTestPeers.kt` (recording events + loopback peers for the dial-back and the local listener; the peers use plain threads on purpose — they stand in for external processes in JUnit, not for app code):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ArcTunnelTestEvents : ArcTunnelControlEvents {
    val bound = CountDownLatch(1)
    val boundCount = AtomicInteger()
    val unbound = AtomicInteger()
    val nudges = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>()

    /** When set, [onRenewNudge] suspends until it completes (single-flight tests). */
    @Volatile
    var nudgeGate: CompletableDeferred<Unit>? = null

    /** When true, [onDialBackFailed] throws after recording (the supervised-child test). */
    @Volatile
    var throwOnDialBackFailed: Boolean = false

    override fun onBound(node: String) {
        boundCount.incrementAndGet()
        bound.countDown()
    }

    override fun onUnbound() {
        unbound.incrementAndGet()
    }

    override suspend fun onRenewNudge(nonce: String) {
        nudges += nonce
        nudgeGate?.await()
    }

    override fun onDialBackFailed(message: String) {
        failures += message
        check(!throwOnDialBackFailed) { "injected failure" }
    }
}

/** Loopback peers: a dial-back endpoint answering `101` and a local listener accepting one socket. */
class ArcTunnelTestPeers : AutoCloseable {
    val dial = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val local = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    fun openFrame(): ByteArray =
        ArcTunnelTestServer.frame(
            ArcTunnelFrames.OPEN,
            """{"token":"t","addr":"127.0.0.1:${dial.localPort}","use_https":false,"use_mtls":false}""",
        )

    /** Accepts one dial-back, drains the upgrade request and answers 101; the accepted socket lands in [accepted]. */
    fun acceptUpgrade(
        accepted: AtomicReference<Socket>,
        upgraded: CountDownLatch,
    ) {
        Thread {
            try {
                val c = dial.accept()
                accepted.set(c)
                val input = c.getInputStream()
                var tail = 0
                while (tail != HEADER_END) {
                    val b = input.read()
                    if (b < 0) break
                    tail = (tail shl 8) or b
                }
                c.getOutputStream().apply {
                    write("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
                    flush()
                }
                upgraded.countDown()
            } catch (e: IOException) {
                System.err.println("dial-back peer ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun acceptLocal(
        accepted: AtomicReference<Socket>,
        done: CountDownLatch,
    ) {
        Thread {
            try {
                accepted.set(local.accept())
                done.countDown()
            } catch (e: IOException) {
                System.err.println("local listener ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        dial.close()
        local.close()
    }

    private companion object {
        const val HEADER_END = 0x0d0a0d0a
    }
}
```

[x] **Action 4.8.4** — create `TA/ArcTunnelTestIdentity.kt` (an identity with JVM keys and `HeldCertificate` chains for the enroller/store/provider tests):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import java.security.cert.X509Certificate

object ArcTunnelTestIdentity {
    const val NAME = "abcdefghij"

    fun chainFor(
        keys: FakeArcTunnelKeyStore,
        alias: String,
        cn: String,
    ): List<X509Certificate> {
        val pair = java.security.KeyPair(keys.publicKey(alias), keys.privateKey(alias))
        val held =
            HeldCertificate
                .Builder()
                .keyPair(pair)
                .commonName(cn)
                .addSubjectAlternativeName(cn)
                .build()
        return listOf(held.certificate)
    }

    /** An enrolled identity: identity-0 + the fixed TLS key, with (or without) a public chain. */
    fun create(
        keys: FakeArcTunnelKeyStore,
        net: ArcTunnelNet,
        withPublicChain: Boolean = true,
        hosts: ArcTunnelHosts = ArcTunnelTestServer.hosts,
    ): ArcTunnelIdentity {
        val prefix = ArcTunnelEnroller.aliasPrefix(hosts)
        keys.generateEcKey("${prefix}identity-0")
        keys.generateEcKey("${prefix}tls")
        val state =
            ArcTunnelIdentity.State(
                identityAlias = "${prefix}identity-0",
                prevIdentityAlias = "",
                tlsAlias = "${prefix}tls",
                identityChain = chainFor(keys, "${prefix}identity-0", NAME),
                publicChain = if (withPublicChain) chainFor(keys, "${prefix}tls", hosts.fqdn(NAME)) else emptyList(),
                caId = "letsencrypt",
                generation = 0,
            )
        return ArcTunnelIdentity(net, keys, NAME, hosts, state)
    }
}
```

[x] **Action 4.8.5** — create `TA/ZeroRandom.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlin.random.Random

/** A [Random] whose every roll is 0, so the Certificate Transparency trigger fires on each request. */
object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
```

Definition of Done:
- [x] The five test helpers as written.

### [x] Task 4.9 — Tests

**File**: `TA/ArcTunnelWireTest.kt`

| Test | Verifies |
|------|----------|
| `OpenPayload isValid rules` | empty token/addr invalid; `use_mtls` without `use_https` invalid; unknown keys ignored |
| `ApiErrorBody defaults` | `{}` → reason "", retryable false, 0 |
| `IssueResponse ca defaults to empty` | missing `ca` |
| `EnrollRequest serializes snake_case names` | `attestation_chain`, `identity_csr` |

**File**: `TA/ArcTunnelPemTest.kt`

| Test | Verifies |
|------|----------|
| `encode wraps at 64 chars` | header/footer + line lengths |
| `chainPem and parseChain round-trip` | `HeldCertificate` chain; `parseChain("")` empty |
| `hexToBytes` | valid; odd length rejected; non-hex rejected |
| `publicKeySha256 matches MessageDigest` | 64 lowercase hex chars of `key.encoded` |

**File**: `TA/ArcTunnelCsrTest.kt` (BouncyCastle `PKCS10CertificationRequest` parsing of the output)

| Test | Verifies |
|------|----------|
| `identity CSR has CN phone and no SAN` | |
| `tls CSR has CN fqdn and exactly one DNS SAN equal to fqdn` | PROTOCOL.md §3 |
| `CSR signature verifies with the public key` | `isSignatureValid` |

**File**: `TA/ArcTunnelFramesTest.kt`

| Test | Verifies |
|------|----------|
| `reads type and payload` | round-trip via `ArcTunnelTestServer.frame` |
| `empty payload` | length 0 |
| `oversized frame throws IOException` | length `(1 shl 20) + 1` |
| `a frame of exactly 1 MiB is accepted` | length `1 shl 20` |
| `EOF returns null` | |

**File**: `TA/ArcTunnelSessionsTest.kt`

| Test | Verifies |
|------|----------|
| `add before close registers` | `size()` 1 |
| `add after closeAll closes and returns false` | the closeable is closed, not registered |
| `closeAll closes every open session once` | two closeables, each closed exactly once |
| `release removes` | `size()` back to 0 |
| `close errors go to the callback` | a closeable throwing `IOException` → callback invoked, the others still closed |

**File**: `TA/ArcTunnelNetTest.kt` (`ArcTunnelTestServer`)

| Test | Verifies |
|------|----------|
| `url without override is https host path` | |
| `url with override carries the port and keeps the host` | `https://enroll.example.test:<port>/x` |
| `clients resolve the override address` | a GET to `enroll.example.test` lands on the mock server |
| `dialBack allows local targets only with an override` | `ArcTunnelNet(null, null).dialBack(...)` refuses `127.0.0.1:<port>`; `ArcTunnelTestServer.net(port).dialBack(...)` accepts it |
| `controlClient has no read timeout` | `readTimeoutMillis == 0` |
| `DynamicIdentityKeyManager picks the current identity per handshake` | swap the identity's alias between two `chooseClientAlias` calls → different aliases, matching chain/key |
| `close evicts and keeps the client usable` | `close()` returns; a request afterwards still succeeds |

**File**: `TA/ArcTunnelHttpTest.kt` (`ArcTunnelTestServer` TLS mock server + `ArcTunnelTestServer.net(port)`)

| Test | Verifies |
|------|----------|
| `get decodes json` | `{"nonce":"ab"}` → `ArcTunnelNonceResponse` |
| `non-200 throws with body fields` | 503 `{"reason":"x","retryable":true,"retry_after_seconds":7}` |
| `non-json error body maps to http_status` | 500 `"oops"` → `http_500`, not retryable |
| `enrollClient and issueClient timeouts` | read + call timeouts 300 s / 400 s |
| `cancelling the coroutine cancels the call` | **Setup**: a `mockwebserver3.Dispatcher` whose `dispatch()` blocks on a latch, so not even the response headers are sent; cancel the job → `get` ends with `CancellationException` within 1 s and the dispatcher's call is cancelled |
| `cancelling the coroutine aborts a stalled body read` | `answer(200)` whose handler sends the headers and then holds the body open; cancel the job → `get` ends with `CancellationException` within 1 s (the body is read on OkHttp's thread, aborted by `call.cancel()`) |
| `malformed 200 body maps to malformed_response` | `"{"` → `ArcTunnelApiException("malformed_response", false, 0, 200)`; the exception message does not contain the body |

**File**: `TA/ArcTunnelEnrollRetryTest.kt` (`runTest`, virtual time)

| Test | Verifies |
|------|----------|
| `success on first attempt fetches one nonce` | |
| `retryable refusal waits retry_after at least 1s with a fresh nonce` | `retryAfterSec = 0` → 1 s; `= 5` → 5 s; nonce refetched |
| `network error waits 2s with a fresh nonce` | |
| `ProviderException waits 3s and keeps the nonce` | same nonce passed twice |
| `non-retryable stops at once` | retryable false → rethrown, no delay |
| `gives up after 3 retries` | 4 attempts total |
| `cancellation is not retried` | `CancellationException` propagates immediately |

**File**: `TA/ArcTunnelIssueRetryTest.kt` (`runTest`)

| Test | Verifies |
|------|----------|
| `uses the given nonce first then fresh ones` | |
| `waits 5 10 15 seconds without retry_after` | |
| `waits retry_after when given` | |
| `ProviderException regenerates over the same nonce up to 5 times without spending retries` | 5 keygen failures then success → `freshNonce` never called |
| `sixth ProviderException gives up` | |
| `non-retryable api error stops` | |
| `gives up after 3 retries` | |

**File**: `TA/ArcTunnelEnrollerTest.kt` (`FakeArcTunnelKeyStore`; the TLS `ArcTunnelTestServer` with a `Dispatcher` answering `/api/v1/enroll/nonce`, `/api/v1/enroll`, `/api/v1/issue` from `ArcTunnelTestServer.json(...)` bodies built with `HeldCertificate` chains; `ArcTunnelTestServer.net(port)`; `runBlocking`)

| Test | Verifies |
|------|----------|
| `enroll generates identity-0 attested over the nonce and sends chain and csr` | request JSON fields; `generated` has `(identity-0, nonceBytes)`; state has `tlsAlias`, no public chain, generation 0 |
| `issue creates the TLS key once and a new identity key per generation` | two issues → `tls` generated once, `identity-1`, `identity-2` generated with the nonces |
| `a renewal with a missing TLS key fails without touching the keystore` | delete the tls alias after generation 1 → `IllegalStateException` mentioning "new name", no key generated |
| `issue requests the fqdn in the TLS CSR` | parse the posted `tls_csr` |
| `issue installs chains and deletes the identity key two generations back` | after gen 2: `deleted` contains `identity-0`, not `identity-1` |
| `failed issue drops the staged identity key and keeps the state` | 400 → `identity-1` deleted, state unchanged |
| `malformed public chain in the response drops the staged key` | `public_cert: "garbage"` |
| `issue retries once over a fresh nonce` | first 503 retryable, then 200; `/enroll/nonce` called once |

**File**: `TA/ArcTunnelIdentityStoreTest.kt` (`@TempDir`, `FakeArcTunnelKeyStore`, `ArcTunnelTestIdentity`)

| Test | Verifies |
|------|----------|
| `load returns null without a file` | |
| `save then load round-trips` | |
| `mergeCtCheck keeps identity fields and merges` | cursor/time replace, flagged keys add (no duplicates), a null alert keeps the current one; `save` afterwards keeps them |
| `mergeCtCheck keeps a cleared alert` | `clearCtAlert()` then `mergeCtCheck(…, newAlertPubkey = null, …)` → alert stays empty |
| `clearCtAlert clears only the alert` | cursor, flagged and check time unchanged |
| `holds compares the name` | |
| `forget deletes the file and the three keys` | `deleted` has identity, prev, tls aliases |
| `unreadable file loads as null and is forgotten` | write `"garbage"` → `load()` null, `forget()` true, file gone |

**File**: `TA/ArcTunnelBackoffTest.kt`

| Test | Verifies |
|------|----------|
| `schedule is 1 2 4 8 16 16` | six `next()` calls |
| `reset returns to 1s` | after three `next()` calls, `reset()` then `next()` → 1 000 ms |

**File**: `TA/ArcTunnelLocalConnectTest.kt` (`runBlocking`)

| Test | Verifies |
|------|----------|
| `connects to a listening port` | |
| `retries until the listener appears within the window` | start the `ServerSocket` after 500 ms |
| `gives up after the window` | `windowMs = 300` → `ConnectException` |

**File**: `TA/ArcTunnelWatchdogTest.kt` (`runTest`, virtual time; `mockk<Call>`; `now = { testScheduler.currentTime * 1_000_000 }` — the watchdog compares nanoseconds)

| Test | Verifies |
|------|----------|
| `cancels the call after 90s of silence and sets silent` | advance 95 s → `cancel()` once, `silent` true |
| `fresh frames keep the call alive` | `lastFrameAt` refreshed every 30 s for 5 min → no cancel |
| `stops polling once the call is cancelled` | `isCanceled` true → returns |

**File**: `TA/ArcTunnelDialBackTest.kt` (`runBlocking`; `ArcTunnelDialBack(CONTROL_HOST, mtlsCtx, serverCtx, allowLocalTargets = true, timeoutMs = 500)` — the peers are loopback; a loopback `ServerSocket` peer; a fresh `ArcTunnelSessions`)

| Test | Verifies |
|------|----------|
| `101 returns the connection and sends the upgrade request` | request bytes contain `X-Dial-Token: t` and `Upgrade: tunneld-dial` |
| `non-101 throws IOException` | `404` |
| `bad address throws IllegalArgumentException` | `"nohost"`, `"h:x"` |
| `local targets are refused without the override flag` | `allowLocalTargets = false` + `127.0.0.1:<port>`, `169.254.1.1:443`, `0.0.0.0:443` → `IllegalArgumentException`, the peer accepts nothing |
| `deadline ends a stalled connect` | peer never accepts (backlog full) → `SocketTimeoutException` within 2 s |
| `deadline ends trickled headers` | peer writes one byte per 100 ms → `SocketTimeoutException` |
| `peer close during the upgrade throws IOException` | |
| `a failed open leaves no session registered` | after any failure above `sessions.size() == 0` |
| `a successful open keeps the raw socket registered` | `sessions.size() == 1`; `conn.raw === ` the registered socket |
| `raw socket is tracked by the sessions` | `sessions.size()` is 1 while blocked; `closeAll()` ends the open with `IOException` |
| `shut sessions refuse a new dial-back` | `closeAll()` first → `IOException` without connecting |
| `TLS dial-back verifies the host name and ALPN` | an `SSLServerSocket` from `ArcTunnelTestServer.held` for `connect.example.test` → 101 succeeds and the server side's `applicationProtocol` is `http/1.1`; a certificate for another name → `IOException`/`SSLHandshakeException` |

**File**: `TA/ArcTunnelOpenHandlerTest.kt` (`runBlocking`; `ArcTunnelTestPeers`; a fresh `ArcTunnelSessions`; `ArcTunnelOpenHandler(ArcTunnelDialBack(CONTROL_HOST, default, default, allowLocalTargets = true), { port }, events, localConnectWindowMs = 300)`)

| Test | Verifies |
|------|----------|
| `a completed splice releases every session` | peer answers 101, local accepts, both sides close → `sessions.size() == 0` |
| `cancellation inside the local-connect window closes the dial-back` | no local listener, `localConnectWindowMs = 5_000`; cancel the handler job after 200 ms → the peer sees EOF within 1 s, `sessions.size() == 0` |
| `cancellation during a splice closes both sockets` | cancel the handler job mid-splice → both peers see EOF |
| `a failed dial-back registers nothing` | refusing address → `failures` 1, `sessions.size() == 0` |
| `a failed local connect closes and releases the remote sockets` | no local listener → peer sees EOF, `sessions.size() == 0` |
| `invalid OPEN is ignored` | no dial-back |

**File**: `TA/ArcTunnelControlTest.kt` (`runBlocking`; `ArcTunnelTestServer`, `ArcTunnelTestPeers`, `ArcTunnelTestEvents`; `ArcTunnelOpenHandler(ArcTunnelDialBack(CONTROL_HOST, default, default, allowLocalTargets = true), { peers.local.localPort }, events, localConnectWindowMs = 300)`; `ArcTunnelControl(net.controlClient(identity), net, CONTROL_HOST, opens, events)` with `identity = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), net)` (the real control client: `readTimeout(0)` is under test); `run()` launched on `CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, t -> recorded += t })`, ended with `control.stop()` + `job.cancelAndJoin()`)

| Test | Verifies |
|------|----------|
| `PING is answered with PONG` | server reads `[3,0,0,0,0]` from the request body |
| `OPEN dials back then connects locally and splices` | bytes written on the dial-back peer arrive on the local socket and back |
| `OPEN with unreachable local listener closes the dial-back` | peer sees EOF within 1 s, `failures` has one entry |
| `OPEN dial-back failure never touches the local listener` | local `ServerSocket` accepts nothing (D9 order) |
| `invalid OPEN is ignored` | no dial-back, stream continues |
| `malformed OPEN tears the stream down and reconnects` | `unbound` 1 then `boundCount` 2 |
| `RENEW_NUDGE runs one renewal at a time` | `nudgeGate` held → two nudges → one callback; after completing the gate a third nudge runs |
| `non-200 answer closes the request body` | the server stream's source reaches EOF cleanly |
| `stop cancels the in-flight stream at once` | `job` completes within 2 s while the server holds the stream |
| `stop closes the dial-back sessions` | spliced sockets see EOF |
| `backoff resets after a bound stream even when it ends abruptly` | first answer 503 (backoff advances to 2 s), second answer a 200 stream the server resets at once → the third attempt comes ≈ 1 s later, not 2 s (the bound stream reset the schedule) |
| `a bound stream stays up through 12 seconds of silence` | hold the stream 12 s without frames → `unbound` stays 0 (no OkHttp read timeout) |
| `a failing OPEN child does not end the control loop` | `events.throwOnDialBackFailed = true`, OPEN to a refusing address → the scope's `CoroutineExceptionHandler` records one `IllegalStateException`; a PING afterwards is still answered |
| `cancelling the scope cancels the in-flight call` | server holds the stream; `job.cancelAndJoin()` (no `stop()`) completes within 2 s |

Definition of Done:
- [x] One test per row in every table.

---

## [x] User Story 5 — Strings and enrollment service

Why: enrollment, re-enrollment, issuance resume and renewals must be usable from the settings UI (server stopped), from ADB (D20), from the e2e receiver and from the provider (server running) through one serialized, application-scoped entry point that owns the identity stores (D4: leaving a screen never cancels an enrollment); an interface keeps the UI and the provider unit-testable (CLAUDE.md §5).

Acceptance criteria:
- [x] `ArcTunnelEnrollment` (interface) is bound to `ArcTunnelEnrollmentImpl` in Hilt and exposes `state`, `savedUrl`, `savedCtAlert`, `store`, `net`, `requestEnroll`, `requestRotate`, `execute`, `readyIdentity`, `renew`, `dismissCtAlert`.
- [x] Requests run inside `ArcTunnelEnrollService`, a short-lived foreground service, so the process is never frozen half-way; a refused foreground start (app in the background on Android 12+) is reported as `Failed`.
- [x] `execute` is idempotent: an identity with a valid certificate is left alone (same name, no network), a certificate-less or expired one only issues (same name, D4), nothing saved enrolls; only `rotate` changes the name. `readyIdentity` waits for an in-flight request. A renewal after a `forget` saves nothing. `savedUrl` is null until a certificate is saved.

### [x] Task 5.1 — Strings and notification channel

[x] **Action 5.1.1** — modify `app/src/main/res/values/strings.xml`:

```xml
    <!-- settings_tunnel_subtitle: replace the value -->
    <string name="settings_tunnel_subtitle">Free E2E tunnel, Cloudflare, ngrok</string>

    <!-- Remote Access / Tunnel: free E2E tunnel -->
    <string name="remote_access_provider_arc_tunnel">Free E2E tunnel</string>
    <string name="remote_access_provider_arc_tunnel_desc">End-to-end encrypted, no account. A public certificate (Let\'s Encrypt, Google Trust Services or ZeroSSL) is issued to this device, so the tunnel cannot read your traffic — unlike Cloudflare and ngrok, which see everything.</string>
    <string name="remote_access_arc_tunnel_base_address_label">Tunnel service base address</string>
    <string name="remote_access_arc_tunnel_base_address_error">Enter a host name such as arc-tunnel.io</string>
    <string name="remote_access_arc_tunnel_ct_check_label">Certificate Transparency check</string>
    <string name="remote_access_arc_tunnel_ct_check_description">Looks up public certificate logs for your tunnel name and warns if a certificate was issued to another key</string>
    <string name="remote_access_arc_tunnel_url_label">Public URL</string>
    <string name="remote_access_arc_tunnel_url_refresh">Get a new name</string>
    <string name="remote_access_arc_tunnel_set_up">Set up tunnel</string>
    <string name="remote_access_arc_tunnel_url_loading">Loading…</string>
    <string name="remote_access_arc_tunnel_enroll_dialog_title">Set up the free E2E tunnel?</string>
    <string name="remote_access_arc_tunnel_enroll_dialog_body">This device gets its own name and public certificate. It takes about 10–30 seconds on average.</string>
    <string name="remote_access_arc_tunnel_enroll_dialog_continue">Continue</string>
    <string name="remote_access_arc_tunnel_enroll_dialog_cancel">Cancel</string>
    <string name="remote_access_arc_tunnel_rotate_dialog_title">Get a new name?</string>
    <string name="remote_access_arc_tunnel_rotate_dialog_body">The current URL stops working and every client must be updated. New keys and a new certificate are created (10–30 seconds).</string>
    <string name="remote_access_arc_tunnel_enrolling">Setting up the tunnel…</string>
    <string name="remote_access_arc_tunnel_enroll_failed">Setup failed: %1$s</string>
    <string name="remote_access_arc_tunnel_retry">Retry</string>
    <string name="remote_access_https_note_arc_tunnel">HTTPS stays enabled for local clients; the free E2E tunnel uses its own certificate.</string>

    <!-- remote_access_https_disabled_warning: replace the value -->
    <string name="remote_access_https_disabled_warning">Cloudflare and ngrok are unavailable while HTTPS is enabled. Disable HTTPS to use them, or choose the free E2E tunnel.</string>

    <!-- Server tab: free E2E tunnel -->
    <string name="server_network_access_suggestion_arc_tunnel">Enable free E2E tunnel</string>
    <string name="server_network_access_suggestion_other_tunnels">Other tunnels</string>
    <string name="server_arc_tunnel_ct_alert_title">Certificate issued to another key</string>
    <string name="server_arc_tunnel_ct_alert_body">A public certificate for %1$s was issued to a key this device does not hold. Someone else could present it to your clients. Consider getting a new name.</string>
    <string name="server_arc_tunnel_ct_alert_change_name">Change name</string>
    <string name="server_arc_tunnel_ct_alert_dismiss">Dismiss</string>

    <!-- Notifications: free E2E tunnel -->
    <string name="notification_channel_arc_tunnel_alert_name">Tunnel certificate alerts</string>
    <string name="notification_arc_tunnel_ct_title">Tunnel certificate alert</string>
    <string name="notification_arc_tunnel_ct_body">A certificate for %1$s was issued to another key — tap to review</string>
    <string name="notification_arc_tunnel_enroll_title">Setting up the free E2E tunnel</string>
    <string name="notification_arc_tunnel_enroll_body">Getting a name and a certificate for this device…</string>
```

[x] **Action 5.1.2** — modify `A/McpApplication.kt`:

```kotlin
// createNotificationChannels(): after updateChannel
        val arcTunnelAlertChannel =
            NotificationChannel(
                ARC_TUNNEL_ALERT_CHANNEL_ID,
                getString(R.string.notification_channel_arc_tunnel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alert when a certificate for the free E2E tunnel name was issued to another key"
            }
        …
        notificationManager.createNotificationChannel(arcTunnelAlertChannel)

// companion
        const val ARC_TUNNEL_ALERT_CHANNEL_ID = "arc_tunnel_alert_channel"
```

Definition of Done:
- [x] Strings and the notification channel as written.

### [x] Task 5.2 — Interface

[x] **Action 5.2.1** — create `arctunnel/ArcTunnelEnrollment.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.flow.StateFlow

sealed class ArcTunnelEnrollmentState {
    data object Idle : ArcTunnelEnrollmentState()

    data object Enrolling : ArcTunnelEnrollmentState()

    data class Failed(
        val message: String,
    ) : ArcTunnelEnrollmentState()
}

/** A certificate for the tunnel name whose key is not the phone's TLS key. */
data class ArcTunnelCtAlert(
    val fqdn: String,
    val pubkeySha256: String,
)

/** Owns the per-environment identity stores and serializes enrollment, rotation, forget and renewal. */
interface ArcTunnelEnrollment {
    /** Idle → Enrolling → Idle | Failed; observed by the UI. */
    val state: StateFlow<ArcTunnelEnrollmentState>

    fun store(env: ArcTunnelEnv): ArcTunnelIdentityStore

    fun net(env: ArcTunnelEnv): ArcTunnelNet

    /** The public URL of a saved identity that HAS a certificate (UI display, no keystore access); null otherwise. */
    fun savedUrl(env: ArcTunnelEnv): String?

    /** The persisted, undismissed CT alert of the environment, if any. */
    fun savedCtAlert(env: ArcTunnelEnv): ArcTunnelCtAlert?

    /**
     * Starts the enrollment foreground service to make the environment enrolled (see [execute]
     * with `rotate = false`).
     * When Android refuses the foreground start (app in the background), [state] becomes `Failed`.
     */
    fun requestEnroll(env: ArcTunnelEnv)

    /** Starts the enrollment foreground service to forget the saved identity and enroll anew (refresh icon). */
    fun requestRotate(env: ArcTunnelEnv)

    /**
     * One request, run by the service: `Enrolling` → `Idle` | `Failed`. Without [rotate]: a saved identity with a
     * valid certificate is left as is, one without (or with an expired) certificate only issues (same name),
     * nothing saved enrolls (Phase 1 + Phase 2). With [rotate]: forget, then enroll. A request while one runs
     * returns at once.
     */
    suspend fun execute(
        env: ArcTunnelEnv,
        rotate: Boolean,
    )

    /**
     * The saved identity ready to serve: waits for an in-flight request, then issues the public certificate when
     * none is saved or the saved one is expired. Null when nothing is saved.
     */
    suspend fun readyIdentity(env: ArcTunnelEnv): ArcTunnelIdentity?

    /** One renewal (nudge nonce or fresh nonce), saved only while [identity] is still the saved one. */
    suspend fun renew(
        identity: ArcTunnelIdentity,
        nonce: String?,
    )

    /** Clears the persisted undismissed CT alert of the environment (the UI dismissed it). */
    suspend fun dismissCtAlert(env: ArcTunnelEnv)
}
```

Definition of Done:
- [x] Interface as written.

### [x] Task 5.3 — Implementation, foreground service and binding

[x] **Action 5.3.1** — create `arctunnel/ArcTunnelEnrollmentFlow.kt` (the workflows of the service; the caller holds the service mutex):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** Rebuilds an identity from its saved record; throws when its identity key is missing from the keystore. */
internal fun restoreArcTunnelIdentity(
    env: ArcTunnelEnv,
    saved: ArcTunnelSavedIdentity,
    keys: ArcTunnelKeyStore,
    net: ArcTunnelNet,
): ArcTunnelIdentity {
    check(keys.hasKey(saved.identityAlias)) { "saved identity ${saved.name} has no usable identity key" }
    val state =
        ArcTunnelIdentity.State(
            identityAlias = saved.identityAlias,
            prevIdentityAlias = saved.prevIdentityAlias,
            tlsAlias = saved.tlsAlias,
            identityChain = ArcTunnelPem.parseChain(saved.identityChain),
            publicChain = ArcTunnelPem.parseChain(saved.publicChain),
            caId = saved.ca,
            generation = saved.generation,
        )
    return ArcTunnelIdentity(net, keys, saved.name, env.hosts, state)
}

/** True when the identity has no public certificate or an expired one (issuance needed). */
internal fun certificateMissingOrExpired(identity: ArcTunnelIdentity): Boolean {
    val leaf = identity.publicLeaf()
    return leaf == null || leaf.notAfter.before(Date())
}

/**
 * True when the saved identity certificate (the mTLS client certificate) is missing or expired: the name can
 * no longer be renewed (PROTOCOL.md §3), only re-enrolled.
 */
internal fun identityCertificateExpired(saved: ArcTunnelSavedIdentity): Boolean {
    val leaf = ArcTunnelPem.parseChain(saved.identityChain).firstOrNull()
    return leaf == null || leaf.notAfter.before(Date())
}

/** Enroll / resume / forget workflows of [ArcTunnelEnrollmentImpl]; every call runs under the service mutex. */
internal class ArcTunnelEnrollmentFlow(
    private val keys: ArcTunnelKeyStore,
    private val enroller: ArcTunnelEnroller,
) {
    /**
     * Idempotent "make enrolled": a saved identity with a valid certificate is returned untouched; one with a
     * missing/expired certificate only issues (same name); nothing saved → fresh two-phase enrollment.
     */
    suspend fun enrollOrResume(
        env: ArcTunnelEnv,
        store: ArcTunnelIdentityStore,
        net: ArcTunnelNet,
    ): ArcTunnelIdentity {
        val saved = withContext(Dispatchers.IO) { store.load() }
        if (saved != null && keys.hasKey(saved.identityAlias) && !identityCertificateExpired(saved)) {
            val resumed = restoreArcTunnelIdentity(env, saved, keys, net)
            if (certificateMissingOrExpired(resumed)) {
                enroller.issue(resumed, null)
                withContext(Dispatchers.IO) { store.save(resumed) }
            }
            return resumed
        }
        // An unusable record (identity key gone or identity certificate expired): worthless without a public
        // certificate (start over below); with one the user must pick a new name (the URL shows the icon).
        check(saved == null || saved.publicChain.isEmpty()) { "identity ${saved?.name} is unusable; get a new name" }
        forget(env, store)
        val (identity, issueNonce) = enroller.enroll(net, env.hosts)
        withContext(Dispatchers.IO) { store.save(identity) }
        enroller.issue(identity, issueNonce)
        withContext(Dispatchers.IO) { store.save(identity) }
        return identity
    }

    /** Deletes the saved identity and every key of the environment. */
    suspend fun forget(
        env: ArcTunnelEnv,
        store: ArcTunnelIdentityStore,
    ) {
        withContext(Dispatchers.IO) {
            store.forget()
            keys.deleteAll(ArcTunnelEnroller.aliasPrefix(env.hosts))
        }
    }
}
```

[x] **Action 5.3.2** — create `arctunnel/ArcTunnelStartTracker.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/**
 * Counts the outstanding start commands of the enrollment service and tells which `startId` to stop with:
 * `stopSelfResult` stops a service only when given the LAST delivered id, so a request that finishes early
 * (a duplicate, ignored by `execute`) must never stop with its own id. Main-thread only.
 */
internal class ArcTunnelStartTracker {
    private var active = 0
    private var lastStartId = 0

    fun started(startId: Int) {
        active++
        lastStartId = startId
    }

    /** The id to pass to `stopSelfResult`, or null while other requests are still running. */
    fun finished(): Int? {
        active--
        return if (active == 0) lastStartId else null
    }
}
```

[x] **Action 5.3.3** — create `arctunnel/ArcTunnelEnrollService.kt` (`NOTIFICATION_ID = 1005`; 1001–1004 are taken):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Short-lived foreground service running one enrollment / rotation request. A foreground service keeps the
 * process out of the cached state, so the cached-apps freezer can never suspend an attempt half-way; it stops
 * itself when the last request ends. Without environment extras the current settings are used.
 */
@AndroidEntryPoint
class ArcTunnelEnrollService : Service() {
    @Inject
    lateinit var enrollment: ArcTunnelEnrollment

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ArcTunnelStartTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification())
        val rotate = intent?.getBooleanExtra(EXTRA_ROTATE, false) ?: false
        requests.started(startId)
        scope.launch {
            try {
                val env = intent?.let(::envOf) ?: ArcTunnelEnv.from(settingsRepository.getServerConfig())
                enrollment.execute(env, rotate)
            } finally {
                // Main thread like onStartCommand: the counter and stopSelfResult are never raced.
                withContext(NonCancellable + Dispatchers.Main) { requests.finished()?.let { stopSelfResult(it) } }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory level=$level")
    }

    private fun notification(): Notification =
        NotificationCompat
            .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_arc_tunnel_enroll_title))
            .setContentText(getString(R.string.notification_arc_tunnel_enroll_body))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    /** The debug-only overrides are honoured in debug builds only, like `ArcTunnelEnv.from`. */
    private fun envOf(intent: Intent): ArcTunnelEnv? =
        intent.getStringExtra(EXTRA_BASE_ADDRESS)?.let {
            ArcTunnelEnv(
                it,
                if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_EDGE_OVERRIDE).orEmpty() else "",
                if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_TRUSTED_CA_PEM).orEmpty() else "",
            )
        }

    companion object {
        private const val TAG = "MCP:ArcTunnelEnrollSvc"
        const val NOTIFICATION_ID = 1005
        private const val EXTRA_BASE_ADDRESS = "base_address"
        private const val EXTRA_EDGE_OVERRIDE = "edge_override"
        private const val EXTRA_TRUSTED_CA_PEM = "trusted_ca_pem"
        private const val EXTRA_ROTATE = "rotate"

        /**
         * Starts the service for [env] (null = the saved settings). False when Android refuses a foreground
         * start from the background (Android 12+): the caller reports it; `am start` of the trampoline
         * activity (`--es action enroll-tunnel`) is the headless way around it.
         */
        fun start(
            context: Context,
            env: ArcTunnelEnv?,
            rotate: Boolean,
        ): Boolean {
            val intent =
                Intent(context, ArcTunnelEnrollService::class.java).apply {
                    putExtra(EXTRA_ROTATE, rotate)
                    if (env != null) {
                        putExtra(EXTRA_BASE_ADDRESS, env.baseAddress)
                        putExtra(EXTRA_EDGE_OVERRIDE, env.edgeOverride)
                        putExtra(EXTRA_TRUSTED_CA_PEM, env.trustedCaPem)
                    }
                }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "foreground service start refused (app in the background)", e)
                false
            }
        }
    }
}
```

Context: the trusted CA PEM travels as an intent extra (a few KB at most).

[x] **Action 5.3.4** — modify `app/src/main/AndroidManifest.xml`:

```xml
<!-- after the FOREGROUND_SERVICE_SPECIAL_USE permission -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- after the McpServerService <service> element -->
        <!-- Free E2E tunnel enrollment (short-lived foreground service, see ArcTunnelEnrollService) -->
        <service
            android:name=".services.tunnel.arctunnel.ArcTunnelEnrollService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

[x] **Action 5.3.5** — create `arctunnel/ArcTunnelEnrollmentImpl.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArcTunnelEnrollmentImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val keys: ArcTunnelKeyStore,
        private val serverLog: ServerLogRepository,
    ) : ArcTunnelEnrollment {
        private val _state = MutableStateFlow<ArcTunnelEnrollmentState>(ArcTunnelEnrollmentState.Idle)
        override val state: StateFlow<ArcTunnelEnrollmentState> = _state.asStateFlow()
        private val mutex = Mutex()
        private val requestInFlight = AtomicBoolean(false)
        private val stores = ConcurrentHashMap<String, ArcTunnelIdentityStore>()
        private val nets = ConcurrentHashMap<ArcTunnelEnv, ArcTunnelNet>()
        private val enroller = ArcTunnelEnroller(keys)
        private val flow = ArcTunnelEnrollmentFlow(keys, enroller)

        /** One store instance per environment, so its `@Synchronized` methods really serialize all writers. */
        override fun store(env: ArcTunnelEnv): ArcTunnelIdentityStore =
            stores.getOrPut(env.hosts.envKey) {
                ArcTunnelIdentityStore(File(context.filesDir, STORE_DIR), env.hosts, keys)
            }

        /** One network layer (one connection pool) per environment, shared by requests and sessions. */
        override fun net(env: ArcTunnelEnv): ArcTunnelNet =
            nets.getOrPut(env) { ArcTunnelNet(env.trustedCaPem, ArcTunnelEdgeOverride.parse(env.edgeOverride)) }

        override fun savedUrl(env: ArcTunnelEnv): String? =
            store(env).load()?.takeIf { it.publicChain.isNotEmpty() }?.let { env.hosts.publicUrl(it.name) }

        override fun savedCtAlert(env: ArcTunnelEnv): ArcTunnelCtAlert? =
            store(env).load()?.takeIf { it.ctAlertPubkey.isNotEmpty() }?.let {
                ArcTunnelCtAlert(env.hosts.fqdn(it.name), it.ctAlertPubkey)
            }

        override fun requestEnroll(env: ArcTunnelEnv) {
            if (!ArcTunnelEnrollService.start(context, env, rotate = false)) {
                _state.value = ArcTunnelEnrollmentState.Failed(START_REFUSED)
            }
        }

        override fun requestRotate(env: ArcTunnelEnv) {
            if (!ArcTunnelEnrollService.start(context, env, rotate = true)) {
                _state.value = ArcTunnelEnrollmentState.Failed(START_REFUSED)
            }
        }

        override suspend fun execute(
            env: ArcTunnelEnv,
            rotate: Boolean,
        ) {
            if (!requestInFlight.compareAndSet(false, true)) return
            try {
                mutex.withLock {
                    _state.value = ArcTunnelEnrollmentState.Enrolling
                    val result =
                        runCatching {
                            val store = store(env)
                            if (rotate) flow.forget(env, store)
                            flow.enrollOrResume(env, store, net(env))
                        }
                    result.onSuccess {
                        _state.value = ArcTunnelEnrollmentState.Idle
                        serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel enrolled: ${it.publicUrl()}")
                    }
                    result.onFailure {
                        if (it is CancellationException) {
                            _state.value = ArcTunnelEnrollmentState.Idle
                            throw it
                        }
                        Log.e(TAG, "enrollment failed", it)
                        val reason = it.message ?: it.javaClass.simpleName
                        _state.value = ArcTunnelEnrollmentState.Failed(reason)
                        serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel enrollment failed: $reason")
                    }
                }
            } finally {
                requestInFlight.set(false)
            }
        }

        override suspend fun readyIdentity(env: ArcTunnelEnv): ArcTunnelIdentity? =
            mutex.withLock {
                val record = withContext(Dispatchers.IO) { store(env).load() } ?: return@withLock null
                check(!identityCertificateExpired(record)) { "identity certificate expired; get a new name" }
                val identity = restoreArcTunnelIdentity(env, record, keys, net(env))
                if (certificateMissingOrExpired(identity)) {
                    enroller.issue(identity, null)
                    withContext(Dispatchers.IO) { store(env).save(identity) }
                }
                identity
            }

        override suspend fun renew(
            identity: ArcTunnelIdentity,
            nonce: String?,
        ) {
            mutex.withLock {
                val store = store(ArcTunnelEnv(identity.hosts.baseAddress))
                check(store.holds(identity)) { "identity ${identity.name} was forgotten; renewal skipped" }
                enroller.issue(identity, nonce)
                if (store.holds(identity)) withContext(Dispatchers.IO) { store.save(identity) }
            }
        }

        override suspend fun dismissCtAlert(env: ArcTunnelEnv) {
            withContext(Dispatchers.IO) { store(env).clearCtAlert() }
        }

        companion object {
            private const val TAG = "MCP:ArcTunnelEnrollment"
            const val STORE_DIR = "arc-tunnel"
            const val START_REFUSED = "Open the app and try again (background start refused)"
        }
    }
```

Context: `ArcTunnelEnrollmentImpl` has 10 functions. `renew`, `execute` and `readyIdentity` share the mutex: a rotation can only start once a renewal ends, the `holds` check makes a late save a no-op, and a provider start waits for an in-flight enrollment instead of failing with "not enrolled". `execute` ignores a second request while one runs (the UI disables the buttons meanwhile). The service cannot reach `McpServerService` here; the callers (UI buttons disabled while running; the ADB handler, the trampoline and the E2E receiver through `isServerActive`) refuse requests while the server runs.

[x] **Action 5.3.6** — modify `A/di/AppModule.kt`: in `ServiceModule` add

```kotlin
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentImpl

    /** Binds the free E2E tunnel enrollment service. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelEnrollment(impl: ArcTunnelEnrollmentImpl): ArcTunnelEnrollment
```

Definition of Done:
- [x] Flow, implementation, foreground service, manifest entries and Hilt binding as written.

### [x] Task 5.4 — Tests

**File**: `TA/ArcTunnelEnrollmentImplTest.kt` (`@TempDir` as `filesDir` through a MockK `Context` that also records `startForegroundService(intent)` calls; `FakeArcTunnelKeyStore`; the TLS `ArcTunnelTestServer` with a `Dispatcher` for `/api/v1/enroll/nonce`, `/api/v1/enroll`, `/api/v1/issue` (JSON bodies built from `HeldCertificate` chains); the env `ArcTunnelEnv("example.test", "127.0.0.1:<port>", ArcTunnelTestServer.certPem)`; `RecordingServerLogRepository`; `runBlocking`; requests are exercised through `execute(env, rotate)` directly)

| Test | Verifies |
|------|----------|
| `savedUrl is null without a saved identity` | |
| `savedUrl is null for a certificate-less identity` | saved record with an empty `public_chain` |
| `savedUrl derives from the saved name` | `https://<name>.d.example.test` once a chain is saved |
| `execute runs phase 1 then issue and saves twice` | store file has the name and a public chain; state Enrolling then Idle; log entry |
| `execute on a certificate-less identity only issues` | pre-save a cert-less identity → `/enroll` NOT called, `/issue` called, same name kept |
| `execute on an identity with a valid certificate is a no-op` | no HTTP call at all, same name, state Idle |
| `execute on an expired certificate issues again` | pre-save an expired `HeldCertificate` chain → `/issue` called once |
| `execute failure sets Failed` | `/enroll` → 401 non-retryable; message carries the reason |
| `execute while one runs returns at once` | a slow `/enroll` answer + a concurrent `execute` → one `/enroll` call |
| `execute with rotate forgets then enrolls a new name` | `deleted` contains the old aliases; two different names |
| `requestEnroll starts the foreground service with the env extras` | **Setup**: the mockable `Intent` returns defaults, so `mockkConstructor(Intent::class)` with `every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }` (and the `Boolean` overload); verify `putExtra("base_address", …)`, `putExtra("edge_override", …)`, `putExtra("trusted_ca_pem", …)`, `putExtra("rotate", false)` and one `context.startForegroundService(any())` |
| `requestRotate starts the service with rotate` | `putExtra("rotate", true)` |
| `a refused foreground start sets Failed` | `startForegroundService` throws `ForegroundServiceStartNotAllowedException` → `Failed(START_REFUSED)` |
| `readyIdentity is null without a saved identity` | |
| `readyIdentity issues only when missing or expired` | leaf null / expired → `/issue` called and saved; valid → not |
| `readyIdentity waits for an in-flight request` | a slow `/issue` inside `execute` launched concurrently → `readyIdentity` returns after it, with the enrolled identity and no second issuance |
| `renew after forget throws and saves nothing` | `IllegalStateException` |
| `execute starts over when the identity key is gone and no certificate was issued` | cert-less record, identity key deleted → `/enroll` called, old TLS alias deleted, new name |
| `execute fails when the identity key is gone but a certificate exists` | `Failed` mentioning "new name", no network call |
| `execute starts over when the identity certificate expired and no public certificate exists` | expired identity `HeldCertificate` chain, empty `public_chain` → `/enroll` called, new name |
| `execute fails when the identity certificate expired but a public certificate exists` | `Failed` mentioning "new name", no network call |
| `readyIdentity fails on an expired identity certificate` | `IllegalStateException`, no network call |
| `a fresh enrollment deletes leftover keys first` | unreadable store file + leftover `tls` alias → `deleted` contains it before the new keys are generated |
| `store returns one instance per environment` | `store(env) === store(env)` |
| `net returns one instance per environment` | `net(env) === net(env)`; a different override → another instance |
| `savedCtAlert and dismissCtAlert round-trip` | `mergeCtCheck("id", listOf("abc"), "abc", t)` → alert; dismiss → null, `ct_last_check_ms` kept |

**File**: `TA/ArcTunnelStartTrackerTest.kt`

| Test | Verifies |
|------|----------|
| `finished returns the id once the only request ends` | started(1), finished() → 1 |
| `out-of-order completion returns the latest start id` | started(1), started(2), finished() → null, finished() → 2 |
| `a new start after a stop starts over` | started(1), finished() → 1, started(5), finished() → 5 |

**Manual QA Steps** (`ArcTunnelEnrollService` is an Android `Service`; no JVM test):
1. Settings → Tunnel → Continue in the enrollment dialog, then immediately press Home: the "Setting up the free E2E tunnel" notification is shown until the URL appears in the settings; the notification disappears afterwards.
2. `adb shell am start -n <app-id>/…AdbServiceTrampolineActivity --es action enroll-tunnel` on a phone whose app is not open: the same notification shows and the URL is set (verified with the Server tab afterwards).

Definition of Done:
- [x] One test per row; the Manual QA Steps recorded in the PR description.

---

## [x] User Story 6 — Certificate Transparency check and alerts

Why: D10 — the only way the user learns about a certificate for their name that they did not issue.

Acceptance criteria:
- [x] On 1 % of requests, at most every 30 minutes, never concurrently, the monitor queries Cert Spotter for `<name>.d.<base>` (wildcards included) after the saved cursor and compares `pubkey_sha256` with the TLS public key.
- [x] An unknown key raises a notification and a dismissible alert state; a flagged key is never re-alerted; the cursor and the last check time are persisted; an undismissed alert is restorable (`restore` sets the state without a notification).

### [x] Task 6.1 — Alerts

[x] **Action 6.1.1** — create `arctunnel/ArcTunnelCtNotifier.kt` (`NOTIFICATION_ID = 1004`; 1001–1003 are taken by the service, OAuth and update notifiers, 1005 by the enrollment service):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity

/** Posts/cancels the single Certificate Transparency alert notification. */
object ArcTunnelCtNotifier {
    const val NOTIFICATION_ID = 1004
    private const val REQUEST_CODE = 4

    /** Posts (or updates) the alert; no-op without POST_NOTIFICATIONS. */
    fun post(
        context: Context,
        fqdn: String,
    ) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED) return
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, McpApplication.ARC_TUNNEL_ALERT_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_arc_tunnel_ct_title))
                .setContentText(context.getString(R.string.notification_arc_tunnel_ct_body, fqdn))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
```

[x] **Action 6.1.2** — create `arctunnel/ArcTunnelCtAlerts.kt` (`ArcTunnelCtAlert` is the data class of US5):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Current CT alert for the UI banner; the notification is posted by [ArcTunnelCtNotifier]. */
interface ArcTunnelCtAlerts {
    val alert: StateFlow<ArcTunnelCtAlert?>

    fun raise(alert: ArcTunnelCtAlert)

    /** Re-shows an undismissed alert after a process restart, without a new notification. */
    fun restore(alert: ArcTunnelCtAlert)

    fun dismiss()
}

@Singleton
class ArcTunnelCtAlertsImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ArcTunnelCtAlerts {
        private val _alert = MutableStateFlow<ArcTunnelCtAlert?>(null)
        override val alert: StateFlow<ArcTunnelCtAlert?> = _alert.asStateFlow()

        override fun raise(alert: ArcTunnelCtAlert) {
            _alert.value = alert
            ArcTunnelCtNotifier.post(context, alert.fqdn)
        }

        override fun restore(alert: ArcTunnelCtAlert) {
            _alert.value = alert
        }

        override fun dismiss() {
            _alert.value = null
            ArcTunnelCtNotifier.cancel(context)
        }
    }
```

[x] **Action 6.1.3** — modify `A/di/AppModule.kt`: in `ServiceModule` add

```kotlin
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlerts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlertsImpl

    /** Binds the Certificate Transparency alert state of the free E2E tunnel. */
    @Binds
    @Singleton
    abstract fun bindArcTunnelCtAlerts(impl: ArcTunnelCtAlertsImpl): ArcTunnelCtAlerts
```

Definition of Done:
- [x] Notifier, alerts interface + implementation and binding as written.

### [x] Task 6.2 — Monitor

[x] **Action 6.2.1** — create `arctunnel/ArcTunnelCtMonitor.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Serializable
data class ArcTunnelCtIssuance(
    val id: String,
    @SerialName("pubkey_sha256") val pubkeySha256: String = "",
    @SerialName("not_after") val notAfter: String = "",
)

/** Pure trigger rule: 1 % of requests, ≥ 30 min apart. */
object ArcTunnelCtTrigger {
    const val PERCENT = 1
    const val ROLL_RANGE = 100
    const val MIN_INTERVAL_MS = 30L * 60 * 1000

    fun shouldCheck(
        enabled: Boolean,
        roll: Int,
        nowMs: Long,
        lastCheckMs: Long,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean = enabled && roll < PERCENT && nowMs - lastCheckMs >= minIntervalMs
}

/**
 * Cert Spotter keyless lookup of the tunnel name, compared with the phone's own TLS public key. One
 * instance per provider session; checks run on [scope] (the provider's), one at a time, and the time of the
 * last check is persisted so the 30-minute spacing survives a restart.
 */
class ArcTunnelCtMonitor(
    private val deps: Deps,
    private val scope: CoroutineScope,
    private val enabled: Boolean,
    private val options: Options = Options(),
) {
    class Deps(
        val identity: ArcTunnelIdentity,
        val store: ArcTunnelIdentityStore,
        val keys: ArcTunnelKeyStore,
        val alerts: ArcTunnelCtAlerts,
    )

    /** Test seams: the API base, the random source and the minimum interval. */
    data class Options(
        val baseUrl: String = API_BASE,
        val random: Random = Random.Default,
        val minIntervalMs: Long = ArcTunnelCtTrigger.MIN_INTERVAL_MS,
    )

    private val inFlight = AtomicBoolean(false)
    private val lastCheckMs = AtomicLong(deps.store.load()?.ctLastCheckMs ?: 0L)
    private val client = OkHttpClient.Builder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Evicts the idle connections of the lookup client (session end). */
    fun close() {
        client.connectionPool.evictAll()
    }

    /** Re-shows an alert the user has not dismissed yet (process restart). */
    fun restoreAlert() {
        val saved = deps.store.load()
        val pubkey = saved?.ctAlertPubkey.orEmpty()
        if (pubkey.isNotEmpty()) deps.alerts.restore(ArcTunnelCtAlert(deps.identity.fqdn(), pubkey))
    }

    /** Called on every request through the tunnel listener; cheap unless a check is due. */
    fun onRequest() {
        val roll = options.random.nextInt(ArcTunnelCtTrigger.ROLL_RANGE)
        val now = System.currentTimeMillis()
        if (!ArcTunnelCtTrigger.shouldCheck(enabled, roll, now, lastCheckMs.get(), options.minIntervalMs)) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                check()
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** One lookup from the saved cursor; flags every issuance whose key is not ours; records the check time. */
    suspend fun check() {
        val saved = deps.store.load() ?: return
        val now = System.currentTimeMillis()
        val ownKey = ArcTunnelPem.publicKeySha256(deps.keys.publicKey(saved.tlsAlias))
        val issuances = fetchOrEmpty(deps.identity.fqdn(), saved.ctLastId)
        val known = saved.ctFlagged.toMutableList()
        val newFlagged = mutableListOf<String>()
        issuances
            .filter { it.pubkeySha256.isNotEmpty() && it.pubkeySha256 != ownKey && it.pubkeySha256 !in known }
            .forEach {
                known += it.pubkeySha256
                newFlagged += it.pubkeySha256
                Log.w(TAG, "certificate for ${deps.identity.fqdn()} issued to another key (ct id ${it.id})")
                deps.alerts.raise(ArcTunnelCtAlert(deps.identity.fqdn(), it.pubkeySha256))
            }
        // Merged into the current record: a dismissal made during the lookup is not undone.
        deps.store.mergeCtCheck(issuances.lastOrNull()?.id ?: saved.ctLastId, newFlagged, newFlagged.lastOrNull(), now)
        lastCheckMs.set(now)
    }

    /** A failed or malformed lookup is logged and yields no issuances, so the cursor is left untouched. */
    private suspend fun fetchOrEmpty(
        fqdn: String,
        after: String,
    ): List<ArcTunnelCtIssuance> =
        try {
            fetch(fqdn, after)
        } catch (e: IOException) {
            Log.w(TAG, "certificate transparency lookup failed", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "certificate transparency answer not understood: ${e.javaClass.simpleName}")
            emptyList()
        }

    private suspend fun fetch(
        fqdn: String,
        after: String,
    ): List<ArcTunnelCtIssuance> {
        val url =
            options.baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("v1/issuances")
                .addQueryParameter("domain", fqdn)
                .addQueryParameter("match_wildcards", "true")
                .addQueryParameter("expand", "dns_names")
                .apply { if (after.isNotEmpty()) addQueryParameter("after", after) }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        val (code, body) = ArcTunnelHttp.await(client.newCall(request))
        if (code != HttpURLConnection.HTTP_OK) throw IOException("cert spotter answered $code")
        return ArcTunnelJson.decodeFromString(ListSerializer(ArcTunnelCtIssuance.serializer()), body)
    }

    companion object {
        private const val TAG = "MCP:ArcTunnelCt"
        private const val API_BASE = "https://api.certspotter.com/"
        private const val TIMEOUT_S = 20L
    }
}
```

Definition of Done:
- [x] Monitor as written.

### [x] Task 6.3 — Tests

**File**: `TA/ArcTunnelCtTriggerTest.kt`

| Test | Verifies |
|------|----------|
| `fires only for roll 0 of 100` | roll 0 true, roll 1 false |
| `respects the minimum interval` | 29 min false, 30 min true |
| `disabled never fires` | |

**File**: `TA/ArcTunnelCtNotifierTest.kt` (MockK `Context`; `mockkStatic(ContextCompat::class, NotificationManagerCompat::class, PendingIntent::class)`; `mockkConstructor(NotificationCompat.Builder::class)` with every setter answering `self`)

| Test | Verifies |
|------|----------|
| `post notifies 1004 on the alert channel with the fqdn` | builder created with `ARC_TUNNEL_ALERT_CHANNEL_ID`; `setContentText` receives the formatted body; `notify(1004, …)` |
| `post without POST_NOTIFICATIONS does nothing` | `checkSelfPermission` → DENIED → no `notify` |
| `cancel cancels 1004` | |

**File**: `TA/ArcTunnelCtAlertsImplTest.kt` (MockK `Context`; `mockkObject(ArcTunnelCtNotifier)`)

| Test | Verifies |
|------|----------|
| `raise sets the flow and posts` | |
| `restore sets the flow without posting` | |
| `dismiss clears and cancels` | |

**File**: `TA/ArcTunnelCtMonitorTest.kt` (plain-HTTP `MockWebServer` as `Options.baseUrl`; `FakeArcTunnelKeyStore`; `@TempDir` store with a saved identity (`ArcTunnelTestIdentity`); MockK `ArcTunnelCtAlerts`; `Options(minIntervalMs = 0, random = ZeroRandom)` (Action 4.8.5); `runBlocking` scope)

| Test | Verifies |
|------|----------|
| `request path and query` | `/v1/issuances?domain=<fqdn>&match_wildcards=true&expand=dns_names` |
| `own key raises nothing and advances the cursor` | one issuance with our hash → no `raise`, `ctLastId` = id |
| `foreign key raises once and is remembered` | two checks with the same foreign key → one `raise`; flagged + `ctAlertPubkey` persisted |
| `after cursor is sent on the next check` | second request has `after=<id>` |
| `http error keeps the cursor` | 429 → cursor unchanged, no raise, `ct_last_check_ms` persisted |
| `malformed body keeps the cursor` | `"not json"` |
| `empty list keeps the cursor` | |
| `the minimum interval survives a restart` | store has `ct_last_check_ms = now` → a NEW monitor's `onRequest` sends no request (`minIntervalMs = 30 min`) |
| `check persists the check time` | `ct_last_check_ms` ≥ the time before the check |
| `a check in flight is cancelled with the scope` | server holds the answer; cancel the scope → the mock server's call is cancelled, `inFlight` released |
| `a dismissal during a check is not undone` | server holds the answer; `store.clearCtAlert()` meanwhile; the answer carries only our key → `ct_alert_pubkey` stays empty after the check |
| `restoreAlert re-raises the persisted alert without a notification` | `restore` called, `raise` not |
| `onRequest runs at most one check at a time` | a 2 s delayed server answer + 20 concurrent `onRequest` calls → one HTTP request |
| `onRequest does nothing when disabled` | |

Definition of Done:
- [x] One test per row.

---

## [x] User Story 7 — The provider and its wiring

Why: ties the enrollment service, the listener host, the control loop and the CT monitor together under the existing `TunnelProvider` contract, with the certificate swap policy of D8, structured concurrency (D17) and a bounded stop; the manager selects it, the HTTPS guard is lifted for it, and ADB can enroll headlessly (D20).

Acceptance criteria:
- [x] `ArcTunnelProvider.start` returns quickly with `Connecting`; its session then resumes the saved identity, issues a missing/expired certificate, starts the listener on a random port and the control loop; `Connected(https://<name>.d.<base>)` once the stream binds; `Error(reason)` for setup failures (not bound, not enrolled, missing key, listener failure).
- [x] A control drop reports `Connecting` and keeps reconnecting; a `RENEW_NUDGE` renews, then the listener restarts on the same port per D8; `stop` completes within 2 s even while a renewal, a CT check or a dial-back is blocked.
- [x] `TunnelManager.start(localPort, listenerHost)` selects the provider for `ARC_TUNNEL` and keeps refusing Cloudflare/ngrok while HTTPS is on; `--ez arc_tunnel_enroll true` over ADB enrolls.

### [x] Task 7.1 — Certificate swap policy

[x] **Action 7.1.1** — create `arctunnel/ArcTunnelCertSwap.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.util.concurrent.atomic.AtomicLong

/**
 * After a renewal, swap at the first idle moment within 24 h, or at once when the SERVED cert expires
 * within 5 min.
 */
class ArcTunnelCertSwap(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pendingSince = AtomicLong(NONE)

    /** A renewal installed a new chain; (re)starts the 24 h window. */
    fun schedule() {
        pendingSince.set(now())
    }

    /** The pending request's timestamp, or [NONE]. */
    fun pending(): Long = pendingSince.get()

    fun isDue(
        activeSplices: Int,
        servedNotAfterMs: Long,
    ): Boolean {
        val since = pendingSince.get()
        if (since == NONE) return false
        val t = now()
        return activeSplices == 0 || t - since >= MAX_WAIT_MS || servedNotAfterMs - t <= EXPIRY_MARGIN_MS
    }

    /** Clears the request that was [pending] when the swap started; a newer request stays pending. */
    fun done(started: Long) {
        pendingSince.compareAndSet(started, NONE)
    }

    companion object {
        const val NONE = 0L
        const val MAX_WAIT_MS = 24L * 60 * 60 * 1000
        const val EXPIRY_MARGIN_MS = 5L * 60 * 1000
    }
}
```

Definition of Done:
- [x] `ArcTunnelCertSwap` as written.

### [x] Task 7.2 — Provider

[x] **Action 7.2.1** — create `arctunnel/ArcTunnelProvider.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelListenerHost
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Free E2E tunnel provider: identity + certificate, loopback TLS listener, control loop,
 * Certificate Transparency check.
 */
class ArcTunnelProvider
    @Inject
    constructor(
        private val enrollment: ArcTunnelEnrollment,
        private val keys: ArcTunnelKeyStore,
        private val ctAlerts: ArcTunnelCtAlerts,
        private val serverLog: ServerLogRepository,
    ) : TunnelProvider {
        /** Everything belonging to one started tunnel; dropped as a whole by [close]. */
        private class Session(
            val host: TunnelListenerHost,
            val scope: CoroutineScope,
            val env: ArcTunnelEnv,
            val ctCheckEnabled: Boolean,
        ) {
            val port = AtomicInteger(0)
            val servedNotAfterMs = AtomicLong(0)
            val certSwap = ArcTunnelCertSwap()
            val connected = AtomicBoolean(false)

            @Volatile
            var identity: ArcTunnelIdentity? = null

            @Volatile
            var monitor: ArcTunnelCtMonitor? = null

            @Volatile
            var opens: ArcTunnelOpenHandler? = null

            @Volatile
            var control: ArcTunnelControl? = null
        }

        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
        private val mutex = Mutex()

        @Volatile
        private var listenerHost: TunnelListenerHost? = null

        @Volatile
        private var session: Session? = null

        /** Test seam for the Certificate Transparency monitor (API base, random source, minimum interval). */
        internal var ctOptions: ArcTunnelCtMonitor.Options = ArcTunnelCtMonitor.Options()

        /** Test seam: the certificate-swap poll interval. */
        internal var swapPollMs: Long = SWAP_POLL_MS

        /** Must be called before [start] with the host that serves the MCP application. */
        fun bind(host: TunnelListenerHost) {
            listenerHost = host
        }

        /**
         * Returns as soon as the session is launched; the status moves on from `Connecting` asynchronously.
         * A second start while running is ignored (logged) and leaves the live status untouched.
         */
        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                if (session != null) {
                    Log.w(TAG, "Free E2E tunnel already running; start ignored")
                    return@withLock
                }
                val launched =
                    runCatching {
                        val host = checkNotNull(listenerHost) { "bind() must be called before start()" }
                        val handler =
                            CoroutineExceptionHandler { _, t -> Log.e(TAG, "unexpected tunnel session failure", t) }
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
                        Session(host, scope, ArcTunnelEnv.from(config), config.arcTunnelCtCheckEnabled)
                    }
                launched.onSuccess {
                    session = it
                    _status.value = TunnelStatus.Connecting
                    it.scope.launch { bootstrap(it) }
                }
                launched.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "Failed to start the free E2E tunnel", it)
                    _status.value = TunnelStatus.Error(it.message ?: it.javaClass.simpleName)
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                session?.let { close(it) }
                session = null
                _status.value = TunnelStatus.Disconnected
            }
        }

        /**
         * The session's own start-up; any failure ends the session with an `Error` status (the next `stop`
         * disposes it). `readyIdentity` waits for an in-flight enrollment and issues a missing certificate.
         */
        private suspend fun bootstrap(s: Session) {
            val outcome =
                runCatching {
                    val identity = enrollment.readyIdentity(s.env) ?: error(NOT_ENROLLED_MESSAGE)
                    val net = identity.net
                    val monitor =
                        ArcTunnelCtMonitor(
                            ArcTunnelCtMonitor.Deps(identity, enrollment.store(s.env), keys, ctAlerts),
                            s.scope,
                            s.ctCheckEnabled,
                            ctOptions,
                        )
                    s.identity = identity
                    s.monitor = monitor
                    startListener(s, identity, null)
                    monitor.restoreAlert()
                    val events = Events(s)
                    val dialBack = net.dialBack(s.env.hosts.controlHost, identity)
                    val opens = ArcTunnelOpenHandler(dialBack, { s.port.get() }, events)
                    val control =
                        ArcTunnelControl(net.controlClient(identity), net, s.env.hosts.controlHost, opens, events)
                    s.opens = opens
                    s.control = control
                    s.scope.launch { control.run() }
                    s.scope.launch { swapLoop(s) }
                }
            outcome.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "Free E2E tunnel session failed", it)
                _status.value = TunnelStatus.Error(it.message ?: it.javaClass.simpleName)
                withContext(NonCancellable + Dispatchers.IO) { s.host.stopTlsListener() }
                s.scope.cancel()
            }
        }

        /** Starts (or restarts) the listener; a cancellation that lands during the blocking start stops it again. */
        private suspend fun startListener(
            s: Session,
            identity: ArcTunnelIdentity,
            port: Int?,
        ) {
            try {
                withContext(Dispatchers.IO) {
                    s.port.set(s.host.startTlsListener(identity.tlsMaterial(), port) { s.monitor?.onRequest() })
                    s.servedNotAfterMs.set(identity.publicLeaf()?.notAfter?.time ?: 0L)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { s.host.stopTlsListener() }
                throw e
            }
        }

        /**
         * Unblock the control read, cancel every child (their OkHttp calls are cancelled with them), wait at most
         * [STOP_JOIN_MS], then stop the listener and the connection pool.
         */
        private suspend fun close(s: Session) {
            s.control?.stop()
            val job = s.scope.coroutineContext[Job]
            job?.cancel()
            withTimeoutOrNull(STOP_JOIN_MS) { job?.join() }
            withContext(Dispatchers.IO) {
                s.host.stopTlsListener()
                s.monitor?.close()
                s.identity?.net?.close()
            }
        }

        private suspend fun swapLoop(s: Session) {
            while (s.scope.isActive) {
                delay(swapPollMs)
                val splices = s.opens?.activeSplices() ?: 0
                if (s.certSwap.isDue(splices, s.servedNotAfterMs.get())) swap(s)
            }
        }

        /**
         * Restart the listener on the SAME port with the renewed chain; a failure is retried at the next
         * poll.
         */
        private suspend fun swap(s: Session) {
            val identity = s.identity ?: return
            val started = s.certSwap.pending()
            val result =
                runCatching {
                    withContext(Dispatchers.IO) { s.host.stopTlsListener() }
                    startListener(s, identity, s.port.get())
                }
            result.onSuccess {
                s.certSwap.done(started)
                if (s.connected.get()) _status.value = connectedStatus(s)
                serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel certificate renewed and installed")
            }
            result.onFailure {
                if (it is CancellationException) throw it
                // The listener is down until the next poll succeeds: say so instead of showing Connected.
                Log.e(TAG, "Tunnel listener restart failed; retrying at the next poll", it)
                _status.value = TunnelStatus.Error("Listener restart failed; retrying: ${it.message}")
            }
        }

        private fun connectedStatus(s: Session): TunnelStatus.Connected =
            TunnelStatus.Connected(
                endpoints = listOf(TunnelEndpoint(url = s.identity?.publicUrl().orEmpty(), valid = true)),
                providerType = TunnelProviderType.ARC_TUNNEL,
            )

        private inner class Events(
            private val s: Session,
        ) : ArcTunnelControlEvents {
            override fun onBound(node: String) {
                s.connected.set(true)
                Log.i(TAG, "Free E2E tunnel control bound via $node")
                _status.value = connectedStatus(s)
            }

            override fun onUnbound() {
                if (s.connected.compareAndSet(true, false)) _status.value = TunnelStatus.Connecting
            }

            @Suppress("TooGenericExceptionCaught")
            override suspend fun onRenewNudge(nonce: String) {
                val identity = s.identity ?: return
                try {
                    enrollment.renew(identity, nonce)
                    s.certSwap.schedule()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "renewal failed; keeping the current certificate", e)
                    serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel renewal failed: ${e.message}")
                }
            }

            override fun onDialBackFailed(message: String) {
                Log.w(TAG, message)
            }
        }

        companion object {
            private const val TAG = "MCP:ArcTunnelProvider"
            private const val SWAP_POLL_MS = 5_000L
            private const val STOP_JOIN_MS = 1_000L
            private const val NOT_ENROLLED_MESSAGE = "Not enrolled: enable Remote Access in Settings → Tunnel first"
        }
    }
```

Context: `start` only launches so the manager's mutex and the service's start path return at once; a bootstrap that fails after the listener started stops it again; the session object stays until `stop()` disposes it (the service always stops the tunnel before restarting). The stop budget: control call cancelled, dial-backs closed through the stream's sessions, HTTP calls cancelled with their coroutines, join capped at 1 s, listener stop ≤ 0.6 s — under the service's 3 s. Status transitions are logged by the service's tunnel-status observer. The renewal catch is the D18 boundary; the `CoroutineExceptionHandler` lambda is not a catch.

[x] **Action 7.2.2** — modify `A/services/tunnel/TunnelProvider.kt` (KDoc of `start` only):

```kotlin
    /**
     * Starts the tunnel, forwarding traffic from a public URL to [localPort] on localhost.
     *
     * Returns once the tunnel is established (status [TunnelStatus.Connected]) or failed
     * (status [TunnelStatus.Error]) — or, for a provider that bootstraps asynchronously
     * (`ArcTunnelProvider`), once the start was accepted with status [TunnelStatus.Connecting].
     * A second start while running is either ignored (logged) or rejected with
     * [IllegalStateException], depending on the provider; it never alters a live status.
     *
     * @param localPort The local port to forward traffic to.
     * @param config The current server configuration (providers extract their own
     *               relevant fields, e.g. ngrok reads [ServerConfig.ngrokAuthtoken]
     *               and [ServerConfig.ngrokDomain]).
     * @throws IllegalStateException if a provider rejects a second start while running.
     */
```

Definition of Done:
- [x] Provider and the `TunnelProvider` KDoc as written.

### [x] Task 7.3 — Manager, service and ADB wiring

[x] **Action 7.3.1** — create `A/services/tunnel/TunnelHttpsGuard.kt` (shared by the manager and the service; lives in `services/tunnel` so no package cycle):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType

/** Cloudflare/ngrok proxy an http://localhost origin and cannot run with HTTPS; the free E2E tunnel can. */
internal fun tunnelAllowedWithHttps(config: ServerConfig): Boolean =
    !config.httpsEnabled || config.tunnelProvider == TunnelProviderType.ARC_TUNNEL
```

[x] **Action 7.3.2** — modify `A/services/tunnel/TunnelManager.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelProvider

// constructor: add after ngrokTunnelProviderFactory
        private val arcTunnelProviderFactory: Provider<ArcTunnelProvider>,

// start(): new signature, guard and selection
        suspend fun start(
            localPort: Int,
            listenerHost: TunnelListenerHost,
        ) {
            mutex.withLock {
                val config = settingsRepository.serverConfig.first()

                if (!config.tunnelEnabled) return

                // Cloudflare and ngrok forward to an http://localhost origin, so they MUST NOT run while the
                // server serves HTTPS; the free E2E tunnel terminates TLS on its own loopback listener.
                if (!tunnelAllowedWithHttps(config)) return

                val provider =
                    when (config.tunnelProvider) {
                        TunnelProviderType.ARC_TUNNEL -> arcTunnelProviderFactory.get().also { it.bind(listenerHost) }
                        TunnelProviderType.CLOUDFLARE -> cloudflareTunnelProviderFactory.get()
                        TunnelProviderType.NGROK -> ngrokTunnelProviderFactory.get()
                    }
                // … the rest of start() is unchanged …
```

[x] **Action 7.3.3** — modify `A/services/mcp/McpServerService.kt` (tunnel start block in `startServer`, plus one top-level function next to `serverStatusLogMessage`; add `import com.danielealbano.androidremotecontrolmcp.services.tunnel.tunnelAllowedWithHttps`):

```kotlin
            // Start tunnel if remote access is enabled. Cloudflare/ngrok target an http://localhost origin,
            // so they MUST NOT run while the server serves HTTPS; the free E2E tunnel has its own TLS listener.
            if (!tunnelAllowedWithHttps(config)) {
                Log.i(TAG, "Remote access tunnel disabled while HTTPS is enabled")
                tunnelManager.stop()
            } else {
                @Suppress("TooGenericExceptionCaught")
                try {
                    tunnelManager.start(config.port, requireNotNull(mcpServer))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
                }
            }

// top-level, after serverStatusLogMessage
/** True while the server is starting or running (enrollment and rotation are refused then). */
internal fun isServerActive(status: ServerStatus): Boolean =
    when (status) {
        is ServerStatus.Starting, is ServerStatus.Running -> true
        else -> false
    }
```

[x] **Action 7.3.4** — modify `A/services/mcp/AdbConfigHandler.kt` and `A/services/mcp/AdbConfigReceiver.kt` (D20):

```kotlin
// AdbConfigHandler: imports (add)
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv

// AdbConfigHandler constructor: add a fourth parameter
    private val arcTunnelEnrollment: ArcTunnelEnrollment,

// handleConfigure: LAST, after applyStorageLocationPermissions(intent)
        applyArcTunnelEnroll(intent)

// new function
    /**
     * `--ez arc_tunnel_enroll true`: enrolls (or resumes issuance) in the background with the settings just
     * applied.
     */
    private suspend fun applyArcTunnelEnroll(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_ARC_TUNNEL_ENROLL, false)) return
        if (isServerActive(McpServerService.serverStatus.value)) {
            Log.w(TAG, "arc_tunnel_enroll ignored: stop the MCP server first")
            return
        }
        val env = ArcTunnelEnv.from(settingsRepository.getServerConfig())
        arcTunnelEnrollment.requestEnroll(env)
        Log.i(TAG, "Free E2E tunnel enrollment requested for ${env.baseAddress}")
    }

// companion
        internal const val EXTRA_ARC_TUNNEL_ENROLL = "arc_tunnel_enroll"

// AdbConfigReceiver: inject and pass it (the statement wraps: 126 characters on one line)
    @Inject
    lateinit var arcTunnelEnrollment: ArcTunnelEnrollment
    …
        val handler =
            AdbConfigHandler(settingsRepository, storageLocationProvider, serverLogRepository, arcTunnelEnrollment)

// AdbConfigReceiver KDoc usage block: change the tunnel_provider example and add three lines after ngrok_domain
 *   --es tunnel_provider "android-remote-control-mcp-tunneld" \
 *   --es ngrok_authtoken "your-ngrok-token" \
 *   --es ngrok_domain "your-domain.ngrok-free.app" \
 *   --es arc_tunnel_base_address "arc-tunnel.io" \
 *   --ez arc_tunnel_ct_check_enabled true \
 *   --ez arc_tunnel_enroll true \
```

Context: on Android 12+ a receiver cannot start a foreground service from the background, so `--ez arc_tunnel_enroll true` works when the app is visible (or on a device that allows it) and otherwise ends in `Failed(START_REFUSED)`; the trampoline action below is the headless way (same mechanism the README already prescribes for starting the server).

[x] **Action 7.3.5** — modify `A/services/mcp/AdbServiceTrampolineActivity.kt` (D20, headless enrollment with the activity's foreground exemption):

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollService

// KDoc usage block: add
 * # Enroll the free E2E tunnel (or resume a failed certificate issuance) with the saved settings
 * adb shell am start \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
 *   --es action enroll-tunnel

// onCreate: the existing `when` moves into handle(); onCreate becomes
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent.getStringExtra(EXTRA_ACTION))
        finish()
    }

    /** Dispatches one trampoline action (internal so the JVM tests can call it on a spy). */
    internal fun handle(action: String?) {
        Log.i(TAG, "Trampoline launched with action: $action")
        when (action) {
            ACTION_VALUE_START -> {
                Log.i(TAG, "Starting McpServerService via trampoline")
                val serviceIntent =
                    Intent(this, McpServerService::class.java).apply {
                        this.action = McpServerService.ACTION_START
                    }
                startForegroundService(serviceIntent)
            }

            ACTION_VALUE_STOP -> {
                Log.i(TAG, "Stopping McpServerService via trampoline")
                val serviceIntent =
                    Intent(this, McpServerService::class.java).apply {
                        this.action = McpServerService.ACTION_STOP
                    }
                startForegroundService(serviceIntent)
            }

            ACTION_VALUE_ENROLL_TUNNEL -> {
                if (isServerActive(McpServerService.serverStatus.value)) {
                    Log.w(TAG, "enroll-tunnel ignored: stop the MCP server first")
                } else {
                    Log.i(TAG, "Starting ArcTunnelEnrollService via trampoline")
                    ArcTunnelEnrollService.start(this, env = null, rotate = false)
                }
            }

            else -> {
                Log.w(TAG, "Unknown or missing action: '$action' (expected 'start', 'stop' or 'enroll-tunnel')")
            }
        }
    }

// companion
        internal const val ACTION_VALUE_ENROLL_TUNNEL = "enroll-tunnel"
```

Definition of Done:
- [x] Manager, service guard functions, ADB handler/receiver and trampoline as written.

### [x] Task 7.4 — Tests

**File**: `TA/ArcTunnelCertSwapTest.kt`

| Test | Verifies |
|------|----------|
| `not due without a schedule` | |
| `due at once when idle` | 0 splices |
| `waits while busy within 24h` | 1 splice, 1 h elapsed, cert valid → false |
| `due after 24h even when busy` | |
| `due when the served cert expires within 5 minutes even when busy` | |
| `done clears only the request it started with` | schedule(t1), schedule(t2), done(t1) → still pending |

**File**: `TA/ArcTunnelProviderTest.kt` (MockK `ArcTunnelEnrollment` whose `readyIdentity` returns a real `ArcTunnelTestIdentity` built with `FakeArcTunnelKeyStore` + `ArcTunnelTestServer.net(port)` and whose `store(env)` returns a real `ArcTunnelIdentityStore` under `@TempDir` holding that identity; the config uses base `example.test` so the control host matches the test certificate; MockK `TunnelListenerHost` whose `startTlsListener` returns a port and records the `onRequest` lambda; MockK `ArcTunnelCtAlerts`; `RecordingServerLogRepository`; the control stream is a real `ArcTunnelTestServer` stream (`hold()`), so `Connected` is observed for real; `runBlocking` with `withTimeout(30_000)` per test; `status.first { … }` to await transitions)

| Test | Verifies |
|------|----------|
| `start without bind reports Error` | status Error mentions `bind()` |
| `start without a saved identity reports Error` | message mentions enrollment |
| `start returns Connecting at once then Connected after the stream binds` | `start()` returns < 1 s; `readyIdentity` → `startTlsListener(…, null, …)` → `Connected(https://<name>.d.<base>)` |
| `start while running is ignored and keeps the status` | second `start()` → no second `readyIdentity`, status unchanged |
| `missing TLS key reports Error` | `FakeArcTunnelKeyStore` without the tls alias → Error mentions "TLS key" |
| `listener failure reports Error and leaves nothing running` | `startTlsListener` throws → Error, `stopTlsListener` called, `stop()` afterwards is clean |
| `control drop reports Connecting and the next bind reports Connected again` | server resets the first stream, holds the second |
| `stop tears down control scope and listener` | `stopTlsListener` once, Disconnected, `run()` ended |
| `stop is a no-op when not started` | |
| `stop completes within 2 seconds while a renewal is blocked` | `enrollment.renew` blocks in `withContext(NonCancellable) { delay(10_000) }` after a RENEW_NUDGE → `stop()` returns in < 2 s |
| `stop completes within 2 seconds while a dial-back is blocked` | an OPEN to a peer that accepts but never answers → `stop()` < 2 s, the peer sees EOF |
| `stop completes within 2 seconds while a CT check is blocked` | `provider.ctOptions = Options(baseUrl = <mock CT server holding its answer>, minIntervalMs = 0, random = ZeroRandom)`, `ctCheckEnabled = true`; one call of the recorded `onRequest` lambda → the CT request is in flight → `stop()` < 2 s and the mock server sees the call cancelled |
| `renew nudge schedules a swap and the idle swap restarts the listener on the same port` | RENEW_NUDGE frame → `renew` called → `stopTlsListener` + `startTlsListener(…, <same port>, …)` |
| `renewal failure keeps the current listener` | `renew` throws `ArcTunnelApiException` → no restart, log entry |
| `a failed swap reports Error and the next poll recovers` | `provider.swapPollMs = 200`; `startTlsListener` throws once on the restart, then succeeds → status `Error` then `Connected` again, one log entry |
| `restoreAlert re-raises a persisted alert at start` | saved `ct_alert_pubkey` → `ctAlerts.restore` |

**File**: `T/services/tunnel/TunnelHttpsGuardTest.kt` (create)

| Test | Verifies |
|------|----------|
| `tunnelAllowedWithHttps allows every provider without HTTPS` | |
| `tunnelAllowedWithHttps allows only ARC_TUNNEL with HTTPS` | CLOUDFLARE/NGROK false, ARC_TUNNEL true |

**File**: `T/services/mcp/McpServerServiceGuardsTest.kt` (create)

| Test | Verifies |
|------|----------|
| `isServerActive is true for Starting and Running only` | Stopped/Stopping/Error false |

**File**: `T/services/tunnel/TunnelManagerTest.kt` (modify): `createManager()` gains `arcTunnelProviderFactory = mockk { every { get() } returns mockArcProvider }`; every existing `start` call passes `mockk<TunnelListenerHost>()`; the existing `start with https enabled does not start tunnel` test sets `tunnelProvider = TunnelProviderType.CLOUDFLARE` explicitly (the default is now ARC); add

| Test | Verifies |
|------|----------|
| `start with ARC_TUNNEL binds the listener host and starts the provider` | `bind(host)` then `start(8080, config)` |
| `start with ARC_TUNNEL and HTTPS enabled still starts` | |
| `start with NGROK and HTTPS enabled does nothing` | |

**File**: `T/services/mcp/AdbConfigHandlerTest.kt` (modify): the handler gets a `mockk<ArcTunnelEnrollment>(relaxed = true)`; `McpServerService.serverStatus` is read through the companion (set `Stopped` in the test); add `arc_tunnel_enroll true requests enrollment with the applied base address`, `arc_tunnel_enroll absent does nothing` and `arc_tunnel_enroll is ignored while the server runs` (`mockkObject(McpServerService.Companion)` returning `Running`).

**File**: `T/services/mcp/AdbServiceTrampolineActivityTest.kt` (modify). **Setup** for the new rows: `val activity = spyk<AdbServiceTrampolineActivity>()` with `every { activity.startForegroundService(any()) } returns null`; `mockkObject(ArcTunnelEnrollService.Companion)` with `every { ArcTunnelEnrollService.start(any(), any(), any()) } returns true`; `mockkObject(McpServerService.Companion)` with `every { McpServerService.serverStatus } returns MutableStateFlow(<status>)`; `mockkConstructor(Intent::class)` with `setAction` answering `self`; call `activity.handle(action)` directly (the mockable `Activity` is never created through the framework).

| Test | Verifies |
|------|----------|
| `handle start starts the server service` | `anyConstructed<Intent>().setAction(McpServerService.ACTION_START)` and one `startForegroundService` |
| `handle enroll-tunnel starts the enrollment service` | `ArcTunnelEnrollService.start(activity, null, false)` |
| `handle enroll-tunnel is ignored while the server runs` | status `Running` → `start` never called |
| `handle unknown action does nothing` | |

Definition of Done:
- [x] One test per row (new and modified test files).

---

## [x] User Story 8 — UI

Why: D1, D2, D4, D16, D19 — provider selection, enrollment dialog on the enable transition, "Set up tunnel" button, URL with refresh, CT toggle and alert banner, home card button. One activity-scoped `ArcTunnelViewModel` is threaded through the screens so the home button and the settings dialog share the same state.

Acceptance criteria:
- [x] Settings → Tunnel lists "Free E2E tunnel" first with the D1 description; the provider list is visible and selectable whenever the server is stopped, also with Remote Access off (D19); HTTPS only disables the Cloudflare/ngrok fields; selecting the free tunnel shows the base address field, the CT switch and, once enrolled, the URL with a refresh icon; while the saved URL is being loaded no dialog can appear.
- [x] Turning Remote Access on with this provider (toggle or home button) opens the enrollment dialog exactly once; Cancel/outside tap turns Remote Access off; Continue enrolls; an enabled-but-not-enrolled state shows "Set up tunnel"; a failure shows the reason with Retry (issuance resumes, D4); the dialog never reappears for an enrolled user or while typing a base address.
- [x] The home card offers "Enable free E2E tunnel" / "Enable Wi-Fi access" / "Other tunnels"; a CT alert shows a dismissible banner with "Change name"; a persisted undismissed alert is shown again after a process restart.

### [x] Task 8.1 — ViewModel

[x] **Action 8.1.1** — create `A/ui/viewmodels/ArcTunnelViewModel.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlert
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlerts
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The saved public URL of the free E2E tunnel: unknown until the store was read, then absent or present. */
sealed class ArcTunnelUrlState {
    data object Loading : ArcTunnelUrlState()

    data object NotEnrolled : ArcTunnelUrlState()

    data class Enrolled(
        val url: String,
    ) : ArcTunnelUrlState()
}

/**
 * UI state and actions of the free E2E tunnel provider (enrollment, URL, CT toggle, CT alert). ONE instance is
 * created at `MainScreen` level and passed to the Server and Settings screens (shared enrollment request).
 */
@HiltViewModel
class ArcTunnelViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val enrollment: ArcTunnelEnrollment,
        private val ctAlerts: ArcTunnelCtAlerts,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _baseAddressInput = MutableStateFlow(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)
        val baseAddressInput: StateFlow<String> = _baseAddressInput.asStateFlow()

        private val _baseAddressValid = MutableStateFlow(true)
        val baseAddressValid: StateFlow<Boolean> = _baseAddressValid.asStateFlow()

        private val _url = MutableStateFlow<ArcTunnelUrlState>(ArcTunnelUrlState.Loading)
        val url: StateFlow<ArcTunnelUrlState> = _url.asStateFlow()

        /** True from "Remote Access turned on with this provider" until the dialog was answered. */
        private val _enrollmentRequested = MutableStateFlow(false)
        val enrollmentRequested: StateFlow<Boolean> = _enrollmentRequested.asStateFlow()

        val enrollmentState: StateFlow<ArcTunnelEnrollmentState> = enrollment.state
        val ctAlert: StateFlow<ArcTunnelCtAlert?> = ctAlerts.alert

        @Volatile
        private var env: ArcTunnelEnv = ArcTunnelEnv(ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS)

        /** The persisted base address; null until the first configuration was read. */
        @Volatile
        private var persistedBaseAddress: String? = null

        init {
            viewModelScope.launch(ioDispatcher) {
                var previousSelected = false
                var alertRestored = false
                settingsRepository.serverConfig.collect { config ->
                    env = ArcTunnelEnv.from(config)
                    // The input mirrors the PERSISTED value only when that value changes, so a config emission
                    // (e.g. the CT switch) never overwrites what the user is typing.
                    if (config.arcTunnelBaseAddress != persistedBaseAddress) {
                        persistedBaseAddress = config.arcTunnelBaseAddress
                        _baseAddressInput.value = config.arcTunnelBaseAddress
                        _baseAddressValid.value = true
                    }
                    val selected = config.tunnelEnabled && config.tunnelProvider == TunnelProviderType.ARC_TUNNEL
                    // The request survives the provider-only emission of enableFreeTunnel (not selected → not
                    // selected) and is dropped only when the selection is LOST (Cancel, other provider).
                    if (previousSelected && !selected) _enrollmentRequested.value = false
                    previousSelected = selected
                    refreshUrl()
                    if (!alertRestored) {
                        alertRestored = true
                        if (ctAlerts.alert.value == null) enrollment.savedCtAlert(env)?.let { ctAlerts.restore(it) }
                    }
                }
            }
            viewModelScope.launch(ioDispatcher) {
                var previous: ArcTunnelEnrollmentState = ArcTunnelEnrollmentState.Idle
                enrollment.state.collect { state ->
                    if (previous is ArcTunnelEnrollmentState.Enrolling) refreshUrl()
                    previous = state
                }
            }
        }

        /** Validates live; nothing is persisted until [commitBaseAddress] (so no environment flips while typing). */
        fun updateBaseAddressInput(value: String) {
            _baseAddressInput.value = value
            _baseAddressValid.value = ArcTunnelHosts.isValidBaseAddress(value)
        }

        /** Persists the typed base address when valid and changed (focus loss); nothing before the first config. */
        fun commitBaseAddress() {
            val persisted = persistedBaseAddress ?: return
            val value = _baseAddressInput.value
            if (!ArcTunnelHosts.isValidBaseAddress(value) || value == persisted) return
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateArcTunnelBaseAddress(value) }
        }

        fun updateCtCheckEnabled(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateArcTunnelCtCheckEnabled(enabled) }
        }

        /** Home button: selects the provider, turns Remote Access on, then (both writes done) requests the dialog. */
        fun enableFreeTunnel() {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateTunnelProvider(TunnelProviderType.ARC_TUNNEL)
                settingsRepository.updateTunnelEnabled(true)
                _enrollmentRequested.value = true
            }
        }

        /** The settings toggle turned Remote Access on while this provider was selected. */
        fun requestEnrollment() {
            _enrollmentRequested.value = true
        }

        /**
         * Dialog "Continue", "Set up tunnel" and "Retry": enrolls against the TYPED base address (persisted
         * first when it changed — a button press does not take the focus from the text field).
         */
        fun enroll() {
            _enrollmentRequested.value = false
            val typed = _baseAddressInput.value
            val target = if (ArcTunnelHosts.isValidBaseAddress(typed)) env.copy(baseAddress = typed) else env
            viewModelScope.launch(ioDispatcher) {
                if (target.baseAddress != persistedBaseAddress) {
                    settingsRepository.updateArcTunnelBaseAddress(target.baseAddress)
                }
                enrollment.requestEnroll(target)
            }
        }

        /** Dialog "Cancel" / outside tap: Remote Access goes back off. */
        fun cancelEnrollment() {
            _enrollmentRequested.value = false
            viewModelScope.launch(ioDispatcher) { settingsRepository.updateTunnelEnabled(false) }
        }

        /** Refresh icon, confirmed: new name + new keys; a pending CT alert belongs to the old name and goes first. */
        fun rotateName() {
            viewModelScope.launch(ioDispatcher) {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
                enrollment.requestRotate(env)
            }
        }

        /** The persisted alert is cleared BEFORE the banner hides, so a config emission cannot restore it. */
        fun dismissCtAlert() {
            viewModelScope.launch(ioDispatcher) {
                enrollment.dismissCtAlert(env)
                ctAlerts.dismiss()
            }
        }

        private fun refreshUrl() {
            val url = enrollment.savedUrl(env)
            _url.value = url?.let { ArcTunnelUrlState.Enrolled(it) } ?: ArcTunnelUrlState.NotEnrolled
            if (url != null) _enrollmentRequested.value = false
        }
    }
```

Context: `ArcTunnelEnv` is a data class whose `hosts` is derived from `baseAddress`, so `env.copy(baseAddress = typed)` carries the typed base with the current debug overrides. `savedUrl` is null until a certificate is saved (US5), so a failed issuance shows "Set up tunnel"/Retry, never a URL.

Definition of Done:
- [x] ViewModel as written.

### [x] Task 8.2 — Settings section and screen

[x] **Action 8.2.1** — create `A/ui/screens/settings/ArcTunnelSettingsSection.kt`:

```kotlin
@file:Suppress("FunctionNaming", "LongMethod")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelUrlState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

private const val PROGRESS_SIZE_DP = 16

data class ArcTunnelSettingsState(
    val tunnelEnabled: Boolean,
    val enabled: Boolean,
    val ctCheckEnabled: Boolean,
    val baseAddress: String,
    val baseAddressValid: Boolean,
    val url: ArcTunnelUrlState,
    val enrollmentState: ArcTunnelEnrollmentState,
    val enrollmentRequested: Boolean,
)

data class ArcTunnelSettingsActions(
    val onBaseAddressChange: (String) -> Unit,
    val onBaseAddressCommit: () -> Unit,
    val onCtCheckChange: (Boolean) -> Unit,
    val onEnroll: () -> Unit,
    val onCancelEnrollment: () -> Unit,
    val onRotateName: () -> Unit,
)

/**
 * The dialog shows only for a pending request, with Remote Access on, nothing enrolled, nothing running,
 * server stopped.
 */
internal fun arcTunnelEnrollDialogVisible(state: ArcTunnelSettingsState): Boolean =
    state.enrollmentRequested &&
        state.tunnelEnabled &&
        state.url is ArcTunnelUrlState.NotEnrolled &&
        state.enrollmentState !is ArcTunnelEnrollmentState.Enrolling &&
        state.enabled

/** "Set up tunnel" covers the enabled-but-not-enrolled idle state (a failure shows Retry instead). */
internal fun arcTunnelSetUpVisible(state: ArcTunnelSettingsState): Boolean =
    state.url is ArcTunnelUrlState.NotEnrolled &&
        state.tunnelEnabled &&
        state.enrollmentState is ArcTunnelEnrollmentState.Idle

/** Stateful entry point: collects the ViewModel and hands plain state + callbacks to [ArcTunnelSettingsContent]. */
@Composable
fun ArcTunnelSettingsSection(
    tunnelEnabled: Boolean,
    enabled: Boolean,
    ctCheckEnabled: Boolean,
    viewModel: ArcTunnelViewModel,
) {
    val baseAddress by viewModel.baseAddressInput.collectAsStateWithLifecycle()
    val baseAddressValid by viewModel.baseAddressValid.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val enrollmentState by viewModel.enrollmentState.collectAsStateWithLifecycle()
    val enrollmentRequested by viewModel.enrollmentRequested.collectAsStateWithLifecycle()
    ArcTunnelSettingsContent(
        state =
            ArcTunnelSettingsState(
                tunnelEnabled = tunnelEnabled,
                enabled = enabled,
                ctCheckEnabled = ctCheckEnabled,
                baseAddress = baseAddress,
                baseAddressValid = baseAddressValid,
                url = url,
                enrollmentState = enrollmentState,
                enrollmentRequested = enrollmentRequested,
            ),
        actions =
            ArcTunnelSettingsActions(
                onBaseAddressChange = viewModel::updateBaseAddressInput,
                onBaseAddressCommit = viewModel::commitBaseAddress,
                onCtCheckChange = viewModel::updateCtCheckEnabled,
                onEnroll = viewModel::enroll,
                onCancelEnrollment = viewModel::cancelEnrollment,
                onRotateName = viewModel::rotateName,
            ),
    )
}

@Composable
fun ArcTunnelSettingsContent(
    state: ArcTunnelSettingsState,
    actions: ArcTunnelSettingsActions,
) {
    val enrolling = state.enrollmentState is ArcTunnelEnrollmentState.Enrolling
    var showRotateDialog by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.remote_access_arc_tunnel_base_address_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.baseAddress,
            onValueChange = actions.onBaseAddressChange,
            singleLine = true,
            enabled = state.enabled,
            isError = !state.baseAddressValid,
            supportingText =
                if (state.baseAddressValid) {
                    null
                } else {
                    { Text(stringResource(R.string.remote_access_arc_tunnel_base_address_error)) }
                },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) actions.onBaseAddressCommit() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_ct_check_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_ct_check_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.ctCheckEnabled, onCheckedChange = actions.onCtCheckChange, enabled = state.enabled)
        }
        Spacer(modifier = Modifier.height(8.dp))
        UrlRow(state, enrolling, onRotate = { showRotateDialog = true }, onEnroll = actions.onEnroll)
        EnrollmentProgress(state.enrollmentState, enabled = state.enabled, onRetry = actions.onEnroll)
    }

    if (arcTunnelEnrollDialogVisible(state)) {
        ConfirmDialog(
            title = R.string.remote_access_arc_tunnel_enroll_dialog_title,
            body = R.string.remote_access_arc_tunnel_enroll_dialog_body,
            onConfirm = actions.onEnroll,
            onDismiss = actions.onCancelEnrollment,
        )
    }
    if (showRotateDialog) {
        ConfirmDialog(
            title = R.string.remote_access_arc_tunnel_rotate_dialog_title,
            body = R.string.remote_access_arc_tunnel_rotate_dialog_body,
            onConfirm = {
                showRotateDialog = false
                actions.onRotateName()
            },
            onDismiss = { showRotateDialog = false },
        )
    }
    LaunchedEffect(state.enabled) { if (!state.enabled) showRotateDialog = false }
}

@Composable
private fun UrlRow(
    state: ArcTunnelSettingsState,
    enrolling: Boolean,
    onRotate: () -> Unit,
    onEnroll: () -> Unit,
) {
    when (val url = state.url) {
        is ArcTunnelUrlState.Loading -> {
            Text(stringResource(R.string.remote_access_arc_tunnel_url_loading))
        }

        is ArcTunnelUrlState.Enrolled -> {
            OutlinedTextField(
                value = url.url,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.remote_access_arc_tunnel_url_label)) },
                trailingIcon = {
                    IconButton(onClick = onRotate, enabled = state.enabled && !enrolling) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.remote_access_arc_tunnel_url_refresh),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ArcTunnelUrlState.NotEnrolled -> {
            if (arcTunnelSetUpVisible(state)) {
                TextButton(onClick = onEnroll, enabled = state.enabled) {
                    Text(stringResource(R.string.remote_access_arc_tunnel_set_up))
                }
            }
        }
    }
}

@Composable
private fun EnrollmentProgress(
    state: ArcTunnelEnrollmentState,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    when (state) {
        is ArcTunnelEnrollmentState.Idle -> {
            // nothing to show
        }

        is ArcTunnelEnrollmentState.Enrolling -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE_DP.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_enrolling),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ArcTunnelEnrollmentState.Failed -> {
            Column {
                Text(
                    text = stringResource(R.string.remote_access_arc_tunnel_enroll_failed, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry, enabled = enabled) {
                    Text(stringResource(R.string.remote_access_arc_tunnel_retry))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: Int,
    body: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remote_access_arc_tunnel_enroll_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_access_arc_tunnel_enroll_dialog_cancel))
            }
        },
    )
}
```

[x] **Action 8.2.2** — modify `A/ui/screens/settings/TunnelSettingsScreen.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

// signature: add the shared ViewModel
fun TunnelSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    arcViewModel: ArcTunnelViewModel = hiltViewModel(),
) {

// state: REPLACE the `sectionEnabled` declaration (and its comment) with
    val arcSelected = serverConfig.tunnelProvider == TunnelProviderType.ARC_TUNNEL
    // The toggle, the provider list and the free E2E tunnel fields are editable whenever the server is
    // stopped; HTTPS only disables the Cloudflare/ngrok fields (they target http://localhost).
    val proxyFieldsEnabled = isEnabled && !serverConfig.httpsEnabled

// the HTTPS card: change its condition from `serverConfig.httpsEnabled` to
// `serverConfig.httpsEnabled && !arcSelected`; add after it
            if (serverConfig.httpsEnabled && arcSelected) {
                Text(
                    text = stringResource(R.string.remote_access_https_note_arc_tunnel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

// the Remote Access switch
                Switch(
                    checked = serverConfig.tunnelEnabled,
                    onCheckedChange = { on ->
                        viewModel.updateTunnelEnabled(on)
                        if (on && arcSelected) arcViewModel.requestEnrollment()
                    },
                    enabled = isEnabled,
                )

// The provider list is always visible: the `AnimatedVisibility(visible = serverConfig.tunnelEnabled)`
// wrapper (with its `Column`) around the
// provider selector and the provider fields is REMOVED; its inner Column content stays, always visible.

// provider rows: `enabled = isEnabled` on `selectable` and on `RadioButton`; the row body becomes
                                if (provider == TunnelProviderType.ARC_TUNNEL) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.remote_access_provider_arc_tunnel),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = stringResource(R.string.remote_access_provider_arc_tunnel_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Text(text = providerTitle(provider), style = MaterialTheme.typography.bodyLarge)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = providerDescription(provider),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
// with the two existing `when (provider)` expressions moved into
@Composable
private fun providerTitle(provider: TunnelProviderType): String =
    when (provider) {
        TunnelProviderType.ARC_TUNNEL -> stringResource(R.string.remote_access_provider_arc_tunnel)
        TunnelProviderType.CLOUDFLARE -> stringResource(R.string.remote_access_provider_cloudflare)
        TunnelProviderType.NGROK -> stringResource(R.string.remote_access_provider_ngrok)
    }

@Composable
private fun providerDescription(provider: TunnelProviderType): String =
    when (provider) {
        TunnelProviderType.ARC_TUNNEL -> stringResource(R.string.remote_access_provider_arc_tunnel_desc)
        TunnelProviderType.CLOUDFLARE -> stringResource(R.string.remote_access_provider_cloudflare_desc)
        TunnelProviderType.NGROK -> stringResource(R.string.remote_access_provider_ngrok_desc)
    }

// Cloudflare/ngrok blocks: `enabled = proxyFieldsEnabled` (was sectionEnabled)

// after the ngrok block, before the status indicator:
                    AnimatedVisibility(visible = arcSelected) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            ArcTunnelSettingsSection(
                                tunnelEnabled = serverConfig.tunnelEnabled,
                                enabled = isEnabled,
                                ctCheckEnabled = serverConfig.arcTunnelCtCheckEnabled,
                                viewModel = arcViewModel,
                            )
                        }
                    }
```

Context: the Cloudflare and ngrok rows keep their side-by-side layout (D2); only the new row wraps its long description.

Definition of Done:
- [x] Settings section file and `TunnelSettingsScreen` changes as written; `sectionEnabled` is gone.

### [x] Task 8.3 — Home card and CT banner

[x] **Action 8.3.1** — modify `A/ui/components/CalloutCard.kt` (an optional body paragraph under the title; the actions row stays right-aligned for buttons):

```kotlin
// KDoc: replace with
/**
 * The uniform compact callout card used on the server screen: gray icon + title on the first row, an
 * optional [body] paragraph, and (optionally) right-aligned action buttons.
 */

// signature: add after modifier
    body: String? = null,

// inside the Column, after the title Row and before `if (actions != null)`
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

[x] **Action 8.3.2** — modify `A/ui/screens/ServerScreen.kt`:

```kotlin
// imports (add; `ServerStatus` only if not imported yet)
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelCtAlert
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

// parameters: add after privacyViewModel
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),

// state: add
    val ctAlert by arcTunnelViewModel.ctAlert.collectAsStateWithLifecycle()

// cards: after the PendingApprovalsCard block (no branch in ServerScreen: the composable returns early itself,
// ServerScreen is at detekt's complexity limit)
            ArcTunnelCtAlertCard(
                alert = ctAlert,
                onChangeName = onNavigateToTunnelSettings,
                onDismiss = { arcTunnelViewModel.dismissCtAlert() },
            )

// NetworkAccessSuggestionCard call (replace); the buttons change settings, so they are enabled only while
// the server is stopped (a request made while it runs would pop the dialog at the next stop)
                NetworkAccessSuggestionCard(
                    enabled = serverStatus is ServerStatus.Stopped,
                    onEnableFreeTunnel = {
                        arcTunnelViewModel.enableFreeTunnel()
                        onNavigateToTunnelSettings()
                    },
                    onEnableWifi = {
                        viewModel.updateBindingAddress(BindingAddress.NETWORK)
                        onNavigateToNetworkSettings()
                    },
                    onOtherTunnels = {
                        viewModel.updateTunnelProvider(TunnelProviderType.CLOUDFLARE)
                        onNavigateToTunnelSettings()
                    },
                )

// composables (replace NetworkAccessSuggestionCard, add ArcTunnelCtAlertCard)
@Composable
private fun NetworkAccessSuggestionCard(
    enabled: Boolean,
    onEnableFreeTunnel: () -> Unit,
    onEnableWifi: () -> Unit,
    onOtherTunnels: () -> Unit,
) {
    CalloutCard(
        icon = Icons.Default.Info,
        title = stringResource(R.string.server_network_access_suggestion_title),
    ) {
        TextButton(onClick = onEnableFreeTunnel, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_arc_tunnel))
        }
        TextButton(onClick = onEnableWifi, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_wifi))
        }
        TextButton(onClick = onOtherTunnels, enabled = enabled) {
            Text(stringResource(R.string.server_network_access_suggestion_other_tunnels))
        }
    }
}

/** The Certificate Transparency banner; nothing when there is no alert. */
@Composable
private fun ArcTunnelCtAlertCard(
    alert: ArcTunnelCtAlert?,
    onChangeName: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (alert == null) return
    CalloutCard(
        icon = Icons.Default.Warning,
        title = stringResource(R.string.server_arc_tunnel_ct_alert_title),
        body = stringResource(R.string.server_arc_tunnel_ct_alert_body, alert.fqdn),
    ) {
        TextButton(onClick = onChangeName) {
            Text(stringResource(R.string.server_arc_tunnel_ct_alert_change_name))
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.server_arc_tunnel_ct_alert_dismiss))
        }
    }
    Spacer(Modifier.height(16.dp))
}
```
and remove `server_network_access_suggestion_tunnel` from `strings.xml` in the same commit (its only use was replaced above).

Context: "Other tunnels" no longer turns Remote Access on (it previously did), so it never triggers the free-tunnel dialog. The home card is shown for `bindingAddress == LOCALHOST && !tunnelEnabled` only, so its button is never offered while a tunnel runs; the dialog itself additionally requires `enabled` (server stopped).

Definition of Done:
- [x] `CalloutCard` body slot and `ServerScreen` changes as written; `server_network_access_suggestion_tunnel` removed from `strings.xml`.

### [x] Task 8.4 — ViewModel threading through the screens (leaf screens first)

[x] **Action 8.4.1** — modify `A/ui/screens/ServerTabScreen.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

// parameters: add after viewModel
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),

// ServerScreen(...) call: add
                arcTunnelViewModel = arcTunnelViewModel,
```

[x] **Action 8.4.2** — modify `A/ui/screens/SettingsScreen.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

// parameters: add after channelViewModel
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),

// the Tunnel destination:
        composable(SettingsRoute.Tunnel.route) {
            TunnelSettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
                arcViewModel = arcTunnelViewModel,
            )
        }
```

[x] **Action 8.4.3** — modify `A/ui/screens/MainScreen.kt`:

```kotlin
// imports (add)
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelViewModel

// parameters: add after updateViewModel
    arcTunnelViewModel: ArcTunnelViewModel = hiltViewModel(),

// BOTH ServerTabScreen(...) call sites: add
                    arcTunnelViewModel = arcTunnelViewModel,
// the SettingsScreen(...) call site: add
                    arcTunnelViewModel = arcTunnelViewModel,
```

Context: `MainScreen` is the root composable of `MainActivity`, so `hiltViewModel()` there is activity-scoped (exactly how `viewModel: MainViewModel` is shared today); the nested NavHosts receive the same instance explicitly instead of creating their own.

Definition of Done:
- [x] The three screens pass one activity-scoped `ArcTunnelViewModel`; the provider list order is ARC_TUNNEL, CLOUDFLARE, NGROK.

### [x] Task 8.5 — Tests

**File**: `T/ui/viewmodels/ArcTunnelViewModelTest.kt` (MockK repository with a `MutableStateFlow<ServerConfig>` as `serverConfig`; MockK `ArcTunnelEnrollment` with a `MutableStateFlow` state; MockK `ArcTunnelCtAlerts` with a `MutableStateFlow` alert; `StandardTestDispatcher` + `runTest`)

| Test | Verifies |
|------|----------|
| `url is Loading until the first config then NotEnrolled or Enrolled` | |
| `config changes refresh base address input and url` | |
| `updateBaseAddressInput validates without persisting` | invalid → `baseAddressValid` false, no repository call |
| `commitBaseAddress persists a valid changed value only` | same value → no call; invalid → no call; changed valid → `updateArcTunnelBaseAddress` |
| `commitBaseAddress does nothing before the first configuration` | Compose's initial unfocused `onFocusChanged` must not persist the default over a self-hoster's base |
| `updateCtCheckEnabled persists` | |
| `enableFreeTunnel selects the provider then enables then requests` | order verified; the request flag is set only after both writes |
| `enableFreeTunnel keeps the request across the provider-only emission` | emit (CLOUDFLARE, off) → call → emit (ARC_TUNNEL, off) then (ARC_TUNNEL, on) → `enrollmentRequested` true |
| `requestEnrollment sets the flag and an enrolled url clears it` | flag true → config with a saved URL → false |
| `request is cleared when remote access turns off or another provider is selected` | |
| `enroll clears the request and requests enrollment with the persisted env` | typed == persisted → no `updateArcTunnelBaseAddress`, `requestEnroll(env)` |
| `enroll uses the typed base address and persists it first` | typed `tunnel.example.org` ≠ persisted → `updateArcTunnelBaseAddress` then `requestEnroll(env.copy(baseAddress = "tunnel.example.org"))`, order verified |
| `enroll ignores an invalid typed base address` | typed `"x"` → `requestEnroll(env)` with the persisted base, nothing persisted |
| `a config emission does not overwrite the typed input` | type `a.b`, emit a config with the unchanged persisted base → input stays `a.b` |
| `url refreshes when the enrollment state leaves Enrolling` | Enrolling → Idle → `savedUrl` re-read |
| `cancelEnrollment clears the request and disables remote access` | |
| `rotateName clears and dismisses the alert then requests a rotation` | order verified: `dismissCtAlert(env)`, `ctAlerts.dismiss()`, `requestRotate(env)` |
| `dismissCtAlert clears the persisted alert before hiding the banner` | order verified: `dismissCtAlert(env)` then `ctAlerts.dismiss()` |
| `a persisted alert is restored once at init when none is shown` | `savedCtAlert` non-null, flow null → one `restore`; a second config emission → no second call |

**File**: `T/ui/screens/settings/ArcTunnelSettingsContentTest.kt` (the pure `arcTunnelEnrollDialogVisible`)

| Test | Verifies |
|------|----------|
| `dialog needs request tunnel on not enrolled idle and server stopped` | all true → true |
| `no dialog while loading` | |
| `no dialog when enrolled` | |
| `no dialog while enrolling` | |
| `dialog allowed after a failure when still requested` | Failed + requested → true (the user can Continue again or Cancel) |
| `no dialog while the server runs` | `enabled = false` |
| `set up button shows when enabled not enrolled and idle` | `arcTunnelSetUpVisible` true |
| `set up button hides while loading enrolled enrolling or failed` | four cases → false |

**File**: `T/ui/components/ConnectionInfoCardTest.kt` (modify): add a case with `providerType = ARC_TUNNEL` and endpoint `https://name.d.arc-tunnel.io`.

**Manual QA Steps** (UI flows without Compose UI tests in this project; recorded in the PR description):
1. Fresh install → Settings → Tunnel: provider list visible with Remote Access off, "Free E2E tunnel" first and selected; description wraps.
2. Toggle Remote Access on → dialog → Cancel → toggle is off again; toggle on → Continue → progress → URL field appears with the refresh icon.
3. Home tab with localhost binding and Remote Access off → "Enable free E2E tunnel" → lands on the tunnel settings with the dialog open; "Other tunnels" → lands on the settings with Cloudflare selected and NO dialog.
4. Start the server → tunnel status Connecting then Connected; the connection info shows `https://<name>.d.arc-tunnel.io`.
5. CT alert banner: with a persisted `ct_alert_pubkey` (debug build, `am broadcast` not needed: edit the JSON via `run-as`) the banner shows at app start; Dismiss hides it and it stays hidden after a restart; Change name opens the tunnel settings.
6. With HTTPS enabled: the Cloudflare and ngrok fields are disabled and the warning card shows while one of them is selected; with the free tunnel selected the note replaces the card and the base address, CT switch and Set up/URL stay editable.
7. Change the base address to an unreachable host, Set up tunnel → "Setup failed: …" with Retry; the URL field is absent; Retry attempts again; restoring the real base address and pressing Retry enrolls.
8. Refresh icon → confirmation dialog; Cancel/outside tap keeps the name; Continue produces a new URL and new keys (the old URL stops answering).

Definition of Done:
- [x] One test per row; the Manual QA Steps recorded in the PR description.

---

## [x] User Story 9 — Debug E2E receiver

Why: D14 — the e2e drives enrollment headlessly and points the client at a local edge: the debug-only `E2EConfigReceiver` learns the tunnel settings (incl. the two debug-only overrides), a stop-server action, an enroll action and a status query whose answer travels back in the broadcast result (`am broadcast` prints it).

Acceptance criteria:
- [x] `E2E_CONFIGURE` accepts `tunnel_enabled`, `tunnel_provider` (ADB value), `arc_tunnel_base_address`, `arc_tunnel_ct_check_enabled`, `arc_tunnel_edge_override`, `arc_tunnel_trusted_ca_pem_b64` (`-` = clear, since `am broadcast --es k ""` drops empty extras).
- [x] `E2E_STOP_SERVER`, `E2E_ARC_TUNNEL_ENROLL` and `E2E_ARC_TUNNEL_QUERY` exist; the query returns Base64 JSON of `E2EArcTunnelStatus` as the broadcast result data.
- [x] `E2EConfigReceiver` keeps ≤ 11 functions.

### [x] Task 9.1 — Status DTO, labels and receiver

[x] **Action 9.1.1** — create `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EArcTunnelStatus.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.debug

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Answer of the `E2E_ARC_TUNNEL_QUERY` broadcast (Base64 JSON in the result data). The e2e module keeps an
 * identical copy (`E2EArcTunnelStatus` in `e2e-tests`): both MUST change together.
 */
@Serializable
data class E2EArcTunnelStatus(
    @SerialName("enrolled") val enrolled: Boolean,
    @SerialName("url") val url: String?,
    @SerialName("enrollment") val enrollment: String,
    @SerialName("tunnel") val tunnel: String,
    @SerialName("tunnel_url") val tunnelUrl: String?,
    @SerialName("server") val server: String,
    @SerialName("base_address") val baseAddress: String,
    @SerialName("edge_override") val edgeOverride: String,
    @SerialName("ct_check_enabled") val ctCheckEnabled: Boolean,
    @SerialName("tunnel_enabled") val tunnelEnabled: Boolean,
    @SerialName("tunnel_provider") val tunnelProvider: String,
    @SerialName("bearer_token_enabled") val bearerTokenEnabled: Boolean,
)
```

[x] **Action 9.1.2** — create `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EArcTunnelLabels.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.debug

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState

internal fun enrollmentLabel(state: ArcTunnelEnrollmentState): String =
    when (state) {
        is ArcTunnelEnrollmentState.Idle -> "Idle"
        is ArcTunnelEnrollmentState.Enrolling -> "Enrolling"
        is ArcTunnelEnrollmentState.Failed -> "Failed: ${state.message}"
    }

internal fun tunnelLabel(status: TunnelStatus): String =
    when (status) {
        is TunnelStatus.Disconnected -> "Disconnected"
        is TunnelStatus.Connecting -> "Connecting"
        is TunnelStatus.Connected -> "Connected"
        is TunnelStatus.Error -> "Error: ${status.message}"
    }

internal fun serverLabel(status: ServerStatus): String =
    when (status) {
        is ServerStatus.Stopped -> "Stopped"
        is ServerStatus.Starting -> "Starting"
        is ServerStatus.Running -> "Running"
        is ServerStatus.Stopping -> "Stopping"
        is ServerStatus.Error -> "Error: ${status.message}"
    }
```

[x] **Action 9.1.3** — modify `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiver.kt`:

```kotlin
// imports (add)
import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.mcp.isServerActive
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts
import kotlinx.serialization.json.Json

// KDoc usage block: add after the "Start the MCP server" example
 * # Stop the MCP server
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_STOP_SERVER \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.E2EConfigReceiver
 *
 * # Free E2E tunnel: configure (override + trusted CA are debug-only; "-" clears a string extra),
 * # enroll, and query the status (Base64 JSON in the broadcast result data)
 * adb shell am broadcast -a ....E2E_CONFIGURE -n ... \
 *   --ez tunnel_enabled true --es tunnel_provider android-remote-control-mcp-tunneld \
 *   --es arc_tunnel_base_address example.test --ez arc_tunnel_ct_check_enabled false \
 *   --es arc_tunnel_edge_override 10.88.0.1:18443 --es arc_tunnel_trusted_ca_pem_b64 <base64 of the PEM>
 * adb shell am broadcast -a ....E2E_ARC_TUNNEL_ENROLL -n ...
 * adb shell am broadcast -a ....E2E_ARC_TUNNEL_QUERY -n ...

// injections (add)
    @Inject
    lateinit var tunnelManager: TunnelManager

    @Inject
    lateinit var arcTunnelEnrollment: ArcTunnelEnrollment

// onReceive `when` (add branches)
                ACTION_E2E_STOP_SERVER -> handleStopServer(context)
                ACTION_E2E_ARC_TUNNEL_ENROLL -> handleArcTunnelEnroll()
                ACTION_E2E_ARC_TUNNEL_QUERY -> handleArcTunnelQuery()

// handleConfigure: FIRST statement inside `scope.launch { … }` (the migration must precede this broadcast's writes)
            settingsRepository.ensureAuthModelMigrated()

// handleConfigure: after applyDownloadSettings(intent)
            applyTunnelSettings(intent)

// new functions
    private suspend fun applyTunnelSettings(intent: Intent) {
        if (intent.hasExtra(EXTRA_TUNNEL_ENABLED)) {
            val enabled = intent.getBooleanExtra(EXTRA_TUNNEL_ENABLED, false)
            settingsRepository.updateTunnelEnabled(enabled)
            Log.i(TAG, "Tunnel enabled updated to $enabled")
        }
        intent.getStringExtra(EXTRA_TUNNEL_PROVIDER)?.takeIf { it.isNotEmpty() }?.let { value ->
            val provider = TunnelProviderType.fromAdbValue(value)
            if (provider == null) {
                Log.w(TAG, "Unknown tunnel provider: $value")
            } else {
                settingsRepository.updateTunnelProvider(provider)
                Log.i(TAG, "Tunnel provider updated to $provider")
            }
        }
        intent.getStringExtra(EXTRA_ARC_TUNNEL_BASE_ADDRESS)?.takeIf { it.isNotEmpty() }?.let { value ->
            if (ArcTunnelHosts.isValidBaseAddress(value)) {
                settingsRepository.updateArcTunnelBaseAddress(value)
                Log.i(TAG, "Free E2E tunnel base address updated to $value")
            } else {
                Log.w(TAG, "Invalid free E2E tunnel base address: $value")
            }
        }
        if (intent.hasExtra(EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED)) {
            val enabled = intent.getBooleanExtra(EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED, true)
            settingsRepository.updateArcTunnelCtCheckEnabled(enabled)
            Log.i(TAG, "Free E2E tunnel CT check updated to $enabled")
        }
        applyArcTunnelOverride(intent)
    }

    /**
     * Debug-only edge override + trusted CA. `-` clears; an empty value counts as absent (`am broadcast` never
     * delivers one, relaxed test mocks do).
     */
    private suspend fun applyArcTunnelOverride(intent: Intent) {
        val override = intent.getStringExtra(EXTRA_ARC_TUNNEL_EDGE_OVERRIDE)?.takeIf { it.isNotEmpty() }
        val pemB64 = intent.getStringExtra(EXTRA_ARC_TUNNEL_TRUSTED_CA_PEM_B64)?.takeIf { it.isNotEmpty() }
        if (override == null && pemB64 == null) return
        val current = settingsRepository.getServerConfig()
        val newOverride = override?.let { if (it == CLEAR) "" else it } ?: current.arcTunnelEdgeOverride
        val decodedPem =
            try {
                pemB64?.let { if (it == CLEAR) "" else String(Base64.decode(it, Base64.DEFAULT)) }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "arc_tunnel_trusted_ca_pem_b64 is not Base64; keeping the stored PEM", e)
                null
            }
        val newPem = decodedPem ?: current.arcTunnelTrustedCaPem
        settingsRepository.updateArcTunnelE2EOverride(newOverride, newPem)
        Log.i(TAG, "Free E2E tunnel override updated (edge='$newOverride', trustedCaPem=${newPem.length} chars)")
    }

    private fun handleStopServer(context: Context) {
        Log.i(TAG, "Received E2E stop server broadcast")
        val intent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_STOP
            }
        context.startForegroundService(intent)
        Log.i(TAG, "McpServerService stop command sent")
    }

    private fun handleArcTunnelEnroll() {
        if (isServerActive(McpServerService.serverStatus.value)) {
            Log.w(TAG, "E2E enroll ignored: stop the MCP server first")
            return
        }
        scope.launch {
            val env = ArcTunnelEnv.from(settingsRepository.getServerConfig())
            arcTunnelEnrollment.requestEnroll(env)
            Log.i(TAG, "Free E2E tunnel enrollment requested for ${env.baseAddress}")
        }
    }

    /** Answers in the broadcast result data (`am broadcast` prints `data="…"`); [goAsync] keeps the result open. */
    private fun handleArcTunnelQuery() {
        val pending = goAsync()
        scope.launch {
            try {
                val config = settingsRepository.getServerConfig()
                val env = ArcTunnelEnv.from(config)
                val tunnel = tunnelManager.tunnelStatus.value
                val status =
                    E2EArcTunnelStatus(
                        enrolled = arcTunnelEnrollment.savedUrl(env) != null,
                        url = arcTunnelEnrollment.savedUrl(env),
                        enrollment = enrollmentLabel(arcTunnelEnrollment.state.value),
                        tunnel = tunnelLabel(tunnel),
                        tunnelUrl = (tunnel as? TunnelStatus.Connected)?.endpoints?.firstOrNull()?.url,
                        server = serverLabel(McpServerService.serverStatus.value),
                        baseAddress = config.arcTunnelBaseAddress,
                        edgeOverride = config.arcTunnelEdgeOverride,
                        ctCheckEnabled = config.arcTunnelCtCheckEnabled,
                        tunnelEnabled = config.tunnelEnabled,
                        tunnelProvider = config.tunnelProvider.adbValue,
                        bearerTokenEnabled = config.bearerTokenEnabled,
                    )
                val json = Json.encodeToString(E2EArcTunnelStatus.serializer(), status)
                pending.setResultData(Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP))
            } finally {
                pending.finish()
            }
        }
    }

// companion (add)
        const val ACTION_E2E_STOP_SERVER = "com.danielealbano.androidremotecontrolmcp.debug.E2E_STOP_SERVER"
        const val ACTION_E2E_ARC_TUNNEL_ENROLL = "com.danielealbano.androidremotecontrolmcp.debug.E2E_ARC_TUNNEL_ENROLL"
        const val ACTION_E2E_ARC_TUNNEL_QUERY = "com.danielealbano.androidremotecontrolmcp.debug.E2E_ARC_TUNNEL_QUERY"
        private const val CLEAR = "-"
        private const val EXTRA_TUNNEL_ENABLED = "tunnel_enabled"
        private const val EXTRA_TUNNEL_PROVIDER = "tunnel_provider"
        private const val EXTRA_ARC_TUNNEL_BASE_ADDRESS = "arc_tunnel_base_address"
        private const val EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED = "arc_tunnel_ct_check_enabled"
        private const val EXTRA_ARC_TUNNEL_EDGE_OVERRIDE = "arc_tunnel_edge_override"
        private const val EXTRA_ARC_TUNNEL_TRUSTED_CA_PEM_B64 = "arc_tunnel_trusted_ca_pem_b64"
```

Context: the receiver is debug-only and DUMP-gated (see its KDoc); no manifest change (every broadcast names the component). The enroll request starts `ArcTunnelEnrollService`; the e2e brings the app to the foreground (`am start` of the main activity) right before the broadcast, so the foreground start is allowed.

Definition of Done:
- [x] DTO, labels and receiver as written; the receiver keeps ≤ 11 functions.

### [x] Task 9.2 — Tests

**File**: `app/src/testDebug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiverTest.kt` (modify). The existing tests keep passing unchanged: the relaxed `Intent` mock returns `""` for every string extra and the receiver treats empty strings as absent. **Setup additions**: `receiver.tunnelManager = mockk { every { tunnelStatus } returns MutableStateFlow(TunnelStatus.Disconnected) }`; `receiver.arcTunnelEnrollment = mockk(relaxed = true)`; `mockkStatic(android.util.Base64::class)` (the mockable jar returns null); `mockkObject(McpServerService.Companion)` with `every { McpServerService.serverStatus } returns MutableStateFlow(<status>)`; the query test uses `spyk(receiver)` with `every { goAsync() } returns mockk<BroadcastReceiver.PendingResult>(relaxed = true)`.

| Test | Verifies |
|------|----------|
| `handleConfigure runs the migration before any write` | `coVerifyOrder { ensureAuthModelMigrated(); updateBearerToken(any()) }` |
| `handleConfigure applies the tunnel settings` | `tunnel_enabled`, `tunnel_provider` (ADB value), base address, CT flag → the four repository updates |
| `handleConfigure ignores an unknown provider and an invalid base address` | no repository call |
| `handleConfigure stores the override and the decoded PEM` | `Base64.decode` stubbed → `updateArcTunnelE2EOverride("10.88.0.1:18443", "<pem>")` |
| `handleConfigure clears the override and the PEM with the sentinel` | `-`/`-` → `updateArcTunnelE2EOverride("", "")` |
| `handleConfigure keeps the stored PEM on invalid Base64` | `Base64.decode` throws `IllegalArgumentException` → the current PEM is written back |
| `handleConfigure leaves the override untouched when its extras are empty` | `""`/`""` → `updateArcTunnelE2EOverride` never called |
| `stop server sends ACTION_STOP` | `startForegroundService` with an intent whose action is `ACTION_STOP` (`mockkConstructor(Intent::class)`, verify `setAction`) |
| `enroll requests enrollment with the current env` | status `Stopped` → `requestEnroll(ArcTunnelEnv.from(config))` |
| `enroll is ignored while the server runs` | status `Running` → no `requestEnroll` |
| `query answers Base64 JSON in the result data` | `Base64.encodeToString` stubbed to a marker → `setResultData(marker)` then `finish()`; the JSON passed to the encoder contains `"tunnel_provider":"android-remote-control-mcp-tunneld"` and `"bearer_token_enabled"` |

Definition of Done:
- [x] One test per row; the existing rows of the file still pass unchanged.

---

## [x] User Story 10 — E2E tests

Why: D14 — (a) the full tunnel stack in containers replicated from the tunnel server repo's own e2e (`internal/tunneltest/containers.go`, `e2e/e2e_test.go` at v0.5.2), the server from the `latest` ghcr image, ACME `tls-alpn-01` against Pebble; (b) a device-gated test against production on the attached phone. The phone in redroid reaches the edge through a HOST-side TCP relay bound on `0.0.0.0` (the one host-reachability mechanism this e2e has proven: `FixtureHttpServer` via the container's gateway IP), so no cross-network container routing is assumed.

Acceptance criteria:
- [x] `E2EArcTunnelTest` boots the stack (Valkey, MinIO, Pebble + challtestsrv, the server), enrolls the redroid phone, starts the server, observes `Connected`, reads `/health` through the tunnel with the Pebble root as trust anchor and SNI `<name>.d.example.test`, and lists MCP tools through the tunnel; it restores the shared container afterwards.
- [x] `E2EArcTunnelDeviceTest` is skipped (`Assumptions`) when no phone is attached — a phone is an `adb devices` entry in state `device` that is neither `emulator-*` nor a redroid container (`getprop ro.boot.redroid_width` empty); USB and `adb connect` phones both count — and otherwise enrolls against production with a per-run random bearer token, observes `Connected` and lists tools at `https://<name>.d.<base>/mcp` with system trust.
- [x] The CI e2e job needs no change (the stack images are pulled by Testcontainers; the server image is `always pull`).

### [x] Task 10.1 — Shared helpers

[x] **Action 10.1.1** — modify `E/McpClient.kt` (injectable HTTP client, so the tunnel tests supply an OkHttp-backed one):

```kotlin
// imports (add)
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

// class header + fields (replace)
class McpClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private var client: Client? = null

    companion object {
        private const val CLIENT_TIMEOUT_MS = 90_000L

        /** The historical client: Ktor CIO, trust-all TLS (self-signed HTTPS mode). */
        fun defaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(SSE)
                install(HttpTimeout) {
                    requestTimeoutMillis = CLIENT_TIMEOUT_MS
                    socketTimeoutMillis = CLIENT_TIMEOUT_MS
                }
                engine {
                    https {
                        trustManager = TrustAllX509TrustManager()
                    }
                    requestTimeout = CLIENT_TIMEOUT_MS
                }
            }

        /** A client on any engine with the SSE + timeout plugins the SDK transport needs. */
        fun <T : HttpClientEngineConfig> httpClient(
            engine: HttpClientEngineFactory<T>,
            configure: T.() -> Unit,
        ): HttpClient =
            HttpClient(engine) {
                install(SSE)
                install(HttpTimeout) {
                    requestTimeoutMillis = CLIENT_TIMEOUT_MS
                    socketTimeoutMillis = CLIENT_TIMEOUT_MS
                }
                engine(configure)
            }
    }
```
The `connect`, `listTools`, `callTool`, `close` functions and `TrustAllX509TrustManager` are unchanged.

[x] **Action 10.1.2** — create `E/E2EArcTunnelStatus.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirror of the app's debug `E2EArcTunnelStatus` (same JSON contract; both MUST change together). */
@Serializable
data class E2EArcTunnelStatus(
    @SerialName("enrolled") val enrolled: Boolean,
    @SerialName("url") val url: String?,
    @SerialName("enrollment") val enrollment: String,
    @SerialName("tunnel") val tunnel: String,
    @SerialName("tunnel_url") val tunnelUrl: String?,
    @SerialName("server") val server: String,
    @SerialName("base_address") val baseAddress: String,
    @SerialName("edge_override") val edgeOverride: String,
    @SerialName("ct_check_enabled") val ctCheckEnabled: Boolean,
    @SerialName("tunnel_enabled") val tunnelEnabled: Boolean,
    @SerialName("tunnel_provider") val tunnelProvider: String,
    @SerialName("bearer_token_enabled") val bearerTokenEnabled: Boolean,
) {
    /** The tunnel-related settings of this status, to restore a device afterwards. */
    fun toSettings(): ArcTunnelE2ESettings =
        ArcTunnelE2ESettings(tunnelEnabled, tunnelProvider, baseAddress, ctCheckEnabled, edgeOverride)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): E2EArcTunnelStatus = json.decodeFromString(serializer(), text)
    }
}

/** What [AndroidContainerSetup.configureArcTunnel] sends. */
data class ArcTunnelE2ESettings(
    val tunnelEnabled: Boolean,
    val provider: String,
    val baseAddress: String,
    val ctCheckEnabled: Boolean,
    val edgeOverride: String = "",
    val trustedCaPem: String = "",
) {
    companion object {
        const val ARC_PROVIDER = "android-remote-control-mcp-tunneld"
        const val CLOUDFLARE_PROVIDER = "CLOUDFLARE"
    }
}
```

[x] **Action 10.1.3** — modify `E/AndroidContainerSetup.kt`:

```kotlin
// imports (add)
import java.util.Base64

// make APP_PACKAGE internal (was private)
    internal const val APP_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"

// constants (add, after MAIN_ACTIVITY_CLASS)
    internal const val E2E_RECEIVER_COMPONENT = "$APP_PACKAGE/$E2E_CONFIG_RECEIVER_CLASS"
    internal const val E2E_CONFIGURE_ACTION = "$E2E_ACTION_BASE.E2E_CONFIGURE"
    internal const val E2E_START_ACTION = "$E2E_ACTION_BASE.E2E_START_SERVER"
    internal const val E2E_STOP_ACTION = "$E2E_ACTION_BASE.E2E_STOP_SERVER"
    internal const val E2E_ARC_ENROLL_ACTION = "$E2E_ACTION_BASE.E2E_ARC_TUNNEL_ENROLL"
    internal const val E2E_ARC_QUERY_ACTION = "$E2E_ACTION_BASE.E2E_ARC_TUNNEL_QUERY"
    internal const val MAIN_COMPONENT = "$APP_PACKAGE/$MAIN_ACTIVITY_CLASS"
    private const val ARC_POLL_INTERVAL_MS = 1_000L
    private const val BROADCAST_DATA_PREFIX = "data=\""

// new functions (add before execAdb)

    /**
     * The address of the HOST as seen from inside the container: its default-gateway IP (podman's bridge).
     * `host.testcontainers.internal` is not resolvable by Android's resolver; Android uses policy routing, so
     * `ip route get <external ip>` is used instead of the main table (prints "<ip> via <gateway> dev …").
     */
    fun containerGatewayIp(serial: String = adbSerial): String {
        val out = execAdbOn(serial, "shell", "ip", "route", "get", "1.1.1.1")
        val tokens = out.split(Regex("\\s+"))
        return tokens[tokens.indexOf("via") + 1]
    }

    /**
     * Stops the MCP server through the debug receiver and waits until nothing answers on [baseUrl]. The main
     * activity is brought to the front first: the stop is a foreground-service command, refused from the
     * background on Android 12+.
     */
    fun stopMcpServer(
        baseUrl: String,
        serial: String = adbSerial,
        timeoutMs: Long = DEFAULT_SERVER_READY_TIMEOUT_MS,
    ) {
        println("[E2E Setup] Stopping MCP server...")
        execAdbOn(serial, "shell", "am", "start", "-n", MAIN_COMPONENT)
        Thread.sleep(2_000)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_STOP_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URI("$baseUrl/health").toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                conn.responseCode
            } catch (_: java.io.IOException) {
                println("[E2E Setup] MCP server stopped (${System.currentTimeMillis() - startTime}ms)")
                return
            } finally {
                conn?.disconnect()
            }
            Thread.sleep(SERVER_READY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("MCP server still answering ${timeoutMs}ms after the stop broadcast")
    }

    /** Stops the server on a device without a reachable base URL; awaits `server == "Stopped"` through the query. */
    fun stopMcpServerOn(serial: String) {
        execAdbOn(serial, "shell", "am", "start", "-n", MAIN_COMPONENT)
        Thread.sleep(2_000)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_STOP_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        awaitArcTunnel(serial = serial, timeoutMs = 30_000L) { it.server == "Stopped" }
    }

    /** Starts the server on [serial] (activity + debug receiver), like [startMcpServer] but for any device. */
    fun startMcpServerOn(serial: String) {
        execAdbOn(serial, "shell", "am", "start", "-n", MAIN_COMPONENT)
        Thread.sleep(5_000)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_START_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        Thread.sleep(5_000)
    }

    /**
     * Applies the free E2E tunnel settings through the debug receiver and waits until the query echoes them.
     * Empty override/PEM are sent as `-` (the receiver's clear sentinel: `--es k ""` never arrives).
     */
    fun configureArcTunnel(
        settings: ArcTunnelE2ESettings,
        serial: String = adbSerial,
    ) {
        val pemB64 =
            if (settings.trustedCaPem.isEmpty()) {
                "-"
            } else {
                Base64.getEncoder().encodeToString(settings.trustedCaPem.toByteArray())
            }
        execAdbOn(
            serial, "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", E2E_CONFIGURE_ACTION, "-n", E2E_RECEIVER_COMPONENT,
            "--ez", "tunnel_enabled", settings.tunnelEnabled.toString(),
            "--es", "tunnel_provider", settings.provider,
            "--es", "arc_tunnel_base_address", settings.baseAddress,
            "--ez", "arc_tunnel_ct_check_enabled", settings.ctCheckEnabled.toString(),
            "--es", "arc_tunnel_edge_override", settings.edgeOverride.ifEmpty { "-" },
            "--es", "arc_tunnel_trusted_ca_pem_b64", pemB64,
        )
        awaitArcTunnel(serial = serial, timeoutMs = 30_000L) { status ->
            status.tunnelEnabled == settings.tunnelEnabled &&
                status.tunnelProvider == settings.provider &&
                status.baseAddress == settings.baseAddress &&
                status.ctCheckEnabled == settings.ctCheckEnabled &&
                status.edgeOverride == settings.edgeOverride
        }
        println("[E2E Setup] Free E2E tunnel settings applied")
    }

    /** Brings the app to the foreground first: the enrollment foreground service cannot start from the background. */
    fun enrollArcTunnel(serial: String = adbSerial) {
        execAdbOn(serial, "shell", "am", "start", "-n", MAIN_COMPONENT)
        Thread.sleep(2_000)
        execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_ARC_ENROLL_ACTION, "-n", E2E_RECEIVER_COMPONENT)
    }

    /** One status query; the receiver answers with Base64 JSON in the broadcast result data. */
    fun queryArcTunnel(serial: String = adbSerial): E2EArcTunnelStatus {
        val out =
            execAdbOn(serial, "shell", "am", "broadcast", "-a", E2E_ARC_QUERY_ACTION, "-n", E2E_RECEIVER_COMPONENT)
        val line =
            out.lines().firstOrNull { it.contains(BROADCAST_DATA_PREFIX) }
                ?: throw IllegalStateException("No result data in: $out")
        val b64 = line.substringAfter(BROADCAST_DATA_PREFIX).substringBefore('"')
        return E2EArcTunnelStatus.parse(String(Base64.getDecoder().decode(b64)))
    }

    /** Polls [queryArcTunnel] until [predicate] holds; the last status is in the failure message. */
    fun awaitArcTunnel(
        serial: String = adbSerial,
        timeoutMs: Long,
        predicate: (E2EArcTunnelStatus) -> Boolean,
    ): E2EArcTunnelStatus {
        val startTime = System.currentTimeMillis()
        var last: E2EArcTunnelStatus? = null
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            last = queryArcTunnel(serial)
            if (predicate(last)) return last
            Thread.sleep(ARC_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Free E2E tunnel condition not met within ${timeoutMs}ms; last status: $last")
    }

    /** [execAdb] for an explicit serial (the device-gated test targets the attached phone, not the container). */
    internal fun execAdbOn(
        serial: String,
        vararg args: String,
    ): String = runProcess("adb", "-s", serial, *args)

// runProcess: make it internal (was private)
    internal fun runProcess(
```

[x] **Action 10.1.4** — modify `E/FixtureHttpServer.kt`: replace the body of `containerReachableBaseUrl()` with

```kotlin
    fun containerReachableBaseUrl(): String = "http://${AndroidContainerSetup.containerGatewayIp()}:$FIXTURE_HTTP_PORT"
```
keeping its KDoc.

Definition of Done:
- [x] `McpClient`, the status DTO/settings, the `AndroidContainerSetup` additions and `FixtureHttpServer` as written.

### [x] Task 10.2 — The tunnel stack

[x] **Action 10.2.1** — create `E/ArcTunnelTestCa.kt` (the server's INTERNAL CA for identity certs, like `writeCA` in the server e2e):

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/** A throw-away P-256 CA (cert + PKCS#8 key, PEM) for the tunnel server's `--ca-cert` / `--ca-key`. */
object ArcTunnelTestCa {
    private const val VALIDITY_MS = 24L * 60 * 60 * 1000
    private const val PEM_LINE = 64

    data class Material(
        val certPem: String,
        val keyPem: String,
    )

    fun generate(): Material {
        Security.addProvider(BouncyCastleProvider())
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val keyPair = generator.generateKeyPair()
        val name = X500Name("CN=arc-tunnel-e2e-ca")
        val now = System.currentTimeMillis()
        val builder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date(now - VALIDITY_MS),
                Date(now + VALIDITY_MS),
                name,
                keyPair.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Material(pem("CERTIFICATE", cert.encoded), pem("PRIVATE KEY", keyPair.private.encoded))
    }

    fun pem(
        type: String,
        der: ByteArray,
    ): String {
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }
}
```

[x] **Action 10.2.2** — create `E/ArcTunnelS3.kt` (bucket creation with a hand-rolled SigV4 `PUT`, the server e2e's `EnsureS3Bucket` without the AWS SDK):

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates an S3 bucket idempotently (200/409 = done) on a MinIO endpoint, retrying while it boots. */
object ArcTunnelS3 {
    private const val REGION = "us-east-1"
    private const val ATTEMPTS = 30
    private const val RETRY_MS = 1_000L
    private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun ensureBucket(
        endpoint: String,
        accessKey: String,
        secretKey: String,
        bucket: String,
    ) {
        var last = ""
        repeat(ATTEMPTS) {
            try {
                val code = putBucket(endpoint, accessKey, secretKey, bucket)
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CONFLICT) return
                last = "HTTP $code"
            } catch (e: java.io.IOException) {
                last = "${e::class.simpleName}: ${e.message}"
            }
            Thread.sleep(RETRY_MS)
        }
        throw IllegalStateException("S3 bucket '$bucket' not created on $endpoint: $last")
    }

    private fun putBucket(
        endpoint: String,
        accessKey: String,
        secretKey: String,
        bucket: String,
    ): Int {
        val uri = URI("$endpoint/$bucket")
        val host = uri.host + (if (uri.port > 0) ":${uri.port}" else "")
        val amzDate = amzDateFormat.format(ZonedDateTime.now(ZoneOffset.UTC))
        val day = amzDate.substring(0, 8)
        val emptyHash = sha256Hex(ByteArray(0))
        val canonical =
            "PUT\n/$bucket\n\nhost:$host\nx-amz-content-sha256:$emptyHash\nx-amz-date:$amzDate\n\n" +
                "host;x-amz-content-sha256;x-amz-date\n$emptyHash"
        val scope = "$day/$REGION/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val signingKey = hmac(hmac(hmac(hmac("AWS4$secretKey".toByteArray(), day), REGION), "s3"), "aws4_request")
        val signature = hmac(signingKey, toSign).joinToString("") { "%02x".format(it) }
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 2_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("x-amz-date", amzDate)
            conn.setRequestProperty("x-amz-content-sha256", emptyHash)
            conn.setRequestProperty(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature",
            )
            conn.doOutput = true
            conn.outputStream.close()
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(data.toByteArray())
    }
}
```

[x] **Action 10.2.3** — create `E/ArcTunnelHostRelay.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opaque TCP relay on the HOST: `0.0.0.0:[listenPort]` → `127.0.0.1:[targetPort]`. The phone in redroid can
 * reach a host process through its gateway IP (proven by `FixtureHttpServer`), so the tunnel server's edge
 * (a mapped container port on 127.0.0.1) is exposed to the phone this way. TLS passes through untouched.
 */
class ArcTunnelHostRelay(
    private val listenPort: Int,
    private val targetPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(listenPort, BACKLOG, InetAddress.getByName("0.0.0.0"))

    fun start() {
        Thread({ acceptLoop() }, "arc-tunnel-relay").apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            val upstream =
                try {
                    Socket("127.0.0.1", targetPort)
                } catch (_: IOException) {
                    client.close()
                    continue
                }
            val open = AtomicInteger(2)
            pump(client, upstream, open)
            pump(upstream, client, open)
        }
    }

    /** Copies one direction; on EOF half-closes the peer, and closes both sockets once both directions ended. */
    private fun pump(
        from: Socket,
        to: Socket,
        open: AtomicInteger,
    ) {
        Thread({
            try {
                from.getInputStream().copyTo(to.getOutputStream())
                to.shutdownOutput()
            } catch (_: IOException) {
                // the peer went away: fall through to the close below
            } finally {
                if (open.decrementAndGet() == 0 || from.isClosed || to.isClosed) {
                    closeQuietly(from)
                    closeQuietly(to)
                }
            }
        }, "arc-tunnel-relay-pump").apply { isDaemon = true }.start()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // already closed
        }
    }

    companion object {
        private const val BACKLOG = 64
    }
}
```

[x] **Action 10.2.4** — create `E/ArcTunnelStack.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import com.github.dockerjava.api.model.Network.Ipam
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.InternetProtocol
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.PullPolicy
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/** [GenericContainer] with a UDP port bound to a fixed host port before start (`withExposedPorts` is TCP-only). */
class UdpPortContainer(
    image: DockerImageName,
) : GenericContainer<UdpPortContainer>(image) {
    fun withFixedUdpPort(
        hostPort: Int,
        containerPort: Int,
    ): UdpPortContainer = apply { addFixedExposedPort(hostPort, containerPort, InternetProtocol.UDP) }
}

/**
 * The free E2E tunnel server stack, replicated from the tunnel server repo's own e2e
 * (`internal/tunneltest/containers.go`, `e2e/e2e_test.go`): Valkey, MinIO, Pebble + challtestsrv (hermetic
 * ACME CA, `tls-alpn-01` validated against the server's own edge), and the server itself from the `latest`
 * ghcr image (NEVER pinned, always pulled, NEVER built locally). The server has a static IP on the stack
 * network so challtestsrv can answer Pebble's validation lookups with it before the server exists. The
 * server's edge (`:443`) is mapped to a random host port, which [ArcTunnelHostRelay] exposes on
 * `0.0.0.0:[EDGE_HOST_PORT]` for the phone (through its gateway) and for the JVM (`127.0.0.1`).
 */
class ArcTunnelStack(
    /** Address the phone dials back to: the host gateway as seen from redroid, with [EDGE_HOST_PORT]. */
    private val dialBackAdvertise: String,
) : AutoCloseable {
    private val subnetOctets = "10.${Random.nextInt(100, 200)}.${Random.nextInt(0, 256)}"
    private val serverIp = "$subnetOctets.10"
    private val network: Network =
        Network
            .builder()
            .createNetworkCmdModifier { cmd ->
                val ipam = Ipam.Config().withSubnet("$subnetOctets.0/24").withGateway("$subnetOctets.1")
                cmd.withIpam(Ipam().withConfig(ipam))
            }.build()
    private val containers = ArrayDeque<GenericContainer<*>>()
    private var relay: ArcTunnelHostRelay? = null

    /** Pebble's per-run issuing root (PEM): the trust anchor the phone and the JVM use for tunnel certs. */
    lateinit var issuingRootPem: String
        private set

    fun start() {
        start(
            GenericContainer(DockerImageName.parse(VALKEY_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("valkey")
                .withExposedPorts(VALKEY_PORT)
                .waitingFor(Wait.forListeningPort()),
        )
        val minio =
            start(
                GenericContainer(DockerImageName.parse(MINIO_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("minio")
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET)
                    .withCommand("server", "/data")
                    .withExposedPorts(MINIO_PORT)
                    .waitingFor(Wait.forListeningPort()),
            )
        val minioUrl = "http://${minio.host}:${minio.getMappedPort(MINIO_PORT)}"
        ArcTunnelS3.ensureBucket(minioUrl, MINIO_ACCESS, MINIO_SECRET, BUCKET)

        // challtestsrv: the DNS Pebble consults; every name resolves to the server's static IP.
        val dnsHostPort = freeUdpPort()
        val chal =
            start(
                UdpPortContainer(DockerImageName.parse(CHALLTESTSRV_IMAGE))
                    .withFixedUdpPort(dnsHostPort, CHAL_DNS_PORT)
                    .withNetwork(network)
                    .withNetworkAliases("challtestsrv")
                    .withCommand("-defaultIPv6", "", "-defaultIPv4", serverIp)
                    .withExposedPorts(CHAL_MGMT_PORT)
                    // The image is FROM scratch (no shell for a port check) and the management API answers 400
                    // to a body-less probe: readiness = any HTTP answer on the management port plus a real DNS
                    // answer (waitForDns below), as the server e2e's dnsAnswers wait does.
                    .waitingFor(Wait.forHttp("/").forPort(CHAL_MGMT_PORT).forStatusCodeMatching { it in 200..499 }),
            )
        waitForDns(chal.host, dnsHostPort)

        // Pebble with host validation: tls-alpn-01 / http-01 go to the server's real edge ports.
        val pebble =
            start(
                GenericContainer(DockerImageName.parse(PEBBLE_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("pebble")
                    .withCopyToContainer(Transferable.of(pebbleConfig()), PEBBLE_CONFIG_PATH)
                    .withCommand("-config", PEBBLE_CONFIG_PATH, "-dnsserver", "challtestsrv:$CHAL_DNS_PORT")
                    .withEnv("PEBBLE_VA_NOSLEEP", "1")
                    .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                    .withExposedPorts(PEBBLE_ACME_PORT, PEBBLE_MGMT_PORT)
                    .waitingFor(
                        Wait
                            .forHttp("/roots/0")
                            .forPort(PEBBLE_MGMT_PORT)
                            .usingTls()
                            .allowInsecure()
                            .forStatusCode(200),
                    ),
            )
        val minica = File.createTempFile("pebble-minica", ".pem").apply { deleteOnExit() }
        pebble.copyFileFromContainer("/test/certs/pebble.minica.pem", minica.absolutePath)
        issuingRootPem = httpsGet("https://${pebble.host}:${pebble.getMappedPort(PEBBLE_MGMT_PORT)}/roots/0")

        val ca = ArcTunnelTestCa.generate()
        val server =
            start(
                GenericContainer(DockerImageName.parse(SERVER_IMAGE))
                    .withImagePullPolicy(PullPolicy.alwaysPull())
                    .withNetwork(network)
                    .withNetworkAliases(SERVER_ALIAS)
                    .withCreateContainerCmdModifier { cmd ->
                        cmd.withIpv4Address(serverIp)
                        // distroless `nonroot` cannot write the copied-in dirs: root inside this throw-away container.
                        cmd.withUser("0:0")
                    }.withCopyToContainer(Transferable.of(ca.certPem), "/ca/ca.pem")
                    .withCopyToContainer(Transferable.of(ca.keyPem), "/ca/ca-key.pem")
                    .withCopyToContainer(Transferable.of(minica.readText()), "/ca/pebble-minica.pem")
                    .withCopyToContainer(Transferable.of(""), "/attest/signers.txt")
                    .withCopyToContainer(Transferable.of(""), "/acme/.keep")
                    .withCopyToContainer(Transferable.of(""), "/conn-logs/.keep")
                    .withEnv(serverEnv())
                    .withExposedPorts(EDGE_PORT, INTERNAL_PORT)
                    .waitingFor(Wait.forHttp("/healthz").forPort(INTERNAL_PORT).forStatusCode(200)),
            )
        relay = ArcTunnelHostRelay(EDGE_HOST_PORT, server.getMappedPort(EDGE_PORT)).also { it.start() }
        waitForReservedCert(ENROLL_HOST)
        waitForReservedCert(CONTROL_HOST)
    }

    /** Phone-side edge override (`host:port`), from the host gateway address redroid routes through. */
    fun edgeOverride(gatewayIp: String): String = "$gatewayIp:$EDGE_HOST_PORT"

    /** Opens a TLS connection to the edge (via the relay) with [sni] as server name, trusting the Pebble root. */
    fun connectTls(sni: String): SSLSocket {
        val raw = Socket("127.0.0.1", EDGE_HOST_PORT).apply { soTimeout = TLS_TIMEOUT_MS }
        val socket = pebbleSslContext().socketFactory.createSocket(raw, sni, EDGE_HOST_PORT, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        socket.startHandshake()
        return socket
    }

    fun pebbleTrustManager(): X509TrustManager {
        val factory = CertificateFactory.getInstance("X.509")
        val root = factory.generateCertificate(issuingRootPem.byteInputStream()) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("pebble-root", root)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun close() {
        relay?.close()
        while (containers.isNotEmpty()) containers.removeLast().stop()
        network.close()
    }

    private fun start(container: GenericContainer<*>): GenericContainer<*> {
        container.withStartupTimeout(Duration.ofSeconds(STARTUP_TIMEOUT_S))
        container.start()
        containers.addLast(container)
        return container
    }

    private fun pebbleSslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pebbleTrustManager()), null) }

    /** The reserved hosts serve an ACME cert only once the server issued it: wait for a verified handshake. */
    private fun waitForReservedCert(host: String) {
        val deadline = System.currentTimeMillis() + RESERVED_CERT_TIMEOUT_MS
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                connectTls(host).close()
                println("[ArcTunnelStack] $host serves a trusted certificate")
                return
            } catch (e: java.io.IOException) {
                last = "${e::class.simpleName}: ${e.message}"
            }
            Thread.sleep(POLL_MS)
        }
        throw IllegalStateException("$host never served a trusted certificate: $last")
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /** One A query to challtestsrv until it answers (what the server e2e's `dnsAnswers` wait does). */
    private fun waitForDns(
        host: String,
        port: Int,
    ) {
        val query =
            byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
                byteArrayOf(7) + "example".toByteArray() + byteArrayOf(4) + "test".toByteArray() +
                byteArrayOf(0, 0, 1, 0, 1)
        val deadline = System.currentTimeMillis() + RESERVED_CERT_TIMEOUT_MS
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
                val answer = DatagramPacket(ByteArray(DNS_BUFFER), DNS_BUFFER)
                try {
                    socket.receive(answer)
                    if (answer.length > DNS_HEADER && answer.data[0] == query[0] && answer.data[1] == query[1]) return
                } catch (_: java.net.SocketTimeoutException) {
                    // not up yet: retry
                }
            }
        }
        throw IllegalStateException("challtestsrv DNS never answered on $host:$port")
    }

    private fun httpsGet(url: String): String {
        val trustAll = arrayOf<TrustManager>(TrustAllX509TrustManager())
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val conn = java.net.URI(url).toURL().openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        try {
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun pebbleConfig(): String =
        """
        {"pebble": {
          "listenAddress": "0.0.0.0:$PEBBLE_ACME_PORT",
          "managementListenAddress": "0.0.0.0:$PEBBLE_MGMT_PORT",
          "certificate": "test/certs/localhost/cert.pem",
          "privateKey": "test/certs/localhost/key.pem",
          "httpPort": $ACME_HTTP_PORT,
          "tlsPort": $EDGE_PORT,
          "ocspResponderURL": "",
          "externalAccountBindingRequired": false,
          "retryAfter": {"authz": 3, "order": 5},
          "keyAlgorithm": "ecdsa",
          "profiles": {
            "default": {"description": "default", "validityPeriod": 7776000},
            "shortlived": {"description": "shortlived", "validityPeriod": 518400}
          }
        }}
        """.trimIndent()

    /**
     * `TUNNELD_*` env twins of the flags the server e2e sets (`runReplicaOnce` + `LooseACMEBudgets`), LE via
     * Pebble.
     */
    private fun serverEnv(): Map<String, String> =
        mapOf(
            "TUNNELD_LISTEN" to ":$EDGE_PORT",
            "TUNNELD_INTERNAL_LISTEN" to ":$INTERNAL_PORT",
            "TUNNELD_MESH_LISTEN" to ":$MESH_PORT",
            "TUNNELD_MESH_ADVERTISE" to "$SERVER_ALIAS:$MESH_PORT",
            "TUNNELD_TUNNEL_DOMAIN" to TUNNEL_DOMAIN,
            "TUNNELD_ENROLL_HOST" to ENROLL_HOST,
            "TUNNELD_CONTROL_HOST" to CONTROL_HOST,
            "TUNNELD_DIALBACK_ADVERTISE" to dialBackAdvertise,
            "TUNNELD_REDIS_URL" to "redis://valkey:$VALKEY_PORT/0",
            "TUNNELD_CA_CERT" to "/ca/ca.pem",
            "TUNNELD_CA_KEY" to "/ca/ca-key.pem",
            "TUNNELD_S3_ENDPOINT" to "http://minio:$MINIO_PORT",
            "TUNNELD_S3_BUCKET" to BUCKET,
            "TUNNELD_S3_ACCESS_KEY" to MINIO_ACCESS,
            "TUNNELD_S3_SECRET_KEY" to MINIO_SECRET,
            "TUNNELD_CONN_LOG_DIR" to "/conn-logs",
            "TUNNELD_ATTEST_SIGNER_DIGEST_FILE" to "/attest/signers.txt",
            "TUNNELD_ATTEST_ROOT_URL" to "http://127.0.0.1:1/root",
            "TUNNELD_ATTEST_STATUS_URL" to "http://127.0.0.1:1/status",
            "TUNNELD_ATTESTATION_OPTIONAL" to "true",
            "TUNNELD_ALLOW_ATTESTATION_OPTIONAL" to "1",
            "TUNNELD_ACME_DIR_LE" to "https://pebble:$PEBBLE_ACME_PORT/dir",
            "TUNNELD_ACME_EMAIL" to "ops@example.test",
            "TUNNELD_ACME_ACCOUNT_DIR" to "/acme",
            "TUNNELD_ACME_CHALLENGE_LE" to "tls-alpn-01",
            "TUNNELD_ACME_HTTP_LISTEN" to ":$ACME_HTTP_PORT",
            "TUNNELD_ACME_BUDGET_LE_DOMAIN" to LOOSE_BUDGET,
            "TUNNELD_ACME_BUDGET_LE_ORDERS" to LOOSE_BUDGET,
            "TUNNELD_ACME_BUDGET_LE_NAME" to LOOSE_BUDGET,
            "TUNNELD_ACME_ATTEMPT_TIMEOUT_LE" to "1m",
            "TUNNELD_ACME_POLL_FIRST_LE" to "100ms",
            "TUNNELD_ACME_POLL_INTERVAL" to "100ms",
            "TUNNELD_ACME_COOLDOWN_DEFAULT" to "1s",
            "TUNNELD_ACME_BACKOFF_INITIAL" to "1s",
            "TUNNELD_ACME_BACKOFF_MAX" to "5s",
            "TUNNELD_ISSUE_PER_WEEK" to "10",
            "TUNNELD_LIMIT_ENROLL_HOUR" to "1000",
            "TUNNELD_LIMIT_ENROLL_MINUTE" to "1000",
            "TUNNELD_LIMIT_ENROLL_BODY" to "64kb",
            "TUNNELD_MAX_CLIENTS" to "100",
            "TUNNELD_LIMIT_CONN_RATE" to "1000",
            "TUNNELD_LIMIT_CONCURRENT" to "8",
            "TUNNELD_LIMIT_PACKETS" to "100000",
            "TUNNELD_LIMIT_BANDWIDTH" to "10mbit",
            "TUNNELD_LIMIT_CONN_EVICT_IDLE" to "1s",
            "TUNNELD_LIMIT_CONN_MIN_RATE" to "1kb",
            "TUNNELD_LIMIT_CONN_PROTECT_RATE" to "1mb",
            "TUNNELD_HANDSHAKE_TIMEOUT" to "5s",
            "TUNNELD_BAN_POLL" to "1s",
            "TUNNELD_SHUTDOWN_GRACE" to "3s",
            "LEGO_CA_CERTIFICATES" to "/ca/pebble-minica.pem",
        )

    companion object {
        const val SERVER_IMAGE = "ghcr.io/danielealbano/android-remote-control-mcp-tunneld:latest"
        const val VALKEY_IMAGE = "valkey/valkey:9.1-alpine"
        const val MINIO_IMAGE = "pgsty/silo:RELEASE.2026-09-16T00-00-00Z"
        const val PEBBLE_IMAGE = "ghcr.io/letsencrypt/pebble:2.10.1"
        const val CHALLTESTSRV_IMAGE = "ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1"
        const val BASE_ADDRESS = "example.test"
        const val TUNNEL_DOMAIN = "d.$BASE_ADDRESS"
        const val ENROLL_HOST = "enroll.$BASE_ADDRESS"
        const val CONTROL_HOST = "connect.$BASE_ADDRESS"

        /** Fixed host port of the relay (like `FixtureHttpServer.FIXTURE_HTTP_PORT`); the phone dials back to it. */
        const val EDGE_HOST_PORT = 18443
        private const val SERVER_ALIAS = "arc-tunnel-server"
        private const val BUCKET = "arc-tunnel-e2e"
        private const val MINIO_ACCESS = "test-access"
        private const val MINIO_SECRET = "test-secret-changeme"
        private const val VALKEY_PORT = 6379
        private const val MINIO_PORT = 9000
        private const val PEBBLE_ACME_PORT = 14000
        private const val PEBBLE_MGMT_PORT = 15000
        private const val PEBBLE_CONFIG_PATH = "/test/config/pebble-host-validation.json"
        private const val CHAL_DNS_PORT = 8053
        private const val CHAL_MGMT_PORT = 8055
        private const val EDGE_PORT = 443
        private const val ACME_HTTP_PORT = 80
        private const val INTERNAL_PORT = 9090
        private const val MESH_PORT = 9443
        private const val LOOSE_BUDGET = "100000/1m"
        private const val STARTUP_TIMEOUT_S = 120L
        private const val RESERVED_CERT_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 1_000L
        private const val TLS_TIMEOUT_MS = 15_000
        private const val DNS_TIMEOUT_MS = 2_000
        private const val DNS_BUFFER = 512
        private const val DNS_HEADER = 12
    }
}
```

Context: the server e2e registers Pebble as all three CAs (LE + a GTS account it registers over ACME + ZeroSSL without EAB, which the server then skips); here only LE points at Pebble (no `--acme-gts-account` = no GTS, no ZeroSSL EAB = no ZeroSSL), one issuing chain is enough. `LEGO_CA_CERTIFICATES` makes the server's ACME client trust Pebble's management/ACME TLS (minica), as the server e2e does. The dial-back `addr` the server advertises is `<gateway>:18443` = the relay; everything from the phone (enroll, control, dial-back) and from the JVM (public side) goes through the relay to the mapped edge port.

[x] **Action 10.2.5** — create `E/ArcTunnelPublicClient.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/** The PUBLIC side of a tunnel: what any Internet client does, minus the DNS the e2e replaces. */
object ArcTunnelPublicClient {
    private const val READ_LIMIT = 64 * 1024

    /** `GET /health` through the edge over one raw TLS connection; returns the HTTP status line + body. */
    fun health(
        stack: ArcTunnelStack,
        fqdn: String,
    ): String {
        val socket: SSLSocket = stack.connectTls(fqdn)
        socket.use {
            it.outputStream.write("GET /health HTTP/1.1\r\nHost: $fqdn\r\nConnection: close\r\n\r\n".toByteArray())
            it.outputStream.flush()
            return it.inputStream.readNBytes(READ_LIMIT).decodeToString()
        }
    }

    /** An MCP client whose HTTPS goes to the local relay with the real name as SNI/Host, trusting the Pebble root. */
    fun stackMcpClient(
        stack: ArcTunnelStack,
        fqdn: String,
        bearerToken: String,
    ): McpClient {
        val trust = stack.pebbleTrustManager()
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val http: HttpClient =
            McpClient.httpClient(OkHttp) {
                preconfigured =
                    OkHttpClient
                        .Builder()
                        .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
                        .sslSocketFactory(ctx.socketFactory, trust)
                        .build()
            }
        return McpClient("https://$fqdn:${ArcTunnelStack.EDGE_HOST_PORT}", bearerToken, http)
    }

    /** An MCP client with the JVM's default trust store (production: a WebPKI certificate). */
    fun systemTrustMcpClient(
        publicUrl: String,
        bearerToken: String,
    ): McpClient = McpClient(publicUrl, bearerToken, McpClient.httpClient(OkHttp) {})
}
```

Definition of Done:
- [x] Test CA, S3 helper, relay, stack and public client as written.

### [x] Task 10.3 — The tests

[x] **Action 10.3.1** — create `E/E2EArcTunnelTest.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E: the free E2E tunnel against a local tunnel server stack ([ArcTunnelStack]). The redroid phone enrolls
 * (attestation optional on the server: redroid has a software KeyMint), the MCP server starts with the
 * tunnel, and the JVM reaches `/health` and `tools/list` THROUGH the tunnel with the Pebble root as trust
 * anchor. The shared container is restored (tunnel off, server restarted, client refreshed) afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EArcTunnelTest {
    private lateinit var stack: ArcTunnelStack
    private lateinit var status: E2EArcTunnelStatus
    private val baseUrl = SharedAndroidContainer.mcpServerUrl

    @BeforeAll
    fun startStackAndTunnel() {
        SharedAndroidContainer.ensureAccessibilityService()
        val gateway = AndroidContainerSetup.containerGatewayIp()
        stack = ArcTunnelStack(dialBackAdvertise = "$gateway:${ArcTunnelStack.EDGE_HOST_PORT}")
        stack.start()

        AndroidContainerSetup.stopMcpServer(baseUrl)
        AndroidContainerSetup.configureArcTunnel(
            ArcTunnelE2ESettings(
                tunnelEnabled = true,
                provider = ArcTunnelE2ESettings.ARC_PROVIDER,
                baseAddress = ArcTunnelStack.BASE_ADDRESS,
                ctCheckEnabled = false,
                edgeOverride = stack.edgeOverride(gateway),
                trustedCaPem = stack.issuingRootPem,
            ),
        )
        AndroidContainerSetup.enrollArcTunnel()
        AndroidContainerSetup.awaitArcTunnel(timeoutMs = ENROLL_TIMEOUT_MS) { it.enrolled && it.enrollment == "Idle" }
        AndroidContainerSetup.startMcpServer()
        AndroidContainerSetup.waitForServerReady(baseUrl)
        status = AndroidContainerSetup.awaitArcTunnel(timeoutMs = CONNECT_TIMEOUT_MS) { it.tunnel == "Connected" }
    }

    @AfterAll
    fun restoreSharedContainer() {
        try {
            AndroidContainerSetup.stopMcpServer(baseUrl)
            AndroidContainerSetup.configureArcTunnel(
                ArcTunnelE2ESettings(
                    tunnelEnabled = false,
                    provider = ArcTunnelE2ESettings.CLOUDFLARE_PROVIDER,
                    baseAddress = ArcTunnelStack.BASE_ADDRESS,
                    ctCheckEnabled = true,
                ),
            )
            AndroidContainerSetup.startMcpServer()
            AndroidContainerSetup.waitForServerReady(baseUrl)
            SharedAndroidContainer.refreshMcpClient()
        } finally {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    fun `the public URL is the enrolled name under the tunnel domain`() {
        val url = requireNotNull(status.url) { "no URL in $status" }
        assertTrue(url.startsWith("https://") && url.endsWith(".${ArcTunnelStack.TUNNEL_DOMAIN}"), url)
        assertEquals(url, status.tunnelUrl)
    }

    @Test
    fun `health is served through the tunnel with a Pebble-issued certificate`() {
        val response = ArcTunnelPublicClient.health(stack, fqdn())
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.contains("\"status\""), response)
    }

    @Test
    fun `tools are listed through the tunnel`() =
        runBlocking {
            val client = ArcTunnelPublicClient.stackMcpClient(stack, fqdn(), AndroidContainerSetup.E2E_BEARER_TOKEN)
            try {
                client.connect()
                val tools = client.listTools()
                assertTrue(tools.tools.any { it.name == "${AndroidContainerSetup.TOOL_NAME_PREFIX}press_home" })
            } finally {
                client.close()
            }
        }

    @Test
    fun `a second connection reuses the bound control stream`() {
        val first = ArcTunnelPublicClient.health(stack, fqdn())
        val second = ArcTunnelPublicClient.health(stack, fqdn())
        assertTrue(first.startsWith("HTTP/1.1 200") && second.startsWith("HTTP/1.1 200"), "$first\n$second")
        assertEquals("Connected", AndroidContainerSetup.queryArcTunnel().tunnel)
    }

    private fun fqdn(): String = requireNotNull(status.url).removePrefix("https://")

    companion object {
        private const val ENROLL_TIMEOUT_MS = 180_000L
        private const val CONNECT_TIMEOUT_MS = 120_000L
    }
}
```

Context: `stopMcpServer` before reconfiguring because the provider is chosen at service start (the service ignores `ACTION_START` while active). The accessibility service survives the stop/start (no force-stop here).

[x] **Action 10.3.2** — create `E/DeviceAdb.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

/**
 * The attached phone: the first `adb devices` entry in state `device` that is neither an `emulator-*` nor a
 * redroid container (`ro.boot.redroid_width` is set by the container's boot arguments). USB and `adb connect`
 * phones both qualify.
 */
object DeviceAdb {
    private const val INSTALL_TIMEOUT_S = 180L

    fun attachedSerial(): String? =
        AndroidContainerSetup
            .runProcess("adb", "devices")
            .lines()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" && !it[0].startsWith("emulator-") }
            .map { it[0] }
            .firstOrNull { !isRedroid(it) }

    fun install(
        serial: String,
        apkPath: String,
    ) {
        val result =
            AndroidContainerSetup.runProcess(
                "adb", "-s", serial, "install", "-r", apkPath,
                timeoutSeconds = INSTALL_TIMEOUT_S,
            )
        check(result.contains("Success")) { "APK installation failed on $serial: $result" }
    }

    private fun isRedroid(serial: String): Boolean =
        AndroidContainerSetup.execAdbOn(serial, "shell", "getprop", "ro.boot.redroid_width").isNotEmpty()
}
```

[x] **Action 10.3.3** — create `E/E2EArcTunnelDeviceTest.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * Device-gated E2E against the PRODUCTION tunnel service: real hardware attestation, real ACME certificate,
 * WebPKI trust. Skipped when no phone is attached (CI). Prerequisite: the local debug signer is allowlisted
 * on the server. The phone's tunnel settings (except the debug-only trusted CA PEM, which is not queryable and
 * is cleared by the restore) and bearer-enforcement flag are snapshotted and restored;
 * the bearer token is random per run and replaced by another random one afterwards (the public URL lands in
 * CT logs within minutes, so the committed E2E token is never exposed, and the previous token cannot be read
 * back). The phone keeps its enrollment (an enrolled phone is a no-op for `execute`); a server already running
 * on it is stopped first and left stopped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EArcTunnelDeviceTest {
    private var serial: String? = null
    private var before: E2EArcTunnelStatus? = null
    private lateinit var status: E2EArcTunnelStatus
    private val token = UUID.randomUUID().toString()

    @BeforeAll
    fun enrollAndStart() {
        val found = DeviceAdb.attachedSerial()
        assumeTrue(found != null, "No phone attached: skipping the production tunnel test")
        val device = requireNotNull(found)
        serial = device
        val rootDir = System.getProperty("project.rootDir") ?: error("System property 'project.rootDir' not set")
        DeviceAdb.install(device, "$rootDir/app/build/outputs/apk/gms/debug/app-gms-debug.apk")
        AndroidContainerSetup.stopMcpServerOn(device)
        before = AndroidContainerSetup.queryArcTunnel(device)
        setBearer(device, token, enabled = true)
        AndroidContainerSetup.configureArcTunnel(
            ArcTunnelE2ESettings(
                tunnelEnabled = true,
                provider = ArcTunnelE2ESettings.ARC_PROVIDER,
                baseAddress = PRODUCTION_BASE_ADDRESS,
                ctCheckEnabled = true,
            ),
            serial = device,
        )
        AndroidContainerSetup.enrollArcTunnel(device)
        AndroidContainerSetup.awaitArcTunnel(device, ENROLL_TIMEOUT_MS) { it.enrolled && it.enrollment == "Idle" }
        AndroidContainerSetup.startMcpServerOn(device)
        status = AndroidContainerSetup.awaitArcTunnel(device, CONNECT_TIMEOUT_MS) { it.tunnel == "Connected" }
    }

    @AfterAll
    fun restoreDevice() {
        val device = serial ?: return
        AndroidContainerSetup.stopMcpServerOn(device)
        val previous = before
        if (previous != null) {
            AndroidContainerSetup.configureArcTunnel(previous.toSettings(), serial = device)
            setBearer(device, UUID.randomUUID().toString(), enabled = previous.bearerTokenEnabled)
        }
    }

    @Test
    fun `tools are listed through the production tunnel with WebPKI trust`() =
        runBlocking {
            val url = requireNotNull(status.url) { "no URL in $status" }
            val client = ArcTunnelPublicClient.systemTrustMcpClient(url, token)
            try {
                client.connect()
                val names = client.listTools().tools.map { it.name }
                // The phone may carry a device slug, which changes the tool prefix: match the suffix only.
                assertTrue(names.any { it.endsWith("press_home") }, names.toString())
            } finally {
                client.close()
            }
        }

    /** The token never reaches a log: a failed broadcast is reported without the command line. */
    private fun setBearer(
        device: String,
        value: String,
        enabled: Boolean,
    ) {
        try {
            AndroidContainerSetup.execAdbOn(
                device, "shell", "am", "broadcast", "--include-stopped-packages",
                "-a", AndroidContainerSetup.E2E_CONFIGURE_ACTION, "-n", AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
                "--es", "bearer_token", value,
                "--ez", "bearer_token_enabled", enabled.toString(),
            )
        } catch (_: IllegalStateException) {
            throw IllegalStateException("bearer token broadcast failed on $device")
        }
        AndroidContainerSetup.awaitArcTunnel(device, 30_000L) { it.bearerTokenEnabled == enabled }
    }

    companion object {
        /** The production base address: confirmed with the user before the quality gates run. */
        private const val PRODUCTION_BASE_ADDRESS = "arc-tunnel.io"
        private const val ENROLL_TIMEOUT_MS = 180_000L
        private const val CONNECT_TIMEOUT_MS = 120_000L
    }
}
```

Context: the device has no port-forwarded base URL, so stops go through `stopMcpServerOn` (status query). On an already-enrolled phone `enrolled && Idle` holds at once and the enroll request is a no-op (US5), so no name is burnt per run. The `assembleGmsDebug` APK is already a dependency of the e2e task.

Definition of Done:
- [x] Both tests and `DeviceAdb` as written.

---

## [x] User Story 11 — Documentation

Why: `docs/PROJECT.md`, `docs/ARCHITECTURE.md` and `README.md` describe the tunnel providers, the defaults, the ADB keys and the e2e infrastructure; they must describe the third provider. Mermaid changes are validated with `mmdc`.

Acceptance criteria:
- [x] The four documents (`docs/PROJECT.md`, `docs/ARCHITECTURE.md`, `README.md`, `docs/PERMISSIONS.md`) describe the free E2E tunnel (provider, defaults, migration, ADB keys incl. `arc_tunnel_enroll`, the enrollment foreground service and its permission, e2e stack); the architecture diagram renders with `mmdc`.

### [x] Task 11.1 — PROJECT.md

[x] **Action 11.1.1** — modify `docs/PROJECT.md`:

```markdown
<!-- Tech Stack: add after the Kotlinx Coroutines bullet -->
- **OkHttp**: 5.5.0, the free E2E tunnel's HTTP/2 duplex control stream, `/issue` and Certificate Transparency calls (duplex request bodies are not expressible through the Ktor client)

<!-- Tech Stack → Testing: add after the Turbine bullet -->
- **MockWebServer3 + okhttp-tls**: TLS/HTTP-2 mock server and test certificates for the free E2E tunnel client tests

<!-- Folder Structure: replace the `services/tunnel/` line -->
  - `services/tunnel/` — `TunnelProvider.kt`, `TunnelManager.kt`, `TunnelListenerHost.kt`, `CloudflareTunnelProvider.kt`, `CloudflaredBinaryResolver.kt`, `AndroidCloudflareBinaryResolver.kt`, `NgrokTunnelProvider.kt`
  - `services/tunnel/arctunnel/` — free E2E tunnel client (`android-remote-control-mcp-tunneld` protocol v2): `ArcTunnelHosts.kt`, `ArcTunnelEnv.kt`, `ArcTunnelPem.kt`, `ArcTunnelSockets.kt`, `ArcTunnelWire.kt`, `ArcTunnelFrames.kt`, `ArcTunnelCsr.kt`, `ArcTunnelSessions.kt`, `ArcTunnelKeyStore.kt`, `AndroidArcTunnelKeyStore.kt`, `ArcTunnelIdentity.kt`, `ArcTunnelNet.kt`, `ArcTunnelHttp.kt`, `ArcTunnelDialBack.kt`, `ArcTunnelEnrollRetry.kt`, `ArcTunnelIssueRetry.kt`, `ArcTunnelEnroller.kt`, `ArcTunnelIdentityStore.kt`, `ArcTunnelBackoff.kt`, `ArcTunnelLocalConnect.kt`, `ArcTunnelSplice.kt`, `ArcTunnelWatchdog.kt`, `ArcTunnelControlEvents.kt`, `ArcTunnelOpenHandler.kt`, `ArcTunnelControl.kt`, `ArcTunnelEnrollment.kt`, `ArcTunnelEnrollmentFlow.kt`, `ArcTunnelStartTracker.kt`, `ArcTunnelEnrollmentImpl.kt`, `ArcTunnelEnrollService.kt`, `ArcTunnelCtNotifier.kt`, `ArcTunnelCtAlerts.kt`, `ArcTunnelCtMonitor.kt`, `ArcTunnelCertSwap.kt`, `ArcTunnelProvider.kt`

<!-- Folder Structure: `ui/screens/` line — append -->
, `settings/ArcTunnelSettingsSection.kt`
<!-- `ui/viewmodels/` line — append -->
, `ArcTunnelViewModel.kt`
<!-- `data/repository/` line — append -->
, `ArcTunnelSettings.kt`, `ArcTunnelSettingsImpl.kt`
<!-- `app/src/debug/` line — replace -->
- `app/src/debug/` — `AndroidManifest.xml` (debug overlay), `kotlin/.../debug/E2EConfigReceiver.kt`, `E2EArcTunnelStatus.kt`, `E2EArcTunnelLabels.kt`

<!-- E2E Tests: replace the Scope and Infrastructure bullets -->
- **Scope**: Full MCP client → server → Android → action flow, Calculator app test (7 + 3 = 10), screenshot capture validation, error handling (auth, unknown tool, invalid params, node not found), the free E2E tunnel against a local tunnel server stack (`E2EArcTunnelTest`) and, when a phone is attached, against production (`E2EArcTunnelDeviceTest`, skipped otherwise)
- **Infrastructure**: `SharedAndroidContainer` singleton shares one container across all test classes (avoids ~2-4 min boot per class); `McpClient` test utility wraps SDK `Client` + `StreamableHttpClientTransport` (trust-all CIO by default, or an injected OkHttp client for the tunnel tests); `E2EConfigReceiver` debug-only BroadcastReceiver injects test settings via `adb shell am broadcast` (incl. the tunnel edge override, trusted CA and a status query); `ArcTunnelStack` runs the tunnel server stack (Valkey, MinIO, Pebble + challtestsrv, `ghcr.io/danielealbano/android-remote-control-mcp-tunneld:latest`, always pulled, never built) with ACME `tls-alpn-01`, and `ArcTunnelHostRelay` exposes the edge to the phone through the container's gateway IP

<!-- "Security Practices" → HTTPS section, the "Remote access tunnels" bullet: replace -->
- **Remote access tunnels**: The app integrates with the **free E2E tunnel** (`android-remote-control-mcp-tunneld`: no account, end-to-end encrypted, a public certificate issued to the device, the relay sees ciphertext only), **Cloudflare Quick Tunnels** (no account required, random `*.trycloudflare.com` URL) and **ngrok** (account required, optional custom domain) to expose the local MCP server via a public HTTPS URL with valid certificates. See the Remote Access / Tunnel section below.

<!-- Remote Access / Tunnel: insert as the FIRST provider bullet -->
- **Free E2E tunnel** (default for fresh installs): speaks the `android-remote-control-mcp-tunneld` wire protocol v2. Enrollment is a two-phase attested handshake (AndroidKeyStore P-256 keys with hardware attestation replace an account); the server assigns `<name>.d.<base>` and a WebPKI certificate (Let's Encrypt, Google Trust Services or ZeroSSL) is issued to the device's own TLS key. The phone keeps one outbound HTTP/2 mTLS control stream to `connect.<base>` and, per public connection, dials back to the entry node and splices it to a loopback TLS listener (random port, served by `McpServer` through `TunnelListenerHost`) — the relay never sees plaintext. One "base address" setting (default `arc-tunnel.io`) derives `enroll.<base>`, `connect.<base>` and `d.<base>` (self-hosting). A Certificate Transparency check (SSLMate Cert Spotter, on by default, 1 % of requests, ≤ 1 per 30 min) alerts when a certificate for the name carries another public key. Identity metadata lives in an app-private JSON file per base address; private keys never leave the keystore.

<!-- Remote Access / Tunnel architecture: add after the `McpServerService` bullet -->
- `TunnelListenerHost` (implemented by `McpServer`) opens/closes the loopback TLS listener the free E2E tunnel needs; `TunnelManager.start(localPort, listenerHost)` binds it to `ArcTunnelProvider`
- The HTTPS guard (no tunnel while the server serves HTTPS) applies to Cloudflare/ngrok only; the free E2E tunnel has its own listener

<!-- Permission Security: in the "Only necessary permissions" sentence, add after `FOREGROUND_SERVICE` -->
`FOREGROUND_SERVICE_DATA_SYNC` (free E2E tunnel enrollment foreground service)

<!-- Default Configuration: replace the two tunnel bullets -->
- **Remote Access Tunnel**: Disabled by default
- **Tunnel Provider**: Free E2E tunnel (`android-remote-control-mcp-tunneld`, no account) for fresh installs. Existing installs that ever wrote a tunnel setting keep **Cloudflare** (one-time migration, `tunnel_provider_migrated`)
- **Free E2E tunnel base address**: `arc-tunnel.io`
- **Free E2E tunnel CT check**: Enabled
```

Definition of Done:
- [x] Edits applied as written.

### [x] Task 11.2 — ARCHITECTURE.md

[x] **Action 11.2.1** — modify `docs/ARCHITECTURE.md`:

```markdown
<!-- diagram: replace the Tunnel subgraph -->
            subgraph Tunnel["TunnelManager (optional)"]
                Arc["ArcTunnelProvider\n(free E2E tunnel, in-process)"]
                CF["CloudflareTunnelProvider\n(process-based)"]
                Ngrok["NgrokTunnelProvider\n(in-process JNI)"]
                PubURL["Public HTTPS URL\n(*.d.arc-tunnel.io / *.trycloudflare.com / ngrok)"]
                Arc --> PubURL
                CF --> PubURL
                Ngrok --> PubURL
            end
<!-- diagram: add after `HTTP --> StreamHTTP --> Auth --> SDK` (inside the Ktor subgraph) -->
                TLSL["Loopback TLS listener\n(TunnelListenerHost, free E2E tunnel)"]
                TLSL --> StreamHTTP
<!-- diagram: add AFTER the Tunnel subgraph's `end` (an edge inside a subgraph would pull `Arc` into it) -->
            Arc -. "dial-back splice" .-> TLSL

<!-- Permission Model table: add after the FOREGROUND_SERVICE row -->
| FOREGROUND_SERVICE_DATA_SYNC | Normal    | Auto-granted (manifest)            | Free E2E tunnel enrollment service |

<!-- Startup Sequence step g: replace -->
     g. If tunnel enabled: starts `TunnelManager` with the `McpServer` as `TunnelListenerHost` (free E2E tunnel: resumes the saved identity, issues a missing certificate, opens the loopback TLS listener and the control stream; Cloudflare/ngrok: as before)
```
```markdown
<!-- Coroutine Scopes table: add two rows -->
| ArcTunnelEnrollService | Custom `CoroutineScope` (IO) | Service start to the end of the last request |
| ArcTunnelProvider session | Custom `CoroutineScope` (IO + handler) | Tunnel start to stop (per session) |
```
Validate: extract the diagram to a file in the session scratchpad directory and run `mmdc -i <file>.mmd -o <file>.svg` (load nvm first if `mmdc` is missing: `. "$NVM_DIR/nvm.sh"`); fix until it renders.

Definition of Done:
- [x] Edits applied as written; `mmdc` renders the diagram.

### [x] Task 11.3 — README.md

[x] **Action 11.3.1** — modify `README.md`:

~~~markdown
<!-- Features bullets: replace -->
- Remote access tunnels via the free end-to-end encrypted tunnel (no account), Cloudflare Quick Tunnels or ngrok (public HTTPS URL)
- Remote access tunnel configuration (free E2E tunnel / Cloudflare / ngrok)

<!-- Claude.ai step 2: replace "(Cloudflare Quick Tunnels needs no account)" with -->
(the default free E2E tunnel needs no account; the first setup takes about 10–30 seconds)
<!-- and its example URL `https://your-tunnel.trycloudflare.com/mcp` with -->
`https://<name>.d.arc-tunnel.io/mcp`

<!-- Default settings table row: replace -->
| Remote Access Tunnel | Disabled | Expose server via public HTTPS URL (free E2E tunnel by default; Cloudflare Quick Tunnels or ngrok) |

<!-- Using Remote Access Tunnels: replace the numbered list -->
1. **Free E2E tunnel** (default, no account required): end-to-end encrypted. The phone enrolls with hardware key attestation, gets a name under `d.arc-tunnel.io` and a public certificate (Let's Encrypt, Google Trust Services or ZeroSSL) issued to its own key, and terminates TLS itself — the tunnel service only relays ciphertext (unlike Cloudflare or ngrok, which read the traffic). The refresh icon next to the URL re-enrolls under a new name. A Certificate Transparency check (on by default) warns if a certificate for your name is ever issued to another key. Self-hosters change the base address.
2. **Cloudflare Quick Tunnels** (no account required): Creates a temporary tunnel with a random `*.trycloudflare.com` HTTPS URL.
3. **ngrok** (account required): Supports optional custom domains. Requires an ngrok authtoken (free tier available). Available on arm64-v8a and x86_64 devices.

<!-- Headless Setup example: replace the two lines `--ez tunnel_enabled false \` and `--es tunnel_provider "CLOUDFLARE" \`
     with these four (the cloudflare_* and ngrok_* lines stay as they are) -->
  --ez tunnel_enabled true \
  --es tunnel_provider "android-remote-control-mcp-tunneld" \
  --es arc_tunnel_base_address "arc-tunnel.io" \
  --ez arc_tunnel_ct_check_enabled true \

<!-- after the "Stop the MCP Server" subsection, add -->
#### Enroll the Free E2E Tunnel

Enrollment (getting the device's name and certificate) runs in a short foreground service. Headless, use the same trampoline Activity as for starting the server; it also resumes a failed certificate issuance. Run it while the server is stopped.

```bash
adb shell am start \
  -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
  --es action enroll-tunnel
```

`--ez arc_tunnel_enroll true` on the configuration broadcast does the same when the app is visible; from the background Android 12+ refuses the foreground service start and the app reports "background start refused".

<!-- Permissions Reference table: add after the FOREGROUND_SERVICE_LOCATION row -->
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | Foreground service type for the free E2E tunnel enrollment (name + certificate) |

<!-- ADB keys table: replace the tunnel_provider row and add three rows after it -->
| `tunnel_provider` | string | `android-remote-control-mcp-tunneld` (free E2E tunnel), `CLOUDFLARE` or `NGROK` |
| `arc_tunnel_base_address` | string | Base address of the free E2E tunnel service (default `arc-tunnel.io`; hosts `enroll.`, `connect.` and `d.` are derived) |
| `arc_tunnel_ct_check_enabled` | boolean | Certificate Transparency check for the free E2E tunnel (default `true`) |
| `arc_tunnel_enroll` | boolean | `true` enrolls the free E2E tunnel (or resumes a failed certificate issuance) with the settings in the same broadcast, while the app is visible and the server is stopped; headless, use the trampoline action `enroll-tunnel` below. The public URL appears in the Server tab |
~~~

[x] **Action 11.3.2** — modify `docs/PERMISSIONS.md` ("Permission categories", the **Normal** bullet): add `FOREGROUND_SERVICE_DATA_SYNC` after `FOREGROUND_SERVICE_LOCATION`.

Definition of Done:
- [x] Edits applied as written.

---

## [x] User Story 12 — Confirmations, quality gates, review, PR

Why: D3 and D13 need the user's word before the gates; the gates run once at the end (CLAUDE.md); the plan-compliance review and the PR close the work.

Acceptance criteria:
- [x] The user confirmed the production base address (D3), the debug-signer allowlist (D13) and whether the phone is attached BEFORE any gate ran.
- [x] `make lint`, `make test-unit`, `make build` and `make test-e2e` pass with their output captured; the `code-reviewer` (plan compliance mode) is clean; the PR exists.

### [x] Task 12.1 — Confirmations with the user (ASK, do not assume)

- [x] D3: "Is `arc-tunnel.io` the production base address to ship as the default?" If not: change `ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS`, `E2EArcTunnelDeviceTest.PRODUCTION_BASE_ADDRESS`, the `ARCHITECTURE.md` diagram label, the README and PROJECT.md mentions in one commit.
- [x] D13: "Is the local debug signer's SHA-256 allowlisted on the production server?" and "Is the phone attached (plain `adb devices` shows it)?" — if either is "no", the device-gated test is expected to be skipped (no phone) or to FAIL (phone attached, signer not allowlisted): in the latter case STOP and ask.
- [x] Device test side effects: "The device test stops a running MCP server on the phone, replaces the DEBUG app's bearer token with a random one (it cannot be read back) and restores the tunnel settings afterwards — OK?" and "Is the phone unlocked with the screen on for the whole run?" (the stop/enroll commands start foreground services, which need the activity visible).

Definition of Done:
- [x] All Task 12.1 confirmations obtained and recorded in this plan (checkmarks) before any gate ran.

### [x] Task 12.2 — Quality gates (ALL captured with `tee`, inspected from the log, never re-run to grep)

- [x] `make lint 2>&1 | tee /tmp/plan66-lint.log | tail -20` → zero ktlint/detekt findings (fix root causes; no suppressions beyond D18).
- [x] `make test-unit 2>&1 | tee /tmp/plan66-test-unit.log | tail -20` → all unit + JVM integration tests pass (`EventDispatcherImplTest` is known-flaky in the full suite: re-run ONCE before treating it as a regression).
- [x] `make build 2>&1 | tee /tmp/plan66-build.log | tail -20` and `make build-foss 2>&1 | tee /tmp/plan66-build-foss.log | tail -20` → no errors, no warnings (both flavors, D13).
- [x] `make test-e2e 2>&1 | tee /tmp/plan66-test-e2e.log | tail -40` → all e2e tests pass, `E2EArcTunnelTest` included; `E2EArcTunnelDeviceTest` passes with the phone attached (or is reported as skipped without one). `make test-e2e` targets the rootful podman socket (CI); locally, when `/run/podman/podman.sock` is not readable, run the same Gradle tasks against Docker Engine: `TESTCONTAINERS_RYUK_DISABLED=true DOCKER_HOST=unix:///var/run/docker.sock ./gradlew :e2e-tests:cleanTest :e2e-tests:test 2>&1 | tee /tmp/plan66-test-e2e.log | tail -40`.

Definition of Done:
- [x] All four gates green with their logs under `/tmp/`.

### [x] Task 12.3 — Review and PR

- [x] Spawn the `code-reviewer` subagent (model `opus`) in plan compliance mode over the ENTIRE implementation; fix ALL findings; re-run until clean.
- [x] Commits: one logical commit per task at least, pushed after every user story; no `git add -A`; no plan file other than this one's checkmarks is touched.
- [x] `gh pr create --base main` following `docs/TOOLS.md` (title `Plan 66: free E2E tunnel provider (android-remote-control-mcp-tunneld)`), body with Summary, Plan Reference, Changes, Testing (incl. the **Manual QA Steps** of User Stories 5 and 8 and the gate log paths), Checklist. Report the PR URL.

Definition of Done:
- [x] Reviewer clean, commits pushed, PR URL reported.
