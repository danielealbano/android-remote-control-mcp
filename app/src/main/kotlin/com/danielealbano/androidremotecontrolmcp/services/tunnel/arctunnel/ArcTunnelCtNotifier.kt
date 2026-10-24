package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.MainActivity

/** Posts/cancels the single Certificate Transparency alert notification. */
object ArcTunnelCtNotifier {
    const val NOTIFICATION_ID = 1004
    private const val REQUEST_CODE = 4

    /** Posts (or updates) the alert; no-op without POST_NOTIFICATIONS. */
    fun post(
        context: Context,
        fqdn: String,
    ) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED) return
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, McpApplication.ARC_TUNNEL_ALERT_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_arc_tunnel_ct_title))
                .setContentText(context.getString(R.string.notification_arc_tunnel_ct_body, fqdn))
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
