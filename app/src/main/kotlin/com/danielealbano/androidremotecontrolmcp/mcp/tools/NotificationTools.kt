package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProviderImpl
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

private val HEX_ID_PATTERN = Regex("[0-9a-f]{${NotificationProviderImpl.HASH_HEX_LENGTH}}")

private fun validateNotificationId(notificationId: String) {
    if (notificationId.isEmpty()) {
        throw McpToolException.InvalidParams("Parameter 'notification_id' must not be empty")
    }
    if (!notificationId.matches(HEX_ID_PATTERN)) {
        throw McpToolException.InvalidParams(
            "Parameter 'notification_id' must be a ${NotificationProviderImpl.HASH_HEX_LENGTH}-char hex string",
        )
    }
}

private fun validateActionId(actionId: String) {
    if (actionId.isEmpty()) {
        throw McpToolException.InvalidParams("Parameter 'action_id' must not be empty")
    }
    if (!actionId.matches(HEX_ID_PATTERN)) {
        throw McpToolException.InvalidParams(
            "Parameter 'action_id' must be a ${NotificationProviderImpl.HASH_HEX_LENGTH}-char hex string",
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// notification_list
// ─────────────────────────────────────────────────────────────────────────────

class NotificationListHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val packageName =
                McpToolUtils
                    .optionalString(arguments, "package_name", "")
                    .ifEmpty { null }
            val limit =
                McpToolUtils
                    .optionalInt(arguments, "limit", 0)
                    .let { if (it <= 0) null else it }
            Logger.d(TAG, "Executing notification_list, package=$packageName, limit=$limit")
            val notifications = notificationProvider.getNotifications(packageName, limit)
            val json =
                buildJsonObject {
                    putJsonArray("notifications") {
                        for (n in notifications) {
                            add(
                                buildJsonObject {
                                    put("notification_id", n.notificationId)
                                    put("package_name", n.packageName)
                                    put("app_name", n.appName)
                                    put("title", n.title)
                                    put("text", n.text)
                                    put("big_text", n.bigText)
                                    put("sub_text", n.subText)
                                    put("timestamp", n.timestamp)
                                    put("is_ongoing", n.isOngoing)
                                    put("is_clearable", n.isClearable)
                                    put("category", n.category)
                                    put("group_key", n.groupKey)
                                    putJsonArray("actions") {
                                        for (a in n.actions) {
                                            add(
                                                buildJsonObject {
                                                    put("action_id", a.actionId)
                                                    put("title", a.title)
                                                    put("accepts_text", a.acceptsText)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                    put("count", notifications.size)
                }
            return McpToolUtils.textResult(json.toString())
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_list",
                description =
                    "List active notifications with structured data " +
                        "(app, title, text, actions, timestamp). Returns notification_id for " +
                        "each notification and action_id for each action button.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("package_name") {
                                    put("type", "string")
                                    put("description", "Filter by source app package name")
                                }
                                putJsonObject("limit") {
                                    put("type", "integer")
                                    put("description", "Maximum number of notifications to return")
                                }
                            },
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_list"
            private const val TAG = "MCP:NotificationListHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// notification_open
// ─────────────────────────────────────────────────────────────────────────────

class NotificationOpenHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val notificationId = McpToolUtils.requireString(arguments, "notification_id")
            validateNotificationId(notificationId)
            Logger.d(TAG, "Executing notification_open for id: $notificationId")
            val result = notificationProvider.openNotification(notificationId)
            return McpToolUtils.handleActionResult(result, "Notification opened")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_open",
                description =
                    "Open/tap a notification (fires its content intent). " +
                        "Use notification_id from notification_list.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("notification_id") {
                                    put("type", "string")
                                    put("description", "The notification_id from notification_list")
                                }
                            },
                        required = listOf("notification_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_open"
            private const val TAG = "MCP:NotificationOpenHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// notification_dismiss
// ─────────────────────────────────────────────────────────────────────────────

class NotificationDismissHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val notificationId = McpToolUtils.requireString(arguments, "notification_id")
            validateNotificationId(notificationId)
            Logger.d(TAG, "Executing notification_dismiss for id: $notificationId")
            val result = notificationProvider.dismissNotification(notificationId)
            return McpToolUtils.handleActionResult(result, "Notification dismissed")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_dismiss",
                description = "Dismiss/remove a notification. Use notification_id from notification_list.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("notification_id") {
                                    put("type", "string")
                                    put("description", "The notification_id from notification_list")
                                }
                            },
                        required = listOf("notification_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_dismiss"
            private const val TAG = "MCP:NotificationDismissHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// notification_snooze
// ─────────────────────────────────────────────────────────────────────────────

class NotificationSnoozeHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val notificationId = McpToolUtils.requireString(arguments, "notification_id")
            validateNotificationId(notificationId)
            val durationMs = McpToolUtils.requireLong(arguments, "duration_ms")
            if (durationMs <= 0) {
                throw McpToolException.InvalidParams(
                    "Parameter 'duration_ms' must be positive, got: $durationMs",
                )
            }
            if (durationMs > MAX_SNOOZE_DURATION_MS) {
                throw McpToolException.InvalidParams(
                    "Parameter 'duration_ms' must not exceed $MAX_SNOOZE_DURATION_MS (7 days), got: $durationMs",
                )
            }
            Logger.d(TAG, "Executing notification_snooze for id: $notificationId, duration: ${durationMs}ms")
            val result = notificationProvider.snoozeNotification(notificationId, durationMs)
            return McpToolUtils.handleActionResult(result, "Notification snoozed for ${durationMs}ms")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_snooze",
                description =
                    "Snooze a notification for a duration. " +
                        "The notification reappears after the specified time. " +
                        "Use notification_id from notification_list.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("notification_id") {
                                    put("type", "string")
                                    put("description", "The notification_id from notification_list")
                                }
                                putJsonObject("duration_ms") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Snooze duration in milliseconds (must be positive, max 604800000 = 7 days)",
                                    )
                                }
                            },
                        required = listOf("notification_id", "duration_ms"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_snooze"
            private const val TAG = "MCP:NotificationSnoozeHandler"
            private const val MAX_SNOOZE_DURATION_MS = 604_800_000L
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// notification_action
// ─────────────────────────────────────────────────────────────────────────────

class NotificationActionHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val actionId = McpToolUtils.requireString(arguments, "action_id")
            validateActionId(actionId)
            Logger.d(TAG, "Executing notification_action for id: $actionId")
            val result = notificationProvider.executeAction(actionId)
            return McpToolUtils.handleActionResult(result, "Notification action executed")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_action",
                description =
                    "Execute a notification action button. " +
                        "Use action_id from notification_list.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("action_id") {
                                    put("type", "string")
                                    put("description", "The action_id from notification_list")
                                }
                            },
                        required = listOf("action_id"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_action"
            private const val TAG = "MCP:NotificationActionHandler"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// notification_reply
// ─────────────────────────────────────────────────────────────────────────────

class NotificationReplyHandler
    @Inject
    constructor(
        private val notificationProvider: NotificationProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            if (!notificationProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Notification listener not enabled. Enable in Settings > Notification access.",
                )
            }
            val actionId = McpToolUtils.requireString(arguments, "action_id")
            validateActionId(actionId)
            val text = McpToolUtils.requireString(arguments, "text")
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must not be empty")
            }
            if (text.length > MAX_REPLY_TEXT_LENGTH) {
                throw McpToolException.InvalidParams(
                    "Parameter 'text' must not exceed $MAX_REPLY_TEXT_LENGTH characters",
                )
            }
            Logger.d(TAG, "Executing notification_reply for id: $actionId")
            val result = notificationProvider.replyToAction(actionId, text)
            return McpToolUtils.handleActionResult(result, "Reply sent")
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
        ) {
            server.addTool(
                name = "${toolNamePrefix}notification_reply",
                description =
                    "Reply to a notification action that accepts text input " +
                        "(e.g., messaging apps). Use action_id from notification_list " +
                        "where accepts_text is true.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("action_id") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "The action_id from notification_list (must have accepts_text=true)",
                                    )
                                }
                                putJsonObject("text") {
                                    put("type", "string")
                                    put("description", "The reply text to send")
                                }
                            },
                        required = listOf("action_id", "text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "notification_reply"
            private const val TAG = "MCP:NotificationReplyHandler"
            private const val MAX_REPLY_TEXT_LENGTH = 10_000
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

fun registerNotificationTools(
    server: Server,
    notificationProvider: NotificationProvider,
    toolNamePrefix: String,
) {
    NotificationListHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationOpenHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationDismissHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationSnoozeHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationActionHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationReplyHandler(notificationProvider).register(server, toolNamePrefix)
}
