package com.danielealbano.androidremotecontrolmcp.services.accessibility

import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Criteria for searching accessibility nodes.
 */
enum class FindBy {
    /** Match by the node's text content. */
    TEXT,

    /** Match by the node's content description. */
    CONTENT_DESC,

    /** Match by the node's resource ID (e.g., "com.example:id/button1"). */
    RESOURCE_ID,

    /** Match by the node's class name (e.g., "android.widget.Button"). */
    CLASS_NAME,
}

/**
 * Represents a found UI element with key properties for MCP tool responses.
 *
 * @property id The node ID (matches [AccessibilityNodeData.id]).
 * @property text The text content of the element.
 * @property contentDescription The content description of the element.
 * @property resourceId The view ID resource name.
 * @property className The class name of the view.
 * @property bounds The screen bounds of the element.
 * @property clickable Whether the element is clickable.
 * @property longClickable Whether the element is long-clickable.
 * @property scrollable Whether the element is scrollable.
 * @property editable Whether the element is an editable text field.
 * @property enabled Whether the element is enabled.
 */
@Serializable
data class ElementInfo(
    val id: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: BoundsData,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = false,
)

/**
 * Finds elements in a parsed [AccessibilityNodeData] tree by various criteria.
 *
 * This class operates entirely on parsed tree data and does NOT access
 * [android.view.accessibility.AccessibilityNodeInfo] directly, making it
 * fully unit-testable without Android framework mocks.
 *
 * This class is Hilt-injectable and stateless.
 */
class ElementFinder
    @Inject
    constructor() {
        /**
         * Searches the [tree] recursively for elements matching the given [by] criteria and [value].
         *
         * @param tree The root of the parsed accessibility tree to search.
         * @param by The criteria to search by (text, content description, resource ID, class name).
         * @param value The value to search for.
         * @param exactMatch If true, matches require exact (case-sensitive) equality.
         *                   If false, matches use case-insensitive contains.
         * @return A list of [ElementInfo] for all matching nodes. Empty list if no matches.
         */
        fun findElements(
            tree: AccessibilityNodeData,
            by: FindBy,
            value: String,
            exactMatch: Boolean = false,
        ): List<ElementInfo> {
            val results = mutableListOf<ElementInfo>()
            searchRecursive(tree, by, value, exactMatch, results)
            return results
        }

        /**
         * Finds a specific [AccessibilityNodeData] by its node [nodeId].
         *
         * @param tree The root of the parsed accessibility tree to search.
         * @param nodeId The node ID to find.
         * @return The matching [AccessibilityNodeData], or null if not found.
         */
        @Suppress("ReturnCount")
        fun findNodeById(
            tree: AccessibilityNodeData,
            nodeId: String,
        ): AccessibilityNodeData? {
            if (tree.id == nodeId) return tree
            for (child in tree.children) {
                val found = findNodeById(child, nodeId)
                if (found != null) return found
            }
            return null
        }

        private fun searchRecursive(
            node: AccessibilityNodeData,
            by: FindBy,
            value: String,
            exactMatch: Boolean,
            results: MutableList<ElementInfo>,
        ) {
            val nodeValue =
                when (by) {
                    FindBy.TEXT -> node.text
                    FindBy.CONTENT_DESC -> node.contentDescription
                    FindBy.RESOURCE_ID -> node.resourceId
                    FindBy.CLASS_NAME -> node.className
                }

            if (matchesValue(nodeValue, value, exactMatch)) {
                results.add(toElementInfo(node))
            }

            for (child in node.children) {
                searchRecursive(child, by, value, exactMatch, results)
            }
        }

        /**
         * Compares a node's property value against the search value.
         *
         * @param nodeValue The value from the node (may be null).
         * @param searchValue The value to search for.
         * @param exactMatch If true, uses case-sensitive equality. If false, uses case-insensitive contains.
         * @return True if the values match according to the matching mode.
         */
        internal fun matchesValue(
            nodeValue: String?,
            searchValue: String,
            exactMatch: Boolean,
        ): Boolean {
            if (nodeValue == null) return false
            return if (exactMatch) {
                nodeValue == searchValue
            } else {
                nodeValue.contains(searchValue, ignoreCase = true)
            }
        }

        private fun toElementInfo(node: AccessibilityNodeData): ElementInfo {
            return ElementInfo(
                id = node.id,
                text = node.text,
                contentDescription = node.contentDescription,
                resourceId = node.resourceId,
                className = node.className,
                bounds = node.bounds,
                clickable = node.clickable,
                longClickable = node.longClickable,
                scrollable = node.scrollable,
                editable = node.editable,
                enabled = node.enabled,
            )
        }
    }
