# Plan 15: Compact `get_screen_state` Tool + `get_element_details` Helper

## Overview

Replace the four separate screen introspection tools (`get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info`) with a single consolidated `get_screen_state` tool that returns:
- A note explaining that structural-only nodes are omitted
- App metadata (package, activity) as a header line
- Screen info (dimensions, density, orientation) as a header line
- A compact **flat** TSV-formatted, filtered list of UI elements (no depth/tree structure)
- Optionally, a low-resolution screenshot (700px longest-side cap)

Additionally, add a new `get_element_details` helper tool that returns full (untruncated) `text` and `desc` for a list of element IDs, allowing LLMs to lazily fetch long descriptions only when needed.

---

## Design Decisions (Agreed with User)

1. **Combined tool `get_screen_state`** replaces `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info`
2. **Compact flat TSV format** — NO depth column, NO tree structure:
   ```
   note:structural-only nodes are omitted from the tree
   app:com.example.myapp activity:.MainActivity
   screen:1080x2400 density:420 orientation:portrait
   id	class	text	desc	res_id	bounds	flags
   node_b2c4	ScrollView	-	-	main_scroll	0,100,1080,2300	vsen
   node_c3d5	Button	OK	Confirm act...truncated	btn_ok	100,200,300,260	vcen
   node_d4e6	EditText	Hello	-	input_name	100,280,980,340	vefen
   ```
3. **No depth column**: All tool interactions use element IDs or coordinates; tree hierarchy adds little value for LLM decision-making. Bounds provide spatial layout. The note line informs the LLM that structural containers are omitted.
4. **Flags**: `v`=visible, `c`=clickable, `l`=long-clickable, `f`=focusable, `s`=scrollable, `e`=editable, `n`=enabled
5. **Class names**: stripped to simple name (e.g., `android.widget.Button` → `Button`)
6. **resourceId**: kept as-is (full form with package prefix). Null/empty → `-`.
7. **Node filtering**: skip nodes where ALL of: no text, no contentDescription, no resourceId, not clickable, not longClickable, not scrollable, not editable. Children of filtered nodes are still walked (they may independently pass the filter). Non-visible nodes that pass the filter ARE included (needed for `scroll_to_element`).
8. **Text/desc sanitization**: tabs and newlines replaced with spaces. Null/empty → `-`.
9. **Text/desc truncation**: Both `text` and `desc` columns truncated to 100 characters. If truncated, suffix `...truncated` is appended (so max display length = 112 chars). The `get_element_details` tool provides untruncated values.
10. **Screenshot**: optional via `include_screenshot` boolean param (default `false`). Hardcoded 700px longest-side cap (both `maxWidth=700` and `maxHeight=700`). Quality kept at default (80). Tool description tells LLM to only request screenshot when layout isn't sufficient.
11. **`find_elements`**: keeps current JSON output format (no change).
12. **Old tools removed**: `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` — handler classes, registrations, unit tests, integration tests all removed.
13. **New tool `get_element_details`**: accepts a list of element IDs (as JSON array of strings), returns TSV with full untruncated `text` and `desc` for each. Registered in UtilityTools. Tool description: "Retrieve full untruncated text and description for elements by ID. Use after get_screen_state when text or desc was truncated."

---

## User Story 1: Create CompactTreeFormatter

**As a** MCP server operator, **I want** the accessibility tree to be output in a compact flat TSV format **so that** LLM token consumption is dramatically reduced.

### Acceptance Criteria
- [ ] `CompactTreeFormatter` class exists and converts `AccessibilityNodeData` + metadata into the agreed compact format
- [ ] Noise nodes are filtered out; their children are still walked
- [ ] Class names are stripped to simple name
- [ ] Tabs and newlines in text/contentDescription are replaced with spaces
- [ ] Null/empty text/desc/resourceId fields render as `-`
- [ ] Text and desc truncated to 100 chars with `...truncated` suffix when exceeded
- [ ] Flags column uses the agreed single-char encoding
- [ ] No depth column — flat output
- [ ] Note line is included as first line
- [ ] Unit tests pass for `CompactTreeFormatter`
- [ ] `./gradlew ktlintCheck` passes
- [ ] `./gradlew detekt` passes

---

### Task 1.1: Create `CompactTreeFormatter` class

**Definition of Done**: A new `CompactTreeFormatter` class in `services/accessibility/` that formats tree + metadata into the agreed compact string.

#### Action 1.1.1: Create `CompactTreeFormatter.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt` (NEW)

**What to implement**:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject

/**
 * Formats an [AccessibilityNodeData] tree into a compact flat TSV representation
 * optimized for LLM token efficiency.
 *
 * Output format:
 * - Line 1: `note:structural-only nodes are omitted from the tree`
 * - Line 2: `app:<package> activity:<activity>`
 * - Line 3: `screen:<w>x<h> density:<dpi> orientation:<orientation>`
 * - Line 4: TSV header: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
 * - Lines 5+: one TSV row per kept node (flat, no depth)
 *
 * Nodes are filtered: a node is KEPT if ANY of:
 * - has non-null, non-empty text
 * - has non-null, non-empty contentDescription
 * - has non-null, non-empty resourceId
 * - is clickable
 * - is longClickable
 * - is scrollable
 * - is editable
 *
 * Filtered nodes are skipped in output, but their children are still
 * walked and may appear if they independently pass the filter.
 */
class CompactTreeFormatter @Inject constructor() {

    /**
     * Formats the full screen state as a compact string.
     */
    fun format(
        tree: AccessibilityNodeData,
        packageName: String,
        activityName: String,
        screenInfo: ScreenInfo,
    ): String { ... }

    /**
     * Determines whether a node should be included in the compact output.
     */
    internal fun shouldKeepNode(node: AccessibilityNodeData): Boolean { ... }

    /**
     * Strips a fully-qualified class name to its simple name.
     * e.g., "android.widget.Button" → "Button"
     * Returns "-" if null or empty.
     */
    internal fun simplifyClassName(className: String?): String { ... }

    /**
     * Sanitizes and truncates text for TSV output.
     * 1. Replaces tabs, newlines, carriage returns with spaces.
     * 2. Trims whitespace.
     * 3. Returns "-" if null, empty, or whitespace-only after sanitization.
     * 4. Truncates to [MAX_TEXT_LENGTH] characters with "...truncated" suffix if exceeded.
     */
    internal fun sanitizeText(text: String?): String { ... }

    /**
     * Sanitizes resourceId for TSV output.
     * Returns "-" if null or empty. Does NOT truncate (resourceIds are short).
     * Replaces tabs/newlines with spaces (defensive).
     */
    internal fun sanitizeResourceId(resourceId: String?): String { ... }

    /**
     * Builds the flags string for a node.
     * Order: v, c, l, f, s, e, n
     */
    internal fun buildFlags(node: AccessibilityNodeData): String { ... }

    companion object {
        const val COLUMN_SEPARATOR = "\t"
        const val NULL_VALUE = "-"
        const val MAX_TEXT_LENGTH = 100
        const val TRUNCATION_SUFFIX = "...truncated"
        const val NOTE_LINE = "note:structural-only nodes are omitted from the tree"
        const val HEADER = "id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}desc${COLUMN_SEPARATOR}res_id${COLUMN_SEPARATOR}bounds${COLUMN_SEPARATOR}flags"
    }
}
```

**Key implementation details**:

- `format()` uses `StringBuilder`. Appends note line, metadata lines, header, then walks tree recursively.
- Recursive walk: for each node, check `shouldKeepNode()`. If kept, append a TSV row. Always recurse into `node.children` regardless of whether the node itself is kept.
- `shouldKeepNode()` returns `true` if ANY of: text non-null/non-empty, contentDescription non-null/non-empty, resourceId non-null/non-empty, clickable, longClickable, scrollable, editable.
- `simplifyClassName()`: takes `className`, returns substring after last `.`, or `-` if null/empty.
- `sanitizeText()`: replaces `\t` and `\n` and `\r` with space, trims, returns `-` if null/empty after sanitization. If length exceeds `MAX_TEXT_LENGTH`, truncates to `MAX_TEXT_LENGTH` chars and appends `TRUNCATION_SUFFIX`.
- `sanitizeResourceId()`: replaces `\t` and `\n` and `\r` with space, trims, returns `-` if null/empty. No truncation.
- `buildFlags()`: concatenates single chars in order: `v` if visible, `c` if clickable, `l` if longClickable, `f` if focusable, `s` if scrollable, `e` if editable, `n` if enabled. E.g., a visible, clickable, enabled button → `"vcn"`.
- Bounds formatted as `"${left},${top},${right},${bottom}"`.

---

### Task 1.2: Write unit tests for `CompactTreeFormatter`

**Definition of Done**: Comprehensive unit tests covering formatting, filtering, sanitization, class name stripping, flags, and truncation.

#### Action 1.2.1: Create `CompactTreeFormatterTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt` (NEW)

**Test cases to implement**:

1. **`format produces note line as first line`**: First line is `note:structural-only nodes are omitted from the tree`.
2. **`format produces correct metadata lines`**: Second line is `app:<pkg> activity:<act>`, third is `screen:<w>x<h> density:<dpi> orientation:<orient>`.
3. **`format produces correct header line`**: Fourth line is the tab-separated header (no depth column).
4. **`format outputs kept nodes as flat TSV rows`**: A node with text/resourceId/clickable appears as a flat row (no depth prefix).
5. **`format filters out noise nodes`**: A node with no text, no contentDescription, no resourceId, not clickable, not longClickable, not scrollable, not editable is NOT in output.
6. **`format includes children of filtered nodes`**: Parent is filtered, child passes filter and appears in output.
7. **`shouldKeepNode returns true for node with text`**
8. **`shouldKeepNode returns true for node with contentDescription`**
9. **`shouldKeepNode returns true for node with resourceId`**
10. **`shouldKeepNode returns true for clickable node`**
11. **`shouldKeepNode returns true for longClickable node`**
12. **`shouldKeepNode returns true for scrollable node`**
13. **`shouldKeepNode returns true for editable node`**
14. **`shouldKeepNode returns false for empty non-interactive node`**
15. **`simplifyClassName strips package prefix`**: `"android.widget.Button"` → `"Button"`.
16. **`simplifyClassName handles no package`**: `"Button"` → `"Button"`.
17. **`simplifyClassName returns dash for null`**: `null` → `"-"`.
18. **`simplifyClassName returns dash for empty`**: `""` → `"-"`.
19. **`sanitizeText replaces tabs with spaces`**: `"hello\tworld"` → `"hello world"`.
20. **`sanitizeText replaces newlines with spaces`**: `"hello\nworld"` → `"hello world"`.
21. **`sanitizeText replaces carriage returns with spaces`**: `"hello\rworld"` → `"hello world"`.
22. **`sanitizeText returns dash for null`**: `null` → `"-"`.
23. **`sanitizeText returns dash for empty string`**: `""` → `"-"`.
24. **`sanitizeText returns dash for whitespace-only after sanitization`**: `"\t\n"` → `"-"`.
25. **`sanitizeText truncates long text to 100 chars with suffix`**: 150-char string → first 100 chars + `"...truncated"`.
26. **`sanitizeText does not truncate text at exactly 100 chars`**: 100-char string → unchanged.
27. **`sanitizeText truncates text at 101 chars`**: 101-char string → first 100 chars + `"...truncated"`.
28. **`sanitizeResourceId returns dash for null`**: `null` → `"-"`.
29. **`sanitizeResourceId returns dash for empty`**: `""` → `"-"`.
30. **`sanitizeResourceId does not truncate`**: long resourceId is returned in full.
31. **`buildFlags includes all set flags in correct order`**: All flags true → `"vclfsen"`.
32. **`buildFlags includes only set flags`**: visible + clickable + enabled → `"vcn"`.
33. **`buildFlags returns empty string when no flags set`**: All false → `""`.
34. **`format includes non-visible nodes that pass filter`**: A button with text but `visible=false` still appears (without `v` in flags).
35. **`format handles tree with all nodes filtered`**: Only note + metadata + header, no data rows.
36. **`format bounds formatted correctly`**: Bounds `BoundsData(10, 20, 300, 400)` → `"10,20,300,400"`.

**Run**: `./gradlew :app:testDebugUnitTest --tests "*.CompactTreeFormatterTest"`

---

## User Story 2: Implement `get_screen_state` Tool and Remove Old Tools

**As a** MCP client (LLM), **I want** a single `get_screen_state` tool that returns all screen information in one call **so that** I can understand the screen with fewer round-trips and lower token usage.

### Acceptance Criteria
- [ ] `get_screen_state` tool is registered and returns compact flat TSV + metadata
- [ ] `include_screenshot` param works: when `true`, response contains both `TextContent` and `ImageContent`
- [ ] Screenshot is capped at 700px longest side
- [ ] Old tools (`get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info`) are removed
- [ ] `registerScreenIntrospectionTools` updated to only register `get_screen_state`
- [ ] `McpToolUtils` has a new helper for mixed content results
- [ ] Unit tests pass for `GetScreenStateHandler`
- [ ] Old unit tests removed
- [ ] `./gradlew ktlintCheck` passes
- [ ] `./gradlew detekt` passes

---

### Task 2.1: Add `textAndImageResult` helper to `McpToolUtils`

**Definition of Done**: A new helper method for creating `CallToolResult` with both `TextContent` and `ImageContent`.

#### Action 2.1.1: Add `textAndImageResult` method to `McpToolUtils`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtils.kt`

**Change**: Add a new method after the existing `imageResult` method (after line 255):

```kotlin
/**
 * Creates a [CallToolResult] containing a [TextContent] item followed by an [ImageContent] item.
 */
fun textAndImageResult(
    text: String,
    imageData: String,
    imageMimeType: String,
): CallToolResult = CallToolResult(
    content = listOf(
        TextContent(text = text),
        ImageContent(data = imageData, mimeType = imageMimeType),
    )
)
```

No other changes to `McpToolUtils`.

---

### Task 2.2: Create `GetScreenStateHandler` and remove old handlers

**Definition of Done**: New `GetScreenStateHandler` implemented. Old four handler classes (`GetAccessibilityTreeHandler`, `CaptureScreenshotHandler`, `GetCurrentAppHandler`, `GetScreenInfoHandler`) deleted. Registration function updated.

#### Action 2.2.1: Rewrite `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**What to remove** (entire classes and their section comments):
- `GetAccessibilityTreeHandler` (lines 23-94, including section comment)
- `CaptureScreenshotHandler` (lines 96-214, including section comment)
- `GetCurrentAppHandler` (lines 216-269, including section comment)
- `GetScreenInfoHandler` (lines 271-316, including section comment)

**What to add** — new `GetScreenStateHandler` class:

```kotlin
/**
 * MCP tool handler for `get_screen_state`.
 *
 * Returns consolidated screen state: app metadata, screen info, and a compact
 * flat TSV-formatted list of UI elements. Optionally includes a low-resolution screenshot.
 *
 * Replaces: get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info.
 */
class GetScreenStateHandler @Inject constructor(
    private val treeParser: AccessibilityTreeParser,
    private val accessibilityServiceProvider: AccessibilityServiceProvider,
    private val screenCaptureProvider: ScreenCaptureProvider,
    private val compactTreeFormatter: CompactTreeFormatter,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult { ... }

    fun register(server: Server) {
        server.addTool(
            name = TOOL_NAME,
            description = "Returns the current screen state: app info, screen dimensions, " +
                "and a compact UI element list (text/desc truncated to 100 chars, use " +
                "get_element_details to retrieve full values). Optionally includes a " +
                "low-resolution screenshot (only request the screenshot when the element " +
                "list alone is not sufficient to understand the screen layout).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("include_screenshot") {
                        put("type", "boolean")
                        put("description", "Include a low-resolution screenshot. " +
                            "Only request when the UI element list is not sufficient.")
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
```

**`execute()` implementation details** (in this exact order):

1. Parse `include_screenshot` boolean from arguments (default `false`) — parse params FIRST before expensive operations.
2. Check `accessibilityServiceProvider.isReady()` — throw `McpToolException.PermissionDenied` if not.
3. Get root node via `accessibilityServiceProvider.getRootNode()` — throw `McpToolException.ActionFailed` if null.
4. Parse tree via `treeParser.parseTree(rootNode)` (in try/finally to recycle root node).
5. Get app info: `accessibilityServiceProvider.getCurrentPackageName() ?: "unknown"`, same for activity.
6. Get screen info: `accessibilityServiceProvider.getScreenInfo()`.
7. Format compact output via `compactTreeFormatter.format(tree, packageName, activityName, screenInfo)`.
8. If `include_screenshot` is `true`:
   a. Check `screenCaptureProvider.isScreenCaptureAvailable()` — throw `McpToolException.PermissionDenied` if not.
   b. Call `screenCaptureProvider.captureScreenshot(quality = ScreenCaptureProvider.DEFAULT_QUALITY, maxWidth = SCREENSHOT_MAX_SIZE, maxHeight = SCREENSHOT_MAX_SIZE)`.
   c. On failure, throw `McpToolException.ActionFailed`.
   d. Return `McpToolUtils.textAndImageResult(compactOutput, screenshotData.data, "image/jpeg")`.
9. Else: return `McpToolUtils.textResult(compactOutput)`.
10. Log: `Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot")`.

#### Action 2.2.2: Update `registerScreenIntrospectionTools` function

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Change**: Replace the existing registration function (lines 318-337) with:

```kotlin
fun registerScreenIntrospectionTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
    compactTreeFormatter: CompactTreeFormatter,
) {
    GetScreenStateHandler(treeParser, accessibilityServiceProvider, screenCaptureProvider, compactTreeFormatter).register(server)
}
```

**Note**: The function signature gains a new `compactTreeFormatter` parameter. All callers must be updated.

#### Action 2.2.3: Update `McpServerService.registerAllTools` to pass `CompactTreeFormatter`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

**Changes**:
1. Add import: `import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter`
2. Add `@Inject lateinit var compactTreeFormatter: CompactTreeFormatter` field (after the existing `@Inject` fields, around line 82).
3. Update the `registerScreenIntrospectionTools` call in `registerAllTools()` (line 233) to pass the new parameter:
   ```kotlin
   registerScreenIntrospectionTools(server, treeParser, accessibilityServiceProvider, screenCaptureProvider, compactTreeFormatter)
   ```

#### Action 2.2.4: Update imports in `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Remove unused imports** (from deleted handlers):
- `kotlinx.serialization.encodeToString`
- `kotlinx.serialization.json.Json`
- `kotlinx.serialization.json.int`
- `kotlinx.serialization.json.putJsonArray`
- `com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData`
- `com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo`

**Add new imports** (for new handler):
- `com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter`
- `android.util.Log`
- `kotlinx.serialization.json.booleanOrNull`

**Keep existing imports** (used by new handler):
- `com.danielealbano.androidremotecontrolmcp.mcp.McpToolException`
- `com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider`
- `com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser`
- `com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider`
- `io.modelcontextprotocol.kotlin.sdk.server.Server`
- `io.modelcontextprotocol.kotlin.sdk.types.CallToolResult`
- `io.modelcontextprotocol.kotlin.sdk.types.ToolSchema`
- `kotlinx.serialization.json.JsonObject`
- `kotlinx.serialization.json.buildJsonObject`
- `kotlinx.serialization.json.jsonPrimitive`
- `kotlinx.serialization.json.put`
- `kotlinx.serialization.json.putJsonObject`
- `javax.inject.Inject`

---

### Task 2.3: Write unit tests for `GetScreenStateHandler`

**Definition of Done**: Unit tests cover all `GetScreenStateHandler` scenarios.

#### Action 2.3.1: Rewrite `ScreenIntrospectionToolsTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt`

**What to remove**: All existing `@Nested` test classes (entire file content from line 34 to 527):
- `GetAccessibilityTreeTests`
- `CaptureScreenshotTests`
- `GetCurrentAppTests`
- `GetScreenInfoTests`

**What to add**: New `@Nested` class `GetScreenStateTests` with these test cases:

1. **`returns compact tree when service is ready`**: Mock service ready, root node, tree parser, compactTreeFormatter. Verify result has 1 `TextContent` item containing the formatted output.
2. **`includes screenshot when include_screenshot is true`**: Pass `include_screenshot=true`. Mock screen capture. Verify result has 2 content items: `TextContent` + `ImageContent`.
3. **`screenshot uses 700px max size`**: Pass `include_screenshot=true`. Verify `captureScreenshot` is called with `maxWidth=700, maxHeight=700`.
4. **`does not include screenshot by default`**: Pass `null` arguments. Verify result has only 1 `TextContent`, no `ImageContent`. Verify `captureScreenshot` is NOT called.
5. **`does not include screenshot when false`**: Pass `include_screenshot=false`. Same assertions as above.
6. **`throws PermissionDenied when accessibility service not ready`**: Service not ready → `McpToolException.PermissionDenied`.
7. **`throws ActionFailed when root node is null`**: Service ready but `getRootNode()` returns null → `McpToolException.ActionFailed`.
8. **`throws PermissionDenied when screenshot requested but capture not available`**: `include_screenshot=true`, accessibility OK, but `isScreenCaptureAvailable()` returns false → `McpToolException.PermissionDenied`.
9. **`throws ActionFailed when screenshot capture fails`**: `include_screenshot=true`, capture returns `Result.failure` → `McpToolException.ActionFailed`.
10. **`recycles root node after parsing`**: Verify `rootNode.recycle()` is called in finally block.

**Updated setUp**: Add `mockCompactTreeFormatter: CompactTreeFormatter` mock. Handler becomes `GetScreenStateHandler(mockTreeParser, mockAccessibilityServiceProvider, mockScreenCaptureProvider, mockCompactTreeFormatter)`.

**Updated imports**: Remove imports for old handler test dependencies (`AccessibilityNodeData`, `BoundsData`, `Json`, `ScreenInfo`, etc.) that are no longer needed. Add imports for new handler dependencies (`CompactTreeFormatter`, `booleanOrNull`).

**Run**: `./gradlew :app:testDebugUnitTest --tests "*.ScreenIntrospectionToolsTest"`

---

### Task 2.4: Add unit test for `McpToolUtils.textAndImageResult`

**Definition of Done**: Unit test verifies the new helper method.

#### Action 2.4.1: Add test to `McpToolUtilsTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtilsTest.kt`

**Add new `@Nested` class** after `ImageResultTests` (after line 376):

```kotlin
// ─────────────────────────────────────────────────────────────────────
// textAndImageResult
// ─────────────────────────────────────────────────────────────────────

@Nested
@DisplayName("textAndImageResult")
inner class TextAndImageResultTests {
    @Test
    @DisplayName("returns CallToolResult with TextContent and ImageContent")
    fun returnsCallToolResultWithTextAndImageContent() {
        val result = McpToolUtils.textAndImageResult("hello", "base64data", "image/jpeg")
        assertEquals(2, result.content.size)
        val text = result.content[0] as TextContent
        val image = result.content[1] as ImageContent
        assertEquals("hello", text.text)
        assertEquals("base64data", image.data)
        assertEquals("image/jpeg", image.mimeType)
    }
}
```

**Run**: `./gradlew :app:testDebugUnitTest --tests "*.McpToolUtilsTest"`

---

## User Story 3: Implement `get_element_details` Tool

**As a** MCP client (LLM), **I want** a `get_element_details` tool that returns full untruncated text and description for given element IDs **so that** I can lazily fetch long content only when needed.

### Acceptance Criteria
- [ ] `GetElementDetailsTool` class exists and is registered in UtilityTools
- [ ] Tool accepts a list of element IDs (JSON array of strings)
- [ ] Returns TSV with columns: `id`, `text`, `desc`
- [ ] Text and desc are NOT truncated (full values)
- [ ] Null/empty text/desc → `-`
- [ ] Returns error row `<id>\tnot_found\tnot_found` for IDs not in tree
- [ ] Unit tests pass
- [ ] `./gradlew ktlintCheck` passes
- [ ] `./gradlew detekt` passes

---

### Task 3.1: Create `GetElementDetailsTool` class

**Definition of Done**: New tool handler in UtilityTools that returns full text/desc for element IDs.

#### Action 3.1.1: Add `GetElementDetailsTool` to `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Where**: Add new class before the `registerUtilityTools` function (before line 431).

**New class**:

```kotlin
/**
 * MCP tool: get_element_details
 *
 * Retrieves full (untruncated) text and contentDescription for a list of element IDs.
 * Use after get_screen_state when text or desc columns were truncated.
 */
class GetElementDetailsTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // 1. Parse "ids" parameter — required, JSON array of strings
            val idsElement = arguments?.get("ids")
                ?: throw McpToolException.InvalidParams("Missing required parameter 'ids'")
            val idsArray = (idsElement as? kotlinx.serialization.json.JsonArray)
                ?: throw McpToolException.InvalidParams("Parameter 'ids' must be an array of strings")
            if (idsArray.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'ids' must not be empty")
            }
            val ids = idsArray.map { element ->
                val primitive = element as? kotlinx.serialization.json.JsonPrimitive
                    ?: throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
                if (!primitive.isString) {
                    throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
                }
                primitive.content
            }

            // 2. Get fresh tree
            val tree = getFreshTree(treeParser, accessibilityServiceProvider)

            // 3. Build TSV output
            val sb = StringBuilder()
            sb.append("id\ttext\tdesc\n")

            for (id in ids) {
                val node = elementFinder.findNodeById(tree, id)
                if (node != null) {
                    val text = sanitizeForTsv(node.text)
                    val desc = sanitizeForTsv(node.contentDescription)
                    sb.append("$id\t$text\t$desc\n")
                } else {
                    sb.append("$id\tnot_found\tnot_found\n")
                }
            }

            Log.d(TAG, "get_element_details: ids=${ids.size}")

            return McpToolUtils.textResult(sb.toString().trimEnd('\n'))
        }

        /**
         * Sanitizes text for TSV: replaces tabs/newlines with spaces.
         * Returns "-" if null or empty.
         * Does NOT truncate — this tool returns full values.
         */
        private fun sanitizeForTsv(text: String?): String {
            if (text.isNullOrEmpty()) return "-"
            val sanitized = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
            return if (sanitized.isEmpty()) "-" else sanitized
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Retrieve full untruncated text and description for elements by ID. " +
                    "Use after get_screen_state when text or desc was truncated.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("ids") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                            put("description", "List of element IDs to retrieve details for")
                        }
                    },
                    required = listOf("ids"),
                ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:GetElementDetailsTool"
            private const val TOOL_NAME = "get_element_details"
        }
    }
```

#### Action 3.1.2: Update `registerUtilityTools` function

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Change**: Update the `registerUtilityTools` function (line 431-441) to also register `GetElementDetailsTool`:

```kotlin
fun registerUtilityTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    accessibilityServiceProvider: AccessibilityServiceProvider,
) {
    GetClipboardTool(accessibilityServiceProvider).register(server)
    SetClipboardTool(accessibilityServiceProvider).register(server)
    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider).register(server)
    WaitForIdleTool(treeParser, accessibilityServiceProvider).register(server)
    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider).register(server)
}
```

**Note**: The function signature does NOT change — `treeParser`, `elementFinder`, `accessibilityServiceProvider` are already parameters.

#### Action 3.1.3: Add required imports to `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Add import**: `kotlinx.serialization.json.JsonArray` (needed for casting `idsElement`).

**All other needed imports** (`JsonPrimitive`, `ToolSchema`, `buildJsonObject`, `putJsonObject`, `put`, `Log`, `McpToolException`, `McpToolUtils`, `ElementFinder`, `AccessibilityTreeParser`, `AccessibilityServiceProvider`, `Server`, `CallToolResult`, `JsonObject`) — verify already present from existing tools in the file.

---

### Task 3.2: Write unit tests for `GetElementDetailsTool`

**Definition of Done**: Comprehensive unit tests for the new tool.

#### Action 3.2.1: Create `GetElementDetailsToolTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GetElementDetailsToolTest.kt` (NEW)

**Test cases**:

1. **`returns text and desc for found elements`**: Two element IDs in tree, both found. Verify TSV has header + 2 data rows with correct text/desc.
2. **`returns not_found for missing element IDs`**: One ID in tree, one not. Verify found row has correct values, missing row has `not_found\tnot_found`.
3. **`returns dash for null text and desc`**: Element exists but has null text and null desc. Verify `-\t-`.
4. **`sanitizes tabs and newlines in text and desc`**: Element with `\t` and `\n` in text. Verify replaced with spaces.
5. **`does not truncate long text`**: Element with 200-char text. Verify full text returned.
6. **`throws InvalidParams when ids missing`**: Null arguments. Verify `McpToolException.InvalidParams`.
7. **`throws InvalidParams when ids is not array`**: `ids` is a string. Verify `McpToolException.InvalidParams`.
8. **`throws InvalidParams when ids is empty array`**: `ids` is `[]`. Verify `McpToolException.InvalidParams`.
9. **`throws InvalidParams when ids contains non-string`**: `ids` contains number. Verify `McpToolException.InvalidParams`.
10. **`throws PermissionDenied when accessibility not available`**: Mock `getRootNode()` returns null. Verify `McpToolException.PermissionDenied`.

**Run**: `./gradlew :app:testDebugUnitTest --tests "*.GetElementDetailsToolTest"`

---

## User Story 4: Update Integration Tests

**As a** developer, **I want** integration tests to reflect the new tools **so that** CI validates the new behavior end-to-end.

### Acceptance Criteria
- [x] `McpIntegrationTestHelper.registerAllTools` passes `CompactTreeFormatter` to `registerScreenIntrospectionTools`
- [x] `ScreenIntrospectionIntegrationTest` tests `get_screen_state` instead of old tools
- [x] `McpProtocolIntegrationTest` expected tool count and names updated (29 → 27 tools: removed 4, added `get_screen_state` + `get_element_details`)
- [x] All integration tests pass
- [x] `./gradlew ktlintCheck` passes
- [x] `./gradlew detekt` passes

---

### Task 4.1: Update `McpIntegrationTestHelper`

**Definition of Done**: Test helper passes `CompactTreeFormatter` instance to `registerScreenIntrospectionTools`.

#### Action 4.1.1: Update `registerAllTools` in `McpIntegrationTestHelper`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

**Changes**:
1. Add import: `com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter`
2. In `registerAllTools()` (line 64-70), update the `registerScreenIntrospectionTools` call to pass a real `CompactTreeFormatter()` instance:
   ```kotlin
   registerScreenIntrospectionTools(
       server,
       deps.treeParser,
       deps.accessibilityServiceProvider,
       deps.screenCaptureProvider,
       CompactTreeFormatter(),
   )
   ```

**Note**: We use a real `CompactTreeFormatter` (not mocked) in integration tests because we want to verify the actual formatting output through the full stack.

---

### Task 4.2: Rewrite `ScreenIntrospectionIntegrationTest`

**Definition of Done**: Integration tests test `get_screen_state` with both text-only and text+screenshot responses.

#### Action 4.2.1: Rewrite `ScreenIntrospectionIntegrationTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`

**Remove all existing tests** (they test old tools: lines 47-154).

**Add new tests**:

1. **`get_screen_state returns compact flat TSV with metadata`**: Mock accessibility service ready, root node, tree parser returns sample tree (with at least one node passing filter), screen info, package/activity. Call `get_screen_state` with no arguments. Verify single `TextContent` containing `note:` line, `app:` line, `screen:` line, and TSV header. Verify no depth column in output.
2. **`get_screen_state with include_screenshot returns text and image`**: Same mocks plus screen capture returning mock data. Call with `include_screenshot=true`. Verify 2 content items: `TextContent` (compact output) + `ImageContent` (JPEG data, mimeType=image/jpeg).
3. **`get_screen_state without screenshot does not call captureScreenshot`**: Call without `include_screenshot`. Verify `captureScreenshot` is NOT invoked via `coVerify(exactly = 0)`.
4. **`get_screen_state when permission denied returns error`**: Service not ready. Verify error result with permission denied message.

**Update `sampleTree`**: Ensure the sample tree has at least one node that passes the filter (e.g., has text or is clickable) and one node that should be filtered out (bare FrameLayout with no text/desc/resourceId and not interactive).

**Update imports**: Remove old imports (`Json`, `jsonObject`, `jsonPrimitive`), add `coVerify` from MockK if needed for screenshot verification.

---

### Task 4.3: Update `McpProtocolIntegrationTest`

**Definition of Done**: Expected tool count and names reflect the new tool set.

#### Action 4.3.1: Update tool count and names

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpProtocolIntegrationTest.kt`

**Changes**:
1. Update `EXPECTED_TOOL_COUNT` from `29` to `27` (removed 4, added 2: `get_screen_state` + `get_element_details`).
2. In `EXPECTED_TOOL_NAMES` set, replace:
   ```kotlin
   // Old (remove these 4):
   "get_accessibility_tree", "capture_screenshot",
   "get_current_app", "get_screen_info",
   // New (add these 2):
   "get_screen_state",
   "get_element_details",
   ```
3. Update test display name `"listTools returns all 29 registered tools"` → `"listTools returns all 27 registered tools"`.

---

## User Story 5: Update Documentation

**As a** developer, **I want** the documentation to reflect the new tools **so that** the docs are accurate and up to date.

### Acceptance Criteria
- [x] `MCP_TOOLS.md` documents `get_screen_state` and `get_element_details`, removes old tool docs
- [x] `PROJECT.md` updated to reference new tools instead of old tools
- [x] KDoc comments in `ScreenInfo.kt` and `AccessibilityTreeParser.kt` updated

---

### Task 5.1: Update `MCP_TOOLS.md`

**Definition of Done**: Old tool documentation replaced with `get_screen_state` and `get_element_details` documentation.

#### Action 5.1.1: Replace screen introspection tool docs in `MCP_TOOLS.md`

**File**: `docs/MCP_TOOLS.md`

**Update the overview table** (line 33): Replace old Screen Introspection tools with `get_screen_state`, update tool count to 1.

**Update Utility tools row** (line 39): Add `get_element_details` to the list, update count to 5.

**Remove sections for** (lines 135-373):
- `get_accessibility_tree`
- `capture_screenshot`
- `get_current_app`
- `get_screen_info`

**Add section for `get_screen_state`**:
- Description: consolidated tool replacing 4 old tools
- Parameters: `include_screenshot` (boolean, optional, default false)
- Output format: document the compact flat TSV format with note line, metadata lines, header, flags encoding
- Example request (without screenshot)
- Example request (with screenshot)
- Example response (text-only with note + metadata + header + sample rows)
- Example response (text + image)
- Flags reference table
- Node filtering rules
- Text/desc truncation note (100 chars, "...truncated" suffix)

**Add section for `get_element_details`** (in Utility Tools section, section 7):
- Description: retrieves full untruncated text/desc by element IDs
- Parameters: `ids` (array of strings, required)
- Output format: TSV with `id`, `text`, `desc` columns
- Example request
- Example response
- Error cases

---

### Task 5.2: Update `PROJECT.md`

**Definition of Done**: Tool summary table and any references to old tools updated.

#### Action 5.2.1: Update tool references in `PROJECT.md`

**File**: `docs/PROJECT.md`

**Changes**:
- Line 189: Update total from "29 tools" to "27 tools"
- Line 191: Update section header from "Screen Introspection Tools (4 tools)" to "Screen Introspection Tools (1 tool)"
- Lines 195-198: Replace 4 old tool rows with 1 `get_screen_state` row
- Line 202: Remove note about `get_accessibility_tree` returning full tree depth, replace with note about `get_screen_state` returning filtered flat TSV with truncated text/desc
- Add `get_element_details` to Utility Tools table (if utilities section has a table)

---

### Task 5.3: Update KDoc comments referencing old tool names

**Definition of Done**: KDoc in source files no longer references old tool names.

#### Action 5.3.1: Update `ScreenInfo.kt` KDoc

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenInfo.kt`

**Change** (line 9): Replace `by the \`get_screen_info\` MCP tool` with `by the \`get_screen_state\` MCP tool`.

#### Action 5.3.2: Update `AccessibilityTreeParser.kt` KDoc

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Change** (line 23): Replace `used as the MCP protocol output for \`get_accessibility_tree\` and related tools` with `used by the \`get_screen_state\` MCP tool and element action tools`.

---

### Task 5.4: ARCHITECTURE.md check

**Definition of Done**: Verified no references to old tool names.

**Result**: No references found in ARCHITECTURE.md — no changes needed.

---

## User Story 6: Update E2E Tests

**As a** developer, **I want** E2E tests to use `get_screen_state` **so that** end-to-end validation covers the new tool.

### Acceptance Criteria
- [x] `E2EScreenshotTest` updated to use `get_screen_state` with `include_screenshot=true`
- [x] `E2ECalculatorTest` updated to use `get_screen_state` instead of old tools
- [x] E2E tests compile (runtime execution is Docker-dependent)

---

### Task 6.1: Update `E2EScreenshotTest`

**Definition of Done**: Tests call `get_screen_state` with `include_screenshot=true` instead of `capture_screenshot`.

#### Action 6.1.1: Rewrite E2E screenshot tests

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EScreenshotTest.kt`

**Changes**:

1. **Test 1** (`capture screenshot of home screen returns valid JPEG data`, lines 29-50):
   - Rename to `get_screen_state with screenshot returns valid JPEG data`
   - Replace `mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))` (line 36) with `mcpClient.callTool("get_screen_state", mapOf("include_screenshot" to true))`
   - Update assertions: result now has 2 content items; screenshot is the second item (`result.content[1]` not `result.content[0]`). First item is `TextContent` with compact TSV.
   - Verify first content item is `TextContent` containing `note:` and `app:` lines
   - Verify second content item is `ImageContent` with `mimeType = "image/jpeg"` and non-empty data

2. **Test 2** (`higher quality produces larger screenshot data`, lines 52-83):
   - **Remove entirely**. Quality is no longer an exposed parameter (hardcoded at 80). This test has no equivalent in the new API.

3. **Update class KDoc** (lines 14-22): Replace references to `capture_screenshot` with `get_screen_state`.

4. **Update imports**: Add `TextContent` import. Remove any unused imports.

---

### Task 6.2: Update `E2ECalculatorTest`

**Definition of Done**: Tests call `get_screen_state` instead of old tools.

#### Action 6.2.1: Update E2E calculator tests

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`

**Changes**:

1. **Test `MCP server lists all available tools`** (line 60):
   - Change `result.tools.size >= 29` to `result.tools.size >= 27`

2. **Test `calculate 7 plus 3 equals 10`**:
   - **Line 75**: Replace `mcpClient.callTool("get_accessibility_tree")` with `mcpClient.callTool("get_screen_state")`
   - **Line 76**: `(tree.content[0] as TextContent).text` stays the same (still TextContent, but now compact TSV format)
   - **Lines 78-82**: Update assertion — check for `Calculator` or `CALCULATOR_PACKAGE` in the compact TSV (same logic, format is different but still contains the text)
   - **Line 112**: Replace `mcpClient.callTool("get_accessibility_tree")` with `mcpClient.callTool("get_screen_state")`
   - **Line 113**: Same — `(resultTree.content[0] as TextContent).text` — content is TSV, `contains("10")` still works

3. **Test `capture screenshot returns valid image data`** (lines 120-137):
   - **Line 123**: Replace `mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))` with `mcpClient.callTool("get_screen_state", mapOf("include_screenshot" to true))`
   - **Line 126**: `assertTrue(content.isNotEmpty(), ...)` stays
   - **Line 128**: Change `content[0] as ImageContent` to `content[1] as ImageContent` (screenshot is second item)
   - Add assertion that `content[0]` is `TextContent` (compact TSV output)
   - **Lines 130-136**: Assertions stay the same (mimeType, data checks)

4. **Update class KDoc** (lines 20-36): Replace references to old tools.

---

## User Story 7: Final Verification

**As a** developer, **I want** to verify all changes are correct, consistent, and passing **so that** the implementation is complete and ready for review.

### Acceptance Criteria
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest`
- [ ] All integration tests pass (included in above)
- [ ] Lint passes: `make lint`
- [ ] Build succeeds: `./gradlew build`
- [ ] No TODOs, no commented-out dead code
- [ ] All old handler classes fully removed (no orphaned code)
- [ ] All old tool names fully removed from test expectations
- [ ] Compact flat TSV output format matches the agreed design exactly
- [ ] Node filtering works correctly (noise nodes excluded, children still walked)
- [ ] Text/desc truncation at 100 chars verified in tests
- [ ] `get_element_details` returns full untruncated values
- [ ] Screenshot cap at 700px verified in tests
- [ ] Documentation accurately reflects the implementation

---

### Task 7.1: Run full quality gates

#### Action 7.1.1: Run lint
```
make lint
```

#### Action 7.1.2: Run all tests
```
./gradlew :app:testDebugUnitTest
```

#### Action 7.1.3: Run build
```
./gradlew build
```

---

### Task 7.2: Manual review of all changes

#### Action 7.2.1: Review every changed file against this plan

Verify:
- [ ] `CompactTreeFormatter.kt` — implements exact format from design (note line, metadata lines, TSV header with NO depth column, flags, filtering, class name stripping, text sanitization, text/desc truncation at 100 chars, resourceId sanitization without truncation)
- [ ] `ScreenIntrospectionTools.kt` — only contains `GetScreenStateHandler`, old handlers fully removed
- [ ] `McpToolUtils.kt` — `textAndImageResult` method added correctly
- [ ] `McpServerService.kt` — `compactTreeFormatter` injected and passed to registration, import added
- [ ] `UtilityTools.kt` — `GetElementDetailsTool` added, registered in `registerUtilityTools`, returns full untruncated text/desc
- [ ] `McpIntegrationTestHelper.kt` — passes `CompactTreeFormatter()` to registration
- [ ] `ScreenIntrospectionToolsTest.kt` — tests `GetScreenStateHandler`, old tests removed
- [ ] `CompactTreeFormatterTest.kt` — comprehensive coverage including truncation tests
- [ ] `GetElementDetailsToolTest.kt` — comprehensive coverage
- [ ] `McpToolUtilsTest.kt` — new helper tested
- [ ] `ScreenIntrospectionIntegrationTest.kt` — tests `get_screen_state`
- [ ] `McpProtocolIntegrationTest.kt` — tool count = 27, names updated (includes `get_screen_state` + `get_element_details`)
- [ ] `E2EScreenshotTest.kt` — uses `get_screen_state`, quality test removed entirely
- [ ] `E2ECalculatorTest.kt` — uses `get_screen_state`, tool count >= 27
- [ ] `MCP_TOOLS.md` — old tools removed, new tools documented
- [ ] `PROJECT.md` — references updated
- [ ] `ScreenInfo.kt` KDoc — references `get_screen_state` not `get_screen_info`
- [ ] `AccessibilityTreeParser.kt` KDoc — references `get_screen_state` not `get_accessibility_tree`
- [ ] ARCHITECTURE.md — verified no references (no changes needed)
- [ ] No orphaned imports, no dead code, no TODOs
