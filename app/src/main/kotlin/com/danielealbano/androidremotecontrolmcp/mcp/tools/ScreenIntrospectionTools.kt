package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_accessibility_tree
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_accessibility_tree`.
 *
 * Returns the full UI hierarchy of the current screen as JSON.
 * The tree is obtained from the accessibility service and parsed
 * via [AccessibilityTreeParser].
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "<tree JSON>" }] }`
 * **Error**: -32001 if accessibility service is not enabled
 */
class GetAccessibilityTreeHandler
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled or not ready. " +
                        "Please enable it in Android Settings > Accessibility.",
                )
            }

            val rootNode =
                accessibilityServiceProvider.getRootNode()
                    ?: throw McpToolException.ActionFailed(
                        "Failed to obtain root accessibility node.",
                    )

            return try {
                val treeData = treeParser.parseTree(rootNode)
                val treeJson =
                    buildJsonObject {
                        putJsonArray("nodes") {
                            add(
                                Json.encodeToJsonElement(
                                    AccessibilityNodeData.serializer(),
                                    treeData,
                                ),
                            )
                        }
                    }
                McpToolUtils.textResult(Json.encodeToString(treeJson))
            } finally {
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Returns the full UI hierarchy of the current screen using accessibility services.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_accessibility_tree"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// capture_screenshot
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `capture_screenshot`.
 *
 * Captures a screenshot of the current screen and returns it as base64-encoded
 * JPEG data with image metadata.
 *
 * **Input**: `{ "quality": 80 }` (optional, default 80, range 1-100)
 * **Output**: `{ "content": [{ "type": "image", "data": "<base64>", "mimeType": "image/jpeg" }] }`
 * **Errors**:
 *   - -32001 if screen capture is not available (accessibility service not enabled)
 *   - -32602 if quality parameter is out of range
 *   - -32003 if screenshot capture fails
 */
class CaptureScreenshotHandler
    @Inject
    constructor(
        private val screenCaptureProvider: ScreenCaptureProvider,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val quality = parseQuality(arguments)
            val maxWidth = parseOptionalPositiveInt(arguments, "width")
            val maxHeight = parseOptionalPositiveInt(arguments, "height")

            if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                throw McpToolException.PermissionDenied(
                    "Screen capture not available. Please enable the accessibility service in Android Settings.",
                )
            }

            val result = screenCaptureProvider.captureScreenshot(quality, maxWidth, maxHeight)
            val screenshotData =
                result.getOrElse { exception ->
                    throw McpToolException.ActionFailed(
                        "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
                    )
                }

            return McpToolUtils.imageResult(data = screenshotData.data, mimeType = "image/jpeg")
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private fun parseQuality(params: JsonObject?): Int {
            val qualityElement = params?.get("quality") ?: return DEFAULT_QUALITY
            val quality =
                try {
                    qualityElement.jsonPrimitive.int
                } catch (e: Exception) {
                    throw McpToolException.InvalidParams(
                        "Quality must be an integer between $MIN_QUALITY and $MAX_QUALITY, got $qualityElement",
                    )
                }
            if (quality < MIN_QUALITY || quality > MAX_QUALITY) {
                throw McpToolException.InvalidParams(
                    "Quality must be between $MIN_QUALITY and $MAX_QUALITY, got $quality",
                )
            }
            return quality
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private fun parseOptionalPositiveInt(
            params: JsonObject?,
            name: String,
        ): Int? {
            val element = params?.get(name) ?: return null
            val value =
                try {
                    element.jsonPrimitive.int
                } catch (e: Exception) {
                    throw McpToolException.InvalidParams(
                        "'$name' must be a positive integer, got $element",
                    )
                }
            if (value <= 0) {
                throw McpToolException.InvalidParams(
                    "'$name' must be a positive integer, got $value",
                )
            }
            return value
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Captures a screenshot of the current screen and returns it as base64-encoded JPEG.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("quality") {
                                    put("type", "integer")
                                    put("description", "JPEG quality (1-100)")
                                    put("default", DEFAULT_QUALITY)
                                }
                                putJsonObject("width") {
                                    put("type", "integer")
                                    put("description", "Maximum width in pixels. Image is resized proportionally.")
                                }
                                putJsonObject("height") {
                                    put("type", "integer")
                                    put("description", "Maximum height in pixels. Image is resized proportionally.")
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "capture_screenshot"
            const val DEFAULT_QUALITY = 80
            const val MIN_QUALITY = 1
            const val MAX_QUALITY = 100
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// get_current_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_current_app`.
 *
 * Returns the package name and activity name of the currently focused application.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "{\"packageName\":\"...\",\"activityName\":\"...\"}" }] }`
 * **Error**: -32001 if accessibility service is not enabled
 */
class GetCurrentAppHandler
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
                )
            }

            val packageName = accessibilityServiceProvider.getCurrentPackageName() ?: "unknown"
            val activityName = accessibilityServiceProvider.getCurrentActivityName() ?: "unknown"

            val resultJson =
                buildJsonObject {
                    put("packageName", packageName)
                    put("activityName", activityName)
                }

            return McpToolUtils.textResult(Json.encodeToString(resultJson))
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Returns the package name and activity name of the currently focused app.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_current_app"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// get_screen_info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_screen_info`.
 *
 * Returns the screen dimensions, density DPI, and orientation.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "{\"width\":1080,...}" }] }`
 * **Error**: -32001 if accessibility service is not enabled
 */
class GetScreenInfoHandler
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val resultJson =
                Json.encodeToString(
                    ScreenInfo.serializer(),
                    screenInfo,
                )

            return McpToolUtils.textResult(resultJson)
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Returns screen dimensions, orientation, and DPI.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_screen_info"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all screen introspection tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerScreenIntrospectionTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
) {
    GetAccessibilityTreeHandler(treeParser, accessibilityServiceProvider).register(server)
    CaptureScreenshotHandler(screenCaptureProvider).register(server)
    GetCurrentAppHandler(accessibilityServiceProvider).register(server)
    GetScreenInfoHandler(accessibilityServiceProvider).register(server)
}
