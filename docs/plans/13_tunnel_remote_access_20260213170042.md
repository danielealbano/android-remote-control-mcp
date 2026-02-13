# Plan 13: Tunnel-Based Remote Access (Cloudflare + ngrok)

## Overview

Add tunnel support to the MCP server so users can expose the server over the internet with valid HTTPS, using either **Cloudflare Quick Tunnels** (zero config, no account, random URL per session) or **ngrok** (authtoken required, stable URL). The tunnel lifecycle is tied to the MCP server: it starts when the server starts (if enabled) and stops when the server stops.

### Key Decisions (from discussion)

- **Cloudflare**: Quick Tunnels only. Bundled `cloudflared` binary run as a child process. No account needed. Random `*.trycloudflare.com` URL each session.
- **ngrok**: Uses `ngrok-java` library (in-process, JNI). Requires authtoken. Optional domain field for paid/free static domains.
- **ABIs**: `arm64-v8a` + `x86_64` for cloudflared binary. ngrok-java only provides `arm64-v8a` (no x86_64) — graceful error on unsupported ABI.
- **Cloudflared build**: Standard (non-FIPS) build. FIPS builds are Linux amd64-only and irrelevant for Android. `go build ./cmd/cloudflared` produces non-FIPS by default.
- **Tunnel lifecycle**: Tied to MCP server start/stop.
- **Binding address**: Left as-is (0.0.0.0 includes 127.0.0.1, tunnel connects to localhost).
- **Settings in DataStore**: `tunnelEnabled`, `tunnelProvider`, `ngrokAuthtoken`, `ngrokDomain`.
- **Binary size**: Not a concern — bundle cloudflared in the APK.

### Cross-Plan Dependency: Plan 12 (MCP SDK Migration)

**IMPORTANT**: Plan 12 (`12_migrate_mcp_sdk_streamable_http_20260213164959.md`) is being implemented concurrently and makes changes that affect this plan:

1. **`McpServerService.kt` structure changes**: Plan 12 removes `protocolHandler` and `toolRegistry` injections, changes `registerAllTools()` to accept an SDK `Server` parameter, and changes `McpServer` constructor (removes `protocolHandler`, adds `mcpSdkServer`). Plan 13's Task 9 diffs are based on the **pre-plan-12** McpServerService. During implementation, the implementer MUST anchor Plan 13's diffs in the **post-plan-12** code. The logical insertion points are the same (tunnel starts after server, stops before server) but surrounding context lines will differ.

2. **`/health` endpoint removed**: Plan 12 removes the `/health` endpoint entirely. The only endpoint after plan 12 is `POST /mcp` (Streamable HTTP at `/mcp`). Plan 13's Cloudflare tunnel integration test (Task 13) uses a standalone simple HTTP test server, NOT the MCP server — so this does not affect the tunnel test logic, only the naming.

3. **`build.gradle.kts` / `libs.versions.toml`**: Plan 12 adds SDK/SLF4J dependencies; Plan 13 adds ngrok-java. These are additive and non-conflicting.

4. **Files NOT touched by Plan 12** (verified no conflict): `MainViewModel.kt`, `MainViewModelTest.kt`, `ServerLogEntry.kt`, `HomeScreen.kt`, `ServerLogsSection.kt`, CI pipeline.

---

## User Story 1: Remote Access via Tunnel Providers

**As a user**, I want to enable remote access to my MCP server via a tunnel provider (Cloudflare or ngrok), so that I can securely control my phone from anywhere over the internet with valid HTTPS.

### Acceptance Criteria / Definition of Done

- [ ] User can toggle "Remote Access" on/off in settings
- [ ] User can select between Cloudflare and ngrok as tunnel provider
- [ ] When ngrok is selected, user can enter authtoken and optionally a domain
- [ ] When MCP server starts with tunnel enabled, the tunnel starts automatically
- [ ] When MCP server stops, the tunnel stops automatically
- [ ] The public tunnel URL is displayed in the ConnectionInfoCard (with Copy All and Share buttons)
- [ ] The public tunnel URL is logged to logcat (`Log.i`)
- [ ] The public tunnel URL is logged in the UI server logs (via generalized `ServerLogEntry`)
- [ ] Cloudflare tunnel works without any account or configuration
- [ ] ngrok tunnel works with a valid authtoken
- [ ] ngrok shows graceful error on x86_64 (unsupported ABI)
- [ ] Bearer token authentication still protects all MCP endpoints through the tunnel
- [ ] All unit tests pass
- [ ] Real Cloudflare tunnel integration test passes
- [ ] Lint passes (`make lint`)
- [ ] Build succeeds without warnings (`./gradlew build`)

---

### Task 1: Data Models and TunnelProvider Interface

**Goal**: Define the data models and interface for tunnel providers.

**Acceptance Criteria**:
- [x] `TunnelProviderType` enum exists with `CLOUDFLARE` and `NGROK` values
- [x] `TunnelStatus` sealed class exists with `Disconnected`, `Connecting`, `Connected(url, providerType)`, `Error(message)` states
- [x] `TunnelProvider` interface exists with `start`, `stop`, and status flow
- [x] All new files compile without warnings

#### Action 1.1: Create `TunnelProviderType` enum

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TunnelProviderType.kt` (NEW)

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Available tunnel provider types for remote access.
 */
enum class TunnelProviderType {
    /** Cloudflare Quick Tunnel — no account needed, random URL per session. */
    CLOUDFLARE,

    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK,
}
```

**Definition of Done**:
- [x] File created and compiles

#### Action 1.2: Create `TunnelStatus` sealed class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TunnelStatus.kt` (NEW)

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents the current state of the tunnel connection.
 *
 * Used as [kotlinx.coroutines.flow.StateFlow] value, observed by the UI
 * to display tunnel status and public URL.
 */
sealed class TunnelStatus {
    /** Tunnel is not active. */
    data object Disconnected : TunnelStatus()

    /** Tunnel is establishing a connection. */
    data object Connecting : TunnelStatus()

    /**
     * Tunnel is connected and serving traffic.
     *
     * @property url The public HTTPS URL (e.g., "https://xxx.trycloudflare.com" or "https://xxx.ngrok-free.app").
     * @property providerType The provider that created this tunnel.
     */
    data class Connected(
        val url: String,
        val providerType: TunnelProviderType,
    ) : TunnelStatus()

    /**
     * Tunnel encountered an error.
     *
     * @property message A human-readable error description.
     */
    data class Error(val message: String) : TunnelStatus()
}
```

**Definition of Done**:
- [x] File created and compiles

#### Action 1.3: Create `TunnelProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/TunnelProvider.kt` (NEW)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for tunnel providers that expose a local server to the internet.
 *
 * Implementations create a tunnel from a public HTTPS URL to a local port.
 * The tunnel lifecycle is managed by [TunnelManager], which reads the
 * [ServerConfig] and passes it to the provider — providers do NOT read
 * settings directly.
 */
interface TunnelProvider {
    /** Observable tunnel status. */
    val status: StateFlow<TunnelStatus>

    /**
     * Starts the tunnel, forwarding traffic from a public URL to [localPort] on localhost.
     *
     * This is a suspend function that returns once the tunnel is established
     * (status becomes [TunnelStatus.Connected]) or fails (status becomes [TunnelStatus.Error]).
     *
     * @param localPort The local port to forward traffic to.
     * @param config The current server configuration (providers extract their own
     *               relevant fields, e.g. ngrok reads [ServerConfig.ngrokAuthtoken]
     *               and [ServerConfig.ngrokDomain]).
     * @throws IllegalStateException if the tunnel is already running.
     */
    suspend fun start(localPort: Int, config: ServerConfig)

    /**
     * Stops the tunnel and releases all resources.
     *
     * After calling stop, the status will be [TunnelStatus.Disconnected].
     * Safe to call even if the tunnel is not running (no-op in that case).
     */
    suspend fun stop()
}
```

**Definition of Done**:
- [x] File created and compiles

---

### Task 2: Settings and DataStore Changes

**Goal**: Add tunnel-related settings to `ServerConfig`, `SettingsRepository`, and `SettingsRepositoryImpl`.

**Acceptance Criteria**:
- [x] `ServerConfig` has `tunnelEnabled`, `tunnelProvider`, `ngrokAuthtoken`, `ngrokDomain` fields
- [x] `SettingsRepository` has update methods for all new settings
- [x] `SettingsRepositoryImpl` persists all new settings in DataStore
- [x] Defaults: `tunnelEnabled = false`, `tunnelProvider = CLOUDFLARE`, `ngrokAuthtoken = ""`, `ngrokDomain = ""`
- [x] `SettingsRepositoryImplTest` updated with tests for new settings
- [x] Targeted test passes: `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryImplTest"`

#### Action 2.1: Add tunnel fields to `ServerConfig`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`

Add 4 new fields to the data class. No import is needed because `TunnelProviderType` is in the same `data.model` package as `ServerConfig`:

```diff
 data class ServerConfig(
     val port: Int = DEFAULT_PORT,
     val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
     val bearerToken: String = "",
     val autoStartOnBoot: Boolean = false,
     val httpsEnabled: Boolean = false,
     val certificateSource: CertificateSource = CertificateSource.AUTO_GENERATED,
     val certificateHostname: String = DEFAULT_CERTIFICATE_HOSTNAME,
+    val tunnelEnabled: Boolean = false,
+    val tunnelProvider: TunnelProviderType = TunnelProviderType.CLOUDFLARE,
+    val ngrokAuthtoken: String = "",
+    val ngrokDomain: String = "",
 )
```

**Definition of Done**:
- [x] `ServerConfig` compiles with new fields and defaults

#### Action 2.2: Add tunnel methods to `SettingsRepository` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

Add import for `TunnelProviderType`. Add 4 new update methods:

```diff
+import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType

 interface SettingsRepository {
     // ... existing methods ...

+    /** Updates the tunnel enabled toggle. */
+    suspend fun updateTunnelEnabled(enabled: Boolean)
+
+    /** Updates the tunnel provider type. */
+    suspend fun updateTunnelProvider(provider: TunnelProviderType)
+
+    /** Updates the ngrok authtoken. */
+    suspend fun updateNgrokAuthtoken(authtoken: String)
+
+    /** Updates the ngrok domain (optional, empty string means auto-assigned). */
+    suspend fun updateNgrokDomain(domain: String)
 }
```

**Definition of Done**:
- [x] Interface compiles with new methods

#### Action 2.3: Implement tunnel settings in `SettingsRepositoryImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

Add import for `TunnelProviderType`. Add 4 DataStore keys in companion. Add 4 override methods. Update `mapPreferencesToServerConfig`:

Add keys to companion:
```kotlin
private val TUNNEL_ENABLED_KEY = booleanPreferencesKey("tunnel_enabled")
private val TUNNEL_PROVIDER_KEY = stringPreferencesKey("tunnel_provider")
private val NGROK_AUTHTOKEN_KEY = stringPreferencesKey("ngrok_authtoken")
private val NGROK_DOMAIN_KEY = stringPreferencesKey("ngrok_domain")
```

Add override methods (same pattern as existing ones):
```kotlin
override suspend fun updateTunnelEnabled(enabled: Boolean) {
    dataStore.edit { prefs -> prefs[TUNNEL_ENABLED_KEY] = enabled }
}

override suspend fun updateTunnelProvider(provider: TunnelProviderType) {
    dataStore.edit { prefs -> prefs[TUNNEL_PROVIDER_KEY] = provider.name }
}

override suspend fun updateNgrokAuthtoken(authtoken: String) {
    dataStore.edit { prefs -> prefs[NGROK_AUTHTOKEN_KEY] = authtoken }
}

override suspend fun updateNgrokDomain(domain: String) {
    dataStore.edit { prefs -> prefs[NGROK_DOMAIN_KEY] = domain }
}
```

Update `mapPreferencesToServerConfig` to include the new fields:
```diff
 return ServerConfig(
     // ... existing fields ...
+    tunnelEnabled = prefs[TUNNEL_ENABLED_KEY] ?: false,
+    tunnelProvider =
+        TunnelProviderType.entries.firstOrNull {
+            it.name == (prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.CLOUDFLARE.name)
+        } ?: TunnelProviderType.CLOUDFLARE,
+    ngrokAuthtoken = prefs[NGROK_AUTHTOKEN_KEY] ?: "",
+    ngrokDomain = prefs[NGROK_DOMAIN_KEY] ?: "",
 )
```

**Definition of Done**:
- [x] Implementation compiles
- [x] Follows existing DataStore patterns exactly

#### Action 2.4: Update `SettingsRepositoryImplTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

Add nested test classes for each new setting, following the existing patterns (e.g., the `UpdatePort` nested class):

- `UpdateTunnelEnabled` — test toggle true/false, verify persisted
- `UpdateTunnelProvider` — test CLOUDFLARE/NGROK, verify persisted
- `UpdateNgrokAuthtoken` — test storing and reading token string
- `UpdateNgrokDomain` — test storing and reading domain string
- Update the `GetServerConfig` default values test to include new fields

**Definition of Done**:
- [x] All new tests pass: `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryImplTest"`

---

### Task 3: Cloudflared Binary Setup

**Goal**: Set up cloudflared as a git submodule, cross-compile the binary for Android (arm64-v8a and x86_64), and bundle it in the APK.

**Acceptance Criteria**:
- [x] `cloudflared` repository added as a git submodule pinned to a specific tag/commit
- [x] Makefile target `compile-cloudflared` exists and cross-compiles from the submodule source for both ABIs
- [x] Compiled binaries placed in `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` and `app/src/main/jniLibs/x86_64/libcloudflared.so`
- [x] Binaries are executable on Android
- [x] `.gitignore` updated to exclude compiled binaries from jniLibs (they are build artifacts)

#### Action 3.0: Add cloudflared as a git submodule

Add the cloudflared repository as a git submodule pinned to a specific release tag:

```bash
git submodule add https://github.com/cloudflare/cloudflared.git vendor/cloudflared
cd vendor/cloudflared
git checkout 2026.2.0   # Pin to a specific release tag
cd ../..
git add .gitmodules vendor/cloudflared
```

This ensures:
- Reproducible builds (pinned to an exact commit for that tag)
- No temporary directories or network fetches during `make compile-cloudflared`
- Easy version updates via `cd vendor/cloudflared && git fetch && git checkout <new-tag>`

Also add to `.gitignore`:
```
# Native binaries in jniLibs (build artifacts — cloudflared from make compile-cloudflared, ngrok from Gradle extractNgrokNative)
app/src/main/jniLibs/
```

Note: Ignore the entire `jniLibs/` directory because ALL `.so` files in it are build artifacts:
- `libcloudflared.so` (arm64-v8a, x86_64) — compiled from the git submodule via `make compile-cloudflared`
- ngrok native `.so` files (arm64-v8a) — extracted by the `extractNgrokNative` Gradle task during preBuild

**Definition of Done**:
- [x] `vendor/cloudflared` submodule exists and points to the correct tag
- [x] `.gitmodules` file created with submodule entry
- [x] `.gitignore` updated for compiled binaries

#### Action 3.1: Add Makefile target for cloudflared cross-compilation

**File**: `Makefile`

Add a new section before the "All-in-One" section:

```makefile
# ─────────────────────────────────────────────────────────────────────────────
# Native Binary Compilation (cloudflared)
# ─────────────────────────────────────────────────────────────────────────────

CLOUDFLARED_SRC_DIR := vendor/cloudflared
JNILIBS_DIR := app/src/main/jniLibs

compile-cloudflared: ## Cross-compile cloudflared for Android (requires Go + Android NDK)
	@if [ ! -f "$(CLOUDFLARED_SRC_DIR)/cmd/cloudflared/main.go" ]; then \
		echo "ERROR: cloudflared submodule not initialized."; \
		echo "Run: git submodule update --init vendor/cloudflared"; \
		exit 1; \
	fi
	@echo "Compiling cloudflared from submodule ($(CLOUDFLARED_SRC_DIR))..."
	@echo ""
	@echo "Compiling cloudflared for arm64-v8a..."
	mkdir -p $(JNILIBS_DIR)/arm64-v8a
	cd $(CLOUDFLARED_SRC_DIR) && \
		CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
		CC=$$(find $$ANDROID_HOME/ndk -name "aarch64-linux-android*-clang" | sort -V | tail -1) \
		go build -a -installsuffix cgo -ldflags="-s -w" \
		-o $(CURDIR)/$(JNILIBS_DIR)/arm64-v8a/libcloudflared.so \
		./cmd/cloudflared
	@echo ""
	@echo "Compiling cloudflared for x86_64..."
	mkdir -p $(JNILIBS_DIR)/x86_64
	cd $(CLOUDFLARED_SRC_DIR) && \
		CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
		CC=$$(find $$ANDROID_HOME/ndk -name "x86_64-linux-android*-clang" | sort -V | tail -1) \
		go build -a -installsuffix cgo -ldflags="-s -w" \
		-o $(CURDIR)/$(JNILIBS_DIR)/x86_64/libcloudflared.so \
		./cmd/cloudflared
	@echo ""
	@echo "cloudflared compiled successfully for arm64-v8a and x86_64"
```

Also add `compile-cloudflared` to the `.PHONY` list at the top.

**Definition of Done**:
- [x] `make compile-cloudflared` runs successfully (requires Go + Android NDK + initialized submodule)
- [x] Binaries exist in `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` and `app/src/main/jniLibs/x86_64/libcloudflared.so`
- [x] Binaries are statically linked enough to run on Android (no glibc dependency)

#### Action 3.2: Add `check-deps` entry for Go (optional, for compilation)

**File**: `Makefile`

Add Go check to `check-deps` target (as optional/informational, since Go is only needed for `compile-cloudflared`):

```diff
+	if command -v go >/dev/null 2>&1; then \
+		GO_VER=$$(go version); \
+		echo "  [OK] $$GO_VER"; \
+	else \
+		echo "  [INFO] Go not found (only needed for compile-cloudflared target)"; \
+	fi; \
```

**Definition of Done**:
- [x] `make check-deps` reports Go presence (or notes it as optional)

---

### Task 4: ngrok-java Gradle Setup

**Goal**: Add ngrok-java dependency and create a Gradle task to extract Android native libraries into `jniLibs/`.

**Acceptance Criteria**:
- [x] `ngrok-java` dependency added to version catalog and `build.gradle.kts`
- [x] Custom Gradle task extracts `ngrok-java-native` `.so` files for `linux-android-aarch_64` into `jniLibs/arm64-v8a/`
- [x] Build succeeds with ngrok-java on classpath

#### Action 4.1: Add ngrok-java to version catalog

**File**: `gradle/libs.versions.toml`

Add version:
```toml
ngrok-java = "1.1.1"
```

Add libraries:
```toml
ngrok-java = { group = "com.ngrok", name = "ngrok-java", version.ref = "ngrok-java" }
ngrok-java-native-android-arm64 = { group = "com.ngrok", name = "ngrok-java-native", version.ref = "ngrok-java" }
```

**Definition of Done**:
- [x] Version catalog entries compile

#### Action 4.2: Add ngrok-java dependencies to `build.gradle.kts`

**File**: `app/build.gradle.kts`

Add to dependencies:
```kotlin
// ngrok tunnel (in-process, JNI-based)
implementation(libs.ngrok.java)
```

Add a custom Gradle task to extract the native library for Android arm64:

```kotlin
// Extract ngrok-java native library for Android arm64
val extractNgrokNative by tasks.registering {
    description = "Extracts ngrok-java native .so for Android arm64 into jniLibs"

    val ngrokNativeConfig = configurations.create("ngrokNativeAndroidArm64") {
        isTransitive = false
    }
    dependencies {
        ngrokNativeConfig(
            libs.ngrok.java.native.android.arm64.get().toString() + ":linux-android-aarch_64"
        )
    }

    doLast {
        val targetDir = file("src/main/jniLibs/arm64-v8a")
        targetDir.mkdirs()
        ngrokNativeConfig.files.forEach { jar ->
            zipTree(jar).matching {
                include("**/*.so")
            }.forEach { soFile ->
                soFile.copyTo(File(targetDir, soFile.name), overwrite = true)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(extractNgrokNative)
}
```

**Definition of Done**:
- [x] `./gradlew preBuild` extracts the ngrok `.so` into `jniLibs/arm64-v8a/`
- [x] Build succeeds

#### Action 4.3: Add packaging exclusion for ngrok native metadata

**File**: `app/build.gradle.kts`

In the `packaging` block, add exclusions for ngrok JNI metadata that may conflict:

```diff
 packaging {
     resources {
         excludes += "/META-INF/{AL2.0,LGPL2.1}"
         excludes += "/META-INF/INDEX.LIST"
         excludes += "/META-INF/io.netty.*"
         excludes += "/META-INF/LICENSE.md"
         excludes += "/META-INF/LICENSE-notice.md"
     }
+    jniLibs {
+        useLegacyPackaging = true
+    }
 }
```

Note: `useLegacyPackaging = true` ensures `.so` files are stored uncompressed in the APK (required for JNI loading on some devices).

**Definition of Done**:
- [x] Build succeeds with no packaging conflicts

---

### Task 5: `CloudflareTunnelProvider` Implementation

**Goal**: Implement the Cloudflare Quick Tunnel provider that runs `cloudflared` as a child process.

**Acceptance Criteria**:
- [x] `CloudflareTunnelProvider` implements `TunnelProvider`
- [x] `start(localPort, config)` signature matches `TunnelProvider` interface (config is available but Cloudflare does not use it)
- [x] Starts `cloudflared tunnel --url http://localhost:<port> --output json` as a child process
- [x] Parses the public `https://*.trycloudflare.com` URL from stderr (JSON output)
- [x] Updates status flow: `Disconnected` → `Connecting` → `Connected(url)` or `Error(msg)`
- [x] Stops the process gracefully on `stop()` (SIGTERM via `process.destroy()`)
- [x] Handles process death (unexpected termination) by updating status to `Error`
- [x] Handles case where cloudflared binary is not found
- [x] Unit tests pass

#### Action 5.1: Create `CloudflaredBinaryResolver` interface and implementation

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/CloudflaredBinaryResolver.kt` (NEW)

Create an injectable interface to resolve the cloudflared binary path. This decouples `CloudflareTunnelProvider` from Android's `Context` and enables testability (the integration test on JVM can provide a host-native binary path):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * Resolves the filesystem path to the cloudflared binary.
 *
 * Production implementation reads from [android.content.Context.getApplicationInfo]
 * nativeLibraryDir. Test implementations can point to a host-native binary.
 */
interface CloudflaredBinaryResolver {
    /** Returns the absolute path to the cloudflared binary, or null if not found. */
    fun resolve(): String?
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/AndroidCloudflareBinaryResolver.kt` (NEW)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the cloudflared binary from the APK's native library directory.
 *
 * The binary is named `libcloudflared.so` and placed in `jniLibs/<abi>/`
 * so that Android extracts it with execute permissions.
 */
class AndroidCloudflareBinaryResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : CloudflaredBinaryResolver {
    override fun resolve(): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        return if (binary.exists() && binary.canExecute()) binary.absolutePath else null
    }
}
```

Add Hilt binding in `AppModule.kt` (`ServiceModule`):
```kotlin
@Binds
abstract fun bindCloudflareBinaryResolver(
    impl: AndroidCloudflareBinaryResolver,
): CloudflaredBinaryResolver
```

**Definition of Done**:
- [x] Interface and implementation compile
- [x] Hilt binding wired

#### Action 5.2: Create `CloudflareTunnelProvider`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/CloudflareTunnelProvider.kt` (NEW)

Implementation details:
- Inject `CloudflaredBinaryResolver` via Hilt to locate the binary (NOT `Context` directly)
- Binary path: resolved via `binaryResolver.resolve()` — returns `null` if binary not found
- Check `binaryResolver.resolve()`: if null, set `Error("cloudflared binary not found")` and return
- The `config` parameter is received but unused by Cloudflare (no per-provider settings needed). Use `@Suppress("UNUSED_PARAMETER")` on the `start()` method to suppress the compiler warning.
- Process command: `[binaryPath, "tunnel", "--url", "http://localhost:$localPort", "--output", "json"]`
- Read stderr in a coroutine (cloudflared writes ALL output to stderr)
- Parse URL with regex: `https://[-a-zA-Z0-9]+\\.trycloudflare\\.com`
- Use `--output json` flag for structured log output (each line is a JSON object with `level`, `time`, `message` fields)
- Monitor process in a separate coroutine (detect unexpected exit via `process.waitFor()`)
- On `stop()`: call `process.destroy()` (sends SIGTERM on Linux/Android), wait up to 5 seconds, then `process.destroyForcibly()`
- Use `Mutex` to prevent concurrent start/stop
- Use `CoroutineScope(SupervisorJob() + Dispatchers.IO)` for process monitoring

Key constants:
- `TUNNEL_URL_REGEX = Regex("https://[-a-zA-Z0-9]+\\.trycloudflare\\.com")`
- `TUNNEL_READY_TIMEOUT_MS = 30_000L` (30 seconds to establish tunnel)
- `SHUTDOWN_TIMEOUT_MS = 5_000L`

**Definition of Done**:
- [x] Compiles without warnings
- [x] Follows existing provider patterns (ScreenCaptureProviderImpl)

#### Action 5.3: Create `CloudflareTunnelProviderTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/CloudflareTunnelProviderTest.kt` (NEW)

Tests:
- `start with valid binary starts process and parses URL`
- `start with missing binary sets error status`
- `stop destroys process and sets disconnected status`
- `stop when not running is no-op`
- `unexpected process exit sets error status`
- `start when already running throws IllegalStateException`
- `URL regex correctly matches trycloudflare URLs`
- `URL regex does not match non-trycloudflare URLs`

Use MockK to mock `CloudflaredBinaryResolver` (return a known path or null).
Use a mock process or process builder to simulate cloudflared output.

**Definition of Done**:
- [x] All tests pass: `./gradlew :app:testDebugUnitTest --tests "*.CloudflareTunnelProviderTest"`

---

### Task 6: `NgrokTunnelProvider` Implementation

**Goal**: Implement the ngrok tunnel provider using the ngrok-java library.

**Acceptance Criteria**:
- [x] `NgrokTunnelProvider` implements `TunnelProvider`
- [x] Uses `Session.forwardHttp()` pattern to forward traffic to localhost
- [x] Accepts authtoken and optional domain from `config` parameter (not from `SettingsRepository` directly)
- [x] Updates status flow appropriately
- [x] Closes session and forwarder on `stop()`
- [x] Returns graceful error on unsupported ABI (x86_64)
- [x] Unit tests pass

#### Action 6.1: Create `NgrokTunnelProvider`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/NgrokTunnelProvider.kt` (NEW)

**IMPORTANT — API verification required**: The ngrok-java API method signatures below are based on the ngrok-java 1.1.1 documentation available at time of planning. Before implementing, verify the actual API surface by checking the ngrok-java javadoc or source code at `https://github.com/ngrok/ngrok-java`. The exact method names (`Session.withAuthtoken`, `forwardHttp`, `httpEndpoint().domain()`) may differ in the actual library. Adjust the implementation accordingly.

Implementation details:
- No `SettingsRepository` injection — config is received via `start(localPort, config)` from `TunnelManager`
- Check `Build.SUPPORTED_ABIS` — if none contains `arm64-v8a`, set `Error("ngrok is not supported on this device architecture (x86_64). Use Cloudflare instead.")`
- Read `config.ngrokAuthtoken` and `config.ngrokDomain` from the `ServerConfig` passed to `start()`
- If authtoken is empty, set `Error("ngrok authtoken is required")` and return
- Create `Session` with authtoken: `Session.withAuthtoken(authtoken).connect()`
- Create forwarder: `session.forwardHttp(session.httpEndpoint().domain(domain), URL("http://localhost:$localPort"))`
  - If domain is empty, omit `.domain()` call (let ngrok assign automatically)
- Get URL: `forwarder.url`
- On `stop()`: `forwarder.close()`, `session.close()`
- Wrap all ngrok operations in try-catch for `IOException`
- Use `Mutex` to prevent concurrent start/stop

**Definition of Done**:
- [x] Compiles without warnings
- [x] Handles all error cases (invalid authtoken, network error, unsupported ABI)

#### Action 6.2: Create `NgrokTunnelProviderTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/NgrokTunnelProviderTest.kt` (NEW)

Tests:
- `start with valid authtoken connects and returns URL`
- `start with empty authtoken sets error status`
- `start on unsupported ABI sets error status with helpful message`
- `stop closes forwarder and session`
- `stop when not running is no-op`
- `start when already running throws IllegalStateException`
- `start with invalid authtoken sets error status` (mock IOException)
- `start with domain passes domain to builder`
- `start without domain omits domain from builder`

Mock `Session`, `Forwarder`, `HttpBuilder` using MockK.
Mock `Build.SUPPORTED_ABIS` for ABI checks.

**Definition of Done**:
- [x] All tests pass: `./gradlew :app:testDebugUnitTest --tests "*.NgrokTunnelProviderTest"`

---

### Task 7: `TunnelManager` Implementation

**Goal**: Create a singleton manager that orchestrates tunnel provider lifecycle, selected by settings.

**Acceptance Criteria**:
- [x] `TunnelManager` is a `@Singleton` Hilt-injected class
- [x] Exposes `tunnelStatus: StateFlow<TunnelStatus>`
- [x] `start(localPort)` reads settings, creates appropriate provider, starts tunnel
- [x] `stop()` stops the active provider
- [x] Provider is created on-demand based on current settings (not cached across restarts)
- [x] Thread-safe (Mutex for start/stop)
- [x] Unit tests pass

#### Action 7.1: Create `TunnelManager`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/TunnelManager.kt` (NEW)

Implementation details:
- Inject: `SettingsRepository`, `javax.inject.Provider<CloudflareTunnelProvider>`, `javax.inject.Provider<NgrokTunnelProvider>`
- `private var activeProvider: TunnelProvider? = null`
- `private val _tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)`
- `val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()`
- **Coroutine scope**: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — owns status relay jobs
- `private var statusRelayJob: Job? = null` — tracks the active status collection job
- `start(localPort)`:
  1. Read `settingsRepository.serverConfig.first()`
  2. If `!config.tunnelEnabled`, return (no-op)
  3. Create a fresh provider instance via `javax.inject.Provider<T>.get()` based on `config.tunnelProvider`
  4. Start status relay: `statusRelayJob = scope.launch { provider.status.collect { _tunnelStatus.value = it } }`
  5. Call `provider.start(localPort, config)`
  6. Set `activeProvider = provider`
- `stop()`:
  1. `statusRelayJob?.cancel()` — stop relaying before stopping the provider
  2. `statusRelayJob = null`
  3. Call `activeProvider?.stop()`
  4. Set `activeProvider = null`
  5. Set `_tunnelStatus.value = TunnelStatus.Disconnected`
- Use `Mutex` for thread safety
- Note: `scope` is never cancelled because TunnelManager is `@Singleton` and lives for the entire app lifecycle. Individual relay jobs are cancelled in `stop()`.

**Definition of Done**:
- [x] Compiles without warnings
- [x] Follows singleton patterns used elsewhere in the project

#### Action 7.2: Create `TunnelManagerTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/TunnelManagerTest.kt` (NEW)

Tests:
- `start with tunnel enabled and Cloudflare provider starts Cloudflare tunnel`
- `start with tunnel enabled and ngrok provider starts ngrok tunnel`
- `start with tunnel disabled is no-op`
- `stop stops active provider and resets status`
- `stop when no active tunnel is no-op`
- `start relays provider status to tunnelStatus`
- `status defaults to Disconnected`

Mock `SettingsRepository`, `javax.inject.Provider<CloudflareTunnelProvider>` factory, and `javax.inject.Provider<NgrokTunnelProvider>` factory. Each factory mock returns a fresh mock provider on `.get()`. This mirrors the actual DI setup where `TunnelManager` receives factories, not singleton providers.

**Definition of Done**:
- [x] All tests pass: `./gradlew :app:testDebugUnitTest --tests "*.TunnelManagerTest"`

---

### Task 8: Hilt Dependency Injection

**Goal**: Wire `TunnelManager` and providers into the Hilt DI graph.

**Acceptance Criteria**:
- [x] `CloudflareTunnelProvider` and `NgrokTunnelProvider` are injectable
- [x] `TunnelManager` is `@Singleton` and injectable
- [x] No circular dependencies

#### Action 8.1: Add tunnel bindings to `AppModule.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

Add `TunnelManager` as a provided singleton (since it's a concrete class with `@Inject constructor`, Hilt can construct it automatically — just ensure the class is annotated with `@Singleton @Inject`).

Since `CloudflareTunnelProvider` and `NgrokTunnelProvider` are concrete classes with `@Inject constructor`, they can be injected directly without explicit module bindings. BUT they should not be singletons — `TunnelManager` creates/manages their lifecycle.

Actually, since TunnelManager receives both providers via constructor injection, and providers are NOT singletons (they have per-tunnel-session state), we should NOT use `@Singleton` on the providers. Instead, use `@Inject constructor` on each provider class, and Hilt will create new instances each time they're injected.

Wait — `TunnelManager` IS a singleton, and it receives providers via constructor injection. If providers are not singletons, `TunnelManager` will get exactly one instance at construction time. But we want fresh providers per tunnel session.

Better approach: Inject `Provider<CloudflareTunnelProvider>` and `Provider<NgrokTunnelProvider>` (javax.inject.Provider) into `TunnelManager`. This gives `TunnelManager` a factory to create new provider instances on each `start()`.

Update `TunnelManager` constructor:
```kotlin
@Singleton
class TunnelManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cloudflareTunnelProviderFactory: javax.inject.Provider<CloudflareTunnelProvider>,
    private val ngrokTunnelProviderFactory: javax.inject.Provider<NgrokTunnelProvider>,
)
```

No explicit module binding needed — Hilt can provide `javax.inject.Provider<T>` automatically for any injectable class.

**Definition of Done**:
- [x] Hilt graph compiles without errors
- [x] `TunnelManager` is injectable in `McpServerService`

---

### Task 9: `McpServerService` Integration

> **Plan 12 Note**: The diffs below are written against the **pre-plan-12** `McpServerService`. After Plan 12 is implemented, `McpServerService` will have these differences: (1) `protocolHandler` and `toolRegistry` injections removed, (2) `registerAllTools()` takes a `server: Server` parameter instead of zero-arg, (3) `McpServer` constructor takes `mcpSdkServer` instead of `protocolHandler`. During implementation, anchor the diffs in the **post-plan-12** code. The logical insertion points are identical: tunnel starts AFTER `mcpServer?.start()` + `updateStatus(Running)`, tunnel stops BEFORE `mcpServer?.stop()` in `onDestroy()`.

**Goal**: Start and stop the tunnel with the MCP server lifecycle. Expose server log events from the service to the UI via a companion-level `SharedFlow` (same architectural pattern as `serverStatus`).

**Acceptance Criteria**:
- [x] `McpServerService` injects `TunnelManager`
- [x] `McpServerService` has a companion-level `SharedFlow<ServerLogEntry>` for emitting log events to the UI
- [x] `startServer()` calls `tunnelManager.start(config.port)` after Ktor server starts
- [x] `onDestroy()` calls `tunnelManager.stop()` before stopping Ktor server
- [x] Tunnel URL is logged to **logcat** when connected (via `Log.i`)
- [x] Tunnel URL is logged to **UI server logs** when connected (via companion `serverLogEvents` SharedFlow)
- [x] `ServerLogEntry` generalized to support tunnel events (type enum: TOOL_CALL, TUNNEL, SERVER)
- [x] `ServerLogsSection.kt` UI composable updated to render different entry types correctly
- [x] No existing **production** `ServerLogEntry` call sites need updating (there are currently none in production code); **test** call sites in `MainViewModelTest.kt` are updated in Task 10 Action 10.2

#### Action 9.1: Add `TunnelManager` injection and companion-level `serverLogEvents` SharedFlow to `McpServerService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

Add injection and companion-level SharedFlow for log events. This follows the exact same architectural pattern as the existing `serverStatus` companion-level `StateFlow` — the service emits events via a companion flow, and `MainViewModel` collects from it. Services must NOT depend on ViewModels.

```diff
+import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
+import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
+import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
+import kotlinx.coroutines.flow.MutableSharedFlow
+import kotlinx.coroutines.flow.SharedFlow
+import kotlinx.coroutines.flow.asSharedFlow

 @AndroidEntryPoint
 class McpServerService : Service() {
+    @Inject lateinit var tunnelManager: TunnelManager
```

Add to the companion object (alongside the existing `_serverStatus`/`serverStatus` pattern):

```diff
     companion object {
         // ... existing constants and serverStatus flow ...

+        /**
+         * Shared server log events flow. Collected by MainViewModel to display
+         * log entries in the UI. Uses a SharedFlow (not StateFlow) because each
+         * event is a discrete emission, not a current-state snapshot.
+         *
+         * extraBufferCapacity = 64 prevents dropped events during brief UI
+         * collection pauses (e.g., during configuration changes).
+         */
+        private val _serverLogEvents = MutableSharedFlow<ServerLogEntry>(extraBufferCapacity = 64)
+        val serverLogEvents: SharedFlow<ServerLogEntry> = _serverLogEvents.asSharedFlow()
     }
```

Add a private helper method in the class body (NOT in the companion, so it can be called from instance methods):

```kotlin
private fun emitLogEntry(entry: ServerLogEntry) {
    _serverLogEvents.tryEmit(entry)
}
```

**Definition of Done**:
- [x] Compiles without warnings
- [x] `serverLogEvents` accessible from `McpServerService.serverLogEvents` (companion-level)
- [x] `emitLogEntry()` callable from instance methods (`startServer()`, tunnel status observer)

#### Action 9.2: Generalize `ServerLogEntry` to support tunnel events

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerLogEntry.kt`

The current `ServerLogEntry` has `toolName`, `params`, `durationMs` fields which are MCP-tool-specific and don't fit tunnel events (e.g., "Tunnel connected: https://abc.trycloudflare.com"). Generalize the data class to support both MCP tool calls and system events:

```diff
 data class ServerLogEntry(
     val timestamp: Long,
-    val toolName: String,
-    val params: String,
-    val durationMs: Long,
+    val type: Type,
+    val message: String,
+    val toolName: String? = null,
+    val params: String? = null,
+    val durationMs: Long? = null,
 ) {
+    /** Categorizes log entries for display. */
+    enum class Type {
+        /** An MCP tool call (has toolName, params, durationMs). */
+        TOOL_CALL,
+
+        /** A tunnel lifecycle event (connected, disconnected, error). */
+        TUNNEL,
+
+        /** A general server event (started, stopped, etc.). */
+        SERVER,
+    }
+
     companion object {
         const val MAX_PARAMS_LENGTH = 100
     }
 }
```

**NOTE**: As of this writing, there are **zero existing call sites in production code** that create `ServerLogEntry` instances. The `addServerLogEntry()` method exists on `MainViewModel` but is never called from production code. However, **test code** (`MainViewModelTest.kt` lines 274-310) DOES construct `ServerLogEntry` instances using the old 4-parameter constructor — these tests MUST be updated in Action 10.2. Additionally, if any new production call sites are added before this plan is implemented, they must include `type = ServerLogEntry.Type.TOOL_CALL` and `message = toolName`.

**Definition of Done**:
- [x] `ServerLogEntry` generalized with `Type` enum
- [x] Existing unit tests updated for new structure (if any reference the old constructor signature)

#### Action 9.3: Update `ServerLogsSection.kt` to handle different entry types

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ServerLogsSection.kt`

The current `ServerLogEntryRow` composable directly accesses `entry.toolName`, `entry.params`, and `entry.durationMs` as non-nullable fields. With the generalized `ServerLogEntry`, these are now nullable (`String?`, `Long?`). The composable must branch on `entry.type` to render each entry type correctly:

Replace the existing `ServerLogEntryRow` composable with:

```diff
 @Composable
 private fun ServerLogEntryRow(entry: ServerLogEntry) {
     val timeFormat = SimpleDateFormat(TIME_FORMAT_PATTERN, Locale.getDefault())
     val timeString = timeFormat.format(Date(entry.timestamp))

-    Row(
-        modifier =
-            Modifier
-                .fillMaxWidth()
-                .padding(vertical = 6.dp),
-        verticalAlignment = Alignment.CenterVertically,
-    ) {
-        Text(
-            text = timeString,
-            style = MaterialTheme.typography.labelSmall,
-            color = MaterialTheme.colorScheme.onSurfaceVariant,
-        )
-        Spacer(modifier = Modifier.width(8.dp))
-        Text(
-            text = entry.toolName,
-            style = MaterialTheme.typography.bodyMedium,
-            modifier = Modifier.weight(1f),
-            maxLines = 1,
-            overflow = TextOverflow.Ellipsis,
-        )
-        Spacer(modifier = Modifier.width(8.dp))
-        Text(
-            text = "${entry.durationMs}ms",
-            style = MaterialTheme.typography.labelSmall,
-            color = MaterialTheme.colorScheme.onSurfaceVariant,
-        )
-    }
-    if (entry.params.isNotEmpty()) {
-        Text(
-            text = entry.params,
-            style = MaterialTheme.typography.bodySmall,
-            color = MaterialTheme.colorScheme.onSurfaceVariant,
-            maxLines = 1,
-            overflow = TextOverflow.Ellipsis,
-            modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
-        )
-    }
+    when (entry.type) {
+        ServerLogEntry.Type.TOOL_CALL -> {
+            Row(
+                modifier =
+                    Modifier
+                        .fillMaxWidth()
+                        .padding(vertical = 6.dp),
+                verticalAlignment = Alignment.CenterVertically,
+            ) {
+                Text(
+                    text = timeString,
+                    style = MaterialTheme.typography.labelSmall,
+                    color = MaterialTheme.colorScheme.onSurfaceVariant,
+                )
+                Spacer(modifier = Modifier.width(8.dp))
+                Text(
+                    text = entry.toolName ?: "",
+                    style = MaterialTheme.typography.bodyMedium,
+                    modifier = Modifier.weight(1f),
+                    maxLines = 1,
+                    overflow = TextOverflow.Ellipsis,
+                )
+                Spacer(modifier = Modifier.width(8.dp))
+                Text(
+                    text = "${entry.durationMs ?: 0}ms",
+                    style = MaterialTheme.typography.labelSmall,
+                    color = MaterialTheme.colorScheme.onSurfaceVariant,
+                )
+            }
+            if (!entry.params.isNullOrEmpty()) {
+                Text(
+                    text = entry.params,
+                    style = MaterialTheme.typography.bodySmall,
+                    color = MaterialTheme.colorScheme.onSurfaceVariant,
+                    maxLines = 1,
+                    overflow = TextOverflow.Ellipsis,
+                    modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
+                )
+            }
+        }
+        ServerLogEntry.Type.TUNNEL,
+        ServerLogEntry.Type.SERVER -> {
+            Row(
+                modifier =
+                    Modifier
+                        .fillMaxWidth()
+                        .padding(vertical = 6.dp),
+                verticalAlignment = Alignment.CenterVertically,
+            ) {
+                Text(
+                    text = timeString,
+                    style = MaterialTheme.typography.labelSmall,
+                    color = MaterialTheme.colorScheme.onSurfaceVariant,
+                )
+                Spacer(modifier = Modifier.width(8.dp))
+                Text(
+                    text = entry.message,
+                    style = MaterialTheme.typography.bodyMedium,
+                    modifier = Modifier.weight(1f),
+                    maxLines = 2,
+                    overflow = TextOverflow.Ellipsis,
+                )
+            }
+        }
+    }
 }
```

Also update the `@Preview` composable to use the new constructor:

```diff
-@Preview(showBackground = true)
-@Composable
-private fun ServerLogsSectionEmptyPreview() {
-    AndroidRemoteControlMcpTheme {
-        ServerLogsSection(logs = emptyList())
-    }
-}
+@Preview(showBackground = true)
+@Composable
+private fun ServerLogsSectionPreview() {
+    AndroidRemoteControlMcpTheme {
+        ServerLogsSection(
+            logs = listOf(
+                ServerLogEntry(
+                    timestamp = System.currentTimeMillis(),
+                    type = ServerLogEntry.Type.TOOL_CALL,
+                    message = "tap",
+                    toolName = "tap",
+                    params = """{"x": 500, "y": 800}""",
+                    durationMs = 42,
+                ),
+                ServerLogEntry(
+                    timestamp = System.currentTimeMillis(),
+                    type = ServerLogEntry.Type.TUNNEL,
+                    message = "Tunnel connected: https://random-words.trycloudflare.com",
+                ),
+            ),
+        )
+    }
+}
```

**Definition of Done**:
- [x] `ServerLogEntryRow` handles all three entry types without NPE
- [x] `TOOL_CALL` entries display the same as before (toolName, params, durationMs)
- [x] `TUNNEL`/`SERVER` entries display timestamp + message only
- [x] Preview compiles and renders both entry types

#### Action 9.4: Start tunnel after server starts and log tunnel URL

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

In `startServer()`, after `mcpServer?.start()` and the `updateStatus(ServerStatus.Running(...))` call, add:

```diff
             mcpServer?.start()

             updateStatus(
                 ServerStatus.Running(
                     port = config.port,
                     bindingAddress = config.bindingAddress.address,
                 ),
             )

+            // Start tunnel if remote access is enabled
+            try {
+                tunnelManager.start(config.port)
+            } catch (e: Exception) {
+                Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
+            }
+
             Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
```

Additionally, observe `tunnelManager.tunnelStatus` to log tunnel events to both **logcat** and the **UI server logs** via the companion-level `emitLogEntry()` helper (see Action 9.1). Add a coroutine in `startServer()` (after the tunnel start) to collect tunnel status changes:

```kotlin
// Observe tunnel status for logging
coroutineScope.launch {
    tunnelManager.tunnelStatus.collect { status ->
        when (status) {
            is TunnelStatus.Connected -> {
                Log.i(TAG, "Tunnel connected: ${status.url} (provider: ${status.providerType})")
                emitLogEntry(
                    ServerLogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = ServerLogEntry.Type.TUNNEL,
                        message = "Tunnel connected: ${status.url}",
                    ),
                )
            }
            is TunnelStatus.Error -> {
                Log.w(TAG, "Tunnel error: ${status.message}")
                emitLogEntry(
                    ServerLogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = ServerLogEntry.Type.TUNNEL,
                        message = "Tunnel error: ${status.message}",
                    ),
                )
            }
            is TunnelStatus.Connecting -> {
                Log.i(TAG, "Tunnel connecting...")
            }
            is TunnelStatus.Disconnected -> {
                // No-op for initial state; logged at stop time
            }
        }
    }
}
```

Note: Tunnel failure does NOT prevent the MCP server from running. The server continues locally even if the tunnel fails.

**Definition of Done**:
- [x] Compiles without warnings
- [x] Tunnel starts after Ktor server
- [x] Tunnel status logged to logcat and emitted to companion SharedFlow

#### Action 9.5: Stop tunnel in `onDestroy()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

In `onDestroy()`, stop the tunnel BEFORE stopping the Ktor server. **CRITICAL**: `onDestroy()` runs on the main thread — blocking it for more than 5 seconds causes an ANR. Use `runBlocking` with a hard `withTimeout` wrapper to ensure the tunnel stop is bounded to 3 seconds max:

```diff
     override fun onDestroy() {
         Log.i(TAG, "McpServerService destroying")
         updateStatus(ServerStatus.Stopping)

+        // Stop tunnel first (with ANR-safe timeout)
+        // onDestroy() is on the main thread — we must not block longer than ~4s.
+        // runBlocking is acceptable here because withTimeout bounds it to 3s max.
+        @Suppress("TooGenericExceptionCaught")
+        try {
+            kotlinx.coroutines.runBlocking {
+                kotlinx.coroutines.withTimeout(TUNNEL_STOP_TIMEOUT_MS) {
+                    tunnelManager.stop()
+                }
+            }
+        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
+            Log.w(TAG, "Tunnel stop timed out after ${TUNNEL_STOP_TIMEOUT_MS}ms, proceeding with shutdown")
+        } catch (e: Exception) {
+            Log.e(TAG, "Error stopping tunnel", e)
+        }
+
         // Stop the Ktor server gracefully
```

Add constant in companion:
```kotlin
const val TUNNEL_STOP_TIMEOUT_MS = 3_000L
```

The `withTimeout` ensures:
- If `tunnelManager.stop()` completes within 3 seconds, normal shutdown
- If it exceeds 3 seconds, `TimeoutCancellationException` is thrown, the tunnel's underlying process is abandoned (the OS will clean it up when the process dies), and shutdown continues
- The total `runBlocking` time is bounded to 3 seconds max, well under the ANR threshold

Additionally, `TunnelManager.stop()` should ensure its own providers have hard-kill behavior:
- `CloudflareTunnelProvider.stop()`: calls `process.destroy()`, waits 2 seconds, then `process.destroyForcibly()`
- `NgrokTunnelProvider.stop()`: calls `forwarder.close()` + `session.close()` — these are in-process and should complete quickly

**Definition of Done**:
- [x] Compiles without warnings
- [x] Tunnel starts after server, stops before server
- [x] `onDestroy()` cannot block main thread longer than 3 seconds due to tunnel stop
- [x] No ANR risk from tunnel shutdown

---

### Task 10: `MainViewModel` Changes

**Goal**: Expose tunnel status and settings to the UI.

**Acceptance Criteria**:
- [x] `MainViewModel` exposes `tunnelStatus: StateFlow<TunnelStatus>`
- [x] `MainViewModel` collects `McpServerService.serverLogEvents` in `init` and feeds entries into `addServerLogEntry()`
- [x] `MainViewModel` has methods for updating tunnel settings
- [x] `MainViewModel` has fields for ngrok authtoken input and error validation
- [x] `MainViewModelTest` updated
- [x] Targeted test passes: `./gradlew :app:testDebugUnitTest --tests "*.MainViewModelTest"`

#### Action 10.1: Add tunnel state to `MainViewModel`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

Add imports for `TunnelManager`, `TunnelStatus`, `TunnelProviderType`.

Add injection of `TunnelManager`:
```diff
 @HiltViewModel
 class MainViewModel @Inject constructor(
     private val settingsRepository: SettingsRepository,
+    private val tunnelManager: TunnelManager,
     @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
 ) : ViewModel() {
```

Add state fields:
```kotlin
private val _tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

private val _ngrokAuthtokenInput = MutableStateFlow("")
val ngrokAuthtokenInput: StateFlow<String> = _ngrokAuthtokenInput.asStateFlow()

private val _ngrokDomainInput = MutableStateFlow("")
val ngrokDomainInput: StateFlow<String> = _ngrokDomainInput.asStateFlow()
```

Add tunnel status collection in `init`:
```kotlin
viewModelScope.launch {
    tunnelManager.tunnelStatus.collect { status ->
        _tunnelStatus.value = status
    }
}
```

Add server log events collection in `init` (collects from the companion-level `SharedFlow` added in Action 9.1 — this is the same pattern as the existing `McpServerService.serverStatus` collection):
```kotlin
// Collect server log events emitted by McpServerService
viewModelScope.launch {
    McpServerService.serverLogEvents.collect { entry ->
        addServerLogEntry(entry)
    }
}
```

Update the existing serverConfig collection to also set ngrok input fields:
```diff
 settingsRepository.serverConfig.collect { config ->
     _serverConfig.value = config
     _portInput.value = config.port.toString()
     _hostnameInput.value = config.certificateHostname
+    _ngrokAuthtokenInput.value = config.ngrokAuthtoken
+    _ngrokDomainInput.value = config.ngrokDomain
 }
```

Add methods:
```kotlin
fun updateTunnelEnabled(enabled: Boolean) {
    viewModelScope.launch(ioDispatcher) {
        settingsRepository.updateTunnelEnabled(enabled)
    }
}

fun updateTunnelProvider(provider: TunnelProviderType) {
    viewModelScope.launch(ioDispatcher) {
        settingsRepository.updateTunnelProvider(provider)
    }
}

fun updateNgrokAuthtoken(authtoken: String) {
    _ngrokAuthtokenInput.value = authtoken
    viewModelScope.launch(ioDispatcher) {
        settingsRepository.updateNgrokAuthtoken(authtoken)
    }
}

fun updateNgrokDomain(domain: String) {
    _ngrokDomainInput.value = domain
    viewModelScope.launch(ioDispatcher) {
        settingsRepository.updateNgrokDomain(domain)
    }
}

fun shareText(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, null)
    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(shareIntent)
}
```

**Definition of Done**:
- [x] Compiles without warnings
- [x] `shareText` triggers Android share sheet

#### Action 10.2: Update `MainViewModelTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`

Update setUp to mock `TunnelManager`:
- Add `private lateinit var tunnelManager: TunnelManager` field
- Add `tunnelStatusFlow = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)`
- Mock `tunnelManager.tunnelStatus` to return `tunnelStatusFlow`
- Update `MainViewModel` constructor call to include `tunnelManager`

**IMPORTANT — update existing `addServerLogEntry` tests**: The tests at lines 274-310 (`addServerLogEntry adds entry to logs` and `addServerLogEntry trims list to max 100 entries`) use the OLD 4-parameter `ServerLogEntry` constructor (`timestamp`, `toolName`, `params`, `durationMs`). These MUST be updated to use the new constructor with `type` and `message` fields:
```kotlin
// Old (will not compile after Action 9.2):
ServerLogEntry(timestamp = 1000L, toolName = "screen_tap", params = "x=100, y=200", durationMs = 42L)

// New:
ServerLogEntry(
    timestamp = 1000L,
    type = ServerLogEntry.Type.TOOL_CALL,
    message = "screen_tap",
    toolName = "screen_tap",
    params = "x=100, y=200",
    durationMs = 42L,
)
```
Also update the assertion at line 308 that accesses `entry.toolName` — the field is now nullable (`String?`), so use appropriate null-safe access or update the assertion.

Add new tests:
- `updateTunnelEnabled calls repository`
- `updateTunnelProvider calls repository`
- `updateNgrokAuthtoken calls repository and updates input state`
- `updateNgrokDomain calls repository and updates input state`
- `tunnelStatus reflects TunnelManager status`
- `serverConfig collection sets ngrok input fields`
- `shareText starts ACTION_SEND intent with correct text`

**Definition of Done**:
- [x] All tests pass: `./gradlew :app:testDebugUnitTest --tests "*.MainViewModelTest"`

---

### Task 11: UI Changes

**Goal**: Add Remote Access section to the UI with tunnel provider selection, configuration, and status display.

**Acceptance Criteria**:
- [x] "Remote Access" section visible in HomeScreen between ConfigurationSection and PermissionsSection
- [x] Toggle to enable/disable remote access
- [x] Provider selector uses RadioButtons with description tags (same style as `CertificateSourceSelector`), NOT SegmentedButtons
- [x] ngrok-specific fields visible when ngrok is selected: authtoken (password field), domain (optional text field)
- [x] Tunnel status indicator (connecting/connected/error)
- [x] Public URL displayed when connected (copy/share handled by ConnectionInfoCard, NOT in this section)
- [x] All settings disabled when server is running (same pattern as other settings)
- [x] String resources added for all new UI text
- [x] Compose preview updated

#### Action 11.1: Add string resources

**File**: `app/src/main/res/values/strings.xml`

Add new strings:
```xml
<!-- Remote Access / Tunnel -->
<string name="remote_access_title">Remote Access</string>
<string name="remote_access_enabled_label">Enable Remote Access</string>
<string name="remote_access_provider_label">Tunnel Provider</string>
<string name="remote_access_provider_cloudflare">Cloudflare</string>
<string name="remote_access_provider_cloudflare_desc">No account required</string>
<string name="remote_access_provider_ngrok">ngrok</string>
<string name="remote_access_provider_ngrok_desc">Account required</string>
<string name="remote_access_ngrok_authtoken_label">ngrok Authtoken</string>
<string name="remote_access_ngrok_domain_label">Domain (optional)</string>
<string name="remote_access_ngrok_domain_hint">Leave empty for auto-assigned</string>
<string name="remote_access_status_disconnected">Disconnected</string>
<string name="remote_access_status_connecting">Connecting…</string>
<string name="remote_access_status_connected">Connected</string>
<string name="remote_access_status_error">Error: %1$s</string>
<string name="remote_access_public_url_label">Public URL</string>
<string name="remote_access_ngrok_unsupported">ngrok is not available on this device (requires ARM64)</string>

<!-- Connection Info (additions for tunnel) -->
<string name="connection_info_share">Share</string>
```

**Definition of Done**:
- [x] All strings added, build succeeds

#### Action 11.2: Create `RemoteAccessSection` composable

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/RemoteAccessSection.kt` (NEW)

Create a new composable following the exact same patterns as `ConfigurationSection`:
- `ElevatedCard` container
- Title: "Remote Access"
- Toggle: "Enable Remote Access" switch (disabled when server running)
- `AnimatedVisibility(visible = tunnelEnabled)` for the provider/config section:
  - Provider selector: **RadioButtons with description tags** (same pattern as `CertificateSourceSelector` in `ConfigurationSection.kt`). Each option shows:
    - Radio button + provider name + small description tag
    - Cloudflare: "Cloudflare" — "No account required"
    - ngrok: "ngrok" — "Account required"
  - `AnimatedVisibility(visible = provider == NGROK)`:
    - Authtoken: `OutlinedTextField` with `PasswordVisualTransformation` and show/hide toggle (same pattern as bearer token)
    - Domain: `OutlinedTextField` with hint text
  - Tunnel status display:
    - `Disconnected`: grey text "Disconnected"
    - `Connecting`: amber text "Connecting..." (or with a circular progress indicator)
    - `Connected`: green text "Connected" + URL text (no copy button here — copy/share is in ConnectionInfoCard)
    - `Error`: red text with error message

Note: Copy and Share functionality is NOT in this section. The public URL is displayed in `ConnectionInfoCard` alongside the local URL and token, with unified Copy All and Share buttons. See Action 11.4.

Parameters (following existing patterns — all state hoisted):
```kotlin
@Composable
fun RemoteAccessSection(
    tunnelEnabled: Boolean,
    tunnelProvider: TunnelProviderType,
    ngrokAuthtoken: String,
    ngrokDomain: String,
    tunnelStatus: TunnelStatus,
    isServerRunning: Boolean,
    onTunnelEnabledChange: (Boolean) -> Unit,
    onTunnelProviderChange: (TunnelProviderType) -> Unit,
    onNgrokAuthtokenChange: (String) -> Unit,
    onNgrokDomainChange: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

Include `@Preview` composable at the bottom.

**Definition of Done**:
- [x] Composable compiles and renders in preview
- [x] Follows existing Material Design 3 patterns and spacing

#### Action 11.3: Add `RemoteAccessSection` to `HomeScreen`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`

Add imports for `RemoteAccessSection`, `TunnelStatus`, `TunnelProviderType`.

Collect new state flows:
```kotlin
val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
val ngrokAuthtokenInput by viewModel.ngrokAuthtokenInput.collectAsStateWithLifecycle()
val ngrokDomainInput by viewModel.ngrokDomainInput.collectAsStateWithLifecycle()
```

Add `RemoteAccessSection` between `ConfigurationSection` and `PermissionsSection`:
```kotlin
RemoteAccessSection(
    tunnelEnabled = serverConfig.tunnelEnabled,
    tunnelProvider = serverConfig.tunnelProvider,
    ngrokAuthtoken = ngrokAuthtokenInput,
    ngrokDomain = ngrokDomainInput,
    tunnelStatus = tunnelStatus,
    isServerRunning = isServerRunning,
    onTunnelEnabledChange = viewModel::updateTunnelEnabled,
    onTunnelProviderChange = viewModel::updateTunnelProvider,
    onNgrokAuthtokenChange = viewModel::updateNgrokAuthtoken,
    onNgrokDomainChange = viewModel::updateNgrokDomain,
)
```

**Definition of Done**:
- [x] HomeScreen compiles and renders with new section

#### Action 11.4: Update `ConnectionInfoCard` to show tunnel URL and add Share button

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConnectionInfoCard.kt`

Add two new parameters:
- `tunnelUrl: String? = null` — the public tunnel URL (null when no tunnel connected)
- `onShare: (String) -> Unit` — callback for the Share button

When `tunnelUrl` is not null, show an additional row:
```kotlin
tunnelUrl?.let { url ->
    ConnectionInfoRow(
        label = stringResource(R.string.remote_access_public_url_label),
        value = url,
    )
}
```

Update the connection string used by both Copy All and Share:
```diff
-val connectionString = "URL: $serverUrl\nToken: $bearerToken"
+val connectionString = buildString {
+    append("URL: $serverUrl\nToken: $bearerToken")
+    tunnelUrl?.let { append("\nPublic URL: $it") }
+}
```

Add a **Share** button next to the existing "Copy All" button. The Share button uses `Intent.ACTION_SEND`:
```kotlin
Row(
    modifier = Modifier.align(Alignment.End),
) {
    TextButton(onClick = { onCopyAll(connectionString) }) {
        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
        Text(
            text = stringResource(R.string.connection_info_copy_all),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    TextButton(onClick = { onShare(connectionString) }) {
        Icon(imageVector = Icons.Default.Share, contentDescription = null)
        Text(
            text = stringResource(R.string.connection_info_share),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
```

Update the `@Preview` composable to include the new parameters:
```kotlin
@Preview(showBackground = true)
@Composable
private fun ConnectionInfoCardPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectionInfoCard(
            bindingAddress = BindingAddress.LOCALHOST,
            ipAddress = "192.168.1.100",
            port = 8080,
            httpsEnabled = false,
            bearerToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            tunnelUrl = "https://random-words.trycloudflare.com",
            onCopyAll = {},
            onShare = {},
        )
    }
}
```

Update `HomeScreen.kt` to pass tunnel URL and share callback:
```diff
 ConnectionInfoCard(
     // ... existing params ...
+    tunnelUrl = (tunnelStatus as? TunnelStatus.Connected)?.url,
+    onShare = { text -> viewModel.shareText(context, text) },
 )
```

**Definition of Done**:
- [x] ConnectionInfoCard shows tunnel URL when connected
- [x] Share button sends connection info via `Intent.ACTION_SEND`
- [x] Copy All includes tunnel URL when present
- [x] Preview updated with new parameters and compiles

---

### Task 12: Unit Test Updates and Additions

**Goal**: Ensure all new code is unit tested.

**Acceptance Criteria**:
- [x] `TunnelStatus` tests (sealed class variants)
- [x] `TunnelProviderType` tests (enum values)
- [x] `ServerConfig` test updated for new defaults
- [x] All tests pass: `./gradlew :app:test`

#### Action 12.1: Create `TunnelStatusTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/TunnelStatusTest.kt` (NEW)

Tests for sealed class variants (same pattern as `ServerStatusTest`).

#### Action 12.2: Update `ServerConfigTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt`

Add tests verifying new defaults:
- `tunnelEnabled defaults to false`
- `tunnelProvider defaults to CLOUDFLARE`
- `ngrokAuthtoken defaults to empty string`
- `ngrokDomain defaults to empty string`

**Definition of Done**:
- [x] All unit tests pass: `./gradlew :app:test`

---

### Task 13: Real Cloudflare Tunnel Integration Test

**Goal**: Create an integration test that starts a real Cloudflare Quick Tunnel and verifies it works end-to-end.

**Acceptance Criteria**:
- [ ] Test starts a **simple standalone HTTP test server** on a random local port (NOT the MCP server — this is a tunnel-layer test, not an MCP protocol test)
- [ ] Test creates a real Cloudflare tunnel to the local test server
- [ ] Test verifies the tunnel URL is accessible from the test (HTTP GET to tunnel URL returns expected response from the test server)
- [ ] Test cleans up the tunnel after completion
- [ ] Test has a reasonable timeout (60 seconds for tunnel establishment)
- [ ] Test FAILS (not skips) if cloudflared binary is not available on the host
- [ ] CI pipeline updated to install cloudflared before running tests

> **Plan 12 Note**: Plan 12 removes the `/health` endpoint from the MCP server. This test uses a standalone simple HTTP test server (not the MCP server), so the `/health` removal does not affect it. The test verifies tunnel connectivity, not MCP protocol compliance.

#### Action 13.1: Create `CloudflareTunnelIntegrationTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/CloudflareTunnelIntegrationTest.kt` (NEW)

This test will:
1. Start a **simple standalone HTTP test server** on a random local port (using Ktor embedded server or a simple ServerSocket — responds with a known string like `"tunnel-test-ok"` on `GET /`)
2. Start a `CloudflareTunnelProvider` pointing to that local port
3. Wait for `TunnelStatus.Connected` with a URL
4. Make an HTTP GET request to the tunnel URL and verify the response matches the expected string from the test server
5. Stop the tunnel and verify status goes to `Disconnected`

Note: This test requires the `libcloudflared.so` binary to be available. On the JVM test host (Linux x86_64), we need the Linux x86_64 binary of cloudflared, NOT the Android one. We should either:
- Use a separately downloaded `cloudflared` binary for the test host
- Or make the test work differently on the JVM

Since JVM tests run on the host machine (not Android), the `AndroidCloudflareBinaryResolver` won't work. Instead, create a test-specific `HostCloudflareBinaryResolver` that locates the host-native `cloudflared` binary via PATH lookup:

```kotlin
class HostCloudflareBinaryResolver : CloudflaredBinaryResolver {
    override fun resolve(): String? {
        val result = ProcessBuilder("which", "cloudflared").start()
        val path = result.inputStream.bufferedReader().readText().trim()
        return if (result.waitFor() == 0 && path.isNotEmpty()) path else null
    }
}
```

The test must **FAIL** (not skip) if `cloudflared` is not found on the host — this is an essential test:
```kotlin
val binaryPath = HostCloudflareBinaryResolver().resolve()
    ?: fail("cloudflared binary not found on host. Install it: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/")
```

**Definition of Done**:
- [ ] Test passes when cloudflared is available on the host
- [ ] Test FAILS with a clear error message if cloudflared is not installed

#### Action 13.2: Update CI pipeline to install cloudflared

**File**: `.github/workflows/ci.yml`

Add a step to install cloudflared before the test jobs run. Cloudflared provides official Linux packages:

```yaml
- name: Install cloudflared
  run: |
    curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o /usr/local/bin/cloudflared
    chmod +x /usr/local/bin/cloudflared
    cloudflared --version
```

This step must be added BEFORE the test step(s) that run integration tests. The exact placement depends on the current CI structure (inspect the file at implementation time).

**Definition of Done**:
- [ ] CI pipeline installs cloudflared
- [ ] Integration test passes in CI
- [ ] `cloudflared --version` prints in CI logs for traceability

---

### Task 14: Lint, Build, and Full Test Run

**Goal**: Verify all quality gates pass.

**Acceptance Criteria**:
- [ ] `make lint` passes with no warnings/errors
- [ ] `make lint-fix` applied if needed
- [ ] `./gradlew build` succeeds without warnings
- [ ] `make test-unit` passes (all unit + integration tests)
- [ ] No TODOs, no dead code, no commented-out code

#### Action 14.1: Run lint

```bash
make lint
```

Fix any issues found.

#### Action 14.2: Run full build

```bash
./gradlew build
```

Fix any warnings.

#### Action 14.3: Run all tests

```bash
make test-unit
```

Fix any failures.

**Definition of Done**:
- [ ] All quality gates pass

---

### Task 15: Documentation Updates

**Goal**: Update project documentation to reflect the new tunnel feature.

**Acceptance Criteria**:
- [ ] `docs/PROJECT.md` updated with tunnel settings, providers, and architecture
- [ ] `docs/ARCHITECTURE.md` updated with tunnel component in the architecture diagram

#### Action 15.1: Update `docs/PROJECT.md`

Add tunnel settings to the settings section:
- `tunnelEnabled: Boolean` — default `false`
- `tunnelProvider: TunnelProviderType` — default `CLOUDFLARE`
- `ngrokAuthtoken: String` — default `""`
- `ngrokDomain: String` — default `""`

Add tunnel providers section describing Cloudflare and ngrok integration.

Update server logs descriptions — `PROJECT.md` currently describes the server logs as "recent MCP requests" in two places (line 60: "server logs viewer (recent MCP requests)" and line 371: "ServerLogsSection (scrollable recent MCP requests)"). After `ServerLogEntry` generalization to include `Type.TUNNEL` and `Type.SERVER` events, these descriptions must be updated to reflect that the logs now show server events including MCP tool calls and tunnel events (e.g., "recent server events" or "recent MCP requests and tunnel events").

#### Action 15.2: Update `docs/ARCHITECTURE.md`

Add tunnel layer to the architecture diagram showing:
- `TunnelManager` in `McpServerService`
- `CloudflareTunnelProvider` (process-based)
- `NgrokTunnelProvider` (in-process JNI)

**Definition of Done**:
- [ ] Documentation is accurate and complete

---

### Task 16: Final Double-Check

**Goal**: Review everything implemented from the ground up, verifying correctness and completeness.

**Acceptance Criteria**:
- [ ] Re-read every new file and verify it matches the plan
- [ ] Re-read every modified file and verify changes are correct and minimal
- [ ] Verify all new classes have proper KDoc comments
- [ ] Verify all new settings are persisted correctly (write + read + defaults)
- [ ] Verify `TunnelProvider` interface is clean and minimal (`start(localPort, config)` signature)
- [ ] Verify `CloudflaredBinaryResolver` interface and `AndroidCloudflareBinaryResolver` impl are correct
- [ ] Verify `CloudflareTunnelProvider` handles all edge cases (binary not found, process crash, timeout)
- [ ] Verify `NgrokTunnelProvider` handles all edge cases (invalid token, unsupported ABI, network error)
- [ ] Verify `NgrokTunnelProvider` does NOT inject `SettingsRepository` (receives config via `start()`)
- [ ] Verify `TunnelManager` correctly relays status from active provider (with its own coroutine scope and relay job)
- [ ] Verify `TunnelManager` uses `javax.inject.Provider<T>` factories for fresh provider instances
- [ ] Verify `McpServerService` starts tunnel AFTER server and stops tunnel BEFORE server
- [ ] Verify `onDestroy()` has `withTimeout(3_000L)` for tunnel stop — no ANR risk
- [ ] Verify tunnel failure does NOT prevent MCP server from running locally
- [ ] Verify tunnel URL is logged to **logcat** when connected
- [ ] Verify tunnel URL is logged to **UI server logs** via companion-level `serverLogEvents` SharedFlow on `McpServerService`
- [ ] Verify `McpServerService` has companion `serverLogEvents: SharedFlow<ServerLogEntry>` and instance `emitLogEntry()` helper
- [ ] Verify `MainViewModel` collects `McpServerService.serverLogEvents` in `init` and feeds entries into `addServerLogEntry()`
- [ ] Verify `ServerLogEntry` has `Type` enum (TOOL_CALL, TUNNEL, SERVER)
- [ ] Verify `ServerLogsSection.kt` / `ServerLogEntryRow` handles all three entry types (TOOL_CALL shows toolName/params/duration, TUNNEL/SERVER shows message)
- [ ] Verify `CloudflareTunnelProvider.start()` suppresses unused `config` parameter warning
- [ ] Verify UI shows correct state for all tunnel statuses (Disconnected, Connecting, Connected, Error)
- [ ] Verify provider selector uses **RadioButtons with description tags** (not SegmentedButtons)
- [ ] Verify ngrok authtoken field uses password transformation (hidden by default)
- [ ] Verify all settings are disabled when server is running
- [ ] Verify `ConnectionInfoCard` shows tunnel URL, has Share button, and preview compiles
- [ ] Verify `MainViewModel` has `shareText()` method using `Intent.ACTION_SEND`
- [ ] Verify `RemoteAccessSection` does NOT have a copy/share button (handled by ConnectionInfoCard)
- [ ] Verify `ServerConfig` has NO unnecessary import for `TunnelProviderType` (same package)
- [ ] Verify Plan 12 cross-impacts: `McpServerService` diffs anchored in post-plan-12 code (no `protocolHandler`, no `toolRegistry`, `registerAllTools(server)` signature)
- [ ] Verify Plan 12 cross-impacts: Cloudflare tunnel integration test does NOT reference `/health` endpoint (removed by Plan 12)
- [ ] Verify Plan 12 cross-impacts: `docs/PROJECT.md` server logs description updated from "recent MCP requests" to include tunnel events
- [ ] Verify cloudflared git submodule is pinned to correct tag in `vendor/cloudflared`
- [ ] Verify non-FIPS standard build of cloudflared
- [ ] Verify CI pipeline installs cloudflared before running integration tests
- [ ] Verify string resources are complete and correctly referenced
- [ ] Run `make lint` one final time
- [ ] Run `make test-unit` one final time
- [ ] Run `./gradlew build` one final time
- [ ] Verify no TODOs, no dead code, no temporary hacks
- [ ] Verify git diff is clean and all changes are committed

**Definition of Done**:
- [ ] All items above checked and passing
