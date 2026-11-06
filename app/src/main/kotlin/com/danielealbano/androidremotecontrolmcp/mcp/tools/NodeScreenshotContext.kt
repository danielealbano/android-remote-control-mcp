package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.Edge
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo

/** A target to crop from the current read: its bounds there and its cut wording, if any. */
internal class Shot(
    val target: ShotTarget,
    val bounds: BoundsData,
    val wording: CutWording?,
)

/** A target acted on, as found in the current read. */
internal sealed interface Sighting {
    /** Present and judged (not right after a show). */
    data class Seen(
        val present: Presence.Present,
        val judgement: Judgement,
    ) : Sighting {
        /** The cut edges, empty when it is whole or off screen. */
        val edges: Map<Edge, Ancestor> get() = (judgement as? Judgement.Cut)?.edges.orEmpty()
    }

    data class Lost(
        val reason: GoneReason,
    ) : Sighting
}

/** The state of one call shared by its flows: its scroller (and so its current read) and its capturer. */
internal class ShotContext(
    private val deps: NodeScreenshotDeps,
    val scroller: ShotScroller,
    val capturer: ShotCapturer,
) {
    val current: ReadIndex get() = scroller.current

    fun screen(): ScreenInfo = deps.accessibilityServiceProvider.getScreenInfo()

    /** Where [target] is in the current read, found again by content when its live node is not there. */
    fun find(target: ShotTarget): Presence {
        val presence = target.presence(current)
        return if (presence is Presence.Present) presence else target.reidentify(current) ?: presence
    }

    /** [target] found and judged in the current read. */
    fun see(target: ShotTarget): Sighting =
        when (val presence = find(target)) {
            is Presence.Present -> Sighting.Seen(presence, judge(current, presence, null))
            is Presence.Gone -> Sighting.Lost(presence.reason)
        }

    /** [target] when it is present by its live node and whole in the current read. */
    fun wholeNow(target: ShotTarget): Presence.Present? =
        (target.presence(current) as? Presence.Present)?.takeIf { judge(current, it, null) == Judgement.Whole }

    /** Captures [shots] from the current read and gives each its outcome: one image, or none when its crop is empty. */
    suspend fun captureEach(shots: List<Shot>) {
        val images = capturer.capture(current.read, shots.map { it.bounds })
        shots.forEachIndexed { index, shot -> shot.target.outcome = singleOutcome(images[index], shot.wording) }
    }

    /**
     * Captures [first] from the current read together with every target of [pending] present and whole in it, which
     * leave [pending] with their image. Returns the image of [first], null when its crop is empty.
     */
    suspend fun captureWith(
        first: Shot,
        pending: MutableCollection<ShotTarget>,
    ): String? {
        val others = pending.mapNotNull { target -> wholeNow(target)?.let { Shot(target, it.data.bounds, null) } }
        pending.removeAll(others.map { it.target }.toSet())
        val shots = listOf(first) + others
        val images = capturer.capture(current.read, shots.map { it.bounds })
        others.forEachIndexed { index, shot -> shot.target.outcome = singleOutcome(images[index + 1], null) }
        return images.first()
    }
}
