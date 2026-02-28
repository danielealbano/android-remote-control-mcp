package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// send_intent
// ─────────────────────────────────────────────────────────────────────────────

class SendIntentHandler @Inject constructor(
    private val intentDispatcher: IntentDispatcher,
) {
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexity", "LongMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val type = McpToolUtils.requireString(arguments, "type")
        if (type !in listOf("activity", "broadcast", "service")) {
            throw McpToolException.InvalidParams(
                "type must be 'activity', 'broadcast', or 'service'",
            )
        }
        val action = McpToolUtils.optionalString(arguments, "action", "")
            .ifEmpty { null }
        val data = McpToolUtils.optionalString(arguments, "data", "")
            .ifEmpty { null }
        val component = McpToolUtils.optionalString(arguments, "component", "")
            .ifEmpty { null }

        val extras = extractExtras(arguments)
        val extrasTypes = extractExtrasTypes(arguments)
        val flags = extractFlags(arguments)

        Log.d(TAG, "Executing send_intent: type=$type, action=$action")
        val result = intentDispatcher.sendIntent(type, action, data, component, extras, extrasTypes, flags)
        return McpToolUtils.handleActionResult(result, "Intent sent successfully: type=$type, action=$action")
    }

    @Suppress("CyclomaticComplexity")
    private fun extractExtras(arguments: JsonObject?): Map<String, Any?>? {
        val extrasElement = arguments?.get("extras") ?: return null
        val extrasObj = extrasElement as? JsonObject ?: return null
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in extrasObj) {
            when (value) {
                is JsonPrimitive -> {
                    when {
                        value.isString -> result[key] = value.content
                        value.content == "true" || value.content == "false" ->
                            result[key] = value.content.toBooleanStrict()
                        value.content.contains('.') ->
                            result[key] = value.content.toDoubleOrNull() ?: value.content
                        else ->
                            result[key] = value.content.toLongOrNull() ?: value.content
                    }
                }
                is JsonArray -> {
                    val stringList = value.mapNotNull { element ->
                        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    if (stringList.size == value.size) {
                        result[key] = stringList
                    } else {
                        Log.w(TAG, "Skipping non-string array extra: $key")
                    }
                }
                else -> Log.w(TAG, "Skipping unsupported extra type for key: $key")
            }
        }
        return result.ifEmpty { null }
    }

    private fun extractExtrasTypes(arguments: JsonObject?): Map<String, String>? {
        val element = arguments?.get("extras_types") ?: return null
        val obj = element as? JsonObject ?: return null
        val result = mutableMapOf<String, String>()
        for ((key, value) in obj) {
            val primitive = value as? JsonPrimitive ?: continue
            result[key] = primitive.content
        }
        return result.ifEmpty { null }
    }

    private fun extractFlags(arguments: JsonObject?): List<String>? {
        val element = arguments?.get("flags") ?: return null
        val array = element as? JsonArray ?: return null
        val result = array.mapNotNull { item ->
            (item as? JsonPrimitive)?.content
        }
        return result.ifEmpty { null }
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}send_intent",
            description = "Send an Android intent. Supports starting activities, " +
                "sending broadcasts, and starting services. Use for opening specific " +
                "settings pages, triggering app-specific actions, or sending broadcasts.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("type") {
                        put("type", "string")
                        put(
                            "description",
                            "The intent delivery type: 'activity', 'broadcast', or 'service'",
                        )
                    }
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "The intent action (e.g., 'android.intent.action.VIEW')")
                    }
                    putJsonObject("data") {
                        put("type", "string")
                        put("description", "Data URI for the intent")
                    }
                    putJsonObject("component") {
                        put("type", "string")
                        put(
                            "description",
                            "Target component as 'package/class' " +
                                "(e.g., 'com.example.app/com.example.app.MyActivity')",
                        )
                    }
                    putJsonObject("extras") {
                        put("type", "object")
                        put(
                            "description",
                            "Key-value extras. Values auto-typed: " +
                                "string→String, integer→Int/Long, decimal→Double, " +
                                "boolean→Boolean, string array→StringArrayList",
                        )
                    }
                    putJsonObject("extras_types") {
                        put("type", "object")
                        put(
                            "description",
                            "Type overrides for extras keys. " +
                                "Supported: 'string', 'int', 'long', 'float', 'double', 'boolean'",
                        )
                    }
                    putJsonObject("flags") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put(
                            "description",
                            "Intent flag names (e.g., 'FLAG_ACTIVITY_CLEAR_TOP'). " +
                                "FLAG_ACTIVITY_NEW_TASK auto-added for activity type.",
                        )
                    }
                },
                required = listOf("type"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "send_intent"
        private const val TAG = "MCP:SendIntentTool"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// open_uri
// ─────────────────────────────────────────────────────────────────────────────

class OpenUriHandler @Inject constructor(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uri = McpToolUtils.requireString(arguments, "uri")
        val packageName = McpToolUtils.optionalString(arguments, "package_name", "")
            .ifEmpty { null }
        val mimeType = McpToolUtils.optionalString(arguments, "mime_type", "")
            .ifEmpty { null }

        Log.d(TAG, "Executing open_uri: $uri")
        val result = intentDispatcher.openUri(uri, packageName, mimeType)
        return McpToolUtils.handleActionResult(result, "URI opened successfully: $uri")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}open_uri",
            description = "Open a URI using Android's ACTION_VIEW. Handles https://, http://, " +
                "tel:, mailto:, geo:, content:// URLs, deep links, and custom app schemes " +
                "(e.g., whatsapp://send?phone=...).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "The URI to open")
                    }
                    putJsonObject("package_name") {
                        put("type", "string")
                        put("description", "Force a specific app to handle the URI")
                    }
                    putJsonObject("mime_type") {
                        put("type", "string")
                        put("description", "MIME type hint (useful for content:// URIs)")
                    }
                },
                required = listOf("uri"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_uri"
        private const val TAG = "MCP:OpenUriTool"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

fun registerIntentTools(
    server: Server,
    intentDispatcher: IntentDispatcher,
    toolNamePrefix: String,
) {
    SendIntentHandler(intentDispatcher).register(server, toolNamePrefix)
    OpenUriHandler(intentDispatcher).register(server, toolNamePrefix)
}
