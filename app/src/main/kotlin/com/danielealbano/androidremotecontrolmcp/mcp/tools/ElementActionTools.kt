@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            // Validate parameters
            val byStr =
                params?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val value =
                params["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (value.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            val exactMatch = params["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            // Get fresh accessibility tree
            val tree = getFreshTree(treeParser)

            // Search
            val elements = elementFinder.findElements(tree, findBy, value, exactMatch)

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

            return McpContentBuilder.textContent(Json.encodeToString(resultJson))
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description =
                    "Find UI elements matching the specified criteria " +
                        "(text, content_desc, resource_id, class_name)",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
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
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("by"))
                                add(JsonPrimitive("value"))
                            },
                        )
                    },
                handler = this,
            )
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val elementId =
                params?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val tree = getFreshTree(treeParser)

            val result = actionExecutor.clickNode(elementId, tree)
            result.onFailure { e -> mapNodeActionException(e, elementId) }

            Log.d(TAG, "click_element: elementId=$elementId succeeded")
            return McpContentBuilder.textContent("Click performed on element '$elementId'")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Click the specified accessibility node by element ID",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Node ID from find_elements")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
                    },
                handler = this,
            )
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val elementId =
                params?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val tree = getFreshTree(treeParser)

            val result = actionExecutor.longClickNode(elementId, tree)
            result.onFailure { e -> mapNodeActionException(e, elementId) }

            Log.d(TAG, "long_click_element: elementId=$elementId succeeded")
            return McpContentBuilder.textContent("Long-click performed on element '$elementId'")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Long-click the specified accessibility node by element ID",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Node ID from find_elements")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:LongClickElementTool"
            private const val TOOL_NAME = "long_click_element"
        }
    }

/**
 * MCP tool: set_text
 *
 * Sets text on an editable accessibility node. Text can be empty to clear the field.
 */
class SetTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val elementId =
                params?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            // text is required but can be empty string (to clear field)
            val textElement =
                params["text"]
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")
            val text = (textElement as? JsonPrimitive)?.contentOrNull ?: ""

            val tree = getFreshTree(treeParser)

            val result = actionExecutor.setTextOnNode(elementId, text, tree)
            result.onFailure { e -> mapNodeActionException(e, elementId) }

            Log.d(TAG, "set_text: elementId=$elementId, textLength=${text.length} succeeded")
            return McpContentBuilder.textContent("Text set on element '$elementId'")
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Set text on an editable accessibility node (empty string to clear)",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Node ID from find_elements")
                            }
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to set (empty string to clear)")
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("element_id"))
                                add(JsonPrimitive("text"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:SetTextTool"
            private const val TOOL_NAME = "set_text"
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
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val elementId =
                params?.get("element_id")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            // Parse tree and find the element
            var tree = getFreshTree(treeParser)
            var node =
                elementFinder.findNodeById(tree, elementId)
                    ?: throw McpToolException.ElementNotFound("Element '$elementId' not found")

            // If already visible, return immediately
            if (node.visible) {
                Log.d(TAG, "scroll_to_element: element '$elementId' already visible")
                return McpContentBuilder.textContent("Element '$elementId' is already visible")
            }

            // Find nearest scrollable ancestor
            val scrollableAncestorId =
                findScrollableAncestor(tree, elementId)
                    ?: throw McpToolException.ActionFailed(
                        "No scrollable container found for element '$elementId'",
                    )

            // Attempt to scroll into view
            for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
                val scrollResult = actionExecutor.scrollNode(scrollableAncestorId, ScrollDirection.DOWN, tree)
                if (scrollResult.isFailure) {
                    throw McpToolException.ActionFailed(
                        "Scroll failed on ancestor '$scrollableAncestorId': ${scrollResult.exceptionOrNull()?.message}",
                    )
                }

                // Small delay to let UI settle after scroll
                kotlinx.coroutines.delay(SCROLL_SETTLE_DELAY_MS)

                // Re-parse and check visibility
                tree = getFreshTree(treeParser)
                node = elementFinder.findNodeById(tree, elementId) ?: continue

                if (node.visible) {
                    Log.d(TAG, "scroll_to_element: element '$elementId' became visible after $attempt scroll(s)")
                    return McpContentBuilder.textContent("Scrolled to element '$elementId' ($attempt scroll(s))")
                }
            }

            throw McpToolException.ActionFailed(
                "Element '$elementId' not visible after $MAX_SCROLL_ATTEMPTS scroll attempts",
            )
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

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Scroll to make the specified element visible",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element_id") {
                                put("type", "string")
                                put("description", "Node ID from find_elements")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
                    },
                handler = this,
            )
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
internal fun mapFindBy(by: String): FindBy? {
    return when (by.lowercase()) {
        "text" -> FindBy.TEXT
        "content_desc" -> FindBy.CONTENT_DESC
        "resource_id" -> FindBy.RESOURCE_ID
        "class_name" -> FindBy.CLASS_NAME
        else -> null
    }
}

/**
 * Gets a fresh accessibility tree by obtaining the root node from the
 * accessibility service and parsing it.
 *
 * @throws McpToolException with -32001 if accessibility service is not available.
 * @throws McpToolException with -32001 if no root node is available.
 */
internal fun getFreshTree(treeParser: AccessibilityTreeParser): AccessibilityNodeData {
    val service =
        McpAccessibilityService.instance
            ?: throw McpToolException.PermissionDenied(
                "Accessibility service is not enabled. Please enable it in Android Settings.",
            )

    val rootNode =
        service.getRootNode()
            ?: throw McpToolException.PermissionDenied(
                "No active window available. Ensure an app is in the foreground.",
            )

    return try {
        treeParser.parseTree(rootNode)
    } finally {
        @Suppress("DEPRECATION")
        rootNode.recycle()
    }
}

/**
 * Registers all element action tools with the [ToolRegistry].
 */
fun registerElementActionTools(toolRegistry: ToolRegistry) {
    val treeParser = AccessibilityTreeParser()
    val elementFinder = ElementFinder()
    val actionExecutor = ActionExecutor()
    FindElementsTool(treeParser, elementFinder).register(toolRegistry)
    ClickElementTool(treeParser, actionExecutor).register(toolRegistry)
    LongClickElementTool(treeParser, actionExecutor).register(toolRegistry)
    SetTextTool(treeParser, actionExecutor).register(toolRegistry)
    ScrollToElementTool(treeParser, elementFinder, actionExecutor).register(toolRegistry)
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
