package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Idempotent ownership of screen/keyguard change broadcasts for one server service. */
internal class ScreenStateObserver(
    private val context: Context,
    private val onScreenOff: () -> Unit = {},
    private val onChanged: () -> Unit,
) {
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
                onChanged()
            }
        }

    fun start() {
        if (!registered) {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
    }

    fun close() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }
}
