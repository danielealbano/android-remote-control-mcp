package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.GesturePoint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("GestureTools")
class GestureToolsTest {
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
        return textContent.text ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────
    // PinchTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PinchTool")
    inner class PinchToolTests {
        private lateinit var tool: PinchTool

        @BeforeEach
        fun setUp() {
            tool = PinchTool(actionExecutor)
        }

        @Test
        @DisplayName("pinch zoom in with valid params returns success")
        fun pinchZoomInWithValidParamsReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.pinch(540f, 1200f, 1.5f, 300L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 1.5)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Pinch"))
                assertTrue(text.contains("zoom in"))
                coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 1.5f, 300L) }
            }

        @Test
        @DisplayName("pinch zoom out with valid params returns success")
        fun pinchZoomOutWithValidParamsReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.pinch(540f, 1200f, 0.5f, 300L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 0.5)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("zoom out"))
                coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 0.5f, 300L) }
            }

        @Test
        @DisplayName("pinch with custom duration returns success")
        fun pinchWithCustomDurationReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.pinch(540f, 1200f, 2.0f, 500L) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 2.0)
                        put("duration", 500)
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("500ms"))
                coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 2.0f, 500L) }
            }

        @Test
        @DisplayName("pinch with zero duration returns invalid params")
        fun pinchWithZeroDurationReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 1.5)
                        put("duration", 0)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("duration"))
            }

        @Test
        @DisplayName("pinch with duration exceeding max returns invalid params")
        fun pinchWithDurationExceedingMaxReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 1.5)
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
        @DisplayName("pinch with missing center_x returns invalid params")
        fun pinchWithMissingCenterXReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_y", 1200)
                        put("scale", 1.5)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("center_x"))
            }

        @Test
        @DisplayName("pinch with missing scale returns invalid params")
        fun pinchWithMissingScaleReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("scale"))
            }

        @Test
        @DisplayName("pinch with zero scale returns invalid params")
        fun pinchWithZeroScaleReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 0)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("scale"))
                assertTrue(exception.message!!.contains("> 0"))
            }

        @Test
        @DisplayName("pinch with negative scale returns invalid params")
        fun pinchWithNegativeScaleReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", -1.0)
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("scale"))
            }

        @Test
        @DisplayName("pinch with negative center_x returns invalid params")
        fun pinchWithNegativeCenterXReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("center_x", -10)
                        put("center_y", 1200)
                        put("scale", 1.5)
                    }

                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("pinch when service not enabled returns permission denied")
        fun pinchWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.pinch(any(), any(), any(), any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 1.5)
                    }

                assertThrows<McpToolException.PermissionDenied> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("pinch when action fails returns action failed")
        fun pinchWhenActionFailsReturnsActionFailed() =
            runTest {
                coEvery { actionExecutor.pinch(any(), any(), any(), any()) } returns
                    Result.failure(
                        RuntimeException("Gesture cancelled"),
                    )

                val params =
                    buildJsonObject {
                        put("center_x", 540)
                        put("center_y", 1200)
                        put("scale", 1.5)
                    }

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(params)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CustomGestureTool
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CustomGestureTool")
    inner class CustomGestureToolTests {
        private lateinit var tool: CustomGestureTool

        @BeforeEach
        fun setUp() {
            tool = CustomGestureTool(actionExecutor)
        }

        @Test
        @DisplayName("custom gesture with valid single path returns success")
        fun customGestureWithValidSinglePathReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.customGesture(any()) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Custom gesture"))
                assertTrue(text.contains("1 path"))
                coVerify(exactly = 1) {
                    actionExecutor.customGesture(
                        match { paths ->
                            paths.size == 1 &&
                                paths[0].size == 2 &&
                                paths[0][0] == GesturePoint(100f, 100f, 0L) &&
                                paths[0][1] == GesturePoint(200f, 200f, 300L)
                        },
                    )
                }
            }

        @Test
        @DisplayName("custom gesture with multiple paths returns success")
        fun customGestureWithMultiplePathsReturnsSuccess() =
            runTest {
                coEvery { actionExecutor.customGesture(any()) } returns Result.success(Unit)

                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 400)
                                                put("y", 400)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 300)
                                                put("y", 300)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                val result = tool.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("2 path"))
                coVerify(exactly = 1) { actionExecutor.customGesture(match { it.size == 2 }) }
            }

        @Test
        @DisplayName("custom gesture with missing paths returns invalid params")
        fun customGestureWithMissingPathsReturnsInvalidParams() =
            runTest {
                val params = buildJsonObject {}

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("paths"))
            }

        @Test
        @DisplayName("custom gesture with null params returns invalid params")
        fun customGestureWithNullParamsReturnsInvalidParams() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    tool.execute(null)
                }
            }

        @Test
        @DisplayName("custom gesture with empty paths returns invalid params")
        fun customGestureWithEmptyPathsReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put("paths", buildJsonArray {})
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("must not be empty"))
            }

        @Test
        @DisplayName("custom gesture with single point path returns invalid params")
        fun customGestureWithSinglePointPathReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("at least 2 points"))
            }

        @Test
        @DisplayName("custom gesture with negative coordinate returns invalid params")
        fun customGestureWithNegativeCoordinateReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", -1)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains(">= 0"))
            }

        @Test
        @DisplayName("custom gesture with negative time returns invalid params")
        fun customGestureWithNegativeTimeReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", -5)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("time"))
                assertTrue(exception.message!!.contains(">= 0"))
            }

        @Test
        @DisplayName("custom gesture with non-monotonic times returns invalid params")
        fun customGestureWithNonMonotonicTimesReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 150)
                                                put("y", 150)
                                                put("time", 200)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 100)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("monotonically increasing"))
            }

        @Test
        @DisplayName("custom gesture with equal times returns invalid params")
        fun customGestureWithEqualTimesReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 0)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("monotonically increasing"))
            }

        @Test
        @DisplayName("custom gesture with missing point field returns invalid params")
        fun customGestureWithMissingPointFieldReturnsInvalidParams() =
            runTest {
                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        tool.execute(params)
                    }
                assertTrue(exception.message!!.contains("time"))
            }

        @Test
        @DisplayName("custom gesture when service not enabled returns permission denied")
        fun customGestureWhenServiceNotEnabledReturnsPermissionDenied() =
            runTest {
                coEvery { actionExecutor.customGesture(any()) } returns
                    Result.failure(
                        IllegalStateException("Accessibility service is not available"),
                    )

                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                assertThrows<McpToolException.PermissionDenied> {
                    tool.execute(params)
                }
            }

        @Test
        @DisplayName("custom gesture when action fails returns action failed")
        fun customGestureWhenActionFailsReturnsActionFailed() =
            runTest {
                coEvery { actionExecutor.customGesture(any()) } returns
                    Result.failure(
                        RuntimeException("Gesture cancelled"),
                    )

                val params =
                    buildJsonObject {
                        put(
                            "paths",
                            buildJsonArray {
                                add(
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("x", 100)
                                                put("y", 100)
                                                put("time", 0)
                                            },
                                        )
                                        add(
                                            buildJsonObject {
                                                put("x", 200)
                                                put("y", 200)
                                                put("time", 300)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                assertThrows<McpToolException.ActionFailed> {
                    tool.execute(params)
                }
            }
    }
}
