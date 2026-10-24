package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.OptionalToolPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.geo.GeoIpResolver
import com.danielealbano.androidremotecontrolmcp.mcp.CertificateManager
import com.danielealbano.androidremotecontrolmcp.mcp.HttpsMaterial
import com.danielealbano.androidremotecontrolmcp.mcp.McpServer
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.AuthorizationCodeStore
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.JwtTokenService
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeManager
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyModeStatus
import com.danielealbano.androidremotecontrolmcp.privacy.PseudonymStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpToolCallIndicators
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.services.sharing.EphemeralFileLinkService
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.services.tunnel.tunnelAllowedWithHttps
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Foreground service that runs the MCP server (HTTP by default, optional HTTPS).
 *
 * Lifecycle:
 * 1. Started via intent from MainActivity (start/stop button)
 * 2. Calls startForeground() with persistent notification
 * 3. Reads configuration from SettingsRepository
 * 4. Creates and starts McpServer (Ktor HTTP, optionally HTTPS)
 * 5. Updates ServerStatus via companion-level StateFlow (collected by MainViewModel)
 * 6. On stop: gracefully shuts down server, clears singleton
 */
@AndroidEntryPoint
class McpServerService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var certificateManager: CertificateManager

    @Inject lateinit var tunnelManager: TunnelManager

    @Inject lateinit var toolRegistration: McpToolRegistration

    @Inject lateinit var screenStateSnapshotCache: ScreenStateSnapshotCache

    @Inject lateinit var ephemeralFileLinkService: EphemeralFileLinkService

    @Inject lateinit var jwtTokenService: JwtTokenService

    @Inject lateinit var oauthClientRepository: OAuthClientRepository

    @Inject lateinit var authorizationCodeStore: AuthorizationCodeStore

    @Inject lateinit var approvalCoordinator: OAuthApprovalCoordinator

    @Inject lateinit var geoIpResolver: GeoIpResolver

    @Inject lateinit var serverLogRepository: ServerLogRepository

    @Inject lateinit var privacyModeManager: PrivacyModeManager

    @Inject lateinit var pseudonymStore: PseudonymStore

    @Inject lateinit var nerCache: NerCache

    /** Config of the currently running server; used to build capability-link base URLs. */
    @Volatile
    private var activeConfig: ServerConfig? = null

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverActive = AtomicBoolean(false)
    private val startTracker = ServerStartTracker(coroutineScope, START_JOIN_TIMEOUT_MS)

    @Volatile
    private var mcpServer: McpServer? = null
    private var tunnelObserverJob: Job? = null
    private var approvalObserverJob: Job? = null
    private var toolCallIndicators: McpToolCallIndicators? = null
    private val screenStateObserver by lazy { McpAccessibilityService.observeScreenState(this) }

    /**
     * Tracks whether the LAST [onStartCommand] action was an explicit stop. Lets [onDestroy] re-commit
     * `server_running=false` when the user explicitly stopped (a second bounded attempt in case the
     * first write in [onStartCommand] timed out), WITHOUT clearing the flag on an OEM/system kill —
     * where the last action was a start, so this stays `false` and the flag is left `true` for restart.
     */
    @Volatile
    private var lastIntentWasStop = false

    override fun onCreate() {
        super.onCreate()
        screenStateObserver.start()
        instance = this
        Log.i(TAG, "McpServerService created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                lastIntentWasStop = true
                persistServerRunning(settingsRepository, false)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                lastIntentWasStop = false
                persistServerRunning(settingsRepository, true)
                if (!serverActive.compareAndSet(false, true)) {
                    Log.w(TAG, "Server already starting or running, ignoring duplicate start request")
                } else {
                    startTracker.launch { startServer() }
                }
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        restartMcpServerIfForeground(this, _serverStatus.value is ServerStatus.Running)
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    internal suspend fun startServer() {
        try {
            updateStatus(ServerStatus.Starting)

            // getServerConfig() guarantees ensureAuthModelMigrated() has run before the server reads
            // the auth model (prevents the cleared-token-user open-server regression).
            val config = settingsRepository.getServerConfig()
            activeConfig = config
            val toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug)
            Log.i(
                TAG,
                "Starting MCP server with config: port=${config.port}, " +
                    "binding=${config.bindingAddress.address}, toolNamePrefix=$toolNamePrefix",
            )

            // Only get/create SSL keystore when HTTPS is enabled
            val keyStore =
                if (config.httpsEnabled) {
                    certificateManager.getOrCreateKeyStore(config)
                } else {
                    null
                }
            val keyStorePassword =
                if (config.httpsEnabled) {
                    certificateManager.getKeyStorePassword()
                } else {
                    null
                }

            // Create SDK Server instance and register all tools
            val sdkServer =
                Server(
                    serverInfo =
                        Implementation(
                            name = McpToolUtils.buildServerName(config.deviceSlug),
                            version = com.danielealbano.androidremotecontrolmcp.BuildConfig.VERSION_NAME,
                        ),
                    options =
                        ServerOptions(
                            capabilities =
                                ServerCapabilities(
                                    tools = ServerCapabilities.Tools(listChanged = false),
                                ),
                        ),
                )
            // ALL four signals MUST come from PermissionUtils — the SAME source the UI uses
            // (MainViewModel.refreshPermissionStatus) — so the server-start gate and the settings grey-out
            // never diverge. Do NOT use cameraProvider.isCameraPermissionGranted()/isMicrophonePermissionGranted()
            // or notificationProvider.isReady() here.
            val grantedOptionalPermissions =
                OptionalToolPermissions.grantedPermissions(
                    camera = PermissionUtils.isCameraPermissionGranted(this@McpServerService),
                    microphone = PermissionUtils.isMicrophonePermissionGranted(this@McpServerService),
                    location = PermissionUtils.isLocationPermissionGranted(this@McpServerService),
                    notificationListener =
                        PermissionUtils.isNotificationListenerEnabled(
                            this@McpServerService,
                            McpNotificationListenerService::class.java,
                        ),
                )
            val effectivePerms =
                OptionalToolPermissions.effectivePermissions(config.toolPermissionsConfig, grantedOptionalPermissions)
            val indicators = McpToolCallIndicators(coroutineScope, config.screenAwakeTimeoutSeconds)
            toolCallIndicators = indicators
            toolRegistration.registerAll(
                sdkServer,
                toolNamePrefix,
                effectivePerms,
                config.fileSizeLimitMb,
                config.toolCallIndicatorEnabled,
                indicators,
                currentBaseUrl,
            )

            // Create and start the Ktor server
            mcpServer =
                McpServer(
                    config = config,
                    httpsMaterial = buildHttpsMaterial(keyStore, keyStorePassword),
                    mcpSdkServer = sdkServer,
                    ephemeralFileLinkService = ephemeralFileLinkService,
                    oauth =
                        OAuthServerDeps(
                            jwtTokenService = jwtTokenService,
                            oauthClientRepository = oauthClientRepository,
                            authorizationCodeStore = authorizationCodeStore,
                            approvalCoordinator = approvalCoordinator,
                            geoIpResolver = geoIpResolver,
                        ),
                    serverLog = serverLogRepository,
                )
            mcpServer?.start()

            // Warm the geolocation DB off the request path so the first /authorize doesn't pay the
            // one-time gzip-inflate + mmap cost. Best-effort; a failure just leaves it lazy.
            coroutineScope.launch { geoIpResolver.resolve("8.8.8.8") }

            // Surface Privacy Mode readiness at start so the user learns of a failure now, not at the
            // first data-returning tool call. Off the request path; the result updates the manager status.
            if (config.privacyModeConfig.enabled) {
                coroutineScope.launch {
                    val message = privacyStatusLogMessage(privacyModeManager.selfCheck())
                    serverLogRepository.log(ServerLogEntry.Type.PRIVACY, message)
                }
            }

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

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
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
                }
            }

            // Observe tunnel status for logging
            tunnelObserverJob =
                coroutineScope.launch {
                    tunnelManager.tunnelStatus.collect { status ->
                        tunnelStatusLogMessage(status)?.let {
                            serverLogRepository.log(ServerLogEntry.Type.TUNNEL, it)
                        }
                        when (status) {
                            is TunnelStatus.Connected -> {
                                Log.i(
                                    TAG,
                                    "Tunnel connected: ${status.endpoints.joinToString { it.url }} " +
                                        "(provider: ${status.providerType})",
                                )
                            }

                            is TunnelStatus.Error -> {
                                Log.w(TAG, "Tunnel error: ${status.message}")
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

            // Observe pending OAuth approvals and surface a single heads-up notification (the service
            // is already foregrounded by onStartCommand; this is a separate, collapsed notification).
            approvalObserverJob =
                coroutineScope.launch {
                    approvalCoordinator.observePending().collect { pending ->
                        if (pending.isEmpty()) {
                            OAuthApprovalNotifier.cancel(this@McpServerService)
                        } else {
                            OAuthApprovalNotifier.post(this@McpServerService, pending.size)
                        }
                    }
                }

            Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
        } catch (e: Exception) {
            // A start cancelled by a teardown is not a start failure.
            currentCoroutineContext().ensureActive()
            // Each listener dispatches its cleanup to Main without blocking start cancellation.
            toolCallIndicators?.close()
            toolCallIndicators = null
            Log.e(TAG, "Failed to start MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverActive.set(false)
        }
    }

    /**
     * Externally-reachable base URL for capability links: the tunnel URL when a tunnel is connected,
     * otherwise the device LAN URL (`scheme://<device-ip>:<port>`).
     */
    private val currentBaseUrl: () -> String = {
        val tunnel = tunnelManager.tunnelStatus.value
        val tunnelUrl = (tunnel as? TunnelStatus.Connected)?.endpoints?.firstOrNull { it.valid }?.url
        if (tunnelUrl != null) {
            tunnelUrl
        } else {
            val cfg = activeConfig
            val scheme = if (cfg?.httpsEnabled == true) "https" else "http"
            val host =
                NetworkUtils.getDeviceIpAddress(applicationContext) ?: cfg?.bindingAddress?.address ?: "127.0.0.1"
            val port = cfg?.port ?: ServerConfig.DEFAULT_PORT
            "$scheme://$host:$port"
        }
    }

    override fun onDestroy() {
        // A start still in progress would bring the server, the tunnel and the Privacy Mode model up after this
        // teardown: cancel it and wait for it (it stops at its next suspension point) before releasing anything.
        if (!startTracker.cancelAndAwait()) {
            Log.w(TAG, "Server start still running after ${START_JOIN_TIMEOUT_MS}ms; proceeding with shutdown")
        }
        toolCallIndicators?.close()
        toolCallIndicators = null
        screenStateObserver.close()
        screenStateSnapshotCache.clear()
        // Release the Privacy Mode model and clear session-scoped pseudonym/NER caches (never persisted).
        privacyModeManager.shutdown()
        pseudonymStore.clear()
        nerCache.clear()
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

        // Second, later attempt to durably commit an explicit stop, in case the first bounded write in
        // onStartCommand timed out. Gated on lastIntentWasStop so an OEM/system kill (last action was a
        // start) never clears the flag — there the flag MUST stay true for restart-if-running.
        if (lastIntentWasStop) {
            persistServerRunning(settingsRepository, false)
        }

        // Cancel tunnel status observer before stopping the tunnel
        tunnelObserverJob?.cancel()
        tunnelObserverJob = null

        // Cancel the OAuth approval observer and clear any pending approval notification.
        approvalObserverJob?.cancel()
        approvalObserverJob = null
        OAuthApprovalNotifier.cancel(this)

        // Stop tunnel first (with ANR-safe timeout).
        // Worst-case blocking time: START_JOIN_TIMEOUT_MS (5s) + the stop flag write (2s) + TUNNEL_STOP_TIMEOUT_MS
        // (3s) + McpServer.stop: SDK close (SHUTDOWN_TIMEOUT_MS, 5s) + SHUTDOWN_GRACE_PERIOD_MS (1s) +
        // SHUTDOWN_TIMEOUT_MS (5s) + the tunnel listener stop (0.6s) = ~22s total. This is well within the Android
        // service onDestroy ANR threshold (~200s), so blocking the main thread here is acceptable.
        stopTunnel()

        // Stop the Ktor server gracefully
        @Suppress("TooGenericExceptionCaught")
        try {
            mcpServer?.stop(
                gracePeriodMillis = SHUTDOWN_GRACE_PERIOD_MS,
                timeoutMillis = SHUTDOWN_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during server shutdown", e)
        }
        mcpServer = null
        serverActive.set(false)

        // Cancel coroutine scope
        coroutineScope.cancel()

        // Clear singleton
        instance = null

        updateStatus(ServerStatus.Stopped)
        Log.i(TAG, "McpServerService destroyed")

        super.onDestroy()
    }

    /** Stops the tunnel within [TUNNEL_STOP_TIMEOUT_MS]; a failure is logged and the shutdown goes on. */
    private fun stopTunnel() {
        @Suppress("TooGenericExceptionCaught")
        try {
            runBlocking {
                withTimeout(TUNNEL_STOP_TIMEOUT_MS) {
                    tunnelManager.stop()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Tunnel stop timed out after ${TUNNEL_STOP_TIMEOUT_MS}ms, proceeding with shutdown", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(status: ServerStatus) {
        _serverStatus.value = status
        serverLogRepository.log(ServerLogEntry.Type.SERVER, serverStatusLogMessage(status))
    }

    private fun createNotification(): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_mcp_server_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MCP:ServerService"
        const val ACTION_START = "com.danielealbano.androidremotecontrolmcp.ACTION_START_MCP_SERVER"
        const val ACTION_STOP = "com.danielealbano.androidremotecontrolmcp.ACTION_STOP_MCP_SERVER"
        const val NOTIFICATION_ID = 1001
        const val SHUTDOWN_GRACE_PERIOD_MS = 1000L
        const val SHUTDOWN_TIMEOUT_MS = 5000L
        const val TUNNEL_STOP_TIMEOUT_MS = 3_000L
        const val START_JOIN_TIMEOUT_MS = 5_000L

        /**
         * Shared server status flow. Collected by MainViewModel to update the UI.
         * Uses a companion-level StateFlow so it survives service rebinding and is
         * accessible without requiring a bound service reference.
         */
        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        @Volatile
        var instance: McpServerService? = null
            private set
    }
}

/** Builds the HTTPS [HttpsMaterial] when both the keystore and its password are present, else null. */
private fun buildHttpsMaterial(
    keyStore: KeyStore?,
    keyStorePassword: CharArray?,
): HttpsMaterial? =
    if (keyStore != null && keyStorePassword != null) {
        HttpsMaterial(keyStore, keyStorePassword)
    } else {
        null
    }

/** Human-readable server-log message for a [ServerStatus] transition. */
internal fun serverStatusLogMessage(status: ServerStatus): String =
    when (status) {
        ServerStatus.Starting -> "Server starting"
        is ServerStatus.Running -> "Server started on ${status.bindingAddress}:${status.port}"
        ServerStatus.Stopping -> "Server stopping"
        ServerStatus.Stopped -> "Server stopped"
        is ServerStatus.Error -> "Server error: ${status.message}"
    }

/** True while the server is starting or running (enrollment and rotation are refused then). */
internal fun isServerActive(status: ServerStatus): Boolean =
    when (status) {
        is ServerStatus.Starting, is ServerStatus.Running -> true
        else -> false
    }

/** Server-log message for a Privacy Mode self-check result at server start. */
internal fun privacyStatusLogMessage(status: PrivacyModeStatus): String =
    when (status) {
        is PrivacyModeStatus.Ready -> {
            "Privacy mode ready (model)"
        }

        is PrivacyModeStatus.ReadyDeterministicOnly -> {
            "Privacy mode ready (deterministic only)"
        }

        is PrivacyModeStatus.Unavailable -> {
            "Privacy mode UNAVAILABLE: ${status.reason} — data-returning tools are blocked"
        }

        is PrivacyModeStatus.Disabled -> {
            "Privacy mode disabled"
        }
    }

/** Server-log message for a tunnel status transition; null when not logged by the observer. */
internal fun tunnelStatusLogMessage(status: TunnelStatus): String? =
    when (status) {
        TunnelStatus.Connecting -> "Tunnel connecting…"
        is TunnelStatus.Connected -> "Tunnel connected: ${status.endpoints.joinToString { it.url }}"
        is TunnelStatus.Error -> "Tunnel error: ${status.message}"
        TunnelStatus.Disconnected -> null // Logged by TunnelManager.stop()
    }
