@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// tap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `tap`.
 *
 * Performs a single tap at the specified coordinates.
 *
 * **Input**: `{ "x": <number>, "y": <number> }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Tap executed at (x, y)" }] }`
 * **Errors**:
 *   - -32602 if parameters are missing or invalid (negative coordinates)
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if tap gesture execution failed
 */
class TapTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val x = McpToolUtils.requireFloat(params, "x")
            val y = McpToolUtils.requireFloat(params, "y")
            McpToolUtils.validateNonNegative(x, "x")
            McpToolUtils.validateNonNegative(y, "y")

            Log.d(TAG, "Executing tap at ($x, $y)")
            val result = actionExecutor.tap(x, y)
            return McpToolUtils.handleActionResult(result, "Tap executed at (${x.toInt()}, ${y.toInt()})")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Performs a single tap at the specified coordinates.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("x") {
                                put("type", "number")
                                put("description", "X coordinate")
                            }
                            putJsonObject("y") {
                                put("type", "number")
                                put("description", "Y coordinate")
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("x"))
                                add(JsonPrimitive("y"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "tap"
            private const val TAG = "MCP:TapTool"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// long_press
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `long_press`.
 *
 * Performs a long press at the specified coordinates.
 *
 * **Input**: `{ "x": <number>, "y": <number>, "duration": <number> }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Long press executed at (x, y) for Nms" }] }`
 * **Errors**:
 *   - -32602 if parameters are missing or invalid
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if long press gesture execution failed
 */
class LongPressTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val x = McpToolUtils.requireFloat(params, "x")
            val y = McpToolUtils.requireFloat(params, "y")
            val duration = McpToolUtils.optionalLong(params, "duration", DEFAULT_DURATION_MS)
            McpToolUtils.validateNonNegative(x, "x")
            McpToolUtils.validateNonNegative(y, "y")
            McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)

            Log.d(TAG, "Executing long press at ($x, $y) for ${duration}ms")
            val result = actionExecutor.longPress(x, y, duration)
            return McpToolUtils.handleActionResult(
                result,
                "Long press executed at (${x.toInt()}, ${y.toInt()}) for ${duration}ms",
            )
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Performs a long press at the specified coordinates.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("x") {
                                put("type", "number")
                                put("description", "X coordinate")
                            }
                            putJsonObject("y") {
                                put("type", "number")
                                put("description", "Y coordinate")
                            }
                            putJsonObject("duration") {
                                put("type", "number")
                                put("description", "Press duration in ms")
                                put("default", DEFAULT_DURATION_MS)
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("x"))
                                add(JsonPrimitive("y"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "long_press"
            private const val TAG = "MCP:LongPressTool"
            private const val DEFAULT_DURATION_MS = 1000L
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// double_tap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `double_tap`.
 *
 * Performs a double tap at the specified coordinates.
 *
 * **Input**: `{ "x": <number>, "y": <number> }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Double tap executed at (x, y)" }] }`
 * **Errors**:
 *   - -32602 if parameters are missing or invalid
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if double tap gesture execution failed
 */
class DoubleTapTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val x = McpToolUtils.requireFloat(params, "x")
            val y = McpToolUtils.requireFloat(params, "y")
            McpToolUtils.validateNonNegative(x, "x")
            McpToolUtils.validateNonNegative(y, "y")

            Log.d(TAG, "Executing double tap at ($x, $y)")
            val result = actionExecutor.doubleTap(x, y)
            return McpToolUtils.handleActionResult(
                result,
                "Double tap executed at (${x.toInt()}, ${y.toInt()})",
            )
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Performs a double tap at the specified coordinates.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("x") {
                                put("type", "number")
                                put("description", "X coordinate")
                            }
                            putJsonObject("y") {
                                put("type", "number")
                                put("description", "Y coordinate")
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("x"))
                                add(JsonPrimitive("y"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "double_tap"
            private const val TAG = "MCP:DoubleTapTool"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// swipe
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `swipe`.
 *
 * Performs a swipe gesture from one point to another.
 *
 * **Input**: `{ "x1": <number>, "y1": <number>, "x2": <number>, "y2": <number>, "duration": <number> }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Swipe executed from (...) to (...) over Nms" }] }`
 * **Errors**:
 *   - -32602 if parameters are missing or invalid
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if swipe gesture execution failed
 */
class SwipeTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val x1 = McpToolUtils.requireFloat(params, "x1")
            val y1 = McpToolUtils.requireFloat(params, "y1")
            val x2 = McpToolUtils.requireFloat(params, "x2")
            val y2 = McpToolUtils.requireFloat(params, "y2")
            val duration = McpToolUtils.optionalLong(params, "duration", DEFAULT_DURATION_MS)
            McpToolUtils.validateNonNegative(x1, "x1")
            McpToolUtils.validateNonNegative(y1, "y1")
            McpToolUtils.validateNonNegative(x2, "x2")
            McpToolUtils.validateNonNegative(y2, "y2")
            McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)

            Log.d(TAG, "Executing swipe from ($x1, $y1) to ($x2, $y2) over ${duration}ms")
            val result = actionExecutor.swipe(x1, y1, x2, y2, duration)
            return McpToolUtils.handleActionResult(
                result,
                "Swipe executed from (${x1.toInt()}, ${y1.toInt()}) to " +
                    "(${x2.toInt()}, ${y2.toInt()}) over ${duration}ms",
            )
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Performs a swipe gesture from one point to another.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("x1") {
                                put("type", "number")
                                put("description", "Start X coordinate")
                            }
                            putJsonObject("y1") {
                                put("type", "number")
                                put("description", "Start Y coordinate")
                            }
                            putJsonObject("x2") {
                                put("type", "number")
                                put("description", "End X coordinate")
                            }
                            putJsonObject("y2") {
                                put("type", "number")
                                put("description", "End Y coordinate")
                            }
                            putJsonObject("duration") {
                                put("type", "number")
                                put("description", "Swipe duration in ms")
                                put("default", DEFAULT_DURATION_MS)
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("x1"))
                                add(JsonPrimitive("y1"))
                                add(JsonPrimitive("x2"))
                                add(JsonPrimitive("y2"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "swipe"
            private const val TAG = "MCP:SwipeTool"
            private const val DEFAULT_DURATION_MS = 300L
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// scroll
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `scroll`.
 *
 * Scrolls the screen in a specified direction.
 *
 * **Input**: `{ "direction": "up"|"down"|"left"|"right", "amount": "small"|"medium"|"large" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Scroll down (medium) executed" }] }`
 * **Errors**:
 *   - -32602 if direction is invalid or amount is invalid
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if scroll gesture execution failed
 */
class ScrollTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val directionStr = McpToolUtils.requireString(params, "direction")
            val amountStr = McpToolUtils.optionalString(params, "amount", "medium")

            val direction =
                when (directionStr.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> throw McpToolException.InvalidParams(
                        "Parameter 'direction' must be one of: up, down, left, right. Got: '$directionStr'",
                    )
                }

            val amount =
                when (amountStr.lowercase()) {
                    "small" -> ScrollAmount.SMALL
                    "medium" -> ScrollAmount.MEDIUM
                    "large" -> ScrollAmount.LARGE
                    else -> throw McpToolException.InvalidParams(
                        "Parameter 'amount' must be one of: small, medium, large. Got: '$amountStr'",
                    )
                }

            Log.d(TAG, "Executing scroll ${direction.name} with amount ${amount.name}")
            val result = actionExecutor.scroll(direction, amount)
            return McpToolUtils.handleActionResult(
                result,
                "Scroll ${directionStr.lowercase()} (${amountStr.lowercase()}) executed",
            )
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Scrolls in the specified direction.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("direction") {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("up"))
                                        add(JsonPrimitive("down"))
                                        add(JsonPrimitive("left"))
                                        add(JsonPrimitive("right"))
                                    },
                                )
                            }
                            putJsonObject("amount") {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(JsonPrimitive("small"))
                                        add(JsonPrimitive("medium"))
                                        add(JsonPrimitive("large"))
                                    },
                                )
                                put("default", "medium")
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("direction"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "scroll"
            private const val TAG = "MCP:ScrollTool"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all touch action tools with the given [ToolRegistry].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerTouchActionTools(toolRegistry: ToolRegistry) {
    val actionExecutor = ActionExecutor()
    TapTool(actionExecutor).register(toolRegistry)
    LongPressTool(actionExecutor).register(toolRegistry)
    DoubleTapTool(actionExecutor).register(toolRegistry)
    SwipeTool(actionExecutor).register(toolRegistry)
    ScrollTool(actionExecutor).register(toolRegistry)
}
