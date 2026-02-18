package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

// NOTE: android.view.accessibility.AccessibilityNodeInfo is NOT explicitly imported because
// it is accessed only through CachedNode.node (same-package type). KDoc uses backtick notation.

/**
 * Thread-safe implementation of [AccessibilityNodeCache] using [AtomicReference].
 *
 * Uses atomic swap for lock-free cache replacement:
 * - [populate]: atomically swaps in a defensive copy of the provided map. Old entries are
 *   NOT recycled because a concurrent [get] call may still hold a reference to a [CachedNode]
 *   from the old map. Since `AccessibilityNodeInfo.recycle()` is a no-op on API 33+ (minSdk),
 *   GC safely collects unreferenced old entries. (P2 fix)
 * - [get]: reads the current map (no locking).
 * - [clear]: atomically swaps to an empty map AND recycles old entries. Recycling is
 *   safe even with concurrent [get] callers because `AccessibilityNodeInfo.recycle()` is
 *   a no-op on API 33+ (minSdk). In practice, only called during service shutdown
 *   ([McpAccessibilityService.onDestroy]) when no new requests are served.
 *
 * Hilt-injectable as a singleton.
 */
class AccessibilityNodeCacheImpl
    @Inject
    constructor() : AccessibilityNodeCache {
        private val cache = AtomicReference<Map<String, CachedNode>>(emptyMap())

        override fun populate(entries: Map<String, CachedNode>) {
            // Defensive copy: the caller passes a MutableMap (accumulatedNodeMap from
            // getFreshWindows). Storing the reference directly would allow the caller to
            // mutate the map after populate(), violating the immutable-snapshot guarantee
            // that concurrent get() callers rely on. toMap() creates a read-only copy.
            // Performance: O(N) copy for ~500 nodes is negligible. If profiling shows this
            // as a hotspot, HashMap(entries) could be more efficient due to pre-sizing.
            val snapshot = entries.toMap()
            val old = cache.getAndSet(snapshot)
            Log.d(TAG, "Cache populated with ${snapshot.size} entries (dropped ${old.size} old)")
        }

        override fun get(nodeId: String): CachedNode? = cache.get()[nodeId]

        override fun clear() {
            val old = cache.getAndSet(emptyMap())
            recycleEntries(old)
            Log.d(TAG, "Cache cleared (recycled ${old.size} entries)")
        }

        override fun size(): Int = cache.get().size

        private fun recycleEntries(entries: Map<String, CachedNode>) {
            for ((_, cached) in entries) {
                @Suppress("DEPRECATION")
                cached.node.recycle()
            }
        }

        companion object {
            private const val TAG = "MCP:NodeCache"
        }
    }
