@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `get_accessibility_tree returns parsed tree from mocked service`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "get_accessibility_tree",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val textContent = (result.content[0] as TextContent).text
                val parsed = Json.parseToJsonElement(textContent).jsonObject
                assertNotNull(parsed["nodes"])
            }
        }

    @Test
    fun `capture_screenshot returns base64 image from mocked service`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns true
            coEvery {
                deps.screenCaptureProvider.captureScreenshot(any())
            } returns
                Result.success(
                    ScreenshotData(
                        data = "dGVzdA==",
                        width = 1080,
                        height = 2400,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "capture_screenshot",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val imageContent = result.content[0] as ImageContent
                assertEquals("image/jpeg", imageContent.mimeType)
                assertEquals("dGVzdA==", imageContent.data)
            }
        }

    @Test
    fun `get_current_app returns package and activity from mocked service`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            every {
                deps.accessibilityServiceProvider.getCurrentPackageName()
            } returns "com.example.app"
            every {
                deps.accessibilityServiceProvider.getCurrentActivityName()
            } returns "MainActivity"

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "get_current_app",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())

                val textContent = (result.content[0] as TextContent).text
                val parsed = Json.parseToJsonElement(textContent).jsonObject
                assertEquals(
                    "com.example.app",
                    parsed["packageName"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    "MainActivity",
                    parsed["activityName"]?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `capture_screenshot when permission denied returns error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.screenCaptureProvider.isScreenCaptureAvailable() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "capture_screenshot",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Screen capture not available"))
            }
        }
}
