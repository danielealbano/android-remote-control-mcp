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
enum class ScrollAmount(val screenPercentage: Float) {
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
 * Provides three categories of actions:
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
        tree: AccessibilityNodeData,
    ): Result<Unit>

    suspend fun longClickNode(
        nodeId: String,
        tree: AccessibilityNodeData,
    ): Result<Unit>

    suspend fun setTextOnNode(
        nodeId: String,
        text: String,
        tree: AccessibilityNodeData,
    ): Result<Unit>

    suspend fun scrollNode(
        nodeId: String,
        direction: ScrollDirection,
        tree: AccessibilityNodeData,
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
    ): Result<Unit>

    suspend fun pressBack(): Result<Unit>

    suspend fun pressHome(): Result<Unit>

    suspend fun pressRecents(): Result<Unit>

    suspend fun openNotifications(): Result<Unit>

    suspend fun openQuickSettings(): Result<Unit>

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
    }
}
