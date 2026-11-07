package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.net.Socket as JavaSocket

/** PROTOCOL.md §7: the connection that carried a MOVE keeps serving, with its own sessions, until it ends. */
@DisplayName("ArcTunnelControl moved-away connection")
class ArcTunnelControlMovedAwayTest : ArcTunnelControlMoveFixture() {
    @Test
    fun `a second MOVE on a moved connection is ignored and the connection keeps serving`() {
        val target = otherServer()
        val other = otherServer()
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
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
    fun `a silent moved-away connection is dropped and nothing is reported for it`() {
        val target = otherServer()
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
        val moved = ArcTunnelTestStream()
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
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
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
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
        val moved = ArcTunnelTestStream()
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
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
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
            val old = ArcTunnelTestStream(moveFrame(addr(target)))
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
            val old = ArcTunnelTestStream(moveFrame(addr(target)))
            server.enqueue(old.response())
            target.enqueue(ArcTunnelTestServer.hold())
            start()
            taken(target)

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).cancelAndJoin() }

            assertLatch(old.clientEnded, "the cancellation must end the moved-away connection")
            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `a scope cancelled before the hand-off still closes the moved-away connection`() =
        runBlocking {
            val sockets = ArcTunnelTestSocketFactory()
            val target = otherServer()
            server.enqueue(moveAndHold(addr(target)))
            // Runs before the frames are read: by then the MOVE is buffered, and it is delivered despite the cancel.
            events.onBoundHook = {
                Thread.sleep(SETTLE_MS)
                requireNotNull(job).cancel()
            }
            start(
                net
                    .controlClient(identity)
                    .newBuilder()
                    .socketFactory(sockets)
                    .build(),
            )

            withTimeout(STOP_BUDGET_MS) { requireNotNull(job).join() }

            assertTrue(sockets.sockets[0].isClosed, "the connection must be cleaned up")
            assertEquals(emptyList<Throwable>(), recorded.toList())
        }

    @Test
    fun `the end of the moved-away connection closes its socket and only its own`() {
        val sockets = ArcTunnelTestSocketFactory()
        val target = otherServer()
        val old = ArcTunnelTestStream(moveFrame(addr(target)))
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
}
