package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** An edge of a node's screen bounds. */
enum class Edge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

/**
 * The visual directions a scroll container can still scroll: up and left reveal the content before, down and right
 * the content after.
 */
data class ScrollReach(
    val up: Boolean,
    val down: Boolean,
    val left: Boolean,
    val right: Boolean,
) {
    /** Whether the container can scroll toward [edge], revealing what lies beyond it. */
    fun allows(edge: Edge): Boolean =
        when (edge) {
            Edge.TOP -> up
            Edge.BOTTOM -> down
            Edge.LEFT -> left
            Edge.RIGHT -> right
        }

    companion object {
        /**
         * The reach of [container] from its actions. ScrollView, Compose lists and Chrome list only the directions
         * they can scroll now; RecyclerView and AbsListView list only backward (up and left) and forward (down and
         * right), narrowed to one axis by their rows and columns. The ids are compile-time ints, never the
         * [AccessibilityNodeInfo.AccessibilityAction] constants, which are null on the JVM.
         */
        fun of(container: AccessibilityNodeInfo): ScrollReach {
            val ids = container.actionList.mapTo(HashSet()) { it.id }
            val directional =
                ScrollReach(
                    up = android.R.id.accessibilityActionScrollUp in ids,
                    down = android.R.id.accessibilityActionScrollDown in ids,
                    left = android.R.id.accessibilityActionScrollLeft in ids,
                    right = android.R.id.accessibilityActionScrollRight in ids,
                )
            return if (directional != NONE) directional else ofForwardBackward(container, ids)
        }

        private val NONE = ScrollReach(up = false, down = false, left = false, right = false)

        private fun ofForwardBackward(
            container: AccessibilityNodeInfo,
            ids: Set<Int>,
        ): ScrollReach {
            val backward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids
            val forward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids
            val rows = container.collectionInfo?.rowCount ?: 0
            val columns = container.collectionInfo?.columnCount ?: 0
            val vertical = !(columns > 1 && rows <= 1)
            val horizontal = !(rows > 1 && columns <= 1)
            return ScrollReach(
                up = backward && vertical,
                down = forward && vertical,
                left = backward && horizontal,
                right = forward && horizontal,
            )
        }
    }
}

/** A scrollable ancestor of a node: its visible screen bounds and where it can still scroll. */
data class ScrollContainer(
    val bounds: BoundsData,
    val reach: ScrollReach,
)

/**
 * The full size of a regular view, from `getBoundsInParent` (deprecated, the only source of it): null for a web node
 * (Chrome reports web units) and for a side ≤ 0 (Compose reports 0x0).
 */
fun fullSize(
    node: AccessibilityNodeInfo,
    data: AccessibilityNodeData,
): Pair<Int, Int>? {
    if (data.webRole != null) return null
    val rect = Rect()
    @Suppress("DEPRECATION")
    node.getBoundsInParent(rect)
    val width = rect.right - rect.left
    val height = rect.bottom - rect.top
    return if (width > 0 && height > 0) width to height else null
}

/**
 * The edges of [node] (visible screen bounds) cut by one of its scrollable [containers] (every scrollable ancestor,
 * nearest first), each with the container that cuts it.
 *
 * A partly visible node reports bounds clipped to its visible part, and the clipped edge equals its container's edge.
 * So an edge is cut by the nearest container whose same edge has the same coordinate and that can still scroll that
 * way. [fullSize] only ever clears: a node of its full size is whole. Right after the node's own show ([justShown]),
 * views and Compose show a fitting node whole and fill the container with one that does not fit, so without a full
 * size only the axes the node fills stay cut; and a show from below aligns the start of a node that does not fit, so
 * the edges in [movedTowardStart] are not cut.
 */
fun cutEdges(
    node: BoundsData,
    containers: List<ScrollContainer>,
    fullSize: Pair<Int, Int>?,
    justShown: Boolean,
    movedTowardStart: Set<Edge>,
): Map<Edge, ScrollContainer> {
    val visibleSize = (node.right - node.left) to (node.bottom - node.top)
    if (containers.isEmpty() || fullSize == visibleSize) return emptyMap()
    val cut = LinkedHashMap<Edge, ScrollContainer>()
    for (edge in Edge.entries) {
        containers
            .firstOrNull { edgeOf(it.bounds, edge) == edgeOf(node, edge) && it.reach.allows(edge) }
            ?.let { cut[edge] = it }
    }
    if (justShown) {
        cut.keys.removeAll(movedTowardStart)
        if (fullSize == null) {
            cut.entries.removeAll { (edge, container) -> extentOf(node, edge) != extentOf(container.bounds, edge) }
        }
    }
    return cut
}

private fun edgeOf(
    bounds: BoundsData,
    edge: Edge,
): Int =
    when (edge) {
        Edge.TOP -> bounds.top
        Edge.BOTTOM -> bounds.bottom
        Edge.LEFT -> bounds.left
        Edge.RIGHT -> bounds.right
    }

/** The extent of [bounds] on the axis of [edge]. */
private fun extentOf(
    bounds: BoundsData,
    edge: Edge,
): Int =
    when (edge) {
        Edge.TOP, Edge.BOTTOM -> bounds.bottom - bounds.top
        Edge.LEFT, Edge.RIGHT -> bounds.right - bounds.left
    }
