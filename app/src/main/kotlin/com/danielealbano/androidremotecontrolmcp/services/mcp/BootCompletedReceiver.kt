package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Receives BOOT_COMPLETED broadcast and auto-starts the MCP server
 * if the auto-start setting is enabled.
 *
 * Uses [goAsync] to extend the broadcast receiver's lifecycle beyond the
 * default 10-second limit, allowing a coroutine to read settings from DataStore.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot completed broadcast received")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    val config = settingsRepository.serverConfig.first()

                    if (config.autoStartOnBoot) {
                        Log.i(TAG, "Auto-start enabled, starting McpServerService")
                        val serviceIntent =
                            Intent(context, McpServerService::class.java).apply {
                                action = McpServerService.ACTION_START
                            }
                        context.startForegroundService(serviceIntent)
                    } else {
                        Log.i(TAG, "Auto-start disabled, skipping MCP server start")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking auto-start setting on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MCP:BootReceiver"
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L
    }
}
