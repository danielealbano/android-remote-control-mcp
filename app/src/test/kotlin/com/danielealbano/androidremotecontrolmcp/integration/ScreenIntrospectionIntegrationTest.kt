@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Screen Introspection Integration Tests")
class ScreenIntrospectionIntegrationTest {
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

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private fun MockDependencies.setupReadyService() {
        McpIntegrationTestHelper.setupMultiWindowMock(
            deps = this,
            tree = sampleTree,
            screenInfo = sampleScreenInfo,
            packageName = "com.example.app",
            activityName = ".MainActivity",
        )
    }

    @Test
    fun `get_screen_state returns compact flat TSV with metadata`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupReadyService()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                assertEquals(1, result.content.size)

                val textContent = (result.content[0] as TextContent).text
                assertTrue(textContent.contains("note:structural-only nodes are omitted from the tree"))
                assertTrue(textContent.contains("note:certain elements are custom and will not be properly reported"))
                assertTrue(textContent.contains("note:flags: on=onscreen off=offscreen"))
                assertTrue(textContent.contains("note:offscreen items require scroll_to_element before interaction"))
                assertTrue(textContent.contains("screen:1080x2400 density:420 orientation:portrait"))
                // Multi-window format: per-window header instead of global app line
                assertTrue(textContent.contains("--- window:0 type:APPLICATION"))
                assertTrue(textContent.contains("pkg:com.example.app"))
                assertTrue(textContent.contains("activity:.MainActivity"))
                assertTrue(textContent.contains("id\tclass\ttext\tdesc\tres_id\tbounds\tflags"))
                assertTrue(textContent.contains("node_btn"))
                assertTrue(textContent.contains("on,clk,ena"))
                // Negative assertions: ensure old single-char flag format is gone
                assertFalse(textContent.contains("\tvcn"))
                assertFalse(textContent.contains("\tvclfsen"))
                // Negative assertions: old global app: line should not be present
                assertFalse(textContent.contains("app:com.example.app activity:.MainActivity"))
            }
        }

    @Test
    fun `get_screen_state with include_screenshot returns text and image`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupReadyService()
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

            // Mock captureScreenshotBitmap to return a relaxed mock Bitmap
            val mockBitmap = mockk<Bitmap>(relaxed = true)
            coEvery {
                deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
            } returns Result.success(mockBitmap)

            // Mock annotator to return the same mock bitmap (no Android Canvas needed)
            val annotatedMockBitmap = mockk<Bitmap>(relaxed = true)
            every {
                deps.screenshotAnnotator.annotate(any(), any(), any(), any())
            } returns annotatedMockBitmap

            // Mock encoder to return known ScreenshotData
            every {
                deps.screenshotEncoder.bitmapToScreenshotData(any(), any())
            } returns ScreenshotData(data = "dGVzdA==", width = 700, height = 500)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = mapOf("include_screenshot" to true),
                    )
                assertNotEquals(true, result.isError)
                assertEquals(2, result.content.size)

                val textContent = (result.content[0] as TextContent).text
                assertTrue(textContent.contains("note:"))
                assertTrue(textContent.contains("note:flags:"))
                assertTrue(textContent.contains("note:offscreen items"))
                assertTrue(textContent.contains("--- window:0 type:APPLICATION"))

                val imageContent = result.content[1] as ImageContent
                assertEquals("image/jpeg", imageContent.mimeType)
                assertEquals("dGVzdA==", imageContent.data)
            }

            // Verify the annotated bitmap (not the resized bitmap) is encoded with the correct quality
            verify {
                deps.screenshotEncoder.bitmapToScreenshotData(
                    annotatedMockBitmap,
                    ScreenCaptureProvider.DEFAULT_QUALITY,
                )
            }
            // Verify annotate received the resized bitmap (not some other bitmap)
            verify {
                deps.screenshotAnnotator.annotate(
                    mockBitmap,
                    any(),
                    1080,
                    2400,
                )
            }
        }

    @Test
    fun `get_screen_state without screenshot does not call captureScreenshot`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupReadyService()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                assertEquals(1, result.content.size)
                assertTrue(result.content[0] is TextContent)

                coVerify(exactly = 0) {
                    deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }
        }

    @Test
    fun `get_screen_state when permission denied returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Accessibility service not enabled"))
            }
        }

    @Test
    fun `get_screen_state with screenshot annotation failure returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupReadyService()
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true

            val mockBitmap = mockk<Bitmap>(relaxed = true)
            coEvery {
                deps.screenCaptureProvider.captureScreenshotBitmap(any(), any())
            } returns Result.success(mockBitmap)

            // Simulate annotation failure
            every {
                deps.screenshotAnnotator.annotate(any(), any(), any(), any())
            } throws RuntimeException("Canvas error")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_screen_state",
                        arguments = mapOf("include_screenshot" to true),
                    )
                // The tool should return an error
                assertEquals(true, result.isError)
                // Verify the error message is generic (does not leak internal "Canvas error" details)
                val errorText = (result.content[0] as TextContent).text
                assertTrue(errorText.contains("Screenshot annotation failed"))
                assertFalse(errorText.contains("Canvas error"))
            }

            // Verify the resized bitmap was still recycled via the finally block
            // (annotatedBitmap is null because annotate() threw before assignment)
            verify { mockBitmap.recycle() }
        }

    @Nested
    @DisplayName("Multi-Window Scenarios")
    inner class MultiWindowScenarios {
        private val appTree =
            AccessibilityNodeData(
                id = "node_root_w42",
                className = "android.widget.FrameLayout",
                bounds = BoundsData(0, 0, 1080, 2400),
                visible = true,
                enabled = true,
                children =
                    listOf(
                        AccessibilityNodeData(
                            id = "node_btn_w42",
                            className = "android.widget.Button",
                            text = "OK",
                            bounds = BoundsData(100, 200, 300, 260),
                            clickable = true,
                            visible = true,
                            enabled = true,
                        ),
                    ),
            )

        private val dialogTree =
            AccessibilityNodeData(
                id = "node_root_w99",
                className = "android.widget.FrameLayout",
                bounds = BoundsData(0, 0, 1080, 2400),
                visible = true,
                enabled = true,
                children =
                    listOf(
                        AccessibilityNodeData(
                            id = "node_allow_w99",
                            className = "android.widget.Button",
                            text = "Allow",
                            bounds = BoundsData(200, 1200, 500, 1300),
                            clickable = true,
                            visible = true,
                            enabled = true,
                        ),
                    ),
            )

        private fun MockDependencies.setupTwoWindowMock() {
            val mockRootApp = mockk<AccessibilityNodeInfo>()
            val mockRootDialog = mockk<AccessibilityNodeInfo>()
            val mockWindowApp = mockk<AccessibilityWindowInfo>(relaxed = true)
            val mockWindowDialog = mockk<AccessibilityWindowInfo>(relaxed = true)

            every { accessibilityServiceProvider.isReady() } returns true
            every { mockWindowApp.id } returns 42
            every { mockWindowApp.root } returns mockRootApp
            every { mockWindowApp.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { mockWindowApp.title } returns "MyApp"
            every { mockWindowApp.layer } returns 0
            every { mockWindowApp.isFocused } returns false
            every { mockRootApp.packageName } returns "com.example.myapp"

            every { mockWindowDialog.id } returns 99
            every { mockWindowDialog.root } returns mockRootDialog
            every { mockWindowDialog.type } returns AccessibilityWindowInfo.TYPE_SYSTEM
            every { mockWindowDialog.title } returns "Permission"
            every { mockWindowDialog.layer } returns 1
            every { mockWindowDialog.isFocused } returns true
            every { mockRootDialog.packageName } returns "com.android.permissioncontroller"

            every {
                accessibilityServiceProvider.getAccessibilityWindows()
            } returns listOf(mockWindowApp, mockWindowDialog)
            every { accessibilityServiceProvider.getCurrentPackageName() } returns "com.example.myapp"
            every { accessibilityServiceProvider.getCurrentActivityName() } returns ".MainActivity"
            every { accessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo

            every { treeParser.parseTree(mockRootApp, "root_w42") } returns appTree
            every { treeParser.parseTree(mockRootDialog, "root_w99") } returns dialogTree

            every { mockRootApp.recycle() } returns Unit
            every { mockRootDialog.recycle() } returns Unit
        }

        @Test
        fun `get_screen_state with two windows returns both window sections`() =
            runTest {
                val deps = McpIntegrationTestHelper.createMockDependencies()
                deps.setupTwoWindowMock()

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_get_screen_state",
                            arguments = emptyMap(),
                        )
                    assertNotEquals(true, result.isError)
                    val text = (result.content[0] as TextContent).text

                    // Both window headers must be present
                    assertTrue(text.contains("--- window:42 type:APPLICATION"))
                    assertTrue(text.contains("pkg:com.example.myapp"))
                    assertTrue(text.contains("--- window:99 type:SYSTEM"))
                    assertTrue(text.contains("pkg:com.android.permissioncontroller"))

                    // Elements from both windows must be present
                    assertTrue(text.contains("node_btn_w42"))
                    assertTrue(text.contains("node_allow_w99"))

                    // No degradation note
                    assertFalse(text.contains("DEGRADED"))
                }
            }

        @Test
        fun `get_screen_state in degraded mode includes degradation note`() =
            runTest {
                val deps = McpIntegrationTestHelper.createMockDependencies()
                val mockRootNode = mockk<AccessibilityNodeInfo>()

                every { deps.accessibilityServiceProvider.isReady() } returns true
                every {
                    deps.accessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
                every { mockRootNode.packageName } returns "com.example.app"
                every { mockRootNode.windowId } returns 0
                every { mockRootNode.window } returns null
                every { deps.treeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
                every { deps.accessibilityServiceProvider.getCurrentPackageName() } returns "com.example.app"
                every { deps.accessibilityServiceProvider.getCurrentActivityName() } returns ".MainActivity"
                every { deps.accessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
                every { mockRootNode.recycle() } returns Unit

                McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                    val result =
                        client.callTool(
                            name = "android_get_screen_state",
                            arguments = emptyMap(),
                        )
                    assertNotEquals(true, result.isError)
                    val text = (result.content[0] as TextContent).text

                    // Degraded mode note must be present
                    assertTrue(text.contains("DEGRADED"))
                    // Still has window section with windowId=0
                    assertTrue(text.contains("--- window:0"))
                    // Elements still present
                    assertTrue(text.contains("node_btn"))
                }
            }
    }
}
