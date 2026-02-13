@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Element Action Integration Tests")
class ElementActionIntegrationTest {
    private val sampleTree =
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
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
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

    @Test
    fun `find_elements returns matching elements from mocked tree`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            every {
                deps.elementFinder.findElements(sampleTree, FindBy.TEXT, "OK", false)
            } returns
                listOf(
                    ElementInfo(
                        id = "node_btn",
                        text = "OK",
                        className = "android.widget.Button",
                        bounds = BoundsData(50, 800, 250, 1000),
                        clickable = true,
                        enabled = true,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "find_elements",
                        arguments =
                            buildJsonObject {
                                put("by", "text")
                                put("value", "OK")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val textContent =
                    rpcResponse.result!!.jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!.jsonPrimitive.content
                val parsed = Json.parseToJsonElement(textContent).jsonObject
                val elements = parsed["elements"]!!.jsonArray
                assertEquals(1, elements.size)
                assertEquals(
                    "node_btn",
                    elements[0].jsonObject["id"]?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `click_element with valid node_id calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("node_btn", sampleTree)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "click_element",
                        arguments =
                            buildJsonObject {
                                put("element_id", "node_btn")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }

    @Test
    fun `click_element with non-existent node_id returns element not found error`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("node_xyz", sampleTree)
            } returns Result.failure(NoSuchElementException("Node 'node_xyz' not found"))

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "click_element",
                        arguments =
                            buildJsonObject {
                                put("element_id", "node_xyz")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(ELEMENT_NOT_FOUND_CODE, rpcResponse.error!!.code)
            }
        }

    companion object {
        private const val ELEMENT_NOT_FOUND_CODE = -32002
    }
}
