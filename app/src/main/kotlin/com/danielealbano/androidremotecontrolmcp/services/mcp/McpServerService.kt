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
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.CertificateManager
import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.McpServer
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolRegistry
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    @Inject lateinit var protocolHandler: McpProtocolHandler

    @Inject lateinit var certificateManager: CertificateManager

    @Inject lateinit var toolRegistry: ToolRegistry

    @Inject lateinit var actionExecutor: ActionExecutor

    @Inject lateinit var accessibilityServiceProvider: AccessibilityServiceProvider

    @Inject lateinit var screenCaptureProvider: ScreenCaptureProvider

    @Inject lateinit var treeParser: AccessibilityTreeParser

    @Inject lateinit var elementFinder: ElementFinder

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

            // Register all MCP tools before starting the server
            registerAllTools()

            // Create and start the Ktor server
            mcpServer =
                McpServer(
                    config = config,
                    keyStore = keyStore,
                    keyStorePassword = keyStorePassword,
                    protocolHandler = protocolHandler,
                )
            mcpServer?.start()

            updateStatus(
                ServerStatus.Running(
                    port = config.port,
                    bindingAddress = config.bindingAddress.address,
                ),
            )

            Log.i(TAG, "MCP server started successfully on ${config.bindingAddress.address}:${config.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP server", e)
            updateStatus(ServerStatus.Error(e.message ?: "Unknown error starting server"))
            serverStarting.set(false)
        }
    }

    private fun registerAllTools() {
        registerScreenIntrospectionTools(
            toolRegistry,
            treeParser,
            accessibilityServiceProvider,
            screenCaptureProvider,
        )
        registerSystemActionTools(toolRegistry, actionExecutor, accessibilityServiceProvider)
        registerTouchActionTools(toolRegistry, actionExecutor)
        registerGestureTools(toolRegistry, actionExecutor)
        registerElementActionTools(
            toolRegistry,
            treeParser,
            elementFinder,
            actionExecutor,
            accessibilityServiceProvider,
        )
        registerTextInputTools(toolRegistry, treeParser, actionExecutor, accessibilityServiceProvider)
        registerUtilityTools(toolRegistry, treeParser, elementFinder, accessibilityServiceProvider)
    }

    override fun onDestroy() {
        Log.i(TAG, "McpServerService destroying")
        updateStatus(ServerStatus.Stopping)

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
