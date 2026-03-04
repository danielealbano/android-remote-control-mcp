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
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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

@DisplayName("NodeActionTools")
class NodeActionToolsTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockElementFinder = mockk<ElementFinder>()
    private val mockActionExecutor = mockk<ActionExecutor>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()

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
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockWindowInfo.recycle() } returns Unit
        every { mockRootNode.packageName } returns "com.example"
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
        every { mockAccessibilityServiceProvider.getScreenInfo() } returns
            ScreenInfo(
                width = 1080,
                height = 2400,
                densityDpi = 420,
                orientation = "portrait",
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("FindNodesTool")
    inner class FindNodesToolTests {
        private val tool =
            FindNodesTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)

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
                val parsed = Json.parseToJsonElement(text).jsonObject

                // Assert
                val elements = parsed["nodes"]!!.jsonArray
                assertEquals(1, elements.size)
                assertEquals("node_abc", elements[0].jsonObject["node_id"]?.jsonPrimitive?.content)
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
                val parsed = Json.parseToJsonElement(text).jsonObject

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
            ClickNodeTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

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
            LongClickNodeTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

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
            )

        @Test
        fun `returns immediately when node already visible`() =
            runTest {
                val visibleNode = sampleTree.children[0] // visible = true
                every { mockElementFinder.findNodeById(sampleWindows, "node_abc") } returns visibleNode
                val params = buildJsonObject { put("node_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("already visible"))
            }

        @Test
        fun `throws error when node not found`() =
            runTest {
                every { mockElementFinder.findNodeById(sampleWindows, "node_xyz") } returns null
                val params = buildJsonObject { put("node_id", "node_xyz") }

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

                // Multi-window findNodeById: first call invisible, after scroll visible
                var findCount = 0
                every {
                    mockElementFinder.findNodeById(any<List<WindowData>>(), eq("node_dialog_btn"))
                } answers {
                    findCount++
                    if (findCount <= 1) invisibleNode else visibleNode
                }
                // findContainingTree calls single-tree overload per window
                every {
                    mockElementFinder.findNodeById(sampleTree, "node_dialog_btn")
                } returns null
                every {
                    mockElementFinder.findNodeById(secondWindowTreeBefore, "node_dialog_btn")
                } returns invisibleNode
                every {
                    mockElementFinder.findNodeById(secondWindowTreeAfter, "node_dialog_btn")
                } returns visibleNode
                coEvery {
                    mockActionExecutor.scrollNode(any(), any(), any())
                } returns Result.success(Unit)

                val params = buildJsonObject { put("node_id", "node_dialog_btn") }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(
                    text.contains("Scrolled") || text.contains("scroll"),
                    "Expected scroll result but got: $text",
                )
            }
    }
}
