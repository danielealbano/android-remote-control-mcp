package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.GesturePoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// pinch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `pinch`.
 *
 * Performs a pinch-to-zoom gesture centered at the specified coordinates.
 * Scale > 1 = zoom in (fingers move apart), scale < 1 = zoom out (fingers come together).
 *
 * **Input**: `{ "center_x": <number>, "center_y": <number>, "scale": <number>, "duration": <number> }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Pinch (zoom in) executed at (...)" }] }`
 * **Errors**:
 *   - InvalidParams if parameters are missing or invalid (negative coords, scale <= 0, bad duration)
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if pinch gesture execution failed
 */
class PinchTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val centerX = McpToolUtils.requireFloat(arguments, "center_x")
            val centerY = McpToolUtils.requireFloat(arguments, "center_y")
            val scale = McpToolUtils.requireFloat(arguments, "scale")
            val duration = McpToolUtils.optionalLong(arguments, "duration", DEFAULT_DURATION_MS)

            McpToolUtils.validateNonNegative(centerX, "center_x")
            McpToolUtils.validateNonNegative(centerY, "center_y")

            if (scale <= 0f) {
                throw McpToolException.InvalidParams(
                    "Parameter 'scale' must be > 0, got: $scale",
                )
            }

            McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)

            val zoomType = if (scale > 1f) "in" else "out"
            Log.d(TAG, "Executing pinch (zoom $zoomType) at ($centerX, $centerY) scale=$scale, ${duration}ms")
            val result = actionExecutor.pinch(centerX, centerY, scale, duration)
            return McpToolUtils.handleActionResult(
                result,
                "Pinch (zoom $zoomType) executed at (${centerX.toInt()}, ${centerY.toInt()}) " +
                    "with scale $scale over ${duration}ms",
            )
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Performs a pinch-to-zoom gesture.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("center_x") {
                                    put("type", "number")
                                    put("description", "Center X coordinate")
                                }
                                putJsonObject("center_y") {
                                    put("type", "number")
                                    put("description", "Center Y coordinate")
                                }
                                putJsonObject("scale") {
                                    put("type", "number")
                                    put("description", "Scale factor (>1 = zoom in, <1 = zoom out)")
                                }
                                putJsonObject("duration") {
                                    put("type", "number")
                                    put("description", "Gesture duration in ms")
                                    put("default", DEFAULT_DURATION_MS)
                                }
                            },
                        required = listOf("center_x", "center_y", "scale"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "pinch"
            private const val TAG = "MCP:PinchTool"
            private const val DEFAULT_DURATION_MS = 300L
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// custom_gesture
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `custom_gesture`.
 *
 * Executes a custom multi-touch gesture defined by path points.
 * Each path represents one finger's movement. Multiple paths enable multi-finger gestures.
 *
 * **Input**: `{ "paths": [ [ { "x": <n>, "y": <n>, "time": <n> }, ... ], ... ] }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Custom gesture executed with N path(s), ..." }] }`
 * **Errors**:
 *   - InvalidParams if paths is missing, empty, has < 2 points per path, negative coords/times, or non-monotonic times
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if custom gesture execution failed
 */
class CustomGestureTool
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val pathsElement =
                arguments?.get("paths")
                    ?: throw McpToolException.InvalidParams("Missing required parameter: 'paths'")

            val pathsArray =
                pathsElement as? JsonArray
                    ?: throw McpToolException.InvalidParams("Parameter 'paths' must be an array")

            if (pathsArray.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'paths' must not be empty")
            }

            val paths = parsePaths(pathsArray)

            Log.d(TAG, "Executing custom gesture with ${paths.size} path(s)")
            val result = actionExecutor.customGesture(paths)
            return McpToolUtils.handleActionResult(
                result,
                "Custom gesture executed with ${paths.size} path(s), " +
                    "total ${paths.sumOf { it.size }} point(s)",
            )
        }

        @Suppress("ThrowsCount")
        private fun parsePaths(pathsArray: JsonArray): List<List<GesturePoint>> {
            val paths = mutableListOf<List<GesturePoint>>()

            for ((pathIndex, pathElement) in pathsArray.withIndex()) {
                val pointsArray =
                    pathElement as? JsonArray
                        ?: throw McpToolException.InvalidParams(
                            "Path at index $pathIndex must be an array of point objects",
                        )

                if (pointsArray.size < MIN_POINTS_PER_PATH) {
                    throw McpToolException.InvalidParams(
                        "Path at index $pathIndex must have at least 2 points, has ${pointsArray.size}",
                    )
                }

                paths.add(parsePoints(pointsArray, pathIndex))
            }

            return paths
        }

        @Suppress("ThrowsCount")
        private fun parsePoints(
            pointsArray: JsonArray,
            pathIndex: Int,
        ): List<GesturePoint> {
            val points = mutableListOf<GesturePoint>()
            var previousTime = -1L

            for ((pointIndex, pointElement) in pointsArray.withIndex()) {
                val pointObj =
                    pointElement as? JsonObject
                        ?: throw McpToolException.InvalidParams(
                            "Point at path[$pathIndex][$pointIndex] must be an object with x, y, time",
                        )

                val x = extractPointFloat(pointObj, "x", pathIndex, pointIndex)
                val y = extractPointFloat(pointObj, "y", pathIndex, pointIndex)
                val time = extractPointLong(pointObj, "time", pathIndex, pointIndex)

                validatePointValues(x, y, time, previousTime, pathIndex, pointIndex)

                previousTime = time
                points.add(GesturePoint(x, y, time))
            }

            return points
        }

        @Suppress("ThrowsCount", "LongParameterList")
        private fun validatePointValues(
            x: Float,
            y: Float,
            time: Long,
            previousTime: Long,
            pathIndex: Int,
            pointIndex: Int,
        ) {
            if (x < 0f) {
                throw McpToolException.InvalidParams(
                    "Point at path[$pathIndex][$pointIndex].x must be >= 0, got: $x",
                )
            }
            if (y < 0f) {
                throw McpToolException.InvalidParams(
                    "Point at path[$pathIndex][$pointIndex].y must be >= 0, got: $y",
                )
            }
            if (time < 0L) {
                throw McpToolException.InvalidParams(
                    "Point at path[$pathIndex][$pointIndex].time must be >= 0, got: $time",
                )
            }
            if (time <= previousTime) {
                throw McpToolException.InvalidParams(
                    "Times must be monotonically increasing within each path. " +
                        "path[$pathIndex][$pointIndex].time ($time) <= previous time ($previousTime)",
                )
            }
        }

        @Suppress("ThrowsCount")
        private fun extractPointFloat(
            obj: JsonObject,
            field: String,
            pathIndex: Int,
            pointIndex: Int,
        ): Float {
            val element =
                obj[field]
                    ?: throw McpToolException.InvalidParams(
                        "Missing '$field' in point at path[$pathIndex][$pointIndex]",
                    )
            val primitive =
                element as? JsonPrimitive
                    ?: throw McpToolException.InvalidParams(
                        "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number",
                    )
            if (primitive.isString) {
                throw McpToolException.InvalidParams(
                    "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, " +
                        "got string: '${primitive.content}'",
                )
            }
            val value =
                primitive.content.toFloatOrNull()
                    ?: throw McpToolException.InvalidParams(
                        "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, " +
                            "got: '${primitive.content}'",
                    )
            if (!value.isFinite()) {
                throw McpToolException.InvalidParams(
                    "Field '$field' in point at path[$pathIndex][$pointIndex] must be a finite number, " +
                        "got: '${primitive.content}'",
                )
            }
            return value
        }

        @Suppress("ThrowsCount")
        private fun extractPointLong(
            obj: JsonObject,
            field: String,
            pathIndex: Int,
            pointIndex: Int,
        ): Long {
            val element =
                obj[field]
                    ?: throw McpToolException.InvalidParams(
                        "Missing '$field' in point at path[$pathIndex][$pointIndex]",
                    )
            val primitive =
                element as? JsonPrimitive
                    ?: throw McpToolException.InvalidParams(
                        "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number",
                    )
            if (primitive.isString) {
                throw McpToolException.InvalidParams(
                    "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, " +
                        "got string: '${primitive.content}'",
                )
            }
            val doubleVal =
                primitive.content.toDoubleOrNull()
                    ?: throw McpToolException.InvalidParams(
                        "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, " +
                            "got: '${primitive.content}'",
                    )
            val longVal = doubleVal.toLong()
            if (doubleVal != longVal.toDouble()) {
                throw McpToolException.InvalidParams(
                    "Field '$field' in point at path[$pathIndex][$pointIndex] must be an integer, " +
                        "got: '${primitive.content}'",
                )
            }
            return longVal
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Executes a custom multi-touch gesture defined by path points.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("paths") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "array")
                                        putJsonObject("items") {
                                            put("type", "object")
                                            putJsonObject("properties") {
                                                putJsonObject("x") {
                                                    put("type", "number")
                                                }
                                                putJsonObject("y") {
                                                    put("type", "number")
                                                }
                                                putJsonObject("time") {
                                                    put("type", "number")
                                                    put("description", "Time offset in ms")
                                                }
                                            }
                                            put(
                                                "required",
                                                buildJsonArray {
                                                    add(JsonPrimitive("x"))
                                                    add(JsonPrimitive("y"))
                                                    add(JsonPrimitive("time"))
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        required = listOf("paths"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "custom_gesture"
            private const val TAG = "MCP:CustomGestureTool"
            private const val MIN_POINTS_PER_PATH = 2
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all gesture tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerGestureTools(
    server: Server,
    actionExecutor: ActionExecutor,
    toolNamePrefix: String,
) {
    PinchTool(actionExecutor).register(server, toolNamePrefix)
    CustomGestureTool(actionExecutor).register(server, toolNamePrefix)
}
