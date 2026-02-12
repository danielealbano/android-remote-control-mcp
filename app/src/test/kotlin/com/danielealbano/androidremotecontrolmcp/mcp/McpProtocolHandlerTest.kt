package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpProtocolHandlerTest {
    private lateinit var handler: McpProtocolHandler
    private lateinit var toolRegistry: ToolRegistry

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        toolRegistry = ToolRegistry()
        handler = McpProtocolHandler(toolRegistry)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // --- handleInitialize tests ---

    @Test
    fun `handleRequest with initialize returns server info and capabilities`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = McpProtocolHandler.METHOD_INITIALIZE,
                )
            val response = handler.handleRequest(request)

            assertNull(response.error)
            assertNotNull(response.result)
            val result = response.result!!.jsonObject
            assertEquals(
                McpProtocolHandler.PROTOCOL_VERSION,
                result["protocolVersion"]?.jsonPrimitive?.content,
            )
            assertEquals(
                McpProtocolHandler.SERVER_NAME,
                result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
            )
        }

    // --- handleToolsList tests ---

    @Test
    fun `handleRequest with tools list returns empty list when no tools registered`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = McpProtocolHandler.METHOD_TOOLS_LIST,
                )
            val response = handler.handleRequest(request)

            assertNull(response.error)
            assertNotNull(response.result)
            val result = response.result!!.jsonObject
            val tools = result["tools"]
            assertNotNull(tools)
        }

    @Test
    fun `handleRequest with tools list returns registered tools`() =
        runTest {
            val schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                }
            toolRegistry.register(
                "test_tool",
                "A test tool",
                schema,
                object : ToolHandler {
                    override suspend fun execute(params: JsonObject?): kotlinx.serialization.json.JsonElement =
                        buildJsonObject { put("result", JsonPrimitive("ok")) }
                },
            )

            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(3),
                    method = McpProtocolHandler.METHOD_TOOLS_LIST,
                )
            val response = handler.handleRequest(request)

            assertNull(response.error)
            val result = response.result!!.jsonObject
            val tools = result["tools"] as? JsonArray
            assertNotNull(tools)
            assertEquals(1, tools!!.size)
            assertEquals("test_tool", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
        }

    // --- handleToolCall tests ---

    @Test
    fun `handleRequest with tools call dispatches to registered handler`() =
        runTest {
            val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
            toolRegistry.register(
                "echo_tool",
                "Echoes params",
                schema,
                object : ToolHandler {
                    override suspend fun execute(params: JsonObject?): kotlinx.serialization.json.JsonElement =
                        buildJsonObject { put("echo", JsonPrimitive("hello")) }
                },
            )

            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(4),
                    method = McpProtocolHandler.METHOD_TOOLS_CALL,
                    params =
                        buildJsonObject {
                            put("name", JsonPrimitive("echo_tool"))
                            put("arguments", buildJsonObject { })
                        },
                )
            val response = handler.handleRequest(request)

            assertNull(response.error)
            assertEquals("hello", response.result?.jsonObject?.get("echo")?.jsonPrimitive?.content)
        }

    @Test
    fun `handleRequest with tools call returns method not found for unknown tool`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(5),
                    method = McpProtocolHandler.METHOD_TOOLS_CALL,
                    params =
                        buildJsonObject {
                            put("name", JsonPrimitive("nonexistent_tool"))
                        },
                )
            val response = handler.handleRequest(request)

            assertNotNull(response.error)
            assertEquals(McpProtocolHandler.ERROR_METHOD_NOT_FOUND, response.error!!.code)
        }

    @Test
    fun `handleRequest with tools call returns invalid params when params missing`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(6),
                    method = McpProtocolHandler.METHOD_TOOLS_CALL,
                    params = null,
                )
            val response = handler.handleRequest(request)

            assertNotNull(response.error)
            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, response.error!!.code)
        }

    @Test
    fun `handleRequest with tools call returns invalid params when name missing`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(7),
                    method = McpProtocolHandler.METHOD_TOOLS_CALL,
                    params = buildJsonObject { },
                )
            val response = handler.handleRequest(request)

            assertNotNull(response.error)
            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, response.error!!.code)
        }

    // --- Unknown method test ---

    @Test
    fun `handleRequest with unknown method returns method not found`() =
        runTest {
            val request =
                JsonRpcRequest(
                    id = JsonPrimitive(8),
                    method = "unknown/method",
                )
            val response = handler.handleRequest(request)

            assertNotNull(response.error)
            assertEquals(McpProtocolHandler.ERROR_METHOD_NOT_FOUND, response.error!!.code)
        }

    // --- Invalid JSON-RPC version test ---

    @Test
    fun `handleRequest with wrong jsonrpc version returns invalid request`() =
        runTest {
            val request =
                JsonRpcRequest(
                    jsonrpc = "1.0",
                    id = JsonPrimitive(9),
                    method = "initialize",
                )
            val response = handler.handleRequest(request)

            assertNotNull(response.error)
            assertEquals(McpProtocolHandler.ERROR_INVALID_REQUEST, response.error!!.code)
        }

    // --- Error factory method tests ---

    @Test
    fun `parseError returns code -32700`() {
        val response = handler.parseError(JsonPrimitive(10))
        assertEquals(McpProtocolHandler.ERROR_PARSE, response.error!!.code)
    }

    @Test
    fun `invalidRequest returns code -32600`() {
        val response = handler.invalidRequest(JsonPrimitive(11))
        assertEquals(McpProtocolHandler.ERROR_INVALID_REQUEST, response.error!!.code)
    }

    @Test
    fun `methodNotFound returns code -32601`() {
        val response = handler.methodNotFound(JsonPrimitive(12), "test")
        assertEquals(McpProtocolHandler.ERROR_METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `invalidParams returns code -32602`() {
        val response = handler.invalidParams(JsonPrimitive(13), "bad param")
        assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun `internalError returns code -32603`() {
        val response = handler.internalError(JsonPrimitive(14), "oops")
        assertEquals(McpProtocolHandler.ERROR_INTERNAL, response.error!!.code)
    }

    @Test
    fun `permissionDenied returns code -32001`() {
        val response = handler.permissionDenied(JsonPrimitive(15), "no access")
        assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, response.error!!.code)
    }

    @Test
    fun `elementNotFound returns code -32002`() {
        val response = handler.elementNotFound(JsonPrimitive(16), "not found")
        assertEquals(McpProtocolHandler.ERROR_ELEMENT_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `actionFailed returns code -32003`() {
        val response = handler.actionFailed(JsonPrimitive(17), "failed")
        assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, response.error!!.code)
    }

    @Test
    fun `timeoutError returns code -32004`() {
        val response = handler.timeoutError(JsonPrimitive(18), "timed out")
        assertEquals(McpProtocolHandler.ERROR_TIMEOUT, response.error!!.code)
    }

    // --- Response structure tests ---

    @Test
    fun `all responses include jsonrpc version 2_0`() =
        runTest {
            val request = JsonRpcRequest(id = JsonPrimitive(19), method = "initialize")
            val response = handler.handleRequest(request)
            assertEquals("2.0", response.jsonrpc)
        }

    @Test
    fun `all responses echo the request id`() =
        runTest {
            val requestId = JsonPrimitive(42)
            val request = JsonRpcRequest(id = requestId, method = "initialize")
            val response = handler.handleRequest(request)
            assertEquals(requestId, response.id)
        }
}
