@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verifyOrder
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@DisplayName("NodeActionTools")
class NodeActionToolsTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockElementFinder = mockk<ElementFinder>()
    private val mockActionExecutor = mockk<ActionExecutor>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()
    private val targetResolver =
        NodeTargetResolver(
            mockElementFinder,
            PrivacyToolTestDoubles.passthroughGate(),
            PrivacyToolTestDoubles.identitySubstitutor(),
        )

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_abc",
                        className = "android.widget.Button",
                        text = "7",
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
                        visible = true,
                    ),
                ),
        )

    private val sampleWindows =
        listOf(
            WindowData(
                windowId = 0,
                windowType = "APPLICATION",
                packageName = "com.example",
                title = "Test",
                activityName = ".Main",
                layer = 0,
                focused = true,
                tree = sampleTree,
            ),
        )

    private val sampleBounds = BoundsData(50, 800, 250, 1000)

    private val sampleElementInfo =
        ElementInfo(
            id = "node_abc",
            text = "7",
            className = "android.widget.Button",
            bounds = sampleBounds,
            clickable = true,
            enabled = true,
            visible = true,
        )

    private fun extractTextContent(result: CallToolResult): String {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        return textContent.text
    }

    @BeforeEach
    fun setUp() {
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockAccessibilityServiceProvider.clearFrameworkNodeCache() } returns Unit
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockWindowInfo.recycle() } returns Unit
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("getFreshWindows serializes concurrent reads (no overlapping tree traversal)")
    fun getFreshWindowsSerializesConcurrentReads() {
        // Regression guard for the framework-cache-clear concurrency fix: getFreshWindows clears the
        // shared framework node cache then traverses it, so parallel MCP requests MUST NOT overlap
        // inside that critical section (an overlap could wipe the cache mid-traversal → torn tree).
        // getAccessibilityWindows (invoked inside the section) records the peak number of threads
        // simultaneously inside; with the synchronized(AccessibilityTreeLock) wrapper it must stay 1.
        // Remove the wrapper and 4 barrier-released threads would overlap, driving maxObserved > 1.
        val threadCount = 4
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val provider = mockk<AccessibilityServiceProvider>(relaxed = true)
        every { provider.isReady() } returns true
        every { provider.getAccessibilityWindows() } answers {
            val now = active.incrementAndGet()
            maxObserved.updateAndGet { m -> maxOf(m, now) }
            Thread.sleep(40)
            active.decrementAndGet()
            // Empty -> getFreshWindows falls to the getRootNode fallback (null -> throws), but the
            // overlap has already been sampled above; the throw is caught per-thread below.
            emptyList()
        }
        every { provider.getRootNode() } returns null

        val barrier = CyclicBarrier(threadCount)
        val workers =
            (1..threadCount).map {
                thread {
                    barrier.await()
                    runCatching { getFreshWindows(mockTreeParser, provider, mockNodeCache) }
                }
            }
        workers.forEach { it.join() }

        assertEquals(
            1,
            maxObserved.get(),
            "concurrent getFreshWindows reads overlapped the framework-cache critical section",
        )
    }

    @Nested
    @DisplayName("FindNodesTool")
    inner class FindNodesToolTests {
        private val tool =
            FindNodesTool(
                mockTreeParser,
                mockElementFinder,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
            )

        @Test
        fun `returns matching nodes`() =
            runTest {
                // Arrange
                every {
                    mockElementFinder.findElements(sampleWindows, FindBy.TEXT, "7", false)
                } returns listOf(sampleElementInfo)
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "7")
                    }

                // Act
                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject

                // Assert
                val elements = parsed["nodes"]!!.jsonArray
                assertEquals(1, elements.size)
                assertEquals("node_abc", elements[0].jsonObject["node_id"]?.jsonPrimitive?.content)
            }

        @Test
        fun `clears the framework node cache before reading windows on the find path`() =
            runTest {
                // find_nodes reads the tree directly via getFreshWindows with NO prior
                // get_screen_state, so this is the path where a WebView change since the last read
                // would otherwise be served stale. The clear MUST precede the window read.
                every {
                    mockElementFinder.findElements(sampleWindows, FindBy.TEXT, "7", false)
                } returns listOf(sampleElementInfo)
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "7")
                    }

                tool.execute(params)

                verifyOrder {
                    mockAccessibilityServiceProvider.clearFrameworkNodeCache()
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                }
            }

        @Test
        fun `returns empty array when no matches found`() =
            runTest {
                // Arrange
                every {
                    mockElementFinder.findElements(sampleWindows, FindBy.TEXT, "99", false)
                } returns emptyList()
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "99")
                    }

                // Act
                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject

                // Assert
                val elements = parsed["nodes"]!!.jsonArray
                assertTrue(elements.isEmpty())
            }

        @Test
        fun `throws error for invalid by value`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "invalid_criteria")
                        put("value", "test")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Invalid 'by' value"))
            }

        @Test
        fun `throws error for empty value`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error for missing by parameter`() =
            runTest {
                val params = buildJsonObject { put("value", "test") }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("ClickNodeTool")
    inner class ClickNodeToolTests {
        private val tool =
            ClickNodeTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                targetResolver,
            )

        @Test
        fun `clicks node successfully`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleWindows) } returns Result.success(Unit)
                val params = buildJsonObject { put("node_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Click performed"))
            }

        @Test
        fun `throws error when node not found`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_xyz", sampleWindows) } returns
                    Result.failure(NoSuchElementException("Node 'node_xyz' not found"))
                val params = buildJsonObject { put("node_id", "node_xyz") }

                assertThrows<McpToolException.NodeNotFound> { tool.execute(params) }
            }

        @Test
        fun `throws error when node not clickable`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleWindows) } returns
                    Result.failure(IllegalStateException("Node 'node_abc' is not clickable"))
                val params = buildJsonObject { put("node_id", "node_abc") }

                assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
            }

        @Test
        fun `throws error for missing node_id`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("LongClickNodeTool")
    inner class LongClickNodeToolTests {
        private val tool =
            LongClickNodeTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                targetResolver,
            )

        @Test
        fun `long-clicks node successfully`() =
            runTest {
                coEvery { mockActionExecutor.longClickNode("node_abc", sampleWindows) } returns Result.success(Unit)
                val params = buildJsonObject { put("node_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Long-click"))
            }
    }

    @Nested
    @DisplayName("TapNodeTool")
    inner class TapNodeToolTests {
        private val tool =
            TapNodeTool(
                mockTreeParser,
                mockElementFinder,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                targetResolver,
            )

        @Test
        fun `taps node successfully with default inset`() =
            runTest {
                // Arrange
                val node = sampleTree.children[0] // bounds = BoundsData(50, 800, 250, 1000)
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_abc"))
                } returns node
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val params = buildJsonObject { put("node_id", "node_abc") }

                // Act
                val result = tool.execute(params)
                val text = extractTextContent(result)

                // Assert
                assertTrue(text.contains("Tap executed"))
                assertTrue(text.contains("node_abc"))
            }

        @Test
        fun `taps small node at top-left corner`() =
            runTest {
                // Arrange — small element: width=3, height=3 (both < 5)
                val smallNode =
                    AccessibilityNodeData(
                        id = "node_small",
                        className = "android.widget.ImageView",
                        bounds = BoundsData(100, 200, 103, 203),
                        visible = true,
                    )
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_small"))
                } returns smallNode
                coEvery { mockActionExecutor.tap(100f, 200f) } returns Result.success(Unit)
                val params = buildJsonObject { put("node_id", "node_small") }

                // Act
                val result = tool.execute(params)
                val text = extractTextContent(result)

                // Assert
                assertTrue(text.contains("Tap executed at (100, 200)"))
                assertTrue(text.contains("node_small"))
            }

        @Test
        fun `uses custom inset_percentage`() =
            runTest {
                // Arrange — bounds (50,800,250,1000), width=200, height=200
                // inset_percentage=20 → insetFraction=0.2 → inset=40px each side
                // insetLeft=90, insetRight=210, insetTop=840, insetBottom=960
                val node = sampleTree.children[0]
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_abc"))
                } returns node
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("inset_percentage", 20.0)
                    }

                // Act
                val result = tool.execute(params)
                val text = extractTextContent(result)

                // Assert
                assertTrue(text.contains("Tap executed"))
                assertTrue(text.contains("node_abc"))
            }

        @Test
        fun `throws error for missing node_id`() =
            runTest {
                val params = buildJsonObject {}

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Missing required parameter 'node_id'"))
            }

        @Test
        fun `throws error for empty node_id`() =
            runTest {
                val params = buildJsonObject { put("node_id", "") }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when node not found`() =
            runTest {
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_xyz"))
                } returns null
                val params = buildJsonObject { put("node_id", "node_xyz") }

                val exception = assertThrows<McpToolException.NodeNotFound> { tool.execute(params) }
                assertTrue(exception.message!!.contains("node_xyz"))
            }

        @Test
        fun `throws error for inset_percentage below minimum`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("inset_percentage", -1.0)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("inset_percentage"))
            }

        @Test
        fun `throws error for inset_percentage above maximum`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("inset_percentage", 50.0)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("inset_percentage"))
            }

        @Test
        fun `returns error when tap action fails`() =
            runTest {
                // Arrange
                val node = sampleTree.children[0]
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_abc"))
                } returns node
                coEvery { mockActionExecutor.tap(any(), any()) } returns
                    Result.failure(RuntimeException("Gesture dispatch failed"))
                val params = buildJsonObject { put("node_id", "node_abc") }

                // Act & Assert
                assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
            }

        @Test
        fun `randomFloatInRange returns min when min equals max`() {
            // min == max → returns min
            assertEquals(10f, tool.randomFloatInRange(10f, 10f))
            // min > max → returns min
            assertEquals(15f, tool.randomFloatInRange(15f, 10f))
        }

        @Test
        fun `throws error for null arguments`() =
            runTest {
                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(null) }
                assertTrue(exception.message!!.contains("Missing required parameter 'node_id'"))
            }

        @Test
        fun `accepts inset_percentage at minimum boundary 0`() =
            runTest {
                // Arrange — inset_percentage=0.0 means full bounds (no inset)
                val node = sampleTree.children[0]
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_abc"))
                } returns node
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("inset_percentage", 0.0)
                    }

                // Act
                val result = tool.execute(params)

                // Assert — no exception, success
                assertTrue(extractTextContent(result).contains("Tap executed"))
            }

        @Test
        fun `accepts inset_percentage at maximum boundary 45`() =
            runTest {
                // Arrange — inset_percentage=45.0, very narrow center strip
                val node = sampleTree.children[0]
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_abc"))
                } returns node
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("inset_percentage", 45.0)
                    }

                // Act
                val result = tool.execute(params)

                // Assert — no exception, success
                assertTrue(extractTextContent(result).contains("Tap executed"))
            }

        @Test
        fun `high inset on small-but-not-tiny node collapses bounds gracefully`() =
            runTest {
                // Arrange — element width=10, height=10 (above threshold of 5)
                // inset_percentage=45.0 → insetFraction=0.45, inset=4.5px each side
                // insetLeft=104.5, insetRight=105.5, insetTop=204.5, insetBottom=205.5
                // But actually: width=10, 45% of 10 = 4.5 from each side → left+4.5=104.5, right-4.5=105.5
                // The bounds don't fully collapse here, but let's verify it works
                val smallishNode =
                    AccessibilityNodeData(
                        id = "node_smallish",
                        className = "android.widget.ImageView",
                        bounds = BoundsData(100, 200, 110, 210),
                        visible = true,
                    )
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_smallish"))
                } returns smallishNode
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("node_id", "node_smallish")
                        put("inset_percentage", 45.0)
                    }

                // Act
                val result = tool.execute(params)

                // Assert — no exception, graceful handling
                assertTrue(extractTextContent(result).contains("Tap executed"))
                assertTrue(extractTextContent(result).contains("node_smallish"))
            }
    }

    @Nested
    @DisplayName("ScrollToNodeTool")
    inner class ScrollToNodeToolTests {
        private val tool =
            ScrollToNodeTool(
                mockTreeParser,
                mockElementFinder,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                targetResolver,
            )

        @Test
        fun `returns immediately when node already visible`() =
            runTest {
                val visibleNode = sampleTree.children[0] // visible = true
                every { mockElementFinder.findNodeById(sampleWindows, "node_abc") } returns visibleNode
                val params =
                    buildJsonObject {
                        put("node_id", "node_abc")
                        put("direction", "down")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("already visible"))
            }

        @Test
        fun `throws error when node not found`() =
            runTest {
                every { mockElementFinder.findNodeById(sampleWindows, "node_xyz") } returns null
                val params =
                    buildJsonObject {
                        put("node_id", "node_xyz")
                        put("direction", "down")
                    }

                assertThrows<McpToolException.NodeNotFound> { tool.execute(params) }
            }

        @Suppress("LongMethod")
        @Test
        fun `scrolls to node in non-primary window`() =
            runTest {
                val invisibleNode =
                    AccessibilityNodeData(
                        id = "node_dialog_btn",
                        className = "android.widget.Button",
                        text = "Allow",
                        bounds = BoundsData(100, 3000, 300, 3060),
                        clickable = true,
                        visible = false,
                    )
                val visibleNode = invisibleNode.copy(visible = true)

                val secondWindowTreeBefore =
                    AccessibilityNodeData(
                        id = "node_dialog_root",
                        className = "android.widget.FrameLayout",
                        bounds = BoundsData(0, 0, 1080, 2400),
                        scrollable = true,
                        visible = true,
                        children = listOf(invisibleNode),
                    )
                val secondWindowTreeAfter =
                    secondWindowTreeBefore.copy(children = listOf(visibleNode))

                val secondMockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                val secondMockWindow = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { secondMockWindow.id } returns 5
                every { secondMockWindow.root } returns secondMockRootNode
                every { secondMockWindow.type } returns AccessibilityWindowInfo.TYPE_SYSTEM
                every { secondMockWindow.title } returns "Dialog"
                every { secondMockWindow.layer } returns 1
                every { secondMockWindow.isFocused } returns false
                every { secondMockRootNode.packageName } returns "android"
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns listOf(mockWindowInfo, secondMockWindow)

                // First call returns invisible node, after scroll returns visible
                var callCount = 0
                every { mockTreeParser.parseTree(secondMockRootNode, "root_w5", any()) } answers {
                    callCount++
                    if (callCount <= 1) secondWindowTreeBefore else secondWindowTreeAfter
                }

                // The target is looked up by id once, in the first read; after the scroll it is found again by its
                // attributes inside its container
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_dialog_btn"))
                } returns invisibleNode
                // The containing window is found with the single-tree overload per window
                every {
                    mockElementFinder.findNodeById(sampleTree, "node_dialog_btn")
                } returns null
                every {
                    mockElementFinder.findNodeById(secondWindowTreeBefore, "node_dialog_btn")
                } returns invisibleNode
                coEvery {
                    mockActionExecutor.scrollNode(any(), any(), any())
                } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("node_id", "node_dialog_btn")
                        put("direction", "down")
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertEquals("Scrolled to node 'node_dialog_btn' (1 scroll(s))", text)
                coVerify { mockActionExecutor.scrollNode("node_dialog_root", ScrollDirection.DOWN, any()) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Selectors, visible_only, scroll_node and scroll_to_node with moving nodes (real ElementFinder)
    // ─────────────────────────────────────────────────────────────────────

    private val realFinder = ElementFinder()
    private val realResolver =
        NodeTargetResolver(
            realFinder,
            PrivacyToolTestDoubles.passthroughGate(),
            PrivacyToolTestDoubles.identitySubstitutor(),
        )

    private fun leaf(
        id: String,
        text: String?,
        visible: Boolean,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
    ) = AccessibilityNodeData(
        id = id,
        className = "android.widget.TextView",
        text = text,
        bounds = bounds,
        clickable = true,
        enabled = true,
        visible = visible,
    )

    private fun screenWith(vararg children: AccessibilityNodeData) =
        AccessibilityNodeData(
            id = "node_list",
            className = "android.widget.ScrollView",
            bounds = BoundsData(0, 0, 1080, 2400),
            scrollable = true,
            visible = true,
            children = children.toList(),
        )

    private fun windowsOf(tree: AccessibilityNodeData) = sampleWindows.map { it.copy(tree = tree) }

    /** The tree parser returns [trees] in order, one per fresh read (the last one repeats). */
    private fun treesInOrder(vararg trees: AccessibilityNodeData) {
        var call = 0
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } answers {
            trees[minOf(call++, trees.lastIndex)]
        }
    }

    private fun selectorParams(
        value: String,
        exactMatch: Boolean = true,
    ) = buildJsonObject {
        put("by", "text")
        put("value", value)
        put("exact_match", exactMatch)
    }

    @Nested
    @DisplayName("node tools with selectors")
    inner class SelectorTests {
        private val tree =
            screenWith(
                leaf("node_ok_on", "OK", visible = true),
                leaf("node_ok_off", "OK", visible = false),
                leaf("node_save_1", "Save", visible = true),
                leaf("node_save_2", "Save", visible = true),
            )

        private val clickTool =
            ClickNodeTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                realResolver,
            )

        @Test
        fun `click_node by selector clicks the single on-screen match`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.clickNode("node_ok_on", windowsOf(tree)) } returns Result.success(Unit)

                val text = extractTextContent(clickTool.execute(selectorParams("OK")))

                assertTrue(text.contains("node_ok_on"), text)
            }

        @Test
        fun `click_node by selector with two on-screen matches returns an untrusted error and clicks nothing`() =
            runTest {
                treesInOrder(tree)

                val result = clickTool.execute(selectorParams("Save"))

                val text = extractTextContent(result)
                assertEquals(true, result.isError)
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                assertTrue(text.contains("node_save_1") && text.contains("node_save_2"), text)
                coVerify(exactly = 0) { mockActionExecutor.clickNode(any(), any()) }
            }

        @Test
        fun `long_click_node, tap_node and scroll_node with two on-screen matches return an untrusted error`() =
            runTest {
                treesInOrder(tree)
                val longClick =
                    LongClickNodeTool(
                        mockTreeParser,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        realResolver,
                    )
                val tap =
                    TapNodeTool(
                        mockTreeParser,
                        realFinder,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        realResolver,
                    )
                val scroll =
                    ScrollNodeTool(
                        mockTreeParser,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        realResolver,
                    )
                val scrollParams =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "Save")
                        put("exact_match", true)
                        put("direction", "down")
                    }

                val results =
                    listOf(
                        longClick.execute(selectorParams("Save")),
                        tap.execute(selectorParams("Save")),
                        scroll.execute(scrollParams),
                    )
                for (result in results) {
                    val text = extractTextContent(result)
                    assertEquals(true, result.isError, text)
                    assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                    assertTrue(text.contains("node_save_1") && text.contains("node_save_2"), text)
                }
                coVerify(exactly = 0) { mockActionExecutor.longClickNode(any(), any()) }
                coVerify(exactly = 0) { mockActionExecutor.tap(any(), any()) }
                coVerify(exactly = 0) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `click_node by selector without an on-screen match is node not found`() =
            runTest {
                treesInOrder(screenWith(leaf("node_ok_off", "OK", visible = false)))

                assertThrows<McpToolException.NodeNotFound> { clickTool.execute(selectorParams("OK")) }
            }

        @Test
        fun `long_click_node by selector long-clicks the single on-screen match`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.longClickNode("node_ok_on", windowsOf(tree)) } returns Result.success(Unit)
                val tool =
                    LongClickNodeTool(
                        mockTreeParser,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        realResolver,
                    )

                assertTrue(extractTextContent(tool.execute(selectorParams("OK"))).contains("Long-click"))
            }

        @Test
        fun `tap_node by selector taps inside the matched node`() =
            runTest {
                val target = leaf("node_ok_on", "OK", visible = true, bounds = BoundsData(100, 200, 300, 400))
                treesInOrder(screenWith(target))
                coEvery { mockActionExecutor.tap(any(), any()) } returns Result.success(Unit)
                val tool =
                    TapNodeTool(
                        mockTreeParser,
                        realFinder,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        realResolver,
                    )

                val text = extractTextContent(tool.execute(selectorParams("OK")))

                assertTrue(text.contains("within node 'node_ok_on'"), text)
                coVerify { mockActionExecutor.tap(match { it in 100f..300f }, match { it in 200f..400f }) }
            }

        @Test
        fun `find_nodes with visible_only returns only on-screen matches`() =
            runTest {
                treesInOrder(tree)
                val tool =
                    FindNodesTool(
                        mockTreeParser,
                        realFinder,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        PrivacyToolTestDoubles.passthroughGate(),
                        PrivacyToolTestDoubles.identitySubstitutor(),
                    )
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "OK")
                        put("exact_match", true)
                        put("visible_only", true)
                    }

                val json = Json.parseToJsonElement(stripUntrustedWarning(extractTextContent(tool.execute(params))))
                val ids = json.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject["node_id"]!!.jsonPrimitive.content }

                assertEquals(listOf("node_ok_on"), ids)
            }

        @Test
        fun `find_nodes rejects a non-boolean visible_only`() =
            runTest {
                val tool =
                    FindNodesTool(
                        mockTreeParser,
                        realFinder,
                        mockAccessibilityServiceProvider,
                        mockNodeCache,
                        PrivacyToolTestDoubles.passthroughGate(),
                        PrivacyToolTestDoubles.identitySubstitutor(),
                    )
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "OK")
                        put("visible_only", "yes")
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("ScrollNodeTool")
    inner class ScrollNodeToolTests {
        private val tree = screenWith(leaf("node_row", "Row", visible = true))
        private val tool =
            ScrollNodeTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                realResolver,
            )

        @Test
        fun `scrolls the node by id once in the given direction`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.scrollNode("node_list", ScrollDirection.DOWN, any()) } returns
                    Result.success(Unit)

                val text =
                    extractTextContent(
                        tool.execute(
                            buildJsonObject {
                                put("node_id", "node_list")
                                put("direction", "down")
                            },
                        ),
                    )

                assertEquals("Scrolled node 'node_list' down", text)
                coVerify(exactly = 1) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `maps every direction`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.scrollNode(any(), any(), any()) } returns Result.success(Unit)

                val directions =
                    listOf(
                        "up" to ScrollDirection.UP,
                        "down" to ScrollDirection.DOWN,
                        "left" to ScrollDirection.LEFT,
                        "right" to ScrollDirection.RIGHT,
                    )
                for ((name, direction) in directions) {
                    tool.execute(
                        buildJsonObject {
                            put("node_id", "node_list")
                            put("direction", name)
                        },
                    )
                    coVerify { mockActionExecutor.scrollNode("node_list", direction, any()) }
                }
            }

        @Test
        fun `scrolls the node matched by a selector`() =
            runTest {
                val list = screenWith(leaf("node_row", "Row", visible = true)).copy(contentDescription = "long_list")
                treesInOrder(list)
                coEvery { mockActionExecutor.scrollNode("node_list", ScrollDirection.UP, any()) } returns
                    Result.success(Unit)

                val text =
                    extractTextContent(
                        tool.execute(
                            buildJsonObject {
                                put("by", "content_desc")
                                put("value", "long_list")
                                put("direction", "up")
                            },
                        ),
                    )

                assertEquals("Scrolled node 'node_list' up", text)
            }

        @Test
        fun `rejects a missing or unknown direction, echoing the unknown one`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(buildJsonObject { put("node_id", "node_list") })
                }
                val e =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(
                            buildJsonObject {
                                put("node_id", "node_list")
                                put("direction", "diagonal")
                            },
                        )
                    }
                assertTrue(e.message!!.contains("'diagonal'"), e.message)
            }

        @Test
        fun `direction is case-insensitive`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.scrollNode("node_list", ScrollDirection.DOWN, any()) } returns
                    Result.success(Unit)

                val text =
                    extractTextContent(
                        tool.execute(
                            buildJsonObject {
                                put("node_id", "node_list")
                                put("direction", "Down")
                            },
                        ),
                    )

                assertEquals("Scrolled node 'node_list' down", text)
            }

        @Test
        fun `a node that is not scrollable is an action failure`() =
            runTest {
                treesInOrder(tree)
                coEvery { mockActionExecutor.scrollNode("node_row", any(), any()) } returns
                    Result.failure(IllegalStateException("Node 'node_row' is not scrollable"))

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(
                        buildJsonObject {
                            put("node_id", "node_row")
                            put("direction", "down")
                        },
                    )
                }
            }
    }

    @Nested
    @DisplayName("ScrollToNodeTool with node ids that change on scroll")
    inner class ScrollToMovingNodeTests {
        private val tool =
            ScrollToNodeTool(
                mockTreeParser,
                realFinder,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
                realResolver,
            )

        private val before =
            screenWith(
                leaf("node_row_0_top", "Row 0", visible = true),
                leaf("node_target_off", "Row 30", visible = false, bounds = BoundsData(0, 3000, 100, 3100)),
            )
        private val after =
            screenWith(
                leaf("node_row_0_gone", "Row 0", visible = false, bounds = BoundsData(0, -900, 100, -800)),
                leaf("node_target_on", "Row 30", visible = true, bounds = BoundsData(0, 1200, 100, 1300)),
            )

        @BeforeEach
        fun scrolls() {
            coEvery { mockActionExecutor.scrollNode("node_list", any(), any()) } returns Result.success(Unit)
        }

        private fun byId(
            nodeId: String,
            direction: String = "down",
        ) = buildJsonObject {
            put("node_id", nodeId)
            put("direction", direction)
        }

        private fun bySelector(value: String) =
            buildJsonObject {
                put("by", "text")
                put("value", value)
                put("exact_match", true)
                put("direction", "down")
            }

        /** A non-scrollable root holding [children]: the scroll container is not the window root. */
        private fun frame(vararg children: AccessibilityNodeData) =
            AccessibilityNodeData(
                id = "node_frame",
                className = "android.widget.FrameLayout",
                bounds = BoundsData(0, 0, 1080, 2400),
                visible = true,
                children = children.toList(),
            )

        @Test
        fun `by node id it finds the moved target and returns its current id`() =
            runTest {
                treesInOrder(before, after)

                val text = extractTextContent(tool.execute(byId("node_target_off")))

                assertEquals("Scrolled to node 'node_target_on' (1 scroll(s))", text)
            }

        @Test
        fun `by selector it matches the off-screen target and returns its current id`() =
            runTest {
                treesInOrder(before, after)

                val text = extractTextContent(tool.execute(bySelector("Row 30")))

                assertEquals("Scrolled to node 'node_target_on' (1 scroll(s))", text)
            }

        @Test
        fun `by node id a target that drops out of its container stops instead of taking a look-alike`() =
            runTest {
                // The target leaves the loaded rows; a node that looks like it shows up afterwards
                val outOfTree = screenWith(leaf("node_row_10", "Row 10", visible = true))
                treesInOrder(before, outOfTree, after)

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                assertTrue(e.message!!.contains("is no longer in its container after 1 scroll(s) down"), e.message)
                coVerify(exactly = 1) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `by node id look-alikes at the first read fail before any scroll`() =
            runTest {
                treesInOrder(
                    screenWith(
                        leaf("node_target_off", "Row 30", visible = false, bounds = BoundsData(0, 3000, 100, 3100)),
                        leaf("node_twin_off", "Row 30", visible = false, bounds = BoundsData(0, 3200, 100, 3300)),
                    ),
                )

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                assertTrue(e.message!!.contains("2 nodes in its container look like node 'node_target_off'"), e.message)
                coVerify(exactly = 0) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `by node id two look-alikes after scrolling are an action failure, not a guess`() =
            runTest {
                val twins =
                    screenWith(
                        leaf("node_twin_1", "Row 30", visible = true),
                        leaf("node_twin_2", "Row 30", visible = true, bounds = BoundsData(0, 200, 100, 300)),
                    )
                treesInOrder(before, twins)

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                // A selector matches the same attributes, so the error must not send the agent to one
                assertTrue(e.message!!.contains("a selector cannot either"), e.message)
            }

        @Test
        fun `by selector two matches are an untrusted error`() =
            runTest {
                val twinsOff =
                    screenWith(
                        leaf("node_twin_1", "Row 30", visible = false),
                        leaf("node_twin_2", "Row 30", visible = false, bounds = BoundsData(0, 200, 100, 300)),
                    )
                treesInOrder(twinsOff)

                val result = tool.execute(bySelector("Row 30"))

                assertEquals(true, result.isError)
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, extractTextContent(result).lines().first())
                coVerify(exactly = 0) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `by selector a target not in the tree is node not found with the scroll_node hint`() =
            runTest {
                treesInOrder(before)

                val e = assertThrows<McpToolException.NodeNotFound> { tool.execute(bySelector("Row 99")) }

                assertTrue(e.message!!.contains("scroll_node"), e.message)
            }

        @Test
        fun `an already visible selector match returns at once`() =
            runTest {
                treesInOrder(before)

                val text = extractTextContent(tool.execute(bySelector("Row 0")))

                assertEquals("Node 'node_row_0_top' is already visible", text)
            }

        @Test
        fun `scrolls the container in the requested direction`() =
            runTest {
                treesInOrder(before, after)

                tool.execute(byId("node_target_off", direction = "up"))

                coVerify(exactly = 1) { mockActionExecutor.scrollNode("node_list", ScrollDirection.UP, any()) }
            }

        @Test
        fun `requires a valid direction`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(buildJsonObject { put("node_id", "node_target_off") })
                }
                assertThrows<McpToolException.InvalidParams> { tool.execute(byId("node_target_off", "sideways")) }
                coVerify(exactly = 0) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `by selector it keeps scrolling while the match is out of the tree`() =
            runTest {
                treesInOrder(before, screenWith(leaf("node_row_10", "Row 10", visible = true)), after)

                val text = extractTextContent(tool.execute(bySelector("Row 30")))

                assertEquals("Scrolled to node 'node_target_on' (2 scroll(s))", text)
            }

        @Test
        fun `by selector a second match appearing after a scroll is an untrusted error, not a guess`() =
            runTest {
                val twinsAfter =
                    screenWith(
                        leaf("node_twin_1", "Row 30", visible = true),
                        leaf("node_twin_2", "Row 30", visible = false, bounds = BoundsData(0, 2500, 100, 2600)),
                    )
                treesInOrder(before, twinsAfter)

                val result = tool.execute(bySelector("Row 30"))

                assertEquals(true, result.isError)
                val text = extractTextContent(result)
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                assertTrue(text.contains("node_twin_1") && text.contains("node_twin_2"), text)
                coVerify(exactly = 1) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `stops after the maximum scrolls when the target never shows`() =
            runTest {
                treesInOrder(before)

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                assertTrue(e.message!!.contains("not visible after 5 scroll attempts"), e.message)
                coVerify(exactly = 5) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `stops early when the container does not scroll any further`() =
            runTest {
                treesInOrder(before)
                coEvery { mockActionExecutor.scrollNode("node_list", any(), any()) } returnsMany
                    listOf(
                        Result.success(Unit),
                        Result.failure(RuntimeException("Scroll DOWN failed on node 'node_list'")),
                    )

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                assertTrue(e.message!!.contains("after 1 scroll(s) down"), e.message)
                assertTrue(e.message!!.contains("did not scroll further"), e.message)
                coVerify(exactly = 2) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `stops when the container is no longer at its place`() =
            runTest {
                val containerGone = screenWith(leaf("node_row_0_top", "Row 0", visible = true)).copy(scrollable = false)
                treesInOrder(before, containerGone)

                val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                assertTrue(e.message!!.contains("no longer at its place"), e.message)
                coVerify(exactly = 1) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `a target without a scrollable ancestor is an action failure before any scroll`() =
            runTest {
                val notScrollable = leaf("node_target_off", "Row 30", visible = false)
                // The target itself being scrollable does not make it its own container
                for (target in listOf(notScrollable, notScrollable.copy(scrollable = true))) {
                    treesInOrder(frame(target))

                    val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                    assertEquals("No scrollable container found for node 'node_target_off'", e.message)
                }
                coVerify(exactly = 0) { mockActionExecutor.scrollNode(any(), any(), any()) }
            }

        @Test
        fun `by node id a look-alike outside the scroll container is ignored`() =
            runTest {
                val outside = leaf("node_outside", "Row 30", visible = true, bounds = BoundsData(0, 0, 100, 60))
                treesInOrder(
                    frame(screenWith(leaf("node_target_off", "Row 30", visible = false)), outside),
                    frame(screenWith(leaf("node_target_on", "Row 30", visible = true)), outside),
                )

                val text = extractTextContent(tool.execute(byId("node_target_off")))

                assertEquals("Scrolled to node 'node_target_on' (1 scroll(s))", text)
            }

        @Test
        fun `tracks a container nested at a non-zero place whose id changes`() =
            runTest {
                val header = leaf("node_header", "Title", visible = true, bounds = BoundsData(0, 0, 1080, 100))
                val toolbar = leaf("node_toolbar", "Tools", visible = true, bounds = BoundsData(0, 100, 1080, 200))

                fun screen(
                    listId: String,
                    target: AccessibilityNodeData,
                ) = frame(
                    header,
                    frame(toolbar, screenWith(target).copy(id = listId, resourceId = "com.example:id/list"))
                        .copy(id = "node_body"),
                )
                // The list sits at child path [1, 1] (root -> body -> list), with a new id on every read
                treesInOrder(
                    screen("node_list_a", leaf("node_target_off", "Row 30", visible = false)),
                    screen("node_list_b", leaf("node_target_still_off", "Row 30", visible = false)),
                    screen("node_list_c", leaf("node_target_on", "Row 30", visible = true)),
                )
                coEvery { mockActionExecutor.scrollNode(any(), any(), any()) } returns Result.success(Unit)

                val text = extractTextContent(tool.execute(byId("node_target_off")))

                assertEquals("Scrolled to node 'node_target_on' (2 scroll(s))", text)
                coVerifyOrder {
                    mockActionExecutor.scrollNode("node_list_a", ScrollDirection.DOWN, any())
                    mockActionExecutor.scrollNode("node_list_b", ScrollDirection.DOWN, any())
                }
            }

        @Test
        fun `a different scrollable node at the container's place is not taken for it`() =
            runTest {
                val otherClass =
                    screenWith(leaf("node_target_on", "Row 30", visible = true))
                        .copy(id = "node_other", className = "androidx.recyclerview.widget.RecyclerView")
                val otherResourceId =
                    screenWith(leaf("node_target_on", "Row 30", visible = true))
                        .copy(id = "node_other", resourceId = "com.example:id/other")
                for (replacement in listOf(otherClass, otherResourceId)) {
                    treesInOrder(before, replacement)

                    val e = assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }

                    assertTrue(e.message!!.contains("no longer at its place"), e.message)
                }
                coVerify(exactly = 0) { mockActionExecutor.scrollNode("node_other", any(), any()) }
            }

        @Test
        fun `a scroll failure that is not a refused scroll keeps its own error, naming the container`() =
            runTest {
                treesInOrder(before)
                coEvery { mockActionExecutor.scrollNode("node_list", any(), any()) } returns
                    Result.failure(IllegalStateException("Accessibility service is not available"))
                assertThrows<McpToolException.PermissionDenied> { tool.execute(byId("node_target_off")) }

                coEvery { mockActionExecutor.scrollNode("node_list", any(), any()) } returns
                    Result.failure(NoSuchElementException("Node 'node_list' not found"))
                val gone = assertThrows<McpToolException.NodeNotFound> { tool.execute(byId("node_target_off")) }
                assertTrue(
                    gone.message!!.contains("scrollable container 'node_list' of node 'node_target_off'"),
                    gone.message,
                )

                coEvery { mockActionExecutor.scrollNode("node_list", any(), any()) } returns
                    Result.failure(IllegalStateException("Node 'node_list' is not scrollable"))
                val notScrollable =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(byId("node_target_off")) }
                assertTrue(
                    notScrollable.message!!.contains("scrollable container 'node_list' of node 'node_target_off'"),
                    notScrollable.message,
                )
            }

        @Test
        fun `follows its container when the container itself moves`() =
            runTest {
                val movedBounds = BoundsData(0, 300, 1080, 2400)
                treesInOrder(
                    frame(screenWith(leaf("node_target_off", "Row 30", visible = false))),
                    frame(
                        screenWith(leaf("node_target_still_off", "Row 30", visible = false))
                            .copy(id = "node_list_moved", bounds = movedBounds),
                    ),
                    frame(
                        screenWith(leaf("node_target_on", "Row 30", visible = true))
                            .copy(id = "node_list_moved", bounds = movedBounds),
                    ),
                )
                coEvery { mockActionExecutor.scrollNode("node_list_moved", any(), any()) } returns Result.success(Unit)

                val text = extractTextContent(tool.execute(byId("node_target_off")))

                assertEquals("Scrolled to node 'node_target_on' (2 scroll(s))", text)
                coVerifyOrder {
                    mockActionExecutor.scrollNode("node_list", ScrollDirection.DOWN, any())
                    mockActionExecutor.scrollNode("node_list_moved", ScrollDirection.DOWN, any())
                }
            }
    }
}
