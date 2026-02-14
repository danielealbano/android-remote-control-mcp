package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * MCP tool: get_clipboard
 *
 * Gets the current clipboard text content. Accessibility services are exempt
 * from Android 10+ clipboard access restrictions.
 */
class GetClipboardTool
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount", "UnusedParameter")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val context =
                accessibilityServiceProvider.getContext()
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    context.getSystemService(ClipboardManager::class.java)
                        ?: throw McpToolException.ActionFailed(
                            "ClipboardManager not available",
                        )

                val clip = clipboardManager.primaryClip
                val text =
                    if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).text?.toString()
                    } else {
                        null
                    }

                Log.d(TAG, "get_clipboard: text=${if (text != null) "${text.length} chars" else "null"}")

                val resultJson =
                    buildJsonObject {
                        put("text", text)
                    }
                McpToolUtils.textResult(Json.encodeToString(resultJson))
            } catch (e: McpToolException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                e: Exception,
            ) {
                Log.e(TAG, "Clipboard access failed", e)
                throw McpToolException.ActionFailed(
                    "Clipboard access failed: ${e.message}",
                )
            }
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Get the current clipboard text content",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject {},
                        required = listOf(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:GetClipboardTool"
            private const val TOOL_NAME = "get_clipboard"
        }
    }

/**
 * MCP tool: set_clipboard
 *
 * Sets the clipboard content to the specified text.
 */
class SetClipboardTool
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val text =
                arguments?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")

            val context =
                accessibilityServiceProvider.getContext()
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    context.getSystemService(ClipboardManager::class.java)
                        ?: throw McpToolException.ActionFailed(
                            "ClipboardManager not available",
                        )

                val clip = ClipData.newPlainText("MCP", text)
                clipboardManager.setPrimaryClip(clip)

                Log.d(TAG, "set_clipboard: set ${text.length} chars")

                McpToolUtils.textResult("Clipboard set successfully (${text.length} characters)")
            } catch (e: McpToolException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                e: Exception,
            ) {
                Log.e(TAG, "Clipboard set failed", e)
                throw McpToolException.ActionFailed(
                    "Clipboard set failed: ${e.message}",
                )
            }
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Set the clipboard content to the specified text",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("text") {
                                    put("type", "string")
                                    put("description", "Text to set in clipboard")
                                }
                            },
                        required = listOf("text"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:SetClipboardTool"
            private const val TOOL_NAME = "set_clipboard"
        }
    }

/**
 * MCP tool: wait_for_element
 *
 * Polls the accessibility tree every [POLL_INTERVAL_MS] until an element matching
 * the criteria appears, or the timeout is reached.
 */
class WaitForElementTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("CyclomaticComplexity", "LongMethod", "ThrowsCount", "InstanceOfCheckForException")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // Validate parameters
            val byStr =
                arguments?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val value =
                arguments["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (value.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            val timeout =
                arguments["timeout"]?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            // Poll loop
            val startTime = SystemClock.elapsedRealtime()
            var attemptCount = 0

            while (SystemClock.elapsedRealtime() - startTime < timeout) {
                attemptCount++

                try {
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val elements = elementFinder.findElements(tree, findBy, value, false)

                    if (elements.isNotEmpty()) {
                        val element = elements.first()
                        val elapsed = SystemClock.elapsedRealtime() - startTime
                        Log.d(TAG, "wait_for_element: found after ${elapsed}ms ($attemptCount attempts)")

                        val resultJson =
                            buildJsonObject {
                                put("found", true)
                                put("elapsedMs", elapsed)
                                put("attempts", attemptCount)
                                putJsonObject("element") {
                                    put("id", element.id)
                                    put("text", element.text)
                                    put("contentDescription", element.contentDescription)
                                    put("resourceId", element.resourceId)
                                    put("className", element.className)
                                    putJsonObject("bounds") {
                                        put("left", element.bounds.left)
                                        put("top", element.bounds.top)
                                        put("right", element.bounds.right)
                                        put("bottom", element.bounds.bottom)
                                    }
                                    put("clickable", element.clickable)
                                    put("enabled", element.enabled)
                                }
                            }
                        return McpToolUtils.textResult(Json.encodeToString(resultJson))
                    }
                } catch (e: McpToolException) {
                    // If accessibility service becomes unavailable during polling, propagate
                    if (e is McpToolException.PermissionDenied) throw e
                    // Other errors (e.g., stale tree) — retry on next poll
                    Log.d(TAG, "wait_for_element: poll attempt $attemptCount failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }

            return McpToolUtils.textResult(
                "Operation timed out after ${timeout}ms waiting for element (by=$byStr, value='$value', " +
                    "attempts=$attemptCount). Retry if the operation is long-running.",
            )
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Wait until an element matching criteria appears (with timeout)",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("by") {
                                    put("type", "string")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("text"))
                                            add(JsonPrimitive("content_desc"))
                                            add(JsonPrimitive("resource_id"))
                                            add(JsonPrimitive("class_name"))
                                        },
                                    )
                                    put("description", "Search criteria type")
                                }
                                putJsonObject("value") {
                                    put("type", "string")
                                    put("description", "Search value")
                                }
                                putJsonObject("timeout") {
                                    put("type", "integer")
                                    put("description", "Timeout in milliseconds (1-30000)")
                                }
                            },
                        required = listOf("by", "value", "timeout"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:WaitForElementTool"
            private const val TOOL_NAME = "wait_for_element"
            private const val POLL_INTERVAL_MS = 500L
            private const val MAX_TIMEOUT_MS = 30000L
        }
    }

/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by comparing accessibility tree fingerprints
 * across consecutive snapshots. Uses a 256-slot histogram fingerprint and
 * normalized difference to compute a similarity percentage. Considers UI idle
 * when two consecutive snapshots (separated by [IDLE_CHECK_INTERVAL_MS]) meet
 * the [DEFAULT_MATCH_PERCENTAGE] threshold (or the caller-provided `match_percentage`).
 */
class WaitForIdleTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        private val treeFingerprint = TreeFingerprint()

        @Suppress(
            "CyclomaticComplexity",
            "NestedBlockDepth",
            "ThrowsCount",
            "InstanceOfCheckForException",
            "LongMethod",
        )
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val timeout =
                arguments?.get("timeout")?.jsonPrimitive?.longOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            val matchPercentage =
                arguments["match_percentage"]
                    ?.jsonPrimitive
                    ?.longOrNull
                    ?.toInt()
                    ?: DEFAULT_MATCH_PERCENTAGE
            if (matchPercentage < 0 || matchPercentage > TreeFingerprint.FULL_MATCH_PERCENTAGE) {
                throw McpToolException.InvalidParams(
                    "match_percentage must be between 0 and " +
                        "${TreeFingerprint.FULL_MATCH_PERCENTAGE}, got: $matchPercentage",
                )
            }

            val startTime = SystemClock.elapsedRealtime()
            var previousFingerprint: IntArray? = null
            var consecutiveIdleChecks = 0
            var lastSimilarity = 0

            while (SystemClock.elapsedRealtime() - startTime < timeout) {
                try {
                    val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                    val currentFingerprint = treeFingerprint.generate(tree)

                    if (previousFingerprint != null) {
                        val similarity = treeFingerprint.compare(previousFingerprint, currentFingerprint)
                        lastSimilarity = similarity

                        if (similarity >= matchPercentage) {
                            consecutiveIdleChecks++
                            if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
                                val elapsed = SystemClock.elapsedRealtime() - startTime
                                Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms (similarity=$similarity%)")
                                val resultJson =
                                    buildJsonObject {
                                        put("message", "UI is idle")
                                        put("elapsedMs", elapsed)
                                        put("similarity", similarity)
                                    }
                                return McpToolUtils.textResult(Json.encodeToString(resultJson))
                            }
                        } else {
                            consecutiveIdleChecks = 0
                        }
                    }

                    previousFingerprint = currentFingerprint
                } catch (e: McpToolException) {
                    if (e is McpToolException.PermissionDenied) throw e
                    // Tree parse failures during transitions — reset idle counter
                    consecutiveIdleChecks = 0
                    previousFingerprint = null
                }

                delay(IDLE_CHECK_INTERVAL_MS)
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            val resultJson =
                buildJsonObject {
                    put(
                        "message",
                        "Operation timed out after ${elapsed}ms waiting for UI idle." +
                            " Retry if the operation is long-running.",
                    )
                    put("elapsedMs", elapsed)
                    put("similarity", lastSimilarity)
                }
            return McpToolUtils.textResult(Json.encodeToString(resultJson))
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description = "Wait for the UI to become idle (similarity-based change detection)",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("timeout") {
                                    put("type", "integer")
                                    put("description", "Timeout in milliseconds (1-30000)")
                                }
                                putJsonObject("match_percentage") {
                                    put("type", "integer")
                                    put(
                                        "description",
                                        "Similarity threshold percentage (0-100, default 100). " +
                                            "100 = exact match, lower values tolerate minor UI changes",
                                    )
                                    put("default", DEFAULT_MATCH_PERCENTAGE)
                                }
                            },
                        required = listOf("timeout"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:WaitForIdleTool"
            private const val TOOL_NAME = "wait_for_idle"
            private const val IDLE_CHECK_INTERVAL_MS = 500L
            private const val MAX_TIMEOUT_MS = 30000L
            private const val DEFAULT_MATCH_PERCENTAGE = 100

            /** Number of consecutive checks meeting the similarity threshold required to consider UI idle. */
            private const val REQUIRED_IDLE_CHECKS = 2
        }
    }

/**
 * MCP tool: get_element_details
 *
 * Retrieves full (untruncated) text and contentDescription for a list of element IDs.
 * Use after get_screen_state when text or desc columns were truncated.
 */
class GetElementDetailsTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // 1. Parse "ids" parameter — required, JSON array of strings
            val idsElement =
                arguments?.get("ids")
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'ids'")
            val idsArray =
                (idsElement as? JsonArray)
                    ?: throw McpToolException.InvalidParams("Parameter 'ids' must be an array of strings")
            if (idsArray.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'ids' must not be empty")
            }
            val ids =
                idsArray.map { element ->
                    val primitive =
                        (element as? JsonPrimitive)
                            ?: throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
                    if (!primitive.isString) {
                        throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
                    }
                    primitive.content
                }

            // 2. Get fresh tree
            val tree = getFreshTree(treeParser, accessibilityServiceProvider)

            // 3. Build TSV output
            val sb = StringBuilder()
            sb.append("id\ttext\tdesc\n")

            for (id in ids) {
                val node = elementFinder.findNodeById(tree, id)
                if (node != null) {
                    val text = sanitizeForTsv(node.text)
                    val desc = sanitizeForTsv(node.contentDescription)
                    sb.append("$id\t$text\t$desc\n")
                } else {
                    sb.append("$id\tnot_found\tnot_found\n")
                }
            }

            Log.d(TAG, "get_element_details: ids=${ids.size}")

            return McpToolUtils.textResult(sb.toString().trimEnd('\n'))
        }

        /**
         * Sanitizes text for TSV: replaces tabs/newlines with spaces.
         * Returns "-" if null or empty.
         * Does NOT truncate — this tool returns full values.
         */
        private fun sanitizeForTsv(text: String?): String {
            if (text.isNullOrEmpty()) return "-"
            val sanitized =
                text
                    .replace('\t', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
            return if (sanitized.isEmpty()) "-" else sanitized
        }

        fun register(server: Server) {
            server.addTool(
                name = TOOL_NAME,
                description =
                    "Retrieve full untruncated text and description for elements by ID. " +
                        "Use after get_screen_state when text or desc was truncated.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("ids") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "string")
                                    }
                                    put("description", "List of element IDs to retrieve details for")
                                }
                            },
                        required = listOf("ids"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:GetElementDetailsTool"
            private const val TOOL_NAME = "get_element_details"
        }
    }

/**
 * Registers all utility tools with the given [Server].
 */
fun registerUtilityTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    accessibilityServiceProvider: AccessibilityServiceProvider,
) {
    GetClipboardTool(accessibilityServiceProvider).register(server)
    SetClipboardTool(accessibilityServiceProvider).register(server)
    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider).register(server)
    WaitForIdleTool(treeParser, accessibilityServiceProvider).register(server)
    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider).register(server)
}
