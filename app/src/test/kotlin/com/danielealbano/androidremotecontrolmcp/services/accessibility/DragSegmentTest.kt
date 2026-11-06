package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The screen is 720x1600: its 1/8 margins leave x 90..630 and y 200..1400. */
@DisplayName("dragSegment")
class DragSegmentTest {
    private val screen = ScreenInfo(720, 1600, 320, ScreenInfo.ORIENTATION_PORTRAIT)

    /** E = 1200, so the inset E/16 leaves y 275..1325. */
    private val list = BoundsData(0, 200, 720, 1400)

    @Test
    fun `a forward drag is centred on the given centre and moves the finger up`() {
        assertEquals(
            DragSegment(360f, 1100f, 360f, 500f),
            dragSegment(list, 360f to 800f, 600f, ScrollDirection.DOWN, screen),
        )
    }

    @Test
    fun `a backward drag moves the finger down`() {
        assertEquals(
            DragSegment(360f, 500f, 360f, 1100f),
            dragSegment(list, 360f to 800f, 600f, ScrollDirection.UP, screen),
        )
    }

    @Test
    fun `a segment past either end of the inset container is shifted inside it`() {
        assertEquals(
            DragSegment(360f, 875f, 360f, 275f),
            dragSegment(list, 360f to 300f, 600f, ScrollDirection.DOWN, screen),
        )
        assertEquals(
            DragSegment(360f, 1325f, 360f, 725f),
            dragSegment(list, 360f to 1300f, 600f, ScrollDirection.DOWN, screen),
        )
    }

    @Test
    fun `the length is clamped to the span left`() {
        assertEquals(
            DragSegment(360f, 1325f, 360f, 275f),
            dragSegment(list, 360f to 800f, 5000f, ScrollDirection.DOWN, screen),
        )
    }

    @Test
    fun `a container reaching the screen's bottom edge is clamped by the screen margin`() {
        // E = 800: inset 50 leaves 850..1550, and the screen margin cuts it at 1400.
        val lower = BoundsData(0, 800, 720, 1600)

        assertEquals(
            DragSegment(360f, 1400f, 360f, 850f),
            dragSegment(lower, 360f to 1200f, 900f, ScrollDirection.DOWN, screen),
        )
    }

    @Test
    fun `the cross coordinate near a side edge is clamped into the screen margin`() {
        assertEquals(
            DragSegment(90f, 1100f, 90f, 500f),
            dragSegment(list, 20f to 800f, 600f, ScrollDirection.DOWN, screen),
        )
    }

    @Test
    fun `a horizontal drag runs along the x axis`() {
        // E = 720: inset 45 leaves 45..675, and the screen margins leave 90..630.
        val carousel = BoundsData(0, 600, 720, 800)

        assertEquals(
            DragSegment(510f, 700f, 210f, 700f),
            dragSegment(carousel, 360f to 700f, 300f, ScrollDirection.RIGHT, screen),
        )
        assertEquals(
            DragSegment(210f, 700f, 510f, 700f),
            dragSegment(carousel, 360f to 700f, 300f, ScrollDirection.LEFT, screen),
        )
    }

    @Test
    fun `no segment fits a container inside the screen margin`() {
        assertNull(dragSegment(BoundsData(0, 0, 720, 150), 360f to 75f, 100f, ScrollDirection.DOWN, screen))
    }
}
