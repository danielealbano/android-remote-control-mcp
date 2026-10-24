package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/** Control reconnect delays: 1, 2, 4, 8, 16 s, then 16 s; [reset] after a bound control stream. */
class ArcTunnelBackoff {
    private var current = INITIAL_MS

    /** Returns the delay to wait before the next attempt and advances the schedule. */
    fun next(): Long {
        val delay = current
        current = (current * 2).coerceAtMost(MAX_MS)
        return delay
    }

    fun reset() {
        current = INITIAL_MS
    }

    companion object {
        const val INITIAL_MS = 1_000L
        const val MAX_MS = 16_000L
    }
}
