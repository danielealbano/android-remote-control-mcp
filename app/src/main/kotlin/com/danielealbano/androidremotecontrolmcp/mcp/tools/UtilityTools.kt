package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    constructor() : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val service =
                McpAccessibilityService.instance
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    service.getSystemService(ClipboardManager::class.java)
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
                McpContentBuilder.textContent(Json.encodeToString(resultJson))
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

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Get the current clipboard text content",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        put("required", buildJsonArray {})
                    },
                handler = this,
            )
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
    constructor() : ToolHandler {
        override suspend fun execute(params: JsonObject?): JsonElement {
            val text =
                params?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'text'")

            val service =
                McpAccessibilityService.instance
                    ?: throw McpToolException.PermissionDenied(
                        "Accessibility service is not enabled",
                    )

            return try {
                val clipboardManager =
                    service.getSystemService(ClipboardManager::class.java)
                        ?: throw McpToolException.ActionFailed(
                            "ClipboardManager not available",
                        )

                val clip = ClipData.newPlainText("MCP", text)
                clipboardManager.setPrimaryClip(clip)

                Log.d(TAG, "set_clipboard: set ${text.length} chars")

                McpContentBuilder.textContent("Clipboard set successfully (${text.length} characters)")
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

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Set the clipboard content to the specified text",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to set in clipboard")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("text")) })
                    },
                handler = this,
            )
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
    ) : ToolHandler {
        @Suppress("CyclomaticComplexity", "LongMethod")
        override suspend fun execute(params: JsonObject?): JsonElement {
            // Validate parameters
            val byStr =
                params?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val value =
                params["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (value.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            val timeout = params["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            // Poll loop
            val startTime = System.currentTimeMillis()
            var attemptCount = 0

            while (System.currentTimeMillis() - startTime < timeout) {
                attemptCount++

                try {
                    val tree = getFreshTree(treeParser)
                    val elements = elementFinder.findElements(tree, findBy, value, false)

                    if (elements.isNotEmpty()) {
                        val element = elements.first()
                        val elapsed = System.currentTimeMillis() - startTime
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
                        return McpContentBuilder.textContent(Json.encodeToString(resultJson))
                    }
                } catch (e: McpToolException) {
                    // If accessibility service becomes unavailable during polling, propagate
                    if (e.code == McpProtocolHandler.ERROR_PERMISSION_DENIED) throw e
                    // Other errors (e.g., stale tree) — retry on next poll
                    Log.d(TAG, "wait_for_element: poll attempt $attemptCount failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }

            throw McpToolException.Timeout(
                "Element not found within ${timeout}ms (by=$byStr, value='$value', attempts=$attemptCount)",
            )
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Wait until an element matching criteria appears (with timeout)",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
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
                                put("type", "number")
                                put("description", "Timeout in milliseconds (1-30000)")
                                put("default", DEFAULT_TIMEOUT_MS.toInt())
                            }
                        }
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("by"))
                                add(JsonPrimitive("value"))
                            },
                        )
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:WaitForElementTool"
            private const val TOOL_NAME = "wait_for_element"
            private const val POLL_INTERVAL_MS = 500L
            private const val DEFAULT_TIMEOUT_MS = 5000L
            private const val MAX_TIMEOUT_MS = 30000L
        }
    }

/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by detecting when the accessibility tree
 * structure stops changing. Considers UI idle when two consecutive snapshots
 * (separated by [IDLE_CHECK_INTERVAL_MS]) produce the same structural hash.
 */
class WaitForIdleTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
    ) : ToolHandler {
        @Suppress("NestedBlockDepth")
        override suspend fun execute(params: JsonObject?): JsonElement {
            val timeout = params?.get("timeout")?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
            if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
                throw McpToolException.InvalidParams(
                    "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
                )
            }

            val startTime = System.currentTimeMillis()
            var previousHash: Int? = null
            var consecutiveIdleChecks = 0

            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    val tree = getFreshTree(treeParser)
                    val currentHash = computeTreeHash(tree)

                    if (previousHash != null && currentHash == previousHash) {
                        consecutiveIdleChecks++
                        if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
                            val elapsed = System.currentTimeMillis() - startTime
                            Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms")
                            val resultJson =
                                buildJsonObject {
                                    put("message", "UI is idle")
                                    put("elapsedMs", elapsed)
                                }
                            return McpContentBuilder.textContent(Json.encodeToString(resultJson))
                        }
                    } else {
                        consecutiveIdleChecks = 0
                    }

                    previousHash = currentHash
                } catch (e: McpToolException) {
                    if (e.code == McpProtocolHandler.ERROR_PERMISSION_DENIED) throw e
                    // Tree parse failures during transitions — reset idle counter
                    consecutiveIdleChecks = 0
                    previousHash = null
                }

                delay(IDLE_CHECK_INTERVAL_MS)
            }

            throw McpToolException.Timeout(
                "UI did not become idle within ${timeout}ms",
            )
        }

        /**
         * Computes a structural hash of the accessibility tree for change detection.
         *
         * Uses a recursive hash incorporating each node's class name, text, bounds,
         * and child count. This is fast and sufficient for detecting structural changes.
         */
        private fun computeTreeHash(node: AccessibilityNodeData): Int {
            var hash = HASH_SEED
            hash = HASH_MULTIPLIER * hash + (node.className?.hashCode() ?: 0)
            hash = HASH_MULTIPLIER * hash + (node.text?.hashCode() ?: 0)
            hash = HASH_MULTIPLIER * hash + node.bounds.hashCode()
            hash = HASH_MULTIPLIER * hash + node.children.size
            for (child in node.children) {
                hash = HASH_MULTIPLIER * hash + computeTreeHash(child)
            }
            return hash
        }

        fun register(toolRegistry: ToolRegistry) {
            toolRegistry.register(
                name = TOOL_NAME,
                description = "Wait for the UI to become idle (no changes detected)",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("timeout") {
                                put("type", "number")
                                put("description", "Timeout in milliseconds (1-30000)")
                                put("default", DEFAULT_TIMEOUT_MS.toInt())
                            }
                        }
                        put("required", buildJsonArray {})
                    },
                handler = this,
            )
        }

        companion object {
            private const val TAG = "MCP:WaitForIdleTool"
            private const val TOOL_NAME = "wait_for_idle"
            private const val IDLE_CHECK_INTERVAL_MS = 500L
            private const val DEFAULT_TIMEOUT_MS = 3000L
            private const val MAX_TIMEOUT_MS = 30000L
            private const val HASH_SEED = 17
            private const val HASH_MULTIPLIER = 31

            /** Number of consecutive identical tree hashes required to consider UI idle. */
            private const val REQUIRED_IDLE_CHECKS = 2
        }
    }

/**
 * Registers all utility tools with the [ToolRegistry].
 */
fun registerUtilityTools(toolRegistry: ToolRegistry) {
    val treeParser = AccessibilityTreeParser()
    val elementFinder = ElementFinder()
    GetClipboardTool().register(toolRegistry)
    SetClipboardTool().register(toolRegistry)
    WaitForElementTool(treeParser, elementFinder).register(toolRegistry)
    WaitForIdleTool(treeParser).register(toolRegistry)
}
