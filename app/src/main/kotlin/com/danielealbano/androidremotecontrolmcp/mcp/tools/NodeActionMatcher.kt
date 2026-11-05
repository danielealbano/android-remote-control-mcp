package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import javax.inject.Inject

/**
 * Matches the action label a client sends to `perform_node_action` against a node's labels as the tools list them,
 * Privacy Mode applied. `find_nodes` and `get_node_details` redact them with the node's context in the windows as
 * read, `get_screen_state` in the WebView-merged tree, where a web node's context differs (its description is folded
 * into its text). A web node's label therefore matches in either form; a native node is the same in both.
 */
class NodeActionMatcher
    @Inject
    constructor(
        private val privacyToolGate: PrivacyToolGate,
        private val webViewNodeMerger: WebViewNodeMerger,
    ) {
        /** The labels of [node] as listed with its context in [result]'s windows as read: one per action, in order. */
        suspend fun shownLabels(
            result: MultiWindowResult,
            node: AccessibilityNodeData,
        ): List<String?> = privacyToolGate.nodeTexts(result.windows, node.actions.map { it to node.id })

        /**
         * The position in [node]'s actions of the first label shown as [action], or -1: among [shown] (its
         * [shownLabels]), then, for a web node, among its labels as `get_screen_state` shows them.
         */
        suspend fun indexOf(
            result: MultiWindowResult,
            node: AccessibilityNodeData,
            shown: List<String?>,
            action: String,
        ): Int {
            val index = shown.indexOf(action)
            if (index >= 0 || node.webRole == null) return index
            val merged = webViewNodeMerger.merge(result)
            return privacyToolGate.nodeTexts(merged.windows, node.actions.map { it to node.id }).indexOf(action)
        }
    }
