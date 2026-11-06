package com.danielealbano.androidremotecontrolmcp.integration

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

@DisplayName("Node screenshots integration tests")
class NodeScreenshotsIntegrationTest {
    private val tree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(100, 200, 300, 260),
                        visible = true,
                    ),
                ),
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private fun MockDependencies.setupButtonOnScreen() {
        McpIntegrationTestHelper.setupMultiWindowMock(this, tree, ScreenInfo(1080, 2400, 420, "portrait"))
        readNodes["node_btn"] = CachedNode(mockk<AccessibilityNodeInfo>(relaxed = true), 1, 0, "node_root")
        every { screenCaptureProvider.isScreenCaptureAvailable() } returns true
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.config } returns Bitmap.Config.ARGB_8888
        coEvery { screenCaptureProvider.captureScreenshotBitmap(null, null) } returns Result.success(bitmap)
        every { nodeImageCropper.crop(bitmap, BoundsData(100, 200, 300, 260), 1080, 2400) } returns
            ScreenshotData(data = "aW1n", width = 200, height = 60)
    }

    @Test
    fun `get_node_screenshots returns the lines then one image over streamable http`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            deps.setupButtonOnScreen()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_get_node_screenshots",
                        arguments = mapOf("node_ids" to listOf("node_btn", "node_gone")),
                    )

                assertNotEquals(true, result.isError)
                assertEquals(2, result.content.size)
                assertEquals(
                    "${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\nnode_btn: image 1\nnode_gone: not found",
                    (result.content[0] as TextContent).text,
                )
                val image = result.content[1] as ImageContent
                assertEquals("aW1n", image.data)
                assertEquals("image/jpeg", image.mimeType)
            }
        }

    @Test
    fun `an invalid get_node_screenshots call is an error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result = client.callTool(name = "android_get_node_screenshots", arguments = emptyMap())

                assertEquals(true, result.isError)
                assertTrue((result.content[0] as TextContent).text.contains("node_ids"))
            }
        }

    @Test
    fun `get_node_screenshots is not listed when disabled`() =
        runTest {
            val perms = ToolPermissionsConfig(disabledTools = setOf("get_node_screenshots"))

            McpIntegrationTestHelper.withTestApplication(perms = perms) { client, _ ->
                val names = client.listTools().tools.map { it.name }

                assertFalse("android_get_node_screenshots" in names)
                assertTrue("android_get_screen_state" in names)
            }
        }
}
