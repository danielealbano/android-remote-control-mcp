package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Cached node reference with the metadata needed to re-verify identity after
 * [AccessibilityNodeInfo.refresh].
 *
 * After `refresh()`, the node's properties (bounds, text, etc.) are updated from the live UI.
 * If the underlying View was reused for a different element (e.g., RecyclerView recycling),
 * the regenerated nodeId will differ from the original. Storing [depth], [index], and [parentId]
 * allows the consumer to re-generate the nodeId and detect identity changes (S1 fix).
 */
data class CachedNode(
    val node: AccessibilityNodeInfo,
    val depth: Int,
    val index: Int,
    val parentId: String,
)

/**
 * Cache for real [AccessibilityNodeInfo] references obtained during tree parsing.
 *
 * Enables O(1) node resolution by ID instead of O(N) tree walks via Binder IPC.
 * The cache is populated with node references obtained during [AccessibilityTreeParser.parseTree]
 * (via the caller's accumulation map) and consumed by [ActionExecutorImpl.performNodeAction].
 *
 * Thread-safe: all operations are safe to call from any thread.
 */
interface AccessibilityNodeCache {
    /**
     * Replaces the entire cache with a snapshot of [entries].
     *
     * A defensive copy (`toMap()`) is made so the caller cannot mutate the cached map
     * after this call returns. The previous cache entries are NOT recycled by this method —
     * a concurrent [get] call may still hold a reference to a node from the old map. Old
     * entries are dropped and collected by GC ([AccessibilityNodeInfo.recycle] is a no-op
     * on API 33+).
     *
     * @param entries Map of nodeId to [CachedNode]. May be mutable — a read-only copy is stored.
     */
    fun populate(entries: Map<String, CachedNode>)

    /**
     * Returns the [CachedNode] for [nodeId], or null if not cached.
     *
     * The returned node may be stale — callers MUST call [AccessibilityNodeInfo.refresh]
     * on [CachedNode.node] and re-verify identity before using it.
     */
    fun get(nodeId: String): CachedNode?

    /**
     * Clears all cached entries and recycles them.
     *
     * Recycling old entries is safe even if a concurrent [get] call holds a reference to a
     * [CachedNode] from the previous map, because [AccessibilityNodeInfo.recycle] is a no-op
     * on API 33+ (which is this project's minSdk). In practice, this is only called during
     * service shutdown ([McpAccessibilityService.onDestroy]) when no new requests are served.
     */
    fun clear()

    /**
     * Returns the number of currently cached entries.
     */
    fun size(): Int
}
