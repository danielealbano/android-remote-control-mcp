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
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.mcp.CertificateManager
import com.danielealbano.androidremotecontrolmcp.mcp.McpServer
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerElementActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerGestureTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTextInputTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerTouchActionTools
import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerUtilityTools
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Inject lateinit var actionExecutor: ActionExecutor

    @Inject lateinit var accessibilityServiceProvider: AccessibilityServiceProvider

    @Inject lateinit var screenCaptureProvider: ScreenCaptureProvider

    @Inject lateinit var treeParser: AccessibilityTreeParser

    @Inject lateinit var elementFinder: ElementFinder

    @Inject lateinit var tunnelManager: TunnelManager

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverStarting = AtomicBoolean(false)
    private var mcpServer: McpServer? = null

    override fun onCreate() {
        super.onCreate()
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
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (!serverStarting.compareAndSet(false, true)) {
                    Log.w(TAG, "Server already starting or running, ignoring duplicate start request")
                } else {
                    coroutineScope.launch {
                        startServer()
                    }
                }
            }
        }

        return START_STICKY
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startServer() {
        try {
            updateStatus(ServerStatus.Starting)

            val config = settingsRepository.serverConfig.first()
            Log.i(
                TAG,
                "Starting MCP server with config: port=${config.port}, binding=${config.bindingAddress.address}",
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
                            name = "android-remote-control-mcp",
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
            registerAllTools(sdkServer)

            // Create and start the Ktor server
            mcpServer =
                McpServer(
                    config = config,
                    keyStore = keyStore,
                    keyStorePassword = keyStorePassword,
                    mcpSdkServer = sdkServer,
                )
            mcpServer?.start()

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

            // Start tunnel if remote access is enabled
            @Suppress("TooGenericExceptionCaught")
            try {
                tunnelManager.start(config.port)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start tunnel (server continues without tunnel)", e)
            }

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

            Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverStarting.set(false)
        }
    }

    private fun registerAllTools(server: Server) {
        registerScreenIntrospectionTools(server, treeParser, accessibilityServiceProvider, screenCaptureProvider)
        registerSystemActionTools(server, actionExecutor, accessibilityServiceProvider)
        registerTouchActionTools(server, actionExecutor)
        registerGestureTools(server, actionExecutor)
        registerElementActionTools(server, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)
        registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider)
        registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider)
    }

    override fun onDestroy() {
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

        // Stop tunnel first (with ANR-safe timeout)
        @Suppress("TooGenericExceptionCaught")
        try {
            runBlocking {
                withTimeout(TUNNEL_STOP_TIMEOUT_MS) {
                    tunnelManager.stop()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Tunnel stop timed out after ${TUNNEL_STOP_TIMEOUT_MS}ms, proceeding with shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
        }

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
        serverStarting.set(false)

        // Cancel coroutine scope
        coroutineScope.cancel()

        // Clear singleton
        instance = null

        updateStatus(ServerStatus.Stopped)
        Log.i(TAG, "McpServerService destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(status: ServerStatus) {
        _serverStatus.value = status
    }

    private fun emitLogEntry(entry: ServerLogEntry) {
        _serverLogEvents.tryEmit(entry)
    }

    private fun createNotification(): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat.Builder(this, McpApplication.MCP_SERVER_CHANNEL_ID)
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

        /**
         * Shared server status flow. Collected by MainViewModel to update the UI.
         * Uses a companion-level StateFlow so it survives service rebinding and is
         * accessible without requiring a bound service reference.
         */
        private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
        val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

        /**
         * Shared server log events flow. Collected by MainViewModel to display
         * log entries in the UI. Uses a SharedFlow (not StateFlow) because each
         * event is a discrete emission, not a current-state snapshot.
         *
         * extraBufferCapacity = 64 prevents dropped events during brief UI
         * collection pauses (e.g., during configuration changes).
         */
        private val _serverLogEvents = MutableSharedFlow<ServerLogEntry>(extraBufferCapacity = 64)
        val serverLogEvents: SharedFlow<ServerLogEntry> = _serverLogEvents.asSharedFlow()

        @Volatile
        var instance: McpServerService? = null
            private set
    }
}
