@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.integration

import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Text Input Integration Tests")
class TextInputIntegrationTest {
    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_edit",
                        className = "android.widget.EditText",
                        text = "",
                        bounds = BoundsData(50, 800, 500, 900),
                        editable = true,
                        focusable = true,
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
    fun `input_text with element_id calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { deps.treeParser.parseTree(mockRootNode) } returns sampleTree
            every { mockRootNode.recycle() } returns Unit

            coEvery {
                deps.actionExecutor.clickNode("node_edit", sampleTree)
            } returns Result.success(Unit)
            coEvery {
                deps.actionExecutor.setTextOnNode("node_edit", "Hello World", sampleTree)
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "input_text",
                        arguments =
                            buildJsonObject {
                                put("text", "Hello World")
                                put("element_id", "node_edit")
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }

    @Test
    fun `input_text with missing text parameter returns error -32602`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    sendToolCall(
                        toolName = "input_text",
                        arguments = buildJsonObject {},
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(INVALID_PARAMS_CODE, rpcResponse.error!!.code)
            }
        }

    companion object {
        private const val INVALID_PARAMS_CODE = -32602
    }
}
