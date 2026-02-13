package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Touch Action Integration Tests")
class TouchActionIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `tap with valid coordinates calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "tap",
                        arguments =
                            buildJsonObject {
                                put("x", 500)
                                put("y", 800)
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)
            }
        }

    @Test
    fun `tap with missing x coordinate returns JSON-RPC error -32602`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    sendToolCall(
                        toolName = "tap",
                        arguments =
                            buildJsonObject {
                                put("y", 800)
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(INVALID_PARAMS_CODE, rpcResponse.error!!.code)
            }
        }

    @Test
    fun `swipe with valid coordinates calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.actionExecutor.swipe(100f, 200f, 300f, 400f, any())
            } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { _ ->
                val response =
                    sendToolCall(
                        toolName = "swipe",
                        arguments =
                            buildJsonObject {
                                put("x1", 100)
                                put("y1", 200)
                                put("x2", 300)
                                put("y2", 400)
                            },
                    )

                assertEquals(HttpStatusCode.OK, response.status)
                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val text =
                    rpcResponse.result!!.jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!.jsonPrimitive.content
                assertTrue(text.contains("Swipe executed"))
            }
        }

    companion object {
        private const val INVALID_PARAMS_CODE = -32602
    }
}
