package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/**
 * Counts the outstanding start commands of the enrollment service and tells which `startId` to stop with:
 * `stopSelfResult` stops a service only when given the LAST delivered id, so a request that finishes early
 * (a duplicate, ignored by `execute`) must never stop with its own id. Main-thread only.
 */
internal class ArcTunnelStartTracker {
    private var active = 0
    private var lastStartId = 0

    fun started(startId: Int) {
        active++
        lastStartId = startId
    }

    /** The id to pass to `stopSelfResult`, or null while other requests are still running. */
    fun finished(): Int? {
        active--
        return if (active == 0) lastStartId else null
    }
}
