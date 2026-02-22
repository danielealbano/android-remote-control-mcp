package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CompactTreeFormatter")
class CompactTreeFormatterTest {
    private val formatter = CompactTreeFormatter()

    private val defaultScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    @Suppress("LongParameterList")
    private fun makeNode(
        id: String = "node_test",
        className: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = false,
        visible: Boolean = false,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = id,
        className = className,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        focusable = focusable,
        scrollable = scrollable,
        editable = editable,
        enabled = enabled,
        visible = visible,
        children = children,
    )

    // ─────────────────────────────────────────────────────────────────────
    // format
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("format")
    inner class FormatTests {
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

        @Test
        @DisplayName("produces flags legend note as third line")
        fun producesFlagsLegendNoteAsThirdLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND, lines[2])
        }

        @Test
        @DisplayName("produces offscreen hint note as fourth line")
        fun producesOffscreenHintNoteAsFourthLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT, lines[3])
        }

        @Test
        @DisplayName("produces correct metadata lines")
        fun producesCorrectMetadataLines() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example.app", ".MainActivity", defaultScreenInfo)
            val lines = output.lines()
            assertEquals("app:com.example.app activity:.MainActivity", lines[4])
            assertEquals("screen:1080x2400 density:420 orientation:portrait", lines[5])
        }

        @Test
        @DisplayName("produces correct header line")
        fun producesCorrectHeaderLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals("element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[6])
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
            // Data rows start at line 7 (0-indexed)
            assertEquals("node_btn\tButton\tOK\t-\tbtn_ok\t100,200,300,260\ton,clk,ena", lines[7])
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
            // Only notes + metadata + header = 7 lines, no data rows
            assertEquals(7, lines.size)
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
            // Parent is filtered, child appears
            assertEquals(8, lines.size)
            assertTrue(lines[7].startsWith("node_child\t"))
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
            assertEquals(8, lines.size)
            assertTrue(lines[7].contains("node_hidden"))
            // Check the flags column (last tab-separated field) starts with "off"
            val flags = lines[7].split("\t").last()
            assertTrue(flags.startsWith("off"), "Flags should start with 'off' but was: $flags")
            assertFalse(flags.startsWith("on"), "Flags should not start with 'on' but was: $flags")
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
            // Only notes + metadata + header = 7 lines
            assertEquals(7, lines.size)
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
            assertTrue(lines[7].contains("10,20,300,400"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // shouldKeepNode
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldKeepNode")
    inner class ShouldKeepNodeTests {
        @Test
        @DisplayName("returns true for node with text")
        fun returnsTrueForNodeWithText() {
            assertTrue(formatter.shouldKeepNode(makeNode(text = "hello")))
        }

        @Test
        @DisplayName("returns true for node with contentDescription")
        fun returnsTrueForNodeWithContentDescription() {
            assertTrue(formatter.shouldKeepNode(makeNode(contentDescription = "desc")))
        }

        @Test
        @DisplayName("returns true for node with resourceId")
        fun returnsTrueForNodeWithResourceId() {
            assertTrue(formatter.shouldKeepNode(makeNode(resourceId = "com.example:id/btn")))
        }

        @Test
        @DisplayName("returns true for clickable node")
        fun returnsTrueForClickableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(clickable = true)))
        }

        @Test
        @DisplayName("returns true for longClickable node")
        fun returnsTrueForLongClickableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(longClickable = true)))
        }

        @Test
        @DisplayName("returns true for scrollable node")
        fun returnsTrueForScrollableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(scrollable = true)))
        }

        @Test
        @DisplayName("returns true for editable node")
        fun returnsTrueForEditableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(editable = true)))
        }

        @Test
        @DisplayName("returns false for empty non-interactive node")
        fun returnsFalseForEmptyNonInteractiveNode() {
            assertFalse(formatter.shouldKeepNode(makeNode()))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // simplifyClassName
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("simplifyClassName")
    inner class SimplifyClassNameTests {
        @Test
        @DisplayName("strips package prefix")
        fun stripsPackagePrefix() {
            assertEquals("Button", formatter.simplifyClassName("android.widget.Button"))
        }

        @Test
        @DisplayName("handles no package")
        fun handlesNoPackage() {
            assertEquals("Button", formatter.simplifyClassName("Button"))
        }

        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.simplifyClassName(null))
        }

        @Test
        @DisplayName("returns dash for empty")
        fun returnsDashForEmpty() {
            assertEquals("-", formatter.simplifyClassName(""))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // sanitizeText
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeText")
    inner class SanitizeTextTests {
        @Test
        @DisplayName("replaces tabs with spaces")
        fun replacesTabsWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\tworld"))
        }

        @Test
        @DisplayName("replaces newlines with spaces")
        fun replacesNewlinesWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\nworld"))
        }

        @Test
        @DisplayName("replaces carriage returns with spaces")
        fun replacesCarriageReturnsWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\rworld"))
        }

        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.sanitizeText(null))
        }

        @Test
        @DisplayName("returns dash for empty string")
        fun returnsDashForEmptyString() {
            assertEquals("-", formatter.sanitizeText(""))
        }

        @Test
        @DisplayName("returns dash for whitespace-only after sanitization")
        fun returnsDashForWhitespaceOnlyAfterSanitization() {
            assertEquals("-", formatter.sanitizeText("\t\n"))
        }

        @Test
        @DisplayName("truncates long text to 100 chars with suffix")
        fun truncatesLongTextTo100CharsWithSuffix() {
            val longText = "a".repeat(150)
            val result = formatter.sanitizeText(longText)
            assertEquals("a".repeat(100) + "...truncated", result)
        }

        @Test
        @DisplayName("does not truncate text at exactly 100 chars")
        fun doesNotTruncateTextAtExactly100Chars() {
            val exactText = "a".repeat(100)
            assertEquals(exactText, formatter.sanitizeText(exactText))
        }

        @Test
        @DisplayName("truncates text at 101 chars")
        fun truncatesTextAt101Chars() {
            val text = "a".repeat(101)
            val result = formatter.sanitizeText(text)
            assertEquals("a".repeat(100) + "...truncated", result)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // sanitizeResourceId
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeResourceId")
    inner class SanitizeResourceIdTests {
        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.sanitizeResourceId(null))
        }

        @Test
        @DisplayName("returns dash for empty")
        fun returnsDashForEmpty() {
            assertEquals("-", formatter.sanitizeResourceId(""))
        }

        @Test
        @DisplayName("does not truncate long resourceId")
        fun doesNotTruncateLongResourceId() {
            val longId = "com.example.very.long.package:id/" + "a".repeat(200)
            assertEquals(longId, formatter.sanitizeResourceId(longId))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildFlags
    // ─────────────────────────────────────────────────────────────────────

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
            assertEquals("on,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
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
            assertEquals("on,clk,ena", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("returns off when no flags set except visibility")
        fun returnsOffWhenNoFlagsSet() {
            assertEquals("off", formatter.buildFlags(makeNode()))
        }

        @Test
        @DisplayName("includes off flag for non-visible nodes")
        fun includesOffFlagForNonVisibleNodes() {
            val node =
                makeNode(
                    visible = false,
                    clickable = true,
                    enabled = true,
                )
            assertEquals("off,clk,ena", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("on is always first flag")
        fun onIsAlwaysFirstFlag() {
            val node =
                makeNode(
                    visible = true,
                    enabled = true,
                )
            val flags = formatter.buildFlags(node)
            assertTrue(flags.startsWith("on,") || flags == "on")
        }

        @Test
        @DisplayName("returns only on when visible and no other flags set")
        fun returnsOnlyOnWhenVisibleAndNoOtherFlags() {
            val node = makeNode(visible = true)
            assertEquals("on", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("includes off flag with all other flags set")
        fun includesOffFlagWithAllOtherFlagsSet() {
            val node =
                makeNode(
                    visible = false,
                    clickable = true,
                    longClickable = true,
                    focusable = true,
                    scrollable = true,
                    editable = true,
                    enabled = true,
                )
            assertEquals("off,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // formatMultiWindow
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatMultiWindow")
    inner class FormatMultiWindow {
        @Test
        @DisplayName("single window produces correct output")
        fun singleWindowProducesCorrectOutput() {
            val tree =
                makeNode(
                    id = "node_btn",
                    className = "android.widget.Button",
                    text = "OK",
                    bounds = BoundsData(0, 0, 100, 48),
                    clickable = true,
                    visible = true,
                    enabled = true,
                )
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
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
            assertTrue(
                output.contains(
                    "--- window:0 type:APPLICATION pkg:com.example.app title:MainActivity " +
                        "activity:com.example.app.MainActivity layer:0 focused:true ---",
                ),
            )
            assertTrue(output.contains("node_btn"))
            assertFalse(output.contains(CompactTreeFormatter.DEGRADATION_NOTE))
        }

        @Test
        @DisplayName("two windows produce both sections")
        fun twoWindowsProduceBothSections() {
            val appTree = makeNode(id = "node_app", text = "App", clickable = true, visible = true)
            val dialogTree = makeNode(id = "node_allow", text = "Allow", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = "com.example",
                                title = "Main",
                                layer = 0,
                                focused = false,
                                tree = appTree,
                            ),
                            WindowData(
                                windowId = 1,
                                windowType = "SYSTEM",
                                packageName = "com.android.permissioncontroller",
                                title = "Permission",
                                layer = 1,
                                focused = true,
                                tree = dialogTree,
                            ),
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
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "SYSTEM",
                                packageName = "com.android.systemui",
                                title = "StatusBar",
                                activityName = null,
                                layer = 0,
                                focused = false,
                                tree = tree,
                            ),
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
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(windowId = 0, windowType = "APPLICATION", tree = tree, focused = true),
                        ),
                    degraded = true,
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.startsWith(CompactTreeFormatter.DEGRADATION_NOTE))
        }

        @Test
        @DisplayName("null packageName rendered as unknown")
        fun nullPackageNameRenderedAsUnknown() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = null,
                                tree = tree,
                                focused = true,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("pkg:unknown"))
        }

        @Test
        @DisplayName("null title rendered as unknown")
        fun nullTitleRenderedAsUnknown() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                title = null,
                                tree = tree,
                                focused = true,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("title:unknown"))
        }

        @Test
        @DisplayName("buildWindowHeader produces correct format with all fields")
        fun buildWindowHeaderAllFields() {
            val wd =
                WindowData(
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
                "--- window:2 type:INPUT_METHOD pkg:com.google.android.inputmethod.latin " +
                    "title:Gboard layer:5 focused:false ---",
                header,
            )
        }

        @Test
        @DisplayName("buildWindowHeader includes activity when present")
        fun buildWindowHeaderWithActivity() {
            val wd =
                WindowData(
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
    }
}
