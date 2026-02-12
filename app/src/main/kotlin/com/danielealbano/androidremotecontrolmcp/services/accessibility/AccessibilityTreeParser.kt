package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Represents the screen bounds of an accessibility node.
 */
@Serializable
data class BoundsData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Represents a parsed accessibility node with all relevant properties.
 *
 * This is a JSON-serializable representation of [AccessibilityNodeInfo],
 * used as the MCP protocol output for `get_accessibility_tree` and related tools.
 *
 * @property id Stable generated ID for this node, used by element action tools.
 * @property className The class name of the view (e.g., "android.widget.Button").
 * @property text The text content of the node.
 * @property contentDescription The content description for accessibility.
 * @property resourceId The view ID resource name (e.g., "com.example:id/button1").
 * @property bounds The screen bounds of the node.
 * @property clickable Whether the node responds to click actions.
 * @property longClickable Whether the node responds to long-click actions.
 * @property focusable Whether the node can receive focus.
 * @property scrollable Whether the node is scrollable.
 * @property editable Whether the node is an editable text field.
 * @property enabled Whether the node is enabled.
 * @property visible Whether the node is visible to the user.
 * @property children The child nodes of this node.
 */
@Serializable
data class AccessibilityNodeData(
    val id: String,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val bounds: BoundsData,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = false,
    val visible: Boolean = false,
    val children: List<AccessibilityNodeData> = emptyList(),
)

/**
 * Parses an [AccessibilityNodeInfo] tree into a serializable [AccessibilityNodeData] hierarchy.
 *
 * All [AccessibilityNodeInfo] child nodes obtained via [AccessibilityNodeInfo.getChild] are
 * recycled after their data has been extracted. The root node passed to [parseTree] is NOT
 * recycled by the parser -- the caller is responsible for recycling it.
 *
 * This class is Hilt-injectable and stateless.
 */
class AccessibilityTreeParser
    @Inject
    constructor() {
        /**
         * Parses the full accessibility tree starting from [rootNode].
         *
         * The [rootNode] is NOT recycled by this method. The caller retains ownership.
         *
         * @param rootNode The root [AccessibilityNodeInfo] to parse.
         * @return The parsed tree as [AccessibilityNodeData].
         */
        fun parseTree(rootNode: AccessibilityNodeInfo): AccessibilityNodeData {
            return parseNode(
                node = rootNode,
                depth = 0,
                index = 0,
                parentId = ROOT_PARENT_ID,
                recycleNode = false,
            )
        }

        /**
         * Parses a single [AccessibilityNodeInfo] and recursively parses its children.
         *
         * @param node The node to parse.
         * @param depth The depth of this node in the tree (root = 0).
         * @param index The index of this node among its siblings.
         * @param parentId The generated ID of the parent node.
         * @param recycleNode Whether to recycle [node] after parsing. Child nodes are always recycled.
         * @return The parsed node as [AccessibilityNodeData].
         */
        internal fun parseNode(
            node: AccessibilityNodeInfo,
            depth: Int,
            index: Int,
            parentId: String,
            recycleNode: Boolean = true,
        ): AccessibilityNodeData {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds =
                BoundsData(
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                )

            val nodeId = generateNodeId(node, bounds, depth, index, parentId)

            val className = node.className?.toString()
            val text = node.text?.toString()
            val contentDescription = node.contentDescription?.toString()
            val resourceId = node.viewIdResourceName
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val focusable = node.isFocusable
            val scrollable = node.isScrollable
            val editable = node.isEditable
            val enabled = node.isEnabled
            val visible = isNodeVisible(node)

            val children = mutableListOf<AccessibilityNodeData>()
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val childNode = node.getChild(i)
                if (childNode != null) {
                    children.add(
                        parseNode(
                            node = childNode,
                            depth = depth + 1,
                            index = i,
                            parentId = nodeId,
                            recycleNode = true,
                        ),
                    )
                }
            }

            if (recycleNode) {
                @Suppress("DEPRECATION")
                node.recycle()
            }

            return AccessibilityNodeData(
                id = nodeId,
                className = className,
                text = text,
                contentDescription = contentDescription,
                resourceId = resourceId,
                bounds = bounds,
                clickable = clickable,
                longClickable = longClickable,
                focusable = focusable,
                scrollable = scrollable,
                editable = editable,
                enabled = enabled,
                visible = visible,
                children = children,
            )
        }

        /**
         * Checks whether [node] is visible to the user.
         */
        fun isNodeVisible(node: AccessibilityNodeInfo): Boolean {
            return node.isVisibleToUser
        }

        /**
         * Generates a stable, deterministic node ID based on the node's properties.
         *
         * The ID is stable across tree parses as long as the UI state has not changed.
         * Uses resource ID, class name, bounds, depth, and sibling index for uniqueness.
         */
        internal fun generateNodeId(
            node: AccessibilityNodeInfo,
            bounds: BoundsData,
            depth: Int,
            index: Int,
            parentId: String,
        ): String {
            val resourceId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val hashInput =
                "$resourceId|$className|${bounds.left},${bounds.top}," +
                    "${bounds.right},${bounds.bottom}|$depth|$index|$parentId"
            val hash = hashInput.hashCode().toUInt().toString(HASH_RADIX)
            return "node_$hash"
        }

        companion object {
            private const val ROOT_PARENT_ID = "root"
            private const val HASH_RADIX = 16
        }
    }
