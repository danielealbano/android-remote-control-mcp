<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 36 — Rename `*_element` to `*_node` in MCP Tools

## Context

MCP node IDs use the format `node_<hash>`. All MCP-facing identifiers currently use `element` terminology (`click_element`, `element_id`, `find_elements`, etc.), which is inconsistent with the `node_` ID prefix. This plan renames every MCP-facing occurrence of `element` to `node` for consistency: tool names, parameter names, output field names, class names, file names, error messages, log messages, descriptions, and documentation.

**Prerequisite**: Plan 35 (`tap_element` tool) must be implemented before this plan. This plan includes renaming Plan 35's additions (`TapElementTool` → `TapNodeTool`, `tap_element` → `tap_node`, `element_id` → `node_id`).

**Out of scope**: Internal classes `ElementInfo`, `ElementFinder`, `FindBy` — these are not MCP-facing and remain unchanged.

---

## User Story 1: Rename element→node in MCP tool source code

### Acceptance Criteria
- [x] All MCP tool names containing `element` are renamed to use `node`
- [x] All MCP parameter names `element_id` → `node_id`, `element_ids` → `node_ids`
- [x] All MCP output field names `element_id` → `node_id` in JSON and TSV
- [x] All tool classes, registration functions, exception subclasses renamed
- [x] All error messages, log messages, descriptions, KDoc updated
- [x] Source files renamed (`ElementActionTools.kt` → `NodeActionTools.kt`)
- [x] No compilation errors

---

### Task 1: Rename `McpToolException.ElementNotFound` → `NodeNotFound`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpToolException.kt` — modify

**Action 1.1** — Rename the sealed subclass:

| Old | New |
|-----|-----|
| `class ElementNotFound(` | `class NodeNotFound(` |

**Definition of Done**:
- [x] `McpToolException.NodeNotFound` compiles
- [x] No remaining references to `McpToolException.ElementNotFound` in this file

---

### Task 2: Rename `ElementActionTools.kt` → `NodeActionTools.kt`

**Action 2.1** — Rename file:
```
git mv app/src/main/kotlin/.../mcp/tools/ElementActionTools.kt app/src/main/kotlin/.../mcp/tools/NodeActionTools.kt
```

**Action 2.2** — Apply all renames in `NodeActionTools.kt`:

**Class renames:**

| Old | New |
|-----|-----|
| `class FindElementsTool` | `class FindNodesTool` |
| `class ClickElementTool` | `class ClickNodeTool` |
| `class LongClickElementTool` | `class LongClickNodeTool` |
| `class TapElementTool` | `class TapNodeTool` |
| `class ScrollToElementTool` | `class ScrollToNodeTool` |

**TOOL_NAME constants:**

| Old | New |
|-----|-----|
| `"find_elements"` | `"find_nodes"` |
| `"click_element"` | `"click_node"` |
| `"long_click_element"` | `"long_click_node"` |
| `"tap_element"` | `"tap_node"` |
| `"scroll_to_element"` | `"scroll_to_node"` |

**TAG constants:**

| Old | New |
|-----|-----|
| `"MCP:FindElementsTool"` | `"MCP:FindNodesTool"` |
| `"MCP:ClickElementTool"` | `"MCP:ClickNodeTool"` |
| `"MCP:LongClickElementTool"` | `"MCP:LongClickNodeTool"` |
| `"MCP:TapElementTool"` | `"MCP:TapNodeTool"` |
| `"MCP:ScrollToElementTool"` | `"MCP:ScrollToNodeTool"` |

**Registration function:**

| Old | New |
|-----|-----|
| `fun registerElementActionTools(` | `fun registerNodeActionTools(` |
| `perms.isToolEnabled(FindElementsTool.TOOL_NAME)` | `perms.isToolEnabled(FindNodesTool.TOOL_NAME)` |
| `FindElementsTool(` (in registration body) | `FindNodesTool(` |
| `perms.isToolEnabled(ClickElementTool.TOOL_NAME)` | `perms.isToolEnabled(ClickNodeTool.TOOL_NAME)` |
| `ClickElementTool(` (in registration body) | `ClickNodeTool(` |
| `perms.isToolEnabled(LongClickElementTool.TOOL_NAME)` | `perms.isToolEnabled(LongClickNodeTool.TOOL_NAME)` |
| `LongClickElementTool(` (in registration body) | `LongClickNodeTool(` |
| `perms.isToolEnabled(TapElementTool.TOOL_NAME)` | `perms.isToolEnabled(TapNodeTool.TOOL_NAME)` |
| `TapElementTool(` (in registration body) | `TapNodeTool(` |
| `perms.isToolEnabled(ScrollToElementTool.TOOL_NAME)` | `perms.isToolEnabled(ScrollToNodeTool.TOOL_NAME)` |
| `ScrollToElementTool(` (in registration body) | `ScrollToNodeTool(` |

**Parameter names in tool schemas** (ClickNodeTool, LongClickNodeTool, TapNodeTool, ScrollToNodeTool):

| Old | New |
|-----|-----|
| `putJsonObject("element_id")` | `putJsonObject("node_id")` |
| `required = listOf("element_id")` | `required = listOf("node_id")` |
| `"Node ID from ${toolNamePrefix}find_elements"` | `"Node ID from ${toolNamePrefix}find_nodes"` |

**Local variable renames** (in ClickNodeTool, LongClickNodeTool, TapNodeTool, ScrollToNodeTool `execute()` methods and in `mapNodeActionException`):

| Old | New |
|-----|-----|
| `val elementId =` | `val nodeId =` |
| All subsequent uses of `elementId` in the same scope | `nodeId` |

Also rename `mapNodeActionException` parameter:

| Old | New |
|-----|-----|
| `elementId: String,` | `nodeId: String,` |

**Error/validation messages** (all `"element_id"` → `"node_id"` in string literals):

| Old | New |
|-----|-----|
| `"Missing required parameter 'element_id'"` | `"Missing required parameter 'node_id'"` |
| `"Parameter 'element_id' must be non-empty"` | `"Parameter 'node_id' must be non-empty"` |

**User-facing output messages** (in execute methods and `mapNodeActionException`):

| Old | New |
|-----|-----|
| `"Click performed on element '$elementId'"` | `"Click performed on node '$nodeId'"` |
| `"Long-click performed on element '$elementId'"` | `"Long-click performed on node '$nodeId'"` |
| `"Tap executed at (${tapX.toInt()}, ${tapY.toInt()}) within element '$elementId'"` | `"Tap executed at (${tapX.toInt()}, ${tapY.toInt()}) within node '$nodeId'"` |
| `"Element '$elementId' not found"` (ScrollToNodeTool) | `"Node '$nodeId' not found"` |
| `"Element '$elementId' is already visible"` | `"Node '$nodeId' is already visible"` |
| `"Element '$elementId' not found in any window tree"` | `"Node '$nodeId' not found in any window tree"` |
| `"No scrollable container found for element '$elementId'"` | `"No scrollable container found for node '$nodeId'"` |
| `"Scrolled to element '$elementId' ($totalAttempts scroll(s))"` | `"Scrolled to node '$nodeId' ($totalAttempts scroll(s))"` |
| `"Element '$elementId' not visible after..."` | `"Node '$nodeId' not visible after..."` |
| `"Element '$elementId' not found in accessibility tree"` (mapNodeActionException) | `"Node '$nodeId' not found in accessibility tree"` |
| `"Action failed on element '$elementId'"` (mapNodeActionException) | `"Action failed on node '$nodeId'"` |
| `"Action failed on element '$elementId': ${exception.message}"` (mapNodeActionException) | `"Action failed on node '$nodeId': ${exception.message}"` |

**Log messages:**

| Old | New |
|-----|-----|
| `"find_elements: by=$byStr..."` | `"find_nodes: by=$byStr..."` |
| `"click_element: elementId=$elementId succeeded"` | `"click_node: nodeId=$nodeId succeeded"` |
| `"long_click_element: elementId=$elementId succeeded"` | `"long_click_node: nodeId=$nodeId succeeded"` |
| `"tap_element: elementId=$elementId, tapAt=..."` | `"tap_node: nodeId=$nodeId, tapAt=..."` |
| `"scroll_to_element: element '$elementId'..."` (all occurrences) | `"scroll_to_node: node '$nodeId'..."` |

**Tool description strings:**

| Old | New |
|-----|-----|
| `"Find UI elements matching the specified criteria..."` (FindNodesTool) | `"Find UI nodes matching the specified criteria..."` |
| `"Click the specified accessibility node by element ID"` | `"Click the specified accessibility node by node ID"` |
| `"Long-click the specified accessibility node by element ID"` | `"Long-click the specified accessibility node by node ID"` |
| TapNodeTool description: all `"element"` references | `"node"` |
| `"Scroll to make the specified element visible"` | `"Scroll to make the specified node visible"` |

**JSON output field renames** (in FindNodesTool):

| Old | New |
|-----|-----|
| `put("elements", ...)` (JSON response key) | `put("nodes", ...)` |

**Exception references:**

| Old | New |
|-----|-----|
| `McpToolException.ElementNotFound` | `McpToolException.NodeNotFound` |

**KDoc and comments:**

| Old | New |
|-----|-----|
| `* MCP tool: find_elements` | `* MCP tool: find_nodes` |
| `* Finds UI elements matching` | `* Finds UI nodes matching` |
| `* MCP tool: click_element` | `* MCP tool: click_node` |
| `* Clicks the accessibility node identified by element_id.` | `* Clicks the accessibility node identified by node_id.` |
| `* MCP tool: long_click_element` | `* MCP tool: long_click_node` |
| `* Long-clicks the accessibility node identified by element_id.` | `* Long-clicks the accessibility node identified by node_id.` |
| `* MCP tool: tap_element` (from Plan 35) | `* MCP tool: tap_node` |
| TapNodeTool KDoc: `element_id` → `node_id`, `element` → `node` (in MCP context) | |
| `* MCP tool: scroll_to_element` | `* MCP tool: scroll_to_node` |
| `// Shared Utilities for Element Action Tools` | `// Shared Utilities for Node Action Tools` |
| `* Registers all element action tools` | `* Registers all node action tools` |

**Definition of Done**:
- [x] File renamed to `NodeActionTools.kt`
- [x] All 5 tool classes renamed
- [x] All TOOL_NAME, TAG constants updated
- [x] Registration function renamed
- [x] All parameter names, variable names, messages, descriptions, KDoc updated
- [x] No remaining `element` references in MCP-facing identifiers

---

### Task 3: Rename in `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt` — modify

**Action 3.1** — Apply renames:

**Class renames:**

| Old | New |
|-----|-----|
| `class WaitForElementTool` | `class WaitForNodeTool` |
| `class GetElementDetailsTool` | `class GetNodeDetailsTool` |

**TOOL_NAME and TAG constants:**

| Old | New |
|-----|-----|
| `"wait_for_element"` (TOOL_NAME) | `"wait_for_node"` |
| `"get_element_details"` (TOOL_NAME) | `"get_node_details"` |
| `"MCP:WaitForElementTool"` (TAG) | `"MCP:WaitForNodeTool"` |
| `"MCP:GetElementDetailsTool"` (TAG) | `"MCP:GetNodeDetailsTool"` |

**GetNodeDetailsTool parameter names:**

| Old | New |
|-----|-----|
| `arguments?.get("element_ids")` | `arguments?.get("node_ids")` |
| `"Missing required parameter 'element_ids'"` | `"Missing required parameter 'node_ids'"` |
| `"Parameter 'element_ids' must be an array of strings"` | `"Parameter 'node_ids' must be an array of strings"` |
| `"Parameter 'element_ids' must not be empty"` | `"Parameter 'node_ids' must not be empty"` |
| `"Each element in 'element_ids' must be a string"` (both occurrences) | `"Each element in 'node_ids' must be a string"` |
| `putJsonObject("element_ids")` | `putJsonObject("node_ids")` |
| `"List of element_ids to retrieve details for"` | `"List of node_ids to retrieve details for"` |
| `required = listOf("element_ids")` | `required = listOf("node_ids")` |

**JSON output field renames** (in WaitForNodeTool):

| Old | New |
|-----|-----|
| `put("element", McpToolUtils.buildNodeJson(element))` | `put("node", McpToolUtils.buildNodeJson(element))` |

**TSV header:**

| Old | New |
|-----|-----|
| `sb.append("element_id\ttext\tdesc\n")` | `sb.append("node_id\ttext\tdesc\n")` |

**Log messages:**

| Old | New |
|-----|-----|
| `"get_element_details: element_ids=${ids.size}"` | `"get_node_details: node_ids=${ids.size}"` |
| `"wait_for_element: found after ${elapsed}ms..."` | `"wait_for_node: found after ${elapsed}ms..."` |
| `"wait_for_element: poll attempt..."` | `"wait_for_node: poll attempt..."` |

**Tool description strings:**

| Old | New |
|-----|-----|
| `"Wait until an element matching criteria appears (with timeout)"` (WaitForNodeTool) | `"Wait until a node matching criteria appears (with timeout)"` |
| `"Retrieve full untruncated text and description for elements by element_id. "` | `"Retrieve full untruncated text and description for nodes by node_id. "` |
| All `element_id` → `node_id` and `element_ids` → `node_ids` in description text | | |

**User-facing output messages:**

| Old | New |
|-----|-----|
| `"...waiting for element (by=$byStr, value='$value')"` | `"...waiting for node (by=$byStr, value='$value')"` |

**KDoc:**

| Old | New |
|-----|-----|
| `* MCP tool: wait_for_element` | `* MCP tool: wait_for_node` |
| `* MCP tool: get_element_details` | `* MCP tool: get_node_details` |
| `* Retrieves full (untruncated) text and contentDescription for a list of element_ids.` | `* Retrieves full (untruncated) text and contentDescription for a list of node_ids.` |
| `// 1. Parse "element_ids" parameter` | `// 1. Parse "node_ids" parameter` |

**`registerUtilityTools()` function body** (constructor calls at lines ~631-642):

| Old | New |
|-----|-----|
| `perms.isToolEnabled(WaitForElementTool.TOOL_NAME)` | `perms.isToolEnabled(WaitForNodeTool.TOOL_NAME)` |
| `WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)` | `WaitForNodeTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)` |
| `perms.isToolEnabled(GetElementDetailsTool.TOOL_NAME)` | `perms.isToolEnabled(GetNodeDetailsTool.TOOL_NAME)` |
| `GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)` | `GetNodeDetailsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)` |

**Definition of Done**:
- [x] Both tool classes renamed
- [x] All constants, parameters, messages, KDoc updated
- [x] `registerUtilityTools()` constructor calls updated
- [x] No remaining `element` in MCP-facing identifiers

---

### Task 4: Rename in `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt` — modify

**Action 4.1** — Apply renames across all 4 text input tool classes (TypeAppendTextTool, TypeInsertTextTool, TypeReplaceTextTool, TypeClearTextTool):

**Parameter names** (in all 4 tools):

| Old | New |
|-----|-----|
| `McpToolUtils.requireString(arguments, "element_id")` | `McpToolUtils.requireString(arguments, "node_id")` |
| `"Parameter 'element_id' must be non-empty"` | `"Parameter 'node_id' must be non-empty"` |
| `putJsonObject("element_id")` | `putJsonObject("node_id")` |
| `required = listOf("element_id", ...)` | `required = listOf("node_id", ...)` |

**Schema descriptions** (in all 4 tools):

| Old | New |
|-----|-----|
| `"Target element ID to type into"` | `"Target node ID to type into"` |
| `"Target element ID to clear"` | `"Target node ID to clear"` |
| `"Target element ID"` | `"Target node ID"` |

**Local variable renames** (4 declarations + all usages):

| Old | New |
|-----|-----|
| `val elementId = McpToolUtils.requireString(arguments, "node_id")` | `val nodeId = McpToolUtils.requireString(arguments, "node_id")` |
| All subsequent `elementId` references in each tool's execute method | `nodeId` |

**Exception references:**

| Old | New |
|-----|-----|
| `McpToolException.ElementNotFound` (all occurrences) | `McpToolException.NodeNotFound` |

**User-facing output/error messages** — replace all `element '$elementId'` with `node '$nodeId'` and all `element '$nodeId'` with `node '$nodeId'` in string templates. Key patterns:

| Old Pattern | New Pattern |
|-------------|-------------|
| `"...focusing element '$elementId'..."` | `"...focusing node '$nodeId'..."` |
| `"...cursor in element '$elementId'..."` | `"...cursor in node '$nodeId'..."` |
| `"...chars on element '$elementId'"` | `"...chars on node '$nodeId'"` |
| `"...at end of element '$elementId'..."` | `"...at end of node '$nodeId'..."` |
| `"...in element '$elementId'..."` (all occurrences) | `"...in node '$nodeId'..."` |
| `"...from element '$elementId'"` | `"...from node '$nodeId'"` |
| `"...text in element '$elementId'..."` | `"...text in node '$nodeId'..."` |
| `"...on element '$elementId'..."` | `"...on node '$nodeId'..."` |
| `"Text cleared from element '$elementId'..."` | `"Text cleared from node '$nodeId'..."` |

**Log messages:**

| Old | New |
|-----|-----|
| `"type_append_text: typed ${text.length} chars on element '$elementId'"` | `"type_append_text: typed ${text.length} chars on node '$nodeId'"` |
| `"type_insert_text: typed ${text.length} chars at offset $offset on '$elementId'"` | `"type_insert_text: typed ${text.length} chars at offset $offset on '$nodeId'"` |
| `"type_clear_text: cleared text on element '$elementId'"` | `"type_clear_text: cleared text on node '$nodeId'"` |

**Additional error messages** (in `awaitInputConnectionReady`, `TypeEnterKeyTool`, `TypeDeleteKeyTool`, `TypeKeyTool`):

| Old | New |
|-----|-----|
| `"The element may not be an editable text field."` | `"The node may not be an editable text field."` |
| `"No focused element found for ENTER key"` | `"No focused node found for ENTER key"` |
| `"No focused editable element found for DEL key"` | `"No focused editable node found for DEL key"` |
| `"No focused editable element found for key input"` | `"No focused editable node found for key input"` |

**KDoc:**

| Old | New |
|-----|-----|
| `@param elementId The element ID (for error message).` | `@param nodeId The node ID (for error message).` |
| `* 1. Click element_id to focus` | `* 1. Click node_id to focus` |
| `* Polls for TypeInputController readiness after clicking an element to focus it.` | `* Polls for TypeInputController readiness after clicking a node to focus it.` |

Also rename the `elementId` parameter in the shared `awaitInputConnectionReady` function to `nodeId`.

**Definition of Done**:
- [x] All 4 tool classes updated
- [x] All parameter names, variable names, messages, KDoc updated
- [x] All `McpToolException.ElementNotFound` → `NodeNotFound`

---

### Task 5: Rename in support files

**Action 5.1** — `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt` — modify:

| Old | New |
|-----|-----|
| `fun buildElementJson(element: ElementInfo): JsonObject =` | `fun buildNodeJson(element: ElementInfo): JsonObject =` |
| `put("element_id", element.id)` | `put("node_id", element.id)` |
| `* Builds a JSON object representation of an [ElementInfo]...` (KDoc) | `* Builds a JSON object representation of an [ElementInfo] for MCP node responses.` |
| `* Shared by [FindElementsTool]...` (KDoc link) | `* Shared by [FindNodesTool]...` |
| `* and [WaitForElementTool]...` (KDoc link) | `* and [WaitForNodeTool]...` |
| `to ensure consistent element serialization across tools.` | `to ensure consistent node serialization across tools.` |

Also update all callers of `buildElementJson` → `buildNodeJson`:
- `NodeActionTools.kt` (FindNodesTool)
- `UtilityTools.kt` (WaitForNodeTool)

**Action 5.2** — `app/src/main/kotlin/.../services/accessibility/CompactTreeFormatter.kt` — modify:

| Old | New |
|-----|-----|
| `"note:offscreen items require scroll_to_element before interaction"` (constant `NOTE_LINE_OFFSCREEN_HINT`) | `"note:offscreen items require scroll_to_node before interaction"` |
| `"element_id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}..."` (constant `HEADER`) | `"node_id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}..."` |
| KDoc line: `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags` | `node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags` |
| KDoc line: `note:offscreen items require scroll_to_element before interaction` | `note:offscreen items require scroll_to_node before interaction` |

**Action 5.3** — `app/src/main/kotlin/.../mcp/tools/ScreenIntrospectionTools.kt` — modify:

| Old | New |
|-----|-----|
| `"and a compact UI element list"` | `"and a compact UI node list"` |
| `"${toolNamePrefix}get_element_details to retrieve full values"` | `"${toolNamePrefix}get_node_details to retrieve full values"` |
| `"only request the screenshot when the element"` | `"only request the screenshot when the node"` |
| `"Only request when the UI element list is not sufficient."` | `"Only request when the UI node list is not sufficient."` |

**Action 5.4** — `app/src/main/kotlin/.../services/screencapture/ScreenshotAnnotator.kt` — modify (KDoc only):

| Old | New |
|-----|-----|
| `* Annotates a screenshot bitmap with bounding boxes and element ID labels` | `* Annotates a screenshot bitmap with bounding boxes and node ID labels` |
| `* - Labels show the element ID hash (without \`node_\` prefix)` | `* - Labels show the node ID hash (without \`node_\` prefix)` |
| `* Extracts the display label from an element ID by stripping the \`node_\` prefix.` | `* Extracts the display label from a node ID by stripping the \`node_\` prefix.` |

Also rename the `extractLabel` parameter and local variable:

| Old | New |
|-----|-----|
| `internal fun extractLabel(elementId: String): String =` | `internal fun extractLabel(nodeId: String): String =` |
| `if (elementId.startsWith(NODE_ID_PREFIX))` | `if (nodeId.startsWith(NODE_ID_PREFIX))` |
| `elementId.removePrefix(NODE_ID_PREFIX)` | `nodeId.removePrefix(NODE_ID_PREFIX)` |
| `elementId` (else branch return) | `nodeId` |

**Definition of Done**:
- [x] All 4 support files updated
- [x] No remaining `element` in MCP-facing identifiers or output fields

---

### Task 6: Update callers and project config references

**Action 6.1** — `app/src/main/kotlin/.../services/mcp/McpServerService.kt` — modify:

| Old | New |
|-----|-----|
| `import ...registerElementActionTools` | `import ...registerNodeActionTools` |
| `registerElementActionTools(` | `registerNodeActionTools(` |

**Action 6.2** — `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` — modify:

| Old | New |
|-----|-----|
| `import ...registerElementActionTools` | `import ...registerNodeActionTools` |
| `registerElementActionTools(` | `registerNodeActionTools(` |

**Action 6.3** — `app/src/main/kotlin/.../ui/screens/settings/McpToolsSettingsScreen.kt` — modify:

| Old | New |
|-----|-----|
| `"Element Actions"` (category name) | `"Node Actions"` |
| `ToolEntry("find_elements", "Find Elements")` | `ToolEntry("find_nodes", "Find Nodes")` |
| `ToolEntry("click_element", "Click Element")` | `ToolEntry("click_node", "Click Node")` |
| `ToolEntry("long_click_element", "Long Click Element")` | `ToolEntry("long_click_node", "Long Click Node")` |
| `ToolEntry("scroll_to_element", "Scroll to Element")` | `ToolEntry("scroll_to_node", "Scroll to Node")` |
| `ToolEntry("wait_for_element", "Wait for Element")` | `ToolEntry("wait_for_node", "Wait for Node")` |
| `ToolEntry("get_element_details", "Get Element Details")` | `ToolEntry("get_node_details", "Get Node Details")` |

**Action 6.4** — `CLAUDE.md` — modify:

| Old | New |
|-----|-----|
| `ElementActionTools.kt` | `NodeActionTools.kt` |

**Definition of Done**:
- [x] All callers updated to use `registerNodeActionTools`
- [x] McpToolsSettingsScreen.kt tool entries and category name updated
- [x] CLAUDE.md reference updated

---

## User Story 2: Update test files

### Acceptance Criteria
- [x] All test files reference the renamed tools, parameters, and output fields
- [x] Test file names updated to match renamed source files
- [x] Test class names and DisplayName annotations updated
- [x] All `"element_id"` → `"node_id"` in test argument maps and assertions

---

### Task 7: Rename `ElementActionToolsTest.kt` → `NodeActionToolsTest.kt`

**Action 7.1** — Rename file:
```
git mv app/src/test/kotlin/.../mcp/tools/ElementActionToolsTest.kt app/src/test/kotlin/.../mcp/tools/NodeActionToolsTest.kt
```

**Action 7.2** — Apply renames in `NodeActionToolsTest.kt`:

| Old | New |
|-----|-----|
| `@DisplayName("ElementActionTools")` | `@DisplayName("NodeActionTools")` |
| `class ElementActionToolsTest {` | `class NodeActionToolsTest {` |
| `@DisplayName("FindElementsTool")` | `@DisplayName("FindNodesTool")` |
| `inner class FindElementsToolTests {` | `inner class FindNodesToolTests {` |
| `FindElementsTool(` | `FindNodesTool(` |
| `@DisplayName("ClickElementTool")` | `@DisplayName("ClickNodeTool")` |
| `inner class ClickElementToolTests {` | `inner class ClickNodeToolTests {` |
| `ClickElementTool(` | `ClickNodeTool(` |
| `@DisplayName("LongClickElementTool")` | `@DisplayName("LongClickNodeTool")` |
| `inner class LongClickElementToolTests {` | `inner class LongClickNodeToolTests {` |
| `LongClickElementTool(` | `LongClickNodeTool(` |
| `@DisplayName("TapElementTool")` (from Plan 35) | `@DisplayName("TapNodeTool")` |
| `inner class TapElementToolTests {` (from Plan 35) | `inner class TapNodeToolTests {` |
| `TapElementTool(` (from Plan 35) | `TapNodeTool(` |
| `@DisplayName("ScrollToElementTool")` | `@DisplayName("ScrollToNodeTool")` |
| `inner class ScrollToElementToolTests {` | `inner class ScrollToNodeToolTests {` |
| `ScrollToElementTool(` | `ScrollToNodeTool(` |
| `put("element_id", ...)` (all occurrences) | `put("node_id", ...)` |
| `parsed["elements"]!!.jsonArray` | `parsed["nodes"]!!.jsonArray` |
| `elements[0].jsonObject["element_id"]` | `elements[0].jsonObject["node_id"]` |
| `McpToolException.ElementNotFound` | `McpToolException.NodeNotFound` |

**Test method name renames** (all test names containing `element`):

| Old | New |
|-----|-----|
| `` `returns matching elements...` `` | `` `returns matching nodes...` `` |
| `` `throws error for missing element_id` `` | `` `throws error for missing node_id` `` |
| `` `clicks element successfully` `` | `` `clicks node successfully` `` |
| `` `throws error when element not found` `` (all occurrences) | `` `throws error when node not found` `` |
| `` `throws error when element not clickable` `` | `` `throws error when node not clickable` `` |
| `` `long-clicks element successfully` `` | `` `long-clicks node successfully` `` |
| `` `returns immediately when element already visible` `` | `` `returns immediately when node already visible` `` |
| `` `scrolls to element in non-primary window` `` | `` `scrolls to node in non-primary window` `` |

**Definition of Done**:
- [x] File renamed
- [x] All class names, DisplayNames, tool instantiations, parameter keys, exception references updated
- [x] All test method names containing `element` renamed to use `node`

---

### Task 8: Rename `GetElementDetailsToolTest.kt` → `GetNodeDetailsToolTest.kt`

**Action 8.1** — Rename file:
```
git mv app/src/test/kotlin/.../mcp/tools/GetElementDetailsToolTest.kt app/src/test/kotlin/.../mcp/tools/GetNodeDetailsToolTest.kt
```

**Action 8.2** — Apply renames in `GetNodeDetailsToolTest.kt`:

| Old | New |
|-----|-----|
| `class GetElementDetailsToolTest` | `class GetNodeDetailsToolTest` |
| `@DisplayName(...)` references to `GetElementDetailsTool` | `GetNodeDetailsTool` |
| `GetElementDetailsTool(` | `GetNodeDetailsTool(` |
| `"element_ids"` (all occurrences in argument maps) | `"node_ids"` |
| `put("element_ids", ...)` | `put("node_ids", ...)` |
| `assertEquals("element_id\ttext\tdesc", lines[0])` | `assertEquals("node_id\ttext\tdesc", lines[0])` |

**Test method name and @DisplayName renames:**

| Old | New |
|-----|-----|
| All test names containing `element_ids` | Replace with `node_ids` |
| `@DisplayName("returns text and desc for found elements")` | `@DisplayName("returns text and desc for found nodes")` |
| `@DisplayName("returns not_found for missing element IDs")` | `@DisplayName("returns not_found for missing node IDs")` |

**Definition of Done**:
- [x] File renamed
- [x] All references, @DisplayNames, and method names updated

---

### Task 9: Update remaining unit test files

**Action 9.1** — `app/src/test/kotlin/.../mcp/tools/UtilityToolsTest.kt` — modify:

| Old | New |
|-----|-----|
| `@DisplayName("WaitForElementTool")` | `@DisplayName("WaitForNodeTool")` |
| `inner class WaitForElementToolTests {` | `inner class WaitForNodeToolTests {` |
| `WaitForElementTool(` | `WaitForNodeTool(` |
| `parsed["element"]` | `parsed["node"]` |
| `?.get("element_id")` | `?.get("node_id")` |

Test method name renames:

| Old | New |
|-----|-----|
| `` `finds element on first attempt` `` | `` `finds node on first attempt` `` |
| `` `finds element after multiple poll attempts` `` | `` `finds node after multiple poll attempts` `` |
| `` `returns timed out message when element never found` `` | `` `returns timed out message when node never found` `` |

**Action 9.2** — `app/src/test/kotlin/.../mcp/tools/ScreenIntrospectionToolsTest.kt` — modify:

| Old | New |
|-----|-----|
| `"note:offscreen items require scroll_to_element before interaction\n"` | `"note:offscreen items require scroll_to_node before interaction\n"` |
| `"element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n"` | `"node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n"` |

**Action 9.3** — `app/src/test/kotlin/.../services/accessibility/CompactTreeFormatterTest.kt` — modify:

| Old | New |
|-----|-----|
| `assertEquals("element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags", ...)` | `assertEquals("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags", ...)` |

Also update any assertion checking `NOTE_LINE_OFFSCREEN_HINT` content.

**Action 9.4** — `app/src/test/kotlin/.../mcp/tools/TextInputToolsTest.kt` — modify:

Replace all occurrences (using `replace_all` semantics):

| Old | New |
|-----|-----|
| `put("element_id",` | `put("node_id",` |
| `McpToolException.ElementNotFound` | `McpToolException.NodeNotFound` |

**Test method name renames:**

| Old | New |
|-----|-----|
| All test names containing `element_id` | Replace with `node_id` |
| `` `appends text to element` `` | `` `appends text to node` `` |
| `` `clears text from element` `` | `` `clears text from node` `` |

**Definition of Done**:
- [x] All 4 unit test files updated
- [x] No remaining `element_id`, `element_ids`, or `element` in MCP-facing test method names or argument maps

---

### Task 10: Rename `ElementActionIntegrationTest.kt` → `NodeActionIntegrationTest.kt`

**Action 10.1** — Rename file:
```
git mv app/src/test/kotlin/.../integration/ElementActionIntegrationTest.kt app/src/test/kotlin/.../integration/NodeActionIntegrationTest.kt
```

**Action 10.2** — Apply renames in `NodeActionIntegrationTest.kt`:

| Old | New |
|-----|-----|
| `@DisplayName("Element Action Integration Tests")` | `@DisplayName("Node Action Integration Tests")` |
| `class ElementActionIntegrationTest {` | `class NodeActionIntegrationTest {` |
| `name = "android_find_elements"` | `name = "android_find_nodes"` |
| `name = "android_click_element"` | `name = "android_click_node"` |
| `arguments = mapOf("element_id" to ...)` (all) | `arguments = mapOf("node_id" to ...)` |
| `parsed["elements"]!!.jsonArray` (all) | `parsed["nodes"]!!.jsonArray` |
| `elements[0].jsonObject["element_id"]` (all) | `elements[0].jsonObject["node_id"]` |

**Test method name renames:**

| Old | New |
|-----|-----|
| `` `find_elements returns matching elements...` `` | `` `find_nodes returns matching nodes...` `` |
| `` `click_element with valid node_id...` `` | `` `click_node with valid node_id...` `` |
| `` `click_element with non-existent node_id returns element not found error` `` | `` `click_node with non-existent node_id returns node not found error` `` |
| `` `find_elements finds element in system dialog...` `` | `` `find_nodes finds node in system dialog...` `` |
| `` `click_element clicks element in non-primary...` `` | `` `click_node clicks node in non-primary...` `` |

Also add integration tests for `tap_node` (from Plan 35's integration tests — update tool name to `android_tap_node` and parameter to `node_id`).

**Definition of Done**:
- [x] File renamed
- [x] All tool names, parameter keys, assertion keys, test method names updated

---

### Task 11: Update remaining integration test files

**Action 11.1** — `app/src/test/kotlin/.../integration/McpProtocolIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `"android_find_elements"` | `"android_find_nodes"` |
| `"android_click_element"` | `"android_click_node"` |
| `"android_long_click_element"` | `"android_long_click_node"` |
| `"android_scroll_to_element"` | `"android_scroll_to_node"` |
| `"android_wait_for_element"` | `"android_wait_for_node"` |
| `"android_get_element_details"` | `"android_get_node_details"` |

Also add `"android_tap_node"` if `"android_tap_element"` was added by Plan 35.

**Action 11.2** — `app/src/test/kotlin/.../integration/ErrorHandlingIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `name = "android_click_element"` (all) | `name = "android_click_node"` |
| `arguments = mapOf("element_id" to ...)` (all) | `arguments = mapOf("node_id" to ...)` |
| `name = "android_wait_for_element"` | `name = "android_wait_for_node"` |

**Test method name renames:**

| Old | New |
|-----|-----|
| `` `element not found returns error result` `` | `` `node not found returns error result` `` |
| `` `wait_for_element timeout...` `` | `` `wait_for_node timeout...` `` |

**Action 11.3** — `app/src/test/kotlin/.../integration/UtilityIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `name = "android_get_element_details"` | `name = "android_get_node_details"` |
| `arguments = mapOf("element_ids" to ...)` | `arguments = mapOf("node_ids" to ...)` |
| `assertEquals("element_id\ttext\tdesc", lines[0])` | `assertEquals("node_id\ttext\tdesc", lines[0])` |
| `name = "android_wait_for_element"` | `name = "android_wait_for_node"` |
| `parsed["element"]` | `parsed["node"]` |
| `?.get("element_id")` | `?.get("node_id")` |

**Test method name renames:**

| Old | New |
|-----|-----|
| `` `get_element_details returns TSV with element_id header...` `` | `` `get_node_details returns TSV with node_id header...` `` |
| `` `wait_for_element success returns element_id in response` `` | `` `wait_for_node success returns node_id in response` `` |

**Action 11.4** — `app/src/test/kotlin/.../integration/ScreenIntrospectionIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `"note:offscreen items require scroll_to_element before interaction"` | `"note:offscreen items require scroll_to_node before interaction"` |
| `"element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags"` | `"node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags"` |

**Action 11.5** — `app/src/test/kotlin/.../integration/TextInputIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `"element_id" to ...` (all argument map entries) | `"node_id" to ...` |

**Test method name renames:**

| Old | New |
|-----|-----|
| `` `type_append_text with element_id returns success...` `` | `` `type_append_text with node_id returns success...` `` |

**Action 11.6** — `app/src/test/kotlin/.../integration/ToolPermissionsIntegrationTest.kt` — modify:

| Old | New |
|-----|-----|
| `"find_elements"` (in tool name lists) | `"find_nodes"` |
| `"click_element"` | `"click_node"` |
| `"long_click_element"` | `"long_click_node"` |
| `"scroll_to_element"` | `"scroll_to_node"` |
| `"wait_for_element"` | `"wait_for_node"` |
| `"get_element_details"` | `"get_node_details"` |

**Definition of Done**:
- [x] All 6 integration test files updated
- [x] No remaining `element_id`, `element_ids`, or old tool names in any integration test

---

## User Story 3: Update E2E tests and documentation

### Acceptance Criteria
- [x] All E2E test files use `node` terminology
- [x] `docs/MCP_TOOLS.md` fully updated with new tool names, parameters, examples
- [x] `docs/PROJECT.md` fully updated
- [x] Tool count in docs remains accurate

---

### Task 12: Update E2E test files

**Action 12.1** — `e2e-tests/src/test/kotlin/.../e2e/E2ECalculatorTest.kt` — modify:

| Old | New |
|-----|-----|
| `"note:offscreen items require scroll_to_element before interaction"` | `"note:offscreen items require scroll_to_node before interaction"` |
| `"${TOOL_PREFIX}click_element"` (all) | `"${TOOL_PREFIX}click_node"` |
| `mapOf("element_id" to ...)` (all) | `mapOf("node_id" to ...)` |
| `"${TOOL_PREFIX}find_elements"` | `"${TOOL_PREFIX}find_nodes"` |
| `innerJson["elements"]?.jsonArray` / `parsed["elements"]!!.jsonArray` | `innerJson["nodes"]?.jsonArray` / `parsed["nodes"]!!.jsonArray` |
| `elements[0].jsonObject["element_id"]` | `elements[0].jsonObject["node_id"]` |
| Comment: `Returns the element_id of the first match` | `Returns the node_id of the first match` |
| Comment: `The find_elements tool returns...` | `The find_nodes tool returns...` |
| `findElementWithRetry` (helper function name) | `findNodeWithRetry` |

**Action 12.2** — `e2e-tests/src/test/kotlin/.../e2e/E2EErrorHandlingTest.kt` — modify:

| Old | New |
|-----|-----|
| `"${TOOL_PREFIX}click_element"` (all) | `"${TOOL_PREFIX}click_node"` |
| `mapOf("element_id" to ...)` (all) | `mapOf("node_id" to ...)` |
| Test method: `` `click on non-existent element returns error result` `` | `` `click on non-existent node returns error result` `` |
| Comment/println: `click_element attempt` | `click_node attempt` |
| `"Error should mention the element ID, got: $text"` | `"Error should mention the node ID, got: $text"` |

**Action 12.3** — `e2e-tests/src/test/kotlin/.../e2e/E2EScreenshotTest.kt` — modify:

| Old | New |
|-----|-----|
| `"note:offscreen items require scroll_to_element before interaction"` | `"note:offscreen items require scroll_to_node before interaction"` |

**Definition of Done**:
- [x] All 3 E2E test files updated

---

### Task 13: Update `docs/MCP_TOOLS.md`

**File**: `docs/MCP_TOOLS.md` — modify

**Action 13.1** — Global replacements throughout the entire file:

| Old | New |
|-----|-----|
| `android_find_elements` | `android_find_nodes` |
| `android_click_element` | `android_click_node` |
| `android_long_click_element` | `android_long_click_node` |
| `android_tap_element` (from Plan 35) | `android_tap_node` |
| `android_scroll_to_element` | `android_scroll_to_node` |
| `android_wait_for_element` | `android_wait_for_node` |
| `android_get_element_details` | `android_get_node_details` |
| `find_elements` (bare tool name references) | `find_nodes` |
| `click_element` (bare tool name references) | `click_node` |
| `long_click_element` (bare) | `long_click_node` |
| `tap_element` (bare) | `tap_node` |
| `scroll_to_element` (bare) | `scroll_to_node` |
| `wait_for_element` (bare) | `wait_for_node` |
| `get_element_details` (bare) | `get_node_details` |
| `"element_id"` (parameter name in schemas, examples, descriptions) | `"node_id"` |
| `"element_ids"` | `"node_ids"` |
| `element_id` (in TSV header examples) | `node_id` |
| Section headers: `### \`android_find_elements\`` etc. | Update to new names |

Also update JSON output field names in examples:
| Old | New |
|-----|-----|
| `"elements"` (JSON response key in find_nodes examples) | `"nodes"` |
| `"element"` (JSON response key in wait_for_node examples) | `"node"` |

Also update the tool name prefix examples:
| Old | New |
|-----|-----|
| `android_pixel7_find_elements` | `android_pixel7_find_nodes` |
| `android_work_phone_find_elements` | `android_work_phone_find_nodes` |

Update the overview table row:
| Old | New |
|-----|-----|
| Element Actions row with old tool names | Node Actions with new tool names |

**Definition of Done**:
- [x] Every occurrence of old tool names replaced
- [x] Every `element_id`/`element_ids` parameter reference replaced
- [x] Section headers updated
- [x] Examples and schemas updated
- [x] Tool count remains accurate

---

### Task 14: Update `docs/PROJECT.md` and `docs/ARCHITECTURE.md`

**Action 14.1** — `docs/PROJECT.md` — modify:

| Old | New |
|-----|-----|
| `ElementActionTools.kt` (file reference) | `NodeActionTools.kt` |
| `android_find_elements` | `android_find_nodes` |
| `android_click_element` | `android_click_node` |
| `android_long_click_element` | `android_long_click_node` |
| `android_scroll_to_element` | `android_scroll_to_node` |
| `android_wait_for_element` | `android_wait_for_node` |
| `android_get_element_details` | `android_get_node_details` |
| `element_id` (parameter references in tool tables) | `node_id` |
| `element_ids` (parameter references) | `node_ids` |
| `wait_for_element` (bare) | `wait_for_node` |
| `"element ID labels"` (in prose descriptions) | `"node ID labels"` |
| `"Scroll to make element visible"` (in tool tables) | `"Scroll to make node visible"` |
| `"if element not found"` / `"or element not clickable"` (in prose) | `"if node not found"` / `"or node not clickable"` |
| `"element not found"` (in test scope descriptions) | `"node not found"` |
| `### 3. Element Action Tools (4 tools)` (section header) | `### 3. Node Action Tools (4 tools)` |
| `"Find UI elements by criteria"` (tool description in table) | `"Find UI nodes by criteria"` |
| `"Wait until element appears"` (tool description in table) | `"Wait until node appears"` |
| `"Get full untruncated text/desc by element_ids"` (tool description in table) | `"Get full untruncated text/desc by node_ids"` |

**Action 14.2** — `docs/ARCHITECTURE.md` — modify:

| Old | New |
|-----|-----|
| `"scroll-to-element"` (in Cross-Window Action Execution section) | `"scroll-to-node"` |

**Definition of Done**:
- [x] All tool name and parameter references updated in PROJECT.md
- [x] File reference updated to `NodeActionTools.kt`
- [x] ARCHITECTURE.md `scroll-to-element` reference updated

---

## User Story 4: Quality gates and final verification

### Acceptance Criteria
- [x] Linting passes (`make lint`)
- [x] All unit tests pass (`make test-unit`)
- [x] Build succeeds without errors (`./gradlew build`)
- [x] No remaining `element_id`, `element_ids`, or old tool names in any modified file

---

### Task 15: Run quality gates

**Action 15.1** — Run linting: `make lint`
**Action 15.2** — Run all unit tests: `make test-unit`
**Action 15.3** — Run build: `./gradlew build`

**Definition of Done**:
- [x] Linting passes with no warnings or errors
- [x] All tests pass (no regressions)
- [x] Build succeeds

---

### Task 16: Final verification — double check everything from the ground up

**Action 16.1** — Verify no remaining `element_id`, `element_ids`, or old tool names in MCP-facing contexts:
```bash
grep -rn '"element_id"' app/src/main/ app/src/test/ e2e-tests/src/
grep -rn '"element_ids"' app/src/main/ app/src/test/ e2e-tests/src/
grep -rn '"elements"' app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/
grep -rn 'put("element"' app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/
grep -rn 'ElementNotFound' app/src/main/ app/src/test/
grep -rn 'buildElementJson' app/src/main/ app/src/test/
grep -rn 'find_elements\|click_element\|long_click_element\|tap_element\|scroll_to_element\|wait_for_element\|get_element_details' app/src/main/ app/src/test/ e2e-tests/src/ docs/
```
All commands must return empty (no matches).

**Action 16.2** — Verify all renamed files exist at new locations:
- `app/src/main/kotlin/.../mcp/tools/NodeActionTools.kt`
- `app/src/test/kotlin/.../mcp/tools/NodeActionToolsTest.kt`
- `app/src/test/kotlin/.../mcp/tools/GetNodeDetailsToolTest.kt`
- `app/src/test/kotlin/.../integration/NodeActionIntegrationTest.kt`

**Action 16.3** — Verify old files no longer exist:
- `ElementActionTools.kt`, `ElementActionToolsTest.kt`, `GetElementDetailsToolTest.kt`, `ElementActionIntegrationTest.kt`

**Action 16.4** — Verify `McpToolException.NodeNotFound` is used everywhere (no `ElementNotFound` references)

**Action 16.5** — Verify `registerNodeActionTools` is called in `McpServerService.kt` and `McpIntegrationTestHelper.kt`

**Action 16.6** — Verify no files outside the plan scope were modified

**Definition of Done**:
- [x] All grep searches return empty (no old names remain)
- [x] All renamed files exist at new locations
- [x] Old files no longer exist
- [x] No files outside the plan scope were modified
- [x] All quality gates pass
