@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * MCP tool: find_elements
 *
 * Finds UI elements matching the specified criteria in the accessibility tree.
 * Returns an array of matching elements (may be empty — empty is NOT an error).
 */
class FindElementsTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // Validate parameters
            val byStr =
                arguments?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val value =
                arguments["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (value.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            val exactMatch = arguments["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            // Get fresh multi-window accessibility snapshot
            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

            // Search across all windows
            val elements = elementFinder.findElements(result.windows, findBy, value, exactMatch)

            Log.d(TAG, "find_elements: by=$byStr, value='$value', exactMatch=$exactMatch, found=${elements.size}")

            val resultJson =
                buildJsonObject {
                    put(
                        "elements",
                        buildJsonArray {
                            elements.forEach { element ->
                                add(
                                    buildJsonObject {
                                        put("id", element.id)
                                        put("text", element.text)
                                        put("contentDescription", element.contentDescription)
                                        put("resourceId", element.resourceId)
                                        put("className", element.className)
                                        putJsonObject("bounds") {
                                            put("left", element.bounds.left)
                                            put("top", element.bounds.top)
                                            put("right", element.bounds.right)
                                            put("bottom", element.bounds.bottom)
                                        }
                                        put("clickable", element.clickable)
                                        put("longClickable", element.longClickable)
                                        put("scrollable", element.scrollable)
                                        put("editable", element.editable)
                                        put("enabled", element.enabled)
                                    },
                                )
                            }
                        },
                    )
                }

            return McpToolUtils.textResult(Json.encodeToString(resultJson))
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Find UI elements matching the specified criteria " +
                        "(text, content_desc, resource_id, class_name)",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("by") {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("text"))
                                            add(JsonPrimitive("content_desc"))
                                            add(JsonPrimitive("resource_id"))
                                            add(JsonPrimitive("class_name"))
                                        },
                                    )
                                    put("description", "Search criteria type")
                                }
                                putJsonObject("value") {
                                    put("type", "string")
                                    put("description", "Search value")
                                }
                                putJsonObject("exact_match") {
                                    put("type", "boolean")
                                    put("default", false)
                                    put(
                                        "description",
                                        "If true, match exactly. If false, match contains (case-insensitive)",
                                    )
                                }
                            },
                        required = listOf("by", "value"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:FindElementsTool"
            private const val TOOL_NAME = "find_elements"
        }
    }

/**
 * MCP tool: click_element
 *
 * Clicks the accessibility node identified by element_id.
 */
class ClickElementTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId =
                arguments?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

            val result = actionExecutor.clickNode(elementId, multiWindowResult.windows)
            result.onFailure { e -> mapNodeActionException(e, elementId) }

            Log.d(TAG, "click_element: elementId=$elementId succeeded")
            return McpToolUtils.textResult("Click performed on element '$elementId'")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Click the specified accessibility node by element ID",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Node ID from ${toolNamePrefix}find_elements")
                                }
                            },
                        required = listOf("element_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ClickElementTool"
            private const val TOOL_NAME = "click_element"
        }
    }

/**
 * MCP tool: long_click_element
 *
 * Long-clicks the accessibility node identified by element_id.
 */
class LongClickElementTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId =
                arguments?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

            val result = actionExecutor.longClickNode(elementId, multiWindowResult.windows)
            result.onFailure { e -> mapNodeActionException(e, elementId) }

            Log.d(TAG, "long_click_element: elementId=$elementId succeeded")
            return McpToolUtils.textResult("Long-click performed on element '$elementId'")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Long-click the specified accessibility node by element ID",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Node ID from ${toolNamePrefix}find_elements")
                                }
                            },
                        required = listOf("element_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:LongClickElementTool"
            private const val TOOL_NAME = "long_click_element"
        }
    }

/**
 * MCP tool: scroll_to_element
 *
 * Scrolls to make the specified element visible by finding its nearest
 * scrollable ancestor and scrolling it. Retries up to [MAX_SCROLL_ATTEMPTS] times.
 */
class ScrollToElementTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) {
        @Suppress("ThrowsCount", "LongMethod")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId =
                arguments?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            // Parse multi-window trees and find the element
            var result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            var node =
                elementFinder.findNodeById(result.windows, elementId)
                    ?: throw McpToolException.ElementNotFound("Element '$elementId' not found")

            // If already visible, return immediately
            if (node.visible) {
                Log.d(TAG, "scroll_to_element: element '$elementId' already visible")
                return McpToolUtils.textResult("Element '$elementId' is already visible")
            }

            // Find the window tree containing the target node
            val containingTree =
                findContainingTree(result.windows, elementId)
                    ?: throw McpToolException.ActionFailed(
                        "Element '$elementId' not found in any window tree",
                    )

            // Find nearest scrollable ancestor within the same window tree
            val scrollableAncestorId =
                findScrollableAncestor(containingTree, elementId)
                    ?: throw McpToolException.ActionFailed(
                        "No scrollable container found for element '$elementId'",
                    )

            // Determine initial scroll direction from the element's position relative to
            // the screen. Elements above the viewport (bounds.bottom <= 0) need UP scrolling;
            // elements below or at unknown positions default to DOWN.
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val primaryDirection = determineScrollDirection(node, screenInfo)
            val oppositeDirection =
                if (primaryDirection == ScrollDirection.DOWN) ScrollDirection.UP else ScrollDirection.DOWN

            // Try primary direction first, then opposite if element not found
            var totalAttempts = 0
            for (direction in listOf(primaryDirection, oppositeDirection)) {
                while (totalAttempts < MAX_SCROLL_ATTEMPTS) {
                    totalAttempts++
                    val scrollResult =
                        actionExecutor.scrollNode(scrollableAncestorId, direction, result.windows)
                    if (scrollResult.isFailure) {
                        throw McpToolException.ActionFailed(
                            "Scroll failed on ancestor '$scrollableAncestorId': " +
                                "${scrollResult.exceptionOrNull()?.message}",
                        )
                    }

                    // Small delay to let UI settle after scroll
                    kotlinx.coroutines.delay(SCROLL_SETTLE_DELAY_MS)

                    // Re-parse and check visibility
                    result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
                    node = elementFinder.findNodeById(result.windows, elementId) ?: continue

                    if (node.visible) {
                        Log.d(
                            TAG,
                            "scroll_to_element: element '$elementId' became visible " +
                                "after $totalAttempts scroll(s) (direction=$direction)",
                        )
                        return McpToolUtils.textResult(
                            "Scrolled to element '$elementId' ($totalAttempts scroll(s))",
                        )
                    }
                }
            }

            throw McpToolException.ActionFailed(
                "Element '$elementId' not visible after $MAX_SCROLL_ATTEMPTS scroll attempts",
            )
        }

        /**
         * Determines the best initial scroll direction based on the element's position
         * relative to the screen viewport.
         *
         * - If the element's bottom edge is at or above the top of the screen → UP
         * - Otherwise (below viewport or unknown) → DOWN (most common case)
         */
        internal fun determineScrollDirection(
            node: AccessibilityNodeData,
            screenInfo: ScreenInfo,
        ): ScrollDirection =
            when {
                node.bounds.bottom <= 0 -> ScrollDirection.UP
                node.bounds.top >= screenInfo.height -> ScrollDirection.DOWN
                else -> ScrollDirection.DOWN
            }

        /**
         * Walks up the tree from [targetNodeId] to find the nearest scrollable ancestor.
         * Returns the ancestor's node ID, or null if none found.
         */
        @Suppress("ReturnCount")
        private fun findScrollableAncestor(
            tree: AccessibilityNodeData,
            targetNodeId: String,
        ): String? {
            val path = mutableListOf<AccessibilityNodeData>()
            if (!findPathToNode(tree, targetNodeId, path)) return null

            // Walk the path from parent to root (excluding the target itself)
            for (i in path.size - 2 downTo 0) {
                if (path[i].scrollable) {
                    return path[i].id
                }
            }
            return null
        }

        /**
         * Builds the path from [root] to the node with [targetId].
         * Returns true if found, with [path] containing all nodes from root to target.
         */
        @Suppress("ReturnCount")
        private fun findPathToNode(
            root: AccessibilityNodeData,
            targetId: String,
            path: MutableList<AccessibilityNodeData>,
        ): Boolean {
            path.add(root)
            if (root.id == targetId) return true
            for (child in root.children) {
                if (findPathToNode(child, targetId, path)) return true
            }
            path.removeAt(path.size - 1)
            return false
        }

        /**
         * Finds the window tree that contains the node with [targetNodeId].
         * Returns the tree's root [AccessibilityNodeData], or null if not found.
         */
        private fun findContainingTree(
            windows: List<WindowData>,
            targetNodeId: String,
        ): AccessibilityNodeData? {
            for (windowData in windows) {
                if (elementFinder.findNodeById(windowData.tree, targetNodeId) != null) {
                    return windowData.tree
                }
            }
            return null
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Scroll to make the specified element visible",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("element_id") {
                                    put("type", "string")
                                    put("description", "Node ID from ${toolNamePrefix}find_elements")
                                }
                            },
                        required = listOf("element_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ScrollToElementTool"
            private const val TOOL_NAME = "scroll_to_element"
            private const val MAX_SCROLL_ATTEMPTS = 5
            private const val SCROLL_SETTLE_DELAY_MS = 300L
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Shared Utilities for Element Action Tools
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps a "by" string parameter to the [FindBy] enum.
 * Returns null if the string does not match any known value.
 */
internal fun mapFindBy(by: String): FindBy? =
    when (by.lowercase()) {
        "text" -> FindBy.TEXT
        "content_desc" -> FindBy.CONTENT_DESC
        "resource_id" -> FindBy.RESOURCE_ID
        "class_name" -> FindBy.CLASS_NAME
        else -> null
    }

/**
 * Gets a fresh multi-window accessibility snapshot by enumerating all on-screen windows
 * and parsing each window's accessibility tree.
 *
 * Falls back to single-window mode via [AccessibilityServiceProvider.getRootNode] if
 * [AccessibilityServiceProvider.getAccessibilityWindows] returns an empty list.
 *
 * **Performance note**: This function re-parses ALL window trees on every invocation to ensure
 * correctness — stale tree data would cause actions to target the wrong nodes. During parsing,
 * real [AccessibilityNodeInfo] references are accumulated in a [MutableMap] and stored in
 * [nodeCache] via [AccessibilityNodeCache.populate] for O(1) node resolution in
 * [ActionExecutorImpl.performNodeAction]. The cache is replaced atomically on each call.
 *
 * @throws McpToolException.PermissionDenied if accessibility service is not connected.
 * @throws McpToolException.ActionFailed if no windows and no root node are available.
 */
@Suppress("LongMethod", "NestedBlockDepth", "ThrowsCount")
internal fun getFreshWindows(
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
): MultiWindowResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    // Accumulate real AccessibilityNodeInfo references from ALL windows for cache population.
    // This map is passed to each parseTree call and populated during tree traversal.
    // We populate the cache ONCE after all windows are parsed to avoid the multi-window
    // cache overwrite problem (Critical Finding 1).
    val accumulatedNodeMap = mutableMapOf<String, CachedNode>()

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
            val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()
            val windowDataList = mutableListOf<WindowData>()

            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue

                // Extract metadata BEFORE parsing
                val wId = window.id
                val windowPackage = rootNode.packageName?.toString()
                val windowTitle = window.title?.toString()
                val windowType = window.type
                val windowLayer = window.layer
                val windowFocused = window.isFocused

                // Root nodes are NOT recycled — they are stored in accumulatedNodeMap
                // and owned by the cache. On API 33+ recycle() is a no-op, but we
                // avoid calling it on cached nodes for semantic clarity (Critical Finding 2).
                val tree = treeParser.parseTree(rootNode, "root_w$wId", accumulatedNodeMap)

                // Best-effort activity name: only for focused APPLICATION window matching tracked package
                val activityName =
                    if (windowFocused &&
                        windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        windowPackage == currentPackageName
                    ) {
                        currentActivityName
                    } else {
                        null
                    }

                windowDataList.add(
                    WindowData(
                        windowId = wId,
                        windowType = AccessibilityTreeParser.mapWindowType(windowType),
                        packageName = windowPackage,
                        title = windowTitle,
                        activityName = activityName,
                        layer = windowLayer,
                        focused = windowFocused,
                        tree = tree,
                    ),
                )
            }

            if (windowDataList.isEmpty()) {
                throw McpToolException.ActionFailed(
                    "All windows returned null root nodes.",
                )
            }

            // Populate cache with ALL windows' nodes in a single atomic swap
            nodeCache.populate(accumulatedNodeMap)

            return MultiWindowResult(windows = windowDataList, degraded = false)
        } finally {
            // Recycle all AccessibilityWindowInfo objects for consistency
            // (no-op on API 34+, but follows the codebase's recycling convention)
            for (w in accessibilityWindows) {
                @Suppress("DEPRECATION")
                w.recycle()
            }
        }
    }

    // Fallback to single-window mode
    val rootNode =
        accessibilityServiceProvider.getRootNode()
            ?: throw McpToolException.ActionFailed(
                "No windows available and no active window root node. " +
                    "The screen may be transitioning.",
            )

    // Extract metadata from the root node before recycling.
    // Use rootNode.window (available API 21+) to detect the actual window type
    // instead of hardcoding APPLICATION — the active window could be SYSTEM, INPUT_METHOD, etc.
    val fallbackWindowId = rootNode.windowId
    val windowInfo = rootNode.window
    val fallbackWindowType =
        if (windowInfo != null) {
            val type = AccessibilityTreeParser.mapWindowType(windowInfo.type)
            @Suppress("DEPRECATION")
            windowInfo.recycle()
            type
        } else {
            "APPLICATION"
        }

    // Fallback path also accumulates into the same map and does NOT recycle root node
    val tree = treeParser.parseTree(rootNode, "root_w$fallbackWindowId", accumulatedNodeMap)

    // Populate cache with fallback window's nodes
    nodeCache.populate(accumulatedNodeMap)

    val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
    val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()

    return MultiWindowResult(
        windows =
            listOf(
                WindowData(
                    windowId = fallbackWindowId,
                    windowType = fallbackWindowType,
                    packageName = currentPackageName,
                    title = "unknown",
                    activityName = currentActivityName,
                    layer = 0,
                    focused = true,
                    tree = tree,
                ),
            ),
        degraded = true,
    )
}

/**
 * Registers all element action tools with the [Server].
 */
@Suppress("LongParameterList")
fun registerElementActionTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
    toolNamePrefix: String,
) {
    FindElementsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)
        .register(server, toolNamePrefix)
    ClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache)
        .register(server, toolNamePrefix)
    LongClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache)
        .register(server, toolNamePrefix)
    ScrollToElementTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, nodeCache)
        .register(server, toolNamePrefix)
}

/**
 * Maps exceptions from [ActionExecutor] node actions to [McpToolException]
 * with appropriate MCP error codes.
 *
 * @throws McpToolException always (this function never returns normally).
 */
@Suppress("ThrowsCount")
internal fun mapNodeActionException(
    exception: Throwable,
    elementId: String,
): Nothing {
    when (exception) {
        is NoSuchElementException -> throw McpToolException.ElementNotFound(
            "Element '$elementId' not found in accessibility tree",
        )
        is IllegalStateException -> {
            if (exception.message?.contains("not available") == true) {
                throw McpToolException.PermissionDenied(
                    exception.message ?: "Accessibility service not available",
                )
            }
            throw McpToolException.ActionFailed(
                exception.message ?: "Action failed on element '$elementId'",
            )
        }
        else -> throw McpToolException.ActionFailed(
            "Action failed on element '$elementId': ${exception.message}",
        )
    }
}
