package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.DragSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.Edge
import com.danielealbano.androidremotecontrolmcp.services.accessibility.dragSegment

/** Parts, seeks and back drags move the content by this share of the container's visible extent E. */
internal const val DRAG_FRACTION = 0.75f

/** The alignment leaves E divided by this between the container's start and the node's start. */
private const val ALIGN_INSET_DIVISOR = 16f

/** The alignment drags only when the node starts at least E divided by this past where it would leave it. */
private const val ALIGN_MIN_DIVISOR = 8f

/** How far [target] starts past [container]'s start + E/16 on [axis]: the length of the drag that aligns it. */
internal fun alignDistance(
    axis: Axis,
    container: BoundsData,
    target: BoundsData,
): Float = axis.start(target) - (axis.start(container) + axis.extent(container) / ALIGN_INSET_DIVISOR)

/** The alignment distance under which [container] (E/8 on [axis]) is not worth a drag. */
internal fun alignThreshold(
    axis: Axis,
    container: BoundsData,
): Float = axis.extent(container) / ALIGN_MIN_DIVISOR

/**
 * The `allow_scroll` flow. The on-screen targets that are whole are captured together first; then each round takes the
 * first pending target (by window, then tree pre-order) as its head: reaches it (shows it, or seeks it when Compose
 * dropped it from the tree), aligns it near the top of its list so the targets after it come into view with it, and
 * captures it with every other pending target that is whole on screen. A head still cut after its show goes to the
 * parts flow. The head always leaves the pending targets in its round, so there are at most as many rounds as targets.
 */
internal class ShotRounds(
    private val ctx: ShotContext,
) {
    /** A head right after its show: where it is and the edges its show moved it toward the start of. */
    private class Reached(
        val present: Presence.Present,
        val movedTowardStart: Set<Edge>,
    )

    private val parts = NodeScreenshotParts(ctx)

    suspend fun run(targets: List<ShotTarget>) {
        val first = ctx.current
        val whole = targets.mapNotNull { target -> ctx.wholeNow(target)?.let { Shot(target, it.data.bounds, null) } }
        if (whole.isNotEmpty()) ctx.captureEach(whole)
        val pending = ArrayDeque(targets.filter { it.outcome == null }.sortedBy { first.order(it.id) })
        while (pending.isNotEmpty()) round(pending.removeFirst(), pending)
    }

    private suspend fun round(
        head: ShotTarget,
        pending: ArrayDeque<ShotTarget>,
    ) {
        val reached = reach(head) ?: return
        when (val judgement = judge(ctx.current, reached.present, reached.movedTowardStart)) {
            Judgement.OffScreen -> head.outcome = ShotOutcome.NotCaptured(NOT_SHOWN_REASON)
            is Judgement.Cut -> parts.run(head, Sighting.Seen(reached.present, judgement), pending)
            Judgement.Whole -> align(head, reached.present, pending)?.let { captureHead(head, it, pending) }
        }
    }

    /** Shows [head], sought first when it is not in the current read; null once its outcome is set. */
    private suspend fun reach(head: ShotTarget): Reached? {
        var presence = ctx.find(head)
        if (presence is Presence.Gone) presence = seek(head, presence)
        var moved = emptySet<Edge>()
        if (presence is Presence.Present) {
            moved = ctx.scroller.show(head, presence.data.bounds)
            presence = ctx.find(head)
        }
        return when (presence) {
            is Presence.Present -> {
                Reached(presence, moved)
            }

            is Presence.Gone -> {
                head.outcome = ShotOutcome.NotCaptured(presence.reason.reason)
                null
            }
        }
    }

    /**
     * Drags the nearest last-seen container of [head] still in the read, at most [MAX_SEEK_DRAGS] times, until the
     * head is present or found again by content: Compose drops nodes scrolled fully out of view. Gone with no drag when
     * none of its last-seen containers is in the read. A refused or failed drag ends the seek.
     */
    private suspend fun seek(
        head: ShotTarget,
        gone: Presence.Gone,
    ): Presence {
        val seen = head.lastSeen
        val container = seen?.containers?.firstOrNull { ctx.current.idOf(it.node) != null }
        if (seen == null || container == null) return Presence.Gone(GoneReason.DISAPPEARED)
        val axis = container.axis
        val forward = seekForward(seen, container)
        var presence: Presence = gone
        var drags = 0
        var dragging = true
        while (dragging && presence !is Presence.Present && drags < MAX_SEEK_DRAGS) {
            drags++
            val bounds =
                ctx.current
                    .idOf(container.node)
                    ?.let { ctx.current.data(it) }
                    ?.bounds
            val result = ctx.scroller.drag(bounds?.let { seekSegment(it, axis, forward) }, axis, forward)
            if (result != DragResult.REFUSED) presence = ctx.find(head)
            dragging = result == DragResult.DONE
        }
        return presence
    }

    /**
     * Seeks opposite to the first movement on [container]'s axis after the target was last seen: a forward movement
     * means it left by the top or left. With no movement since, backward when it was before the container's centre.
     */
    private fun seekForward(
        seen: LastSeen,
        container: Ancestor,
    ): Boolean {
        val axis = container.axis
        val first = ctx.scroller.firstMovement(seen.readSeq, axis)
        return if (first != null) !first else axis.centre(seen.bounds) >= axis.centre(container.data.bounds)
    }

    private fun seekSegment(
        container: BoundsData,
        axis: Axis,
        forward: Boolean,
    ): DragSegment? =
        dragSegment(
            container,
            container.centre(),
            DRAG_FRACTION * axis.extent(container),
            axis.direction(forward),
            ctx.screen(),
        )

    /**
     * Drags [present] near the start of its list when other targets are pending, so those after it come into view with
     * it: a minimal show leaves a node against the far edge, which would cost one screenshot per target. After a
     * dispatched drag, even a failed one, the round goes on from the read that follows it, showing the head again when
     * it is no longer whole. Returns the head as found, null once its outcome is set.
     */
    private suspend fun align(
        head: ShotTarget,
        present: Presence.Present,
        pending: Collection<ShotTarget>,
    ): Sighting.Seen? {
        val alignment = if (pending.isEmpty()) null else alignment(present)
        val result = alignment?.let { (segment, axis) -> ctx.scroller.drag(segment, axis, forward = true) }
        if (result == null || result == DragResult.REFUSED) return Sighting.Seen(present, Judgement.Whole)
        return when (val after = ctx.see(head)) {
            is Sighting.Lost -> {
                head.outcome = ShotOutcome.NotCaptured(after.reason.reason)
                null
            }

            is Sighting.Seen -> {
                if (after.judgement == Judgement.Whole) after else showAgain(head, after)
            }
        }
    }

    /**
     * The drag aligning [present] and its axis: on the first scrollable ancestor that reaches down (vertical), else
     * the first that reaches right (horizontal). Null when there is none or the node is already near the start.
     */
    private fun alignment(present: Presence.Present): Pair<DragSegment?, Axis>? {
        val ancestors = ctx.current.scrollableAncestors(present.id)
        val container = ancestors.firstOrNull { it.reach.down } ?: ancestors.firstOrNull { it.reach.right }
        val axis = if (container?.reach?.down == true) Axis.VERTICAL else Axis.HORIZONTAL
        val bounds = container?.data?.bounds
        val distance = bounds?.let { alignDistance(axis, it, present.data.bounds) }
        return if (bounds != null && distance != null && distance >= alignThreshold(axis, bounds)) {
            dragSegment(bounds, present.data.bounds.centre(), distance, axis.forward, ctx.screen()) to axis
        } else {
            null
        }
    }

    /** Shows a head the alignment left cut or off screen again, and judges it right after that show. */
    private suspend fun showAgain(
        head: ShotTarget,
        seen: Sighting.Seen,
    ): Sighting.Seen? {
        val moved = ctx.scroller.show(head, seen.present.data.bounds)
        return when (val presence = ctx.find(head)) {
            is Presence.Gone -> {
                head.outcome = ShotOutcome.NotCaptured(presence.reason.reason)
                null
            }

            is Presence.Present -> {
                val judgement = judge(ctx.current, presence, moved)
                if (judgement == Judgement.OffScreen) {
                    head.outcome = ShotOutcome.NotCaptured(NOT_SHOWN_REASON)
                    null
                } else {
                    Sighting.Seen(presence, judgement)
                }
            }
        }
    }

    /** Captures the head (with its cut line when it is cut now) and every other pending target whole on screen. */
    private suspend fun captureHead(
        head: ShotTarget,
        seen: Sighting.Seen,
        pending: ArrayDeque<ShotTarget>,
    ) {
        val wording = (seen.judgement as? Judgement.Cut)?.wording
        head.outcome = singleOutcome(ctx.captureWith(Shot(head, seen.present.data.bounds, wording), pending), wording)
    }

    companion object {
        internal const val MAX_SEEK_DRAGS = 2
    }
}
