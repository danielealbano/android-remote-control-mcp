@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
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
        val mockRootNode = mockk<AccessibilityNodeInfo>()
        every { accessibilityServiceProvider.isReady() } returns true
        every { accessibilityServiceProvider.getRootNode() } returns mockRootNode
        every { treeParser.parseTree(mockRootNode) } returns sampleTree
        every { accessibilityServiceProvider.getCurrentPackageName() } returns "com.example.app"
        every { accessibilityServiceProvider.getCurrentActivityName() } returns ".MainActivity"
        every { accessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
        every { mockRootNode.recycle() } returns Unit
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
                assertTrue(textContent.contains("app:com.example.app activity:.MainActivity"))
                assertTrue(textContent.contains("screen:1080x2400 density:420 orientation:portrait"))
                assertTrue(textContent.contains("id\tclass\ttext\tdesc\tres_id\tbounds\tflags"))
                assertTrue(textContent.contains("node_btn"))
                assertTrue(textContent.contains("on,clk,ena"))
                // Negative assertions: ensure old single-char flag format is gone
                assertFalse(textContent.contains("\tvcn"))
                assertFalse(textContent.contains("\tvclfsen"))
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
                assertTrue(textContent.contains("app:"))

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
}
