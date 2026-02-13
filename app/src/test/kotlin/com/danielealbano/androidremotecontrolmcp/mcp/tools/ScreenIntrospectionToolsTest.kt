package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                assertEquals(1, content!!.size)
                assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
                val textContent = content[0].jsonObject["text"]?.jsonPrimitive?.content
                assertNotNull(textContent)
                assertTrue(textContent!!.contains("root_0"))
            }

        @Test
        @DisplayName("throws error -32001 when service is not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
                assertTrue(exception.message!!.contains("Accessibility service not enabled"))
            }

        @Test
        @DisplayName("throws error -32001 when service is not ready")
        fun throwsErrorWhenServiceNotReady() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
            }

        @Test
        @DisplayName("throws error -32003 when root node is null")
        fun throwsErrorWhenRootNodeNull() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
                every { mockScreenCaptureProvider.isMediaProjectionActive() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(80) } returns
                    Result.success(
                        ScreenshotData(data = "base64data", width = 1080, height = 2400),
                    )

                // Act
                val result = handler.execute(null)

                // Assert
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                assertEquals(1, content!!.size)
                assertEquals("image", content[0].jsonObject["type"]?.jsonPrimitive?.content)
                assertEquals("base64data", content[0].jsonObject["data"]?.jsonPrimitive?.content)
                assertEquals("image/jpeg", content[0].jsonObject["mimeType"]?.jsonPrimitive?.content)
                assertEquals(1080, content[0].jsonObject["width"]?.jsonPrimitive?.int)
                assertEquals(2400, content[0].jsonObject["height"]?.jsonPrimitive?.int)
            }

        @Test
        @DisplayName("captures screenshot with custom quality")
        fun capturesScreenshotWithCustomQuality() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isMediaProjectionActive() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(50) } returns
                    Result.success(
                        ScreenshotData(data = "base64data50", width = 1080, height = 2400),
                    )
                val params = buildJsonObject { put("quality", JsonPrimitive(50)) }

                // Act
                val result = handler.execute(params)

                // Assert
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                assertEquals("base64data50", content!![0].jsonObject["data"]?.jsonPrimitive?.content)
            }

        @Test
        @DisplayName("rejects quality below minimum (0)")
        fun rejectsQualityBelowMinimum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(0)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
                assertTrue(exception.message!!.contains("between 1 and 100"))
            }

        @Test
        @DisplayName("rejects quality above maximum (101)")
        fun rejectsQualityAboveMaximum() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(101)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
            }

        @Test
        @DisplayName("rejects negative quality (-1)")
        fun rejectsNegativeQuality() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive(-1)) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
            }

        @Test
        @DisplayName("rejects non-integer quality (string)")
        fun rejectsNonIntegerQuality() =
            runTest {
                // Arrange
                val params = buildJsonObject { put("quality", JsonPrimitive("high")) }

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(params)
                    }
                assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
            }

        @Test
        @DisplayName("throws error -32001 when MediaProjection not granted")
        fun throwsErrorWhenMediaProjectionNotGranted() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isMediaProjectionActive() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
                assertTrue(exception.message!!.contains("MediaProjection"))
            }

        @Test
        @DisplayName("throws error -32003 when capture fails")
        fun throwsErrorWhenCaptureFails() =
            runTest {
                // Arrange
                every { mockScreenCaptureProvider.isMediaProjectionActive() } returns true
                coEvery { mockScreenCaptureProvider.captureScreenshot(80) } returns
                    Result.failure(
                        RuntimeException("Capture timeout"),
                    )

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
                assertNotNull(textContent)
                assertTrue(textContent!!.contains("com.android.calculator2"))
                assertTrue(textContent.contains(".Calculator"))
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
                val content = result.jsonObject["content"]?.jsonArray
                val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
                assertTrue(textContent!!.contains("unknown"))
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
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
                val content = result.jsonObject["content"]?.jsonArray
                assertNotNull(content)
                val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
                assertNotNull(textContent)
                assertTrue(textContent!!.contains("1080"))
                assertTrue(textContent.contains("2400"))
                assertTrue(textContent.contains("420"))
                assertTrue(textContent.contains("portrait"))
            }

        @Test
        @DisplayName("throws error -32001 when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.getScreenInfo() } throws
                    McpToolException.PermissionDenied("Accessibility service not enabled")

                // Act & Assert
                val exception =
                    assertThrows<McpToolException> {
                        handler.execute(null)
                    }
                assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
            }
    }
}
