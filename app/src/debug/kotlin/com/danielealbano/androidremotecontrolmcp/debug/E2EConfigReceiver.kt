package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only [BroadcastReceiver] that accepts test configuration overrides
 * via `adb shell am broadcast`.
 *
 * This receiver is ONLY included in debug builds. It allows E2E tests to
 * inject server settings (bearer token, binding address, port) into the
 * app's DataStore without manipulating protobuf files directly.
 *
 * **Usage** (from E2E test via adb):
 * ```
 * # Configure settings
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.E2EConfigReceiver \
 *   --es bearer_token "test-token-uuid" \
 *   --es binding_address "0.0.0.0" \
 *   --ei port 8080 \
 *   --ez auto_start_on_boot true
 *
 * # Start the MCP server (runs inside app process, avoids exported=false restriction)
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.E2EConfigReceiver
 * ```
 */
@AndroidEntryPoint
class E2EConfigReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Log immediately to verify receiver is being called
        Log.i(TAG, "!!! onReceive called with action: ${intent.action}")

        try {
            when (intent.action) {
                ACTION_E2E_CONFIGURE -> handleConfigure(intent)
                ACTION_E2E_START_SERVER -> handleStartServer(context)
                else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onReceive", e)
        }
    }

    private fun handleConfigure(intent: Intent) {
        Log.i(TAG, "Received E2E configuration broadcast")

        val bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN)
        val bindingAddress = intent.getStringExtra(EXTRA_BINDING_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val hasAutoStart = intent.hasExtra(EXTRA_AUTO_START_ON_BOOT)
        val autoStart = intent.getBooleanExtra(EXTRA_AUTO_START_ON_BOOT, false)

        scope.launch {
            if (!bearerToken.isNullOrEmpty()) {
                settingsRepository.updateBearerToken(bearerToken)
                Log.i(TAG, "Bearer token updated (length=${bearerToken.length})")
            }
            if (!bindingAddress.isNullOrEmpty()) {
                val address =
                    if (bindingAddress == "0.0.0.0") {
                        BindingAddress.NETWORK
                    } else {
                        BindingAddress.LOCALHOST
                    }
                settingsRepository.updateBindingAddress(address)
                Log.i(TAG, "Binding address updated to $address")
            }
            if (port in 1..65535) {
                settingsRepository.updatePort(port)
                Log.i(TAG, "Port updated to $port")
            }
            if (hasAutoStart) {
                settingsRepository.updateAutoStartOnBoot(autoStart)
                Log.i(TAG, "Auto-start on boot updated to $autoStart")
            }
            Log.i(TAG, "E2E configuration applied successfully")
        }
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

    companion object {
        private const val TAG = "E2E:ConfigReceiver"
        const val ACTION_E2E_CONFIGURE = "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE"
        const val ACTION_E2E_START_SERVER = "com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER"
        private const val EXTRA_BEARER_TOKEN = "bearer_token"
        private const val EXTRA_BINDING_ADDRESS = "binding_address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_AUTO_START_ON_BOOT = "auto_start_on_boot"
    }
}
