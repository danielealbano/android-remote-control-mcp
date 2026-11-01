package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin entry point tools call to redact device-derived text before returning it. Delegates to the
 * [PrivacyPipeline] (which fails closed and passes through when Privacy Mode is disabled). [texts]
 * redacts many fields in a single batched model pass; [nodeTexts] does the same for text read from
 * accessibility nodes, with each node's own detection context.
 */
@Singleton
class PrivacyToolGate
    @Inject
    constructor(
        private val pipeline: PrivacyPipeline,
        private val contextExtractor: ContextExtractor,
    ) {
        suspend fun text(
            text: String?,
            fieldName: String,
        ): String? = text?.let { pipeline.processText(it, DetectionContext.forField(fieldName)) }

        /** Redacts each `(text, fieldName)` pair in one batch; null texts stay null and keep their slot. */
        suspend fun texts(items: List<Pair<String?, String>>): List<String?> =
            redactPresent(items.map { it.first }) { index -> DetectionContext.forField(items[index].second) }

        /**
         * Redacts each `(text, nodeId)` pair in one batch, where the text was read from the node `nodeId` of [windows]
         * (its text or its content description). Each text gets that node's detection context (resource id, hint,
         * label, content description, password and editable flags) from the same [ContextExtractor] calls [tree] makes
         * for `get_screen_state`, run over [windows]: the nearest-label pass over the node's window, then the node's
         * own context. `get_screen_state` runs them over the WebView-merged tree, so web content can get a slightly
         * different context there. Null texts stay null and keep their slot; a node id that is not in [windows] gets
         * an empty context.
         */
        suspend fun nodeTexts(
            windows: List<WindowData>,
            items: List<Pair<String?, String>>,
        ): List<String?> {
            val nodeIds = items.mapNotNullTo(HashSet()) { (text, nodeId) -> nodeId.takeIf { text != null } }
            val contexts = nodeContexts(windows, nodeIds)
            return redactPresent(items.map { it.first }) { index ->
                contexts[items[index].second] ?: DetectionContext.EMPTY
            }
        }

        /**
         * Redacts one [text] read from the node [nodeId] of [windows] with that node's context, like [nodeTexts]. When
         * [passwordInput] is true (the text comes from an editor whose input type is a password) the context is also
         * marked as an editable password field, whatever the node reports.
         */
        suspend fun nodeText(
            windows: List<WindowData>,
            text: String?,
            nodeId: String,
            passwordInput: Boolean = false,
        ): String? {
            if (text == null) return null
            val context = nodeContexts(windows, setOf(nodeId))[nodeId] ?: DetectionContext.EMPTY
            val effective = if (passwordInput) context.copy(isPassword = true, isEditable = true) else context
            return pipeline.processTexts(listOf(TextItem(text, effective))).single()
        }

        suspend fun tree(result: MultiWindowResult): ProcessedTree = pipeline.processTree(result)

        private suspend fun redactPresent(
            texts: List<String?>,
            contextOf: (Int) -> DetectionContext,
        ): List<String?> {
            val present = texts.withIndex().mapNotNull { (index, text) -> text?.let { IndexedValue(index, it) } }
            if (present.isEmpty()) return texts.map { null }
            val processed = pipeline.processTexts(present.map { (index, text) -> TextItem(text, contextOf(index)) })
            val result = arrayOfNulls<String>(texts.size)
            present.forEachIndexed { batchIndex, (itemIndex, _) -> result[itemIndex] = processed[batchIndex] }
            return result.toList()
        }

        /**
         * The detection contexts of the nodes [nodeIds] in [windows], keyed by node id: one walk finds them, then the
         * nearest-label pass runs only over the windows that hold one of them.
         */
        private fun nodeContexts(
            windows: List<WindowData>,
            nodeIds: Set<String>,
        ): Map<String, DetectionContext> {
            if (nodeIds.isEmpty()) return emptyMap()
            val contexts = HashMap<String, DetectionContext>()
            for (window in windows) {
                val found = ArrayList<AccessibilityNodeData>()
                collectNodes(window.tree, nodeIds, found)
                if (found.isEmpty()) continue
                val nearestLabels = contextExtractor.computeNearestLabels(window.tree)
                for (node in found) {
                    contexts.putIfAbsent(node.id, contextExtractor.extract(node, nearestLabels[node.id]))
                }
            }
            return contexts
        }

        private fun collectNodes(
            node: AccessibilityNodeData,
            nodeIds: Set<String>,
            out: MutableList<AccessibilityNodeData>,
        ) {
            if (node.id in nodeIds) out += node
            node.children.forEach { collectNodes(it, nodeIds, out) }
        }
    }
