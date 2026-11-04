package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The notification for a problem the tunnel service's status page reports. */
interface ArcTunnelServiceAlerts {
    fun show(problem: ArcTunnelServiceProblem)

    fun clear()
}

@Singleton
class ArcTunnelServiceAlertsImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ArcTunnelServiceAlerts {
        /** Posts or updates the notification, alerting only the first time; no-op without POST_NOTIFICATIONS. */
        override fun show(problem: ArcTunnelServiceProblem) {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (permission != PackageManager.PERMISSION_GRANTED) return
            val statusPage = Intent(Intent.ACTION_VIEW, Uri.parse(ArcTunnelStatusPage.PAGE_URL))
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE,
                    statusPage,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val body = body(problem)
            val notification =
                NotificationCompat
                    .Builder(context, McpApplication.ARC_TUNNEL_STATUS_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.server_arc_tunnel_status_title))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        override fun clear() {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun body(problem: ArcTunnelServiceProblem): String =
            if (problem.affected.isEmpty()) {
                context.getString(R.string.server_arc_tunnel_status_body, problem.state)
            } else {
                context.getString(
                    R.string.server_arc_tunnel_status_body_affected,
                    problem.state,
                    problem.affected.joinToString(", "),
                )
            }

        private companion object {
            const val NOTIFICATION_ID = 1007
            const val REQUEST_CODE = 7
        }
    }
