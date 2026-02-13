package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Executes accessibility actions on nodes and the screen.
 *
 * Provides three categories of actions:
 * 1. **Node actions**: Click, long-click, set text, scroll on specific accessibility nodes.
 * 2. **Coordinate-based actions**: Tap, long press, double tap, swipe, scroll at screen coordinates.
 * 3. **Global actions**: Back, home, recents, notifications, quick settings.
 * 4. **Advanced gestures**: Pinch, custom multi-path gestures.
 *
 * Accesses [McpAccessibilityService.instance] directly (singleton pattern) because the
 * accessibility service is system-managed and cannot be injected via Hilt.
 *
 * This class is Hilt-injectable and stateless.
 */
@Suppress("TooManyFunctions")
class ActionExecutorImpl
    @Inject
    constructor() : ActionExecutor {
        // ─────────────────────────────────────────────────────────────────────────
        // Node Actions
        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Clicks the node identified by [nodeId] in the parsed [tree].
         *
         * Finds the corresponding real [AccessibilityNodeInfo] and performs ACTION_CLICK.
         *
         * @return [Result.success] if the action was performed, [Result.failure] otherwise.
         */
        override suspend fun clickNode(
            nodeId: String,
            tree: AccessibilityNodeData,
        ): Result<Unit> {
            return performNodeAction(nodeId, tree, "click") { realNode ->
                if (!realNode.isClickable) {
                    return@performNodeAction Result.failure(
                        IllegalStateException("Node '$nodeId' is not clickable"),
                    )
                }
                val success = realNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        RuntimeException("ACTION_CLICK failed on node '$nodeId'"),
                    )
                }
            }
        }

        /**
         * Long-clicks the node identified by [nodeId] in the parsed [tree].
         */
        override suspend fun longClickNode(
            nodeId: String,
            tree: AccessibilityNodeData,
        ): Result<Unit> {
            return performNodeAction(nodeId, tree, "long click") { realNode ->
                if (!realNode.isLongClickable) {
                    return@performNodeAction Result.failure(
                        IllegalStateException("Node '$nodeId' is not long-clickable"),
                    )
                }
                val success = realNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        RuntimeException("ACTION_LONG_CLICK failed on node '$nodeId'"),
                    )
                }
            }
        }

        /**
         * Sets [text] on the editable node identified by [nodeId] in the parsed [tree].
         */
        override suspend fun setTextOnNode(
            nodeId: String,
            text: String,
            tree: AccessibilityNodeData,
        ): Result<Unit> {
            return performNodeAction(nodeId, tree, "set text") { realNode ->
                if (!realNode.isEditable) {
                    return@performNodeAction Result.failure(
                        IllegalStateException("Node '$nodeId' is not editable"),
                    )
                }
                val arguments =
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                val success =
                    realNode.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments,
                    )
                if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        RuntimeException("ACTION_SET_TEXT failed on node '$nodeId'"),
                    )
                }
            }
        }

        /**
         * Scrolls the node identified by [nodeId] in the given [direction].
         */
        override suspend fun scrollNode(
            nodeId: String,
            direction: ScrollDirection,
            tree: AccessibilityNodeData,
        ): Result<Unit> {
            return performNodeAction(nodeId, tree, "scroll") { realNode ->
                if (!realNode.isScrollable) {
                    return@performNodeAction Result.failure(
                        IllegalStateException("Node '$nodeId' is not scrollable"),
                    )
                }
                val action =
                    when (direction) {
                        ScrollDirection.UP, ScrollDirection.LEFT ->
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                val success = realNode.performAction(action)
                if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        RuntimeException("Scroll ${direction.name} failed on node '$nodeId'"),
                    )
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Coordinate-Based Actions
        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Performs a single tap at the specified coordinates.
         */
        override suspend fun tap(
            x: Float,
            y: Float,
        ): Result<Unit> {
            validateCoordinates(x, y)?.let { return it }
            val path = Path().apply { moveTo(x, y) }
            val stroke =
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MS,
                )
            return dispatchSingleStrokeGesture(stroke, "tap($x, $y)")
        }

        /**
         * Performs a long press at the specified coordinates.
         *
         * @param duration Press duration in milliseconds.
         *     Defaults to [ActionExecutor.DEFAULT_LONG_PRESS_DURATION_MS].
         */
        override suspend fun longPress(
            x: Float,
            y: Float,
            duration: Long,
        ): Result<Unit> {
            validateCoordinates(x, y)?.let { return it }
            val path = Path().apply { moveTo(x, y) }
            val stroke =
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    duration,
                )
            return dispatchSingleStrokeGesture(stroke, "longPress($x, $y, ${duration}ms)")
        }

        /**
         * Performs a double tap at the specified coordinates.
         *
         * Uses a [GestureDescription] with two sequential tap strokes at the same
         * coordinates, separated by [DOUBLE_TAP_GAP_MS]. This is more reliable than
         * dispatching two separate gestures because Android recognizes the two strokes
         * within a single gesture dispatch as a double-tap pattern.
         */
        @Suppress("ReturnCount")
        override suspend fun doubleTap(
            x: Float,
            y: Float,
        ): Result<Unit> {
            validateCoordinates(x, y)?.let { return it }

            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val firstTapPath = Path().apply { moveTo(x, y) }
            val secondTapPath = Path().apply { moveTo(x, y) }

            val firstTapStroke =
                GestureDescription.StrokeDescription(
                    firstTapPath,
                    0L,
                    TAP_DURATION_MS,
                )
            val secondTapStartTime = TAP_DURATION_MS + DOUBLE_TAP_GAP_MS
            val secondTapStroke =
                GestureDescription.StrokeDescription(
                    secondTapPath,
                    secondTapStartTime,
                    TAP_DURATION_MS,
                )

            val gesture =
                GestureDescription.Builder()
                    .addStroke(firstTapStroke)
                    .addStroke(secondTapStroke)
                    .build()

            return dispatchGesture(service, gesture, "doubleTap($x, $y)")
        }

        /**
         * Performs a swipe gesture from (x1, y1) to (x2, y2).
         *
         * @param duration Swipe duration in milliseconds.
         *     Defaults to [ActionExecutor.DEFAULT_SWIPE_DURATION_MS].
         */
        @Suppress("ReturnCount")
        override suspend fun swipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            duration: Long,
        ): Result<Unit> {
            validateCoordinates(x1, y1)?.let { return it }
            validateCoordinates(x2, y2)?.let { return it }
            val path =
                Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            val stroke =
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    duration,
                )
            return dispatchSingleStrokeGesture(
                stroke,
                "swipe($x1,$y1 -> $x2,$y2, ${duration}ms)",
            )
        }

        /**
         * Scrolls the screen in the given [direction] by the given [amount].
         *
         * Calculates start and end coordinates as percentages of the screen size
         * and dispatches a swipe gesture.
         */
        @Suppress("ReturnCount")
        override suspend fun scroll(
            direction: ScrollDirection,
            amount: ScrollAmount,
        ): Result<Unit> {
            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val rootNode =
                service.getRootNode()
                    ?: return Result.failure(
                        IllegalStateException("No root node available for screen dimensions"),
                    )

            val rect = Rect()
            rootNode.getBoundsInScreen(rect)
            @Suppress("DEPRECATION")
            rootNode.recycle()

            val screenWidth = rect.right.toFloat()
            val screenHeight = rect.bottom.toFloat()
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            val scrollDistance =
                when (direction) {
                    ScrollDirection.UP, ScrollDirection.DOWN ->
                        screenHeight * amount.screenPercentage
                    ScrollDirection.LEFT, ScrollDirection.RIGHT ->
                        screenWidth * amount.screenPercentage
                }
            val halfDistance = scrollDistance / 2f

            return when (direction) {
                ScrollDirection.UP ->
                    swipe(
                        centerX,
                        centerY + halfDistance,
                        centerX,
                        centerY - halfDistance,
                    )
                ScrollDirection.DOWN ->
                    swipe(
                        centerX,
                        centerY - halfDistance,
                        centerX,
                        centerY + halfDistance,
                    )
                ScrollDirection.LEFT ->
                    swipe(
                        centerX + halfDistance,
                        centerY,
                        centerX - halfDistance,
                        centerY,
                    )
                ScrollDirection.RIGHT ->
                    swipe(
                        centerX - halfDistance,
                        centerY,
                        centerX + halfDistance,
                        centerY,
                    )
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Global Actions
        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Presses the Back button.
         */
        override suspend fun pressBack(): Result<Unit> {
            return performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                "pressBack",
            )
        }

        /**
         * Presses the Home button.
         */
        override suspend fun pressHome(): Result<Unit> {
            return performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                "pressHome",
            )
        }

        /**
         * Opens the Recents screen.
         */
        override suspend fun pressRecents(): Result<Unit> {
            return performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
                "pressRecents",
            )
        }

        /**
         * Opens the notification shade.
         */
        override suspend fun openNotifications(): Result<Unit> {
            return performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                "openNotifications",
            )
        }

        /**
         * Opens the quick settings panel.
         */
        override suspend fun openQuickSettings(): Result<Unit> {
            return performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                "openQuickSettings",
            )
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Advanced Gestures
        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Performs a pinch gesture centered at ([centerX], [centerY]).
         *
         * @param scale Scale factor. > 1 = zoom in (fingers move apart), < 1 = zoom out (fingers come together).
         * @param duration Gesture duration in milliseconds.
         */
        @Suppress("ReturnCount")
        override suspend fun pinch(
            centerX: Float,
            centerY: Float,
            scale: Float,
            duration: Long,
        ): Result<Unit> {
            validateCoordinates(centerX, centerY)?.let { return it }

            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val startDistance = PINCH_BASE_DISTANCE
            val endDistance = PINCH_BASE_DISTANCE * scale

            val finger1Path =
                Path().apply {
                    moveTo(centerX - startDistance, centerY)
                    lineTo(centerX - endDistance, centerY)
                }
            val finger2Path =
                Path().apply {
                    moveTo(centerX + startDistance, centerY)
                    lineTo(centerX + endDistance, centerY)
                }

            val stroke1 = GestureDescription.StrokeDescription(finger1Path, 0L, duration)
            val stroke2 = GestureDescription.StrokeDescription(finger2Path, 0L, duration)

            val gesture =
                GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()

            return dispatchGesture(
                service,
                gesture,
                "pinch($centerX, $centerY, scale=$scale, ${duration}ms)",
            )
        }

        /**
         * Executes a custom multi-path gesture.
         *
         * @param paths A list of paths, where each path is a list of [GesturePoint]s.
         *              Each path represents one finger's movement.
         */
        @Suppress("ReturnCount")
        override suspend fun customGesture(paths: List<List<GesturePoint>>): Result<Unit> {
            if (paths.isEmpty()) {
                return Result.failure(IllegalArgumentException("Gesture paths must not be empty"))
            }

            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val builder = GestureDescription.Builder()

            for ((pathIndex, points) in paths.withIndex()) {
                if (points.size < 2) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Path $pathIndex must have at least 2 points, has ${points.size}",
                        ),
                    )
                }

                for (point in points) {
                    validateCoordinates(point.x, point.y)?.let { return it }
                }

                val path =
                    Path().apply {
                        moveTo(points[0].x, points[0].y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }

                val startTime = points[0].time
                val endTime = points.last().time
                val duration = (endTime - startTime).coerceAtLeast(1L)

                val stroke = GestureDescription.StrokeDescription(path, startTime, duration)
                builder.addStroke(stroke)
            }

            return dispatchGesture(
                service,
                builder.build(),
                "customGesture(${paths.size} paths)",
            )
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Node Resolution (Parsed ID -> Real AccessibilityNodeInfo)
        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Walks the real [AccessibilityNodeInfo] tree in parallel with the parsed
         * [AccessibilityNodeData] tree to find the real node matching [nodeId].
         *
         * This is necessary because [AccessibilityNodeInfo.performAction] must be
         * called on the actual framework node, not on our parsed data class.
         *
         * @param rootNode The real root [AccessibilityNodeInfo].
         * @param nodeId The node ID to find.
         * @param tree The parsed tree (used to regenerate IDs for comparison).
         * @return The matching real [AccessibilityNodeInfo], or null if not found.
         *         The caller is responsible for recycling the returned node.
         */
        override fun findAccessibilityNodeByNodeId(
            rootNode: AccessibilityNodeInfo,
            nodeId: String,
            tree: AccessibilityNodeData,
        ): AccessibilityNodeInfo? {
            return walkAndMatch(rootNode, tree, nodeId, recycleOnMismatch = false)
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Internal Helpers
        // ─────────────────────────────────────────────────────────────────────────

        @Suppress("ReturnCount")
        private suspend fun performNodeAction(
            nodeId: String,
            tree: AccessibilityNodeData,
            actionName: String,
            action: (AccessibilityNodeInfo) -> Result<Unit>,
        ): Result<Unit> {
            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val rootNode =
                service.getRootNode()
                    ?: return Result.failure(
                        IllegalStateException("No root node available"),
                    )

            val realNode = findAccessibilityNodeByNodeId(rootNode, nodeId, tree)
            if (realNode == null) {
                @Suppress("DEPRECATION")
                rootNode.recycle()
                return Result.failure(
                    NoSuchElementException("Node '$nodeId' not found in accessibility tree"),
                )
            }

            return try {
                val result = action(realNode)
                if (result.isSuccess) {
                    Log.d(TAG, "Node action '$actionName' succeeded on node '$nodeId'")
                } else {
                    Log.w(
                        TAG,
                        "Node action '$actionName' failed on node '$nodeId': " +
                            "${result.exceptionOrNull()?.message}",
                    )
                }
                result
            } finally {
                @Suppress("DEPRECATION")
                realNode.recycle()
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }

        private suspend fun performGlobalAction(
            action: Int,
            actionName: String,
        ): Result<Unit> {
            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val success = service.performGlobalAction(action)
            return if (success) {
                Log.d(TAG, "Global action '$actionName' succeeded")
                Result.success(Unit)
            } else {
                Log.w(TAG, "Global action '$actionName' failed")
                Result.failure(RuntimeException("Global action '$actionName' failed"))
            }
        }

        private suspend fun dispatchSingleStrokeGesture(
            stroke: GestureDescription.StrokeDescription,
            description: String,
        ): Result<Unit> {
            val service =
                McpAccessibilityService.instance
                    ?: return Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

            val gesture =
                GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

            return dispatchGesture(service, gesture, description)
        }

        private suspend fun dispatchGesture(
            service: McpAccessibilityService,
            gesture: GestureDescription,
            description: String,
        ): Result<Unit> =
            suspendCancellableCoroutine { continuation ->
                val callback =
                    object :
                        android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "Gesture completed: $description")
                            if (continuation.isActive) {
                                continuation.resume(Result.success(Unit))
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled: $description")
                            if (continuation.isActive) {
                                continuation.resume(
                                    Result.failure(
                                        RuntimeException("Gesture cancelled: $description"),
                                    ),
                                )
                            }
                        }
                    }

                val dispatched = service.dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    Log.e(TAG, "Failed to dispatch gesture: $description")
                    if (continuation.isActive) {
                        continuation.resume(
                            Result.failure(
                                RuntimeException("Failed to dispatch gesture: $description"),
                            ),
                        )
                    }
                }
            }

        /**
         * Recursively walks the real node tree and the parsed tree in parallel.
         * When a parsed node's ID matches [targetNodeId], returns the corresponding real node.
         */
        @Suppress("ReturnCount")
        private fun walkAndMatch(
            realNode: AccessibilityNodeInfo,
            parsedNode: AccessibilityNodeData,
            targetNodeId: String,
            recycleOnMismatch: Boolean,
        ): AccessibilityNodeInfo? {
            if (parsedNode.id == targetNodeId) {
                return realNode
            }

            val childCount = realNode.childCount
            val parsedChildCount = parsedNode.children.size
            val minCount = minOf(childCount, parsedChildCount)

            for (i in 0 until minCount) {
                val realChild = realNode.getChild(i) ?: continue
                val parsedChild = parsedNode.children[i]

                val found = walkAndMatch(realChild, parsedChild, targetNodeId, recycleOnMismatch = true)
                if (found != null) {
                    return found
                }
            }

            if (recycleOnMismatch) {
                @Suppress("DEPRECATION")
                realNode.recycle()
            }

            return null
        }

        private fun validateCoordinates(
            x: Float,
            y: Float,
        ): Result<Unit>? {
            if (x < 0f || y < 0f) {
                return Result.failure(
                    IllegalArgumentException(
                        "Coordinates must be non-negative: ($x, $y)",
                    ),
                )
            }
            return null
        }

        companion object {
            private const val TAG = "MCP:ActionExecutor"
            private const val TAP_DURATION_MS = 50L
            private const val DOUBLE_TAP_GAP_MS = 100L
            private const val PINCH_BASE_DISTANCE = 100f
        }
    }
