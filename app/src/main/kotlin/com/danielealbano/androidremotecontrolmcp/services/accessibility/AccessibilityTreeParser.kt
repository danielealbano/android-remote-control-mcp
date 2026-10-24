package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import javax.inject.Inject

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
            val isPassword = node.isPassword
            val hintText = node.hintText?.toString()?.takeIf { it.isNotEmpty() }
            val labeledByText = readLabeledByText(node)
            val enabled = node.isEnabled
            val visible = isNodeVisible(node)

            // Chromium (Chrome + Android System WebView) populates these extras on every web node.
            // They are absent on native/Compose nodes, so webRole/targetUrl stay null there.
            val extras = node.extras
            val webRole = extras?.getString(EXTRA_KEY_CHROME_ROLE)?.takeIf(String::isNotEmpty)
            val targetUrl = extras?.getString(EXTRA_KEY_TARGET_URL)?.takeIf(String::isNotEmpty)

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
                        isPassword = isPassword,
                        hintText = hintText,
                        labeledByText = labeledByText,
                        enabled = enabled,
                        visible = visible,
                        webRole = webRole,
                        targetUrl = targetUrl,
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
                    // Refresh nodes that are likely virtual (Compose, WebView, custom
                    // AccessibilityNodeProvider implementations). Virtual nodes can
                    // return stale cached data after recomposition/DOM changes.
                    // - No viewIdResourceName: virtual nodes typically lack resource IDs
                    // - Compose extra data key: definitive marker for Compose virtual nodes
                    // Nodes with a resource ID are real Views and don't need refresh.
                    if (childNode.viewIdResourceName == null ||
                        childNode.availableExtraData.contains(COMPOSE_SEMANTICS_ID_KEY)
                    ) {
                        childNode.refresh()
                    }
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
                    isPassword = isPassword,
                    hintText = hintText,
                    labeledByText = labeledByText,
                    enabled = enabled,
                    visible = visible,
                    webRole = webRole,
                    targetUrl = targetUrl,
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
         * Resolves the text of the node's first label association through
         * [AccessibilityNodeInfoCompat.getLabeledByList] (the API 36 list getter, or the single-label getter it
         * replaced on older releases), preferring the label's text and falling back to its content description.
         * Returns null when there is no label or it has no text. Reading the label can throw
         * [IllegalStateException] on stale nodes, so failures are swallowed to null.
         */
        private fun readLabeledByText(node: AccessibilityNodeInfo): String? =
            runCatching {
                AccessibilityNodeInfoCompat
                    .wrap(node)
                    .labeledByList
                    .firstOrNull()
                    ?.unwrap()
                    ?.let { label ->
                        (label.text?.toString() ?: label.contentDescription?.toString())?.takeIf { it.isNotEmpty() }
                    }
            }.getOrNull()

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

            /** `getExtras()` key holding the Chromium DOM role on web accessibility nodes. */
            private const val EXTRA_KEY_CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"

            /** `getExtras()` key holding the target URL for web links and images. */
            private const val EXTRA_KEY_TARGET_URL = "AccessibilityNodeInfo.targetUrl"

            /**
             * Extra data key added by Compose's AccessibilityNodeProvider to every
             * virtual accessibility node. Present in [AccessibilityNodeInfo.getAvailableExtraData].
             */
            internal const val COMPOSE_SEMANTICS_ID_KEY = "androidx.compose.ui.semantics.id"

            /** Maps [AccessibilityWindowInfo] type constants to human-readable labels. */
            fun mapWindowType(type: Int): String =
                when (type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        "APPLICATION"
                    }

                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                        "INPUT_METHOD"
                    }

                    AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        "SYSTEM"
                    }

                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> {
                        "ACCESSIBILITY_OVERLAY"
                    }

                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> {
                        "SPLIT_SCREEN_DIVIDER"
                    }

                    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> {
                        "MAGNIFICATION_OVERLAY"
                    }

                    else -> {
                        "UNKNOWN($type)"
                    }
                }
        }
    }
