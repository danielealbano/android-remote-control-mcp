@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Error Handling Integration Tests")
class ErrorHandlingIntegrationTest {
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
    fun `permission denied exception returns JSON-RPC error code -32001`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns false

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "press_back")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_PERMISSION_DENIED,
                    rpcResponse.error!!.code,
                )
            }
        }

    @Test
    fun `element not found exception returns JSON-RPC error code -32002`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("nonexistent", sampleTree)
            } returns
                Result.failure(
                    NoSuchElementException("Node 'nonexistent' not found"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "click_element",
                        arguments =
                            buildJsonObject {
                                put("element_id", "nonexistent")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_ELEMENT_NOT_FOUND,
                    rpcResponse.error!!.code,
                )
            }
        }

    @Test
    fun `action failed exception returns JSON-RPC error code -32003`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit
            coEvery {
                deps.actionExecutor.clickNode("node_root", sampleTree)
            } returns
                Result.failure(
                    IllegalStateException("Node 'node_root' is not clickable"),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "click_element",
                        arguments =
                            buildJsonObject {
                                put("element_id", "node_root")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_ACTION_FAILED,
                    rpcResponse.error!!.code,
                )
            }
        }

    @Test
    fun `timeout exception returns JSON-RPC error code -32004`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery {
                deps.actionExecutor.pressHome()
            } throws
                McpToolException.Timeout(
                    "Operation timed out",
                )

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "press_home")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_TIMEOUT,
                    rpcResponse.error!!.code,
                )
            }
        }

    @Test
    fun `invalid params exception returns JSON-RPC error code -32602`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    sendToolCall(
                        toolName = "tap",
                        arguments =
                            buildJsonObject {
                                put("x", "not_a_number")
                                put("y", 100)
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_INVALID_PARAMS,
                    rpcResponse.error!!.code,
                )
            }
        }

    @Test
    fun `internal error exception returns JSON-RPC error code -32603`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery {
                deps.actionExecutor.pressBack()
            } throws RuntimeException("Unexpected internal error")

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response = sendToolCall(toolName = "press_back")

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(
                    McpProtocolHandler.ERROR_INTERNAL,
                    rpcResponse.error!!.code,
                )
            }
        }
}
