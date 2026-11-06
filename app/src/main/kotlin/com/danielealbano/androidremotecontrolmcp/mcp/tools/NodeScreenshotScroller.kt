package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.DragSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.Edge
import kotlinx.coroutines.delay

/** What became of a drag. */
internal enum class DragResult {
    /** Nothing was dispatched (no segment fits, bad or too short, no service): no settle and no read follow. */
    REFUSED,

    /** Dispatched and completed. */
    DONE,

    /** Dispatched and failed: the content may have moved anyway. */
    FAILED,
}

/**
 * The reads, shows and drags of one call. Every read is indexed once and recorded as the last read where each tracked
 * target was seen; every show and dispatched drag is followed by its settle and a fresh read. Remembers whether the app
 * was scrolled (a dispatched drag, whatever its result, or a show that moved its target) and the direction of each
 * movement, for the seek of a target that left the tree.
 */
internal class ShotScroller(
    private val actionExecutor: ActionExecutor,
    private val read: () -> FreshRead,
) {
    private class Movement(
        val afterRead: Int,
        val axis: Axis,
        val forward: Boolean,
    )

    private val movements = mutableListOf<Movement>()
    private var tracked: List<ShotTarget> = emptyList()
    private var reads = 0

    lateinit var current: ReadIndex
        private set

    var scrolled = false
        private set

    /** Reads the screen again and makes it [current]. */
    fun readAgain(): ReadIndex {
        current = ReadIndex(read(), ++reads)
        tracked.forEach { it.track(current) }
        return current
    }

    /** Follows [targets] from the current read on. */
    fun track(targets: List<ShotTarget>) {
        tracked = targets
        targets.forEach { it.track(current) }
    }

    /**
     * Whether the first movement on [axis] after read [afterRead] was forward, or null when nothing has moved on that
     * axis since.
     */
    fun firstMovement(
        afterRead: Int,
        axis: Axis,
    ): Boolean? = movements.firstOrNull { it.afterRead >= afterRead && it.axis == axis }?.forward

    /**
     * Shows [target] (bounds [before] in the current read), waits for it to settle and reads again. Returns the edges
     * its show moved it toward the start of: TOP when its top decreased, LEFT when its left decreased. The return value
     * of the action is ignored: Compose always answers true, views false when there is nothing to do.
     */
    suspend fun show(
        target: ShotTarget,
        before: BoundsData,
    ): Set<Edge> {
        target.node.performAction(android.R.id.accessibilityActionShowOnScreen)
        val settled = settleAfterShow(target.node)
        // A node that cannot be refreshed after its show tells nothing about what moved: count it as scrolled.
        if (settled != before) scrolled = true
        if (settled != null) recordShow(before, settled)
        readAgain()
        return buildSet {
            if (settled != null && settled.top < before.top) add(Edge.TOP)
            if (settled != null && settled.left < before.left) add(Edge.LEFT)
        }
    }

    /**
     * Drags along [segment] on [axis] (forward when [forward]). A null segment, or a failure before dispatch
     * ([IllegalArgumentException], [IllegalStateException]), is refused. Anything dispatched, even failed, scrolled the
     * app and is followed by its settle and a fresh read.
     */
    suspend fun drag(
        segment: DragSegment?,
        axis: Axis,
        forward: Boolean,
    ): DragResult {
        val result = segment?.let { actionExecutor.dragAndHold(it.fromX, it.fromY, it.toX, it.toY) }
        val error = result?.exceptionOrNull()
        if (result == null || error is IllegalArgumentException || error is IllegalStateException) {
            Log.d(TAG, "Drag refused (${error?.javaClass?.simpleName ?: "no segment"})")
            return DragResult.REFUSED
        }
        scrolled = true
        movements.add(Movement(reads, axis, forward))
        if (error != null) Log.w(TAG, "Drag failed: ${error.message}")
        // A held drag does not glide; one reaching the end of a list stretches it until release, gone within 400 ms.
        delay(DRAG_SETTLE_MS)
        readAgain()
        return if (error == null) DragResult.DONE else DragResult.FAILED
    }

    /**
     * Polls [node] until its screen bounds are the same over [SHOW_STABLE_POLLS] polls in a row, [SHOW_SETTLE_MAX_MS]
     * have passed, or it cannot be refreshed. Returns its last bounds, null when it could not be refreshed once.
     */
    private suspend fun settleAfterShow(node: AccessibilityNodeInfo): BoundsData? {
        var last: BoundsData? = null
        var stable = 0
        var waited = 0L
        while (stable < SHOW_STABLE_POLLS && waited < SHOW_SETTLE_MAX_MS) {
            delay(SHOW_POLL_MS)
            waited += SHOW_POLL_MS
            if (!node.refresh()) break
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val bounds = BoundsData(rect.left, rect.top, rect.right, rect.bottom)
            stable = if (bounds == last) stable + 1 else 1
            last = bounds
        }
        return last
    }

    private fun recordShow(
        before: BoundsData,
        settled: BoundsData,
    ) {
        if (settled.top != before.top) movements.add(Movement(reads, Axis.VERTICAL, settled.top < before.top))
        if (settled.left != before.left) movements.add(Movement(reads, Axis.HORIZONTAL, settled.left < before.left))
    }

    companion object {
        private const val TAG = "MCP:NodeScreenshots"
        internal const val SHOW_POLL_MS = 50L
        internal const val SHOW_STABLE_POLLS = 3
        internal const val SHOW_SETTLE_MAX_MS = 1500L
        internal const val DRAG_SETTLE_MS = 500L
    }
}
