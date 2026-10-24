package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Current CT alert for the UI banner; the notification is posted by [ArcTunnelCtNotifier]. */
interface ArcTunnelCtAlerts {
    val alert: StateFlow<ArcTunnelCtAlert?>

    fun raise(alert: ArcTunnelCtAlert)

    /** Re-shows an undismissed alert after a process restart, without a new notification. */
    fun restore(alert: ArcTunnelCtAlert)

    fun dismiss()
}

@Singleton
class ArcTunnelCtAlertsImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ArcTunnelCtAlerts {
        private val _alert = MutableStateFlow<ArcTunnelCtAlert?>(null)
        override val alert: StateFlow<ArcTunnelCtAlert?> = _alert.asStateFlow()

        override fun raise(alert: ArcTunnelCtAlert) {
            _alert.value = alert
            ArcTunnelCtNotifier.post(context, alert.fqdn)
        }

        override fun restore(alert: ArcTunnelCtAlert) {
            _alert.value = alert
        }

        override fun dismiss() {
            _alert.value = null
            ArcTunnelCtNotifier.cancel(context)
        }
    }
