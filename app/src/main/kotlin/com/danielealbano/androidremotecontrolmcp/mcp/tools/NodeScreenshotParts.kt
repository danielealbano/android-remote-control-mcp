package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.Edge
import com.danielealbano.androidremotecontrolmcp.services.accessibility.dragSegment

/**
 * The parts flow, for a head still cut after its show: up to [MAX_PARTS] overlapping images, one per [DRAG_FRACTION]
 * of its container, never glued together. Back drags first reach its start (at most [MAX_BACK_DRAGS]); a start far
 * from the container's start is aligned first, so part 1 is not a sliver. Each part's capture also takes the other
 * pending targets whole on screen. A failed drag ends its phase.
 */
internal class ShotParts(
    private val ctx: ShotContext,
) {
    /** What the parts of one node add to its line. */
    private class PartsState(
        private val axis: Axis,
    ) {
        val images = mutableListOf<String>()
        private var startNotReached = false
        private var wording: CutWording? = null
        private var lastEdges: Set<Edge> = emptySet()
        var lost: GoneReason? = null

        fun add(
            image: String,
            seen: Sighting.Seen,
        ) {
            images.add(image)
            if (images.size == 1 && axis.startEdge in seen.edges) startNotReached = true
            val cut = seen.judgement as? Judgement.Cut
            // Still cut on the other axis at a part: the parts line carries the partial wording.
            if (cut != null && seen.edges.keys.any { !axis.owns(it) } && wording != CutWording.PARTIAL) {
                wording = cut.wording
            }
            lastEdges = seen.edges.keys
        }

        fun outcome(): ShotOutcome {
            val lostReason = lost
            return if (images.isEmpty()) {
                ShotOutcome.NotCaptured(lostReason?.reason ?: NOT_SHOWN_REASON)
            } else {
                val ending =
                    if (lostReason != null) lostNote(lostReason) else CONTINUES_NOTE.takeIf { axis.endEdge in lastEdges }
                ShotOutcome.Captured(images.toList(), wording, startNotReached, ending)
            }
        }
    }

    suspend fun run(
        head: ShotTarget,
        cut: Sighting.Seen,
        pending: ArrayDeque<ShotTarget>,
    ) {
        val axis = if (cut.edges.keys.any { Axis.VERTICAL.owns(it) }) Axis.VERTICAL else Axis.HORIZONTAL
        val start = alignBeforeFirstPart(head, axis, backPhase(head, axis, cut))
        head.outcome = partsPhase(head, axis, start, pending)
    }

    /**
     * Drags backward while the start edge is cut, at most [MAX_BACK_DRAGS] times. The parts start from the read after
     * the last drag dispatched, or the current read when it was refused.
     */
    private suspend fun backPhase(
        head: ShotTarget,
        axis: Axis,
        cut: Sighting.Seen,
    ): Sighting {
        var sighting: Sighting = cut
        var drags = 0
        var dragging = true
        while (dragging && drags < MAX_BACK_DRAGS) {
            drags++
            val seen = sighting as? Sighting.Seen
            val result = seen?.let { drag(it, axis.startEdge, axis, forward = false) } ?: DragResult.REFUSED
            if (result != DragResult.REFUSED) sighting = ctx.see(head)
            dragging = result == DragResult.DONE
        }
        return sighting
    }

    /**
     * Before part 1, a node whose start is visible but more than E/8 past the container's start + E/16 (after a back
     * drag past its start, or a show from above that left it against the far edge) is aligned with the drag of a round
     * head. No show follows, as the node is cut anyway.
     */
    private suspend fun alignBeforeFirstPart(
        head: ShotTarget,
        axis: Axis,
        sighting: Sighting,
    ): Sighting {
        val seen = sighting as? Sighting.Seen ?: return sighting
        val target = seen.present.data.bounds
        val container = seen.edges.takeIf { axis.startEdge !in it }?.get(axis.endEdge)?.data?.bounds
        val distance = container?.let { alignDistance(axis, it, target) } ?: 0f
        val segment =
            container
                ?.takeIf { distance > alignThreshold(axis, it) }
                ?.let { dragSegment(it, target.centre(), distance, axis.forward, ctx.screen()) }
        val dispatched = segment != null && ctx.scroller.drag(segment, axis, forward = true) != DragResult.REFUSED
        return if (dispatched) ctx.see(head) else seen
    }

    /**
     * Captures a part, then drags forward for the next while the end edge is cut, up to [MAX_PARTS] parts. A target
     * lost, off screen or with an empty crop ends the parts there.
     */
    private suspend fun partsPhase(
        head: ShotTarget,
        axis: Axis,
        start: Sighting,
        pending: ArrayDeque<ShotTarget>,
    ): ShotOutcome {
        val state = PartsState(axis)
        var sighting = start
        var capturing = true
        while (capturing) {
            val seen = (sighting as? Sighting.Seen)?.takeIf { it.judgement != Judgement.OffScreen }
            val image = seen?.let { ctx.captureWith(Shot(head, it.present.data.bounds, null), pending) }
            if (seen != null && image != null) {
                state.add(image, seen)
                val next = if (state.images.size < MAX_PARTS) nextPart(head, axis, seen) else null
                capturing = next != null
                if (next != null) sighting = next
            } else {
                state.lost = (sighting as? Sighting.Lost)?.reason
                capturing = false
            }
        }
        return state.outcome()
    }

    /** The next part's read after a forward drag, null when the end edge is not cut or the drag did not complete. */
    private suspend fun nextPart(
        head: ShotTarget,
        axis: Axis,
        seen: Sighting.Seen,
    ): Sighting? = if (drag(seen, axis.endEdge, axis, forward = true) == DragResult.DONE) ctx.see(head) else null

    /** Drags the container cutting [edge] of [seen] by [DRAG_FRACTION] of it, centred on the node; refused if none. */
    private suspend fun drag(
        seen: Sighting.Seen,
        edge: Edge,
        axis: Axis,
        forward: Boolean,
    ): DragResult {
        val container = seen.edges[edge]?.data?.bounds ?: return DragResult.REFUSED
        val segment =
            dragSegment(
                container,
                seen.present.data.bounds.centre(),
                DRAG_FRACTION * axis.extent(container),
                axis.direction(forward),
                ctx.screen(),
            )
        return ctx.scroller.drag(segment, axis, forward)
    }

    companion object {
        internal const val MAX_BACK_DRAGS = 3
        internal const val MAX_PARTS = 3
    }
}
