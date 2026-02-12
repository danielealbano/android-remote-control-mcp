@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Executes a system action via [ActionExecutor], with standard error handling.
 *
 * Checks accessibility service availability, executes the action, and returns
 * a text content response on success. Throws [McpToolException] on failure.
 *
 * @param actionName Human-readable name of the action (for error/success messages).
 * @param action Suspend function that performs the system action and returns [Result].
 * @return MCP content [JsonElement] with confirmation message.
 */
private suspend fun executeSystemAction(
    actionName: String,
    action: suspend () -> Result<Unit>,
): JsonElement {
    McpAccessibilityService.instance
        ?: throw McpToolException.PermissionDenied(
            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
        )

    val result = action()
    result.onFailure { exception ->
        throw McpToolException.ActionFailed(
            "$actionName failed: ${exception.message ?: "Unknown error"}",
        )
    }

    return McpContentBuilder.textContent("$actionName executed successfully")
}

// ─────────────────────────────────────────────────────────────────────────────
// press_back
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_back`.
 *
 * Presses the system back button via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Back button press executed successfully" }] }`
 * **Errors**:
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if action execution failed
 */
class PressBackHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            return executeSystemAction("Back button press") {
                actionExecutor.pressBack()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Presses the back button (global accessibility action).",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "press_back"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_home
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_home`.
 *
 * Navigates to the home screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Home button press executed successfully" }] }`
 * **Errors**:
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if action execution failed
 */
class PressHomeHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            return executeSystemAction("Home button press") {
                actionExecutor.pressHome()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Navigates to the home screen.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "press_home"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// press_recents
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `press_recents`.
 *
 * Opens the recent apps screen via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Recents button press executed successfully" }] }`
 * **Errors**:
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if action execution failed
 */
class PressRecentsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            return executeSystemAction("Recents button press") {
                actionExecutor.pressRecents()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Opens the recent apps screen.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "press_recents"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// open_notifications
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_notifications`.
 *
 * Pulls down the notification shade via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Open notifications executed successfully" }] }`
 * **Errors**:
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if action execution failed
 */
class OpenNotificationsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            return executeSystemAction("Open notifications") {
                actionExecutor.openNotifications()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Pulls down the notification shade.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "open_notifications"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// open_quick_settings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `open_quick_settings`.
 *
 * Opens the quick settings panel via accessibility global action.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "Open quick settings executed successfully" }] }`
 * **Errors**:
 *   - -32001 if accessibility service is not enabled
 *   - -32003 if action execution failed
 */
class OpenQuickSettingsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
    ) : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            return executeSystemAction("Open quick settings") {
                actionExecutor.openQuickSettings()
            }
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Opens the quick settings panel.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "open_quick_settings"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// get_device_logs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_device_logs`.
 *
 * Retrieves device logcat logs filtered by time range, last N lines, tag, level,
 * or package name. Useful for debugging app behavior and system events.
 *
 * **Input**: `{ "last_lines": 100, "since": "...", "until": "...",
 *   "tag": "...", "level": "D", "package_name": "..." }`
 * **Output**: `{ "content": [{ "type": "text",
 *   "text": "{\"logs\":\"...\",\"line_count\":100,\"truncated\":false}" }] }`
 * **Errors**:
 *   - -32602 if parameters are invalid
 *   - -32003 if logcat execution fails
 */
class GetDeviceLogsHandler
    @Inject
    constructor() : ToolHandler {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override suspend fun execute(params: JsonObject?): JsonElement {
            val lastLines = params?.get("last_lines")?.jsonPrimitive?.int ?: DEFAULT_LAST_LINES
            if (lastLines < 1 || lastLines > MAX_LAST_LINES) {
                throw McpToolException.InvalidParams(
                    "last_lines must be between 1 and $MAX_LAST_LINES, got $lastLines",
                )
            }

            val since = params?.get("since")?.jsonPrimitive?.contentOrNull
            val until = params?.get("until")?.jsonPrimitive?.contentOrNull
            val tag = params?.get("tag")?.jsonPrimitive?.contentOrNull
            val levelStr = params?.get("level")?.jsonPrimitive?.contentOrNull ?: DEFAULT_LEVEL
            val packageName = params?.get("package_name")?.jsonPrimitive?.contentOrNull

            if (levelStr !in VALID_LEVELS) {
                throw McpToolException.InvalidParams(
                    "level must be one of: ${VALID_LEVELS.joinToString(", ")}. Got: '$levelStr'",
                )
            }

            return try {
                val command = buildLogcatCommand(lastLines, since, until, tag, levelStr, packageName)
                val process = Runtime.getRuntime().exec(command.toTypedArray())
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0 && output.isEmpty()) {
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    throw McpToolException.ActionFailed(
                        "logcat command failed (exit $exitCode): $errorOutput",
                    )
                }

                val lines = output.lines().filter { it.isNotBlank() }
                val truncated = lines.size >= lastLines

                val resultJson =
                    buildJsonObject {
                        put("logs", lines.joinToString("\n"))
                        put("line_count", lines.size)
                        put("truncated", truncated)
                    }

                McpContentBuilder.textContent(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to retrieve device logs: ${e.message ?: "Unknown error"}",
                )
            }
        }

        @Suppress("UnusedParameter", "LongParameterList")
        private fun buildLogcatCommand(
            lastLines: Int,
            since: String?,
            until: String?,
            tag: String?,
            level: String,
            packageName: String?,
        ): List<String> {
            val cmd = mutableListOf("logcat", "-d")

            if (since != null) {
                cmd.addAll(listOf("-T", since))
            } else {
                cmd.addAll(listOf("-t", lastLines.toString()))
            }

            if (tag != null) {
                cmd.addAll(listOf("-s", "$tag:$level"))
            } else {
                cmd.add("*:$level")
            }

            return cmd
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Retrieves device logcat logs filtered by time range, tag, level, or package name.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("last_lines") {
                                put("type", "integer")
                                put("description", "Number of most recent log lines to return (1-1000)")
                                put("default", DEFAULT_LAST_LINES)
                            }
                            putJsonObject("since") {
                                put("type", "string")
                                put("description", "ISO 8601 timestamp to filter logs from (e.g., 2024-01-15T10:30:00)")
                            }
                            putJsonObject("until") {
                                put("type", "string")
                                put("description", "ISO 8601 timestamp to filter logs until (used with since)")
                            }
                            putJsonObject("tag") {
                                put("type", "string")
                                put("description", "Filter by log tag (exact match, e.g., MCP:ServerService)")
                            }
                            putJsonObject("level") {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        VALID_LEVELS.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put("description", "Minimum log level to include")
                                put("default", DEFAULT_LEVEL)
                            }
                            putJsonObject("package_name") {
                                put("type", "string")
                                put("description", "Filter logs by package name")
                            }
                        }
                        putJsonArray("required") {}
                    },
                handler = this,
            )
        }

        companion object {
            const val TOOL_NAME = "get_device_logs"
            private const val DEFAULT_LAST_LINES = 100
            private const val MAX_LAST_LINES = 1000
            private const val DEFAULT_LEVEL = "D"
            private val VALID_LEVELS = setOf("V", "D", "I", "W", "E", "F")
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all system action tools with the given [ToolRegistry].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerSystemActionTools(toolRegistry: ToolRegistry) {
    val actionExecutor = ActionExecutor()
    PressBackHandler(actionExecutor).register(toolRegistry)
    PressHomeHandler(actionExecutor).register(toolRegistry)
    PressRecentsHandler(actionExecutor).register(toolRegistry)
    OpenNotificationsHandler(actionExecutor).register(toolRegistry)
    OpenQuickSettingsHandler(actionExecutor).register(toolRegistry)
    GetDeviceLogsHandler().register(toolRegistry)
}
