package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AccessibilityTreeParser")
class AccessibilityTreeParserTest {
    private lateinit var parser: AccessibilityTreeParser

    @BeforeEach
    fun setUp() {
        parser = AccessibilityTreeParser()
    }

    @Suppress("LongParameterList")
    private fun createMockNode(
        className: String? = "android.widget.TextView",
        text: CharSequence? = null,
        contentDescription: CharSequence? = null,
        resourceId: String? = null,
        boundsLeft: Int = 0,
        boundsTop: Int = 0,
        boundsRight: Int = 100,
        boundsBottom: Int = 50,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visibleToUser: Boolean = true,
        childCount: Int = 0,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.className } returns className
        every { node.text } returns text
        every { node.contentDescription } returns contentDescription
        every { node.viewIdResourceName } returns resourceId
        every { node.isClickable } returns clickable
        every { node.isLongClickable } returns longClickable
        every { node.isFocusable } returns focusable
        every { node.isScrollable } returns scrollable
        every { node.isEditable } returns editable
        every { node.isEnabled } returns enabled
        every { node.isVisibleToUser } returns visibleToUser
        every { node.childCount } returns childCount

        val rectSlot = slot<Rect>()
        every { node.getBoundsInScreen(capture(rectSlot)) } answers {
            rectSlot.captured.left = boundsLeft
            rectSlot.captured.top = boundsTop
            rectSlot.captured.right = boundsRight
            rectSlot.captured.bottom = boundsBottom
        }

        for (i in children.indices) {
            every { node.getChild(i) } returns children[i]
        }

        @Suppress("DEPRECATION")
        every { node.recycle() } just runs

        return node
    }

    @Nested
    @DisplayName("parseTree")
    inner class ParseTree {
        @Test
        @DisplayName("parses single node with all properties")
        fun parsesSingleNodeWithAllProperties() {
            // Arrange
            val node =
                createMockNode(
                    className = "android.widget.Button",
                    text = "Click me",
                    contentDescription = "Action button",
                    resourceId = "com.example:id/btn_action",
                    boundsLeft = 10,
                    boundsTop = 20,
                    boundsRight = 200,
                    boundsBottom = 80,
                    clickable = true,
                    longClickable = true,
                    focusable = true,
                    scrollable = false,
                    editable = false,
                    enabled = true,
                    visibleToUser = true,
                )

            // Act
            val result = parser.parseTree(node)

            // Assert
            assertNotNull(result.id)
            assertTrue(result.id.startsWith("node_"))
            assertEquals("android.widget.Button", result.className)
            assertEquals("Click me", result.text)
            assertEquals("Action button", result.contentDescription)
            assertEquals("com.example:id/btn_action", result.resourceId)
            assertEquals(BoundsData(10, 20, 200, 80), result.bounds)
            assertTrue(result.clickable)
            assertTrue(result.longClickable)
            assertTrue(result.focusable)
            assertFalse(result.scrollable)
            assertFalse(result.editable)
            assertTrue(result.enabled)
            assertTrue(result.visible)
            assertTrue(result.children.isEmpty())
        }

        @Test
        @DisplayName("parses node with null text and contentDescription")
        fun parsesNodeWithNullTextAndContentDescription() {
            // Arrange
            val node =
                createMockNode(
                    text = null,
                    contentDescription = null,
                    resourceId = null,
                )

            // Act
            val result = parser.parseTree(node)

            // Assert
            assertNull(result.text)
            assertNull(result.contentDescription)
            assertNull(result.resourceId)
        }

        @Test
        @DisplayName("parses nested tree with parent and children")
        fun parsesNestedTreeWithParentAndChildren() {
            // Arrange
            val child1 =
                createMockNode(
                    className = "android.widget.Button",
                    text = "Button 1",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 100,
                    boundsBottom = 50,
                    clickable = true,
                )
            val child2 =
                createMockNode(
                    className = "android.widget.Button",
                    text = "Button 2",
                    boundsLeft = 100,
                    boundsTop = 0,
                    boundsRight = 200,
                    boundsBottom = 50,
                    clickable = true,
                )
            val parent =
                createMockNode(
                    className = "android.widget.LinearLayout",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 200,
                    boundsBottom = 50,
                    childCount = 2,
                    children = listOf(child1, child2),
                )

            // Act
            val result = parser.parseTree(parent)

            // Assert
            assertEquals("android.widget.LinearLayout", result.className)
            assertEquals(2, result.children.size)
            assertEquals("Button 1", result.children[0].text)
            assertEquals("Button 2", result.children[1].text)
            assertTrue(result.children[0].clickable)
            assertTrue(result.children[1].clickable)
        }

        @Test
        @DisplayName("parses deep tree with 3 levels")
        fun parsesDeepTreeWith3Levels() {
            // Arrange
            val grandchild =
                createMockNode(
                    className = "android.widget.TextView",
                    text = "Deep text",
                )
            val child =
                createMockNode(
                    className = "android.widget.FrameLayout",
                    childCount = 1,
                    children = listOf(grandchild),
                )
            val root =
                createMockNode(
                    className = "android.widget.LinearLayout",
                    childCount = 1,
                    children = listOf(child),
                )

            // Act
            val result = parser.parseTree(root)

            // Assert
            assertEquals(1, result.children.size)
            assertEquals(1, result.children[0].children.size)
            assertEquals("Deep text", result.children[0].children[0].text)
        }

        @Test
        @DisplayName("does not recycle root node")
        fun doesNotRecycleRootNode() {
            // Arrange
            val node = createMockNode()

            // Act
            parser.parseTree(node)

            // Assert
            @Suppress("DEPRECATION")
            verify(exactly = 0) { node.recycle() }
        }

        @Test
        @DisplayName("recycles child nodes after parsing")
        fun recyclesChildNodesAfterParsing() {
            // Arrange
            val child = createMockNode(text = "Child")
            val parent =
                createMockNode(
                    childCount = 1,
                    children = listOf(child),
                )

            // Act
            parser.parseTree(parent)

            // Assert
            @Suppress("DEPRECATION")
            verify(exactly = 1) { child.recycle() }
        }
    }

    @Nested
    @DisplayName("isNodeVisible")
    inner class IsNodeVisible {
        @Test
        @DisplayName("returns true for visible node")
        fun returnsTrueForVisibleNode() {
            // Arrange
            val node = createMockNode(visibleToUser = true)

            // Act & Assert
            assertTrue(parser.isNodeVisible(node))
        }

        @Test
        @DisplayName("returns false for invisible node")
        fun returnsFalseForInvisibleNode() {
            // Arrange
            val node = createMockNode(visibleToUser = false)

            // Act & Assert
            assertFalse(parser.isNodeVisible(node))
        }
    }

    @Nested
    @DisplayName("generateNodeId")
    inner class GenerateNodeId {
        @Test
        @DisplayName("generates stable IDs for same input")
        fun generatesStableIdsForSameInput() {
            // Arrange
            val node =
                createMockNode(
                    className = "android.widget.Button",
                    resourceId = "com.example:id/button",
                )
            val bounds = BoundsData(10, 20, 100, 80)

            // Act
            val id1 = parser.generateNodeId(node, bounds, 0, 0, "root")
            val id2 = parser.generateNodeId(node, bounds, 0, 0, "root")

            // Assert
            assertEquals(id1, id2)
        }

        @Test
        @DisplayName("generates different IDs for different positions")
        fun generatesDifferentIdsForDifferentPositions() {
            // Arrange
            val node =
                createMockNode(
                    className = "android.widget.Button",
                    resourceId = "com.example:id/button",
                )
            val bounds = BoundsData(10, 20, 100, 80)

            // Act
            val id1 = parser.generateNodeId(node, bounds, 0, 0, "root")
            val id2 = parser.generateNodeId(node, bounds, 0, 1, "root")

            // Assert
            assertTrue(id1 != id2)
        }

        @Test
        @DisplayName("generates IDs with node_ prefix")
        fun generatesIdsWithNodePrefix() {
            // Arrange
            val node = createMockNode()
            val bounds = BoundsData(0, 0, 100, 50)

            // Act
            val id = parser.generateNodeId(node, bounds, 0, 0, "root")

            // Assert
            assertTrue(id.startsWith("node_"))
        }
    }

    @Nested
    @DisplayName("parseTree with nodeMap")
    inner class ParseTreeWithNodeMap {
        @Test
        @DisplayName("does not recycle children when nodeMap is provided")
        fun parseTreeWithNodeMapDoesNotRecycleChildren() {
            // Arrange
            val child1 = createMockNode(text = "Child 1")
            val child2 = createMockNode(text = "Child 2")
            val root =
                createMockNode(
                    className = "android.widget.LinearLayout",
                    childCount = 2,
                    children = listOf(child1, child2),
                )
            val nodeMap = mutableMapOf<String, CachedNode>()

            // Act
            parser.parseTree(root, nodeMap = nodeMap)

            // Assert
            @Suppress("DEPRECATION")
            verify(exactly = 0) { child1.recycle() }
            @Suppress("DEPRECATION")
            verify(exactly = 0) { child2.recycle() }
            assertEquals(3, nodeMap.size)
        }

        @Test
        @DisplayName("populates nodeMap with correct metadata")
        fun parseTreePopulatesNodeMap() {
            // Arrange
            val child1 =
                createMockNode(
                    className = "android.widget.Button",
                    text = "Button 1",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 100,
                    boundsBottom = 50,
                )
            val child2 =
                createMockNode(
                    className = "android.widget.Button",
                    text = "Button 2",
                    boundsLeft = 100,
                    boundsTop = 0,
                    boundsRight = 200,
                    boundsBottom = 50,
                )
            val root =
                createMockNode(
                    className = "android.widget.LinearLayout",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 200,
                    boundsBottom = 50,
                    childCount = 2,
                    children = listOf(child1, child2),
                )
            val nodeMap = mutableMapOf<String, CachedNode>()

            // Act
            val result = parser.parseTree(root, nodeMap = nodeMap)

            // Assert
            assertEquals(3, nodeMap.size)

            val rootNodeId = result.id
            val child1NodeId = result.children[0].id
            val child2NodeId = result.children[1].id

            assertTrue(nodeMap.containsKey(rootNodeId))
            assertTrue(nodeMap.containsKey(child1NodeId))
            assertTrue(nodeMap.containsKey(child2NodeId))

            val rootCached = nodeMap[rootNodeId]!!
            assertEquals(root, rootCached.node)
            assertEquals(0, rootCached.depth)
            assertEquals(0, rootCached.index)
            assertEquals("root", rootCached.parentId)

            val child1Cached = nodeMap[child1NodeId]!!
            assertEquals(child1, child1Cached.node)
            assertEquals(1, child1Cached.depth)
            assertEquals(0, child1Cached.index)
            assertEquals(rootNodeId, child1Cached.parentId)

            val child2Cached = nodeMap[child2NodeId]!!
            assertEquals(child2, child2Cached.node)
            assertEquals(1, child2Cached.depth)
            assertEquals(1, child2Cached.index)
            assertEquals(rootNodeId, child2Cached.parentId)
        }

        @Test
        @DisplayName("populates nodeMap with window-scoped rootParentId")
        fun parseTreeWithDifferentRootParentIdPopulatesNodeMap() {
            // Arrange
            val child = createMockNode(text = "Child")
            val root =
                createMockNode(
                    className = "android.widget.FrameLayout",
                    childCount = 1,
                    children = listOf(child),
                )
            val nodeMap = mutableMapOf<String, CachedNode>()

            // Act
            val result = parser.parseTree(root, rootParentId = "root_w42", nodeMap = nodeMap)

            // Assert
            assertEquals(2, nodeMap.size)

            val rootCached = nodeMap[result.id]!!
            assertEquals("root_w42", rootCached.parentId)

            val childCached = nodeMap[result.children[0].id]!!
            assertEquals(result.id, childCached.parentId)
        }

        @Test
        @DisplayName("nodeMap includes root node even when no children")
        fun parseTreeNodeMapIncludesRootNode() {
            // Arrange
            val root =
                createMockNode(
                    className = "android.widget.TextView",
                    text = "Alone",
                )
            val nodeMap = mutableMapOf<String, CachedNode>()

            // Act
            val result = parser.parseTree(root, nodeMap = nodeMap)

            // Assert
            assertEquals(1, nodeMap.size)
            assertTrue(nodeMap.containsKey(result.id))

            val cached = nodeMap[result.id]!!
            assertEquals(root, cached.node)
            assertEquals(0, cached.depth)
            assertEquals(0, cached.index)
            assertEquals("root", cached.parentId)
        }

        @Test
        @DisplayName("multiple parseTree calls accumulate into same nodeMap")
        fun multipleParseTreeCallsAccumulateIntoSameNodeMap() {
            // Arrange
            val child1 =
                createMockNode(
                    text = "Child W0",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 50,
                    boundsBottom = 50,
                )
            val root1 =
                createMockNode(
                    className = "android.widget.FrameLayout",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 100,
                    boundsBottom = 100,
                    childCount = 1,
                    children = listOf(child1),
                )
            val child2 =
                createMockNode(
                    text = "Child W1",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 60,
                    boundsBottom = 60,
                )
            val root2 =
                createMockNode(
                    className = "android.widget.FrameLayout",
                    boundsLeft = 0,
                    boundsTop = 0,
                    boundsRight = 100,
                    boundsBottom = 100,
                    childCount = 1,
                    children = listOf(child2),
                )
            val nodeMap = mutableMapOf<String, CachedNode>()

            // Act
            val result1 = parser.parseTree(root1, "root_w0", nodeMap)
            val result2 = parser.parseTree(root2, "root_w1", nodeMap)

            // Assert
            assertEquals(4, nodeMap.size)
            assertTrue(nodeMap.containsKey(result1.id))
            assertTrue(nodeMap.containsKey(result1.children[0].id))
            assertTrue(nodeMap.containsKey(result2.id))
            assertTrue(nodeMap.containsKey(result2.children[0].id))

            assertEquals("root_w0", nodeMap[result1.id]!!.parentId)
            assertEquals("root_w1", nodeMap[result2.id]!!.parentId)
        }
    }

    @Nested
    @DisplayName("parseTree with rootParentId")
    inner class ParseTreeWithRootParentId {
        @Test
        @DisplayName("default rootParentId preserves existing behavior")
        fun defaultRootParentIdPreservesExistingBehavior() {
            val node = createMockNode(text = "Hello")
            val resultDefault = parser.parseTree(node)
            val resultExplicit = parser.parseTree(createMockNode(text = "Hello"), "root")
            assertEquals(resultDefault.id, resultExplicit.id)
        }

        @Test
        @DisplayName("window-specific rootParentId produces different IDs")
        fun windowSpecificRootParentIdProducesDifferentIds() {
            val node1 = createMockNode(text = "Hello")
            val node2 = createMockNode(text = "Hello")
            val resultW0 = parser.parseTree(node1, "root_w0")
            val resultW1 = parser.parseTree(node2, "root_w1")
            assertNotEquals(resultW0.id, resultW1.id)
        }

        @Test
        @DisplayName("window-specific IDs differ from default IDs")
        fun windowSpecificIdsDifferFromDefault() {
            val node1 = createMockNode(text = "Hello")
            val node2 = createMockNode(text = "Hello")
            val resultDefault = parser.parseTree(node1)
            val resultW0 = parser.parseTree(node2, "root_w0")
            assertNotEquals(resultDefault.id, resultW0.id)
        }
    }

    @Nested
    @DisplayName("mapWindowType")
    inner class MapWindowType {
        @Test
        @DisplayName("maps all known window types")
        fun mapsAllKnownWindowTypes() {
            // Android AccessibilityWindowInfo type constants: APPLICATION=1, INPUT_METHOD=2,
            // SYSTEM=3, ACCESSIBILITY_OVERLAY=4, SPLIT_SCREEN_DIVIDER=5, MAGNIFICATION_OVERLAY=6
            assertEquals("APPLICATION", AccessibilityTreeParser.mapWindowType(1))
            assertEquals("INPUT_METHOD", AccessibilityTreeParser.mapWindowType(2))
            assertEquals("SYSTEM", AccessibilityTreeParser.mapWindowType(3))
            assertEquals("ACCESSIBILITY_OVERLAY", AccessibilityTreeParser.mapWindowType(4))
            assertEquals("SPLIT_SCREEN_DIVIDER", AccessibilityTreeParser.mapWindowType(5))
            assertEquals("MAGNIFICATION_OVERLAY", AccessibilityTreeParser.mapWindowType(6))
        }

        @Test
        @DisplayName("maps unknown type to UNKNOWN(N)")
        fun mapsUnknownType() {
            assertEquals("UNKNOWN(0)", AccessibilityTreeParser.mapWindowType(0))
            assertEquals("UNKNOWN(99)", AccessibilityTreeParser.mapWindowType(99))
        }
    }
}
