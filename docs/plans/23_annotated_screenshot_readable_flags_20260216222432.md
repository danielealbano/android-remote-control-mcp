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
- [ ] The flags column uses comma-separated abbreviated words: `on`/`off`, `clk`, `lclk`, `foc`, `scr`, `edt`, `ena`
- [ ] The `on`/`off` flag is always the first flag in the list
- [ ] A flags legend note line is always present in the TSV output
- [ ] An offscreen hint note line is always present in the TSV output
- [ ] The old single-letter `v` flag is removed and replaced by `on`/`off`
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatterTest"`
- [ ] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`
- [ ] Lint passes on changed files

---

### Task 1.1: Update `CompactTreeFormatter` — flag format and note lines

**Acceptance Criteria**:
- [ ] `buildFlags` returns comma-separated abbreviated words with `on`/`off` as the first flag
- [ ] New constants for flag abbreviations and note lines are added
- [ ] `format()` outputs the flags legend note line and offscreen hint note line
- [ ] Lint passes: `./gradlew ktlintCheck`

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

#### Action 1.1.2: Update `buildFlags` method

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`
**Lines**: 158-168 (`buildFlags` method)

**What**: Replace single-letter flag building with comma-separated abbreviated words using `buildString` (zero intermediate allocations). Add `on`/`off` as the first flag based on the `visible` property.

```diff
-    internal fun buildFlags(node: AccessibilityNodeData): String {
-        val sb = StringBuilder(FLAG_COUNT)
-        if (node.visible) sb.append('v')
-        if (node.clickable) sb.append('c')
-        if (node.longClickable) sb.append('l')
-        if (node.focusable) sb.append('f')
-        if (node.scrollable) sb.append('s')
-        if (node.editable) sb.append('e')
-        if (node.enabled) sb.append('n')
-        return sb.toString()
-    }
+    internal fun buildFlags(node: AccessibilityNodeData): String = buildString {
+        append(if (node.visible) FLAG_ONSCREEN else FLAG_OFFSCREEN)
+        if (node.clickable) { append(FLAG_SEPARATOR); append(FLAG_CLICKABLE) }
+        if (node.longClickable) { append(FLAG_SEPARATOR); append(FLAG_LONG_CLICKABLE) }
+        if (node.focusable) { append(FLAG_SEPARATOR); append(FLAG_FOCUSABLE) }
+        if (node.scrollable) { append(FLAG_SEPARATOR); append(FLAG_SCROLLABLE) }
+        if (node.editable) { append(FLAG_SEPARATOR); append(FLAG_EDITABLE) }
+        if (node.enabled) { append(FLAG_SEPARATOR); append(FLAG_ENABLED) }
+    }
```

**Note**: Uses `buildString` instead of `mutableListOf + joinToString` to avoid intermediate list allocation and the `joinToString` overhead. This keeps allocation minimal (single `StringBuilder`), similar to the original implementation.

#### Action 1.1.3: Update `format()` to include new note lines

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`
**Lines**: 35-65 (`format` method)

**What**: Add the flags legend note line after the custom elements note, and the offscreen hint note line after the flags legend.

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
- [ ] All `buildFlags` tests updated to expect new comma-separated format
- [ ] All `format` tests updated to expect new note lines and correct line indices
- [ ] New test cases added for `on`/`off` flags
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatterTest"`

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
+    assertTrue(lines[7].contains("off,"))
+    assertFalse(lines[7].contains("on,"))
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
- [ ] Integration test assertions updated for new note lines and flag format
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

#### Action 1.3.1: Update assertion in `get_screen_state returns compact flat TSV with metadata`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`
**Lines**: 77-100 (first test)

**What**: Add assertions for the new note lines and update the flag format assertion.

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

---

## User Story 2: Annotated Screenshot with Bounding Boxes

**Goal**: When `include_screenshot=true`, overlay red dashed bounding boxes with element ID hash labels on the screenshot for all on-screen elements that appear in the TSV.

**Acceptance Criteria / Definition of Done**:
- [ ] Screenshot returned by `get_screen_state` has red dashed bounding boxes drawn on on-screen TSV elements
- [ ] Each bounding box has a semi-transparent red pill label with white bold text showing the element ID hash (without `node_` prefix)
- [ ] Element bounds from screen coordinates are correctly mapped to the scaled screenshot coordinates
- [ ] Off-screen elements have no bounding boxes
- [ ] Elements not in the TSV (filtered out by `shouldKeepNode`) have no bounding boxes
- [ ] `ScreenCaptureProvider` interface exposes a method to get the raw resized `Bitmap`
- [ ] `ScreenshotAnnotator` is a new injectable class under `services/screencapture/`
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotatorTest"`
- [ ] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`
- [ ] Lint passes on changed files

---

### Task 2.1: Add `captureScreenshotBitmap` to `ScreenCaptureProvider`

**Acceptance Criteria**:
- [ ] `ScreenCaptureProvider` interface has a new `captureScreenshotBitmap(maxWidth, maxHeight)` method returning `Result<Bitmap>`
- [ ] `ScreenCaptureProviderImpl` implements it (capture + resize, no JPEG encoding)
- [ ] Bitmap lifecycle is documented (caller must recycle)
- [ ] Lint passes on changed files

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
+        val resizedBitmap = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
+        // If resize produced a new bitmap, recycle the original
+        if (resizedBitmap !== bitmap) {
+            bitmap.recycle()
+        }
+        return Result.success(resizedBitmap)
+    }
```

---

### Task 2.2: Create `ScreenshotAnnotator`

**Acceptance Criteria**:
- [ ] New file `ScreenshotAnnotator.kt` in `services/screencapture/`
- [ ] Draws red dashed 2px bounding boxes on on-screen elements
- [ ] Draws semi-transparent red pill label with white bold text (element ID hash) at top-left of each box
- [ ] Correctly maps element bounds from screen coordinates to scaled bitmap coordinates
- [ ] Only annotates elements that pass `shouldKeepNode` AND are visible (on-screen)
- [ ] Returns a new annotated `Bitmap` (does not mutate the input)
- [ ] Hilt-injectable via `@Inject constructor()`
- [ ] Lint passes

#### Action 2.2.1: Create `ScreenshotAnnotator.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotator.kt` (NEW)

**What**: Create a class that takes a `Bitmap`, a flat list of `AccessibilityNodeData` (already filtered + on-screen), and the original screen dimensions. It draws bounding boxes and labels on a copy of the bitmap and returns the annotated copy.

**Implementation details**:

1. **Input**: `annotate(bitmap: Bitmap, elements: List<AccessibilityNodeData>, screenWidth: Int, screenHeight: Int): Bitmap`
   - `bitmap`: The resized screenshot bitmap (e.g., 700px max)
   - `elements`: Flat list of nodes that are both `shouldKeepNode == true` AND `visible == true`
   - `screenWidth`/`screenHeight`: Original screen dimensions for coordinate mapping

2. **Coordinate mapping**:
   - `scaleX = bitmap.width.toFloat() / screenWidth.toFloat()`
   - `scaleY = bitmap.height.toFloat() / screenHeight.toFloat()`
   - For each element: `left = bounds.left * scaleX`, `top = bounds.top * scaleY`, `right = bounds.right * scaleX`, `bottom = bounds.bottom * scaleY`

3. **Bounding box style**:
   - Red dashed outline: `Paint()` with `color = Color.RED`, `style = STROKE`, `strokeWidth = 2f * density` (where density = `bitmap.width / 360f` as approximate dp-to-px), `pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), 0f)`
   - Clamp drawn bounds to bitmap dimensions to avoid drawing outside

4. **Label style**:
   - Text: element ID hash (strip `node_` prefix)
   - Font: bold, size scaled to bitmap (approximately `10f * (bitmap.width / 360f)`)
   - Background pill: semi-transparent red rectangle (`Color.argb(180, 255, 0, 0)`) with small padding
   - Text color: `Color.WHITE`
   - Position: top-left corner of bounding box, shifted inside if near edge

5. **Paint object reuse** (IMPORTANT for performance):
   - All `Paint` objects (box stroke paint, label background paint, label text paint) MUST be created **once** before the element loop, NOT inside the per-element loop.
   - The `Canvas` object wrapping the mutable bitmap copy is also created once.

6. **Return**: A new `Bitmap` (mutable software copy of input via `bitmap.copy(config, true)`). Input bitmap is NOT recycled — caller retains ownership.

6. **Edge cases**:
   - Empty elements list → return copy of bitmap unchanged
   - Element bounds fully outside bitmap → skip that element
   - Element bounds partially outside bitmap → clamp to bitmap edges

**Helper method**: `collectAnnotatableElements(tree: AccessibilityNodeData): List<AccessibilityNodeData>`
   - Walks the tree recursively
   - Collects nodes where `shouldKeepNode(node) == true` AND `node.visible == true`
   - This reuses the same `shouldKeepNode` logic from `CompactTreeFormatter` — to avoid duplication, extract the predicate or accept it as a parameter. Since `CompactTreeFormatter.shouldKeepNode` is `internal`, the simplest approach is to make `ScreenshotAnnotator` accept the already-filtered list from the caller.

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
     */
    fun annotate(
        bitmap: Bitmap,
        elements: List<AccessibilityNodeData>,
        screenWidth: Int,
        screenHeight: Int,
    ): Bitmap {
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (elements.isEmpty()) return copy

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
            val scaledRect = computeScaledBounds(
                element, scaleX, scaleY, copy.width, copy.height,
            ) ?: continue

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
    }

    /**
     * Computes the scaled bounding rectangle for an element on the bitmap.
     *
     * @return Scaled [RectF] clamped to bitmap bounds, or null if fully outside.
     */
    internal fun computeScaledBounds(
        element: AccessibilityNodeData,
        scaleX: Float,
        scaleY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): RectF? {
        val bounds = element.bounds ?: return null
        val left = (bounds.left * scaleX).coerceIn(0f, bitmapWidth.toFloat())
        val top = (bounds.top * scaleY).coerceIn(0f, bitmapHeight.toFloat())
        val right = (bounds.right * scaleX).coerceIn(0f, bitmapWidth.toFloat())
        val bottom = (bounds.bottom * scaleY).coerceIn(0f, bitmapHeight.toFloat())

        // Skip if fully collapsed after clamping
        if (right <= left || bottom <= top) return null

        return RectF(left, top, right, bottom)
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
- All `Paint` objects are created **once** before the loop (performance review fix).
- `computeScaledBounds` and `extractLabel` are `internal` methods, directly unit-testable on JVM without mocking Android graphics classes.
- `annotate` creates a mutable copy via `bitmap.copy()` — input is never modified.
- Password/sensitive fields are intentionally annotated — Android masks display text, so no secrets are exposed.
- Elements with null bounds are skipped. Elements fully outside bitmap after scaling are skipped.

---

### Task 2.3: Inject `ScreenshotAnnotator` and `ScreenshotEncoder` into screen introspection

**Acceptance Criteria**:
- [ ] `GetScreenStateHandler` accepts `ScreenshotAnnotator` and `ScreenshotEncoder` as constructor dependencies
- [ ] `registerScreenIntrospectionTools` function signature updated to accept the new dependencies
- [ ] `McpServerService.registerAllTools` passes the new dependencies
- [ ] `McpIntegrationTestHelper.registerAllTools` passes the new dependencies (mocked or real)
- [ ] Lint passes on changed files

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
- [ ] When `include_screenshot=true`, captures bitmap, collects on-screen filtered elements, annotates, encodes, returns
- [ ] Uses `captureScreenshotBitmap` instead of `captureScreenshot`
- [ ] Properly recycles all bitmaps (resized, annotated)
- [ ] Element collection reuses `CompactTreeFormatter.shouldKeepNode` logic
- [ ] Lint passes

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
                     throw McpToolException.ActionFailed(
                         "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
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
- [ ] New test file `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotatorTest.kt`
- [ ] Tests cover: empty elements list, single element, multiple elements, coordinate mapping, bounds clamping, element outside bitmap, ID hash extraction
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotatorTest"`

#### Action 2.5.1: Separate testable coordinate/label logic from drawing in `ScreenshotAnnotator`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotator.kt`

**What**: To make the `ScreenshotAnnotator` properly testable on JVM without mocking a dozen Android graphics classes, extract **pure logic** into `internal` helper methods that the unit tests can call directly:

1. `internal fun computeScaledBounds(element: AccessibilityNodeData, scaleX: Float, scaleY: Float, bitmapWidth: Int, bitmapHeight: Int): RectF?`
   - Computes the scaled rectangle for an element.
   - Returns `null` if the element is fully outside the bitmap.
   - Clamps coordinates to bitmap edges if partially outside.
   - This is pure math — no Android drawing APIs.

2. `internal fun extractLabel(elementId: String): String`
   - Strips `node_` prefix from element ID to get hash.
   - Returns full ID if prefix not present.
   - Pure string logic.

3. `internal fun computeLabelRect(label: String, boxRect: RectF, textSize: Float, padding: Float): RectF`
   - Computes the pill background rectangle position for a label.
   - Pure math based on estimated text width (can use `label.length * textSize * 0.6f` as approximation, or accept textWidth as parameter).

The `annotate` method itself calls these helpers and performs the actual Canvas drawing. The drawing logic is a thin layer that's verified via integration tests.

#### Action 2.5.2: Create `ScreenshotAnnotatorTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotAnnotatorTest.kt` (NEW)

**What**: Create unit tests that test the **extracted pure logic methods** and the overall `annotate` method using MockK for Android graphics classes. Structure:

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
}
```

**Inner class 2: `ComputeScaledBoundsTests`** (pure math, no mocking needed):
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
}
```

**Inner class 3: `AnnotateTests`** (uses MockK for Bitmap/Canvas):
```kotlin
@Nested
@DisplayName("annotate")
inner class AnnotateTests {
    private lateinit var mockBitmap: Bitmap
    private lateinit var mockCopy: Bitmap
    private lateinit var mockCanvas: Canvas

    @BeforeEach
    fun setup() {
        mockBitmap = mockk(relaxed = true)
        mockCopy = mockk(relaxed = true)
        mockCanvas = mockk(relaxed = true)
        every { mockBitmap.width } returns 540
        every { mockBitmap.height } returns 1200
        every { mockBitmap.config } returns Bitmap.Config.ARGB_8888
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
}
```

**Note**: The `AnnotateTests` use `mockkConstructor(Canvas::class)` to intercept Canvas construction with a Bitmap argument. `@AfterEach` ensures `unmockkConstructor` is always called to prevent test pollution. The exact verify patterns may need adjustment during implementation based on the final drawing code.

---

### Task 2.6: Add unit tests for `ScreenCaptureProviderImpl.captureScreenshotBitmap`

**Acceptance Criteria**:
- [ ] New test class/inner class covering `captureScreenshotBitmap` in the existing `ScreenCaptureProviderImplTest.kt` (or a new file if it doesn't exist)
- [ ] Tests cover: success path, failure path (null bitmap), bitmap recycling when resize produces new bitmap, bitmap NOT recycled when resize returns same bitmap
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProviderImplTest"`

#### Action 2.6.1: Add `captureScreenshotBitmap` tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImplTest.kt` (existing or new)

**What**: Add unit tests for the new method. The test approach:
- Mock `McpAccessibilityService.takeScreenshotBitmap()` and `ScreenshotEncoder.resizeBitmapProportional()`
- Verify bitmap recycling behavior

```kotlin
@Nested
@DisplayName("captureScreenshotBitmap")
inner class CaptureScreenshotBitmapTests {
    @Test
    fun `returns resized bitmap on success`() = runTest {
        val originalBitmap = mockk<Bitmap>(relaxed = true)
        val resizedBitmap = mockk<Bitmap>(relaxed = true)
        // Mock service.takeScreenshotBitmap() → originalBitmap
        // Mock screenshotEncoder.resizeBitmapProportional(originalBitmap, ...) → resizedBitmap
        val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)
        assertTrue(result.isSuccess)
        assertEquals(resizedBitmap, result.getOrNull())
        // Original bitmap recycled because a new one was produced
        verify { originalBitmap.recycle() }
    }

    @Test
    fun `does not recycle bitmap when resize returns same instance`() = runTest {
        val bitmap = mockk<Bitmap>(relaxed = true)
        // Mock service.takeScreenshotBitmap() → bitmap
        // Mock screenshotEncoder.resizeBitmapProportional(bitmap, ...) → bitmap (same)
        val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)
        assertTrue(result.isSuccess)
        verify(exactly = 0) { bitmap.recycle() }
    }

    @Test
    fun `returns failure when takeScreenshotBitmap returns null`() = runTest {
        // Mock service.takeScreenshotBitmap() → null
        val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)
        assertTrue(result.isFailure)
    }

    @Test
    fun `returns failure when service not available`() = runTest {
        // Mock validateService() → Invalid
        val result = provider.captureScreenshotBitmap(maxWidth = 700, maxHeight = 700)
        assertTrue(result.isFailure)
    }
}
```

---

### Task 2.7: Update `ScreenIntrospectionIntegrationTest` for annotated screenshot

**Acceptance Criteria**:
- [ ] Integration test for screenshot now mocks `captureScreenshotBitmap`, `screenshotAnnotator.annotate()`, and `screenshotEncoder.bitmapToScreenshotData()` instead of `captureScreenshot`
- [ ] No Android framework mocking needed (no `mockkStatic(Bitmap::class)`)
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

#### Action 2.7.1: Update `get_screen_state with include_screenshot returns text and image`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`
**Lines**: 102-136

**What**: Since `ScreenshotAnnotator` and `ScreenshotEncoder` are now **mocked** (via `MockDependencies`, see Action 2.3.4), the integration test does NOT need to mock Android `Bitmap`/`Canvas`/`Paint` classes. Instead:
- Mock `captureScreenshotBitmap` to return a mock Bitmap
- Mock `screenshotAnnotator.annotate()` to return the same mock Bitmap
- Mock `screenshotEncoder.bitmapToScreenshotData()` to return a known `ScreenshotData`

This is clean, avoids all Android framework mocking, and isolates each component properly.

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
     }
```

**Note**: No `mockkStatic(Bitmap::class)` is needed because we mock the `ScreenshotAnnotator` and `ScreenshotEncoder` interfaces, so no Android drawing code runs in this test.

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
- [ ] New test verifies that when `screenshotAnnotator.annotate()` throws an exception, the tool returns an error and both bitmaps are still recycled
- [ ] Tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.ScreenIntrospectionIntegrationTest"`

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
        }

        // Verify the bitmap was still recycled via the finally block
        verify { mockBitmap.recycle() }
    }
```

---

## User Story 3: Documentation Updates

**Goal**: Update all documentation to reflect the new TSV format and annotated screenshot behavior.

**Acceptance Criteria / Definition of Done**:
- [ ] `docs/MCP_TOOLS.md` updated with new TSV format, flag abbreviations, note lines, and annotated screenshot description
- [ ] `docs/PROJECT.md` updated with new flag format and screenshot annotation mention
- [ ] Lint passes (no code changes, documentation only)

---

### Task 3.1: Update `docs/MCP_TOOLS.md`

**Acceptance Criteria**:
- [ ] Response examples show new flag format
- [ ] Flags Reference table updated with abbreviations
- [ ] Output Format section updated with new note lines
- [ ] Screenshot section describes bounding box annotations

#### Action 3.1.1: Update response examples

**File**: `docs/MCP_TOOLS.md`
**Lines**: ~207-242 (response examples)

**What**: Update the TSV text in both response examples to use new flag format (`on,clk,ena` instead of `vcn`) and include the two new note lines.

#### Action 3.1.2: Update Output Format section

**File**: `docs/MCP_TOOLS.md`
**Lines**: ~244-253 (output format description)

**What**: Update the numbered list to include the two new note lines (flags legend at position 3, offscreen hint at position 4) and shift subsequent line numbers.

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
- [ ] Screen Introspection Tools table updated to mention annotated screenshot
- [ ] Note about flag format updated

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

---

## User Story 4: Run All Tests & Final Verification

**Goal**: Ensure everything works end-to-end.

**Acceptance Criteria / Definition of Done**:
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest`
- [ ] All integration tests pass (included in the above)
- [ ] Lint passes: `make lint`
- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] Final review: re-read every changed file against this plan to verify nothing was missed or diverged

---

### Task 4.1: Run full test suite

#### Action 4.1.1: Run unit + integration tests

```bash
./gradlew :app:testDebugUnitTest
```

**Expected**: All tests pass, including updated CompactTreeFormatterTest, ScreenIntrospectionIntegrationTest, and new ScreenshotAnnotatorTest.

#### Action 4.1.2: Run lint

```bash
make lint
```

**Expected**: No new warnings or errors.

#### Action 4.1.3: Run build

```bash
./gradlew assembleDebug
```

**Expected**: Build succeeds without errors or warnings.

---

### Task 4.2: Update E2E tests for new TSV format and annotated screenshot

**Acceptance Criteria**:
- [ ] Existing E2E tests that call `get_screen_state` are updated to assert new TSV note lines and flag format
- [ ] If screenshot E2E tests exist, they should verify the screenshot is non-empty (visual annotation verification is not feasible in automated E2E)
- [ ] E2E tests pass: `make test-e2e` (if E2E environment is available)

#### Action 4.2.1: Update E2E test assertions for TSV format

**File**: E2E test files in `e2e-tests/src/test/kotlin/` that call `get_screen_state`

**What**: Update assertions to check for:
- New note lines (`note:flags:` and `note:offscreen items`)
- New flag format (comma-separated abbreviations like `on,clk,ena`)
- Negative assertions for old format (`vcn`, `vclfsen` should NOT appear)
- If the E2E test checks screenshot content, verify `ImageContent` is returned with `image/jpeg` mimeType and non-empty data

**Note**: If no E2E test currently tests `get_screen_state`, document this as a gap and skip this action. The Unit + Integration tests provide sufficient coverage for the logic changes.

---

### Task 4.3: Final verification against plan

#### Action 4.3.1: Re-read every changed file and verify against this plan

**What**: Systematically go through each file changed during implementation and verify:

1. **`CompactTreeFormatter.kt`**:
   - [ ] `buildFlags` uses comma-separated abbreviated words
   - [ ] `on`/`off` is always the first flag
   - [ ] New note line constants exist: `NOTE_LINE_FLAGS_LEGEND`, `NOTE_LINE_OFFSCREEN_HINT`
   - [ ] Flag abbreviation constants exist: `FLAG_ONSCREEN`, `FLAG_OFFSCREEN`, `FLAG_CLICKABLE`, `FLAG_LONG_CLICKABLE`, `FLAG_FOCUSABLE`, `FLAG_SCROLLABLE`, `FLAG_EDITABLE`, `FLAG_ENABLED`, `FLAG_SEPARATOR`
   - [ ] `format()` outputs 4 note lines (structural, custom elements, flags legend, offscreen hint)
   - [ ] Old `FLAG_COUNT` constant removed

2. **`CompactTreeFormatterTest.kt`**:
   - [ ] All `buildFlags` tests use new expected values
   - [ ] All `format` tests use updated line indices (data rows at line 7+)
   - [ ] New tests for flags legend note line and offscreen hint note line
   - [ ] New test for `off` flag on non-visible nodes
   - [ ] New test verifying `on` is always first flag

3. **`ScreenCaptureProvider.kt`**:
   - [ ] New `captureScreenshotBitmap(maxWidth, maxHeight)` method in interface
   - [ ] Returns `Result<Bitmap>`
   - [ ] KDoc documents caller must recycle

4. **`ScreenCaptureProviderImpl.kt`**:
   - [ ] Implements `captureScreenshotBitmap`
   - [ ] Captures bitmap, resizes, returns without encoding
   - [ ] Recycles original bitmap if resize produced a new one

5. **`ScreenshotAnnotator.kt`** (NEW):
   - [ ] `@Inject constructor()`
   - [ ] `annotate(bitmap, elements, screenWidth, screenHeight)` method
   - [ ] Red dashed 2px bounding boxes
   - [ ] Semi-transparent red pill labels with white bold text
   - [ ] Element ID hash (without `node_` prefix)
   - [ ] Coordinate mapping from screen to bitmap space
   - [ ] Handles empty list, out-of-bounds elements, partial clipping
   - [ ] Returns new bitmap (does not mutate input)
   - [ ] Paint objects created ONCE before the element loop (not per-element)
   - [ ] Testable helper methods extracted: `computeScaledBounds`, `extractLabel`, `computeLabelRect`
   - [ ] Password/sensitive fields ARE annotated (intentional; Android already masks display text)

6. **`ScreenshotAnnotatorTest.kt`** (NEW):
   - [ ] Tests for empty list, single element, multiple elements (AnnotateTests inner class)
   - [ ] Tests for coordinate mapping (ComputeScaledBoundsTests inner class)
   - [ ] Tests for ID hash extraction (ExtractLabelTests inner class)
   - [ ] Tests for out-of-bounds elements (fully outside → null, partially outside → clamped)
   - [ ] `unmockkConstructor(Canvas::class)` in `@AfterEach` to prevent test pollution

7. **`ScreenIntrospectionTools.kt`**:
   - [ ] `GetScreenStateHandler` constructor takes `ScreenshotAnnotator` and `ScreenshotEncoder`
   - [ ] `collectOnScreenElements` helper method uses accumulator pattern (single `MutableList` parameter, no `addAll`)
   - [ ] Screenshot path: `captureScreenshotBitmap` → annotate → encode → return
   - [ ] Proper bitmap recycling (resized + annotated in finally block)

8. **`McpServerService.kt`**:
   - [ ] `@Inject lateinit var screenshotAnnotator: ScreenshotAnnotator`
   - [ ] `@Inject lateinit var screenshotEncoder: ScreenshotEncoder`
   - [ ] Both passed to `registerScreenIntrospectionTools`

9. **`McpIntegrationTestHelper.kt`**:
   - [ ] `MockDependencies` has `screenshotAnnotator` and `screenshotEncoder` fields (mocked, NOT real instances)
   - [ ] `createMockDependencies()` creates both as `mockk(relaxed = true)`
   - [ ] `registerAllTools` passes `deps.screenshotAnnotator` and `deps.screenshotEncoder` to `registerScreenIntrospectionTools`

10. **`ScreenIntrospectionIntegrationTest.kt`**:
    - [ ] Assertions for new note lines (`note:flags:`, `note:offscreen items`)
    - [ ] Assertions for new flag format (`on,clk,ena`)
    - [ ] Negative assertions for old flag format (no `vcn`, no `vclfsen`)
    - [ ] Screenshot test mocks `captureScreenshotBitmap`, `screenshotAnnotator.annotate()`, and `screenshotEncoder.bitmapToScreenshotData()` — NO `mockkStatic(Bitmap::class)` needed
    - [ ] No-screenshot test verifies `captureScreenshotBitmap` not called
    - [ ] New test for annotation failure exception propagation and bitmap cleanup

11. **`ScreenCaptureProviderImplTest.kt`** (existing or new):
    - [ ] Tests for `captureScreenshotBitmap`: success, failure (null bitmap), bitmap recycling when resize produces new bitmap, bitmap NOT recycled when resize returns same instance

12. **`docs/MCP_TOOLS.md`**:
    - [ ] Response examples use new flag format
    - [ ] Output Format lists 4 note lines
    - [ ] Flags Reference table uses abbreviations
    - [ ] Screenshot section describes annotations

13. **`docs/PROJECT.md`**:
    - [ ] Screen Introspection Tools table updated
    - [ ] Note updated with flag format and annotation info

14. **E2E tests** (if applicable):
    - [ ] Assertions for new TSV note lines and flag format
    - [ ] Negative assertions for old format
