package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object ArcTunnelSplice {
    private const val TAG = "MCP:ArcTunnelSplice"
    private const val BUFFER = 16384

    /**
     * Copies both directions until either side ends, then closes both sockets; returns when both copies ended.
     * The wait is a cancellable join: a cancellation closes both sockets, which unblocks the two copies.
     */
    suspend fun run(
        local: Socket,
        remote: ArcTunnelDialBack.Conn,
    ) = coroutineScope {
        val closed = AtomicBoolean(false)
        val closeBoth = {
            if (closed.compareAndSet(false, true)) {
                ArcTunnelSockets.closeQuietly(local)
                ArcTunnelSockets.closeQuietly(remote.socket)
            }
        }
        val up =
            launch(Dispatchers.IO) {
                copy({ local.getInputStream() }, { remote.socket.getOutputStream() }, "upstream")
                closeBoth()
            }
        val down =
            launch(Dispatchers.IO) {
                copy({ remote.input }, { local.getOutputStream() }, "downstream")
                closeBoth()
            }
        try {
            joinAll(up, down)
        } finally {
            closeBoth()
        }
    }

    private fun copy(
        source: () -> InputStream,
        sink: () -> OutputStream,
        direction: String,
    ) {
        try {
            val src = source()
            val dst = sink()
            val buf = ByteArray(BUFFER)
            var n = src.read(buf)
            while (n >= 0) {
                dst.write(buf, 0, n)
                dst.flush()
                n = src.read(buf)
            }
        } catch (e: IOException) {
            Log.d(TAG, "$direction copy ended", e)
        }
    }
}
