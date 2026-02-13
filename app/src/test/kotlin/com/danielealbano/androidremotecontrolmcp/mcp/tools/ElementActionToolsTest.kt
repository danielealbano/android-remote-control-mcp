@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
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
    private val mockRootNode = mockk<AccessibilityNodeInfo>()

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
        every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
        every { mockRootNode.recycle() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("FindElementsTool")
    inner class FindElementsToolTests {
        private val tool = FindElementsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)

        @Test
        fun `returns matching elements`() =
            runTest {
                // Arrange
                every {
                    mockElementFinder.findElements(sampleTree, FindBy.TEXT, "7", false)
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
                    mockElementFinder.findElements(sampleTree, FindBy.TEXT, "99", false)
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

                val exception = assertThrows<McpToolException> { tool.execute(params) }
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

                val exception = assertThrows<McpToolException> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error for missing by parameter`() =
            runTest {
                val params = buildJsonObject { put("value", "test") }

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("ClickElementTool")
    inner class ClickElementToolTests {
        private val tool = ClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `clicks element successfully`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleTree) } returns Result.success(Unit)
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Click performed"))
            }

        @Test
        fun `throws error when element not found`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_xyz", sampleTree) } returns
                    Result.failure(NoSuchElementException("Node 'node_xyz' not found"))
                val params = buildJsonObject { put("element_id", "node_xyz") }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `throws error when element not clickable`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_abc", sampleTree) } returns
                    Result.failure(IllegalStateException("Node 'node_abc' is not clickable"))
                val params = buildJsonObject { put("element_id", "node_abc") }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `throws error for missing element_id`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("LongClickElementTool")
    inner class LongClickElementToolTests {
        private val tool = LongClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `long-clicks element successfully`() =
            runTest {
                coEvery { mockActionExecutor.longClickNode("node_abc", sampleTree) } returns Result.success(Unit)
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Long-click"))
            }
    }

    @Nested
    @DisplayName("SetTextTool")
    inner class SetTextToolTests {
        private val tool = SetTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `sets text on element`() =
            runTest {
                coEvery {
                    mockActionExecutor.setTextOnNode("node_abc", "Hello", sampleTree)
                } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("element_id", "node_abc")
                        put("text", "Hello")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text set"))
            }

        @Test
        fun `allows empty text to clear field`() =
            runTest {
                coEvery {
                    mockActionExecutor.setTextOnNode("node_abc", "", sampleTree)
                } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("element_id", "node_abc")
                        put("text", "")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text set"))
            }

        @Test
        fun `throws error for non-editable element`() =
            runTest {
                coEvery {
                    mockActionExecutor.setTextOnNode("node_abc", "Hi", sampleTree)
                } returns
                    Result.failure(IllegalStateException("Node 'node_abc' is not editable"))
                val params =
                    buildJsonObject {
                        put("element_id", "node_abc")
                        put("text", "Hi")
                    }

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("ScrollToElementTool")
    inner class ScrollToElementToolTests {
        private val tool =
            ScrollToElementTool(mockTreeParser, mockElementFinder, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `returns immediately when element already visible`() =
            runTest {
                val visibleNode = sampleTree.children[0] // visible = true
                every { mockElementFinder.findNodeById(sampleTree, "node_abc") } returns visibleNode
                val params = buildJsonObject { put("element_id", "node_abc") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("already visible"))
            }

        @Test
        fun `throws error when element not found`() =
            runTest {
                every { mockElementFinder.findNodeById(sampleTree, "node_xyz") } returns null
                val params = buildJsonObject { put("element_id", "node_xyz") }

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }
}
