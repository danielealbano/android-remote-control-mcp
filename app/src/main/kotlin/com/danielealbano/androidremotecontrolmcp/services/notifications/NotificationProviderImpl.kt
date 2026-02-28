package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

class NotificationProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : NotificationProvider {
        override fun isReady(): Boolean = McpNotificationListenerService.instance != null

        override suspend fun getNotifications(
            packageName: String?,
            limit: Int?,
        ): List<NotificationData> {
            val service = requireService()
            val notifications =
                service
                    .getNotifications()
                    .let { list ->
                        if (packageName != null) {
                            list.filter { sbn -> sbn.packageName == packageName }
                        } else {
                            list.toList()
                        }
                    }.sortedByDescending { it.postTime }
                    .let { if (limit != null) it.take(limit) else it }
            return notifications.map { toNotificationData(it) }
        }

        @Suppress("ReturnCount")
        override suspend fun openNotification(notificationId: String): Result<Unit> {
            val service = requireService()
            val sbn =
                service.getNotifications().firstOrNull {
                    computeNotificationHash(it.key) == notificationId
                } ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
            val pendingIntent =
                sbn.notification.contentIntent
                    ?: return Result.failure(IllegalStateException("Notification has no content intent"))
            return try {
                pendingIntent.send()
                Result.success(Unit)
            } catch (e: PendingIntent.CanceledException) {
                Result.failure(e)
            }
        }

        override suspend fun dismissNotification(notificationId: String): Result<Unit> {
            val service = requireService()
            val sbn =
                service.getNotifications().firstOrNull {
                    computeNotificationHash(it.key) == notificationId
                } ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
            return try {
                service.dismissNotification(sbn.key)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
        }

        override suspend fun snoozeNotification(
            notificationId: String,
            durationMs: Long,
        ): Result<Unit> {
            val service = requireService()
            val sbn =
                service.getNotifications().firstOrNull {
                    computeNotificationHash(it.key) == notificationId
                } ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
            return try {
                service.snoozeNotificationByKey(sbn.key, durationMs)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
        }

        @Suppress("ReturnCount")
        override suspend fun executeAction(actionId: String): Result<Unit> {
            val (_, action) =
                findActionByHash(actionId)
                    ?: return Result.failure(IllegalArgumentException("Action not found: $actionId"))
            val pendingIntent =
                action.actionIntent
                    ?: return Result.failure(IllegalStateException("Action has no pending intent"))
            return try {
                pendingIntent.send()
                Result.success(Unit)
            } catch (e: PendingIntent.CanceledException) {
                Result.failure(e)
            }
        }

        @Suppress("ReturnCount")
        override suspend fun replyToAction(
            actionId: String,
            text: String,
        ): Result<Unit> {
            val (_, action) =
                findActionByHash(actionId)
                    ?: return Result.failure(IllegalArgumentException("Action not found: $actionId"))
            val remoteInputs =
                action.remoteInputs
                    ?: return Result.failure(IllegalStateException("Action does not accept text input"))
            val pendingIntent =
                action.actionIntent
                    ?: return Result.failure(IllegalStateException("Action has no pending intent"))
            val replyIntent = Intent()
            val resultsBundle = Bundle()
            for (remoteInput in remoteInputs) {
                resultsBundle.putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(remoteInputs, replyIntent, resultsBundle)
            return try {
                pendingIntent.send(context, 0, replyIntent)
                Result.success(Unit)
            } catch (e: PendingIntent.CanceledException) {
                Result.failure(e)
            }
        }

        private fun requireService(): McpNotificationListenerService =
            McpNotificationListenerService.instance
                ?: error("Notification listener service not available")

        private fun toNotificationData(sbn: StatusBarNotification): NotificationData {
            val notification = sbn.notification
            val extras = notification.extras
            val pm = context.packageManager
            val appName =
                try {
                    pm
                        .getApplicationLabel(
                            pm.getApplicationInfo(sbn.packageName, PackageManager.ApplicationInfoFlags.of(0)),
                        ).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    Logger.d(TAG, "App not found for ${sbn.packageName}, using package name")
                    sbn.packageName
                }
            val actions =
                notification.actions?.mapIndexed { index, action ->
                    NotificationActionData(
                        actionId = computeActionHash(sbn.key, index),
                        index = index,
                        title = action.title?.toString() ?: "",
                        acceptsText = action.remoteInputs?.any { !it.isDataOnly } ?: false,
                    )
                } ?: emptyList()
            return NotificationData(
                notificationId = computeNotificationHash(sbn.key),
                key = sbn.key,
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

        private fun findActionByHash(actionId: String): Pair<StatusBarNotification, Notification.Action>? {
            val service = requireService()
            for (sbn in service.getNotifications()) {
                val actions = sbn.notification.actions ?: continue
                for ((index, action) in actions.withIndex()) {
                    if (computeActionHash(sbn.key, index) == actionId) {
                        return Pair(sbn, action)
                    }
                }
            }
            return null
        }

        companion object {
            private const val TAG = "MCP:NotificationProvider"
            private const val HASH_BYTE_LENGTH = 3

            fun computeNotificationHash(key: String): String =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(key.toByteArray())
                    .take(HASH_BYTE_LENGTH)
                    .joinToString("") { "%02x".format(it) }

            fun computeActionHash(
                key: String,
                actionIndex: Int,
            ): String =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$key::$actionIndex".toByteArray())
                    .take(HASH_BYTE_LENGTH)
                    .joinToString("") { "%02x".format(it) }
        }
    }
