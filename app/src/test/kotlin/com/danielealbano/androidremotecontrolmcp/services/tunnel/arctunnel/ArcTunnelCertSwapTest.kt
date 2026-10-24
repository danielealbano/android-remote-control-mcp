package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelCertSwap")
class ArcTunnelCertSwapTest {
    private var now = START_MS
    private val swap = ArcTunnelCertSwap { now }
    private val certValid = START_MS + 7 * DAY_MS

    @Test
    fun `not due without a schedule`() {
        assertFalse(swap.isDue(activeSplices = 0, servedNotAfterMs = certValid))
    }

    @Test
    fun `due at once when idle`() {
        swap.schedule()

        assertTrue(swap.isDue(activeSplices = 0, servedNotAfterMs = certValid))
    }

    @Test
    fun `waits while busy within 24h`() {
        swap.schedule()
        now += HOUR_MS

        assertFalse(swap.isDue(activeSplices = 1, servedNotAfterMs = certValid))
    }

    @Test
    fun `due after 24h even when busy`() {
        swap.schedule()
        now += ArcTunnelCertSwap.MAX_WAIT_MS

        assertTrue(swap.isDue(activeSplices = 1, servedNotAfterMs = certValid))
    }

    @Test
    fun `due when the served cert expires within 5 minutes even when busy`() {
        swap.schedule()
        now += HOUR_MS

        assertTrue(swap.isDue(activeSplices = 1, servedNotAfterMs = now + ArcTunnelCertSwap.EXPIRY_MARGIN_MS))
    }

    @Test
    fun `done clears only the request it started with`() {
        swap.schedule()
        val first = swap.pending()
        now += HOUR_MS
        swap.schedule()

        swap.done(first)

        assertEquals(START_MS + HOUR_MS, swap.pending())
        assertTrue(swap.isDue(activeSplices = 0, servedNotAfterMs = certValid))
    }

    private companion object {
        const val START_MS = 1_000_000_000L
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
    }
}
