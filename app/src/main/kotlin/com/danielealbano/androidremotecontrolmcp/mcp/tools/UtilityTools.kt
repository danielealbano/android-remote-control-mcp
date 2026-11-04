package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeLock
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.NodeActionLabels
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
        private val privacyToolGate: PrivacyToolGate,
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
                        put("text", privacyToolGate.text(text, "clipboard"))
                    }
                McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
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

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
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
            const val TOOL_NAME = "get_clipboard"
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
        private val substitutor: PlaceholderSubstitutor,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val text =
                arguments
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { substitutor.substitute(it) }
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

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
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
            const val TOOL_NAME = "set_clipboard"
        }
    }

/**
 * MCP tool: wait_for_node
 *
 * Polls the accessibility tree every [POLL_INTERVAL_MS] until a node matching
 * the criteria appears, or the timeout is reached.
 */
class WaitForNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        @Suppress(
            "CyclomaticComplexity",
            "LongMethod",
            "ThrowsCount",
            "InstanceOfCheckForException",
            "NestedBlockDepth",
        )
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // Validate parameters
            val byStr =
                arguments?.get("by")?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'by'")

            val rawValue =
                arguments["value"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'value'")

            if (rawValue.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'value' must be non-empty")
            }

            // Reverse any pseudonym placeholder so polling matches the real on-screen value.
            val value = substitutor.substitute(rawValue)

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
                    // Phase 1: lightweight raw-node existence check (no intermediate objects, no cache)
                    val exists = rawNodeExists(findBy, value, accessibilityServiceProvider)

                    if (exists) {
                        // Phase 2: full parse to build response and populate cache
                        val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
                        val elements = elementFinder.findElements(multiWindowResult.windows, findBy, value, false)

                        if (elements.isNotEmpty()) {
                            val element = elements.first()
                            val elapsed = SystemClock.elapsedRealtime() - startTime
                            Log.d(TAG, "wait_for_node: found after ${elapsed}ms ($attemptCount attempts)")

                            val redactedElement =
                                McpToolUtils
                                    .redactElements(privacyToolGate, multiWindowResult.windows, listOf(element))
                                    .single()
                            val resultJson =
                                buildJsonObject {
                                    put("found", true)
                                    put("elapsedMs", elapsed)
                                    put("attempts", attemptCount)
                                    put("node", McpToolUtils.buildNodeJson(redactedElement))
                                }
                            return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
                        }
                        // Node disappeared between raw check and full parse — continue polling
                        Log.d(TAG, "wait_for_node: raw check found match but full parse did not, retrying")
                    }
                } catch (e: McpToolException) {
                    // If accessibility service becomes unavailable during polling, propagate
                    if (e is McpToolException.PermissionDenied) throw e
                    // Other errors (e.g., stale tree) — retry on next poll
                    Log.d(TAG, "wait_for_node: poll attempt $attemptCount failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }

            val timeoutJson =
                buildJsonObject {
                    put("found", false)
                    put("elapsedMs", timeout)
                    put("attempts", attemptCount)
                    put(
                        "message",
                        "Operation timed out after ${timeout}ms waiting for node " +
                            "(by=$byStr, value='$value'). Retry if the operation is long-running.",
                    )
                }
            return McpToolUtils.untrustedTextResult(Json.encodeToString(timeoutJson))
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Wait until a node matching criteria appears (with timeout). " +
                        "Only use when you need a specific element to appear (e.g., after navigation). " +
                        "Action tools already wait for their gesture/action to complete before returning.",
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
            private const val TAG = "MCP:WaitForNodeTool"
            const val TOOL_NAME = "wait_for_node"
            private const val POLL_INTERVAL_MS = 150L
            private const val MAX_TIMEOUT_MS = 30000L
        }
    }

/**
 * Lightweight raw-node search that walks [AccessibilityNodeInfo] trees directly
 * without creating intermediate [com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData]
 * objects or populating [AccessibilityNodeCache]. Returns true on first match (early exit).
 *
 * Refreshes root nodes and virtual/Compose child nodes using the same logic as
 * [AccessibilityTreeParser.parseNode] to ensure fresh data from Jetpack Compose
 * and other virtual node providers.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled.
 * On API 33+ (this project's minSdk) [AccessibilityNodeInfo.recycle] is a no-op.
 * This matches the pattern used by [TreeFingerprint.populateFingerprintFromRaw].
 *
 * @param findBy The search criteria type.
 * @param value The search value (matched case-insensitive contains, same as
 *   [com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder.matchesValue]
 *   with exactMatch=false).
 * @param accessibilityServiceProvider Provider for accessibility windows and root nodes.
 * @return true if at least one node matching the criteria exists.
 * @throws McpToolException.PermissionDenied if accessibility service is not ready.
 * @throws McpToolException.ActionFailed if no windows or root nodes are available.
 */
internal fun rawNodeExists(
    findBy: FindBy,
    value: String,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): Boolean =
    // Serialize the clear+read critical section against concurrent MCP requests (see
    // AccessibilityTreeLock): a parallel request's cache clear must not wipe the framework tree
    // mid-traversal of this poll read.
    synchronized(AccessibilityTreeLock.monitor) {
        rawNodeExistsLocked(findBy, value, accessibilityServiceProvider)
    }

@Suppress("ThrowsCount", "ReturnCount", "NestedBlockDepth")
private fun rawNodeExistsLocked(
    findBy: FindBy,
    value: String,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): Boolean {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    // Drop the framework's accessibility node cache before this poll read. Chromium WebView
    // suppresses/throttles the content-changed events that invalidate that cache, so a node added
    // by a JavaScript DOM change would otherwise never appear here (refresh() cannot re-fetch what
    // the cache still serves as current) and wait_for_node would time out on present content. See
    // AccessibilityServiceProvider.clearFrameworkNodeCache.
    accessibilityServiceProvider.clearFrameworkNodeCache()

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue
                rootNode.refresh()
                if (rawNodeMatchesRecursive(rootNode, findBy, value, 0)) {
                    return true
                }
            }
            // All windows processed, no match
            return false
        } finally {
            for (w in accessibilityWindows) {
                @Suppress("DEPRECATION")
                w.recycle()
            }
        }
    }

    // Fallback to single-window mode
    val rootNode =
        accessibilityServiceProvider.getRootNode()
            ?: throw McpToolException.ActionFailed(
                "No windows available and no active window root node. " +
                    "The screen may be transitioning.",
            )
    rootNode.refresh()
    return rawNodeMatchesRecursive(rootNode, findBy, value, 0)
}

/**
 * Recursively walks a raw [AccessibilityNodeInfo] tree checking for a match.
 * Returns true immediately on first match (early exit). Refreshes virtual/Compose
 * child nodes before reading their properties.
 *
 * Child nodes obtained via [AccessibilityNodeInfo.getChild] are NOT recycled —
 * [AccessibilityNodeInfo.recycle] is a no-op on API 33+ (this project's minSdk).
 */
@Suppress("ReturnCount")
private fun rawNodeMatchesRecursive(
    node: AccessibilityNodeInfo,
    findBy: FindBy,
    value: String,
    depth: Int,
): Boolean {
    // Check current node
    val nodeValue =
        when (findBy) {
            FindBy.TEXT -> node.text?.toString()
            FindBy.CONTENT_DESC -> node.contentDescription?.toString()
            FindBy.RESOURCE_ID -> node.viewIdResourceName
            FindBy.CLASS_NAME -> node.className?.toString()
        }

    if (nodeValue != null && nodeValue.contains(value, ignoreCase = true)) {
        return true
    }

    // Depth guard — same limit as AccessibilityTreeParser.MAX_TREE_DEPTH
    if (depth >= AccessibilityTreeParser.MAX_TREE_DEPTH) {
        return false
    }

    // Recurse into children
    val childCount = node.childCount
    for (i in 0 until childCount) {
        val child = node.getChild(i) ?: continue

        // Refresh virtual/Compose nodes — same logic as AccessibilityTreeParser.parseNode
        if (child.viewIdResourceName == null ||
            child.availableExtraData.contains(AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY)
        ) {
            child.refresh()
        }

        if (rawNodeMatchesRecursive(child, findBy, value, depth + 1)) {
            return true
        }
    }

    return false
}

/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by comparing accessibility tree fingerprints
 * across consecutive snapshots. Uses a lightweight path that walks raw
 * [android.view.accessibility.AccessibilityNodeInfo] trees directly, without
 * creating intermediate data objects or populating the node cache. Considers UI
 * idle when two consecutive snapshots (separated by [IDLE_CHECK_INTERVAL_MS])
 * meet the [DEFAULT_MATCH_PERCENTAGE] threshold (or the caller-provided
 * `match_percentage`).
 */
class WaitForIdleTool
    @Inject
    constructor(
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
                    val currentFingerprint = generateCurrentFingerprint()

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
                                return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
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
            return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
        }

        private fun generateCurrentFingerprint(): IntArray =
            // Serialize the clear+read critical section against concurrent MCP requests (see
            // AccessibilityTreeLock): a parallel request's cache clear must not wipe the framework
            // tree mid-traversal of this fingerprint read.
            synchronized(AccessibilityTreeLock.monitor) {
                generateCurrentFingerprintLocked()
            }

        @Suppress("ThrowsCount")
        private fun generateCurrentFingerprintLocked(): IntArray {
            if (!accessibilityServiceProvider.isReady()) {
                throw McpToolException.PermissionDenied(
                    "Accessibility service not enabled or not ready. " +
                        "Please enable it in Android Settings > Accessibility.",
                )
            }

            // Drop the framework's accessibility node cache before fingerprinting. Chromium WebView
            // suppresses/throttles the content-changed events that invalidate that cache, so without
            // this the fingerprint would be computed from a stale tree that never reflects ongoing
            // JavaScript DOM changes — reporting the UI as idle while it is still mutating. See
            // AccessibilityServiceProvider.clearFrameworkNodeCache.
            accessibilityServiceProvider.clearFrameworkNodeCache()

            val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

            if (accessibilityWindows.isNotEmpty()) {
                try {
                    val rootNodes =
                        accessibilityWindows.mapNotNull { window ->
                            window.root
                        }
                    if (rootNodes.isEmpty()) {
                        throw McpToolException.ActionFailed("All windows returned null root nodes.")
                    }
                    return treeFingerprint.generateFromRawNodes(rootNodes)
                } finally {
                    for (w in accessibilityWindows) {
                        @Suppress("DEPRECATION")
                        w.recycle()
                    }
                }
            }

            // Fallback to single root node
            val rootNode =
                accessibilityServiceProvider.getRootNode()
                    ?: throw McpToolException.ActionFailed(
                        "No windows available and no active window root node. " +
                            "The screen may be transitioning.",
                    )
            return treeFingerprint.generateFromRawNodes(listOf(rootNode))
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Wait for the UI to become idle (similarity-based change detection). " +
                        "Only use when you need to confirm a specific UI transition completed " +
                        "(e.g., screen finished loading). " +
                        "Action tools already wait for their gesture/action to complete before returning.",
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
            const val TOOL_NAME = "wait_for_idle"
            private const val IDLE_CHECK_INTERVAL_MS = 250L
            private const val MAX_TIMEOUT_MS = 30000L
            private const val DEFAULT_MATCH_PERCENTAGE = 100

            /** Number of consecutive checks meeting the similarity threshold required to consider UI idle. */
            private const val REQUIRED_IDLE_CHECKS = 2
        }
    }

/**
 * MCP tool: get_node_details
 *
 * Retrieves full (untruncated) text and contentDescription for a list of node_ids, plus
 * whether the app marks each node as a password field (`true`/`false` in the `password` column)
 * and the labels of its accessibility actions (the `actions` column, [NodeActionLabels.SEPARATOR]-joined).
 * Use after get_screen_state when text or desc columns were truncated.
 */
class GetNodeDetailsTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            // 1. Parse "node_ids" parameter — required, JSON array of strings
            val idsElement =
                arguments?.get("node_ids")
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'node_ids'")
            val idsArray =
                (idsElement as? JsonArray)
                    ?: throw McpToolException.InvalidParams("Parameter 'node_ids' must be an array of strings")
            if (idsArray.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'node_ids' must not be empty")
            }
            val ids =
                idsArray.map { element ->
                    val primitive =
                        (element as? JsonPrimitive)
                            ?: throw McpToolException.InvalidParams("Each element in 'node_ids' must be a string")
                    if (!primitive.isString) {
                        throw McpToolException.InvalidParams("Each element in 'node_ids' must be a string")
                    }
                    primitive.content
                }

            // 2. Get fresh multi-window snapshot
            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

            // 3. Resolve nodes and batch-redact their text, desc and action labels in one model pass, each with its
            //    node's context. Every node contributes its text and desc slots, then one slot per action label.
            val nodes = ids.map { id -> id to elementFinder.findNodeById(multiWindowResult.windows, id) }
            val redacted =
                privacyToolGate.nodeTexts(
                    multiWindowResult.windows,
                    nodes.flatMap { (id, node) ->
                        listOf(node?.text to id, node?.contentDescription to id) + node?.actions.orEmpty().map { it to id }
                    },
                )

            // 4. Build TSV output
            val sb = StringBuilder()
            sb.append("node_id\ttext\tdesc\tpassword\tactions\n")

            var offset = 0
            for ((id, node) in nodes) {
                val actionCount = node?.actions?.size ?: 0
                if (node != null) {
                    val text = sanitizeForTsv(redacted[offset])
                    val desc = sanitizeForTsv(redacted[offset + 1])
                    val actions = actionsColumn(redacted.subList(offset + 2, offset + 2 + actionCount))
                    sb.append("$id\t$text\t$desc\t${node.isPassword}\t$actions\n")
                } else {
                    sb.append("$id\tnot_found\tnot_found\tnot_found\tnot_found\n")
                }
                offset += 2 + actionCount
            }

            Log.d(TAG, "get_node_details: node_ids=${ids.size}")

            return McpToolUtils.untrustedTextResult(sb.toString().trimEnd('\n'))
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

        /** The `actions` column: the labels (already sanitized when parsed) joined, or "-" when there are none. */
        private fun actionsColumn(labels: List<String?>): String =
            if (labels.isEmpty()) "-" else labels.joinToString(NodeActionLabels.SEPARATOR) { it.orEmpty() }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Retrieve full untruncated text and description for nodes by node_id, " +
                        "whether the app marks each node as a password field, " +
                        "and the labels of its accessibility actions " +
                        "(run one with ${toolNamePrefix}perform_node_action). " +
                        "Use after ${toolNamePrefix}get_screen_state when text or desc was truncated.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putJsonObject("node_ids") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "string")
                                    }
                                    put("description", "List of node_ids to retrieve details for")
                                }
                            },
                        required = listOf("node_ids"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:GetNodeDetailsTool"
            const val TOOL_NAME = "get_node_details"
        }
    }

/**
 * Registers all utility tools with the given [Server].
 */
@Suppress("LongParameterList")
fun registerUtilityTools(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
    privacyToolGate: PrivacyToolGate,
    substitutor: PlaceholderSubstitutor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetClipboardTool.TOOL_NAME)) {
        GetClipboardTool(accessibilityServiceProvider, privacyToolGate).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(SetClipboardTool.TOOL_NAME)) {
        SetClipboardTool(accessibilityServiceProvider, substitutor).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(WaitForNodeTool.TOOL_NAME)) {
        WaitForNodeTool(
            treeParser,
            elementFinder,
            accessibilityServiceProvider,
            nodeCache,
            privacyToolGate,
            substitutor,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(WaitForIdleTool.TOOL_NAME)) {
        WaitForIdleTool(accessibilityServiceProvider)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(GetNodeDetailsTool.TOOL_NAME)) {
        GetNodeDetailsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache, privacyToolGate)
            .register(registrar, toolNamePrefix)
    }
}
