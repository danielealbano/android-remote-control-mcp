package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.KeyEvent
import android.view.inputmethod.SurroundingText

/**
 * Abstraction over AccessibilityInputConnection operations for natural text input.
 * Wraps the InputMethod API (API 33+) provided by the AccessibilityService
 * with FLAG_INPUT_METHOD_EDITOR.
 *
 * Implementations access the real AccessibilityInputConnection via
 * McpAccessibilityService's InputMethod instance.
 *
 * **Threading**: The AccessibilityInputConnection obtained from InputMethod is an
 * IPC proxy managed by the accessibility framework — it is NOT a View-bound
 * InputConnection. Methods can be called from any thread safely.
 * If runtime testing reveals thread-safety issues, the interface methods would
 * need to be changed to `suspend` to enable `withContext(Dispatchers.Main)`.
 *
 * **Concurrency**: All mutating operations are serialized via a file-level Mutex
 * (`typeOperationMutex` in TextInputTools.kt) at the tool layer, preventing
 * concurrent MCP requests from interleaving character commits.
 *
 * **Return values**: The underlying `AccessibilityInputConnection` mutating methods
 * (`commitText`, `setSelection`, `performContextMenuAction`, `sendKeyEvent`,
 * `deleteSurroundingText`) return `void` in the Android framework. The `Boolean`
 * return on this interface indicates IC **availability** (true = IC was non-null
 * and the call was dispatched), NOT whether the target field accepted the
 * operation. Silent rejection by the target app (e.g., input filters, maxLength)
 * is undetectable via this interface.
 */
interface TypeInputController {
    /**
     * Returns true if the input connection is available
     * (accessibility service connected, text field focused, input started).
     */
    fun isReady(): Boolean

    /**
     * Commits a single character or text to the focused text field.
     * Delegates to AccessibilityInputConnection.commitText().
     *
     * @param text The text to commit.
     * @param newCursorPosition Cursor position relative to the committed text.
     *   1 = after the text (most common for typing).
     * @return true if the IC was available and the call was dispatched, false if IC unavailable.
     */
    fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
    ): Boolean

    /**
     * Sets the selection/cursor position in the focused text field.
     * If start == end, positions the cursor without selecting.
     *
     * @param start Selection start (0-based character index).
     * @param end Selection end (0-based character index).
     * @return true if the IC was available and the call was dispatched, false if IC unavailable.
     */
    fun setSelection(
        start: Int,
        end: Int,
    ): Boolean

    /**
     * Gets the text surrounding the cursor in the focused text field.
     *
     * @param beforeLength Characters to retrieve before cursor.
     * @param afterLength Characters to retrieve after cursor.
     * @param flags 0 or InputConnection.GET_TEXT_WITH_STYLES.
     * @return SurroundingText, or null if unavailable.
     */
    fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText?

    /**
     * Performs a context menu action on the focused text field.
     * Used for select-all (android.R.id.selectAll).
     *
     * @param id The context menu action ID (e.g., android.R.id.selectAll).
     * @return true if the IC was available and the call was dispatched, false if IC unavailable.
     */
    fun performContextMenuAction(id: Int): Boolean

    /**
     * Sends a key event to the focused text field.
     * Used for DELETE key after selection.
     *
     * @param event The KeyEvent to send.
     * @return true if the IC was available and the call was dispatched, false if IC unavailable.
     */
    fun sendKeyEvent(event: KeyEvent): Boolean

    /**
     * Deletes text surrounding the cursor.
     * Included for future extensibility — not currently used by any tool.
     *
     * @param beforeLength Characters to delete before cursor.
     * @param afterLength Characters to delete after cursor.
     * @return true if the IC was available and the call was dispatched, false if IC unavailable.
     */
    fun deleteSurroundingText(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean
}
