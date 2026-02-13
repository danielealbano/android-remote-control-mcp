package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
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
                McpContentBuilder.textContent(Json.encodeToString(treeJson))
            } finally {
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Returns the full UI hierarchy of the current screen using accessibility services.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
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
 *   - -32001 if MediaProjection permission not granted
 *   - -32602 if quality parameter is out of range
 *   - -32003 if screenshot capture fails
 */
class CaptureScreenshotHandler
    @Inject
    constructor(
        private val screenCaptureProvider: ScreenCaptureProvider,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val quality = parseQuality(params)

            if (!screenCaptureProvider.isMediaProjectionActive()) {
                throw McpToolException.PermissionDenied(
                    "MediaProjection permission not granted. Please grant screen capture permission in the app.",
                )
            }

            val result = screenCaptureProvider.captureScreenshot(quality)
            val screenshotData =
                result.getOrElse { exception ->
                    throw McpToolException.ActionFailed(
                        "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
                    )
                }

            return McpContentBuilder.imageContent(
                data = screenshotData.data,
                mimeType = "image/jpeg",
                width = screenshotData.width,
                height = screenshotData.height,
            )
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

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Captures a screenshot of the current screen and returns it as base64-encoded JPEG.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("quality") {
                                put("type", "integer")
                                put("description", "JPEG quality (1-100)")
                                put("default", DEFAULT_QUALITY)
                            }
                        }
                        putJsonArray("required") {}
                    },
                handler = this,
            )
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
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

            return McpContentBuilder.textContent(Json.encodeToString(resultJson))
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Returns the package name and activity name of the currently focused app.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val resultJson =
                Json.encodeToString(
                    ScreenInfo.serializer(),
                    screenInfo,
                )

            return McpContentBuilder.textContent(resultJson)
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Returns screen dimensions, orientation, and DPI.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "get_screen_info"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all screen introspection tools with the given [ToolRegistry].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerScreenIntrospectionTools(
    toolRegistry: ToolRegistry,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
) {
    GetAccessibilityTreeHandler(treeParser, accessibilityServiceProvider).register(toolRegistry)
    CaptureScreenshotHandler(screenCaptureProvider).register(toolRegistry)
    GetCurrentAppHandler(accessibilityServiceProvider).register(toolRegistry)
    GetScreenInfoHandler(accessibilityServiceProvider).register(toolRegistry)
}
