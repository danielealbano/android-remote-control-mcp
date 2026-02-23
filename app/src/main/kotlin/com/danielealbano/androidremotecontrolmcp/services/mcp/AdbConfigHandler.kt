package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository

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
) {
    /**
     * Dispatches the intent to the appropriate handler based on its action.
     */
    suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            AdbConfigReceiver.ACTION_CONFIGURE -> handleConfigure(intent)
            AdbConfigReceiver.ACTION_START_SERVER -> handleStartServer(context)
            AdbConfigReceiver.ACTION_STOP_SERVER -> handleStopServer(context)
            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun handleConfigure(intent: Intent) {
        Log.i(TAG, "Received ADB configuration broadcast")

        applyBearerToken(intent)
        applyBindingAddress(intent)
        applyPort(intent)
        applyAutoStartOnBoot(intent)
        applyHttpsEnabled(intent)
        applyCertificateSource(intent)
        applyCertificateHostname(intent)
        applyTunnelEnabled(intent)
        applyTunnelProvider(intent)
        applyNgrokAuthtoken(intent)
        applyNgrokDomain(intent)
        applyFileSizeLimit(intent)
        applyAllowHttpDownloads(intent)
        applyAllowUnverifiedHttpsCerts(intent)
        applyDownloadTimeout(intent)
        applyDeviceSlug(intent)

        Log.i(TAG, "ADB configuration applied successfully")
    }

    private suspend fun applyBearerToken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_BEARER_TOKEN) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty bearer_token")
            return
        }
        settingsRepository.updateBearerToken(value)
        Log.i(TAG, "Bearer token updated (length=${value.length})")
    }

    private suspend fun applyBindingAddress(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_BINDING_ADDRESS) ?: return
        val address =
            if (value == BindingAddress.NETWORK.address) {
                BindingAddress.NETWORK
            } else {
                BindingAddress.LOCALHOST
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
        val provider =
            try {
                TunnelProviderType.valueOf(value)
            } catch (_: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "Ignoring invalid tunnel_provider '$value' " +
                        "(valid: ${TunnelProviderType.entries.joinToString()})",
                )
                return
            }
        settingsRepository.updateTunnelProvider(provider)
        Log.i(TAG, "Tunnel provider updated to $provider")
    }

    private suspend fun applyNgrokAuthtoken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_NGROK_AUTHTOKEN) ?: return
        settingsRepository.updateNgrokAuthtoken(value)
        Log.i(TAG, "ngrok authtoken updated (length=${value.length})")
    }

    private suspend fun applyNgrokDomain(intent: Intent) {
        if (!intent.hasExtra(EXTRA_NGROK_DOMAIN)) return
        val value = intent.getStringExtra(EXTRA_NGROK_DOMAIN) ?: ""
        settingsRepository.updateNgrokDomain(value)
        Log.i(TAG, "ngrok domain updated to '$value'")
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
        context.startService(serviceIntent)
        Log.i(TAG, "McpServerService stop command sent")
    }

    companion object {
        private const val TAG = "MCP:AdbConfigReceiver"

        internal const val EXTRA_BEARER_TOKEN = "bearer_token"
        internal const val EXTRA_BINDING_ADDRESS = "binding_address"
        internal const val EXTRA_PORT = "port"
        internal const val EXTRA_AUTO_START_ON_BOOT = "auto_start_on_boot"
        internal const val EXTRA_HTTPS_ENABLED = "https_enabled"
        internal const val EXTRA_CERTIFICATE_SOURCE = "certificate_source"
        internal const val EXTRA_CERTIFICATE_HOSTNAME = "certificate_hostname"
        internal const val EXTRA_TUNNEL_ENABLED = "tunnel_enabled"
        internal const val EXTRA_TUNNEL_PROVIDER = "tunnel_provider"
        internal const val EXTRA_NGROK_AUTHTOKEN = "ngrok_authtoken"
        internal const val EXTRA_NGROK_DOMAIN = "ngrok_domain"
        internal const val EXTRA_FILE_SIZE_LIMIT_MB = "file_size_limit_mb"
        internal const val EXTRA_ALLOW_HTTP_DOWNLOADS = "allow_http_downloads"
        internal const val EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS = "allow_unverified_https_certs"
        internal const val EXTRA_DOWNLOAD_TIMEOUT_SECONDS = "download_timeout_seconds"
        internal const val EXTRA_DEVICE_SLUG = "device_slug"
    }
}
