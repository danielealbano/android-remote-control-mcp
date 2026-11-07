package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NodeActionLabels")
class NodeActionLabelsTest {
    private fun nodeWith(vararg actions: AccessibilityNodeInfo.AccessibilityAction): AccessibilityNodeInfo =
        mockk { every { actionList } returns actions.toList() }

    @Nested
    @DisplayName("labelsOf")
    inner class LabelsOf {
        @Test
        @DisplayName("lists custom and labelled standard actions in the node's order")
        fun listsLabelledActionsInOrder() {
            val node =
                nodeWith(
                    mockAction(CUSTOM_ACTION_ID, "Upvote"),
                    mockAction(AccessibilityNodeInfo.ACTION_CLICK, "Open post"),
                    mockAction(CUSTOM_ACTION_ID + 1, "Downvote"),
                )

            assertEquals(listOf("Upvote", "Open post", "Downvote"), NodeActionLabels.labelsOf(node))
        }

        @Test
        @DisplayName("skips actions without a label or with a blank one")
        fun skipsUnlabelledActions() {
            val node =
                nodeWith(
                    mockAction(AccessibilityNodeInfo.ACTION_CLICK, null),
                    mockAction(CUSTOM_ACTION_ID, " \t\n "),
                    mockAction(CUSTOM_ACTION_ID + 1, "Share post"),
                )

            assertEquals(listOf("Share post"), NodeActionLabels.labelsOf(node))
        }

        @Test
        @DisplayName("leaves out every standard action that takes arguments, even when labelled")
        fun leavesOutArgumentActions() {
            val node = nodeWith(*ARGUMENT_ACTION_IDS.map { mockAction(it, "Labelled") }.toTypedArray())

            assertEquals(emptyList<String>(), NodeActionLabels.labelsOf(node))
        }

        @Test
        @DisplayName("returns the shared empty list when nothing is listed")
        fun returnsSharedEmptyList() {
            assertSame(emptyList<String>(), NodeActionLabels.labelsOf(nodeWith()))
            assertSame(
                emptyList<String>(),
                NodeActionLabels.labelsOf(nodeWith(mockAction(AccessibilityNodeInfo.ACTION_CLICK, null))),
            )
        }

        @Test
        @DisplayName("sanitizes tabs, line breaks and the separator into spaces, then trims")
        fun sanitizesLabels() {
            val node =
                nodeWith(
                    mockAction(CUSTOM_ACTION_ID, " Up\tvote\n"),
                    mockAction(CUSTOM_ACTION_ID + 1, "Down\r\nvote"),
                    mockAction(CUSTOM_ACTION_ID + 2, "Share¦post"),
                )

            assertEquals(listOf("Up vote", "Down  vote", "Share post"), NodeActionLabels.labelsOf(node))
        }
    }

    @Nested
    @DisplayName("actionFor")
    inner class ActionFor {
        @Test
        @DisplayName("returns the first listed action with the label")
        fun returnsFirstMatch() {
            val first = mockAction(CUSTOM_ACTION_ID, "Upvote")
            val second = mockAction(CUSTOM_ACTION_ID + 1, "Upvote")

            assertSame(first, NodeActionLabels.actionFor(nodeWith(first, second), "Upvote"))
        }

        @Test
        @DisplayName("matches the label as listed, after sanitizing")
        fun matchesSanitizedLabel() {
            val action = mockAction(CUSTOM_ACTION_ID, " Up\tvote ")

            assertSame(action, NodeActionLabels.actionFor(nodeWith(action), "Up vote"))
        }

        @Test
        @DisplayName("skips a standard action that takes arguments even when its label matches")
        fun skipsArgumentActions() {
            val setText = mockAction(AccessibilityNodeInfo.ACTION_SET_TEXT, "Edit")
            val custom = mockAction(CUSTOM_ACTION_ID, "Edit")

            assertSame(custom, NodeActionLabels.actionFor(nodeWith(setText, custom), "Edit"))
        }

        @Test
        @DisplayName("returns null when no listed action has the label, case-sensitively")
        fun returnsNullWithoutMatch() {
            val node = nodeWith(mockAction(CUSTOM_ACTION_ID, "Upvote"))

            assertNull(NodeActionLabels.actionFor(node, "upvote"))
            assertNull(NodeActionLabels.actionFor(node, "Downvote"))
        }
    }

    @Test
    @DisplayName("the literal ids equal the SDK's")
    fun literalIdsEqualSdk() {
        assertEquals(android.R.id.accessibilityActionScrollInDirection, NodeActionLabels.ID_SCROLL_IN_DIRECTION)
        assertEquals(android.R.id.accessibilityActionSetExtendedSelection, NodeActionLabels.ID_SET_EXTENDED_SELECTION)
    }
}
