package com.danielealbano.androidremotecontrolmcp.testutil

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode

/**
 * The node references a read of [tree] returns: the entries of [nodes] whose ids are in [tree]. A stubbed
 * `AccessibilityTreeParser.parseTree` puts these into its accumulator, as the real parser puts exactly the nodes of the
 * tree it returns.
 */
fun readNodesOf(
    tree: AccessibilityNodeData,
    nodes: Map<String, CachedNode>,
): Map<String, CachedNode> {
    val ids = HashSet<String>()

    fun collect(node: AccessibilityNodeData) {
        ids += node.id
        node.children.forEach(::collect)
    }
    collect(tree)
    return nodes.filterKeys { it in ids }
}
