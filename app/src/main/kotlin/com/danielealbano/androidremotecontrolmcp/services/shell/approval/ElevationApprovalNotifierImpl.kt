package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.ElevationApprovalActivity
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val NOTIFICATION_TAG = "shell_elevation"

class ElevationApprovalNotifierImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ElevationApprovalNotifier {
        override fun canNotify(): Boolean {
            val manager = NotificationManagerCompat.from(context)
            val importance = manager.getNotificationChannel(McpApplication.ELEVATION_APPROVAL_CHANNEL_ID)?.importance
            return PermissionUtils.isNotificationPermissionGranted(context) &&
                manager.areNotificationsEnabled() &&
                importance != null &&
                importance != NotificationManager.IMPORTANCE_NONE
        }

        override fun post(request: PendingElevation) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, request.id, build(request))
        }

        override fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, id)

        private fun build(request: PendingElevation): Notification {
            val title = context.getString(R.string.elevation_notification_title)
            val publicVersion =
                NotificationCompat
                    .Builder(context, McpApplication.ELEVATION_APPROVAL_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .build()
            val details =
                context.getString(
                    R.string.elevation_notification_details,
                    request.displayCommand,
                    request.justification,
                )
            return NotificationCompat
                .Builder(context, McpApplication.ELEVATION_APPROVAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(request.displayCommand)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOngoing(true)
                .setContentIntent(openIntent(request.id))
                .setDeleteIntent(decisionIntent(request.id, approve = false))
                .addAction(action(request.id, approve = true))
                .addAction(action(request.id, approve = false))
                .build()
        }

        private fun action(
            id: Int,
            approve: Boolean,
        ): NotificationCompat.Action {
            val label = context.getString(if (approve) R.string.elevation_approve else R.string.elevation_deny)
            return NotificationCompat.Action
                .Builder(R.drawable.ic_notification, label, decisionIntent(id, approve))
                .setAuthenticationRequired(true)
                .build()
        }

        private fun decisionIntent(
            id: Int,
            approve: Boolean,
        ): PendingIntent {
            val intent =
                Intent(context, ElevationApprovalReceiver::class.java)
                    .putExtra(ElevationApprovalIntents.EXTRA_REQUEST_ID, id)
                    .putExtra(ElevationApprovalIntents.EXTRA_APPROVED, approve)
            return PendingIntent.getBroadcast(
                context,
                id * 2 + (if (approve) 1 else 0),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun openIntent(id: Int): PendingIntent {
            val intent =
                Intent(context, ElevationApprovalActivity::class.java)
                    .putExtra(ElevationApprovalIntents.EXTRA_REQUEST_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
