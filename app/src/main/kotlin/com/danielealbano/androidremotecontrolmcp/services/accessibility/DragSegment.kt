package com.danielealbano.androidremotecontrolmcp.services.accessibility

/** The finger path of a drag in screen coordinates, for [ActionExecutor.dragAndHold]. */
data class DragSegment(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
)

/** A container's visible extent is divided by this to inset the drag area at each end on the axis. */
private const val CONTAINER_INSET_DIVISOR = 16f

/**
 * The screen is shrunk by its size divided by this on each side: the margin of the `scroll` tool's largest preset at
 * zero variance, which keeps the finger out of the gesture-navigation zones.
 */
private const val SCREEN_MARGIN_DIVISOR = 8f

/**
 * The drag that scrolls [container] by [length] toward [direction] (DOWN/RIGHT forward, revealing the content below or
 * to the right; UP/LEFT backward), or null when no drag fits.
 *
 * The finger stays inside the container's visible bounds inset by E/16 at each end on the axis (E: its visible extent
 * on the axis), intersected with the screen shrunk by 1/8 of its size on each side. The segment is clamped to that
 * span, centred on [centre], then shifted inside it; its cross coordinate is [centre]'s, clamped into it. A forward
 * drag moves the finger toward the top or left, a backward one the opposite way.
 */
fun dragSegment(
    container: BoundsData,
    centre: Pair<Float, Float>,
    length: Float,
    direction: ScrollDirection,
    screen: ScreenInfo,
): DragSegment? {
    val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
    val (alongStart, alongEnd) = if (vertical) container.top to container.bottom else container.left to container.right
    val (crossStart, crossEnd) = if (vertical) container.left to container.right else container.top to container.bottom
    val alongScreen = (if (vertical) screen.height else screen.width).toFloat()
    val crossScreen = (if (vertical) screen.width else screen.height).toFloat()
    val inset = (alongEnd - alongStart) / CONTAINER_INSET_DIVISOR
    val along = allowedSpan(alongStart + inset, alongEnd - inset, alongScreen)
    val cross = allowedSpan(crossStart.toFloat(), crossEnd.toFloat(), crossScreen)
    if (along == null || cross == null) return null
    val span = length.coerceIn(0f, along.endInclusive - along.start)
    val centreAlong = if (vertical) centre.second else centre.first
    val low = (centreAlong - span / 2).coerceIn(along.start, along.endInclusive - span)
    val high = low + span
    val crossAt = (if (vertical) centre.first else centre.second).coerceIn(cross)
    val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
    val (from, to) = if (forward) high to low else low to high
    return if (vertical) DragSegment(crossAt, from, crossAt, to) else DragSegment(from, crossAt, to, crossAt)
}

/** The part of [start]..[end] inside a screen side of [screenSize] shrunk by its margins, or null when empty. */
private fun allowedSpan(
    start: Float,
    end: Float,
    screenSize: Float,
): ClosedFloatingPointRange<Float>? {
    val margin = screenSize / SCREEN_MARGIN_DIVISOR
    val low = maxOf(start, margin)
    val high = minOf(end, screenSize - margin)
    return if (high > low) low..high else null
}
