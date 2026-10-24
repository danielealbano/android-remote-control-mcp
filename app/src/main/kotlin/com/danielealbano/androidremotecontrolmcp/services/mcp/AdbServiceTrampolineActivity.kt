package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollService

/**
 * Invisible trampoline [Activity] that starts or stops [McpServerService], or starts the free E2E tunnel
 * enrollment ([ArcTunnelEnrollService]), from ADB when the app is in the background.
 *
 * On Android 12+ (API 31+), apps cannot call [android.content.Context.startForegroundService]
 * from the background. Using `am start` to launch an Activity gives the app a foreground
 * exemption, allowing it to start the foreground service. This Activity renders no UI
 * (uses `Theme.NoDisplay`), performs the service start/stop or the enrollment start in [onCreate], and calls
 * [finish] immediately.
 *
 * For configuration-only changes (no service start/stop), use [AdbConfigReceiver] instead
 * — it works from the background without a foreground exemption.
 *
 * Because this Activity exists specifically to bypass the background foreground-service-start
 * restriction, an unprotected export would let any installed app start the MCP server. It is
 * therefore gated by `android:permission="android.permission.DUMP"` in the manifest, enforced by
 * ActivityManager against the caller's real binder UID — see [AdbConfigReceiver] for why DUMP is
 * the right permission and GHSA-v82h-m32h-3j39 for the issue this closes.
 *
 * **Usage** (from adb):
 * ```
 * # Start the MCP server
 * adb shell am start \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
 *   --es action start
 *
 * # Stop the MCP server
 * adb shell am start \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
 *   --es action stop
 *
 * # Enroll the free E2E tunnel (or resume a failed certificate issuance) with the saved settings
 * adb shell am start \
 *   -n <app-id>/com.danielealbano.androidremotecontrolmcp.services.mcp.AdbServiceTrampolineActivity \
 *   --es action enroll-tunnel
 * ```
 *
 * Where `<app-id>` is:
 * - Debug: `com.danielealbano.androidremotecontrolmcp.debug`
 * - Release: `com.danielealbano.androidremotecontrolmcp`
 */
class AdbServiceTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent.getStringExtra(EXTRA_ACTION))
        finish()
    }

    /** Dispatches one trampoline action (internal so the JVM tests can call it on a spy). */
    internal fun handle(action: String?) {
        Log.i(TAG, "Trampoline launched with action: $action")
        when (action) {
            ACTION_VALUE_START -> {
                Log.i(TAG, "Starting McpServerService via trampoline")
                val serviceIntent =
                    Intent(this, McpServerService::class.java).apply {
                        this.action = McpServerService.ACTION_START
                    }
                startForegroundService(serviceIntent)
            }

            ACTION_VALUE_STOP -> {
                Log.i(TAG, "Stopping McpServerService via trampoline")
                val serviceIntent =
                    Intent(this, McpServerService::class.java).apply {
                        this.action = McpServerService.ACTION_STOP
                    }
                startForegroundService(serviceIntent)
            }

            ACTION_VALUE_ENROLL_TUNNEL -> {
                if (isServerActive(McpServerService.serverStatus.value)) {
                    Log.w(TAG, "enroll-tunnel ignored: stop the MCP server first")
                } else {
                    Log.i(TAG, "Starting ArcTunnelEnrollService via trampoline")
                    ArcTunnelEnrollService.start(this, env = null, rotate = false)
                }
            }

            else -> {
                Log.w(TAG, "Unknown or missing action: '$action' (expected 'start', 'stop' or 'enroll-tunnel')")
            }
        }
    }

    companion object {
        private const val TAG = "MCP:ServiceTrampoline"
        internal const val EXTRA_ACTION = "action"
        internal const val ACTION_VALUE_START = "start"
        internal const val ACTION_VALUE_STOP = "stop"
        internal const val ACTION_VALUE_ENROLL_TUNNEL = "enroll-tunnel"
    }
}
