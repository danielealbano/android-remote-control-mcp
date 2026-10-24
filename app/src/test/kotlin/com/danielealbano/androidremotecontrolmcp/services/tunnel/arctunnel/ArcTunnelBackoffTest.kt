package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelBackoff")
class ArcTunnelBackoffTest {
    @Test
    fun `schedule is 1 2 4 8 16 16`() {
        val backoff = ArcTunnelBackoff()

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L), List(6) { backoff.next() })
    }

    @Test
    fun `reset returns to 1s`() {
        val backoff = ArcTunnelBackoff()
        repeat(3) { backoff.next() }

        backoff.reset()

        assertEquals(1_000L, backoff.next())
    }
}
