@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
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

                val result =
                    screenCaptureProvider.captureScreenshot(
                        quality = ScreenCaptureProvider.DEFAULT_QUALITY,
                        maxWidth = SCREENSHOT_MAX_SIZE,
                        maxHeight = SCREENSHOT_MAX_SIZE,
                    )
                val screenshotData =
                    result.getOrElse { exception ->
                        throw McpToolException.ActionFailed(
                            "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
                        )
                    }

                return McpToolUtils.textAndImageResult(compactOutput, screenshotData.data, "image/jpeg")
            }

            // 9. Return text-only result
            return McpToolUtils.textResult(compactOutput)
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
            const val SCREENSHOT_MAX_SIZE = 700
            private const val TAG = "MCP:GetScreenStateTool"
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
    toolNamePrefix: String,
) {
    GetScreenStateHandler(treeParser, accessibilityServiceProvider, screenCaptureProvider, compactTreeFormatter)
        .register(server, toolNamePrefix)
}
