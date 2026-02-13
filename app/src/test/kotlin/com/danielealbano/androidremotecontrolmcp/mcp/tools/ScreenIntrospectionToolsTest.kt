package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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

@DisplayName("Screen Introspection Tools")
class ScreenIntrospectionToolsTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockScreenCaptureProvider: ScreenCaptureProvider
    private lateinit var mockTreeParser: AccessibilityTreeParser
    private lateinit var mockRootNode: AccessibilityNodeInfo

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockScreenCaptureProvider = mockk<ScreenCaptureProvider>()
        mockTreeParser = mockk<AccessibilityTreeParser>()
        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
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
    // get_accessibility_tree
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetAccessibilityTreeHandler")
    inner class GetAccessibilityTreeTests {
        private lateinit var handler: GetAccessibilityTreeHandler

        @BeforeEach
        fun setUp() {
            handler = GetAccessibilityTreeHandler(mockTreeParser, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("returns tree JSON when service is ready")
        fun returnsTreeJsonWhenServiceIsReady() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode
                val mockTreeData =
                    AccessibilityNodeData(
                        id = "root_0",
                        className = "android.widget.FrameLayout",
                        text = null,
                        bounds = BoundsData(0, 0, 1080, 2400),
                        visible = true,
                        enabled = true,
                        children = emptyList(),
                    )
                every { mockTreeParser.parseTree(mockRootNode) } returns mockTreeData

                // Act
                val result = handler.execute(null)

                // Assert
                val text = extractTextContent(result)
                assertTrue(text.contains("root_0"))
            }

        @Test
        @DisplayName("throws error when service is not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Accessibility service not enabled"))
            }

        @Test
        @DisplayName("throws error when service is not ready")
        fun throwsErrorWhenServiceNotReady() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }

        @Test
        @DisplayName("throws error when root node is null")
        fun throwsErrorWhenRootNodeNull() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                // Act & Assert
                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(null)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // capture_screenshot
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CaptureScreenshotHandler")
    inner class CaptureScreenshotTests {
        private lateinit var handler: CaptureScreenshotHandler

        @BeforeEach
        fun setUp() {
            handler = CaptureScreenshotHandler(mockScreenCaptureProvider)
        }

        @Test
        @DisplayName("captures screenshot with default quality")
        fun capturesScreenshotWithDefaultQuality() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(80) } returns
                    Result.success(
                        ScreenshotData(data = "base64data", width = 1080, height = 2400),
                    )

                // Act
                val result = handler.execute(null)

                // Assert
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("image/jpeg", imageContent.mimeType)
                assertEquals("base64data", imageContent.data)
            }

        @Test
        @DisplayName("captures screenshot with custom quality")
        fun capturesScreenshotWithCustomQuality() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(50) } returns
                    Result.success(
                        ScreenshotData(data = "base64data50", width = 1080, height = 2400),
                    )
                val params = buildJsonObject { put("quality", JsonPrimitive(50)) }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("base64data50", imageContent.data)
            }

        @Test
        @DisplayName("captures screenshot with width only")
        fun capturesScreenshotWithWidthOnly() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery {
                    mockScreenCaptureProvider.captureScreenshot(80, 720, null)
                } returns
                    Result.success(
                        ScreenshotData(data = "resized", width = 720, height = 1600),
                    )
                val params = buildJsonObject { put("width", 720) }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("resized", imageContent.data)
                coVerify(exactly = 1) {
                    mockScreenCaptureProvider.captureScreenshot(80, 720, null)
                }
            }

        @Test
        @DisplayName("captures screenshot with height only")
        fun capturesScreenshotWithHeightOnly() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery {
                    mockScreenCaptureProvider.captureScreenshot(80, null, 1200)
                } returns
                    Result.success(
                        ScreenshotData(data = "resized_h", width = 540, height = 1200),
                    )
                val params = buildJsonObject { put("height", 1200) }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("resized_h", imageContent.data)
                coVerify(exactly = 1) {
                    mockScreenCaptureProvider.captureScreenshot(80, null, 1200)
                }
            }

        @Test
        @DisplayName("captures screenshot with both width and height")
        fun capturesScreenshotWithBothWidthAndHeight() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery {
                    mockScreenCaptureProvider.captureScreenshot(80, 720, 1200)
                } returns
                    Result.success(
                        ScreenshotData(data = "resized_wh", width = 720, height = 1200),
                    )
                val params =
                    buildJsonObject {
                        put("width", 720)
                        put("height", 1200)
                    }

                // Act
                val result = handler.execute(params)

                // Assert
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("resized_wh", imageContent.data)
                coVerify(exactly = 1) {
                    mockScreenCaptureProvider.captureScreenshot(80, 720, 1200)
                }
            }

        @Test
        @DisplayName("rejects negative width")
        fun rejectsNegativeWidth() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("width", -1) }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("rejects zero height")
        fun rejectsZeroHeight() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("height", 0) }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("rejects non-integer width (string)")
        fun rejectsNonIntegerWidth() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("width", "large") }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("rejects quality below minimum (0)")
        fun rejectsQualityBelowMinimum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(0)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("between 1 and 100"))
            }

        @Test
        @DisplayName("rejects quality above maximum (101)")
        fun rejectsQualityAboveMaximum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(101)) }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("rejects negative quality (-1)")
        fun rejectsNegativeQuality() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(-1)) }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("rejects non-integer quality (string)")
        fun rejectsNonIntegerQuality() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive("high")) }

                // Act & Assert
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(params)
                }
            }

        @Test
        @DisplayName("throws error when screen capture not available")
        fun throwsErrorWhenScreenCaptureNotAvailable() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Screen capture not available"))
            }

        @Test
        @DisplayName("throws error when capture fails")
        fun throwsErrorWhenCaptureFails() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(80) } returns
                    Result.failure(
                        RuntimeException("Capture timeout"),
                    )

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Capture timeout"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // get_current_app
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetCurrentAppHandler")
    inner class GetCurrentAppTests {
        private lateinit var handler: GetCurrentAppHandler

        @BeforeEach
        fun setUp() {
            handler = GetCurrentAppHandler(mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("returns package and activity name")
        fun returnsPackageAndActivityName() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.android.calculator2"
                every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Calculator"

                // Act
                val result = handler.execute(null)

                // Assert
                val text = extractTextContent(result)
                assertTrue(text.contains("com.android.calculator2"))
                assertTrue(text.contains(".Calculator"))
            }

        @Test
        @DisplayName("returns unknown when no app focused")
        fun returnsUnknownWhenNoAppFocused() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns null
                every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns null

                // Act
                val result = handler.execute(null)

                // Assert
                val text = extractTextContent(result)
                assertTrue(text.contains("unknown"))
            }

        @Test
        @DisplayName("throws error when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // get_screen_info
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetScreenInfoHandler")
    inner class GetScreenInfoTests {
        private lateinit var handler: GetScreenInfoHandler

        @BeforeEach
        fun setUp() {
            handler = GetScreenInfoHandler(mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("returns screen dimensions and orientation")
        fun returnsScreenDimensionsAndOrientation() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.getScreenInfo() } returns
                    ScreenInfo(
                        width = 1080,
                        height = 2400,
                        densityDpi = 420,
                        orientation = ScreenInfo.ORIENTATION_PORTRAIT,
                    )

                // Act
                val result = handler.execute(null)

                // Assert
                val text = extractTextContent(result)
                assertTrue(text.contains("1080"))
                assertTrue(text.contains("2400"))
                assertTrue(text.contains("420"))
                assertTrue(text.contains("portrait"))
            }

        @Test
        @DisplayName("throws error when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.getScreenInfo() } throws
                    McpToolException.PermissionDenied("Accessibility service not enabled")

                // Act & Assert
                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }
    }
}
