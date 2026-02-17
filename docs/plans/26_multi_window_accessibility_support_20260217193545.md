# Plan 26: Multi-Window Accessibility Support

**Created**: 2026-02-17 19:35:45
**Status**: Draft
**Branch**: `feat/multi-window-accessibility`

## Problem Statement

When an app requests a permission via a popup (or any system dialog appears), the accessibility
service reports as "offline" because `isReady()` depends on `rootInActiveWindow` which returns
`null` during window transitions. Additionally, the current implementation only sees the active
window — it cannot see system dialogs, permission popups, IME keyboards, or other overlay windows.

## Solution

1. **Decouple `isReady()` from `rootInActiveWindow`** — only check if the service is connected.
2. **Multi-window support** — use `getWindows()` API to enumerate all on-screen windows and parse
   each window's accessibility tree independently.
3. **Update TSV output** — group elements by window with metadata headers.
4. **Update all tools** — search and act across all windows transparently.
5. **Fallback** — if `getWindows()` returns empty, fall back to `rootInActiveWindow` with a
   degradation note in the output.

## Design Decisions (Agreed)

| Decision | Choice |
|---|---|
| Global `app:`/`activity:` line | Removed; each window header carries `pkg:`, `title:`, and `activity:` (when available) |
| Window header format | `--- window:N type:TYPE pkg:PKG title:TITLE activity:ACT layer:N focused:BOOL ---` |
| Window types included | ALL types: APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY |
| Element search in action tools | Transparent cross-window search (no API change for the model) |
| Fallback when `getWindows()` empty | Fall back to `rootInActiveWindow`, add degradation note to TSV |
| `findFocusedEditableNode()` | Iterate over all window roots, call `findFocus()` on each |
| Node ID uniqueness | Root parentId uses `"root_w${windowId}"` per window, where `windowId` = `AccessibilityWindowInfo.getId()` |
| Window matching in `performNodeAction()` | Match by `AccessibilityWindowInfo.getId()` (stable ID), not positional index |
| `isReady()` semantics | Changed to only check `instance != null` (service connected) |
| Activity in window header | Best-effort — included when window's package matches tracked `currentPackageName` |

## TSV Output Format (New)

```
note:structural-only nodes are omitted from the tree
note:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account
note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled
note:offscreen items require scroll_to_element before interaction
screen:1080x2400 density:420 orientation:portrait
--- window:42 type:APPLICATION pkg:com.example.app title:MainActivity activity:com.example.app.MainActivity layer:0 focused:true ---
id	class	text	desc	res_id	bounds	flags
node_a1b2	Button	Click me	-	btn_click	0,0,100,48	on,clk,ena
node_c3d4	TextView	Hello	-	-	0,48,100,96	on,ena
--- window:57 type:SYSTEM pkg:com.android.permissioncontroller title:Permission layer:1 focused:false ---
id	class	text	desc	res_id	bounds	flags
node_e5f6	Button	Allow	-	btn_allow	50,500,250,548	on,clk,ena
node_g7h8	Button	Deny	-	btn_deny	300,500,500,548	on,clk,ena
```

### Degraded mode (fallback to single window)

```
note:DEGRADED — multi-window unavailable, only active window reported
note:structural-only nodes are omitted from the tree
...
screen:1080x2400 density:420 orientation:portrait
--- window:42 type:APPLICATION pkg:com.example.app title:unknown activity:com.example.app.MainActivity layer:0 focused:true ---
id	class	text	desc	res_id	bounds	flags
...
```

---

## User Story 1: Multi-Window Data Model and Service Layer

**Goal**: Introduce the `WindowData` data class, add multi-window retrieval to the service layer,
and decouple `isReady()` from `rootInActiveWindow`.

**Acceptance Criteria / Definition of Done**:
- [x] `WindowData` data class exists with all agreed fields
- [x] `MultiWindowResult` data class exists with `windows` list and `degraded` flag
- [x] `McpAccessibilityService.isReady()` only checks `instance != null`
- [x] `McpAccessibilityService.getAccessibilityWindows()` returns all windows
- [x] `AccessibilityServiceProvider` interface has `getAccessibilityWindows()` method
- [x] `AccessibilityServiceProviderImpl` implements the new method
- [x] All new/changed code passes lint (`ktlintCheck`, `detekt`)
- [x] Unit tests pass for all changes in this user story

### Task 1.1: Create `WindowData` and `MultiWindowResult` data classes

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Definition of Done**:
- [x] `WindowData` data class added with fields: `windowId: Int`, `windowType: String`, `packageName: String?`, `title: String?`, `activityName: String?`, `layer: Int`, `focused: Boolean`, `tree: AccessibilityNodeData`
- [x] `MultiWindowResult` data class added with fields: `windows: List<WindowData>`, `degraded: Boolean`
- [x] Both classes are `@Serializable`

**Action 1.1.1**: Add `WindowData` data class after `AccessibilityNodeData`

Add the following after the `AccessibilityNodeData` class (after line 56):

```kotlin
/**
 * Represents a single window's parsed accessibility data with window metadata.
 *
 * @property windowId System-assigned unique window ID from [AccessibilityWindowInfo.getId].
 * @property windowType Window type label: APPLICATION, INPUT_METHOD, SYSTEM,
 *   ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY, or UNKNOWN.
 * @property packageName Package name of the window's root node, or null if unavailable.
 * @property title Window title (e.g., activity name, dialog title), or null if unavailable.
 * @property activityName Activity class name (best-effort, only for focused app window), or null.
 * @property layer Window layer (z-order from Android).
 * @property focused Whether this window currently has input focus.
 * @property tree The parsed accessibility node tree for this window.
 */
@Serializable
data class WindowData(
    val windowId: Int,
    val windowType: String,
    val packageName: String? = null,
    val title: String? = null,
    val activityName: String? = null,
    val layer: Int = 0,
    val focused: Boolean = false,
    val tree: AccessibilityNodeData,
)

/**
 * Result of multi-window accessibility tree parsing.
 *
 * @property windows List of parsed windows with metadata, ordered by z-order.
 * @property degraded True if the multi-window API was unavailable and the result
 *   fell back to single-window mode via [rootInActiveWindow]. When true, the output
 *   may not reflect all on-screen windows (e.g., system dialogs, permission popups).
 */
@Serializable
data class MultiWindowResult(
    val windows: List<WindowData>,
    val degraded: Boolean = false,
)
```

---

### Task 1.2: Change `McpAccessibilityService.isReady()` semantics

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

**Definition of Done**:
- [x] `isReady()` only checks `instance != null`, no longer checks `rootInActiveWindow`
- [x] KDoc updated to reflect new semantics

**Action 1.2.1**: Update `isReady()` method

Change line 124-127 from:

```kotlin
/**
 * Returns true if the service is connected and has an active root node available.
 */
fun isReady(): Boolean = instance != null && rootInActiveWindow != null
```

To:

```kotlin
/**
 * Returns true if the service is connected and ready to process requests.
 * Does NOT check for an active window — multi-window support handles
 * window availability at tree-parsing time.
 */
fun isReady(): Boolean = instance != null
```

---

### Task 1.3: Add `getAccessibilityWindows()` to `McpAccessibilityService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

**Definition of Done**:
- [x] `getAccessibilityWindows()` method returns all windows from `AccessibilityService.getWindows()`
- [x] Method handles null/exception gracefully, returns empty list on failure
- [x] Includes import for `AccessibilityWindowInfo`

**Action 1.3.1**: Add import for `AccessibilityWindowInfo`

Add to imports section:

```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

**Action 1.3.2**: Add `getAccessibilityWindows()` method

Add after `getRootNode()` method (after line 110):

```kotlin
/**
 * Returns all on-screen windows via [AccessibilityService.getWindows].
 * Requires [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS].
 *
 * @return List of [AccessibilityWindowInfo], or empty list if unavailable.
 */
fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
    try {
        windows ?: emptyList()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "getWindows() failed: ${e.message}")
        emptyList()
    }
```

---

### Task 1.4: Update `AccessibilityServiceProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityServiceProvider.kt`

**Definition of Done**:
- [x] `getAccessibilityWindows()` method added to interface
- [x] Import for `AccessibilityWindowInfo` added

**Action 1.4.1**: Add import

Add to imports:

```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

**Action 1.4.2**: Add `getAccessibilityWindows()` to interface

Add after `getRootNode()` declaration:

```kotlin
fun getAccessibilityWindows(): List<AccessibilityWindowInfo>
```

---

### Task 1.5: Update `AccessibilityServiceProviderImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityServiceProviderImpl.kt`

**Definition of Done**:
- [x] `getAccessibilityWindows()` implemented, delegates to `McpAccessibilityService.instance`
- [x] Returns empty list when service instance is null
- [x] Import for `AccessibilityWindowInfo` added

**Action 1.5.1**: Add import

Add to imports:

```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

**Action 1.5.2**: Add implementation

Add after `getRootNode()` override:

```kotlin
override fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
    McpAccessibilityService.instance?.getAccessibilityWindows() ?: emptyList()
```

---

### Task 1.6: Unit tests for User Story 1

**Definition of Done**:
- [x] Tests verify `isReady()` returns true when `instance` is set (regardless of `rootInActiveWindow`)
- [x] Tests verify `isReady()` returns false when `instance` is null
- [x] Tests verify `getAccessibilityWindows()` returns empty list when service is null
- [x] Tests verify `getAccessibilityWindows()` delegates to service instance
- [x] All tests pass

**Action 1.6.1**: Update `ActionExecutorImplTest.kt` — existing tests that depend on `isReady()` semantics

No changes needed in `ActionExecutorImplTest.kt` — the tests there use `McpAccessibilityService.instance` directly, not `isReady()`.

**Action 1.6.2**: Add tests in a new test class or update existing tests for `AccessibilityServiceProviderImpl`

If no test file exists for `AccessibilityServiceProviderImpl`, these tests will be covered by the integration tests and tool-level tests that mock `AccessibilityServiceProvider`. The interface is simple delegation — no business logic to unit test independently. The `isReady()` change will be verified via the tool tests that check the "service not ready" error path.

---

## User Story 2: Multi-Window Tree Parsing

**Goal**: Update `AccessibilityTreeParser` to support window-specific root parent IDs (using window IDs from
`AccessibilityWindowInfo.getId()`) for globally unique node IDs across windows.

**Acceptance Criteria / Definition of Done**:
- [x] `parseTree()` accepts optional `rootParentId` parameter (default `"root"` for backward compat)
- [x] When called with `"root_w0"`, `"root_w1"`, etc., all generated node IDs differ from the default
- [x] Existing tests still pass (default behavior unchanged)
- [x] New tests verify window-specific ID uniqueness
- [x] Lint passes

### Task 2.1: Add `rootParentId` parameter to `parseTree()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Definition of Done**:
- [x] `parseTree()` accepts `rootParentId: String = ROOT_PARENT_ID` parameter
- [x] Passed through to `parseNode()` as `parentId`
- [x] Default value preserves existing behavior

**Action 2.1.1**: Update `parseTree()` signature and body

Change lines 78-85 from:

```kotlin
fun parseTree(rootNode: AccessibilityNodeInfo): AccessibilityNodeData =
    parseNode(
        node = rootNode,
        depth = 0,
        index = 0,
        parentId = ROOT_PARENT_ID,
        recycleNode = false,
    )
```

To:

```kotlin
fun parseTree(
    rootNode: AccessibilityNodeInfo,
    rootParentId: String = ROOT_PARENT_ID,
): AccessibilityNodeData =
    parseNode(
        node = rootNode,
        depth = 0,
        index = 0,
        parentId = rootParentId,
        recycleNode = false,
    )
```

---

### Task 2.2: Add window type mapping utility

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Definition of Done**:
- [x] `mapWindowType()` companion function maps Android `AccessibilityWindowInfo.TYPE_*` constants to string labels
- [x] All 6 types mapped: APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY
- [x] Unknown types return `"UNKNOWN(N)"`

**Action 2.2.1**: Add `mapWindowType()` to companion object

**Import to add** (if not already present):
```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

Add inside the `AccessibilityTreeParser` companion object (after `HASH_RADIX` constant):

```kotlin
/** Maps [AccessibilityWindowInfo] type constants to human-readable labels. */
fun mapWindowType(type: Int): String =
    when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ->
            "ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ->
            "SPLIT_SCREEN_DIVIDER"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY ->
            "MAGNIFICATION_OVERLAY"
        else -> "UNKNOWN($type)"
    }
```

---

### Task 2.3: Unit tests for tree parser changes

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParserTest.kt`

**Definition of Done**:
- [x] Test verifies `parseTree()` with default `rootParentId` produces same IDs as before
- [x] Test verifies `parseTree()` with `"root_w0"` produces different IDs from default
- [x] Test verifies `parseTree()` with `"root_w0"` vs `"root_w1"` produces different IDs for identical node structures
- [x] Test verifies `mapWindowType()` returns correct labels for all 6 types + unknown
- [x] All existing tests still pass

**Action 2.3.1**: Add test for `rootParentId` parameter

Add a new `@Nested` class inside `AccessibilityTreeParserTest`:

```kotlin
@Nested
@DisplayName("parseTree with rootParentId")
inner class ParseTreeWithRootParentId {
    @Test
    @DisplayName("default rootParentId preserves existing behavior")
    fun defaultRootParentIdPreservesExistingBehavior() {
        val node = createMockNode(text = "Hello")
        val resultDefault = parser.parseTree(node)
        val resultExplicit = parser.parseTree(createMockNode(text = "Hello"), "root")
        assertEquals(resultDefault.id, resultExplicit.id)
    }

    @Test
    @DisplayName("window-specific rootParentId produces different IDs")
    fun windowSpecificRootParentIdProducesDifferentIds() {
        val node1 = createMockNode(text = "Hello")
        val node2 = createMockNode(text = "Hello")
        val resultW0 = parser.parseTree(node1, "root_w0")
        val resultW1 = parser.parseTree(node2, "root_w1")
        assertNotEquals(resultW0.id, resultW1.id)
    }

    @Test
    @DisplayName("window-specific IDs differ from default IDs")
    fun windowSpecificIdsDifferFromDefault() {
        val node1 = createMockNode(text = "Hello")
        val node2 = createMockNode(text = "Hello")
        val resultDefault = parser.parseTree(node1)
        val resultW0 = parser.parseTree(node2, "root_w0")
        assertNotEquals(resultDefault.id, resultW0.id)
    }
}
```

**Action 2.3.2**: Add test for `mapWindowType()`

Add a new `@Nested` class:

```kotlin
@Nested
@DisplayName("mapWindowType")
inner class MapWindowType {
    @Test
    @DisplayName("maps all known window types")
    fun mapsAllKnownWindowTypes() {
        // Android AccessibilityWindowInfo type constants: APPLICATION=1, INPUT_METHOD=2,
        // SYSTEM=3, ACCESSIBILITY_OVERLAY=4, SPLIT_SCREEN_DIVIDER=5, MAGNIFICATION_OVERLAY=6
        assertEquals("APPLICATION", AccessibilityTreeParser.mapWindowType(1))
        assertEquals("INPUT_METHOD", AccessibilityTreeParser.mapWindowType(2))
        assertEquals("SYSTEM", AccessibilityTreeParser.mapWindowType(3))
        assertEquals("ACCESSIBILITY_OVERLAY", AccessibilityTreeParser.mapWindowType(4))
        assertEquals("SPLIT_SCREEN_DIVIDER", AccessibilityTreeParser.mapWindowType(5))
        assertEquals("MAGNIFICATION_OVERLAY", AccessibilityTreeParser.mapWindowType(6))
    }

    @Test
    @DisplayName("maps unknown type to UNKNOWN(N)")
    fun mapsUnknownType() {
        assertEquals("UNKNOWN(0)", AccessibilityTreeParser.mapWindowType(0))
        assertEquals("UNKNOWN(99)", AccessibilityTreeParser.mapWindowType(99))
    }
}
```

---

## User Story 3: Multi-Window Compact Tree Formatter

**Goal**: Update `CompactTreeFormatter` to produce the new multi-window TSV format with
per-window headers and optional degradation note.

**Acceptance Criteria / Definition of Done**:
- [x] New `formatMultiWindow()` method produces the agreed TSV format
- [x] Per-window header line: `--- window:N type:TYPE pkg:PKG title:TITLE activity:ACT layer:N focused:BOOL ---`
- [x] `activity:` field omitted from header when null
- [x] Degradation note added when `MultiWindowResult.degraded` is true
- [x] Global `app:`/`activity:` line removed from multi-window output
- [x] Global `screen:` line preserved
- [x] TSV header repeated per window section
- [x] Existing `format()` method unchanged (backward compat for tests)
- [x] Lint passes
- [x] Unit tests pass

### Task 3.1: Add `formatMultiWindow()` method

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`

**Definition of Done**:
- [x] Method signature: `fun formatMultiWindow(result: MultiWindowResult, screenInfo: ScreenInfo): String`
- [x] Outputs note lines, degradation note (if applicable), screen info line, then per-window sections
- [x] Each window section has: window header line, TSV column header, element rows
- [x] Window header fields: `window:N type:TYPE pkg:PKG title:TITLE [activity:ACT] layer:N focused:BOOL`
- [x] Null `packageName` rendered as `unknown`
- [x] Null `title` rendered as `unknown`
- [x] Null `activityName` causes `activity:` to be omitted entirely from the header

**Action 3.1.1**: Add `DEGRADATION_NOTE` constant to companion object

Add to the companion object:

```kotlin
const val DEGRADATION_NOTE =
    "note:DEGRADED — multi-window unavailable, only active window reported"
```

**Action 3.1.2**: Add `formatMultiWindow()` method

Add after the existing `format()` method:

```kotlin
/**
 * Formats multi-window screen state as a compact string.
 *
 * Each window gets its own section with a header line containing window metadata,
 * followed by a TSV column header and element rows.
 */
fun formatMultiWindow(
    result: MultiWindowResult,
    screenInfo: ScreenInfo,
): String {
    val sb = StringBuilder()

    // Degradation note (if applicable)
    if (result.degraded) {
        sb.appendLine(DEGRADATION_NOTE)
    }

    // Note lines
    sb.appendLine(NOTE_LINE)
    sb.appendLine(NOTE_LINE_CUSTOM_ELEMENTS)
    sb.appendLine(NOTE_LINE_FLAGS_LEGEND)
    sb.appendLine(NOTE_LINE_OFFSCREEN_HINT)

    // Screen info (global)
    sb.appendLine(
        "screen:${screenInfo.width}x${screenInfo.height} " +
            "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
    )

    // Per-window sections
    for (windowData in result.windows) {
        sb.appendLine(buildWindowHeader(windowData))
        sb.appendLine(HEADER)
        walkNode(sb, windowData.tree)
    }

    return sb.toString().trimEnd('\n')
}

/**
 * Builds the window header line for a single window.
 *
 * Format: `--- window:N type:TYPE pkg:PKG title:TITLE [activity:ACT] layer:N focused:BOOL ---`
 *
 * The `activity:` field is omitted when [WindowData.activityName] is null.
 */
internal fun buildWindowHeader(windowData: WindowData): String =
    buildString {
        append("--- ")
        append("window:${windowData.windowId} ")
        append("type:${windowData.windowType} ")
        append("pkg:${windowData.packageName ?: "unknown"} ")
        append("title:${windowData.title ?: "unknown"} ")
        if (windowData.activityName != null) {
            append("activity:${windowData.activityName} ")
        }
        append("layer:${windowData.layer} ")
        append("focused:${windowData.focused}")
        append(" ---")
    }
```

---

### Task 3.2: Unit tests for multi-window formatter

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt`

**Definition of Done**:
- [x] Test: `formatMultiWindow` with single APPLICATION window produces correct output
- [x] Test: `formatMultiWindow` with two windows (APPLICATION + SYSTEM) produces both sections
- [x] Test: window header includes `activity:` when present, omits when null
- [x] Test: `degraded=true` adds DEGRADATION_NOTE line at the top
- [x] Test: `degraded=false` does NOT add DEGRADATION_NOTE
- [x] Test: null `packageName` renders as `unknown`
- [x] Test: null `title` renders as `unknown`
- [x] Test: `buildWindowHeader()` produces correct format
- [x] Test: existing `format()` tests still pass unchanged

**Action 3.2.1**: Add test class for `formatMultiWindow`

Add a new `@Nested` class inside `CompactTreeFormatterTest`:

```kotlin
@Nested
@DisplayName("formatMultiWindow")
inner class FormatMultiWindow {
    @Test
    @DisplayName("single window produces correct output")
    fun singleWindowProducesCorrectOutput() {
        val tree = makeNode(
            id = "node_btn",
            className = "android.widget.Button",
            text = "OK",
            bounds = BoundsData(0, 0, 100, 48),
            clickable = true,
            visible = true,
            enabled = true,
        )
        val result = MultiWindowResult(
            windows = listOf(
                WindowData(
                    windowId = 0,
                    windowType = "APPLICATION",
                    packageName = "com.example.app",
                    title = "MainActivity",
                    activityName = "com.example.app.MainActivity",
                    layer = 0,
                    focused = true,
                    tree = tree,
                ),
            ),
            degraded = false,
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.contains("screen:1080x2400"))
        assertTrue(output.contains("--- window:0 type:APPLICATION pkg:com.example.app title:MainActivity activity:com.example.app.MainActivity layer:0 focused:true ---"))
        assertTrue(output.contains("node_btn"))
        assertFalse(output.contains(CompactTreeFormatter.DEGRADATION_NOTE))
    }

    @Test
    @DisplayName("two windows produce both sections")
    fun twoWindowsProduceBothSections() {
        val appTree = makeNode(id = "node_app", text = "App", clickable = true, visible = true)
        val dialogTree = makeNode(id = "node_allow", text = "Allow", clickable = true, visible = true)
        val result = MultiWindowResult(
            windows = listOf(
                WindowData(windowId = 0, windowType = "APPLICATION", packageName = "com.example", title = "Main", layer = 0, focused = false, tree = appTree),
                WindowData(windowId = 1, windowType = "SYSTEM", packageName = "com.android.permissioncontroller", title = "Permission", layer = 1, focused = true, tree = dialogTree),
            ),
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.contains("--- window:0 type:APPLICATION"))
        assertTrue(output.contains("--- window:1 type:SYSTEM"))
        assertTrue(output.contains("node_app"))
        assertTrue(output.contains("node_allow"))
    }

    @Test
    @DisplayName("activity omitted when null")
    fun activityOmittedWhenNull() {
        val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
        val result = MultiWindowResult(
            windows = listOf(
                WindowData(windowId = 0, windowType = "SYSTEM", packageName = "com.android.systemui", title = "StatusBar", activityName = null, layer = 0, focused = false, tree = tree),
            ),
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.contains("--- window:0 type:SYSTEM"))
        assertFalse(output.contains("activity:"))
    }

    @Test
    @DisplayName("degraded mode adds note")
    fun degradedModeAddsNote() {
        val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
        val result = MultiWindowResult(
            windows = listOf(WindowData(windowId = 0, windowType = "APPLICATION", tree = tree, focused = true)),
            degraded = true,
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.startsWith(CompactTreeFormatter.DEGRADATION_NOTE))
    }

    @Test
    @DisplayName("null packageName rendered as unknown")
    fun nullPackageNameRenderedAsUnknown() {
        val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
        val result = MultiWindowResult(
            windows = listOf(WindowData(windowId = 0, windowType = "APPLICATION", packageName = null, tree = tree, focused = true)),
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.contains("pkg:unknown"))
    }

    @Test
    @DisplayName("null title rendered as unknown")
    fun nullTitleRenderedAsUnknown() {
        val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
        val result = MultiWindowResult(
            windows = listOf(WindowData(windowId = 0, windowType = "APPLICATION", title = null, tree = tree, focused = true)),
        )
        val output = formatter.formatMultiWindow(result, defaultScreenInfo)
        assertTrue(output.contains("title:unknown"))
    }
}
```

**Action 3.2.2**: Add test for `buildWindowHeader()`

Add inside the `FormatMultiWindow` nested class:

```kotlin
@Test
@DisplayName("buildWindowHeader produces correct format with all fields")
fun buildWindowHeaderAllFields() {
    val wd = WindowData(
        windowId = 2,
        windowType = "INPUT_METHOD",
        packageName = "com.google.android.inputmethod.latin",
        title = "Gboard",
        activityName = null,
        layer = 5,
        focused = false,
        tree = makeNode(id = "node_x"),
    )
    val header = formatter.buildWindowHeader(wd)
    assertEquals(
        "--- window:2 type:INPUT_METHOD pkg:com.google.android.inputmethod.latin title:Gboard layer:5 focused:false ---",
        header,
    )
}

@Test
@DisplayName("buildWindowHeader includes activity when present")
fun buildWindowHeaderWithActivity() {
    val wd = WindowData(
        windowId = 0,
        windowType = "APPLICATION",
        packageName = "com.example",
        title = "Main",
        activityName = "com.example.MainActivity",
        layer = 0,
        focused = true,
        tree = makeNode(id = "node_x"),
    )
    val header = formatter.buildWindowHeader(wd)
    assertTrue(header.contains("activity:com.example.MainActivity"))
}
```

---

## User Story 4: Multi-Window Element Finder

**Goal**: Update `ElementFinder` to search for elements across multiple window trees.

**Acceptance Criteria / Definition of Done**:
- [x] `findElements()` overload accepts `List<WindowData>` and searches all trees
- [x] `findNodeById()` overload accepts `List<WindowData>` and searches all trees
- [x] Original single-tree methods unchanged (used internally)
- [x] Lint passes
- [x] Unit tests pass

### Task 4.1: Add multi-window overloads to `ElementFinder`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinder.kt`

**Definition of Done**:
- [x] `findElements(windows, by, value, exactMatch)` delegates to single-tree version for each window
- [x] `findNodeById(windows, nodeId)` searches each window's tree, returns first match
- [x] Both return results consistent with single-tree behavior

**Action 4.1.1**: Add multi-window `findElements()` overload

Add after the existing `findElements()` method:

```kotlin
/**
 * Searches all windows' trees for elements matching the criteria.
 * Delegates to [findElements] for each window's tree and aggregates results.
 *
 * @param windows The list of parsed windows to search.
 * @param by The criteria to search by.
 * @param value The value to search for.
 * @param exactMatch If true, exact match. If false, case-insensitive contains.
 * @return Aggregated list of [ElementInfo] across all windows.
 */
fun findElements(
    windows: List<WindowData>,
    by: FindBy,
    value: String,
    exactMatch: Boolean = false,
): List<ElementInfo> {
    val results = mutableListOf<ElementInfo>()
    for (windowData in windows) {
        results.addAll(findElements(windowData.tree, by, value, exactMatch))
    }
    return results
}
```

**Action 4.1.2**: Add multi-window `findNodeById()` overload

Add after the existing `findNodeById()` method:

```kotlin
/**
 * Searches all windows' trees for a node with the given [nodeId].
 * Returns the first match found (searches in window order).
 *
 * @param windows The list of parsed windows to search.
 * @param nodeId The node ID to find.
 * @return The matching [AccessibilityNodeData], or null if not found in any window.
 */
fun findNodeById(
    windows: List<WindowData>,
    nodeId: String,
): AccessibilityNodeData? {
    for (windowData in windows) {
        val found = findNodeById(windowData.tree, nodeId)
        if (found != null) return found
    }
    return null
}
```

---

### Task 4.2: Unit tests for multi-window element finder

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinderTest.kt`

**Definition of Done**:
- [x] Test: `findElements` across two windows finds elements from both
- [x] Test: `findElements` with no matches returns empty list
- [x] Test: `findNodeById` finds node in first window
- [x] Test: `findNodeById` finds node in second window
- [x] Test: `findNodeById` returns null when not found in any window
- [x] Existing single-tree tests still pass

**Action 4.2.1**: Add multi-window test class

Add a new `@Nested` class inside `ElementFinderTest`:

```kotlin
@Nested
@DisplayName("Multi-window search")
inner class MultiWindowSearch {
    private fun makeWindowData(
        windowId: Int,
        tree: AccessibilityNodeData,
    ): WindowData =
        WindowData(
            windowId = windowId,
            windowType = "APPLICATION",
            packageName = "com.example",
            title = "Test",
            layer = windowId,
            focused = windowId == 0,
            tree = tree,
        )

    @Test
    @DisplayName("findElements across two windows finds elements from both")
    fun findElementsAcrossTwoWindows() {
        val tree1 = createNode(id = "node_a", text = "Hello")
        val tree2 = createNode(id = "node_b", text = "Hello World")
        val windows = listOf(makeWindowData(0, tree1), makeWindowData(1, tree2))
        val results = finder.findElements(windows, FindBy.TEXT, "Hello", false)
        assertEquals(2, results.size)
    }

    @Test
    @DisplayName("findElements with no matches returns empty")
    fun findElementsNoMatches() {
        val tree1 = createNode(id = "node_a", text = "Foo")
        val windows = listOf(makeWindowData(0, tree1))
        val results = finder.findElements(windows, FindBy.TEXT, "Bar", false)
        assertTrue(results.isEmpty())
    }

    @Test
    @DisplayName("findNodeById finds node in second window")
    fun findNodeByIdInSecondWindow() {
        val tree1 = createNode(id = "node_a", text = "First")
        val tree2 = createNode(id = "node_b", text = "Second")
        val windows = listOf(makeWindowData(0, tree1), makeWindowData(1, tree2))
        val result = finder.findNodeById(windows, "node_b")
        assertNotNull(result)
        assertEquals("node_b", result!!.id)
    }

    @Test
    @DisplayName("findNodeById returns null when not found")
    fun findNodeByIdNotFound() {
        val tree1 = createNode(id = "node_a", text = "First")
        val windows = listOf(makeWindowData(0, tree1))
        val result = finder.findNodeById(windows, "node_nonexistent")
        assertNull(result)
    }
}
```

---

## User Story 5: Multi-Window Action Executor

**Goal**: Update `ActionExecutor` interface and `ActionExecutorImpl` to resolve and execute actions
on nodes across multiple windows.

**Acceptance Criteria / Definition of Done**:
- [x] `ActionExecutor` interface node-action methods accept `List<WindowData>` instead of single `AccessibilityNodeData`
- [x] `ActionExecutorImpl.performNodeAction()` iterates all windows to find the target node
- [x] `ActionExecutorImpl.performNodeAction()` has degraded-mode fallback via `getRootNode()` when `getAccessibilityWindows()` is empty
- [x] `ActionExecutorImpl.scroll()` uses `getScreenInfo()` instead of `getRootNode()` for screen dimensions
- [x] `findFocusedEditableNode()` searches across all window roots
- [x] Lint passes
- [x] Unit tests pass

### Task 5.1: Update `ActionExecutor` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutor.kt`

**Definition of Done**:
- [x] `clickNode`, `longClickNode`, `setTextOnNode`, `scrollNode` accept `windows: List<WindowData>`
- [x] `findAccessibilityNodeByNodeId` signature unchanged (operates on single tree)
- [x] Coordinate-based and global actions unchanged
- [x] Import for `WindowData` not needed (same package)

**Action 5.1.1**: Update node-action method signatures

Change:

```kotlin
suspend fun clickNode(
    nodeId: String,
    tree: AccessibilityNodeData,
): Result<Unit>

suspend fun longClickNode(
    nodeId: String,
    tree: AccessibilityNodeData,
): Result<Unit>

suspend fun setTextOnNode(
    nodeId: String,
    text: String,
    tree: AccessibilityNodeData,
): Result<Unit>

suspend fun scrollNode(
    nodeId: String,
    direction: ScrollDirection,
    tree: AccessibilityNodeData,
): Result<Unit>
```

To:

```kotlin
suspend fun clickNode(
    nodeId: String,
    windows: List<WindowData>,
): Result<Unit>

suspend fun longClickNode(
    nodeId: String,
    windows: List<WindowData>,
): Result<Unit>

suspend fun setTextOnNode(
    nodeId: String,
    text: String,
    windows: List<WindowData>,
): Result<Unit>

suspend fun scrollNode(
    nodeId: String,
    direction: ScrollDirection,
    windows: List<WindowData>,
): Result<Unit>
```

---

### Task 5.2: Update `ActionExecutorImpl` — `performNodeAction()` for multi-window

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt`

**Definition of Done**:
- [x] `performNodeAction()` accepts `List<WindowData>`, iterates all windows
- [x] For each window, gets real root node from service's `getAccessibilityWindows()` by matching `AccessibilityWindowInfo.getId()` to `windowData.windowId`
- [x] Walks real and parsed trees in parallel per window (existing `walkAndMatch` logic)
- [x] Returns first successful match; returns failure if not found in any window
- [x] **Degraded-mode fallback**: when `getAccessibilityWindows()` returns empty, falls back to `service.getRootNode()` and walks the parsed trees against it
- [x] All real root nodes and `AccessibilityWindowInfo` objects properly recycled
- [x] `clickNode`, `longClickNode`, `setTextOnNode`, `scrollNode` pass `windows` to `performNodeAction`

**Action 5.2.1**: Update `clickNode`, `longClickNode`, `setTextOnNode`, `scrollNode` signatures

**Import to add** (if not already present):
```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

(`WindowData` is in the same package — no import needed.)

Update each method to accept `windows: List<WindowData>` instead of `tree: AccessibilityNodeData` and pass it to `performNodeAction`.

Example for `clickNode`:

```kotlin
override suspend fun clickNode(
    nodeId: String,
    windows: List<WindowData>,
): Result<Unit> {
    return performNodeAction(nodeId, windows, "click") { realNode ->
        if (!realNode.isClickable) {
            return@performNodeAction Result.failure(
                IllegalStateException("Node '$nodeId' is not clickable"),
            )
        }
        val success = realNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(
                RuntimeException("ACTION_CLICK failed on node '$nodeId'"),
            )
        }
    }
}
```

Apply the same pattern to `longClickNode`, `setTextOnNode`, `scrollNode` — change `tree: AccessibilityNodeData` parameter to `windows: List<WindowData>` in each.

**Action 5.2.2**: Rewrite `performNodeAction()` for multi-window

> **DESIGN NOTE — Window matching by ID**: `WindowData.windowId` stores the system-assigned `AccessibilityWindowInfo.getId()` value. When `performNodeAction()` re-queries the live windows via `service.getAccessibilityWindows()`, it matches by this stable ID rather than positional index. This avoids a race condition where windows appearing, disappearing, or reordering between parsing and action could cause the positional index to point to the wrong window. The parallel tree walk (`findAccessibilityNodeByNodeId`) provides an additional safety net: even if a window ID were somehow stale, the tree structures would not match and the action would fail with "not found" rather than execute on the wrong node.
>
> **DESIGN NOTE — Degraded-mode fallback**: If `getAccessibilityWindows()` returns an empty list (the same condition that triggers degraded mode in `getFreshWindows()`), `performNodeAction()` falls back to `service.getRootNode()` and attempts to match the node against each parsed `WindowData` tree using the single real root. This preserves backward compatibility — node-based actions continue to work even when the multi-window API is unavailable.
>
> **DESIGN NOTE — AccessibilityWindowInfo recycling**: On API 34+ (our minSdk), `AccessibilityWindowInfo.recycle()` is a no-op, but we call it with `@Suppress("DEPRECATION")` for consistency with the codebase's `AccessibilityNodeInfo` recycling convention.

Replace the existing `performNodeAction()` method with:

```kotlin
@Suppress("ReturnCount", "LongMethod")
private suspend fun performNodeAction(
    nodeId: String,
    windows: List<WindowData>,
    actionName: String,
    action: (AccessibilityNodeInfo) -> Result<Unit>,
): Result<Unit> {
    val service =
        McpAccessibilityService.instance
            ?: return Result.failure(
                IllegalStateException("Accessibility service is not available"),
            )

    val realWindows = service.getAccessibilityWindows()

    // Multi-window path: match by AccessibilityWindowInfo.getId()
    if (realWindows.isNotEmpty()) {
        try {
            for (windowData in windows) {
                val realWindow =
                    realWindows.firstOrNull { it.id == windowData.windowId } ?: continue
                val realRootNode = realWindow.root ?: continue

                val realNode =
                    findAccessibilityNodeByNodeId(realRootNode, nodeId, windowData.tree)
                if (realNode != null) {
                    return try {
                        val result = action(realNode)
                        if (result.isSuccess) {
                            Log.d(
                                TAG,
                                "Node action '$actionName' succeeded on node '$nodeId' " +
                                    "in window ${windowData.windowId}",
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Node action '$actionName' failed on node '$nodeId': " +
                                    "${result.exceptionOrNull()?.message}",
                            )
                        }
                        result
                    } finally {
                        @Suppress("DEPRECATION")
                        realNode.recycle()
                        @Suppress("DEPRECATION")
                        realRootNode.recycle()
                    }
                }

                @Suppress("DEPRECATION")
                realRootNode.recycle()
            }
        } finally {
            // Recycle all AccessibilityWindowInfo objects for consistency
            for (w in realWindows) {
                @Suppress("DEPRECATION")
                w.recycle()
            }
        }

        return Result.failure(
            NoSuchElementException(
                "Node '$nodeId' not found in any window's accessibility tree",
            ),
        )
    }

    // Degraded-mode fallback: getAccessibilityWindows() returned empty,
    // fall back to getRootNode() (same fallback as getFreshWindows()).
    Log.w(TAG, "getAccessibilityWindows() returned empty, falling back to getRootNode()")
    val rootNode =
        service.getRootNode()
            ?: return Result.failure(
                IllegalStateException("No root node available"),
            )

    for (windowData in windows) {
        val realNode = findAccessibilityNodeByNodeId(rootNode, nodeId, windowData.tree)
        if (realNode != null) {
            return try {
                val result = action(realNode)
                if (result.isSuccess) {
                    Log.d(
                        TAG,
                        "Node action '$actionName' succeeded (degraded) on node '$nodeId'",
                    )
                } else {
                    Log.w(
                        TAG,
                        "Node action '$actionName' failed (degraded) on node '$nodeId': " +
                            "${result.exceptionOrNull()?.message}",
                    )
                }
                result
            } finally {
                @Suppress("DEPRECATION")
                realNode.recycle()
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    rootNode.recycle()

    return Result.failure(
        NoSuchElementException("Node '$nodeId' not found in any window's accessibility tree"),
    )
}
```

---

### Task 5.3: Update `ActionExecutorImpl.scroll()` to use `getScreenInfo()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt`

**Definition of Done**:
- [x] `scroll()` uses `service.getScreenInfo()` for screen dimensions instead of `service.getRootNode()`
- [x] No dependency on `rootInActiveWindow` for screen dimensions

**Action 5.3.1**: Replace `getRootNode()` with `getScreenInfo()` in `scroll()`

> **DESIGN NOTE — Split-screen behavioral change**: The current `scroll()` obtains screen dimensions from `rootNode.getBoundsInScreen()`, which in split-screen mode returns only the app's half of the screen. The new implementation uses `getScreenInfo()` (via `WindowManager.currentWindowMetrics`), which returns the full display dimensions. This means scroll gestures may extend beyond the app's window bounds in split-screen. This is acceptable because: (1) coordinate-based actions (`swipe`, `tap`) already use full-screen coordinates, so this makes `scroll` consistent; (2) the accessibility gesture API clips to the display anyway; (3) multi-window support makes split-screen a first-class scenario where `scrollNode()` (which scrolls via `ACTION_SCROLL_FORWARD/BACKWARD`) is the preferred scroll method within a specific window.

Change the beginning of `scroll()` from:

```kotlin
override suspend fun scroll(
    direction: ScrollDirection,
    amount: ScrollAmount,
): Result<Unit> {
    val service =
        McpAccessibilityService.instance
            ?: return Result.failure(
                IllegalStateException("Accessibility service is not available"),
            )

    val rootNode =
        service.getRootNode()
            ?: return Result.failure(
                IllegalStateException("No root node available for screen dimensions"),
            )

    val rect = Rect()
    rootNode.getBoundsInScreen(rect)
    @Suppress("DEPRECATION")
    rootNode.recycle()

    val screenWidth = rect.right.toFloat()
    val screenHeight = rect.bottom.toFloat()
```

To:

```kotlin
override suspend fun scroll(
    direction: ScrollDirection,
    amount: ScrollAmount,
): Result<Unit> {
    val service =
        McpAccessibilityService.instance
            ?: return Result.failure(
                IllegalStateException("Accessibility service is not available"),
            )

    val screenInfo = service.getScreenInfo()
    val screenWidth = screenInfo.width.toFloat()
    val screenHeight = screenInfo.height.toFloat()
```

Also remove the now-unused `import android.graphics.Rect` if it is only used by `scroll()`. Check whether `Rect` is used elsewhere in the file before removing — it IS used in `performNodeAction` (removed in Task 5.2), so it may no longer be needed. Verify during implementation.

---

### Task 5.4: Update `findFocusedEditableNode()` for multi-window

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

**Definition of Done**:
- [x] `findFocusedEditableNode()` iterates all window roots from `getAccessibilityWindows()`
- [x] Calls `findFocus(FOCUS_INPUT)` on each window's root node
- [x] Returns first editable focused node found across all windows
- [x] All root nodes properly recycled

**Action 5.4.1**: Rewrite `findFocusedEditableNode()`

**Imports to add** (if not already present):
```kotlin
import android.view.accessibility.AccessibilityWindowInfo
```

Replace the existing function (at the bottom of `TextInputTools.kt`):

```kotlin
@Suppress("ReturnCount", "MaxLineLength")
internal fun findFocusedEditableNode(accessibilityServiceProvider: AccessibilityServiceProvider): AccessibilityNodeInfo? {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service is not enabled",
        )
    }
    val rootNode = accessibilityServiceProvider.getRootNode() ?: return null
    val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    @Suppress("DEPRECATION")
    rootNode.recycle()

    if (focusedNode != null && !focusedNode.isEditable) {
        @Suppress("DEPRECATION")
        focusedNode.recycle()
        return null
    }

    return focusedNode
}
```

With:

```kotlin
@Suppress("ReturnCount")
internal fun findFocusedEditableNode(
    accessibilityServiceProvider: AccessibilityServiceProvider,
): AccessibilityNodeInfo? {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service is not enabled",
        )
    }

    // Search across all windows for the focused editable node
    val windows = accessibilityServiceProvider.getAccessibilityWindows()
    try {
        for (window in windows) {
            val rootNode = window.root ?: continue
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            @Suppress("DEPRECATION")
            rootNode.recycle()

            if (focusedNode != null) {
                if (focusedNode.isEditable) {
                    return focusedNode
                }
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            }
        }
    } finally {
        // Recycle all AccessibilityWindowInfo objects for consistency
        // (no-op on API 34+, but follows the codebase's recycling convention)
        for (w in windows) {
            @Suppress("DEPRECATION")
            w.recycle()
        }
    }

    // Fallback: try rootInActiveWindow if getWindows() returned empty
    if (windows.isEmpty()) {
        val rootNode = accessibilityServiceProvider.getRootNode() ?: return null
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        @Suppress("DEPRECATION")
        rootNode.recycle()

        if (focusedNode != null && !focusedNode.isEditable) {
            @Suppress("DEPRECATION")
            focusedNode.recycle()
            return null
        }
        return focusedNode
    }

    return null
}
```

---

### Task 5.5: Unit tests for action executor changes

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImplTest.kt`

**Definition of Done**:
- [x] Existing tests updated to pass `List<WindowData>` instead of single `AccessibilityNodeData`
- [x] Test: `clickNode` finds and clicks node in first window
- [x] Test: `clickNode` finds and clicks node in second window when not in first
- [x] Test: `clickNode` returns failure when node not found in any window
- [x] Test: `clickNode` succeeds in degraded mode (empty `getAccessibilityWindows()`, falls back to `getRootNode()`)
- [x] Test: `scroll()` works without `rootInActiveWindow` (uses `getScreenInfo()`)
- [x] All existing tests adapted and passing

**Action 5.5.1**: Update all existing tests that call node-action methods

Every existing test that calls `executor.clickNode(nodeId, tree)`, `executor.longClickNode(nodeId, tree)`, etc. must change the `tree` parameter to a `List<WindowData>` wrapping the same tree. Create a helper:

```kotlin
private fun wrapInWindows(tree: AccessibilityNodeData): List<WindowData> =
    listOf(
        WindowData(
            windowId = 0,
            windowType = "APPLICATION",
            tree = tree,
            focused = true,
        ),
    )
```

Then replace `executor.clickNode(nodeId, tree)` → `executor.clickNode(nodeId, wrapInWindows(tree))` etc.

**Action 5.5.2**: Add multi-window specific tests

Add new tests for multi-window node resolution (node found in second window, not found in any window).

**Action 5.5.3**: Add degraded-mode fallback test for `performNodeAction()`

Add a test verifying that node actions work when `getAccessibilityWindows()` returns empty:
- Mock `McpAccessibilityService.instance` with `getAccessibilityWindows()` returning empty list
- Mock `getRootNode()` returning a valid mock `AccessibilityNodeInfo`
- Create a `WindowData` with a tree that the parallel walk can match against the mock root
- Call `executor.clickNode(nodeId, windows)` — verify it succeeds via the `getRootNode()` fallback
- Verify the fallback log message ("falling back to getRootNode()") was emitted

This ensures backward compatibility: node-based actions continue to work when the multi-window API is unavailable (degraded mode).

---

## User Story 6: Multi-Window Tool Updates

**Goal**: Update `getFreshTree()` to `getFreshWindows()` and update all MCP tool handlers to use
multi-window data.

**Acceptance Criteria / Definition of Done**:
- [x] `getFreshTree()` replaced/supplemented by `getFreshWindows()` that returns `MultiWindowResult`
- [x] `GetScreenStateHandler` uses `formatMultiWindow()` and passes `MultiWindowResult`
- [x] All element action tools use `getFreshWindows()` and pass `List<WindowData>` to action executor
- [x] All text input tools use `getFreshWindows()` for tree access
- [x] Utility tools (`GetElementDetailsTool`, `WaitForElementTool`, `WaitForIdleTool`) updated
- [x] System action tools' `isReady()` checks work with new semantics
- [x] Screenshot annotation collects elements from all windows
- [x] `getFreshTree()` removed after all tools are migrated (no remaining callers)
- [x] Lint passes
- [ ] Unit tests pass

### Task 6.1: Create `getFreshWindows()` shared utility

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Definition of Done**:
- [x] `getFreshWindows()` function added
- [x] Uses `getAccessibilityWindows()` to get all windows
- [x] Parses each window's root node with `treeParser.parseTree(rootNode, "root_w${window.id}")`
- [x] Extracts window metadata: type, title, package, layer, focused
- [x] Includes activity name for focused APPLICATION window when package matches tracked package
- [x] Falls back to `getRootNode()` if `getAccessibilityWindows()` returns empty
- [x] Returns `MultiWindowResult` with `degraded` flag
- [x] Throws `McpToolException.PermissionDenied` if service not ready
- [x] Throws `McpToolException.ActionFailed` if no windows AND no root node available

**Action 6.1.1**: Add `getFreshWindows()` function

**Imports to add** (if not already present):
```kotlin
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
```

Add after the existing `getFreshTree()` function. Once all tools are migrated to `getFreshWindows()` (Tasks 6.2–6.5), remove `getFreshTree()` entirely — it will have no remaining callers:

```kotlin
/**
 * Gets a fresh multi-window accessibility snapshot by enumerating all on-screen windows
 * and parsing each window's accessibility tree.
 *
 * Falls back to single-window mode via [AccessibilityServiceProvider.getRootNode] if
 * [AccessibilityServiceProvider.getAccessibilityWindows] returns an empty list.
 *
 * @throws McpToolException.PermissionDenied if accessibility service is not connected.
 * @throws McpToolException.ActionFailed if no windows and no root node are available.
 */
@Suppress("LongMethod")
internal fun getFreshWindows(
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): MultiWindowResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
            val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()
            val windowDataList = mutableListOf<WindowData>()

            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue

                // Extract metadata BEFORE recycling rootNode
                val wId = window.id
                val windowPackage = rootNode.packageName?.toString()
                val windowTitle = window.title?.toString()
                val windowType = window.type
                val windowLayer = window.layer
                val windowFocused = window.isFocused

                val tree =
                    try {
                        treeParser.parseTree(rootNode, "root_w$wId")
                    } finally {
                        @Suppress("DEPRECATION")
                        rootNode.recycle()
                    }

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

    // Extract windowId from the root node before recycling
    val fallbackWindowId = rootNode.windowId

    val tree =
        try {
            treeParser.parseTree(rootNode, "root_w$fallbackWindowId")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }

    val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
    val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()

    return MultiWindowResult(
        windows = listOf(
            WindowData(
                windowId = fallbackWindowId,
                windowType = "APPLICATION",
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
```

> **NOTE**: All window metadata (`windowPackage`, `windowTitle`, `windowType`, `windowLayer`, `windowFocused`) is extracted BEFORE the `try/finally` block that recycles `rootNode`. This ensures no access to recycled nodes. Additionally, all `AccessibilityWindowInfo` objects are recycled in a top-level `finally` block after the window iteration loop completes. While `recycle()` is a no-op on API 34+, this follows the codebase's consistent recycling convention established in `performNodeAction()`.

---

### Task 6.2: Update `GetScreenStateHandler`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Definition of Done**:
- [x] Uses `getFreshWindows()` instead of manual `isReady()` + `getRootNode()` + `parseTree()`
- [x] Passes `MultiWindowResult` to `compactTreeFormatter.formatMultiWindow()`
- [x] Removes manual `isReady()` check (handled by `getFreshWindows()`)
- [x] Removes separate `getRootNode()` + `parseTree()` calls
- [x] Removes separate `getCurrentPackageName()` / `getCurrentActivityName()` calls
- [x] Screenshot annotation collects elements from ALL windows' trees
- [x] `collectOnScreenElements()` updated to work with `List<WindowData>`

**Action 6.2.1**: Rewrite `execute()` method

**Imports to add** (if not already present):
```kotlin
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
```

(`getFreshWindows` is in the same `mcp.tools` package — no import needed.)

Replace the current `execute()` method (lines 49–152) with:

```kotlin
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
```

**Action 6.2.2**: Update `collectOnScreenElements()` to accept `List<WindowData>`

Change from:

```kotlin
private fun collectOnScreenElements(
    node: AccessibilityNodeData,
    result: MutableList<AccessibilityNodeData> = mutableListOf(),
): List<AccessibilityNodeData>
```

To:

```kotlin
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
```

---

### Task 6.3: Update element action tools

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Definition of Done**:
- [x] `FindElementsTool.execute()` uses `getFreshWindows()` and multi-window `elementFinder.findElements(windows, ...)`
- [x] `ClickElementTool.execute()` uses `getFreshWindows()` and passes `windows` to `actionExecutor.clickNode()`
- [x] `LongClickElementTool.execute()` uses `getFreshWindows()` and passes `windows` to `actionExecutor.longClickNode()`
- [x] `ScrollToElementTool.execute()` uses `getFreshWindows()` and multi-window finder + executor
- [x] `ScrollToElementTool` has `findContainingTree()` helper to locate the window tree containing a node

**Action 6.3.1**: Update `FindElementsTool.execute()`

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)` and use `elementFinder.findElements(result.windows, findBy, value, exactMatch)`.

**Action 6.3.2**: Update `ClickElementTool.execute()`

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)` and use `actionExecutor.clickNode(elementId, result.windows)`.

**Action 6.3.3**: Update `LongClickElementTool.execute()`

Same pattern as `ClickElementTool`.

**Action 6.3.4**: Update `ScrollToElementTool.execute()`

The key insight: `findScrollableAncestor()` and `findPathToNode()` walk within a single tree. Since a node belongs to exactly one window's tree, we need to find which window's tree contains the target node and then operate on that tree only.

Replace `execute()` with:

```kotlin
@Suppress("ThrowsCount")
suspend fun execute(arguments: JsonObject?): CallToolResult {
    val elementId =
        arguments?.get("element_id")?.jsonPrimitive?.contentOrNull
            ?: throw McpToolException.InvalidParams("Missing required parameter 'element_id'")

    if (elementId.isEmpty()) {
        throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
    }

    // Parse multi-window trees and find the element
    var result = getFreshWindows(treeParser, accessibilityServiceProvider)
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

    // Attempt to scroll into view
    for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
        val scrollResult =
            actionExecutor.scrollNode(scrollableAncestorId, ScrollDirection.DOWN, result.windows)
        if (scrollResult.isFailure) {
            throw McpToolException.ActionFailed(
                "Scroll failed on ancestor '$scrollableAncestorId': " +
                    "${scrollResult.exceptionOrNull()?.message}",
            )
        }

        // Small delay to let UI settle after scroll
        kotlinx.coroutines.delay(SCROLL_SETTLE_DELAY_MS)

        // Re-parse and check visibility
        result = getFreshWindows(treeParser, accessibilityServiceProvider)
        node = elementFinder.findNodeById(result.windows, elementId) ?: continue

        if (node.visible) {
            Log.d(
                TAG,
                "scroll_to_element: element '$elementId' became visible " +
                    "after $attempt scroll(s)",
            )
            return McpToolUtils.textResult(
                "Scrolled to element '$elementId' ($attempt scroll(s))",
            )
        }
    }

    throw McpToolException.ActionFailed(
        "Element '$elementId' not visible after $MAX_SCROLL_ATTEMPTS scroll attempts",
    )
}
```

Add helper `findContainingTree()` after `findPathToNode()`:

```kotlin
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
```

The existing `findScrollableAncestor()` and `findPathToNode()` remain unchanged — they already operate on a single `AccessibilityNodeData` tree. We just pass the correct window's tree to them via `findContainingTree()`.

---

### Task 6.4: Update text input tools

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

**Definition of Done**:
- [x] `TypeAppendTextTool`, `TypeInsertTextTool`, `TypeReplaceTextTool`, `TypeClearTextTool` use `getFreshWindows()` and pass `windows` to `actionExecutor.clickNode()`
- [x] `PressKeyTool` unchanged (uses `findFocusedEditableNode()` which was updated in Task 5.4, and global actions which don't need trees)

**Action 6.4.1**: Update `TypeAppendTextTool.execute()`

**Imports to add** (if not already present — once per file, applies to all tools in this file):
```kotlin
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
```

(`getFreshWindows` is in the same `mcp.tools` package — no import needed.)

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)` and use `actionExecutor.clickNode(elementId, result.windows)`.

**Action 6.4.2**: Update `TypeInsertTextTool.execute()`

Same pattern.

**Action 6.4.3**: Update `TypeReplaceTextTool.execute()`

Same pattern.

**Action 6.4.4**: Update `TypeClearTextTool.execute()`

Same pattern.

---

### Task 6.5: Update utility tools

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Definition of Done**:
- [x] `GetElementDetailsTool` uses `getFreshWindows()` and `elementFinder.findNodeById(windows, id)`
- [x] `WaitForElementTool` uses `getFreshWindows()` and `elementFinder.findElements(windows, ...)`
- [x] `WaitForIdleTool` uses `getFreshWindows()` and generates fingerprints from all windows
- [x] Window appearing/disappearing between polls is correctly detected as "UI not idle" (fingerprint changes because the set of walked trees changes)

**Imports to add** (if not already present — once per file, applies to all tools in this file):
```kotlin
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
```

(`getFreshWindows` is in the same `mcp.tools` package — no import needed.)

**Action 6.5.1**: Update `GetElementDetailsTool.execute()`

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)` and use `elementFinder.findNodeById(result.windows, id)`.

**Action 6.5.2**: Update `WaitForElementTool.execute()`

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)` and use `elementFinder.findElements(result.windows, ...)`.

**Action 6.5.3**: Update `WaitForIdleTool.execute()` and `TreeFingerprint`

Change `val tree = getFreshTree(...)` to `val result = getFreshWindows(...)`. The `TreeFingerprint` currently generates a fingerprint from a single `AccessibilityNodeData`. Update to generate a combined fingerprint from all windows' trees.

**Import to add to `TreeFingerprint.kt`** (if not already present):
```kotlin
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
```

The implementor should generate the fingerprint by walking all windows' trees sequentially (same as walking one big tree). Add a helper method to `TreeFingerprint`:

```kotlin
fun generate(windows: List<WindowData>): IntArray {
    val fingerprint = IntArray(FINGERPRINT_SIZE)
    for (windowData in windows) {
        populateFingerprint(windowData.tree, fingerprint)
    }
    return fingerprint
}
```

> **IMPORTANT — Window change = UI not idle**: A window appearing or disappearing between two consecutive fingerprint polls (e.g., a permission dialog popping up, a toast appearing, or a dialog being dismissed) naturally changes the combined histogram because the set of trees being walked changes. This means `WaitForIdleTool` correctly detects window changes as "the UI is not idle" — no additional logic is needed beyond using `generate(windows)`. This is the desired behavior: if the window set itself changes, the UI has not settled.

---

### Task 6.6: Update system action tools `isReady()` checks

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt`

**Definition of Done**:
- [x] `executeSystemAction()` still checks `isReady()` — but now `isReady()` only checks service connected, so this works correctly during permission dialogs
- [x] No code changes needed if `isReady()` semantics are already updated in US1

**Action 6.6.1**: Verify — no code change needed

The `executeSystemAction()` helper in `SystemActionTools.kt` calls `accessibilityServiceProvider.isReady()`. With the updated semantics (US1 Task 1.2), `isReady()` now only checks `instance != null`, which is the correct check for global actions (press_back, etc.) that don't need window trees. No code change needed here.

---

### Task 6.7: Unit tests for tool updates

**Definition of Done**:
- [x] `ScreenIntrospectionToolsTest` updated: mock returns for `getAccessibilityWindows()` and multi-window flow
- [x] `ElementActionToolsTest` updated: `getFreshWindows()` mocking, `List<WindowData>` passing
- [x] `TextInputToolsTest` updated: same pattern
- [x] `UtilityToolsTest` updated: same pattern
- [x] `GetElementDetailsToolTest` updated: same pattern
- [x] `SystemActionToolsTest`: verify no changes needed (isReady semantics cover it)
- [x] Test: `getFreshWindows()` fallback path returns `degraded=true` with `DEGRADATION_NOTE` in output
- [x] Test: `getFreshWindows()` throws `ActionFailed` when all windows have null roots
- [x] Test: `getFreshWindows()` throws `ActionFailed` when no windows AND no root node
- [x] Test: `ScrollToElementTool` scrolls to element in non-primary window
- [x] Test: `TreeFingerprint.generate(windows)` produces correct deterministic histogram
- [x] Test: `WaitForIdleTool` detects window appearing/disappearing as "UI not idle"
- [ ] All tests pass

**Action 6.7.1**: Update test helpers and mocking

In each test file that previously mocked `getRootNode()` + `treeParser.parseTree()`, update to mock `getAccessibilityWindows()` returning mock `AccessibilityWindowInfo` objects, or mock the higher-level tool behavior by mocking `getFreshWindows()` return values.

Since `getFreshWindows()` is a top-level `internal` function (not mockable easily), the tests should mock the underlying calls:
- `accessibilityServiceProvider.isReady()` → `true`
- `accessibilityServiceProvider.getAccessibilityWindows()` → list of mock `AccessibilityWindowInfo`
- Each mock window: `window.id` → a test value (e.g., `0`), `window.root` → mock `AccessibilityNodeInfo`, `window.type` → `TYPE_APPLICATION`, `window.title` → `"Test"`, `window.layer` → `0`, `window.isFocused` → `true`, `window.recycle()` → `Unit` (relaxed or explicit mock)
- `treeParser.parseTree(rootNode, "root_w<MOCK_WINDOW_ID>")` → mock `AccessibilityNodeData` — the `rootParentId` argument **must match** `"root_w"` + the `window.id` value from the mock `AccessibilityWindowInfo` (e.g., if `window.id` returns `0`, use `"root_w0"`; if `window.id` returns `42`, use `"root_w42"`)

**Action 6.7.2**: Add `getFreshWindows()` fallback path test

Add a test that verifies the degraded/fallback path:
- Mock `accessibilityServiceProvider.isReady()` → `true`
- Mock `accessibilityServiceProvider.getAccessibilityWindows()` → empty list
- Mock `accessibilityServiceProvider.getRootNode()` → mock `AccessibilityNodeInfo`
- Mock `rootNode.windowId` → some test ID (e.g., `42`)
- Mock `treeParser.parseTree(rootNode, "root_w42")` → mock `AccessibilityNodeData`
- Call a tool that uses `getFreshWindows()` (e.g., `get_screen_state`)
- Verify the output contains `DEGRADATION_NOTE`
- Verify the output contains a single window section with `type:APPLICATION`

**Action 6.7.3**: Add `getFreshWindows()` all-null-roots test

Add a test that verifies the error when all windows have null roots:
- Mock `accessibilityServiceProvider.isReady()` → `true`
- Mock `accessibilityServiceProvider.getAccessibilityWindows()` → list of mock `AccessibilityWindowInfo` where every `window.root` returns `null`, `window.recycle()` → `Unit`
- Call a tool that uses `getFreshWindows()`
- Verify it throws `McpToolException.ActionFailed` with message "All windows returned null root nodes."
- Verify `window.recycle()` was called for each mock window (recycling must happen even in the error path)

**Action 6.7.4**: Add `getFreshWindows()` no-windows-no-root test

Add a test that verifies the error when no windows AND no root node:
- Mock `accessibilityServiceProvider.isReady()` → `true`
- Mock `accessibilityServiceProvider.getAccessibilityWindows()` → empty list
- Mock `accessibilityServiceProvider.getRootNode()` → `null`
- Call a tool that uses `getFreshWindows()`
- Verify it throws `McpToolException.ActionFailed` with message containing "No windows available"

**Action 6.7.5**: Add `ScrollToElementTool` multi-window test

Add a test that verifies scrolling to an element in a non-primary window:
- Mock multi-window setup with two windows
- Target element in second window (e.g., not visible, with a scrollable ancestor in the same window)
- Verify the tool finds the element in the correct window's tree and scrolls within that window

**Action 6.7.6**: Add `TreeFingerprint.generate(windows)` unit test

Add a unit test in `TreeFingerprintTest.kt`:
- Create two `WindowData` objects with different trees
- Verify `generate(windows)` produces a non-zero histogram
- Verify `generate(windows)` with the same windows returns the same histogram (deterministic)
- Verify `generate(windows)` with different windows returns a different histogram
- Verify `generate(singleWindowList)` matches `generate(singleTree)` when the tree is the same (walking one window sequentially is equivalent to walking that tree alone)

**Action 6.7.7**: Add `WaitForIdleTool` window-change test

Add a test that verifies a window appearing/disappearing between polls is detected as "UI not idle":
- First poll: mock `getFreshWindows()` returns 1 window → `TreeFingerprint.generate()` produces fingerprint A
- Second poll: mock `getFreshWindows()` returns 2 windows (new dialog appeared) → `TreeFingerprint.generate()` produces fingerprint B
- Verify fingerprint A != fingerprint B (window change = UI not idle)
- Verify the tool does NOT report "UI is idle" after these two polls

A window appearing or disappearing means the UI is NOT idle — the fingerprint naturally changes because the multi-window `generate()` walks all trees sequentially, and adding/removing a window changes the combined histogram. This is correct behavior and the test verifies it explicitly.

---

## User Story 7: Integration Tests and Documentation

**Goal**: Update integration tests and documentation to reflect multi-window support.

**Acceptance Criteria / Definition of Done**:
- [ ] `McpIntegrationTestHelper` updated for multi-window mocking
- [ ] Existing integration tests updated and passing
- [ ] New integration test: multi-window `get_screen_state` returns window sections
- [ ] New integration test: `find_elements` finds elements across windows
- [ ] New integration test: `click_element` clicks element in non-primary window
- [ ] `docs/MCP_TOOLS.md` updated with new TSV format
- [ ] `docs/ARCHITECTURE.md` updated with multi-window architecture
- [ ] All tests pass
- [ ] Build succeeds

### Task 7.1: Update `McpIntegrationTestHelper`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

**Definition of Done**:
- [ ] Mock dependencies support multi-window mocking
- [ ] Helper method to set up multi-window mock state

**Action 7.1.1**: Update mock setup for multi-window

Add a helper method to `McpIntegrationTestHelper` that configures the mock `accessibilityServiceProvider` to return multi-window data via `getAccessibilityWindows()`.

---

### Task 7.2: Update existing integration tests

**Definition of Done**:
- [ ] All existing integration tests updated to mock `getAccessibilityWindows()` where they previously mocked `getRootNode()` + `parseTree()`
- [ ] Tests that verify TSV output format updated for new multi-window format
- [ ] All tests pass

**Action 7.2.1**: Update `ScreenIntrospectionIntegrationTest`

Update to mock multi-window responses and verify the new TSV format with window headers.

**Action 7.2.2**: Update `ElementActionIntegrationTest`

Update mocking to use multi-window — mock `getAccessibilityWindows()` and `parseTree()` with `rootParentId`.

**Action 7.2.3**: Update `TextInputIntegrationTest`

Same pattern.

**Action 7.2.4**: Update `UtilityIntegrationTest`

Same pattern.

**Action 7.2.5**: Update `ErrorHandlingIntegrationTest`

Same pattern.

**Action 7.2.6**: Update any other impacted integration tests

Check and update: `SystemActionIntegrationTest`, `TouchActionIntegrationTest`, `GestureIntegrationTest`, `AppManagementToolsIntegrationTest`, `McpProtocolIntegrationTest`, `AuthIntegrationTest`, `FileToolsIntegrationTest`.

---

### Task 7.3: Add new multi-window integration tests

**Definition of Done**:
- [ ] Test: `get_screen_state` with two windows returns both window sections in TSV
- [ ] Test: `find_elements` finds element in system dialog window
- [ ] Test: `click_element` clicks element in non-primary window
- [ ] Test: `get_screen_state` in degraded mode includes degradation note

**Action 7.3.1**: Add multi-window scenarios to `ScreenIntrospectionIntegrationTest`

**Action 7.3.2**: Add multi-window scenarios to `ElementActionIntegrationTest`

---

### Task 7.4: Update documentation

**Definition of Done**:
- [ ] `docs/MCP_TOOLS.md`: `get_screen_state` tool documentation updated with new TSV format, window headers, degraded mode
- [ ] `docs/ARCHITECTURE.md`: multi-window architecture described (data flow, `getWindows()` API, fallback)

**Action 7.4.1**: Update `docs/MCP_TOOLS.md`

Update the `get_screen_state` section to show the new multi-window TSV format, explain window headers, and document the degraded mode fallback.

**Action 7.4.2**: Update `docs/ARCHITECTURE.md`

Add a section on multi-window support: how `getWindows()` is used, the `WindowData` model, the fallback mechanism, and the impact on element ID generation.

---

## User Story 8: Final Verification

**Goal**: Comprehensive verification that all changes are correct, consistent, and complete.

**Acceptance Criteria / Definition of Done**:
- [ ] All linters pass (`make lint`)
- [ ] All unit tests pass (`make test-unit`)
- [ ] All integration tests pass (`make test-integration`)
- [ ] Full build succeeds (`make build`)
- [ ] Ground-up review of every changed file confirms consistency with this plan

### Task 8.1: Run all linters

- [ ] `./gradlew ktlintCheck` — no errors
- [ ] `./gradlew detekt` — no errors

### Task 8.2: Run all tests

- [ ] `make test-unit` — all pass
- [ ] `make test-integration` — all pass
- [ ] `make test` — all pass

### Task 8.3: Build verification

- [ ] `make build` — succeeds without warnings

### Task 8.4: Ground-up implementation review

**Definition of Done**:
- [ ] Re-read every changed source file top-to-bottom
- [ ] Verify `WindowData` fields match the agreed design
- [ ] Verify `isReady()` only checks `instance != null`
- [ ] Verify `getAccessibilityWindows()` is used everywhere that previously used `getRootNode()` for tree parsing
- [ ] Verify `getRootNode()` is ONLY used in fallback paths
- [ ] Verify node IDs use `"root_w$windowId"` parent ID (using `AccessibilityWindowInfo.getId()`) in multi-window mode
- [ ] Verify node IDs use `"root_w$windowId"` parent ID (using `rootNode.windowId`) in fallback (degraded) mode
- [ ] Verify `CompactTreeFormatter.formatMultiWindow()` output matches the agreed TSV format exactly
- [ ] Verify window header includes `activity:` only when available
- [ ] Verify degradation note appears only when `degraded = true`
- [ ] Verify `ElementFinder` multi-window methods search all windows
- [ ] Verify `ActionExecutorImpl.performNodeAction()` matches windows by `AccessibilityWindowInfo.getId()` (not positional index)
- [ ] Verify `ActionExecutorImpl.performNodeAction()` has degraded-mode fallback via `getRootNode()` when `getAccessibilityWindows()` is empty
- [ ] Verify `AccessibilityWindowInfo` objects are recycled in `performNodeAction()` after use
- [ ] Verify `AccessibilityWindowInfo` objects are recycled in `getFreshWindows()` after use (top-level `finally` block)
- [ ] Verify `AccessibilityWindowInfo` objects are recycled in `findFocusedEditableNode()` after use (`finally` block)
- [ ] Verify `ActionExecutorImpl.scroll()` uses `getScreenInfo()` not `getRootNode()`
- [ ] Verify `findFocusedEditableNode()` searches all window roots
- [ ] Verify `getFreshWindows()` fallback sets `degraded = true`
- [ ] Verify `collectOnScreenElements()` collects from all windows for screenshot annotation
- [ ] Verify no TODOs, no commented-out code, no stubs remain
- [ ] Verify all new code has KDoc comments
- [ ] Verify all test assertions are meaningful (not just `assertTrue(true)`)
- [ ] Verify documentation matches implementation

---

## Files Changed Summary

### Source files (main)

| File | Changes |
|---|---|
| `services/accessibility/AccessibilityTreeParser.kt` | Add `WindowData`, `MultiWindowResult` classes; add `rootParentId` param to `parseTree()`; add `mapWindowType()` |
| `services/accessibility/McpAccessibilityService.kt` | Change `isReady()` semantics; add `getAccessibilityWindows()` |
| `services/accessibility/AccessibilityServiceProvider.kt` | Add `getAccessibilityWindows()` to interface |
| `services/accessibility/AccessibilityServiceProviderImpl.kt` | Implement `getAccessibilityWindows()` |
| `services/accessibility/CompactTreeFormatter.kt` | Add `formatMultiWindow()`; add `buildWindowHeader()`; add `DEGRADATION_NOTE` |
| `services/accessibility/ElementFinder.kt` | Add multi-window `findElements()` and `findNodeById()` overloads |
| `services/accessibility/ActionExecutor.kt` | Change node-action signatures: `tree` → `windows: List<WindowData>` |
| `services/accessibility/ActionExecutorImpl.kt` | Rewrite `performNodeAction()` for multi-window (match by `AccessibilityWindowInfo.getId()`); update `scroll()` to use `getScreenInfo()` |
| `mcp/tools/ElementActionTools.kt` | Add `getFreshWindows()`; add `findContainingTree()` in `ScrollToElementTool`; update all tool handlers |
| `mcp/tools/ScreenIntrospectionTools.kt` | Use `getFreshWindows()` + `formatMultiWindow()`; update `collectOnScreenElements()` |
| `mcp/tools/TextInputTools.kt` | Update type tools to use `getFreshWindows()`; rewrite `findFocusedEditableNode()` |
| `mcp/tools/UtilityTools.kt` | Update `GetElementDetailsTool`, `WaitForElementTool`, `WaitForIdleTool` |
| `services/accessibility/TreeFingerprint.kt` | Add `generate(windows: List<WindowData>)` overload |
| `mcp/tools/SystemActionTools.kt` | No changes needed (isReady semantics handled by US1) |

### Test files

| File | Changes |
|---|---|
| `services/accessibility/AccessibilityTreeParserTest.kt` | Tests for `rootParentId` and `mapWindowType()` |
| `services/accessibility/CompactTreeFormatterTest.kt` | Tests for `formatMultiWindow()` and `buildWindowHeader()` |
| `services/accessibility/ElementFinderTest.kt` | Tests for multi-window search overloads |
| `services/accessibility/ActionExecutorImplTest.kt` | Update existing tests; add multi-window resolution tests |
| `mcp/tools/ScreenIntrospectionToolsTest.kt` | Update for multi-window flow |
| `mcp/tools/ElementActionToolsTest.kt` | Update for `getFreshWindows()` |
| `mcp/tools/TextInputToolsTest.kt` | Update for multi-window |
| `mcp/tools/UtilityToolsTest.kt` | Update for multi-window |
| `mcp/tools/GetElementDetailsToolTest.kt` | Update for multi-window |
| `integration/McpIntegrationTestHelper.kt` | Add multi-window mock helpers |
| Various integration tests | Update mocking, verify new format |

### Documentation

| File | Changes |
|---|---|
| `docs/MCP_TOOLS.md` | Update `get_screen_state` format documentation |
| `docs/ARCHITECTURE.md` | Add multi-window architecture section |
