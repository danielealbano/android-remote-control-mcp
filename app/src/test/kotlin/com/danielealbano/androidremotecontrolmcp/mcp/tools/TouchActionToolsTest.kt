package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("TouchActionTools")
class TouchActionToolsTest {
    private lateinit var actionExecutor: ActionExecutor

    @BeforeEach
    fun setUp() {
        actionExecutor = mockk()
    }

    /**
     * Extracts the text content from a CallToolResult.
     */
    private fun extractTextContent(result: CallToolResult): String {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        return textContent.text
    }

    // ─────────────────────────────────────────────────────────────────────
    // TapTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TapTool")
    inner class TapToolTests {
        private lateinit var tool: TapTool

        @BeforeEach
        fun setUp() {
            tool = TapTool(actionExecutor)
        }

        @Test
        @DisplayName("tap with valid coordinates returns success")
        fun tapWithValidCoordinatesReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.tap(500f, 1000f) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Tap executed"))
                assertTrue(text.contains("500"))
                assertTrue(text.contains("1000"))
                coVerify(exactly = 1) { actionExecutor.tap(500f, 1000f) }
            }

        @Test
        @DisplayName("tap with float coordinates returns success")
        fun tapWithFloatCoordinatesReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.tap(500.5f, 1000.3f) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x", 500.5)
                        put("y", 1000.3)
                    }
                val result = tool.execute(params)

                extractTextContent(result)
                coVerify(exactly = 1) { actionExecutor.tap(500.5f, 1000.3f) }
            }

        @Test
        @DisplayName("tap with missing x returns invalid params")
        fun tapWithMissingXReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("y", 1000)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("x"))
            }

        @Test
        @DisplayName("tap with missing y returns invalid params")
        fun tapWithMissingYReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x", 500)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("y"))
            }

        @Test
        @DisplayName("tap with null params returns invalid params")
        fun tapWithNullParamsReturnsInvalidParams() =
            runTest {
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(null)
                    }
                assertTrue(exception.message!!.contains("x"))
            }

        @Test
        @DisplayName("tap with negative x returns invalid params")
        fun tapWithNegativeXReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x", -1)
                        put("y", 1000)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("x"))
                assertTrue(exception.message!!.contains(">= 0"))
            }

        @Test
        @DisplayName("tap with negative y returns invalid params")
        fun tapWithNegativeYReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", -5)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("y"))
            }

        @Test
        @DisplayName("tap when service not enabled returns permission denied")
        fun tapWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.tap(any(), any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("Accessibility service"))
            }

        @Test
        @DisplayName("tap when action fails returns action failed")
        fun tapWhenActionFailsReturnsActionFailed() =
            runTest {
                coEvery { actionExecutor.tap(any(), any()) } returns
                    Result.failure(
                        RuntimeException("Gesture cancelled"),
                    )

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("Gesture cancelled"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // LongPressTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LongPressTool")
    inner class LongPressToolTests {
        private lateinit var tool: LongPressTool

        @BeforeEach
        fun setUp() {
            tool = LongPressTool(actionExecutor)
        }

        @Test
        @DisplayName("long press with default duration returns success")
        fun longPressWithDefaultDurationReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.longPress(500f, 1000f, 1000L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Long press"))
                assertTrue(text.contains("1000ms"))
                coVerify(exactly = 1) { actionExecutor.longPress(500f, 1000f, 1000L) }
            }

        @Test
        @DisplayName("long press with custom duration returns success")
        fun longPressWithCustomDurationReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.longPress(500f, 1000f, 2000L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                        put("duration", 2000)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("2000ms"))
                coVerify(exactly = 1) { actionExecutor.longPress(500f, 1000f, 2000L) }
            }

        @Test
        @DisplayName("long press with zero duration returns invalid params")
        fun longPressWithZeroDurationReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                        put("duration", 0)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("duration"))
            }

        @Test
        @DisplayName("long press with duration exceeding max returns invalid params")
        fun longPressWithDurationExceedingMaxReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                        put("duration", 60001)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("duration"))
                assertTrue(exception.message!!.contains("60000"))
            }

        @Test
        @DisplayName("long press when service not enabled returns permission denied")
        fun longPressWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.longPress(any(), any(), any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }

                assertThrows<McpToolException.PermissionDenied> {
                    tool.execute(params)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DoubleTapTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DoubleTapTool")
    inner class DoubleTapToolTests {
        private lateinit var tool: DoubleTapTool

        @BeforeEach
        fun setUp() {
            tool = DoubleTapTool(actionExecutor)
        }

        @Test
        @DisplayName("double tap with valid coordinates returns success")
        fun doubleTapWithValidCoordinatesReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.doubleTap(500f, 1000f) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Double tap"))
                coVerify(exactly = 1) { actionExecutor.doubleTap(500f, 1000f) }
            }

        @Test
        @DisplayName("double tap with missing params returns invalid params")
        fun doubleTapWithMissingParamsReturnsInvalidParams() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(null)
                }
            }

        @Test
        @DisplayName("double tap when action fails returns action failed")
        fun doubleTapWhenActionFailsReturnsActionFailed() =
            runTest {
                coEvery { actionExecutor.doubleTap(any(), any()) } returns
                    Result.failure(
                        RuntimeException("Failed to dispatch gesture"),
                    )

                val params =
                    buildJsonObject {
                        put("x", 500)
                        put("y", 1000)
                    }

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(params)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SwipeTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SwipeTool")
    inner class SwipeToolTests {
        private lateinit var tool: SwipeTool

        @BeforeEach
        fun setUp() {
            tool = SwipeTool(actionExecutor)
        }

        @Test
        @DisplayName("swipe with valid coordinates returns success")
        fun swipeWithValidCoordinatesReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.swipe(100f, 200f, 300f, 400f, 300L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Swipe executed"))
                coVerify(exactly = 1) { actionExecutor.swipe(100f, 200f, 300f, 400f, 300L) }
            }

        @Test
        @DisplayName("swipe with custom duration returns success")
        fun swipeWithCustomDurationReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.swipe(100f, 200f, 300f, 400f, 500L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                        put("duration", 500)
                    }
                val result = tool.execute(params)

                extractTextContent(result)
                coVerify(exactly = 1) { actionExecutor.swipe(100f, 200f, 300f, 400f, 500L) }
            }

        @Test
        @DisplayName("swipe with zero duration returns invalid params")
        fun swipeWithZeroDurationReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                        put("duration", 0)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("duration"))
            }

        @Test
        @DisplayName("swipe with duration exceeding max returns invalid params")
        fun swipeWithDurationExceedingMaxReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                        put("duration", 60001)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("duration"))
                assertTrue(exception.message!!.contains("60000"))
            }

        @Test
        @DisplayName("swipe with missing x2 returns invalid params")
        fun swipeWithMissingX2ReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("y2", 400)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("x2"))
            }

        @Test
        @DisplayName("swipe with negative coordinate returns invalid params")
        fun swipeWithNegativeCoordinateReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("x1", -5)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                    }

                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("swipe when service not enabled returns permission denied")
        fun swipeWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.swipe(any(), any(), any(), any(), any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put("x1", 100)
                        put("y1", 200)
                        put("x2", 300)
                        put("y2", 400)
                    }

                assertThrows<McpToolException.PermissionDenied> {
                    tool.execute(params)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ScrollTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ScrollTool")
    inner class ScrollToolTests {
        private lateinit var tool: ScrollTool

        @BeforeEach
        fun setUp() {
            tool = ScrollTool(actionExecutor)
        }

        @Test
        @DisplayName("scroll down with default amount returns success")
        fun scrollDownWithDefaultAmountReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM) } returns
                    Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("direction", "down")
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Scroll down"))
                coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM) }
            }

        @Test
        @DisplayName("scroll up with large amount returns success")
        fun scrollUpWithLargeAmountReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.scroll(ScrollDirection.UP, ScrollAmount.LARGE) } returns
                    Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("direction", "up")
                        put("amount", "large")
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Scroll up"))
                assertTrue(text.contains("large"))
                coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.UP, ScrollAmount.LARGE) }
            }

        @Test
        @DisplayName("scroll with case-insensitive direction returns success")
        fun scrollWithCaseInsensitiveDirectionReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.MEDIUM) } returns
                    Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("direction", "LEFT")
                    }
                tool.execute(params)
                coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.MEDIUM) }
            }

        @Test
        @DisplayName("scroll with invalid direction returns invalid params")
        fun scrollWithInvalidDirectionReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("direction", "diagonal")
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("direction"))
                assertTrue(exception.message!!.contains("diagonal"))
            }

        @Test
        @DisplayName("scroll with invalid amount returns invalid params")
        fun scrollWithInvalidAmountReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("direction", "down")
                        put("amount", "huge")
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("amount"))
                assertTrue(exception.message!!.contains("huge"))
            }

        @Test
        @DisplayName("scroll with missing direction returns invalid params")
        fun scrollWithMissingDirectionReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("amount", "small")
                    }

                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("scroll when service not enabled returns permission denied")
        fun scrollWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.scroll(any(), any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put("direction", "down")
                    }

                assertThrows<McpToolException.PermissionDenied> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("scroll when action fails returns action failed")
        fun scrollWhenActionFailsReturnsActionFailed() =
            runTest {
                coEvery { actionExecutor.scroll(any(), any()) } returns
                    Result.failure(
                        RuntimeException("No root node available for screen dimensions"),
                    )

                val params =
                    buildJsonObject {
                        put("direction", "down")
                    }

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(params)
                }
            }
    }
}
