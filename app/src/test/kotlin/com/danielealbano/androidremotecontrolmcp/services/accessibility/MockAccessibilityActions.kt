package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.mockk.every
import io.mockk.mockk

/** An app-defined (custom) action id, outside the framework's standard ones. */
internal const val CUSTOM_ACTION_ID = 0x7f0b0012

/**
 * A mocked [AccessibilityAction] with [id] and [label]: real instances answer 0 and null in JVM unit tests, where the
 * framework classes are stubs.
 */
internal fun mockAction(
    id: Int,
    label: CharSequence?,
): AccessibilityAction =
    mockk {
        every { this@mockk.id } returns id
        every { this@mockk.label } returns label
    }

/** The ids of the standard actions that take arguments, which are never listed even when labelled. */
internal val ARGUMENT_ACTION_IDS =
    listOf(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
        AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT,
        AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT,
        android.R.id.accessibilityActionScrollToPosition,
        android.R.id.accessibilityActionSetProgress,
        android.R.id.accessibilityActionMoveWindow,
        android.R.id.accessibilityActionPressAndHold,
        android.R.id.accessibilityActionScrollInDirection,
        android.R.id.accessibilityActionSetExtendedSelection,
    )
