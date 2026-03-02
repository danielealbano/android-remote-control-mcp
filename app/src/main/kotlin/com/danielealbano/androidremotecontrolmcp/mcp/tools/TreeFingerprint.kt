package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
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
     * Generates a combined [FINGERPRINT_SIZE]-slot histogram fingerprint from all
     * windows' accessibility trees.
     *
     * @param windows the list of [WindowData] whose trees to fingerprint
     * @return an [IntArray] of size [FINGERPRINT_SIZE] representing the combined fingerprint
     */
    fun generate(windows: List<WindowData>): IntArray {
        val fingerprint = IntArray(FINGERPRINT_SIZE)
        for (windowData in windows) {
            populateFingerprint(windowData.tree, fingerprint)
        }
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
    fun compare(
        a: IntArray,
        b: IntArray,
    ): Int {
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

        val similarity =
            (
                FULL_MATCH_PERCENTAGE.toFloat() -
                    (
                        diffSum.toFloat() * FULL_MATCH_PERCENTAGE.toFloat() /
                            (2f * totalNodes.toFloat())
                    )
            ).toInt()
        return similarity.coerceIn(0, FULL_MATCH_PERCENTAGE)
    }

    /**
     * Generates a combined [FINGERPRINT_SIZE]-slot histogram fingerprint by walking
     * raw [AccessibilityNodeInfo] trees directly, without creating intermediate
     * [AccessibilityNodeData] objects or populating any cache.
     *
     * This is significantly faster than [generate] for use cases that only need
     * fingerprint comparison (e.g., idle detection) because it skips node ID
     * generation, property extraction for unused fields, and cache population.
     *
     * Child nodes obtained via [AccessibilityNodeInfo.getChild] are not recycled
     * (safe on API 33+ where recycle() is a no-op).
     *
     * @param rootNodes the root [AccessibilityNodeInfo] for each on-screen window
     * @return an [IntArray] of size [FINGERPRINT_SIZE] representing the combined fingerprint
     */
    fun generateFromRawNodes(rootNodes: List<AccessibilityNodeInfo>): IntArray {
        val fingerprint = IntArray(FINGERPRINT_SIZE)
        val rect = Rect()
        for (rootNode in rootNodes) {
            populateFingerprintFromRaw(rootNode, fingerprint, 0, rect)
        }
        return fingerprint
    }

    private fun populateFingerprint(
        node: AccessibilityNodeData,
        fingerprint: IntArray,
    ) {
        val hash = computeNodeHash(node)
        val index = (hash and INDEX_MASK) % FINGERPRINT_SIZE
        fingerprint[index]++

        for (child in node.children) {
            populateFingerprint(child, fingerprint)
        }
    }

    private fun populateFingerprintFromRaw(
        node: AccessibilityNodeInfo,
        fingerprint: IntArray,
        depth: Int,
        rect: Rect,
    ) {
        node.getBoundsInScreen(rect)
        val childCount = node.childCount

        var hash = HASH_SEED
        hash = HASH_MULTIPLIER * hash + (node.className?.hashCode() ?: 0)
        hash = HASH_MULTIPLIER * hash + (node.text?.hashCode() ?: 0)
        // Compute bounds hash matching BoundsData.hashCode() (Kotlin data class)
        var boundsHash = rect.left
        boundsHash = HASH_MULTIPLIER * boundsHash + rect.top
        boundsHash = HASH_MULTIPLIER * boundsHash + rect.right
        boundsHash = HASH_MULTIPLIER * boundsHash + rect.bottom
        hash = HASH_MULTIPLIER * hash + boundsHash
        hash = HASH_MULTIPLIER * hash + childCount

        val index = (hash and INDEX_MASK) % FINGERPRINT_SIZE
        fingerprint[index]++

        if (depth < MAX_RAW_TREE_DEPTH) {
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                populateFingerprintFromRaw(child, fingerprint, depth + 1, rect)
            }
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

        /** Maximum tree depth for raw node traversal (matches AccessibilityTreeParser.MAX_TREE_DEPTH). */
        private const val MAX_RAW_TREE_DEPTH = 100
    }
}
