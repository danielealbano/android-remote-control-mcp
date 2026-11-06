package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Represents a single point in a gesture path.
 *
 * @property x The X coordinate on screen.
 * @property y The Y coordinate on screen.
 * @property time The time offset in milliseconds from the start of the gesture.
 */
data class GesturePoint(
    val x: Float,
    val y: Float,
    val time: Long,
)

/**
 * Direction for scroll gestures.
 */
enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

/**
 * Amount for scroll gestures, mapping to screen percentages.
 */
@Suppress("MagicNumber")
enum class ScrollAmount(
    val screenPercentage: Float,
) {
    /** 25% of screen dimension. */
    SMALL(0.25f),

    /** 50% of screen dimension. */
    MEDIUM(0.50f),

    /** 75% of screen dimension. */
    LARGE(0.75f),
}

/**
 * Interface for executing accessibility actions on nodes and the screen.
 *
 * Provides four categories of actions:
 * 1. **Node actions**: Click, long-click, set text, scroll on specific accessibility nodes.
 * 2. **Coordinate-based actions**: Tap, long press, double tap, swipe, scroll at screen coordinates.
 * 3. **Global actions**: Back, home, recents, notifications, quick settings.
 * 4. **Advanced gestures**: Pinch, custom multi-path gestures.
 *
 * Implementations may access [McpAccessibilityService.instance] directly (singleton pattern)
 * because the accessibility service is system-managed and cannot be injected via Hilt.
 */
@Suppress("TooManyFunctions")
interface ActionExecutor {
    suspend fun clickNode(
        nodeId: String,
        windows: List<WindowData>,
    ): Result<Unit>

    suspend fun longClickNode(
        nodeId: String,
        windows: List<WindowData>,
    ): Result<Unit>

    /**
     * Performs the first action of the live node [nodeId] listed with [label] ([NodeActionLabels]). The failures
     * never name the label: with Privacy Mode on it is the unredacted one.
     */
    suspend fun performLabelledAction(
        nodeId: String,
        windows: List<WindowData>,
        label: String,
    ): Result<Unit>

    /** Gives input focus to the node [nodeId] (ACTION_FOCUS), for a field a click did not focus. */
    suspend fun focusNode(
        nodeId: String,
        windows: List<WindowData>,
    ): Result<Unit>

    suspend fun setTextOnNode(
        nodeId: String,
        text: String,
        windows: List<WindowData>,
    ): Result<Unit>

    suspend fun scrollNode(
        nodeId: String,
        direction: ScrollDirection,
        windows: List<WindowData>,
    ): Result<Unit>

    suspend fun tap(
        x: Float,
        y: Float,
    ): Result<Unit>

    suspend fun longPress(
        x: Float,
        y: Float,
        duration: Long = DEFAULT_LONG_PRESS_DURATION_MS,
    ): Result<Unit>

    suspend fun doubleTap(
        x: Float,
        y: Float,
    ): Result<Unit>

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long = DEFAULT_SWIPE_DURATION_MS,
    ): Result<Unit>

    suspend fun scroll(
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.MEDIUM,
        variancePercent: Float = DEFAULT_SCROLL_VARIANCE_PERCENT,
    ): Result<Unit>

    /**
     * Drags from (fromX, fromY) to (toX, toY) in [DRAG_MOVE_MS], then holds still for [DRAG_HOLD_MS] before lifting,
     * so the content stops with the finger instead of gliding on.
     *
     * Fails with [IllegalArgumentException] (invalid coordinates, or a drag shorter than twice the touch slop, which
     * would not scroll) or [IllegalStateException] (no accessibility service) before dispatching anything; a failure
     * once dispatched is any other exception.
     */
    suspend fun dragAndHold(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ): Result<Unit>

    suspend fun pressBack(): Result<Unit>

    suspend fun pressHome(): Result<Unit>

    suspend fun pressRecents(): Result<Unit>

    suspend fun openNotifications(): Result<Unit>

    suspend fun openQuickSettings(): Result<Unit>

    /**
     * Dismisses the on-screen soft keyboard if one is currently open.
     *
     * Unlike [pressBack], this is a no-op when no input-method window is visible: the back action
     * is dispatched only when a keyboard was detected, so it is not used to navigate back.
     *
     * @return `Result.success(true)` if a keyboard was open and dismissed,
     *   `Result.success(false)` if no keyboard was open, or `Result.failure` on error.
     */
    suspend fun dismissKeyboard(): Result<Boolean>

    suspend fun pinch(
        centerX: Float,
        centerY: Float,
        scale: Float,
        duration: Long = DEFAULT_GESTURE_DURATION_MS,
    ): Result<Unit>

    suspend fun customGesture(paths: List<List<GesturePoint>>): Result<Unit>

    fun findAccessibilityNodeByNodeId(
        rootNode: AccessibilityNodeInfo,
        nodeId: String,
        tree: AccessibilityNodeData,
    ): AccessibilityNodeInfo?

    companion object {
        internal const val DEFAULT_LONG_PRESS_DURATION_MS = 1000L
        internal const val DEFAULT_SWIPE_DURATION_MS = 300L
        internal const val DEFAULT_GESTURE_DURATION_MS = 300L
        internal const val DEFAULT_SCROLL_VARIANCE_PERCENT = 0.05f
        internal const val MAX_SCROLL_VARIANCE_PERCENT = 0.20f

        /** How long the finger of [dragAndHold] moves; measured on a real phone. */
        internal const val DRAG_MOVE_MS = 600L

        /** How long the finger of [dragAndHold] holds still before lifting; measured on a real phone. */
        internal const val DRAG_HOLD_MS = 300L
    }
}
