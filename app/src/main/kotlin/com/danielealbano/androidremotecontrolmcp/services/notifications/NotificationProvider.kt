package com.danielealbano.androidremotecontrolmcp.services.notifications

data class NotificationData(
    val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val timestamp: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String?,
    val groupKey: String?,
    val actions: List<NotificationActionData>,
)

data class NotificationActionData(
    val actionId: String,
    val index: Int,
    val title: String,
    val acceptsText: Boolean,
)

interface NotificationProvider {
    fun isReady(): Boolean

    suspend fun getNotifications(
        packageName: String? = null,
        limit: Int? = null,
    ): List<NotificationData>

    suspend fun openNotification(notificationId: String): Result<Unit>

    suspend fun dismissNotification(notificationId: String): Result<Unit>

    suspend fun snoozeNotification(
        notificationId: String,
        durationMs: Long,
    ): Result<Unit>

    suspend fun executeAction(actionId: String): Result<Unit>

    suspend fun replyToAction(
        actionId: String,
        text: String,
    ): Result<Unit>
}
