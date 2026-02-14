# Plan 16: Replace `wait_for_idle` Binary Hash with Fingerprint-Based Similarity Comparison

## Summary

Replace the current binary hash comparison in `wait_for_idle` with a 256-slot histogram fingerprint approach, enabling similarity-based idle detection via a `match_percentage` parameter (integer, 0-100, default 100). Uses normalized difference as the comparison metric. The fingerprint logic is extracted into a separate `TreeFingerprint` class for testability.

## Background

The current `wait_for_idle` tool uses a single recursive hash (`computeTreeHash`) to detect changes. This is binary: either the tree is identical or it's not. Minor changes (blinking cursor, clock update, subtle animations) cause false "not idle" results even though the UI is essentially stable.

The new approach generates a 256-slot integer histogram (fingerprint) from the accessibility tree. Each node's properties are combined via `hashCode()` and mapped to a bucket via `% 256`. Two fingerprints are compared using normalized difference to produce a similarity percentage.

## Key Design Decisions (Agreed)

- **Fingerprint**: 256-slot `IntArray` histogram
- **Hashing**: per-node `hashCode()` from `className`, `text`, `bounds`, `children.size` (same properties as current) → `% 256` → increment bucket
- **Comparison metric**: Normalized difference → `1 - (sum |a[i] - b[i]|) / (2 * totalNodes)` producing a value in `[0, 1]`, multiplied by 100 for percentage
- **`match_percentage` parameter**: integer 0-100, default 100 (preserves backward compatibility)
- **Consecutive checks**: keep `REQUIRED_IDLE_CHECKS = 2` (2 consecutive checks meeting threshold = idle)
- **Response JSON**: include `similarity` field (integer, 0-100) in both success and timeout responses
- **Code design**: extract fingerprint logic into separate `TreeFingerprint` class
- **NOT using**: cosine similarity

## Files Affected

| File | Change |
|------|--------|
| `app/src/main/kotlin/.../mcp/tools/TreeFingerprint.kt` | **NEW** — `TreeFingerprint` class |
| `app/src/main/kotlin/.../mcp/tools/UtilityTools.kt` | **MODIFY** — `WaitForIdleTool` to use `TreeFingerprint` + `match_percentage` param |
| `app/src/test/kotlin/.../mcp/tools/TreeFingerprintTest.kt` | **NEW** — Unit tests for `TreeFingerprint` |
| `app/src/test/kotlin/.../mcp/tools/UtilityToolsTest.kt` | **MODIFY** — Update `WaitForIdleToolTests` |
| `app/src/test/kotlin/.../integration/ErrorHandlingIntegrationTest.kt` | **MODIFY** — Update `wait_for_idle` integration test |
| `docs/MCP_TOOLS.md` | **MODIFY** — Update `wait_for_idle` documentation |

---

## User Story 1: Fingerprint-Based Similarity Detection for `wait_for_idle`

**As** an MCP client, **I want** the `wait_for_idle` tool to support a `match_percentage` threshold **so that** I can detect when the UI is "mostly idle" even if minor elements (cursors, clocks) keep changing.

### Acceptance Criteria / Definition of Done
- [ ] `TreeFingerprint` class exists with `generate()` and `compare()` methods
- [ ] `WaitForIdleTool` uses `TreeFingerprint` instead of `computeTreeHash`
- [ ] `match_percentage` parameter accepted (integer, 0-100, default 100)
- [ ] Response JSON includes `similarity` field (integer, 0-100)
- [ ] 2 consecutive checks meeting the threshold still required
- [ ] Default behavior (`match_percentage=100`) is identical to current behavior (exact match)
- [ ] All unit tests pass: `TreeFingerprintTest`, updated `WaitForIdleToolTests`
- [ ] All integration tests pass: updated `ErrorHandlingIntegrationTest`
- [ ] `MCP_TOOLS.md` documentation updated
- [ ] `./gradlew ktlintCheck` passes with no warnings/errors
- [ ] `./gradlew detekt` passes with no warnings/errors
- [ ] `./gradlew build` succeeds with no warnings/errors

---

### Task 1: Create `TreeFingerprint` class

**Goal**: Extract fingerprint generation and comparison into a standalone, testable class.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TreeFingerprint.kt` (NEW)

#### Acceptance Criteria
- [x] `TreeFingerprint` class created with correct package and imports
- [x] `generate(root: AccessibilityNodeData): IntArray` method works correctly
- [x] `compare(a: IntArray, b: IntArray): Int` method works correctly
- [x] Constants defined: `FINGERPRINT_SIZE = 256`
- [ ] No lint warnings/errors

#### Action 1.1: Create `TreeFingerprint.kt` file

Create new file `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TreeFingerprint.kt`.

**Contents**:

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import kotlin.math.abs

/**
 * Generates a fixed-size histogram fingerprint from an accessibility tree and
 * compares two fingerprints using normalized difference to produce a similarity
 * percentage.
 *
 * The fingerprint is a [FINGERPRINT_SIZE]-slot [IntArray]. For each node in the
 * tree, the node's properties (className, text, bounds, children.size) are
 * combined into a hash code, mapped to a bucket via `hash % FINGERPRINT_SIZE`,
 * and that bucket is incremented. The process recurses into all children.
 *
 * Comparison uses normalized difference with float arithmetic to avoid
 * integer truncation:
 * `similarity = (100f - (diffSum.toFloat() * 100f / (2f * totalNodes.toFloat()))).toInt()`
 * where `totalNodes = max(sum(a), sum(b))`, clamped to [0, 100].
 */
class TreeFingerprint {

    /**
     * Generates a [FINGERPRINT_SIZE]-slot histogram fingerprint from the given
     * accessibility tree root node.
     *
     * @param root the root [AccessibilityNodeData] of the tree
     * @return an [IntArray] of size [FINGERPRINT_SIZE] representing the fingerprint
     */
    fun generate(root: AccessibilityNodeData): IntArray {
        val fingerprint = IntArray(FINGERPRINT_SIZE)
        populateFingerprint(root, fingerprint)
        return fingerprint
    }

    /**
     * Compares two fingerprints and returns a similarity percentage (0-100).
     *
     * Uses normalized difference with float arithmetic to avoid integer truncation:
     * `similarity = (100f - (diffSum.toFloat() * 100f / (2f * totalNodes.toFloat()))).toInt()`
     * where `totalNodes = max(sum(a), sum(b))`.
     *
     * Returns 100 when both fingerprints are identical.
     * Returns 100 when both fingerprints are all zeros (both empty trees).
     *
     * @param a first fingerprint (must be of size [FINGERPRINT_SIZE])
     * @param b second fingerprint (must be of size [FINGERPRINT_SIZE])
     * @return similarity percentage as an integer in [0, 100]
     * @throws IllegalArgumentException if either array is not of size [FINGERPRINT_SIZE]
     */
    fun compare(a: IntArray, b: IntArray): Int {
        require(a.size == FINGERPRINT_SIZE) {
            "Fingerprint 'a' must have size $FINGERPRINT_SIZE, got ${a.size}"
        }
        require(b.size == FINGERPRINT_SIZE) {
            "Fingerprint 'b' must have size $FINGERPRINT_SIZE, got ${b.size}"
        }

        val totalNodes = maxOf(a.sum(), b.sum())
        if (totalNodes == 0) return FULL_MATCH_PERCENTAGE

        var diffSum = 0L
        for (i in 0 until FINGERPRINT_SIZE) {
            diffSum += abs(a[i] - b[i])
        }

        val similarity = (FULL_MATCH_PERCENTAGE.toFloat() - (diffSum.toFloat() * FULL_MATCH_PERCENTAGE.toFloat() / (2f * totalNodes.toFloat()))).toInt()
        return similarity.coerceIn(0, FULL_MATCH_PERCENTAGE)
    }

    private fun populateFingerprint(node: AccessibilityNodeData, fingerprint: IntArray) {
        val hash = computeNodeHash(node)
        val index = (hash and INDEX_MASK) % FINGERPRINT_SIZE
        fingerprint[index]++

        for (child in node.children) {
            populateFingerprint(child, fingerprint)
        }
    }

    private fun computeNodeHash(node: AccessibilityNodeData): Int {
        var hash = HASH_SEED
        hash = HASH_MULTIPLIER * hash + (node.className?.hashCode() ?: 0)
        hash = HASH_MULTIPLIER * hash + (node.text?.hashCode() ?: 0)
        hash = HASH_MULTIPLIER * hash + node.bounds.hashCode()
        hash = HASH_MULTIPLIER * hash + node.children.size
        return hash
    }

    companion object {
        /** Number of slots in the fingerprint histogram. */
        const val FINGERPRINT_SIZE = 256

        /** Percentage value representing a full match (100%). */
        const val FULL_MATCH_PERCENTAGE = 100

        private const val HASH_SEED = 17
        private const val HASH_MULTIPLIER = 31

        /** Mask to ensure non-negative index from hash code. */
        private const val INDEX_MASK = 0x7FFFFFFF
    }
}
```

**Key details**:
- `generate()` recursively walks the tree, hashing each node's `className`, `text`, `bounds`, `children.size` (same 4 properties as the current `computeTreeHash`)
- `computeNodeHash()` uses the same seed (17) and multiplier (31) as the current implementation
- `INDEX_MASK = 0x7FFFFFFF` ensures the hash is non-negative before `% 256`
- `compare()` uses normalized difference with float arithmetic: `similarity = (100f - (diffSum.toFloat() * 100f / (2f * totalNodes.toFloat()))).toInt()`, clamped to [0, 100]. Float arithmetic prevents integer division truncation that would round small changes to 0 in large trees (e.g., 1 node change in 101 nodes correctly yields 99, not 100).
- `totalNodes = max(sum(a), sum(b))` — uses the larger tree's node count as denominator
- Two empty fingerprints (both all-zeros) return 100 (both empty = identical)
- `compare()` validates array sizes via `require()`

---

### Task 2: Modify `WaitForIdleTool` to use `TreeFingerprint` and accept `match_percentage`

**Goal**: Replace `computeTreeHash` with `TreeFingerprint`, add `match_percentage` parameter, include `similarity` in response.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

#### Acceptance Criteria
- [x] `computeTreeHash` method removed
- [x] `HASH_SEED`, `HASH_MULTIPLIER` constants removed from companion object
- [x] `TreeFingerprint` instance used instead
- [x] `match_percentage` parameter parsed (integer, 0-100, default 100)
- [x] Comparison uses `TreeFingerprint.compare()` against `matchPercentage` threshold
- [x] Success response includes `similarity` field (integer)
- [x] Timeout response includes `similarity` field (integer, last computed value)
- [x] Tool description and inputSchema updated
- [ ] No lint warnings/errors

#### Action 2.1: Add `TreeFingerprint` dependency to `WaitForIdleTool`

In `UtilityTools.kt`, modify the `WaitForIdleTool` class.

**Change the class declaration** (lines 320-325):

```diff
 class WaitForIdleTool
     @Inject
     constructor(
         private val treeParser: AccessibilityTreeParser,
         private val accessibilityServiceProvider: AccessibilityServiceProvider,
-    ) {
+    ) {
+        private val treeFingerprint = TreeFingerprint()
```

No functional diff here — just add the `treeFingerprint` field after the opening brace.

#### Action 2.2: Replace `execute()` method body

Replace the entire `execute()` method (lines 327-377) with the new implementation:

**Old** (lines 327-377):
```kotlin
@Suppress("NestedBlockDepth", "ThrowsCount", "InstanceOfCheckForException")
suspend fun execute(arguments: JsonObject?): CallToolResult {
    val timeout =
        arguments?.get("timeout")?.jsonPrimitive?.longOrNull
            ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
    if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
        throw McpToolException.InvalidParams(
            "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
        )
    }

    val startTime = SystemClock.elapsedRealtime()
    var previousHash: Int? = null
    var consecutiveIdleChecks = 0

    while (SystemClock.elapsedRealtime() - startTime < timeout) {
        try {
            val tree = getFreshTree(treeParser, accessibilityServiceProvider)
            val currentHash = computeTreeHash(tree)

            if (previousHash != null && currentHash == previousHash) {
                consecutiveIdleChecks++
                if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms")
                    val resultJson =
                        buildJsonObject {
                            put("message", "UI is idle")
                            put("elapsedMs", elapsed)
                        }
                    return McpToolUtils.textResult(Json.encodeToString(resultJson))
                }
            } else {
                consecutiveIdleChecks = 0
            }

            previousHash = currentHash
        } catch (e: McpToolException) {
            if (e is McpToolException.PermissionDenied) throw e
            // Tree parse failures during transitions — reset idle counter
            consecutiveIdleChecks = 0
            previousHash = null
        }

        delay(IDLE_CHECK_INTERVAL_MS)
    }

    return McpToolUtils.textResult(
        "Operation timed out after ${timeout}ms waiting for UI idle. " +
            "Retry if the operation is long-running.",
    )
}
```

**New**:
```kotlin
@Suppress("CyclomaticComplexity", "NestedBlockDepth", "ThrowsCount", "InstanceOfCheckForException")
suspend fun execute(arguments: JsonObject?): CallToolResult {
    val timeout =
        arguments?.get("timeout")?.jsonPrimitive?.longOrNull
            ?: throw McpToolException.InvalidParams("Missing required parameter 'timeout'")
    if (timeout <= 0 || timeout > MAX_TIMEOUT_MS) {
        throw McpToolException.InvalidParams(
            "Timeout must be between 1 and $MAX_TIMEOUT_MS ms, got: $timeout",
        )
    }

    val matchPercentage =
        arguments?.get("match_percentage")?.jsonPrimitive?.intOrNull
            ?: DEFAULT_MATCH_PERCENTAGE
    if (matchPercentage < 0 || matchPercentage > TreeFingerprint.FULL_MATCH_PERCENTAGE) {
        throw McpToolException.InvalidParams(
            "match_percentage must be between 0 and ${TreeFingerprint.FULL_MATCH_PERCENTAGE}, got: $matchPercentage",
        )
    }

    val startTime = SystemClock.elapsedRealtime()
    var previousFingerprint: IntArray? = null
    var consecutiveIdleChecks = 0
    var lastSimilarity = 0

    while (SystemClock.elapsedRealtime() - startTime < timeout) {
        try {
            val tree = getFreshTree(treeParser, accessibilityServiceProvider)
            val currentFingerprint = treeFingerprint.generate(tree)

            if (previousFingerprint != null) {
                val similarity = treeFingerprint.compare(previousFingerprint, currentFingerprint)
                lastSimilarity = similarity

                if (similarity >= matchPercentage) {
                    consecutiveIdleChecks++
                    if (consecutiveIdleChecks >= REQUIRED_IDLE_CHECKS) {
                        val elapsed = SystemClock.elapsedRealtime() - startTime
                        Log.d(TAG, "wait_for_idle: UI idle after ${elapsed}ms (similarity=$similarity%)")
                        val resultJson =
                            buildJsonObject {
                                put("message", "UI is idle")
                                put("elapsedMs", elapsed)
                                put("similarity", similarity)
                            }
                        return McpToolUtils.textResult(Json.encodeToString(resultJson))
                    }
                } else {
                    consecutiveIdleChecks = 0
                }
            }

            previousFingerprint = currentFingerprint
        } catch (e: McpToolException) {
            if (e is McpToolException.PermissionDenied) throw e
            // Tree parse failures during transitions — reset idle counter
            consecutiveIdleChecks = 0
            previousFingerprint = null
        }

        delay(IDLE_CHECK_INTERVAL_MS)
    }

    val elapsed = SystemClock.elapsedRealtime() - startTime
    val resultJson =
        buildJsonObject {
            put("message", "Operation timed out after ${elapsed}ms waiting for UI idle. Retry if the operation is long-running.")
            put("elapsedMs", elapsed)
            put("similarity", lastSimilarity)
        }
    return McpToolUtils.textResult(Json.encodeToString(resultJson))
}
```

**Key changes from old to new**:
- `previousHash: Int?` → `previousFingerprint: IntArray?`
- `computeTreeHash(tree)` → `treeFingerprint.generate(tree)`
- Binary equality (`currentHash == previousHash`) → `treeFingerprint.compare() >= matchPercentage`
- New `match_percentage` parameter parsing with validation (0-100)
- New `lastSimilarity` variable tracking the last computed similarity
- Success response: added `"similarity"` field
- Timeout response: changed from plain string to JSON object with `"message"`, `"elapsedMs"` (actual elapsed time, consistent with success case), `"similarity"` fields
- Added `intOrNull` import (see Action 2.7)

#### Action 2.3: Delete `computeTreeHash()` method

Remove the entire `computeTreeHash` method (lines 379-395):

```diff
-        /**
-         * Computes a structural hash of the accessibility tree for change detection.
-         *
-         * Uses a recursive hash incorporating each node's class name, text, bounds,
-         * and child count. This is fast and sufficient for detecting structural changes.
-         */
-        private fun computeTreeHash(node: AccessibilityNodeData): Int {
-            var hash = HASH_SEED
-            hash = HASH_MULTIPLIER * hash + (node.className?.hashCode() ?: 0)
-            hash = HASH_MULTIPLIER * hash + (node.text?.hashCode() ?: 0)
-            hash = HASH_MULTIPLIER * hash + node.bounds.hashCode()
-            hash = HASH_MULTIPLIER * hash + node.children.size
-            for (child in node.children) {
-                hash = HASH_MULTIPLIER * hash + computeTreeHash(child)
-            }
-            return hash
-        }
```

#### Action 2.4: Update `register()` method — description and inputSchema

Replace the `register()` method (lines 397-413) to add the `match_percentage` parameter to the schema and update the description:

**Old**:
```kotlin
fun register(server: Server) {
    server.addTool(
        name = TOOL_NAME,
        description = "Wait for the UI to become idle (no changes detected)",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("timeout") {
                            put("type", "integer")
                            put("description", "Timeout in milliseconds (1-30000)")
                        }
                    },
                required = listOf("timeout"),
            ),
    ) { request -> execute(request.arguments) }
}
```

**New**:
```kotlin
fun register(server: Server) {
    server.addTool(
        name = TOOL_NAME,
        description = "Wait for the UI to become idle (similarity-based change detection)",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("timeout") {
                            put("type", "integer")
                            put("description", "Timeout in milliseconds (1-30000)")
                        }
                        putJsonObject("match_percentage") {
                            put("type", "integer")
                            put("description", "Similarity threshold percentage (0-100, default 100). 100 = exact match, lower values tolerate minor UI changes")
                            put("default", DEFAULT_MATCH_PERCENTAGE)
                        }
                    },
                required = listOf("timeout"),
            ),
    ) { request -> execute(request.arguments) }
}
```

**Key changes**:
- Tool description updated from `"Wait for the UI to become idle (no changes detected)"` to `"Wait for the UI to become idle (similarity-based change detection)"`
- Added `match_percentage` property to inputSchema (not in `required` — it has a default)

#### Action 2.5: Update companion object — remove hash constants, add match percentage default

**Old** (lines 415-425):
```kotlin
companion object {
    private const val TAG = "MCP:WaitForIdleTool"
    private const val TOOL_NAME = "wait_for_idle"
    private const val IDLE_CHECK_INTERVAL_MS = 500L
    private const val MAX_TIMEOUT_MS = 30000L
    private const val HASH_SEED = 17
    private const val HASH_MULTIPLIER = 31

    /** Number of consecutive identical tree hashes required to consider UI idle. */
    private const val REQUIRED_IDLE_CHECKS = 2
}
```

**New**:
```kotlin
companion object {
    private const val TAG = "MCP:WaitForIdleTool"
    private const val TOOL_NAME = "wait_for_idle"
    private const val IDLE_CHECK_INTERVAL_MS = 500L
    private const val MAX_TIMEOUT_MS = 30000L
    private const val DEFAULT_MATCH_PERCENTAGE = 100

    /** Number of consecutive checks meeting the similarity threshold required to consider UI idle. */
    private const val REQUIRED_IDLE_CHECKS = 2
}
```

**Key changes**:
- Removed `HASH_SEED` and `HASH_MULTIPLIER` (now in `TreeFingerprint`)
- Added `DEFAULT_MATCH_PERCENTAGE = 100`
- Updated KDoc on `REQUIRED_IDLE_CHECKS` to say "similarity threshold" instead of "identical tree hashes"

#### Action 2.6: Update class KDoc

Replace the class-level KDoc (lines 313-319):

**Old**:
```kotlin
/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by detecting when the accessibility tree
 * structure stops changing. Considers UI idle when two consecutive snapshots
 * (separated by [IDLE_CHECK_INTERVAL_MS]) produce the same structural hash.
 */
```

**New**:
```kotlin
/**
 * MCP tool: wait_for_idle
 *
 * Waits for the UI to become idle by comparing accessibility tree fingerprints
 * across consecutive snapshots. Uses a 256-slot histogram fingerprint and
 * normalized difference to compute a similarity percentage. Considers UI idle
 * when two consecutive snapshots (separated by [IDLE_CHECK_INTERVAL_MS]) meet
 * the [DEFAULT_MATCH_PERCENTAGE] threshold (or the caller-provided `match_percentage`).
 */
```

#### Action 2.7: Add `intOrNull` import

Add the missing import to the imports section at the top of `UtilityTools.kt`:

```diff
 import kotlinx.serialization.json.longOrNull
+import kotlinx.serialization.json.intOrNull
 import kotlinx.serialization.json.put
```

#### Action 2.8: Remove unused import

The `AccessibilityNodeData` import (line 8) is no longer needed in this file since `computeTreeHash` has been removed and `TreeFingerprint` handles its own import. Verify if any other code in this file uses `AccessibilityNodeData` directly — if `getFreshTree` returns `AccessibilityNodeData` and is called from this file, the import is still needed. Check before removing.

**Verification**: `WaitForIdleTool.execute()` calls `getFreshTree()` which returns `AccessibilityNodeData`, and `WaitForElementTool` also calls `getFreshTree()`. The `treeFingerprint.generate()` accepts `AccessibilityNodeData`. So `AccessibilityNodeData` IS still used in this file. **Do NOT remove this import.**

---

### Task 3: Create `TreeFingerprintTest` unit tests

**Goal**: Comprehensive unit tests for the `TreeFingerprint` class.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TreeFingerprintTest.kt` (NEW)

#### Acceptance Criteria
- [ ] All test cases pass
- [ ] Covers: identical trees, completely different trees, single-node change, empty children, deep trees
- [ ] Covers: `compare()` with mismatched array sizes
- [ ] Covers: `compare()` with two empty fingerprints (both all-zeros)
- [ ] Covers: `generate()` produces array of size 256
- [ ] Covers: same tree always produces same fingerprint (deterministic)
- [ ] No lint warnings/errors

#### Action 3.1: Create `TreeFingerprintTest.kt`

Create new file `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TreeFingerprintTest.kt`.

**Test cases to implement**:

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TreeFingerprint")
class TreeFingerprintTest {
    private val fingerprint = TreeFingerprint()

    // Helper to build an AccessibilityNodeData
    private fun node(
        className: String? = "android.widget.FrameLayout",
        text: String? = null,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
        children: List<AccessibilityNodeData> = emptyList(),
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = "node_test",
            className = className,
            text = text,
            bounds = bounds,
            children = children,
        )

    @Nested
    @DisplayName("generate")
    inner class GenerateTests {

        @Test
        fun `returns array of size FINGERPRINT_SIZE`() {
            // Arrange
            val root = node()

            // Act
            val result = fingerprint.generate(root)

            // Assert
            assertEquals(TreeFingerprint.FINGERPRINT_SIZE, result.size)
        }

        @Test
        fun `same tree produces identical fingerprint`() {
            // Arrange
            val root = node(text = "Hello", children = listOf(node(text = "Child")))

            // Act
            val first = fingerprint.generate(root)
            val second = fingerprint.generate(root)

            // Assert
            assertArrayEquals(first, second)
        }

        @Test
        fun `single node increments exactly one bucket`() {
            // Arrange
            val root = node()

            // Act
            val result = fingerprint.generate(root)

            // Assert
            assertEquals(1, result.sum())
        }

        @Test
        fun `parent with children increments one bucket per node`() {
            // Arrange
            val child1 = node(className = "android.widget.TextView", text = "A")
            val child2 = node(className = "android.widget.Button", text = "B")
            val root = node(children = listOf(child1, child2))

            // Act
            val result = fingerprint.generate(root)

            // Assert — 3 nodes total (root + 2 children)
            assertEquals(3, result.sum())
        }

        @Test
        fun `different trees produce different fingerprints`() {
            // Arrange
            val tree1 = node(text = "Hello")
            val tree2 = node(text = "World")

            // Act
            val fp1 = fingerprint.generate(tree1)
            val fp2 = fingerprint.generate(tree2)

            // Assert — they should differ (not strictly guaranteed but
            // extremely likely with different text)
            assertTrue(!fp1.contentEquals(fp2))
        }

        @Test
        fun `deep tree counts all descendants`() {
            // Arrange — depth 4: root -> child -> grandchild -> great-grandchild
            val greatGrandchild = node(text = "leaf")
            val grandchild = node(children = listOf(greatGrandchild))
            val child = node(children = listOf(grandchild))
            val root = node(children = listOf(child))

            // Act
            val result = fingerprint.generate(root)

            // Assert — 4 nodes total
            assertEquals(4, result.sum())
        }
    }

    @Nested
    @DisplayName("compare")
    inner class CompareTests {

        @Test
        fun `identical fingerprints return 100`() {
            // Arrange
            val root = node(text = "Hello", children = listOf(node(text = "Child")))
            val fp = fingerprint.generate(root)

            // Act
            val similarity = fingerprint.compare(fp, fp.copyOf())

            // Assert
            assertEquals(100, similarity)
        }

        @Test
        fun `two empty fingerprints return 100`() {
            // Arrange
            val a = IntArray(TreeFingerprint.FINGERPRINT_SIZE)
            val b = IntArray(TreeFingerprint.FINGERPRINT_SIZE)

            // Act
            val similarity = fingerprint.compare(a, b)

            // Assert
            assertEquals(100, similarity)
        }

        @Test
        fun `completely different fingerprints return low similarity`() {
            // Arrange — build two trees with very different content
            val tree1 = node(
                className = "android.widget.FrameLayout",
                text = "AAA",
                bounds = BoundsData(0, 0, 100, 100),
                children = List(10) { node(text = "item_$it") },
            )
            val tree2 = node(
                className = "android.widget.LinearLayout",
                text = "ZZZ",
                bounds = BoundsData(500, 500, 1000, 1000),
                children = List(10) { node(text = "other_${it + 100}") },
            )
            val fp1 = fingerprint.generate(tree1)
            val fp2 = fingerprint.generate(tree2)

            // Act
            val similarity = fingerprint.compare(fp1, fp2)

            // Assert — should be well below 100
            assertTrue(similarity < 100)
        }

        @Test
        fun `one node changed in large tree returns high similarity`() {
            // Arrange — 11 nodes, change text of one leaf
            val children = (0 until 10).map { node(text = "item_$it") }
            val tree1 = node(children = children)

            val modifiedChildren = children.toMutableList()
            modifiedChildren[5] = node(text = "modified_item")
            val tree2 = node(children = modifiedChildren)

            val fp1 = fingerprint.generate(tree1)
            val fp2 = fingerprint.generate(tree2)

            // Act
            val similarity = fingerprint.compare(fp1, fp2)

            // Assert — most nodes identical, similarity should be high but < 100
            assertTrue(similarity in 1 until 100, "Expected similarity between 1 and 99, got $similarity")
        }

        @Test
        fun `similarity is clamped to 0-100 range`() {
            // Arrange
            val root = node()
            val fp = fingerprint.generate(root)
            val empty = IntArray(TreeFingerprint.FINGERPRINT_SIZE)

            // Act
            val similarity = fingerprint.compare(fp, empty)

            // Assert
            assertTrue(similarity in 0..100)
        }

        @Test
        fun `throws for mismatched array size - first argument`() {
            // Arrange
            val wrongSize = IntArray(128)
            val correct = IntArray(TreeFingerprint.FINGERPRINT_SIZE)

            // Act & Assert
            assertThrows(IllegalArgumentException::class.java) {
                fingerprint.compare(wrongSize, correct)
            }
        }

        @Test
        fun `throws for mismatched array size - second argument`() {
            // Arrange
            val correct = IntArray(TreeFingerprint.FINGERPRINT_SIZE)
            val wrongSize = IntArray(512)

            // Act & Assert
            assertThrows(IllegalArgumentException::class.java) {
                fingerprint.compare(correct, wrongSize)
            }
        }

        @Test
        fun `compare is symmetric`() {
            // Arrange
            val tree1 = node(text = "A", children = listOf(node(text = "B")))
            val tree2 = node(text = "A", children = listOf(node(text = "C")))
            val fp1 = fingerprint.generate(tree1)
            val fp2 = fingerprint.generate(tree2)

            // Act
            val sim1 = fingerprint.compare(fp1, fp2)
            val sim2 = fingerprint.compare(fp2, fp1)

            // Assert
            assertEquals(sim1, sim2)
        }
    }
}
```

**Note**: The exact similarity values in assertions use ranges (e.g., `in 1 until 100`) rather than exact values because the bucket distribution depends on hash codes. The tests verify the properties (high similarity for minor changes, low for major changes, 100 for identical, symmetry) rather than exact percentages.

---

### Task 4: Update `WaitForIdleToolTests` in `UtilityToolsTest.kt`

**Goal**: Update existing tests and add new tests for `match_percentage` parameter.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityToolsTest.kt`

#### Acceptance Criteria
- [ ] Existing `detects idle when tree does not change` test updated to verify `similarity` in response
- [ ] New test: `match_percentage defaults to 100 when not provided`
- [ ] New test: `detects idle with match_percentage below 100 when tree has minor changes`
- [ ] New test: `throws error for match_percentage above 100`
- [ ] New test: `throws error for negative match_percentage`
- [ ] New test: `timeout response includes similarity field`
- [ ] All tests pass
- [ ] No lint warnings/errors

#### Action 4.1: Update existing `detects idle when tree does not change` test

**Old** (lines 285-295):
```kotlin
@Test
fun `detects idle when tree does not change`() =
    runTest {
        // Same tree returned each time -> idle detected
        val params = buildJsonObject { put("timeout", 5000) }

        val result = tool.execute(params)
        val text = extractTextContent(result)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
    }
```

**New**:
```kotlin
@Test
fun `detects idle when tree does not change`() =
    runTest {
        // Same tree returned each time -> idle detected
        val params = buildJsonObject { put("timeout", 5000) }

        val result = tool.execute(params)
        val text = extractTextContent(result)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
        assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
    }
```

**Key change**: Added assertion that `similarity` is 100 when tree does not change.

#### Action 4.2: Add new test cases to `WaitForIdleToolTests`

Add the following tests inside the `WaitForIdleToolTests` inner class, after the existing tests:

```kotlin
@Test
fun `match_percentage defaults to 100 when not provided`() =
    runTest {
        // Same tree each time -> idle at 100% similarity
        val params = buildJsonObject { put("timeout", 5000) }

        val result = tool.execute(params)
        val text = extractTextContent(result)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals(100, parsed["similarity"]?.jsonPrimitive?.int)
    }

@Test
fun `detects idle with match_percentage below 100 when tree has minor changes`() =
    runTest {
        mockkStatic(SystemClock::class)
        try {
            var clockMs = 0L
            every { SystemClock.elapsedRealtime() } answers { clockMs }

            // Return trees that differ slightly each time (1 node text changes out of 11 total)
            var callCount = 0
            every { mockAccessibilityServiceProvider.getRootNode() } returns mockk {
                every { recycle() } returns Unit
            }
            every { mockTreeParser.parseTree(any()) } answers {
                callCount++
                clockMs += 600L
                AccessibilityNodeData(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    visible = true,
                    children = (0 until 10).map { i ->
                        AccessibilityNodeData(
                            id = "node_$i",
                            className = "android.widget.TextView",
                            text = if (i == 0) "changing_$callCount" else "stable_$i",
                            bounds = BoundsData(0, i * 100, 1080, (i + 1) * 100),
                            visible = true,
                        )
                    },
                )
            }

            // With match_percentage=80, the minor change should still be considered idle
            val params = buildJsonObject {
                put("timeout", 10000)
                put("match_percentage", 80)
            }
            val result = tool.execute(params)
            val text = extractTextContent(result)
            val parsed = Json.parseToJsonElement(text).jsonObject
            assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("idle") == true)
            val similarity = parsed["similarity"]?.jsonPrimitive?.int ?: 0
            assertTrue(similarity in 80..100, "Expected similarity between 80 and 100, got $similarity")
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

@Test
fun `throws error for match_percentage above 100`() =
    runTest {
        val params = buildJsonObject {
            put("timeout", 5000)
            put("match_percentage", 101)
        }

        assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
    }

@Test
fun `throws error for negative match_percentage`() =
    runTest {
        val params = buildJsonObject {
            put("timeout", 5000)
            put("match_percentage", -1)
        }

        assertThrows<McpToolException.InvalidParams> { tool.execute(params) }
    }

@Test
fun `timeout response includes similarity field`() =
    runTest {
        mockkStatic(SystemClock::class)
        try {
            var clockMs = 0L
            every { SystemClock.elapsedRealtime() } answers { clockMs }

            // Return different trees each time to force timeout
            var callCount = 0
            every { mockAccessibilityServiceProvider.getRootNode() } returns mockk {
                every { recycle() } returns Unit
            }
            every { mockTreeParser.parseTree(any()) } answers {
                callCount++
                clockMs += 600L
                AccessibilityNodeData(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    text = "changing_$callCount",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    visible = true,
                )
            }

            val params = buildJsonObject { put("timeout", 1000) }
            val result = tool.execute(params)
            val text = extractTextContent(result)
            val parsed = Json.parseToJsonElement(text).jsonObject
            assertTrue(parsed.containsKey("similarity"))
            assertTrue(parsed.containsKey("elapsedMs"))
            assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("timed out") == true)
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }
```

**Note**: The `int` extension on `jsonPrimitive` requires import `kotlinx.serialization.json.int` — add this import if not already present at the top of the test file.

---

### Task 5: Update `ErrorHandlingIntegrationTest` for `wait_for_idle`

**Goal**: Update the integration test to verify the new timeout response format (JSON with `similarity` field).

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ErrorHandlingIntegrationTest.kt`

#### Acceptance Criteria
- [ ] Test updated to parse JSON response instead of plain string
- [ ] Asserts `similarity` field is present in timeout response
- [ ] Asserts `message` field contains "timed out"
- [ ] Test passes
- [ ] No lint warnings/errors

#### Action 5.1: Update `wait_for_idle timeout returns informational non-error result` test

**Old** (lines 194-234):
```kotlin
@Test
fun `wait_for_idle timeout returns informational non-error result`() =
    runTest {
        mockkStatic(SystemClock::class)
        try {
            var clockMs = 0L
            every { SystemClock.elapsedRealtime() } answers { clockMs }

            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockRootNode = mockk<AccessibilityNodeInfo>()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            every { deps.accessibilityServiceProvider.getRootNode() } returns mockRootNode
            every { mockRootNode.recycle() } returns Unit

            var callCount = 0
            every { deps.treeParser.parseTree(mockRootNode) } answers {
                callCount++
                clockMs += 600L
                AccessibilityNodeData(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    text = "changing_text_$callCount",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    visible = true,
                )
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "wait_for_idle",
                        arguments = mapOf("timeout" to 1000),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("timed out"))
            }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }
```

**New** — change only the assertions inside the `withTestApplication` block:
```kotlin
McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
    val result =
        client.callTool(
            name = "wait_for_idle",
            arguments = mapOf("timeout" to 1000),
        )
    assertNotEquals(true, result.isError)
    val text = (result.content[0] as TextContent).text
    val parsed = Json.parseToJsonElement(text).jsonObject
    assertTrue(parsed["message"]?.jsonPrimitive?.content?.contains("timed out") == true)
    assertTrue(parsed.containsKey("similarity"))
    assertTrue(parsed.containsKey("elapsedMs"))
}
```

**Required additional imports** at the top of the file (add if not present):
```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

---

### Task 6: Update `MCP_TOOLS.md` documentation

**Goal**: Update the `wait_for_idle` documentation to reflect the new `match_percentage` parameter and `similarity` response field.

**File**: `docs/MCP_TOOLS.md`

#### Acceptance Criteria
- [ ] `wait_for_idle` input schema updated with `match_percentage` parameter
- [ ] Description updated to mention similarity-based comparison
- [ ] Response examples updated to include `similarity` field
- [ ] Timeout behavior section updated
- [ ] No Markdown formatting issues

#### Action 6.1: Update `wait_for_idle` section (lines 1584-1626)

Replace the entire `### wait_for_idle` section with:

````markdown
### `wait_for_idle`

Wait for the UI to become idle by comparing accessibility tree fingerprints using similarity-based change detection. Generates a 256-slot histogram fingerprint of the tree structure and uses normalized difference to compute a similarity percentage. Considers UI idle when two consecutive snapshots (500ms apart) meet the `match_percentage` threshold.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "timeout": {
      "type": "integer",
      "description": "Timeout in milliseconds (1-30000). Required."
    },
    "match_percentage": {
      "type": "integer",
      "description": "Similarity threshold percentage (0-100, default 100). 100 = exact match, lower values tolerate minor UI changes",
      "default": 100
    }
  },
  "required": ["timeout"]
}
```

**Output** (on success): JSON string:
```json
{
  "message": "UI is idle",
  "elapsedMs": 1500,
  "similarity": 100
}
```

**Timeout behavior**: When the timeout expires without the UI becoming idle, a **non-error** `CallToolResult` is returned with a JSON object containing the last computed similarity:
```json
{
  "message": "Operation timed out after 3000ms waiting for UI idle. Retry if the operation is long-running.",
  "elapsedMs": 3000,
  "similarity": 85
}
```
This is not a tool error — the caller should check the `message` field.

**Example** (exact match, default behavior):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "wait_for_idle", "arguments": { "timeout": 3000 } }
  }'
```

**Example** (tolerate minor UI changes):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "wait_for_idle", "arguments": { "timeout": 5000, "match_percentage": 95 } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- Timeout out of range (1-30000) or missing
- `match_percentage` out of range (0-100)
- Accessibility service not enabled
````

---

### Task 7: Run linting and build

**Goal**: Verify all code passes quality gates.

#### Acceptance Criteria
- [ ] `./gradlew ktlintCheck` passes with no warnings/errors
- [ ] `./gradlew detekt` passes with no warnings/errors
- [ ] `./gradlew build` succeeds with no warnings/errors

#### Action 7.1: Run ktlintCheck
```bash
./gradlew ktlintCheck
```
Fix any issues found.

#### Action 7.2: Run detekt
```bash
./gradlew detekt
```
Fix any issues found.

#### Action 7.3: Run full build (includes all tests)
```bash
./gradlew build
```
Fix any issues found.

---

### Task 8: Final verification — double-check everything from the ground up

**Goal**: Re-read all changed files and verify correctness, consistency, and alignment with what was discussed.

#### Acceptance Criteria
- [ ] `TreeFingerprint.kt` — `generate()` uses `className`, `text`, `bounds`, `children.size` (the 4 agreed properties)
- [ ] `TreeFingerprint.kt` — `generate()` uses `hashCode()` → `% 256` to map to buckets
- [ ] `TreeFingerprint.kt` — `compare()` uses normalized difference formula with float arithmetic: `(100f - (diffSum.toFloat() * 100f / (2f * totalNodes.toFloat()))).toInt()`
- [ ] `TreeFingerprint.kt` — `compare()` does NOT use cosine similarity
- [ ] `TreeFingerprint.kt` — fingerprint size is 256
- [ ] `TreeFingerprint.kt` — `compare()` returns integer 0-100
- [ ] `TreeFingerprint.kt` — two empty fingerprints return 100
- [ ] `WaitForIdleTool` — `match_percentage` is integer, 0-100, default 100
- [ ] `WaitForIdleTool` — 2 consecutive checks meeting threshold required (not 1)
- [ ] `WaitForIdleTool` — `computeTreeHash` fully removed
- [ ] `WaitForIdleTool` — `HASH_SEED` and `HASH_MULTIPLIER` removed from companion object
- [ ] `WaitForIdleTool` — success response includes `similarity` (integer)
- [ ] `WaitForIdleTool` — timeout response includes `similarity` (integer, last computed value)
- [ ] `WaitForIdleTool` — timeout response is now JSON (not plain string)
- [ ] `WaitForIdleTool` — tool description updated
- [ ] `WaitForIdleTool` — inputSchema includes `match_percentage` with `default: 100`
- [ ] `WaitForIdleTool` — `match_percentage` is NOT in `required` list
- [ ] `TreeFingerprintTest.kt` — covers identical trees, different trees, single-node change, empty fingerprints, mismatched sizes, symmetry, deep trees
- [ ] `UtilityToolsTest.kt` — existing test updated for `similarity` field
- [ ] `UtilityToolsTest.kt` — new tests for `match_percentage` validation
- [ ] `UtilityToolsTest.kt` — timeout response test verifies JSON with `similarity`
- [ ] `ErrorHandlingIntegrationTest.kt` — updated to parse JSON timeout response
- [ ] `MCP_TOOLS.md` — `wait_for_idle` section fully updated
- [ ] No references to cosine similarity anywhere in code
- [ ] No TODOs, no commented-out code, no dead code
- [ ] All tests pass
- [ ] All linting passes
- [ ] Build succeeds
