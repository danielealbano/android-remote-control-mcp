package com.danielealbano.androidremotecontrolmcp.testutil

import android.view.inputmethod.SurroundingText
import com.danielealbano.androidremotecontrolmcp.services.accessibility.TypeInputController
import io.mockk.every
import io.mockk.mockk

/**
 * Makes the [controller] mock edit an in-memory text field holding [initial], with the cursor at its end:
 * setSelection, commitText (which replaces the selection) and the select-all context menu action change it, and
 * getSurroundingText returns the requested window around the selection. [reportedOffset] is the offset the field
 * reports for that window: 0 means the real one, like an EditText; -1 means none, like a Compose text field. With
 * [loseDeletions] an empty commit leaves the text as it is, like a deletion the field lost.
 */
class FakeTextField(
    controller: TypeInputController,
    initial: String,
    private val reportedOffset: Int = 0,
    private val loseDeletions: Boolean = false,
) {
    /** The field's current text. */
    var content = initial
        private set
    private var selectionStart = initial.length
    private var selectionEnd = initial.length

    init {
        every { controller.isReady() } returns true
        every { controller.setSelection(any(), any()) } answers {
            selectionStart = firstArg()
            selectionEnd = secondArg()
            true
        }
        every { controller.performContextMenuAction(android.R.id.selectAll) } answers {
            selectionStart = 0
            selectionEnd = content.length
            true
        }
        every { controller.commitText(any(), any()) } answers {
            val committed = firstArg<CharSequence>().toString()
            if (committed.isNotEmpty() || !loseDeletions) {
                content = content.replaceRange(selectionStart, selectionEnd, committed)
                selectionStart += committed.length
                selectionEnd = selectionStart
            }
            true
        }
        every { controller.getSurroundingText(any(), any(), any()) } answers { window(firstArg(), secondArg()) }
    }

    private fun window(
        before: Int,
        after: Int,
    ): SurroundingText {
        val start = maxOf(0, selectionStart - before)
        val end = minOf(content.length, selectionEnd + after)
        val windowText = content.substring(start, end)
        val windowOffset = if (reportedOffset < 0) reportedOffset else start
        val windowSelectionStart = selectionStart - start
        val windowSelectionEnd = selectionEnd - start
        val surrounding = mockk<SurroundingText>()
        every { surrounding.text } returns windowText
        every { surrounding.offset } returns windowOffset
        every { surrounding.selectionStart } returns windowSelectionStart
        every { surrounding.selectionEnd } returns windowSelectionEnd
        return surrounding
    }
}
