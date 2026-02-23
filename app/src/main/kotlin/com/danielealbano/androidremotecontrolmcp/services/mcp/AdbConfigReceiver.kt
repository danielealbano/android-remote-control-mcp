package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [BroadcastReceiver] that accepts configuration overrides via `adb shell am broadcast`.
 *
 * This receiver is available in both debug and release builds, allowing headless
 * configuration of the MCP server settings via ADB. It is `exported=true` so that
 * ADB (running as the shell user) can send broadcasts to it. On API 34+, only
 * broadcasts from the ADB shell user (UID 2000) are accepted via [getSentFromUid];
 * on API 33 the sender UID cannot be reliably determined, so the check is skipped.
 *
 * Only settings that do not require direct user interaction (e.g., SAF document
 * picker for storage locations) are supported. Each extra is optional; omitted
 * extras leave the corresponding setting unchanged.
 *
 * All configuration logic is delegated to [AdbConfigHandler] for testability.
 *
 * **Usage** (from adb):
 * ```
 * # Configure settings (all extras are optional — only provided ones are updated)
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver \
 *   --es bearer_token "my-secret-token" \
 *   --es binding_address "0.0.0.0" \
 *   --ei port 8080 \
 *   --ez auto_start_on_boot true \
 *   --ez https_enabled false \
 *   --es certificate_source "AUTO_GENERATED" \
 *   --es certificate_hostname "android-mcp.local" \
 *   --ez tunnel_enabled true \
 *   --es tunnel_provider "CLOUDFLARE" \
 *   --es ngrok_authtoken "your-ngrok-token" \
 *   --es ngrok_domain "your-domain.ngrok-free.app" \
 *   --ei file_size_limit_mb 50 \
 *   --ez allow_http_downloads false \
 *   --ez allow_unverified_https_certs false \
 *   --ei download_timeout_seconds 60 \
 *   --es device_slug "my_pixel"
 *
 * # Start the MCP server
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.ADB_START_SERVER \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver
 *
 * # Stop the MCP server
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.ADB_STOP_SERVER \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver
 * ```
 *
 * Where `<app-id>` is:
 * - Debug: `com.danielealbano.androidremotecontrolmcp.debug`
 * - Release: `com.danielealbano.androidremotecontrolmcp`
 */
@AndroidEntryPoint
class AdbConfigReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val senderUid = getSentFromUid()
            if (senderUid != Process.SHELL_UID) {
                Log.w(TAG, "Rejecting broadcast from non-shell caller (uid=$senderUid)")
                return
            }
        }

        Log.i(TAG, "onReceive called with action: ${intent.action}")

        val handler = AdbConfigHandler(settingsRepository)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handler.handle(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in onReceive", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MCP:AdbConfigReceiver"

        const val ACTION_CONFIGURE = "com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE"
        const val ACTION_START_SERVER = "com.danielealbano.androidremotecontrolmcp.ADB_START_SERVER"
        const val ACTION_STOP_SERVER = "com.danielealbano.androidremotecontrolmcp.ADB_STOP_SERVER"
    }
}
