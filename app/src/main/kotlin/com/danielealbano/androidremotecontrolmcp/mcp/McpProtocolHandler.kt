@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handler interface for individual MCP tools.
 *
 * Each tool category (touch actions, element actions, etc.) implements this
 * interface. Implementations are registered via [McpProtocolHandler.registerTool].
 */
interface ToolHandler {
    /**
     * Executes the tool with the given parameters.
     *
     * @param params The JSON parameters from the MCP tool call, or null if none provided.
     * @return The result as a [JsonElement] to be included in the JSON-RPC response.
     */
    suspend fun execute(params: JsonObject?): JsonElement
}

/**
 * Metadata for a registered MCP tool, used in the tools/list response.
 *
 * @property name The unique tool name (e.g., "tap", "get_accessibility_tree").
 * @property description Human-readable description of what the tool does.
 * @property inputSchema JSON Schema describing the tool's input parameters.
 * @property handler The handler that executes this tool.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: ToolHandler,
)

/**
 * JSON-RPC 2.0 request.
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null,
)

/**
 * JSON-RPC 2.0 response.
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/**
 * JSON-RPC 2.0 error object.
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

private const val JSON_RPC_VERSION = "2.0"

/**
 * Handles MCP JSON-RPC 2.0 requests by routing methods to appropriate handlers.
 *
 * Supports tool registration via [registerTool] and dispatches tool calls to
 * the registered [ToolHandler] instances. Thread-safe via [ConcurrentHashMap].
 *
 * If the official MCP Kotlin SDK supports server-side HTTP transport and is
 * Android-compatible, this class should be replaced with the SDK's built-in
 * protocol handling. See the SDK evaluation note in the plan document.
 */
@Singleton
class McpProtocolHandler
    @Inject
    constructor() {
        private val tools = ConcurrentHashMap<String, ToolDefinition>()

        /**
         * Registers a tool with the protocol handler.
         *
         * @param name Unique tool name.
         * @param description Human-readable description.
         * @param inputSchema JSON Schema for the tool's input parameters.
         * @param handler The handler that executes this tool.
         */
        fun registerTool(
            name: String,
            description: String,
            inputSchema: JsonObject,
            handler: ToolHandler,
        ) {
            tools[name] = ToolDefinition(name, description, inputSchema, handler)
            Log.d(TAG, "Registered tool: $name")
        }

        /**
         * Routes a JSON-RPC request to the appropriate handler method.
         *
         * @param request The parsed JSON-RPC request.
         * @return The JSON-RPC response.
         */
        suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
            if (request.jsonrpc != JSON_RPC_VERSION) {
                return invalidRequest(request.id, "Unsupported JSON-RPC version: ${request.jsonrpc}")
            }

            return when (request.method) {
                METHOD_INITIALIZE -> handleInitialize(request)
                METHOD_TOOLS_LIST -> handleToolsList(request)
                METHOD_TOOLS_CALL -> handleToolCall(request)
                else -> methodNotFound(request.id, request.method)
            }
        }

        private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
            val result =
                buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("serverInfo") {
                        put("name", SERVER_NAME)
                        put("version", BuildConfig.VERSION_NAME)
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                }
            return JsonRpcResponse(id = request.id, result = result)
        }

        private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
            val result =
                buildJsonObject {
                    putJsonArray("tools") {
                        tools.values.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("inputSchema", tool.inputSchema)
                                },
                            )
                        }
                    }
                }
            return JsonRpcResponse(id = request.id, result = result)
        }

        @Suppress("TooGenericExceptionCaught", "ReturnCount")
        private suspend fun handleToolCall(request: JsonRpcRequest): JsonRpcResponse {
            val params =
                request.params
                    ?: return invalidParams(request.id, "Missing params for tools/call")

            val toolName =
                (params["name"] as? JsonPrimitive)?.content
                    ?: return invalidParams(request.id, "Missing 'name' in params")

            val toolDef =
                tools[toolName]
                    ?: return methodNotFound(request.id, toolName)

            val toolArgs = params["arguments"] as? JsonObject

            return try {
                val result = toolDef.handler.execute(toolArgs)
                JsonRpcResponse(id = request.id, result = result)
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed: $toolName", e)
                internalError(request.id, "Tool execution failed: ${e.message ?: "Unknown error"}")
            }
        }

        // --- Error factory methods ---

        fun parseError(id: JsonElement?): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_PARSE, message = "Parse error: invalid JSON"),
            )

        fun invalidRequest(
            id: JsonElement?,
            message: String = "Invalid request",
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_INVALID_REQUEST, message = message),
            )

        fun methodNotFound(
            id: JsonElement?,
            method: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_METHOD_NOT_FOUND, message = "Method not found: $method"),
            )

        fun invalidParams(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_INVALID_PARAMS, message = message),
            )

        fun internalError(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_INTERNAL, message = message),
            )

        fun permissionDenied(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error =
                    JsonRpcError(
                        code = ERROR_PERMISSION_DENIED,
                        message = message,
                        data =
                            buildJsonObject {
                                put("details", message)
                            },
                    ),
            )

        fun elementNotFound(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_ELEMENT_NOT_FOUND, message = message),
            )

        fun actionFailed(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_ACTION_FAILED, message = message),
            )

        fun timeoutError(
            id: JsonElement?,
            message: String,
        ): JsonRpcResponse =
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = ERROR_TIMEOUT, message = message),
            )

        companion object {
            private const val TAG = "MCP:ProtocolHandler"

            const val METHOD_INITIALIZE = "initialize"
            const val METHOD_TOOLS_LIST = "tools/list"
            const val METHOD_TOOLS_CALL = "tools/call"

            const val PROTOCOL_VERSION = "2024-11-05"
            const val SERVER_NAME = "android-remote-control-mcp"

            // Standard JSON-RPC error codes
            const val ERROR_PARSE = -32700
            const val ERROR_INVALID_REQUEST = -32600
            const val ERROR_METHOD_NOT_FOUND = -32601
            const val ERROR_INVALID_PARAMS = -32602
            const val ERROR_INTERNAL = -32603

            // Custom MCP error codes
            const val ERROR_PERMISSION_DENIED = -32001
            const val ERROR_ELEMENT_NOT_FOUND = -32002
            const val ERROR_ACTION_FAILED = -32003
            const val ERROR_TIMEOUT = -32004
        }
    }
