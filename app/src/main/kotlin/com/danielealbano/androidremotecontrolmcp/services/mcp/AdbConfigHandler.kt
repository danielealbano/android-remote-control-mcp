package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts
import com.danielealbano.androidremotecontrolmcp.utils.RecentsUtils

/**
 * Handles ADB configuration broadcast intents by parsing extras and
 * applying them to [SettingsRepository].
 *
 * Extracted from [AdbConfigReceiver] to allow unit testing without
 * Hilt's [dagger.hilt.android.AndroidEntryPoint] injection lifecycle.
 */
@Suppress("TooManyFunctions")
class AdbConfigHandler(
    private val settingsRepository: SettingsRepository,
    private val storageLocationProvider: StorageLocationProvider,
    private val serverLogRepository: ServerLogRepository,
    private val arcTunnelEnrollment: ArcTunnelEnrollment,
) {
    /**
     * Dispatches the intent to the appropriate handler based on its action.
     */
    suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            AdbConfigReceiver.ACTION_CONFIGURE -> handleConfigure(context, intent)
            AdbConfigReceiver.ACTION_START_SERVER -> handleStartServer(context)
            AdbConfigReceiver.ACTION_STOP_SERVER -> handleStopServer(context)
            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    @Suppress("LongMethod")
    private suspend fun handleConfigure(
        context: Context,
        intent: Intent,
    ) {
        // The one-time migrations run before this broadcast's writes, so a fresh install configured over
        // ADB keeps the new default tunnel provider.
        settingsRepository.ensureAuthModelMigrated()
        Log.i(TAG, "Received ADB configuration broadcast")
        serverLogRepository.log(ServerLogEntry.Type.SETTINGS, "Configuration update received via ADB")

        applyBearerToken(intent)
        applyOauthEnabled(intent)
        applyBearerTokenEnabled(intent)
        applyPublicUrlOverride(intent)
        applyBindingAddress(intent)
        applyPort(intent)
        applyAutoStartOnBoot(intent)
        applyHideFromRecents(context, intent)
        applyHttpsEnabled(intent)
        applyCertificateSource(intent)
        applyCertificateHostname(intent)
        applyTunnelEnabled(intent)
        applyTunnelProvider(intent)
        applyCloudflareTunnelMode(intent)
        applyCloudflareTunnelToken(intent)
        applyCloudflareTunnelExtraArgs(intent)
        applyNgrokAuthtoken(intent)
        applyNgrokDomain(intent)
        applyArcTunnelBaseAddress(intent)
        applyArcTunnelCtCheckEnabled(intent)
        applyFileSizeLimit(intent)
        applyAllowHttpDownloads(intent)
        applyAllowUnverifiedHttpsCerts(intent)
        applyBlockLocalNetworkDownloads(intent)
        applyDownloadTimeout(intent)
        applyScreenAwakeTimeout(intent)
        applyDeviceSlug(intent)
        applyToolPermissions(intent)
        applyStorageLocationPermissions(intent)
        applyArcTunnelEnroll(intent)

        Log.i(TAG, "ADB configuration applied successfully")
    }

    private suspend fun applyBearerToken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_BEARER_TOKEN) ?: return
        settingsRepository.updateBearerToken(value)
        if (value.isEmpty()) {
            Log.w(
                TAG,
                "Bearer token cleared; while bearer_token_enabled=true this makes /mcp fail closed (401) " +
                    "until a token is set or bearer is disabled",
            )
        } else {
            Log.i(TAG, "Bearer token updated (length=${value.length})")
        }
    }

    private suspend fun applyOauthEnabled(intent: Intent) {
        if (!intent.hasExtra(EXTRA_OAUTH_ENABLED)) return
        val value = intent.getBooleanExtra(EXTRA_OAUTH_ENABLED, false)
        settingsRepository.updateOauthEnabled(value)
        Log.i(TAG, "OAuth enabled updated to $value")
    }

    private suspend fun applyBearerTokenEnabled(intent: Intent) {
        if (!intent.hasExtra(EXTRA_BEARER_TOKEN_ENABLED)) return
        val value = intent.getBooleanExtra(EXTRA_BEARER_TOKEN_ENABLED, false)
        settingsRepository.updateBearerTokenEnabled(value)
        if (value) {
            Log.i(TAG, "Bearer token authentication enabled")
        } else {
            Log.w(
                TAG,
                "Bearer token authentication disabled; if OAuth is also disabled the server is UNAUTHENTICATED",
            )
        }
    }

    private suspend fun applyPublicUrlOverride(intent: Intent) {
        if (!intent.hasExtra(EXTRA_PUBLIC_URL_OVERRIDE)) return
        val value = intent.getStringExtra(EXTRA_PUBLIC_URL_OVERRIDE) ?: ""
        settingsRepository.validatePublicUrlOverride(value).fold(
            onSuccess = {
                settingsRepository.updatePublicUrlOverride(it)
                if (it.isEmpty()) {
                    Log.i(TAG, "Public URL override cleared (auto-detect from request)")
                } else {
                    Log.i(TAG, "Public URL override updated")
                }
            },
            onFailure = { Log.w(TAG, "Ignoring invalid public_url_override: ${it.message}") },
        )
    }

    private suspend fun applyBindingAddress(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_BINDING_ADDRESS) ?: return
        val address =
            when (value) {
                BindingAddress.NETWORK.address -> {
                    BindingAddress.NETWORK
                }

                BindingAddress.LOCALHOST.address -> {
                    BindingAddress.LOCALHOST
                }

                else -> {
                    Log.w(
                        TAG,
                        "Ignoring unrecognized binding_address '$value' " +
                            "(valid: ${BindingAddress.LOCALHOST.address}, " +
                            "${BindingAddress.NETWORK.address})",
                    )
                    return
                }
            }
        settingsRepository.updateBindingAddress(address)
        Log.i(TAG, "Binding address updated to $address")
    }

    private suspend fun applyPort(intent: Intent) {
        if (!intent.hasExtra(EXTRA_PORT)) return
        val value = intent.getIntExtra(EXTRA_PORT, -1)
        settingsRepository.validatePort(value).fold(
            onSuccess = {
                settingsRepository.updatePort(it)
                Log.i(TAG, "Port updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid port $value: ${it.message}") },
        )
    }

    private suspend fun applyAutoStartOnBoot(intent: Intent) {
        if (!intent.hasExtra(EXTRA_AUTO_START_ON_BOOT)) return
        val value = intent.getBooleanExtra(EXTRA_AUTO_START_ON_BOOT, false)
        settingsRepository.updateAutoStartOnBoot(value)
        Log.i(TAG, "Auto-start on boot updated to $value")
    }

    private suspend fun applyHideFromRecents(
        context: Context,
        intent: Intent,
    ) {
        if (!intent.hasExtra(EXTRA_HIDE_FROM_RECENTS)) return
        val value = intent.getBooleanExtra(EXTRA_HIDE_FROM_RECENTS, false)
        settingsRepository.updateHideFromRecents(value)
        RecentsUtils.setExcludeFromRecents(context, value)
        Log.i(TAG, "Hide from recents updated to $value")
    }

    private suspend fun applyHttpsEnabled(intent: Intent) {
        if (!intent.hasExtra(EXTRA_HTTPS_ENABLED)) return
        val value = intent.getBooleanExtra(EXTRA_HTTPS_ENABLED, false)
        settingsRepository.updateHttpsEnabled(value)
        Log.i(TAG, "HTTPS enabled updated to $value")
    }

    private suspend fun applyCertificateSource(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_CERTIFICATE_SOURCE) ?: return
        val source =
            try {
                CertificateSource.valueOf(value)
            } catch (_: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "Ignoring invalid certificate_source '$value' " +
                        "(valid: ${CertificateSource.entries.joinToString()})",
                )
                return
            }
        settingsRepository.updateCertificateSource(source)
        Log.i(TAG, "Certificate source updated to $source")
    }

    private suspend fun applyCertificateHostname(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_CERTIFICATE_HOSTNAME) ?: return
        settingsRepository.validateCertificateHostname(value).fold(
            onSuccess = {
                settingsRepository.updateCertificateHostname(it)
                Log.i(TAG, "Certificate hostname updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid certificate_hostname '$value': ${it.message}") },
        )
    }

    private suspend fun applyTunnelEnabled(intent: Intent) {
        if (!intent.hasExtra(EXTRA_TUNNEL_ENABLED)) return
        val value = intent.getBooleanExtra(EXTRA_TUNNEL_ENABLED, false)
        settingsRepository.updateTunnelEnabled(value)
        Log.i(TAG, "Tunnel enabled updated to $value")
    }

    private suspend fun applyTunnelProvider(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_TUNNEL_PROVIDER) ?: return
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
    }

    private suspend fun applyCloudflareTunnelMode(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_CLOUDFLARE_TUNNEL_MODE) ?: return
        val mode =
            try {
                CloudflareTunnelMode.valueOf(value)
            } catch (_: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "Ignoring invalid cloudflare_tunnel_mode '$value' " +
                        "(valid: ${CloudflareTunnelMode.entries.joinToString()})",
                )
                return
            }
        settingsRepository.updateCloudflareTunnelMode(mode)
        Log.i(TAG, "Cloudflare tunnel mode updated to $mode")
    }

    private suspend fun applyCloudflareTunnelToken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_CLOUDFLARE_TUNNEL_TOKEN) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty cloudflare_tunnel_token")
            return
        }
        settingsRepository.updateCloudflareTunnelToken(value)
        Log.i(TAG, "Cloudflare tunnel token updated (length=${value.length})")
    }

    private suspend fun applyCloudflareTunnelExtraArgs(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_CLOUDFLARE_TUNNEL_EXTRA_ARGS) ?: return
        settingsRepository.updateCloudflareTunnelExtraArgs(value)
        Log.i(TAG, "Cloudflare tunnel extra arguments updated (length=${value.length})")
    }

    private suspend fun applyNgrokAuthtoken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_NGROK_AUTHTOKEN) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty ngrok_authtoken")
            return
        }
        settingsRepository.updateNgrokAuthtoken(value)
        Log.i(TAG, "ngrok authtoken updated (length=${value.length})")
    }

    private suspend fun applyNgrokDomain(intent: Intent) {
        if (!intent.hasExtra(EXTRA_NGROK_DOMAIN)) return
        val value = intent.getStringExtra(EXTRA_NGROK_DOMAIN) ?: ""
        settingsRepository.updateNgrokDomain(value)
        Log.i(TAG, "ngrok domain updated to '$value'")
    }

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

    private suspend fun applyFileSizeLimit(intent: Intent) {
        if (!intent.hasExtra(EXTRA_FILE_SIZE_LIMIT_MB)) return
        val value = intent.getIntExtra(EXTRA_FILE_SIZE_LIMIT_MB, -1)
        settingsRepository.validateFileSizeLimit(value).fold(
            onSuccess = {
                settingsRepository.updateFileSizeLimit(it)
                Log.i(TAG, "File size limit updated to ${it}MB")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid file_size_limit_mb $value: ${it.message}") },
        )
    }

    private suspend fun applyAllowHttpDownloads(intent: Intent) {
        if (!intent.hasExtra(EXTRA_ALLOW_HTTP_DOWNLOADS)) return
        val value = intent.getBooleanExtra(EXTRA_ALLOW_HTTP_DOWNLOADS, false)
        settingsRepository.updateAllowHttpDownloads(value)
        Log.i(TAG, "Allow HTTP downloads updated to $value")
    }

    private suspend fun applyAllowUnverifiedHttpsCerts(intent: Intent) {
        if (!intent.hasExtra(EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS)) return
        val value = intent.getBooleanExtra(EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS, false)
        settingsRepository.updateAllowUnverifiedHttpsCerts(value)
        Log.i(TAG, "Allow unverified HTTPS certs updated to $value")
    }

    private suspend fun applyBlockLocalNetworkDownloads(intent: Intent) {
        if (!intent.hasExtra(EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS)) return
        val value = intent.getBooleanExtra(EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS, false)
        settingsRepository.updateBlockLocalNetworkDownloads(value)
        Log.i(TAG, "Block local network downloads updated to $value")
    }

    private suspend fun applyDownloadTimeout(intent: Intent) {
        if (!intent.hasExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS)) return
        val value = intent.getIntExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS, -1)
        settingsRepository.validateDownloadTimeout(value).fold(
            onSuccess = {
                settingsRepository.updateDownloadTimeout(it)
                Log.i(TAG, "Download timeout updated to ${it}s")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid download_timeout_seconds $value: ${it.message}") },
        )
    }

    private suspend fun applyScreenAwakeTimeout(intent: Intent) {
        if (!intent.hasExtra(EXTRA_SCREEN_AWAKE_TIMEOUT_SECONDS)) return
        val value = intent.getIntExtra(EXTRA_SCREEN_AWAKE_TIMEOUT_SECONDS, -1)
        settingsRepository.validateScreenAwakeTimeout(value).fold(
            onSuccess = {
                settingsRepository.updateScreenAwakeTimeout(it)
                Log.i(TAG, "Screen-awake timeout updated to ${it}s")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid screen_awake_timeout_seconds $value: ${it.message}") },
        )
    }

    private suspend fun applyDeviceSlug(intent: Intent) {
        if (!intent.hasExtra(EXTRA_DEVICE_SLUG)) return
        val value = intent.getStringExtra(EXTRA_DEVICE_SLUG) ?: ""
        settingsRepository.validateDeviceSlug(value).fold(
            onSuccess = {
                settingsRepository.updateDeviceSlug(it)
                Log.i(TAG, "Device slug updated to '$it'")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid device_slug '$value': ${it.message}") },
        )
    }

    private suspend fun applyToolPermissions(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_TOOL_PERMISSIONS) ?: return
        val config = ToolPermissionsConfig.fromJson(value)
        if (config == null) {
            Log.w(TAG, "Ignoring invalid tool_permissions JSON")
            return
        }
        settingsRepository.updateToolPermissionsConfig(config)
        Log.i(
            TAG,
            "Tool permissions updated: ${config.disabledTools.size} tools disabled, " +
                "${config.disabledParams.size} param overrides",
        )
    }

    private suspend fun applyStorageLocationPermissions(intent: Intent) {
        val locationId = intent.getStringExtra(EXTRA_STORAGE_LOCATION_ID) ?: return
        if (!storageLocationProvider.isLocationAuthorized(locationId)) {
            Log.w(TAG, "Ignoring storage permissions for unknown location")
            return
        }
        if (intent.hasExtra(EXTRA_STORAGE_ALLOW_WRITE)) {
            val allowWrite = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_WRITE, false)
            storageLocationProvider.updateLocationAllowWrite(locationId, allowWrite)
            Log.i(TAG, "Storage location allowWrite updated to $allowWrite")
        }
        if (intent.hasExtra(EXTRA_STORAGE_ALLOW_DELETE)) {
            val allowDelete = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_DELETE, false)
            storageLocationProvider.updateLocationAllowDelete(locationId, allowDelete)
            Log.i(TAG, "Storage location allowDelete updated to $allowDelete")
        }
    }

    private fun handleStartServer(context: Context) {
        Log.i(TAG, "Received ADB start server broadcast")
        val serviceIntent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_START
            }
        context.startForegroundService(serviceIntent)
        Log.i(TAG, "McpServerService start command sent")
    }

    private fun handleStopServer(context: Context) {
        Log.i(TAG, "Received ADB stop server broadcast")
        val serviceIntent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_STOP
            }
        context.startForegroundService(serviceIntent)
        Log.i(TAG, "McpServerService stop command sent")
    }

    companion object {
        private const val TAG = "MCP:AdbConfigHandler"

        internal const val EXTRA_BEARER_TOKEN = "bearer_token"
        internal const val EXTRA_OAUTH_ENABLED = "oauth_enabled"
        internal const val EXTRA_BEARER_TOKEN_ENABLED = "bearer_token_enabled"
        internal const val EXTRA_PUBLIC_URL_OVERRIDE = "public_url_override"
        internal const val EXTRA_BINDING_ADDRESS = "binding_address"
        internal const val EXTRA_PORT = "port"
        internal const val EXTRA_AUTO_START_ON_BOOT = "auto_start_on_boot"
        internal const val EXTRA_HIDE_FROM_RECENTS = "hide_from_recents"
        internal const val EXTRA_HTTPS_ENABLED = "https_enabled"
        internal const val EXTRA_CERTIFICATE_SOURCE = "certificate_source"
        internal const val EXTRA_CERTIFICATE_HOSTNAME = "certificate_hostname"
        internal const val EXTRA_TUNNEL_ENABLED = "tunnel_enabled"
        internal const val EXTRA_TUNNEL_PROVIDER = "tunnel_provider"
        internal const val EXTRA_CLOUDFLARE_TUNNEL_MODE = "cloudflare_tunnel_mode"
        internal const val EXTRA_CLOUDFLARE_TUNNEL_TOKEN = "cloudflare_tunnel_token"
        internal const val EXTRA_CLOUDFLARE_TUNNEL_EXTRA_ARGS = "cloudflare_tunnel_extra_args"
        internal const val EXTRA_NGROK_AUTHTOKEN = "ngrok_authtoken"
        internal const val EXTRA_NGROK_DOMAIN = "ngrok_domain"
        internal const val EXTRA_ARC_TUNNEL_BASE_ADDRESS = "arc_tunnel_base_address"
        internal const val EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED = "arc_tunnel_ct_check_enabled"
        internal const val EXTRA_ARC_TUNNEL_ENROLL = "arc_tunnel_enroll"
        internal const val EXTRA_FILE_SIZE_LIMIT_MB = "file_size_limit_mb"
        internal const val EXTRA_ALLOW_HTTP_DOWNLOADS = "allow_http_downloads"
        internal const val EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS = "allow_unverified_https_certs"
        internal const val EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS = "block_local_network_downloads"
        internal const val EXTRA_DOWNLOAD_TIMEOUT_SECONDS = "download_timeout_seconds"
        internal const val EXTRA_SCREEN_AWAKE_TIMEOUT_SECONDS = "screen_awake_timeout_seconds"
        internal const val EXTRA_DEVICE_SLUG = "device_slug"
        internal const val EXTRA_TOOL_PERMISSIONS = "tool_permissions"
        internal const val EXTRA_STORAGE_LOCATION_ID = "storage_location_id"
        internal const val EXTRA_STORAGE_ALLOW_WRITE = "storage_allow_write"
        internal const val EXTRA_STORAGE_ALLOW_DELETE = "storage_allow_delete"
    }
}
