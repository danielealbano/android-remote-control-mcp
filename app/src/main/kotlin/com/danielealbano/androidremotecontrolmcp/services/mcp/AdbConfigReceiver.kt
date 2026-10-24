package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * [BroadcastReceiver] that accepts configuration overrides via `adb shell am broadcast`.
 *
 * This receiver is available in both debug and release builds, allowing headless
 * configuration of the MCP server settings via ADB. It is `exported=true` so that
 * ADB (running as the shell user) can send broadcasts to it. The security boundary
 * is the `android:permission="android.permission.DUMP"` requirement declared on this
 * receiver in the manifest: DUMP is a signature/privileged platform permission held
 * by the adb shell UID (com.android.shell) but not grantable to third-party apps.
 * ActivityManager enforces it against the sender's real binder calling UID before
 * delivery, so `adb shell am broadcast` still works while any ordinary app is
 * rejected with a SecurityException — no in-code UID check is needed (and none is
 * reliable anyway, since [getSentFromUid] returns -1 for `am broadcast` on API 34+).
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
 *   --ez hide_from_recents false \
 *   --ez https_enabled false \
 *   --es certificate_source "AUTO_GENERATED" \
 *   --es certificate_hostname "android-mcp.local" \
 *   --ez tunnel_enabled true \
 *   --es tunnel_provider "android-remote-control-mcp-tunneld" \
 *   --es ngrok_authtoken "your-ngrok-token" \
 *   --es ngrok_domain "your-domain.ngrok-free.app" \
 *   --es arc_tunnel_base_address "arc-tunnel.io" \
 *   --ez arc_tunnel_ct_check_enabled true \
 *   --ez arc_tunnel_enroll true \
 *   --ei file_size_limit_mb 50 \
 *   --ez allow_http_downloads false \
 *   --ez allow_unverified_https_certs false \
 *   --ez block_local_network_downloads false \
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

    @Inject
    lateinit var storageLocationProvider: StorageLocationProvider

    @Inject
    lateinit var serverLogRepository: ServerLogRepository

    @Inject
    lateinit var arcTunnelEnrollment: ArcTunnelEnrollment

    // No in-code sender UID check is needed: the manifest gates this exported receiver behind
    // android:permission="android.permission.DUMP", which ActivityManager enforces against the
    // real binder calling UID. DUMP is held by the adb shell UID (com.android.shell) but is not
    // grantable to third-party apps, so `am broadcast` from adb shell reaches this receiver while
    // an ordinary app is blocked with a SecurityException before onReceive() is ever invoked.
    // (An in-code check would be unreliable regardless: getSentFromUid() returns -1 for
    // `am broadcast` on API 34+.)
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.i(TAG, "onReceive called with action: ${intent.action}")

        val handler =
            AdbConfigHandler(settingsRepository, storageLocationProvider, serverLogRepository, arcTunnelEnrollment)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(HANDLER_TIMEOUT_MS) {
                    handler.handle(context, intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in onReceive", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MCP:AdbConfigReceiver"
        private const val HANDLER_TIMEOUT_MS = 10_000L

        const val ACTION_CONFIGURE = "com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE"
        const val ACTION_START_SERVER = "com.danielealbano.androidremotecontrolmcp.ADB_START_SERVER"
        const val ACTION_STOP_SERVER = "com.danielealbano.androidremotecontrolmcp.ADB_STOP_SERVER"
    }
}
