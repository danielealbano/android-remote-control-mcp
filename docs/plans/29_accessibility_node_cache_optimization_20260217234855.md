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
| Old cache cleanup on clear | `clear()` recycles all entries. Called only during service shutdown (`onDestroy`), when no concurrent reads are possible. |
| Post-refresh identity check | After `refresh()` succeeds, re-generate the nodeId from refreshed properties and compare with the requested nodeId. If mismatched, the node has changed identity → treat as cache miss and fall back to `walkAndMatch`. |

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

---

## User Story 1: Create AccessibilityNodeCache Interface and Implementation

**Goal**: Create the cache class with thread-safe populate/get/clear operations.

**Acceptance Criteria / Definition of Done**:
- [ ] `CachedNode` data class exists with `node`, `depth`, `index`, `parentId` fields
- [ ] `AccessibilityNodeCache` interface exists with `populate`, `get`, `clear`, `size` methods using `CachedNode`
- [ ] `AccessibilityNodeCacheImpl` implements the interface using `AtomicReference<Map<String, CachedNode>>`
- [ ] `populate` atomically swaps in a new map. Old entries are NOT recycled (concurrent `get` safety — P2 fix)
- [ ] `clear` atomically swaps to empty map and calls `recycle()` on all entries from the old map (safe: only called during shutdown)
- [ ] `get` returns the cached `CachedNode` or null
- [ ] `size` returns the current number of cached entries
- [ ] Bound in Hilt `ServiceModule` as `@Singleton`
- [ ] Unit tests pass for all cache operations (including P2 fix verification)
- [ ] Linting passes on all new/changed files

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
 * The cache is populated during [AccessibilityTreeParser.parseTree] and consumed
 * by [ActionExecutorImpl.performNodeAction].
 *
 * Thread-safe: all operations are safe to call from any thread.
 */
interface AccessibilityNodeCache {
    /**
     * Replaces the entire cache with [entries].
     *
     * The previous cache entries are NOT recycled by this method — a concurrent [get]
     * call may still hold a reference to a node from the old map. Old entries are dropped
     * and collected by GC ([AccessibilityNodeInfo.recycle] is a no-op on API 33+).
     *
     * @param entries Map of nodeId to [CachedNode].
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
     * Only safe to call when no concurrent [get] calls are possible (e.g., service shutdown).
     */
    fun clear()

    /**
     * Returns the number of currently cached entries.
     */
    fun size(): Int
}
```

**Definition of Done**:
- [ ] File created with `CachedNode` data class and `AccessibilityNodeCache` interface
- [ ] Linting passes

### Task 1.2: Create `AccessibilityNodeCacheImpl` class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityNodeCacheImpl.kt`

**Action**: Create new file with the implementation:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Thread-safe implementation of [AccessibilityNodeCache] using [AtomicReference].
 *
 * Uses atomic swap for lock-free cache replacement:
 * - [populate]: atomically swaps in a new immutable map. Old entries are NOT recycled
 *   because a concurrent [get] call may still hold a reference to a [CachedNode] from
 *   the old map. Since [AccessibilityNodeInfo.recycle] is a no-op on API 33+ (minSdk),
 *   GC safely collects unreferenced old entries. (P2 fix)
 * - [get]: reads the current map (no locking).
 * - [clear]: atomically swaps to an empty map AND recycles old entries. This is only
 *   called during service shutdown ([McpAccessibilityService.onDestroy]) when no
 *   concurrent reads are possible.
 *
 * Hilt-injectable as a singleton.
 */
class AccessibilityNodeCacheImpl
    @Inject
    constructor() : AccessibilityNodeCache {
        private val cache = AtomicReference<Map<String, CachedNode>>(emptyMap())

        override fun populate(entries: Map<String, CachedNode>) {
            val old = cache.getAndSet(entries)
            Log.d(TAG, "Cache populated with ${entries.size} entries (dropped ${old.size} old)")
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
- [ ] File created with the implementation above
- [ ] Linting passes

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
- [ ] Binding added after `ServiceModule` class declaration, before existing bindings
- [ ] Linting passes

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
- [ ] All tests pass
- [ ] Linting passes

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
- [ ] `parseTree` accepts optional `nodeMap: MutableMap<String, CachedNode>?` parameter (default `null`)
- [ ] `parseNode` accepts optional `nodeMap: MutableMap<String, CachedNode>?` parameter (default `null`)
- [ ] When `nodeMap` is non-null, each parsed node is stored as a `CachedNode` in the map
- [ ] When `nodeMap` is non-null, child nodes are NOT recycled (stored in map instead)
- [ ] When `nodeMap` is null, existing recycle behavior is preserved (backward compatible)
- [ ] `parseTree` does NOT call `cache.populate()` — the caller accumulates and populates
- [ ] `AccessibilityTreeParser` constructor is UNCHANGED (no cache injection)
- [ ] Root node is still NOT recycled by `parseTree` (caller retains ownership), but IS stored in nodeMap when provided
- [ ] Existing unit tests updated to pass `nodeMap` where appropriate
- [ ] New tests verify nodeMap is populated during parsing
- [ ] Linting passes on all changed files

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
- [ ] `nodeMap` parameter added to `parseTree`
- [ ] Parameter is passed through to `parseNode`
- [ ] No `cache.populate()` call — caller is responsible
- [ ] Linting passes

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
- `recycleNode` parameter for children is `false` when caching is active (`nodeMap == null` is `false`).
- The `recycleNode && nodeMap == null` condition is defensively redundant for the normal flow (where `recycleNode` already reflects `nodeMap`'s state). However, it is kept explicitly to guard against direct `parseNode` calls that might set `recycleNode = true` alongside a non-null `nodeMap`. An inline comment in the code explains this intent (Minor Finding 7 clarification).
- Each `CachedNode` stores `depth`, `index`, and `parentId` so the consumer can re-generate the nodeId after `refresh()` (S1 identity check).

**Definition of Done**:
- [ ] `nodeMap` parameter added to `parseNode`
- [ ] Nodes stored in map when `nodeMap` is non-null
- [ ] Child nodes NOT recycled when caching is active
- [ ] Existing non-cached behavior preserved when `nodeMap` is null
- [ ] Defensive double condition has inline comment explaining intent (Minor Finding 7)
- [ ] Linting passes

### Task 2.3: Update `AccessibilityTreeParser` KDoc

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

**Action**: Update class-level KDoc to reflect that the parser now supports cache population via the
`nodeMap` output parameter. Remove the statement that child nodes are recycled after data extraction.

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

**Definition of Done**:
- [ ] KDoc updated
- [ ] Linting passes

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
     - Root: depth=0, index=0, parentId=ROOT_PARENT_ID
     - Child 0: depth=1, index=0, parentId=rootNodeId
     - Child 1: depth=1, index=1, parentId=rootNodeId

6. **Add new test `parseTreeWithDifferentRootParentIdPopulatesNodeMap`**:
   - Parse with `rootParentId = "root_w42"` and a nodeMap
   - Verify nodeMap is populated with entries using window-scoped IDs

7. **Add new test `parseTreeNodeMapIncludesRootNode`** (from QA finding Q2):
   - Parse a single root node (no children) with a nodeMap
   - Verify the map contains exactly 1 entry with the root node's ID as key
   - Verify the `CachedNode.node` is the root `AccessibilityNodeInfo`
   - Verify `CachedNode.depth == 0`, `CachedNode.index == 0`, `CachedNode.parentId == ROOT_PARENT_ID`

8. **Add new test `parseTreeWithoutNodeMapRecyclesChildren`**:
   - Parse a tree with root + 2 children WITHOUT passing a nodeMap (default null)
   - Verify `recycle()` WAS called on each child node (original behavior preserved)

**Definition of Done**:
- [ ] All existing tests continue to pass (parser constructor unchanged, no mock needed)
- [ ] New nodeMap-related tests added and passing (including QA finding Q2)
- [ ] Linting passes

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
- [ ] `getFreshWindows` accepts a `nodeCache: AccessibilityNodeCache` parameter
- [ ] `getFreshWindows` creates a `MutableMap<String, CachedNode>()` and passes it to each `parseTree` call
- [ ] `getFreshWindows` calls `nodeCache.populate(accumulatedMap)` ONCE after the window loop
- [ ] Root nodes are NOT recycled by `getFreshWindows` (they are stored in the cache)
- [ ] The fallback single-window path also accumulates and populates the cache
- [ ] All 12 tool classes that call `getFreshWindows` have `nodeCache` in their constructor
- [ ] All 4 affected `register*Tools` functions accept and pass `nodeCache`
- [ ] `McpServerService` injects `AccessibilityNodeCache` and passes it to register functions
- [ ] `McpIntegrationTestHelper` passes a mock `nodeCache` to register functions
- [ ] Linting passes on all changed files

### Task 3.1: Add `nodeCache` parameter to `getFreshWindows` and accumulate across windows

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Action**: Modify `getFreshWindows` to accept `nodeCache`, accumulate a `nodeMap` across all
`parseTree` calls, and populate the cache once after the loop. Stop recycling root nodes.

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
- [ ] `nodeCache` parameter added to `getFreshWindows`
- [ ] `accumulatedNodeMap` created at the start, passed to every `parseTree` call
- [ ] `nodeCache.populate(accumulatedNodeMap)` called ONCE after multi-window loop
- [ ] Root nodes NOT recycled in multi-window path (stored in cache)
- [ ] Root node NOT recycled in fallback single-window path (stored in cache)
- [ ] `nodeCache.populate()` also called in fallback path
- [ ] Linting passes

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
- [ ] All 4 tool classes have `nodeCache` in constructor
- [ ] All `getFreshWindows` call sites pass `nodeCache`
- [ ] `registerElementActionTools` accepts and passes `nodeCache`
- [ ] Linting passes

### Task 3.3: Add `nodeCache` to tool classes in `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Action**: Add `nodeCache: AccessibilityNodeCache` to `GetScreenStateHandler` constructor
and update its `getFreshWindows` call. Update `registerScreenIntrospectionTools`.

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
- [ ] `GetScreenStateHandler` has `nodeCache` in constructor, passes to `getFreshWindows`
- [ ] `registerScreenIntrospectionTools` accepts and passes `nodeCache`
- [ ] Linting passes

### Task 3.4: Add `nodeCache` to tool classes in `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

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

Update `registerTextInputTools`:
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
     TypeAppendTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController,
-        ).register(server, toolNamePrefix)
+        nodeCache).register(server, toolNamePrefix)
     // ... same pattern for TypeInsertTextTool, TypeReplaceTextTool, TypeClearTextTool ...
     PressKeyTool(actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix) // unchanged
 }
```

**Definition of Done**:
- [ ] All 4 Type* tool classes have `nodeCache` in constructor, pass to `getFreshWindows`
- [ ] `PressKeyTool` is NOT modified (does not call `getFreshWindows`)
- [ ] `registerTextInputTools` accepts and passes `nodeCache`
- [ ] Linting passes

### Task 3.5: Add `nodeCache` to tool classes in `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

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

Update `registerUtilityTools`:
```diff
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
-    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider)
+    WaitForElementTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)
         .register(server, toolNamePrefix)
-    WaitForIdleTool(treeParser, accessibilityServiceProvider).register(server, toolNamePrefix)
-    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider)
+    WaitForIdleTool(treeParser, accessibilityServiceProvider, nodeCache).register(server, toolNamePrefix)
+    GetElementDetailsTool(treeParser, elementFinder, accessibilityServiceProvider, nodeCache)
         .register(server, toolNamePrefix)
 }
```

**Definition of Done**:
- [ ] All 3 utility tool classes that call `getFreshWindows` have `nodeCache` in constructor
- [ ] `GetClipboardTool` and `SetClipboardTool` are NOT modified
- [ ] `registerUtilityTools` accepts and passes `nodeCache`
- [ ] Linting passes

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
- [ ] `AccessibilityNodeCache` injected via `@Inject lateinit var`
- [ ] Import added
- [ ] `nodeCache` passed to `registerElementActionTools`, `registerScreenIntrospectionTools`, `registerTextInputTools`, `registerUtilityTools`
- [ ] Linting passes

### Task 3.7: Update `McpIntegrationTestHelper` to pass mock `nodeCache`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

**Action**: Add a mock `AccessibilityNodeCache` to the test dependencies and pass it to all
affected `register*Tools` calls in the test helper.

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache

 // In createMockDependencies or equivalent:
+val mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)

 // In withTestApplication or equivalent, pass to register calls:
     registerElementActionTools(
         server, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider,
+        mockNodeCache,
         toolNamePrefix,
     )
     // ... same for registerScreenIntrospectionTools, registerTextInputTools, registerUtilityTools
```

**Definition of Done**:
- [ ] Mock `AccessibilityNodeCache` added to test dependencies
- [ ] All register function calls in test helper pass the mock
- [ ] Integration tests continue to compile and pass
- [ ] Linting passes

---

## User Story 4: Modify ActionExecutorImpl to Use Cache

**Goal**: Before falling back to `walkAndMatch`, check the cache for a hit and validate with
`refresh()`. If valid, use the cached node directly (O(1) IPC). Otherwise, fall back to
`walkAndMatch` (O(N) IPC, same as current behavior).

**Acceptance Criteria / Definition of Done**:
- [ ] `ActionExecutorImpl` receives `AccessibilityNodeCache` and `AccessibilityTreeParser` via constructor injection
- [ ] `performNodeAction` checks cache before walking the tree
- [ ] Cache hit + `refresh()` returns `true` + identity verified (nodeId re-generated matches) → node used directly (no tree walk)
- [ ] Cache hit + `refresh()` returns `true` + identity mismatch → fall back to `walkAndMatch` (S1 fix)
- [ ] Cache miss or `refresh()` returns `false` → fall back to `walkAndMatch` (existing behavior)
- [ ] Logging added for cache hit/miss/stale/identity-mismatch
- [ ] Existing unit tests updated for cache injection
- [ ] New tests verify cache hit path, fallback path, and identity mismatch path
- [ ] Linting passes on all changed files

### Task 4.1: Inject `AccessibilityNodeCache` and `AccessibilityTreeParser` into `ActionExecutorImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt`

**Action**: Modify constructor to accept cache and tree parser (parser needed for `generateNodeId`
to re-verify node identity after `refresh()` — S1 fix).

> **Note**: The current constructor is `constructor() : ActionExecutor`. If another plan has
> already added parameters before this plan is implemented, the diff base will differ — merge
> accordingly (Minor Finding 9).

```diff
 class ActionExecutorImpl
     @Inject
-    constructor() : ActionExecutor {
+    constructor(
+        private val nodeCache: AccessibilityNodeCache,
+        private val treeParser: AccessibilityTreeParser,
+    ) : ActionExecutor {
```

Update class KDoc to remove "stateless" and mention cache usage.

**Definition of Done**:
- [ ] Constructor parameters added (cache + tree parser)
- [ ] KDoc updated
- [ ] Linting passes

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
- [ ] Cache lookup added at the start of `performNodeAction`
- [ ] `import android.graphics.Rect` added at top of file (Major Finding 3)
- [ ] No empty finally block — code uses plain sequential flow (Major Finding 4)
- [ ] Cache hit + `refresh()` true + identity verified → action executed, node NOT recycled
- [ ] Cache hit + `refresh()` true + identity mismatch → falls through to walkAndMatch (S1 fix)
- [ ] Cache stale (`refresh()` false) → falls through to existing walkAndMatch logic
- [ ] Cache miss (null) → falls through to existing walkAndMatch logic
- [ ] Logging for cache hit, stale, identity mismatch, and implicit miss
- [ ] Linting passes

### Task 4.3: Update `ActionExecutorImplTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImplTest.kt`

**Action**: Update tests for cache injection and add cache-specific tests:

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

3. **Helper for cache-hit tests** (shared setup for tests a, d, f, g):
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
           // Mock getBoundsInScreen to set known bounds (Minor Finding 6)
           every { cachedNode.node.getBoundsInScreen(any()) } answers {
               val rect = firstArg<Rect>()
               rect.set(0, 0, 100, 100) // Known bounds for deterministic nodeId
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
      - Set up cache hit with `mockCacheHitWithIdentity(cachedNode, "node_target")` (includes `getBoundsInScreen` mock)
      - Execute action
      - Verify `recycle()` was NOT called on the cached node

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

   g. **`clickNodeFallsBackWhenIdentityMismatch`** (from S1 fix):
      - Set up service instance, mock windows, mock root nodes
      - `every { mockCache.get("node_target") } returns cachedNode`
      - `every { cachedNode.node.refresh() } returns true` (node still alive)
      - Mock `getBoundsInScreen` on cached node (sets known bounds)
      - Mock `treeParser.generateNodeId(...)` to return `"node_DIFFERENT"` (identity mismatch — View reused for different element)
      - Set up walkAndMatch to succeed via mocked windows
      - Call `executor.clickNode("node_target", windows)`
      - Verify `result.isSuccess` (via fallback walkAndMatch)
      - Verify `mockService.getAccessibilityWindows()` WAS called (fell back to tree walk)

**Definition of Done**:
- [ ] All existing tests updated and passing (constructor now takes `mockCache` + `mockTreeParser`)
- [ ] New cache hit/miss/stale tests added and passing
- [ ] All cache-hit tests (a, d, f, g) mock `getBoundsInScreen` on cached node (Minor Finding 6)
- [ ] Identity mismatch test added and passing (S1 fix — test g)
- [ ] Additional tests from QA review findings (Q1, Q4) added and passing
- [ ] Linting passes

---

## User Story 5: Flush Cache on Service Stop

**Goal**: Clear the cache when `McpAccessibilityService` is destroyed, since all
`AccessibilityNodeInfo` references become invalid when the service disconnects.

**Acceptance Criteria / Definition of Done**:
- [ ] `McpAccessibilityService.onDestroy()` clears the cache
- [ ] Cache is accessed via Hilt `EntryPoint` (since `McpAccessibilityService` is system-managed, not Hilt-injectable)
- [ ] Linting passes on all changed files

### Task 5.1: Add cache flush to `McpAccessibilityService.onDestroy()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

**Action**: Access the `AccessibilityNodeCache` singleton and clear it in `onDestroy()`.

Since `McpAccessibilityService` extends `android.accessibilityservice.AccessibilityService` (system-managed),
it cannot use standard Hilt `@Inject`. Instead, use `EntryPointAccessors` to get the cache singleton:

```diff
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
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
- [ ] Cache cleared in `onDestroy()` before nulling `instance`
- [ ] EntryPoint used to access cache singleton (or existing pattern if available)
- [ ] Defensive try/catch for edge cases
- [ ] Linting passes

---

## Review Findings (All Resolved)

### Performance Review

| # | Severity | Finding | Resolution |
|---|---|---|---|
| P1 | Critical | `AtomicReference` pattern is correct for this use case. `getAndSet` is atomic, `get()` returns immutable snapshot, no locking needed. | **Verified**: Pattern confirmed correct. No change needed. |
| P2 | Critical | Race between `populate` and `get`: a concurrent `get()` may see the old map whose entries get recycled. Since `recycle()` is a no-op on API 33+ (minSdk), this is safe. If minSdk is ever lowered below 33, a lock would be needed. | **FIXED**: Removed `recycle()` from `populate()`. Old entries are simply dropped and GC'd (`recycle()` is no-op on API 33+). Only `clear()` recycles, and it's called exclusively during service shutdown when no concurrent reads are possible. This eliminates the race condition entirely regardless of future minSdk changes. See updated Task 1.2 and Design Decisions table. Test 1.4.1 updated to verify populate does NOT recycle. |
| P3 | Critical | Memory cost is ~100-250KB for ~500 nodes — acceptable. Cleared on every `getFreshWindows` call (via `populate` atomic swap) and service destroy. | **Verified**: `CachedNode` wrapper adds minimal overhead (~16 bytes per entry for depth/index/parentId ref). Total cost remains acceptable. Cleared on every `getFreshWindows` call (via `populate` atomic swap) and during service destroy (via `clear`). |
| P4 | Critical | Optimization trades O(N) IPC for O(1) on cache hits. Worst case (miss/stale) is identical to current behavior. No regression possible. | **Verified**: No regression path exists. Identity mismatch (S1 fix) adds one more fallback case but still falls through to walkAndMatch. |

### Security Review

| # | Severity | Finding | Resolution |
|---|---|---|---|
| S1 | Critical | Stale node after `refresh()`: `refresh()` updates the node's properties from the live UI. If a different element now occupies the same position, `refresh()` returns `true` with updated data. This is the same risk as the current `walkAndMatch` approach — no new attack surface. | **FIXED**: Added post-refresh identity check. After `refresh()` succeeds, `ActionExecutorImpl` re-generates the nodeId using `treeParser.generateNodeId()` with the refreshed node properties (bounds) and cached metadata (depth, index, parentId). If the re-generated nodeId differs from the requested nodeId, the node has changed identity (e.g., RecyclerView reused the View for a different element) → treated as cache miss → falls back to `walkAndMatch`. `CachedNode` data class stores depth/index/parentId metadata for this purpose. New test `clickNodeFallsBackWhenIdentityMismatch` (Task 4.3 test g) verifies this path. This provides **stronger** correctness guarantees than the current `walkAndMatch` approach. |
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

---

## User Story 6: Final Verification

**Goal**: Double-check everything implemented from the ground up to ensure correctness,
consistency, and no regressions.

**Acceptance Criteria / Definition of Done**:
- [ ] Re-read ALL created and modified files end-to-end
- [ ] Verify `CachedNode` data class has `node`, `depth`, `index`, `parentId` fields
- [ ] Verify `AccessibilityNodeCache` interface has correct method signatures using `CachedNode`
- [ ] Verify `AccessibilityNodeCacheImpl` uses `AtomicReference<Map<String, CachedNode>>` correctly
- [ ] Verify `populate()` does NOT call `recycle()` on old entries (P2 fix)
- [ ] Verify `clear()` DOES call `recycle()` on old entries (shutdown-only operation)
- [ ] Verify `AccessibilityTreeParser` has `nodeMap` parameter on `parseTree`/`parseNode` (NOT cache injection)
- [ ] Verify `AccessibilityTreeParser` does NOT recycle nodes when `nodeMap` is provided
- [ ] Verify `AccessibilityTreeParser` DOES recycle nodes when `nodeMap` is null (backward compat)
- [ ] Verify `getFreshWindows` accumulates nodes across ALL windows into a single `nodeMap`
- [ ] Verify `getFreshWindows` calls `nodeCache.populate()` ONCE after the window loop
- [ ] Verify `getFreshWindows` does NOT recycle root nodes (they are in the cache)
- [ ] Verify the fallback single-window path in `getFreshWindows` also populates the cache
- [ ] Verify all 12 tool classes that call `getFreshWindows` pass `nodeCache`
- [ ] Verify all 4 `register*Tools` functions accept and pass `nodeCache`
- [ ] Verify `McpServerService` injects `nodeCache` and passes to register functions
- [ ] Verify `McpIntegrationTestHelper` passes mock `nodeCache` to register functions
- [ ] Verify `ActionExecutorImpl` constructor takes both `AccessibilityNodeCache` and `AccessibilityTreeParser`
- [ ] Verify `ActionExecutorImpl.performNodeAction` checks cache first, validates with `refresh()`, AND re-verifies identity via `generateNodeId()` (S1 fix)
- [ ] Verify `import android.graphics.Rect` in `ActionExecutorImpl` (not fully-qualified, Major Finding 3)
- [ ] Verify no empty finally block in `ActionExecutorImpl` cache path (Major Finding 4)
- [ ] Verify identity mismatch falls through to walkAndMatch (S1 fix)
- [ ] Verify cached nodes are NOT recycled by action executor (owned by cache)
- [ ] Verify `McpAccessibilityService.onDestroy()` clears the cache
- [ ] Verify Hilt binding exists in `AppModule.kt`
- [ ] Verify ALL unit tests pass: `./gradlew :app:testDebugUnitTest`
- [ ] Verify linting passes: `make lint`
- [ ] Verify build succeeds: `./gradlew assembleDebug`
- [ ] Verify no TODOs, no dead code, no temporary hacks in changed files
- [ ] Verify KDoc is accurate and up-to-date on all changed/created classes and methods
- [ ] Verify thread safety: `AtomicReference` swap in cache, no shared mutable state elsewhere
- [ ] Verify `populate()` race safety: no recycle on old entries, safe for concurrent `get()` (P2 fix verified)
- [ ] Verify identity check: `generateNodeId()` re-called after `refresh()`, mismatch → fallback (S1 fix verified)
- [ ] Verify the fallback path (cache miss/stale/identity mismatch) is identical to previous behavior (no regression)
- [ ] Verify all review findings (P1-P4, S1-S4, Q1-Q4) are addressed
- [ ] Verify all plan review findings (Critical 1-2, Major 3-5, Minor 6-9, Info 10) are addressed
- [ ] Review git diff to ensure only intended changes are present
