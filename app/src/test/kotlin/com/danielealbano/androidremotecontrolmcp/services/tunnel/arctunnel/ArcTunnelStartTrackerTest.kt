package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelStartTracker")
class ArcTunnelStartTrackerTest {
    private val tracker = ArcTunnelStartTracker()

    @Test
    fun `finished returns the id once the only request ends`() {
        tracker.started(1)

        assertEquals(1, tracker.finished())
    }

    @Test
    fun `out-of-order completion returns the latest start id`() {
        tracker.started(1)
        tracker.started(2)

        assertNull(tracker.finished())
        assertEquals(2, tracker.finished())
    }

    @Test
    fun `a new start after a stop starts over`() {
        tracker.started(1)
        assertEquals(1, tracker.finished())

        tracker.started(5)

        assertEquals(5, tracker.finished())
    }
}
