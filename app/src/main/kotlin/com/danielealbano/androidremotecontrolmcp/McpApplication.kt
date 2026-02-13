package com.danielealbano.androidremotecontrolmcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class McpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.i(TAG, "Application initialized, notification channels created")
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val mcpServerChannel =
            NotificationChannel(
                MCP_SERVER_CHANNEL_ID,
                getString(R.string.notification_channel_mcp_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notification for the running MCP server"
            }

        notificationManager.createNotificationChannel(mcpServerChannel)
    }

    companion object {
        private const val TAG = "MCP:Application"
        const val MCP_SERVER_CHANNEL_ID = "mcp_server_channel"
    }
}
