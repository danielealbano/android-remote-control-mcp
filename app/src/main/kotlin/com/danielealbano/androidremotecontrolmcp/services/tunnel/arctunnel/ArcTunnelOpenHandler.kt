package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ArcTunnelOpenHandler(
    private val dialBack: ArcTunnelDialBack,
    private val localPort: () -> Int,
    private val events: ArcTunnelControlEvents,
    private val localConnectWindowMs: Long = ArcTunnelLocalConnect.WINDOW_MS,
) {
    private val splices = AtomicInteger()

    /** Open dial-back splices right now (the idle signal of the certificate swap). */
    fun activeSplices(): Int = splices.get()

    /** Dial back FIRST, then connect locally, then splice; every registered socket is released at the end. */
    suspend fun handle(
        open: ArcTunnelOpenPayload,
        sessions: ArcTunnelSessions,
    ) {
        if (!open.isValid()) {
            Log.w(TAG, "invalid OPEN ignored: addr=${open.addr} use_https=${open.useHttps} use_mtls=${open.useMtls}")
            return
        }
        val remote = openRemote(open, sessions) ?: return
        var spliced = false
        try {
            val local = if (sessions.add(remote.socket)) openLocal(open.addr, remote, sessions) else null
            if (local != null) {
                spliced = true
                splice(local, remote, sessions)
            }
        } finally {
            // A cancellation before the splice (e.g. inside the local-connect window) must not leave the
            // dial-back open; a finished splice closed both sides itself.
            if (!spliced) closeRemote(remote)
            sessions.release(remote.socket)
            sessions.release(remote.raw)
        }
    }

    private suspend fun splice(
        local: Socket,
        remote: ArcTunnelDialBack.Conn,
        sessions: ArcTunnelSessions,
    ) {
        splices.incrementAndGet()
        try {
            ArcTunnelSplice.run(local, remote)
        } finally {
            splices.decrementAndGet()
            sessions.release(local)
        }
    }

    private suspend fun openRemote(
        open: ArcTunnelOpenPayload,
        sessions: ArcTunnelSessions,
    ): ArcTunnelDialBack.Conn? =
        try {
            dialBack.open(ArcTunnelDialBack.Target(open.token, open.addr, open.useHttps, open.useMtls), sessions)
        } catch (e: IOException) {
            events.onDialBackFailed("dial-back to ${open.addr} failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            events.onDialBackFailed("dial-back to ${open.addr} refused: ${e.message}")
            null
        }

    /** Null when the local connect failed or the registry was shut meanwhile; the remote sockets are closed then. */
    private suspend fun openLocal(
        addr: String,
        remote: ArcTunnelDialBack.Conn,
        sessions: ArcTunnelSessions,
    ): Socket? {
        val local =
            try {
                ArcTunnelLocalConnect.open(localPort(), localConnectWindowMs)
            } catch (e: IOException) {
                events.onDialBackFailed("local listener unreachable for a dial-back to $addr: ${e.message}")
                null
            }
        val added = local != null && sessions.add(local)
        if (!added) {
            closeRemote(remote)
            local?.let { ArcTunnelSockets.closeQuietly(it) }
        }
        return if (added) local else null
    }

    private fun closeRemote(remote: ArcTunnelDialBack.Conn) {
        ArcTunnelSockets.closeQuietly(remote.socket)
        ArcTunnelSockets.closeQuietly(remote.raw)
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelOpen"
    }
}
