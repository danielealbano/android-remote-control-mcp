@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
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
        @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // 1. Parse include_screenshot param FIRST (before expensive operations)
            val includeScreenshot =
                arguments?.get("include_screenshot")?.jsonPrimitive?.booleanOrNull ?: false

            // 2. Get multi-window accessibility snapshot
            //    (getFreshWindows handles isReady check and fallback to single-window)
            val result = getFreshWindows(treeParser, accessibilityServiceProvider)

            // 3. Get screen info
            val screenInfo = accessibilityServiceProvider.getScreenInfo()

            // 4. Format compact multi-window output
            val compactOutput = compactTreeFormatter.formatMultiWindow(result, screenInfo)

            Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot")

            // 5. Optionally include annotated screenshot.
            // NOTE: There is an inherent timing gap between tree parsing and
            // screenshot capture below. If the UI changes in between, bounding boxes may
            // reference stale element positions. Atomic capture is not possible with the
            // current Android accessibility APIs.
            if (includeScreenshot) {
                if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                    throw McpToolException.PermissionDenied(
                        "Screen capture not available. Please enable the accessibility " +
                            "service in Android Settings.",
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
                    // Collect on-screen elements from ALL windows' trees
                    val onScreenElements = collectOnScreenElements(result.windows)

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

                    return McpToolUtils.textAndImageResult(
                        compactOutput,
                        screenshotData.data,
                        "image/jpeg",
                    )
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

            // 6. Return text-only result
            return McpToolUtils.textResult(compactOutput)
        }

        /**
         * Collects elements from all windows that should be annotated on the screenshot:
         * nodes that pass the formatter's keep filter AND are visible (on-screen).
         */
        private fun collectOnScreenElements(
            windows: List<WindowData>,
        ): List<AccessibilityNodeData> {
            val result = mutableListOf<AccessibilityNodeData>()
            for (windowData in windows) {
                collectOnScreenElementsFromTree(windowData.tree, result)
            }
            return result
        }

        private fun collectOnScreenElementsFromTree(
            node: AccessibilityNodeData,
            result: MutableList<AccessibilityNodeData>,
        ) {
            if (compactTreeFormatter.shouldKeepNode(node) && node.visible) {
                result.add(node)
            }
            for (child in node.children) {
                collectOnScreenElementsFromTree(child, result)
            }
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
