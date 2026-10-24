package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@DisplayName("ArcTunnelSessions")
class ArcTunnelSessionsTest {
    private val errors = CopyOnWriteArrayList<IOException>()
    private val sessions = ArcTunnelSessions { errors += it }

    private class Counting(
        private val failure: IOException? = null,
    ) : Closeable {
        var closed = 0

        override fun close() {
            closed++
            failure?.let { throw it }
        }
    }

    @Test
    fun `add before close registers`() {
        val c = Counting()

        assertTrue(sessions.add(c))
        assertEquals(1, sessions.size())
        assertEquals(0, c.closed)
    }

    @Test
    fun `add after closeAll closes and returns false`() {
        sessions.closeAll()
        val c = Counting()

        assertFalse(sessions.add(c))
        assertEquals(1, c.closed)
        assertEquals(0, sessions.size())
    }

    @Test
    fun `closeAll closes every open session once`() {
        val a = Counting()
        val b = Counting()
        sessions.add(a)
        sessions.add(b)

        sessions.closeAll()
        sessions.closeAll()

        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
        assertEquals(0, sessions.size())
    }

    @Test
    fun `release removes`() {
        val c = Counting()
        sessions.add(c)

        sessions.release(c)

        assertEquals(0, sessions.size())
        sessions.closeAll()
        assertEquals(0, c.closed)
    }

    @Test
    fun `close errors go to the callback`() {
        val failure = IOException("boom")
        val failing = Counting(failure)
        val other = Counting()
        sessions.add(failing)
        sessions.add(other)

        sessions.closeAll()

        assertEquals(listOf(failure), errors.toList())
        assertEquals(1, other.closed)
    }
}
