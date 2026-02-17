package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ElementFinder")
class ElementFinderTest {
    private lateinit var finder: ElementFinder

    private val defaultBounds = BoundsData(0, 0, 100, 50)

    @BeforeEach
    fun setUp() {
        finder = ElementFinder()
    }

    @Suppress("LongParameterList")
    private fun createNode(
        id: String = "node_test",
        className: String? = "android.widget.TextView",
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: BoundsData = defaultBounds,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        children: List<AccessibilityNodeData> = emptyList(),
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = className,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            editable = editable,
            enabled = enabled,
            visible = visible,
            children = children,
        )

    private fun buildSampleTree(): AccessibilityNodeData =
        createNode(
            id = "node_root",
            className = "android.widget.FrameLayout",
            children =
                listOf(
                    createNode(
                        id = "node_button_7",
                        className = "android.widget.Button",
                        text = "7",
                        contentDescription = "Seven",
                        resourceId = "com.calculator:id/btn_7",
                        clickable = true,
                    ),
                    createNode(
                        id = "node_button_plus",
                        className = "android.widget.Button",
                        text = "+",
                        contentDescription = "Plus",
                        resourceId = "com.calculator:id/btn_plus",
                        clickable = true,
                    ),
                    createNode(
                        id = "node_button_3",
                        className = "android.widget.Button",
                        text = "3",
                        contentDescription = "Three",
                        resourceId = "com.calculator:id/btn_3",
                        clickable = true,
                    ),
                    createNode(
                        id = "node_display",
                        className = "android.widget.EditText",
                        text = "0",
                        resourceId = "com.calculator:id/display",
                        editable = true,
                    ),
                ),
        )

    @Nested
    @DisplayName("findElements by TEXT")
    inner class FindByText {
        @Test
        @DisplayName("finds element by exact text match")
        fun findsElementByExactTextMatch() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results = finder.findElements(tree, FindBy.TEXT, "7", exactMatch = true)

            // Assert
            assertEquals(1, results.size)
            assertEquals("node_button_7", results[0].id)
            assertEquals("7", results[0].text)
        }

        @Test
        @DisplayName("finds element by contains text match (case-insensitive)")
        fun findsElementByContainsTextMatch() {
            // Arrange
            val tree =
                createNode(
                    id = "node_root",
                    children =
                        listOf(
                            createNode(id = "node_1", text = "Hello World"),
                            createNode(id = "node_2", text = "hello there"),
                            createNode(id = "node_3", text = "Goodbye"),
                        ),
                )

            // Act
            val results = finder.findElements(tree, FindBy.TEXT, "hello", exactMatch = false)

            // Assert
            assertEquals(2, results.size)
            assertEquals("node_1", results[0].id)
            assertEquals("node_2", results[1].id)
        }

        @Test
        @DisplayName("returns empty list when no text matches")
        fun returnsEmptyListWhenNoTextMatches() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results = finder.findElements(tree, FindBy.TEXT, "NonExistent", exactMatch = true)

            // Assert
            assertTrue(results.isEmpty())
        }

        @Test
        @DisplayName("exact match is case-sensitive")
        fun exactMatchIsCaseSensitive() {
            // Arrange
            val tree =
                createNode(
                    id = "node_root",
                    children =
                        listOf(
                            createNode(id = "node_1", text = "Hello"),
                            createNode(id = "node_2", text = "hello"),
                        ),
                )

            // Act
            val results = finder.findElements(tree, FindBy.TEXT, "Hello", exactMatch = true)

            // Assert
            assertEquals(1, results.size)
            assertEquals("node_1", results[0].id)
        }
    }

    @Nested
    @DisplayName("findElements by CONTENT_DESC")
    inner class FindByContentDesc {
        @Test
        @DisplayName("finds element by content description")
        fun findsElementByContentDescription() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.CONTENT_DESC,
                    "Seven",
                    exactMatch = true,
                )

            // Assert
            assertEquals(1, results.size)
            assertEquals("node_button_7", results[0].id)
        }

        @Test
        @DisplayName("contains match on content description is case-insensitive")
        fun containsMatchIsCaseInsensitive() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.CONTENT_DESC,
                    "seven",
                    exactMatch = false,
                )

            // Assert
            assertEquals(1, results.size)
            assertEquals("node_button_7", results[0].id)
        }
    }

    @Nested
    @DisplayName("findElements by RESOURCE_ID")
    inner class FindByResourceId {
        @Test
        @DisplayName("finds element by resource ID")
        fun findsElementByResourceId() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.RESOURCE_ID,
                    "com.calculator:id/btn_plus",
                    exactMatch = true,
                )

            // Assert
            assertEquals(1, results.size)
            assertEquals("node_button_plus", results[0].id)
        }

        @Test
        @DisplayName("contains match on resource ID")
        fun containsMatchOnResourceId() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.RESOURCE_ID,
                    "btn_",
                    exactMatch = false,
                )

            // Assert
            assertEquals(3, results.size)
        }
    }

    @Nested
    @DisplayName("findElements by CLASS_NAME")
    inner class FindByClassName {
        @Test
        @DisplayName("finds elements by class name")
        fun findsElementsByClassName() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.CLASS_NAME,
                    "android.widget.Button",
                    exactMatch = true,
                )

            // Assert
            assertEquals(3, results.size)
        }

        @Test
        @DisplayName("finds elements by partial class name")
        fun findsElementsByPartialClassName() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val results =
                finder.findElements(
                    tree,
                    FindBy.CLASS_NAME,
                    "Button",
                    exactMatch = false,
                )

            // Assert
            assertEquals(3, results.size)
        }
    }

    @Nested
    @DisplayName("findNodeById")
    inner class FindNodeById {
        @Test
        @DisplayName("finds node by ID in tree")
        fun findsNodeByIdInTree() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val result = finder.findNodeById(tree, "node_button_3")

            // Assert
            assertNotNull(result)
            assertEquals("3", result?.text)
            assertEquals("node_button_3", result?.id)
        }

        @Test
        @DisplayName("returns null for non-existent ID")
        fun returnsNullForNonExistentId() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val result = finder.findNodeById(tree, "node_does_not_exist")

            // Assert
            assertNull(result)
        }

        @Test
        @DisplayName("finds root node by ID")
        fun findsRootNodeById() {
            // Arrange
            val tree = buildSampleTree()

            // Act
            val result = finder.findNodeById(tree, "node_root")

            // Assert
            assertNotNull(result)
            assertEquals("node_root", result?.id)
        }
    }

    @Nested
    @DisplayName("matchesValue")
    inner class MatchesValue {
        @Test
        @DisplayName("returns false for null node value")
        fun returnsFalseForNullNodeValue() {
            // Act & Assert
            assertEquals(false, finder.matchesValue(null, "test", exactMatch = true))
            assertEquals(false, finder.matchesValue(null, "test", exactMatch = false))
        }

        @Test
        @DisplayName("exact match returns true for equal strings")
        fun exactMatchReturnsTrueForEqualStrings() {
            // Act & Assert
            assertEquals(true, finder.matchesValue("Hello", "Hello", exactMatch = true))
        }

        @Test
        @DisplayName("exact match returns false for different case")
        fun exactMatchReturnsFalseForDifferentCase() {
            // Act & Assert
            assertEquals(false, finder.matchesValue("Hello", "hello", exactMatch = true))
        }

        @Test
        @DisplayName("contains match is case-insensitive")
        fun containsMatchIsCaseInsensitive() {
            // Act & Assert
            assertEquals(true, finder.matchesValue("Hello World", "hello", exactMatch = false))
        }
    }

    @Nested
    @DisplayName("Multi-window search")
    inner class MultiWindowSearch {
        private fun makeWindowData(
            windowId: Int,
            tree: AccessibilityNodeData,
        ): WindowData =
            WindowData(
                windowId = windowId,
                windowType = "APPLICATION",
                packageName = "com.example",
                title = "Test",
                layer = windowId,
                focused = windowId == 0,
                tree = tree,
            )

        @Test
        @DisplayName("findElements across two windows finds elements from both")
        fun findElementsAcrossTwoWindows() {
            val tree1 = createNode(id = "node_a", text = "Hello")
            val tree2 = createNode(id = "node_b", text = "Hello World")
            val windows = listOf(makeWindowData(0, tree1), makeWindowData(1, tree2))
            val results = finder.findElements(windows, FindBy.TEXT, "Hello", false)
            assertEquals(2, results.size)
        }

        @Test
        @DisplayName("findElements with no matches returns empty")
        fun findElementsNoMatches() {
            val tree1 = createNode(id = "node_a", text = "Foo")
            val windows = listOf(makeWindowData(0, tree1))
            val results = finder.findElements(windows, FindBy.TEXT, "Bar", false)
            assertTrue(results.isEmpty())
        }

        @Test
        @DisplayName("findNodeById finds node in second window")
        fun findNodeByIdInSecondWindow() {
            val tree1 = createNode(id = "node_a", text = "First")
            val tree2 = createNode(id = "node_b", text = "Second")
            val windows = listOf(makeWindowData(0, tree1), makeWindowData(1, tree2))
            val result = finder.findNodeById(windows, "node_b")
            assertNotNull(result)
            assertEquals("node_b", result!!.id)
        }

        @Test
        @DisplayName("findNodeById returns null when not found")
        fun findNodeByIdNotFound() {
            val tree1 = createNode(id = "node_a", text = "First")
            val windows = listOf(makeWindowData(0, tree1))
            val result = finder.findNodeById(windows, "node_nonexistent")
            assertNull(result)
        }
    }
}
