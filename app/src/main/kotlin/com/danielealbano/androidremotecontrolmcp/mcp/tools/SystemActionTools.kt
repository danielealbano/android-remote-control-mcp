@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * @return [CallToolResult] with confirmation message.
 */
private suspend fun executeSystemAction(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    actionName: String,
    action: suspend () -> Result<Unit>,
): CallToolResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
        )
    }

    val result = action()
    result.onFailure { exception ->
        throw McpToolException.ActionFailed(
            "$actionName failed: ${exception.message ?: "Unknown error"}",
        )
    }

    return McpToolUtils.textResult("$actionName executed successfully")
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
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressBackHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Back button press") {
                actionExecutor.pressBack()
            }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Presses the back button (global accessibility action).",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
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
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressHomeHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Home button press") {
                actionExecutor.pressHome()
            }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Navigates to the home screen.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
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
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class PressRecentsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Recents button press") {
                actionExecutor.pressRecents()
            }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Opens the recent apps screen.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
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
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class OpenNotificationsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Open notifications") {
                actionExecutor.openNotifications()
            }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Pulls down the notification shade.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
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
 *   - PermissionDenied if accessibility service is not enabled
 *   - ActionFailed if action execution failed
 */
class OpenQuickSettingsHandler
    @Inject
    constructor(
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult =
            executeSystemAction(accessibilityServiceProvider, "Open quick settings") {
                actionExecutor.openQuickSettings()
            }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Opens the quick settings panel.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
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
 *   - InvalidParams if parameters are invalid
 *   - ActionFailed if logcat execution fails
 */
class GetDeviceLogsHandler
    @Inject
    constructor() {
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val lastLines = parseLastLines(arguments)
            val since = arguments?.get("since")?.jsonPrimitive?.contentOrNull
            val until = arguments?.get("until")?.jsonPrimitive?.contentOrNull
            val tag = arguments?.get("tag")?.jsonPrimitive?.contentOrNull
            val levelStr = arguments?.get("level")?.jsonPrimitive?.contentOrNull ?: DEFAULT_LEVEL
            val packageName = arguments?.get("package_name")?.jsonPrimitive?.contentOrNull

            if (levelStr !in VALID_LEVELS) {
                throw McpToolException.InvalidParams(
                    "level must be one of: ${VALID_LEVELS.joinToString(", ")}. Got: '$levelStr'",
                )
            }

            return try {
                val pid = if (packageName != null) resolvePid(packageName) else null
                // Request one extra line to reliably detect truncation
                val requestLines = lastLines + 1
                val command = buildLogcatCommand(requestLines, since, tag, levelStr, pid)
                val process = Runtime.getRuntime().exec(command.toTypedArray())
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0 && output.isEmpty()) {
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    throw McpToolException.ActionFailed(
                        "logcat command failed (exit $exitCode): $errorOutput",
                    )
                }

                var allLines = output.lines().filter { it.isNotBlank() }
                if (until != null) {
                    allLines = filterByUntil(allLines, until)
                }
                val truncated = allLines.size > lastLines
                val lines = if (truncated) allLines.take(lastLines) else allLines

                val resultJson =
                    buildJsonObject {
                        put("logs", lines.joinToString("\n"))
                        put("line_count", lines.size)
                        put("truncated", truncated)
                    }

                McpToolUtils.textResult(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to retrieve device logs: ${e.message ?: "Unknown error"}",
                )
            }
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private fun parseLastLines(params: JsonObject?): Int {
            val element = params?.get("last_lines") ?: return DEFAULT_LAST_LINES
            val lastLines =
                try {
                    element.jsonPrimitive.int
                } catch (e: Exception) {
                    throw McpToolException.InvalidParams(
                        "last_lines must be an integer, got $element",
                    )
                }
            if (lastLines < 1 || lastLines > MAX_LAST_LINES) {
                throw McpToolException.InvalidParams(
                    "last_lines must be between 1 and $MAX_LAST_LINES, got $lastLines",
                )
            }
            return lastLines
        }

        private fun buildLogcatCommand(
            lastLines: Int,
            since: String?,
            tag: String?,
            level: String,
            pid: Int?,
        ): List<String> {
            val cmd = mutableListOf("logcat", "-d")

            if (since != null) {
                cmd.addAll(listOf("-T", since))
            } else {
                cmd.addAll(listOf("-t", lastLines.toString()))
            }

            if (pid != null) {
                cmd.addAll(listOf("--pid", pid.toString()))
            }

            if (tag != null) {
                cmd.addAll(listOf("-s", "$tag:$level"))
            } else {
                cmd.add("*:$level")
            }

            return cmd
        }

        /**
         * Resolves the PID of a running package via `pidof`.
         *
         * @return The PID, or null if the package is not running or pidof fails.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun resolvePid(packageName: String): Int? =
            try {
                val process = Runtime.getRuntime().exec(arrayOf("pidof", "-s", packageName))
                val output =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                process.waitFor()
                output.toIntOrNull()
            } catch (e: Exception) {
                null
            }

        /**
         * Filters logcat lines to only include those with timestamps at or before [until].
         *
         * Expects [until] in ISO 8601 format (e.g., `2024-01-15T10:30:00`).
         * Logcat timestamps are in `MM-DD HH:MM:SS.mmm` format. The comparison
         * is best-effort: the year component from ISO 8601 is dropped, and
         * the month-day + time portion is compared lexicographically.
         */
        private fun filterByUntil(
            lines: List<String>,
            until: String,
        ): List<String> {
            val untilComparable = isoToLogcatTimestamp(until) ?: return lines
            return lines.filter { line ->
                val lineTimestamp = line.take(LOGCAT_TIMESTAMP_LENGTH).trim()
                if (lineTimestamp.length < LOGCAT_TIMESTAMP_LENGTH) {
                    return@filter true
                }
                lineTimestamp <= untilComparable
            }
        }

        /**
         * Converts an ISO 8601 timestamp to logcat's `MM-DD HH:MM:SS.mmm` format
         * for lexicographic comparison.
         *
         * @return The converted timestamp, or null if parsing fails.
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
        private fun isoToLogcatTimestamp(iso: String): String? {
            return try {
                val parts = iso.split("T")
                if (parts.size != ISO_PARTS_COUNT) return null

                val dateParts = parts[0].split("-")
                if (dateParts.size != DATE_PARTS_COUNT) return null

                val monthDay = "${dateParts[1]}-${dateParts[2]}"
                val timePart =
                    if (parts[1].contains(".")) parts[1] else "${parts[1]}.000"
                "$monthDay $timePart"
            } catch (e: Exception) {
                null
            }
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Retrieves device logcat logs filtered by time range, tag, level, or package name.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("last_lines") {
                                    put("type", "integer")
                                    put("description", "Number of most recent log lines to return (1-1000)")
                                    put("default", DEFAULT_LAST_LINES)
                                }
                                putJsonObject("since") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "ISO 8601 timestamp to filter logs from (e.g., 2024-01-15T10:30:00)",
                                    )
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
                            },
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_device_logs"
            private const val DEFAULT_LAST_LINES = 100
            private const val MAX_LAST_LINES = 1000
            private const val DEFAULT_LEVEL = "D"
            private const val LOGCAT_TIMESTAMP_LENGTH = 18
            private const val ISO_PARTS_COUNT = 2
            private const val DATE_PARTS_COUNT = 3
            private val VALID_LEVELS = setOf("V", "D", "I", "W", "E", "F")
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all system action tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
fun registerSystemActionTools(
    server: Server,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
) {
    PressBackHandler(actionExecutor, accessibilityServiceProvider).register(server)
    PressHomeHandler(actionExecutor, accessibilityServiceProvider).register(server)
    PressRecentsHandler(actionExecutor, accessibilityServiceProvider).register(server)
    OpenNotificationsHandler(actionExecutor, accessibilityServiceProvider).register(server)
    OpenQuickSettingsHandler(actionExecutor, accessibilityServiceProvider).register(server)
    GetDeviceLogsHandler().register(server)
}
