package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP tool: get_node_screenshots
 *
 * One JPEG per requested node, cropped from the screen and masked by Privacy Mode like `get_screen_state`'s
 * screenshot. With `allow_scroll` the app is scrolled to capture nodes whole, which changes node ids. The reply is one
 * text item (the untrusted content warning, the scrolled note, one line per requested id), then the images in request
 * order. See [NodeScreenshotSession].
 */
class GetNodeScreenshotsTool(
    private val deps: NodeScreenshotDeps,
) {
    private class Request(
        val nodeIds: List<String>,
        val allowScroll: Boolean,
    )

    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val request = parseRequest(arguments)
        if (!deps.screenCaptureProvider.isScreenCaptureAvailable()) {
            throw McpToolException.PermissionDenied(
                "Screen capture not available. Please enable the accessibility service in Android Settings.",
            )
        }
        val shots = NodeScreenshotSession(deps).run(request.nodeIds, request.allowScroll)
        Log.d(
            TAG,
            "get_node_screenshots: nodes=${request.nodeIds.size} allowScroll=${request.allowScroll} " +
                "images=${shots.images.size} captures=${shots.captures} scrolled=${shots.scrolled}",
        )
        return McpToolUtils.untrustedTextAndImagesResult(shots.text, shots.images, "image/jpeg")
    }

    private fun parseRequest(arguments: JsonObject?): Request {
        val nodeIds = McpToolUtils.requireNodeIds(arguments)
        val seen = HashSet<String>()
        val duplicate = nodeIds.firstOrNull { !seen.add(it) }
        val error =
            when {
                nodeIds.size > MAX_NODE_IDS -> "Parameter 'node_ids' must have at most $MAX_NODE_IDS node ids"
                duplicate != null -> "Parameter 'node_ids' must not contain duplicates: '$duplicate'"
                else -> null
            }
        if (error != null) throw McpToolException.InvalidParams(error)
        return Request(nodeIds, McpToolUtils.optionalBoolean(arguments, PARAM_ALLOW_SCROLL, false))
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Screenshots of the given nodes, one JPEG per node (max 700 px). With allow_scroll=false (default) " +
                    "nothing moves: nodes not on screen get no image and partly visible nodes are captured as shown " +
                    "and marked partial. With allow_scroll=true the app is scrolled so each node is captured whole " +
                    "(a node longer than the screen comes in up to 3 overlapping parts); scrolling changes node ids, " +
                    "so use it with caution and read the screen again afterwards. The first text item says what each " +
                    "node got; image numbers count the images only, starting at 1.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject(McpToolUtils.PARAM_NODE_IDS) {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "string")
                                }
                                put(
                                    "description",
                                    "Node ids from ${toolNamePrefix}get_screen_state (1 to $MAX_NODE_IDS)",
                                )
                            }
                            putJsonObject(PARAM_ALLOW_SCROLL) {
                                put("type", "boolean")
                                put("default", false)
                                put(
                                    "description",
                                    "Scroll the app to capture nodes that are off screen or cut, whole. " +
                                        "Changes node ids.",
                                )
                            }
                        },
                    required = listOf(McpToolUtils.PARAM_NODE_IDS),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        private const val TAG = "MCP:GetNodeScreenshotsTool"
        const val TOOL_NAME = "get_node_screenshots"
        internal const val PARAM_ALLOW_SCROLL = "allow_scroll"
        internal const val MAX_NODE_IDS = 100
    }
}
