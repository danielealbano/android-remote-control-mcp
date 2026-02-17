# Plan 23 — Annotated Screenshot & Readable TSV Flags

## Summary

Enhance the `get_screen_state` tool with two improvements:

1. **Annotated screenshot**: When `include_screenshot=true`, overlay red dashed bounding boxes with element ID hash labels on the screenshot for all on-screen TSV elements. Replaces the current clean screenshot.
2. **Readable TSV flags**: Replace single-letter flags (`vclfsen`) with comma-separated abbreviated words using a legend. Add `on`/`off` (onscreen/offscreen) as a flag replacing the old `v` flag.

### Design Decisions (from discussion)

- Only annotated screenshot is returned (no clean screenshot alongside it).
- Bounding box labels show only the hash part of the element ID (e.g., `a3f2` not `node_a3f2`).
- Bounding boxes: 2px red dashed outline, semi-transparent red background pill label with white bold text.
- Only on-screen elements that pass `shouldKeepNode` get bounding boxes.
- Off-screen elements are included in the TSV with the `off` flag but have no bounding box.
- Note lines (flags legend, offscreen hint) are always present.
- No per-element screenshots.
- No interleaved `TextContent` captions.
- **All elements are annotated, including password/sensitive fields.** This is intentional — the MCP server must allow models to identify and interact with password fields. Android already masks password text with dots/asterisks at the display level, so the screenshot does not expose actual password content.

### Flag Abbreviation Map

| Abbreviation | Meaning        |
|-------------|----------------|
| `on`        | onscreen       |
| `off`       | offscreen      |
| `clk`       | clickable      |
| `lclk`      | longClickable  |
| `foc`       | focusable      |
| `scr`       | scrollable     |
| `edt`       | editable       |
| `ena`       | enabled        |

### New TSV Format Example

```
note:structural-only nodes are omitted from the tree
note:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account
note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled
note:offscreen items require scroll_to_element before interaction
app:com.android.calculator2 activity:.Calculator
screen:1080x2400 density:420 orientation:portrait
id	class	text	desc	res_id	bounds	flags
node_1a2b	Button	7	-	com.android.calculator2:id/digit_7	50,800,270,1000	on,clk,ena
node_c3d4	EditText	-	Type here	input_name	50,500,500,560	on,clk,foc,edt,ena
node_e5f6	Button	Hidden	-	-	100,2600,300,2660	off,clk,ena
```

---

## User Story 1: Readable TSV Flags

**Goal**: Replace single-letter flags with comma-separated abbreviated words and add `on`/`off` visibility flag.

**Acceptance Criteria / Definition of Done**:
- [x] The flags column uses comma-separated abbreviated words: `on`/`off`, `clk`, `lclk`, `foc`, `scr`, `edt`, `ena`
- [x] The `on`/`off` flag is always the first flag in the list
- [x] A flags legend note line is always present in the TSV output
- [x] An offscreen hint note line is always present in the TSV output
- [x] The old single-letter `v` flag is removed and replaced by `on`/`off`
- [x] `ScreenIntrospectionToolsTest.sampleCompactOutput` fixture updated with new flag format and note lines
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatterTest"`
- [x] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`
- [x] Lint passes on changed files

---

### Task 1.1: Update `CompactTreeFormatter` — flag format and note lines

**Acceptance Criteria**:
- [x] `buildFlags` returns comma-separated abbreviated words with `on`/`off` as the first flag
- [x] New constants for flag abbreviations and note lines are added
- [x] `format()` outputs the flags legend note line and offscreen hint note line
- [x] Lint passes: `./gradlew ktlintCheck`

#### Action 1.1.1: Add new constants to `CompactTreeFormatter.companion`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`
**Lines**: 170-185 (companion object)

**What**: Add new constants for flag abbreviations and note lines. Remove or repurpose old single-char approach.

```diff
 companion object {
     private const val SEP = "\t"
-    private const val FLAG_COUNT = 7
     const val COLUMN_SEPARATOR = "\t"
     const val NULL_VALUE = "-"
     const val MAX_TEXT_LENGTH = 100
     const val TRUNCATION_SUFFIX = "...truncated"
     const val NOTE_LINE = "note:structural-only nodes are omitted from the tree"
     const val NOTE_LINE_CUSTOM_ELEMENTS =
         "note:certain elements are custom and will not be properly reported, " +
             "if needed or if tools are not working as expected set " +
             "include_screenshot=true to see the screen and take what you see into account"
+    const val NOTE_LINE_FLAGS_LEGEND =
+        "note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable " +
+            "foc=focusable scr=scrollable edt=editable ena=enabled"
+    const val NOTE_LINE_OFFSCREEN_HINT =
+        "note:offscreen items require scroll_to_element before interaction"
+    const val FLAG_ONSCREEN = "on"
+    const val FLAG_OFFSCREEN = "off"
+    const val FLAG_CLICKABLE = "clk"
+    const val FLAG_LONG_CLICKABLE = "lclk"
+    const val FLAG_FOCUSABLE = "foc"
+    const val FLAG_SCROLLABLE = "scr"
+    const val FLAG_EDITABLE = "edt"
+    const val FLAG_ENABLED = "ena"
+    private const val FLAG_SEPARATOR = ","
     const val HEADER =
         "id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}" +
             "desc${COLUMN_SEPARATOR}res_id${COLUMN_SEPARATOR}bounds${COLUMN_SEPARATOR}flags"
 }
```

#### Action 1.1.2: Update `buildFlags` method and its KDoc

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`
**Lines**: 154-168 (`buildFlags` method and its KDoc)

**What**: Replace single-letter flag building with comma-separated abbreviated words using `buildString` (zero intermediate allocations). Add `on`/`off` as the first flag based on the `visible` property. Update the KDoc to reflect the new flag format.

```diff
-        /**
-         * Builds the flags string for a node.
-         * Order: v, c, l, f, s, e, n
-         */
-        internal fun buildFlags(node: AccessibilityNodeData): String {
-            val sb = StringBuilder(FLAG_COUNT)
-            if (node.visible) sb.append('v')
-            if (node.clickable) sb.append('c')
-            if (node.longClickable) sb.append('l')
-            if (node.focusable) sb.append('f')
-            if (node.scrollable) sb.append('s')
-            if (node.editable) sb.append('e')
-            if (node.enabled) sb.append('n')
-            return sb.toString()
-        }
+        /**
+         * Builds the comma-separated flags string for a node.
+         * The first flag is always `on` (onscreen) or `off` (offscreen).
+         * Subsequent flags are appended only when `true`.
+         * Order: on/off, clk, lclk, foc, scr, edt, ena
+         */
+        internal fun buildFlags(node: AccessibilityNodeData): String = buildString {
+            append(if (node.visible) FLAG_ONSCREEN else FLAG_OFFSCREEN)
+            if (node.clickable) { append(FLAG_SEPARATOR); append(FLAG_CLICKABLE) }
+            if (node.longClickable) { append(FLAG_SEPARATOR); append(FLAG_LONG_CLICKABLE) }
+            if (node.focusable) { append(FLAG_SEPARATOR); append(FLAG_FOCUSABLE) }
+            if (node.scrollable) { append(FLAG_SEPARATOR); append(FLAG_SCROLLABLE) }
+            if (node.editable) { append(FLAG_SEPARATOR); append(FLAG_EDITABLE) }
+            if (node.enabled) { append(FLAG_SEPARATOR); append(FLAG_ENABLED) }
+        }
```

**Note**: Uses `buildString` instead of `mutableListOf + joinToString` to avoid intermediate list allocation and the `joinToString` overhead. This keeps allocation minimal (single `StringBuilder`), similar to the original implementation.

#### Action 1.1.3: Update class-level KDoc and `format()` to include new note lines

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`
**Lines**: 5-16 (class-level KDoc) and 35-65 (`format` method)

**What**: First, update the class-level KDoc to reflect the new output format with 4 note lines (structural, custom elements, flags legend, offscreen hint) and adjusted line numbers. Then, add the flags legend note line after the custom elements note, and the offscreen hint note line after the flags legend in the `format()` method.

**Class-level KDoc update**:
```diff
 /**
  * Formats an [AccessibilityNodeData] tree into a compact flat TSV representation
  * optimized for LLM token efficiency.
  *
  * Output format:
  * - Line 1: `note:structural-only nodes are omitted from the tree`
  * - Line 2: `note:certain elements are custom and will not be properly reported, ...`
- * - Line 3: `app:<package> activity:<activity>`
- * - Line 4: `screen:<w>x<h> density:<dpi> orientation:<orientation>`
- * - Line 5: TSV header: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
- * - Lines 6+: one TSV row per kept node (flat, no depth)
+ * - Line 3: `note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled`
+ * - Line 4: `note:offscreen items require scroll_to_element before interaction`
+ * - Line 5: `app:<package> activity:<activity>`
+ * - Line 6: `screen:<w>x<h> density:<dpi> orientation:<orientation>`
+ * - Line 7: TSV header: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
+ * - Lines 8+: one TSV row per kept node (flat, no depth)
```

**`format()` method update**:

```diff
     fun format(
         tree: AccessibilityNodeData,
         packageName: String,
         activityName: String,
         screenInfo: ScreenInfo,
     ): String {
         val sb = StringBuilder()

         // Line 1: note
         sb.appendLine(NOTE_LINE)

         // Line 2: note about custom elements
         sb.appendLine(NOTE_LINE_CUSTOM_ELEMENTS)

+        // Line 3: flags legend
+        sb.appendLine(NOTE_LINE_FLAGS_LEGEND)
+
+        // Line 4: offscreen hint
+        sb.appendLine(NOTE_LINE_OFFSCREEN_HINT)
+
-        // Line 3: app metadata
+        // Line 5: app metadata
         sb.appendLine("app:$packageName activity:$activityName")

-        // Line 4: screen info
+        // Line 6: screen info
         sb.appendLine(
             "screen:${screenInfo.width}x${screenInfo.height} " +
                 "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
         )

-        // Line 5: header
+        // Line 7: header
         sb.appendLine(HEADER)

-        // Lines 6+: walk tree and append kept nodes
+        // Lines 8+: walk tree and append kept nodes
         walkNode(sb, tree)

         return sb.toString().trimEnd('\n')
     }
```

---

### Task 1.2: Update `CompactTreeFormatterTest` unit tests

**Acceptance Criteria**:
- [x] All `buildFlags` tests updated to expect new comma-separated format
- [x] All `format` tests updated to expect new note lines and correct line indices
- [x] New test cases added for `on`/`off` flags
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatterTest"`

#### Action 1.2.1: Update `buildFlags` tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt`
**Lines**: 401-437 (`BuildFlagsTests` inner class)

**What**: Update expected values in all `buildFlags` tests to use the new comma-separated format. Add test for `off` flag.

```diff
 @Nested
 @DisplayName("buildFlags")
 inner class BuildFlagsTests {
     @Test
     @DisplayName("includes all set flags in correct order")
     fun includesAllSetFlagsInCorrectOrder() {
         val node =
             makeNode(
                 visible = true,
                 clickable = true,
                 longClickable = true,
                 focusable = true,
                 scrollable = true,
                 editable = true,
                 enabled = true,
             )
-        assertEquals("vclfsen", formatter.buildFlags(node))
+        assertEquals("on,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
     }

     @Test
     @DisplayName("includes only set flags")
     fun includesOnlySetFlags() {
         val node =
             makeNode(
                 visible = true,
                 clickable = true,
                 enabled = true,
             )
-        assertEquals("vcn", formatter.buildFlags(node))
+        assertEquals("on,clk,ena", formatter.buildFlags(node))
     }

     @Test
-    @DisplayName("returns empty string when no flags set")
-    fun returnsEmptyStringWhenNoFlagsSet() {
-        assertEquals("", formatter.buildFlags(makeNode()))
+    @DisplayName("returns off when no flags set except visibility")
+    fun returnsOffWhenNoFlagsSet() {
+        assertEquals("off", formatter.buildFlags(makeNode()))
     }
+
+    @Test
+    @DisplayName("includes off flag for non-visible nodes")
+    fun includesOffFlagForNonVisibleNodes() {
+        val node =
+            makeNode(
+                visible = false,
+                clickable = true,
+                enabled = true,
+            )
+        assertEquals("off,clk,ena", formatter.buildFlags(node))
+    }
+
+    @Test
+    @DisplayName("on is always first flag")
+    fun onIsAlwaysFirstFlag() {
+        val node =
+            makeNode(
+                visible = true,
+                enabled = true,
+            )
+        val flags = formatter.buildFlags(node)
+        assertTrue(flags.startsWith("on,") || flags == "on")
+    }
+
+    @Test
+    @DisplayName("returns only on when visible and no other flags set")
+    fun returnsOnlyOnWhenVisibleAndNoOtherFlags() {
+        val node = makeNode(visible = true)
+        assertEquals("on", formatter.buildFlags(node))
+    }
+
+    @Test
+    @DisplayName("includes off flag with all other flags set")
+    fun includesOffFlagWithAllOtherFlagsSet() {
+        val node =
+            makeNode(
+                visible = false,
+                clickable = true,
+                longClickable = true,
+                focusable = true,
+                scrollable = true,
+                editable = true,
+                enabled = true,
+            )
+        assertEquals("off,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
+    }
 }
```

#### Action 1.2.2: Update `format` tests for new note lines and line indices

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt`
**Lines**: 59-213 (`FormatTests` inner class)

**What**: Update all line index references (data rows now start at line 7 instead of line 5 due to 2 new note lines). Update flag assertions. Add tests for the new note lines.

Key changes:
- `lines[0]` = note line (unchanged)
- `lines[1]` = custom elements note (unchanged)
- `lines[2]` = flags legend note (NEW)
- `lines[3]` = offscreen hint note (NEW)
- `lines[4]` = app metadata (was `lines[2]`)
- `lines[5]` = screen info (was `lines[3]`)
- `lines[6]` = header (was `lines[4]`)
- `lines[7]+` = data rows (was `lines[5]+`)

```diff
 @Test
 @DisplayName("produces note line as first line")
 fun producesNoteLineAsFirstLine() {
     val tree = makeNode(text = "hello")
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
     assertEquals(CompactTreeFormatter.NOTE_LINE, lines[0])
 }

 @Test
 @DisplayName("produces custom elements note as second line")
 fun producesCustomElementsNoteAsSecondLine() {
     val tree = makeNode(text = "hello")
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
     assertEquals(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS, lines[1])
 }

+@Test
+@DisplayName("produces flags legend note as third line")
+fun producesFlagsLegendNoteAsThirdLine() {
+    val tree = makeNode(text = "hello")
+    val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
+    val lines = output.lines()
+    assertEquals(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND, lines[2])
+}
+
+@Test
+@DisplayName("produces offscreen hint note as fourth line")
+fun producesOffscreenHintNoteAsFourthLine() {
+    val tree = makeNode(text = "hello")
+    val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
+    val lines = output.lines()
+    assertEquals(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT, lines[3])
+}
+
 @Test
 @DisplayName("produces correct metadata lines")
 fun producesCorrectMetadataLines() {
     val tree = makeNode(text = "hello")
     val output = formatter.format(tree, "com.example.app", ".MainActivity", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals("app:com.example.app activity:.MainActivity", lines[2])
-    assertEquals("screen:1080x2400 density:420 orientation:portrait", lines[3])
+    assertEquals("app:com.example.app activity:.MainActivity", lines[4])
+    assertEquals("screen:1080x2400 density:420 orientation:portrait", lines[5])
 }

 @Test
 @DisplayName("produces correct header line")
 fun producesCorrectHeaderLine() {
     val tree = makeNode(text = "hello")
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals("id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[4])
+    assertEquals("id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[6])
 }

 @Test
 @DisplayName("outputs kept nodes as flat TSV rows")
 fun outputsKeptNodesAsFlatTsvRows() {
     val tree =
         makeNode(
             id = "node_btn",
             className = "android.widget.Button",
             text = "OK",
             resourceId = "btn_ok",
             bounds = BoundsData(100, 200, 300, 260),
             clickable = true,
             visible = true,
             enabled = true,
         )
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals("node_btn\tButton\tOK\t-\tbtn_ok\t100,200,300,260\tvcn", lines[5])
+    assertEquals("node_btn\tButton\tOK\t-\tbtn_ok\t100,200,300,260\ton,clk,ena", lines[7])
 }

 @Test
 @DisplayName("filters out noise nodes")
 fun filtersOutNoiseNodes() {
     val tree =
         makeNode(
             id = "node_frame",
             className = "android.widget.FrameLayout",
             bounds = BoundsData(0, 0, 1080, 2400),
         )
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals(5, lines.size)
+    assertEquals(7, lines.size)
 }

 @Test
 @DisplayName("includes children of filtered nodes")
 fun includesChildrenOfFilteredNodes() {
     val child =
         makeNode(
             id = "node_child",
             className = "android.widget.Button",
             text = "Click me",
             clickable = true,
             visible = true,
             enabled = true,
         )
     val parent =
         makeNode(
             id = "node_parent",
             className = "android.widget.FrameLayout",
             children = listOf(child),
         )
     val output = formatter.format(parent, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals(6, lines.size)
-    assertTrue(lines[5].startsWith("node_child\t"))
+    assertEquals(8, lines.size)
+    assertTrue(lines[7].startsWith("node_child\t"))
 }

 @Test
 @DisplayName("includes non-visible nodes that pass filter")
 fun includesNonVisibleNodesThatPassFilter() {
     val tree =
         makeNode(
             id = "node_hidden",
             className = "android.widget.Button",
             text = "Hidden",
             visible = false,
             enabled = true,
         )
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals(6, lines.size)
-    assertTrue(lines[5].contains("node_hidden"))
-    assertTrue(lines[5].endsWith("n"))
-    assertFalse(lines[5].endsWith("vn"))
+    assertEquals(8, lines.size)
+    assertTrue(lines[7].contains("node_hidden"))
+    // Check the flags column (last tab-separated field) starts with "off"
+    val flags = lines[7].split("\t").last()
+    assertTrue(flags.startsWith("off"), "Flags should start with 'off' but was: $flags")
+    assertFalse(flags.startsWith("on"), "Flags should not start with 'on' but was: $flags")
 }

 @Test
 @DisplayName("handles tree with all nodes filtered")
 fun handlesTreeWithAllNodesFiltered() {
     val tree =
         makeNode(
             id = "node_root",
             className = "android.widget.FrameLayout",
             children =
                 listOf(
                     makeNode(
                         id = "node_inner",
                         className = "android.widget.LinearLayout",
                     ),
                 ),
         )
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertEquals(5, lines.size)
+    assertEquals(7, lines.size)
 }

 @Test
 @DisplayName("bounds formatted correctly")
 fun boundsFormattedCorrectly() {
     val tree =
         makeNode(
             id = "node_a",
             text = "Test",
             bounds = BoundsData(10, 20, 300, 400),
         )
     val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
     val lines = output.lines()
-    assertTrue(lines[5].contains("10,20,300,400"))
+    assertTrue(lines[7].contains("10,20,300,400"))
 }
```

---

### Task 1.3: Update `ScreenIntrospectionIntegrationTest`

**Acceptance Criteria**:
- [x] Integration test assertions updated for new note lines and flag format
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

#### Action 1.3.1: Update assertion in `get_screen_state returns compact flat TSV with metadata`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`
**Lines**: 77-100 (first test)

**What**: Add assertions for the new note lines and update the flag format assertion. Add missing `assertFalse` import at the top of the file. (The `Bitmap` import is deferred to Action 2.7.1 where it is first used, to avoid a ktlint unused-import error.)

**Import to add** (at the top of the file):
```diff
+import org.junit.jupiter.api.Assertions.assertFalse
```

**Test update**:
```diff
 @Test
 fun `get_screen_state returns compact flat TSV with metadata`() =
     runTest {
         val deps = McpIntegrationTestHelper.createMockDependencies()
         deps.setupReadyService()

         McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
             val result =
                 client.callTool(
                     name = "android_get_screen_state",
                     arguments = emptyMap(),
                 )
             assertNotEquals(true, result.isError)
             assertEquals(1, result.content.size)

             val textContent = (result.content[0] as TextContent).text
             assertTrue(textContent.contains("note:structural-only nodes are omitted from the tree"))
             assertTrue(textContent.contains("note:certain elements are custom and will not be properly reported"))
+            assertTrue(textContent.contains("note:flags: on=onscreen off=offscreen"))
+            assertTrue(textContent.contains("note:offscreen items require scroll_to_element before interaction"))
             assertTrue(textContent.contains("app:com.example.app activity:.MainActivity"))
             assertTrue(textContent.contains("screen:1080x2400 density:420 orientation:portrait"))
             assertTrue(textContent.contains("id\tclass\ttext\tdesc\tres_id\tbounds\tflags"))
             assertTrue(textContent.contains("node_btn"))
+            assertTrue(textContent.contains("on,clk,ena"))
+            // Negative assertions: ensure old single-char flag format is gone
+            assertFalse(textContent.contains("\tvcn"))
+            assertFalse(textContent.contains("\tvclfsen"))
         }
     }
```

### Task 1.4: Update `ScreenIntrospectionToolsTest` stale fixture data

**Acceptance Criteria**:
- [x] `sampleCompactOutput` fixture updated to use new flag format and note lines
- [x] Screenshot tests updated to mock `captureScreenshotBitmap` instead of `captureScreenshot`
- [x] `GetScreenStateHandler` constructor call updated with new dependencies (mocked)
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.mcp.tools.ScreenIntrospectionToolsTest"`

**Note**: This task technically depends on US2 changes (new constructor parameters for `GetScreenStateHandler`). During implementation, the constructor update and mock additions in this task should be applied AFTER Task 2.3 (Action 2.3.1). However, the fixture data update (`sampleCompactOutput`) can be done as part of US1. The implementor should either: (a) update the fixture now and defer the constructor changes to US2, or (b) do the full update together with Task 2.3.

#### Action 1.4.1: Update `sampleCompactOutput` and test mocks

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt`
**Lines**: 84-92 (fixture data), 117-123 (handler construction), 145-151, 170-172, 177-183, 196-198, 212-214, 263-265

**What**: Update `sampleCompactOutput` to include the 2 new note lines and use new flag format. Also add mocked `ScreenshotAnnotator` and `ScreenshotEncoder` fields and pass them to `GetScreenStateHandler`. Update screenshot test mocks to use `captureScreenshotBitmap` instead of `captureScreenshot`.

**Fixture update**:
```diff
     private val sampleCompactOutput =
         "note:structural-only nodes are omitted from the tree\n" +
             "note:certain elements are custom and will not be properly reported, " +
             "if needed or if tools are not working as expected set " +
             "include_screenshot=true to see the screen and take what you see into account\n" +
+            "note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable " +
+                "foc=focusable scr=scrollable edt=editable ena=enabled\n" +
+            "note:offscreen items require scroll_to_element before interaction\n" +
             "app:com.example activity:.Main\n" +
             "screen:1080x2400 density:420 orientation:portrait\n" +
             "id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n" +
-            "node_btn\tButton\tOK\t-\t-\t100,200,300,260\tvcn"
+            "node_btn\tButton\tOK\t-\t-\t100,200,300,260\ton,clk,ena"
```

**New mock fields** (add after existing mock declarations):
```diff
+import android.graphics.Bitmap
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder

+    private lateinit var mockScreenshotAnnotator: ScreenshotAnnotator
+    private lateinit var mockScreenshotEncoder: ScreenshotEncoder
```

**Update `@BeforeEach`** (add after existing mock initializations):
```diff
+        mockScreenshotAnnotator = mockk(relaxed = true)
+        mockScreenshotEncoder = mockk(relaxed = true)
```

**Update handler construction**:
```diff
         handler =
             GetScreenStateHandler(
                 mockTreeParser,
                 mockAccessibilityServiceProvider,
                 mockScreenCaptureProvider,
                 mockCompactTreeFormatter,
+                mockScreenshotAnnotator,
+                mockScreenshotEncoder,
             )
```

**Update screenshot test mocks** (in `includesScreenshotWhenIncludeScreenshotIsTrue` and `screenshotUses700pxMaxSize`): Replace `captureScreenshot` mocks with `captureScreenshotBitmap` + `annotate` + `bitmapToScreenshotData` mocks:
```diff
-                coEvery {
-                    mockScreenCaptureProvider.captureScreenshot(
-                        ScreenCaptureProvider.DEFAULT_QUALITY,
-                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
-                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
-                    )
-                } returns Result.success(ScreenshotData(data = "base64data", width = 700, height = 500))
+                val mockBitmap = mockk<Bitmap>(relaxed = true)
+                val mockAnnotatedBitmap = mockk<Bitmap>(relaxed = true)
+                coEvery {
+                    mockScreenCaptureProvider.captureScreenshotBitmap(
+                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
+                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
+                    )
+                } returns Result.success(mockBitmap)
+                every {
+                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
+                } returns mockAnnotatedBitmap
+                every {
+                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
+                } returns ScreenshotData(data = "base64data", width = 700, height = 500)
```

**Update no-screenshot verify** (in `doesNotIncludeScreenshotByDefault` and `doesNotIncludeScreenshotWhenFalse`):
```diff
                 coVerify(exactly = 0) {
-                    mockScreenCaptureProvider.captureScreenshot(any(), any(), any())
+                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                 }
```

**Update screenshot failure test** (`throwsActionFailedWhenScreenshotCaptureFails`):
```diff
                 coEvery {
-                    mockScreenCaptureProvider.captureScreenshot(any(), any(), any())
-                } returns Result.failure(RuntimeException("Capture timeout"))
+                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
+                } returns Result.failure(RuntimeException("Screenshot capture failed"))
```

---

---

## User Story 2: Annotated Screenshot with Bounding Boxes

**Goal**: When `include_screenshot=true`, overlay red dashed bounding boxes with element ID hash labels on the screenshot for all on-screen elements that appear in the TSV.

**Acceptance Criteria / Definition of Done**:
- [x] Screenshot returned by `get_screen_state` has red dashed bounding boxes drawn on on-screen TSV elements
- [x] Each bounding box has a semi-transparent red pill label with white bold text showing the element ID hash (without `node_` prefix)
- [x] Element bounds from screen coordinates are correctly mapped to the scaled screenshot coordinates
- [x] Off-screen elements have no bounding boxes
- [x] Elements not in the TSV (filtered out by `shouldKeepNode`) have no bounding boxes
- [x] Password/sensitive fields ARE annotated (intentional — Android masks display text)
- [x] `ScreenCaptureProvider` interface exposes a method to get the raw resized `Bitmap`
- [x] `ApiLevelProvider` interface extracted for testable SDK version checks (avoids JDK 17 reflection issues with `Build.VERSION.SDK_INT`)
- [x] `ScreenCaptureProviderImpl` injects `ApiLevelProvider` instead of reading `Build.VERSION.SDK_INT` directly
- [x] `ScreenCaptureProviderImpl.captureScreenshotBitmap` is unit tested (success, failure, bitmap recycling)
- [x] `ScreenshotAnnotator` is a new injectable class under `services/screencapture/` with testable helper methods
- [x] `ScreenshotAnnotator` creates Paint objects ONCE before the element loop
- [x] `collectOnScreenElements` uses accumulator pattern (no intermediate list allocations)
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotatorTest"`
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImplTest"`
- [x] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`
- [x] Integration test for annotation failure / bitmap cleanup passes
- [x] Lint passes on changed files

---

### Task 2.0: Extract `ApiLevelProvider` for testable SDK version checks

**Acceptance Criteria**:
- [x] New `ApiLevelProvider` interface in `services/screencapture/`
- [x] New `DefaultApiLevelProvider` implementation using `Build.VERSION.SDK_INT`
- [x] `ScreenCaptureProviderImpl` constructor updated to inject `ApiLevelProvider`
- [x] `validateService()` uses `apiLevelProvider.getSdkInt()` instead of `Build.VERSION.SDK_INT` directly
- [x] `isScreenCaptureAvailable()` uses `apiLevelProvider.getSdkInt()` instead of `Build.VERSION.SDK_INT` directly
- [x] Hilt `@Binds` added in `ServiceModule`
- [x] Lint passes on changed files

**Why**: `Build.VERSION.SDK_INT` is a `static final int` field. On JDK 17+, the `Field.modifiers` reflection trick for mocking it is broken (`NoSuchFieldException`), and MockK's `mockkStatic` only intercepts methods, not fields. Extracting an injectable interface enables clean mocking in unit tests without JDK-version-dependent reflection hacks.

#### Action 2.0.1: Create `ApiLevelProvider.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ApiLevelProvider.kt` (NEW)

**What**: Simple interface and default implementation for reading the device API level.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.os.Build
import javax.inject.Inject

/**
 * Provides the device API level for testability.
 *
 * Production code uses [DefaultApiLevelProvider] which reads [Build.VERSION.SDK_INT].
 * Tests inject a mock to avoid JDK 17+ reflection issues with static final fields.
 */
interface ApiLevelProvider {
    fun getSdkInt(): Int
}

/**
 * Default implementation that delegates to [Build.VERSION.SDK_INT].
 */
class DefaultApiLevelProvider
    @Inject
    constructor() : ApiLevelProvider {
    override fun getSdkInt(): Int = Build.VERSION.SDK_INT
}
```

#### Action 2.0.2: Update `ScreenCaptureProviderImpl` to inject `ApiLevelProvider`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImpl.kt`
**Lines**: 5, 20-24, 72, 98

**What**: Add `ApiLevelProvider` constructor parameter. Replace `Build.VERSION.SDK_INT` reads with `apiLevelProvider.getSdkInt()`. Remove unused `import android.os.Build`.

```diff
-import android.os.Build
 import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
 import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
 import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
 import javax.inject.Inject

 class ScreenCaptureProviderImpl
     @Inject
     constructor(
         private val screenshotEncoder: ScreenshotEncoder,
+        private val apiLevelProvider: ApiLevelProvider,
     ) : ScreenCaptureProvider {

     // ...in validateService():
-        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
+        if (apiLevelProvider.getSdkInt() < API_LEVEL_R) {

     // ...in isScreenCaptureAvailable():
-        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service.canTakeScreenshot()
+        return apiLevelProvider.getSdkInt() >= API_LEVEL_R && service.canTakeScreenshot()

+    companion object {
+        /** Android 11 (API 30) — minimum for AccessibilityService.takeScreenshot(). */
+        private const val API_LEVEL_R = 30
+    }
```

**Note**: Using a `const val API_LEVEL_R = 30` instead of `Build.VERSION_CODES.R` avoids the dependency on `android.os.Build` entirely in the class, making it cleaner for JVM testing.

#### Action 2.0.3: Add Hilt binding in `ServiceModule`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`
**Lines**: ~88 (in `ServiceModule`)

**What**: Add a `@Binds` entry for `ApiLevelProvider`.

```diff
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ApiLevelProvider
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.DefaultApiLevelProvider

 abstract class ServiceModule {
+    @Binds
+    @Singleton
+    abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider
+
     @Binds
     @Singleton
     abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor
```

---

### Task 2.1: Add `captureScreenshotBitmap` to `ScreenCaptureProvider`

**Acceptance Criteria**:
- [x] `ScreenCaptureProvider` interface has a new `captureScreenshotBitmap(maxWidth, maxHeight)` method returning `Result<Bitmap>`
- [x] `ScreenCaptureProviderImpl` implements it (capture + resize, no JPEG encoding)
- [x] Bitmap lifecycle is documented (caller must recycle)
- [x] Lint passes on changed files

#### Action 2.1.1: Add method to `ScreenCaptureProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProvider.kt`
**Lines**: 11-23

**What**: Add `captureScreenshotBitmap` method that returns a resized `Bitmap` without encoding. The caller is responsible for recycling the bitmap.

```diff
+import android.graphics.Bitmap
+
 interface ScreenCaptureProvider {
     companion object {
         const val DEFAULT_QUALITY = 80
     }

     suspend fun captureScreenshot(
         quality: Int = DEFAULT_QUALITY,
         maxWidth: Int? = null,
         maxHeight: Int? = null,
     ): Result<ScreenshotData>

+    /**
+     * Captures a screenshot and returns the resized [Bitmap] without JPEG encoding.
+     *
+     * The caller is responsible for calling [Bitmap.recycle] on the returned bitmap
+     * when it is no longer needed.
+     *
+     * @param maxWidth Maximum width in pixels, or null.
+     * @param maxHeight Maximum height in pixels, or null.
+     * @return A [Result] containing the resized [Bitmap].
+     */
+    suspend fun captureScreenshotBitmap(
+        maxWidth: Int? = null,
+        maxHeight: Int? = null,
+    ): Result<Bitmap>
+
     fun isScreenCaptureAvailable(): Boolean
 }
```

#### Action 2.1.2: Implement in `ScreenCaptureProviderImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImpl.kt`
**Lines**: After `captureScreenshot` method (after line 68)

**What**: Add implementation that captures the bitmap and resizes it, but does NOT encode to JPEG. The original (non-resized) bitmap is recycled if a different resized bitmap is produced; otherwise the same bitmap is returned and the caller owns it.

```diff
+    @SuppressLint("NewApi")
+    @Suppress("ReturnCount")
+    override suspend fun captureScreenshotBitmap(
+        maxWidth: Int?,
+        maxHeight: Int?,
+    ): Result<Bitmap> {
+        val validation = validateService()
+        if (validation is ServiceValidation.Invalid) {
+            return Result.failure(validation.error)
+        }
+        val service = (validation as ServiceValidation.Valid).service
+
+        val bitmap =
+            service.takeScreenshotBitmap()
+                ?: return Result.failure(
+                    McpToolException.ActionFailed("Screenshot capture failed or timed out"),
+                )
+
+        return try {
+            val resizedBitmap = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
+            // If resize produced a new bitmap, recycle the original
+            if (resizedBitmap !== bitmap) {
+                bitmap.recycle()
+            }
+            Result.success(resizedBitmap)
+        } catch (e: Exception) {
+            // Ensure original bitmap is recycled even if resize throws
+            bitmap.recycle()
+            Log.e(TAG, "Screenshot resize failed", e)
+            Result.failure(
+                McpToolException.ActionFailed("Screenshot resize failed"),
+            )
+        }
+    }
+
+    companion object {
+        private const val TAG = "MCP:ScreenCapture"
+        /** Android 11 (API 30) — minimum for AccessibilityService.takeScreenshot(). */
+        private const val API_LEVEL_R = 30
+    }
```

---

### Task 2.2: Create `ScreenshotAnnotator`

**Acceptance Criteria**:
- [x] New file `ScreenshotAnnotator.kt` in `services/screencapture/`
- [x] Draws red dashed 2px bounding boxes on on-screen elements
- [x] Draws semi-transparent red pill label with white bold text (element ID hash) at top-left of each box
- [x] Correctly maps element bounds from screen coordinates to scaled bitmap coordinates
- [x] Only annotates elements that pass `shouldKeepNode` AND are visible (on-screen)
- [x] Returns a new annotated `Bitmap` (does not mutate the input)
- [x] Hilt-injectable via `@Inject constructor()`
- [x] Lint passes

#### Action 2.2.1: Create `ScreenshotAnnotator.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotator.kt` (NEW)

**What**: Create a class that takes a `Bitmap`, a flat list of `AccessibilityNodeData` (already filtered + on-screen), and the original screen dimensions. It draws bounding boxes and labels on a copy of the bitmap and returns the annotated copy.

**Implementation details**:

1. **Input**: `annotate(bitmap: Bitmap, elements: List<AccessibilityNodeData>, screenWidth: Int, screenHeight: Int): Bitmap`
   - `bitmap`: The resized screenshot bitmap (e.g., 700px max)
   - `elements`: Flat list of nodes that are both `shouldKeepNode == true` AND `visible == true`
   - `screenWidth`/`screenHeight`: Original screen dimensions for coordinate mapping

2. **Coordinate mapping** (via `computeScaledBounds` returning `ScaledBounds` data class):
   - `scaleX = bitmap.width.toFloat() / screenWidth.toFloat()`
   - `scaleY = bitmap.height.toFloat() / screenHeight.toFloat()`
   - For each element: `left = bounds.left * scaleX`, `top = bounds.top * scaleY`, `right = bounds.right * scaleX`, `bottom = bounds.bottom * scaleY`
   - `ScaledBounds` is converted to `RectF` only inside `annotate()` for Canvas drawing

3. **Bounding box style**:
   - Red dashed outline: `Paint()` with `color = Color.RED`, `style = STROKE`, `strokeWidth = 2f * density` (where density = `bitmap.width / 360f` as approximate dp-to-px), `pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), 0f)`
   - Clamp drawn bounds to bitmap dimensions to avoid drawing outside

4. **Label style**:
   - Text: element ID hash (strip `node_` prefix via `extractLabel`)
   - Font: bold, size scaled to bitmap (approximately `10f * (bitmap.width / 360f)`)
   - Background pill: semi-transparent red rectangle (`Color.argb(180, 255, 0, 0)`) with small padding
   - Text color: `Color.WHITE`
   - Position: top-left corner of bounding box, shifted inside if near edge

5. **Paint object reuse** (IMPORTANT for performance):
   - All `Paint` objects (box stroke paint, label background paint, label text paint) MUST be created **once** before the element loop, NOT inside the per-element loop.
   - The `Canvas` object wrapping the mutable bitmap copy is also created once.

6. **Bitmap copy**:
   - Always use `Bitmap.Config.ARGB_8888` (not `bitmap.config`) — HARDWARE bitmaps can't be copied as mutable
   - Null-check the result of `bitmap.copy()` — throws `IllegalStateException` on null (OOM)
   - try/catch wraps the drawing code — recycles the copy bitmap on failure

7. **Return**: A new `Bitmap` (mutable software copy of input via `bitmap.copy(ARGB_8888, true)`). Input bitmap is NOT recycled — caller retains ownership.

8. **Edge cases**:
   - Empty elements list → return copy of bitmap unchanged
   - Element bounds fully outside bitmap → skip that element (computeScaledBounds returns null)
   - Element bounds partially outside bitmap → clamp to bitmap edges
   - Drawing failure → copy is recycled, exception re-thrown

**Note**: `ScreenshotAnnotator` receives the already-filtered list of elements from the caller (`GetScreenStateHandler.collectOnScreenElements`). It does NOT perform its own filtering — the `shouldKeepNode` logic stays in `CompactTreeFormatter`, and element collection stays in `GetScreenStateHandler`.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import javax.inject.Inject

/**
 * Annotates a screenshot bitmap with bounding boxes and element ID labels
 * for on-screen UI elements.
 *
 * Used by [GetScreenStateHandler] when `include_screenshot=true` to help
 * vision-language models identify and reference UI elements.
 *
 * Drawing style follows Set-of-Mark prompting conventions:
 * - Red dashed bounding boxes (2px scaled)
 * - Semi-transparent red pill labels with white bold text
 * - Labels show the element ID hash (without `node_` prefix)
 */
class ScreenshotAnnotator
    @Inject
    constructor() {

    /**
     * Pure-Kotlin data class for scaled element bounds.
     * Avoids [RectF] (Android framework) in testable methods so unit tests
     * on JVM with `isReturnDefaultValues = true` work correctly.
     */
    internal data class ScaledBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /**
     * Annotates a screenshot with bounding boxes and labels for the given elements.
     *
     * Creates a mutable copy of the input bitmap and draws on it. The input bitmap
     * is NOT modified or recycled — the caller retains ownership of both.
     *
     * Paint objects are created ONCE before the element loop for efficiency.
     *
     * @param bitmap The resized screenshot bitmap.
     * @param elements Flat list of on-screen elements to annotate (already filtered).
     * @param screenWidth Original screen width in pixels (for coordinate mapping).
     * @param screenHeight Original screen height in pixels (for coordinate mapping).
     * @return A new annotated [Bitmap]. Caller must recycle when done.
     * @throws IllegalStateException if the bitmap cannot be copied.
     */
    fun annotate(
        bitmap: Bitmap,
        elements: List<AccessibilityNodeData>,
        screenWidth: Int,
        screenHeight: Int,
    ): Bitmap {
        // Always use ARGB_8888 for the mutable copy — HARDWARE bitmaps cannot be mutable
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Failed to create mutable bitmap copy for annotation")
        if (elements.isEmpty()) return copy

        try {
            val canvas = Canvas(copy)
            val scaleX = copy.width.toFloat() / screenWidth.toFloat()
            val scaleY = copy.height.toFloat() / screenHeight.toFloat()
            val density = copy.width.toFloat() / REFERENCE_WIDTH_DP

            // Create Paint objects ONCE before the loop
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = BOX_STROKE_WIDTH_DP * density
                pathEffect = DashPathEffect(
                    floatArrayOf(DASH_LENGTH_DP * density, DASH_GAP_DP * density),
                    0f,
                )
            }
            val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(LABEL_BG_ALPHA, 255, 0, 0)
                style = Paint.Style.FILL
            }
            val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = LABEL_TEXT_SIZE_DP * density
                typeface = Typeface.DEFAULT_BOLD
            }
            val padding = LABEL_PADDING_DP * density

            for (element in elements) {
                val scaled = computeScaledBounds(
                    element, scaleX, scaleY, copy.width, copy.height,
                ) ?: continue

                // Convert ScaledBounds to RectF for Canvas drawing
                val scaledRect = RectF(scaled.left, scaled.top, scaled.right, scaled.bottom)

                // Draw bounding box
                canvas.drawRect(scaledRect, boxPaint)

                // Draw label
                val label = extractLabel(element.id)
                val textWidth = labelTextPaint.measureText(label)
                val textHeight = labelTextPaint.textSize
                val labelRect = RectF(
                    scaledRect.left,
                    scaledRect.top - textHeight - padding * 2,
                    scaledRect.left + textWidth + padding * 2,
                    scaledRect.top,
                )
                // Shift label inside box if it would go above bitmap
                if (labelRect.top < 0) {
                    labelRect.offset(0f, -labelRect.top)
                }
                canvas.drawRoundRect(labelRect, padding, padding, labelBgPaint)
                canvas.drawText(
                    label,
                    labelRect.left + padding,
                    labelRect.bottom - padding,
                    labelTextPaint,
                )
            }

            return copy
        } catch (e: Exception) {
            copy.recycle()
            throw e
        }
    }

    /**
     * Computes the scaled bounding rectangle for an element on the bitmap.
     *
     * Returns a pure-Kotlin [ScaledBounds] (not [RectF]) so this method is
     * fully testable on JVM without Android framework stubs.
     *
     * @return Scaled [ScaledBounds] clamped to bitmap bounds, or null if fully outside.
     */
    internal fun computeScaledBounds(
        element: AccessibilityNodeData,
        scaleX: Float,
        scaleY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): ScaledBounds? {
        val bounds = element.bounds
        val left = (bounds.left * scaleX).coerceIn(0f, bitmapWidth.toFloat())
        val top = (bounds.top * scaleY).coerceIn(0f, bitmapHeight.toFloat())
        val right = (bounds.right * scaleX).coerceIn(0f, bitmapWidth.toFloat())
        val bottom = (bounds.bottom * scaleY).coerceIn(0f, bitmapHeight.toFloat())

        // Skip if fully collapsed after clamping
        if (right <= left || bottom <= top) return null

        return ScaledBounds(left, top, right, bottom)
    }

    /**
     * Extracts the display label from an element ID by stripping the `node_` prefix.
     */
    internal fun extractLabel(elementId: String): String =
        if (elementId.startsWith(NODE_ID_PREFIX)) {
            elementId.removePrefix(NODE_ID_PREFIX)
        } else {
            elementId
        }

    companion object {
        internal const val NODE_ID_PREFIX = "node_"
        private const val BOX_STROKE_WIDTH_DP = 2f
        private const val DASH_LENGTH_DP = 6f
        private const val DASH_GAP_DP = 3f
        private const val LABEL_TEXT_SIZE_DP = 10f
        private const val LABEL_PADDING_DP = 2f
        private const val LABEL_BG_ALPHA = 180
        private const val REFERENCE_WIDTH_DP = 360f
    }
}
```

**Key design points**:
- **`ScaledBounds` data class** (not `RectF`): `computeScaledBounds` returns a pure Kotlin data class, NOT `android.graphics.RectF`. `RectF` constructor is a no-op on JVM with `isReturnDefaultValues = true` — fields stay at `0.0f`, breaking unit tests. `ScaledBounds` is converted to `RectF` only inside `annotate()` when interacting with `Canvas`.
- **`Bitmap.Config.ARGB_8888` unconditionally**: `bitmap.copy(ARGB_8888, true)` avoids NPE when input is a HARDWARE bitmap (whose `config` can't be used for mutable copies).
- **Null check on `bitmap.copy()`**: `bitmap.copy()` can return `null` on OOM; throws `IllegalStateException` with descriptive message instead of NPE.
- **try/catch inside `annotate()`**: If drawing fails after `copy` is created, the copy is recycled before re-throwing. Prevents bitmap leak if `annotate()` throws (the caller's `finally` block only has `annotatedBitmap` which is still null at that point).
- **`element.bounds` is non-nullable**: `AccessibilityNodeData.bounds` is declared as `BoundsData` (non-nullable). No `?:` operator needed.
- All `Paint` objects are created **once** before the loop (performance review fix).
- `computeScaledBounds` and `extractLabel` are `internal` methods, directly unit-testable on JVM without mocking Android graphics classes.
- `annotate` creates a mutable copy via `bitmap.copy()` — input is never modified.
- Password/sensitive fields are intentionally annotated — Android masks display text, so no secrets are exposed.

---

### Task 2.3: Inject `ScreenshotAnnotator` and `ScreenshotEncoder` into screen introspection

**Acceptance Criteria**:
- [x] `GetScreenStateHandler` accepts `ScreenshotAnnotator` and `ScreenshotEncoder` as constructor dependencies
- [x] `registerScreenIntrospectionTools` function signature updated to accept the new dependencies
- [x] `McpServerService.registerAllTools` passes the new dependencies
- [x] `McpIntegrationTestHelper.registerAllTools` passes the new dependencies (mocked or real)
- [x] Lint passes on changed files

#### Action 2.3.1: Update `GetScreenStateHandler` constructor

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`
**Lines**: 34-41

**What**: Add `ScreenshotAnnotator` and `ScreenshotEncoder` as constructor parameters.

```diff
 class GetScreenStateHandler
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
         private val screenCaptureProvider: ScreenCaptureProvider,
         private val compactTreeFormatter: CompactTreeFormatter,
+        private val screenshotAnnotator: ScreenshotAnnotator,
+        private val screenshotEncoder: ScreenshotEncoder,
     ) {
```

#### Action 2.3.2: Update `registerScreenIntrospectionTools` function

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`
**Lines**: 159-170

**What**: Add `ScreenshotAnnotator` and `ScreenshotEncoder` parameters. Pass them to `GetScreenStateHandler`.

```diff
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder

 @Suppress("LongParameterList")
 fun registerScreenIntrospectionTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     accessibilityServiceProvider: AccessibilityServiceProvider,
     screenCaptureProvider: ScreenCaptureProvider,
     compactTreeFormatter: CompactTreeFormatter,
+    screenshotAnnotator: ScreenshotAnnotator,
+    screenshotEncoder: ScreenshotEncoder,
     toolNamePrefix: String,
 ) {
-    GetScreenStateHandler(treeParser, accessibilityServiceProvider, screenCaptureProvider, compactTreeFormatter)
+    GetScreenStateHandler(treeParser, accessibilityServiceProvider, screenCaptureProvider, compactTreeFormatter, screenshotAnnotator, screenshotEncoder)
         .register(server, toolNamePrefix)
 }
```

#### Action 2.3.3: Update `McpServerService.registerAllTools`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

**What**: Add `@Inject lateinit var screenshotAnnotator: ScreenshotAnnotator` and `@Inject lateinit var screenshotEncoder: ScreenshotEncoder` fields. Pass them in `registerAllTools`.

```diff
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder

 @Inject lateinit var compactTreeFormatter: CompactTreeFormatter
+@Inject lateinit var screenshotAnnotator: ScreenshotAnnotator
+@Inject lateinit var screenshotEncoder: ScreenshotEncoder

 // ...in registerAllTools:
 registerScreenIntrospectionTools(
     server,
     treeParser,
     accessibilityServiceProvider,
     screenCaptureProvider,
     compactTreeFormatter,
+    screenshotAnnotator,
+    screenshotEncoder,
     toolNamePrefix,
 )
```

#### Action 2.3.4: Update `McpIntegrationTestHelper` — add mocks to `MockDependencies` and pass them

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

**What**: Add `screenshotAnnotator` and `screenshotEncoder` as **mocked** fields in `MockDependencies`. Using real instances would crash JVM tests because `ScreenshotAnnotator` uses `Canvas`, `Paint`, `Color`, `DashPathEffect` etc. (Android native classes), and `ScreenshotEncoder` uses `Base64` (Android static). Mock them to avoid needing to mock a dozen Android framework classes.

**Step 1**: Update `MockDependencies` data class (lines 252-261):

```diff
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder

 data class MockDependencies(
     val actionExecutor: ActionExecutor,
     val accessibilityServiceProvider: AccessibilityServiceProvider,
     val screenCaptureProvider: ScreenCaptureProvider,
     val treeParser: AccessibilityTreeParser,
     val elementFinder: ElementFinder,
     val storageLocationProvider: StorageLocationProvider,
     val fileOperationProvider: FileOperationProvider,
     val appManager: AppManager,
+    val screenshotAnnotator: ScreenshotAnnotator,
+    val screenshotEncoder: ScreenshotEncoder,
 )
```

**Step 2**: Update `createMockDependencies()` (lines 55-65):

```diff
 fun createMockDependencies(): MockDependencies =
     MockDependencies(
         actionExecutor = mockk(relaxed = true),
         accessibilityServiceProvider = mockk(relaxed = true),
         screenCaptureProvider = mockk(relaxed = true),
         treeParser = mockk(relaxed = true),
         elementFinder = mockk(relaxed = true),
         storageLocationProvider = mockk(relaxed = true),
         fileOperationProvider = mockk(relaxed = true),
         appManager = mockk(relaxed = true),
+        screenshotAnnotator = mockk(relaxed = true),
+        screenshotEncoder = mockk(relaxed = true),
     )
```

**Step 3**: Update `registerAllTools` to use mocked instances (lines 76-83):

```diff
 registerScreenIntrospectionTools(
     server,
     deps.treeParser,
     deps.accessibilityServiceProvider,
     deps.screenCaptureProvider,
     CompactTreeFormatter(),
+    deps.screenshotAnnotator,
+    deps.screenshotEncoder,
     toolNamePrefix,
 )
```

---

### Task 2.4: Update `GetScreenStateHandler.execute` to use annotated screenshot

**Acceptance Criteria**:
- [x] When `include_screenshot=true`, captures bitmap, collects on-screen filtered elements, annotates, encodes, returns
- [x] Uses `captureScreenshotBitmap` instead of `captureScreenshot`
- [x] Properly recycles all bitmaps (resized, annotated)
- [x] Element collection reuses `CompactTreeFormatter.shouldKeepNode` logic
- [x] Lint passes

#### Action 2.4.1: Add helper to collect annotatable elements

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**What**: Add a private method in `GetScreenStateHandler` that walks the parsed tree and collects elements that are both kept by the formatter AND visible (on-screen). This mirrors the `walkNode` logic in `CompactTreeFormatter` but collects into a list instead of appending to a StringBuilder.

```kotlin
/**
 * Collects elements that should be annotated on the screenshot:
 * nodes that pass the formatter's keep filter AND are visible (on-screen).
 *
 * Uses an accumulator parameter to avoid O(N) intermediate list allocations
 * and O(N²) element copies from recursive addAll calls.
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
```

Note: `shouldKeepNode` is `internal` visibility in `CompactTreeFormatter`. Since `ScreenIntrospectionTools.kt` is in package `com.danielealbano.androidremotecontrolmcp.mcp.tools` and `CompactTreeFormatter` is in `com.danielealbano.androidremotecontrolmcp.services.accessibility`, `internal` means module-internal (same Gradle module), so this is accessible. Verify at compile time.

#### Action 2.4.2: Rewrite the screenshot capture section of `execute()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`
**Lines**: 84-106 (the `if (includeScreenshot)` block)

**What**: Replace the current flow (call `captureScreenshot` → get base64 → return) with: call `captureScreenshotBitmap` → collect on-screen elements → annotate → encode → return. Properly recycle bitmaps.

```diff
         // 8. Optionally include screenshot
         if (includeScreenshot) {
             if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                 throw McpToolException.PermissionDenied(
                     "Screen capture not available. Please enable the accessibility service in Android Settings.",
                 )
             }

-            val result =
-                screenCaptureProvider.captureScreenshot(
-                    quality = ScreenCaptureProvider.DEFAULT_QUALITY,
+            val bitmapResult =
+                screenCaptureProvider.captureScreenshotBitmap(
                     maxWidth = SCREENSHOT_MAX_SIZE,
                     maxHeight = SCREENSHOT_MAX_SIZE,
                 )
-            val screenshotData =
-                result.getOrElse { exception ->
+            val resizedBitmap =
+                bitmapResult.getOrElse { exception ->
+                    Log.e(TAG, "Screenshot capture failed", exception)
                     throw McpToolException.ActionFailed(
-                        "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
+                        "Screenshot capture failed",
                     )
                 }
-
-            return McpToolUtils.textAndImageResult(compactOutput, screenshotData.data, "image/jpeg")
+
+            var annotatedBitmap: Bitmap? = null
+            try {
+                // Collect on-screen elements that appear in the TSV
+                val onScreenElements = collectOnScreenElements(tree)
+
+                // Annotate the screenshot with bounding boxes
+                annotatedBitmap = screenshotAnnotator.annotate(
+                    resizedBitmap,
+                    onScreenElements,
+                    screenInfo.width,
+                    screenInfo.height,
+                )
+
+                // Encode annotated bitmap to base64 JPEG
+                val screenshotData = screenshotEncoder.bitmapToScreenshotData(
+                    annotatedBitmap,
+                    ScreenCaptureProvider.DEFAULT_QUALITY,
+                )
+
+                return McpToolUtils.textAndImageResult(compactOutput, screenshotData.data, "image/jpeg")
+            } catch (e: McpToolException) {
+                throw e
+            } catch (e: Exception) {
+                Log.e(TAG, "Screenshot annotation failed", e)
+                throw McpToolException.ActionFailed(
+                    "Screenshot annotation failed",
+                )
+            } finally {
+                annotatedBitmap?.recycle()
+                resizedBitmap.recycle()
+            }
         }
```

Add the necessary imports at the top of the file:
```diff
+import android.graphics.Bitmap
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
```

---

### Task 2.5: Create `ScreenshotAnnotatorTest` unit tests

**Acceptance Criteria**:
- [x] New test file `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotatorTest.kt`
- [x] Tests cover: empty elements list, single element, multiple elements, coordinate mapping, bounds clamping, element outside bitmap, ID hash extraction
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotatorTest"`

**Note on testability**: The `computeScaledBounds` and `extractLabel` methods were already defined as `internal` in Action 2.2.1 (`ScreenshotAnnotator.kt`). No additional code changes are needed — this task only creates the tests.

#### Action 2.5.1: Create `ScreenshotAnnotatorTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotatorTest.kt` (NEW)

**What**: Create unit tests that test the **extracted pure logic methods** and the overall `annotate` method using MockK for Android graphics classes. Structure:

**Test file structure**: The outer class has a `private val annotator = ScreenshotAnnotator()` and a local `makeNode` helper:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.OfTypeMatcher
import io.mockk.Runs
import io.mockk.constructedWith
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScreenshotAnnotatorTest")
class ScreenshotAnnotatorTest {

    private val annotator = ScreenshotAnnotator()

    private fun makeNode(
        id: String = "node_test",
        className: String = "android.widget.Button",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
        visible: Boolean = true,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = false,
        children: List<AccessibilityNodeData> = emptyList(),
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = className,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            bounds = bounds,
            visible = visible,
            clickable = clickable,
            longClickable = longClickable,
            focusable = focusable,
            scrollable = scrollable,
            editable = editable,
            enabled = enabled,
            children = children,
        )

    // ... inner classes below ...
}
```

**Inner class 1: `ExtractLabelTests`** (pure logic, no mocking needed):
```kotlin
@Nested
@DisplayName("extractLabel")
inner class ExtractLabelTests {
    @Test
    fun `strips node_ prefix`() {
        assertEquals("a3f2", annotator.extractLabel("node_a3f2"))
    }

    @Test
    fun `returns full id when no prefix`() {
        assertEquals("custom_id", annotator.extractLabel("custom_id"))
    }

    @Test
    fun `handles empty string`() {
        assertEquals("", annotator.extractLabel(""))
    }

    @Test
    fun `handles node_ prefix only (empty hash)`() {
        assertEquals("", annotator.extractLabel("node_"))
    }
}
```

**Inner class 2: `ComputeScaledBoundsTests`** (pure math, returns `ScaledBounds` — no Android mocking needed):
```kotlin
@Nested
@DisplayName("computeScaledBounds")
inner class ComputeScaledBoundsTests {
    @Test
    fun `correctly scales coordinates`() {
        // Screen 1080x2400, bitmap 540x1200 → scaleX=0.5, scaleY=0.5
        val element = makeNode(bounds = BoundsData(100, 200, 300, 400))
        val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
        assertNotNull(result)
        assertEquals(50f, result!!.left)
        assertEquals(100f, result.top)
        assertEquals(150f, result.right)
        assertEquals(200f, result.bottom)
    }

    @Test
    fun `returns null for element fully outside bitmap`() {
        val element = makeNode(bounds = BoundsData(2000, 3000, 2100, 3100))
        val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
        assertNull(result)
    }

    @Test
    fun `clamps partially outside element`() {
        val element = makeNode(bounds = BoundsData(1000, 2300, 1200, 2600))
        val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
        assertNotNull(result)
        assertEquals(500f, result!!.left)
        assertEquals(540f, result.right) // clamped to bitmapWidth
    }

    @Test
    fun `returns null when zero-width element maps to same edge`() {
        // Element with left == right at screen edge
        val element = makeNode(bounds = BoundsData(1080, 0, 1080, 100))
        val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
        assertNull(result)
    }

    @Test
    fun `returns null when non-zero-width element collapses after clamping`() {
        // Element has width (1080 to 1200) but after scale (0.5) both map to 540+
        // and clamp to bitmapWidth=540, resulting in left==right==540
        val element = makeNode(bounds = BoundsData(1080, 0, 1200, 100))
        val result = annotator.computeScaledBounds(element, 0.5f, 0.5f, 540, 1200)
        assertNull(result)
    }
}
```

**Inner class 3: `AnnotateTests`** (uses MockK for Bitmap/Canvas):
```kotlin
@Nested
@DisplayName("annotate")
inner class AnnotateTests {
    private lateinit var mockBitmap: Bitmap
    private lateinit var mockCopy: Bitmap

    @BeforeEach
    fun setup() {
        mockBitmap = mockk(relaxed = true)
        mockCopy = mockk(relaxed = true)
        every { mockBitmap.width } returns 540
        every { mockBitmap.height } returns 1200
        // annotate() uses ARGB_8888 unconditionally (not bitmap.config)
        every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns mockCopy
        every { mockCopy.width } returns 540
        every { mockCopy.height } returns 1200
        mockkConstructor(Canvas::class)
        every { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawRoundRect(any(), any(), any(), any()) } just Runs
        every { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawRect(any<RectF>(), any()) } just Runs
        every { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawText(any(), any(), any(), any()) } just Runs
    }

    @AfterEach
    fun teardown() {
        unmockkConstructor(Canvas::class)
    }

    @Test
    fun `empty elements returns copy without draw calls`() {
        val result = annotator.annotate(mockBitmap, emptyList(), 1080, 2400)
        assertEquals(mockCopy, result)
        verify(exactly = 0) { mockCopy.recycle() } // input not recycled
    }

    @Test
    fun `single element draws box and label`() {
        val element = makeNode(id = "node_a3f2", bounds = BoundsData(100, 200, 300, 400), visible = true)
        annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
        // Verify at least one drawRect and one drawText were called
        verify(atLeast = 1) { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawRect(any<RectF>(), any()) }
        verify(atLeast = 1) { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawText(match { it.contains("a3f2") }, any(), any(), any()) }
    }

    @Test
    fun `does not mutate input bitmap`() {
        val element = makeNode(id = "node_x1y2", bounds = BoundsData(100, 200, 300, 400), visible = true)
        val result = annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
        assertNotSame(mockBitmap, result)
        assertEquals(mockCopy, result)
    }

    @Test
    fun `recycles copy bitmap when drawing fails`() {
        // Simulate Canvas drawing throwing
        every { constructedWith<Canvas>(OfTypeMatcher<Bitmap>(Bitmap::class)).drawRect(any<RectF>(), any()) } throws RuntimeException("Draw failed")
        val element = makeNode(id = "node_fail", bounds = BoundsData(100, 200, 300, 400), visible = true)

        var thrown = false
        try {
            annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
        } catch (_: RuntimeException) {
            thrown = true
        }
        assertTrue(thrown, "Should have thrown RuntimeException")
        // The copy bitmap should be recycled despite the exception
        verify(exactly = 1) { mockCopy.recycle() }
    }

    @Test
    fun `throws IllegalStateException when bitmap copy returns null`() {
        every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns null
        val element = makeNode(id = "node_test", bounds = BoundsData(100, 200, 300, 400), visible = true)

        val exception = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            annotator.annotate(mockBitmap, listOf(element), 1080, 2400)
        }
        assertTrue(exception.message!!.contains("Failed to create mutable bitmap copy"))
    }

    @Test
    fun `throws IllegalStateException when bitmap copy returns null for empty elements`() {
        every { mockBitmap.copy(Bitmap.Config.ARGB_8888, true) } returns null

        val exception = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            annotator.annotate(mockBitmap, emptyList(), 1080, 2400)
        }
        assertTrue(exception.message!!.contains("Failed to create mutable bitmap copy"))
    }
}
```

**Notes**:
- The `mockCanvas` field was removed — it was declared but never used. The tests use `mockkConstructor(Canvas::class)` with `constructedWith<Canvas>(...)` to intercept Canvas methods instead.
- `@AfterEach` ensures `unmockkConstructor` is always called to prevent test pollution.
- Two tests for `bitmap.copy()` returning null are included (with elements and with empty elements) to verify the `IllegalStateException` is thrown correctly in both paths.
- `OfTypeMatcher` is from MockK's `io.mockk` package and is the standard way to match constructor arguments by type when using `constructedWith`. While it's a less common MockK API, it's stable and documented for constructor mocking use cases. The exact verify patterns may need adjustment during implementation based on the final drawing code.

---

### Task 2.6: Add unit tests for `ScreenCaptureProviderImpl.captureScreenshotBitmap`

**Acceptance Criteria**:
- [x] New test class/inner class covering `captureScreenshotBitmap` in the existing `ScreenCaptureProviderImplTest.kt` (or a new file if it doesn't exist)
- [x] Tests cover: success path, failure path (null bitmap), bitmap recycling when resize produces new bitmap, bitmap NOT recycled when resize returns same bitmap
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImplTest"`

#### Action 2.6.1: Add `captureScreenshotBitmap` tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImplTest.kt` (existing or new)

**What**: Add unit tests for the new method. The test approach:
- Mock `McpAccessibilityService.takeScreenshotBitmap()` and `ScreenshotEncoder.resizeBitmapProportional()`
- Verify bitmap recycling behavior

**Full file**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImplTest.kt` (NEW)

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScreenCaptureProviderImplTest")
class ScreenCaptureProviderImplTest {

    private lateinit var screenshotEncoder: ScreenshotEncoder
    private lateinit var mockApiLevelProvider: ApiLevelProvider
    private lateinit var provider: ScreenCaptureProviderImpl
    private lateinit var mockService: McpAccessibilityService

    @BeforeEach
    fun setup() {
        screenshotEncoder = mockk(relaxed = true)
        mockApiLevelProvider = mockk()
        // Return API 30 (Android R) — minimum for screenshot capability
        every { mockApiLevelProvider.getSdkInt() } returns 30

        provider = ScreenCaptureProviderImpl(screenshotEncoder, mockApiLevelProvider)
        mockService = mockk(relaxed = true)
        every { mockService.canTakeScreenshot() } returns true

        // Mock McpAccessibilityService.instance companion
        mockkObject(McpAccessibilityService)
        every { McpAccessibilityService.instance } returns mockService
    }

    @AfterEach
    fun teardown() {
        unmockkObject(McpAccessibilityService)
    }

    @Nested
    @DisplayName("captureScreenshotBitmap")
    inner class CaptureScreenshotBitmapTests {
        @Test
        fun `returns resized bitmap on success`() = runTest {
            val originalBitmap = mockk<Bitmap>(relaxed = true)
            val resizedBitmap = mockk<Bitmap>(relaxed = true)
            coEvery { mockService.takeScreenshotBitmap() } returns originalBitmap
            every {
                screenshotEncoder.resizeBitmapProportional(originalBitmap, 700, 700)
            } returns resizedBitmap

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isSuccess)
            assertEquals(resizedBitmap, result.getOrNull())
            // Original bitmap recycled because a new (different) one was produced
            verify(exactly = 1) { originalBitmap.recycle() }
        }

        @Test
        fun `does not recycle bitmap when resize returns same instance`() = runTest {
            val bitmap = mockk<Bitmap>(relaxed = true)
            coEvery { mockService.takeScreenshotBitmap() } returns bitmap
            every {
                screenshotEncoder.resizeBitmapProportional(bitmap, 700, 700)
            } returns bitmap // same instance

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isSuccess)
            verify(exactly = 0) { bitmap.recycle() }
        }

        @Test
        fun `returns failure when takeScreenshotBitmap returns null`() = runTest {
            coEvery { mockService.takeScreenshotBitmap() } returns null

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Screenshot capture failed") == true)
        }

        @Test
        fun `returns failure when service not available`() = runTest {
            every { McpAccessibilityService.instance } returns null

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Accessibility service not enabled") == true)
        }

        @Test
        fun `recycles original bitmap when resize throws`() = runTest {
            val bitmap = mockk<Bitmap>(relaxed = true)
            coEvery { mockService.takeScreenshotBitmap() } returns bitmap
            every {
                screenshotEncoder.resizeBitmapProportional(bitmap, any(), any())
            } throws RuntimeException("Resize failed")

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isFailure)
            // Error message is generic (does not leak internal exception details)
            assertTrue(result.exceptionOrNull()?.message == "Screenshot resize failed")
            // Original bitmap must be recycled even on resize failure
            verify(exactly = 1) { bitmap.recycle() }
        }

        @Test
        fun `returns failure when API level below 30`() = runTest {
            every { mockApiLevelProvider.getSdkInt() } returns 29

            val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Android 11") == true)
        }

        @Test
        fun `passes through null maxWidth and maxHeight`() = runTest {
            val bitmap = mockk<Bitmap>(relaxed = true)
            coEvery { mockService.takeScreenshotBitmap() } returns bitmap
            every {
                screenshotEncoder.resizeBitmapProportional(bitmap, null, null)
            } returns bitmap // no resize needed

            val result = provider.captureScreenshotBitmap(maxWidth = null, maxHeight = null)

            assertTrue(result.isSuccess)
            verify(exactly = 0) { bitmap.recycle() }
        }
    }
}
```

**Key design points**:
- Uses injectable `ApiLevelProvider` (created in Task 2.0) instead of reflective `Build.VERSION.SDK_INT` modification. On JDK 17+, `Field.getDeclaredField("modifiers")` throws `NoSuchFieldException`, and MockK's `mockkStatic` cannot intercept static field reads — only static methods. The `ApiLevelProvider` interface cleanly solves this.
- No `mockkStatic(Build.VERSION::class)` or `setStaticField` needed — all Android SDK dependencies are abstracted via constructor injection.
- `@AfterEach` ensures `unmockkObject` is called to prevent test pollution.
- Tests cover: success, same-bitmap (no recycle), null bitmap, service unavailable, resize throws, API level too low, and null maxWidth/maxHeight.

---

### Task 2.7: Update `ScreenIntrospectionIntegrationTest` for annotated screenshot

**Acceptance Criteria**:
- [x] Integration test for screenshot now mocks `captureScreenshotBitmap`, `screenshotAnnotator.annotate()`, and `screenshotEncoder.bitmapToScreenshotData()` instead of `captureScreenshot`
- [x] No Android framework mocking needed (no `mockkStatic(Bitmap::class)`)
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

#### Action 2.7.1: Update `get_screen_state with include_screenshot returns text and image`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`
**Lines**: 102-136

**What**: Since `ScreenshotAnnotator` and `ScreenshotEncoder` are now **mocked** (via `MockDependencies`, see Action 2.3.4), the integration test does NOT need to mock Android `Bitmap`/`Canvas`/`Paint` classes. Instead:
- Mock `captureScreenshotBitmap` to return a mock Bitmap
- Mock `screenshotAnnotator.annotate()` to return the same mock Bitmap
- Mock `screenshotEncoder.bitmapToScreenshotData()` to return a known `ScreenshotData`

This is clean, avoids all Android framework mocking, and isolates each component properly.

**Import to add** (at the top of the file, if not already present):
```diff
+import android.graphics.Bitmap
```

(The `assertFalse` import was already added in Action 1.3.1.)

```diff
 @Test
 fun `get_screen_state with include_screenshot returns text and image`() =
     runTest {
         val deps = McpIntegrationTestHelper.createMockDependencies()
         deps.setupReadyService()
         every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

-        coEvery {
-            deps.screenCaptureProvider.captureScreenshot(any(), any(), any())
-        } returns
-            Result.success(
-                ScreenshotData(
-                    data = "dGVzdA==",
-                    width = 700,
-                    height = 500,
-                ),
-            )
+        // Mock captureScreenshotBitmap to return a relaxed mock Bitmap
+        val mockBitmap = mockk<Bitmap>(relaxed = true)
+        coEvery {
+            deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
+        } returns Result.success(mockBitmap)
+
+        // Mock annotator to return the same mock bitmap (no Android Canvas needed)
+        val annotatedMockBitmap = mockk<Bitmap>(relaxed = true)
+        every {
+            deps.screenshotAnnotator.annotate(any(), any(), any(), any())
+        } returns annotatedMockBitmap
+
+        // Mock encoder to return known ScreenshotData
+        every {
+            deps.screenshotEncoder.bitmapToScreenshotData(any(), any())
+        } returns ScreenshotData(data = "dGVzdA==", width = 700, height = 500)

         McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
             val result =
                 client.callTool(
                     name = "android_get_screen_state",
                     arguments = mapOf("include_screenshot" to true),
                 )
             assertNotEquals(true, result.isError)
             assertEquals(2, result.content.size)

             val textContent = (result.content[0] as TextContent).text
             assertTrue(textContent.contains("note:"))
+            assertTrue(textContent.contains("note:flags:"))
+            assertTrue(textContent.contains("note:offscreen items"))
             assertTrue(textContent.contains("app:"))

             val imageContent = result.content[1] as ImageContent
             assertEquals("image/jpeg", imageContent.mimeType)
             assertEquals("dGVzdA==", imageContent.data)
         }

+        // Verify the annotated bitmap (not the resized bitmap) is encoded with the correct quality
+        verify {
+            deps.screenshotEncoder.bitmapToScreenshotData(
+                annotatedMockBitmap,
+                ScreenCaptureProvider.DEFAULT_QUALITY,
+            )
+        }
+        // Verify annotate received the resized bitmap (not some other bitmap)
+        verify {
+            deps.screenshotAnnotator.annotate(
+                mockBitmap,
+                any(),
+                1080,
+                2400,
+            )
+        }
     }
```

**Note**: No `mockkStatic(Bitmap::class)` is needed because we mock the `ScreenshotAnnotator` and `ScreenshotEncoder` interfaces, so no Android drawing code runs in this test. The added `verify` blocks ensure the correct bitmap flows through the pipeline: resizedBitmap → annotate → annotatedBitmap → encode.

#### Action 2.7.2: Update `get_screen_state without screenshot does not call captureScreenshot`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`
**Lines**: 138-158

**What**: Update the verify to check `captureScreenshotBitmap` is not called (instead of `captureScreenshot`).

```diff
 coVerify(exactly = 0) {
-    deps.screenCaptureProvider.captureScreenshot(any(), any(), any())
+    deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
 }
```

---

### Task 2.8: Add integration test for exception propagation and bitmap cleanup

**Acceptance Criteria**:
- [x] New test verifies that when `screenshotAnnotator.annotate()` throws an exception, the tool returns an error and both bitmaps are still recycled
- [x] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

#### Action 2.8.1: Add `get_screen_state with screenshot annotation failure returns error`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`

**What**: Add a new test that simulates `screenshotAnnotator.annotate()` throwing an exception. Verify the tool returns an error result. Since this test uses mocked annotator/encoder (via MockDependencies), we just configure the mock to throw.

```kotlin
@Test
fun `get_screen_state with screenshot annotation failure returns error`() =
    runTest {
        val deps = McpIntegrationTestHelper.createMockDependencies()
        deps.setupReadyService()
        every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

        val mockBitmap = mockk<Bitmap>(relaxed = true)
        coEvery {
            deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
        } returns Result.success(mockBitmap)

        // Simulate annotation failure
        every {
            deps.screenshotAnnotator.annotate(any(), any(), any(), any())
        } throws RuntimeException("Canvas error")

        McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
            val result =
                client.callTool(
                    name = "android_get_screen_state",
                    arguments = mapOf("include_screenshot" to true),
                )
            // The tool should return an error
            assertEquals(true, result.isError)
            // Verify the error message is generic (does not leak internal "Canvas error" details)
            val errorText = (result.content[0] as TextContent).text
            assertTrue(errorText.contains("Screenshot annotation failed"))
            assertFalse(errorText.contains("Canvas error"))
        }

        // Verify the resized bitmap was still recycled via the finally block
        // (annotatedBitmap is null because annotate() threw before assignment)
        verify { mockBitmap.recycle() }
    }
```

---

## User Story 3: Documentation Updates

**Goal**: Update all documentation to reflect the new TSV format and annotated screenshot behavior.

**Acceptance Criteria / Definition of Done**:
- [x] `docs/MCP_TOOLS.md` updated with new TSV format, flag abbreviations, note lines, annotated screenshot description, and tool intro
- [x] `docs/PROJECT.md` updated with new flag format, screenshot annotation mention, and `ScreenshotAnnotator.kt` in folder structure
- [x] `README.md` tool table updated to mention annotated screenshot
- [ ] Lint passes (no code changes, documentation only)

---

### Task 3.1: Update `docs/MCP_TOOLS.md`

**Acceptance Criteria**:
- [x] Tool intro description mentions annotated screenshot
- [x] Response examples show new flag format
- [x] Flags Reference table updated with abbreviations
- [x] Output Format section updated with new note lines
- [x] Screenshot section describes bounding box annotations

#### Action 3.1.0: Update tool intro description

**File**: `docs/MCP_TOOLS.md`
**Lines**: 154-156 (tool intro)

**What**: Update the tool description to mention the annotated screenshot.

```diff
-Returns the consolidated current screen state: app metadata, screen dimensions, and a compact filtered flat TSV list of UI elements. Optionally includes a low-resolution screenshot.
+Returns the consolidated current screen state: app metadata, screen dimensions, and a compact filtered flat TSV list of UI elements. Optionally includes an annotated low-resolution screenshot with bounding boxes and element ID labels.
```

#### Action 3.1.1: Update response examples

**File**: `docs/MCP_TOOLS.md`
**Lines**: 203-237 (response examples)

**What**: Update the TSV text in both response examples to use new flag format (`on,clk,ena` instead of `vcn`) and include the two new note lines.

```diff
 **Response Example (text only)**:
 ```json
 {
   "jsonrpc": "2.0",
   "id": 1,
   "result": {
     "content": [
       {
         "type": "text",
-        "text": "note:structural-only nodes are omitted from the tree\nnote:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\nnode_1\tTextView\tCalculator\t-\tcom.android.calculator2:id/title\t100,50,500,120\tvn\nnode_2\tButton\t7\t-\tcom.android.calculator2:id/digit_7\t50,800,270,1000\tvcn"
+        "text": "note:structural-only nodes are omitted from the tree\nnote:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account\nnote:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled\nnote:offscreen items require scroll_to_element before interaction\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\nnode_1\tTextView\tCalculator\t-\tcom.android.calculator2:id/title\t100,50,500,120\ton,ena\nnode_2\tButton\t7\t-\tcom.android.calculator2:id/digit_7\t50,800,270,1000\ton,clk,ena"
       }
     ]
   }
 }
 ```

 **Response Example (with screenshot)**:
 ```json
 {
   "jsonrpc": "2.0",
   "id": 1,
   "result": {
     "content": [
       {
         "type": "text",
-        "text": "note:structural-only nodes are omitted from the tree\nnote:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\n..."
+        "text": "note:structural-only nodes are omitted from the tree\nnote:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account\nnote:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled\nnote:offscreen items require scroll_to_element before interaction\napp:com.android.calculator2 activity:.Calculator\nscreen:1080x2400 density:420 orientation:portrait\nid\tclass\ttext\tdesc\tres_id\tbounds\tflags\n..."
       },
       {
         "type": "image",
-        "data": "/9j/4AAQSkZJRgABAQ...<base64 JPEG data>",
+        "data": "/9j/4AAQSkZJRgABAQ...<base64 annotated JPEG data>",
         "mimeType": "image/jpeg"
       }
     ]
   }
 }
 ```
```

#### Action 3.1.2: Update Output Format section

**File**: `docs/MCP_TOOLS.md`
**Lines**: 240-249 (output format description)

**What**: Update the numbered list to include the two new note lines (flags legend at position 3, offscreen hint at position 4) and shift subsequent line numbers.

```diff
 #### Output Format

 The text output is a compact flat TSV (tab-separated values) format designed for token-efficient LLM consumption:

 1. **Note line**: `note:structural-only nodes are omitted from the tree`
 2. **Note line**: `note:certain elements are custom and will not be properly reported, if needed or if tools are not working as expected set include_screenshot=true to see the screen and take what you see into account`
-3. **App line**: `app:<package> activity:<activity>`
-4. **Screen line**: `screen:<width>x<height> density:<dpi> orientation:<orientation>`
-5. **Header**: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
-6. **Data rows**: One row per filtered node with tab-separated values
+3. **Note line**: `note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable foc=focusable scr=scrollable edt=editable ena=enabled`
+4. **Note line**: `note:offscreen items require scroll_to_element before interaction`
+5. **App line**: `app:<package> activity:<activity>`
+6. **Screen line**: `screen:<width>x<height> density:<dpi> orientation:<orientation>`
+7. **Header**: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
+8. **Data rows**: One row per filtered node with tab-separated values
```

#### Action 3.1.3: Update Flags Reference table

**File**: `docs/MCP_TOOLS.md`
**Lines**: ~255-270 (flags reference)

**What**: Replace the single-letter flag table with the new abbreviation table. Update the description text.

```diff
-The `flags` column uses single-character codes for node properties:
+The `flags` column uses comma-separated abbreviated codes. A legend is included in the TSV notes.
+Only flags that are `true` are included. The `on`/`off` flag is always first.

-| Flag | Meaning |
-|------|---------|
-| `v` | visible |
-| `c` | clickable |
-| `l` | longClickable |
-| `f` | focusable |
-| `s` | scrollable |
-| `e` | editable |
-| `n` | enabled |
+| Flag   | Meaning       |
+|--------|---------------|
+| `on`   | onscreen      |
+| `off`  | offscreen     |
+| `clk`  | clickable     |
+| `lclk` | longClickable |
+| `foc`  | focusable     |
+| `scr`  | scrollable    |
+| `edt`  | editable      |
+| `ena`  | enabled       |

-Only flags that are `true` are included. Example: `vcn` means visible + clickable + enabled.
+Example: `on,clk,ena` means onscreen + clickable + enabled.
```

#### Action 3.1.4: Update Screenshot section

**File**: `docs/MCP_TOOLS.md`
**Lines**: ~288-295 (screenshot section)

**What**: Update to describe that the screenshot is annotated with bounding boxes and element ID labels.

```diff
-When `include_screenshot` is `true`, a low-resolution JPEG screenshot (max 700px in either dimension, quality 80) is included as a second content item (`ImageContent`). Only request the screenshot when the element list alone is not sufficient to understand the screen layout.
+When `include_screenshot` is `true`, a low-resolution annotated JPEG screenshot (max 700px in either dimension, quality 80) is included as a second content item (`ImageContent`). The screenshot is annotated with:
+- **Red dashed bounding boxes** (2px) around each on-screen element that appears in the TSV
+- **Semi-transparent red pill labels** with white bold text showing the element ID hash (e.g., `a3f2` for `node_a3f2`) at the top-left of each bounding box
+
+Off-screen elements (marked with `off` flag in the TSV) do not have bounding boxes on the screenshot. Use the `scroll_to_element` tool to bring them into view first.
+
+Only request the screenshot when the element list alone is not sufficient to understand the screen layout.
```

---

### Task 3.2: Update `docs/PROJECT.md`

**Acceptance Criteria**:
- [x] Screen Introspection Tools table updated to mention annotated screenshot
- [x] Note about flag format updated
- [x] Folder structure updated with `ScreenshotAnnotator.kt`

#### Action 3.2.1: Update Screen Introspection Tools table

**File**: `docs/PROJECT.md`
**Lines**: ~195-203

**What**: Update the description and output column to mention annotated screenshot and new flag format.

```diff
-| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI elements | `include_screenshot` (boolean, optional, default false) | `TextContent` with compact TSV (text/desc truncated to 100 chars). Optionally includes `ImageContent` with low-resolution JPEG screenshot (700px max). |
+| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI elements | `include_screenshot` (boolean, optional, default false) | `TextContent` with compact TSV (text/desc truncated to 100 chars, comma-separated abbreviated flags with on/off visibility). Optionally includes `ImageContent` with annotated low-resolution JPEG screenshot (700px max, red bounding boxes with element ID labels). |
```

Update the note below the table:

```diff
-**Note**: `android_get_screen_state` returns a filtered flat TSV list — structural-only nodes (no text, no contentDescription, no resourceId, not interactive) are omitted. Text and contentDescription are truncated to 100 characters; use `android_get_element_details` to retrieve full values.
+**Note**: `android_get_screen_state` returns a filtered flat TSV list — structural-only nodes (no text, no contentDescription, no resourceId, not interactive) are omitted. Text and contentDescription are truncated to 100 characters; use `android_get_element_details` to retrieve full values. Flags use comma-separated abbreviations with a legend in the TSV notes. When screenshot is included, it is annotated with red bounding boxes and element ID labels for on-screen elements.
```

#### Action 3.2.2: Add `ScreenshotAnnotator.kt` to folder structure

**File**: `docs/PROJECT.md`
**Lines**: ~131 (folder structure, `services/screencapture/` line)

**What**: Add `ScreenshotAnnotator.kt` to the `services/screencapture/` listing.

```diff
-  - `services/screencapture/` — `ScreenCaptureProvider.kt`, `ScreenCaptureProviderImpl.kt`, `ScreenshotEncoder.kt`
+  - `services/screencapture/` — `ScreenCaptureProvider.kt`, `ScreenCaptureProviderImpl.kt`, `ScreenshotEncoder.kt`, `ScreenshotAnnotator.kt`, `ApiLevelProvider.kt`
```

---

### Task 3.3: Update `README.md` tool table

**Acceptance Criteria**:
- [x] Screen Introspection description mentions annotated screenshot

#### Action 3.3.1: Update tool table row

**File**: `README.md`
**Lines**: ~29 (Screen Introspection row)

**What**: Update the description to mention annotated screenshot.

```diff
-| **Screen Introspection** (1) | `android_get_screen_state` | Consolidated screen state: app info, screen dimensions, filtered UI element list (TSV), optional low-res screenshot |
+| **Screen Introspection** (1) | `android_get_screen_state` | Consolidated screen state: app info, screen dimensions, filtered UI element list (TSV), optional annotated low-res screenshot with bounding boxes and element ID labels |
```

---

## User Story 4: Update E2E Tests, Run All Tests & Final Verification

**Goal**: Update E2E tests for the new format, then verify everything works end-to-end.

**Acceptance Criteria / Definition of Done**:
- [ ] E2E tests updated with new flag format and note line assertions (`E2ECalculatorTest.kt`, `E2EScreenshotTest.kt`)
- [ ] Project compiles without errors or warnings: `./gradlew assembleDebug`
- [ ] All unit + integration tests pass: `make test-unit`
- [ ] Lint passes: `make lint`
- [ ] E2E tests pass: `make test-e2e` (if Docker environment is available; skip if not)
- [ ] Final review: re-read every changed file against this plan to verify nothing was missed or diverged

---

### Task 4.1: Update E2E tests for new TSV format and annotated screenshot

**Acceptance Criteria**:
- [ ] `E2ECalculatorTest.kt` updated to add assertions for new note lines and flag format
- [ ] `E2EScreenshotTest.kt` updated to add assertions for new note lines
- [ ] Both files add negative assertions for old single-char flag format
- [ ] Lint passes on changed E2E files

#### Action 4.1.1: Update `E2ECalculatorTest.kt` assertions

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`
**Lines**: 74-82 (`calculate 7 plus 3 equals 10` — Step 3 assertions)

**What**: Add assertions for the new flags legend and offscreen hint note lines after the existing TSV checks. Add negative assertions for the old single-char flag format.

```diff
         // Step 3: Verify Calculator is visible in screen state
         val tree = mcpClient.callTool("get_screen_state")
         val treeStr = (tree.content[0] as TextContent).text
         println("[E2E Calculator] Screen state excerpt: ${treeStr.take(1000)}")
         assertTrue(
             treeStr.contains("Calculator", ignoreCase = true) ||
                 treeStr.contains(CALCULATOR_PACKAGE, ignoreCase = true),
             "Screen state should contain Calculator app. Excerpt: ${treeStr.take(500)}",
         )
+        // Verify new note lines
+        assertTrue(
+            treeStr.contains("note:flags: on=onscreen off=offscreen"),
+            "Screen state should contain flags legend note line",
+        )
+        assertTrue(
+            treeStr.contains("note:offscreen items require scroll_to_element before interaction"),
+            "Screen state should contain offscreen hint note line",
+        )
+        // Verify new comma-separated flag format (at least one element should be on-screen + clickable + enabled)
+        assertTrue(
+            treeStr.contains("on,clk") || treeStr.contains("on,ena"),
+            "Screen state flags should use comma-separated abbreviations",
+        )
+        // Negative assertions: old single-char format must not appear
+        assertFalse(
+            treeStr.contains("\tvcn") || treeStr.contains("\tvclfsen") || treeStr.contains("\tvn"),
+            "Screen state should NOT contain old single-char flag format",
+        )
```

#### Action 4.1.2: Update `E2EScreenshotTest.kt` assertions

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EScreenshotTest.kt`
**Lines**: 48-54 (TextContent assertions)

**What**: Add assertions for the new note lines after the existing `text.contains("note:")` check. Add negative assertions for old flag format.

```diff
         // Verify first content item is TextContent with compact TSV
         val textContent = result.content[0]
         assertTrue(textContent is TextContent, "First content item should be TextContent")
         val text = (textContent as TextContent).text
         assertTrue(text.contains("note:"), "Text should contain note line")
         assertTrue(text.contains("app:"), "Text should contain app line")
+        assertTrue(
+            text.contains("note:flags: on=onscreen off=offscreen"),
+            "Text should contain flags legend note line",
+        )
+        assertTrue(
+            text.contains("note:offscreen items require scroll_to_element before interaction"),
+            "Text should contain offscreen hint note line",
+        )
+        // Negative assertions: old single-char flags must not appear
+        assertFalse(
+            text.contains("\tvcn") || text.contains("\tvn"),
+            "Text should NOT contain old single-char flag format",
+        )
```

---

### Task 4.2: Build, lint, and run full test suite

**Acceptance Criteria**:
- [ ] Project compiles without errors or warnings
- [ ] All unit + integration tests pass
- [ ] Lint passes with no new warnings/errors
- [ ] E2E tests pass (if Docker available)

#### Action 4.2.1: Compile the project

```bash
./gradlew assembleDebug
```

**Expected**: Build succeeds without errors or warnings. This verifies all new code compiles before running tests.

#### Action 4.2.2: Run unit + integration tests

```bash
make test-unit
```

**Expected**: All tests pass, including updated CompactTreeFormatterTest, ScreenIntrospectionIntegrationTest, new ScreenshotAnnotatorTest, and new ScreenCaptureProviderImplTest.

#### Action 4.2.3: Run lint

```bash
make lint
```

**Expected**: No new warnings or errors.

#### Action 4.2.4: Run E2E tests (if Docker available)

```bash
make test-e2e
```

**Expected**: All E2E tests pass including updated assertions in `E2ECalculatorTest` and `E2EScreenshotTest`. If Docker is not available, skip and note as a gap.

---

### Task 4.3: Final verification against plan

#### Action 4.3.1: Re-read every changed file and verify against this plan

**What**: Systematically go through each file changed during implementation and verify:

1. **`CompactTreeFormatter.kt`**:
   - [ ] Class-level KDoc updated with new line numbers (4 note lines, data rows at 8+)
   - [ ] `buildFlags` KDoc updated: says `on/off, clk, lclk, foc, scr, edt, ena`
   - [ ] `buildFlags` uses comma-separated abbreviated words via `buildString`
   - [ ] `on`/`off` is always the first flag
   - [ ] New note line constants exist: `NOTE_LINE_FLAGS_LEGEND`, `NOTE_LINE_OFFSCREEN_HINT`
   - [ ] Flag abbreviation constants exist: `FLAG_ONSCREEN`, `FLAG_OFFSCREEN`, `FLAG_CLICKABLE`, `FLAG_LONG_CLICKABLE`, `FLAG_FOCUSABLE`, `FLAG_SCROLLABLE`, `FLAG_EDITABLE`, `FLAG_ENABLED`, `FLAG_SEPARATOR`
   - [ ] `format()` outputs 4 note lines (structural, custom elements, flags legend, offscreen hint)
   - [ ] `format()` comments updated with correct line numbers
   - [ ] Old `FLAG_COUNT` constant removed

2. **`CompactTreeFormatterTest.kt`**:
   - [ ] All `buildFlags` tests use new expected values
   - [ ] All `format` tests use updated line indices (data rows at line 7+)
   - [ ] New tests for flags legend note line and offscreen hint note line
   - [ ] New test for `off` flag on non-visible nodes
   - [ ] New test verifying `on` is always first flag
   - [ ] New test for `off` with ALL other flags set (`off,clk,lclk,foc,scr,edt,ena`)
   - [ ] `includesNonVisibleNodesThatPassFilter` uses flags-column-specific assertion (split on tab, check last field)

3. **`ScreenCaptureProvider.kt`**:
   - [ ] New `captureScreenshotBitmap(maxWidth, maxHeight)` method in interface
   - [ ] Returns `Result<Bitmap>`
   - [ ] KDoc documents caller must recycle

4. **`ScreenCaptureProviderImpl.kt`**:
   - [ ] Injects `ApiLevelProvider` instead of reading `Build.VERSION.SDK_INT` directly
   - [ ] Uses `apiLevelProvider.getSdkInt()` in `validateService()` and `isScreenCaptureAvailable()`
   - [ ] `import android.os.Build` removed (uses `API_LEVEL_R` constant instead)
   - [ ] Implements `captureScreenshotBitmap`
   - [ ] Captures bitmap, resizes, returns without encoding
   - [ ] Recycles original bitmap if resize produced a new one
   - [ ] try/catch ensures original bitmap is recycled if `resizeBitmapProportional` throws
   - [ ] Error messages are generic (no `exception.message` leaked to client), full exception logged server-side

4a. **`ApiLevelProvider.kt`** (NEW):
   - [ ] `ApiLevelProvider` interface with `getSdkInt(): Int`
   - [ ] `DefaultApiLevelProvider` implementation reading `Build.VERSION.SDK_INT`
   - [ ] Both Hilt-injectable (`@Inject constructor()`)

4b. **`AppModule.kt` / `ServiceModule`**:
   - [ ] `@Binds @Singleton` for `ApiLevelProvider` → `DefaultApiLevelProvider`

5. **`ScreenshotAnnotator.kt`** (NEW):
   - [ ] `@Inject constructor()`
   - [ ] `ScaledBounds` inner data class (pure Kotlin, NOT `RectF`) for `computeScaledBounds` return type
   - [ ] `annotate(bitmap, elements, screenWidth, screenHeight)` method
   - [ ] Uses `Bitmap.Config.ARGB_8888` unconditionally (not `bitmap.config`)
   - [ ] Null check on `bitmap.copy()` result — throws `IllegalStateException` on null
   - [ ] try/catch inside `annotate()` — recycles copy bitmap on failure before re-throwing
   - [ ] Red dashed 2px bounding boxes
   - [ ] Semi-transparent red pill labels with white bold text
   - [ ] Element ID hash (without `node_` prefix)
   - [ ] Coordinate mapping from screen to bitmap space, `ScaledBounds` converted to `RectF` only for Canvas drawing
   - [ ] Handles empty list, out-of-bounds elements, partial clipping
   - [ ] Returns new bitmap (does not mutate input)
   - [ ] Paint objects created ONCE before the element loop (not per-element)
   - [ ] Testable helper methods extracted: `computeScaledBounds` (returns `ScaledBounds`), `extractLabel`
   - [ ] `element.bounds` accessed directly (non-nullable `BoundsData`), no `?:` operator
   - [ ] Password/sensitive fields ARE annotated (intentional; Android already masks display text)

6. **`ScreenshotAnnotatorTest.kt`** (NEW):
   - [ ] Local `makeNode` helper defined in the outer test class
   - [ ] Tests for empty list, single element, multiple elements (AnnotateTests inner class)
   - [ ] Test for copy bitmap recycling when drawing fails (AnnotateTests inner class)
   - [ ] Tests for `bitmap.copy()` returning null — `IllegalStateException` thrown (with elements AND with empty elements)
   - [ ] Tests for coordinate mapping using `ScaledBounds` (ComputeScaledBoundsTests inner class) — no `RectF` assertions
   - [ ] Tests for ID hash extraction (ExtractLabelTests inner class), including `node_` edge case (empty hash)
   - [ ] Tests for out-of-bounds elements (fully outside → null, partially outside → clamped, zero-width → null, clamping-caused collapse → null)
   - [ ] `unmockkConstructor(Canvas::class)` in `@AfterEach` to prevent test pollution
   - [ ] `bitmap.copy` mock uses `ARGB_8888` (not `bitmap.config`)
   - [ ] No dead `mockCanvas` field — uses `mockkConstructor(Canvas::class)` with `constructedWith` instead

7. **`ScreenIntrospectionTools.kt`**:
   - [ ] `GetScreenStateHandler` constructor takes `ScreenshotAnnotator` and `ScreenshotEncoder`
   - [ ] `collectOnScreenElements` helper method uses accumulator pattern (single `MutableList` parameter, no `addAll`)
   - [ ] Screenshot path: `captureScreenshotBitmap` → annotate → encode → return
   - [ ] Proper bitmap recycling (resized + annotated in finally block)
   - [ ] Generic exceptions wrapped in `McpToolException.ActionFailed` with generic message (no `exception.message` leaked)
   - [ ] Full exception logged server-side via `Log.e(TAG, ...)`
   - [ ] `McpToolException` re-thrown as-is (not double-wrapped)

8. **`McpServerService.kt`**:
   - [ ] `@Inject lateinit var screenshotAnnotator: ScreenshotAnnotator`
   - [ ] `@Inject lateinit var screenshotEncoder: ScreenshotEncoder`
   - [ ] Both passed to `registerScreenIntrospectionTools`

9. **`McpIntegrationTestHelper.kt`**:
   - [ ] `MockDependencies` has `screenshotAnnotator` and `screenshotEncoder` fields (mocked, NOT real instances)
   - [ ] `createMockDependencies()` creates both as `mockk(relaxed = true)`
   - [ ] `registerAllTools` passes `deps.screenshotAnnotator` and `deps.screenshotEncoder` to `registerScreenIntrospectionTools`

10. **`ScreenIntrospectionIntegrationTest.kt`**:
    - [ ] Added imports: `android.graphics.Bitmap`, `org.junit.jupiter.api.Assertions.assertFalse`
    - [ ] Assertions for new note lines (`note:flags:`, `note:offscreen items`)
    - [ ] Assertions for new flag format (`on,clk,ena`)
    - [ ] Negative assertions for old flag format (no `vcn`, no `vclfsen`)
    - [ ] Screenshot test mocks `captureScreenshotBitmap`, `screenshotAnnotator.annotate()`, and `screenshotEncoder.bitmapToScreenshotData()` — NO `mockkStatic(Bitmap::class)` needed
    - [ ] Screenshot test verifies correct bitmap and quality passed to encoder (`annotatedMockBitmap`, `DEFAULT_QUALITY`)
    - [ ] Screenshot test verifies correct bitmap and screen dimensions passed to annotator (`mockBitmap`, `1080`, `2400`)
    - [ ] No-screenshot test verifies `captureScreenshotBitmap` not called
    - [ ] New test for annotation failure: verifies error returned, generic error message (no internal details leaked), bitmap recycled

11. **`ScreenCaptureProviderImplTest.kt`** (NEW):
    - [ ] Uses mock `ApiLevelProvider` (no `mockkStatic(Build.VERSION::class)`, no `setStaticField` reflection — JDK 17 compatible)
    - [ ] `@BeforeEach`/`@AfterEach` with `mockkObject(McpAccessibilityService)`/`unmockkObject`
    - [ ] Tests for `captureScreenshotBitmap`: success, failure (null bitmap), service not available, bitmap recycling when resize produces new bitmap, bitmap NOT recycled when resize returns same instance
    - [ ] Test for bitmap recycling when resize throws (generic error message verified)
    - [ ] Test for API level below 30 (returns failure)
    - [ ] Test for null maxWidth/maxHeight pass-through

11a. **`ScreenIntrospectionToolsTest.kt`** (UPDATED):
    - [ ] `sampleCompactOutput` updated with new note lines and flag format (`on,clk,ena`)
    - [ ] Handler construction updated with `screenshotAnnotator` and `screenshotEncoder` mock dependencies
    - [ ] Screenshot test mocks updated from `captureScreenshot` to `captureScreenshotBitmap` + `annotate` + `bitmapToScreenshotData`
    - [ ] No-screenshot verify updated from `captureScreenshot` to `captureScreenshotBitmap`

12. **`docs/MCP_TOOLS.md`**:
    - [ ] Tool intro description mentions "annotated" screenshot
    - [ ] Response examples use new flag format and include new note lines
    - [ ] Output Format lists 8 numbered items (4 note lines, app, screen, header, data rows)
    - [ ] Flags Reference table uses abbreviations (old single-char table removed)
    - [ ] Screenshot section describes bounding box annotations

13. **`docs/PROJECT.md`**:
    - [ ] Screen Introspection Tools table updated
    - [ ] Note updated with flag format and annotation info
    - [ ] Folder structure lists `ScreenshotAnnotator.kt` and `ApiLevelProvider.kt` in `services/screencapture/`

14. **`README.md`**:
    - [ ] Screen Introspection tool table row updated to mention annotated screenshot

15. **E2E tests**:
    - [ ] `E2ECalculatorTest.kt`: assertions for new note lines, flag format, negative assertions for old format
    - [ ] `E2EScreenshotTest.kt`: assertions for new note lines, negative assertions for old format
