package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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

@DisplayName("Screen Introspection Tools")
class ScreenIntrospectionToolsTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockScreenCaptureProvider: ScreenCaptureProvider
    private lateinit var mockTreeParser: AccessibilityTreeParser
    private lateinit var mockCompactTreeFormatter: CompactTreeFormatter
    private lateinit var mockScreenshotAnnotator: ScreenshotAnnotator
    private lateinit var mockScreenshotEncoder: ScreenshotEncoder
    private lateinit var mockNodeCache: AccessibilityNodeCache
    private lateinit var mockRootNode: AccessibilityNodeInfo
    private lateinit var mockWindowInfo: AccessibilityWindowInfo

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockScreenCaptureProvider = mockk<ScreenCaptureProvider>()
        mockTreeParser = mockk<AccessibilityTreeParser>()
        mockCompactTreeFormatter = mockk<CompactTreeFormatter>()
        mockScreenshotAnnotator = mockk(relaxed = true)
        mockScreenshotEncoder = mockk(relaxed = true)
        mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(100, 200, 300, 260),
                        clickable = true,
                        visible = true,
                        enabled = true,
                    ),
                ),
        )

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    private val sampleCompactOutput =
        "note:structural-only nodes are omitted from the tree\n" +
            "note:certain elements are custom and will not be properly reported, " +
            "if needed or if tools are not working as expected set " +
            "include_screenshot=true to see the screen and take what you see into account\n" +
            "note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable " +
            "foc=focusable scr=scrollable edt=editable ena=enabled\n" +
            "note:offscreen items require scroll_to_element before interaction\n" +
            "screen:1080x2400 density:420 orientation:portrait\n" +
            "--- window:0 type:APPLICATION pkg:com.example title:Test activity:.Main " +
            "layer:0 focused:true ---\n" +
            "id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n" +
            "node_btn\tButton\tOK\t-\t-\t100,200,300,260\ton,clk,ena"

    @Suppress("LongMethod")
    private fun setupReadyService() {
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockRootNode.packageName } returns "com.example"
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockAccessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
        every {
            mockCompactTreeFormatter.formatMultiWindow(any(), eq(sampleScreenInfo))
        } returns sampleCompactOutput
        every { mockCompactTreeFormatter.shouldKeepNode(any()) } returns true
    }

    // ─────────────────────────────────────────────────────────────────────
    // get_screen_state
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetScreenStateHandler")
    inner class GetScreenStateTests {
        private lateinit var handler: GetScreenStateHandler

        @BeforeEach
        fun setUp() {
            handler =
                GetScreenStateHandler(
                    mockTreeParser,
                    mockAccessibilityServiceProvider,
                    mockScreenCaptureProvider,
                    mockCompactTreeFormatter,
                    mockScreenshotAnnotator,
                    mockScreenshotEncoder,
                    mockNodeCache,
                )
        }

        @Test
        @DisplayName("returns compact tree when service is ready")
        fun returnsCompactTreeWhenServiceIsReady() =
            runTest {
                setupReadyService()

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(sampleCompactOutput, textContent.text)
            }

        @Test
        @DisplayName("includes screenshot when include_screenshot is true")
        fun includesScreenshotWhenIncludeScreenshotIsTrue() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                val mockAnnotatedBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                    )
                } returns Result.success(mockBitmap)
                every {
                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
                } returns mockAnnotatedBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "base64data", width = 700, height = 500)

                val params = buildJsonObject { put("include_screenshot", true) }
                val result = handler.execute(params)

                assertEquals(2, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(sampleCompactOutput, textContent.text)
                val imageContent = result.content[1] as ImageContent
                assertEquals("base64data", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }

        @Test
        @DisplayName("screenshot uses 700px max size")
        fun screenshotUses700pxMaxSize() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                val mockAnnotatedBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.success(mockBitmap)
                every {
                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
                } returns mockAnnotatedBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "base64data", width = 700, height = 500)

                val params = buildJsonObject { put("include_screenshot", true) }
                handler.execute(params)

                coVerify(exactly = 1) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                    )
                }
            }

        @Test
        @DisplayName("does not include screenshot by default")
        fun doesNotIncludeScreenshotByDefault() =
            runTest {
                setupReadyService()

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                assertTrue(result.content[0] is TextContent)
                coVerify(exactly = 0) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }

        @Test
        @DisplayName("does not include screenshot when false")
        fun doesNotIncludeScreenshotWhenFalse() =
            runTest {
                setupReadyService()

                val params = buildJsonObject { put("include_screenshot", false) }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                assertTrue(result.content[0] is TextContent)
                coVerify(exactly = 0) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }

        @Test
        @DisplayName("throws PermissionDenied when accessibility service not ready")
        fun throwsPermissionDeniedWhenAccessibilityServiceNotReady() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Accessibility service not enabled"))
            }

        @Test
        @DisplayName("throws ActionFailed when no windows and no root node available")
        fun throwsActionFailedWhenNoWindowsAndNoRootNode() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(null)
                }
            }

        @Test
        @DisplayName("throws PermissionDenied when screenshot requested but capture not available")
        fun throwsPermissionDeniedWhenScreenshotRequestedButCaptureNotAvailable() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns false

                val params = buildJsonObject { put("include_screenshot", true) }
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("Screen capture not available"))
            }

        @Test
        @DisplayName("throws ActionFailed when screenshot capture fails")
        fun throwsActionFailedWhenScreenshotCaptureFails() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.failure(RuntimeException("Screenshot capture failed"))

                val params = buildJsonObject { put("include_screenshot", true) }
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("Screenshot capture failed"))
            }

        @Test
        @DisplayName("populates cache and recycles window info but not root node after parsing")
        fun populatesCacheAndRecyclesWindowInfoButNotRootNodeAfterParsing() =
            runTest {
                setupReadyService()

                handler.execute(null)

                @Suppress("DEPRECATION")
                verify(exactly = 0) { mockRootNode.recycle() }
                verify(exactly = 1) { mockNodeCache.populate(any()) }
                @Suppress("DEPRECATION")
                verify(exactly = 1) { mockWindowInfo.recycle() }
            }

        @Test
        @DisplayName("getFreshWindows fallback path returns degraded output")
        fun getFreshWindowsFallbackPathReturnsDegradedOutput() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                val fallbackRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockAccessibilityServiceProvider.getRootNode() } returns fallbackRootNode
                every { fallbackRootNode.windowId } returns 42
                every { fallbackRootNode.packageName } returns "com.fallback"
                every { mockTreeParser.parseTree(fallbackRootNode, "root_w42", any()) } returns sampleTree
                every { mockAccessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
                val degradedOutput = "degraded output"
                every {
                    mockCompactTreeFormatter.formatMultiWindow(
                        match { it.degraded },
                        eq(sampleScreenInfo),
                    )
                } returns degradedOutput

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(degradedOutput, textContent.text)

                @Suppress("DEPRECATION")
                verify(exactly = 0) { fallbackRootNode.recycle() }
                verify(exactly = 1) { mockNodeCache.populate(any()) }
            }

        @Test
        @DisplayName("getFreshWindows throws ActionFailed when all windows have null roots")
        fun throwsActionFailedWhenAllWindowsHaveNullRoots() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                val mockNullRootWindow = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockNullRootWindow.root } returns null
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns listOf(mockNullRootWindow)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("All windows returned null root nodes"))

                @Suppress("DEPRECATION")
                verify(exactly = 1) { mockNullRootWindow.recycle() }
            }

        @Test
        @DisplayName("getFreshWindows throws ActionFailed when no windows and no root node")
        fun throwsActionFailedWhenNoWindowsAndNoRoot() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("No windows available"))
            }
    }
}
