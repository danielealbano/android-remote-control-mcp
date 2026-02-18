@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
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
import kotlinx.serialization.json.int
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
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()
    private val mockClipboardManager = mockk<ClipboardManager>()
    private val mockContext = mockk<Context>()

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
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

    @Suppress("LongMethod")
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
        every { mockAccessibilityServiceProvider.getContext() } returns mockContext
        every { mockContext.getSystemService(ClipboardManager::class.java) } returns mockClipboardManager
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
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

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }

    @Nested
    @DisplayName("WaitForElementTool")
    inner class WaitForElementToolTests {
        private val tool = WaitForElementTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)

        @Test
        fun `finds element on first attempt`() =
            runTest {
                every {
                    mockElementFinder.findElements(sampleWindows, FindBy.TEXT, "Result", false)
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

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for empty value`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("by", "text")
                        put("value", "")
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
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

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
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

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `finds element after multiple poll attempts`() =
            runTest {
                var callCount = 0
                every {
                    mockElementFinder.findElements(any<List<WindowData>>(), eq(FindBy.TEXT), eq("Delayed"), eq(false))
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
                        mockElementFinder.findElements(
                            any<List<WindowData>>(),
                            eq(FindBy.TEXT),
                            eq("Missing"),
                            eq(false),
                        )
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
        private val tool = WaitForIdleTool(mockTreeParser, mockAccessibilityServiceProvider, mockNodeCache)

        @Test
        fun `detects idle when tree does not change`() =
            runTest {
                // Same tree returned each time -> idle detected
                val params = buildJsonObject { put("timeout", 5000) }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
                assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
            }

        @Test
        fun `match_percentage defaults to 100 when not provided`() =
            runTest {
                // Same tree each time -> idle at 100% similarity
                val params = buildJsonObject { put("timeout", 5000) }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                val parsed = Json.parseToJsonElement(text).jsonObject
                assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
            }

        @Test
        fun `detects idle with match_percentage below 100 when tree has minor changes`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }

                    // Return trees that differ slightly each time (1 node text changes out of 11 total)
                    var callCount = 0
                    every { mockTreeParser.parseTree(any(), any(), any()) } answers {
                        callCount++
                        clockMs += 600L
                        AccessibilityNodeData(
                            id = "node_root",
                            className = "android.widget.FrameLayout",
                            bounds = BoundsData(0, 0, 1080, 2400),
                            visible = true,
                            children =
                                (0 until 10).map { i ->
                                    AccessibilityNodeData(
                                        id = "node_$i",
                                        className = "android.widget.TextView",
                                        text = if (i == 0) "changing_$callCount" else "stable_$i",
                                        bounds = BoundsData(0, i * 100, 1080, (i + 1) * 100),
                                        visible = true,
                                    )
                                },
                        )
                    }

                    // With match_percentage=80, the minor change should still be considered idle
                    val params =
                        buildJsonObject {
                            put("timeout", 10000)
                            put("match_percentage", 80)
                        }
                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    val parsed = Json.parseToJsonElement(text).jsonObject
                    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
                    val similarity = parsed["similarity"]?.jsonPrimitive?.int ?: 0
                    assertTrue(similarity in 80..100, "Expected similarity between 80 and 100, got $similarity")
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `throws error for match_percentage above 100`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("timeout", 5000)
                        put("match_percentage", 101)
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for negative match_percentage`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("timeout", 5000)
                        put("match_percentage", -1)
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `timeout response includes similarity field`() =
            runTest {
                mockkStatic(SystemClock::class)
                try {
                    var clockMs = 0L
                    every { SystemClock.elapsedRealtime() } answers { clockMs }

                    // Return different trees each time to force timeout
                    var callCount = 0
                    every { mockTreeParser.parseTree(any(), any(), any()) } answers {
                        callCount++
                        clockMs += 600L
                        AccessibilityNodeData(
                            id = "node_root",
                            className = "android.widget.FrameLayout",
                            text = "changing_$callCount",
                            bounds = BoundsData(0, 0, 1080, 2400),
                            visible = true,
                        )
                    }

                    val params = buildJsonObject { put("timeout", 1000) }
                    val result = tool.execute(params)
                    val text = extractTextContent(result)
                    val parsed = Json.parseToJsonElement(text).jsonObject
                    assertTrue(parsed.containsKey("similarity"))
                    assertTrue(parsed.containsKey("elapsedMs"))
                    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("timed out") == true)
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }

        @Test
        fun `throws error for timeout exceeding max`() =
            runTest {
                val params = buildJsonObject { put("timeout", 50000) }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error for zero timeout`() =
            runTest {
                val params = buildJsonObject { put("timeout", 0) }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }
}
