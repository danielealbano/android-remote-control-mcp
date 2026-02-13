package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
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

@DisplayName("Gesture Integration Tests")
class GestureIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `pinch with valid parameters calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.pinch(540f, 1200f, 2.0f, any())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "pinch",
                        arguments =
                            buildJsonObject {
                                put("center_x", 540)
                                put("center_y", 1200)
                                put("scale", 2.0)
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }

    @Test
    fun `pinch with invalid scale returns error -32602`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    sendToolCall(
                        toolName = "pinch",
                        arguments =
                            buildJsonObject {
                                put("center_x", 540)
                                put("center_y", 1200)
                                put("scale", 0.0)
                            },
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
