package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
 * used by the `get_screen_state` MCP tool and element action tools.
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
 * Represents a single window's parsed accessibility data with window metadata.
 *
 * @property windowId System-assigned unique window ID from [AccessibilityWindowInfo.getId].
 * @property windowType Window type label: APPLICATION, INPUT_METHOD, SYSTEM,
 *   ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY, or UNKNOWN.
 * @property packageName Package name of the window's root node, or null if unavailable.
 * @property title Window title (e.g., activity name, dialog title), or null if unavailable.
 * @property activityName Activity class name (best-effort, only for focused app window), or null.
 * @property layer Window layer (z-order from Android).
 * @property focused Whether this window currently has input focus.
 * @property tree The parsed accessibility node tree for this window.
 */
@Serializable
data class WindowData(
    val windowId: Int,
    val windowType: String,
    val packageName: String? = null,
    val title: String? = null,
    val activityName: String? = null,
    val layer: Int = 0,
    val focused: Boolean = false,
    val tree: AccessibilityNodeData,
)

/**
 * Result of multi-window accessibility tree parsing.
 *
 * @property windows List of parsed windows with metadata, ordered by z-order.
 * @property degraded True if the multi-window API was unavailable and the result
 *   fell back to single-window mode via [rootInActiveWindow]. When true, the output
 *   may not reflect all on-screen windows (e.g., system dialogs, permission popups).
 */
@Serializable
data class MultiWindowResult(
    val windows: List<WindowData>,
    val degraded: Boolean = false,
)

/**
 * Parses an [AccessibilityNodeInfo] tree into a serializable [AccessibilityNodeData] hierarchy.
 *
 * When a [nodeMap] parameter is provided to [parseTree], real [AccessibilityNodeInfo] references
 * are stored in it as [CachedNode] entries. Child nodes are NOT recycled during parsing in this
 * mode — the caller (e.g., `getFreshWindows`) accumulates references across multiple [parseTree]
 * calls and populates the [AccessibilityNodeCache] once with all windows' nodes.
 *
 * When [nodeMap] is null, child nodes are recycled after data extraction (original behavior).
 *
 * The root node passed to [parseTree] is NOT recycled by the parser — the caller retains
 * ownership. When [nodeMap] is provided, the root IS stored in the map.
 *
 * This class is Hilt-injectable and stateless (no injected cache, no internal mutable state).
 */
class AccessibilityTreeParser
    @Inject
    constructor() {
        /**
         * Parses the full accessibility tree starting from [rootNode].
         *
         * The [rootNode] is NOT recycled by this method. The caller retains ownership.
         *
         * @param rootNode The root [AccessibilityNodeInfo] to start parsing from. NOT recycled by the parser.
         * @param rootParentId Parent ID for the root node (used in nodeId generation, typically "root_w{windowId}").
         * @param nodeMap Optional output map. When non-null, real [AccessibilityNodeInfo] references are
         *   stored as [CachedNode] entries during traversal. Child nodes are NOT recycled in this mode —
         *   the caller accumulates references and populates [AccessibilityNodeCache] externally.
         *   When null, child nodes are recycled after data extraction (original behavior).
         * @return The parsed tree as [AccessibilityNodeData].
         */
        fun parseTree(
            rootNode: AccessibilityNodeInfo,
            rootParentId: String = ROOT_PARENT_ID,
            nodeMap: MutableMap<String, CachedNode>? = null,
        ): AccessibilityNodeData =
            parseNode(
                node = rootNode,
                depth = 0,
                index = 0,
                parentId = rootParentId,
                recycleNode = false,
                nodeMap = nodeMap,
            )

        /**
         * Parses a single [AccessibilityNodeInfo] and recursively parses its children.
         *
         * @param node The node to parse.
         * @param depth The depth of this node in the tree (root = 0).
         * @param index The index of this node among its siblings.
         * @param parentId The generated ID of the parent node.
         * @param recycleNode Whether to recycle [node] after parsing. When [nodeMap] is null, child
         *   nodes are recycled. When [nodeMap] is non-null, neither [node] nor its children are recycled.
         * @param nodeMap Optional output map for caching real node references. When non-null, the node
         *   is stored as a [CachedNode] and child nodes are not recycled. When null, nodes are recycled
         *   after data extraction per the original behavior.
         * @return The parsed node as [AccessibilityNodeData].
         */
        @Suppress("LongParameterList", "LongMethod")
        internal fun parseNode(
            node: AccessibilityNodeInfo,
            depth: Int,
            index: Int,
            parentId: String,
            recycleNode: Boolean = true,
            nodeMap: MutableMap<String, CachedNode>? = null,
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

            // Max depth protection: return current node as leaf without recursing into children
            if (depth >= MAX_TREE_DEPTH) {
                Log.w(TAG, "Maximum tree depth ($MAX_TREE_DEPTH) reached, truncating subtree")

                val leafNode =
                    AccessibilityNodeData(
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
                        children = emptyList(),
                    )

                // Store real node reference in cache map (if caching is active)
                nodeMap?.put(
                    nodeId,
                    CachedNode(node, depth, index, parentId),
                )

                if (recycleNode && nodeMap == null) {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }

                return leafNode
            }

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
                            recycleNode = nodeMap == null,
                            nodeMap = nodeMap,
                        ),
                    )
                }
            }

            val nodeData =
                AccessibilityNodeData(
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

            // Store real node reference + metadata in cache map (if caching is active).
            // Metadata (depth, index, parentId) is needed by ActionExecutorImpl to
            // re-verify node identity after refresh() (S1 fix).
            nodeMap?.put(nodeId, CachedNode(node, depth, index, parentId))

            // Defensive: nodeMap == null is redundant with recycleNode in normal flow,
            // but guards against direct parseNode calls with recycleNode=true + non-null nodeMap.
            if (recycleNode && nodeMap == null) {
                @Suppress("DEPRECATION")
                node.recycle()
            }

            return nodeData
        }

        /**
         * Checks whether [node] is visible to the user.
         */
        fun isNodeVisible(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser

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
            private const val TAG = "MCP:TreeParser"
            private const val ROOT_PARENT_ID = "root"
            private const val HASH_RADIX = 16
            internal const val MAX_TREE_DEPTH = 100

            /** Maps [AccessibilityWindowInfo] type constants to human-readable labels. */
            fun mapWindowType(type: Int): String =
                when (type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ->
                        "ACCESSIBILITY_OVERLAY"
                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ->
                        "SPLIT_SCREEN_DIVIDER"
                    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY ->
                        "MAGNIFICATION_OVERLAY"
                    else -> "UNKNOWN($type)"
                }
        }
    }
