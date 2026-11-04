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
    /** Posts or updates the notification, alerting only the first time. Answers false if it cannot be posted. */
    fun show(problem: ArcTunnelServiceProblem): Boolean

    fun clear()
}

@Singleton
class ArcTunnelServiceAlertsImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ArcTunnelServiceAlerts {
        override fun show(problem: ArcTunnelServiceProblem): Boolean {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (permission != PackageManager.PERMISSION_GRANTED) return false
            val statusPage =
                Intent(Intent.ACTION_VIEW, Uri.parse(ArcTunnelStatusPage.PAGE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            return true
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

        companion object {
            /** Only a running tunnel session keeps it up, so a new process removes what a dead one left behind. */
            fun clearLeftover(context: Context) {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }

            const val NOTIFICATION_ID = 1007
            private const val REQUEST_CODE = 7
        }
    }
