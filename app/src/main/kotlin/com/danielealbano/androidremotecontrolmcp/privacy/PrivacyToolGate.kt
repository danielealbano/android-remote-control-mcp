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
         * (its text, its content description or the content of its field). Each text gets that node's detection
         * context (resource id, hint, label, password and editable flags), built like [tree] builds it for
         * `get_screen_state`, so node text is redacted the same way whichever tool returns it. Null texts stay null and
         * keep their slot; a node id that is not in [windows] gets an empty context.
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

        /** [nodeTexts] for one [text] read from the node [nodeId] of [windows]. */
        suspend fun nodeText(
            windows: List<WindowData>,
            text: String?,
            nodeId: String,
        ): String? = nodeTexts(windows, listOf(text to nodeId)).single()

        suspend fun tree(result: MultiWindowResult): ProcessedTree = pipeline.processTree(result)

        private suspend fun redactPresent(
            texts: List<String?>,
            contextOf: (Int) -> DetectionContext,
        ): List<String?> {
            val presentIndices = texts.indices.filter { texts[it] != null }
            if (presentIndices.isEmpty()) return texts.map { null }
            val processed = pipeline.processTexts(presentIndices.map { TextItem(texts[it]!!, contextOf(it)) })
            val result = arrayOfNulls<String>(texts.size)
            presentIndices.forEachIndexed { batchIndex, itemIndex -> result[itemIndex] = processed[batchIndex] }
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
