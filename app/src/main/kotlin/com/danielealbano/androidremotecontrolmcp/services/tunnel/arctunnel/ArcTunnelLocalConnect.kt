package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import kotlin.coroutines.coroutineContext

object ArcTunnelLocalConnect {
    /**
     * The address the tunnel listener binds (`TunnelListenerHost`). Never `InetAddress.getLoopbackAddress()`:
     * on Android that is `::1`, which the IPv4-only listener refuses.
     */
    const val HOST = "127.0.0.1"
    const val WINDOW_MS = 10_000L
    private const val RETRY_MS = 50L
    private const val NANOS_PER_MS = 1_000_000L

    /** Connects to the loopback listener, retrying a refused connect for [windowMs]; never leaks a socket. */
    suspend fun open(
        port: Int,
        windowMs: Long = WINDOW_MS,
    ): Socket {
        val deadline = System.nanoTime() + windowMs * NANOS_PER_MS
        while (true) {
            // The loopback connect is instantaneous and runs non-cancellable so a cancellation can never
            // discard a connected socket; cancellation is honoured right after it instead.
            val attempt =
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Socket(HOST, port).apply { configure() } }
                }
            val socket = attempt.getOrNull()
            if (socket != null) {
                closeIfCancelled(socket)
                return socket
            }
            val failure = requireNotNull(attempt.exceptionOrNull())
            if (failure !is ConnectException || System.nanoTime() >= deadline) throw failure
            delay(RETRY_MS)
        }
    }

    /** A failed option set must not leak the connected socket. */
    private fun Socket.configure() {
        try {
            tcpNoDelay = true
        } catch (e: SocketException) {
            ArcTunnelSockets.closeQuietly(this)
            throw e
        }
    }

    private suspend fun closeIfCancelled(socket: Socket) {
        if (!coroutineContext.isActive) ArcTunnelSockets.closeQuietly(socket)
        coroutineContext.ensureActive()
    }
}
