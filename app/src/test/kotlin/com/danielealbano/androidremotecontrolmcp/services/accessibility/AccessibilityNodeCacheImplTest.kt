package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AccessibilityNodeCacheImpl")
class AccessibilityNodeCacheImplTest {
    private lateinit var cache: AccessibilityNodeCacheImpl

    @BeforeEach
    fun setUp() {
        cache = AccessibilityNodeCacheImpl()
    }

    private fun mockNode(): AccessibilityNodeInfo = mockk(relaxed = true)

    @Nested
    @DisplayName("populate")
    inner class Populate {
        @Test
        @DisplayName("replaces cache without recycling old entries")
        fun `populate replaces cache without recycling old entries`() {
            val nodeA = mockNode()
            val entriesA = mapOf("a" to CachedNode(nodeA, depth = 0, index = 0, parentId = "root"))

            val nodeB = mockNode()
            val entriesB = mapOf("b" to CachedNode(nodeB, depth = 1, index = 0, parentId = "root"))

            cache.populate(entriesA)
            cache.populate(entriesB)

            verify(exactly = 0) { nodeA.recycle() }

            val result = cache.get("b")
            assertNotNull(result)
            assertEquals(nodeB, result!!.node)
            assertEquals(1, result.depth)
            assertEquals(0, result.index)
            assertEquals("root", result.parentId)

            assertNull(cache.get("a"))
        }

        @Test
        @DisplayName("with empty map clears cache without recycling")
        fun `populate with empty map clears cache without recycling`() {
            val node = mockNode()
            val entries = mapOf("x" to CachedNode(node, depth = 0, index = 0, parentId = "root"))

            cache.populate(entries)
            cache.populate(emptyMap())

            verify(exactly = 0) { node.recycle() }
            assertEquals(0, cache.size())
        }
    }

    @Nested
    @DisplayName("get")
    inner class Get {
        @Test
        @DisplayName("returns cached node with metadata")
        fun `get returns cached node with metadata`() {
            val node = mockNode()
            val entries =
                mapOf(
                    "node1" to CachedNode(node, depth = 2, index = 1, parentId = "parent"),
                )

            cache.populate(entries)

            val result = cache.get("node1")
            assertNotNull(result)
            assertEquals(node, result!!.node)
            assertEquals(2, result.depth)
            assertEquals(1, result.index)
            assertEquals("parent", result.parentId)
        }

        @Test
        @DisplayName("returns null for unknown nodeId")
        fun `get returns null for unknown nodeId`() {
            val node = mockNode()
            cache.populate(mapOf("known" to CachedNode(node, depth = 0, index = 0, parentId = "root")))

            assertNull(cache.get("unknown"))
        }
    }

    @Nested
    @DisplayName("clear")
    inner class Clear {
        @Test
        @DisplayName("removes all entries and recycles them")
        fun `clear removes all entries and recycles them`() {
            val node1 = mockNode()
            val node2 = mockNode()
            val entries =
                mapOf(
                    "a" to CachedNode(node1, depth = 0, index = 0, parentId = "root"),
                    "b" to CachedNode(node2, depth = 0, index = 1, parentId = "root"),
                )

            cache.populate(entries)
            cache.clear()

            assertEquals(0, cache.size())
            verify(exactly = 1) { node1.recycle() }
            verify(exactly = 1) { node2.recycle() }
        }

        @Test
        @DisplayName("after populate recycles only current entries")
        fun `clear after populate recycles only current entries`() {
            val nodeA = mockNode()
            val entriesA = mapOf("a" to CachedNode(nodeA, depth = 0, index = 0, parentId = "root"))

            val nodeB = mockNode()
            val entriesB = mapOf("b" to CachedNode(nodeB, depth = 1, index = 0, parentId = "root"))

            cache.populate(entriesA)
            cache.populate(entriesB)
            cache.clear()

            verify(exactly = 0) { nodeA.recycle() }
            verify(exactly = 1) { nodeB.recycle() }
        }
    }

    @Nested
    @DisplayName("size")
    inner class Size {
        @Test
        @DisplayName("returns correct count")
        fun `size returns correct count`() {
            assertEquals(0, cache.size())

            val entries =
                mapOf(
                    "a" to CachedNode(mockNode(), depth = 0, index = 0, parentId = "root"),
                    "b" to CachedNode(mockNode(), depth = 0, index = 1, parentId = "root"),
                    "c" to CachedNode(mockNode(), depth = 0, index = 2, parentId = "root"),
                )
            cache.populate(entries)
            assertEquals(3, cache.size())

            cache.clear()
            assertEquals(0, cache.size())
        }
    }
}
