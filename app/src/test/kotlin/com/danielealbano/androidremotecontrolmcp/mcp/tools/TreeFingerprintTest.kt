@file:Suppress("DEPRECATION")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            val tree1 =
                node(
                    className = "android.widget.FrameLayout",
                    text = "AAA",
                    bounds = BoundsData(0, 0, 100, 100),
                    children = List(10) { node(text = "item_$it") },
                )
            val tree2 =
                node(
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

    @Nested
    @DisplayName("generate(windows)")
    inner class GenerateWindowsTests {
        private fun windowData(
            windowId: Int = 0,
            tree: AccessibilityNodeData,
        ): WindowData =
            WindowData(
                windowId = windowId,
                windowType = "APPLICATION",
                tree = tree,
                focused = windowId == 0,
            )

        @Test
        fun `returns array of size FINGERPRINT_SIZE`() {
            val windows = listOf(windowData(tree = node()))
            val result = fingerprint.generate(windows)
            assertEquals(TreeFingerprint.FINGERPRINT_SIZE, result.size)
        }

        @Test
        fun `same windows produce identical fingerprint`() {
            val tree = node(text = "Hello", children = listOf(node(text = "Child")))
            val windows = listOf(windowData(tree = tree))
            val first = fingerprint.generate(windows)
            val second = fingerprint.generate(windows)
            assertArrayEquals(first, second)
        }

        @Test
        fun `different windows produce different fingerprints`() {
            val windows1 = listOf(windowData(tree = node(text = "Hello")))
            val windows2 = listOf(windowData(tree = node(text = "World")))
            val fp1 = fingerprint.generate(windows1)
            val fp2 = fingerprint.generate(windows2)
            assertFalse(fp1.contentEquals(fp2))
        }

        @Test
        fun `single window matches single tree fingerprint`() {
            val tree = node(text = "Hello", children = listOf(node(text = "Child")))
            val singleTreeFp = fingerprint.generate(tree)
            val windowsFp = fingerprint.generate(listOf(windowData(tree = tree)))
            assertArrayEquals(singleTreeFp, windowsFp)
        }

        @Test
        fun `two windows combine fingerprints from both trees`() {
            val tree1 = node(text = "A")
            val tree2 = node(text = "B")
            val singleFp1 = fingerprint.generate(tree1)
            val singleFp2 = fingerprint.generate(tree2)

            val combinedFp =
                fingerprint.generate(
                    listOf(windowData(windowId = 0, tree = tree1), windowData(windowId = 1, tree = tree2)),
                )

            // Combined should have sum == sum(fp1) + sum(fp2)
            assertEquals(singleFp1.sum() + singleFp2.sum(), combinedFp.sum())
        }

        @Test
        fun `window appearing changes fingerprint`() {
            val tree1 = node(text = "App", children = listOf(node(text = "Content")))
            val tree2 = node(text = "Dialog")

            val fpBefore = fingerprint.generate(listOf(windowData(windowId = 0, tree = tree1)))
            val fpAfter =
                fingerprint.generate(
                    listOf(windowData(windowId = 0, tree = tree1), windowData(windowId = 1, tree = tree2)),
                )

            assertFalse(fpBefore.contentEquals(fpAfter))
        }
    }

    @Nested
    @DisplayName("generateFromRawNodes")
    inner class GenerateFromRawNodesTests {
        private fun rawNode(
            className: String? = "android.widget.FrameLayout",
            text: String? = null,
            bounds: BoundsData = BoundsData(0, 0, 100, 100),
            children: List<AccessibilityNodeInfo> = emptyList(),
        ): AccessibilityNodeInfo {
            val mock = mockk<AccessibilityNodeInfo>()
            every { mock.className } returns className
            every { mock.text } returns text
            val rectSlot = slot<Rect>()
            every { mock.getBoundsInScreen(capture(rectSlot)) } answers {
                rectSlot.captured.left = bounds.left
                rectSlot.captured.top = bounds.top
                rectSlot.captured.right = bounds.right
                rectSlot.captured.bottom = bounds.bottom
            }
            every { mock.childCount } returns children.size
            for (i in children.indices) {
                every { mock.getChild(i) } returns children[i]
            }
            return mock
        }

        @Test
        fun `returns array of size FINGERPRINT_SIZE`() {
            val root = rawNode()
            val result = fingerprint.generateFromRawNodes(listOf(root))
            assertEquals(TreeFingerprint.FINGERPRINT_SIZE, result.size)
        }

        @Test
        fun `same raw tree produces identical fingerprint`() {
            val root = rawNode(text = "Hello")
            val first = fingerprint.generateFromRawNodes(listOf(root))
            val second = fingerprint.generateFromRawNodes(listOf(root))
            assertArrayEquals(first, second)
        }

        @Test
        fun `single node increments exactly one bucket`() {
            val root = rawNode()
            val result = fingerprint.generateFromRawNodes(listOf(root))
            assertEquals(1, result.sum())
        }

        @Test
        fun `parent with children increments one bucket per node`() {
            val child1 = rawNode(className = "android.widget.TextView", text = "A")
            val child2 = rawNode(className = "android.widget.Button", text = "B")
            val root = rawNode(children = listOf(child1, child2))
            val result = fingerprint.generateFromRawNodes(listOf(root))
            assertEquals(3, result.sum())
        }

        @Test
        fun `different raw trees produce different fingerprints`() {
            val tree1 = rawNode(text = "Hello")
            val tree2 = rawNode(text = "World")
            val fp1 = fingerprint.generateFromRawNodes(listOf(tree1))
            val fp2 = fingerprint.generateFromRawNodes(listOf(tree2))
            assertFalse(fp1.contentEquals(fp2))
        }

        @Test
        fun `deep tree counts all descendants`() {
            val greatGrandchild = rawNode(text = "leaf")
            val grandchild = rawNode(children = listOf(greatGrandchild))
            val child = rawNode(children = listOf(grandchild))
            val root = rawNode(children = listOf(child))
            val result = fingerprint.generateFromRawNodes(listOf(root))
            assertEquals(4, result.sum())
        }

        @Test
        fun `multiple root nodes combine fingerprints`() {
            val root1 = rawNode(text = "A")
            val root2 = rawNode(text = "B")
            val fp1 = fingerprint.generateFromRawNodes(listOf(root1))
            val fp2 = fingerprint.generateFromRawNodes(listOf(root2))
            val combined = fingerprint.generateFromRawNodes(listOf(root1, root2))
            assertEquals(fp1.sum() + fp2.sum(), combined.sum())
        }

        @Test
        fun `matches parsed tree fingerprint for equivalent structure`() {
            // Build equivalent trees: raw AccessibilityNodeInfo and parsed AccessibilityNodeData
            val bounds = BoundsData(10, 20, 300, 400)
            val rawChild =
                rawNode(
                    className = "android.widget.TextView",
                    text = "Child",
                    bounds = bounds,
                )
            val rawRoot =
                rawNode(
                    className = "android.widget.FrameLayout",
                    text = "Root",
                    bounds = bounds,
                    children = listOf(rawChild),
                )

            val parsedChild =
                node(
                    className = "android.widget.TextView",
                    text = "Child",
                    bounds = bounds,
                )
            val parsedRoot =
                node(
                    className = "android.widget.FrameLayout",
                    text = "Root",
                    bounds = bounds,
                    children = listOf(parsedChild),
                )

            val rawFp = fingerprint.generateFromRawNodes(listOf(rawRoot))
            val parsedFp = fingerprint.generate(parsedRoot)

            // Both paths should produce identical fingerprints for the same data
            assertArrayEquals(parsedFp, rawFp)
        }
    }
}
