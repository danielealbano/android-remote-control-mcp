package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

@DisplayName("MCP Protocol Integration Tests")
class McpProtocolIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `initialize returns server info with correct protocol version`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                    }

                val response =
                    client.post(McpIntegrationTestHelper.INITIALIZE_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val result = rpcResponse.result!!.jsonObject
                assertEquals(
                    McpProtocolHandler.PROTOCOL_VERSION,
                    result["protocolVersion"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    McpProtocolHandler.SERVER_NAME,
                    result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `tools-list returns all 29 registered tools`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    client.get(McpIntegrationTestHelper.TOOLS_LIST_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val rpcResponse = response.toJsonRpcResponse()
                assertNull(rpcResponse.error)
                assertNotNull(rpcResponse.result)

                val tools = rpcResponse.result!!.jsonObject["tools"]!!.jsonArray
                assertEquals(EXPECTED_TOOL_COUNT, tools.size)
            }
        }

    @Test
    fun `tools-list includes correct input schemas for each tool`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    client.get(McpIntegrationTestHelper.TOOLS_LIST_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                    }

                val rpcResponse = response.toJsonRpcResponse()
                val tools = rpcResponse.result!!.jsonObject["tools"]!!.jsonArray

                tools.forEach { tool ->
                    val toolObj = tool.jsonObject
                    assertNotNull(toolObj["name"], "Tool missing 'name' field")
                    assertNotNull(toolObj["description"], "Tool missing 'description' field")
                    assertNotNull(toolObj["inputSchema"], "Tool missing 'inputSchema' field")

                    val schema = toolObj["inputSchema"]!!.jsonObject
                    assertEquals(
                        "object",
                        schema["type"]?.jsonPrimitive?.content,
                        "inputSchema.type must be 'object' for tool ${toolObj["name"]}",
                    )
                }
            }
        }

    @Test
    fun `unknown method returns JSON-RPC error -32601 method not found`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "nonexistent/method")
                    }

                val response =
                    client.post(McpIntegrationTestHelper.INITIALIZE_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val rpcResponse = response.toJsonRpcResponse()
                val error = rpcResponse.error
                assertNotNull(error)
                assertEquals(
                    McpProtocolHandler.ERROR_METHOD_NOT_FOUND,
                    error!!.code,
                )
                assertTrue(error.message.contains("not found"))
            }
        }

    @Test
    fun `malformed JSON body returns JSON-RPC error -32700 parse error`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                        contentType(ContentType.Application.Json)
                        setBody("{invalid json")
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val rpcResponse = response.toJsonRpcResponse()
                assertNotNull(rpcResponse.error)
                assertEquals(McpProtocolHandler.ERROR_PARSE, rpcResponse.error!!.code)
            }
        }

    @Test
    fun `wrong jsonrpc version returns JSON-RPC error -32600 invalid request`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "1.0")
                        put("id", 1)
                        put("method", "tools/call")
                    }

                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.OK, response.status)

                val rpcResponse = response.toJsonRpcResponse()
                val error = rpcResponse.error
                assertNotNull(error)
                assertEquals(
                    McpProtocolHandler.ERROR_INVALID_REQUEST,
                    error!!.code,
                )
                assertTrue(error.message.contains("version"))
            }
        }

    companion object {
        private const val EXPECTED_TOOL_COUNT = 29
    }
}
