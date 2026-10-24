package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * A selector over the current accessibility tree, with the same fields as `find_nodes`. [value] is the real value
 * (pseudonym placeholders reversed): it is only matched, never echoed or logged.
 */
class NodeSelector(
    val findBy: FindBy,
    val by: String,
    val value: String,
    val exactMatch: Boolean,
) {
    override fun toString(): String = "NodeSelector(by=$by, exactMatch=$exactMatch)"
}

/** The target of a node tool: a node id from a previous read, or a selector matched against the tool's own read. */
sealed interface NodeTarget {
    data class ById(
        val nodeId: String,
    ) : NodeTarget

    data class BySelector(
        val selector: NodeSelector,
    ) : NodeTarget
}

/** The outcome of resolving a [NodeTarget] in a tree read. */
sealed interface NodeResolution {
    data class Resolved(
        val nodeId: String,
    ) : NodeResolution

    /** More than one node matched the selector; [result] is the error to return as is. */
    data class Ambiguous(
        val result: CallToolResult,
    ) : NodeResolution
}

/**
 * Parses and resolves the target of the node tools: `node_id`, or a selector (`by` + `value`, optional
 * `exact_match`). A selector never guesses: no match is [McpToolException.NodeNotFound], more than one match is a
 * [NodeResolution.Ambiguous] error listing up to [MAX_CANDIDATES] candidates, redacted by Privacy Mode and marked
 * untrusted (it carries device text).
 */
class NodeTargetResolver
    @Inject
    constructor(
        private val elementFinder: ElementFinder,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        /** Validates the target arguments (no tree read). */
        fun parse(arguments: JsonObject?): NodeTarget {
            val nodeId = optionalNonNullString(arguments, PARAM_NODE_ID)
            val by = optionalNonNullString(arguments, PARAM_BY)
            val rawValue = optionalNonNullString(arguments, PARAM_VALUE)
            // Like the other selector fields, a JSON null exact_match counts as absent; checked with either form
            val exactMatch =
                if (arguments?.get(PARAM_EXACT_MATCH) is JsonNull) {
                    false
                } else {
                    McpToolUtils.optionalBoolean(arguments, PARAM_EXACT_MATCH, false)
                }
            val hasSelector = by != null || rawValue != null
            if (nodeId != null) {
                if (hasSelector) {
                    invalid("Provide either '$PARAM_NODE_ID' or a selector ('$PARAM_BY' + '$PARAM_VALUE'), not both")
                }
                if (nodeId.isEmpty()) invalid("Parameter '$PARAM_NODE_ID' must be non-empty")
                return NodeTarget.ById(nodeId)
            }
            if (!hasSelector) {
                invalid("Missing required parameter '$PARAM_NODE_ID' (or a selector: '$PARAM_BY' + '$PARAM_VALUE')")
            }
            if (by == null || rawValue == null) invalid("A selector needs both '$PARAM_BY' and '$PARAM_VALUE'")
            if (rawValue.isEmpty()) invalid("Parameter '$PARAM_VALUE' must be non-empty")
            val findBy =
                mapFindBy(by)
                    ?: invalid(
                        "Invalid '$PARAM_BY' value: '$by'. Must be one of: text, content_desc, resource_id, class_name",
                    )
            return NodeTarget.BySelector(NodeSelector(findBy, by, substitutor.substitute(rawValue), exactMatch))
        }

        /**
         * Resolves [target] in [windows]. A node id is returned as is (the action reports a missing node); a
         * selector is matched among on-screen nodes, or among all nodes when [onScreenOnly] is false.
         */
        suspend fun resolve(
            target: NodeTarget,
            windows: List<WindowData>,
            onScreenOnly: Boolean = true,
        ): NodeResolution =
            when (target) {
                is NodeTarget.ById -> {
                    NodeResolution.Resolved(target.nodeId)
                }

                is NodeTarget.BySelector -> {
                    val matches = matches(target.selector, windows, onScreenOnly)
                    when (matches.size) {
                        0 -> throw McpToolException.NodeNotFound(noMatchMessage(target.selector, onScreenOnly))
                        1 -> NodeResolution.Resolved(matches.single().id)
                        else -> NodeResolution.Ambiguous(ambiguousResult(target.selector, matches, onScreenOnly))
                    }
                }
            }

        /** The nodes [selector] matches in [windows], in tree order; on-screen ones only when [onScreenOnly]. */
        fun matches(
            selector: NodeSelector,
            windows: List<WindowData>,
            onScreenOnly: Boolean,
        ): List<ElementInfo> =
            elementFinder
                .findElements(windows, selector.findBy, selector.value, selector.exactMatch)
                .filter { !onScreenOnly || it.visible }

        /**
         * The error listing the first [MAX_CANDIDATES] of [matches], with Privacy Mode applied to their texts and the
         * texts shortened like in `get_screen_state`.
         */
        suspend fun ambiguousResult(
            selector: NodeSelector,
            matches: List<ElementInfo>,
            onScreenOnly: Boolean,
        ): CallToolResult {
            val shown = matches.take(MAX_CANDIDATES)
            val redacted =
                privacyToolGate.texts(
                    shown.flatMap { listOf(it.text to "node text", it.contentDescription to "node description") },
                )
            val candidates =
                buildJsonObject {
                    put(
                        "nodes",
                        buildJsonArray {
                            shown.forEachIndexed { index, element ->
                                val safe =
                                    element.copy(
                                        text = shorten(redacted[index * 2]),
                                        contentDescription = shorten(redacted[index * 2 + 1]),
                                    )
                                add(McpToolUtils.buildNodeJson(safe))
                            }
                        },
                    )
                }
            val scope = if (onScreenOnly) "on-screen nodes" else "nodes"
            val text =
                "Ambiguous selector: ${matches.size} $scope match by=${selector.by}. Retry with '$PARAM_NODE_ID' " +
                    "set to one of these candidates (first ${shown.size}), or with a more specific selector:\n" +
                    Json.encodeToString(candidates)
            return McpToolUtils.untrustedTextResult(text).copy(isError = true)
        }

        private fun shorten(text: String?): String? =
            if (text != null && text.length > CompactTreeFormatter.MAX_TEXT_LENGTH) {
                text.take(CompactTreeFormatter.MAX_TEXT_LENGTH) + CompactTreeFormatter.TRUNCATION_SUFFIX
            } else {
                text
            }

        private fun noMatchMessage(
            selector: NodeSelector,
            onScreenOnly: Boolean,
        ): String =
            if (onScreenOnly) {
                "No on-screen node matches the selector (by=${selector.by}, exact_match=${selector.exactMatch})"
            } else {
                "No node in the current screen matches the selector (by=${selector.by}, " +
                    "exact_match=${selector.exactMatch}); scroll its list with scroll_node and retry"
            }

        /** A string parameter, absent when missing or JSON null. */
        private fun optionalNonNullString(
            arguments: JsonObject?,
            name: String,
        ): String? {
            if (arguments?.get(name) == null || arguments[name] is JsonNull) return null
            return McpToolUtils.optionalString(arguments, name, "")
        }

        private fun invalid(message: String): Nothing = throw McpToolException.InvalidParams(message)

        companion object {
            const val PARAM_NODE_ID = "node_id"
            const val PARAM_BY = "by"
            const val PARAM_VALUE = "value"
            const val PARAM_EXACT_MATCH = "exact_match"
            const val MAX_CANDIDATES = 5
        }
    }

/**
 * Adds the target properties shared by the node tools: `node_id`, or the selector `by` + `value` (+ `exact_match`),
 * with the same semantics as `find_nodes`. None is required by the schema: the tool requires exactly one form.
 */
internal fun JsonObjectBuilder.putNodeTargetProperties(
    toolNamePrefix: String,
    selectorScope: String = "on-screen node",
) {
    putJsonObject(NodeTargetResolver.PARAM_NODE_ID) {
        put("type", "string")
        put(
            "description",
            "Node ID from ${toolNamePrefix}get_screen_state or ${toolNamePrefix}find_nodes. " +
                "Use either this or a selector (by + value).",
        )
    }
    putJsonObject(NodeTargetResolver.PARAM_BY) {
        put("type", "string")
        put(
            "enum",
            buildJsonArray {
                listOf("text", "content_desc", "resource_id", "class_name").forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "description",
            "Selector criteria (with value), instead of node_id: the target is the single $selectorScope " +
                "that matches; more than one match is an error listing the candidates.",
        )
    }
    putJsonObject(NodeTargetResolver.PARAM_VALUE) {
        put("type", "string")
        put("description", "Selector value (with by)")
    }
    putJsonObject(NodeTargetResolver.PARAM_EXACT_MATCH) {
        put("type", "boolean")
        put("default", false)
        put("description", "Selector: if true, match exactly. If false, match contains (case-insensitive)")
    }
}
