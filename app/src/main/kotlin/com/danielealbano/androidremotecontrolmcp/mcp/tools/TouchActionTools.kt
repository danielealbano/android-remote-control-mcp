@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
 */
class TapTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val x = McpToolUtils.requireFloat(arguments, "x")
            val y = McpToolUtils.requireFloat(arguments, "y")
            McpToolUtils.validateNonNegative(x, "x")
            McpToolUtils.validateNonNegative(y, "y")

            Log.d(TAG, "Executing tap at ($x, $y)")
            val result = actionExecutor.tap(x, y)
            return McpToolUtils.handleActionResult(result, "Tap executed at (${x.toInt()}, ${y.toInt()})")
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Performs a single tap at the specified coordinates.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("x") {
                                    put("type", "number")
                                    put("description", "X coordinate")
                                }
                                putJsonObject("y") {
                                    put("type", "number")
                                    put("description", "Y coordinate")
                                }
                            },
                        required = listOf("x", "y"),
                    ),
            ) { request -> execute(request.arguments) }
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
 */
class LongPressTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val x = McpToolUtils.requireFloat(arguments, "x")
            val y = McpToolUtils.requireFloat(arguments, "y")
            val duration = McpToolUtils.optionalLong(arguments, "duration", DEFAULT_DURATION_MS)
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

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Performs a long press at the specified coordinates.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
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
                            },
                        required = listOf("x", "y"),
                    ),
            ) { request -> execute(request.arguments) }
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
 */
class DoubleTapTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val x = McpToolUtils.requireFloat(arguments, "x")
            val y = McpToolUtils.requireFloat(arguments, "y")
            McpToolUtils.validateNonNegative(x, "x")
            McpToolUtils.validateNonNegative(y, "y")

            Log.d(TAG, "Executing double tap at ($x, $y)")
            val result = actionExecutor.doubleTap(x, y)
            return McpToolUtils.handleActionResult(
                result,
                "Double tap executed at (${x.toInt()}, ${y.toInt()})",
            )
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Performs a double tap at the specified coordinates.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("x") {
                                    put("type", "number")
                                    put("description", "X coordinate")
                                }
                                putJsonObject("y") {
                                    put("type", "number")
                                    put("description", "Y coordinate")
                                }
                            },
                        required = listOf("x", "y"),
                    ),
            ) { request -> execute(request.arguments) }
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
 */
class SwipeTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val x1 = McpToolUtils.requireFloat(arguments, "x1")
            val y1 = McpToolUtils.requireFloat(arguments, "y1")
            val x2 = McpToolUtils.requireFloat(arguments, "x2")
            val y2 = McpToolUtils.requireFloat(arguments, "y2")
            val duration = McpToolUtils.optionalLong(arguments, "duration", DEFAULT_DURATION_MS)
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

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Performs a swipe gesture from one point to another.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
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
                            },
                        required = listOf("x1", "y1", "x2", "y2"),
                    ),
            ) { request -> execute(request.arguments) }
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
 */
class ScrollTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val directionStr = McpToolUtils.requireString(arguments, "direction")
            val amountStr = McpToolUtils.optionalString(arguments, "amount", "medium")

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

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Scrolls in the specified direction.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
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
                            },
                        required = listOf("direction"),
                    ),
            ) { request -> execute(request.arguments) }
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
 * Registers all touch action tools with the given [Server].
 */
fun registerTouchActionTools(
    server: Server,
    actionExecutor: ActionExecutor,
) {
    TapTool(actionExecutor).register(server)
    LongPressTool(actionExecutor).register(server)
    DoubleTapTool(actionExecutor).register(server)
    SwipeTool(actionExecutor).register(server)
    ScrollTool(actionExecutor).register(server)
}
