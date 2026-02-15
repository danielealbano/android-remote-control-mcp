package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// open_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_app`.
 *
 * Opens (launches) an application by its package ID.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text", "text": "Application '<package_id>' launched successfully." }] }`
 */
class OpenAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")

            Log.d(TAG, "Executing open_app for package: $packageId")
            val result = appManager.openApp(packageId)
            result.onFailure { e ->
                throw McpToolException.ActionFailed("Failed to open application '$packageId': ${e.message}")
            }
            return McpToolUtils.textResult("Application '$packageId' launched successfully.")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Opens (launches) an application by its package ID. " +
                        "The app must be installed and have a launchable activity.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("package_id") {
                                    put("type", "string")
                                    put("description", "The application package name (e.g., 'com.example.app')")
                                }
                            },
                        required = listOf("package_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "open_app"
            private const val TAG = "MCP:OpenAppHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// list_apps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_apps`.
 *
 * Lists installed applications on the device with optional filtering.
 *
 * **Input**: `{ "filter": "all"|"user"|"system" (optional), "name_query": "<string>" (optional) }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "[{\"package_id\":\"...\",\"name\":\"...\", ...}]" }] }`
 */
class ListAppsHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val filterStr = McpToolUtils.optionalString(arguments, "filter", "all")
            val filter =
                when (filterStr.lowercase()) {
                    "all" -> AppFilter.ALL
                    "user" -> AppFilter.USER
                    "system" -> AppFilter.SYSTEM
                    else -> throw McpToolException.InvalidParams(
                        "Parameter 'filter' must be one of: 'all', 'user', 'system'. Got: '$filterStr'",
                    )
                }

            val nameQuery = McpToolUtils.optionalString(arguments, "name_query", "").ifEmpty { null }

            Log.d(TAG, "Executing list_apps with filter=$filter, nameQuery=$nameQuery")
            val apps = appManager.listInstalledApps(filter, nameQuery)

            val jsonResult =
                buildJsonArray {
                    apps.forEach { app ->
                        add(
                            buildJsonObject {
                                put("package_id", app.packageId)
                                put("name", app.name)
                                if (app.versionName != null) {
                                    put("version_name", app.versionName)
                                }
                                put("version_code", app.versionCode)
                                put("is_system", app.isSystemApp)
                            },
                        )
                    }
                }

            return McpToolUtils.textResult(Json.encodeToString(jsonResult))
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Lists installed applications on the device. " +
                        "Can filter by type (user, system, all) and by name substring.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("filter") {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("all"))
                                            add(JsonPrimitive("user"))
                                            add(JsonPrimitive("system"))
                                        },
                                    )
                                    put("description", "Filter by app type: 'all', 'user', or 'system'")
                                    put("default", "all")
                                }
                                putJsonObject("name_query") {
                                    put("type", "string")
                                    put("description", "Case-insensitive substring to filter by app name")
                                }
                            },
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "list_apps"
            private const val TAG = "MCP:ListAppsHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// close_app
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `close_app`.
 *
 * Kills a background application process.
 *
 * **Input**: `{ "package_id": "<string>" }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "Kill signal sent for application '<package_id>'..." }] }`
 */
class CloseAppHandler
    @Inject
    constructor(
        private val appManager: AppManager,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val packageId = McpToolUtils.requireString(arguments, "package_id")

            Log.d(TAG, "Executing close_app for package: $packageId")
            val result = appManager.closeApp(packageId)
            result.onFailure { e ->
                throw McpToolException.ActionFailed("Failed to close application '$packageId': ${e.message}")
            }
            return McpToolUtils.textResult(
                "Kill signal sent for application '$packageId'. " +
                    "Note: this only affects background processes.",
            )
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Kills a background application process. This only works for apps " +
                        "that are in the background. For foreground apps that are hung or " +
                        "unresponsive, first use the '${toolNamePrefix}press_home' tool to send the app to the " +
                        "background, wait briefly, then call this tool. Note: some system " +
                        "processes may restart automatically after being killed.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("package_id") {
                                    put("type", "string")
                                    put("description", "The application package name (e.g., 'com.example.app')")
                                }
                            },
                        required = listOf("package_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "close_app"
            private const val TAG = "MCP:CloseAppHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all app management tools with the given [Server].
 */
fun registerAppManagementTools(
    server: Server,
    appManager: AppManager,
    toolNamePrefix: String,
) {
    OpenAppHandler(appManager).register(server, toolNamePrefix)
    ListAppsHandler(appManager).register(server, toolNamePrefix)
    CloseAppHandler(appManager).register(server, toolNamePrefix)
}
