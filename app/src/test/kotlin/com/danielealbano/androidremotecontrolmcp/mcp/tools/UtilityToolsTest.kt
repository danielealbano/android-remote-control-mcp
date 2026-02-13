@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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

@DisplayName("UtilityTools")
class UtilityToolsTest {
    private val mockTreeParser = mockk<AccessibilityTreeParser>()
    private val mockElementFinder = mockk<ElementFinder>()
    private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockClipboardManager = mockk<ClipboardManager>()
    private val mockContext = mockk<Context>()

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
        )

    private val sampleBounds = BoundsData(50, 800, 250, 1000)

    private val sampleElementInfo =
        ElementInfo(
            id = "node_abc",
            text = "Result",
            className = "android.widget.TextView",
            bounds = sampleBounds,
            clickable = false,
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
        every { mockAccessibilityServiceProvider.getContext() } returns mockContext
        every { mockContext.getSystemService(ClipboardManager::class.java) } returns mockClipboardManager
        every { mockTreeParser.parseTree(mockRootNode) } returns sampleTree
        every { mockRootNode.recycle() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("GetClipboardTool")
    inner class GetClipboardToolTests {
        private val tool = GetClipboardTool(mockAccessibilityServiceProvider)

        @Test
        fun `returns clipboard text`() =
            runTest {
                val mockClip = mockk<ClipData>()
                val mockItem = mockk<ClipData.Item>()
                every { mockClipboardManager.primaryClip } returns mockClip
                every { mockClip.itemCount } returns 1
                every { mockClip.getItemAt(0) } returns mockItem
                every { mockItem.text } returns "copied text"

                val result = tool.execute(null)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertEquals("copied text", parsed["text"]?.jsonPrimitive?.content)
            }

        @Test
        fun `returns null when clipboard empty`() =
            runTest {
                every { mockClipboardManager.primaryClip } returns null

                val result = tool.execute(null)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertTrue(
                    parsed["text"]?.jsonPrimitive?.content == null ||
                        parsed["text"]?.jsonPrimitive?.content == "null",
                )
            }
    }

    @Nested
    @DisplayName("SetClipboardTool")
    inner class SetClipboardToolTests {
        private val tool = SetClipboardTool(mockAccessibilityServiceProvider)

        @Test
        fun `sets clipboard text`() =
            runTest {
                every { mockClipboardManager.setPrimaryClip(any()) } returns Unit
                val params = buildJsonObject { put("text", "new content") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Clipboard set"))
                verify { mockClipboardManager.setPrimaryClip(any()) }
            }

        @Test
        fun `throws error when text missing`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("WaitForElementTool")
    inner class WaitForElementToolTests {
        private val tool = WaitForElementTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)

        @Test
        fun `finds element on first attempt`() =
            runTest {
                every {
                    mockElementFinder.findElements(sampleTree, FindBy.TEXT, "Result", false)
                } returns listOf(sampleElementInfo)
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "Result")
                        put("timeout", 5000)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
            }

        @Test
        fun `throws error for invalid by parameter`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "invalid")
                        put("value", "test")
                    }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `throws error for empty value`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "")
                    }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `throws error for timeout exceeding max`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "test")
                        put("timeout", 50000)
                    }

                val exception = assertThrows<McpToolException> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Timeout must be between"))
            }

        @Test
        fun `throws error for negative timeout`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "test")
                        put("timeout", -1)
                    }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `finds element after multiple poll attempts`() =
            runTest {
                var callCount = 0
                every {
                    mockElementFinder.findElements(sampleTree, FindBy.TEXT, "Delayed", false)
                } answers {
                    callCount++
                    if (callCount >= 3) listOf(sampleElementInfo) else emptyList()
                }
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "Delayed")
                        put("timeout", 10000)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
                assertTrue(parsed["attempts"]?.jsonPrimitive?.content?.toInt()!! >= 3)
            }

        @Test
        fun `returns timed out message when element never found`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }
                    every {
                        mockElementFinder.findElements(sampleTree, FindBy.TEXT, "Missing", false)
                    } answers {
                        clockMs += 600L
                        emptyList()
                    }
                    val params =
                        buildJsonObject {
                            put("by", "text")
                            put("value", "Missing")
                            put("timeout", 2000)
                        }

                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    assertTrue(text.contains("timed out"))
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }
    }

    @Nested
    @DisplayName("WaitForIdleTool")
    inner class WaitForIdleToolTests {
        private val tool = WaitForIdleTool(mockTreeParser, mockAccessibilityServiceProvider)

        @Test
        fun `detects idle when tree does not change`() =
            runTest {
                // Same tree returned each time -> idle detected
                val params = buildJsonObject { put("timeout", 5000) }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
            }

        @Test
        fun `throws error for timeout exceeding max`() =
            runTest {
                val params = buildJsonObject { put("timeout", 50000) }

                assertThrows<McpToolException> { tool.execute(params) }
            }

        @Test
        fun `throws error for zero timeout`() =
            runTest {
                val params = buildJsonObject { put("timeout", 0) }

                assertThrows<McpToolException> { tool.execute(params) }
            }
    }
}
