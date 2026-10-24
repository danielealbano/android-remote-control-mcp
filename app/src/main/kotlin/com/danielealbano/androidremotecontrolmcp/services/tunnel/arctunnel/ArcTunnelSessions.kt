package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The sockets opened through one control stream; [closeAll] ends them when the stream ends. */
class ArcTunnelSessions(
    private val onCloseError: (IOException) -> Unit,
) {
    private val open = ConcurrentHashMap.newKeySet<Closeable>()

    @Volatile
    private var shut = false

    fun size(): Int = open.size

    /** Registers [c]; false (and [c] closed) when the registry is already shut. */
    fun add(c: Closeable): Boolean {
        open.add(c)
        if (!shut) return true
        if (open.remove(c)) close(c)
        return false
    }

    fun release(c: Closeable) {
        open.remove(c)
    }

    fun closeAll() {
        shut = true
        for (c in open.toList()) if (open.remove(c)) close(c)
    }

    private fun close(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
            onCloseError(e)
        }
    }
}
