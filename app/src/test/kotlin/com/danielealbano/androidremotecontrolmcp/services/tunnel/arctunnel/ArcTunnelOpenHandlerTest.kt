package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

@DisplayName("ArcTunnelOpenHandler")
class ArcTunnelOpenHandlerTest {
    private val peers = ArcTunnelTestPeers()
    private val events = ArcTunnelTestEvents()
    private val sessions = ArcTunnelSessions {}
    private val dialBack =
        ArcTunnelDialBack(
            ArcTunnelTestServer.CONTROL_HOST,
            SSLContext.getDefault(),
            SSLContext.getDefault(),
            allowLocalTargets = true,
        )

    @AfterEach
    fun tearDown() {
        peers.close()
    }

    private fun handler(
        localPort: Int,
        windowMs: Long = WINDOW_MS,
    ) = ArcTunnelOpenHandler(dialBack, { localPort }, events, windowMs)

    private fun open(
        addr: String = "127.0.0.1:${peers.dial.localPort}",
        token: String = "t",
    ) = ArcTunnelOpenPayload(token = token, addr = addr)

    private fun closedPort(): Int = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** True when [socket] reaches EOF (or is reset) within [timeoutMs]. */
    private fun seesEof(
        socket: Socket,
        timeoutMs: Int = EOF_BUDGET_MS,
    ): Boolean {
        socket.soTimeout = timeoutMs
        return try {
            socket.getInputStream().read() < 0
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(LATCH_BUDGET_MS) { while (latch.count > 0) delay(POLL_MS) }
    }

    @Test
    fun `a completed splice releases every session`() =
        runBlocking {
            val remote = AtomicReference<Socket>()
            val local = AtomicReference<Socket>()
            val upgraded = CountDownLatch(1)
            val accepted = CountDownLatch(1)
            peers.acceptUpgrade(remote, upgraded)
            peers.acceptLocal(local, accepted)
            val job = launch(Dispatchers.IO) { handler(peers.local.localPort).handle(open(), sessions) }
            awaitLatch(upgraded)
            awaitLatch(accepted)

            remote.get().close()
            local.get().close()
            withTimeout(LATCH_BUDGET_MS) { job.join() }

            assertEquals(0, sessions.size())
        }

    @Test
    fun `cancellation inside the local-connect window closes the dial-back`() =
        runBlocking {
            val remote = AtomicReference<Socket>()
            val upgraded = CountDownLatch(1)
            peers.acceptUpgrade(remote, upgraded)
            val job = launch(Dispatchers.IO) { handler(closedPort(), LONG_WINDOW_MS).handle(open(), sessions) }
            awaitLatch(upgraded)
            delay(CANCEL_AFTER_MS)

            job.cancelAndJoin()

            assertTrue(seesEof(remote.get()))
            assertEquals(0, sessions.size())
        }

    @Test
    fun `cancellation during a splice closes both sockets`() =
        runBlocking {
            val remote = AtomicReference<Socket>()
            val local = AtomicReference<Socket>()
            val upgraded = CountDownLatch(1)
            val accepted = CountDownLatch(1)
            peers.acceptUpgrade(remote, upgraded)
            peers.acceptLocal(local, accepted)
            val job = launch(Dispatchers.IO) { handler(peers.local.localPort).handle(open(), sessions) }
            awaitLatch(upgraded)
            awaitLatch(accepted)

            withTimeout(LATCH_BUDGET_MS) { job.cancelAndJoin() }

            assertTrue(seesEof(remote.get()))
            assertTrue(seesEof(local.get()))
            assertEquals(0, sessions.size())
        }

    @Test
    fun `a failed dial-back registers nothing`() =
        runBlocking {
            handler(peers.local.localPort).handle(open(addr = "127.0.0.1:${closedPort()}"), sessions)

            assertEquals(1, events.failures.size)
            assertEquals(0, sessions.size())
            peers.local.soTimeout = NOTHING_ACCEPTED_MS
            assertTrue(runCatching { peers.local.accept().close() }.exceptionOrNull() is SocketTimeoutException)
        }

    @Test
    fun `a failed local connect closes and releases the remote sockets`() =
        runBlocking {
            val remote = AtomicReference<Socket>()
            val upgraded = CountDownLatch(1)
            peers.acceptUpgrade(remote, upgraded)

            handler(closedPort()).handle(open(), sessions)

            awaitLatch(upgraded)
            assertTrue(seesEof(remote.get()))
            assertEquals(1, events.failures.size)
            assertEquals(0, sessions.size())
        }

    @Test
    fun `invalid OPEN is ignored`() =
        runBlocking {
            handler(peers.local.localPort).handle(open(token = ""), sessions)

            peers.dial.soTimeout = NOTHING_ACCEPTED_MS
            assertTrue(runCatching { peers.dial.accept().close() }.exceptionOrNull() is SocketTimeoutException)
            assertTrue(events.failures.isEmpty())
        }

    private companion object {
        const val BACKLOG = 50
        const val WINDOW_MS = 300L
        const val LONG_WINDOW_MS = 5_000L
        const val CANCEL_AFTER_MS = 200L
        const val EOF_BUDGET_MS = 1_000
        const val LATCH_BUDGET_MS = 5_000L
        const val POLL_MS = 20L
        const val NOTHING_ACCEPTED_MS = 300
    }
}
