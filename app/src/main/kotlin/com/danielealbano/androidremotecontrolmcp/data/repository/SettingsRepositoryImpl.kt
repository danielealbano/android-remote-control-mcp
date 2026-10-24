package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URL
import java.util.UUID
import javax.inject.Inject

// Preferences keys and shared constants for SettingsRepositoryImpl (file-private; referenced unqualified).
private const val TAG = "MCP:SettingsRepo"
private const val MAX_LOC_ID_LEN = 200
private const val JWT_SECRET_BYTES = 32
private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")

private fun sanitizeLocationId(locationId: String) = locationId.take(MAX_LOC_ID_LEN).replace(CONTROL_CHAR_REGEX, "")

private val PORT_KEY = intPreferencesKey("port")
private val BINDING_ADDRESS_KEY = stringPreferencesKey("binding_address")
private val BEARER_TOKEN_KEY = stringPreferencesKey("bearer_token")
private val BEARER_TOKEN_INITIALIZED_KEY = booleanPreferencesKey("bearer_token_initialized")
private val OAUTH_ENABLED_KEY = booleanPreferencesKey("oauth_enabled")
private val BEARER_TOKEN_ENABLED_KEY = booleanPreferencesKey("bearer_token_enabled")
private val BEARER_TOKEN_ENABLED_INITIALIZED_KEY =
    booleanPreferencesKey("bearer_token_enabled_initialized")
private val PUBLIC_URL_OVERRIDE_KEY = stringPreferencesKey("public_url_override")
private val JWT_SIGNING_SECRET_KEY = stringPreferencesKey("jwt_signing_secret")
private val AUTO_START_KEY = booleanPreferencesKey("auto_start_on_boot")
private val HIDE_FROM_RECENTS_KEY = booleanPreferencesKey("hide_from_recents")
private val TOOL_CALL_INDICATOR_ENABLED_KEY = booleanPreferencesKey("tool_call_indicator_enabled")
private val SCREEN_AWAKE_TIMEOUT_KEY = intPreferencesKey("screen_awake_timeout_seconds")
private val SERVER_RUNNING_KEY = booleanPreferencesKey("server_running")
private val HTTPS_ENABLED_KEY = booleanPreferencesKey("https_enabled")
private val CERTIFICATE_SOURCE_KEY = stringPreferencesKey("certificate_source")
private val CERTIFICATE_HOSTNAME_KEY = stringPreferencesKey("certificate_hostname")
private val TUNNEL_ENABLED_KEY = booleanPreferencesKey("tunnel_enabled")
private val TUNNEL_PROVIDER_KEY = stringPreferencesKey("tunnel_provider")
private val NGROK_AUTHTOKEN_KEY = stringPreferencesKey("ngrok_authtoken")
private val NGROK_DOMAIN_KEY = stringPreferencesKey("ngrok_domain")
private val CLOUDFLARE_TUNNEL_MODE_KEY = stringPreferencesKey("cloudflare_tunnel_mode")
private val CLOUDFLARE_TUNNEL_TOKEN_KEY = stringPreferencesKey("cloudflare_tunnel_token")
private val CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY = stringPreferencesKey("cloudflare_tunnel_extra_args")
private val TUNNEL_PROVIDER_MIGRATED_KEY = booleanPreferencesKey("tunnel_provider_migrated")
private val FILE_SIZE_LIMIT_KEY = intPreferencesKey("file_size_limit_mb")
private val DOWNLOAD_TIMEOUT_KEY = intPreferencesKey("download_timeout_seconds")
private val DEVICE_SLUG_KEY = stringPreferencesKey("device_slug")
private val TOOL_PERMISSIONS_KEY = stringPreferencesKey("tool_permissions")
private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")
private val BUILTIN_LOCATION_PERMISSIONS_KEY = stringPreferencesKey("builtin_location_permissions")
private val EVENT_CHANNEL_CONFIG_KEY = stringPreferencesKey("event_channel_config")
private val PRIVACY_MODE_CONFIG_KEY = stringPreferencesKey("privacy_mode_config")
private val PRIVACY_BENCHMARK_SECONDS_KEY = stringPreferencesKey("privacy_benchmark_estimate_seconds")
private val PRIVACY_CARD_DISMISSED_KEY = booleanPreferencesKey("privacy_mode_card_home_dismissed")
private val AUTO_UPDATE_CHECK_ENABLED_KEY = booleanPreferencesKey("auto_update_check_enabled")
private val AVAILABLE_UPDATE_VERSION_KEY = stringPreferencesKey("available_update_version")
private val AVAILABLE_UPDATE_URL_KEY = stringPreferencesKey("available_update_url")
private val NOTIFIED_UPDATE_VERSION_KEY = stringPreferencesKey("notified_update_version")
private val LAST_AUTO_CHECK_AT_KEY = longPreferencesKey("last_auto_update_check_at_millis")

/**
 * Regex pattern for valid hostnames.
 *
 * Allows labels of letters, digits, and hyphens separated by dots.
 * Each label must start and end with an alphanumeric character.
 * Maximum total length is 253 characters per RFC 1035.
 */
private val HOSTNAME_PATTERN =
    Regex(
        "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*" +
            "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
    )

// Non-override helpers extracted to top-level (file-private) to keep the class within detekt LargeClass.

/**
 * Maps raw [Preferences] to a [ServerConfig] instance, applying defaults
 * for any missing keys.
 */
@Suppress("CyclomaticComplexMethod")
private fun mapPreferencesToServerConfig(prefs: Preferences): ServerConfig {
    val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
    val certificateSourceName = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name

    val tunnelProviderName = prefs[TUNNEL_PROVIDER_KEY] ?: TunnelProviderType.ARC_TUNNEL.name
    val cloudflareTunnelModeName =
        prefs[CLOUDFLARE_TUNNEL_MODE_KEY] ?: CloudflareTunnelMode.FREE.name

    return ServerConfig(
        port = prefs[PORT_KEY] ?: ServerConfig.DEFAULT_PORT,
        bindingAddress =
            BindingAddress.entries.firstOrNull { it.name == bindingAddressName }
                ?: BindingAddress.LOCALHOST,
        bearerToken = prefs[BEARER_TOKEN_KEY] ?: "",
        autoStartOnBoot = prefs[AUTO_START_KEY] ?: false,
        toolCallIndicatorEnabled = prefs[TOOL_CALL_INDICATOR_ENABLED_KEY] ?: true,
        screenAwakeTimeoutSeconds =
            runCatching { prefs[SCREEN_AWAKE_TIMEOUT_KEY] }
                .getOrNull()
                ?.takeIf(ServerConfig::isValidScreenAwakeTimeout)
                ?: ServerConfig.DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS,
        httpsEnabled = prefs[HTTPS_ENABLED_KEY] ?: false,
        certificateSource =
            CertificateSource.entries.firstOrNull { it.name == certificateSourceName }
                ?: CertificateSource.AUTO_GENERATED,
        certificateHostname =
            prefs[CERTIFICATE_HOSTNAME_KEY]
                ?: ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME,
        tunnelEnabled = prefs[TUNNEL_ENABLED_KEY] ?: false,
        tunnelProvider =
            TunnelProviderType.entries.firstOrNull { it.name == tunnelProviderName }
                ?: TunnelProviderType.ARC_TUNNEL,
        ngrokAuthtoken = prefs[NGROK_AUTHTOKEN_KEY] ?: "",
        ngrokDomain = prefs[NGROK_DOMAIN_KEY] ?: "",
        cloudflareTunnelMode =
            CloudflareTunnelMode.entries.firstOrNull { it.name == cloudflareTunnelModeName }
                ?: CloudflareTunnelMode.FREE,
        cloudflareTunnelToken = prefs[CLOUDFLARE_TUNNEL_TOKEN_KEY] ?: "",
        cloudflareTunnelExtraArgs = prefs[CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY] ?: "",
        arcTunnelBaseAddress = prefs[ARC_TUNNEL_BASE_ADDRESS_KEY] ?: ServerConfig.DEFAULT_ARC_TUNNEL_BASE_ADDRESS,
        arcTunnelCtCheckEnabled = prefs[ARC_TUNNEL_CT_CHECK_ENABLED_KEY] ?: true,
        arcTunnelEdgeOverride = prefs[ARC_TUNNEL_EDGE_OVERRIDE_KEY] ?: "",
        arcTunnelTrustedCaPem = prefs[ARC_TUNNEL_TRUSTED_CA_PEM_KEY] ?: "",
        fileSizeLimitMb = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
        allowHttpDownloads = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false,
        allowUnverifiedHttpsCerts = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false,
        blockLocalNetworkDownloads = prefs[BLOCK_LOCAL_NETWORK_DOWNLOADS_KEY] ?: false,
        downloadTimeoutSeconds =
            prefs[DOWNLOAD_TIMEOUT_KEY]
                ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
        deviceSlug = prefs[DEVICE_SLUG_KEY] ?: "",
        oauthEnabled = prefs[OAUTH_ENABLED_KEY] ?: true,
        bearerTokenEnabled = prefs[BEARER_TOKEN_ENABLED_KEY] ?: true,
        publicUrlOverride = prefs[PUBLIC_URL_OVERRIDE_KEY] ?: "",
        toolPermissionsConfig = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY]),
        privacyModeConfig = PrivacyModeConfig.fromJsonOrDefault(prefs[PRIVACY_MODE_CONFIG_KEY]),
        hideFromRecents = prefs[HIDE_FROM_RECENTS_KEY] ?: false,
    )
}

/**
 * Generates a random UUID string for use as a bearer token.
 */
private fun generateTokenString(): String = UUID.randomUUID().toString()

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

private fun getBuiltinLocationPermissionsInternal(prefs: Preferences): Map<String, BuiltinPermissions> {
    val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
    return parseBuiltinPermissionsJson(json)
}

private fun logToolPermissionsDiff(
    logger: SettingsChangeLogger,
    old: ToolPermissionsConfig,
    new: ToolPermissionsConfig,
) {
    (new.disabledTools - old.disabledTools).forEach { tool ->
        logger.submit("tool:$tool", "enabled", "disabled") { _, _ -> "Tool '$tool' disabled" }
    }
    (old.disabledTools - new.disabledTools).forEach { tool ->
        logger.submit("tool:$tool", "disabled", "enabled") { _, _ -> "Tool '$tool' enabled" }
    }
    (new.disabledParams.keys + old.disabledParams.keys).forEach { tool ->
        val oldParams = old.disabledParams[tool].orEmpty()
        val newParams = new.disabledParams[tool].orEmpty()
        (newParams - oldParams).forEach { param ->
            logger.submit("param:$tool:$param", "enabled", "disabled") { _, _ ->
                "Parameter '$param' of tool '$tool' disabled"
            }
        }
        (oldParams - newParams).forEach { param ->
            logger.submit("param:$tool:$param", "disabled", "enabled") { _, _ ->
                "Parameter '$param' of tool '$tool' enabled"
            }
        }
    }
}

private fun logPrivacyModeDiff(
    logger: SettingsChangeLogger,
    old: PrivacyModeConfig,
    new: PrivacyModeConfig,
) {
    if (old.enabled != new.enabled) {
        logger.submit("privacy_enabled", old.enabled.toString(), new.enabled.toString()) { _, n ->
            "Privacy mode ${if (n.toBoolean()) "enabled" else "disabled"}"
        }
    }
    (new.disabledCategories - old.disabledCategories).forEach { category ->
        logger.submit("privacy_category:${category.name}", "enabled", "disabled") { _, _ ->
            "Privacy category '${category.name}' disabled"
        }
    }
    (old.disabledCategories - new.disabledCategories).forEach { category ->
        logger.submit("privacy_category:${category.name}", "disabled", "enabled") { _, _ ->
            "Privacy category '${category.name}' enabled"
        }
    }
    if (old.redactionMode != new.redactionMode) {
        logger.submit("privacy_redaction_mode", old.redactionMode.name, new.redactionMode.name) { o, n ->
            "Privacy redaction mode changed $o → $n"
        }
    }
    if (old.placeholderFormat != new.placeholderFormat) {
        logger.submit("privacy_placeholder_format", old.placeholderFormat.name, new.placeholderFormat.name) { o, n ->
            "Privacy placeholder format changed $o → $n"
        }
    }
}

/**
 * [SettingsRepository] implementation backed by Preferences DataStore.
 *
 * This is the single access point for all persisted settings in the
 * application. No other class should access DataStore directly.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 */
@Suppress("TooManyFunctions")
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
        eventChannelSettings: EventChannelSettings,
        arcTunnelSettings: ArcTunnelSettings,
        downloadSettings: DownloadSettings,
    ) : SettingsRepository,
        EventChannelSettings by eventChannelSettings,
        ArcTunnelSettings by arcTunnelSettings,
        DownloadSettings by downloadSettings {
        override val serverConfig: Flow<ServerConfig> =
            dataStore.data.map { prefs ->
                mapPreferencesToServerConfig(prefs)
            }

        override suspend fun getServerConfig(): ServerConfig {
            ensureAuthModelMigrated()
            return mapPreferencesToServerConfig(dataStore.data.first())
        }

        override suspend fun ensureAuthModelMigrated() {
            dataStore.edit { prefs ->
                // One-time bearer-enabled migration (idempotent; guarded).
                if (prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] != true) {
                    val wasInitialized = prefs[BEARER_TOKEN_INITIALIZED_KEY] == true
                    val hadToken = !prefs[BEARER_TOKEN_KEY].isNullOrEmpty()
                    prefs[BEARER_TOKEN_ENABLED_KEY] = if (wasInitialized) hadToken else true
                    prefs[BEARER_TOKEN_ENABLED_INITIALIZED_KEY] = true
                }
                // Bearer-token auto-generation: only when bearer is enabled and empty.
                if (prefs[BEARER_TOKEN_INITIALIZED_KEY] != true) {
                    if (prefs[BEARER_TOKEN_ENABLED_KEY] == true && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
                        prefs[BEARER_TOKEN_KEY] = generateTokenString()
                    }
                    prefs[BEARER_TOKEN_INITIALIZED_KEY] = true
                }
                migrateTunnelProvider(prefs)
            }
        }

        /** Reads the previous scalar value, persists [newValue], and submits a coalesced change entry. */
        private suspend fun <T : Any> logScalarChange(
            key: Preferences.Key<T>,
            coalesceKey: String,
            newValue: T,
            default: T,
            render: (old: String, new: String) -> String,
        ) {
            dataStore.edit { prefs ->
                val old = prefs[key] ?: default
                prefs[key] = newValue
                settingsChangeLogger.submit(coalesceKey, old.toString(), newValue.toString(), render)
            }
        }

        private fun logToggle(
            key: String,
            oldValue: Boolean,
            newValue: Boolean,
            subject: String,
            onWord: String = "enabled",
            offWord: String = "disabled",
        ) {
            settingsChangeLogger.submitToggle(key, oldValue, newValue, subject, onWord, offWord)
        }

        override suspend fun updateOauthEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[OAUTH_ENABLED_KEY] ?: true
                prefs[OAUTH_ENABLED_KEY] = enabled
                logToggle("oauth_enabled", old, enabled, "OAuth")
            }
        }

        override suspend fun updateBearerTokenEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[BEARER_TOKEN_ENABLED_KEY] ?: true
                prefs[BEARER_TOKEN_ENABLED_KEY] = enabled
                logToggle("bearer_token_enabled", old, enabled, "Bearer token auth")
                if (enabled && prefs[BEARER_TOKEN_KEY].isNullOrEmpty()) {
                    val generated = generateTokenString()
                    prefs[BEARER_TOKEN_KEY] = generated
                    settingsChangeLogger.submit("bearer_token", "", generated) { _, _ -> "Bearer token changed" }
                }
            }
        }

        override suspend fun updatePublicUrlOverride(url: String) =
            logScalarChange(PUBLIC_URL_OVERRIDE_KEY, "public_url_override", url, "") { o, n ->
                "Public URL override changed ${o.ifEmpty { "(none)" }} → ${n.ifEmpty { "(none)" }}"
            }

        override fun validatePublicUrlOverride(url: String): Result<String> {
            if (url.isBlank()) {
                return Result.success("")
            }
            return try {
                val parsed = URL(url)
                if (parsed.protocol != "http" && parsed.protocol != "https") {
                    Result.failure(IllegalArgumentException("URL must use http or https protocol"))
                } else {
                    Result.success(url)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(IllegalArgumentException("Invalid URL format: ${e.message}"))
            }
        }

        override suspend fun getOrCreateJwtSigningSecret(): String {
            dataStore.data
                .first()[JWT_SIGNING_SECRET_KEY]
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            dataStore.edit { prefs ->
                if (prefs[JWT_SIGNING_SECRET_KEY].isNullOrEmpty()) {
                    val raw = ByteArray(JWT_SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) }
                    prefs[JWT_SIGNING_SECRET_KEY] =
                        java.util.Base64
                            .getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(raw)
                }
            }
            // read-AFTER-edit: key is guaranteed present, so !! is always safe
            return dataStore.data.first()[JWT_SIGNING_SECRET_KEY]!!
        }

        override suspend fun updatePort(port: Int) =
            logScalarChange(PORT_KEY, "port", port, ServerConfig.DEFAULT_PORT) { o, n -> "Port changed $o → $n" }

        override suspend fun updateBindingAddress(bindingAddress: BindingAddress) =
            logScalarChange(
                BINDING_ADDRESS_KEY,
                "binding_address",
                bindingAddress.name,
                BindingAddress.LOCALHOST.name,
            ) { o, n ->
                "Binding address changed $o → $n"
            }

        override suspend fun updateBearerToken(token: String) =
            logScalarChange(BEARER_TOKEN_KEY, "bearer_token", token, "") { _, _ -> "Bearer token changed" }

        override suspend fun generateNewBearerToken(): String {
            val token = generateTokenString()
            updateBearerToken(token)
            return token
        }

        override suspend fun updateAutoStartOnBoot(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[AUTO_START_KEY] ?: false
                prefs[AUTO_START_KEY] = enabled
                logToggle("auto_start", old, enabled, "Auto-start on boot")
            }
        }

        override suspend fun updateHideFromRecents(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[HIDE_FROM_RECENTS_KEY] ?: false
                prefs[HIDE_FROM_RECENTS_KEY] = enabled
                logToggle("hide_from_recents", old, enabled, "Hide from recents")
            }
        }

        override suspend fun updateToolCallIndicatorEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[TOOL_CALL_INDICATOR_ENABLED_KEY] ?: true
                prefs[TOOL_CALL_INDICATOR_ENABLED_KEY] = enabled
                logToggle("tool_call_indicator", old, enabled, "Tool-call indicator")
            }
        }

        override val serverRunning: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[SERVER_RUNNING_KEY] ?: false }

        override suspend fun updateServerRunning(running: Boolean) {
            dataStore.edit { prefs -> prefs[SERVER_RUNNING_KEY] = running }
        }

        override suspend fun updateHttpsEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[HTTPS_ENABLED_KEY] ?: false
                prefs[HTTPS_ENABLED_KEY] = enabled
                logToggle("https_enabled", old, enabled, "HTTPS")
            }
        }

        override suspend fun updateCertificateSource(source: CertificateSource) =
            logScalarChange(
                CERTIFICATE_SOURCE_KEY,
                "certificate_source",
                source.name,
                CertificateSource.AUTO_GENERATED.name,
            ) { o, n ->
                "Certificate source changed $o → $n"
            }

        override suspend fun updateCertificateHostname(hostname: String) =
            logScalarChange(
                CERTIFICATE_HOSTNAME_KEY,
                "certificate_hostname",
                hostname,
                ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME,
            ) { o, n ->
                "Certificate hostname changed $o → $n"
            }

        override suspend fun updateTunnelEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[TUNNEL_ENABLED_KEY] ?: false
                prefs[TUNNEL_ENABLED_KEY] = enabled
                logToggle("tunnel_enabled", old, enabled, "Remote access tunnel")
            }
        }

        override suspend fun updateTunnelProvider(provider: TunnelProviderType) =
            logScalarChange(
                TUNNEL_PROVIDER_KEY,
                "tunnel_provider",
                provider.name,
                TunnelProviderType.ARC_TUNNEL.name,
            ) { o, n ->
                "Tunnel provider changed $o → $n"
            }

        override suspend fun updateNgrokAuthtoken(authtoken: String) =
            logScalarChange(NGROK_AUTHTOKEN_KEY, "ngrok_authtoken", authtoken, "") { _, _ -> "ngrok authtoken changed" }

        override suspend fun updateNgrokDomain(domain: String) =
            logScalarChange(NGROK_DOMAIN_KEY, "ngrok_domain", domain, "") { o, n -> "ngrok domain changed $o → $n" }

        override suspend fun updateCloudflareTunnelMode(mode: CloudflareTunnelMode) =
            logScalarChange(
                CLOUDFLARE_TUNNEL_MODE_KEY,
                "cloudflare_tunnel_mode",
                mode.name,
                CloudflareTunnelMode.FREE.name,
            ) { o, n ->
                "Cloudflare tunnel mode changed $o → $n"
            }

        override suspend fun updateCloudflareTunnelToken(token: String) =
            logScalarChange(CLOUDFLARE_TUNNEL_TOKEN_KEY, "cloudflare_tunnel_token", token, "") { _, _ ->
                "Cloudflare tunnel token changed"
            }

        override suspend fun updateCloudflareTunnelExtraArgs(extraArgs: String) =
            logScalarChange(
                CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY,
                "cloudflare_tunnel_extra_args",
                extraArgs,
                "",
            ) { o, n ->
                "Cloudflare tunnel extra arguments changed $o → $n"
            }

        override suspend fun updateFileSizeLimit(limitMb: Int) =
            logScalarChange(
                FILE_SIZE_LIMIT_KEY,
                "file_size_limit",
                limitMb,
                ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
            ) { o, n ->
                "File size limit changed $o → $n MB"
            }

        override fun validateFileSizeLimit(limitMb: Int): Result<Int> =
            if (limitMb in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
                Result.success(limitMb)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "File size limit must be between ${ServerConfig.MIN_FILE_SIZE_LIMIT_MB} and " +
                            "${ServerConfig.MAX_FILE_SIZE_LIMIT_MB} MB",
                    ),
                )
            }

        override suspend fun updateDownloadTimeout(seconds: Int) =
            logScalarChange(
                DOWNLOAD_TIMEOUT_KEY,
                "download_timeout",
                seconds,
                ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
            ) { o, n ->
                "Download timeout changed $o → $n s"
            }

        override suspend fun updateScreenAwakeTimeout(seconds: Int) {
            validateScreenAwakeTimeout(seconds).getOrThrow()
            logScalarChange(
                SCREEN_AWAKE_TIMEOUT_KEY,
                "screen_awake_timeout_seconds",
                seconds,
                ServerConfig.DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS,
            ) { old, new -> "Screen-awake timeout changed $old to $new s" }
        }

        override fun validateScreenAwakeTimeout(seconds: Int): Result<Int> = validateScreenAwakeTimeoutValue(seconds)

        override suspend fun updateDeviceSlug(slug: String) =
            logScalarChange(DEVICE_SLUG_KEY, "device_slug", slug, "") { o, n -> "Device slug changed $o → $n" }

        override suspend fun updateToolPermissionsConfig(config: ToolPermissionsConfig) {
            dataStore.edit { prefs ->
                val old = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                prefs[TOOL_PERMISSIONS_KEY] = config.toJson()
                logToolPermissionsDiff(settingsChangeLogger, old, config)
            }
        }

        override suspend fun updateToolEnabled(
            toolName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val updated =
                    if (enabled) {
                        current.copy(disabledTools = current.disabledTools - toolName)
                    } else {
                        current.copy(disabledTools = current.disabledTools + toolName)
                    }
                prefs[TOOL_PERMISSIONS_KEY] = updated.toJson()
                logToolPermissionsDiff(settingsChangeLogger, current, updated)
            }
        }

        override suspend fun updateParamEnabled(
            toolName: String,
            paramName: String,
            enabled: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = ToolPermissionsConfig.fromJsonOrDefault(prefs[TOOL_PERMISSIONS_KEY])
                val currentParams = current.disabledParams[toolName] ?: emptySet()
                val newParams = if (enabled) currentParams - paramName else currentParams + paramName
                val newDisabledParams =
                    if (newParams.isEmpty()) {
                        current.disabledParams - toolName
                    } else {
                        current.disabledParams + (toolName to newParams)
                    }
                val updated = current.copy(disabledParams = newDisabledParams)
                prefs[TOOL_PERMISSIONS_KEY] = updated.toJson()
                logToolPermissionsDiff(settingsChangeLogger, current, updated)
            }
        }

        private suspend fun editPrivacyConfig(transform: (PrivacyModeConfig) -> PrivacyModeConfig) {
            dataStore.edit { prefs ->
                val current = PrivacyModeConfig.fromJsonOrDefault(prefs[PRIVACY_MODE_CONFIG_KEY])
                val updated = transform(current)
                prefs[PRIVACY_MODE_CONFIG_KEY] = updated.toJson()
                logPrivacyModeDiff(settingsChangeLogger, current, updated)
            }
        }

        override suspend fun updatePrivacyModeConfig(config: PrivacyModeConfig) = editPrivacyConfig { config }

        override suspend fun updatePrivacyModeEnabled(enabled: Boolean) {
            editPrivacyConfig { it.copy(enabled = enabled) }
        }

        override suspend fun updatePrivacyCategoryEnabled(
            category: PiiCategory,
            enabled: Boolean,
        ) = editPrivacyConfig {
            it.copy(
                disabledCategories =
                    if (enabled) it.disabledCategories - category else it.disabledCategories + category,
            )
        }

        override suspend fun updatePrivacyRedactionMode(mode: RedactionMode) {
            editPrivacyConfig { it.copy(redactionMode = mode) }
        }

        override suspend fun updatePrivacyPlaceholderFormat(format: PlaceholderFormat) =
            editPrivacyConfig { it.copy(placeholderFormat = format) }

        override suspend fun updatePrivacyBenchmarkEstimateSeconds(seconds: Double) {
            dataStore.edit { prefs -> prefs[PRIVACY_BENCHMARK_SECONDS_KEY] = seconds.toString() }
        }

        override val privacyBenchmarkEstimateSeconds: Flow<Double?> =
            dataStore.data.map { prefs -> prefs[PRIVACY_BENCHMARK_SECONDS_KEY]?.toDoubleOrNull() }

        override suspend fun updatePrivacyModeCardDismissed(dismissed: Boolean) {
            dataStore.edit { prefs -> prefs[PRIVACY_CARD_DISMISSED_KEY] = dismissed }
        }

        override val privacyModeCardDismissed: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[PRIVACY_CARD_DISMISSED_KEY] ?: false }

        override val autoUpdateCheckEnabled: Flow<Boolean> =
            dataStore.data.map { prefs -> prefs[AUTO_UPDATE_CHECK_ENABLED_KEY] ?: true }

        override suspend fun updateAutoUpdateCheckEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[AUTO_UPDATE_CHECK_ENABLED_KEY] = enabled }
        }

        override val availableUpdate: Flow<AvailableUpdate?> =
            dataStore.data.map { prefs ->
                val version = prefs[AVAILABLE_UPDATE_VERSION_KEY]
                val url = prefs[AVAILABLE_UPDATE_URL_KEY]
                if (!version.isNullOrEmpty() && !url.isNullOrEmpty()) {
                    AvailableUpdate(version, url)
                } else {
                    null
                }
            }

        override suspend fun setAvailableUpdate(update: AvailableUpdate?) {
            if (update == null) {
                // Skip the write entirely when nothing is stored — avoids a redundant DataStore commit
                // (and a spurious dataStore.data emission) on every up-to-date check.
                val current = dataStore.data.first()
                if (!current.contains(AVAILABLE_UPDATE_VERSION_KEY) && !current.contains(AVAILABLE_UPDATE_URL_KEY)) {
                    return
                }
            }
            dataStore.edit { prefs ->
                if (update == null) {
                    prefs.remove(AVAILABLE_UPDATE_VERSION_KEY)
                    prefs.remove(AVAILABLE_UPDATE_URL_KEY)
                } else {
                    prefs[AVAILABLE_UPDATE_VERSION_KEY] = update.versionName
                    prefs[AVAILABLE_UPDATE_URL_KEY] = update.releaseUrl
                }
            }
        }

        override suspend fun getNotifiedUpdateVersion(): String {
            val prefs = dataStore.data.first()
            return prefs[NOTIFIED_UPDATE_VERSION_KEY] ?: ""
        }

        override suspend fun setNotifiedUpdateVersion(version: String) {
            dataStore.edit { prefs -> prefs[NOTIFIED_UPDATE_VERSION_KEY] = version }
        }

        override suspend fun getLastAutoCheckAtMillis(): Long {
            val prefs = dataStore.data.first()
            return prefs[LAST_AUTO_CHECK_AT_KEY] ?: 0L
        }

        override suspend fun setLastAutoCheckAtMillis(millis: Long) {
            dataStore.edit { prefs -> prefs[LAST_AUTO_CHECK_AT_KEY] = millis }
        }

        override fun validateDownloadTimeout(seconds: Int): Result<Int> = validateDownloadTimeoutValue(seconds)

        @Suppress("ReturnCount")
        override fun validateDeviceSlug(slug: String): Result<String> {
            if (slug.length > ServerConfig.MAX_DEVICE_SLUG_LENGTH) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug must be at most ${ServerConfig.MAX_DEVICE_SLUG_LENGTH} characters",
                    ),
                )
            }
            if (!ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Device slug can only contain letters, digits, and underscores",
                    ),
                )
            }
            return Result.success(slug)
        }

        override suspend fun getStoredLocations(): List<SettingsRepository.StoredLocation> {
            val prefs = dataStore.data.first()
            val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyList()
            return parseStoredLocationsJson(jsonString)
        }

        override suspend fun addStoredLocation(location: SettingsRepository.StoredLocation) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                existing.add(location)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                settingsChangeLogger.submit("storage_add:${location.id}", "absent", "present") { _, _ ->
                    "Storage location added: ${location.description}"
                }
            }
        }

        override suspend fun removeStoredLocation(locationId: String) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val removed = existing.firstOrNull { it.id == locationId }
                existing.removeAll { it.id == locationId }
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                if (removed != null) {
                    settingsChangeLogger.submit("storage_remove:$locationId", "present", "absent") { _, _ ->
                        "Storage location removed: ${removed.description}"
                    }
                }
            }
        }

        override suspend fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val oldDescription = existing[index].description
                    existing[index] = existing[index].copy(description = description)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit("storage_desc:$locationId", oldDescription, description) { o, n ->
                        "Storage location renamed $o → $n"
                    }
                } else {
                    Log.w(TAG, "updateLocationDescription: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val description = existing[index].description
                    val old = existing[index].allowWrite
                    existing[index] = existing[index].copy(allowWrite = allowWrite)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit(
                        "storage_write:$locationId",
                        old.toString(),
                        allowWrite.toString(),
                    ) { _, n ->
                        "Storage location '$description' write access ${if (n.toBoolean()) "allowed" else "revoked"}"
                    }
                } else {
                    Log.w(TAG, "updateLocationAllowWrite: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
                val index = existing.indexOfFirst { it.id == locationId }
                if (index >= 0) {
                    val description = existing[index].description
                    val old = existing[index].allowDelete
                    existing[index] = existing[index].copy(allowDelete = allowDelete)
                    prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
                    settingsChangeLogger.submit(
                        "storage_delete:$locationId",
                        old.toString(),
                        allowDelete.toString(),
                    ) { _, n ->
                        "Storage location '$description' delete access ${if (n.toBoolean()) "allowed" else "revoked"}"
                    }
                } else {
                    Log.w(TAG, "updateLocationAllowDelete: location ${sanitizeLocationId(locationId)} not found, no-op")
                }
            }
        }

        override suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions> =
            getBuiltinLocationPermissionsInternal(dataStore.data.first())

        override suspend fun updateBuiltinLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowWrite = allowWrite))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
                settingsChangeLogger.submit(
                    "storage_write:$locationId",
                    existing.allowWrite.toString(),
                    allowWrite.toString(),
                ) { _, n ->
                    "Storage location '$locationId' write access ${if (n.toBoolean()) "allowed" else "revoked"}"
                }
            }
        }

        override suspend fun updateBuiltinLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = getBuiltinLocationPermissionsInternal(prefs)
                val existing = current[locationId] ?: BuiltinPermissions()
                val updated = current + (locationId to existing.copy(allowDelete = allowDelete))
                prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
                settingsChangeLogger.submit(
                    "storage_delete:$locationId",
                    existing.allowDelete.toString(),
                    allowDelete.toString(),
                ) { _, n ->
                    "Storage location '$locationId' delete access ${if (n.toBoolean()) "allowed" else "revoked"}"
                }
            }
        }

        override fun validatePort(port: Int): Result<Int> =
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                Result.success(port)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}",
                    ),
                )
            }

        @Suppress("ReturnCount")
        override fun validateCertificateHostname(hostname: String): Result<String> {
            if (hostname.isBlank()) {
                return Result.failure(
                    IllegalArgumentException("Certificate hostname must not be empty"),
                )
            }

            if (!HOSTNAME_PATTERN.matches(hostname)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Certificate hostname contains invalid characters. " +
                            "Use only letters, digits, hyphens, and dots.",
                    ),
                )
            }

            return Result.success(hostname)
        }
    }

private fun validateScreenAwakeTimeoutValue(seconds: Int): Result<Int> =
    if (ServerConfig.isValidScreenAwakeTimeout(seconds)) {
        Result.success(seconds)
    } else {
        Result.failure(
            IllegalArgumentException(
                "Screen-awake timeout must be 0 (disabled) or between " +
                    "${ServerConfig.MIN_SCREEN_AWAKE_TIMEOUT_SECONDS} and " +
                    "${ServerConfig.MAX_SCREEN_AWAKE_TIMEOUT_SECONDS} seconds",
            ),
        )
    }

private fun validateDownloadTimeoutValue(seconds: Int): Result<Int> =
    if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
        Result.success(seconds)
    } else {
        Result.failure(
            IllegalArgumentException(
                "Download timeout must be between ${ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS} and " +
                    "${ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS} seconds",
            ),
        )
    }
