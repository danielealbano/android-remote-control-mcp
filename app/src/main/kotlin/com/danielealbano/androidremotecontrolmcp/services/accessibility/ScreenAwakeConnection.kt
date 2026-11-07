package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/** Retains the server's request across accessibility reconnects and rejects stale queued operations. */
internal class ScreenAwakeConnection(
    private val dispatch: (() -> Unit) -> Unit,
    @Volatile private var canRequest: () -> Boolean,
) {
    @Volatile
    private var overlay: ScreenAwakeOverlay? = null

    @Volatile
    private var requested = false

    fun connect(next: ScreenAwakeOverlay) {
        overlay?.close()
        overlay = next
        requested = next.setRequested(requested && canRequest())
    }

    fun disconnect(previous: ScreenAwakeOverlay) {
        previous.close()
        if (overlay === previous) overlay = null
    }

    fun setRequested(awake: Boolean) {
        requested = awake && canRequest()
        val target = overlay
        dispatch {
            if (overlay === target && target != null) {
                requested = target.setRequested(requested && canRequest())
            }
        }
    }

    fun refresh() {
        if (!canRequest()) {
            setRequested(false)
        } else {
            overlay?.let { requested = it.refresh() }
        }
    }

    /** Observe for the server lifetime, including intervals with no accessibility service connected. */
    fun observeScreenState(context: Context): ScreenStateObserver {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        canRequest = { power.isInteractive && !keyguard.isKeyguardLocked }
        return ScreenStateObserver(appContext, onScreenOff = { setRequested(false) }) { refresh() }
    }
}
