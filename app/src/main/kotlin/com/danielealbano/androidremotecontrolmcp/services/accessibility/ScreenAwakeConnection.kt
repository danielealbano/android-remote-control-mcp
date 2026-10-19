package com.danielealbano.androidremotecontrolmcp.services.accessibility

/** Retains the server's request across accessibility reconnects and rejects stale queued operations. */
internal class ScreenAwakeConnection(
    private val dispatch: (() -> Unit) -> Unit,
) {
    @Volatile
    private var overlay: ScreenAwakeOverlay? = null

    @Volatile
    private var requested = false

    fun connect(next: ScreenAwakeOverlay) {
        overlay?.close()
        overlay = next
        next.setRequested(requested)
    }

    fun disconnect(previous: ScreenAwakeOverlay) {
        previous.close()
        if (overlay === previous) overlay = null
    }

    fun setRequested(awake: Boolean) {
        requested = awake
        val target = overlay
        dispatch {
            if (overlay === target) {
                target?.setRequested(requested)
            }
        }
    }
}
