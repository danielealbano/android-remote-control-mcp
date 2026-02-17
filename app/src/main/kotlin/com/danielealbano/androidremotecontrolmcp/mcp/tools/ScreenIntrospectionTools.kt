@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_screen_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_screen_state`.
 *
 * Returns consolidated screen state: app metadata, screen info, and a compact
 * flat TSV-formatted list of UI elements. Optionally includes a low-resolution screenshot.
 *
 * Replaces: get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info.
 */
class GetScreenStateHandler
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val screenshotAnnotator: ScreenshotAnnotator,
        private val screenshotEncoder: ScreenshotEncoder,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // 1. Parse include_screenshot param FIRST (before expensive operations)
            val includeScreenshot =
                arguments?.get("include_screenshot")?.jsonPrimitive?.booleanOrNull ?: false

            // 2. Check accessibility service
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled or not ready. " +
                        "Please enable it in Android Settings > Accessibility.",
                )
            }

            // 3. Get root node
            val rootNode =
                accessibilityServiceProvider.getRootNode()
                    ?: throw McpToolException.ActionFailed(
                        "Failed to obtain root accessibility node.",
                    )

            // 4. Parse tree (recycle root node in finally)
            val tree =
                try {
                    treeParser.parseTree(rootNode)
                } finally {
                    @Suppress("DEPRECATION")
                    rootNode.recycle()
                }

            // 5. Get app info
            val packageName = accessibilityServiceProvider.getCurrentPackageName() ?: "unknown"
            val activityName = accessibilityServiceProvider.getCurrentActivityName() ?: "unknown"

            // 6. Get screen info
            val screenInfo = accessibilityServiceProvider.getScreenInfo()

            // 7. Format compact output
            val compactOutput = compactTreeFormatter.format(tree, packageName, activityName, screenInfo)

            Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot")

            // 8. Optionally include screenshot
            if (includeScreenshot) {
                if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                    throw McpToolException.PermissionDenied(
                        "Screen capture not available. Please enable the accessibility service in Android Settings.",
                    )
                }

                val bitmapResult =
                    screenCaptureProvider.captureScreenshotBitmap(
                        maxWidth = SCREENSHOT_MAX_SIZE,
                        maxHeight = SCREENSHOT_MAX_SIZE,
                    )
                val resizedBitmap =
                    bitmapResult.getOrElse { exception ->
                        Log.e(TAG, "Screenshot capture failed", exception)
                        throw McpToolException.ActionFailed(
                            "Screenshot capture failed",
                        )
                    }

                var annotatedBitmap: Bitmap? = null
                try {
                    // Collect on-screen elements that appear in the TSV
                    val onScreenElements = collectOnScreenElements(tree)

                    // Annotate the screenshot with bounding boxes
                    annotatedBitmap =
                        screenshotAnnotator.annotate(
                            resizedBitmap,
                            onScreenElements,
                            screenInfo.width,
                            screenInfo.height,
                        )

                    // Encode annotated bitmap to base64 JPEG
                    val screenshotData =
                        screenshotEncoder.bitmapToScreenshotData(
                            annotatedBitmap,
                            ScreenCaptureProvider.DEFAULT_QUALITY,
                        )

                    return McpToolUtils.textAndImageResult(compactOutput, screenshotData.data, "image/jpeg")
                } catch (e: McpToolException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot annotation failed", e)
                    throw McpToolException.ActionFailed(
                        "Screenshot annotation failed",
                    )
                } finally {
                    annotatedBitmap?.recycle()
                    resizedBitmap.recycle()
                }
            }

            // 9. Return text-only result
            return McpToolUtils.textResult(compactOutput)
        }

        /**
         * Collects elements that should be annotated on the screenshot:
         * nodes that pass the formatter's keep filter AND are visible (on-screen).
         *
         * Uses an accumulator parameter to avoid O(N) intermediate list allocations
         * and O(N^2) element copies from recursive addAll calls.
         */
        private fun collectOnScreenElements(
            node: AccessibilityNodeData,
            result: MutableList<AccessibilityNodeData> = mutableListOf(),
        ): List<AccessibilityNodeData> {
            if (compactTreeFormatter.shouldKeepNode(node) && node.visible) {
                result.add(node)
            }
            for (child in node.children) {
                collectOnScreenElements(child, result)
            }
            return result
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Returns the current screen state: app info, screen dimensions, " +
                        "and a compact UI element list (text/desc truncated to 100 chars, use " +
                        "${toolNamePrefix}get_element_details to retrieve full values). Optionally includes a " +
                        "low-resolution screenshot (only request the screenshot when the element " +
                        "list alone is not sufficient to understand the screen layout).",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("include_screenshot") {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "Include a low-resolution screenshot. " +
                                            "Only request when the UI element list is not sufficient.",
                                    )
                                    put("default", false)
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_screen_state"
            internal const val SCREENSHOT_MAX_SIZE = 700
            private const val TAG = "MCP:ScreenIntrospection"
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
@Suppress("LongParameterList")
fun registerScreenIntrospectionTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
    compactTreeFormatter: CompactTreeFormatter,
    screenshotAnnotator: ScreenshotAnnotator,
    screenshotEncoder: ScreenshotEncoder,
    toolNamePrefix: String,
) {
    GetScreenStateHandler(
        treeParser,
        accessibilityServiceProvider,
        screenCaptureProvider,
        compactTreeFormatter,
        screenshotAnnotator,
        screenshotEncoder,
    ).register(server, toolNamePrefix)
}
