package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger

class McpNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Logger.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Logger.i(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        Logger.i(TAG, "Notification listener service destroying")
        instance = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.d(TAG, "onTrimMemory level=$level")
    }

    fun getNotifications(): Array<StatusBarNotification> = activeNotifications ?: emptyArray()

    fun dismissNotification(key: String) {
        cancelNotification(key)
    }

    fun dismissAllNotifications() {
        cancelAllNotifications()
    }

    fun snoozeNotificationByKey(
        key: String,
        durationMs: Long,
    ) {
        snoozeNotification(key, durationMs)
    }

    companion object {
        private const val TAG = "MCP:NotificationListener"

        @Volatile
        var instance: McpNotificationListenerService? = null
            private set
    }
}
