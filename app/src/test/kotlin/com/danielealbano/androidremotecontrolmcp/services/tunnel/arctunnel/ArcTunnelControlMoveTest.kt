package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
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

    /**
     * A control stream the test drives: frames in [outgoing] are written and PONGs counted in [pongs]; [end] ends it
     * from the server side, [clientEnded] tells that the client ended its side.
     */
    private class DrivenStream(
        vararg first: ByteArray,
    ) {
        val outgoing = LinkedBlockingQueue(first.toList())
        val pongs = Semaphore(0)
        val end = CountDownLatch(1)
        val clientEnded = CountDownLatch(1)

        fun response(): MockResponse =
            ArcTunnelTestServer.answer(200) { socket: Socket ->
                val reader =
                    Thread {
                        try {
                            val source = socket.source.buffer()
                            var frame = ArcTunnelFrames.read(source)
                            while (frame != null) {
                                if (frame.first == ArcTunnelFrames.PONG) pongs.release()
                                frame = ArcTunnelFrames.read(source)
                            }
                        } catch (e: IOException) {
                            System.err.println("driven stream read ended: $e")
                        } finally {
                            clientEnded.countDown()
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                try {
                    val sink = socket.sink.buffer()
                    while (end.count > 0 && clientEnded.count > 0) {
                        val frame = outgoing.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                        sink.write(frame)
                        sink.flush()
                    }
                    if (end.count == 0L) sink.close()
                } catch (e: IOException) {
                    System.err.println("driven stream write ended: $e")
                }
                reader.join(JOIN_MS)
            }

        fun assertAnswersPing() {
            outgoing += ArcTunnelTestServer.frame(ArcTunnelFrames.PING)
            assertTrue(pongs.tryAcquire(BUDGET_S, TimeUnit.SECONDS), "the connection must still answer PING")
        }
    }

    /** Records the raw sockets of a client, in the order the connections were opened. */
    private class RecordingSocketFactory : SocketFactory() {
        val sockets = CopyOnWriteArrayList<JavaSocket>()

        override fun createSocket(): JavaSocket = JavaSocket().also { sockets += it }

        override fun createSocket(
            host: String,
            port: Int,
        ): JavaSocket = throw UnsupportedOperationException()

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): JavaSocket = throw UnsupportedOperationException()

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): JavaSocket = throw UnsupportedOperationException()

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): JavaSocket = throw UnsupportedOperationException()
    }

    /** A dial-back spliced to the local listener: its two far ends, as the peers see them. */
    private class Session(
        val remote: JavaSocket,
        val local: JavaSocket,
    )

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

    private fun start(
        client: OkHttpClient = net.controlClient(identity),
        watchdogClock: () -> Long = System::nanoTime,
    ): ArcTunnelControl {
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
        val c = ArcTunnelControl(client, net, ArcTunnelTestServer.CONTROL_HOST, handler, events)
        c.watchdogClock = watchdogClock
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

    private fun refuseWith(
        code: Int,
        onRequest: () -> Unit = {},
    ): MockResponse =
        ArcTunnelTestServer.answer(code) { s: Socket ->
            onRequest()
            try {
                s.sink.close()
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("refused control stream ended: $e")
            }
        }

    private fun taken(s: MockWebServer): RecordedRequest =
        requireNotNull(s.takeRequest(BUDGET_S, TimeUnit.SECONDS)) { "no control request on port ${s.port}" }

    private fun awaitThat(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_S)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for $what")
            Thread.sleep(POLL_MS)
        }
    }

    private fun assertLatch(
        latch: CountDownLatch,
        what: String,
    ) {
        assertTrue(latch.await(BUDGET_S, TimeUnit.SECONDS), what)
    }

    private fun assertStaysOpen(s: JavaSocket) {
        s.soTimeout = STAYS_OPEN_MS
        val read = runCatching { s.getInputStream().read() }
        assertTrue(read.exceptionOrNull() is SocketTimeoutException, "the session must stay open, got $read")
    }

    private fun assertEnded(s: JavaSocket) {
        s.soTimeout = BUDGET_S.toInt() * MS_PER_S
        val read = runCatching { s.getInputStream().read() }
        assertTrue(read.isFailure || read.getOrNull() == -1, "the session must have ended, got $read")
        assertFalse(read.exceptionOrNull() is SocketTimeoutException, "the session must have ended, got $read")
    }

    private fun assertNoStatusChange() {
        assertEquals(0, events.unbound.get())
        assertEquals(emptyList<String>(), events.attemptFailures.toList())
    }

    /** Waits until the server has stamped [count] requests and returns the stamps. */
    private fun stamps(
        stamps: ConcurrentLinkedQueue<Long>,
        count: Int,
    ): List<Long> {
        awaitThat("$count control requests") { stamps.size >= count }
        return stamps.toList()
    }

    private fun gapMs(
        from: Long,
        to: Long,
    ): Long = TimeUnit.NANOSECONDS.toMillis(to - from)

    /** A session opened by an OPEN on [old] once its MOVE was followed to [target]. */
    private fun sessionOnMovedAway(
        old: DrivenStream,
        target: MockWebServer,
    ): Session {
        val remote = AtomicReference<JavaSocket>()
        val local = AtomicReference<JavaSocket>()
        val upgraded = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        peers.acceptUpgrade(remote, upgraded)
        peers.acceptLocal(local, accepted)
        taken(target)
        old.outgoing += peers.openFrame()
        assertLatch(upgraded, "an OPEN on the moved-away connection must dial back")
        assertLatch(accepted, "the dial-back must reach the local listener")
        return Session(remote.get(), local.get())
    }

    @Test
    fun `MOVE opens a new connection and keeps the old one`() {
        val other = otherServer()
        val old = DrivenStream(moveFrame(addr(other)))
        server.enqueue(old.response())
        other.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(other)
        val closedEarly = old.clientEnded.await(STAYS_OPEN_MS.toLong(), TimeUnit.MILLISECONDS)
        assertFalse(closedEarly, "the old connection was closed")
        old.assertAnswersPing()
        old.end.countDown()
        assertLatch(old.clientEnded, "the client must end the old connection after the server did")
        Thread.sleep(SETTLE_MS)

        assertEquals(1, server.requestCount, "the end of the old connection must not reconnect")
        assertEquals(2, events.boundCount.get())
        assertNoStatusChange()
    }

    @Test
    fun `a MOVE target is dialed without resolving the control host`() {
        val lookups = AtomicInteger()
        val dns =
            Dns {
                lookups.incrementAndGet()
                listOf(InetAddress.getLoopbackAddress())
            }
        val target = otherServer()
        server.enqueue(moveAndHold(addr(target)))
        target.enqueue(ArcTunnelTestServer.hold())
        start(
            net
                .controlClient(identity)
                .newBuilder()
                .dns(dns)
                .build(),
        )

        taken(target)

        assertEquals(1, lookups.get())
    }

    @Test
    fun `MOVE falls back to the control host on a new connection, resolved again`() {
        val lookups = AtomicInteger()
        val dns =
            Dns {
                lookups.incrementAndGet()
                listOf(InetAddress.getLoopbackAddress())
            }
        server.enqueue(moveAndHold("not-an-address", "127.0.0.1:${closedPort()}"))
        server.enqueue(ArcTunnelTestServer.hold())
        start(
            net
                .controlClient(identity)
                .newBuilder()
                .dns(dns)
                .build(),
        )

        val first = taken(server)
        val second = taken(server)

        assertNotEquals(first.connectionIndex, second.connectionIndex)
        assertEquals(2, lookups.get())
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
    fun `when the addresses and the fallback refuse the status reconnects while the old connection serves`() {
        val refuser = otherServer()
        val old = DrivenStream(moveFrame(addr(refuser)))
        server.enqueue(old.response())
        refuser.enqueue(refuseWith(503))
        server.enqueue(refuseWith(503))
        start()

        awaitThat("the status to leave Connected") { events.unbound.get() == 1 }

        assertEquals(listOf(REFUSED_REASON), events.attemptFailures.toList())
        old.assertAnswersPing()
    }

    @Test
    fun `a target that never completes TLS does not wait for the watchdog`() {
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
    fun `a moved connection stays up through 12 seconds of silence`() {
        val target = otherServer()
        server.enqueue(moveAndHold(addr(target)))
        target.enqueue(ArcTunnelTestServer.hold())
        start()
        taken(target)

        Thread.sleep(SILENCE_MS)

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
    fun `a second MOVE on a moved connection is ignored and the connection keeps serving`() {
        val target = otherServer()
        val other = otherServer()
        val old = DrivenStream(moveFrame(addr(target)))
        server.enqueue(old.response())
        target.enqueue(ArcTunnelTestServer.hold())
        start()
        taken(target)

        old.outgoing += moveFrame(addr(other))
        old.assertAnswersPing()

        assertEquals(0, other.requestCount)
        assertNoStatusChange()
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
        val stamped = ConcurrentLinkedQueue<Long>()
        val unreachable = "127.0.0.1:${closedPort()}"
        repeat(FALLBACK_STREAMS) {
            server.enqueue(
                scripted { out ->
                    stamped.add(System.nanoTime())
                    out.write(moveFrame(unreachable))
                    out.flush()
                },
            )
        }
        start()

        val at = stamps(stamped, FALLBACK_STREAMS)

        assertTrue(gapMs(at[1], at[2]) >= FIRST_BACKOFF_MIN_MS, "first wait ${gapMs(at[1], at[2])} ms")
        assertTrue(gapMs(at[2], at[3]) >= SECOND_BACKOFF_MIN_MS, "second wait ${gapMs(at[2], at[3])} ms")
        assertEquals(0, events.unbound.get())
    }

    @Test
    fun `a draining answer on a reconnect does not reset the backoff`() {
        val stamped = ConcurrentLinkedQueue<Long>()
        val unreachable = "127.0.0.1:${closedPort()}"
        val draining = {
            scripted { out ->
                stamped.add(System.nanoTime())
                out.write(moveFrame(unreachable))
                out.flush()
            }
        }
        val refused = { refuseWith(503) { stamped.add(System.nanoTime()) } }
        server.enqueue(draining())
        server.enqueue(refused())
        server.enqueue(draining())
        server.enqueue(refused())
        server.enqueue(draining())
        start()

        val at = stamps(stamped, RECONNECT_STREAMS)

        assertTrue(gapMs(at[1], at[2]) >= FIRST_BACKOFF_MIN_MS, "first wait ${gapMs(at[1], at[2])} ms")
        assertTrue(gapMs(at[3], at[4]) >= SECOND_BACKOFF_MIN_MS, "second wait ${gapMs(at[3], at[4])} ms")
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
    fun `a silent moved-away connection is dropped and nothing is reported for it`() {
        val target = otherServer()
        val old = DrivenStream(moveFrame(addr(target)))
        val moved = DrivenStream()
        server.enqueue(old.response())
        target.enqueue(moved.response())
        server.enqueue(ArcTunnelTestServer.hold())
        val clockOffset = AtomicLong()
        start(watchdogClock = { System.nanoTime() + clockOffset.get() })
        taken(target)

        // Every watchdog poll now sees more than 90 s of silence, on both connections.
        clockOffset.set(SILENCE_NANOS)
        assertLatch(old.clientEnded, "the watchdog must drop the moved-away connection")
        assertLatch(moved.clientEnded, "the watchdog must drop the new connection")
        clockOffset.set(0)

        awaitThat("the reconnect") { events.boundCount.get() == 3 }
        assertEquals(listOf(SILENT_REASON), events.attemptFailures.toList())
        assertEquals(1, events.unbound.get())
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
        val old = DrivenStream(moveFrame(addr(target)))
        server.enqueue(old.response())
        target.enqueue(
            scripted { out ->
                out.write(peers.openFrame())
                out.flush()
            },
        )
        start()

        assertLatch(upgraded, "an OPEN on the new connection must dial back")
        assertLatch(accepted, "the dial-back must reach the local listener")
        old.end.countDown()
        assertLatch(old.clientEnded, "the client must end the old connection after the server did")

        assertStaysOpen(remote.get())
        assertStaysOpen(local.get())
        remote.get().close()
        local.get().close()
    }

    @Test
    fun `a session on the moved-away connection survives the end of the new one`() {
        val target = otherServer()
        val old = DrivenStream(moveFrame(addr(target)))
        val moved = DrivenStream()
        server.enqueue(old.response())
        target.enqueue(moved.response())
        server.enqueue(ArcTunnelTestServer.hold())
        start()
        val session = sessionOnMovedAway(old, target)

        moved.end.countDown()
        taken(server)
        taken(server)

        assertStaysOpen(session.remote)
        assertStaysOpen(session.local)
        assertEquals(1, events.unbound.get())
        session.remote.close()
        session.local.close()
    }

    @Test
    fun `the end of the moved-away connection ends its own sessions`() {
        val target = otherServer()
        val old = DrivenStream(moveFrame(addr(target)))
        server.enqueue(old.response())
        target.enqueue(ArcTunnelTestServer.hold())
        start()
        val session = sessionOnMovedAway(old, target)

        old.end.countDown()

        assertEnded(session.remote)
        assertEnded(session.local)
        assertNoStatusChange()
    }

    @Test
    fun `stop ends the moved-away connection and its sessions at once`() =
        runBlocking {
            val target = otherServer()
            val old = DrivenStream(moveFrame(addr(target)))
            server.enqueue(old.response())
            target.enqueue(ArcTunnelTestServer.hold())
            val c = start()
            val session = sessionOnMovedAway(old, target)

            c.stop()

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).join() }
            assertLatch(old.clientEnded, "stop must end the moved-away connection")
            assertEnded(session.remote)
            assertEnded(session.local)
            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `cancelling the scope ends the moved-away connection`() =
        runBlocking {
            val target = otherServer()
            val old = DrivenStream(moveFrame(addr(target)))
            server.enqueue(old.response())
            target.enqueue(ArcTunnelTestServer.hold())
            start()
            taken(target)

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).cancelAndJoin() }

            assertLatch(old.clientEnded, "the cancellation must end the moved-away connection")
            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `the end of the moved-away connection closes its socket and only its own`() {
        val sockets = RecordingSocketFactory()
        val target = otherServer()
        val old = DrivenStream(moveFrame(addr(target)))
        server.enqueue(old.response())
        target.enqueue(ArcTunnelTestServer.hold())
        start(
            net
                .controlClient(identity)
                .newBuilder()
                .socketFactory(sockets)
                .build(),
        )
        taken(target)

        old.end.countDown()

        awaitThat("the moved-away connection's socket to close") { sockets.sockets[0].isClosed }
        assertEquals(2, sockets.sockets.size)
        assertFalse(sockets.sockets[1].isClosed)
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
    fun `a reconnect after a stream ended opens a new connection and closes the old socket`() {
        val sockets = RecordingSocketFactory()
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
        start(
            net
                .controlClient(identity)
                .newBuilder()
                .socketFactory(sockets)
                .build(),
        )

        val first = taken(server)
        val second = taken(server)

        assertNotEquals(first.connectionIndex, second.connectionIndex)
        assertTrue(sockets.sockets[0].isClosed)
        assertEquals(1, events.unbound.get())
    }

    private companion object {
        const val BACKLOG = 50
        const val LOCAL_WINDOW_MS = 300L
        const val BUDGET_S = 10L
        const val MS_PER_S = 1_000
        const val TLS_SILENT_BUDGET_S = 20L
        const val POLL_MS = 10L
        const val JOIN_MS = 1_000L
        const val SETTLE_MS = 300L
        const val STAYS_OPEN_MS = 500
        const val SILENCE_MS = 12_000L
        const val STOP_BUDGET_MS = 2_000L
        const val FALLBACK_STREAMS = 4
        const val RECONNECT_STREAMS = 5
        const val FIRST_BACKOFF_MIN_MS = 900L
        const val SECOND_BACKOFF_MIN_MS = 1_900L
        const val REFUSED_REASON = "the tunnel service refused the connection (HTTP 503)"
        const val SILENT_REASON = "no data from the tunnel service for 90 s"
        val SILENCE_NANOS: Long = TimeUnit.SECONDS.toNanos(91)
    }
}
