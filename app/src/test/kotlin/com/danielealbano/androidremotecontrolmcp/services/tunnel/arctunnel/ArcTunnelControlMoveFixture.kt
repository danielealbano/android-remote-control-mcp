package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.BufferedSink
import okio.Socket
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

/** What the tests of the MOVE handling (PROTOCOL.md §7) of [ArcTunnelControl] share. */
abstract class ArcTunnelControlMoveFixture {
    protected lateinit var server: MockWebServer
    protected lateinit var net: ArcTunnelNet
    protected lateinit var identity: ArcTunnelIdentity
    protected val others = CopyOnWriteArrayList<MockWebServer>()
    protected val peers = ArcTunnelTestPeers()
    protected val events = ArcTunnelTestEvents()
    protected val recorded = CopyOnWriteArrayList<Throwable>()
    protected val scope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, t -> recorded += t })
    protected var control: ArcTunnelControl? = null
    protected var job: Job? = null

    /** A dial-back spliced to the local listener: its two far ends, as the peers see them. */
    protected class Session(
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

    protected fun start(
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
                { _, _ -> AutoCloseable {} },
                LOCAL_WINDOW_MS,
            )
        val c = ArcTunnelControl(client, net, ArcTunnelTestServer.CONTROL_HOST, handler, events)
        c.watchdogClock = watchdogClock
        control = c
        job = scope.launch { c.run() }
        return c
    }

    protected fun otherServer(): MockWebServer = ArcTunnelTestServer.start().also { others += it }

    protected fun addr(s: MockWebServer): String = "127.0.0.1:${s.port}"

    protected fun closedPort(): Int = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()).use { it.localPort }

    protected fun moveFrame(vararg addrs: String): ByteArray =
        ArcTunnelTestServer.frame(
            ArcTunnelFrames.MOVE,
            """{"addrs":[${addrs.joinToString(",") { "\"$it\"" }}]}""",
        )

    /** A stream the [script] writes to; once the script returns it waits for the client's end of the stream. */
    protected fun scripted(script: (BufferedSink) -> Unit): MockResponse =
        ArcTunnelTestServer.answer(200) { s: Socket ->
            try {
                val out = s.sink.buffer()
                script(out)
                s.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("scripted control stream ended: $e")
            }
        }

    protected fun moveAndHold(vararg addrs: String): MockResponse =
        scripted { out ->
            out.write(moveFrame(*addrs))
            out.flush()
        }

    protected fun refuseWith(
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

    protected fun taken(s: MockWebServer): RecordedRequest =
        requireNotNull(s.takeRequest(BUDGET_S, TimeUnit.SECONDS)) { "no control request on port ${s.port}" }

    protected fun awaitThat(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUDGET_S)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for $what")
            Thread.sleep(POLL_MS)
        }
    }

    protected fun assertLatch(
        latch: CountDownLatch,
        what: String,
    ) {
        assertTrue(latch.await(BUDGET_S, TimeUnit.SECONDS), what)
    }

    protected fun assertStaysOpen(s: JavaSocket) {
        s.soTimeout = STAYS_OPEN_MS
        val read = runCatching { s.getInputStream().read() }
        assertTrue(read.exceptionOrNull() is SocketTimeoutException, "the session must stay open, got $read")
    }

    protected fun assertEnded(s: JavaSocket) {
        s.soTimeout = BUDGET_S.toInt() * MS_PER_S
        val read = runCatching { s.getInputStream().read() }
        assertTrue(read.isFailure || read.getOrNull() == -1, "the session must have ended, got $read")
        assertFalse(read.exceptionOrNull() is SocketTimeoutException, "the session must have ended, got $read")
    }

    protected fun assertNoStatusChange() {
        assertEquals(0, events.unbound.get())
        assertEquals(emptyList<String>(), events.attemptFailures.toList())
    }

    /** Waits until the server has stamped [count] requests and returns the stamps. */
    protected fun stamps(
        stamps: ConcurrentLinkedQueue<Long>,
        count: Int,
    ): List<Long> {
        awaitThat("$count control requests") { stamps.size >= count }
        return stamps.toList()
    }

    protected fun gapMs(
        from: Long,
        to: Long,
    ): Long = TimeUnit.NANOSECONDS.toMillis(to - from)

    /** A session opened by an OPEN on [old] once its MOVE was followed to [target]. */
    protected fun sessionOnMovedAway(
        old: ArcTunnelTestStream,
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

    protected companion object {
        const val BACKLOG = 50
        const val LOCAL_WINDOW_MS = 300L
        const val BUDGET_S = 10L
        const val MS_PER_S = 1_000
        const val TLS_SILENT_BUDGET_S = 20L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L

        /** A reconnect would have arrived by now: the first backoff wait, plus a margin. */
        const val RECONNECT_WINDOW_MS = ArcTunnelBackoff.INITIAL_MS + SETTLE_MS
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
