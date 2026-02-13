@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "get_accessibility_tree")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val textContent =
                    rpcResponse.result!!.jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!.jsonPrimitive.content
                val parsed = Json.parseToJsonElement(textContent).jsonObject
                assertNotNull(parsed["nodes"])
            }
        }

    @Test
    fun `capture_screenshot returns base64 image from mocked service`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.screenCaptureProvider.isMediaProjectionActive() } returns true
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

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "capture_screenshot")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val content = rpcResponse.result!!.jsonObject["content"]!!.jsonArray
                val imageItem = content[0].jsonObject
                assertEquals("image", imageItem["type"]?.jsonPrimitive?.content)
                assertEquals("image/jpeg", imageItem["mimeType"]?.jsonPrimitive?.content)
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

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "get_current_app")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val textContent =
                    rpcResponse.result!!.jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!.jsonPrimitive.content
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
    fun `capture_screenshot when permission denied returns error -32001`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.screenCaptureProvider.isMediaProjectionActive() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "capture_screenshot")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(PERMISSION_DENIED_CODE, rpcResponse.error!!.code)
            }
        }

    companion object {
        private const val PERMISSION_DENIED_CODE = -32001
    }
}
