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

@DisplayName("ElementActionTools")
class ElementActionToolsTest {
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
    @DisplayName("FindElementsTool")
    inner class FindElementsToolTests {
        private val tool =
            FindElementsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)

        @Test
        fun `returns matching elements`() =
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
                val elements = parsed["elements"]!!.jsonArray
                assertEquals(1, elements.size)
                assertEquals("node_abc", elements[0].jsonObject["id"]?.jsonPrimitive?.content)
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
                val elements = parsed["elements"]!!.jsonArray
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
    @DisplayName("ClickElementTool")
    inner class ClickElementToolTests {
        private val tool =
            ClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

        @Test
        fun `clicks element successfully`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleWindows) } returns Result.success(Unit)
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Click performed"))
            }

        @Test
        fun `throws error when element not found`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_xyz", sampleWindows) } returns
                    Result.failure(NoSuchElementException("Node 'node_xyz' not found"))
                val params = buildJsonObject { put("element_id", "node_xyz") }

                assertThrows<McpToolException.ElementNotFound> { tool.execute(params) }
            }

        @Test
        fun `throws error when element not clickable`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleWindows) } returns
                    Result.failure(IllegalStateException("Node 'node_abc' is not clickable"))
                val params = buildJsonObject { put("element_id", "node_abc") }

                assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
            }

        @Test
        fun `throws error for missing element_id`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("LongClickElementTool")
    inner class LongClickElementToolTests {
        private val tool =
            LongClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

        @Test
        fun `long-clicks element successfully`() =
            runTest {
                coEvery { mockActionExecutor.longClickNode("node_abc", sampleWindows) } returns Result.success(Unit)
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Long-click"))
            }
    }

    @Nested
    @DisplayName("ScrollToElementTool")
    inner class ScrollToElementToolTests {
        private val tool =
            ScrollToElementTool(
                mockTreeParser,
                mockElementFinder,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockNodeCache,
            )

        @Test
        fun `returns immediately when element already visible`() =
            runTest {
                val visibleNode = sampleTree.children[0] // visible = true
                every { mockElementFinder.findNodeById(sampleWindows, "node_abc") } returns visibleNode
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("already visible"))
            }

        @Test
        fun `throws error when element not found`() =
            runTest {
                every { mockElementFinder.findNodeById(sampleWindows, "node_xyz") } returns null
                val params = buildJsonObject { put("element_id", "node_xyz") }

                assertThrows<McpToolException.ElementNotFound> { tool.execute(params) }
            }

        @Suppress("LongMethod")
        @Test
        fun `scrolls to element in non-primary window`() =
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

                val params = buildJsonObject { put("element_id", "node_dialog_btn") }
                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(
                    text.contains("Scrolled") || text.contains("scroll"),
                    "Expected scroll result but got: $text",
                )
            }
    }
}
