package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NodeCutRule")
class NodeCutRuleTest {
    private val list = BoundsData(0, 200, 720, 1400)
    private val vertical = ScrollContainer(list, ScrollReach(up = true, down = true, left = false, right = false))
    private val all = ScrollReach(up = true, down = true, left = true, right = true)

    private fun cut(
        node: BoundsData,
        vararg containers: ScrollContainer,
        fullSize: Pair<Int, Int>? = null,
        justShown: Boolean = false,
        movedTowardStart: Set<Edge> = emptySet(),
    ): Map<Edge, ScrollContainer> = cutEdges(node, containers.toList(), fullSize, justShown, movedTowardStart)

    @Nested
    @DisplayName("cutEdges")
    inner class CutEdges {
        @Test
        fun `every edge at its container's edge is cut when the container scrolls that way`() {
            val box = ScrollContainer(BoundsData(100, 300, 600, 900), all)

            assertEquals(setOf(Edge.TOP), cut(BoundsData(150, 300, 550, 500), box).keys)
            assertEquals(setOf(Edge.BOTTOM), cut(BoundsData(150, 700, 550, 900), box).keys)
            assertEquals(setOf(Edge.LEFT), cut(BoundsData(100, 400, 300, 500), box).keys)
            assertEquals(setOf(Edge.RIGHT), cut(BoundsData(400, 400, 600, 500), box).keys)
        }

        @Test
        fun `full-width rows in a vertical list are not cut`() {
            assertEquals(emptyMap<Edge, ScrollContainer>(), cut(BoundsData(0, 500, 720, 700), vertical))
        }

        @Test
        fun `an edge is cut only toward a direction the container can still scroll`() {
            val atTop = ScrollContainer(list, ScrollReach(up = false, down = true, left = false, right = false))

            assertEquals(emptyMap<Edge, ScrollContainer>(), cut(BoundsData(0, 200, 720, 400), atTop))
            assertEquals(mapOf(Edge.BOTTOM to atTop), cut(BoundsData(0, 1300, 720, 1400), atTop))
        }

        @Test
        fun `a carousel item is cut by the carousel on its axis and by the outer list on the other`() {
            val carousel = ScrollContainer(BoundsData(0, 1300, 720, 1400), ScrollReach(false, false, false, true))

            val edges = cut(BoundsData(420, 1300, 720, 1400), carousel, vertical)

            assertEquals(mapOf(Edge.RIGHT to carousel, Edge.BOTTOM to vertical), edges)
        }

        @Test
        fun `a full size equal to the visible size clears the cut`() {
            assertEquals(
                emptyMap<Edge, ScrollContainer>(),
                cut(BoundsData(0, 1200, 720, 1400), vertical, fullSize = 720 to 200),
            )
        }

        @Test
        fun `a full size never adds a cut`() {
            assertEquals(
                emptyMap<Edge, ScrollContainer>(),
                cut(BoundsData(0, 500, 720, 700), vertical, fullSize = 720 to 900),
            )
            assertEquals(
                setOf(Edge.BOTTOM),
                cut(BoundsData(0, 1300, 720, 1400), vertical, fullSize = 720 to 200).keys,
            )
        }

        @Test
        fun `right after a show without a full size only the axes the node fills stay cut`() {
            val fitting = cut(BoundsData(0, 1200, 720, 1400), vertical, justShown = true)
            val filling = cut(list, vertical, justShown = true)

            assertEquals(emptyMap<Edge, ScrollContainer>(), fitting)
            assertEquals(setOf(Edge.TOP, Edge.BOTTOM), filling.keys)
        }

        @Test
        fun `edges moved toward the start are cleared right after a show only`() {
            val pager = ScrollContainer(BoundsData(0, 0, 720, 1600), ScrollReach(false, false, true, true))
            val node = BoundsData(0, 200, 720, 1400)

            val full = 1000 to 2000
            val top = setOf(Edge.TOP)

            val shown = cut(node, vertical, pager, fullSize = full, justShown = true, movedTowardStart = top)
            val notShown = cut(node, vertical, pager, fullSize = full, movedTowardStart = top)

            assertEquals(setOf(Edge.BOTTOM, Edge.LEFT, Edge.RIGHT), shown.keys)
            assertEquals(setOf(Edge.TOP, Edge.BOTTOM, Edge.LEFT, Edge.RIGHT), notShown.keys)
        }

        @Test
        fun `a Compose node aligned to its container's top is cut there when not just shown`() {
            assertEquals(setOf(Edge.TOP), cut(BoundsData(0, 200, 720, 400), vertical).keys)
        }

        @Test
        fun `a node with no scrollable ancestor is never cut`() {
            assertEquals(emptyMap<Edge, ScrollContainer>(), cut(BoundsData(0, 0, 720, 1600)))
        }
    }

    @Nested
    @DisplayName("ScrollReach.of")
    inner class Reach {
        private fun container(
            vararg ids: Int,
            rows: Int? = null,
            columns: Int? = null,
        ): AccessibilityNodeInfo {
            val info =
                if (rows != null && columns != null) {
                    mockk<AccessibilityNodeInfo.CollectionInfo> {
                        every { rowCount } returns rows
                        every { columnCount } returns columns
                    }
                } else {
                    null
                }
            val actions = ids.map { mockAction(it, null) }
            return mockk {
                every { actionList } returns actions
                every { collectionInfo } returns info
            }
        }

        @Test
        fun `directional actions give exactly those directions`() {
            val node =
                container(android.R.id.accessibilityActionScrollDown, android.R.id.accessibilityActionScrollRight)

            assertEquals(ScrollReach(up = false, down = true, left = false, right = true), ScrollReach.of(node))
        }

        @Test
        fun `forward and backward on a vertical collection reach up and down`() {
            val node =
                container(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    rows = 10,
                    columns = 1,
                )

            assertEquals(ScrollReach(up = true, down = true, left = false, right = false), ScrollReach.of(node))
        }

        @Test
        fun `forward on a horizontal collection reaches right`() {
            val node = container(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, rows = 1, columns = 5)

            assertEquals(ScrollReach(up = false, down = false, left = false, right = true), ScrollReach.of(node))
        }

        @Test
        fun `backward with no collection info reaches up and left`() {
            val node = container(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

            assertEquals(ScrollReach(up = true, down = false, left = true, right = false), ScrollReach.of(node))
        }

        @Test
        fun `no scroll action reaches nowhere`() {
            val none = ScrollReach(up = false, down = false, left = false, right = false)

            assertEquals(none, ScrollReach.of(container()))
        }
    }

    @Nested
    @DisplayName("fullSize")
    inner class FullSize {
        private fun node(
            width: Int,
            height: Int,
        ): AccessibilityNodeInfo =
            mockk {
                // The deprecated getter is the only source of a view's full size (approved for fullSize).
                @Suppress("DEPRECATION")
                every { getBoundsInParent(any()) } answers {
                    firstArg<Rect>().apply {
                        left = 0
                        top = 0
                        right = width
                        bottom = height
                    }
                }
            }

        private val data = AccessibilityNodeData(id = "n", bounds = BoundsData(0, 0, 10, 10))

        @Test
        fun `a regular view reports its full size`() {
            assertEquals(720 to 900, fullSize(node(720, 900), data))
        }

        @Test
        fun `a web node has no full size`() {
            assertNull(fullSize(node(720, 900), data.copy(webRole = "paragraph")))
        }

        @Test
        fun `a Compose node reporting 0x0 has no full size`() {
            assertNull(fullSize(node(0, 0), data))
        }
    }
}
