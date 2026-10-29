package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.InetAddresses
import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import com.danielealbano.androidremotecontrolmcp.services.mcp.isServerActive
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHosts
import com.danielealbano.androidremotecontrolmcp.utils.RecentsUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Debug-only [BroadcastReceiver] that accepts test configuration overrides
 * via `adb shell am broadcast`.
 *
 * This receiver lives in the `debug` source set, so it is absent from the release
 * APK entirely. It allows E2E tests to inject server settings (bearer token,
 * binding address, port) into the app's DataStore without manipulating protobuf
 * files directly.
 *
 * The source set alone is NOT the security boundary: debug APKs are attached to
 * GitHub releases, so this receiver does reach real devices. It is additionally
 * gated by `android:permission="android.permission.DUMP"` in the debug manifest —
 * a signature/privileged platform permission held by the adb shell UID
 * (com.android.shell) but not grantable to third-party apps. ActivityManager
 * enforces it against the sender's real binder calling UID, so `adb shell am
 * broadcast` still reaches this receiver while an ordinary app is rejected with a
 * SecurityException before [onReceive] runs. Without that gate, any installed app
 * could rewrite the MCP server's configuration (see GHSA-v82h-m32h-3j39).
 *
 * **Usage** (from E2E test via adb):
 * ```
 * # Configure settings
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver \
 *   --es bearer_token "test-token-uuid" \
 *   --es binding_address "0.0.0.0" \
 *   --ei port 8080 \
 *   --ei tunnel_local_port 61200 \
 *   --ez auto_start_on_boot true \
 *   --ez hide_from_recents false
 *
 * (`binding_address`: `127.0.0.1`, `0.0.0.0`, or another IP address literal of the device for Specific IP)
 *
 * # Start the MCP server (runs inside app process, avoids exported=false restriction)
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver
 *
 * # Stop the MCP server
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_STOP_SERVER \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.debug.E2EConfigReceiver
 *
 * # Free E2E tunnel: configure (override + trusted CA are debug-only; "-" clears a string extra),
 * # enroll, and query the status (Base64 JSON in the broadcast result data)
 * adb shell am broadcast -a ....E2E_CONFIGURE -n ... \
 *   --ez tunnel_enabled true --es tunnel_provider android-remote-control-mcp-tunneld \
 *   --es arc_tunnel_base_address example.test --ez arc_tunnel_ct_check_enabled false \
 *   --es arc_tunnel_edge_override 10.88.0.1:18443 --es arc_tunnel_trusted_ca_pem_b64 <base64 of the PEM>
 * adb shell am broadcast -a ....E2E_ARC_TUNNEL_ENROLL -n ...
 * adb shell am broadcast -a ....E2E_ARC_TUNNEL_QUERY -n ...
 * ```
 *
 * Where `<app-id>` is `com.danielealbano.androidremotecontrolmcp.gms.debug` (gms flavor) or
 * `com.danielealbano.androidremotecontrolmcp.foss.debug` (foss flavor): this receiver exists only in debug builds.
 * The actions keep the receiver's source package (`com.danielealbano.androidremotecontrolmcp.debug`).
 */
@AndroidEntryPoint
class E2EConfigReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var storageLocationProvider: StorageLocationProvider

    @Inject
    lateinit var tunnelManager: TunnelManager

    @Inject
    lateinit var arcTunnelEnrollment: ArcTunnelEnrollment

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Log immediately to verify receiver is being called
        Log.i(TAG, "!!! onReceive called with action: ${intent.action}")

        @Suppress("TooGenericExceptionCaught")
        try {
            when (intent.action) {
                ACTION_E2E_CONFIGURE -> handleConfigure(context, intent)
                ACTION_E2E_START_SERVER -> handleStartServer(context)
                ACTION_E2E_STOP_SERVER -> handleStopServer(context)
                ACTION_E2E_ARC_TUNNEL_ENROLL -> handleArcTunnelEnroll()
                ACTION_E2E_ARC_TUNNEL_QUERY -> handleArcTunnelQuery()
                else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onReceive", e)
        }
    }

    private fun handleConfigure(
        context: Context,
        intent: Intent,
    ) {
        Log.i(TAG, "Received E2E configuration broadcast")

        val bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN)
        val bindingAddress = intent.getStringExtra(EXTRA_BINDING_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val hasAutoStart = intent.hasExtra(EXTRA_AUTO_START_ON_BOOT)
        val autoStart = intent.getBooleanExtra(EXTRA_AUTO_START_ON_BOOT, false)

        scope.launch {
            settingsRepository.ensureAuthModelMigrated()
            if (!bearerToken.isNullOrEmpty()) {
                settingsRepository.updateBearerToken(bearerToken)
                Log.i(TAG, "Bearer token updated (length=${bearerToken.length})")
            }
            if (!bindingAddress.isNullOrEmpty()) applyBindingAddress(bindingAddress.trim())
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                settingsRepository.updatePort(port)
                Log.i(TAG, "Port updated to $port")
            }
            if (hasAutoStart) {
                settingsRepository.updateAutoStartOnBoot(autoStart)
                Log.i(TAG, "Auto-start on boot updated to $autoStart")
            }
            if (intent.hasExtra(EXTRA_HIDE_FROM_RECENTS)) {
                val hideFromRecents = intent.getBooleanExtra(EXTRA_HIDE_FROM_RECENTS, false)
                settingsRepository.updateHideFromRecents(hideFromRecents)
                RecentsUtils.setExcludeFromRecents(context, hideFromRecents)
                Log.i(TAG, "Hide from recents updated to $hideFromRecents")
            }
            applyAuthFlags(intent)
            if (intent.hasExtra(EXTRA_TOOL_CALL_INDICATOR_ENABLED)) {
                settingsRepository.updateToolCallIndicatorEnabled(
                    intent.getBooleanExtra(EXTRA_TOOL_CALL_INDICATOR_ENABLED, true),
                )
            }
            applyDownloadSettings(intent)
            applyTunnelSettings(intent)
            val storageLocationId = intent.getStringExtra(EXTRA_STORAGE_LOCATION_ID)
            if (!storageLocationId.isNullOrEmpty()) {
                if (storageLocationProvider.isLocationAuthorized(storageLocationId)) {
                    if (intent.hasExtra(EXTRA_STORAGE_ALLOW_WRITE)) {
                        val allowWrite = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_WRITE, false)
                        storageLocationProvider.updateLocationAllowWrite(storageLocationId, allowWrite)
                        Log.i(TAG, "Storage location allowWrite=$allowWrite")
                    }
                    if (intent.hasExtra(EXTRA_STORAGE_ALLOW_DELETE)) {
                        val allowDelete = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_DELETE, false)
                        storageLocationProvider.updateLocationAllowDelete(storageLocationId, allowDelete)
                        Log.i(TAG, "Storage location allowDelete=$allowDelete")
                    }
                } else {
                    Log.w(TAG, "Unknown storage location: $storageLocationId")
                }
            }
            Log.i(TAG, "E2E configuration applied successfully")
        }
    }

    private suspend fun applyBindingAddress(value: String) {
        when {
            value == BindingAddress.NETWORK.address || value == BindingAddress.LOCALHOST.address -> {
                val address = BindingAddress.fromAddress(value)
                settingsRepository.updateBindingAddress(address)
                Log.i(TAG, "Binding address updated to $address")
            }

            // Without its zone index, which the start refuses with a clear error
            InetAddresses.isNumericAddress(value.substringBefore('%')) -> {
                settingsRepository.updateSpecificBindAddress(value)
                settingsRepository.updateBindingAddress(BindingAddress.SPECIFIC)
                Log.i(TAG, "Binding address updated to the specific IP $value")
            }

            else -> {
                Log.w(TAG, "Ignoring unrecognized binding_address '$value'")
            }
        }
    }

    private suspend fun applyAuthFlags(intent: Intent) {
        if (intent.hasExtra(EXTRA_OAUTH_ENABLED)) {
            val oauthEnabled = intent.getBooleanExtra(EXTRA_OAUTH_ENABLED, false)
            settingsRepository.updateOauthEnabled(oauthEnabled)
            Log.i(TAG, "OAuth enabled updated to $oauthEnabled")
        }
        if (intent.hasExtra(EXTRA_BEARER_TOKEN_ENABLED)) {
            val bearerEnabled = intent.getBooleanExtra(EXTRA_BEARER_TOKEN_ENABLED, false)
            settingsRepository.updateBearerTokenEnabled(bearerEnabled)
            Log.i(TAG, "Bearer token enabled updated to $bearerEnabled")
        }
    }

    private suspend fun applyDownloadSettings(intent: Intent) {
        val downloadTimeout = intent.getIntExtra(EXTRA_DOWNLOAD_TIMEOUT_SECONDS, -1)
        if (downloadTimeout in
            ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS
        ) {
            settingsRepository.updateDownloadTimeout(downloadTimeout)
            Log.i(TAG, "Download timeout updated to $downloadTimeout s")
        }
        if (intent.hasExtra(EXTRA_ALLOW_HTTP_DOWNLOADS)) {
            val allowHttp = intent.getBooleanExtra(EXTRA_ALLOW_HTTP_DOWNLOADS, false)
            settingsRepository.updateAllowHttpDownloads(allowHttp)
            Log.i(TAG, "Allow HTTP downloads updated to $allowHttp")
        }
        if (intent.hasExtra(EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS)) {
            val block = intent.getBooleanExtra(EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS, false)
            settingsRepository.updateBlockLocalNetworkDownloads(block)
            Log.i(TAG, "Block local network downloads updated to $block")
        }
    }

    private suspend fun applyTunnelSettings(intent: Intent) {
        val tunnelLocalPort = intent.getIntExtra(EXTRA_TUNNEL_LOCAL_PORT, -1)
        if (tunnelLocalPort in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
            settingsRepository.updateTunnelLocalPort(tunnelLocalPort)
            Log.i(TAG, "Tunnel local port updated to $tunnelLocalPort")
        }
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

    private fun handleStartServer(context: Context) {
        Log.i(TAG, "Received E2E start server broadcast")
        val intent =
            Intent(context, McpServerService::class.java).apply {
                action = McpServerService.ACTION_START
            }
        context.startForegroundService(intent)
        Log.i(TAG, "McpServerService start command sent")
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

    companion object {
        private const val TAG = "E2E:ConfigReceiver"
        const val ACTION_E2E_CONFIGURE = "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE"
        const val ACTION_E2E_START_SERVER = "com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER"
        const val ACTION_E2E_STOP_SERVER = "com.danielealbano.androidremotecontrolmcp.debug.E2E_STOP_SERVER"
        const val ACTION_E2E_ARC_TUNNEL_ENROLL = "com.danielealbano.androidremotecontrolmcp.debug.E2E_ARC_TUNNEL_ENROLL"
        const val ACTION_E2E_ARC_TUNNEL_QUERY = "com.danielealbano.androidremotecontrolmcp.debug.E2E_ARC_TUNNEL_QUERY"
        private const val CLEAR = "-"
        private const val EXTRA_TUNNEL_ENABLED = "tunnel_enabled"
        private const val EXTRA_TUNNEL_LOCAL_PORT = "tunnel_local_port"
        private const val EXTRA_TUNNEL_PROVIDER = "tunnel_provider"
        private const val EXTRA_ARC_TUNNEL_BASE_ADDRESS = "arc_tunnel_base_address"
        private const val EXTRA_ARC_TUNNEL_CT_CHECK_ENABLED = "arc_tunnel_ct_check_enabled"
        private const val EXTRA_ARC_TUNNEL_EDGE_OVERRIDE = "arc_tunnel_edge_override"
        private const val EXTRA_ARC_TUNNEL_TRUSTED_CA_PEM_B64 = "arc_tunnel_trusted_ca_pem_b64"
        private const val EXTRA_BEARER_TOKEN = "bearer_token"
        private const val EXTRA_OAUTH_ENABLED = "oauth_enabled"
        private const val EXTRA_BEARER_TOKEN_ENABLED = "bearer_token_enabled"
        private const val EXTRA_BINDING_ADDRESS = "binding_address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_AUTO_START_ON_BOOT = "auto_start_on_boot"
        private const val EXTRA_TOOL_CALL_INDICATOR_ENABLED = "tool_call_indicator_enabled"
        private const val EXTRA_HIDE_FROM_RECENTS = "hide_from_recents"
        private const val EXTRA_STORAGE_LOCATION_ID = "storage_location_id"
        private const val EXTRA_STORAGE_ALLOW_WRITE = "storage_allow_write"
        private const val EXTRA_STORAGE_ALLOW_DELETE = "storage_allow_delete"
        private const val EXTRA_DOWNLOAD_TIMEOUT_SECONDS = "download_timeout_seconds"
        private const val EXTRA_ALLOW_HTTP_DOWNLOADS = "allow_http_downloads"
        private const val EXTRA_BLOCK_LOCAL_NETWORK_DOWNLOADS = "block_local_network_downloads"
    }
}
