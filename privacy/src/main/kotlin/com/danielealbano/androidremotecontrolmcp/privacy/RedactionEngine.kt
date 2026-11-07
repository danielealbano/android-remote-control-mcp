package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerEngine
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure detection + rendering core: structural/deterministic detection plus (when the config requires
 * it) one batched model pass, category filter BEFORE merge, rendering via [Redactor], and the
 * accessibility-tree walk. Contains NO enable/readiness gating and NO MCP error mapping —
 * [PrivacyPipelineImpl] gates and maps
 * [com.danielealbano.androidremotecontrolmcp.privacy.ner.PrivacyModelException]; the effectiveness
 * benchmark drives this class directly so published numbers measure the production code path.
 */
@Singleton
class RedactionEngine
    @Inject
    constructor(
        private val deterministicEngine: DeterministicEngine,
        private val nerEngine: NerEngine,
        private val contextExtractor: ContextExtractor,
        private val redactor: Redactor,
    ) {
        /** Merged, category-filtered detections per item. Model errors propagate as PrivacyModelException. */
        suspend fun detect(
            items: List<TextItem>,
            config: PrivacyModeConfig,
        ): List<List<PiiDetection>> {
            val deterministic = items.map { deterministicEngine.detectAll(it.text, it.context) }
            val modelByIndex = if (config.modelRequired()) runModel(items) else emptyMap()
            return items.mapIndexed { index, _ ->
                // Filter by enabled category BEFORE merging so a disabled category can never suppress an
                // overlapping still-enabled detection (which would then leave that span redacted by nobody).
                val enabled =
                    (deterministic[index] + modelByIndex[index].orEmpty())
                        .filter { config.isCategoryEnabled(it.category) }
                DeterministicEngine.mergeOverlaps(enabled)
            }
        }

        suspend fun redactTexts(
            items: List<TextItem>,
            config: PrivacyModeConfig,
        ): List<String> {
            val detections = detect(items, config)
            return items.mapIndexed { index, item -> redactor.apply(item.text, detections[index], config) }
        }

        private suspend fun runModel(items: List<TextItem>): Map<Int, List<PiiDetection>> {
            val segments =
                items.mapIndexedNotNull { index, item ->
                    if (item.text.isBlank()) {
                        null
                    } else {
                        NerSegment(index.toString(), item.context.contextText(), item.text)
                    }
                }
            if (segments.isEmpty()) return emptyMap()
            val results = nerEngine.detect(segments)
            return segments.associate { it.key.toInt() to results[it.key].orEmpty() }
        }

        suspend fun redactTree(
            result: MultiWindowResult,
            config: PrivacyModeConfig,
        ): ProcessedTree {
            val items = mutableListOf<TextItem>()
            val refs = mutableListOf<FieldRef>()
            for (window in result.windows) {
                val nearestLabels = contextExtractor.computeNearestLabels(window.tree)
                collectFields(window.tree, nearestLabels, items, refs)
            }
            val redacted = redactTexts(items, config)
            val byNode = RedactedFields()
            redacted.forEachIndexed { index, value ->
                val ref = refs[index]
                when (ref.field) {
                    Field.TEXT -> byNode.text[ref.nodeId] = value
                    Field.DESCRIPTION -> byNode.description[ref.nodeId] = value
                    Field.ACTION -> byNode.labels.add(value)
                }
            }
            val flaggedBounds = mutableListOf<BoundsData>()
            val newWindows = result.windows.map { it.copy(tree = rebuild(it.tree, byNode, flaggedBounds)) }
            return ProcessedTree(result.copy(windows = newWindows), flaggedBounds)
        }

        private fun collectFields(
            node: AccessibilityNodeData,
            nearestLabels: Map<String, String>,
            items: MutableList<TextItem>,
            refs: MutableList<FieldRef>,
        ) {
            val context = contextExtractor.extract(node, nearestLabels[node.id])
            node.text?.takeIf { it.isNotBlank() }?.let {
                items += TextItem(it, context)
                refs += FieldRef(node.id, Field.TEXT)
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                items += TextItem(it, context)
                refs += FieldRef(node.id, Field.DESCRIPTION)
            }
            // Action labels are never blank: the parser lists only labels with content.
            for (label in node.actions) {
                items += TextItem(label, context)
                refs += FieldRef(node.id, Field.ACTION)
            }
            node.children.forEach { collectFields(it, nearestLabels, items, refs) }
        }

        private fun rebuild(
            node: AccessibilityNodeData,
            byNode: RedactedFields,
            flaggedBounds: MutableList<BoundsData>,
        ): AccessibilityNodeData {
            val newText = byNode.text[node.id] ?: node.text
            val newDesc = byNode.description[node.id] ?: node.contentDescription
            // Before the children: this walk takes the labels in the order collectFields gave them.
            val newActions = if (node.actions.isEmpty()) node.actions else byNode.takeLabels(node.actions.size)
            if (newText != node.text || newDesc != node.contentDescription || newActions != node.actions) {
                flaggedBounds += node.bounds
            }
            return node.copy(
                text = newText,
                contentDescription = newDesc,
                actions = newActions,
                children = node.children.map { rebuild(it, byNode, flaggedBounds) },
            )
        }

        /** Which field of a node a redacted text belongs to. */
        private enum class Field { TEXT, DESCRIPTION, ACTION }

        private data class FieldRef(
            val nodeId: String,
            val field: Field,
        )

        /**
         * The redacted values of a tree: text and content description by node id, and the action labels in the
         * pre-order [collectFields] walks, which [rebuild] repeats. Labels go by position, not by id: two nodes can
         * share an id, and each must keep its own labels.
         */
        private class RedactedFields {
            val text = HashMap<String, String>()
            val description = HashMap<String, String>()
            val labels = ArrayList<String>()
            private var nextLabel = 0

            /** The next [count] labels, in order. */
            fun takeLabels(count: Int): List<String> {
                val taken = labels.subList(nextLabel, nextLabel + count).toList()
                nextLabel += count
                return taken
            }
        }
    }
