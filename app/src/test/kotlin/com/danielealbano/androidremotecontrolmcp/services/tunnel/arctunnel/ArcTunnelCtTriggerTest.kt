package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelCtTrigger")
class ArcTunnelCtTriggerTest {
    private val longAgo = 0L
    private val now = 10L * ArcTunnelCtTrigger.MIN_INTERVAL_MS

    @Test
    fun `fires only for roll 0 of 100`() {
        assertTrue(ArcTunnelCtTrigger.shouldCheck(enabled = true, roll = 0, nowMs = now, lastCheckMs = longAgo))
        assertFalse(ArcTunnelCtTrigger.shouldCheck(enabled = true, roll = 1, nowMs = now, lastCheckMs = longAgo))
        assertFalse(ArcTunnelCtTrigger.shouldCheck(enabled = true, roll = 99, nowMs = now, lastCheckMs = longAgo))
    }

    @Test
    fun `respects the minimum interval`() {
        val minute = 60_000L
        val early = now - 29 * minute
        val due = now - 30 * minute

        assertFalse(ArcTunnelCtTrigger.shouldCheck(enabled = true, roll = 0, nowMs = now, lastCheckMs = early))
        assertTrue(ArcTunnelCtTrigger.shouldCheck(enabled = true, roll = 0, nowMs = now, lastCheckMs = due))
    }

    @Test
    fun `disabled never fires`() {
        assertFalse(ArcTunnelCtTrigger.shouldCheck(enabled = false, roll = 0, nowMs = now, lastCheckMs = longAgo))
    }
}
