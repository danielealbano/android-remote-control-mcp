@file:Suppress("TooManyFunctions", "LongMethod", "NestedBlockDepth", "ThrowsCount", "CyclomaticComplexMethod")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * MCP tool: `execute_batch`
 *
 * Executes a scripted sequence of UI steps in a SINGLE MCP round trip. Steps run
 * sequentially against the live accessibility tree on-device — no network
 * serialization, no client round trip, no re-read between steps.
 *
 * This collapses the classic "dump -> find -> tap -> dump" loop (N+ MCP calls
 * per UI interaction) into one call. The batch re-reads the tree between steps
 * exactly like the single-step tools do, but does it in-process.
 *
 * Step types:
 * - `wait`            {ms}                                    sleep
 * - `tap`             {x, y}                                  coordinate tap
 * - `tap_node`        {node_id} OR {by, value, exact_match}   tap node (id or predicate)
 * - `click_node`      {node_id} OR {by, value, exact_match}   a11y click node (id or predicate)
 * - `type`            {text, node_id?} OR {text, by, value, exact_match}
 *                    Types text via ACTION_SET_TEXT on the target node. With no
 *                    node_id/by, types into the currently focused editable node.
 *                    NOTE: ACTION_SET_TEXT replaces the field's entire content.
 * - `press`           {key: back|home|recents|enter|tab|delete}
 * - `swipe`           {x1, y1, x2, y2, duration_ms?}
 * - `scroll`          {direction: up|down|left|right, amount: small|medium|large?}
 * - `get_state`       {}                                      capture screen state inline
 *
 * Behavior:
 * - Steps run in order; execution stops at the first failed step unless
 *   `continue_on_error: true` (default false).
 * - Each step result is a JSON object {step: N, type, ok, detail}. On failure,
 *   `error` carries the message and execution stops (or continues if configured).
 * - With `capture_state: true` (default), a final compact screen state is appended.
 * - `get_state` steps capture mid-batch state into the `states` array, keyed by
 *   step index, so a client can branch on intermediate UI without extra calls.
 */
class ExecuteBatchTool
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val webViewNodeMerger: WebViewNodeMerger,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val steps =
                arguments?.get("steps")?.jsonArray
                    ?: throw McpToolException.InvalidParams("Missing required parameter 'steps' (array)")

            if (steps.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'steps' must not be empty")
            }
            if (steps.size > MAX_STEPS) {
                throw McpToolException.InvalidParams("Parameter 'steps' exceeds max of $MAX_STEPS")
            }

            val continueOnError = arguments["continue_on_error"]?.jsonPrimitive?.booleanOrNull ?: false
            val captureState = arguments["capture_state"]?.jsonPrimitive?.booleanOrNull ?: true

            val stepResults = buildJsonArray {
                for ((index, stepElement) in steps.withIndex()) {
                    val stepObj =
                        stepElement as? JsonObject
                            ?: throw McpToolException.InvalidParams("Step $index must be a JSON object")
                    val result = runStep(index, stepObj)
                    add(result)
                    val ok = result["ok"]?.jsonPrimitive?.contentOrNull == "true"
                    if (!ok && !continueOnError) {
                        // Stop at first failure. The result array already includes the failed step.
                        return@buildJsonArray
                    }
                }
            }

            val finalStateText = if (captureState) captureStateText() else null

            val response =
                buildJsonObject {
                    put("steps", stepResults)
                    if (finalStateText != null) {
                        putJsonObject("final_state") {
                            put("text", finalStateText)
                        }
                    }
                }

            return McpToolUtils.untrustedTextResult(Json.encodeToString(response))
        }

        /**
         * Runs one step and returns its JSON result object. Throws nothing — failures
         * are captured in the result unless the caller decides to propagate.
         */
        private suspend fun runStep(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val type = step["type"]?.jsonPrimitive?.contentOrNull
                ?: return errorResult(index, "missing", "Step is missing 'type'")

            return try {
                when (type) {
                    "wait" -> stepWait(index, step)
                    "tap" -> stepTap(index, step)
                    "tap_node" -> stepTapNode(index, step)
                    "click_node" -> stepClickNode(index, step)
                    "type" -> stepType(index, step)
                    "press" -> stepPress(index, step)
                    "swipe" -> stepSwipe(index, step)
                    "scroll" -> stepScroll(index, step)
                    "get_state" -> stepGetState(index)
                    else -> errorResult(index, type, "Unknown step type: '$type'")
                }
            } catch (e: McpToolException) {
                errorResult(index, type, e.message ?: "MCP error")
            } catch (e: Exception) {
                Log.w(TAG, "execute_batch step $index ($type) failed", e)
                errorResult(index, type, e.message ?: "Unknown error")
            }
        }

        private fun okResult(
            index: Int,
            type: String,
            detail: String,
        ): JsonObject =
            buildJsonObject {
                put("step", index)
                put("type", type)
                put("ok", true)
                put("detail", detail)
            }

        private fun errorResult(
            index: Int,
            type: String,
            message: String,
        ): JsonObject =
            buildJsonObject {
                put("step", index)
                put("type", type)
                put("ok", false)
                put("error", message)
            }

        private suspend fun stepWait(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val ms = step["ms"]?.jsonPrimitive?.longOrNull ?: 0L
            if (ms < 0 || ms > MAX_WAIT_MS) {
                throw McpToolException.InvalidParams("'ms' must be 0..$MAX_WAIT_MS")
            }
            delay(ms)
            return okResult(index, "wait", "Waited ${ms}ms")
        }

        private suspend fun stepTap(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val x = McpToolUtils.requireFloat(step, "x")
            val y = McpToolUtils.requireFloat(step, "y")
            val result = actionExecutor.tap(x, y)
            return actionOutcome(index, "tap", result, "Tap at (${x.toInt()}, ${y.toInt()})")
        }

        private suspend fun stepTapNode(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val (windows, element) = resolveTarget(step)
            val bounds = element.bounds
            val (tapX, tapY) = randomPointInBounds(bounds)
            val result = actionExecutor.tap(tapX, tapY)
            return actionOutcome(
                index,
                "tap_node",
                result,
                "Tap at (${tapX.toInt()}, ${tapY.toInt()}) on node '${element.id}' [${element.text ?: element.contentDescription ?: element.resourceId}]",
            )
        }

        private suspend fun stepClickNode(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val (windows, element) = resolveTarget(step)
            val result = actionExecutor.clickNode(element.id, windows.windows)
            return actionOutcome(
                index,
                "click_node",
                result,
                "Clicked node '${element.id}' [${element.text ?: element.contentDescription ?: element.resourceId}]",
            )
        }

        private suspend fun stepType(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val text =
                step["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw McpToolException.InvalidParams("'type' step requires 'text'")

            // Resolve target: node_id / predicate, or fall back to focused editable node.
            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val targetId: String? =
                if (step["node_id"] != null || step["by"] != null) {
                    resolveTargetId(step, windows)
                } else {
                    null
                }

            val result =
                if (targetId != null) {
                    actionExecutor.setTextOnNode(targetId, text, windows.windows)
                } else {
                    setTextOnFocused(text)
                }
            return actionOutcome(index, "type", result, "Set text (${text.length} chars)")
        }

        private suspend fun stepPress(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val key = step["key"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?: throw McpToolException.InvalidParams("'press' step requires 'key'")
            val result =
                when (key) {
                    "back" -> actionExecutor.pressBack()
                    "home" -> actionExecutor.pressHome()
                    "recents" -> actionExecutor.pressRecents()
                    else -> throw McpToolException.InvalidParams(
                        "Unsupported 'press' key: '$key'. Use back|home|recents",
                    )
                }
            return actionOutcome(index, "press", result, "Pressed '$key'")
        }

        private suspend fun stepSwipe(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val x1 = McpToolUtils.requireFloat(step, "x1")
            val y1 = McpToolUtils.requireFloat(step, "y1")
            val x2 = McpToolUtils.requireFloat(step, "x2")
            val y2 = McpToolUtils.requireFloat(step, "y2")
            val duration = step["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L
            val result = actionExecutor.swipe(x1, y1, x2, y2, duration)
            return actionOutcome(index, "swipe", result, "Swiped ($x1,$y1)->($x2,$y2)")
        }

        private suspend fun stepScroll(
            index: Int,
            step: JsonObject,
        ): JsonObject {
            val directionStr = step["direction"]?.jsonPrimitive?.contentOrNull
                ?: throw McpToolException.InvalidParams("'scroll' step requires 'direction'")
            val direction =
                when (directionStr.lowercase()) {
                    "up" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection.UP
                    "down" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection.DOWN
                    "left" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection.LEFT
                    "right" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection.RIGHT
                    else -> throw McpToolException.InvalidParams("Invalid 'direction': '$directionStr'")
                }
            val amountStr = step["amount"]?.jsonPrimitive?.contentOrNull ?: "medium"
            val amount =
                when (amountStr.lowercase()) {
                    "small" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount.SMALL
                    "medium" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount.MEDIUM
                    "large" -> com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount.LARGE
                    else -> throw McpToolException.InvalidParams("Invalid 'amount': '$amountStr'")
                }
            val result = actionExecutor.scroll(direction, amount)
            return actionOutcome(index, "scroll", result, "Scrolled $directionStr")
        }

        private suspend fun stepGetState(index: Int): JsonObject {
            val state = captureStateText()
            return buildJsonObject {
                put("step", index)
                put("type", "get_state")
                put("ok", true)
                put("state", state)
            }
        }

        private fun actionOutcome(
            index: Int,
            type: String,
            result: Result<Unit>,
            successDetail: String,
        ): JsonObject =
            if (result.isSuccess) {
                okResult(index, type, successDetail)
            } else {
                errorResult(index, type, result.exceptionOrNull()?.message ?: "Action failed")
            }

        /**
         * Resolves a step's target node. Accepts either `node_id` (exact) or
         * `by`/`value`/`exact_match` predicate (first match wins).
         * Returns the fresh windows (needed by ActionExecutor) and the element.
         */
        private fun resolveTarget(step: JsonObject): Pair<MultiWindowResult, AccessibilityNodeData> {
            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
            val nodeId = resolveTargetId(step, windows)
            val element =
                elementFinder.findNodeById(windows.windows, nodeId)
                    ?: throw McpToolException.NodeNotFound("Node '$nodeId' not found")
            return Pair(windows, element)
        }

        private fun resolveTargetId(
            step: JsonObject,
            windows: MultiWindowResult,
        ): String {
            val nodeId = step["node_id"]?.jsonPrimitive?.contentOrNull
            if (!nodeId.isNullOrEmpty()) return nodeId

            val byStr = step["by"]?.jsonPrimitive?.contentOrNull
                ?: throw McpToolException.InvalidParams("Step requires 'node_id' or 'by'/'value'")
            val rawValue = step["value"]?.jsonPrimitive?.contentOrNull
                ?: throw McpToolException.InvalidParams("'by' given without 'value'")
            val value = substitutor.substitute(rawValue)
            val exactMatch = step["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false

            val findBy = mapFindBy(byStr)
                ?: throw McpToolException.InvalidParams(
                    "Invalid 'by': '$byStr'. Must be text|content_desc|resource_id|class_name",
                )

            val elements = elementFinder.findElements(windows.windows, findBy, value, exactMatch)
            if (elements.isEmpty()) {
                throw McpToolException.NodeNotFound(
                    "No node found by=$byStr value='$value' exact=$exactMatch",
                )
            }
            return elements.first().id
        }

        private fun randomPointInBounds(bounds: BoundsData): Pair<Float, Float> {
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            return if (width < SMALL_ELEMENT_THRESHOLD && height < SMALL_ELEMENT_THRESHOLD) {
                Pair(bounds.left.toFloat(), bounds.top.toFloat())
            } else {
                val insetLeft = bounds.left + width * INSET_FRACTION
                val insetRight = bounds.right - width * INSET_FRACTION
                val insetTop = bounds.top + height * INSET_FRACTION
                val insetBottom = bounds.bottom - height * INSET_FRACTION
                Pair(
                    if (insetLeft < insetRight) Random.nextFloat() * (insetRight - insetLeft) + insetLeft else insetLeft,
                    if (insetTop < insetBottom) Random.nextFloat() * (insetBottom - insetTop) + insetTop else insetTop,
                )
            }
        }

        private suspend fun setTextOnFocused(text: String): Result<Unit> {
            // Fall back to the focused editable node, like the single-type tools do.
            val focused = findFocusedEditableNode(accessibilityServiceProvider) ?: return Result.failure(
                IllegalStateException("No focused editable node found and no node target given"),
            )
            return try {
                val arguments =
                    android.os.Bundle().apply {
                        putCharSequence(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                val success = focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (success) Result.success(Unit) else Result.failure(IllegalStateException("ACTION_SET_TEXT failed"))
            } finally {
                @Suppress("DEPRECATION")
                focused.recycle()
            }
        }

        private suspend fun captureStateText(): String {
            val rawResult = webViewNodeMerger.merge(getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache))
            val processed = privacyToolGate.tree(rawResult)
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            return compactTreeFormatter.formatMultiWindow(processed.result, screenInfo)
        }

        companion object {
            private const val TAG = "MCP:ExecuteBatchTool"
            const val TOOL_NAME = "execute_batch"
            private const val MAX_STEPS = 100
            private const val MAX_WAIT_MS = 120_000L
            private const val SMALL_ELEMENT_THRESHOLD = 5
            private const val INSET_FRACTION = 0.05f
        }
    }

fun registerBatchTools(
    registrar: LoggedToolRegistrar,
    treeParser: AccessibilityTreeParser,
    elementFinder: ElementFinder,
    actionExecutor: ActionExecutor,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    nodeCache: AccessibilityNodeCache,
    compactTreeFormatter: CompactTreeFormatter,
    webViewNodeMerger: WebViewNodeMerger,
    privacyToolGate: PrivacyToolGate,
    placeholderSubstitutor: PlaceholderSubstitutor,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (!perms.isToolEnabled(ExecuteBatchTool.TOOL_NAME)) return

    val tool =
        ExecuteBatchTool(
            treeParser,
            elementFinder,
            actionExecutor,
            accessibilityServiceProvider,
            nodeCache,
            compactTreeFormatter,
            webViewNodeMerger,
            privacyToolGate,
            placeholderSubstitutor,
        )

    registrar.addTool(
        toolName = ExecuteBatchTool.TOOL_NAME,
        name = "$toolNamePrefix${ExecuteBatchTool.TOOL_NAME}",
        description =
            "Executes a scripted sequence of UI steps in a single round trip: " +
                "wait, tap, tap_node, click_node, type, press, swipe, scroll, get_state. " +
                "Steps run on-device with no network between them — this is the fastest way to " +
                "drive multi-step UI flows. Node targets accept either node_id or by/value " +
                "predicates (text, content_desc, resource_id, class_name); the server finds the " +
                "node itself. 'type' uses ACTION_SET_TEXT (replaces the field's full content) " +
                "and needs a node target or an already-focused field. Execution stops at the " +
                "first failed step unless continue_on_error=true. Returns per-step results and, " +
                "by default, a final compact screen state.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("steps") {
                            put("type", "array")
                            put(
                                "description",
                                "Ordered steps to execute. Each step is an object: " +
                                    "{type: wait|tap|tap_node|click_node|type|press|swipe|scroll|get_state, ...}",
                            )
                            putJsonObject("items") {
                                put("type", "object")
                                put(
                                    "description",
                                    "Step types: wait{ms}, tap{x,y}, tap_node{node_id|by,value}, " +
                                        "click_node{node_id|by,value}, type{text, node_id?|by,value?}, " +
                                        "press{key: back|home|recents}, swipe{x1,y1,x2,y2,duration_ms?}, " +
                                        "scroll{direction,amount?}, get_state{}",
                                )
                            }
                        }
                        putJsonObject("continue_on_error") {
                            put("type", "boolean")
                            put("default", false)
                            put("description", "Continue past failed steps (default false)")
                        }
                        putJsonObject("capture_state") {
                            put("type", "boolean")
                            put("default", true)
                            put("description", "Append final compact screen state (default true)")
                        }
                    },
                required = listOf("steps"),
            ),
    ) { request -> tool.execute(request.arguments) }
}
