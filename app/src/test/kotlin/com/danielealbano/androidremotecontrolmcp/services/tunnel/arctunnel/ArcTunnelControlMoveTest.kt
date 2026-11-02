package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.BufferedSink
import okio.Socket
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import java.net.Socket as JavaSocket

/** PROTOCOL.md §7: a MOVE opens a new control connection, the old one serves until the server closes it. */
@DisplayName("ArcTunnelControl MOVE")
class ArcTunnelControlMoveTest {
    private lateinit var server: MockWebServer
    private lateinit var net: ArcTunnelNet
    private lateinit var identity: ArcTunnelIdentity
    private val others = CopyOnWriteArrayList<MockWebServer>()
    private val peers = ArcTunnelTestPeers()
    private val events = ArcTunnelTestEvents()
    private val recorded = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, t -> recorded += t })
    private var control: ArcTunnelControl? = null
    private var job: Job? = null

    @BeforeEach
    fun setUp() {
        server = ArcTunnelTestServer.start()
        net = ArcTunnelTestServer.net(server.port)
        identity = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), net)
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            control?.stop()
            job?.cancelAndJoin()
        }
        peers.close()
        others.forEach { it.close() }
        server.close()
    }

    private fun start(): ArcTunnelControl {
        val handler =
            ArcTunnelOpenHandler(
                ArcTunnelDialBack(
                    ArcTunnelTestServer.CONTROL_HOST,
                    SSLContext.getDefault(),
                    SSLContext.getDefault(),
                    allowLocalTargets = true,
                    keepalive = {},
                ),
                { peers.local.localPort },
                events,
                localConnectWindowMs = LOCAL_WINDOW_MS,
            )
        val c = ArcTunnelControl(net.controlClient(identity), net, ArcTunnelTestServer.CONTROL_HOST, handler, events)
        control = c
        job = scope.launch { c.run() }
        return c
    }

    private fun otherServer(): MockWebServer = ArcTunnelTestServer.start().also { others += it }

    private fun addr(s: MockWebServer): String = "127.0.0.1:${s.port}"

    private fun closedPort(): Int = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun moveFrame(vararg addrs: String): ByteArray =
        ArcTunnelTestServer.frame(
            ArcTunnelFrames.MOVE,
            """{"addrs":[${addrs.joinToString(",") { "\"$it\"" }}]}""",
        )

    /** A stream the [script] writes to; once the script returns it waits for the client's end of the stream. */
    private fun scripted(script: (BufferedSink) -> Unit): MockResponse =
        ArcTunnelTestServer.answer(200) { s: Socket ->
            try {
                val out = s.sink.buffer()
                script(out)
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("scripted control stream ended: $e")
            }
        }

    private fun moveAndHold(vararg addrs: String): MockResponse =
        scripted { out ->
            out.write(moveFrame(*addrs))
            out.flush()
        }

    private fun refuseWith(code: Int): MockResponse =
        ArcTunnelTestServer.answer(code) { s: Socket ->
            try {
                s.sink.close()
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("refused control stream ended: $e")
            }
        }

    private fun taken(s: MockWebServer): RecordedRequest =
        requireNotNull(s.takeRequest(BUDGET_S, TimeUnit.SECONDS)) { "no control request on port ${s.port}" }

    private fun assertStaysOpen(s: JavaSocket) {
        s.soTimeout = STAYS_OPEN_MS
        val read = runCatching { s.getInputStream().read() }
        assertTrue(read.exceptionOrNull() is SocketTimeoutException, "the session must stay open, got $read")
    }

    private fun assertNoStatusChange() {
        assertEquals(0, events.unbound.get())
        assertEquals(emptyList<String>(), events.attemptFailures.toList())
    }

    @Test
    fun `MOVE opens a new connection and keeps the old one`() {
        val other = otherServer()
        val pingNow = CountDownLatch(1)
        val pong = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedByPhone = CountDownLatch(1)
        server.enqueue(
            ArcTunnelTestServer.answer(200) { s: Socket ->
                try {
                    val out = s.sink.buffer()
                    out.write(moveFrame(addr(other)))
                    out.flush()
                    Thread {
                        try {
                            val source = s.source.buffer()
                            var f = ArcTunnelFrames.read(source)
                            while (f != null) {
                                if (f.first == ArcTunnelFrames.PONG) pong.countDown()
                                f = ArcTunnelFrames.read(source)
                            }
                        } catch (e: IOException) {
                            System.err.println("moved-away stream read ended: $e")
                        }
                        if (release.count > 0) closedByPhone.countDown()
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    pingNow.await(BUDGET_S, TimeUnit.SECONDS)
                    out.write(ArcTunnelTestServer.frame(ArcTunnelFrames.PING))
                    out.flush()
                    release.await(BUDGET_S, TimeUnit.SECONDS)
                    out.close()
                } catch (e: IOException) {
                    System.err.println("moved-away stream write ended: $e")
                }
            },
        )
        other.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(other)
        assertFalse(closedByPhone.await(STAYS_OPEN_MS.toLong(), TimeUnit.MILLISECONDS), "the old connection was closed")
        pingNow.countDown()
        assertTrue(pong.await(BUDGET_S, TimeUnit.SECONDS), "the moved-away connection must still answer PING")
        release.countDown()
        Thread.sleep(SETTLE_MS)

        assertEquals(1, server.requestCount, "the end of the old connection must not reconnect")
        assertEquals(2, events.boundCount.get())
        assertNoStatusChange()
    }

    @Test
    fun `MOVE falls back to the control host on a new connection`() {
        server.enqueue(moveAndHold("not-an-address", "127.0.0.1:${closedPort()}"))
        server.enqueue(ArcTunnelTestServer.hold())
        start()

        val first = taken(server)
        val second = taken(server)

        assertNotEquals(first.connectionIndex, second.connectionIndex)
        assertNoStatusChange()
    }

    @Test
    fun `MOVE tries the next address on a refusal`() {
        val refuser = otherServer()
        val target = otherServer()
        server.enqueue(moveAndHold(addr(refuser), addr(target)))
        refuser.enqueue(refuseWith(503))
        target.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(refuser)
        taken(target)

        assertEquals(1, server.requestCount)
        assertNoStatusChange()
    }

    @Test
    fun `MOVE falls back when every address refuses`() {
        val first = otherServer()
        val second = otherServer()
        server.enqueue(moveAndHold(addr(first), addr(second)))
        first.enqueue(refuseWith(503))
        second.enqueue(refuseWith(403))
        server.enqueue(ArcTunnelTestServer.hold())
        start()

        val moved = taken(server)
        taken(first)
        taken(second)

        assertNotEquals(moved.connectionIndex, taken(server).connectionIndex)
    }

    @Test
    fun `a target that never completes TLS is skipped within the read timeout`() {
        val held = CopyOnWriteArrayList<JavaSocket>()
        ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()).use { hole ->
            Thread {
                try {
                    while (true) held += hole.accept()
                } catch (e: IOException) {
                    System.err.println("TLS-silent listener closed: $e")
                }
            }.apply {
                isDaemon = true
                start()
            }
            val target = otherServer()
            server.enqueue(moveAndHold("127.0.0.1:${hole.localPort}", addr(target)))
            target.enqueue(ArcTunnelTestServer.hold())
            start()

            assertNotNull(target.takeRequest(TLS_SILENT_BUDGET_S, TimeUnit.SECONDS))
            assertTrue(held.isNotEmpty(), "the TLS-silent target must have been tried first")
            held.forEach { it.close() }
        }
    }

    @Test
    fun `a moved connection stays up through 12 seconds of silence`() =
        runBlocking {
            val target = otherServer()
            server.enqueue(moveAndHold(addr(target)))
            target.enqueue(ArcTunnelTestServer.hold())
            start()
            taken(target)

            delay(SILENCE_MS)

            assertEquals(2, events.boundCount.get())
            assertEquals(1, target.requestCount)
            assertEquals(1, server.requestCount)
            assertNoStatusChange()
        }

    @Test
    fun `MOVE on the new connection is followed`() {
        val second = otherServer()
        val third = otherServer()
        server.enqueue(moveAndHold(addr(second)))
        second.enqueue(moveAndHold(addr(third)))
        third.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(third)

        assertEquals(1, second.requestCount)
        assertNoStatusChange()
    }

    @Test
    fun `a second MOVE on a moved connection is ignored`() {
        val target = otherServer()
        val other = otherServer()
        val targetBound = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        server.enqueue(
            scripted { out ->
                out.write(moveFrame(addr(target)))
                out.flush()
                targetBound.await(BUDGET_S, TimeUnit.SECONDS)
                out.write(moveFrame(addr(other)))
                out.flush()
                secondSent.countDown()
            },
        )
        target.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(target)
        targetBound.countDown()
        assertTrue(secondSent.await(BUDGET_S, TimeUnit.SECONDS))
        Thread.sleep(SETTLE_MS)

        assertEquals(0, other.requestCount)
    }

    @Test
    fun `a MOVE answered without a bind is followed`() {
        val target = otherServer()
        server.enqueue(
            ArcTunnelTestServer.answer(200) { s: Socket ->
                try {
                    val out = s.sink.buffer()
                    out.write(moveFrame(addr(target)))
                    out.flush()
                    out.close()
                } catch (e: IOException) {
                    System.err.println("draining answer write ended: $e")
                }
            },
        )
        target.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(target)
        Thread.sleep(SETTLE_MS)

        assertEquals(1, server.requestCount)
        assertNoStatusChange()
    }

    @Test
    fun `a MOVE from the fallback is followed after the backoff, which does not reset`() {
        val stamps = ConcurrentLinkedQueue<Long>()
        val unreachable = "127.0.0.1:${closedPort()}"
        repeat(FALLBACK_STREAMS) {
            server.enqueue(
                scripted { out ->
                    stamps.add(System.nanoTime())
                    out.write(moveFrame(unreachable))
                    out.flush()
                },
            )
        }
        start()

        repeat(FALLBACK_STREAMS) { taken(server) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_S)
        while (stamps.size < FALLBACK_STREAMS && System.nanoTime() < deadline) Thread.sleep(POLL_MS)

        val at = stamps.toList()
        assertEquals(FALLBACK_STREAMS, at.size)
        val firstWaitMs = TimeUnit.NANOSECONDS.toMillis(at[2] - at[1])
        val secondWaitMs = TimeUnit.NANOSECONDS.toMillis(at[3] - at[2])
        assertTrue(firstWaitMs >= FIRST_BACKOFF_MIN_MS, "first wait $firstWaitMs ms")
        assertTrue(secondWaitMs >= SECOND_BACKOFF_MIN_MS, "second wait $secondWaitMs ms")
        assertEquals(0, events.unbound.get())
    }

    @Test
    fun `a lost MOVE target connection reconnects through the control host`() {
        val target = otherServer()
        val next = otherServer()
        server.enqueue(moveAndHold(addr(target), addr(next)))
        target.enqueue(
            scripted { out ->
                // An oversized frame tears the bound stream down.
                out.write(byteArrayOf(ArcTunnelFrames.PING.toByte(), 0x7f, -1, -1, -1))
                out.flush()
            },
        )
        server.enqueue(ArcTunnelTestServer.hold())
        next.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(server)
        taken(target)
        taken(server)

        assertEquals(0, next.requestCount, "a lost bound connection must not try the next address")
        assertEquals(1, events.unbound.get())
        assertEquals(1, events.attemptFailures.size)
    }

    @Test
    fun `a session on the new connection survives the end of the old one`() {
        val target = otherServer()
        val remote = AtomicReference<JavaSocket>()
        val local = AtomicReference<JavaSocket>()
        val upgraded = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        peers.acceptUpgrade(remote, upgraded)
        peers.acceptLocal(local, accepted)
        val release = CountDownLatch(1)
        val oldEnded = CountDownLatch(1)
        server.enqueue(
            ArcTunnelTestServer.answer(200) { s: Socket ->
                try {
                    val out = s.sink.buffer()
                    out.write(moveFrame(addr(target)))
                    out.flush()
                    release.await(BUDGET_S, TimeUnit.SECONDS)
                    out.close()
                } catch (e: IOException) {
                    System.err.println("moved-away stream write ended: $e")
                } finally {
                    oldEnded.countDown()
                }
            },
        )
        target.enqueue(
            scripted { out ->
                out.write(peers.openFrame())
                out.flush()
            },
        )
        start()

        assertTrue(upgraded.await(BUDGET_S, TimeUnit.SECONDS))
        assertTrue(accepted.await(BUDGET_S, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(oldEnded.await(BUDGET_S, TimeUnit.SECONDS))

        assertStaysOpen(remote.get())
        assertStaysOpen(local.get())
        remote.get().close()
        local.get().close()
    }

    @Test
    fun `a session on the moved-away connection survives the end of the new one`() {
        val target = otherServer()
        val remote = AtomicReference<JavaSocket>()
        val local = AtomicReference<JavaSocket>()
        val upgraded = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        peers.acceptUpgrade(remote, upgraded)
        peers.acceptLocal(local, accepted)
        val targetBound = CountDownLatch(1)
        val releaseTarget = CountDownLatch(1)
        server.enqueue(
            scripted { out ->
                out.write(moveFrame(addr(target)))
                out.flush()
                targetBound.await(BUDGET_S, TimeUnit.SECONDS)
                out.write(peers.openFrame())
                out.flush()
            },
        )
        target.enqueue(
            ArcTunnelTestServer.answer(200) { s: Socket ->
                try {
                    targetBound.countDown()
                    releaseTarget.await(BUDGET_S, TimeUnit.SECONDS)
                    s.sink.buffer().close()
                } catch (e: IOException) {
                    System.err.println("target stream ended: $e")
                }
            },
        )
        server.enqueue(ArcTunnelTestServer.hold())
        start()

        assertTrue(upgraded.await(BUDGET_S, TimeUnit.SECONDS), "an OPEN on the moved-away connection must dial back")
        assertTrue(accepted.await(BUDGET_S, TimeUnit.SECONDS))
        releaseTarget.countDown()
        taken(server)
        taken(server)

        assertStaysOpen(remote.get())
        assertStaysOpen(local.get())
        assertEquals(1, events.unbound.get())
        remote.get().close()
        local.get().close()
    }

    @Test
    fun `stop ends the moved-away connection at once`() =
        runBlocking {
            val target = otherServer()
            val oldEnded = CountDownLatch(1)
            server.enqueue(
                ArcTunnelTestServer.answer(200) { s: Socket ->
                    try {
                        val out = s.sink.buffer()
                        out.write(moveFrame(addr(target)))
                        out.flush()
                        s.source.buffer().readByteArray()
                    } catch (e: IOException) {
                        System.err.println("moved-away stream reset: $e")
                    } finally {
                        oldEnded.countDown()
                    }
                },
            )
            target.enqueue(ArcTunnelTestServer.hold())
            val c = start()
            taken(target)
            assertFalse(oldEnded.await(STAYS_OPEN_MS.toLong(), TimeUnit.MILLISECONDS), "the old connection ended early")

            c.stop()

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).join() }
            assertTrue(oldEnded.await(BUDGET_S, TimeUnit.SECONDS))
            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `cancelling the scope ends the moved-away connection`() =
        runBlocking {
            val target = otherServer()
            server.enqueue(moveAndHold(addr(target)))
            target.enqueue(ArcTunnelTestServer.hold())
            start()
            taken(target)

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).cancelAndJoin() }

            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `a reconnect after a refusal opens a new connection`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .doNotReadRequestBody()
                .build(),
        )
        server.enqueue(ArcTunnelTestServer.hold())
        start()

        val first = taken(server)
        val second = taken(server)

        assertNotEquals(first.connectionIndex, second.connectionIndex)
    }

    @Test
    fun `a reconnect after a stream ended opens a new connection`() {
        server.enqueue(
            ArcTunnelTestServer.answer(200) { s: Socket ->
                try {
                    s.sink.buffer().close()
                    s.source.buffer().readByteArray()
                } catch (e: IOException) {
                    System.err.println("ended control stream: $e")
                }
            },
        )
        server.enqueue(ArcTunnelTestServer.hold())
        start()

        val first = taken(server)
        val second = taken(server)

        assertNotEquals(first.connectionIndex, second.connectionIndex)
        assertEquals(1, events.unbound.get())
    }

    private companion object {
        const val BACKLOG = 50
        const val LOCAL_WINDOW_MS = 300L
        const val BUDGET_S = 10L
        const val TLS_SILENT_BUDGET_S = 20L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
        const val STAYS_OPEN_MS = 500
        const val SILENCE_MS = 12_000L
        const val STOP_BUDGET_MS = 2_000L
        const val FALLBACK_STREAMS = 4
        const val FIRST_BACKOFF_MIN_MS = 900L
        const val SECOND_BACKOFF_MIN_MS = 1_900L
    }
}
