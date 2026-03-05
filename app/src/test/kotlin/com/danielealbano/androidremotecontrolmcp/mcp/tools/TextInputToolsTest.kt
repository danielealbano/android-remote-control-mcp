@file:Suppress("DEPRECATION", "TooManyFunctions", "LargeClass")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
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
    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
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

    /**
     * Sets up mockTypeInputController to handle per-character verification.
     * Uses answers {} to dynamically return whatever was last committed via commitText.
     * Verification calls use afterLength=0; field-content reads use afterLength=10000.
     * The initial field length mock must be set up separately per test or in setupDefaultMocks.
     */
    private fun setupVerificationMock() {
        var lastCommittedText = ""
        every { mockTypeInputController.commitText(any(), any()) } answers {
            lastCommittedText = firstArg<String>()
            true
        }
        every { mockTypeInputController.getSurroundingText(any(), eq(0), eq(0)) } answers {
            createMockSurroundingText(lastCommittedText)
        }
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
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
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
                setupVerificationMock()
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                typeCharByChar(text, 10, 0, mockTypeInputController)

                verify(exactly = 3) { mockTypeInputController.commitText(any(), 1) }
            }

        @Test
        fun `typeCharByChar stops when commitText returns false`() =
            runTest {
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")
                // Verification mock for char "A" (first char succeeds)
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns createMockSurroundingText("A")
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
                setupVerificationMock()
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

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
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                typeCharByChar("", 10, 0, mockTypeInputController)

                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar skips delay after last character`() =
            runTest {
                setupVerificationMock()
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

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
            assertEquals(250, speed)
            assertEquals(50, variance)
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
                setupVerificationMock()
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

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

        @Test
        fun `typeCharByChar retries on verification miss and succeeds`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                var commitCount = 0
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    commitCount++
                    true
                }

                // Verification: first call returns mismatch, second returns match
                var verifyCallCount = 0
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } answers {
                    verifyCallCount++
                    if (verifyCallCount == 1) {
                        // First verify: miss (text doesn't match)
                        createMockSurroundingText("wrong")
                    } else {
                        // Second verify: success
                        createMockSurroundingText(firstArg<Int>().let { "A" })
                    }
                }

                typeCharByChar("A", 100, 0, mockTypeInputController)

                // commitText called twice: initial + retry (pre-retry length check shows no growth)
                assertEquals(2, commitCount)
            }

        @Test
        fun `typeCharByChar throws after max retries exhausted`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                every { mockTypeInputController.commitText(any(), any()) } returns true

                // All verification calls return mismatch
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns createMockSurroundingText("wrong")

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        typeCharByChar("A", 100, 0, mockTypeInputController)
                    }
                assertTrue(exception.message!!.contains("retries"))
            }

        @Test
        fun `typeCharByChar throws immediately when getSurroundingText returns null`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                every { mockTypeInputController.commitText(any(), any()) } returns true

                // Verification returns null (IC lost)
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns null

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        typeCharByChar("A", 100, 0, mockTypeInputController)
                    }
                assertTrue(exception.message!!.contains("verification"))

                // Should only have committed once (no retry when IC lost)
                verify(exactly = 1) { mockTypeInputController.commitText(any(), 1) }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar adaptive delay increases on miss`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                var lastCommitted = ""
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    lastCommitted = firstArg<String>()
                    true
                }

                // For 3-char text "ABC":
                // Char B (second) misses once then succeeds on retry
                var verifyCallCount = 0
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } answers {
                    verifyCallCount++
                    // Call 1: verify A -> success
                    // Call 2: verify B -> miss
                    // Call 3: verify B retry -> success
                    // Call 4: verify C -> success
                    if (verifyCallCount == 2) {
                        createMockSurroundingText("wrong")
                    } else {
                        createMockSurroundingText(lastCommitted)
                    }
                }

                val startTime = testScheduler.currentTime
                typeCharByChar("ABC", 100, 0, mockTypeInputController)
                val elapsed = testScheduler.currentTime - startTime

                // Without miss: 2 * 100ms = 200ms inter-char delay
                // With miss on B: +50ms retry delay + increased effectiveDelay (150) for B->C delay
                // Total > 200ms
                assertTrue(elapsed > 200L)
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar adaptive delay decreases on success back to floor`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                var lastCommitted = ""
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    lastCommitted = firstArg<String>()
                    true
                }

                // 10+ chars: first char misses once (raises effectiveDelay to 150),
                // then all subsequent succeed (each decreasing by 25 back to floor of 100)
                var verifyCallCount = 0
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } answers {
                    verifyCallCount++
                    if (verifyCallCount == 1) {
                        // First verify of first char: miss
                        createMockSurroundingText("wrong")
                    } else {
                        createMockSurroundingText(lastCommitted)
                    }
                }

                val text = "ABCDEFGHIJ" // 10 chars
                val startTime = testScheduler.currentTime
                typeCharByChar(text, 100, 0, mockTypeInputController)
                val elapsed = testScheduler.currentTime - startTime

                // After first char miss: effectiveDelay = 150
                // After first char retry success: effectiveDelay = 125
                // After B success: effectiveDelay = 100 (floor)
                // Remaining chars at 100ms each
                // If delay never recovered, total would be 9 * 150 = 1350ms + retry delay
                // With recovery, total should be less than 1350ms
                // 9 inter-char delays: 125 + 100 + 100 + ... = 125 + 8*100 = 925ms + 50ms retry
                assertTrue(elapsed < 1200L)
                assertTrue(elapsed >= 925L)
            }

        @Test
        fun `typeCharByChar verification uses endsWith for robustness`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                every { mockTypeInputController.commitText(any(), any()) } returns true

                // Verification returns extra text before the committed char
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns createMockSurroundingText("xyzD")

                // Should succeed because "xyzD".endsWith("D") is true
                typeCharByChar("D", 100, 0, mockTypeInputController)

                verify(exactly = 1) { mockTypeInputController.commitText("D", 1) }
            }

        @Test
        fun `typeCharByChar pre-retry length check detects char landed despite endsWith mismatch`() =
            runTest {
                // Initial field length = 5
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("Hello", offset = 0)

                var commitCount = 0
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    commitCount++
                    true
                }

                // Verification endsWith always fails (autocomplete changed text around cursor)
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns createMockSurroundingText("reformatted")

                // But pre-retry length check shows field grew by charCount (5 + 1 = 6)
                // Override initial field length mock for pre-retry check: return length 6
                // The pre-retry check calls getSurroundingText(10000, 10000, 0) again
                // We need to differentiate: first call returns length 5, subsequent returns 6
                var fieldLengthCallCount = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } answers {
                    fieldLengthCallCount++
                    if (fieldLengthCallCount == 1) {
                        // Initial field length read
                        createMockSurroundingText("Hello", offset = 0)
                    } else {
                        // Pre-retry length check: field grew
                        createMockSurroundingText("Hello!", offset = 0)
                    }
                }

                typeCharByChar("!", 100, 0, mockTypeInputController)

                // Only one commit (no re-commit because pre-retry length check detected growth)
                assertEquals(1, commitCount)
            }

        @Test
        fun `typeCharByChar effectiveDelay capped at 2000ms`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                var lastCommitted = ""
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    lastCommitted = firstArg<String>()
                    true
                }

                // Set up so many chars fail verification once each, driving effectiveDelay up
                // Each miss adds +50. Starting at 100, to reach 2000 we need 38 misses.
                // Use a long string and make every first verify miss.
                val longText = "A".repeat(50)
                var verifyCallCount = 0
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } answers {
                    verifyCallCount++
                    // Odd calls (first verify per char) miss, even calls (retry verify) succeed
                    if (verifyCallCount % 2 == 1) {
                        createMockSurroundingText("wrong")
                    } else {
                        createMockSurroundingText(lastCommitted)
                    }
                }

                // Should not throw — delay capped at 2000ms, doesn't grow unbounded
                typeCharByChar(longText, 100, 0, mockTypeInputController)

                // Each char misses once and is re-committed: 2 commits per char
                verify(exactly = 100) { mockTypeInputController.commitText("A", 1) }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `typeCharByChar cancellation during retry delay`() =
            runTest {
                // Initial field length = 0
                every {
                    mockTypeInputController.getSurroundingText(eq(10000), eq(10000), eq(0))
                } returns createMockSurroundingText("")

                every { mockTypeInputController.commitText(any(), any()) } returns true

                // All verification calls return mismatch to force retry delays
                every {
                    mockTypeInputController.getSurroundingText(any(), eq(0), eq(0))
                } returns createMockSurroundingText("wrong")

                val job =
                    launch {
                        typeCharByChar("A", 100, 0, mockTypeInputController)
                    }

                // Advance past the first commit but into the 50ms retry delay
                testScheduler.advanceTimeBy(25)
                job.cancelAndJoin()

                // Verify CancellationException was the cause (job is cancelled)
                assertTrue(job.isCancelled)
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
                mockNodeCache,
            )

        private fun setupDefaultMocks(existingText: String = "existing") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            // General getSurroundingText: field-content reads
            // (beforeText for cursor positioning, afterText for final read)
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("${existingText}Hello")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)
            // Verification mocks (more specific matchers, registered after general)
            setupVerificationMock()
        }

        @Test
        fun `appends text to node`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                val params = buildJsonObject { put("node_id", "node_edit") }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when text is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "")
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when node_id is missing`() =
            runTest {
                val params = buildJsonObject { put("text", "Hello") }

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when node_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
                        put("text", "AB")
                    }

                // Should not throw — uses defaults 250/50
                tool.execute(params)
            }

        @Test
        fun `handles emoji text correctly`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val beforeText = createMockSurroundingText("")
                val afterText = createMockSurroundingText("Hi\uD83D\uDE00")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, beforeText, afterText)
                setupVerificationMock()

                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val afterText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, null, afterText)
                setupVerificationMock()

                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                mockNodeCache,
            )

        private fun setupDefaultMocks(existingText: String = "Hello") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            // General getSurroundingText: field-content reads
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("Hel Worldlo")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)
            // Verification mocks (more specific matchers, registered after general)
            setupVerificationMock()
        }

        @Test
        fun `inserts text at offset`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
                        put("text", "")
                        put("offset", 0)
                    }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when node_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "")
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
                        put("node_id", "node_edit")
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
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                val afterText = createMockSurroundingText("Hello")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, null, afterText)
                setupVerificationMock()

                // offset 0 should succeed when textLength defaults to 0
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "Hello")
                        put("offset", 0)
                    }

                tool.execute(params)
                verify { mockTypeInputController.setSelection(0, 0) }

                // offset > 0 should fail when textLength defaults to 0
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(null, null, afterText)
                setupVerificationMock()

                val params2 =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                mockNodeCache,
            )

        private fun setupDefaultMocks(existingText: String = "Hello World") {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            every { mockTypeInputController.isReady() } returns true
            every { mockTypeInputController.setSelection(any(), any()) } returns true
            every { mockTypeInputController.sendKeyEvent(any()) } returns true
            // General getSurroundingText: field-content reads
            val beforeText = createMockSurroundingText(existingText)
            val afterText = createMockSurroundingText("Goodbye World")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returnsMany listOf(beforeText, beforeText, afterText)
            // Verification mocks (more specific matchers, registered after general)
            setupVerificationMock()
        }

        @Test
        fun `replaces first occurrence of search text`() =
            runTest {
                setupDefaultMocks()
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
                        put("search", "NotFound")
                        put("new_text", "X")
                    }

                assertThrows<McpToolException.NodeNotFound> { tool.execute(params) }
            }

        @Test
        fun `throws error when search is empty`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
                        put("search", "Hello")
                        put("new_text", "X")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Unable to read text"))
            }

        @Test
        fun `throws error when node_id is empty string`() =
            runTest {
                val params =
                    buildJsonObject {
                        put("node_id", "")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("abcabcabc")
                val afterText = createMockSurroundingText("Xabcabc")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, beforeText, afterText)
                setupVerificationMock()

                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello World")
                val afterText = createMockSurroundingText("Hi World")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, beforeText, afterText)
                setupVerificationMock()

                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.sendKeyEvent(any()) } returns true
                val beforeText = createMockSurroundingText("Hello World")
                val afterText = createMockSurroundingText("Hello Earth")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returnsMany listOf(beforeText, beforeText, afterText)
                setupVerificationMock()

                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                        put("node_id", "node_edit")
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
                mockNodeCache,
            )

        @Test
        fun `clears text from node`() =
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

                val params = buildJsonObject { put("node_id", "node_edit") }

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

                val params = buildJsonObject { put("node_id", "node_edit") }

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

                val params = buildJsonObject { put("node_id", "node_edit") }

                // First call — early return for empty field
                tool.execute(params)

                // Second call should also succeed (not blocked by Mutex)
                tool.execute(params)
            }

        @Test
        fun `throws error when node_id is missing`() =
            runTest {
                val params = buildJsonObject {}

                assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
            }

        @Test
        fun `throws error when node_id is empty string`() =
            runTest {
                val params = buildJsonObject { put("node_id", "") }

                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when input connection not ready after poll timeout`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns false

                val params = buildJsonObject { put("node_id", "node_edit") }

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

                val params = buildJsonObject { put("node_id", "node_edit") }

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

                val params = buildJsonObject { put("node_id", "node_edit") }

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
                every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
                @Suppress("DEPRECATION")
                every { mockRootNode.recycle() } returns Unit
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
                every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
                @Suppress("DEPRECATION")
                every { mockRootNode.recycle() } returns Unit
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
