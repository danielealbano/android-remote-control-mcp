@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeLock
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import kotlin.random.Random

/**
 * MCP tool: find_nodes
 *
 * Finds UI nodes matching the specified criteria in the accessibility tree.
 * Returns an array of matching nodes (may be empty — empty is NOT an error).
 */
class FindNodesTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        @Suppress("ThrowsCount")
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

            // Reverse any pseudonym placeholder so the search matches the real on-screen value.
            val value = substitutor.substitute(rawValue)

            val exactMatch = arguments["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false
            val visibleOnly = McpToolUtils.optionalBoolean(arguments, PARAM_VISIBLE_ONLY, false)

            val findBy =
                mapFindBy(byStr)
                    ?: throw McpToolException.InvalidParams(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )

            // Get fresh multi-window accessibility snapshot
            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)

            // Search across all windows
            val elements =
                elementFinder
                    .findElements(result.windows, findBy, value, exactMatch)
                    .filter { !visibleOnly || it.visible }

            Log.d(TAG, "find_nodes: by=$byStr, visibleOnly=$visibleOnly, found=${elements.size}")

            val redactedFields =
                privacyToolGate.texts(
                    elements.flatMap { listOf(it.text to "node text", it.contentDescription to "node description") },
                )
            val redactedElements =
                elements.mapIndexed { index, element ->
                    element.copy(text = redactedFields[index * 2], contentDescription = redactedFields[index * 2 + 1])
                }

            val resultJson =
                buildJsonObject {
                    put(
                        "nodes",
                        buildJsonArray {
                            redactedElements.forEach { element ->
                                add(McpToolUtils.buildNodeJson(element))
                            }
                        },
                    )
                }

            return McpToolUtils.untrustedTextResult(Json.encodeToString(resultJson))
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Find UI nodes matching the specified criteria " +
                        "(text, content_desc, resource_id, class_name)",
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
                                putJsonObject("exact_match") {
                                    put("type", "boolean")
                                    put("default", false)
                                    put(
                                        "description",
                                        "If true, match exactly. If false, match contains (case-insensitive)",
                                    )
                                }
                                putJsonObject(PARAM_VISIBLE_ONLY) {
                                    put("type", "boolean")
                                    put("default", false)
                                    put("description", "If true, return only the matches that are on screen")
                                }
                            },
                        required = listOf("by", "value"),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:FindNodesTool"
            const val TOOL_NAME = "find_nodes"
            internal const val PARAM_VISIBLE_ONLY = "visible_only"
        }
    }

/**
 * MCP tool: click_node
 *
 * Clicks the accessibility node identified by node_id or by a selector.
 */
class ClickNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val targetResolver: NodeTargetResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId =
                when (val resolution = targetResolver.resolve(target, multiWindowResult.windows)) {
                    is NodeResolution.Resolved -> resolution.nodeId
                    is NodeResolution.Ambiguous -> return resolution.result
                }

            val result = actionExecutor.clickNode(nodeId, multiWindowResult.windows)
            result.onFailure { e -> mapNodeActionException(e, nodeId) }

            Log.d(TAG, "click_node: nodeId=$nodeId succeeded")
            return McpToolUtils.textResult("Click performed on node '$nodeId'")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Click the specified accessibility node, by node ID or by a selector (by + value, the single " +
                        "on-screen match). Returns after the click is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject { putNodeTargetProperties(toolNamePrefix) },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ClickNodeTool"
            const val TOOL_NAME = "click_node"
        }
    }

/**
 * MCP tool: long_click_node
 *
 * Long-clicks the accessibility node identified by node_id or by a selector.
 */
class LongClickNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val targetResolver: NodeTargetResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId =
                when (val resolution = targetResolver.resolve(target, multiWindowResult.windows)) {
                    is NodeResolution.Resolved -> resolution.nodeId
                    is NodeResolution.Ambiguous -> return resolution.result
                }

            val result = actionExecutor.longClickNode(nodeId, multiWindowResult.windows)
            result.onFailure { e -> mapNodeActionException(e, nodeId) }

            Log.d(TAG, "long_click_node: nodeId=$nodeId succeeded")
            return McpToolUtils.textResult("Long-click performed on node '$nodeId'")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Long-click the specified accessibility node, by node ID or by a selector (by + value, the " +
                        "single on-screen match). Returns after the action is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject { putNodeTargetProperties(toolNamePrefix) },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:LongClickNodeTool"
            const val TOOL_NAME = "long_click_node"
        }
    }

/**
 * MCP tool: tap_node
 *
 * Performs a gesture-based tap at a random point within the bounds of the
 * node identified by node_id or by a selector. Unlike click_node (which uses the
 * accessibility ACTION_CLICK), this performs a coordinate-based touch gesture.
 */
class TapNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val targetResolver: NodeTargetResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)

            val insetPercentage =
                McpToolUtils.optionalFloat(arguments, "inset_percentage", DEFAULT_INSET_PERCENTAGE)

            if (insetPercentage < MIN_INSET_PERCENTAGE || insetPercentage > MAX_INSET_PERCENTAGE) {
                throw McpToolException.InvalidParams(
                    "Parameter 'inset_percentage' must be between $MIN_INSET_PERCENTAGE and $MAX_INSET_PERCENTAGE",
                )
            }

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId =
                when (val resolution = targetResolver.resolve(target, multiWindowResult.windows)) {
                    is NodeResolution.Resolved -> resolution.nodeId
                    is NodeResolution.Ambiguous -> return resolution.result
                }

            val node =
                elementFinder.findNodeById(multiWindowResult.windows, nodeId)
                    ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found")

            val bounds = node.bounds
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top

            val (tapX, tapY) =
                if (width < SMALL_ELEMENT_THRESHOLD && height < SMALL_ELEMENT_THRESHOLD) {
                    Pair(bounds.left.toFloat(), bounds.top.toFloat())
                } else {
                    val insetFraction = insetPercentage / PERCENTAGE_DIVISOR
                    val insetLeft = bounds.left + width * insetFraction
                    val insetRight = bounds.right - width * insetFraction
                    val insetTop = bounds.top + height * insetFraction
                    val insetBottom = bounds.bottom - height * insetFraction
                    Pair(
                        randomFloatInRange(insetLeft, insetRight),
                        randomFloatInRange(insetTop, insetBottom),
                    )
                }

            Log.d(TAG, "tap_node: nodeId=$nodeId, tapAt=($tapX, $tapY)")

            val result = actionExecutor.tap(tapX, tapY)
            return McpToolUtils.handleActionResult(
                result,
                "Tap executed at (${tapX.toInt()}, ${tapY.toInt()}) within node '$nodeId'",
            )
        }

        internal fun randomFloatInRange(
            min: Float,
            max: Float,
        ): Float {
            if (min >= max) return min
            return min + Random.nextFloat() * (max - min)
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Performs a gesture-based tap at a random point within the bounds of the " +
                        "node identified by node_id or by a selector (by + value, the single on-screen match). " +
                        "Unlike click_node (which uses the " +
                        "accessibility ACTION_CLICK), this performs a coordinate-based touch gesture. " +
                        "The tap point is randomized within the node bounds, inset by a configurable " +
                        "percentage (default 5%) from each edge to avoid hitting borders. " +
                        "Returns after the gesture completes.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
                                putJsonObject("inset_percentage") {
                                    put("type", "number")
                                    put("default", DEFAULT_INSET_PERCENTAGE.toDouble())
                                    put(
                                        "description",
                                        "Percentage to inset from each edge of the node bounds " +
                                            "(0.0-45.0). Default 5.0",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TapNodeTool"
            const val TOOL_NAME = "tap_node"
            private const val DEFAULT_INSET_PERCENTAGE = 5.0f
            private const val MIN_INSET_PERCENTAGE = 0.0f
            private const val MAX_INSET_PERCENTAGE = 45.0f
            private const val SMALL_ELEMENT_THRESHOLD = 5
            private const val PERCENTAGE_DIVISOR = 100.0f
        }
    }

/**
 * MCP tool: scroll_to_node
 *
 * Scrolls the nearest scrollable container of the specified node in the given direction until the node is
 * visible, up to [MAX_SCROLL_ATTEMPTS] scrolls; stops early when the container does not scroll any further.
 *
 * Node ids hash the node's bounds, so the ids of the target and of its container change as soon as they move.
 * After each scroll the container is located again by its place in its window, and the target is found again:
 * when given by id, inside that container by its resource id, class, text and content description; when given
 * by a selector, by matching the selector again across all windows. The result names the node's current id.
 *
 * A target given by id is never confused with a look-alike: its attributes must tell it apart in its container
 * from the first read, and once it drops out of the container the tool stops instead of taking another node.
 */
class ScrollToNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val targetResolver: NodeTargetResolver,
    ) {
        /** What identifies a node given by id once it has moved: its id does not, these attributes do. */
        private data class NodeIdentity(
            val resourceId: String?,
            val className: String?,
            val text: String?,
            val contentDescription: String?,
        ) {
            fun matches(node: AccessibilityNodeData): Boolean =
                node.resourceId == resourceId &&
                    node.className == className &&
                    node.text == text &&
                    node.contentDescription == contentDescription
        }

        /**
         * The scrollable container of the target. Its id changes when it moves (e.g. a collapsing toolbar shifts a
         * list); its place does not: its window and the child indexes leading to it, checked against its class and
         * resource id.
         */
        private class TrackedContainer(
            val windowId: Int,
            val childIndexPath: List<Int>,
            val resourceId: String?,
            val className: String?,
        ) {
            /** The container in [windows], or null when its place no longer holds a scrollable node like it. */
            fun locate(windows: List<WindowData>): AccessibilityNodeData? =
                childIndexPath
                    .fold(windows.firstOrNull { it.windowId == windowId }?.tree) { node, index ->
                        node?.children?.getOrNull(index)
                    }?.takeIf { it.scrollable && it.resourceId == resourceId && it.className == className }
        }

        /** The target being scrolled to, what finds it again once it has moved, and the container to scroll. */
        private class TrackedTarget(
            val target: NodeTarget,
            val identity: NodeIdentity,
            val originalNodeId: String,
            val container: TrackedContainer,
        )

        /** The target found again after a scroll. */
        private sealed interface Refound {
            data class Found(
                val node: AccessibilityNodeData,
            ) : Refound

            data object Missing : Refound

            data class Ambiguous(
                val result: CallToolResult,
            ) : Refound

            /** The tool cannot go on: [error] says why. */
            data class Failed(
                val error: McpToolException,
            ) : Refound
        }

        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)
            val direction = requireScrollDirection(arguments)

            // Parse multi-window trees and find the node (a selector matches on- and off-screen nodes)
            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            return when (val resolution = targetResolver.resolve(target, result.windows, onScreenOnly = false)) {
                is NodeResolution.Ambiguous -> resolution.result
                is NodeResolution.Resolved -> scrollTo(target, resolution.nodeId, direction, result)
            }
        }

        /** Scrolls the nearest scrollable container of [nodeId] in [direction] until the target is visible. */
        private suspend fun scrollTo(
            target: NodeTarget,
            nodeId: String,
            direction: ScrollDirection,
            initial: MultiWindowResult,
        ): CallToolResult {
            val node =
                elementFinder.findNodeById(initial.windows, nodeId)
                    ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found")

            // If already visible, return immediately
            if (node.visible) {
                Log.d(TAG, "scroll_to_node: node '$nodeId' already visible")
                return McpToolUtils.textResult("Node '$nodeId' is already visible")
            }

            val container =
                findScrollableContainer(initial.windows, nodeId)
                    ?: throw McpToolException.ActionFailed("No scrollable container found for node '$nodeId'")
            val identity = NodeIdentity(node.resourceId, node.className, node.text, node.contentDescription)
            val tracked = TrackedTarget(target, identity, nodeId, container)
            if (target is NodeTarget.ById) requireDistinct(tracked, initial.windows)
            return scrollUntilVisible(tracked, direction, initial)
        }

        /**
         * A target given by id is found again by its attributes after each scroll, so they must tell it apart in its
         * container from the start: look-alikes fail before any scroll.
         */
        private fun requireDistinct(
            tracked: TrackedTarget,
            windows: List<WindowData>,
        ) {
            val lookAlikes =
                tracked.container
                    .locate(windows)
                    ?.let { lookAlikes(it, tracked.identity) }
                    .orEmpty()
            if (lookAlikes.size > 1) throw lookAlikeError(tracked, lookAlikes.size)
        }

        /** Scrolls until the target shows; [scrollContainer] stops early when the container does not scroll. */
        private suspend fun scrollUntilVisible(
            tracked: TrackedTarget,
            direction: ScrollDirection,
            initial: MultiWindowResult,
        ): CallToolResult {
            var result = initial
            for (attempt in 1..MAX_SCROLL_ATTEMPTS) {
                scrollContainer(tracked, direction, result.windows, scrollsDone = attempt - 1)

                // Small delay to let UI settle after scroll
                kotlinx.coroutines.delay(SCROLL_SETTLE_DELAY_MS)

                // Re-parse and find the target again (its id changed if it moved)
                result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
                when (val refound = refind(tracked, result.windows, attempt, direction)) {
                    is Refound.Missing -> {
                        continue
                    }

                    is Refound.Failed -> {
                        throw refound.error
                    }

                    is Refound.Ambiguous -> {
                        return refound.result
                    }

                    is Refound.Found -> {
                        if (refound.node.visible) {
                            Log.d(
                                TAG,
                                "scroll_to_node: node '${tracked.originalNodeId}' became visible as " +
                                    "'${refound.node.id}' after $attempt scroll(s) (direction=$direction)",
                            )
                            return McpToolUtils.textResult(
                                "Scrolled to node '${refound.node.id}' ($attempt scroll(s))",
                            )
                        }
                    }
                }
            }

            throw McpToolException.ActionFailed(
                "Node '${tracked.originalNodeId}' not visible after $MAX_SCROLL_ATTEMPTS scroll attempts",
            )
        }

        /**
         * Scrolls the target's container once, located in [windows] by its place. Stops the tool when the container
         * is not at its place any more or does not scroll (e.g. it reached the end of its content in [direction]).
         */
        private suspend fun scrollContainer(
            tracked: TrackedTarget,
            direction: ScrollDirection,
            windows: List<WindowData>,
            scrollsDone: Int,
        ) {
            val containerId = tracked.container.locate(windows)?.id ?: throw containerGoneError(tracked)
            actionExecutor.scrollNode(containerId, direction, windows).onFailure { e ->
                throw containerScrollError(e, tracked, containerId, direction, scrollsDone)
            }
        }

        /**
         * The error for a failed scroll of the container. Only a refused scroll (e.g. the end of its content in
         * [direction]) is reported as the container not scrolling further.
         */
        private fun containerScrollError(
            e: Throwable,
            tracked: TrackedTarget,
            containerId: String,
            direction: ScrollDirection,
            scrollsDone: Int,
        ): McpToolException {
            val container = "The scrollable container '$containerId' of node '${tracked.originalNodeId}'"
            return when {
                e is IllegalStateException && e.message?.contains("not available") == true -> {
                    McpToolException.PermissionDenied(e.message ?: "Accessibility service not available")
                }

                e is IllegalStateException -> {
                    McpToolException.ActionFailed("$container cannot be scrolled: ${e.message}")
                }

                e is NoSuchElementException -> {
                    McpToolException.NodeNotFound("$container is no longer in the accessibility tree")
                }

                else -> {
                    McpToolException.ActionFailed(
                        "Node '${tracked.originalNodeId}' not visible after $scrollsDone scroll(s) " +
                            "${direction.name.lowercase()}: its container did not scroll further (${e.message})",
                    )
                }
            }
        }

        private fun containerGoneError(tracked: TrackedTarget): McpToolException =
            McpToolException.ActionFailed(
                "The scrollable container of node '${tracked.originalNodeId}' is no longer at its place in its " +
                    "window (gone, moved or no longer scrollable)",
            )

        private fun lookAlikeError(
            tracked: TrackedTarget,
            count: Int,
        ): McpToolException =
            McpToolException.ActionFailed(
                "$count nodes in its container look like node '${tracked.originalNodeId}' (same resource id, class, " +
                    "text and description), so it cannot be told apart (a selector cannot either): scroll with " +
                    "scroll_node, read the screen again and act on the node ID you pick",
            )

        /**
         * Finds the target in [windows] after [scrolls] scroll(s) in [direction]: a selector is matched again (on-
         * and off-screen, across all windows); a node given by id is looked up by its identity among the
         * descendants of its container. More than one candidate is an error, never a guess.
         */
        private suspend fun refind(
            tracked: TrackedTarget,
            windows: List<WindowData>,
            scrolls: Int,
            direction: ScrollDirection,
        ): Refound =
            when (val target = tracked.target) {
                is NodeTarget.BySelector -> refindBySelector(target.selector, windows)
                is NodeTarget.ById -> refindByIdentity(tracked, windows, scrolls, direction)
            }

        private suspend fun refindBySelector(
            selector: NodeSelector,
            windows: List<WindowData>,
        ): Refound {
            val matches = targetResolver.matches(selector, windows, onScreenOnly = false)
            return when (matches.size) {
                0 -> {
                    Refound.Missing
                }

                1 -> {
                    elementFinder.findNodeById(windows, matches.single().id)?.let { Refound.Found(it) }
                        ?: Refound.Missing
                }

                else -> {
                    Refound.Ambiguous(targetResolver.ambiguousResult(selector, matches, onScreenOnly = false))
                }
            }
        }

        /**
         * The target given by id, found by its identity in its container. Once it is not there any more it is not
         * looked for again: scrolling further the same way only moves it farther, and a look-alike that shows up
         * later is not the target.
         */
        private fun refindByIdentity(
            tracked: TrackedTarget,
            windows: List<WindowData>,
            scrolls: Int,
            direction: ScrollDirection,
        ): Refound {
            val container = tracked.container.locate(windows) ?: return Refound.Failed(containerGoneError(tracked))
            val candidates = lookAlikes(container, tracked.identity)
            val way = direction.name.lowercase()
            return when (candidates.size) {
                0 -> {
                    Refound.Failed(
                        McpToolException.ActionFailed(
                            "Node '${tracked.originalNodeId}' is no longer in its container after $scrolls " +
                                "scroll(s) $way (it moved out of the loaded content, or changed): scrolling $way " +
                                "cannot bring it back; scroll the other way, or read the screen again",
                        ),
                    )
                }

                1 -> {
                    Refound.Found(candidates.single())
                }

                else -> {
                    Refound.Failed(lookAlikeError(tracked, candidates.size))
                }
            }
        }

        /** The descendants of [container] that match [identity]. */
        private fun lookAlikes(
            container: AccessibilityNodeData,
            identity: NodeIdentity,
        ): List<AccessibilityNodeData> {
            val matches = mutableListOf<AccessibilityNodeData>()
            collectDescendants(container, identity, matches)
            return matches
        }

        private fun collectDescendants(
            node: AccessibilityNodeData,
            identity: NodeIdentity,
            out: MutableList<AccessibilityNodeData>,
        ) {
            for (child in node.children) {
                if (identity.matches(child)) out.add(child)
                collectDescendants(child, identity, out)
            }
        }

        /**
         * The nearest scrollable ancestor of [nodeId] (the node itself excluded), tracked by its place in its
         * window; null when the node has none.
         */
        private fun findScrollableContainer(
            windows: List<WindowData>,
            nodeId: String,
        ): TrackedContainer? {
            val window = windows.firstOrNull { elementFinder.findNodeById(it.tree, nodeId) != null } ?: return null
            val path = mutableListOf<AccessibilityNodeData>()
            findPathToNode(window.tree, nodeId, path)
            return (path.size - 2 downTo 0).firstOrNull { path[it].scrollable }?.let { depth ->
                TrackedContainer(
                    windowId = window.windowId,
                    childIndexPath =
                        (1..depth).map { level -> path[level - 1].children.indexOfFirst { it === path[level] } },
                    resourceId = path[depth].resourceId,
                    className = path[depth].className,
                )
            }
        }

        /**
         * Builds the path from [root] to the node with [targetId].
         * Returns true if found, with [path] containing all nodes from root to target.
         */
        @Suppress("ReturnCount")
        private fun findPathToNode(
            root: AccessibilityNodeData,
            targetId: String,
            path: MutableList<AccessibilityNodeData>,
        ): Boolean {
            path.add(root)
            if (root.id == targetId) return true
            for (child in root.children) {
                if (findPathToNode(child, targetId, path)) return true
            }
            path.removeAt(path.size - 1)
            return false
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Scroll the nearest scrollable container of the specified node in the given direction until " +
                        "the node is visible (up to $MAX_SCROLL_ATTEMPTS scrolls; stops early when the container " +
                        "does not scroll any further). Target by node ID or by a selector (by + value, the single " +
                        "matching node on or off screen). Node IDs change when a node moves: the result names the " +
                        "node's current ID.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix, "node (on or off screen)")
                                putScrollDirectionProperty(
                                    "Direction to scroll the node's container: down/right scroll forward, " +
                                        "up/left backward",
                                )
                            },
                        required = listOf(PARAM_SCROLL_DIRECTION),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ScrollToNodeTool"
            const val TOOL_NAME = "scroll_to_node"
            private const val MAX_SCROLL_ATTEMPTS = 5
            private const val SCROLL_SETTLE_DELAY_MS = 300L
        }
    }

/**
 * MCP tool: scroll_node
 *
 * Scrolls the specified scrollable node once in a direction, with the accessibility scroll action on the node
 * itself (up/left scroll backward, down/right forward). Nothing else is searched or retried: the node must be
 * scrollable.
 */
class ScrollNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val targetResolver: NodeTargetResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = targetResolver.parse(arguments)
            val direction = requireScrollDirection(arguments)
            val directionName = direction.name.lowercase()

            val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId =
                when (val resolution = targetResolver.resolve(target, multiWindowResult.windows)) {
                    is NodeResolution.Resolved -> resolution.nodeId
                    is NodeResolution.Ambiguous -> return resolution.result
                }

            val result = actionExecutor.scrollNode(nodeId, direction, multiWindowResult.windows)
            result.onFailure { e -> mapNodeActionException(e, nodeId) }

            Log.d(TAG, "scroll_node: nodeId=$nodeId direction=$directionName succeeded")
            return McpToolUtils.textResult("Scrolled node '$nodeId' $directionName")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Scroll a scrollable node (a list, a carousel, a scroll view) once in the given direction, by " +
                        "node ID or by a selector (by + value, the single on-screen match). Uses the node's own " +
                        "accessibility scroll action: up/left scroll backward, down/right forward. " +
                        "Returns once the scroll has started: the content may still be animating, use " +
                        "${toolNamePrefix}wait_for_idle before reading the screen again if needed.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
                                putScrollDirectionProperty("Scroll direction")
                            },
                        required = listOf(PARAM_SCROLL_DIRECTION),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ScrollNodeTool"
            const val TOOL_NAME = "scroll_node"
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Shared Utilities for Node Action Tools
// ─────────────────────────────────────────────────────────────────────────────

internal const val PARAM_SCROLL_DIRECTION = "direction"

/** The directions of the node scroll tools: up/left scroll the node backward, down/right forward. */
private val SCROLL_DIRECTIONS =
    linkedMapOf(
        "up" to ScrollDirection.UP,
        "down" to ScrollDirection.DOWN,
        "left" to ScrollDirection.LEFT,
        "right" to ScrollDirection.RIGHT,
    )

/** The required `direction` of the node scroll tools (case-insensitive). */
internal fun requireScrollDirection(arguments: JsonObject?): ScrollDirection {
    val raw = McpToolUtils.requireString(arguments, PARAM_SCROLL_DIRECTION)
    return SCROLL_DIRECTIONS[raw.lowercase()]
        ?: throw McpToolException.InvalidParams(
            "Parameter '$PARAM_SCROLL_DIRECTION' must be one of: ${SCROLL_DIRECTIONS.keys.joinToString()}. " +
                "Got: '$raw'",
        )
}

/** Adds the required `direction` property of the node scroll tools. */
internal fun JsonObjectBuilder.putScrollDirectionProperty(description: String) {
    putJsonObject(PARAM_SCROLL_DIRECTION) {
        put("type", "string")
        put("enum", buildJsonArray { SCROLL_DIRECTIONS.keys.forEach { add(JsonPrimitive(it)) } })
        put("description", description)
    }
}

/**
 * Maps a "by" string parameter to the [FindBy] enum.
 * Returns null if the string does not match any known value.
 */
internal fun mapFindBy(by: String): FindBy? =
    when (by.lowercase()) {
        "text" -> FindBy.TEXT
        "content_desc" -> FindBy.CONTENT_DESC
        "resource_id" -> FindBy.RESOURCE_ID
        "class_name" -> FindBy.CLASS_NAME
        else -> null
    }

/**
 * Gets a fresh multi-window accessibility snapshot by enumerating all on-screen windows
 * and parsing each window's accessibility tree.
 *
 * Falls back to single-window mode via [AccessibilityServiceProvider.getRootNode] if
 * [AccessibilityServiceProvider.getAccessibilityWindows] returns an empty list.
 *
 * **Performance note**: This function re-parses ALL window trees on every invocation to ensure
 * correctness — stale tree data would cause actions to target the wrong nodes. During parsing,
 * real [AccessibilityNodeInfo] references are accumulated in a [MutableMap] and stored in
 * [nodeCache] via [AccessibilityNodeCache.populate] for O(1) node resolution in
 * [ActionExecutorImpl.performNodeAction]. The cache is replaced atomically on each call.
 *
 * @throws McpToolException.PermissionDenied if accessibility service is not connected.
 * @throws McpToolException.ActionFailed if no windows and no root node are available.
 */
internal fun getFreshWindows(
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
): MultiWindowResult =
    // Serialize the entire clear+read+populate critical section against concurrent MCP requests:
    // clearing the shared framework node cache mid-traversal of another request would yield a torn
    // tree and flaky node-id resolution. See AccessibilityTreeLock.
    synchronized(AccessibilityTreeLock.monitor) {
        getFreshWindowsLocked(treeParser, accessibilityServiceProvider, nodeCache)
    }

@Suppress("LongMethod", "NestedBlockDepth", "ThrowsCount")
private fun getFreshWindowsLocked(
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
): MultiWindowResult {
    if (!accessibilityServiceProvider.isReady()) {
        throw McpToolException.PermissionDenied(
            "Accessibility service not enabled or not ready. " +
                "Please enable it in Android Settings > Accessibility.",
        )
    }

    // Drop the framework's accessibility node cache before reading. Chromium WebView
    // suppresses/throttles the content-changed events that invalidate that cache, so a JavaScript
    // DOM change (e.g. updated text) would otherwise read back stale — and node.refresh() cannot
    // re-fetch what the cache still serves as current. Clearing here makes get_screen_state,
    // find_nodes, and the action tools' re-reads (which all go through getFreshWindows) round-trip
    // live and populate the node cache with live nodes. The lightweight wait_for_node/wait_for_idle
    // poll paths do not go through here and clear the cache themselves (see UtilityTools). See
    // AccessibilityServiceProvider.clearFrameworkNodeCache.
    accessibilityServiceProvider.clearFrameworkNodeCache()

    // Accumulate real AccessibilityNodeInfo references from ALL windows for cache population.
    // This map is passed to each parseTree call and populated during tree traversal.
    // We populate the cache ONCE after all windows are parsed to avoid the multi-window
    // cache overwrite problem (Critical Finding 1).
    val accumulatedNodeMap = mutableMapOf<String, CachedNode>()

    val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

    if (accessibilityWindows.isNotEmpty()) {
        try {
            val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
            val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()
            val windowDataList = mutableListOf<WindowData>()

            for (window in accessibilityWindows) {
                val rootNode = window.root ?: continue

                // Force the accessibility framework to re-query the underlying
                // AccessibilityNodeProvider (e.g., Compose's virtual node provider).
                // Without this, window.root can return stale cached snapshots —
                // particularly problematic for Jetpack Compose apps where
                // TYPE_WINDOW_CONTENT_CHANGED events may be throttled or missed.
                rootNode.refresh()

                // Extract metadata BEFORE parsing
                val wId = window.id
                val windowPackage = rootNode.packageName?.toString()
                val windowTitle = window.title?.toString()
                val windowType = window.type
                val windowLayer = window.layer
                val windowFocused = window.isFocused

                // Root nodes are NOT recycled — they are stored in accumulatedNodeMap
                // and owned by the cache. On API 33+ recycle() is a no-op, but we
                // avoid calling it on cached nodes for semantic clarity (Critical Finding 2).
                val tree = treeParser.parseTree(rootNode, "root_w$wId", accumulatedNodeMap)

                // Best-effort activity name: only for focused APPLICATION window matching tracked package
                val activityName =
                    if (windowFocused &&
                        windowType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        windowPackage == currentPackageName
                    ) {
                        currentActivityName
                    } else {
                        null
                    }

                windowDataList.add(
                    WindowData(
                        windowId = wId,
                        windowType = AccessibilityTreeParser.mapWindowType(windowType),
                        packageName = windowPackage,
                        title = windowTitle,
                        activityName = activityName,
                        layer = windowLayer,
                        focused = windowFocused,
                        tree = tree,
                    ),
                )
            }

            if (windowDataList.isEmpty()) {
                throw McpToolException.ActionFailed(
                    "All windows returned null root nodes.",
                )
            }

            // Populate cache with ALL windows' nodes in a single atomic swap
            nodeCache.populate(accumulatedNodeMap)

            return MultiWindowResult(windows = windowDataList, degraded = false)
        } finally {
            // Recycle all AccessibilityWindowInfo objects for consistency
            // (no-op on API 34+, but follows the codebase's recycling convention)
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

    // Force fresh data from the accessibility framework (see multi-window path comment).
    rootNode.refresh()

    // Extract metadata from the root node before recycling.
    // Use rootNode.window (available API 21+) to detect the actual window type
    // instead of hardcoding APPLICATION — the active window could be SYSTEM, INPUT_METHOD, etc.
    val fallbackWindowId = rootNode.windowId
    val windowInfo = rootNode.window
    val fallbackWindowType =
        if (windowInfo != null) {
            val type = AccessibilityTreeParser.mapWindowType(windowInfo.type)
            @Suppress("DEPRECATION")
            windowInfo.recycle()
            type
        } else {
            "APPLICATION"
        }

    // Fallback path also accumulates into the same map and does NOT recycle root node
    val tree = treeParser.parseTree(rootNode, "root_w$fallbackWindowId", accumulatedNodeMap)

    // Populate cache with fallback window's nodes
    nodeCache.populate(accumulatedNodeMap)

    val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
    val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()

    return MultiWindowResult(
        windows =
            listOf(
                WindowData(
                    windowId = fallbackWindowId,
                    windowType = fallbackWindowType,
                    packageName = currentPackageName,
                    title = "unknown",
                    activityName = currentActivityName,
                    layer = 0,
                    focused = true,
                    tree = tree,
                ),
            ),
        degraded = true,
    )
}

/**
 * Registers all node action tools with the [Server].
 */
@Suppress("LongParameterList")
fun registerNodeActionTools(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
    privacyToolGate: PrivacyToolGate,
    substitutor: PlaceholderSubstitutor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    val targetResolver = NodeTargetResolver(elementFinder, privacyToolGate, substitutor)
    if (perms.isToolEnabled(FindNodesTool.TOOL_NAME)) {
        FindNodesTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache, privacyToolGate, substitutor)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ClickNodeTool.TOOL_NAME)) {
        ClickNodeTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache, targetResolver)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(LongClickNodeTool.TOOL_NAME)) {
        LongClickNodeTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache, targetResolver)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(TapNodeTool.TOOL_NAME)) {
        TapNodeTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, nodeCache, targetResolver)
            .register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ScrollToNodeTool.TOOL_NAME)) {
        ScrollToNodeTool(
            treeParser,
            elementFinder,
            actionExecutor,
            accessibilityServiceProvider,
            nodeCache,
            targetResolver,
        ).register(registrar, toolNamePrefix)
    }
    if (perms.isToolEnabled(ScrollNodeTool.TOOL_NAME)) {
        ScrollNodeTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache, targetResolver)
            .register(registrar, toolNamePrefix)
    }
}

/**
 * Maps exceptions from [ActionExecutor] node actions to [McpToolException]
 * with appropriate MCP error codes.
 *
 * @throws McpToolException always (this function never returns normally).
 */
@Suppress("ThrowsCount")
internal fun mapNodeActionException(
    exception: Throwable,
    nodeId: String,
): Nothing {
    when (exception) {
        is NoSuchElementException -> {
            throw McpToolException.NodeNotFound(
                "Node '$nodeId' not found in accessibility tree",
            )
        }

        is IllegalStateException -> {
            if (exception.message?.contains("not available") == true) {
                throw McpToolException.PermissionDenied(
                    exception.message ?: "Accessibility service not available",
                )
            }
            throw McpToolException.ActionFailed(
                exception.message ?: "Action failed on node '$nodeId'",
            )
        }

        else -> {
            throw McpToolException.ActionFailed(
                "Action failed on node '$nodeId': ${exception.message}",
            )
        }
    }
}
