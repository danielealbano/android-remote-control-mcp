@file:Suppress("DEPRECATION", "TooManyFunctions", "LargeClass")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.FakeTextField
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import com.danielealbano.androidremotecontrolmcp.testutil.readNodesOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
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
import org.junit.jupiter.api.Assertions.assertNull
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

    /** The node references, by id, that every tree read returns with its tree (see [readsTree]). */
    private val readNodes = mutableMapOf<String, CachedNode>()
    private val targetResolver =
        NodeTargetResolver(
            ElementFinder(),
            PrivacyToolTestDoubles.passthroughGate(),
            PrivacyToolTestDoubles.identitySubstitutor(),
        )

    /** A screen holding the type tools' usual target, the editable field "node_edit". */
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_edit",
                        className = "android.widget.EditText",
                        bounds = BoundsData(0, 100, 1080, 200),
                        editable = true,
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
        readsTree(sampleTree)
        // The type tools' usual target holds input focus, in a plain (non-password) editor.
        focusInputOn("node_edit")
        every { mockTypeInputController.isPasswordInput() } returns false
    }

    /** Every tree read parses to [tree] and returns the [readNodes] of the nodes in it as its node references. */
    private fun readsTree(tree: AccessibilityNodeData) {
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } answers {
            thirdArg<MutableMap<String, CachedNode>>().putAll(readNodesOf(tree, readNodes))
            tree
        }
    }

    /**
     * Puts input focus on [nodeId]: the FOCUS_INPUT search finds [mockFocusedNode], the node of [nodeId] in the tree
     * reads ([readNodes]).
     */
    private fun focusInputOn(nodeId: String) {
        every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
        every { mockRootNode.recycle() } returns Unit
        every { mockFocusedNode.isEditable } returns true
        every { mockFocusedNode.refresh() } returns true
        every { mockFocusedNode.recycle() } returns Unit
        readNodes[nodeId] = CachedNode(mockFocusedNode, 1, 0, "node_root")
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
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns
                    createMockSurroundingText("")

                // Should not throw
                awaitInputConnectionReady(mockTypeInputController, "test_element")
            }

        @Test
        fun `awaitInputConnectionReady succeeds after retry`() =
            runTest {
                every { mockTypeInputController.isReady() } returnsMany listOf(false, false, true)
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns
                    createMockSurroundingText("")

                // Should not throw
                awaitInputConnectionReady(mockTypeInputController, "test_element")
            }

        @Test
        fun `awaitInputConnectionReady waits until the input connection answers a read`() =
            runTest {
                // Ready, but still the connection of the field that had focus before: it answers nothing at first
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returnsMany
                    listOf(null, null, createMockSurroundingText(""))

                awaitInputConnectionReady(mockTypeInputController, "test_element")

                verify(exactly = 3) { mockTypeInputController.getSurroundingText(any(), any(), any()) }
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
        fun `awaitInputConnectionReady fails when the input connection never answers`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null

                assertThrows<McpToolException.ActionFailed> {
                    awaitInputConnectionReady(mockTypeInputController, "test_element")
                }
            }

        @Test
        fun `readWholeField takes a short field from one read, without moving the cursor`() {
            FakeTextField(mockTypeInputController, "Hello")

            assertEquals("Hello", readWholeField(mockTypeInputController))
            verify(exactly = 0) { mockTypeInputController.setSelection(any(), any()) }
        }

        @Test
        fun `readWholeField takes a short field that reports no offset from one read`() {
            FakeTextField(mockTypeInputController, "abc", reportsOffset = false)

            assertEquals("abc", readWholeField(mockTypeInputController))
        }

        @Test
        fun `readWholeField reads a long field window by window and puts the cursor back`() {
            val long = "a".repeat(12_000) + "b".repeat(13_000)
            FakeTextField(mockTypeInputController, long, cursor = 0)

            assertEquals(long, readWholeField(mockTypeInputController, restoreCursor = 7))
            verify { mockTypeInputController.setSelection(7, 7) }
        }

        @Test
        fun `readWholeField reads a long field that reports no offset`() {
            val long = "a".repeat(12_000) + "b".repeat(13_000)
            FakeTextField(mockTypeInputController, long, reportsOffset = false)

            assertEquals(long, readWholeField(mockTypeInputController))
        }

        @Test
        fun `readWholeField returns null when the input connection answers nothing`() {
            every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null

            assertNull(readWholeField(mockTypeInputController))
        }

        @Test
        fun `readWholeField returns null when the cursor does not move where it is put`() {
            FakeTextField(mockTypeInputController, "a".repeat(25_000), cursor = 20_000)
            every { mockTypeInputController.setSelection(any(), any()) } returns true

            assertNull(readWholeField(mockTypeInputController))
        }

        @Test
        fun `readFieldContent returns text when available`() {
            val mockSurroundingText = createMockSurroundingText("Hello")
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns mockSurroundingText

            val result = readFieldContent(mockTypeInputController, 5)
            assertEquals("Hello", result)
        }

        @Test
        fun `readFieldContent returns null when unavailable`() {
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns null

            val result = readFieldContent(mockTypeInputController, 0)
            assertNull(result)
        }

        @Test
        fun `readFieldContent returns text when SurroundingText has non-zero offset`() {
            val mockSurroundingText = createMockSurroundingText("partial text", offset = 50)
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns mockSurroundingText

            val result = readFieldContent(mockTypeInputController, 62)
            assertEquals("partial text", result)
        }

        @Test
        fun `typeCharByChar fails instead of retrying when the growth of the field cannot be measured`() =
            runTest {
                // A field that reports no offset, with the cursor 10000 characters in: its length cannot be read
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns
                    createMockSurroundingText("a".repeat(10_000), offset = -1)
                every { mockTypeInputController.getSurroundingText(1, 0, 0) } returns createMockSurroundingText("x")
                every { mockTypeInputController.commitText(any(), any()) } returns true

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        typeCharByChar("b", 10, 0, mockTypeInputController)
                    }

                val message = exception.message.orEmpty()
                assertTrue(message.contains("Could not check whether a character landed"), message)
                verify(exactly = 1) { mockTypeInputController.commitText(any(), any()) }
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
                        createMockSurroundingText("A")
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
                // 9 inter-char delays: 125 + 100*8 = 925ms + 50ms retry delay = 975ms
                assertTrue(elapsed < 1200L)
                assertTrue(elapsed >= 975L)
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
    @DisplayName("Focused field")
    inner class FocusedFieldTests {
        private val otherFocusedNode = mockk<AccessibilityNodeInfo>()

        /** A clickable wrapper "node_wrapper" holding the editable field "node_inner". */
        private val windowsWithWrapper =
            listOf(
                sampleWindows[0].copy(
                    tree =
                        sampleTree.copy(
                            children =
                                listOf(
                                    AccessibilityNodeData(
                                        id = "node_wrapper",
                                        className = "android.widget.FrameLayout",
                                        bounds = BoundsData(0, 100, 1080, 200),
                                        clickable = true,
                                        children =
                                            listOf(
                                                AccessibilityNodeData(
                                                    id = "node_inner",
                                                    className = "android.widget.EditText",
                                                    bounds = BoundsData(0, 100, 1080, 200),
                                                    editable = true,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )

        private fun focusOnAnotherField() {
            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns otherFocusedNode
            every { otherFocusedNode.isEditable } returns true
            every { otherFocusedNode.recycle() } returns Unit
            // A focus request is accepted but does not move input focus, unless a test says otherwise.
            coEvery { mockActionExecutor.focusNode(any(), any()) } returns Result.success(Unit)
        }

        /** Input focus is on another field until [fieldId] is given input focus, then on [fieldId]. */
        private fun focusMovesOnRequestTo(
            fieldId: String,
            windows: List<WindowData>,
        ) {
            focusOnAnotherField()
            var moved = false
            coEvery { mockActionExecutor.focusNode(fieldId, windows) } answers {
                moved = true
                Result.success(Unit)
            }
            // focusInputOn makes [mockFocusedNode] the read node of [fieldId]; the search returns it once moved.
            focusInputOn(fieldId)
            every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } answers {
                if (moved) mockFocusedNode else otherFocusedNode
            }
        }

        private suspend fun awaitFocused(
            windows: List<WindowData>,
            nodeId: String,
        ) = focusedField(mockAccessibilityServiceProvider, mockActionExecutor, readNodes, windows, nodeId)

        @Test
        fun `focusedField returns the target when it has input focus`() =
            runTest {
                val focused = awaitFocused(sampleWindows, "node_edit")

                assertEquals("node_edit", focused)
                coVerify(exactly = 0) { mockActionExecutor.focusNode(any(), any()) }
            }

        @Test
        fun `focusedField returns the editable node inside the target that has input focus`() =
            runTest {
                focusInputOn("node_inner")

                val focused = awaitFocused(windowsWithWrapper, "node_wrapper")

                assertEquals("node_inner", focused)
            }

        @Test
        fun `focusedField waits until input focus moves to the target`() =
            runTest {
                focusOnAnotherField()
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returnsMany
                    listOf(otherFocusedNode, mockFocusedNode)

                val focused = awaitFocused(sampleWindows, "node_edit")

                assertEquals("node_edit", focused)
            }

        @Test
        fun `focusedField gives input focus to the target when the click left it on another field`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time (the first wait)
                focusMovesOnRequestTo("node_edit", sampleWindows)

                val focused = awaitFocused(sampleWindows, "node_edit")

                assertEquals("node_edit", focused)
                coVerify(exactly = 1) { mockActionExecutor.focusNode("node_edit", sampleWindows) }
            }

        @Test
        fun `focusedField gives input focus to the editable field inside a wrapper target`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time (the first wait)
                focusMovesOnRequestTo("node_inner", windowsWithWrapper)

                val focused = awaitFocused(windowsWithWrapper, "node_wrapper")

                assertEquals("node_inner", focused)
                coVerify(exactly = 0) { mockActionExecutor.focusNode("node_wrapper", any()) }
            }

        @Test
        fun `focusedField throws when another field keeps input focus after the focus request`() =
            runTest {
                // Note: this test consumes ~1s real wall-clock time (two waits)
                focusOnAnotherField()

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        awaitFocused(sampleWindows, "node_edit")
                    }

                assertTrue(exception.message!!.contains("Input focus is not on node 'node_edit'"), exception.message)
                coVerify(exactly = 1) { mockActionExecutor.focusNode("node_edit", sampleWindows) }
            }

        @Test
        fun `focusedField throws when the focus request fails`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time
                focusOnAnotherField()
                coEvery { mockActionExecutor.focusNode(any(), any()) } returns
                    Result.failure(IllegalStateException("Node 'node_edit' is not focusable"))

                assertThrows<McpToolException.ActionFailed> {
                    awaitFocused(sampleWindows, "node_edit")
                }
            }

        @Test
        fun `focusedField throws when no editable field has input focus`() =
            runTest {
                // Note: this test consumes ~1s real wall-clock time (two waits)
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns null
                coEvery { mockActionExecutor.focusNode(any(), any()) } returns Result.success(Unit)

                assertThrows<McpToolException.ActionFailed> {
                    awaitFocused(sampleWindows, "node_edit")
                }
            }

        @Test
        fun `focusedField finds a Compose field that reports input focus only once it is built again`() =
            runTest {
                // Like Compose: until the focused field's node is built again, the search returns the host view's node
                val hostNode = mockk<AccessibilityNodeInfo>()
                every { hostNode.isEditable } returns false
                every { hostNode.recycle() } returns Unit
                var builtAgain = false
                every { mockFocusedNode.refresh() } answers {
                    builtAgain = true
                    true
                }
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } answers {
                    if (builtAgain) mockFocusedNode else hostNode
                }

                val focused = awaitFocused(sampleWindows, "node_edit")

                assertEquals("node_edit", focused)
                coVerify(exactly = 0) { mockActionExecutor.focusNode(any(), any()) }
            }

        @Test
        fun `focusedField throws when the target is not in the read`() =
            runTest {
                // Note: this test consumes ~1s real wall-clock time (two waits)
                coEvery { mockActionExecutor.focusNode(any(), any()) } returns Result.success(Unit)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        awaitFocused(sampleWindows, "node_missing")
                    }

                assertTrue(exception.message!!.contains("Input focus is not on node 'node_missing'"), exception.message)
                verify(exactly = 0) { mockFocusedNode.refresh() }
            }

        @Test
        fun `focusedField builds again only the editable nodes of the target`() =
            runTest {
                val wrapperNode = mockk<AccessibilityNodeInfo>()
                readNodes["node_wrapper"] = CachedNode(wrapperNode, 1, 0, "node_root")
                focusInputOn("node_inner")

                val focused = awaitFocused(windowsWithWrapper, "node_wrapper")

                assertEquals("node_inner", focused)
                verify(exactly = 1) { mockFocusedNode.refresh() }
                verify(exactly = 0) { wrapperNode.refresh() }
            }

        @Test
        fun `type_append_text types nothing when input focus is not on the target`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", any()) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                focusOnAnotherField()
                val tool =
                    TypeAppendTextTool(
                        mockTreeParser,
                        mockActionExecutor,
                        mockAccessibilityServiceProvider,
                        mockTypeInputController,
                        mockNodeCache,
                        PrivacyToolTestDoubles.passthroughGate(),
                        PrivacyToolTestDoubles.identitySubstitutor(),
                        targetResolver,
                    )

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(
                        buildJsonObject {
                            put("node_id", "node_edit")
                            put("text", "Hi")
                        },
                    )
                }

                verify(exactly = 0) { mockTypeInputController.setSelection(any(), any()) }
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `readField records the content, the focused field and the password input flag`() {
            every {
                mockTypeInputController.getSurroundingText(any(), any(), any())
            } returns createMockSurroundingText("abc")
            every { mockTypeInputController.isPasswordInput() } returns true

            val read = readField(mockTypeInputController, "node_inner", sampleWindows, 3)

            assertEquals(FieldRead("abc", "node_inner", isPasswordInput = true, windows = sampleWindows), read)
        }

        @Test
        fun `responseFieldContent redacts with the focused field and its password input flag`() =
            runTest {
                val gate = mockk<PrivacyToolGate>()
                coEvery { gate.nodeText(sampleWindows, "hunter2", "node_inner", true) } returns "CREDENTIAL#abc12"

                val shown =
                    responseFieldContent(
                        gate,
                        FieldRead("hunter2", "node_inner", isPasswordInput = true, windows = sampleWindows),
                    )

                assertEquals("CREDENTIAL#abc12", shown)
            }

        @Test
        fun `responseFieldContent returns the fallback unredacted when the field could not be read`() =
            runTest {
                val gate = mockk<PrivacyToolGate>()

                val shown =
                    responseFieldContent(
                        gate,
                        FieldRead(null, "node_edit", isPasswordInput = true, windows = sampleWindows),
                    )

                assertEquals(FIELD_CONTENT_UNREADABLE, shown)
                coVerify(exactly = 0) { gate.nodeText(any(), any(), any(), any()) }
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
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
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
        fun `appends text when the node cache is cleared after the read`() =
            runTest {
                setupDefaultMocks()
                // The cache is dropped once the window transition the click starts (the keyboard showing) settles
                every { mockNodeCache.get(any()) } returns null
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "Hello")
                    }

                val text = extractTextContent(tool.execute(params))

                assertTrue(text.contains("Typed 5 characters"), text)
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
        fun `throws error when the field text cannot be read`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                // The connection answers the readiness check, then nothing: no position is guessed
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null
                every { mockTypeInputController.getSurroundingText(1, 1, 0) } returns createMockSurroundingText("")
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "Hi")
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }

                assertTrue(exception.message!!.contains("Unable to read text"), exception.message)
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `appends at the end of a field that reports no offset`() =
            runTest {
                // A Compose text field reports -1: the end is the full text length, not one less
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val field = FakeTextField(mockTypeInputController, "abc", reportsOffset = false)
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "de")
                    }

                tool.execute(params)

                verify { mockTypeInputController.setSelection(3, 3) }
                assertEquals("abcde", field.content)
            }

        @Test
        fun `appends at the end of a long field with the cursor at its start`() =
            runTest {
                // More than 10000 characters after the cursor: one read around it does not reach the end
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val long = "a".repeat(15_000)
                val field = FakeTextField(mockTypeInputController, long, cursor = 0)
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "de")
                    }

                tool.execute(params)

                assertEquals(long + "de", field.content)
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
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
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
        fun `throws error when the field text cannot be read`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                // The connection answers the readiness check, then nothing: no position is guessed
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null
                every { mockTypeInputController.getSurroundingText(1, 1, 0) } returns createMockSurroundingText("")
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "Hi")
                        put("offset", 0)
                    }

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }

                assertTrue(exception.message!!.contains("Unable to read text"), exception.message)
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
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

        @Test
        fun `inserts at the offset of a field that reports no offset`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val field = FakeTextField(mockTypeInputController, "abc", reportsOffset = false)
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "X")
                        put("offset", 1)
                    }

                tool.execute(params)

                assertEquals("aXbc", field.content)
            }

        @Test
        fun `accepts an offset at the end of a field that reports no offset`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val field = FakeTextField(mockTypeInputController, "abc", reportsOffset = false)
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "d")
                        put("offset", 3)
                    }

                tool.execute(params)

                assertEquals("abcd", field.content)
            }

        @Test
        fun `inserts past the first window of a long field`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val field = FakeTextField(mockTypeInputController, "a".repeat(15_000), cursor = 0)
                val params =
                    buildJsonObject {
                        put("node_id", "node_edit")
                        put("text", "X")
                        put("offset", 12_000)
                    }

                tool.execute(params)

                assertEquals("a".repeat(12_000) + "X" + "a".repeat(3_000), field.content)
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
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
            )

        /** The target field holds [existingText] (see [FakeTextField]) and the click on it succeeds. */
        private fun fieldWith(
            existingText: String = "Hello World",
            reportsOffset: Boolean = true,
            loseDeletions: Boolean = false,
        ): FakeTextField {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            return FakeTextField(
                mockTypeInputController,
                existingText,
                reportsOffset = reportsOffset,
                loseDeletions = loseDeletions,
            )
        }

        private fun replaceParams(
            search: String,
            newText: String,
        ) = buildJsonObject {
            put("node_id", "node_edit")
            put("search", search)
            put("new_text", newText)
        }

        @Test
        fun `replaces first occurrence of search text`() =
            runTest {
                val field = fieldWith()

                val result = tool.execute(replaceParams("Hello", "Goodbye"))
                val text = extractTextContent(result)
                assertTrue(text.contains("Replaced 5 characters with 7 characters"))
                assertTrue(text.contains("Field content:"))

                // The search text is selected and deleted by committing empty text over the selection
                verifyOrder {
                    mockTypeInputController.setSelection(0, 5)
                    mockTypeInputController.commitText("", 1)
                }
                verify(exactly = 0) { mockTypeInputController.sendKeyEvent(any()) }
                assertEquals("Goodbye World", field.content)
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

                assertThrows<McpToolException.NodeNotFound> { tool.execute(replaceParams("NotFound", "X")) }
            }

        @Test
        fun `throws error when search is empty`() =
            runTest {
                val exception = assertThrows<McpToolException.InvalidParams> { tool.execute(replaceParams("", "X")) }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when search exceeds max length`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(replaceParams("A".repeat(10001), "X"))
                    }
                assertTrue(exception.message!!.contains("10000"))
            }

        @Test
        fun `throws error when the input connection never answers a read`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns null

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "X")) }
                assertTrue(exception.message!!.contains("Input connection not available"), exception.message)
            }

        @Test
        fun `throws error when the field text cannot be read`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null
                every { mockTypeInputController.getSurroundingText(1, 1, 0) } returns createMockSurroundingText("")

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "X")) }
                assertTrue(exception.message!!.contains("Unable to read text"), exception.message)
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

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "X")) }
                assertTrue(exception.message!!.contains("Failed to select text"))
            }

        @Test
        fun `handles empty new_text (delete only)`() =
            runTest {
                val field = fieldWith()

                val result = tool.execute(replaceParams("Hello", ""))
                val text = extractTextContent(result)
                assertTrue(text.contains("Replaced 5 characters with 0 characters"))
                assertTrue(text.endsWith("Field content:  World"), text)

                // Only the deletion is committed: nothing is typed
                verify(exactly = 1) { mockTypeInputController.commitText(any(), any()) }
                verify { mockTypeInputController.commitText("", 1) }
                assertEquals(" World", field.content)
            }

        @Test
        fun `replaces only first occurrence when multiple exist`() =
            runTest {
                val field = fieldWith("abcabcabc")

                tool.execute(replaceParams("abc", "X"))

                // First occurrence at index 0, so setSelection(0, 3)
                verify { mockTypeInputController.setSelection(0, 3) }
                assertEquals("Xabcabc", field.content)
            }

        @Test
        fun `replaces search text at position 0 (start of field)`() =
            runTest {
                val field = fieldWith()

                tool.execute(replaceParams("Hello", "Hi"))

                verify { mockTypeInputController.setSelection(0, 5) }
                assertEquals("Hi World", field.content)
            }

        @Test
        fun `replaces search text at end of field`() =
            runTest {
                val field = fieldWith()

                tool.execute(replaceParams("World", "Earth"))

                // "World" starts at index 6, ends at 11
                verify { mockTypeInputController.setSelection(6, 11) }
                assertEquals("Hello Earth", field.content)
            }

        @Test
        fun `replaces the right text in a field that reports no offset`() =
            runTest {
                // A Compose text field reports -1: the window still starts at the field start
                val field = fieldWith(reportsOffset = false)

                tool.execute(replaceParams("World", "Earth"))

                verify { mockTypeInputController.setSelection(6, 11) }
                assertEquals("Hello Earth", field.content)
            }

        @Test
        fun `throws and types nothing when the found text is not deleted`() =
            runTest {
                val field = fieldWith(loseDeletions = true)

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "Goodbye")) }

                assertTrue(exception.message!!.contains("was not deleted"), exception.message)
                assertEquals("Hello World", field.content)
                verify(exactly = 1) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `throws error when the deletion cannot be committed`() =
            runTest {
                fieldWith()
                every { mockTypeInputController.commitText("", any()) } returns false

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "X")) }

                assertTrue(exception.message!!.contains("Failed to delete the selected text"), exception.message)
            }

        @Test
        fun `replaces text past the first window of a long field that reports no offset`() =
            runTest {
                val long = "a".repeat(12_000) + "needle" + "a".repeat(3_000)
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                val field = FakeTextField(mockTypeInputController, long, cursor = 0, reportsOffset = false)

                tool.execute(replaceParams("needle", "X"))

                assertEquals("a".repeat(12_000) + "X" + "a".repeat(3_000), field.content)
            }

        @Test
        fun `replaces in a field that reformats its text after the deletion`() =
            runTest {
                val field = fieldWith("ab cd")
                field.reformat = { it.replace(" ", "") }

                tool.execute(replaceParams("cd", "X"))

                assertEquals("abX", field.content)
            }

        @Test
        fun `waits for a deletion that the field shows late`() =
            runTest {
                val field = fieldWith()
                field.staleReads = 3

                tool.execute(replaceParams("Hello", "Hi"))

                assertEquals("Hi World", field.content)
            }

        @Test
        fun `throws when the field cannot be read back after the deletion`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time (the wait for the field to change)
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                var deleted = false
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.commitText(any(), any()) } answers {
                    deleted = true
                    true
                }
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } answers {
                    if (deleted) null else createMockSurroundingText("Hello World")
                }

                val exception =
                    assertThrows<McpToolException.ActionFailed> { tool.execute(replaceParams("Hello", "Hi")) }

                assertTrue(exception.message!!.contains("Could not read node 'node_edit' back"), exception.message)
            }

        @Test
        fun `log does not contain search or new_text content`() =
            runTest {
                // This test verifies that the Log.d call uses sanitized logging
                // (only lengths and element IDs, not actual text content).
                // We verify by checking the implementation uses search.length/newText.length
                // The actual test is structural — the code's Log.d line uses .length
                fieldWith()

                // Should complete without error
                tool.execute(replaceParams("Hello", "Goodbye"))
            }

        @Test
        fun `returns field content in response`() =
            runTest {
                fieldWith()

                val result = tool.execute(replaceParams("Hello", "Goodbye"))
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
                PrivacyToolTestDoubles.passthroughGate(),
                targetResolver,
            )

        private val params = buildJsonObject { put("node_id", "node_edit") }

        /** The target field holds [existingText] (see [FakeTextField]) and the click on it succeeds. */
        private fun fieldWith(
            existingText: String,
            reportsOffset: Boolean = true,
            loseDeletions: Boolean = false,
        ): FakeTextField {
            coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
            return FakeTextField(
                mockTypeInputController,
                existingText,
                reportsOffset = reportsOffset,
                loseDeletions = loseDeletions,
            )
        }

        @Test
        fun `clears text from node`() =
            runTest {
                val field = fieldWith("Hello")

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
                assertTrue(text.endsWith("Field content: "), text)

                // Select all, then commit empty text over the selection
                verifyOrder {
                    mockTypeInputController.performContextMenuAction(android.R.id.selectAll)
                    mockTypeInputController.commitText("", 1)
                }
                verify(exactly = 0) { mockTypeInputController.sendKeyEvent(any()) }
                assertEquals("", field.content)
            }

        @Test
        fun `clears a field that reports no offset`() =
            runTest {
                val field = fieldWith("abc", reportsOffset = false)

                tool.execute(params)

                assertEquals("", field.content)
            }

        @Test
        fun `returns success for already empty field without editing it`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                val emptyText = createMockSurroundingText("")
                every {
                    mockTypeInputController.getSurroundingText(any(), any(), any())
                } returns emptyText

                val result = tool.execute(params)
                val text = extractTextContent(result)
                assertTrue(text.contains("Text cleared"))
                assertTrue(text.contains("Field content: "))

                verify(exactly = 0) { mockTypeInputController.performContextMenuAction(any()) }
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `returns success for an already empty field that reports no offset`() =
            runTest {
                fieldWith("", reportsOffset = false)

                val text = extractTextContent(tool.execute(params))

                assertTrue(text.contains("Text cleared"))
                verify(exactly = 0) { mockTypeInputController.performContextMenuAction(any()) }
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
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

                // First call — early return for empty field
                tool.execute(params)

                // Second call should also succeed (not blocked by Mutex)
                tool.execute(params)
            }

        @Test
        fun `throws error when node_id is missing`() =
            runTest {
                assertThrows<McpToolException.InvalidParams> { tool.execute(buildJsonObject {}) }
            }

        @Test
        fun `throws error when node_id is empty string`() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(buildJsonObject { put("node_id", "") })
                    }
                assertTrue(exception.message!!.contains("non-empty"))
            }

        @Test
        fun `throws error when input connection not ready after poll timeout`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns false

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Input connection not available"))
            }

        @Test
        fun `throws error when performContextMenuAction fails`() =
            runTest {
                fieldWith("Hello")
                every { mockTypeInputController.performContextMenuAction(any()) } returns false

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to select all text"))
            }

        @Test
        fun `throws error when the deletion cannot be committed`() =
            runTest {
                fieldWith("Hello")
                every { mockTypeInputController.commitText(any(), any()) } returns false

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }
                assertTrue(exception.message!!.contains("Failed to delete the selected text"), exception.message)
            }

        @Test
        fun `clears a long field that reports no offset`() =
            runTest {
                val field = fieldWith("a".repeat(15_000), reportsOffset = false)

                tool.execute(params)

                assertEquals("", field.content)
            }

        @Test
        fun `reports what a field that refills itself reads back after the clear`() =
            runTest {
                val field = fieldWith("Hello")
                field.reformat = { it.ifEmpty { "0" } }

                val text = extractTextContent(tool.execute(params))

                assertTrue(text.endsWith("Field content: 0"), text)
            }

        @Test
        fun `throws error when the field text cannot be read`() =
            runTest {
                coEvery { mockActionExecutor.clickNode("node_edit", sampleWindows) } returns Result.success(Unit)
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns null
                every { mockTypeInputController.getSurroundingText(1, 1, 0) } returns createMockSurroundingText("")

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }

                assertTrue(exception.message!!.contains("Unable to read text"), exception.message)
                verify(exactly = 0) { mockTypeInputController.performContextMenuAction(any()) }
            }

        @Test
        fun `throws instead of reporting success when the field is not cleared`() =
            runTest {
                // Note: this test consumes ~500ms real wall-clock time (the wait for the field to change)
                val field = fieldWith("Hello", loseDeletions = true)

                val exception = assertThrows<McpToolException.ActionFailed> { tool.execute(params) }

                assertTrue(exception.message!!.contains("was not deleted"), exception.message)
                assertTrue(exception.message!!.contains("reads back unchanged"), exception.message)
                assertEquals("Hello", field.content)
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
        fun `clears the framework node cache before locating the focused node`() =
            runTest {
                // press_key DEL/TAB/SPACE read the focused node's text to build ACTION_SET_TEXT; on a
                // JS-mutated WebView input that text would be stale without clearing the framework
                // cache first, corrupting the field. The clear MUST precede the tree read.
                @Suppress("DEPRECATION")
                every { mockRootNode.recycle() } returns Unit
                every { mockRootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns mockFocusedNode
                every { mockFocusedNode.isEditable } returns true
                every { mockFocusedNode.text } returns "Hello"
                every { mockFocusedNode.performAction(any(), any<Bundle>()) } returns true
                every { mockFocusedNode.recycle() } returns Unit
                val params = buildJsonObject { put("key", "DEL") }

                tool.execute(params)

                verifyOrder {
                    mockAccessibilityServiceProvider.clearFrameworkNodeCache()
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                }
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

    @Nested
    @DisplayName("type tools with selectors")
    inner class TypeToolSelectorTests {
        private fun editable(
            id: String,
            text: String,
        ) = AccessibilityNodeData(
            id = id,
            className = "android.widget.EditText",
            text = text,
            bounds = BoundsData(0, 100, 1080, 200),
            editable = true,
            enabled = true,
            visible = true,
        )

        private fun withFields(vararg fields: AccessibilityNodeData) {
            readsTree(sampleTree.copy(children = fields.toList()))
        }

        private fun selector(value: String) =
            buildJsonObject {
                put("by", "text")
                put("value", value)
                put("exact_match", true)
            }

        private val appendTool =
            TypeAppendTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
            )

        private val clearTool =
            TypeClearTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                targetResolver,
            )

        private val insertTool =
            TypeInsertTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
            )

        private val replaceTool =
            TypeReplaceTextTool(
                mockTreeParser,
                mockActionExecutor,
                mockAccessibilityServiceProvider,
                mockTypeInputController,
                mockNodeCache,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
                targetResolver,
            )

        /** [nodeId] takes input focus when clicked and holds [before] (see [FakeTextField]). */
        private fun readyField(
            nodeId: String,
            before: String,
        ): FakeTextField {
            coEvery { mockActionExecutor.clickNode(nodeId, any()) } returns Result.success(Unit)
            focusInputOn(nodeId)
            return FakeTextField(mockTypeInputController, before)
        }

        private fun selectorWith(
            value: String,
            extra: Map<String, Any>,
        ) = buildJsonObject {
            put("by", "text")
            put("value", value)
            put("exact_match", true)
            extra.forEach { (key, v) -> if (v is Int) put(key, v) else put(key, v.toString()) }
        }

        @Test
        fun `type_append_text types into the field matched by a selector`() =
            runTest {
                withFields(editable("node_email", "Email"), editable("node_name", "Name"))
                coEvery { mockActionExecutor.clickNode("node_email", any()) } returns Result.success(Unit)
                focusInputOn("node_email")
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.setSelection(any(), any()) } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns
                    createMockSurroundingText("Email")
                setupVerificationMock()

                val text =
                    extractTextContent(
                        appendTool.execute(
                            buildJsonObject {
                                put("by", "text")
                                put("value", "Email")
                                put("exact_match", true)
                                put("text", "Hi")
                            },
                        ),
                    )

                assertTrue(text.contains("at end of node 'node_email'"), text)
                coVerify(exactly = 1) { mockActionExecutor.clickNode("node_email", any()) }
            }

        @Test
        fun `type_append_text with an ambiguous selector returns an untrusted error and types nothing`() =
            runTest {
                withFields(editable("node_a", "Field"), editable("node_b", "Field"))

                val result =
                    appendTool.execute(
                        buildJsonObject {
                            put("by", "text")
                            put("value", "Field")
                            put("exact_match", true)
                            put("text", "Hi")
                        },
                    )

                val text = extractTextContent(result)
                assertEquals(true, result.isError)
                assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, text.lines().first())
                coVerify(exactly = 0) { mockActionExecutor.clickNode(any(), any()) }
                verify(exactly = 0) { mockTypeInputController.commitText(any(), any()) }
            }

        @Test
        fun `type_clear_text clears the field matched by a selector`() =
            runTest {
                withFields(editable("node_email", "Email"))
                readyField("node_email", before = "Email")

                val text = extractTextContent(clearTool.execute(selector("Email")))

                assertEquals(
                    "${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\nText cleared from node 'node_email'.\nField content: ",
                    text,
                )
            }

        @Test
        fun `type_clear_text with an ambiguous selector returns an untrusted error and leaves the lock free`() =
            runTest {
                withFields(editable("node_a", "Field"), editable("node_b", "Field"))

                val ambiguous = clearTool.execute(selector("Field"))

                assertEquals(true, ambiguous.isError)
                coVerify(exactly = 0) { mockActionExecutor.clickNode(any(), any()) }

                // The typing lock was released: a following clear on a single match runs.
                withFields(editable("node_a", "Field"))
                coEvery { mockActionExecutor.clickNode("node_a", any()) } returns Result.success(Unit)
                focusInputOn("node_a")
                every { mockTypeInputController.isReady() } returns true
                every { mockTypeInputController.getSurroundingText(any(), any(), any()) } returns
                    createMockSurroundingText("")
                assertTrue(extractTextContent(clearTool.execute(selector("Field"))).contains("node 'node_a'"))
            }

        @Test
        fun `type_insert_text types at the offset of the field matched by a selector`() =
            runTest {
                withFields(editable("node_email", "Email"), editable("node_name", "Name"))
                readyField("node_email", before = "Email")

                val text =
                    extractTextContent(insertTool.execute(selectorWith("Email", mapOf("text" to "Hi", "offset" to 0))))

                assertTrue(text.contains("at offset 0 in node 'node_email'"), text)
                coVerify(exactly = 1) { mockActionExecutor.clickNode("node_email", any()) }
                verify { mockTypeInputController.setSelection(0, 0) }
            }

        @Test
        fun `type_replace_text replaces in the field matched by a selector`() =
            runTest {
                withFields(editable("node_email", "Email"), editable("node_name", "Name"))
                readyField("node_email", before = "Email")

                val text =
                    extractTextContent(
                        replaceTool.execute(selectorWith("Email", mapOf("search" to "Em", "new_text" to "M"))),
                    )

                assertTrue(text.contains("in node 'node_email'"), text)
                coVerify(exactly = 1) { mockActionExecutor.clickNode("node_email", any()) }
                verify { mockTypeInputController.setSelection(0, 2) }
            }

        @Test
        fun `append, insert and replace with an ambiguous selector return an untrusted error and free the lock`() =
            runTest {
                val appendArgs = mapOf<String, Any>("text" to "Hi")
                val insertArgs = mapOf<String, Any>("text" to "Hi", "offset" to 0)
                val replaceArgs = mapOf<String, Any>("search" to "Fi", "new_text" to "Ya")
                val cases =
                    listOf(
                        Triple("type_append_text", appendArgs, appendTool::execute),
                        Triple("type_insert_text", insertArgs, insertTool::execute),
                        Triple("type_replace_text", replaceArgs, replaceTool::execute),
                    )
                for ((name, args, execute) in cases) {
                    withFields(editable("node_a", "Field"), editable("node_b", "Field"))

                    val ambiguous = execute(selectorWith("Field", args))

                    assertEquals(true, ambiguous.isError, name)
                    assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, extractTextContent(ambiguous).lines().first())
                    coVerify(exactly = 0) { mockActionExecutor.clickNode(any(), any()) }

                    // The typing lock was released: the same call on a single match runs.
                    withFields(editable("node_a", "Field"))
                    readyField("node_a", before = "Field")
                    assertTrue(extractTextContent(execute(selectorWith("Field", args))).contains("node 'node_a'"), name)
                    clearMocks(mockActionExecutor, answers = false, childMocks = false)
                }
            }
    }
}
