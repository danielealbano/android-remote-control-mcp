package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.KeyEvent
import android.view.inputmethod.SurroundingText
import javax.inject.Inject

/**
 * Implementation of [TypeInputController] that delegates to the
 * [AccessibilityInputConnection] obtained from the [McpAccessibilityService]'s
 * [InputMethod] instance.
 *
 * All methods access the singleton [McpAccessibilityService.inputMethodInstance]
 * to get the current [AccessibilityInputConnection].
 *
 * **Threading**: The AccessibilityInputConnection is an IPC proxy managed by
 * the accessibility framework — NOT a View-bound InputConnection. Methods can
 * be called safely from any thread. If runtime testing reveals thread-safety
 * issues, the [TypeInputController] interface methods would need to be changed
 * to `suspend` to enable `withContext(Dispatchers.Main)`.
 *
 * **Concurrency**: This class is stateless and safe to call from any thread.
 * Callers must use the file-level `typeOperationMutex` in the typing tools
 * to serialize operations and prevent interleaved character commits.
 *
 * **Return values**: The underlying AccessibilityInputConnection methods return
 * `void`. The Boolean return here indicates IC availability only — NOT whether
 * the target field accepted the operation.
 */
class TypeInputControllerImpl
    @Inject
    constructor() : TypeInputController {
        private fun getInputConnection() = McpAccessibilityService.inputMethodInstance?.getCurrentInputConnection()

        override fun isReady(): Boolean =
            McpAccessibilityService.inputMethodInstance?.getCurrentInputStarted() == true &&
                getInputConnection() != null

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            val ic = getInputConnection() ?: return false
            ic.commitText(text, newCursorPosition, null)
            return true
        }

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            val ic = getInputConnection() ?: return false
            ic.setSelection(start, end)
            return true
        }

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? = getInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)

        override fun performContextMenuAction(id: Int): Boolean {
            val ic = getInputConnection() ?: return false
            ic.performContextMenuAction(id)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val ic = getInputConnection() ?: return false
            ic.sendKeyEvent(event)
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            val ic = getInputConnection() ?: return false
            ic.deleteSurroundingText(beforeLength, afterLength)
            return true
        }
    }
