package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import java.util.concurrent.ConcurrentHashMap

object NotificationDataExtractor {
    private const val TAG = "MCP:NotifExtractor"
    private val appNameCache = ConcurrentHashMap<String, String>()

    fun extract(
        sbn: StatusBarNotification,
        context: Context,
    ): NotificationData {
        val notification = sbn.notification
        val extras = notification.extras
        val appName =
            appNameCache.getOrPut(sbn.packageName) {
                val pm = context.packageManager
                try {
                    val appInfo =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(sbn.packageName, 0)
                        }
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    Logger.d(TAG, "App not found for ${sbn.packageName}, using package name")
                    sbn.packageName
                }
            }
        val actions =
            notification.actions?.mapIndexed { index, action ->
                NotificationActionData(
                    actionId = NotificationProviderImpl.computeActionHash(sbn.key, index),
                    index = index,
                    title = action.title?.toString() ?: "",
                    acceptsText = action.remoteInputs?.any { !it.isDataOnly } ?: false,
                )
            } ?: emptyList()
        return NotificationData(
            notificationId = NotificationProviderImpl.computeNotificationHash(sbn.key),
            packageName = sbn.packageName,
            appName = appName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            timestamp = sbn.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = sbn.isClearable,
            category = notification.category,
            groupKey = sbn.groupKey,
            actions = actions,
        )
    }
}
