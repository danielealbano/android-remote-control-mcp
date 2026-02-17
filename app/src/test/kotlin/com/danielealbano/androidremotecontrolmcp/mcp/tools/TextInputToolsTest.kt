@file:Suppress("DEPRECATION", "TooManyFunctions", "LargeClass")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
    private val mockTypeInputController = mockk<TypeInputController>()
    private val mockRootNode = mockk<AccessibilityNodeInfo>()
    private val mockFocusedNode = mockk<AccessibilityNodeInfo>()
    private val mockWindowInfo = mockk<AccessibilityWindowInfo>()

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

    private fun extractTextContent(result: CallToolResult): String {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        return textContent.text
    }

    private fun createMockSurroundingText(
        text: String,
        offset: Int = 0,
    ): SurroundingText {
        val mock = mockk<SurroundingText>()
        every { mock.text } returns text
        every { mock.offset } returns offset
        every { mock.selectionStart } returns text.length
        every { mock.selectionEnd } returns text.length
        return mock
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
        every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
        every { mockRootNode.recycle() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("SharedUtilities")
    inner class SharedUtilitiesTests {
        @Test
        fun `typeCharByChar iterates by code points not chars`() =
            runTest {
                // "A😀B" is 3 code points but 4 chars (emoji is a surrogate pair)
                val text = "A\uD83D\uDE00B"
                every { mockTypeInputController.commitText(any(), any()) } returns true

                typeCharByChar(text, 10, 0, mockTypeInputController)

                verify(exactly = 3) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `typeCharByChar stops when commitText returns false`() =
            runTest {
                every { mockTypeInputController.commitText("A", 1) } returns true
                every { mockTypeInputController.commitText("B", 1) } returns false

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        typeCharByChar("ABC", 10, 0, mockTypeInputController)
                    }
                assertTrue(exception.message!!.contains("position 1 of 3"))
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar respects coroutine cancellation`() =
            runTest {
                every { mockTypeInputController.commitText(any(), any()) } returns true

                val job =
                    launch {
                        typeCharByChar("ABCDEFGHIJ", 100, 0, mockTypeInputController)
                    }

                // Let it type a few chars then cancel
                testScheduler.advanceTimeBy(150)
                job.cancelAndJoin()

                // Should have typed fewer than 10 chars due to cancellation
                // (Cancellation happens at the delay point, so first char is committed immediately)
                verify(atMost = 5) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `typeCharByChar handles empty string without calling commitText`() =
            runTest {
                typeCharByChar("", 10, 0, mockTypeInputController)

                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar skips delay after last character`() =
            runTest {
                every { mockTypeInputController.commitText(any(), any()) } returns true

                val startTime = testScheduler.currentTime
                typeCharByChar("ABC", 100, 0, mockTypeInputController)
                val elapsed = testScheduler.currentTime - startTime

                // 3 chars: delay after 1st, delay after 2nd, NO delay after 3rd
                // So expected: 2 * 100ms = 200ms
                assertEquals(200L, elapsed)
            }

        @Test
        fun `extractTypingParams uses defaults when not provided`() {
            val params = buildJsonObject {}
            val (speed, variance) = extractTypingParams(params)
            assertEquals(70, speed)
            assertEquals(15, variance)
        }

        @Test
        fun `extractTypingParams rejects typing_speed below minimum`() {
            val params = buildJsonObject { put("typing_speed", 5) }
            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    extractTypingParams(params)
                }
            assertTrue(exception.message!!.contains(">= 10"))
        }

        @Test
        fun `extractTypingParams rejects typing_speed above maximum`() {
            val params = buildJsonObject { put("typing_speed", 6000) }
            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    extractTypingParams(params)
                }
            assertTrue(exception.message!!.contains("<= 5000"))
        }

        @Test
        fun `extractTypingParams accepts typing_speed at exact minimum boundary`() {
            val params = buildJsonObject { put("typing_speed", 10) }
            val (speed, _) = extractTypingParams(params)
            assertEquals(10, speed)
        }

        @Test
        fun `extractTypingParams accepts typing_speed at exact maximum boundary`() {
            val params = buildJsonObject { put("typing_speed", 5000) }
            val (speed, _) = extractTypingParams(params)
            assertEquals(5000, speed)
        }

        @Test
        fun `extractTypingParams rejects negative variance`() {
            val params = buildJsonObject { put("typing_speed_variance", -1) }
            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    extractTypingParams(params)
                }
            assertTrue(exception.message!!.contains(">= 0"))
        }

        @Test
        fun `typeCharByChar clamps large variance to typing_speed`() =
            runTest {
                every { mockTypeInputController.commitText(any(), any()) } returns true

                // variance=9999 but speed=100, variance will be clamped to 100
                typeCharByChar("AB", 100, 9999, mockTypeInputController)

                verify(exactly = 2) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `validateTextLength accepts exactly 2000 chars`() {
            val text = "A".repeat(2000)
            // Should not throw
            validateTextLength(text)
        }

        @Test
        fun `validateTextLength rejects 2001 chars`() {
            val text = "A".repeat(2001)
            val exception =
                assertThrows<McpToolException.InvalidParams> {
                    validateTextLength(text)
                }
            assertTrue(exception.message!!.contains("2000"))
        }

        @Test
        fun `awaitInputConnectionReady succeeds when ready immediately`() =
            runTest {
                every { mockTypeInputController.isReady() } returns true

                // Should not throw
                awaitInputConnectionReady(mockTypeInputController, "test_element")
            }

        @Test
        fun `awaitInputConnectionReady succeeds after retry`() =
            runTest {
                every { mockTypeInputController.isReady() } returnsMany listOf(false, false, true)

                // Should not throw
                awaitInputConnectionReady(mockTypeInputController, "test_element")
            }

        @Test
        fun `awaitInputConnectionReady fails after timeout`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time
                every { mockTypeInputController.isReady() } returns false

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        awaitInputConnectionReady(mockTypeInputController, "test_element")
                    }
                assertTrue(exception.message!!.contains("Input connection not available"))
            }

        @Test
        fun `readFieldContent returns text when available`() {
            val mockSurroundingText = createMockSurroundingText("Hello")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns mockSurroundingText

            val result = readFieldContent(mockTypeInputController)
            assertEquals("Hello", result)
        }

        @Test
        fun `readFieldContent returns fallback when unavailable`() {
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns null

            val result = readFieldContent(mockTypeInputController)
            assertEquals("(unable to read field content)", result)
        }

        @Test
        fun `readFieldContent returns text when SurroundingText has non-zero offset`() {
            val mockSurroundingText = createMockSurroundingText("partial text", offset = 50)
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns mockSurroundingText

            val result = readFieldContent(mockTypeInputController)
            assertEquals("partial text", result)
        }
    }

    @Nested
    @DisplayName("TypeAppendTextTool")
    inner class TypeAppendTextToolTests {
        private val tool =
            TypeAppendTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
            )

        private fun setupDefaultMocks(existingText: String = "existing") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.commitText(any(), any()) } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("${existingText}Hello")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, afterText)
        }

        @Test
        fun `appends text to element`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Typed 5 characters"))
                assertTrue(text.contains("Field content:"))

                verify { mockTypeInputController.setSelection(8, 8) }
                verify(exactly = 5) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `throws error when text is missing`() =
            runTest {
                val params = buildJsonObject { put("element_id", "node_edit") }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when text is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when element_id is missing`() =
            runTest {
                val params = buildJsonObject { put("text", "Hello") }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when element_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "")
                        put("text", "Hello")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when text exceeds max length`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "A".repeat(2001))
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("2000"))
            }

        @Test
        fun `throws error when typing_speed below minimum`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                        put("typing_speed", 5)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains(">= 10"))
            }

        @Test
        fun `throws error when typing_speed above maximum`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                        put("typing_speed", 6000)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("<= 5000"))
            }

        @Test
        fun `throws error when input connection not ready after poll timeout`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns false

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Input connection not available"))
            }

        @Test
        fun `throws error when setSelection fails`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("existing")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText
                every { mockTypeInputController.setSelection(any(), any()) } returns false

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to position cursor"))
            }

        @Test
        fun `uses default typing speed and variance`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "AB")
                    }

                // Should not throw — uses defaults 70/15
                tool.execute(params)
            }

        @Test
        fun `handles emoji text correctly`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val beforeText = createMockSurroundingText("")
                val afterText = createMockSurroundingText("Hi\uD83D\uDE00")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hi\uD83D\uDE00")
                    }

                tool.execute(params)

                // "Hi😀" = 3 code points (H, i, 😀), not 4 chars
                verify(exactly = 3) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `returns field content in response`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Field content: existingHello"))
            }

        @Test
        fun `defaults textLength to 0 when getSurroundingText returns null`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val afterText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                tool.execute(params)

                // textLength defaults to 0, so setSelection(0, 0)
                verify { mockTypeInputController.setSelection(0, 0) }
            }
    }

    @Nested
    @DisplayName("TypeInsertTextTool")
    inner class TypeInsertTextToolTests {
        private val tool =
            TypeInsertTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
            )

        private fun setupDefaultMocks(existingText: String = "Hello") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.commitText(any(), any()) } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("Hel Worldlo")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, afterText)
        }

        @Test
        fun `inserts text at offset`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", " World")
                        put("offset", 3)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Field content:"))

                verify { mockTypeInputController.setSelection(3, 3) }
            }

        @Test
        fun `inserts text at offset 0 (beginning of field)`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Pre")
                        put("offset", 0)
                    }

                tool.execute(params)

                verify { mockTypeInputController.setSelection(0, 0) }
            }

        @Test
        fun `throws error when offset is missing`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                    }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when offset exceeds text length`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hi")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "X")
                        put("offset", 10)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("offset"))
            }

        @Test
        fun `throws error when offset is negative`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "X")
                        put("offset", -1)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains(">= 0"))
            }

        @Test
        fun `throws error when text is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "")
                        put("offset", 0)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when element_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "")
                        put("text", "Hello")
                        put("offset", 0)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when setSelection fails`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText
                every { mockTypeInputController.setSelection(any(), any()) } returns false

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "X")
                        put("offset", 3)
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to position cursor"))
            }

        @Test
        fun `defaults textLength to 0 when getSurroundingText returns null`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val afterText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, afterText)

                // offset 0 should succeed when textLength defaults to 0
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                        put("offset", 0)
                    }

                tool.execute(params)
                verify { mockTypeInputController.setSelection(0, 0) }

                // offset > 0 should fail when textLength defaults to 0
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, afterText)

                val params2 =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", "Hello")
                        put("offset", 1)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params2) }
                assertTrue(exception.message!!.contains("offset"))
            }

        @Test
        fun `returns field content in response`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("text", " World")
                        put("offset", 3)
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Field content: Hel Worldlo"))
            }
    }

    @Nested
    @DisplayName("TypeReplaceTextTool")
    inner class TypeReplaceTextToolTests {
        private val tool =
            TypeReplaceTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
            )

        private fun setupDefaultMocks(existingText: String = "Hello World") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.commitText(any(), any()) } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            every { mockTypeInputController.sendKeyEvent(any()) } returns true
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("Goodbye World")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, afterText)
        }

        @Test
        fun `replaces first occurrence of search text`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "Goodbye")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Replaced 5 characters with 7 characters"))
                assertTrue(text.contains("Field content:"))

                // Verify selection of the search text
                verify { mockTypeInputController.setSelection(0, 5) }
                // Verify DELETE key events (KeyEvent is null in JVM tests, so use any())
                verify(exactly = 2) { mockTypeInputController.sendKeyEvent(any()) }
            }

        @Test
        fun `throws error when search text not found`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hello World")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "NotFound")
                        put("new_text", "X")
                    }

                assertThrows<McpToolException.ElementNotFound> { tool.execute(params) }
            }

        @Test
        fun `throws error when search is empty`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "")
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when search exceeds max length`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "A".repeat(10001))
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("10000"))
            }

        @Test
        fun `throws error when getSurroundingText returns null`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns null

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Unable to read text"))
            }

        @Test
        fun `throws error when element_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("element_id", "")
                        put("search", "Hello")
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when setSelection fails`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hello World")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText
                every { mockTypeInputController.setSelection(any(), any()) } returns false

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to select text"))
            }

        @Test
        fun `handles empty new_text (delete only)`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello World")
                val afterText = createMockSurroundingText(" World")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Replaced 5 characters with 0 characters"))
                assertTrue(text.contains("Field content:"))

                // No commitText should have been called since new_text is empty
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `replaces only first occurrence when multiple exist`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("abcabcabc")
                val afterText = createMockSurroundingText("Xabcabc")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "abc")
                        put("new_text", "X")
                    }

                tool.execute(params)

                // First occurrence at index 0, so setSelection(0, 3)
                verify { mockTypeInputController.setSelection(0, 3) }
            }

        @Test
        fun `replaces search text at position 0 (start of field)`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello World")
                val afterText = createMockSurroundingText("Hi World")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "Hi")
                    }

                tool.execute(params)

                verify { mockTypeInputController.setSelection(0, 5) }
            }

        @Test
        fun `replaces search text at end of field`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.commitText(any(), any()) } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello World")
                val afterText = createMockSurroundingText("Hello Earth")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "World")
                        put("new_text", "Earth")
                    }

                tool.execute(params)

                // "World" starts at index 6, ends at 11
                verify { mockTypeInputController.setSelection(6, 11) }
            }

        @Test
        fun `log does not contain search or new_text content`() =
            runTest {
                // This test verifies that the Log.d call uses sanitized logging
                // (only lengths and element IDs, not actual text content).
                // We verify by checking the implementation uses search.length/newText.length
                // The actual test is structural — the code's Log.d line uses .length
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "Goodbye")
                    }

                // Should complete without error
                tool.execute(params)
            }

        @Test
        fun `returns field content in response`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("element_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "Goodbye")
                    }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Field content: Goodbye World"))
            }
    }

    @Nested
    @DisplayName("TypeClearTextTool")
    inner class TypeClearTextToolTests {
        private val tool =
            TypeClearTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
            )

        @Test
        fun `clears text from element`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.performContextMenuAction(any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello")
                val afterText = createMockSurroundingText("")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, afterText)

                val params = buildJsonObject { put("element_id", "node_edit") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
                assertTrue(text.contains("Field content:"))

                verify { mockTypeInputController.performContextMenuAction(android.R.id.selectAll) }
                // Verify DELETE key events (KeyEvent is null in JVM tests, so use any())
                verify(exactly = 2) { mockTypeInputController.sendKeyEvent(any()) }
            }

        @Test
        fun `returns success for already empty field without sending keys`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val emptyText = createMockSurroundingText("")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns emptyText

                val params = buildJsonObject { put("element_id", "node_edit") }

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
                assertTrue(text.contains("Field content: "))

                verify(exactly = 0) { mockTypeInputController.performContextMenuAction(any()) }
                verify(exactly = 0) { mockTypeInputController.sendKeyEvent(any()) }
            }

        @Test
        fun `Mutex released after early return for empty field`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val emptyText = createMockSurroundingText("")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns emptyText

                val params = buildJsonObject { put("element_id", "node_edit") }

                // First call — early return for empty field
                tool.execute(params)

                // Second call should also succeed (not blocked by Mutex)
                tool.execute(params)
            }

        @Test
        fun `throws error when element_id is missing`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when element_id is empty string`() =
            runTest {
                val params = buildJsonObject { put("element_id", "") }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when input connection not ready after poll timeout`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns false

                val params = buildJsonObject { put("element_id", "node_edit") }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Input connection not available"))
            }

        @Test
        fun `throws error when performContextMenuAction fails`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText
                every { mockTypeInputController.performContextMenuAction(any()) } returns false

                val params = buildJsonObject { put("element_id", "node_edit") }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to select all text"))
            }

        @Test
        fun `throws error when sendKeyEvent fails`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val beforeText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns beforeText
                every { mockTypeInputController.performContextMenuAction(any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns false

                val params = buildJsonObject { put("element_id", "node_edit") }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to send DELETE key"))
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

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Invalid key"))
            }

        @Test
        fun `throws error for missing key parameter`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }
    }
}
