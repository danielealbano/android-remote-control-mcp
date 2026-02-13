@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("TextInputTools")
class TextInputToolsTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockActionExecutor = mockk<ActionExecutor>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockFocusedNode = mockk<AccessibilityNodeInfo>()

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
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
        every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
        every { mockRootNode.recycle() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("InputTextTool")
    inner class InputTextToolTests {
        private val tool = InputTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `inputs text on specified element`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_123", sampleTree) } returns Result.success(Unit)
                coEvery {
                    mockActionExecutor.setTextOnNode("node_123", "Hello", sampleTree)
                } returns Result.success(Unit)
                val params =
                    buildJsonObject {
                        put("text", "Hello")
                        put("element_id", "node_123")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text input completed"))
            }

        @Test
        fun `inputs text on focused element when no element_id`() =
            runTest {
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
                every { mockFocusedNode.isEditable } returns true
                every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
                every { mockFocusedNode.recycle() } returns Unit
                val params = buildJsonObject { put("text", "World") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text input completed"))
            }

        @Test
        fun `throws error when text is missing`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("ClearTextTool")
    inner class ClearTextToolTests {
        private val tool = ClearTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `clears text on specified element`() =
            runTest {
                coEvery { mockActionExecutor.setTextOnNode("node_123", "", sampleTree) } returns Result.success(Unit)
                val params = buildJsonObject { put("element_id", "node_123") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
            }

        @Test
        fun `clears text on focused element when no element_id`() =
            runTest {
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
                every { mockFocusedNode.isEditable } returns true
                every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
                every { mockFocusedNode.recycle() } returns Unit
                val params = buildJsonObject {}

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
            }
    }

    @Nested
    @DisplayName("PressKeyTool")
    inner class PressKeyToolTests {
        private val tool = PressKeyTool(mockActionExecutor, mockAccessibilityServiceProvider)

        @Test
        fun `presses BACK key`() =
            runTest {
                coEvery { mockActionExecutor.pressBack() } returns Result.success(Unit)
                val params = buildJsonObject { put("key", "BACK") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("BACK"))
            }

        @Test
        fun `presses HOME key`() =
            runTest {
                coEvery { mockActionExecutor.pressHome() } returns Result.success(Unit)
                val params = buildJsonObject { put("key", "HOME") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("HOME"))
            }

        @Test
        fun `presses DEL key removes last character`() =
            runTest {
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
                every { mockFocusedNode.isEditable } returns true
                every { mockFocusedNode.text } returns "Hello"
                every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
                every { mockFocusedNode.recycle() } returns Unit
                val params = buildJsonObject { put("key", "DEL") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("DEL"))
            }

        @Test
        fun `presses SPACE key appends space`() =
            runTest {
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
                every { mockFocusedNode.isEditable } returns true
                every { mockFocusedNode.text } returns "Hello"
                every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
                every { mockFocusedNode.recycle() } returns Unit
                val params = buildJsonObject { put("key", "SPACE") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("SPACE"))
            }

        @Test
        fun `throws error for invalid key`() =
            runTest {
                val params = buildJsonObject { put("key", "ESCAPE") }

                val exception = assertThrows<McpToolException> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Invalid key"))
            }

        @Test
        fun `throws error for missing key parameter`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }
}
