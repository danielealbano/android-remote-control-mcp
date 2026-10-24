package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.coroutines.flow.Flow

/**
 * Repository for accessing and persisting MCP server settings.
 *
 * This is the single access point for all application settings.
 * All DataStore access MUST go through this interface. UI, ViewModels,
 * and Services must not access DataStore directly.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository :
    EventChannelSettings,
    ArcTunnelSettings,
    DownloadSettings {
    /**
     * Observes the current server configuration. Emits a new [ServerConfig]
     * whenever any setting changes.
     */
    val serverConfig: Flow<ServerConfig>

    /**
     * Returns the current server configuration as a one-shot read.
     * Runs [ensureAuthModelMigrated] first, so the one-time bearer-enabled migration and the
     * bearer-token auto-generation have applied before the auth model is read.
     */
    suspend fun getServerConfig(): ServerConfig

    /**
     * Updates the server port.
     *
     * @param port The new port value. Must pass [validatePort] first.
     */
    suspend fun updatePort(port: Int)

    /** Updates the network binding address. */
    suspend fun updateBindingAddress(bindingAddress: BindingAddress)

    /**
     * Updates the bearer token used for MCP request authentication.
     * Passing an empty string clears the value. Whether bearer authentication is enforced is controlled
     * by [updateBearerTokenEnabled], not by the value: clearing the token while bearer is enabled makes
     * `/mcp` fail closed (401) until a token is set or bearer is disabled.
     *
     * @param token The new bearer token value (empty string clears the value).
     */
    suspend fun updateBearerToken(token: String)

    /**
     * Generates a new random bearer token (UUID), persists it, and returns
     * the generated value.
     *
     * @return The newly generated bearer token.
     */
    suspend fun generateNewBearerToken(): String

    /** Enables or disables the self-contained OAuth 2.1 authorization server. */
    suspend fun updateOauthEnabled(enabled: Boolean)

    /**
     * Enables or disables static bearer-token authentication.
     *
     * Enabling with an empty stored value auto-generates a token; disabling preserves the stored value
     * (re-enabling restores it).
     */
    suspend fun updateBearerTokenEnabled(enabled: Boolean)

    /** Updates the optional public-URL override (empty = auto-detect from the request). */
    suspend fun updatePublicUrlOverride(url: String)

    /**
     * Validates a public-URL override.
     *
     * UNLIKE [validateEndpointUrl] (which rejects blank), an empty value IS valid here and means
     * auto-detect → [Result.success] with `""`; otherwise the same http/https protocol check applies.
     *
     * This is a pure validation function with no I/O; it is intentionally non-suspending.
     *
     * @return [Result.success] with the validated URL (possibly empty), or [Result.failure].
     */
    fun validatePublicUrlOverride(url: String): Result<String>

    /**
     * Runs the one-time bearer-enabled migration AND the bearer-token auto-generation.
     *
     * Idempotent; MUST be invoked before the auth model is consumed by the server or the UI so a
     * previously-cleared-token user is not regressed into a token-required state.
     *
     * Also runs the one-time tunnel-provider migration (an install that ever wrote a tunnel setting without
     * choosing a provider keeps Cloudflare).
     */
    suspend fun ensureAuthModelMigrated()

    /** Returns a stable base64url HS256 signing secret, generated once (SecureRandom) on first read. */
    suspend fun getOrCreateJwtSigningSecret(): String

    /** Updates the auto-start-on-boot preference. */
    suspend fun updateAutoStartOnBoot(enabled: Boolean)

    /** Updates the hide-from-recents preference. */
    suspend fun updateHideFromRecents(enabled: Boolean)

    /** Enables or disables the on-device MCP tool-call activity indicator. */
    suspend fun updateToolCallIndicatorEnabled(enabled: Boolean)

    /** Persists a validated screen-awake idle grace; zero disables it. */
    suspend fun updateScreenAwakeTimeout(seconds: Int)

    /**
     * Observes the persisted server-running intent flag (`true` after ACTION_START, `false` after
     * ACTION_STOP). Used by restart triggers to decide whether to bring the MCP server back up.
     */
    val serverRunning: Flow<Boolean>

    /** Persists the server-running intent flag. */
    suspend fun updateServerRunning(running: Boolean)

    /** Updates the HTTPS enabled toggle. */
    suspend fun updateHttpsEnabled(enabled: Boolean)

    /** Updates the HTTPS certificate source. */
    suspend fun updateCertificateSource(source: CertificateSource)

    /**
     * Updates the hostname used for auto-generated HTTPS certificates.
     *
     * @param hostname The new hostname. Must pass [validateCertificateHostname] first.
     */
    suspend fun updateCertificateHostname(hostname: String)

    /**
     * Validates a port number.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated port, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validatePort(port: Int): Result<Int>

    /**
     * Validates a certificate hostname.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated hostname, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateCertificateHostname(hostname: String): Result<String>

    /** Updates the tunnel enabled toggle. */
    suspend fun updateTunnelEnabled(enabled: Boolean)

    /** Updates the tunnel provider type. */
    suspend fun updateTunnelProvider(provider: TunnelProviderType)

    /** Updates the ngrok authtoken. */
    suspend fun updateNgrokAuthtoken(authtoken: String)

    /** Updates the ngrok domain (optional, empty string means auto-assigned). */
    suspend fun updateNgrokDomain(domain: String)

    /** Updates the Cloudflare tunnel mode (Free quick tunnel vs token-based named tunnel). */
    suspend fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode)

    /** Updates the Cloudflare tunnel token (required when using token mode). */
    suspend fun updateCloudflareTunnelToken(token: String)

    /** Updates the optional extra command-line arguments for Cloudflare tunnel. */
    suspend fun updateCloudflareTunnelExtraArgs(extraArgs: String)

    /** Updates the file size limit for file operations (in MB). */
    suspend fun updateFileSizeLimit(limitMb: Int)

    /**
     * Validates a file size limit value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated limit, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateFileSizeLimit(limitMb: Int): Result<Int>

    /** Updates the download timeout in seconds. */
    suspend fun updateDownloadTimeout(seconds: Int)

    /**
     * Updates the device slug used for tool name prefix.
     *
     * @param slug The new device slug. Must pass [validateDeviceSlug] first.
     */
    suspend fun updateDeviceSlug(slug: String)

    /**
     * Validates a download timeout value.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated timeout, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDownloadTimeout(seconds: Int): Result<Int>

    /** Validates zero (disabled) or the configured nonzero screen-awake timeout range. */
    fun validateScreenAwakeTimeout(seconds: Int): Result<Int>

    /**
     * Validates a device slug string.
     *
     * Valid slugs contain only letters (a-z, A-Z), digits (0-9), and underscores.
     * Maximum length is [ServerConfig.MAX_DEVICE_SLUG_LENGTH] characters. Empty is valid.
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated slug, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateDeviceSlug(slug: String): Result<String>

    /** Updates the full tool permissions configuration. */
    suspend fun updateToolPermissionsConfig(config: ToolPermissionsConfig)

    /** Enables or disables a specific tool. */
    suspend fun updateToolEnabled(
        toolName: String,
        enabled: Boolean,
    )

    /** Enables or disables a specific parameter for a tool. */
    suspend fun updateParamEnabled(
        toolName: String,
        paramName: String,
        enabled: Boolean,
    )

    /** Replaces the full Privacy Mode configuration. */
    suspend fun updatePrivacyModeConfig(config: PrivacyModeConfig)

    /** Enables or disables Privacy Mode. */
    suspend fun updatePrivacyModeEnabled(enabled: Boolean)

    /** Enables or disables a specific PII category within Privacy Mode. */
    suspend fun updatePrivacyCategoryEnabled(
        category: PiiCategory,
        enabled: Boolean,
    )

    /** Sets the redaction mode (pseudonymize vs full redaction). */
    suspend fun updatePrivacyRedactionMode(mode: RedactionMode)

    /** Sets the pseudonym placeholder format (hashed vs numbered). */
    suspend fun updatePrivacyPlaceholderFormat(format: PlaceholderFormat)

    /** Persists the measured per-100-node inference overhead estimate, in seconds. */
    suspend fun updatePrivacyBenchmarkEstimateSeconds(seconds: Double)

    /** Emits the stored Privacy Mode benchmark estimate (seconds), or null if never measured. */
    val privacyBenchmarkEstimateSeconds: Flow<Double?>

    /** Persists whether the home-screen Privacy Mode callout card was dismissed. */
    suspend fun updatePrivacyModeCardDismissed(dismissed: Boolean)

    /** Emits whether the home-screen Privacy Mode callout card was dismissed (default false). */
    val privacyModeCardDismissed: Flow<Boolean>

    /** Emits whether automatic GitHub update checks are enabled (default true). */
    val autoUpdateCheckEnabled: Flow<Boolean>

    /** Enables or disables automatic GitHub update checks. */
    suspend fun updateAutoUpdateCheckEnabled(enabled: Boolean)

    /**
     * Emits the currently-known available update (a release newer than the installed build), or null
     * when none is known. Drives the in-app "update available" banner.
     */
    val availableUpdate: Flow<AvailableUpdate?>

    /** Persists the detected available update, or clears it when [update] is null. */
    suspend fun setAvailableUpdate(update: AvailableUpdate?)

    /** Returns the version for which an update notification was last posted (empty if none). Used for de-dup. */
    suspend fun getNotifiedUpdateVersion(): String

    /** Records the version for which an update notification has now been posted. */
    suspend fun setNotifiedUpdateVersion(version: String)

    /** Returns the epoch-millis of the last automatic update check, or 0 if none has run. */
    suspend fun getLastAutoCheckAtMillis(): Long

    /** Records the epoch-millis of the most recent automatic update check (for on-open throttling). */
    suspend fun setLastAutoCheckAtMillis(millis: Long)

    /**
     * Data class representing a stored storage location record.
     * This is the persistence format; the full [StorageLocation] includes
     * dynamic fields like [StorageLocation.availableBytes].
     *
     * @property id Unique identifier: "{authority}/{documentId}".
     * @property name Display name of the directory.
     * @property path Human-readable path within the provider.
     * @property description User-provided description.
     * @property treeUri The granted persistent tree URI string.
     * @property allowWrite Whether write operations are allowed for this location.
     * @property allowDelete Whether delete operations are allowed for this location.
     */
    data class StoredLocation(
        val id: String,
        val name: String,
        val path: String,
        val description: String,
        val treeUri: String,
        val allowWrite: Boolean = false,
        val allowDelete: Boolean = false,
    )

    /**
     * Returns all stored storage locations.
     */
    suspend fun getStoredLocations(): List<StoredLocation>

    /**
     * Adds a storage location.
     */
    suspend fun addStoredLocation(location: StoredLocation)

    /**
     * Removes a storage location by ID.
     */
    suspend fun removeStoredLocation(locationId: String)

    /**
     * Updates the description of an existing storage location.
     *
     * @param locationId The storage location identifier.
     * @param description The new description.
     */
    suspend fun updateLocationDescription(
        locationId: String,
        description: String,
    )

    /**
     * Updates whether write operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowWrite Whether write operations are allowed.
     */
    suspend fun updateLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    )

    /**
     * Updates whether delete operations are allowed for a storage location.
     *
     * @param locationId The storage location identifier.
     * @param allowDelete Whether delete operations are allowed.
     */
    suspend fun updateLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    )

    /** Returns permission overrides for all built-in locations. */
    suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions>

    /** Updates the allowWrite flag for a built-in location. */
    suspend fun updateBuiltinLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    )

    /** Updates the allowDelete flag for a built-in location. */
    suspend fun updateBuiltinLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    )
}
