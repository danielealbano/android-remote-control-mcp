# Plan 9: Element Action, Text Input & Utility MCP Tools

**Branch**: `feat/mcp-element-text-utility-tools`
**PR Title**: `Plan 9: Element action, text input, and utility MCP tools`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan implements the remaining 12 MCP tools: 5 element action tools, 3 text input tools, and 4 utility tools. These tools build on the infrastructure established in Plans 1-8, including the `McpProtocolHandler` with `ToolHandler` interface, `ToolRegistry`, `ElementFinder`, `ActionExecutor`, and `AccessibilityTreeParser`.

### Dependencies on Previous Plans

- **Plan 1**: Project scaffolding, Gradle build system, all dependencies in `libs.versions.toml`
- **Plan 2**: `ServerConfig`, `SettingsRepository`, `Logger`, `PermissionUtils`
- **Plan 3**: `MainViewModel`, `MainActivity`, Compose UI
- **Plan 4**: `McpAccessibilityService` (singleton), `AccessibilityTreeParser` (`parseTree()`), `ElementFinder` (`findElements()`, `findNodeById()`), `ActionExecutor` (node actions: `clickNode()`, `longClickNode()`, `setTextOnNode()`, `scrollNode()`; global actions: `pressBack()`, `pressHome()`)
- **Plan 5**: `ScreenCaptureService`, `ScreenshotEncoder`
- **Plan 6**: `McpProtocolHandler` (with `ToolHandler` interface, `registerTool()`, error factories), `McpServer`, `McpServerService`, `BearerTokenAuth`
- **Plan 7**: `ScreenIntrospectionTools` (4 tools), `SystemActionTools` (6 tools), `ToolRegistry` pattern
- **Plan 8**: `TouchActionTools` (5 tools), `GestureTools` (2 tools)

### Package Base

`com.danielealbano.androidremotecontrolmcp`

### Path References

- **Source root**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Test root**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Tools package**: `mcp/tools/`
- **Tools test package**: `mcp/tools/`

### Available Infrastructure (from Plan 4)

Key method signatures available in `ActionExecutor`:

```kotlin
// Node actions
suspend fun clickNode(nodeId: String, tree: AccessibilityNodeData): Result<Unit>
suspend fun longClickNode(nodeId: String, tree: AccessibilityNodeData): Result<Unit>
suspend fun setTextOnNode(nodeId: String, text: String, tree: AccessibilityNodeData): Result<Unit>
suspend fun scrollNode(nodeId: String, direction: ScrollDirection, tree: AccessibilityNodeData): Result<Unit>

// Global actions
suspend fun pressBack(): Result<Unit>
suspend fun pressHome(): Result<Unit>
```

Key method signatures available in `ElementFinder`:

```kotlin
fun findElements(tree: AccessibilityNodeData, by: FindBy, value: String, exactMatch: Boolean = false): List<ElementInfo>
fun findNodeById(tree: AccessibilityNodeData, nodeId: String): AccessibilityNodeData?
```

Key method signature available in `AccessibilityTreeParser`:

```kotlin
fun parseTree(rootNode: AccessibilityNodeInfo): AccessibilityNodeData
```

### Error Code Reference (from `McpProtocolHandler`)

| Code | Constant | Meaning |
|------|----------|---------|
| -32602 | `ERROR_INVALID_PARAMS` | Invalid or missing tool parameters |
| -32603 | `ERROR_INTERNAL` | Internal server error |
| -32001 | `ERROR_PERMISSION_DENIED` | Accessibility/screenshot permission missing |
| -32002 | `ERROR_ELEMENT_NOT_FOUND` | Element not found by ID or criteria |
| -32003 | `ERROR_ACTION_FAILED` | Accessibility action execution failed |
| -32004 | `ERROR_TIMEOUT` | Operation timed out |

---

## User Story 1: Element Action, Text Input & Utility MCP Tools

**As a** developer or AI model connecting to the Android Remote Control MCP server
**I want** MCP tools to find elements, click/interact with them by ID, input text, press keys, access the clipboard, and wait for UI conditions
**So that** I can perform high-level, element-based interactions with the Android device instead of relying solely on coordinate-based touch actions.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] `FindElementsTool` correctly finds elements by text, content_desc, resource_id, class_name with exact and contains matching
- [ ] `FindElementsTool` returns empty array (not an error) when no matches found
- [ ] `FindElementsTool` validates `by` parameter against allowed values and returns -32602 for invalid values
- [ ] `FindElementsTool` validates `value` is non-empty and returns -32602 for empty value
- [ ] `ClickElementTool` clicks a node by element_id and returns confirmation
- [ ] `ClickElementTool` returns -32002 for stale/invalid element_id
- [ ] `ClickElementTool` returns -32003 if element is not clickable or action fails
- [ ] `LongClickElementTool` long-clicks a node by element_id
- [ ] `LongClickElementTool` returns -32002 for not found, -32003 for action failure
- [ ] `SetTextTool` sets text on an editable node by element_id
- [ ] `SetTextTool` allows empty text (to clear field)
- [ ] `SetTextTool` returns -32002 for not found, -32003 for non-editable
- [ ] `ScrollToElementTool` scrolls parent scrollable container to make element visible
- [ ] `ScrollToElementTool` returns -32002 for not found, -32003 for scroll failure
- [ ] `InputTextTool` types text into a specified element or currently focused field
- [ ] `InputTextTool` validates text is present and returns -32602 if missing
- [ ] `ClearTextTool` clears text from a specified element or currently focused field
- [ ] `PressKeyTool` dispatches key events for ENTER, BACK, DEL, HOME, TAB, SPACE
- [ ] `PressKeyTool` validates key name against allowed values and returns -32602 for invalid key
- [ ] `GetClipboardTool` reads clipboard text via ClipboardManager from accessibility service context
- [ ] `SetClipboardTool` sets clipboard text via ClipboardManager
- [ ] `SetClipboardTool` validates text parameter is present
- [ ] `WaitForElementTool` polls the accessibility tree every 500ms until element appears or timeout
- [ ] `WaitForElementTool` returns element details on success, -32004 on timeout
- [ ] `WaitForElementTool` validates timeout is > 0 and <= 30000
- [ ] `WaitForIdleTool` monitors accessibility events to detect UI idle state
- [ ] `WaitForIdleTool` returns confirmation on idle, -32004 on timeout
- [ ] `WaitForIdleTool` validates timeout is > 0 and <= 30000
- [ ] All 12 tools are registered in `ToolRegistry` with correct names, descriptions, and input schemas
- [ ] All tools parse the accessibility tree fresh on each invocation (nodes can be stale)
- [ ] All tools handle JSON type coercion for parameters (e.g., timeout as int or long)
- [ ] `docs/MCP_TOOLS.md` documents all 29 MCP tools (17 from Plans 7-8 + 12 from this plan)
- [ ] Unit tests exist and pass for all 12 tools
- [ ] `make lint` passes with no errors or warnings
- [ ] `make test-unit` passes
- [ ] `make build` succeeds with no errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add element action MCP tools (find_elements, click_element, long_click_element, set_text, scroll_to_element)` | `ElementActionTools.kt`, update `ToolRegistry.kt` |
| 2 | `feat: add text input MCP tools (input_text, clear_text, press_key)` | `TextInputTools.kt`, update `ToolRegistry.kt` |
| 3 | `feat: add utility MCP tools (get_clipboard, set_clipboard, wait_for_element, wait_for_idle)` | `UtilityTools.kt`, update `ToolRegistry.kt` |
| 4 | `test: add unit tests for element, text input, and utility tools` | `ElementActionToolsTest.kt`, `TextInputToolsTest.kt`, `UtilityToolsTest.kt` |
| 5 | `docs: complete MCP_TOOLS.md with all tool categories` | `docs/MCP_TOOLS.md` |

---

### Task 9.1: Create Element Action Tools

**Description**: Create `ElementActionTools.kt` containing five `ToolHandler` implementations for element-based interactions: `find_elements`, `click_element`, `long_click_element`, `set_text`, and `scroll_to_element`. Each tool gets a fresh accessibility tree on every invocation to avoid stale node references.

**Acceptance Criteria**:
- [ ] `FindElementsTool` implements `ToolHandler` and is Hilt-injectable
- [ ] `FindElementsTool` validates `by` against allowed values: text, content_desc, resource_id, class_name
- [ ] `FindElementsTool` validates `value` is non-empty
- [ ] `FindElementsTool` maps `by` string to `FindBy` enum correctly
- [ ] `FindElementsTool` returns JSON array of `ElementInfo` objects (may be empty)
- [ ] `ClickElementTool` validates `element_id` is non-empty
- [ ] `ClickElementTool` parses tree, delegates to `ActionExecutor.clickNode()`
- [ ] `ClickElementTool` maps `NoSuchElementException` to error -32002
- [ ] `ClickElementTool` maps `IllegalStateException` (not clickable) to error -32003
- [ ] `LongClickElementTool` follows same patterns as `ClickElementTool`
- [ ] `SetTextTool` validates `element_id` is non-empty, allows empty `text`
- [ ] `SetTextTool` delegates to `ActionExecutor.setTextOnNode()`
- [ ] `ScrollToElementTool` finds element, identifies parent scrollable container, scrolls, retries find
- [ ] All tools check `McpAccessibilityService.instance` and return -32001 if null
- [ ] File compiles without errors and passes lint

**Tests**: Unit tests in Task 9.4 (`ElementActionToolsTest.kt`).

#### Action 9.1.1: Create `ElementActionTools.kt`

**What**: Create the five element action tool handlers.

**Context**: Each tool follows the same pattern: (1) validate parameters, (2) check accessibility service availability, (3) get root node, (4) parse tree via `AccessibilityTreeParser`, (5) use `ElementFinder` or `ActionExecutor` as appropriate, (6) return JSON result or throw appropriate error. The tools receive `AccessibilityTreeParser`, `ElementFinder`, and `ActionExecutor` via constructor injection (Hilt). Error mapping: `NoSuchElementException` from `ActionExecutor` maps to -32002, `IllegalStateException` (not clickable/editable) maps to -32003, `RuntimeException` (action failed) maps to -32003.

For `scroll_to_element`, the approach is: find the element by ID in the parsed tree; if the element is not visible (`visible == false`), walk up the tree to find the nearest scrollable ancestor; scroll that ancestor using `ActionExecutor.scrollNode()`; re-parse the tree and check if the element is now visible. Retry up to `MAX_SCROLL_ATTEMPTS` (5) times.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt
@@ -0,0 +1,390 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
+import kotlinx.serialization.json.Json
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.boolean
+import kotlinx.serialization.json.booleanOrNull
+import kotlinx.serialization.json.buildJsonArray
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.contentOrNull
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import kotlinx.serialization.json.putJsonObject
+import javax.inject.Inject
+
+/**
+ * MCP tool: find_elements
+ *
+ * Finds UI elements matching the specified criteria in the accessibility tree.
+ * Returns an array of matching elements (may be empty — empty is NOT an error).
+ */
+class FindElementsTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val elementFinder: ElementFinder,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        // Validate parameters
+        val byStr = params?.get("by")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'by'")
+
+        val value = params["value"]?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'value'")
+
+        if (value.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'value' must be non-empty")
+        }
+
+        val exactMatch = params["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false
+
+        val findBy = mapFindBy(byStr)
+            ?: throw McpToolException.InvalidParams(
+                "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
+            )
+
+        // Get fresh accessibility tree
+        val tree = getFreshTree(treeParser)
+
+        // Search
+        val elements = elementFinder.findElements(tree, findBy, value, exactMatch)
+
+        Log.d(TAG, "find_elements: by=$byStr, value='$value', exactMatch=$exactMatch, found=${elements.size}")
+
+        val resultJson = buildJsonObject {
+            put("elements", buildJsonArray {
+                elements.forEach { element ->
+                    add(buildJsonObject {
+                        put("id", element.id)
+                        put("text", element.text)
+                        put("contentDescription", element.contentDescription)
+                        put("resourceId", element.resourceId)
+                        put("className", element.className)
+                        putJsonObject("bounds") {
+                            put("left", element.bounds.left)
+                            put("top", element.bounds.top)
+                            put("right", element.bounds.right)
+                            put("bottom", element.bounds.bottom)
+                        }
+                        put("clickable", element.clickable)
+                        put("longClickable", element.longClickable)
+                        put("scrollable", element.scrollable)
+                        put("editable", element.editable)
+                        put("enabled", element.enabled)
+                    })
+                }
+            })
+        }
+
+        return McpContentBuilder.textContent(Json.encodeToString(resultJson))
+    }
+
+    companion object {
+        private const val TAG = "MCP:FindElementsTool"
+    }
+}
+
+/**
+ * MCP tool: click_element
+ *
+ * Clicks the accessibility node identified by element_id.
+ */
+class ClickElementTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'element_id'")
+
+        if (elementId.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'element_id' must be non-empty")
+        }
+
+        val tree = getFreshTree(treeParser)
+
+        val result = actionExecutor.clickNode(elementId, tree)
+        result.onFailure { e -> mapNodeActionException(e, elementId) }
+
+        Log.d(TAG, "click_element: elementId=$elementId succeeded")
+        return McpContentBuilder.textContent("Click performed on element '$elementId'")
+    }
+
+    companion object {
+        private const val TAG = "MCP:ClickElementTool"
+    }
+}
+
+/**
+ * MCP tool: long_click_element
+ *
+ * Long-clicks the accessibility node identified by element_id.
+ */
+class LongClickElementTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'element_id'")
+
+        if (elementId.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'element_id' must be non-empty")
+        }
+
+        val tree = getFreshTree(treeParser)
+
+        val result = actionExecutor.longClickNode(elementId, tree)
+        result.onFailure { e -> mapNodeActionException(e, elementId) }
+
+        Log.d(TAG, "long_click_element: elementId=$elementId succeeded")
+        return McpContentBuilder.textContent("Long-click performed on element '$elementId'")
+    }
+
+    companion object {
+        private const val TAG = "MCP:LongClickElementTool"
+    }
+}
+
+/**
+ * MCP tool: set_text
+ *
+ * Sets text on an editable accessibility node. Text can be empty to clear the field.
+ */
+class SetTextTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'element_id'")
+
+        if (elementId.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'element_id' must be non-empty")
+        }
+
+        // text is required but can be empty string (to clear field)
+        val textElement = params["text"]
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'text'")
+        val text = (textElement as? JsonPrimitive)?.contentOrNull ?: ""
+
+        val tree = getFreshTree(treeParser)
+
+        val result = actionExecutor.setTextOnNode(elementId, text, tree)
+        result.onFailure { e -> mapNodeActionException(e, elementId) }
+
+        Log.d(TAG, "set_text: elementId=$elementId, textLength=${text.length} succeeded")
+        return McpContentBuilder.textContent("Text set on element '$elementId'")
+    }
+
+    companion object {
+        private const val TAG = "MCP:SetTextTool"
+    }
+}
+
+/**
+ * MCP tool: scroll_to_element
+ *
+ * Scrolls to make the specified element visible by finding its nearest
+ * scrollable ancestor and scrolling it. Retries up to [MAX_SCROLL_ATTEMPTS] times.
+ */
+class ScrollToElementTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val elementFinder: ElementFinder,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'element_id'")
+
+        if (elementId.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'element_id' must be non-empty")
+        }
+
+        // Parse tree and find the element
+        var tree = getFreshTree(treeParser)
+        var node = elementFinder.findNodeById(tree, elementId)
+            ?: throw McpToolException.ElementNotFound( "Element '$elementId' not found")
+
+        // If already visible, return immediately
+        if (node.visible) {
+            Log.d(TAG, "scroll_to_element: element '$elementId' already visible")
+            return McpContentBuilder.textContent("Element '$elementId' is already visible")
+        }
+
+        // Find nearest scrollable ancestor
+        val scrollableAncestorId = findScrollableAncestor(tree, elementId)
+            ?: throw McpToolException.ActionFailed(
+                "No scrollable container found for element '$elementId'",
+            )
+
+        // Attempt to scroll into view
+        for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
+            val scrollResult = actionExecutor.scrollNode(scrollableAncestorId, ScrollDirection.DOWN, tree)
+            if (scrollResult.isFailure) {
+                throw McpToolException.ActionFailed(
+                    "Scroll failed on ancestor '$scrollableAncestorId': ${scrollResult.exceptionOrNull()?.message}",
+                )
+            }
+
+            // Small delay to let UI settle after scroll
+            kotlinx.coroutines.delay(SCROLL_SETTLE_DELAY_MS)
+
+            // Re-parse and check visibility
+            tree = getFreshTree(treeParser)
+            node = elementFinder.findNodeById(tree, elementId) ?: continue
+
+            if (node.visible) {
+                Log.d(TAG, "scroll_to_element: element '$elementId' became visible after $attempt scroll(s)")
+                return McpContentBuilder.textContent("Scrolled to element '$elementId' (${attempt} scroll(s))")
+            }
+        }
+
+        throw McpToolException.ActionFailed(
+            "Element '$elementId' not visible after $MAX_SCROLL_ATTEMPTS scroll attempts",
+        )
+    }
+
+    /**
+     * Walks up the tree from [targetNodeId] to find the nearest scrollable ancestor.
+     * Returns the ancestor's node ID, or null if none found.
+     */
+    private fun findScrollableAncestor(
+        tree: AccessibilityNodeData,
+        targetNodeId: String,
+    ): String? {
+        val path = mutableListOf<AccessibilityNodeData>()
+        if (!findPathToNode(tree, targetNodeId, path)) return null
+
+        // Walk the path from parent to root (excluding the target itself)
+        for (i in path.size - 2 downTo 0) {
+            if (path[i].scrollable) {
+                return path[i].id
+            }
+        }
+        return null
+    }
+
+    /**
+     * Builds the path from [root] to the node with [targetId].
+     * Returns true if found, with [path] containing all nodes from root to target.
+     */
+    private fun findPathToNode(
+        root: AccessibilityNodeData,
+        targetId: String,
+        path: MutableList<AccessibilityNodeData>,
+    ): Boolean {
+        path.add(root)
+        if (root.id == targetId) return true
+        for (child in root.children) {
+            if (findPathToNode(child, targetId, path)) return true
+        }
+        path.removeAt(path.size - 1)
+        return false
+    }
+
+    companion object {
+        private const val TAG = "MCP:ScrollToElementTool"
+        private const val MAX_SCROLL_ATTEMPTS = 5
+        private const val SCROLL_SETTLE_DELAY_MS = 300L
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// Shared Utilities for Element Action Tools
+// ─────────────────────────────────────────────────────────────────────────────
+
+// NOTE: Uses McpToolException sealed class defined in Plan 7 (mcp/McpToolException.kt).
+// Import: com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+// Subtypes used: McpToolException.InvalidParams, McpToolException.PermissionDenied,
+//                McpToolException.ActionFailed, McpToolException.ElementNotFound,
+//                McpToolException.Timeout
+
+/**
+ * Maps a "by" string parameter to the [FindBy] enum.
+ * Returns null if the string does not match any known value.
+ */
+internal fun mapFindBy(by: String): FindBy? {
+    return when (by.lowercase()) {
+        "text" -> FindBy.TEXT
+        "content_desc" -> FindBy.CONTENT_DESC
+        "resource_id" -> FindBy.RESOURCE_ID
+        "class_name" -> FindBy.CLASS_NAME
+        else -> null
+    }
+}
+
+/**
+ * Gets a fresh accessibility tree by obtaining the root node from the
+ * accessibility service and parsing it.
+ *
+ * @throws McpToolException with -32001 if accessibility service is not available.
+ * @throws McpToolException with -32001 if no root node is available.
+ */
+internal fun getFreshTree(treeParser: AccessibilityTreeParser): AccessibilityNodeData {
+    val service = McpAccessibilityService.instance
+        ?: throw McpToolException.PermissionDenied(
+            "Accessibility service is not enabled. Please enable it in Android Settings.",
+        )
+
+    val rootNode = service.getRootNode()
+        ?: throw McpToolException.PermissionDenied(
+            "No active window available. Ensure an app is in the foreground.",
+        )
+
+    return try {
+        treeParser.parseTree(rootNode)
+    } finally {
+        @Suppress("DEPRECATION")
+        rootNode.recycle()
+    }
+}
+
+/**
+ * Maps exceptions from [ActionExecutor] node actions to [McpToolException]
+ * with appropriate MCP error codes.
+ *
+ * @throws McpToolException always (this function never returns normally).
+ */
+internal fun mapNodeActionException(exception: Throwable, elementId: String): Nothing {
+    when (exception) {
+        is NoSuchElementException -> throw McpToolException.ElementNotFound(
+            "Element '$elementId' not found in accessibility tree",
+        )
+        is IllegalStateException -> {
+            if (exception.message?.contains("not available") == true) {
+                throw McpToolException.PermissionDenied(
+                    exception.message ?: "Accessibility service not available",
+                )
+            }
+            throw McpToolException.ActionFailed(
+                exception.message ?: "Action failed on element '$elementId'",
+            )
+        }
+        else -> throw McpToolException.ActionFailed(
+            "Action failed on element '$elementId': ${exception.message}",
+        )
+    }
+}
```

> **Implementation Notes**:
> - `McpToolException` is a custom exception carrying an MCP error code. The `McpProtocolHandler.handleToolCall()` method (Plan 6) currently catches generic `Exception` and wraps it in `internalError()`. At implementation time, update `handleToolCall()` to check for `McpToolException` and use the appropriate error factory (e.g., `elementNotFound()`, `actionFailed()`, `invalidParams()`). This is a small modification to the existing catch block.
> - `getFreshTree()` obtains and parses the accessibility tree on every call. The root node is recycled in a `finally` block after parsing. This ensures tools always work with current UI state.
> - `mapFindBy()` converts the MCP parameter string (lowercase) to the `FindBy` enum. The `lowercase()` call ensures case-insensitive matching.
> - `mapNodeActionException()` never returns normally — it always throws `McpToolException`. The `Nothing` return type makes this explicit.
> - `ScrollToElementTool.findScrollableAncestor()` builds a path from root to the target element, then walks backwards looking for the first scrollable node. This is a DFS-based path construction.
> - The `scroll_to_element` implementation scrolls DOWN by default. A more sophisticated implementation could determine scroll direction based on element position relative to the visible viewport. The current approach handles the most common case (scrolling down to reveal off-screen content). If the user needs upward scrolling, they can use `scroll_to_element` with a preceding `scroll` tool call.

#### Action 9.1.2: Verify `McpProtocolHandler.handleToolCall()` handles `McpToolException`

**What**: Verify that `handleToolCall()` already catches `McpToolException` (from Plan 7 Action 7.2.4).

**Context**: Plan 7 already updated `handleToolCall()` to catch `McpToolException` (sealed base class) and use `e.code` to produce the correct JSON-RPC error response. Since the sealed class hierarchy covers `InvalidParams`, `InternalError`, `PermissionDenied`, `ElementNotFound`, `ActionFailed`, and `Timeout`, a single catch block handles all subtypes. This action is a **no-op** -- the implementer just needs to verify the catch block exists.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt`

No diff required -- already handled by Plan 7 Action 7.2.4.

---

### Task 9.2: Create Text Input Tools

**Description**: Create `TextInputTools.kt` containing three `ToolHandler` implementations: `input_text`, `clear_text`, and `press_key`. These tools handle keyboard input and text manipulation.

**Acceptance Criteria**:
- [ ] `InputTextTool` implements `ToolHandler` and is Hilt-injectable
- [ ] `InputTextTool` validates `text` parameter is present
- [ ] `InputTextTool` with `element_id` targets that specific element (click to focus, then set text)
- [ ] `InputTextTool` without `element_id` finds the currently focused editable node
- [ ] `ClearTextTool` with `element_id` clears that element's text
- [ ] `ClearTextTool` without `element_id` finds the currently focused editable node and clears it
- [ ] `PressKeyTool` validates `key` against allowed values: ENTER, BACK, DEL, HOME, TAB, SPACE
- [ ] `PressKeyTool` maps BACK and HOME to global actions via `ActionExecutor`
- [ ] `PressKeyTool` maps ENTER to `AccessibilityNodeInfo.ACTION_IME_ENTER` (API 30+) or fallback
- [ ] `PressKeyTool` maps DEL to text manipulation (get current text, remove last character, set text)
- [ ] `PressKeyTool` maps TAB and SPACE to `ACTION_SET_TEXT` with appended character
- [ ] All tools check accessibility service availability
- [ ] File compiles without errors and passes lint

**Tests**: Unit tests in Task 9.4 (`TextInputToolsTest.kt`).

#### Action 9.2.1: Create `TextInputTools.kt`

**What**: Create the three text input tool handlers.

**Context**: `input_text` and `clear_text` both work with editable text fields. When `element_id` is provided, the tools target that specific element. When not provided, they find the currently focused node by traversing the accessibility tree looking for a node with `focused == true` and `editable == true`. The `press_key` tool is more complex because Android's accessibility API does not have a direct "press key" mechanism for all keys. BACK and HOME delegate to the existing global actions. ENTER uses `ACTION_IME_ENTER` (available on API 30+, our minSdk is 26 so we need a fallback). DEL manipulates the text of the focused field. TAB and SPACE append to the focused field's text.

For finding the focused node, we add a helper `findFocusedEditableNode()` that traverses the parsed `AccessibilityNodeData` tree looking for a node where `focusable == true` (or is `editable`) and is the input-focused node. Since `AccessibilityNodeData` does not track `isFocused`, we need to check the real node. We will use `McpAccessibilityService.instance?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` which returns the currently input-focused `AccessibilityNodeInfo`. We then match it to our parsed tree by comparing bounds and resource IDs.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt
@@ -0,0 +1,295 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.os.Build
+import android.os.Bundle
+import android.util.Log
+import android.view.accessibility.AccessibilityNodeInfo
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.contentOrNull
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import javax.inject.Inject
+
+/**
+ * MCP tool: input_text
+ *
+ * Types text into a specified element or the currently focused input field.
+ * If element_id is provided, clicks it to focus and then sets the text.
+ * If element_id is not provided, finds the currently focused editable node.
+ */
+class InputTextTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val text = params?.get("text")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'text'")
+
+        val elementId = params["element_id"]?.jsonPrimitive?.contentOrNull
+
+        if (elementId != null && elementId.isNotEmpty()) {
+            // Target specific element: click to focus, then set text
+            val tree = getFreshTree(treeParser)
+            val clickResult = actionExecutor.clickNode(elementId, tree)
+            clickResult.onFailure { e -> mapNodeActionException(e, elementId) }
+
+            // Re-parse after click (focus may have changed the tree)
+            val freshTree = getFreshTree(treeParser)
+            val setResult = actionExecutor.setTextOnNode(elementId, text, freshTree)
+            setResult.onFailure { e -> mapNodeActionException(e, elementId) }
+
+            Log.d(TAG, "input_text: set text on element '$elementId', length=${text.length}")
+        } else {
+            // Find currently focused editable node
+            val focusedNode = findFocusedEditableNode()
+                ?: throw McpToolException.ElementNotFound(
+                    "No focused editable element found. Focus an input field first or provide element_id.",
+                )
+
+            try {
+                val arguments = Bundle().apply {
+                    putCharSequence(
+                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                        text,
+                    )
+                }
+                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
+                if (!success) {
+                    throw McpToolException.ActionFailed(
+                        "Failed to set text on focused element",
+                    )
+                }
+                Log.d(TAG, "input_text: set text on focused element, length=${text.length}")
+            } finally {
+                @Suppress("DEPRECATION")
+                focusedNode.recycle()
+            }
+        }
+
+        return McpContentBuilder.textContent("Text input completed (${text.length} characters)")
+    }
+
+    companion object {
+        private const val TAG = "MCP:InputTextTool"
+    }
+}
+
+/**
+ * MCP tool: clear_text
+ *
+ * Clears text from a specified element or the currently focused input field.
+ */
+class ClearTextTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val elementId = params?.get("element_id")?.jsonPrimitive?.contentOrNull
+
+        if (elementId != null && elementId.isNotEmpty()) {
+            // Clear specific element's text
+            val tree = getFreshTree(treeParser)
+            val result = actionExecutor.setTextOnNode(elementId, "", tree)
+            result.onFailure { e -> mapNodeActionException(e, elementId) }
+
+            Log.d(TAG, "clear_text: cleared text on element '$elementId'")
+        } else {
+            // Find currently focused editable node and clear it
+            val focusedNode = findFocusedEditableNode()
+                ?: throw McpToolException.ElementNotFound(
+                    "No focused editable element found. Focus an input field first or provide element_id.",
+                )
+
+            try {
+                val arguments = Bundle().apply {
+                    putCharSequence(
+                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                        "",
+                    )
+                }
+                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
+                if (!success) {
+                    throw McpToolException.ActionFailed(
+                        "Failed to clear text on focused element",
+                    )
+                }
+                Log.d(TAG, "clear_text: cleared text on focused element")
+            } finally {
+                @Suppress("DEPRECATION")
+                focusedNode.recycle()
+            }
+        }
+
+        return McpContentBuilder.textContent("Text cleared successfully")
+    }
+
+    companion object {
+        private const val TAG = "MCP:ClearTextTool"
+    }
+}
+
+/**
+ * MCP tool: press_key
+ *
+ * Presses a specific key. Supported keys: ENTER, BACK, DEL, HOME, TAB, SPACE.
+ *
+ * Key mapping strategy:
+ * - BACK, HOME: Delegate to ActionExecutor global actions (already implemented).
+ * - ENTER: Use ACTION_IME_ENTER on API 30+, fallback to ACTION_CLICK on the focused node.
+ * - DEL: Get current text from focused node, remove last character, set text.
+ * - TAB, SPACE: Get current text from focused node, append character, set text.
+ */
+class PressKeyTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val key = params?.get("key")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'key'")
+
+        val upperKey = key.uppercase()
+        if (upperKey !in ALLOWED_KEYS) {
+            throw McpToolException.InvalidParams(
+                "Invalid key: '$key'. Allowed values: ${ALLOWED_KEYS.joinToString(", ")}",
+            )
+        }
+
+        when (upperKey) {
+            "BACK" -> {
+                val result = actionExecutor.pressBack()
+                result.onFailure { e ->
+                    throw McpToolException.ActionFailed( "BACK key failed: ${e.message}")
+                }
+            }
+            "HOME" -> {
+                val result = actionExecutor.pressHome()
+                result.onFailure { e ->
+                    throw McpToolException.ActionFailed( "HOME key failed: ${e.message}")
+                }
+            }
+            "ENTER" -> pressEnter()
+            "DEL" -> pressDelete()
+            "TAB" -> appendCharToFocused('\t')
+            "SPACE" -> appendCharToFocused(' ')
+        }
+
+        Log.d(TAG, "press_key: key=$upperKey succeeded")
+        return McpContentBuilder.textContent("Key '$upperKey' pressed successfully")
+    }
+
+    private fun pressEnter() {
+        val focusedNode = findFocusedEditableNode()
+            ?: throw McpToolException.ElementNotFound(
+                "No focused element found for ENTER key",
+            )
+
+        try {
+            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
+                focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
+            } else {
+                // Fallback for API < 30: append newline character
+                val currentText = focusedNode.text?.toString() ?: ""
+                val arguments = Bundle().apply {
+                    putCharSequence(
+                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                        currentText + "\n",
+                    )
+                }
+                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
+            }
+            if (!success) {
+                throw McpToolException.ActionFailed( "ENTER key action failed")
+            }
+        } finally {
+            @Suppress("DEPRECATION")
+            focusedNode.recycle()
+        }
+    }
+
+    private fun pressDelete() {
+        val focusedNode = findFocusedEditableNode()
+            ?: throw McpToolException.ElementNotFound(
+                "No focused editable element found for DEL key",
+            )
+
+        try {
+            val currentText = focusedNode.text?.toString() ?: ""
+            if (currentText.isNotEmpty()) {
+                val newText = currentText.dropLast(1)
+                val arguments = Bundle().apply {
+                    putCharSequence(
+                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                        newText,
+                    )
+                }
+                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
+                if (!success) {
+                    throw McpToolException.ActionFailed( "DEL key action failed")
+                }
+            }
+            // If text is already empty, DEL is a no-op (not an error)
+        } finally {
+            @Suppress("DEPRECATION")
+            focusedNode.recycle()
+        }
+    }
+
+    private fun appendCharToFocused(char: Char) {
+        val focusedNode = findFocusedEditableNode()
+            ?: throw McpToolException.ElementNotFound(
+                "No focused editable element found for key input",
+            )
+
+        try {
+            val currentText = focusedNode.text?.toString() ?: ""
+            val arguments = Bundle().apply {
+                putCharSequence(
+                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                    currentText + char,
+                )
+            }
+            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
+            if (!success) {
+                throw McpToolException.ActionFailed( "Key input action failed")
+            }
+        } finally {
+            @Suppress("DEPRECATION")
+            focusedNode.recycle()
+        }
+    }
+
+    companion object {
+        private const val TAG = "MCP:PressKeyTool"
+        private val ALLOWED_KEYS = setOf("ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE")
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// Shared Utility for Text Input Tools
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * Finds the currently input-focused editable node using the accessibility service.
+ *
+ * Uses [AccessibilityService.findFocus] with [AccessibilityNodeInfo.FOCUS_INPUT]
+ * to locate the node that currently has keyboard focus.
+ *
+ * @return The focused [AccessibilityNodeInfo], or null if no editable node is focused.
+ *         The caller is responsible for recycling the returned node.
+ */
+internal fun findFocusedEditableNode(): AccessibilityNodeInfo? {
+    val service = McpAccessibilityService.instance
+        ?: throw McpToolException.PermissionDenied(
+            "Accessibility service is not enabled",
+        )
+
+    val rootNode = service.getRootNode() ?: return null
+    val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
+
+    @Suppress("DEPRECATION")
+    rootNode.recycle()
+
+    if (focusedNode != null && !focusedNode.isEditable) {
+        @Suppress("DEPRECATION")
+        focusedNode.recycle()
+        return null
+    }
+
+    return focusedNode
+}
```

> **Implementation Notes**:
> - `findFocusedEditableNode()` uses `AccessibilityNodeInfo.findFocus(FOCUS_INPUT)` which is a framework method that returns the currently input-focused node. This avoids needing to add a `focused` field to `AccessibilityNodeData`.
> - `PressKeyTool` uses `ACTION_IME_ENTER` on API 30+ (Android 11+). On older API levels, it falls back to appending a newline character. This is a reasonable approximation since most text fields interpret newlines correctly.
> - `pressDelete()` is a no-op when text is empty (not an error). This prevents errors when the user presses DEL on an already-empty field.
> - `appendCharToFocused()` appends to existing text rather than replacing it. This allows TAB and SPACE to work incrementally.
> - The focused node returned by `findFocusedEditableNode()` must be recycled by the caller. The root node is recycled immediately after `findFocus()`.

---

### Task 9.3: Create Utility Tools

**Description**: Create `UtilityTools.kt` containing four `ToolHandler` implementations: `get_clipboard`, `set_clipboard`, `wait_for_element`, and `wait_for_idle`. These are helper tools for clipboard access and UI synchronization.

**Acceptance Criteria**:
- [ ] `GetClipboardTool` implements `ToolHandler` and is Hilt-injectable
- [ ] `GetClipboardTool` reads clipboard text via `ClipboardManager` from the accessibility service context
- [ ] `GetClipboardTool` returns `{ "text": "..." }` or `{ "text": null }` if clipboard is empty
- [ ] `GetClipboardTool` returns -32603 if clipboard access fails
- [ ] `SetClipboardTool` validates `text` parameter is present
- [ ] `SetClipboardTool` sets clipboard via `ClipboardManager.setPrimaryClip()`
- [ ] `WaitForElementTool` validates `by` and `value` parameters
- [ ] `WaitForElementTool` validates timeout > 0 and <= 30000, defaults to 5000
- [ ] `WaitForElementTool` polls every 500ms (parse tree, search for element)
- [ ] `WaitForElementTool` returns element details when found
- [ ] `WaitForElementTool` returns -32004 when timeout is reached
- [ ] `WaitForIdleTool` validates timeout > 0 and <= 30000, defaults to 3000
- [ ] `WaitForIdleTool` detects idle by monitoring whether the accessibility tree changes between polls
- [ ] `WaitForIdleTool` returns confirmation when idle, -32004 on timeout
- [ ] All tools check accessibility service availability where needed
- [ ] File compiles without errors and passes lint

**Tests**: Unit tests in Task 9.4 (`UtilityToolsTest.kt`).

#### Action 9.3.1: Create `UtilityTools.kt`

**What**: Create the four utility tool handlers.

**Context**: Clipboard access from an accessibility service is allowed on Android 10+ (accessibility services are exempt from the clipboard restriction that applies to background apps). We use `context.getSystemService(ClipboardManager::class.java)` from the accessibility service's context.

`wait_for_element` uses a simple polling loop with `kotlinx.coroutines.delay(500)` between attempts. Each attempt parses a fresh tree and searches for the element. This is straightforward and reliable.

`wait_for_idle` monitors for UI stability by comparing successive accessibility tree snapshots. If the tree structure does not change between two consecutive polls (separated by `IDLE_CHECK_INTERVAL_MS`), we consider the UI idle. We compare by hashing the tree (using the root node's ID and child count as a quick proxy, or a deeper structural hash).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt
@@ -0,0 +1,310 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.content.ClipData
+import android.content.ClipboardManager
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import kotlinx.coroutines.delay
+import kotlinx.serialization.json.Json
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.contentOrNull
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.long
+import kotlinx.serialization.json.longOrNull
+import kotlinx.serialization.json.put
+import kotlinx.serialization.json.putJsonObject
+import javax.inject.Inject
+
+/**
+ * MCP tool: get_clipboard
+ *
+ * Gets the current clipboard text content. Accessibility services are exempt
+ * from Android 10+ clipboard access restrictions.
+ */
+class GetClipboardTool @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val service = McpAccessibilityService.instance
+            ?: throw McpToolException.PermissionDenied(
+                "Accessibility service is not enabled",
+            )
+
+        return try {
+            val clipboardManager = service.getSystemService(ClipboardManager::class.java)
+                ?: throw McpToolException.ActionFailed(
+                    "ClipboardManager not available",
+                )
+
+            val clip = clipboardManager.primaryClip
+            val text = if (clip != null && clip.itemCount > 0) {
+                clip.getItemAt(0).text?.toString()
+            } else {
+                null
+            }
+
+            Log.d(TAG, "get_clipboard: text=${if (text != null) "${text.length} chars" else "null"}")
+
+            val resultJson = buildJsonObject {
+                put("text", text)
+            }
+            McpContentBuilder.textContent(Json.encodeToString(resultJson))
+        } catch (e: McpToolException) {
+            throw e
+        } catch (e: Exception) {
+            Log.e(TAG, "Clipboard access failed", e)
+            throw McpToolException.ActionFailed(
+                "Clipboard access failed: ${e.message}",
+            )
+        }
+    }
+
+    companion object {
+        private const val TAG = "MCP:GetClipboardTool"
+    }
+}
+
+/**
+ * MCP tool: set_clipboard
+ *
+ * Sets the clipboard content to the specified text.
+ */
+class SetClipboardTool @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val text = params?.get("text")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'text'")
+
+        val service = McpAccessibilityService.instance
+            ?: throw McpToolException.PermissionDenied(
+                "Accessibility service is not enabled",
+            )
+
+        return try {
+            val clipboardManager = service.getSystemService(ClipboardManager::class.java)
+                ?: throw McpToolException.ActionFailed(
+                    "ClipboardManager not available",
+                )
+
+            val clip = ClipData.newPlainText("MCP", text)
+            clipboardManager.setPrimaryClip(clip)
+
+            Log.d(TAG, "set_clipboard: set ${text.length} chars")
+
+            McpContentBuilder.textContent("Clipboard set successfully (${text.length} characters)")
+        } catch (e: McpToolException) {
+            throw e
+        } catch (e: Exception) {
+            Log.e(TAG, "Clipboard set failed", e)
+            throw McpToolException.ActionFailed(
+                "Clipboard set failed: ${e.message}",
+            )
+        }
+    }
+
+    companion object {
+        private const val TAG = "MCP:SetClipboardTool"
+    }
+}
+
+/**
+ * MCP tool: wait_for_element
+ *
+ * Polls the accessibility tree every [POLL_INTERVAL_MS] until an element matching
+ * the criteria appears, or the timeout is reached.
+ */
+class WaitForElementTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+    private val elementFinder: ElementFinder,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        // Validate parameters
+        val byStr = params?.get("by")?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'by'")
+
+        val value = params["value"]?.jsonPrimitive?.contentOrNull
+            ?: throw McpToolException.InvalidParams( "Missing required parameter 'value'")
+
+        if (value.isEmpty()) {
+            throw McpToolException.InvalidParams( "Parameter 'value' must be non-empty")
+        }
+
+        val findBy = mapFindBy(byStr)
+            ?: throw McpToolException.InvalidParams(
+                "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
+            )
+
+        val timeout = params["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
+        if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
+            throw McpToolException.InvalidParams(
+                "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
+            )
+        }
+
+        // Poll loop
+        val startTime = System.currentTimeMillis()
+        var attemptCount = 0
+
+        while (System.currentTimeMillis() - startTime < timeout) {
+            attemptCount++
+
+            try {
+                val tree = getFreshTree(treeParser)
+                val elements = elementFinder.findElements(tree, findBy, value, false)
+
+                if (elements.isNotEmpty()) {
+                    val element = elements.first()
+                    val elapsed = System.currentTimeMillis() - startTime
+                    Log.d(TAG, "wait_for_element: found after ${elapsed}ms ($attemptCount attempts)")
+
+                    val resultJson = buildJsonObject {
+                        put("found", true)
+                        put("elapsedMs", elapsed)
+                        put("attempts", attemptCount)
+                        putJsonObject("element") {
+                            put("id", element.id)
+                            put("text", element.text)
+                            put("contentDescription", element.contentDescription)
+                            put("resourceId", element.resourceId)
+                            put("className", element.className)
+                            putJsonObject("bounds") {
+                                put("left", element.bounds.left)
+                                put("top", element.bounds.top)
+                                put("right", element.bounds.right)
+                                put("bottom", element.bounds.bottom)
+                            }
+                            put("clickable", element.clickable)
+                            put("enabled", element.enabled)
+                        }
+                    }
+                    return McpContentBuilder.textContent(Json.encodeToString(resultJson))
+                }
+            } catch (e: McpToolException) {
+                // If accessibility service becomes unavailable during polling, propagate
+                if (e.code == McpProtocolHandler.ERROR_PERMISSION_DENIED) throw e
+                // Other errors (e.g., stale tree) — retry on next poll
+                Log.d(TAG, "wait_for_element: poll attempt $attemptCount failed: ${e.message}")
+            }
+
+            delay(POLL_INTERVAL_MS)
+        }
+
+        throw McpToolException.Timeout(
+            "Element not found within ${timeout}ms (by=$byStr, value='$value', attempts=$attemptCount)",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:WaitForElementTool"
+        private const val POLL_INTERVAL_MS = 500L
+        private const val DEFAULT_TIMEOUT_MS = 5000L
+        private const val MAX_TIMEOUT_MS = 30000L
+    }
+}
+
+/**
+ * MCP tool: wait_for_idle
+ *
+ * Waits for the UI to become idle by detecting when the accessibility tree
+ * structure stops changing. Considers UI idle when two consecutive snapshots
+ * (separated by [IDLE_CHECK_INTERVAL_MS]) produce the same structural hash.
+ */
+class WaitForIdleTool @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val timeout = params?.get("timeout")?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
+        if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
+            throw McpToolException.InvalidParams(
+                "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
+            )
+        }
+
+        val startTime = System.currentTimeMillis()
+        var previousHash: Int? = null
+        var consecutiveIdleChecks = 0
+
+        while (System.currentTimeMillis() - startTime < timeout) {
+            try {
+                val tree = getFreshTree(treeParser)
+                val currentHash = computeTreeHash(tree)
+
+                if (previousHash != null && currentHash == previousHash) {
+                    consecutiveIdleChecks++
+                    if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
+                        val elapsed = System.currentTimeMillis() - startTime
+                        Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms")
+                        val resultJson = buildJsonObject {
+                            put("message", "UI is idle")
+                            put("elapsedMs", elapsed)
+                        }
+                        return McpContentBuilder.textContent(Json.encodeToString(resultJson))
+                    }
+                } else {
+                    consecutiveIdleChecks = 0
+                }
+
+                previousHash = currentHash
+            } catch (e: McpToolException) {
+                if (e.code == McpProtocolHandler.ERROR_PERMISSION_DENIED) throw e
+                // Tree parse failures during transitions — reset idle counter
+                consecutiveIdleChecks = 0
+                previousHash = null
+            }
+
+            delay(IDLE_CHECK_INTERVAL_MS)
+        }
+
+        throw McpToolException.Timeout(
+            "UI did not become idle within ${timeout}ms",
+        )
+    }
+
+    /**
+     * Computes a structural hash of the accessibility tree for change detection.
+     *
+     * Uses a recursive hash incorporating each node's class name, text, bounds,
+     * and child count. This is fast and sufficient for detecting structural changes.
+     */
+    private fun computeTreeHash(node: AccessibilityNodeData): Int {
+        var hash = 17
+        hash = 31 * hash + (node.className?.hashCode() ?: 0)
+        hash = 31 * hash + (node.text?.hashCode() ?: 0)
+        hash = 31 * hash + node.bounds.hashCode()
+        hash = 31 * hash + node.children.size
+        for (child in node.children) {
+            hash = 31 * hash + computeTreeHash(child)
+        }
+        return hash
+    }
+
+    companion object {
+        private const val TAG = "MCP:WaitForIdleTool"
+        private const val IDLE_CHECK_INTERVAL_MS = 500L
+        private const val DEFAULT_TIMEOUT_MS = 3000L
+        private const val MAX_TIMEOUT_MS = 30000L
+        /** Number of consecutive identical tree hashes required to consider UI idle. */
+        private const val REQUIRED_IDLE_CHECKS = 2
+    }
+}
```

> **Implementation Notes**:
> - Clipboard access: `ClipboardManager` is obtained from the accessibility service context. Accessibility services are exempt from the Android 10+ background clipboard restriction (only foreground apps can read clipboard), so this works reliably.
> - `SetClipboardTool` uses `ClipData.newPlainText("MCP", text)` where "MCP" is the label. The label is metadata and not shown to users.
> - `WaitForElementTool` catches `McpToolException` during polling to handle transient failures (e.g., tree parse failures during UI transitions). Only permission errors are immediately propagated; other errors trigger a retry on the next poll cycle.
> - `WaitForIdleTool.computeTreeHash()` produces a structural hash of the tree. Two consecutive identical hashes (`REQUIRED_IDLE_CHECKS = 2`) indicate the UI is stable. The hash includes class name, text, bounds, and child count -- this is sufficient to detect meaningful UI changes without being too expensive.
> - Both wait tools use `System.currentTimeMillis()` for timeout tracking and `kotlinx.coroutines.delay()` for non-blocking waits. The delay is cooperative and cancellable.
> - Timeout validation ensures values are within bounds (1 to 30000 ms). Values of 0 or negative are rejected. Values exceeding 30000 are also rejected to prevent excessively long tool calls.

#### Action 9.3.2: Update `ToolRegistry.kt` to register all 12 new tools

**What**: Add registrations for all 12 new tools in the `ToolRegistry` class.

**Context**: The `ToolRegistry` (created in Plans 7-8) calls `McpProtocolHandler.registerTool()` for each tool with the tool name, description, JSON input schema, and handler instance. The registrar is called during `McpServerService` startup. The new tools need Hilt injection for their dependencies (`AccessibilityTreeParser`, `ElementFinder`, `ActionExecutor`), so the tool instances are injected into `ToolRegistry` via constructor injection.

> **IMPORTANT — Constructor evolution**: Plan 7 defined an empty constructor. Plan 8 replaced it with `(protocolHandler, actionExecutor)`. This plan further expands the constructor to add 12 new tool parameters. Each plan's constructor definition **supersedes** the previous one. At implementation time, the final constructor includes all dependencies accumulated across Plans 7-9.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt
@@ constructor injection - add new tool parameters:
 class ToolRegistry @Inject constructor(
     private val protocolHandler: McpProtocolHandler,
     // ... existing tool injections from Plans 7-8 ...
+    private val findElementsTool: FindElementsTool,
+    private val clickElementTool: ClickElementTool,
+    private val longClickElementTool: LongClickElementTool,
+    private val setTextTool: SetTextTool,
+    private val scrollToElementTool: ScrollToElementTool,
+    private val inputTextTool: InputTextTool,
+    private val clearTextTool: ClearTextTool,
+    private val pressKeyTool: PressKeyTool,
+    private val getClipboardTool: GetClipboardTool,
+    private val setClipboardTool: SetClipboardTool,
+    private val waitForElementTool: WaitForElementTool,
+    private val waitForIdleTool: WaitForIdleTool,
 ) {

@@ in registerAll() method - add after existing registrations:

+        // Element Action Tools
+        register(
+            name = "find_elements",
+            description = "Find UI elements matching the specified criteria (text, content_desc, resource_id, class_name)",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("by") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            add(JsonPrimitive("text"))
+                            add(JsonPrimitive("content_desc"))
+                            add(JsonPrimitive("resource_id"))
+                            add(JsonPrimitive("class_name"))
+                        })
+                        put("description", "Search criteria type")
+                    }
+                    putJsonObject("value") {
+                        put("type", "string")
+                        put("description", "Search value")
+                    }
+                    putJsonObject("exact_match") {
+                        put("type", "boolean")
+                        put("default", false)
+                        put("description", "If true, match exactly. If false, match contains (case-insensitive)")
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(JsonPrimitive("by"))
+                    add(JsonPrimitive("value"))
+                })
+            },
+            handler = findElementsTool,
+        )
+
+        register(
+            name = "click_element",
+            description = "Click the specified accessibility node by element ID",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Node ID from find_elements")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
+            },
+            handler = clickElementTool,
+        )
+
+        register(
+            name = "long_click_element",
+            description = "Long-click the specified accessibility node by element ID",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Node ID from find_elements")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
+            },
+            handler = longClickElementTool,
+        )
+
+        register(
+            name = "set_text",
+            description = "Set text on an editable accessibility node (empty string to clear)",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Node ID from find_elements")
+                    }
+                    putJsonObject("text") {
+                        put("type", "string")
+                        put("description", "Text to set (empty string to clear)")
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(JsonPrimitive("element_id"))
+                    add(JsonPrimitive("text"))
+                })
+            },
+            handler = setTextTool,
+        )
+
+        register(
+            name = "scroll_to_element",
+            description = "Scroll to make the specified element visible",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Node ID from find_elements")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("element_id")) })
+            },
+            handler = scrollToElementTool,
+        )
+
+        // Text Input Tools
+        register(
+            name = "input_text",
+            description = "Type text into the focused input field or a specified element",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("text") {
+                        put("type", "string")
+                        put("description", "Text to type")
+                    }
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Optional: target element ID to focus and type into")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("text")) })
+            },
+            handler = inputTextTool,
+        )
+
+        register(
+            name = "clear_text",
+            description = "Clear text from the focused input field or a specified element",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("element_id") {
+                        put("type", "string")
+                        put("description", "Optional: target element ID to clear")
+                    }
+                }
+                put("required", buildJsonArray {})
+            },
+            handler = clearTextTool,
+        )
+
+        register(
+            name = "press_key",
+            description = "Press a specific key (ENTER, BACK, DEL, HOME, TAB, SPACE)",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("key") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            add(JsonPrimitive("ENTER"))
+                            add(JsonPrimitive("BACK"))
+                            add(JsonPrimitive("DEL"))
+                            add(JsonPrimitive("HOME"))
+                            add(JsonPrimitive("TAB"))
+                            add(JsonPrimitive("SPACE"))
+                        })
+                        put("description", "Key to press")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("key")) })
+            },
+            handler = pressKeyTool,
+        )
+
+        // Utility Tools
+        register(
+            name = "get_clipboard",
+            description = "Get the current clipboard text content",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                put("required", buildJsonArray {})
+            },
+            handler = getClipboardTool,
+        )
+
+        register(
+            name = "set_clipboard",
+            description = "Set the clipboard content to the specified text",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("text") {
+                        put("type", "string")
+                        put("description", "Text to set in clipboard")
+                    }
+                }
+                put("required", buildJsonArray { add(JsonPrimitive("text")) })
+            },
+            handler = setClipboardTool,
+        )
+
+        register(
+            name = "wait_for_element",
+            description = "Wait until an element matching criteria appears (with timeout)",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("by") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            add(JsonPrimitive("text"))
+                            add(JsonPrimitive("content_desc"))
+                            add(JsonPrimitive("resource_id"))
+                            add(JsonPrimitive("class_name"))
+                        })
+                        put("description", "Search criteria type")
+                    }
+                    putJsonObject("value") {
+                        put("type", "string")
+                        put("description", "Search value")
+                    }
+                    putJsonObject("timeout") {
+                        put("type", "number")
+                        put("description", "Timeout in milliseconds (1-30000)")
+                        put("default", 5000)
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(JsonPrimitive("by"))
+                    add(JsonPrimitive("value"))
+                })
+            },
+            handler = waitForElementTool,
+        )
+
+        register(
+            name = "wait_for_idle",
+            description = "Wait for the UI to become idle (no changes detected)",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("timeout") {
+                        put("type", "number")
+                        put("description", "Timeout in milliseconds (1-30000)")
+                        put("default", 3000)
+                    }
+                }
+                put("required", buildJsonArray {})
+            },
+            handler = waitForIdleTool,
+        )
```

> **Implementation Note**: The `buildJsonArray` and `JsonPrimitive` imports needed for the schema definitions should already be available from Plans 7-8. Verify at implementation time. The input schemas follow the exact structure defined in PROJECT.md for each tool.

---

### Task 9.4: Unit Tests for All 12 Tools

**Description**: Create unit tests for all element action, text input, and utility tools. Tests mock `AccessibilityTreeParser`, `ElementFinder`, `ActionExecutor`, `McpAccessibilityService`, and `ClipboardManager` using MockK. Tests verify correct behavior for valid inputs, invalid inputs, error conditions, and edge cases.

**Acceptance Criteria**:
- [ ] `ElementActionToolsTest` covers find_elements (valid, empty results, invalid by, empty value), click_element (valid, not found, not clickable), long_click_element, set_text (valid, non-editable), scroll_to_element
- [ ] `TextInputToolsTest` covers input_text (with element_id, without element_id), clear_text (with/without element_id), press_key (each valid key, invalid key)
- [ ] `UtilityToolsTest` covers get_clipboard, set_clipboard, wait_for_element (found, timeout, invalid params), wait_for_idle (idle, timeout, invalid params)
- [ ] All tests follow Arrange-Act-Assert pattern
- [ ] All tests use JUnit 5 and MockK
- [ ] All tests pass via `./gradlew test --tests '*ElementActionToolsTest*'`, `*TextInputToolsTest*`, `*UtilityToolsTest*`

**Tests**: This IS the test task.

#### Action 9.4.1: Create `ElementActionToolsTest.kt`

**What**: Create unit tests for the five element action tools.

**Context**: Tests mock `AccessibilityTreeParser`, `ElementFinder`, `ActionExecutor`, and `McpAccessibilityService.instance`. The `getFreshTree()` utility function accesses `McpAccessibilityService.instance` directly (companion object singleton), so tests must set this singleton via `mockkObject(McpAccessibilityService.Companion)` or by directly setting the field. Since the companion object field is `@Volatile var`, MockK's `every { McpAccessibilityService.instance }` approach is used.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionToolsTest.kt
@@ -0,0 +1,350 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.view.accessibility.AccessibilityNodeInfo
+import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import io.mockk.coEvery
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkObject
+import io.mockk.unmockkObject
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonArray
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertThrows
+
+@DisplayName("ElementActionTools")
+class ElementActionToolsTest {
+
+    private val mockTreeParser = mockk<AccessibilityTreeParser>()
+    private val mockElementFinder = mockk<ElementFinder>()
+    private val mockActionExecutor = mockk<ActionExecutor>()
+    private val mockService = mockk<McpAccessibilityService>()
+    private val mockRootNode = mockk<AccessibilityNodeInfo>()
+
+    private val sampleTree = AccessibilityNodeData(
+        id = "node_root",
+        className = "android.widget.FrameLayout",
+        bounds = BoundsData(0, 0, 1080, 2400),
+        visible = true,
+        children = listOf(
+            AccessibilityNodeData(
+                id = "node_abc",
+                className = "android.widget.Button",
+                text = "7",
+                bounds = BoundsData(50, 800, 250, 1000),
+                clickable = true,
+                enabled = true,
+                visible = true,
+            ),
+        ),
+    )
+
+    private val sampleBounds = BoundsData(50, 800, 250, 1000)
+
+    private val sampleElementInfo = ElementInfo(
+        id = "node_abc",
+        text = "7",
+        className = "android.widget.Button",
+        bounds = sampleBounds,
+        clickable = true,
+        enabled = true,
+    )
+
+    @BeforeEach
+    fun setUp() {
+        mockkObject(McpAccessibilityService.Companion)
+        every { McpAccessibilityService.instance } returns mockService
+        every { mockService.getRootNode() } returns mockRootNode
+        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
+        every { mockRootNode.recycle() } returns Unit
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkObject(McpAccessibilityService.Companion)
+    }
+
+    @Nested
+    @DisplayName("FindElementsTool")
+    inner class FindElementsToolTests {
+
+        private val tool = FindElementsTool(mockTreeParser, mockElementFinder)
+
+        @Test
+        fun `returns matching elements`() = runTest {
+            // Arrange
+            every { mockElementFinder.findElements(sampleTree, FindBy.TEXT, "7", false) } returns listOf(sampleElementInfo)
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "7")
+            }
+
+            // Act
+            val result = tool.execute(params).jsonObject
+
+            // Assert
+            val elements = result["elements"]!!.jsonArray
+            assertEquals(1, elements.size)
+            assertEquals("node_abc", elements[0].jsonObject["id"]?.jsonPrimitive?.content)
+        }
+
+        @Test
+        fun `returns empty array when no matches found`() = runTest {
+            // Arrange
+            every { mockElementFinder.findElements(sampleTree, FindBy.TEXT, "99", false) } returns emptyList()
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "99")
+            }
+
+            // Act
+            val result = tool.execute(params).jsonObject
+
+            // Assert
+            val elements = result["elements"]!!.jsonArray
+            assertTrue(elements.isEmpty())
+        }
+
+        @Test
+        fun `throws error for invalid by value`() = runTest {
+            val params = buildJsonObject {
+                put("by", "invalid_criteria")
+                put("value", "test")
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("Invalid 'by' value"))
+        }
+
+        @Test
+        fun `throws error for empty value`() = runTest {
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "")
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("non-empty"))
+        }
+
+        @Test
+        fun `throws error for missing by parameter`() = runTest {
+            val params = buildJsonObject { put("value", "test") }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("ClickElementTool")
+    inner class ClickElementToolTests {
+
+        private val tool = ClickElementTool(mockTreeParser, mockActionExecutor)
+
+        @Test
+        fun `clicks element successfully`() = runTest {
+            coEvery { mockActionExecutor.clickNode("node_abc", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject { put("element_id", "node_abc") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Click performed") == true)
+        }
+
+        @Test
+        fun `throws error when element not found`() = runTest {
+            coEvery { mockActionExecutor.clickNode("node_xyz", sampleTree) } returns
+                Result.failure(NoSuchElementException("Node 'node_xyz' not found"))
+            val params = buildJsonObject { put("element_id", "node_xyz") }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_ELEMENT_NOT_FOUND, exception.code)
+        }
+
+        @Test
+        fun `throws error when element not clickable`() = runTest {
+            coEvery { mockActionExecutor.clickNode("node_abc", sampleTree) } returns
+                Result.failure(IllegalStateException("Node 'node_abc' is not clickable"))
+            val params = buildJsonObject { put("element_id", "node_abc") }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+
+        @Test
+        fun `throws error for missing element_id`() = runTest {
+            val params = buildJsonObject {}
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("LongClickElementTool")
+    inner class LongClickElementToolTests {
+
+        private val tool = LongClickElementTool(mockTreeParser, mockActionExecutor)
+
+        @Test
+        fun `long-clicks element successfully`() = runTest {
+            coEvery { mockActionExecutor.longClickNode("node_abc", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject { put("element_id", "node_abc") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Long-click") == true)
+        }
+    }
+
+    @Nested
+    @DisplayName("SetTextTool")
+    inner class SetTextToolTests {
+
+        private val tool = SetTextTool(mockTreeParser, mockActionExecutor)
+
+        @Test
+        fun `sets text on element`() = runTest {
+            coEvery { mockActionExecutor.setTextOnNode("node_abc", "Hello", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject {
+                put("element_id", "node_abc")
+                put("text", "Hello")
+            }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text set") == true)
+        }
+
+        @Test
+        fun `allows empty text to clear field`() = runTest {
+            coEvery { mockActionExecutor.setTextOnNode("node_abc", "", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject {
+                put("element_id", "node_abc")
+                put("text", "")
+            }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text set") == true)
+        }
+
+        @Test
+        fun `throws error for non-editable element`() = runTest {
+            coEvery { mockActionExecutor.setTextOnNode("node_abc", "Hi", sampleTree) } returns
+                Result.failure(IllegalStateException("Node 'node_abc' is not editable"))
+            val params = buildJsonObject {
+                put("element_id", "node_abc")
+                put("text", "Hi")
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("ScrollToElementTool")
+    inner class ScrollToElementToolTests {
+
+        private val tool = ScrollToElementTool(mockTreeParser, mockElementFinder, mockActionExecutor)
+
+        @Test
+        fun `returns immediately when element already visible`() = runTest {
+            val visibleNode = sampleTree.children[0] // visible = true
+            every { mockElementFinder.findNodeById(sampleTree, "node_abc") } returns visibleNode
+            val params = buildJsonObject { put("element_id", "node_abc") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("already visible") == true)
+        }
+
+        @Test
+        fun `throws error when element not found`() = runTest {
+            every { mockElementFinder.findNodeById(sampleTree, "node_xyz") } returns null
+            val params = buildJsonObject { put("element_id", "node_xyz") }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_ELEMENT_NOT_FOUND, exception.code)
+        }
+    }
+}
```

> **Implementation Note on mocking `McpAccessibilityService.instance`**: The `instance` field is a `@Volatile var` in a companion object. MockK's `mockkObject(McpAccessibilityService.Companion)` combined with `every { McpAccessibilityService.instance } returns mockService` properly mocks the singleton access. The `unmockkObject()` in `@AfterEach` cleans up to prevent test interference.

#### Action 9.4.2: Create `TextInputToolsTest.kt`

**What**: Create unit tests for the three text input tools.

**Context**: `InputTextTool` and `ClearTextTool` tests need to mock both `ActionExecutor` (for element_id path) and `McpAccessibilityService.findFocus()` (for focused node path). `PressKeyTool` tests mock `ActionExecutor` for BACK/HOME and the focused node for other keys. Since `findFocusedEditableNode()` is a package-level function that directly accesses `McpAccessibilityService.instance`, the same `mockkObject` pattern is used.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputToolsTest.kt
@@ -0,0 +1,230 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.os.Bundle
+import android.view.accessibility.AccessibilityNodeInfo
+import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import io.mockk.coEvery
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkObject
+import io.mockk.unmockkObject
+import io.mockk.verify
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertThrows
+
+@DisplayName("TextInputTools")
+class TextInputToolsTest {
+
+    private val mockTreeParser = mockk<AccessibilityTreeParser>()
+    private val mockActionExecutor = mockk<ActionExecutor>()
+    private val mockService = mockk<McpAccessibilityService>()
+    private val mockRootNode = mockk<AccessibilityNodeInfo>()
+    private val mockFocusedNode = mockk<AccessibilityNodeInfo>()
+
+    private val sampleTree = AccessibilityNodeData(
+        id = "node_root",
+        className = "android.widget.FrameLayout",
+        bounds = BoundsData(0, 0, 1080, 2400),
+        visible = true,
+    )
+
+    @BeforeEach
+    fun setUp() {
+        mockkObject(McpAccessibilityService.Companion)
+        every { McpAccessibilityService.instance } returns mockService
+        every { mockService.getRootNode() } returns mockRootNode
+        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
+        every { mockRootNode.recycle() } returns Unit
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkObject(McpAccessibilityService.Companion)
+    }
+
+    @Nested
+    @DisplayName("InputTextTool")
+    inner class InputTextToolTests {
+
+        private val tool = InputTextTool(mockTreeParser, mockActionExecutor)
+
+        @Test
+        fun `inputs text on specified element`() = runTest {
+            coEvery { mockActionExecutor.clickNode("node_123", sampleTree) } returns Result.success(Unit)
+            coEvery { mockActionExecutor.setTextOnNode("node_123", "Hello", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject {
+                put("text", "Hello")
+                put("element_id", "node_123")
+            }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text input completed") == true)
+        }
+
+        @Test
+        fun `inputs text on focused element when no element_id`() = runTest {
+            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
+            every { mockFocusedNode.isEditable } returns true
+            every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
+            every { mockFocusedNode.recycle() } returns Unit
+            val params = buildJsonObject { put("text", "World") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text input completed") == true)
+        }
+
+        @Test
+        fun `throws error when text is missing`() = runTest {
+            val params = buildJsonObject {}
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("ClearTextTool")
+    inner class ClearTextToolTests {
+
+        private val tool = ClearTextTool(mockTreeParser, mockActionExecutor)
+
+        @Test
+        fun `clears text on specified element`() = runTest {
+            coEvery { mockActionExecutor.setTextOnNode("node_123", "", sampleTree) } returns Result.success(Unit)
+            val params = buildJsonObject { put("element_id", "node_123") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text cleared") == true)
+        }
+
+        @Test
+        fun `clears text on focused element when no element_id`() = runTest {
+            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
+            every { mockFocusedNode.isEditable } returns true
+            every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
+            every { mockFocusedNode.recycle() } returns Unit
+            val params = buildJsonObject {}
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Text cleared") == true)
+        }
+    }
+
+    @Nested
+    @DisplayName("PressKeyTool")
+    inner class PressKeyToolTests {
+
+        private val tool = PressKeyTool(mockActionExecutor)
+
+        @Test
+        fun `presses BACK key`() = runTest {
+            coEvery { mockActionExecutor.pressBack() } returns Result.success(Unit)
+            val params = buildJsonObject { put("key", "BACK") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("BACK") == true)
+        }
+
+        @Test
+        fun `presses HOME key`() = runTest {
+            coEvery { mockActionExecutor.pressHome() } returns Result.success(Unit)
+            val params = buildJsonObject { put("key", "HOME") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("HOME") == true)
+        }
+
+        @Test
+        fun `presses DEL key removes last character`() = runTest {
+            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
+            every { mockFocusedNode.isEditable } returns true
+            every { mockFocusedNode.text } returns "Hello"
+            every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
+            every { mockFocusedNode.recycle() } returns Unit
+            val params = buildJsonObject { put("key", "DEL") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("DEL") == true)
+        }
+
+        @Test
+        fun `presses SPACE key appends space`() = runTest {
+            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
+            every { mockFocusedNode.isEditable } returns true
+            every { mockFocusedNode.text } returns "Hello"
+            every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
+            every { mockFocusedNode.recycle() } returns Unit
+            val params = buildJsonObject { put("key", "SPACE") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("SPACE") == true)
+        }
+
+        @Test
+        fun `throws error for invalid key`() = runTest {
+            val params = buildJsonObject { put("key", "ESCAPE") }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("Invalid key"))
+        }
+
+        @Test
+        fun `throws error for missing key parameter`() = runTest {
+            val params = buildJsonObject {}
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+}
```

#### Action 9.4.3: Create `UtilityToolsTest.kt`

**What**: Create unit tests for the four utility tools.

**Context**: `GetClipboardTool` and `SetClipboardTool` tests mock `ClipboardManager` obtained from the accessibility service context. `WaitForElementTool` and `WaitForIdleTool` tests need to mock the tree parser to return different trees on successive calls (to simulate element appearing or UI changing). Since wait tools use `kotlinx.coroutines.delay()`, tests should use `runTest` which auto-advances virtual time.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityToolsTest.kt
@@ -0,0 +1,280 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.content.ClipData
+import android.content.ClipboardManager
+import android.view.accessibility.AccessibilityNodeInfo
+import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkObject
+import io.mockk.unmockkObject
+import io.mockk.verify
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertThrows
+
+@DisplayName("UtilityTools")
+class UtilityToolsTest {
+
+    private val mockTreeParser = mockk<AccessibilityTreeParser>()
+    private val mockElementFinder = mockk<ElementFinder>()
+    private val mockService = mockk<McpAccessibilityService>()
+    private val mockRootNode = mockk<AccessibilityNodeInfo>()
+    private val mockClipboardManager = mockk<ClipboardManager>()
+
+    private val sampleTree = AccessibilityNodeData(
+        id = "node_root",
+        className = "android.widget.FrameLayout",
+        bounds = BoundsData(0, 0, 1080, 2400),
+        visible = true,
+    )
+
+    private val sampleBounds = BoundsData(50, 800, 250, 1000)
+
+    private val sampleElementInfo = ElementInfo(
+        id = "node_abc",
+        text = "Result",
+        className = "android.widget.TextView",
+        bounds = sampleBounds,
+        clickable = false,
+        enabled = true,
+    )
+
+    @BeforeEach
+    fun setUp() {
+        mockkObject(McpAccessibilityService.Companion)
+        every { McpAccessibilityService.instance } returns mockService
+        every { mockService.getRootNode() } returns mockRootNode
+        every { mockService.getSystemService(ClipboardManager::class.java) } returns mockClipboardManager
+        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
+        every { mockRootNode.recycle() } returns Unit
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkObject(McpAccessibilityService.Companion)
+    }
+
+    @Nested
+    @DisplayName("GetClipboardTool")
+    inner class GetClipboardToolTests {
+
+        private val tool = GetClipboardTool()
+
+        @Test
+        fun `returns clipboard text`() = runTest {
+            val mockClip = mockk<ClipData>()
+            val mockItem = mockk<ClipData.Item>()
+            every { mockClipboardManager.primaryClip } returns mockClip
+            every { mockClip.itemCount } returns 1
+            every { mockClip.getItemAt(0) } returns mockItem
+            every { mockItem.text } returns "copied text"
+
+            val result = tool.execute(null).jsonObject
+            assertEquals("copied text", result["text"]?.jsonPrimitive?.content)
+        }
+
+        @Test
+        fun `returns null when clipboard empty`() = runTest {
+            every { mockClipboardManager.primaryClip } returns null
+
+            val result = tool.execute(null).jsonObject
+            assertTrue(result["text"]?.jsonPrimitive?.content == null ||
+                result["text"]?.jsonPrimitive?.content == "null")
+        }
+    }
+
+    @Nested
+    @DisplayName("SetClipboardTool")
+    inner class SetClipboardToolTests {
+
+        private val tool = SetClipboardTool()
+
+        @Test
+        fun `sets clipboard text`() = runTest {
+            every { mockClipboardManager.setPrimaryClip(any()) } returns Unit
+            val params = buildJsonObject { put("text", "new content") }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Clipboard set") == true)
+            verify { mockClipboardManager.setPrimaryClip(any()) }
+        }
+
+        @Test
+        fun `throws error when text missing`() = runTest {
+            val params = buildJsonObject {}
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("WaitForElementTool")
+    inner class WaitForElementToolTests {
+
+        private val tool = WaitForElementTool(mockTreeParser, mockElementFinder)
+
+        @Test
+        fun `finds element on first attempt`() = runTest {
+            every { mockElementFinder.findElements(sampleTree, FindBy.TEXT, "Result", false) } returns
+                listOf(sampleElementInfo)
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "Result")
+                put("timeout", 5000)
+            }
+
+            val result = tool.execute(params).jsonObject
+            assertEquals(true, result["found"]?.jsonPrimitive?.content?.toBoolean())
+        }
+
+        @Test
+        fun `throws error for invalid by parameter`() = runTest {
+            val params = buildJsonObject {
+                put("by", "invalid")
+                put("value", "test")
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+
+        @Test
+        fun `throws error for empty value`() = runTest {
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "")
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+
+        @Test
+        fun `throws error for timeout exceeding max`() = runTest {
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "test")
+                put("timeout", 50000)
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("Timeout must be between"))
+        }
+
+        @Test
+        fun `throws error for negative timeout`() = runTest {
+            val params = buildJsonObject {
+                put("by", "text")
+                put("value", "test")
+                put("timeout", -1)
+            }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+
+    @Nested
+    @DisplayName("WaitForIdleTool")
+    inner class WaitForIdleToolTests {
+
+        private val tool = WaitForIdleTool(mockTreeParser)
+
+        @Test
+        fun `detects idle when tree does not change`() = runTest {
+            // Same tree returned each time -> idle detected
+            val params = buildJsonObject { put("timeout", 5000) }
+
+            val result = tool.execute(params).jsonObject
+            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("idle") == true)
+        }
+
+        @Test
+        fun `throws error for timeout exceeding max`() = runTest {
+            val params = buildJsonObject { put("timeout", 50000) }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+
+        @Test
+        fun `throws error for zero timeout`() = runTest {
+            val params = buildJsonObject { put("timeout", 0) }
+
+            val exception = assertThrows<McpToolException> { tool.execute(params) }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+    }
+}
```

> **Implementation Notes**:
> - `WaitForElementTool` test for timeout: Since `runTest` auto-advances virtual time, the poll loop with `delay(500)` completes instantly. For the "element found on first attempt" test, the mock returns a match immediately. For a real timeout test, the mock would need to return empty results for all attempts; however, since `runTest` uses virtual time, `System.currentTimeMillis()` does not advance with virtual time. At implementation time, consider injecting a `TimeProvider` interface (or use `TestCoroutineScheduler.currentTime`) to make the timeout testable. Alternatively, use a short timeout and verify the exception is thrown.
> - `WaitForIdleTool` test for idle detection: Since the mock `treeParser` returns the same `sampleTree` every time, the structural hash will be identical on consecutive calls, causing idle detection to succeed quickly.
> - All tests clean up MockK state in `@AfterEach` to prevent cross-test contamination.

---

### Task 9.5: Complete MCP_TOOLS.md Documentation

**Description**: Update (or create) `docs/MCP_TOOLS.md` with comprehensive documentation for all 29 MCP tools across 7 categories. This document covers all tools from Plans 7-9: 4 screen introspection + 6 system + 5 touch + 2 gesture + 5 element action + 3 text input + 4 utility.

**Acceptance Criteria**:
- [ ] Document includes all 29 tools organized by category
- [ ] Each tool has: name, description, input schema, output format, error cases, usage examples
- [ ] Document includes error code reference table
- [ ] Document includes authentication notes
- [ ] File is well-formatted markdown

**Tests**: Documentation — no automated tests. Verify content accuracy by cross-referencing with tool implementations.

#### Action 9.5.1: Create or update `docs/MCP_TOOLS.md`

**What**: Write comprehensive MCP tools documentation.

**Context**: This file is referenced in PROJECT.md's "Related Documentation" section. It should contain all 29 tools with full input/output schemas, usage examples with curl commands, error codes, and implementation notes. The document structure follows the 7 tool categories from PROJECT.md.

**File**: `docs/MCP_TOOLS.md`

The document should contain the following sections:

1. **Header** — Title, overview, table of contents
2. **Authentication** — Bearer token requirement, header format
3. **Error Codes** — Table of all standard and custom error codes
4. **Tool Categories** (7 sections, one per category):
   - For each tool: Name, description, input schema (JSON), output format (JSON), error cases, curl example
5. **Screen Introspection Tools** (4): `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info`
6. **System Action Tools** (6): `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs`
7. **Touch Action Tools** (5): `tap`, `long_press`, `double_tap`, `swipe`, `scroll`
8. **Gesture Tools** (2): `pinch`, `custom_gesture`
9. **Element Action Tools** (5): `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element`
10. **Text Input Tools** (3): `input_text`, `clear_text`, `press_key`
11. **Utility Tools** (4): `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle`

Due to the large size of this documentation file, the full content is not included as a diff here. At implementation time, create the file with all 29 tools documented following the patterns in PROJECT.md section "MCP Tools Specification". Include:
- JSON input schema (matching the schemas registered in ToolRegistry)
- JSON output examples
- Error cases with specific error codes
- A curl example for each tool showing the full JSON-RPC request

> **Implementation Note**: The MCP_TOOLS.md file will be approximately 800-1000 lines. Copy the input schemas from the ToolRegistry registrations to ensure consistency. For output examples, use the JSON structures returned by each tool's `execute()` method.

---

### Task 9.6: Final Verification

**Description**: Run linting, unit tests, and build to verify all changes compile and pass quality gates.

**Acceptance Criteria**:
- [ ] `make lint` passes with no errors or warnings
- [ ] `make test-unit` passes with all tests green
- [ ] `make build` succeeds with no errors or warnings

#### Action 9.6.1: Run linting

**What**: Execute lint checks to ensure code style compliance.

```bash
make lint
```

If lint issues are found, fix them with `make lint-fix` where possible, then manually fix remaining issues.

#### Action 9.6.2: Run unit tests

**What**: Execute all unit tests to verify correctness.

```bash
make test-unit
```

All tests must pass. If any test fails, fix the root cause before proceeding.

Run targeted tests for just the new tool files:

```bash
./gradlew test --tests '*ElementActionToolsTest*'
./gradlew test --tests '*TextInputToolsTest*'
./gradlew test --tests '*UtilityToolsTest*'
```

#### Action 9.6.3: Run build

**What**: Build the debug APK to verify compilation.

```bash
make build
```

Build must succeed with no errors and no warnings.

---

## Performance, Security, and QA Review

### Performance Considerations

1. **Fresh tree parsing on every tool call**: Each element action tool parses the full accessibility tree on every invocation. For complex UIs with deep trees, this could take 10-50ms. This is acceptable for MCP tool call latency but should be monitored. If performance becomes an issue, consider a short-lived tree cache (e.g., 200ms TTL) shared across tool calls within the same JSON-RPC request.

2. **`wait_for_element` polling**: The 500ms poll interval means the tool may respond up to 500ms after the element actually appears. For time-critical operations, the caller can use shorter intervals by chaining `find_elements` calls manually. The 500ms interval is a good balance between responsiveness and CPU usage.

3. **`wait_for_idle` tree hashing**: The `computeTreeHash()` function recursively hashes the entire tree on each poll. For very deep trees, this could be expensive. The hash is intentionally shallow (class name, text, bounds, child count) to be fast. If profiling shows this is a bottleneck, consider hashing only the first 2-3 levels of the tree.

4. **`scroll_to_element` retry loop**: Up to 5 scroll attempts with 300ms settle delay each = max 1.5 seconds of scrolling. This is reasonable for most scenarios. The settle delay allows the UI to render the new scroll position before re-parsing.

### Security Considerations

1. **Clipboard access**: The `get_clipboard` and `set_clipboard` tools can read/write any clipboard content. This is by design (the MCP server controls the device), but callers should be aware that clipboard data may contain sensitive information. The bearer token authentication protects against unauthorized access.

2. **Text input**: The `input_text` and `set_text` tools can type into any field, including password fields. Again, this is by design for full device control. The tools do not log the text content (only the length).

3. **Parameter validation**: All tools validate their parameters before executing. Invalid parameters result in -32602 errors without side effects. This prevents injection or unexpected behavior from malformed requests.

### QA Considerations

1. **Stale node IDs**: The element_id returned by `find_elements` is only valid as long as the UI does not change. If the UI changes between `find_elements` and `click_element`, the node ID may be stale and the click will fail with -32002. This is documented behavior and the caller should re-find elements if they encounter this error.

2. **`findFocusedEditableNode()` edge cases**: On some Android ROMs or custom keyboards, `findFocus(FOCUS_INPUT)` may not return the expected node. The function returns null in such cases, and the tools report a clear error message.

3. **`press_key` DEL on empty field**: Pressing DEL on an empty text field is a no-op (not an error). This prevents unnecessary error handling in automated scripts.

4. **`wait_for_element` with `exact_match`**: The wait tool always uses `exactMatch = false` (contains, case-insensitive) to be more forgiving. If exact matching is needed, use `find_elements` with `exact_match: true` in a manual poll loop.

5. **`wait_for_idle` false positives**: The idle detection is approximate. A UI that updates at exactly the polling frequency (500ms) could appear idle between updates. The `REQUIRED_IDLE_CHECKS = 2` mitigates this by requiring two consecutive identical snapshots.

---

**End of Plan 9**
