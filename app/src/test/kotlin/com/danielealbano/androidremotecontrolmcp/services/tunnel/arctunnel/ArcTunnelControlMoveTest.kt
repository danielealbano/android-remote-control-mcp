package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import mockwebserver3.MockResponse
import okhttp3.Dns
import okio.Socket
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.Socket as JavaSocket

/** PROTOCOL.md §7: a MOVE opens a new control connection, the old one serves until the server closes it. */
@DisplayName("ArcTunnelControl MOVE")
class ArcTunnelControlMoveTest : ArcTunnelControlMoveFixture() {
    @Test
    fun `MOVE opens a new connection and keeps the old one`() {
        val other = otherServer()
        val old = ArcTunnelTestStream(moveFrame(addr(other)))
        server.enqueue(old.response())
        other.enqueue(ArcTunnelTestServer.hold())
        start()

        taken(other)
        val closedEarly = old.clientEnded.await(STAYS_OPEN_MS.toLong(), TimeUnit.MILLISECONDS)
        assertFalse(closedEarly, "the old connection was closed")
        old.assertAnswersPing()
        old.end.countDown()
        assertLatch(old.clientEnded, "the client must end the old connection after the server did")
        Thread.sleep(RECONNECT_WINDOW_MS)

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
        val old = ArcTunnelTestStream(moveFrame(addr(refuser)))
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
    fun `MOVE on the new connection is followed, each at once`() {
        val stamped = ConcurrentLinkedQueue<Long>()
        val second = otherServer()
        val third = otherServer()
        server.enqueue(
            scripted { out ->
                stamped.add(System.nanoTime())
                out.write(moveFrame(addr(second)))
                out.flush()
            },
        )
        second.enqueue(
            scripted { out ->
                stamped.add(System.nanoTime())
                out.write(moveFrame(addr(third)))
                out.flush()
            },
        )
        third.enqueue(scripted { stamped.add(System.nanoTime()) })
        start()

        val at = stamps(stamped, 3)

        // Waiting the backoff would take at least its first delay.
        assertTrue(gapMs(at[0], at[1]) < ArcTunnelBackoff.INITIAL_MS, "first move: ${gapMs(at[0], at[1])} ms")
        assertTrue(gapMs(at[1], at[2]) < ArcTunnelBackoff.INITIAL_MS, "second move: ${gapMs(at[1], at[2])} ms")
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
        Thread.sleep(RECONNECT_WINDOW_MS)

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
        val sockets = ArcTunnelTestSocketFactory()
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
}
