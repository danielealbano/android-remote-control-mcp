package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * The accessibility actions of a node that can be performed by label, and the labels they are listed with.
 *
 * Some apps expose controls only as actions of a single node (a vote bar whose "Upvote" and "Downvote" buttons are
 * not nodes of their own). An action is listed when its label is not blank after [sanitize]: custom actions and the
 * standard ones the app labelled, in the node's order. Standard actions that take arguments are left out, as one
 * performed by label gets none and some of them (set text, set selection) clear the field without them.
 *
 * The excluded ids are compile-time ints, never the [AccessibilityAction] constants: those are null in JVM unit tests.
 */
object NodeActionLabels {
    /** Separator between labels in the text outputs; a listed label never contains it. */
    const val SEPARATOR = "¦"

    /** `android.R.id.accessibilityActionScrollInDirection` (API 34); a literal, as the minimum SDK is 33. */
    internal const val ID_SCROLL_IN_DIRECTION = 16908382

    /** `android.R.id.accessibilityActionSetExtendedSelection` (API 36); a literal, as the minimum SDK is 33. */
    internal const val ID_SET_EXTENDED_SELECTION = 16908383

    private val EXCLUDED_IDS =
        intArrayOf(
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
            ID_SCROLL_IN_DIRECTION,
            ID_SET_EXTENDED_SELECTION,
        )

    /** The listed labels of [node], in order, or the shared empty list when it has none. */
    fun labelsOf(node: AccessibilityNodeInfo): List<String> {
        var labels: ArrayList<String>? = null
        for (action in node.actionList) {
            val label = listedLabel(action) ?: continue
            if (labels == null) labels = ArrayList()
            labels.add(label)
        }
        return labels ?: emptyList()
    }

    /** The first listed action of [node] whose label is [label], or null when there is none. */
    fun actionFor(
        node: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityAction? = node.actionList.firstOrNull { listedLabel(it) == label }

    /**
     * [label] as listed: tabs, line breaks and [SEPARATOR] become spaces, then it is trimmed. Null when nothing is
     * left, as an action without a label cannot be named.
     */
    internal fun sanitize(label: CharSequence?): String? =
        label
            ?.toString()
            ?.replace('\t', ' ')
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace(SEPARATOR, " ")
            ?.trim()
            ?.ifEmpty { null }

    private fun listedLabel(action: AccessibilityAction): String? =
        if (action.id in EXCLUDED_IDS) null else sanitize(action.label)
}
