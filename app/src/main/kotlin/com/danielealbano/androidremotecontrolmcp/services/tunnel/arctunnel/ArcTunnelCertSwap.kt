package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.util.concurrent.atomic.AtomicLong

/**
 * After a renewal, swap at the first idle moment within 24 h, or at once when the SERVED cert expires
 * within 5 min.
 */
class ArcTunnelCertSwap(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pendingSince = AtomicLong(NONE)

    /** A renewal installed a new chain; (re)starts the 24 h window. */
    fun schedule() {
        pendingSince.set(now())
    }

    /** The pending request's timestamp, or [NONE]. */
    fun pending(): Long = pendingSince.get()

    fun isDue(
        activeSplices: Int,
        servedNotAfterMs: Long,
    ): Boolean {
        val since = pendingSince.get()
        if (since == NONE) return false
        val t = now()
        return activeSplices == 0 || t - since >= MAX_WAIT_MS || servedNotAfterMs - t <= EXPIRY_MARGIN_MS
    }

    /** Clears the request that was [pending] when the swap started; a newer request stays pending. */
    fun done(started: Long) {
        pendingSince.compareAndSet(started, NONE)
    }

    companion object {
        const val NONE = 0L
        const val MAX_WAIT_MS = 24L * 60 * 60 * 1000
        const val EXPIRY_MARGIN_MS = 5L * 60 * 1000
    }
}
