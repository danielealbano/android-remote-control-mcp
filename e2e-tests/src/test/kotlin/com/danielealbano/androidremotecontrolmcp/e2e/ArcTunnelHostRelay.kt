package com.danielealbano.androidremotecontrolmcp.e2e

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opaque TCP relay on the HOST: `0.0.0.0:[listenPort]` → `127.0.0.1:[targetPort]`. The phone in redroid can
 * reach a host process through its gateway IP (proven by `FixtureHttpServer`), so the tunnel server's edge
 * (a mapped container port on 127.0.0.1) is exposed to the phone this way. TLS passes through untouched.
 */
class ArcTunnelHostRelay(
    private val listenPort: Int,
    private val targetPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(listenPort, BACKLOG, InetAddress.getByName("0.0.0.0"))

    fun start() {
        Thread({ acceptLoop() }, "arc-tunnel-relay").apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            val upstream =
                try {
                    Socket("127.0.0.1", targetPort)
                } catch (_: IOException) {
                    client.close()
                    continue
                }
            val open = AtomicInteger(2)
            pump(client, upstream, open)
            pump(upstream, client, open)
        }
    }

    /** Copies one direction; on EOF half-closes the peer, and closes both sockets once both directions ended. */
    private fun pump(
        from: Socket,
        to: Socket,
        open: AtomicInteger,
    ) {
        Thread({
            try {
                from.getInputStream().copyTo(to.getOutputStream())
                to.shutdownOutput()
            } catch (_: IOException) {
                // the peer went away: fall through to the close below
            } finally {
                if (open.decrementAndGet() == 0 || from.isClosed || to.isClosed) {
                    closeQuietly(from)
                    closeQuietly(to)
                }
            }
        }, "arc-tunnel-relay-pump").apply { isDaemon = true }.start()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // already closed
        }
    }

    companion object {
        private const val BACKLOG = 64
    }
}
