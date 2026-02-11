# Plan 2: Data Layer & Utilities

**Branch**: `feat/data-layer-utilities`
**PR Title**: `Plan 2: Data layer, settings repository, and utilities`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan creates the data models, settings repository (DataStore), Hilt DI bindings, and utility classes that every subsequent plan depends on. After implementation, the application has a fully tested data layer with type-safe settings persistence, network utilities, permission helpers, and a structured logging wrapper.

**Prerequisite**: Plan 1 (project scaffolding) is implemented. The Gradle project compiles, Hilt is configured, and the `AppModule` exists as an empty shell.

### Package base: `com.danielealbano.androidremotecontrolmcp`
### Source root: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
### Test root: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`

---

## User Story 1: Data Layer & Utilities

**As a** developer
**I want** data models, a settings repository backed by DataStore, utility classes for networking/permissions/logging, and comprehensive unit tests
**So that** all subsequent plans (UI, services, MCP server) can depend on a stable, tested data layer and common utilities.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] `BindingAddress` enum exists with `LOCALHOST("127.0.0.1")` and `NETWORK("0.0.0.0")` values
- [ ] `CertificateSource` enum exists with `AUTO_GENERATED` and `CUSTOM` values
- [ ] `ServerConfig` data class exists with all fields and sensible defaults matching PROJECT.md
- [ ] `ServerStatus` sealed class exists with `Stopped`, `Starting`, `Running`, `Stopping`, `Error` subtypes
- [ ] `SettingsRepository` interface declares all CRUD operations, validation methods, and `Flow<ServerConfig>`
- [ ] `SettingsRepositoryImpl` implements `SettingsRepository` using Preferences DataStore with full validation
- [ ] `AppModule` provides `DataStore<Preferences>` as singleton and binds `SettingsRepository` to `SettingsRepositoryImpl`
- [ ] `NetworkUtils` provides device IP address lookup, port availability check, and network interface listing
- [ ] `PermissionUtils` provides accessibility service detection, accessibility settings navigation, and notification permission check
- [ ] `Logger` wraps Android `Log` with sanitization and build-type-aware log level filtering
- [ ] Unit tests exist and pass for all data models, repository, and utility classes
- [ ] `make lint` passes with no warnings or errors
- [ ] `make test-unit` passes with all tests green
- [ ] `make build` succeeds without errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add data models for server configuration and status` | `BindingAddress.kt`, `CertificateSource.kt`, `ServerConfig.kt`, `ServerStatus.kt` |
| 2 | `feat: add settings repository with DataStore persistence` | `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`, updated `AppModule.kt` |
| 3 | `feat: add utility classes for network, permissions, and logging` | `NetworkUtils.kt`, `PermissionUtils.kt`, `Logger.kt` |
| 4 | `test: add unit tests for data layer and utilities` | `ServerConfigTest.kt`, `ServerStatusTest.kt`, `SettingsRepositoryImplTest.kt`, `NetworkUtilsTest.kt`, `PermissionUtilsTest.kt`, `LoggerTest.kt` |
| 5 | (if needed) `fix: address lint or test issues` | Any files requiring fixes |

---

### Task 2.1: Create Data Model Enums

**Description**: Create the `BindingAddress` and `CertificateSource` enum classes that are used as field types in `ServerConfig`. These must exist before `ServerConfig` can be created.

**Acceptance Criteria**:
- [ ] `BindingAddress` enum has `LOCALHOST` and `NETWORK` entries with correct `address` property values
- [ ] `CertificateSource` enum has `AUTO_GENERATED` and `CUSTOM` entries
- [ ] Both files compile without errors
- [ ] Both files pass ktlint and detekt

**Tests**: Unit tests for these enums are included in `ServerConfigTest.kt` (Task 2.6). No separate test file needed for enums alone.

#### Action 2.1.1: Create `BindingAddress.kt`

**What**: Create the `BindingAddress` enum class with an `address` property.

**Context**: This enum represents the two possible binding addresses for the MCP server. `LOCALHOST` binds to `127.0.0.1` (only accessible via ADB port forwarding), `NETWORK` binds to `0.0.0.0` (accessible on all network interfaces). Referenced by `ServerConfig.bindingAddress` and used throughout the settings UI and MCP server configuration.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BindingAddress.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BindingAddress.kt
@@ -0,0 +1,20 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+/**
+ * Represents the network binding address for the MCP server.
+ *
+ * @property address The IP address string to bind to.
+ */
+enum class BindingAddress(val address: String) {
+    /** Bind to localhost only. Requires ADB port forwarding for access. */
+    LOCALHOST("127.0.0.1"),
+
+    /** Bind to all network interfaces. Accessible on the local network. */
+    NETWORK("0.0.0.0"),
+    ;
+
+    companion object {
+        /** Returns the [BindingAddress] matching the given [address] string, or [LOCALHOST] if not found. */
+        fun fromAddress(address: String): BindingAddress =
+            entries.firstOrNull { it.address == address } ?: LOCALHOST
+    }
+}
```

---

#### Action 2.1.2: Create `CertificateSource.kt`

**What**: Create the `CertificateSource` enum class.

**Context**: This enum represents the HTTPS certificate source. `AUTO_GENERATED` means the app creates a self-signed certificate on first launch. `CUSTOM` means the user uploads their own `.p12`/`.pfx` certificate. HTTPS is optional (disabled by default). When enabled, the user chooses between an auto-generated self-signed certificate or a custom uploaded certificate.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/CertificateSource.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/CertificateSource.kt
@@ -0,0 +1,17 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+/**
+ * Represents the source of the HTTPS certificate used by the MCP server.
+ *
+ * HTTPS is optional (disabled by default). When enabled, the user
+ * chooses between an auto-generated self-signed certificate or a custom uploaded certificate.
+ */
+enum class CertificateSource {
+    /** Auto-generated self-signed certificate with configurable hostname. */
+    AUTO_GENERATED,
+
+    /** Custom certificate uploaded by the user (.p12 / .pfx file). */
+    CUSTOM,
+    ;
+
+    companion object {
+        /** Returns the [CertificateSource] matching the given [name], or [AUTO_GENERATED] if not found. */
+        fun fromName(name: String): CertificateSource =
+            entries.firstOrNull { it.name == name } ?: AUTO_GENERATED
+    }
+}
```

---

### Task 2.2: Create ServerConfig Data Class

**Description**: Create the `ServerConfig` data class that holds all server configuration fields with default values matching PROJECT.md.

**Acceptance Criteria**:
- [ ] `ServerConfig` has all six fields: `port`, `bindingAddress`, `bearerToken`, `autoStartOnBoot`, `certificateSource`, `certificateHostname`
- [ ] Default values match PROJECT.md: port=8080, bindingAddress=LOCALHOST, bearerToken="", autoStartOnBoot=false, certificateSource=AUTO_GENERATED, certificateHostname="android-mcp.local"
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests for `ServerConfig` are in Task 2.6 (`ServerConfigTest.kt`).

#### Action 2.2.1: Create `ServerConfig.kt`

**What**: Create the `ServerConfig` data class.

**Context**: This is the central configuration data class for the MCP server. It is stored in DataStore via `SettingsRepositoryImpl` and consumed by `McpServerService`, `MainViewModel`, and the UI. The `bearerToken` defaults to empty string; `SettingsRepositoryImpl` auto-generates a UUID on first read if empty. All defaults are defined in PROJECT.md section "Default Configuration".

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`

> **CRITICAL -- Missing field**: `ServerConfig` is missing `httpsEnabled: Boolean = false`. This field is required by PROJECT.md (line 69: "HTTPS toggle") and referenced by Plan 3 (line 1568: `serverConfig.httpsEnabled`). At implementation time, add `val httpsEnabled: Boolean = false` to the data class, add a `PREF_HTTPS_ENABLED` DataStore key to `SettingsRepositoryImpl`, add `updateHttpsEnabled(enabled: Boolean)` to `SettingsRepository` interface and implementation, and include it in `mapPreferencesToServerConfig()`.

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt
@@ -0,0 +1,32 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+/**
+ * Holds the MCP server configuration.
+ *
+ * All fields have sensible defaults matching the project specification.
+ * The bearer token defaults to an empty string and is auto-generated
+ * (UUID) on first read by [com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl].
+ *
+ * @property port The server port (1-65535).
+ * @property bindingAddress The network binding address.
+ * @property bearerToken The bearer token for MCP request authentication.
+ * @property autoStartOnBoot Whether to start the MCP server on device boot.
+ * @property certificateSource The source of the HTTPS certificate.
+ * @property certificateHostname The hostname for auto-generated certificates.
+ */
+data class ServerConfig(
+    val port: Int = DEFAULT_PORT,
+    val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
+    val bearerToken: String = "",
+    val autoStartOnBoot: Boolean = false,
+    val certificateSource: CertificateSource = CertificateSource.AUTO_GENERATED,
+    val certificateHostname: String = DEFAULT_CERTIFICATE_HOSTNAME,
+) {
+    companion object {
+        /** Default server port. */
+        const val DEFAULT_PORT = 8080
+        /** Minimum valid port number. */
+        const val MIN_PORT = 1
+        /** Maximum valid port number. */
+        const val MAX_PORT = 65535
+        /** Default hostname for auto-generated certificates. */
+        const val DEFAULT_CERTIFICATE_HOSTNAME = "android-mcp.local"
+    }
+}
```

---

### Task 2.3: Create ServerStatus Sealed Class

**Description**: Create the `ServerStatus` sealed class representing the MCP server lifecycle states.

**Acceptance Criteria**:
- [ ] `ServerStatus` sealed class has `Stopped`, `Starting`, `Running`, `Stopping`, `Error` subtypes
- [ ] `Stopped` and `Starting` and `Stopping` are `data object` (singleton, no fields)
- [ ] `Running` is a `data class` with `port: Int`, `bindingAddress: String`, `httpsEnabled: Boolean = false`
- [ ] `Error` is a `data class` with `message: String`
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests for `ServerStatus` are in Task 2.6 (`ServerStatusTest.kt`).

#### Action 2.3.1: Create `ServerStatus.kt`

**What**: Create the `ServerStatus` sealed class.

**Context**: This sealed class represents the MCP server's lifecycle states. It is exposed via `StateFlow` from the `MainViewModel` and observed by the UI to display the current server status. The `Running` state carries connection details. The `Error` state carries an error message. `httpsEnabled` reflects whether the user has enabled HTTPS in settings (disabled by default). The field is used for UI display and connection info.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatus.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatus.kt
@@ -0,0 +1,35 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+/**
+ * Represents the current state of the MCP server lifecycle.
+ *
+ * Used as [kotlinx.coroutines.flow.StateFlow] value in ViewModels
+ * and observed by the UI to display server status.
+ */
+sealed class ServerStatus {
+
+    /** The MCP server is stopped and not listening for connections. */
+    data object Stopped : ServerStatus()
+
+    /** The MCP server is in the process of starting up. */
+    data object Starting : ServerStatus()
+
+    /**
+     * The MCP server is running and accepting connections.
+     *
+     * @property port The port the server is listening on.
+     * @property bindingAddress The IP address the server is bound to.
+     * @property httpsEnabled Whether HTTPS is enabled (optional, disabled by default).
+     */
+    data class Running(
+        val port: Int,
+        val bindingAddress: String,
+        val httpsEnabled: Boolean = false,
+    ) : ServerStatus()
+
+    /** The MCP server is in the process of shutting down. */
+    data object Stopping : ServerStatus()
+
+    /**
+     * The MCP server encountered an error.
+     *
+     * @property message A human-readable description of the error.
+     */
+    data class Error(val message: String) : ServerStatus()
+}
```

---

### Task 2.4: Create Settings Repository

**Description**: Create the `SettingsRepository` interface and `SettingsRepositoryImpl` implementation backed by Preferences DataStore. This is the single access point for all settings in the application per CLAUDE.md rules ("All DataStore access MUST go through SettingsRepository").

**Acceptance Criteria**:
- [ ] `SettingsRepository` interface declares `serverConfig: Flow<ServerConfig>`, `getServerConfig()`, and all update/validate methods
- [ ] `SettingsRepositoryImpl` uses `DataStore<Preferences>` injected via constructor
- [ ] Port validation rejects values outside 1-65535
- [ ] Certificate hostname validation rejects empty or invalid hostnames
- [ ] Bearer token is auto-generated (UUID) on first read if empty
- [ ] `generateNewBearerToken()` creates a new UUID and persists it
- [ ] All DataStore reads provide default fallbacks
- [ ] Files compile without errors
- [ ] Files pass ktlint and detekt

**Tests**: Unit tests for `SettingsRepositoryImpl` are in Task 2.6 (`SettingsRepositoryImplTest.kt`).

#### Action 2.4.1: Create `SettingsRepository.kt`

**What**: Create the `SettingsRepository` interface.

**Context**: This interface defines the contract for settings access. All consumers (ViewModel, services) depend on this interface, not the implementation, per SOLID principles. The `serverConfig` Flow emits the full `ServerConfig` whenever any setting changes. Individual update methods allow changing one setting at a time. Validation methods return `Result<T>` for type-safe error handling.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt
@@ -0,0 +1,78 @@
+package com.danielealbano.androidremotecontrolmcp.data.repository
+
+import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
+import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
+import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
+import kotlinx.coroutines.flow.Flow
+
+/**
+ * Repository for accessing and persisting MCP server settings.
+ *
+ * This is the single access point for all application settings.
+ * All DataStore access MUST go through this interface. UI, ViewModels,
+ * and Services must not access DataStore directly.
+ */
+interface SettingsRepository {
+
+    /**
+     * Observes the current server configuration. Emits a new [ServerConfig]
+     * whenever any setting changes.
+     */
+    val serverConfig: Flow<ServerConfig>
+
+    /**
+     * Returns the current server configuration as a one-shot read.
+     * If the bearer token is empty, a new one is auto-generated and persisted.
+     */
+    suspend fun getServerConfig(): ServerConfig
+
+    /**
+     * Updates the server port.
+     *
+     * @param port The new port value. Must pass [validatePort] first.
+     */
+    suspend fun updatePort(port: Int)
+
+    /** Updates the network binding address. */
+    suspend fun updateBindingAddress(bindingAddress: BindingAddress)
+
+    /**
+     * Updates the bearer token used for MCP request authentication.
+     *
+     * @param token The new bearer token value.
+     */
+    suspend fun updateBearerToken(token: String)
+
+    /**
+     * Generates a new random bearer token (UUID), persists it, and returns
+     * the generated value.
+     *
+     * @return The newly generated bearer token.
+     */
+    suspend fun generateNewBearerToken(): String
+
+    /** Updates the auto-start-on-boot preference. */
+    suspend fun updateAutoStartOnBoot(enabled: Boolean)
+
+    /** Updates the HTTPS certificate source. */
+    suspend fun updateCertificateSource(source: CertificateSource)
+
+    /**
+     * Updates the hostname used for auto-generated HTTPS certificates.
+     *
+     * @param hostname The new hostname. Must pass [validateCertificateHostname] first.
+     */
+    suspend fun updateCertificateHostname(hostname: String)
+
+    /**
+     * Validates a port number.
+     *
+     * This is a pure validation function with no I/O; it is intentionally
+     * non-suspending so callers are not forced into a coroutine context.
+     *
+     * @return [Result.success] with the validated port, or [Result.failure] with an [IllegalArgumentException].
+     */
+    fun validatePort(port: Int): Result<Int>
+
+    /**
+     * Validates a certificate hostname.
+     *
+     * This is a pure validation function with no I/O; it is intentionally
+     * non-suspending so callers are not forced into a coroutine context.
+     *
+     * @return [Result.success] with the validated hostname, or [Result.failure] with an [IllegalArgumentException].
+     */
+    fun validateCertificateHostname(hostname: String): Result<String>
+}
```

---

#### Action 2.4.2: Create `SettingsRepositoryImpl.kt`

**What**: Create the `SettingsRepositoryImpl` implementation using Preferences DataStore.

**Context**: This class is the sole DataStore accessor in the entire application. It maps between DataStore preference keys and the `ServerConfig` data class. Each update method writes a single preference key. The `serverConfig` Flow maps the raw `Preferences` to `ServerConfig` on every emission **without side effects** (no auto-generation in the Flow — that would cause re-emission loops and race conditions with multiple collectors). Bearer token auto-generation happens **only** in `getServerConfig()` (the suspend function). On first app launch, `getServerConfig()` is called by the service startup path, which generates and persists the token; subsequent Flow emissions then see the persisted token. Port validation enforces the 1-65535 range. Hostname validation enforces non-empty, valid hostname characters (letters, digits, hyphens, dots). The class uses `@Inject constructor` for Hilt and is scoped as `@Singleton` via the Hilt module binding.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt
@@ -0,0 +1,164 @@
+package com.danielealbano.androidremotecontrolmcp.data.repository
+
+import androidx.datastore.core.DataStore
+import androidx.datastore.preferences.core.Preferences
+import androidx.datastore.preferences.core.booleanPreferencesKey
+import androidx.datastore.preferences.core.edit
+import androidx.datastore.preferences.core.intPreferencesKey
+import androidx.datastore.preferences.core.stringPreferencesKey
+import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
+import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
+import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
+import java.util.UUID
+import javax.inject.Inject
+import kotlinx.coroutines.flow.Flow
+import kotlinx.coroutines.flow.first
+import kotlinx.coroutines.flow.map
+
+/**
+ * [SettingsRepository] implementation backed by Preferences DataStore.
+ *
+ * This is the single access point for all persisted settings in the
+ * application. No other class should access DataStore directly.
+ *
+ * @property dataStore The Preferences DataStore instance provided by Hilt.
+ */
+class SettingsRepositoryImpl @Inject constructor(
+    private val dataStore: DataStore<Preferences>,
+) : SettingsRepository {
+
+    override val serverConfig: Flow<ServerConfig> = dataStore.data.map { prefs ->
+        mapPreferencesToServerConfig(prefs)
+    }
+
+    override suspend fun getServerConfig(): ServerConfig {
+        val config = dataStore.data.first().let { prefs ->
+            mapPreferencesToServerConfig(prefs)
+        }
+
+        if (config.bearerToken.isEmpty()) {
+            val token = generateTokenString()
+            updateBearerToken(token)
+            return config.copy(bearerToken = token)
+        }
+
+        return config
+    }
+
+    override suspend fun updatePort(port: Int) {
+        dataStore.edit { prefs ->
+            prefs[PORT_KEY] = port
+        }
+    }
+
+    override suspend fun updateBindingAddress(bindingAddress: BindingAddress) {
+        dataStore.edit { prefs ->
+            prefs[BINDING_ADDRESS_KEY] = bindingAddress.name
+        }
+    }
+
+    override suspend fun updateBearerToken(token: String) {
+        dataStore.edit { prefs ->
+            prefs[BEARER_TOKEN_KEY] = token
+        }
+    }
+
+    override suspend fun generateNewBearerToken(): String {
+        val token = generateTokenString()
+        updateBearerToken(token)
+        return token
+    }
+
+    override suspend fun updateAutoStartOnBoot(enabled: Boolean) {
+        dataStore.edit { prefs ->
+            prefs[AUTO_START_KEY] = enabled
+        }
+    }
+
+    override suspend fun updateCertificateSource(source: CertificateSource) {
+        dataStore.edit { prefs ->
+            prefs[CERTIFICATE_SOURCE_KEY] = source.name
+        }
+    }
+
+    override suspend fun updateCertificateHostname(hostname: String) {
+        dataStore.edit { prefs ->
+            prefs[CERTIFICATE_HOSTNAME_KEY] = hostname
+        }
+    }
+
+    override fun validatePort(port: Int): Result<Int> =
+        if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
+            Result.success(port)
+        } else {
+            Result.failure(
+                IllegalArgumentException(
+                    "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}",
+                ),
+            )
+        }
+
+    override fun validateCertificateHostname(hostname: String): Result<String> {
+        if (hostname.isBlank()) {
+            return Result.failure(
+                IllegalArgumentException("Certificate hostname must not be empty"),
+            )
+        }
+
+        if (!HOSTNAME_PATTERN.matches(hostname)) {
+            return Result.failure(
+                IllegalArgumentException(
+                    "Certificate hostname contains invalid characters. " +
+                        "Use only letters, digits, hyphens, and dots.",
+                ),
+            )
+        }
+
+        return Result.success(hostname)
+    }
+
+    /**
+     * Maps raw [Preferences] to a [ServerConfig] instance, applying defaults
+     * for any missing keys.
+     */
+    private fun mapPreferencesToServerConfig(prefs: Preferences): ServerConfig {
+        val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
+        val certificateSourceName = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name
+
+        return ServerConfig(
+            port = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT,
+            bindingAddress = BindingAddress.entries.firstOrNull { it.name == bindingAddressName }
+                ?: BindingAddress.LOCALHOST,
+            bearerToken = prefs[BEARER_TOKEN_KEY] ?: "",
+            autoStartOnBoot = prefs[AUTO_START_KEY] ?: false,
+            certificateSource = CertificateSource.entries.firstOrNull { it.name == certificateSourceName }
+                ?: CertificateSource.AUTO_GENERATED,
+            certificateHostname = prefs[CERTIFICATE_HOSTNAME_KEY]
+                ?: ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME,
+        )
+    }
+
+    /**
+     * Generates a random UUID string for use as a bearer token.
+     */
+    private fun generateTokenString(): String = UUID.randomUUID().toString()
+
+    companion object {
+        private val PORT_KEY = intPreferencesKey("port")
+        private val BINDING_ADDRESS_KEY = stringPreferencesKey("binding_address")
+        private val BEARER_TOKEN_KEY = stringPreferencesKey("bearer_token")
+        private val AUTO_START_KEY = booleanPreferencesKey("auto_start_on_boot")
+        private val CERTIFICATE_SOURCE_KEY = stringPreferencesKey("certificate_source")
+        private val CERTIFICATE_HOSTNAME_KEY = stringPreferencesKey("certificate_hostname")
+
+        /**
+         * Regex pattern for valid hostnames.
+         *
+         * Allows labels of letters, digits, and hyphens separated by dots.
+         * Each label must start and end with an alphanumeric character.
+         * Maximum total length is 253 characters per RFC 1035.
+         */
+        private val HOSTNAME_PATTERN = Regex(
+            "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*" +
+                "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
+        )
+    }
+}
```

---

### Task 2.5: Update AppModule with DataStore and Repository Bindings

**Description**: Update the existing `AppModule.kt` to provide `DataStore<Preferences>` as a singleton and bind `SettingsRepository` to `SettingsRepositoryImpl`.

**Acceptance Criteria**:
- [ ] `AppModule` provides `DataStore<Preferences>` via `@Provides @Singleton`
- [ ] A separate `RepositoryModule` (abstract class) binds `SettingsRepository` to `SettingsRepositoryImpl` via `@Binds @Singleton`
- [ ] Both modules are `@InstallIn(SingletonComponent::class)`
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: The Hilt module is verified implicitly by `SettingsRepositoryImplTest` (Task 2.6) which uses the DataStore directly, and by `make build` succeeding.

#### Action 2.5.1: Update `AppModule.kt`

**What**: Add the `DataStore<Preferences>` provider and the `SettingsRepository` binding to the Hilt DI module.

**Context**: The existing `AppModule` from Plan 1 is an empty `object`. We need to add a `@Provides` function for `DataStore<Preferences>` (since DataStore creation requires a `Context` and a file name). The `SettingsRepository` binding uses `@Binds` which requires an abstract class/interface, so we create a separate `RepositoryModule` abstract class in the same file. This is a standard Hilt pattern: use `object` for `@Provides` and `abstract class` for `@Binds`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt
@@ -1,10 +1,41 @@
 package com.danielealbano.androidremotecontrolmcp.di

+import android.content.Context
+import androidx.datastore.core.DataStore
+import androidx.datastore.preferences.core.Preferences
+import androidx.datastore.preferences.preferencesDataStore
+import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
+import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImpl
+import dagger.Binds
 import dagger.Module
+import dagger.Provides
 import dagger.hilt.InstallIn
+import dagger.hilt.android.qualifiers.ApplicationContext
 import dagger.hilt.components.SingletonComponent
+import javax.inject.Singleton
+
+/** Extension property for creating the Preferences DataStore on [Context]. */
+private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
+    name = "settings",
+)

 @Module
 @InstallIn(SingletonComponent::class)
-object AppModule {
-}
+object AppModule {
+
+    /**
+     * Provides the application-scoped [DataStore] for settings persistence.
+     */
+    @Provides
+    @Singleton
+    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
+        context.settingsDataStore
+}
+
+@Module
+@InstallIn(SingletonComponent::class)
+abstract class RepositoryModule {
+
+    /**
+     * Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository].
+     */
+    @Binds
+    @Singleton
+    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
+}
```

---

### Task 2.6: Create Utility Classes

**Description**: Create the `NetworkUtils`, `PermissionUtils`, and `Logger` utility classes.

**Acceptance Criteria**:
- [ ] `NetworkUtils` provides `getDeviceIpAddress()`, `isPortAvailable()`, `getNetworkInterfaces()` with `NetworkInterfaceInfo` data class
- [ ] `PermissionUtils` provides `isAccessibilityServiceEnabled()`, `openAccessibilitySettings()`, `isNotificationPermissionGranted()`
- [ ] `Logger` provides `d()`, `i()`, `w()`, `e()` methods with bearer token sanitization and build-type log level filtering
- [ ] All files compile without errors
- [ ] All files pass ktlint and detekt

**Tests**: Unit tests for all utilities are in Task 2.7.

#### Action 2.6.1: Create `NetworkUtils.kt`

**What**: Create the `NetworkUtils` utility class with network helper functions.

**Context**: `getDeviceIpAddress()` returns the device's WiFi IP address (useful for displaying the MCP server URL in the UI when binding to `0.0.0.0`). `isPortAvailable()` checks if a TCP port is free (useful before starting the server). `getNetworkInterfaces()` lists all available network interfaces (informational, for the connection info UI card). The `NetworkInterfaceInfo` data class is defined inside this file.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtils.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtils.kt
@@ -0,0 +1,82 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import android.content.Context
+import android.net.ConnectivityManager
+import android.net.LinkProperties
+import java.net.Inet4Address
+import java.net.NetworkInterface
+import java.net.ServerSocket
+
+/**
+ * Utility functions for network operations.
+ */
+object NetworkUtils {
+
+    private const val TAG = "MCP:NetworkUtils"
+
+    /**
+     * Returns the device's primary IPv4 address, or `null` if unavailable.
+     *
+     * Uses [ConnectivityManager] to query the active network's link properties.
+     *
+     * @param context Application context for accessing system services.
+     * @return The device's IPv4 address as a string, or `null`.
+     */
+    fun getDeviceIpAddress(context: Context): String? {
+        val connectivityManager =
+            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
+                ?: return null
+
+        val activeNetwork = connectivityManager.activeNetwork ?: return null
+        val linkProperties: LinkProperties =
+            connectivityManager.getLinkProperties(activeNetwork) ?: return null
+
+        return linkProperties.linkAddresses
+            .map { it.address }
+            .filterIsInstance<Inet4Address>()
+            .firstOrNull { !it.isLoopbackAddress }
+            ?.hostAddress
+    }
+
+    /**
+     * Checks whether a TCP port is currently available for binding.
+     *
+     * Attempts to open a [ServerSocket] on the given port. If it succeeds,
+     * the port is available. If it throws, the port is in use.
+     *
+     * @param port The TCP port to check (1-65535).
+     * @return `true` if the port is available, `false` otherwise.
+     */
+    fun isPortAvailable(port: Int): Boolean =
+        try {
+            ServerSocket(port).use { true }
+        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
+            false
+        }
+
+    /**
+     * Lists all available network interfaces with their IPv4 addresses.
+     *
+     * @return A list of [NetworkInterfaceInfo] for each interface with an IPv4 address.
+     */
+    fun getNetworkInterfaces(): List<NetworkInterfaceInfo> {
+        val result = mutableListOf<NetworkInterfaceInfo>()
+
+        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
+
+        for (networkInterface in interfaces.asSequence()) {
+            for (address in networkInterface.inetAddresses.asSequence()) {
+                if (address is Inet4Address) {
+                    result.add(
+                        NetworkInterfaceInfo(
+                            name = networkInterface.displayName,
+                            address = address.hostAddress ?: continue,
+                            isLoopback = address.isLoopbackAddress,
+                        ),
+                    )
+                }
+            }
+        }
+
+        return result
+    }
+}
+
+/**
+ * Information about a network interface.
+ *
+ * @property name The display name of the interface (e.g., "wlan0", "lo").
+ * @property address The IPv4 address assigned to this interface.
+ * @property isLoopback Whether this is a loopback interface.
+ */
+data class NetworkInterfaceInfo(
+    val name: String,
+    val address: String,
+    val isLoopback: Boolean,
+)
```

---

#### Action 2.6.2: Create `PermissionUtils.kt`

**What**: Create the `PermissionUtils` utility class with permission helper functions.

**Context**: `isAccessibilityServiceEnabled()` checks if the app's accessibility service is currently enabled in Android Settings. This is needed before performing accessibility operations (MCP tool calls return error `-32001` if not enabled). `openAccessibilitySettings()` launches the Android Accessibility Settings screen (used as a quick link in the UI). `isNotificationPermissionGranted()` checks the `POST_NOTIFICATIONS` permission required on Android 13+ (API 33+) for foreground service notifications.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtils.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtils.kt
@@ -0,0 +1,75 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import android.Manifest
+import android.content.Context
+import android.content.Intent
+import android.content.pm.PackageManager
+import android.os.Build
+import android.provider.Settings
+import android.text.TextUtils
+import androidx.core.content.ContextCompat
+
+/**
+ * Utility functions for checking and requesting Android permissions.
+ */
+object PermissionUtils {
+
+    private const val TAG = "MCP:PermissionUtils"
+    private const val ENABLED_SERVICES_SEPARATOR = ':'
+
+    /**
+     * Checks whether a specific accessibility service is currently enabled.
+     *
+     * Reads the `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` system setting
+     * and checks if the given service class is listed.
+     *
+     * @param context Application context.
+     * @param serviceClass The accessibility service class to check (e.g., `McpAccessibilityService::class.java`).
+     * @return `true` if the service is enabled, `false` otherwise.
+     */
+    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
+        val expectedComponentName =
+            "${context.packageName}/${serviceClass.canonicalName}"
+
+        val enabledServices = Settings.Secure.getString(
+            context.contentResolver,
+            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
+        ) ?: return false
+
+        val colonSplitter = TextUtils.SimpleStringSplitter(ENABLED_SERVICES_SEPARATOR)
+        colonSplitter.setString(enabledServices)
+
+        while (colonSplitter.hasNext()) {
+            val componentName = colonSplitter.next()
+            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
+                return true
+            }
+        }
+
+        return false
+    }
+
+    /**
+     * Opens the Android Accessibility Settings screen.
+     *
+     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
+     *   so this can be called from non-Activity contexts.
+     */
+    fun openAccessibilitySettings(context: Context) {
+        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
+            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+        }
+        context.startActivity(intent)
+    }
+
+    /**
+     * Checks whether the `POST_NOTIFICATIONS` permission is granted.
+     *
+     * On Android 12 (API 32) and below, notification permission is always
+     * granted. On Android 13 (API 33) and above, runtime permission is required.
+     *
+     * @param context Application context.
+     * @return `true` if notification permission is granted, `false` otherwise.
+     */
+    fun isNotificationPermissionGranted(context: Context): Boolean =
+        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
+            ContextCompat.checkSelfPermission(
+                context,
+                Manifest.permission.POST_NOTIFICATIONS,
+            ) == PackageManager.PERMISSION_GRANTED
+        } else {
+            true
+        }
+}
```

---

#### Action 2.6.3: Create `Logger.kt`

**What**: Create the `Logger` utility class wrapping Android `Log` with sanitization and build-type filtering.

**Context**: The `Logger` wraps `android.util.Log` to add two features: (1) sanitize sensitive data (bearer tokens) before they reach the log output, and (2) respect the build type by filtering verbose/debug logs in release builds. The sanitization uses a UUID regex pattern to detect and mask bearer tokens. The build type check uses `BuildConfig.DEBUG`. All services and utilities should use this logger instead of calling `android.util.Log` directly.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/Logger.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/Logger.kt
@@ -0,0 +1,98 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.BuildConfig
+
+/**
+ * Logging wrapper that sanitizes sensitive data and respects build type.
+ *
+ * **Why this exists instead of raw `android.util.Log` calls:**
+ * 1. **Sanitization** -- Bearer tokens (UUID format) are automatically masked
+ *    with `[REDACTED]` before reaching the log output, preventing accidental
+ *    credential exposure. Every log call passes through [sanitize] so developers
+ *    cannot accidentally log a token in plaintext.
+ * 2. **Build-type filtering** -- Debug-level messages are suppressed in release
+ *    builds (`BuildConfig.DEBUG` gate), reducing log noise in production without
+ *    requiring callers to check the build type themselves.
+ *
+ * All services and utilities should use this logger instead of calling
+ * `android.util.Log` directly. Log tags should follow the `MCP:<Component>`
+ * convention (e.g., `MCP:ServerService`, `MCP:AccessibilityService`).
+ *
+ * - **Debug builds**: All log levels are emitted (verbose, debug, info, warn, error).
+ * - **Release builds**: Only info, warn, and error are emitted.
+ */
+object Logger {
+
+    /**
+     * Regex matching UUID strings (standard 8-4-4-4-12 hex format).
+     * Used to detect and sanitize bearer tokens in log messages.
+     */
+    private val UUID_PATTERN = Regex(
+        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
+    )
+
+    private const val REDACTED_TOKEN = "[REDACTED]"
+
+    /**
+     * Logs a debug-level message. Only emitted in debug builds.
+     *
+     * @param tag Log tag identifying the source component.
+     * @param message The message to log. Bearer tokens are auto-sanitized.
+     */
+    fun d(tag: String, message: String) {
+        if (BuildConfig.DEBUG) {
+            Log.d(tag, sanitize(message))
+        }
+    }
+
+    /**
+     * Logs an info-level message.
+     *
+     * @param tag Log tag identifying the source component.
+     * @param message The message to log. Bearer tokens are auto-sanitized.
+     */
+    fun i(tag: String, message: String) {
+        Log.i(tag, sanitize(message))
+    }
+
+    /**
+     * Logs a warning-level message.
+     *
+     * @param tag Log tag identifying the source component.
+     * @param message The message to log. Bearer tokens are auto-sanitized.
+     * @param throwable Optional throwable to include in the log.
+     */
+    fun w(tag: String, message: String, throwable: Throwable? = null) {
+        if (throwable != null) {
+            Log.w(tag, sanitize(message), throwable)
+        } else {
+            Log.w(tag, sanitize(message))
+        }
+    }
+
+    /**
+     * Logs an error-level message.
+     *
+     * @param tag Log tag identifying the source component.
+     * @param message The message to log. Bearer tokens are auto-sanitized.
+     * @param throwable Optional throwable to include in the log.
+     */
+    fun e(tag: String, message: String, throwable: Throwable? = null) {
+        if (throwable != null) {
+            Log.e(tag, sanitize(message), throwable)
+        } else {
+            Log.e(tag, sanitize(message))
+        }
+    }
+
+    /**
+     * Sanitizes a log message by replacing UUID-format strings with [REDACTED_TOKEN].
+     *
+     * This prevents bearer tokens (which are UUIDs) from appearing in logs.
+     *
+     * @param message The raw log message.
+     * @return The sanitized message with UUIDs replaced.
+     */
+    internal fun sanitize(message: String): String =
+        UUID_PATTERN.replace(message, REDACTED_TOKEN)
+}
```

---

### Task 2.7: Create Unit Tests

**Description**: Create comprehensive unit tests for all data models, the settings repository, and all utility classes.

**Acceptance Criteria**:
- [ ] `ServerConfigTest` tests default values, copy behavior, companion constants
- [ ] `ServerStatusTest` tests all sealed class subtypes, equality, and toString
- [ ] `SettingsRepositoryImplTest` tests all CRUD operations, validation (port range, hostname pattern), bearer token auto-generation, Flow emission via Turbine
- [ ] `NetworkUtilsTest` tests port availability check, network interface listing
- [ ] `PermissionUtilsTest` tests accessibility service detection with mocked Context/ContentResolver
- [ ] `LoggerTest` tests UUID sanitization, log level filtering based on build type
- [ ] All tests pass with `./gradlew test` (or targeted `./gradlew test --tests "..."`)
- [ ] No warnings or errors from test execution

**Tests**: These ARE the tests.

#### Action 2.7.1: Create `ServerConfigTest.kt`

**What**: Create unit tests for `ServerConfig` data class and the `BindingAddress`/`CertificateSource` enums.

**Context**: Tests verify default values match PROJECT.md specification, that the `copy()` function works correctly for immutable updates, and that enum helper functions (`fromAddress`, `fromName`) work for known and unknown inputs.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt
@@ -0,0 +1,120 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertNotEquals
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("ServerConfig")
+class ServerConfigTest {
+
+    @Nested
+    @DisplayName("default values")
+    inner class DefaultValues {
+
+        @Test
+        fun `default port is 8080`() {
+            val config = ServerConfig()
+            assertEquals(8080, config.port)
+        }
+
+        @Test
+        fun `default binding address is LOCALHOST`() {
+            val config = ServerConfig()
+            assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
+        }
+
+        @Test
+        fun `default bearer token is empty`() {
+            val config = ServerConfig()
+            assertEquals("", config.bearerToken)
+        }
+
+        @Test
+        fun `default auto start on boot is false`() {
+            val config = ServerConfig()
+            assertFalse(config.autoStartOnBoot)
+        }
+
+        @Test
+        fun `default certificate source is AUTO_GENERATED`() {
+            val config = ServerConfig()
+            assertEquals(CertificateSource.AUTO_GENERATED, config.certificateSource)
+        }
+
+        @Test
+        fun `default certificate hostname is android-mcp local`() {
+            val config = ServerConfig()
+            assertEquals("android-mcp.local", config.certificateHostname)
+        }
+    }
+
+    @Nested
+    @DisplayName("copy behavior")
+    inner class CopyBehavior {
+
+        @Test
+        fun `copy with changed port preserves other fields`() {
+            val original = ServerConfig(port = 8080, bearerToken = "test-token")
+            val copied = original.copy(port = 9090)
+
+            assertEquals(9090, copied.port)
+            assertEquals("test-token", copied.bearerToken)
+            assertEquals(original.bindingAddress, copied.bindingAddress)
+        }
+
+        @Test
+        fun `copy creates a distinct instance`() {
+            val original = ServerConfig()
+            val copied = original.copy(port = 9090)
+
+            assertNotEquals(original, copied)
+        }
+    }
+
+    @Nested
+    @DisplayName("companion constants")
+    inner class CompanionConstants {
+
+        @Test
+        fun `DEFAULT_PORT is 8080`() {
+            assertEquals(8080, ServerConfig.DEFAULT_PORT)
+        }
+
+        @Test
+        fun `MIN_PORT is 1`() {
+            assertEquals(1, ServerConfig.MIN_PORT)
+        }
+
+        @Test
+        fun `MAX_PORT is 65535`() {
+            assertEquals(65535, ServerConfig.MAX_PORT)
+        }
+
+        @Test
+        fun `DEFAULT_CERTIFICATE_HOSTNAME is android-mcp local`() {
+            assertEquals("android-mcp.local", ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME)
+        }
+    }
+
+    @Nested
+    @DisplayName("BindingAddress enum")
+    inner class BindingAddressTest {
+
+        @Test
+        fun `LOCALHOST address is 127 0 0 1`() {
+            assertEquals("127.0.0.1", BindingAddress.LOCALHOST.address)
+        }
+
+        @Test
+        fun `NETWORK address is 0 0 0 0`() {
+            assertEquals("0.0.0.0", BindingAddress.NETWORK.address)
+        }
+
+        @Test
+        fun `fromAddress returns LOCALHOST for known address`() {
+            assertEquals(BindingAddress.LOCALHOST, BindingAddress.fromAddress("127.0.0.1"))
+        }
+
+        @Test
+        fun `fromAddress returns NETWORK for known address`() {
+            assertEquals(BindingAddress.NETWORK, BindingAddress.fromAddress("0.0.0.0"))
+        }
+
+        @Test
+        fun `fromAddress returns LOCALHOST for unknown address`() {
+            assertEquals(BindingAddress.LOCALHOST, BindingAddress.fromAddress("192.168.1.1"))
+        }
+    }
+
+    @Nested
+    @DisplayName("CertificateSource enum")
+    inner class CertificateSourceTest {
+
+        @Test
+        fun `fromName returns AUTO_GENERATED for known name`() {
+            assertEquals(CertificateSource.AUTO_GENERATED, CertificateSource.fromName("AUTO_GENERATED"))
+        }
+
+        @Test
+        fun `fromName returns CUSTOM for known name`() {
+            assertEquals(CertificateSource.CUSTOM, CertificateSource.fromName("CUSTOM"))
+        }
+
+        @Test
+        fun `fromName returns AUTO_GENERATED for unknown name`() {
+            assertEquals(CertificateSource.AUTO_GENERATED, CertificateSource.fromName("INVALID"))
+        }
+    }
+}
```

---

#### Action 2.7.2: Create `ServerStatusTest.kt`

**What**: Create unit tests for the `ServerStatus` sealed class.

**Context**: Tests verify that each sealed subtype is distinct, that singleton subtypes (`Stopped`, `Starting`, `Stopping`) maintain identity, and that data class subtypes (`Running`, `Error`) support equality and carry correct fields.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatusTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatusTest.kt
@@ -0,0 +1,96 @@
+package com.danielealbano.androidremotecontrolmcp.data.model
+
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertInstanceOf
+import org.junit.jupiter.api.Assertions.assertNotEquals
+import org.junit.jupiter.api.Assertions.assertSame
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("ServerStatus")
+class ServerStatusTest {
+
+    @Nested
+    @DisplayName("Stopped")
+    inner class StoppedTest {
+
+        @Test
+        fun `Stopped is a singleton`() {
+            assertSame(ServerStatus.Stopped, ServerStatus.Stopped)
+        }
+
+        @Test
+        fun `Stopped is a ServerStatus`() {
+            assertInstanceOf(ServerStatus::class.java, ServerStatus.Stopped)
+        }
+    }
+
+    @Nested
+    @DisplayName("Starting")
+    inner class StartingTest {
+
+        @Test
+        fun `Starting is a singleton`() {
+            assertSame(ServerStatus.Starting, ServerStatus.Starting)
+        }
+
+        @Test
+        fun `Starting is a ServerStatus`() {
+            assertInstanceOf(ServerStatus::class.java, ServerStatus.Starting)
+        }
+    }
+
+    @Nested
+    @DisplayName("Running")
+    inner class RunningTest {
+
+        @Test
+        fun `Running carries port and binding address`() {
+            val status = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
+            assertEquals(8080, status.port)
+            assertEquals("127.0.0.1", status.bindingAddress)
+        }
+
+        @Test
+        fun `Running defaults httpsEnabled to false`() {
+            val status = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
+            assertFalse(status.httpsEnabled)
+        }
+
+        @Test
+        fun `Running equality is based on fields`() {
+            val a = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
+            val b = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
+            assertEquals(a, b)
+        }
+
+        @Test
+        fun `Running with different port are not equal`() {
+            val a = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
+            val b = ServerStatus.Running(port = 9090, bindingAddress = "127.0.0.1")
+            assertNotEquals(a, b)
+        }
+    }
+
+    @Nested
+    @DisplayName("Stopping")
+    inner class StoppingTest {
+
+        @Test
+        fun `Stopping is a singleton`() {
+            assertSame(ServerStatus.Stopping, ServerStatus.Stopping)
+        }
+    }
+
+    @Nested
+    @DisplayName("Error")
+    inner class ErrorTest {
+
+        @Test
+        fun `Error carries message`() {
+            val status = ServerStatus.Error(message = "Port in use")
+            assertEquals("Port in use", status.message)
+        }
+
+        @Test
+        fun `Error equality is based on message`() {
+            val a = ServerStatus.Error(message = "Port in use")
+            val b = ServerStatus.Error(message = "Port in use")
+            assertEquals(a, b)
+        }
+
+        @Test
+        fun `Errors with different messages are not equal`() {
+            val a = ServerStatus.Error(message = "Port in use")
+            val b = ServerStatus.Error(message = "Permission denied")
+            assertNotEquals(a, b)
+        }
+    }
+}
```

---

#### Action 2.7.3: Create `SettingsRepositoryImplTest.kt`

**What**: Create comprehensive unit tests for the `SettingsRepositoryImpl`.

**Context**: This test uses a real `DataStore<Preferences>` created with the `PreferenceDataStoreFactory.create()` test helper (writes to a temporary file). This avoids mocking DataStore internals and tests the actual read/write behavior. Turbine is used to test the `serverConfig` Flow emissions. Each test gets a fresh DataStore file via `@TempDir`. The tests cover: reading defaults, updating each setting, validation (port range, hostname), bearer token auto-generation, and Flow emission on changes.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt
@@ -0,0 +1,284 @@
+package com.danielealbano.androidremotecontrolmcp.data.repository
+
+import androidx.datastore.core.DataStore
+import androidx.datastore.preferences.core.PreferenceDataStoreFactory
+import androidx.datastore.preferences.core.Preferences
+import app.cash.turbine.test
+import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
+import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
+import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
+import java.io.File
+import kotlinx.coroutines.ExperimentalCoroutinesApi
+import kotlinx.coroutines.flow.first
+import kotlinx.coroutines.test.TestScope
+import kotlinx.coroutines.test.UnconfinedTestDispatcher
+import kotlinx.coroutines.test.runTest
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertNotEquals
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.io.TempDir
+
+@OptIn(ExperimentalCoroutinesApi::class)
+@DisplayName("SettingsRepositoryImpl")
+class SettingsRepositoryImplTest {
+
+    @TempDir
+    lateinit var tempDir: File
+
+    private val testDispatcher = UnconfinedTestDispatcher()
+    private val testScope = TestScope(testDispatcher)
+
+    private lateinit var dataStore: DataStore<Preferences>
+    private lateinit var repository: SettingsRepositoryImpl
+
+    @BeforeEach
+    fun setUp() {
+        dataStore = PreferenceDataStoreFactory.create(
+            scope = testScope,
+            produceFile = { File(tempDir, "test_settings.preferences_pb") },
+        )
+        repository = SettingsRepositoryImpl(dataStore)
+    }
+
+    @Nested
+    @DisplayName("getServerConfig")
+    inner class GetServerConfig {
+
+        @Test
+        fun `returns default values when no settings stored`() = testScope.runTest {
+            val config = repository.getServerConfig()
+
+            assertEquals(ServerConfig.DEFAULT_PORT, config.port)
+            assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
+            assertFalse(config.autoStartOnBoot)
+            assertEquals(CertificateSource.AUTO_GENERATED, config.certificateSource)
+            assertEquals(ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME, config.certificateHostname)
+        }
+
+        @Test
+        fun `auto-generates bearer token when empty`() = testScope.runTest {
+            val config = repository.getServerConfig()
+
+            assertTrue(config.bearerToken.isNotEmpty())
+        }
+
+        @Test
+        fun `auto-generated bearer token is UUID format`() = testScope.runTest {
+            val config = repository.getServerConfig()
+
+            val uuidPattern = Regex(
+                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
+            )
+            assertTrue(uuidPattern.matches(config.bearerToken))
+        }
+
+        @Test
+        fun `auto-generated bearer token is persisted`() = testScope.runTest {
+            val firstRead = repository.getServerConfig()
+            val secondRead = repository.getServerConfig()
+
+            assertEquals(firstRead.bearerToken, secondRead.bearerToken)
+        }
+    }
+
+    @Nested
+    @DisplayName("updatePort")
+    inner class UpdatePort {
+
+        @Test
+        fun `updates port value`() = testScope.runTest {
+            repository.updatePort(9090)
+            val config = repository.getServerConfig()
+
+            assertEquals(9090, config.port)
+        }
+    }
+
+    @Nested
+    @DisplayName("updateBindingAddress")
+    inner class UpdateBindingAddress {
+
+        @Test
+        fun `updates binding address to NETWORK`() = testScope.runTest {
+            repository.updateBindingAddress(BindingAddress.NETWORK)
+            val config = repository.getServerConfig()
+
+            assertEquals(BindingAddress.NETWORK, config.bindingAddress)
+        }
+
+        @Test
+        fun `updates binding address back to LOCALHOST`() = testScope.runTest {
+            repository.updateBindingAddress(BindingAddress.NETWORK)
+            repository.updateBindingAddress(BindingAddress.LOCALHOST)
+            val config = repository.getServerConfig()
+
+            assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
+        }
+    }
+
+    @Nested
+    @DisplayName("updateBearerToken")
+    inner class UpdateBearerToken {
+
+        @Test
+        fun `updates bearer token`() = testScope.runTest {
+            repository.updateBearerToken("custom-token-123")
+            val config = repository.getServerConfig()
+
+            assertEquals("custom-token-123", config.bearerToken)
+        }
+    }
+
+    @Nested
+    @DisplayName("generateNewBearerToken")
+    inner class GenerateNewBearerToken {
+
+        @Test
+        fun `generates new UUID token`() = testScope.runTest {
+            val token = repository.generateNewBearerToken()
+
+            val uuidPattern = Regex(
+                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
+            )
+            assertTrue(uuidPattern.matches(token))
+        }
+
+        @Test
+        fun `persists the generated token`() = testScope.runTest {
+            val token = repository.generateNewBearerToken()
+            val config = repository.getServerConfig()
+
+            assertEquals(token, config.bearerToken)
+        }
+
+        @Test
+        fun `generates different token each time`() = testScope.runTest {
+            val token1 = repository.generateNewBearerToken()
+            val token2 = repository.generateNewBearerToken()
+
+            assertNotEquals(token1, token2)
+        }
+    }
+
+    @Nested
+    @DisplayName("updateAutoStartOnBoot")
+    inner class UpdateAutoStartOnBoot {
+
+        @Test
+        fun `enables auto start on boot`() = testScope.runTest {
+            repository.updateAutoStartOnBoot(true)
+            val config = repository.getServerConfig()
+
+            assertTrue(config.autoStartOnBoot)
+        }
+
+        @Test
+        fun `disables auto start on boot`() = testScope.runTest {
+            repository.updateAutoStartOnBoot(true)
+            repository.updateAutoStartOnBoot(false)
+            val config = repository.getServerConfig()
+
+            assertFalse(config.autoStartOnBoot)
+        }
+    }
+
+    @Nested
+    @DisplayName("updateCertificateSource")
+    inner class UpdateCertificateSource {
+
+        @Test
+        fun `updates certificate source to CUSTOM`() = testScope.runTest {
+            repository.updateCertificateSource(CertificateSource.CUSTOM)
+            val config = repository.getServerConfig()
+
+            assertEquals(CertificateSource.CUSTOM, config.certificateSource)
+        }
+    }
+
+    @Nested
+    @DisplayName("updateCertificateHostname")
+    inner class UpdateCertificateHostname {
+
+        @Test
+        fun `updates certificate hostname`() = testScope.runTest {
+            repository.updateCertificateHostname("my-device.local")
+            val config = repository.getServerConfig()
+
+            assertEquals("my-device.local", config.certificateHostname)
+        }
+    }
+
+    @Nested
+    @DisplayName("validatePort")
+    inner class ValidatePort {
+
+        @Test
+        fun `valid port returns success`() {
+            assertTrue(repository.validatePort(8080).isSuccess)
+        }
+
+        @Test
+        fun `port 1 is valid`() {
+            assertTrue(repository.validatePort(1).isSuccess)
+        }
+
+        @Test
+        fun `port 65535 is valid`() {
+            assertTrue(repository.validatePort(65535).isSuccess)
+        }
+
+        @Test
+        fun `port 0 is invalid`() {
+            assertTrue(repository.validatePort(0).isFailure)
+        }
+
+        @Test
+        fun `port 65536 is invalid`() {
+            assertTrue(repository.validatePort(65536).isFailure)
+        }
+
+        @Test
+        fun `negative port is invalid`() {
+            assertTrue(repository.validatePort(-1).isFailure)
+        }
+    }
+
+    @Nested
+    @DisplayName("validateCertificateHostname")
+    inner class ValidateCertificateHostname {
+
+        @Test
+        fun `valid hostname returns success`() {
+            assertTrue(repository.validateCertificateHostname("android-mcp.local").isSuccess)
+        }
+
+        @Test
+        fun `single label hostname is valid`() {
+            assertTrue(repository.validateCertificateHostname("localhost").isSuccess)
+        }
+
+        @Test
+        fun `empty hostname is invalid`() {
+            assertTrue(repository.validateCertificateHostname("").isFailure)
+        }
+
+        @Test
+        fun `blank hostname is invalid`() {
+            assertTrue(repository.validateCertificateHostname("   ").isFailure)
+        }
+
+        @Test
+        fun `hostname with spaces is invalid`() {
+            assertTrue(repository.validateCertificateHostname("my host").isFailure)
+        }
+
+        @Test
+        fun `hostname with underscore is invalid`() {
+            assertTrue(repository.validateCertificateHostname("my_host.local").isFailure)
+        }
+    }
+
+    @Nested
+    @DisplayName("serverConfig Flow")
+    inner class ServerConfigFlow {
+
+        @Test
+        fun `emits default config initially`() = testScope.runTest {
+            repository.serverConfig.test {
+                val config = awaitItem()
+                assertEquals(ServerConfig.DEFAULT_PORT, config.port)
+                assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
+                cancelAndIgnoreRemainingEvents()
+            }
+        }
+
+        @Test
+        fun `emits updated config after port change`() = testScope.runTest {
+            repository.serverConfig.test {
+                awaitItem() // initial emission
+
+                repository.updatePort(9090)
+                val updated = awaitItem()
+                assertEquals(9090, updated.port)
+
+                cancelAndIgnoreRemainingEvents()
+            }
+        }
+
+        @Test
+        fun `emits updated config after binding address change`() = testScope.runTest {
+            repository.serverConfig.test {
+                awaitItem() // initial emission
+
+                repository.updateBindingAddress(BindingAddress.NETWORK)
+                val updated = awaitItem()
+                assertEquals(BindingAddress.NETWORK, updated.bindingAddress)
+
+                cancelAndIgnoreRemainingEvents()
+            }
+        }
+
+        @Test
+        fun `Flow does not auto-generate bearer token when empty`() = testScope.runTest {
+            // The Flow should NOT have side effects — it simply maps preferences.
+            // Auto-generation only happens via getServerConfig().
+            repository.serverConfig.test {
+                val config = awaitItem()
+                assertTrue(config.bearerToken.isEmpty())
+
+                cancelAndIgnoreRemainingEvents()
+            }
+        }
+
+        @Test
+        fun `Flow reflects token after getServerConfig auto-generates it`() = testScope.runTest {
+            // getServerConfig() triggers auto-generation and persists it
+            val generated = repository.getServerConfig()
+            assertTrue(generated.bearerToken.isNotEmpty())
+
+            // Flow should now emit the persisted token
+            repository.serverConfig.test {
+                val config = awaitItem()
+                assertEquals(generated.bearerToken, config.bearerToken)
+
+                cancelAndIgnoreRemainingEvents()
+            }
+        }
+    }
+}
```

---

#### Action 2.7.4: Create `NetworkUtilsTest.kt`

**What**: Create unit tests for `NetworkUtils`.

**Context**: `getDeviceIpAddress()` requires a real `ConnectivityManager` which cannot be easily unit tested without Robolectric or an instrumented test, so it is tested only at the integration level (noted for later). `isPortAvailable()` is tested by binding a port first and then checking availability. `getNetworkInterfaces()` is tested by verifying the loopback interface is always present. The `NetworkInterfaceInfo` data class equality is also tested.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtilsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtilsTest.kt
@@ -0,0 +1,70 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import java.net.ServerSocket
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("NetworkUtils")
+class NetworkUtilsTest {
+
+    @Nested
+    @DisplayName("isPortAvailable")
+    inner class IsPortAvailable {
+
+        @Test
+        fun `returns true for available port`() {
+            // Arrange: find a free port by letting the system assign one
+            val freePort = ServerSocket(0).use { it.localPort }
+
+            // Act & Assert: port should be available after we closed it
+            assertTrue(NetworkUtils.isPortAvailable(freePort))
+        }
+
+        @Test
+        fun `returns false for port in use`() {
+            // Arrange: bind a port
+            val server = ServerSocket(0)
+            val boundPort = server.localPort
+
+            // Act & Assert: port should not be available
+            try {
+                assertFalse(NetworkUtils.isPortAvailable(boundPort))
+            } finally {
+                server.close()
+            }
+        }
+    }
+
+    @Nested
+    @DisplayName("getNetworkInterfaces")
+    inner class GetNetworkInterfaces {
+
+        @Test
+        fun `returns at least loopback interface`() {
+            val interfaces = NetworkUtils.getNetworkInterfaces()
+
+            assertTrue(interfaces.any { it.isLoopback })
+        }
+
+        @Test
+        fun `loopback interface has 127 0 0 1 address`() {
+            val interfaces = NetworkUtils.getNetworkInterfaces()
+            val loopback = interfaces.first { it.isLoopback }
+
+            assertEquals("127.0.0.1", loopback.address)
+        }
+    }
+
+    @Nested
+    @DisplayName("NetworkInterfaceInfo")
+    inner class NetworkInterfaceInfoTest {
+
+        @Test
+        fun `data class equality works`() {
+            val a = NetworkInterfaceInfo(name = "lo", address = "127.0.0.1", isLoopback = true)
+            val b = NetworkInterfaceInfo(name = "lo", address = "127.0.0.1", isLoopback = true)
+
+            assertEquals(a, b)
+        }
+    }
+}
```

---

#### Action 2.7.5: Create `PermissionUtilsTest.kt`

**What**: Create unit tests for `PermissionUtils`.

**Context**: `isAccessibilityServiceEnabled()` is tested by mocking the `ContentResolver` and `Settings.Secure` values. Since `Settings.Secure.getString()` is a static method on Android, we mock the `Context.contentResolver` and use MockK's `mockkStatic` to intercept `Settings.Secure.getString()`. `openAccessibilitySettings()` is tested by verifying the correct Intent is constructed. `isNotificationPermissionGranted()` is tested with MockK intercepting `ContextCompat.checkSelfPermission()`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtilsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtilsTest.kt
@@ -0,0 +1,94 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import android.content.ContentResolver
+import android.content.Context
+import android.content.pm.PackageManager
+import android.provider.Settings
+import androidx.core.content.ContextCompat
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkStatic
+import io.mockk.unmockkStatic
+import io.mockk.verify
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("PermissionUtils")
+class PermissionUtilsTest {
+
+    private val mockContext: Context = mockk(relaxed = true)
+    private val mockContentResolver: ContentResolver = mockk(relaxed = true)
+
+    @BeforeEach
+    fun setUp() {
+        every { mockContext.contentResolver } returns mockContentResolver
+        every { mockContext.packageName } returns "com.danielealbano.androidremotecontrolmcp"
+        mockkStatic(Settings.Secure::class)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkStatic(Settings.Secure::class)
+    }
+
+    @Nested
+    @DisplayName("isAccessibilityServiceEnabled")
+    inner class IsAccessibilityServiceEnabled {
+
+        @Test
+        fun `returns true when service is in enabled list`() {
+            val serviceName = "com.danielealbano.androidremotecontrolmcp/" +
+                "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
+            every {
+                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
+            } returns serviceName
+
+            assertTrue(
+                PermissionUtils.isAccessibilityServiceEnabled(
+                    mockContext,
+                    Class.forName(
+                        "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService",
+                    ),
+                ),
+            )
+        }
+
+        @Test
+        fun `returns false when enabled services is null`() {
+            every {
+                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
+            } returns null
+
+            assertFalse(
+                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
+            )
+        }
+
+        @Test
+        fun `returns false when service is not in enabled list`() {
+            every {
+                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
+            } returns "com.other.package/com.other.Service"
+
+            assertFalse(
+                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
+            )
+        }
+    }
+
+    @Nested
+    @DisplayName("openAccessibilitySettings")
+    inner class OpenAccessibilitySettings {
+
+        @Test
+        fun `starts activity with accessibility settings intent`() {
+            PermissionUtils.openAccessibilitySettings(mockContext)
+
+            verify { mockContext.startActivity(any()) }
+        }
+    }
+
+    @Nested
+    @DisplayName("isNotificationPermissionGranted")
+    inner class IsNotificationPermissionGranted {
+
+        @Test
+        fun `returns true on API below 33`() {
+            // On API < 33, notification permission is always granted.
+            // In unit test environment, Build.VERSION.SDK_INT is 0, which is < 33.
+            assertTrue(PermissionUtils.isNotificationPermissionGranted(mockContext))
+        }
+    }
+}
```

---

#### Action 2.7.6: Create `LoggerTest.kt`

**What**: Create unit tests for the `Logger` utility class.

**Context**: The sanitization logic is the primary testable behavior. We test that UUID strings in log messages are replaced with `[REDACTED]`, that non-UUID strings are left untouched, and that messages with multiple UUIDs are fully sanitized. Log level filtering (debug-only in release) depends on `BuildConfig.DEBUG` which is always `true` in unit tests (debug build variant), so we test the `sanitize` function directly via its `internal` visibility. The actual `Log.d/i/w/e` calls are Android framework methods that cannot be tested in unit tests without Robolectric -- they are verified implicitly by the project building and running.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/LoggerTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/LoggerTest.kt
@@ -0,0 +1,57 @@
+package com.danielealbano.androidremotecontrolmcp.utils
+
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("Logger")
+class LoggerTest {
+
+    @Nested
+    @DisplayName("sanitize")
+    inner class Sanitize {
+
+        @Test
+        fun `replaces UUID with REDACTED`() {
+            val message = "Token: 550e8400-e29b-41d4-a716-446655440000"
+
+            val result = Logger.sanitize(message)
+
+            assertEquals("Token: [REDACTED]", result)
+        }
+
+        @Test
+        fun `replaces multiple UUIDs`() {
+            val message = "Old: 550e8400-e29b-41d4-a716-446655440000, " +
+                "New: 6ba7b810-9dad-11d1-80b4-00c04fd430c8"
+
+            val result = Logger.sanitize(message)
+
+            assertEquals("Old: [REDACTED], New: [REDACTED]", result)
+        }
+
+        @Test
+        fun `leaves non-UUID strings untouched`() {
+            val message = "Server started on port 8080"
+
+            val result = Logger.sanitize(message)
+
+            assertEquals("Server started on port 8080", result)
+        }
+
+        @Test
+        fun `handles empty string`() {
+            val result = Logger.sanitize("")
+
+            assertEquals("", result)
+        }
+
+        @Test
+        fun `leaves partial UUID untouched`() {
+            val message = "Value: 550e8400-e29b-41d4"
+
+            val result = Logger.sanitize(message)
+
+            assertEquals("Value: 550e8400-e29b-41d4", result)
+        }
+
+        @Test
+        fun `replaces uppercase UUID`() {
+            val message = "Token: 550E8400-E29B-41D4-A716-446655440000"
+
+            val result = Logger.sanitize(message)
+
+            assertEquals("Token: [REDACTED]", result)
+        }
+    }
+}
```

---

### Task 2.8: Verification and Commit

**Description**: Run all verification commands to ensure the data layer and utilities are correct, then create the commits.

**Acceptance Criteria**:
- [ ] `make lint` succeeds (no ktlint or detekt violations)
- [ ] `make test-unit` succeeds (all unit tests pass)
- [ ] `make build` succeeds (debug APK generated without errors or warnings)
- [ ] All 4-5 commits are created on the `feat/data-layer-utilities` branch

**Tests**: These are the verification steps.

#### Action 2.8.1: Run `make lint`

**What**: Verify no linting errors in new or modified files.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make lint
```

**Expected outcome**: BUILD SUCCESSFUL with no ktlint or detekt violations.

**If it fails**: Run `make lint-fix` for auto-fixable issues, then re-run `make lint`. For non-auto-fixable issues, manually fix the code.

---

#### Action 2.8.2: Run `make test-unit`

**What**: Verify all unit tests pass.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make test-unit
```

**Expected outcome**: All tests pass. Look for the `BUILD SUCCESSFUL` message and verify the test count matches the expected number of tests (approximately 40+ tests across 6 test files).

**If it fails**: Inspect the failure output. Common issues:
- MockK static mocking not working: ensure `mockkStatic` is used correctly
- DataStore test file conflicts: ensure `@TempDir` provides unique directories
- Flow timing issues: ensure Turbine `test {}` blocks properly await emissions
- Android framework class mocking: ensure classes like `Settings.Secure` are properly mocked

---

#### Action 2.8.3: Run `make build`

**What**: Verify the project compiles with all new files.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make build
```

**Expected outcome**: BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.

---

#### Action 2.8.4: Create the feature branch and commits

**What**: Create the `feat/data-layer-utilities` branch and make all commits as defined in the commit strategy.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Create and switch to feature branch
git checkout -b feat/data-layer-utilities

# Commit 1: Data models
git add \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BindingAddress.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/CertificateSource.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatus.kt
git commit -m "$(cat <<'EOF'
feat: add data models for server configuration and status

Create BindingAddress enum (LOCALHOST/NETWORK), CertificateSource enum
(AUTO_GENERATED/CUSTOM), ServerConfig data class with all configuration
fields and PROJECT.md defaults, and ServerStatus sealed class with
lifecycle states (Stopped, Starting, Running, Stopping, Error).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 2: Settings repository
git add \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt
git commit -m "$(cat <<'EOF'
feat: add settings repository with DataStore persistence

Create SettingsRepository interface and SettingsRepositoryImpl backed by
Preferences DataStore. Includes port/hostname validation, bearer token
auto-generation (UUID), and Flow-based config observation. Update
AppModule to provide DataStore singleton and bind repository.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 3: Utility classes
git add \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtils.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtils.kt \
  app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/Logger.kt
git commit -m "$(cat <<'EOF'
feat: add utility classes for network, permissions, and logging

Create NetworkUtils (IP address, port availability, interface listing),
PermissionUtils (accessibility service check, settings navigation,
notification permission), and Logger (Android Log wrapper with UUID
sanitization and build-type-aware filtering).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 4: Unit tests
git add \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerStatusTest.kt \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/NetworkUtilsTest.kt \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtilsTest.kt \
  app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/LoggerTest.kt
git commit -m "$(cat <<'EOF'
test: add unit tests for data layer and utilities

Comprehensive tests for ServerConfig defaults and copy behavior,
ServerStatus sealed subtypes, SettingsRepositoryImpl CRUD and validation
(using real DataStore with TempDir and Turbine for Flow testing),
NetworkUtils port/interface checks, PermissionUtils with mocked Android
APIs, and Logger UUID sanitization.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

#### Action 2.8.5: Push and create PR

**What**: Push the branch and create the pull request.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Push feature branch
git push -u origin feat/data-layer-utilities

# Create PR
gh pr create --base main --title "Plan 2: Data layer, settings repository, and utilities" --body "$(cat <<'EOF'
## Summary

- Add data models: `BindingAddress`, `CertificateSource`, `ServerConfig`, `ServerStatus`
- Add `SettingsRepository` interface and `SettingsRepositoryImpl` with Preferences DataStore persistence
- Add Hilt DI bindings for DataStore and repository
- Add utility classes: `NetworkUtils`, `PermissionUtils`, `Logger`
- Add comprehensive unit tests for all new code

## Plan Reference

Implementation of Plan 2: Data Layer & Utilities from `docs/plans/2_data-layer-utilities_20260211163214.md`.

## Test plan

- [ ] `make lint` passes (no ktlint/detekt violations)
- [ ] `make test-unit` passes (all ~40 tests green)
- [ ] `make build` succeeds (debug APK generated)
- [ ] Settings repository correctly persists and retrieves all config values
- [ ] Port validation rejects values outside 1-65535
- [ ] Hostname validation rejects empty/invalid hostnames
- [ ] Bearer token auto-generated on first read
- [ ] Logger sanitizes UUID strings in messages

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Performance, Security, and QA Review

### Performance

- **DataStore access**: DataStore is accessed via `Flow` which is lazy and non-blocking. The `getServerConfig()` one-shot read uses `first()` which is efficient. No performance concerns for settings access patterns.
- **Bearer token generation**: `UUID.randomUUID()` uses `SecureRandom` internally which is appropriate for token generation. Called only on first launch or explicit regeneration, not on every request.
- **Network interface enumeration**: `getNetworkInterfaces()` iterates all system interfaces. This is a lightweight system call, but should not be called in tight loops. Suitable for UI display on settings screen load.
- **Port availability check**: `isPortAvailable()` opens and immediately closes a `ServerSocket`. This is fast but could theoretically race with another process. Acceptable for pre-start validation.
- **Logger sanitization**: Regex replacement on every log message adds negligible overhead. The UUID pattern is compiled once (static field) and reused.

### Security

- **Bearer token storage**: Stored in Preferences DataStore (unencrypted). Per PROJECT.md section "Bearer Token Security", this is the current approach. For enhanced security, migration to `EncryptedSharedPreferences` or `EncryptedFile` could be considered in a future plan, but is not required now.
- **Bearer token sanitization**: The `Logger` class automatically redacts UUID-format strings from all log messages, preventing accidental token exposure in logcat output.
- **Hostname validation**: The regex pattern enforces RFC 1035 compliant hostnames, preventing injection of malicious values into certificate generation.
- **Port validation**: Strict range enforcement (1-65535) prevents invalid port usage.
- **No secrets in code**: All sensitive values (bearer token) are generated at runtime and stored in DataStore. No hardcoded credentials.

### QA

- **Test coverage**: Every public method in the data layer and utilities has at least one unit test. Validation methods have both positive and negative test cases. The `ServerConfig` test verifies all defaults match PROJECT.md specification.
- **DataStore test isolation**: Each test gets a fresh DataStore file via JUnit 5 `@TempDir`, preventing test interference.
- **Flow testing**: Turbine library is used for deterministic Flow testing in `SettingsRepositoryImplTest`, avoiding flaky timing-dependent assertions.
- **Mock boundaries**: Android framework classes (`Settings.Secure`, `Context`, `ContentResolver`) are mocked using MockK. Pure Kotlin logic (validation, sanitization) is tested without mocks.
- **Build verification**: The plan explicitly includes `make lint`, `make test-unit`, and `make build` as verification steps before committing.
- **Edge cases tested**: Port boundaries (0, 1, 65535, 65536, -1), empty/blank hostnames, underscores in hostnames, partial UUIDs, multiple UUIDs in a single message, uppercase UUIDs.

---

## File Inventory

All files created or modified in this plan:

| # | File Path | Task | Action |
|---|-----------|------|--------|
| 1 | `app/src/main/kotlin/.../data/model/BindingAddress.kt` | 2.1 | Create |
| 2 | `app/src/main/kotlin/.../data/model/CertificateSource.kt` | 2.1 | Create |
| 3 | `app/src/main/kotlin/.../data/model/ServerConfig.kt` | 2.2 | Create |
| 4 | `app/src/main/kotlin/.../data/model/ServerStatus.kt` | 2.3 | Create |
| 5 | `app/src/main/kotlin/.../data/repository/SettingsRepository.kt` | 2.4 | Create |
| 6 | `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt` | 2.4 | Create |
| 7 | `app/src/main/kotlin/.../di/AppModule.kt` | 2.5 | Modify |
| 8 | `app/src/main/kotlin/.../utils/NetworkUtils.kt` | 2.6 | Create |
| 9 | `app/src/main/kotlin/.../utils/PermissionUtils.kt` | 2.6 | Create |
| 10 | `app/src/main/kotlin/.../utils/Logger.kt` | 2.6 | Create |
| 11 | `app/src/test/kotlin/.../data/model/ServerConfigTest.kt` | 2.7 | Create |
| 12 | `app/src/test/kotlin/.../data/model/ServerStatusTest.kt` | 2.7 | Create |
| 13 | `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt` | 2.7 | Create |
| 14 | `app/src/test/kotlin/.../utils/NetworkUtilsTest.kt` | 2.7 | Create |
| 15 | `app/src/test/kotlin/.../utils/PermissionUtilsTest.kt` | 2.7 | Create |
| 16 | `app/src/test/kotlin/.../utils/LoggerTest.kt` | 2.7 | Create |

**Total**: 16 files (15 new, 1 modified)

(`...` = `com/danielealbano/androidremotecontrolmcp`)

---

## Dependency Graph

This plan has no dependencies on plans after it. All subsequent plans depend on this plan:

- **Plan 3 (UI Layer)**: Uses `ServerConfig`, `ServerStatus`, `SettingsRepository`, `PermissionUtils`, `Logger`
- **Plan 4 (AccessibilityService)**: Uses `Logger`, `PermissionUtils`
- **Plan 5 (ScreenCaptureService)**: Uses `Logger`, `PermissionUtils`
- **Plan 6 (McpServerService)**: Uses `ServerConfig`, `SettingsRepository`, `NetworkUtils`, `Logger`
- **Plans 7-9 (MCP Tools)**: Uses `ServerConfig`, `Logger`
- **Plan 10 (E2E Tests)**: Uses all data layer components indirectly

---

**End of Plan 2**
