package com.danielealbano.androidremotecontrolmcp.testutil

import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import io.mockk.every
import io.mockk.mockk

/**
 * Makes the [controller] mock edit an in-memory text field holding [initial], with the cursor at [cursor]:
 * setSelection (clamped to the text), commitText (which replaces the selection) and the select-all context menu action
 * change it, and getSurroundingText returns the requested window around the selection.
 *
 * - [reportsOffset]: true reports where the window starts, like an EditText; false reports -1, like a Compose text
 *   field.
 * - [loseDeletions]: an empty commit leaves the text as it is, like a deletion the field lost.
 * - [reformat] (settable): applied to the text after every commit, like a field that formats what it holds.
 * - [staleReads] (settable): after every deletion (an empty commit), that many reads still return the text from
 *   before it, like a field whose input connection updates late.
 */
class FakeTextField(
    controller: TypeInputController,
    initial: String,
    cursor: Int = initial.length,
    private val reportsOffset: Boolean = true,
    private val loseDeletions: Boolean = false,
) {
    var reformat: (String) -> String = { it }
    var staleReads = 0

    /** The field's current text. */
    var content = initial
        private set
    private var selectionStart = cursor
    private var selectionEnd = cursor
    private var readsBehind = 0
    private var previousContent = initial

    init {
        every { controller.isReady() } returns true
        every { controller.setSelection(any(), any()) } answers {
            selectionStart = firstArg<Int>().coerceIn(0, content.length)
            selectionEnd = secondArg<Int>().coerceIn(0, content.length)
            true
        }
        every { controller.performContextMenuAction(android.R.id.selectAll) } answers {
            selectionStart = 0
            selectionEnd = content.length
            true
        }
        every { controller.commitText(any(), any()) } answers {
            commit(firstArg<CharSequence>().toString())
            true
        }
        every { controller.getSurroundingText(any(), any(), any()) } answers { window(firstArg(), secondArg()) }
    }

    private fun commit(committed: String) {
        previousContent = content
        if (committed.isNotEmpty() || !loseDeletions) {
            content = reformat(content.replaceRange(selectionStart, selectionEnd, committed))
            selectionStart = (selectionStart + committed.length).coerceAtMost(content.length)
            selectionEnd = selectionStart
        }
        if (committed.isEmpty()) readsBehind = staleReads
    }

    private fun window(
        before: Int,
        after: Int,
    ): SurroundingText {
        val shown =
            if (readsBehind > 0) {
                readsBehind--
                previousContent
            } else {
                content
            }
        val cursorStart = selectionStart.coerceAtMost(shown.length)
        val cursorEnd = selectionEnd.coerceAtMost(shown.length)
        val start = maxOf(0, cursorStart - before)
        val end = minOf(shown.length, cursorEnd + after)
        val windowText = shown.substring(start, end)
        val windowOffset = if (reportsOffset) start else -1
        val windowSelectionStart = cursorStart - start
        val windowSelectionEnd = cursorEnd - start
        val surrounding = mockk<SurroundingText>()
        every { surrounding.text } returns windowText
        every { surrounding.offset } returns windowOffset
        every { surrounding.selectionStart } returns windowSelectionStart
        every { surrounding.selectionEnd } returns windowSelectionEnd
        return surrounding
    }
}
