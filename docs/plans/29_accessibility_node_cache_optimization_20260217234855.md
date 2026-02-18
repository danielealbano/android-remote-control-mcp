# Plan 29: Accessibility Node Cache Optimization

**Created**: 2026-02-17 23:48:55
**Status**: Draft
**Branch**: `feat/accessibility-node-cache`

## Problem Statement

When MCP tools execute node-targeted actions (click_element, long_click_element, scroll_to_element,
type_* tools), the system performs two full accessibility tree traversals via Binder IPC:

1. **Parse phase** (`getFreshWindows` → `parseTree`): Walks the entire real `AccessibilityNodeInfo`
   tree via `getChild(i)` IPC calls (O(N) per window) to build `AccessibilityNodeData`.
2. **Resolve phase** (`performNodeAction` → `walkAndMatch`): Walks the real tree **again** via
   `getChild(i)` IPC calls (O(N) per window) to find the real `AccessibilityNodeInfo` matching a
   `nodeId`.

This doubles the Binder IPC cost of every node action. For complex UIs with hundreds or thousands
of nodes, this adds meaningful latency.

## Solution (Agreed)

Introduce an `AccessibilityNodeCache` — a dedicated Hilt singleton that caches real
`AccessibilityNodeInfo` references during parsing. On node action, check the cache first with
`AccessibilityNodeInfo.refresh()` (single O(1) IPC call). Fall back to the existing `walkAndMatch`
if the cache misses or `refresh()` returns `false` (node stale).

## Design Decisions (Agreed)

| Decision | Choice |
|---|---|
| Cache location | New `AccessibilityNodeCache` interface + `AccessibilityNodeCacheImpl` class (separation of concerns) |
| Cache scope | One single cache instance, populated once per `getFreshWindows` call with nodes from ALL windows |
| Cache population | `getFreshWindows` accumulates a `MutableMap<String, CachedNode>` across all `parseTree` calls in its window loop, then calls `nodeCache.populate()` once. `parseTree` does NOT populate the cache itself — it only fills the caller-provided `nodeMap`. This ensures all windows' nodes are in a single cache snapshot. |
| Root node lifecycle during parse | When caching is active, callers of `parseTree` (i.e., `getFreshWindows`) do NOT recycle root nodes — they are stored in the cache. On API 33+ `recycle()` is a no-op, but semantically the cache owns the nodes. |
| Cache data structure | `Map<String, CachedNode>` (nodeId → `CachedNode` wrapping real node + depth/index/parentId metadata for identity re-verification) |
| Thread safety | `AtomicReference<Map<String, CachedNode>>` for lock-free atomic swaps |
| Validation | `AccessibilityNodeInfo.refresh()` — single Binder IPC call, returns `true` if node still valid |
| Fallback | On cache miss or `refresh()` returns `false` → fall back to `walkAndMatch` (current behavior, no regression) |
| TTL | No TTL — `refresh()` handles correctness, `parseTree` calls provide natural cache replacement |
| Service stop | Cache flushed when `McpAccessibilityService.onDestroy()` fires |
| Node recycling during parse | Stop recycling child nodes during `parseNode` when `nodeMap` is provided (they're stored in cache instead); `recycle()` is a no-op on minSdk 33+ but code should not call it on cached nodes for clarity |
| Old cache cleanup on populate | Do NOT recycle old entries in `populate()` — a concurrent `get()` may still hold a reference. Let GC handle cleanup (`recycle()` is a no-op on minSdk 33+). |
| Old cache cleanup on clear | `clear()` recycles all entries. Recycling is safe even with concurrent `get()` callers because `recycle()` is a no-op on API 33+ (minSdk). In practice, `clear()` is only called during service shutdown (`onDestroy`) when no new requests are served. |
| Post-refresh identity check | After `refresh()` succeeds, re-generate the nodeId from refreshed properties and compare with the requested nodeId. If mismatched, the node has changed identity → treat as cache miss and fall back to `walkAndMatch`. |
| `onTrimMemory` behavior | The cache is NOT cleared on `onTrimMemory` callbacks. Rationale: (1) the cache is small (~100-250KB for ~500 nodes), (2) it is already replaced atomically on every `getFreshWindows` call (natural turnover), and (3) clearing it would force the next action to take the slow `walkAndMatch` path unnecessarily. Memory savings from clearing the cache would be negligible compared to the performance regression. If this becomes a concern, a future plan can add `TRIM_MEMORY_RUNNING_CRITICAL` handling. **Edge case**: If `getFreshWindows` fails mid-way through parsing (e.g., exception during the window loop), `populate()` is never called and the cache retains the previous snapshot. This is harmless — the fallback `walkAndMatch` path handles node resolution, and the next successful `getFreshWindows` call replaces the cache. No special cleanup is needed. |

## Implementation Overview

### Files to create:
- `app/src/main/kotlin/.../services/accessibility/AccessibilityNodeCache.kt` (interface + `CachedNode` data class)
- `app/src/main/kotlin/.../services/accessibility/AccessibilityNodeCacheImpl.kt` (implementation)
- `app/src/test/kotlin/.../services/accessibility/AccessibilityNodeCacheImplTest.kt` (unit tests)

### Files to modify:
- `app/src/main/kotlin/.../services/accessibility/AccessibilityTreeParser.kt` (add `nodeMap` output parameter to `parseTree`/`parseNode`, stop recycling when caching)
- `app/src/main/kotlin/.../services/accessibility/ActionExecutorImpl.kt` (inject cache + tree parser, cache check with identity verification before walkAndMatch)
- `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` (flush cache on destroy)
- `app/src/main/kotlin/.../di/AppModule.kt` (bind cache interface)
- `app/src/main/kotlin/.../mcp/tools/ElementActionTools.kt` (`getFreshWindows` gains `nodeCache` param, accumulates nodeMap across windows, populates cache once; tool classes gain `nodeCache` constructor param; `registerElementActionTools` gains `nodeCache` param)
- `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt` (tool classes gain `nodeCache` constructor param; `registerTextInputTools` gains `nodeCache` param)
- `app/src/main/kotlin/.../mcp/tools/ScreenIntrospectionTools.kt` (tool class gains `nodeCache` constructor param; `registerScreenIntrospectionTools` gains `nodeCache` param)
- `app/src/main/kotlin/.../mcp/tools/UtilityTools.kt` (tool classes gain `nodeCache` constructor param; `registerUtilityTools` gains `nodeCache` param)
- `app/src/main/kotlin/.../services/mcp/McpServerService.kt` (`@Inject` `nodeCache`, pass to all affected `register*Tools` functions)
- `app/src/test/kotlin/.../services/accessibility/AccessibilityTreeParserTest.kt` (update for `nodeMap` parameter, verify map population)
- `app/src/test/kotlin/.../services/accessibility/ActionExecutorImplTest.kt` (update for cache + tree parser injection, add cache + identity tests)
- `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` (pass `nodeCache` mock to register functions)
- `app/src/test/kotlin/.../mcp/tools/ElementActionToolsTest.kt` (add `mockNodeCache`, update tool constructors, update `parseTree` mocks to 3-arg, remove `rootNode.recycle()` mocks)
- `app/src/test/kotlin/.../mcp/tools/TextInputToolsTest.kt` (add `mockNodeCache`, update tool constructors, update `parseTree` mocks to 3-arg, remove `rootNode.recycle()` mocks)
- `app/src/test/kotlin/.../mcp/tools/ScreenIntrospectionToolsTest.kt` (add `mockNodeCache`, update handler constructor, update `parseTree` mocks to 3-arg, update `recyclesRootNodeAndWindowInfoAfterParsing` test — root nodes no longer recycled)
- `app/src/test/kotlin/.../mcp/tools/UtilityToolsTest.kt` (add `mockNodeCache`, update tool constructors, update `parseTree` mocks to 3-arg, remove `rootNode.recycle()` mocks)
- `app/src/test/kotlin/.../mcp/tools/GetElementDetailsToolTest.kt` (add `mockNodeCache`, update tool constructor, update `parseTree` mock to 3-arg)
- `app/src/test/kotlin/.../integration/ScreenIntrospectionIntegrationTest.kt` (update local `parseTree` mocks to 3-arg, remove `rootNode.recycle()` mocks)
- `app/src/test/kotlin/.../integration/ElementActionIntegrationTest.kt` (update local `parseTree` mocks to 3-arg, remove `rootNode.recycle()` mocks)
- `app/src/test/kotlin/.../integration/ErrorHandlingIntegrationTest.kt` (update local `parseTree` mock to 3-arg, remove `rootNode.recycle()` mock)

---

## User Story 1: Create AccessibilityNodeCache Interface and Implementation

**Goal**: Create the cache class with thread-safe populate/get/clear operations.

**Acceptance Criteria / Definition of Done**:
- [x] `CachedNode` data class exists with `node`, `depth`, `index`, `parentId` fields
- [x] `AccessibilityNodeCache` interface exists with `populate`, `get`, `clear`, `size` methods using `CachedNode`
- [x] `AccessibilityNodeCacheImpl` implements the interface using `AtomicReference<Map<String, CachedNode>>`
- [x] `populate` atomically swaps in a new map. Old entries are NOT recycled (concurrent `get` safety — P2 fix)
- [x] `clear` atomically swaps to empty map and calls `recycle()` on all entries from the old map (safe: only called during shutdown)
- [x] `get` returns the cached `CachedNode` or null
- [x] `size` returns the current number of cached entries
- [x] Bound in Hilt `ServiceModule` (inside `AppModule.kt`) as `@Singleton`
- [x] Unit tests pass for all cache operations (including P2 fix verification)
- [x] Linting passes on all new/changed files

### Task 1.1: Create `CachedNode` data class and `AccessibilityNodeCache` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityNodeCache.kt`

**Action**: Create new file with the `CachedNode` data class and the `AccessibilityNodeCache` interface:

```kotlin
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
```

**Definition of Done**:
- [x] File created with `CachedNode` data class and `AccessibilityNodeCache` interface
- [x] Linting passes

### Task 1.2: Create `AccessibilityNodeCacheImpl` class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityNodeCacheImpl.kt`

**Action**: Create new file with the implementation:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
// NOTE: android.view.accessibility.AccessibilityNodeInfo is NOT explicitly imported.
// Method calls like cached.node.recycle() resolve the type from CachedNode.node's
// declared type. KDoc references use fully-qualified names if needed:
// [android.view.accessibility.AccessibilityNodeInfo.recycle]. If ktlint reports
// an unused import after adding it, remove it and use fully-qualified KDoc references.

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
```

**Definition of Done**:
- [x] File created with the implementation above
- [x] Linting passes

### Task 1.3: Register in Hilt `ServiceModule`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

**Action**: Add binding in `ServiceModule`:

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCacheImpl

 abstract class ServiceModule {
+    @Binds
+    @Singleton
+    abstract fun bindAccessibilityNodeCache(impl: AccessibilityNodeCacheImpl): AccessibilityNodeCache
+
     @Binds
     @Singleton
     abstract fun bindApiLevelProvider(impl: DefaultApiLevelProvider): ApiLevelProvider
```

**Definition of Done**:
- [x] Binding added after `ServiceModule` class declaration, before existing bindings
- [x] Linting passes

### Task 1.4: Unit tests for `AccessibilityNodeCacheImpl`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityNodeCacheImplTest.kt`

**Action**: Create test file covering:

1. **`populate` replaces cache without recycling old entries** (P2 fix):
   - Populate with `CachedNode` entries A, then populate with `CachedNode` entries B
   - Verify entries A were NOT recycled (`recycle()` not called on A's nodes)
   - Verify entries B are accessible via `get` and return correct `CachedNode`
2. **`get` returns cached node with metadata**:
   - Populate with a known nodeId → `CachedNode(mockNode, depth=2, index=1, parentId="parent")`
   - Verify `get(nodeId)` returns the `CachedNode` with correct `node`, `depth`, `index`, `parentId`
3. **`get` returns null for unknown nodeId**:
   - Populate with entries
   - Verify `get("unknown")` returns null
4. **`clear` removes all entries and recycles them**:
   - Populate with `CachedNode` entries
   - Call `clear()`
   - Verify `size()` is 0, verify `recycle()` was called on each `CachedNode.node`
5. **`size` returns correct count**:
   - Empty cache → 0
   - Populate with 3 `CachedNode` entries → 3
   - Clear → 0
6. **`populate` with empty map clears cache without recycling**:
   - Populate with `CachedNode` entries, then populate with empty map
   - Verify old entries NOT recycled, `size()` is 0
7. **`clear` after populate recycles only current entries**:
   - Populate with `CachedNode` entries A, then populate with `CachedNode` entries B, then `clear()`
   - Verify `recycle()` called on B's `CachedNode.node` entries, NOT on A's `CachedNode.node` entries

**Definition of Done**:
- [x] All tests pass
- [x] Linting passes

---

## User Story 2: Modify AccessibilityTreeParser to Support Cache Population

**Goal**: Add a `nodeMap` output parameter to `parseTree` and `parseNode`. When the caller
provides a `MutableMap<String, CachedNode>`, the parser stores real `AccessibilityNodeInfo`
references into it during traversal and stops recycling nodes. The parser does NOT call
`cache.populate()` — that responsibility belongs to the caller (`getFreshWindows`), which
accumulates nodes from all windows before populating. This design keeps the parser stateless and
avoids the multi-window cache overwrite problem (Critical Finding 1).

> **Note**: `AccessibilityTreeParser` is NOT annotated `@Singleton` and is not explicitly
> scoped. Hilt will create a new instance for each injection point by default. This is fine
> because the parser is stateless — `generateNodeId` is a pure function and the `nodeMap`
> parameter is caller-provided. `ActionExecutorImpl` and tool classes may receive different
> parser instances; this has no impact on correctness (Minor Finding 8).

**Acceptance Criteria / Definition of Done**:
- [x] `parseTree` accepts optional `nodeMap: MutableMap<String, CachedNode>?` parameter (default `null`)
- [x] `parseNode` accepts optional `nodeMap: MutableMap<String, CachedNode>?` parameter (default `null`)
- [x] When `nodeMap` is non-null, each parsed node is stored as a `CachedNode` in the map
- [x] When `nodeMap` is non-null, child nodes are NOT recycled (stored in map instead)
- [x] When `nodeMap` is null, existing recycle behavior is preserved (backward compatible)
- [x] `parseTree` does NOT call `cache.populate()` — the caller accumulates and populates
- [x] `AccessibilityTreeParser` constructor is UNCHANGED (no cache injection)
- [x] Root node is still NOT recycled by `parseTree` (caller retains ownership), but IS stored in nodeMap when provided
- [x] Existing unit tests updated to pass `nodeMap` where appropriate
- [x] New tests verify nodeMap is populated during parsing
- [x] Linting passes on all changed files

### Task 2.1: Add `nodeMap` parameter to `parseTree`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Action**: Add optional `nodeMap` parameter. When provided, pass it to `parseNode`. The parser
does NOT call `cache.populate()` — the caller is responsible for accumulating across multiple
`parseTree` calls (e.g., multi-window loop in `getFreshWindows`) and populating the cache once.

```diff
     fun parseTree(
         rootNode: AccessibilityNodeInfo,
         rootParentId: String = ROOT_PARENT_ID,
-    ): AccessibilityNodeData =
-        parseNode(
+        nodeMap: MutableMap<String, CachedNode>? = null,
+    ): AccessibilityNodeData =
+        parseNode(
             node = rootNode,
             depth = 0,
             index = 0,
             parentId = rootParentId,
             recycleNode = false,
+            nodeMap = nodeMap,
         )
```

**Definition of Done**:
- [x] `nodeMap` parameter added to `parseTree`
- [x] Parameter is passed through to `parseNode`
- [x] No `cache.populate()` call — caller is responsible
- [x] Linting passes

### Task 2.2: Modify `parseNode` to store nodes in map and stop recycling

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Action**: Add `nodeMap` parameter to `parseNode`. Store each node as a `CachedNode` (with depth,
index, parentId metadata for S1 identity re-verification). Remove the `recycle()` call on child
nodes when caching is active:

```diff
     internal fun parseNode(
         node: AccessibilityNodeInfo,
         depth: Int,
         index: Int,
         parentId: String,
         recycleNode: Boolean = true,
+        nodeMap: MutableMap<String, CachedNode>? = null,
     ): AccessibilityNodeData {
         val rect = Rect()
         node.getBoundsInScreen(rect)
         val bounds = BoundsData(
             left = rect.left,
             top = rect.top,
             right = rect.right,
             bottom = rect.bottom,
         )

         val nodeId = generateNodeId(node, bounds, depth, index, parentId)
+
+        // Store real node reference + metadata in cache map (if caching is active).
+        // Metadata (depth, index, parentId) is needed by ActionExecutorImpl to
+        // re-verify node identity after refresh() (S1 fix).
+        nodeMap?.put(nodeId, CachedNode(node, depth, index, parentId))

         val className = node.className?.toString()
         // ... (remaining property extraction unchanged) ...

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
-                        recycleNode = true,
+                        recycleNode = nodeMap == null,
+                        nodeMap = nodeMap,
                     ),
                 )
             }
         }

-        if (recycleNode) {
+        if (recycleNode && nodeMap == null) {
             @Suppress("DEPRECATION")
             node.recycle()
         }

         return AccessibilityNodeData(
             // ... unchanged ...
         )
     }
```

Key points:
- When `nodeMap` is non-null (caching active), child nodes are NOT recycled — they're stored as `CachedNode`.
- When `nodeMap` is null (no caching, e.g., direct `parseNode` call in tests), existing recycle behavior is preserved.
- When caching is active (`nodeMap` is non-null), `recycleNode` for children is set to `false` because the expression `nodeMap == null` evaluates to `false`.
- The `recycleNode && nodeMap == null` condition is defensively redundant for the normal flow (where `recycleNode` already reflects `nodeMap`'s state). However, it is kept explicitly to guard against direct `parseNode` calls that might set `recycleNode = true` alongside a non-null `nodeMap`. An inline comment in the code explains this intent (Minor Finding 7 clarification).
- Each `CachedNode` stores `depth`, `index`, and `parentId` so the consumer can re-generate the nodeId after `refresh()` (S1 identity check).

**Definition of Done**:
- [x] `nodeMap` parameter added to `parseNode`
- [x] Nodes stored in map when `nodeMap` is non-null
- [x] Child nodes NOT recycled when caching is active
- [x] Existing non-cached behavior preserved when `nodeMap` is null
- [x] Defensive double condition has inline comment explaining intent (Minor Finding 7)
- [x] Linting passes

### Task 2.3: Update `AccessibilityTreeParser` KDoc (class-level and function-level)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Action**: Update **both** the class-level KDoc and the function-level KDoc for `parseTree` and
`parseNode` to reflect the new `nodeMap` parameter and conditional recycling behavior.

Update from:
```
 * All [AccessibilityNodeInfo] child nodes obtained via [AccessibilityNodeInfo.getChild] are
 * recycled after their data has been extracted. The root node passed to [parseTree] is NOT
 * recycled by the parser -- the caller is responsible for recycling it.
 *
 * This class is Hilt-injectable and stateless.
```

To:
```
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
```

Also update `parseTree` function-level KDoc to document the new `nodeMap` parameter:
```
 * @param rootNode The root [AccessibilityNodeInfo] to start parsing from. NOT recycled by the parser.
 * @param rootParentId Parent ID for the root node (used in nodeId generation, typically "root_w{windowId}").
 * @param nodeMap Optional output map. When non-null, real [AccessibilityNodeInfo] references are
 *   stored as [CachedNode] entries during traversal. Child nodes are NOT recycled in this mode —
 *   the caller accumulates references and populates [AccessibilityNodeCache] externally.
 *   When null, child nodes are recycled after data extraction (original behavior).
```

Also update `parseNode` function-level KDoc. The existing `@param recycleNode` states "Child nodes
are always recycled" — this is now stale. Replace with conditional description and add `@param nodeMap`:
```diff
- * @param recycleNode Whether to recycle [node] after parsing. Child nodes are always recycled.
+ * @param recycleNode Whether to recycle [node] after parsing. When [nodeMap] is null, child
+ *   nodes are recycled. When [nodeMap] is non-null, neither [node] nor its children are recycled.
+ * @param nodeMap Optional output map for caching real node references. When non-null, the node
+ *   is stored as a [CachedNode] and child nodes are not recycled. When null, nodes are recycled
+ *   after data extraction per the original behavior.
```

**Definition of Done**:
- [x] Class-level KDoc updated
- [x] `parseTree` function-level KDoc updated with `@param nodeMap`
- [x] `parseNode` function-level KDoc updated with `@param nodeMap`
- [x] Linting passes

### Task 2.4: Update `AccessibilityTreeParserTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParserTest.kt`

**Action**: Update tests for `nodeMap` parameter (no cache mock needed — parser constructor is unchanged):

1. **`setUp`**: Parser construction is unchanged — no mock cache needed:
   ```kotlin
   @BeforeEach
   fun setUp() {
       parser = AccessibilityTreeParser()
   }
   ```

2. **Update `recyclesChildNodesAfterParsing` test**: When `nodeMap` is NOT provided (null, the
   default), child nodes are STILL recycled. This test should continue to pass unchanged. Add a
   complementary test (test 4 below) for the caching path.

3. **Update `doesNotRecycleRootNode` test**: Unchanged — root is still not recycled by `parseTree`
   regardless of `nodeMap`. Continue verifying `recycle()` is NOT called on root.

4. **Add new test `parseTreeWithNodeMapDoesNotRecycleChildren`**:
   - Create a `MutableMap<String, CachedNode>()` and pass to `parseTree(rootNode, nodeMap = map)`
   - Parse a tree with root + 2 children
   - Verify `recycle()` was NOT called on any child node
   - Verify the map contains 3 `CachedNode` entries (root + 2 children)

5. **Add new test `parseTreePopulatesNodeMap`**:
   - Create a `MutableMap<String, CachedNode>()`
   - Parse a tree with root + 2 children, passing the map
   - Verify the map contains 3 entries
   - Verify each entry's key matches the expected nodeId
   - Verify each `CachedNode` has correct `node`, `depth`, `index`, and `parentId` metadata:
     - Root: depth=0, index=0, parentId="root" (the default `rootParentId` value)
     - Child 0: depth=1, index=0, parentId=rootNodeId
     - Child 1: depth=1, index=1, parentId=rootNodeId

6. **Add new test `parseTreeWithDifferentRootParentIdPopulatesNodeMap`**:
   - Parse with `rootParentId = "root_w42"` and a nodeMap
   - Verify nodeMap is populated with entries using window-scoped IDs

7. **Add new test `parseTreeNodeMapIncludesRootNode`** (from QA finding Q2):
   - Parse a single root node (no children) with a nodeMap
   - Verify the map contains exactly 1 entry with the root node's ID as key
   - Verify the `CachedNode.node` is the root `AccessibilityNodeInfo`
   - Verify `CachedNode.depth == 0`, `CachedNode.index == 0`, `CachedNode.parentId == "root"` (the default `rootParentId` value)

8. **Add new test `multipleParseTreeCallsAccumulateIntoSameNodeMap`** (from QA finding — verify accumulation):
   - Create a single `MutableMap<String, CachedNode>()`
   - Create two separate root nodes, each with 1 child (simulating 2 windows)
   - Call `parser.parseTree(root1, "root_w0", nodeMap)` then `parser.parseTree(root2, "root_w1", nodeMap)`
   - Verify the map contains **4** entries (2 roots + 2 children from both calls)
   - Verify entries from both calls coexist in the same map (entries have distinct nodeId keys, with CachedNode metadata containing `parentId` values rooted in `"root_w0"` and `"root_w1"` respectively)
   - NodeId collisions across windows are impossible because each `parseTree` call receives a
     different `rootParentId` (`"root_w0"` vs `"root_w1"`), and `generateNodeId` incorporates the
     `parentId` chain — so even structurally identical windows produce unique nodeIds.
   - This validates the accumulation pattern used by `getFreshWindows` without needing to test `getFreshWindows` directly

9. ~~**`parseTreeWithoutNodeMapRecyclesChildren`**~~ — **REMOVED**: This test is redundant with
   existing test `recyclesChildNodesAfterParsing` (test 2 above). Both verify that children are
   recycled when `nodeMap` is null (the default). The existing test already covers this behavior.
   No new test needed.

**Definition of Done**:
- [x] All existing tests continue to pass (parser constructor unchanged, no mock needed)
- [x] New nodeMap-related tests added and passing (including QA finding Q2 and accumulation test)
- [x] Linting passes

---

## User Story 3: Modify `getFreshWindows` and Callers to Accumulate and Populate Cache

**Goal**: `getFreshWindows` currently calls `parseTree` once per window in a loop. With the
`nodeMap` parameter from US2, `getFreshWindows` accumulates nodes from ALL windows into a single
map, then calls `nodeCache.populate()` ONCE after the loop completes. This ensures the cache
contains nodes from all windows (Critical Finding 1). Root nodes are no longer recycled by
`getFreshWindows` when caching is active — they are stored in the cache (Critical Finding 2).

All tool classes that call `getFreshWindows` must pass the `nodeCache` parameter. This cascades
through tool constructors → `register*Tools` functions → `McpServerService`.

**Acceptance Criteria / Definition of Done**:
- [x] `getFreshWindows` accepts a `nodeCache: AccessibilityNodeCache` parameter
- [x] `getFreshWindows` creates a `MutableMap<String, CachedNode>()` and passes it to each `parseTree` call
- [x] `getFreshWindows` calls `nodeCache.populate(accumulatedMap)` ONCE after the window loop
- [x] Root nodes are NOT recycled by `getFreshWindows` (they are stored in the cache)
- [x] The fallback single-window path also accumulates and populates the cache
- [x] All 12 tool classes that call `getFreshWindows` have `nodeCache` in their constructor
- [x] All 4 affected `register*Tools` functions accept and pass `nodeCache`
- [x] `McpServerService` injects `AccessibilityNodeCache` and passes it to register functions
- [x] `McpIntegrationTestHelper` passes a mock `nodeCache` to register functions
- [x] All 5 unit test files updated: tool constructors gain `mockNodeCache`, `parseTree` mocks updated to 3-arg, `rootNode.recycle()` mocks removed
- [x] All 3 integration test files updated: local `parseTree` mocks updated to 3-arg, `rootNode.recycle()` mocks removed
- [x] `ScreenIntrospectionToolsTest` test `recyclesRootNodeAndWindowInfoAfterParsing` updated — root nodes no longer recycled, verify `nodeCache.populate()` instead
- [x] Linting passes on all changed files

### Task 3.1: Add `nodeCache` parameter to `getFreshWindows` and accumulate across windows

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Action**: Modify `getFreshWindows` to accept `nodeCache`, accumulate a `nodeMap` across all
`parseTree` calls, and populate the cache once after the loop. Stop recycling root nodes.

Also update the `getFreshWindows` function-level KDoc — the existing "**Performance note**" states
"No caching is used because the accessibility tree can change between calls". Replace with:
```
 * **Performance note**: This function re-parses ALL window trees on every invocation to ensure
 * correctness — stale tree data would cause actions to target the wrong nodes. During parsing,
 * real [AccessibilityNodeInfo] references are accumulated in a [MutableMap] and stored in
 * [nodeCache] via [AccessibilityNodeCache.populate] for O(1) node resolution in
 * [ActionExecutorImpl.performNodeAction]. The cache is replaced atomically on each call.
```

**Imports required** (add at the top of `ElementActionTools.kt`):
```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.CachedNode
```

```diff
 @Suppress("LongMethod", "NestedBlockDepth", "ThrowsCount")
 internal fun getFreshWindows(
     treeParser: AccessibilityTreeParser,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    nodeCache: AccessibilityNodeCache,
 ): MultiWindowResult {
     if (!accessibilityServiceProvider.isReady()) {
         throw McpToolException.PermissionDenied(
             "Accessibility service not enabled or not ready. " +
                 "Please enable it in Android Settings > Accessibility.",
         )
     }

+    // Accumulate real AccessibilityNodeInfo references from ALL windows for cache population.
+    // This map is passed to each parseTree call and populated during tree traversal.
+    // We populate the cache ONCE after all windows are parsed to avoid the multi-window
+    // cache overwrite problem (Critical Finding 1).
+    val accumulatedNodeMap = mutableMapOf<String, CachedNode>()
+
     val accessibilityWindows = accessibilityServiceProvider.getAccessibilityWindows()

     if (accessibilityWindows.isNotEmpty()) {
         try {
             val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
             val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()
             val windowDataList = mutableListOf<WindowData>()

             for (window in accessibilityWindows) {
                 val rootNode = window.root ?: continue

                 // Extract metadata BEFORE parsing
                 val wId = window.id
                 val windowPackage = rootNode.packageName?.toString()
                 val windowTitle = window.title?.toString()
                 val windowType = window.type
                 val windowLayer = window.layer
                 val windowFocused = window.isFocused

-                val tree =
-                    try {
-                        treeParser.parseTree(rootNode, "root_w$wId")
-                    } finally {
-                        @Suppress("DEPRECATION")
-                        rootNode.recycle()
-                    }
+                // Root nodes are NOT recycled — they are stored in accumulatedNodeMap
+                // and owned by the cache. On API 33+ recycle() is a no-op, but we
+                // avoid calling it on cached nodes for semantic clarity (Critical Finding 2).
+                val tree = treeParser.parseTree(rootNode, "root_w$wId", accumulatedNodeMap)

                 // ... (windowDataList.add unchanged) ...
             }

             if (windowDataList.isEmpty()) {
                 throw McpToolException.ActionFailed("All windows returned null root nodes.")
             }

+            // Populate cache with ALL windows' nodes in a single atomic swap
+            nodeCache.populate(accumulatedNodeMap)
+
             return MultiWindowResult(windows = windowDataList, degraded = false)
         } finally {
             // Recycle all AccessibilityWindowInfo objects for consistency
             for (w in accessibilityWindows) {
                 @Suppress("DEPRECATION")
                 w.recycle()
             }
         }
     }

     // Fallback to single-window mode
     val rootNode =
         accessibilityServiceProvider.getRootNode()
             ?: throw McpToolException.ActionFailed(
                 "No windows available and no active window root node. " +
                     "The screen may be transitioning.",
             )

     val fallbackWindowId = rootNode.windowId
     val windowInfo = rootNode.window
     val fallbackWindowType =
         if (windowInfo != null) {
             val type = AccessibilityTreeParser.mapWindowType(windowInfo.type)
             @Suppress("DEPRECATION")
             windowInfo.recycle()
             type
         } else {
             "APPLICATION"
         }

-    val tree =
-        try {
-            treeParser.parseTree(rootNode, "root_w$fallbackWindowId")
-        } finally {
-            @Suppress("DEPRECATION")
-            rootNode.recycle()
-        }
+    // Fallback path also accumulates into the same map and does NOT recycle root node
+    val tree = treeParser.parseTree(rootNode, "root_w$fallbackWindowId", accumulatedNodeMap)
+
+    // Populate cache with fallback window's nodes
+    nodeCache.populate(accumulatedNodeMap)

     val currentPackageName = accessibilityServiceProvider.getCurrentPackageName()
     val currentActivityName = accessibilityServiceProvider.getCurrentActivityName()

     return MultiWindowResult(
         // ... unchanged ...
     )
 }
```

**Definition of Done**:
- [x] `nodeCache` parameter added to `getFreshWindows`
- [x] `accumulatedNodeMap` created at the start, passed to every `parseTree` call
- [x] `nodeCache.populate(accumulatedNodeMap)` called ONCE after multi-window loop
- [x] Root nodes NOT recycled in multi-window path (stored in cache)
- [x] Root node NOT recycled in fallback single-window path (stored in cache)
- [x] `nodeCache.populate()` also called in fallback path
- [x] `getFreshWindows` function-level KDoc updated — "No caching is used" replaced with cache population description
- [x] Linting passes

### Task 3.2: Add `nodeCache` to tool classes in `ElementActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Action**: Add `nodeCache: AccessibilityNodeCache` to the constructor of all 4 tool classes
that call `getFreshWindows`. Update each call site to pass `nodeCache`.

Tool classes to update:
1. `FindElementsTool` — add `private val nodeCache: AccessibilityNodeCache` to constructor
2. `ClickElementTool` — add `private val nodeCache: AccessibilityNodeCache` to constructor
3. `LongClickElementTool` — add `private val nodeCache: AccessibilityNodeCache` to constructor
4. `ScrollToElementTool` — add `private val nodeCache: AccessibilityNodeCache` to constructor

Example diff for `FindElementsTool`:
```diff
 class FindElementsTool
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val elementFinder: ElementFinder,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
+        private val nodeCache: AccessibilityNodeCache,
     ) {
         // ...
-            val result = getFreshWindows(treeParser, accessibilityServiceProvider)
+            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
```

Apply same pattern to `ClickElementTool`, `LongClickElementTool`, `ScrollToElementTool`.

> **IMPORTANT**: `ScrollToElementTool` has **2** `getFreshWindows` call sites: (1) the initial
> tree fetch at the top of `execute()` (line ~300), and (2) the re-parse inside the scroll retry
> loop (line ~351). Both MUST be updated to pass `nodeCache`.

Update `registerElementActionTools` to accept and pass `nodeCache`:
```diff
 fun registerElementActionTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     elementFinder: ElementFinder,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    nodeCache: AccessibilityNodeCache,
     toolNamePrefix: String,
 ) {
-    FindElementsTool(treeParser, elementFinder, accessibilityServiceProvider).register(server, toolNamePrefix)
-    ClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
-    LongClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
-    ScrollToElementTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)
+    FindElementsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache).register(server, toolNamePrefix)
+    ClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache).register(server, toolNamePrefix)
+    LongClickElementTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache).register(server, toolNamePrefix)
+    ScrollToElementTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, nodeCache)
         .register(server, toolNamePrefix)
 }
```

**Definition of Done**:
- [x] All 4 tool classes have `nodeCache` in constructor
- [x] All `getFreshWindows` call sites pass `nodeCache`
- [x] `registerElementActionTools` accepts and passes `nodeCache`
- [x] Linting passes

### Task 3.3: Add `nodeCache` to tool classes in `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Action**: Add `nodeCache: AccessibilityNodeCache` to `GetScreenStateHandler` constructor
and update its `getFreshWindows` call. Update `registerScreenIntrospectionTools`.

**Import required** (add at the top of `ScreenIntrospectionTools.kt`, after existing accessibility imports):
```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
```

```diff
 class GetScreenStateHandler
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
         private val screenCaptureProvider: ScreenCaptureProvider,
         private val compactTreeFormatter: CompactTreeFormatter,
         private val screenshotAnnotator: ScreenshotAnnotator,
         private val screenshotEncoder: ScreenshotEncoder,
+        private val nodeCache: AccessibilityNodeCache,
     ) {
         // ...
-            val result = getFreshWindows(treeParser, accessibilityServiceProvider)
+            val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
```

Update `registerScreenIntrospectionTools`:
```diff
 fun registerScreenIntrospectionTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     accessibilityServiceProvider: AccessibilityServiceProvider,
     screenCaptureProvider: ScreenCaptureProvider,
     compactTreeFormatter: CompactTreeFormatter,
     screenshotAnnotator: ScreenshotAnnotator,
     screenshotEncoder: ScreenshotEncoder,
+    nodeCache: AccessibilityNodeCache,
     toolNamePrefix: String,
 ) {
     GetScreenStateHandler(
         treeParser,
         accessibilityServiceProvider,
         screenCaptureProvider,
         compactTreeFormatter,
         screenshotAnnotator,
         screenshotEncoder,
+        nodeCache,
     ).register(server, toolNamePrefix)
 }
```

**Definition of Done**:
- [x] `GetScreenStateHandler` has `nodeCache` in constructor, passes to `getFreshWindows`
- [x] `registerScreenIntrospectionTools` accepts and passes `nodeCache`
- [x] Linting passes

### Task 3.4: Add `nodeCache` to tool classes in `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

**Import required** (add at the top of `TextInputTools.kt`, after existing accessibility imports):
```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
```

**Action**: Add `nodeCache: AccessibilityNodeCache` to the constructor of all 4 tool classes
that call `getFreshWindows`. Update each call site to pass `nodeCache`. `PressKeyTool` does NOT
call `getFreshWindows` and is NOT modified.

Tool classes to update:
1. `TypeAppendTextTool` — add `nodeCache`, update `getFreshWindows` call
2. `TypeInsertTextTool` — add `nodeCache`, update `getFreshWindows` call
3. `TypeReplaceTextTool` — add `nodeCache`, update `getFreshWindows` call
4. `TypeClearTextTool` — add `nodeCache`, update `getFreshWindows` call

Example diff for `TypeAppendTextTool`:
```diff
 class TypeAppendTextTool
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val actionExecutor: ActionExecutor,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
         private val typeInputController: TypeInputController,
+        private val nodeCache: AccessibilityNodeCache,
     ) {
         // ...
-                    val result = getFreshWindows(treeParser, accessibilityServiceProvider)
+                    val result = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
```

Apply same pattern to `TypeInsertTextTool`, `TypeReplaceTextTool`, `TypeClearTextTool`.

Update `registerTextInputTools`. The actual source has each constructor call on a single line
followed by `.register(server, toolNamePrefix)` on the next line. Adding `nodeCache` will cause
ktlint to require multi-line wrapping:
```diff
 fun registerTextInputTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
     typeInputController: TypeInputController,
+    nodeCache: AccessibilityNodeCache,
     toolNamePrefix: String,
 ) {
-    TypeAppendTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+    TypeAppendTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, nodeCache)
         .register(server, toolNamePrefix)
-    TypeInsertTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+    TypeInsertTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, nodeCache)
         .register(server, toolNamePrefix)
-    TypeReplaceTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+    TypeReplaceTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, nodeCache)
         .register(server, toolNamePrefix)
-    TypeClearTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+    TypeClearTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, nodeCache)
         .register(server, toolNamePrefix)
     PressKeyTool(actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix) // unchanged
 }
```

**Definition of Done**:
- [x] All 4 Type* tool classes have `nodeCache` in constructor, pass to `getFreshWindows`
- [x] `PressKeyTool` is NOT modified (does not call `getFreshWindows`)
- [x] `registerTextInputTools` accepts and passes `nodeCache`
- [x] Linting passes

### Task 3.5: Add `nodeCache` to tool classes in `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Import required** (add at the top of `UtilityTools.kt`, after existing accessibility imports):
```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
```

**Action**: Add `nodeCache: AccessibilityNodeCache` to the constructor of all 3 tool classes
that call `getFreshWindows`. `GetClipboardTool` and `SetClipboardTool` do NOT call `getFreshWindows`
and are NOT modified.

Tool classes to update:
1. `WaitForElementTool` — add `nodeCache`, update `getFreshWindows` call
2. `WaitForIdleTool` — add `nodeCache`, update `getFreshWindows` call
3. `GetElementDetailsTool` — add `nodeCache`, update `getFreshWindows` call

Example diff for `WaitForElementTool`:
```diff
 class WaitForElementTool
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val elementFinder: ElementFinder,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
+        private val nodeCache: AccessibilityNodeCache,
     ) {
         // ...
-                    val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider)
+                    val multiWindowResult = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache)
```

Apply same pattern to `WaitForIdleTool` and `GetElementDetailsTool`.

Update `registerUtilityTools` (note: the current source has all constructor calls on a single
line; the new `nodeCache` parameter may cause ktlint to require line wrapping — follow ktlint's
auto-format output). The function gains a 6th parameter, which exceeds detekt's default
`LongParameterList` threshold — add the suppression annotation:
```diff
+@Suppress("LongParameterList")
 fun registerUtilityTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     elementFinder: ElementFinder,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    nodeCache: AccessibilityNodeCache,
     toolNamePrefix: String,
 ) {
     GetClipboardTool(accessibilityServiceProvider).register(server, toolNamePrefix) // unchanged
     SetClipboardTool(accessibilityServiceProvider).register(server, toolNamePrefix) // unchanged
-    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider).register(server, toolNamePrefix)
-    WaitForIdleTool(treeParser, accessibilityServiceProvider).register(server, toolNamePrefix)
-    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider).register(server, toolNamePrefix)
+    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)
+        .register(server, toolNamePrefix)
+    WaitForIdleTool(treeParser, accessibilityServiceProvider, nodeCache)
+        .register(server, toolNamePrefix)
+    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)
+        .register(server, toolNamePrefix)
 }
```

**Definition of Done**:
- [x] All 3 utility tool classes that call `getFreshWindows` have `nodeCache` in constructor
- [x] `GetClipboardTool` and `SetClipboardTool` are NOT modified
- [x] `registerUtilityTools` accepts and passes `nodeCache`
- [x] `registerUtilityTools` has `@Suppress("LongParameterList")` annotation (6 parameters exceeds detekt threshold)
- [x] Linting passes

### Task 3.6: Inject `AccessibilityNodeCache` in `McpServerService` and pass to register functions

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

**Action**: Add `@Inject lateinit var nodeCache: AccessibilityNodeCache` and add the import.
Pass `nodeCache` to all 4 affected register function calls.

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
+
 // ... existing @Inject fields ...
 @Inject lateinit var typeInputController: TypeInputController
+@Inject lateinit var nodeCache: AccessibilityNodeCache

 // ... in registerTools function ...

     registerScreenIntrospectionTools(
         server,
         treeParser,
         accessibilityServiceProvider,
         screenCaptureProvider,
         compactTreeFormatter,
         screenshotAnnotator,
         screenshotEncoder,
+        nodeCache,
         toolNamePrefix,
     )

     registerElementActionTools(
         server,
         treeParser,
         elementFinder,
         actionExecutor,
         accessibilityServiceProvider,
+        nodeCache,
         toolNamePrefix,
     )

     registerTextInputTools(
         server,
         treeParser,
         actionExecutor,
         accessibilityServiceProvider,
         typeInputController,
+        nodeCache,
         toolNamePrefix,
     )

-    registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider, toolNamePrefix)
+    registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider, nodeCache, toolNamePrefix)
```

**Definition of Done**:
- [x] `AccessibilityNodeCache` injected via `@Inject lateinit var`
- [x] Import added
- [x] `nodeCache` passed to `registerElementActionTools`, `registerScreenIntrospectionTools`, `registerTextInputTools`, `registerUtilityTools`
- [x] Linting passes

### Task 3.7: Update `McpIntegrationTestHelper` to pass mock `nodeCache`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

**Action**: Five changes are required:

**1. Add import** (at the top of the file, after existing accessibility imports):
```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
```

**2. Add `nodeCache` field to `MockDependencies` data class** (line 314-327):
```diff
 data class MockDependencies(
     val actionExecutor: ActionExecutor,
     val accessibilityServiceProvider: AccessibilityServiceProvider,
     val screenCaptureProvider: ScreenCaptureProvider,
     val treeParser: AccessibilityTreeParser,
     val elementFinder: ElementFinder,
     val storageLocationProvider: StorageLocationProvider,
     val fileOperationProvider: FileOperationProvider,
     val appManager: AppManager,
     val typeInputController: TypeInputController,
     val screenshotAnnotator: ScreenshotAnnotator,
     val screenshotEncoder: ScreenshotEncoder,
     val cameraProvider: CameraProvider,
+    val nodeCache: AccessibilityNodeCache,
 )
```

**3. Create mock in `createMockDependencies()`** (line 109-123):
```diff
     fun createMockDependencies(): MockDependencies =
         MockDependencies(
             actionExecutor = mockk(relaxed = true),
             accessibilityServiceProvider = mockk(relaxed = true),
             screenCaptureProvider = mockk(relaxed = true),
             treeParser = mockk(relaxed = true),
             elementFinder = mockk(relaxed = true),
             storageLocationProvider = mockk(relaxed = true),
             fileOperationProvider = mockk(relaxed = true),
             appManager = mockk(relaxed = true),
             typeInputController = mockk(relaxed = true),
             screenshotAnnotator = mockk(relaxed = true),
             screenshotEncoder = mockk(relaxed = true),
             cameraProvider = mockk(relaxed = true),
+            nodeCache = mockk(relaxed = true),
         )
```

**4. Update `setupMultiWindowMock` parseTree mock** (line 101) — CRITICAL: after US2,
`parseTree` gains a 3rd `nodeMap` parameter. `getFreshWindows` calls `parseTree(rootNode,
"root_w$wId", accumulatedNodeMap)` with a non-null map. The existing 2-arg mock won't match
the 3-arg call. Since `treeParser` is a relaxed mock, the unmatched call silently returns a
default empty `AccessibilityNodeData`, causing all integration tests using
`setupMultiWindowMock` to return wrong data.

Also remove the `rootNode.recycle()` mock — root nodes are no longer recycled when caching
is active.

```diff
-        @Suppress("DEPRECATION")
-        every { deps.treeParser.parseTree(mockRootNode, "root_w$windowId") } returns tree
-        @Suppress("DEPRECATION")
-        every { mockRootNode.recycle() } returns Unit
+        every { deps.treeParser.parseTree(mockRootNode, "root_w$windowId", any()) } returns tree
```

**5. Pass `deps.nodeCache` to all 4 affected register calls in `registerAllTools()`** (lines 128-177):
```diff
         registerScreenIntrospectionTools(
             server,
             deps.treeParser,
             deps.accessibilityServiceProvider,
             deps.screenCaptureProvider,
             CompactTreeFormatter(),
             deps.screenshotAnnotator,
             deps.screenshotEncoder,
+            deps.nodeCache,
             toolNamePrefix,
         )
         // ...
         registerElementActionTools(
             server,
             deps.treeParser,
             deps.elementFinder,
             deps.actionExecutor,
             deps.accessibilityServiceProvider,
+            deps.nodeCache,
             toolNamePrefix,
         )
         registerTextInputTools(
             server,
             deps.treeParser,
             deps.actionExecutor,
             deps.accessibilityServiceProvider,
             deps.typeInputController,
+            deps.nodeCache,
             toolNamePrefix,
         )
         registerUtilityTools(
             server,
             deps.treeParser,
             deps.elementFinder,
             deps.accessibilityServiceProvider,
+            deps.nodeCache,
             toolNamePrefix,
         )
```

**Definition of Done**:
- [x] Import added for `AccessibilityNodeCache`
- [x] `MockDependencies` data class has `nodeCache: AccessibilityNodeCache` field
- [x] `createMockDependencies()` creates `nodeCache` mock
- [x] `setupMultiWindowMock` uses 3-arg `parseTree` mock (`any()` for nodeMap)
- [x] `setupMultiWindowMock` no longer mocks `rootNode.recycle()` (roots not recycled when caching)
- [x] All 4 affected register calls pass `deps.nodeCache`
- [x] Integration tests continue to compile and pass
- [x] Linting passes

### Task 3.8: Update 8 test files for `parseTree` signature change, tool constructor changes, and `rootNode.recycle()` removal

After US2 adds the third `nodeMap` parameter to `parseTree`, and US3 adds `nodeCache` to tool
constructors and stops recycling root nodes in `getFreshWindows`, **8 additional test files** will
fail to compile or behave incorrectly. These files have their own local `parseTree` mocks (2-arg),
`rootNode.recycle()` mocks, and/or tool constructor calls that don't include `nodeCache`.

This task updates all 8 files. Changes follow a mechanical pattern:

#### A) Unit test files (5 files)

All 5 unit test files share the same change pattern:

1. **Add `mockNodeCache` field** — `private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)`
2. **Add import** — `import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache`
3. **Update `parseTree` mocks** — from 2-arg to 3-arg by adding `any()` for `nodeMap`
4. **Remove `rootNode.recycle()` mocks from setUp** (where they exist) — root nodes are no longer recycled by `getFreshWindows`. Applies to `ElementActionToolsTest`, `TextInputToolsTest`, `UtilityToolsTest`. Does NOT apply to `GetElementDetailsToolTest` or `ScreenIntrospectionToolsTest` (which have no `every { mockRootNode.recycle() }` setup — `ScreenIntrospectionToolsTest` has `verify` calls instead, handled separately below).

> **IMPORTANT — PressKeyTool exception (TextInputToolsTest only):** `PressKeyTool` does NOT use
> `getFreshWindows`. It uses `findFocusedEditableNode`, which obtains a root node via
> `window.root` or `getRootNode()` and recycles it directly. Removing the `rootNode.recycle()`
> mock from `setUp()` is correct for the Type* tools, but the PressKeyTool tests that exercise
> `findFocusedEditableNode` paths (`presses DEL key removes last character` and
> `presses SPACE key appends space`) MUST add local mocks:
> `every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode` and
> `@Suppress("DEPRECATION") every { mockRootNode.recycle() } returns Unit`
> within those specific test methods.
5. **Update tool constructor calls** — add `mockNodeCache` as the last argument before `)` or `,`

**File 1: `ElementActionToolsTest.kt`**

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 class ElementActionToolsTest {
     private val mockTreeParser = mockk<AccessibilityTreeParser>()
     private val mockElementFinder = mockk<ElementFinder>()
     private val mockActionExecutor = mockk<ActionExecutor>()
     private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
+    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
     private val mockRootNode = mockk<AccessibilityNodeInfo>()
     private val mockWindowInfo = mockk<AccessibilityWindowInfo>()

     // ... in setUp():
-    every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
-    every { mockRootNode.recycle() } returns Unit
+    every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
```

Tool constructor updates (4 classes):
```diff
-    private val tool = FindElementsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)
+    private val tool = FindElementsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)

-    private val tool = ClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)
+    private val tool = ClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

-    private val tool = LongClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider)
+    private val tool = LongClickElementTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)

-        ScrollToElementTool(mockTreeParser, mockElementFinder, mockActionExecutor, mockAccessibilityServiceProvider)
+        ScrollToElementTool(mockTreeParser, mockElementFinder, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)
```

Also update the `scrollsToElementInNonPrimaryWindow` test's local `parseTree` mock:
```diff
-    every { mockTreeParser.parseTree(secondMockRootNode, "root_w5") } answers {
+    every { mockTreeParser.parseTree(secondMockRootNode, "root_w5", any()) } answers {
```

**File 2: `TextInputToolsTest.kt`**

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 class TextInputToolsTest {
     private val mockTreeParser = mockk<AccessibilityTreeParser>()
     private val mockActionExecutor = mockk<ActionExecutor>()
     private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
     private val mockTypeInputController = mockk<TypeInputController>()
+    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
     private val mockRootNode = mockk<AccessibilityNodeInfo>()

     // ... in setUp():
-    every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
-    every { mockRootNode.recycle() } returns Unit
+    every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
```

Tool constructor updates (4 classes):
```diff
-    TypeAppendTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController,)
+    TypeAppendTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController, mockNodeCache,)

-    TypeInsertTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController,)
+    TypeInsertTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController, mockNodeCache,)

-    TypeReplaceTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController,)
+    TypeReplaceTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController, mockNodeCache,)

-    TypeClearTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController,)
+    TypeClearTextTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockTypeInputController, mockNodeCache,)
```

**File 3: `ScreenIntrospectionToolsTest.kt`**

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 class ScreenIntrospectionToolsTest {
     // ... existing fields ...
+    private lateinit var mockNodeCache: AccessibilityNodeCache

     @BeforeEach
     fun setUp() {
         // ... existing mocks ...
+        mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
```

Update `parseTree` mock in `setupReadyService()`:
```diff
-    every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
+    every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
```

Update `GetScreenStateHandler` constructor:
```diff
-    GetScreenStateHandler(
-        mockTreeParser,
-        mockAccessibilityServiceProvider,
-        mockScreenCaptureProvider,
-        mockCompactTreeFormatter,
-        mockScreenshotAnnotator,
-        mockScreenshotEncoder,
-    )
+    GetScreenStateHandler(
+        mockTreeParser,
+        mockAccessibilityServiceProvider,
+        mockScreenCaptureProvider,
+        mockCompactTreeFormatter,
+        mockScreenshotAnnotator,
+        mockScreenshotEncoder,
+        mockNodeCache,
+    )
```

**CRITICAL**: Update test `recyclesRootNodeAndWindowInfoAfterParsing` — root nodes are NO LONGER
recycled by `getFreshWindows` when caching is active. Change from verifying `rootNode.recycle()` to
verifying it is NOT called and verifying `nodeCache.populate()` IS called:
```diff
     @Test
-    @DisplayName("recycles root node and window info after parsing")
-    fun recyclesRootNodeAndWindowInfoAfterParsing() =
+    @DisplayName("populates cache and recycles window info but not root node after parsing")
+    fun populatesCacheAndRecyclesWindowInfoButNotRootNodeAfterParsing() =
         runTest {
             setupReadyService()

             handler.execute(null)

-            @Suppress("DEPRECATION")
-            verify(exactly = 1) { mockRootNode.recycle() }
+            @Suppress("DEPRECATION")
+            verify(exactly = 0) { mockRootNode.recycle() }
+            verify(exactly = 1) { mockNodeCache.populate(any()) }
             @Suppress("DEPRECATION")
             verify(exactly = 1) { mockWindowInfo.recycle() }
         }
```

Update `getFreshWindowsFallbackPathReturnsDegradedOutput` test:
```diff
-    every { mockTreeParser.parseTree(fallbackRootNode, "root_w42") } returns sampleTree
+    every { mockTreeParser.parseTree(fallbackRootNode, "root_w42", any()) } returns sampleTree

-    @Suppress("DEPRECATION")
-    verify(exactly = 1) { fallbackRootNode.recycle() }
+    @Suppress("DEPRECATION")
+    verify(exactly = 0) { fallbackRootNode.recycle() }
+    verify(exactly = 1) { mockNodeCache.populate(any()) }
```

**File 4: `UtilityToolsTest.kt`**

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 class UtilityToolsTest {
     private val mockTreeParser = mockk<AccessibilityTreeParser>()
     private val mockElementFinder = mockk<ElementFinder>()
     private val mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()
+    private val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
     private val mockRootNode = mockk<AccessibilityNodeInfo>()

     // ... in setUp():
-    every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
-    every { mockRootNode.recycle() } returns Unit
+    every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
```

Tool constructor updates (2 classes):
```diff
-    private val tool = WaitForElementTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)
+    private val tool = WaitForElementTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)

-    private val tool = WaitForIdleTool(mockTreeParser, mockAccessibilityServiceProvider)
+    private val tool = WaitForIdleTool(mockTreeParser, mockAccessibilityServiceProvider, mockNodeCache)
```

Also update `WaitForIdleTool` tests that use `parseTree(any(), any())` pattern:
```diff
-    every { mockTreeParser.parseTree(any(), any()) } answers {
+    every { mockTreeParser.parseTree(any(), any(), any()) } answers {
```
This applies to 2 test methods: `detects idle with match_percentage below 100` and
`timeout response includes similarity field`.

**File 5: `GetElementDetailsToolTest.kt`**

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 class GetElementDetailsToolTest {
     // ... existing fields ...
+    private lateinit var mockNodeCache: AccessibilityNodeCache

     @BeforeEach
     fun setUp() {
         // ... existing mocks ...
+        mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)

-        every { mockTreeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
+        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree

-        tool = GetElementDetailsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)
+        tool = GetElementDetailsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider, mockNodeCache)
```

#### B) Integration test files (3 files)

These files have their OWN local `parseTree` mocks and `rootNode.recycle()` mocks that bypass
`McpIntegrationTestHelper`. They need the same 2-arg → 3-arg and recycle removal treatment.

**File 6: `ScreenIntrospectionIntegrationTest.kt`**

In the `MultiWindowIntegration` nested class's `setupTwoWindowMock()`:
```diff
-    every { treeParser.parseTree(mockRootApp, "root_w42") } returns appTree
-    every { treeParser.parseTree(mockRootDialog, "root_w99") } returns dialogTree
-
-    every { mockRootApp.recycle() } returns Unit
-    every { mockRootDialog.recycle() } returns Unit
+    every { treeParser.parseTree(mockRootApp, "root_w42", any()) } returns appTree
+    every { treeParser.parseTree(mockRootDialog, "root_w99", any()) } returns dialogTree
```

In the degraded mode test:
```diff
-    every { deps.treeParser.parseTree(mockRootNode, "root_w0") } returns sampleTree
-    // ...
-    every { mockRootNode.recycle() } returns Unit
+    every { deps.treeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
```

**File 7: `ElementActionIntegrationTest.kt`**

In the `MultiWindowIntegration` nested class's `setupTwoWindowMock()`:
```diff
-    every { treeParser.parseTree(mockRootApp, "root_w42") } returns sampleTree
-    every { treeParser.parseTree(mockRootDialog, "root_w99") } returns dialogTree
-
-    every { mockRootApp.recycle() } returns Unit
-    every { mockRootDialog.recycle() } returns Unit
+    every { treeParser.parseTree(mockRootApp, "root_w42", any()) } returns sampleTree
+    every { treeParser.parseTree(mockRootDialog, "root_w99", any()) } returns dialogTree
```

**File 8: `ErrorHandlingIntegrationTest.kt`**

In the `wait_for_idle` test:
```diff
-    @Suppress("DEPRECATION")
-    every { mockRootNode.recycle() } returns Unit
-
-    var callCount = 0
-    every { deps.treeParser.parseTree(any(), any()) } answers {
+    var callCount = 0
+    every { deps.treeParser.parseTree(any(), any(), any()) } answers {
```

**Definition of Done**:
- [x] All 5 unit test files: `mockNodeCache` field added, import added, constructors updated, `parseTree` mocks updated to 3-arg, `rootNode.recycle()` mocks removed
- [x] `ScreenIntrospectionToolsTest`: `recyclesRootNodeAndWindowInfoAfterParsing` test updated to verify root node NOT recycled and `nodeCache.populate()` called; fallback test updated similarly
- [x] All 3 integration test files: local `parseTree` mocks updated to 3-arg, `rootNode.recycle()` mocks removed
- [x] All tests compile and pass
- [x] Linting passes on all changed files

---

## User Story 4: Modify ActionExecutorImpl to Use Cache

**Goal**: Before falling back to `walkAndMatch`, check the cache for a hit and validate with
`refresh()`. If valid, use the cached node directly (O(1) IPC). Otherwise, fall back to
`walkAndMatch` (O(N) IPC, same as current behavior).

**Acceptance Criteria / Definition of Done**:
- [x] `ActionExecutorImpl` receives `AccessibilityNodeCache` and `AccessibilityTreeParser` via constructor injection
- [x] `performNodeAction` checks cache before walking the tree
- [x] Cache hit + `refresh()` returns `true` + identity verified (nodeId re-generated matches) → node used directly (no tree walk)
- [x] Cache hit + `refresh()` returns `true` + identity mismatch → fall back to `walkAndMatch` (S1 fix)
- [x] Cache miss or `refresh()` returns `false` → fall back to `walkAndMatch` (existing behavior)
- [x] Logging added for cache hit, cache miss, cache stale, and identity mismatch (explicit Log.d for each path)
- [x] Existing unit tests updated for cache injection
- [x] New tests verify cache hit path, fallback path, and identity mismatch path
- [x] Linting passes on all changed files

### Task 4.1: Inject `AccessibilityNodeCache` and `AccessibilityTreeParser` into `ActionExecutorImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt`

**Action**: Modify constructor to accept cache and tree parser (parser needed for `generateNodeId`
to re-verify node identity after `refresh()` — S1 fix).

> **Note**: The current constructor is `constructor() : ActionExecutor`. If another plan has
> already added parameters before this plan is implemented, the diff base will differ — merge
> accordingly (Minor Finding 9).

> **Note on `generateNodeId` visibility**: `generateNodeId` is declared `internal` in
> `AccessibilityTreeParser.kt`. Since both `AccessibilityTreeParser` and `ActionExecutorImpl`
> are in the same Gradle module (`app`), `internal` visibility is sufficient —
> `ActionExecutorImpl` can call `treeParser.generateNodeId()` without any visibility changes.

```diff
 class ActionExecutorImpl
     @Inject
-    constructor() : ActionExecutor {
+    constructor(
+        private val nodeCache: AccessibilityNodeCache,
+        private val treeParser: AccessibilityTreeParser,
+    ) : ActionExecutor {
```

Update class KDoc — replace the last two lines:
```diff
- * Accesses [McpAccessibilityService.instance] directly (singleton pattern) because the
- * accessibility service is system-managed and cannot be injected via Hilt.
- *
- * This class is Hilt-injectable and stateless.
+ * Accesses [McpAccessibilityService.instance] directly (singleton pattern) because the
+ * accessibility service is system-managed and cannot be injected via Hilt.
+ *
+ * Uses [AccessibilityNodeCache] for O(1) node resolution on cache hits (populated during
+ * tree parsing). Falls back to [walkAndMatch] tree traversal on cache miss/stale/identity
+ * mismatch. Uses [AccessibilityTreeParser.generateNodeId] to re-verify node identity after
+ * [AccessibilityNodeInfo.refresh].
+ *
+ * This class is Hilt-injectable.
```

**Definition of Done**:
- [x] Constructor parameters added (cache + tree parser)
- [x] KDoc updated with concrete replacement text above
- [x] Linting passes

### Task 4.2: Add cache lookup in `performNodeAction` (multi-window path)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt`

**Action**: At the **beginning** of `performNodeAction`, before the multi-window tree walk, add a cache
lookup. If the cached node is still valid via `refresh()` AND its identity matches (re-generated
nodeId equals the requested nodeId), use it directly.

The identity check (S1 fix) prevents acting on the wrong element when the UI has changed but the
View object was reused (e.g., RecyclerView). After `refresh()`, re-generate the nodeId using
`treeParser.generateNodeId()` with the refreshed node properties (bounds from `getBoundsInScreen()`)
and the cached metadata (`depth`, `index`, `parentId` from `CachedNode` — defined in Task 1.1).
If the re-generated nodeId differs from the requested one, the node has changed identity → treat as
cache miss and fall back to `walkAndMatch`.

**Import required** (add at the top of the file):
```diff
+import android.graphics.Rect
```

> **Note**: `Rect` must be imported directly (not used fully-qualified as `android.graphics.Rect()`)
> to match the codebase style. `AccessibilityTreeParser.kt` already imports `android.graphics.Rect`
> at the top of the file (Major Finding 3).

Cache lookup diff in `performNodeAction`:

```diff
     private suspend fun performNodeAction(
         nodeId: String,
         windows: List<WindowData>,
         actionName: String,
         action: (AccessibilityNodeInfo) -> Result<Unit>,
     ): Result<Unit> {
         val service =
             McpAccessibilityService.instance
                 ?: return Result.failure(
                     IllegalStateException("Accessibility service is not available"),
                 )

+        // Fast path: check cache for a direct hit
+        val cached = nodeCache.get(nodeId)
+        if (cached != null) {
+            if (cached.node.refresh()) {
+                // S1 fix: re-verify node identity after refresh.
+                // refresh() updates the node's properties from the live UI.
+                // If the View was reused for a different element (e.g., RecyclerView),
+                // the regenerated nodeId will differ → treat as cache miss.
+                // NOTE: getBoundsInScreen() reads the locally cached bounds field
+                // (already updated by refresh()) — this is NOT an additional IPC call.
+                val rect = Rect()
+                cached.node.getBoundsInScreen(rect)
+                val refreshedBounds = BoundsData(rect.left, rect.top, rect.right, rect.bottom)
+                val refreshedId = treeParser.generateNodeId(
+                    cached.node, refreshedBounds, cached.depth, cached.index, cached.parentId,
+                )
+                if (refreshedId == nodeId) {
+                    Log.d(TAG, "Cache hit for node '$nodeId', identity verified")
+                    val result = action(cached.node)
+                    if (result.isSuccess) {
+                        Log.d(TAG, "Node action '$actionName' succeeded (cached) on '$nodeId'")
+                    } else {
+                        Log.w(
+                            TAG,
+                            "Node action '$actionName' failed (cached) on '$nodeId': " +
+                                "${result.exceptionOrNull()?.message}",
+                        )
+                    }
+                    // Do NOT recycle cached nodes — they are owned by the cache.
+                    // No try/finally needed since there is no cleanup to perform.
+                    return result
+                } else {
+                    Log.d(
+                        TAG,
+                        "Cache identity mismatch for '$nodeId' (refreshed='$refreshedId'), " +
+                            "falling back to tree walk",
+                    )
+                }
+            } else {
+                Log.d(TAG, "Cache stale for node '$nodeId', falling back to tree walk")
+            }
+        } else {
+            Log.d(TAG, "Cache miss for node '$nodeId', falling back to tree walk")
+        }
+
         val realWindows = service.getAccessibilityWindows()
         // ... rest of existing multi-window and degraded-mode code unchanged ...
```

> **Note on empty finally block removal (Major Finding 4)**: The previous version used
> `try { ... } finally { /* comment only */ }` which would trigger a detekt lint error
> (`EmptyFinallyBlock`). The fix eliminates the try/finally entirely and places the
> "do not recycle" comment after the action execution, before `return result`.

**Critical**: The cached node is NOT recycled after use — it's owned by the cache. Only the cache
recycles nodes (during `clear`).

**Definition of Done**:
- [x] Cache lookup added at the start of `performNodeAction`
- [x] `import android.graphics.Rect` added at top of file (Major Finding 3)
- [x] No empty finally block — code uses plain sequential flow (Major Finding 4)
- [x] Cache hit + `refresh()` true + identity verified → action executed, node NOT recycled
- [x] Cache hit + `refresh()` true + identity mismatch → falls through to walkAndMatch (S1 fix)
- [x] Cache stale (`refresh()` false) → falls through to existing walkAndMatch logic
- [x] Cache miss (null) → falls through to existing walkAndMatch logic
- [x] Logging for cache hit, cache miss, cache stale, and identity mismatch (explicit Log.d for each path)
- [x] Linting passes

### Task 4.3: Update `ActionExecutorImplTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImplTest.kt`

**Action**: Update tests for cache injection and add cache-specific tests:

**Imports required** in `ActionExecutorImplTest.kt`:
```diff
+import android.graphics.Rect
+import io.mockk.slot
```

> **NOTE**: Do NOT import `AccessibilityNodeCache`, `AccessibilityTreeParser`, or `CachedNode` —
> `ActionExecutorImplTest` is in the `services.accessibility` package (same package as these classes).
> ktlint's `no-unused-imports` rule flags same-package imports as unnecessary.

1. **`setUp`**: Create mocks for `AccessibilityNodeCache` and `AccessibilityTreeParser`, pass to
   constructor:
   ```kotlin
   private lateinit var mockCache: AccessibilityNodeCache
   private lateinit var mockTreeParser: AccessibilityTreeParser

   @BeforeEach
   fun setUp() {
       mockCache = mockk<AccessibilityNodeCache>(relaxed = true)
       mockTreeParser = mockk<AccessibilityTreeParser>(relaxed = true)
       executor = ActionExecutorImpl(mockCache, mockTreeParser)
       mockService = mockk<McpAccessibilityService>(relaxed = true)
   }
   ```

2. **Default mock behavior**: `mockCache.get(any())` returns `null` by default (relaxed mock).
   Existing tests should pass unchanged since cache misses fall through to existing behavior.
   Also mock `treeParser.generateNodeId(...)` to return the expected nodeId by default for cache
   hit tests (identity check passes).

3. **Helper for cache-hit tests** (shared setup for tests a, d, f, g, h):
   All cache-hit tests that reach the identity check MUST mock `getBoundsInScreen` on the cached
   node. The `performNodeAction` code calls `cached.node.getBoundsInScreen(rect)` after `refresh()`
   to re-generate the nodeId. Without this mock, the `Rect` stays at (0,0,0,0) and the regenerated
   nodeId may not match expectations.
   ```kotlin
   // Helper function used by cache-hit tests:
   private fun mockCacheHitWithIdentity(
       cachedNode: CachedNode,
       expectedNodeId: String,
       refreshResult: Boolean = true,
   ) {
       every { mockCache.get(expectedNodeId) } returns cachedNode
       every { cachedNode.node.refresh() } returns refreshResult
       if (refreshResult) {
           // Mock getBoundsInScreen to set known bounds (Minor Finding 6).
           // Uses slot + field assignment pattern (not Rect.set()) because
           // build.gradle.kts configures unitTests.isReturnDefaultValues = true,
           // which makes Rect.set() a no-op. This matches the existing codebase
           // pattern used in AccessibilityTreeParserTest.createMockNode() (lines 65-71).
           val rectSlot = slot<Rect>()
           every { cachedNode.node.getBoundsInScreen(capture(rectSlot)) } answers {
               rectSlot.captured.left = 0
               rectSlot.captured.top = 0
               rectSlot.captured.right = 100
               rectSlot.captured.bottom = 100
           }
           every {
               mockTreeParser.generateNodeId(
                   cachedNode.node, any(), cachedNode.depth, cachedNode.index, cachedNode.parentId,
               )
           } returns expectedNodeId
       }
   }
   ```

4. **Add new `CacheHit` nested class** with tests:

   > **Note on `windows` parameter**: Tests (a), (d), (f), (g), (h) exercise the cache hit path,
   > which returns before accessing `windows`. The `windows` parameter is still required by the
   > method signature. Use any valid `List<WindowData>` — e.g., `emptyList()` or a single-entry
   > list from the existing `wrapInWindows()` test helper. The value does not affect the test
   > outcome since the cache path short-circuits before the window walk.

   a. **`clickNodeUsesValidCachedNode`**:
      - Set up service instance
      - Create a mock `AccessibilityNodeInfo` for cache, wrap in `CachedNode(node, depth=1, index=0, parentId="root")`
      - Call `mockCacheHitWithIdentity(cachedNode, "node_target")` (sets up cache get, refresh, getBoundsInScreen, generateNodeId)
      - `every { cachedNode.node.isClickable } returns true`
      - `every { cachedNode.node.performAction(ACTION_CLICK) } returns true`
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isSuccess`
      - Verify `mockService.getAccessibilityWindows()` was NOT called (fast path, no tree walk)

   b. **`clickNodeFallsBackWhenCacheStale`**:
      - Set up service instance, mock windows, mock root nodes
      - `every { mockCache.get("node_target") } returns cachedNode`
      - `every { cachedNode.node.refresh() } returns false` (stale — no `getBoundsInScreen` mock needed since code short-circuits before identity check)
      - Set up walkAndMatch to succeed via mocked windows
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isSuccess`
      - Verify `mockService.getAccessibilityWindows()` WAS called (fallback path)

   c. **`clickNodeFallsBackWhenCacheMiss`**:
      - Set up service instance, mock windows, mock root nodes
      - `every { mockCache.get("node_nonexistent") } returns null`
      - Set up walkAndMatch to fail (node not found)
      - Call `executor.clickNode("node_nonexistent", windows)`
      - Verify `result.isFailure`
      - Verify `mockService.getAccessibilityWindows()` WAS called

   d. **`cachedNodeNotRecycledAfterUse`**:
      - Set up service instance
      - Create a mock `AccessibilityNodeInfo`, wrap in `CachedNode(node, depth=1, index=0, parentId="root")`
      - Call `mockCacheHitWithIdentity(cachedNode, "node_target")` (sets up cache get, refresh, getBoundsInScreen, generateNodeId)
      - `every { cachedNode.node.isClickable } returns true`
      - `every { cachedNode.node.performAction(ACTION_CLICK) } returns true`
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isSuccess`
      - Verify `cachedNode.node.recycle()` was NOT called (node owned by cache, not by action executor)

   e. **`clickNodeFailsWhenServiceUnavailableEvenWithCache`** (from QA finding Q1):
      - Set service instance to null
      - Set up `mockCache.get("node_target")` to return a valid `CachedNode`
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isFailure` with "not available" message
      - Verify `mockCache.get()` was NOT called (service check is first)

   f. **`scrollNodeUsesValidCachedNode`** (from QA finding Q4):
      - Same pattern as test (a) but for `scrollNode`
      - Uses `mockCacheHitWithIdentity` (includes `getBoundsInScreen` mock)
      - Verify cache hit path works for non-click node actions

   g. **`clickNodeActionFailsViaCachedNode`** (from QA finding — action failure on cache path):
      - Set up service instance
      - Create a mock `AccessibilityNodeInfo`, wrap in `CachedNode(node, depth=1, index=0, parentId="root")`
      - Call `mockCacheHitWithIdentity(cachedNode, "node_target")` (sets up cache get, refresh, getBoundsInScreen, generateNodeId)
      - `every { cachedNode.node.isClickable } returns true`
      - `every { cachedNode.node.performAction(ACTION_CLICK) } returns false` (action fails)
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isFailure` with appropriate error message
      - Verify `mockService.getAccessibilityWindows()` was NOT called (stayed on cache path, did NOT fall back)

   h. **`clickNodeFallsBackWhenIdentityMismatch`** (from S1 fix):
      - Set up service instance, mock windows, mock root nodes
      - Call `mockCacheHitWithIdentity(cachedNode, "node_target")` (sets up cache get, refresh,
        `getBoundsInScreen` via slot+field assignment, and `generateNodeId` → `"node_target"`)
      - **Override** `generateNodeId` to return `"node_DIFFERENT"` instead of `"node_target"`:
        ```kotlin
        every {
            mockTreeParser.generateNodeId(
                cachedNode.node, any(), cachedNode.depth, cachedNode.index, cachedNode.parentId,
            )
        } returns "node_DIFFERENT"
        ```
        This simulates an identity mismatch — the View was reused for a different element
        (e.g., RecyclerView recycling). The helper's `getBoundsInScreen` mock (slot+field
        assignment pattern) is still applied, ensuring bounds are read correctly before the
        regenerated nodeId comparison fails.
      - Set up walkAndMatch to succeed via mocked windows
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isSuccess` (via fallback walkAndMatch)
      - Verify `mockService.getAccessibilityWindows()` WAS called (fell back to tree walk)

**Definition of Done**:
- [x] All existing tests updated and passing (constructor now takes `mockCache` + `mockTreeParser`)
- [x] New cache hit/miss/stale tests added and passing
- [x] All cache-hit tests (a, d, f, g, h) mock `getBoundsInScreen` on cached node via slot + field assignment (Minor Finding 6, M4 fix)
- [x] Action failure on cached node path test added and passing (test g)
- [x] Identity mismatch test added and passing (S1 fix — test h)
- [x] Additional tests from QA review findings (Q1, Q4) added and passing
- [x] Linting passes

---

## User Story 5: Flush Cache on Service Stop

**Goal**: Clear the cache when `McpAccessibilityService` is destroyed, since all
`AccessibilityNodeInfo` references become invalid when the service disconnects.

**Acceptance Criteria / Definition of Done**:
- [x] `McpAccessibilityService.onDestroy()` clears the cache
- [x] Cache is accessed via Hilt `EntryPoint` (since `McpAccessibilityService` is system-managed, not Hilt-injectable)
- [x] Linting passes on all changed files

> **Note on testing**: `McpAccessibilityService.onDestroy()` uses `EntryPointAccessors.fromApplication()`
> which requires a real Hilt application context. This cannot be unit tested with MockK alone.
> The cache flush is verified via:
> 1. US6 Final Verification (manual code review during implementation)
> 2. `AccessibilityNodeCacheImplTest` already covers `clear()` behavior (Test 1.4.4 and 1.4.7)
> 3. E2E tests exercise the full service lifecycle
>
> No additional unit test is added for US5 — the `onDestroy()` method is 4 lines of straightforward
> `EntryPointAccessors` boilerplate with a defensive try/catch.

### Task 5.1: Add cache flush to `McpAccessibilityService.onDestroy()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

**Action**: Access the `AccessibilityNodeCache` singleton and clear it in `onDestroy()`.

Since `McpAccessibilityService` extends `android.accessibilityservice.AccessibilityService` (system-managed),
it cannot use standard Hilt `@Inject`. Instead, use `EntryPointAccessors` to get the cache singleton:

```diff
+// NOTE: AccessibilityNodeCache is NOT imported — it is in the same package
+// (com.danielealbano.androidremotecontrolmcp.services.accessibility).
+// ktlint would flag a same-package import as unnecessary.
+import dagger.hilt.EntryPoint
+import dagger.hilt.InstallIn
+import dagger.hilt.android.EntryPointAccessors
+import dagger.hilt.components.SingletonComponent

 class McpAccessibilityService : AccessibilityService() {
+
+    @EntryPoint
+    @InstallIn(SingletonComponent::class)
+    interface NodeCacheEntryPoint {
+        fun nodeCache(): AccessibilityNodeCache
+    }

     override fun onDestroy() {
         Log.i(TAG, "Accessibility service destroying")

+        // Flush the node cache — all AccessibilityNodeInfo references become invalid
+        try {
+            val entryPoint = EntryPointAccessors.fromApplication(
+                applicationContext,
+                NodeCacheEntryPoint::class.java,
+            )
+            entryPoint.nodeCache().clear()
+        } catch (e: IllegalStateException) {
+            Log.w(TAG, "Could not flush node cache during destroy", e)
+        }
+
         serviceScope?.cancel()
         serviceScope = null
         currentPackageName = null
         currentActivityName = null
         inputMethodInstance = null
         instance = null

         super.onDestroy()
     }
```

NOTE: The `try/catch` handles the edge case where `applicationContext` might not be available
during destroy (rare, but defensive). Check if `McpAccessibilityService` already uses
`EntryPointAccessors` or if there's an existing pattern in the codebase. If so, follow the existing
pattern. If an `EntryPoint` already exists in this class, add `nodeCache()` to it instead of
creating a new one.

**Definition of Done**:
- [x] Cache cleared in `onDestroy()` before nulling `instance`
- [x] EntryPoint used to access cache singleton (or existing pattern if available)
- [x] Defensive try/catch for edge cases
- [x] Linting passes

---

## Review Findings (All Resolved)

### Performance Review

| # | Severity | Finding | Resolution |
|---|---|---|---|
| P1 | Critical | `AtomicReference` pattern is correct for this use case. `getAndSet` is atomic, `get()` returns immutable snapshot, no locking needed. | **Verified**: Pattern confirmed correct. No change needed. |
| P2 | Critical | Race between `populate` and `get`: a concurrent `get()` may see the old map whose entries get recycled. Since `recycle()` is a no-op on API 33+ (minSdk), this is safe. If minSdk is ever lowered below 33, a lock would be needed. | **FIXED**: Removed `recycle()` from `populate()`. Old entries are simply dropped and GC'd (`recycle()` is no-op on API 33+). Only `clear()` recycles, and it's called exclusively during service shutdown when no concurrent reads are possible. This eliminates the race condition entirely regardless of future minSdk changes. See updated Task 1.2 and Design Decisions table. Test 1.4.1 updated to verify populate does NOT recycle. |
| P3 | Critical | Memory cost is ~100-250KB for ~500 nodes — acceptable. Cleared on every `getFreshWindows` call (via `populate` atomic swap) and service destroy. | **Verified**: `CachedNode` wrapper adds minimal overhead (~16 bytes per entry for depth/index/parentId ref). Total cost remains acceptable for typical UIs (~500 nodes). Text-heavy UIs (e.g., chat apps with 1000+ nodes and long text content) may exceed 250KB due to `AccessibilityNodeInfo`'s internal text references, but this is still well within Android's per-app memory limits and is transient (replaced on next `getFreshWindows` call). Cleared on every `getFreshWindows` call (via `populate` atomic swap) and during service destroy (via `clear`). |
| P4 | Critical | Optimization trades O(N) IPC for O(1) on cache hits. Worst case (miss/stale) is identical to current behavior. No regression possible. | **Verified**: No regression path exists. Identity mismatch (S1 fix) adds one more fallback case but still falls through to walkAndMatch. **Note on `generateNodeId` hashCode**: `generateNodeId` uses `hashCode()` to produce node IDs. Theoretical hash collisions are possible for structurally different nodes but extremely rare in practice. If a collision occurred, the cache would return a different node whose identity re-verification via `generateNodeId()` would produce the same nodeId (since the IDs collided), potentially executing an action on the wrong node. This is a pre-existing risk in `walkAndMatch` (which also uses `generateNodeId`) and is not introduced by caching. Mitigation: the identity check after `refresh()` compares bounds/depth/index/parentId, which provides strong structural validation beyond the hash alone. |

### Security Review

| # | Severity | Finding | Resolution |
|---|---|---|---|
| S1 | Critical | Stale node after `refresh()`: `refresh()` updates the node's properties from the live UI. If a different element now occupies the same position, `refresh()` returns `true` with updated data. This is the same risk as the current `walkAndMatch` approach — no new attack surface. | **FIXED**: Added post-refresh identity check. After `refresh()` succeeds, `ActionExecutorImpl` re-generates the nodeId using `treeParser.generateNodeId()` with the refreshed node properties (bounds) and cached metadata (depth, index, parentId). If the re-generated nodeId differs from the requested nodeId, the node has changed identity (e.g., RecyclerView reused the View for a different element) → treated as cache miss → falls back to `walkAndMatch`. `CachedNode` data class stores depth/index/parentId metadata for this purpose. New test `clickNodeFallsBackWhenIdentityMismatch` (Task 4.3 test h) verifies this path. This provides **stronger** correctness guarantees than the current `walkAndMatch` approach. |
| S2 | Critical | Service availability check still happens before cache lookup in `performNodeAction`. No permission bypass possible. | **Verified**: Service check is the first operation in `performNodeAction`, before any cache access. Test `clickNodeFailsWhenServiceUnavailableEvenWithCache` (Task 4.3 test e) explicitly verifies this ordering. |
| S3 | Critical | Cache is internal, not exposed via any API. No information leakage. | **Verified**: `AccessibilityNodeCache` is only accessible via Hilt DI within the app module. No MCP endpoint, no broadcast, no intent exposes it. |
| S4 | Critical | `EntryPoint` pattern is standard Hilt. No concern. | **Verified**: Standard Hilt `EntryPointAccessors.fromApplication()` pattern. Defensive `try/catch` handles edge case where `applicationContext` might not be available during destroy. |

### QA Review

| # | Severity | Finding | Resolution |
|---|---|---|---|
| Q1 | Critical | Missing test: service unavailable but cache has data — verify service check still triggers failure before cache lookup. | **FIXED**: Added test in Task 4.3 (e): `clickNodeFailsWhenServiceUnavailableEvenWithCache` — verifies `mockCache.get()` is NOT called when service is null. |
| Q2 | Critical | Missing test: verify root node is stored in cache (not just children). | **FIXED**: Added test in Task 2.4 (7): `parseTreeNodeMapIncludesRootNode` — verifies nodeMap includes root node's ID as key and root `AccessibilityNodeInfo` as `CachedNode.node` value. |
| Q3 | Critical | No explicit integration test for the cache path. | **Verified**: Existing MCP integration tests in `integration/` mock `treeParser`, `actionExecutor`, `accessibilityServiceProvider`, and other Android service interfaces — they do NOT exercise the real `parseTree` → `performNodeAction` flow and therefore do NOT implicitly test the cache path (Major Finding 5 correction). However, the conclusion remains unchanged: no additional integration test is needed. The cache is a transparent optimization layer — it's a single Hilt singleton injected into `ActionExecutorImpl`. If the cache hits, the action is executed on the cached node; if it misses, the existing walkAndMatch path runs unchanged. The correctness of cache lookup, identity verification, and fallback is fully covered by the unit tests in Task 4.3 (`ActionExecutorImplTest`). An integration test would add no additional value because it would require a real Android accessibility tree, which is only available in E2E tests. |
| Q4 | Critical | Only `clickNode` cache tests specified. At least one test for another node action (e.g., `scrollNode`) would confirm the pattern. | **FIXED**: Added test in Task 4.3 (f): `scrollNodeUsesValidCachedNode` — verifies cache hit path for `scrollNode` action. |

### Plan Review Findings (All Resolved)

| # | Severity | Finding | Resolution |
|---|---|---|---|
| CF1 | Critical | **Multi-window cache overwrite**: `getFreshWindows()` calls `parseTree()` in a loop per window. If `parseTree()` calls `cache.populate()` directly, each call replaces the entire cache — only the last window's nodes survive. | **FIXED**: Redesigned cache population. `parseTree` takes optional `nodeMap` param and does NOT call `populate()`. `getFreshWindows` creates a single `accumulatedNodeMap`, passes it to every `parseTree` call, then calls `nodeCache.populate()` ONCE. See User Story 3. |
| CF2 | Critical | **Root node recycled after cache storage**: `getFreshWindows()` unconditionally recycles root nodes even though `parseTree` stores them in the cache. | **FIXED**: `getFreshWindows` no longer recycles root nodes when caching is active. Root nodes are stored in `accumulatedNodeMap` and owned by the cache. See Task 3.1. |
| MF3 | Major | **Fully-qualified `Rect` import**: `ActionExecutorImpl.kt` diff used `android.graphics.Rect()` fully qualified instead of importing `Rect`. | **FIXED**: Added `import android.graphics.Rect` to Task 4.2 diff. |
| MF4 | Major | **Empty `finally` block**: `performNodeAction` cache path had `try { ... } finally { /* comment only */ }` triggering detekt `EmptyFinallyBlock`. | **FIXED**: Removed `try/finally` entirely in Task 4.2 diff. |
| MF5 | Major | **Q3 reasoning incorrect**: Q3 stated integration tests exercise the full cache path. They don't — they mock all deps. | **FIXED**: Q3 resolution rewritten with correct reasoning. Conclusion unchanged. |
| MN6 | Minor | **`getBoundsInScreen` mock missing**: Cache-hit tests didn't mock `getBoundsInScreen(rect)`. | **FIXED**: `mockCacheHitWithIdentity` helper mocks it. All cache-hit tests use this helper. |
| MN7 | Minor | **Redundant double condition in `parseNode`**: `recycleNode && nodeMap == null` is defensively redundant. | **FIXED**: Inline comment added in Task 2.2 diff explaining the defensive intent. |
| MN8 | Minor | **`AccessibilityTreeParser` not `@Singleton`**: Different injection points may get different instances. | **FIXED**: Note in US2 header explaining statelessness makes this safe. |
| MN9 | Minor | **Constructor diff merge conflict risk**: Task 4.1 assumes empty `constructor()`. | **FIXED**: Note in Task 4.1 alerting implementor. |
| IF10 | Info | **`ElementActionTools.kt` missing from files to modify**: Not listed in Implementation Overview. | **FIXED**: All affected files now listed. |

### Second Plan Review Findings (All Resolved)

| # | Severity | Finding | Resolution |
|---|---|---|---|
| M1 | Major | **`setupMultiWindowMock` will break**: 2-arg `parseTree` mock silently returns empty data after US2. | **FIXED**: Task 3.7 step 4 updates mock to 3-arg with `any()` for `nodeMap`, removes `rootNode.recycle()` mock. |
| M2 | Major | **Task 3.7 diff incomplete**: Missing `MockDependencies` field, `createMockDependencies()`, `registerAllTools()`. | **FIXED**: Task 3.7 now has 5 explicit numbered steps with complete diffs. |
| M3 | Major | **US6 missing `setupMultiWindowMock` verification**. | **FIXED**: US6 checklist now includes 3-arg mock and integration test data correctness checks. |
| M4 | Major | **`mockCacheHitWithIdentity` uses `Rect.set()` no-op** under `returnDefaultValues = true`. | **FIXED**: Changed to slot + field assignment matching `AccessibilityTreeParserTest` pattern. |
| m1 | Minor | **`populate()` stores mutable map without defensive copy**. | **FIXED**: Added `entries.toMap()`. KDoc updated. |
| m2 | Minor | **`clear()` doc says "no concurrent reads possible"** — inaccurate. | **FIXED**: Updated to reference API 33+ no-op safety. |
| m3 | Minor | **No `Log.d` for cache miss** — contradicts acceptance criteria. | **FIXED**: Added explicit `Log.d` for cache miss. Acceptance criteria updated. |
| m4 | Minor | **Missing import instructions** for 3 tool files. | **FIXED**: Added import diffs to Tasks 3.3, 3.4, 3.5. |
| m5 | Minor | **`ScrollToElementTool` has 2 `getFreshWindows` calls** — second in retry loop not shown. | **FIXED**: Added prominent note in Task 3.2. |
| m6 | Minor | **No tests for US5** (`onDestroy` + EntryPoint). | **FIXED**: Added note explaining EntryPoint not MockK-testable; `clear()` covered by cache tests. |
| m7 | Minor | **Missing test for action failure on cached node path**. | **FIXED**: Added test g in Task 4.3. Tests renumbered a-h. |
| m8 | Minor | **No unit test for multi-window cache accumulation**. | **FIXED**: Added test 8 in Task 2.4. |
| m9 | Minor | **Task 4.1 KDoc has no concrete replacement text**. | **FIXED**: Added concrete old/new KDoc diff. |
| m10 | Minor | **Test d underspecified**. | **FIXED**: Added full mock setup and verification steps. |
| m11 | Minor | **`registerUtilityTools` diff formatting wrong**. | **FIXED**: Updated to match actual source layout. |
| m12 | Minor | **Test 8 redundant** with existing Test 2. | **FIXED**: Removed with explanation. |
| m13 | Minor | **`onTrimMemory` doesn't clear cache** — undocumented. | **FIXED**: Added to Design Decisions table with rationale. |
| i4 | Info | **Unnecessary same-package import** in Task 5.1. | **FIXED**: Removed import, added comment. |
| i5 | Info | **`AccessibilityNodeCacheImpl` unused `AccessibilityNodeInfo` import**. | **FIXED**: Removed import, KDoc uses backtick notation. |

### Third Plan Review Findings (All Resolved)

> **Note on IDs**: To avoid ambiguity with the Second Review table (which uses M1-M4, m1-m13),
> this table continues numbering: Majors start at M5, Minors start at m14.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| C1 | Critical | **8 test files missing from plan**: 5 unit tests (`ElementActionToolsTest`, `TextInputToolsTest`, `ScreenIntrospectionToolsTest`, `UtilityToolsTest`, `GetElementDetailsToolTest`) and 3 integration tests (`ScreenIntrospectionIntegrationTest`, `ElementActionIntegrationTest`, `ErrorHandlingIntegrationTest`) not listed in "Files to modify" and have no update task. Tool constructor changes, `parseTree` 3-arg signature, and `rootNode.recycle()` removal would cause compile failures. | **FIXED**: Added all 8 files to "Files to modify" section. Added comprehensive Task 3.8 covering all changes per file: `mockNodeCache` field, import, constructor updates, `parseTree` 3-arg mocks, `rootNode.recycle()` mock removal. Special handling for `ScreenIntrospectionToolsTest` test rename and behavior change (root nodes no longer recycled, `nodeCache.populate()` verified). US3 acceptance criteria updated. US6 checklist updated with 4 new verification items. |
| M5 | Major | **Missing `@Suppress("LongParameterList")`** on `registerUtilityTools` — gains 6th parameter. | **FIXED**: Added `@Suppress("LongParameterList")` annotation to Task 3.5 diff and DoD. |
| M6 | Major | **Stale S1 cross-reference**: "test g" should be "test h" (test g is `clickNodeActionFailsViaCachedNode`, test h is `clickNodeFallsBackWhenIdentityMismatch`). | **FIXED**: Updated S1 resolution text from "Task 4.3 test g" to "Task 4.3 test h". |
| M7 | Major | **3 unnecessary same-package imports** in Task 4.3: `AccessibilityNodeCache`, `AccessibilityTreeParser`, `CachedNode` are in the same `services.accessibility` package as `ActionExecutorImplTest` — ktlint `no-unused-imports` would flag them. | **FIXED**: Removed all 3 imports from Task 4.3 imports section. Added NOTE explaining same-package rationale. Only `Rect` and `slot` imports remain. |
| M8 | Major | **Test h `getBoundsInScreen` mock implicit** — description said "Mock `getBoundsInScreen` on cached node (sets known bounds)" without showing the slot+field pattern, risking `Rect.set()` no-op bug. | **FIXED**: Rewrote test h to use `mockCacheHitWithIdentity` helper (which has the correct slot+field pattern) then override `generateNodeId` with explicit code snippet. This also resolves m17. |
| m14 | Minor | **Missing function-level KDoc** for `parseTree` `@param nodeMap`. Task 2.3 only updated class-level KDoc. | **FIXED**: Task 2.3 now explicitly updates both class-level and function-level KDoc for `parseTree` (with `@param nodeMap`) and `parseNode`. DoD updated with 3 separate KDoc items. |
| m15 | Minor | **Stale `parseNode` KDoc** — `@param recycleNode` says "Child nodes are always recycled" (line 137 of source). | **FIXED**: Task 2.3 now includes diff to replace the stale `@param recycleNode` description with conditional recycling explanation. |
| m16 | Minor | **`registerTextInputTools` diff formatting** doesn't match actual source layout. Plan showed single-line constructor with trailing comma wrapping. Actual source has each constructor on one line with `.register()` on next line. | **FIXED**: Replaced diff with format matching actual source layout. |
| m17 | Minor | **Helper documented for "a, d, f, g, h"** but test h showed all manual mocks (didn't use helper). | **FIXED**: Test h now uses the helper (`mockCacheHitWithIdentity`) and overrides `generateNodeId`. Helper doc stays "a, d, f, g, h" (all correct now). |
| m18 | Minor | **US1 says "ServiceModule"** but US6 says "AppModule.kt"** — both refer to `ServiceModule` abstract class inside `AppModule.kt` file but at different specificity levels. | **FIXED**: US1 acceptance criteria now says "`ServiceModule` (inside `AppModule.kt`)" for clarity. |
| m19 | Minor | **`getBoundsInScreen()` after `refresh()` IPC cost unclear** — could be misread as an additional IPC call. It reads the locally cached bounds field (updated by refresh), costing 0 additional IPCs. | **FIXED**: Added inline comment in Task 4.2 cache lookup diff explaining 0 IPC cost. |
| m20 | Minor | **Memory estimate slightly optimistic** — 100-250KB for ~500 nodes may underestimate text-heavy UIs (chat apps with 1000+ nodes). | **FIXED**: P3 resolution updated to note text-heavy UIs may exceed 250KB, but this is still within Android per-app limits and is transient. |
| m21 | Minor | **`getFreshWindows` KDoc says "No caching is used"** — becomes stale after adding cache population. | **FIXED**: Task 3.1 now includes replacement KDoc for the "Performance note" section. DoD updated. |
| m22 | Minor | **Accumulation test should explain why nodeId collisions are impossible** across windows. | **FIXED**: Test 8 description in Task 2.4 now explains that different `rootParentId` per window ensures unique nodeIds. |
| m23 | Minor | **`onTrimMemory` + failed-`getFreshWindows` edge case undocumented** — if `getFreshWindows` fails mid-way, `populate()` is never called and cache retains previous snapshot. | **FIXED**: Added edge case documentation to `onTrimMemory` design decision explaining this is harmless (fallback `walkAndMatch` handles resolution, next successful call replaces cache). |

### Fourth Plan Review Findings (All Resolved)

| # | Severity | Finding | Resolution |
|---|---|---|---|
| m24 | Minor | **Task 3.8 common pattern imprecise for `rootNode.recycle()` removal**: Item 4 in the common pattern said "Remove `rootNode.recycle()` mocks" as applying to all 5 unit test files. `GetElementDetailsToolTest` has no `every { mockRootNode.recycle() }` setup, and `ScreenIntrospectionToolsTest` has `verify` calls (not `every` mocks) handled separately. The instruction was harmless but imprecise. | **FIXED**: Item 4 now specifies which 3 files have recycle mocks to remove (`ElementActionToolsTest`, `TextInputToolsTest`, `UtilityToolsTest`) and explicitly notes that `GetElementDetailsToolTest` and `ScreenIntrospectionToolsTest` do not apply. |
| m25 | Minor | **Task 4.3 cache-hit tests: `windows` parameter unspecified**: Tests (a), (d), (f), (g), (h) call `executor.clickNode("node_target", windows)` but don't describe how to construct `windows`. The cache hit path short-circuits before accessing `windows`, so the value doesn't affect the test outcome — but it's unspecified. | **FIXED**: Added a note above test (a) in Task 4.3 explaining that `windows` can be any valid `List<WindowData>` (e.g., `emptyList()` or from `wrapInWindows()`) since the cache path short-circuits before the window walk. |
| m26 | Minor | **Finding ID collision across review tables**: "m4" appeared in both the Second Review table (m4: "Missing import instructions for 3 tool files") and the Third Review table (m4: "Helper documented for a, d, f, g, h"), causing ambiguity when referencing them. | **FIXED**: Renumbered the Third Review table to use unique IDs — Majors continue at M5-M8, Minors at m14-m23. All US6 checklist cross-references updated to use new IDs. Added a note at the top of the Third Review table explaining the numbering scheme. |
| i1 | Info | **`generateNodeId` visibility**: `generateNodeId` is `internal` in `AccessibilityTreeParser`. `ActionExecutorImpl` is in the same `app` module, so `internal` is sufficient. No issue, but worth confirming during implementation. | **FIXED**: Added note in Task 4.1 confirming `internal` visibility is sufficient since both classes are in the same Gradle module. |

### Fifth Plan Review Findings (All Resolved)

> **Numbering**: Findings use F-prefix for Medium, M-prefix for Minor (continuing from m26),
> P-prefix for Performance, I-prefix for Informational, D-prefix for implementation Discrepancies.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| F1 | Medium | **Task 3.8 TextInputToolsTest PressKeyTool deviation undocumented**: The plan instructs removing `mockRootNode.recycle()` from setUp, which is correct for Type* tools using `getFreshWindows`. However, `PressKeyTool` uses `findFocusedEditableNode` → `getRootNode()` and recycles the root node directly. During implementation, `mockRootNode.recycle()` + `getRootNode()` mocks were added to 2 specific PressKeyTool tests. This deviation was not documented. | **FIXED**: Added a detailed note to Task 3.8 common pattern item 4 explaining the PressKeyTool exception: which tests need local mocks and why. |
| m27 | Minor | **Task 2.4 test 8 "prefixed IDs" wording**: Said "keys include both 'root_w0' and 'root_w1' prefixed IDs". Node IDs are hashes (`node_<hash>`), not literally prefixed with `root_w0`. The `rootParentId` values flow through CachedNode metadata, but keys are generated hashes. | **FIXED**: Reworded to "entries have distinct nodeId keys, with CachedNode metadata containing parentId values rooted in root_w0 and root_w1 respectively". |
| m28 | Minor | **Task 2.2 confusing recycleNode phrasing**: "`recycleNode` parameter for children is `false` when caching is active (`nodeMap == null` is `false`)" was hard to parse. | **FIXED**: Reworded to "When caching is active (nodeMap is non-null), recycleNode for children is set to false because the expression nodeMap == null evaluates to false." |
| m29 | Minor | **Task 2.4 test 5/7 `ROOT_PARENT_ID` reference**: Used `ROOT_PARENT_ID` which is a private constant in AccessibilityTreeParser; tests must use the string `"root"` directly. | **FIXED**: Changed to `parentId="root" (the default rootParentId value)` in both test 5 and test 7. |
| P5 | Minor | **`entries.toMap()` is O(N) copy**: While acceptable for ~500 nodes, worth documenting that `HashMap(entries)` could be more efficient if profiling shows a hotspot. | **FIXED**: Added inline comment in Task 1.2 populate() noting the O(N) cost and HashMap alternative. |
| P6 | Minor | **`generateNodeId` hashCode collision risk**: `generateNodeId` uses `hashCode()` — theoretical collision risk for structurally different nodes. Very rare but could target wrong node. | **FIXED**: Added detailed note to P4 performance review entry documenting the risk, noting it is pre-existing in `walkAndMatch`, and explaining that the identity check after `refresh()` provides structural validation beyond the hash. |
| I7 | Info | **Interface KDoc imprecise**: Said "populated during parseTree" — technically parseTree fills the nodeMap, the caller populates the cache via `populate()`. | **FIXED**: Updated KDoc in both plan and source to "populated with node references obtained during parseTree (via the caller's accumulation map)". |
| D1 | Info | **McpAccessibilityService.kt missing NOTE comment**: Plan specified a comment explaining why `AccessibilityNodeCache` is not imported (same package). Implementation omitted it. | **FIXED**: Added comment above `NodeCacheEntryPoint` interface: `// AccessibilityNodeCache is in the same package — no import needed`. Placed at class level (not in imports) to avoid breaking ktlint import ordering. |

### Sixth Plan Review Findings (All Resolved)

> **Numbering**: Findings use DISC-prefix for plan-to-code discrepancies, P-PERF for performance,
> S-SEC for security, Q-QA for QA.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| DISC-01 | Minor | **`AccessibilityNodeCacheImpl.populate()` missing P5 performance comment**: Plan's Task 1.2 code block includes the inline comment `// Performance: O(N) copy for ~500 nodes is negligible. If profiling shows this as a hotspot, HashMap(entries) could be more efficient due to pre-sizing.` (added as P5 fix). The actual source file omitted it. | **FIXED**: Added the performance comment to `AccessibilityNodeCacheImpl.kt` `populate()` method, matching the plan exactly. |
| DISC-02 | Info | **`AccessibilityNodeCacheImpl` missing NOTE about absent `AccessibilityNodeInfo` import**: Plan's Task 1.2 code block includes a `// NOTE: android.view.accessibility.AccessibilityNodeInfo is NOT explicitly imported...` comment. The actual source file omitted it. | **FIXED**: Added the NOTE comment above the class KDoc in `AccessibilityNodeCacheImpl.kt`. |
| P-PERF-05 | Minor | **`Rect` allocation on every cache-hit identity check**: `performNodeAction` allocates a new `Rect()` on each cache hit for `getBoundsInScreen()`. Trivial cost (object allocation, no IPC) and by design — must read fresh bounds to detect identity changes. | **Acknowledged**: No code change needed. The `Rect` allocation is a few nanoseconds. Caching the `Rect` would not help because fresh bounds are required for identity verification (that's the whole point of the S1 fix). The inline comment at `ActionExecutorImpl.kt` line 574 already documents that `getBoundsInScreen()` is not an additional IPC call. |
| S-SEC-05 | Minor | **Pre-existing `generateNodeId` hashCode collision risk**: `generateNodeId` uses `String.hashCode()` (32-bit). Theoretical collision between structurally different nodes could cause wrong-node action. Identity check uses the same hash, so a collision would pass verification. | **Acknowledged**: Pre-existing risk in `walkAndMatch` (not introduced by cache). Already documented in P4/P6 review entries. Probability is negligible for real UI trees (~500 nodes). Mitigation: `generateNodeId` inputs include viewIdResourceName, className, bounds, depth, index, parentId — making collisions astronomically unlikely. Future mitigation if needed: use a longer hash (e.g., SHA-256 truncated). |
| Q-QA-04 | Minor | **No concurrent stress test for `populate()`+`get()`**: Thread safety relies on JDK `AtomicReference` contract rather than an explicit concurrent test. | **Acknowledged**: `AtomicReference` is a well-established JDK primitive with formally verified thread-safety guarantees. A concurrent stress test would test the JDK, not application logic. The unit tests verify populate/get/clear behavior sequentially, which covers the application-level correctness. |

---

## User Story 6: Final Verification

**Goal**: Double-check everything implemented from the ground up to ensure correctness,
consistency, and no regressions.

**Acceptance Criteria / Definition of Done**:
- [x] Re-read ALL created and modified files end-to-end
- [x] Verify `CachedNode` data class has `node`, `depth`, `index`, `parentId` fields
- [x] Verify `AccessibilityNodeCache` interface has correct method signatures using `CachedNode`
- [x] Verify `AccessibilityNodeCacheImpl` uses `AtomicReference<Map<String, CachedNode>>` correctly
- [x] Verify `populate()` does NOT call `recycle()` on old entries (P2 fix)
- [x] Verify `clear()` DOES call `recycle()` on old entries (shutdown-only operation)
- [x] Verify `AccessibilityTreeParser` has `nodeMap` parameter on `parseTree`/`parseNode` (NOT cache injection)
- [x] Verify `AccessibilityTreeParser` does NOT recycle nodes when `nodeMap` is provided
- [x] Verify `AccessibilityTreeParser` DOES recycle nodes when `nodeMap` is null (backward compat)
- [x] Verify `getFreshWindows` accumulates nodes across ALL windows into a single `nodeMap`
- [x] Verify `getFreshWindows` calls `nodeCache.populate()` ONCE after the window loop
- [x] Verify `getFreshWindows` does NOT recycle root nodes (they are in the cache)
- [x] Verify the fallback single-window path in `getFreshWindows` also populates the cache
- [x] Verify all 12 tool classes that call `getFreshWindows` pass `nodeCache`
- [x] Verify all 4 `register*Tools` functions accept and pass `nodeCache`
- [x] Verify `McpServerService` injects `nodeCache` and passes to register functions
- [x] Verify `McpIntegrationTestHelper` passes mock `nodeCache` to register functions
- [x] Verify `setupMultiWindowMock` uses 3-arg `parseTree` mock (`any()` for nodeMap) and no longer mocks `rootNode.recycle()`
- [x] Verify integration tests still return correct tree content (not empty `AccessibilityNodeData` defaults from relaxed mock)
- [x] Verify `ActionExecutorImpl` constructor takes both `AccessibilityNodeCache` and `AccessibilityTreeParser`
- [x] Verify `ActionExecutorImpl.performNodeAction` checks cache first, validates with `refresh()`, AND re-verifies identity via `generateNodeId()` (S1 fix)
- [x] Verify `import android.graphics.Rect` in `ActionExecutorImpl` (not fully-qualified, Major Finding 3)
- [x] Verify no empty finally block in `ActionExecutorImpl` cache path (Major Finding 4)
- [x] Verify identity mismatch falls through to walkAndMatch (S1 fix)
- [x] Verify cached nodes are NOT recycled by action executor (owned by cache)
- [x] Verify `McpAccessibilityService.onDestroy()` clears the cache
- [x] Verify Hilt binding exists in `AppModule.kt`
- [x] Verify ALL unit tests pass: `./gradlew :app:testDebugUnitTest`
- [x] Verify linting passes: `make lint`
- [x] Verify build succeeds: `./gradlew assembleDebug`
- [x] Verify no TODOs, no dead code, no temporary hacks in changed files
- [x] Verify KDoc is accurate and up-to-date on all changed/created classes and methods
- [x] Verify thread safety: `AtomicReference` swap in cache, no shared mutable state elsewhere
- [x] Verify `populate()` race safety: no recycle on old entries, safe for concurrent `get()` (P2 fix verified)
- [x] Verify identity check: `generateNodeId()` re-called after `refresh()`, mismatch → fallback (S1 fix verified)
- [x] Verify the fallback path (cache miss/stale/identity mismatch) is identical to previous behavior (no regression)
- [x] Verify all review findings (P1-P4, S1-S4, Q1-Q4) are addressed
- [x] Verify all plan review findings (Critical 1-2, Major 3-5, Minor 6-9, Info 10) are addressed
- [x] Verify all second plan review findings (Major M1-M4, Minor m1-m13, Info i4-i5) are addressed
- [x] Verify `populate()` uses `entries.toMap()` defensive copy (m1 fix)
- [x] Verify `mockCacheHitWithIdentity` uses slot + field assignment, NOT `Rect.set()` (M4 fix)
- [x] Verify `ScrollToElementTool` both `getFreshWindows` call sites pass `nodeCache` (m5 fix)
- [x] Verify `onTrimMemory` does NOT clear cache (documented design decision, m13 fix)
- [x] Verify `AccessibilityNodeCacheImpl` does NOT import `AccessibilityNodeInfo` (i5 fix)
- [x] Verify `McpAccessibilityService` does NOT import same-package `AccessibilityNodeCache` (i4 fix)
- [x] Verify all 5 unit test files updated: `mockNodeCache` field, `parseTree` 3-arg mocks, tool constructors with `nodeCache` (C1/Task 3.8)
- [x] Verify all 3 integration test files updated: local `parseTree` 3-arg mocks, `rootNode.recycle()` mocks removed (C1/Task 3.8)
- [x] Verify `ScreenIntrospectionToolsTest.recyclesRootNodeAndWindowInfoAfterParsing` renamed and updated: root node NOT recycled, `nodeCache.populate()` verified (C1/Task 3.8)
- [x] Verify `ScreenIntrospectionToolsTest` fallback test: root node NOT recycled, `nodeCache.populate()` verified (C1/Task 3.8)
- [x] Verify `registerUtilityTools` has `@Suppress("LongParameterList")` annotation (M5/Task 3.5)
- [x] Verify `ActionExecutorImplTest` does NOT import same-package classes (`AccessibilityNodeCache`, `AccessibilityTreeParser`, `CachedNode`) (M7/Task 4.3)
- [x] Verify test h uses `mockCacheHitWithIdentity` helper then overrides `generateNodeId` (M8/Task 4.3)
- [x] Verify `parseTree` function-level KDoc has `@param nodeMap` (m14/Task 2.3)
- [x] Verify `parseNode` function-level KDoc `@param recycleNode` updated — no longer says "always recycled" (m15/Task 2.3)
- [x] Verify `registerTextInputTools` diff matches actual source layout (m16/Task 3.4)
- [x] Verify `Hilt ServiceModule` reference says "inside `AppModule.kt`" (m18/US1)
- [x] Verify `getBoundsInScreen` after `refresh()` comment explains 0 IPC cost (m19/Task 4.2)
- [x] Verify P3 memory estimate notes text-heavy UI caveat (m20)
- [x] Verify `getFreshWindows` KDoc updated — "No caching is used" replaced (m21/Task 3.1)
- [x] Verify accumulation test notes nodeId collision impossibility due to different `rootParentId` (m22/Task 2.4)
- [x] Verify `onTrimMemory` design decision documents failed-`getFreshWindows` edge case (m23)
- [x] Verify all third plan review findings (C1, M5-M8, m14-m23) are addressed
- [x] Verify all fourth plan review findings (m24-m26, i1) are addressed
- [x] Verify Task 3.8 common pattern item 4 specifies which files have recycle mocks to remove (m24)
- [x] Verify Task 4.3 has note explaining `windows` parameter for cache-hit tests (m25)
- [x] Verify Third Review table uses unique IDs (M5-M8, m14-m23) with no collisions with Second Review (m26)
- [x] Verify Task 4.1 has note confirming `generateNodeId` `internal` visibility is sufficient (i1)
- [x] Verify all fifth plan review findings (F1, m27-m29, P5-P6, I7, D1) are addressed
- [x] Verify Task 3.8 documents PressKeyTool exception for TextInputToolsTest (F1)
- [x] Verify Task 2.4 test 8 uses correct wording for nodeId keys vs parentId metadata (m27)
- [x] Verify Task 2.2 recycleNode phrasing is clear (m28)
- [x] Verify Task 2.4 tests 5 and 7 use `"root"` instead of `ROOT_PARENT_ID` (m29)
- [x] Verify `populate()` has O(N) efficiency note in plan (P5)
- [x] Verify P4 documents `generateNodeId` hashCode collision risk (P6)
- [x] Verify `AccessibilityNodeCache` interface KDoc uses "via the caller's accumulation map" (I7)
- [x] Verify `McpAccessibilityService.kt` has same-package import comment (D1)
- [x] Verify all sixth plan review findings (DISC-01, DISC-02, P-PERF-05, S-SEC-05, Q-QA-04) are addressed
- [x] Verify `AccessibilityNodeCacheImpl.populate()` has P5 performance comment in source (DISC-01)
- [x] Verify `AccessibilityNodeCacheImpl` has NOTE about absent `AccessibilityNodeInfo` import in source (DISC-02)
- [x] Verify `Rect` allocation in cache-hit path is documented as by-design (P-PERF-05)
- [x] Verify `generateNodeId` hashCode collision risk is documented in P4/P6 (S-SEC-05)
- [x] Verify thread-safety relies on JDK `AtomicReference` contract, no concurrent stress test needed (Q-QA-04)
- [x] Review git diff to ensure only intended changes are present
