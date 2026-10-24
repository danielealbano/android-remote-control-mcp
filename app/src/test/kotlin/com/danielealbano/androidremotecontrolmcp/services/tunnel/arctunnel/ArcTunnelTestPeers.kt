package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ArcTunnelTestEvents : ArcTunnelControlEvents {
    val bound = CountDownLatch(1)
    val boundCount = AtomicInteger()
    val unbound = AtomicInteger()
    val nudges = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>()
    val attemptFailures = CopyOnWriteArrayList<String>()

    /** When true, the FIRST [onBound] throws an unexpected exception (the catch-all reconnect test). */
    @Volatile
    var throwOnFirstBound: Boolean = false

    /** When set, [onRenewNudge] suspends until it completes (single-flight tests). */
    @Volatile
    var nudgeGate: CompletableDeferred<Unit>? = null

    /** When true, [onDialBackFailed] throws after recording (the supervised-child test). */
    @Volatile
    var throwOnDialBackFailed: Boolean = false

    override fun onBound(node: String) {
        val count = boundCount.incrementAndGet()
        bound.countDown()
        check(!(throwOnFirstBound && count == 1)) { "injected unexpected failure" }
    }

    override fun onUnbound() {
        unbound.incrementAndGet()
    }

    override suspend fun onRenewNudge(nonce: String) {
        nudges += nonce
        nudgeGate?.await()
    }

    override fun onDialBackFailed(message: String) {
        failures += message
        check(!throwOnDialBackFailed) { "injected failure" }
    }

    override fun onAttemptFailed(reason: String) {
        attemptFailures += reason
    }
}

/** Loopback peers: a dial-back endpoint answering `101` and a local listener accepting one socket. */
class ArcTunnelTestPeers : AutoCloseable {
    val dial = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val local = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    fun openFrame(): ByteArray =
        ArcTunnelTestServer.frame(
            ArcTunnelFrames.OPEN,
            """{"token":"t","addr":"127.0.0.1:${dial.localPort}","use_https":false,"use_mtls":false}""",
        )

    /** Accepts one dial-back, drains the upgrade request and answers 101; the accepted socket lands in [accepted]. */
    fun acceptUpgrade(
        accepted: AtomicReference<Socket>,
        upgraded: CountDownLatch,
    ) {
        Thread {
            try {
                val c = dial.accept()
                accepted.set(c)
                val input = c.getInputStream()
                var tail = 0
                while (tail != HEADER_END) {
                    val b = input.read()
                    if (b < 0) break
                    tail = (tail shl 8) or b
                }
                c.getOutputStream().apply {
                    write("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
                    flush()
                }
                upgraded.countDown()
            } catch (e: IOException) {
                System.err.println("dial-back peer ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun acceptLocal(
        accepted: AtomicReference<Socket>,
        done: CountDownLatch,
    ) {
        Thread {
            try {
                accepted.set(local.accept())
                done.countDown()
            } catch (e: IOException) {
                System.err.println("local listener ended: $e")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        dial.close()
        local.close()
    }

    private companion object {
        const val HEADER_END = 0x0d0a0d0a
    }
}
