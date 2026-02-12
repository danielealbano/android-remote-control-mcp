package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class holding a registered tool's metadata and handler.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: ToolHandler,
)

/**
 * Central registry for all MCP tools.
 *
 * Holds a map of tool name -> [ToolDefinition] and provides:
 * - [register]: adds a tool to the registry
 * - [execute]: dispatches a tool call by name
 * - [listTools]: returns all registered tool definitions
 *
 * [McpProtocolHandler] delegates tool dispatch to this registry.
 * Plans 7, 8, and 9 each call registration functions to register their
 * respective tool categories.
 *
 * The registry is called once during [McpServerService] startup,
 * before the Ktor server begins accepting requests.
 */
@Singleton
class ToolRegistry
    @Inject
    constructor() {
        private val tools = ConcurrentHashMap<String, ToolDefinition>()

        /**
         * Registers a tool with the registry.
         *
         * @param name The MCP tool name (e.g., "get_accessibility_tree").
         * @param description Human-readable tool description.
         * @param inputSchema JSON Schema for the tool's input parameters.
         * @param handler The [ToolHandler] implementation that executes the tool.
         */
        fun register(
            name: String,
            description: String,
            inputSchema: JsonObject,
            handler: ToolHandler,
        ) {
            tools[name] = ToolDefinition(name, description, inputSchema, handler)
            Log.d(TAG, "Registered tool: $name")
        }

        /**
         * Executes a tool by name with the given parameters.
         *
         * @param name The tool name to execute.
         * @param params Optional JSON parameters for the tool.
         * @return The tool's result as a [JsonElement].
         * @throws NoSuchElementException if the tool name is not registered.
         */
        suspend fun execute(
            name: String,
            params: JsonObject?,
        ): JsonElement {
            val toolDef =
                tools[name]
                    ?: throw NoSuchElementException("Unknown tool: $name")
            return toolDef.handler.execute(params)
        }

        /**
         * Returns all registered tool definitions.
         */
        fun listTools(): List<ToolDefinition> = tools.values.toList()

        /**
         * Returns the number of registered tools.
         */
        fun size(): Int = tools.size

        companion object {
            private const val TAG = "MCP:ToolRegistry"
        }
    }
