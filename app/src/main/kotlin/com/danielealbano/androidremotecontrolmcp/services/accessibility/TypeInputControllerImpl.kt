package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import javax.inject.Inject

/**
 * Implementation of [TypeInputController] that delegates to the
 * API-33+ [AccessibilityInputConnection] or the classic [InputConnection] exposed by
 * [McpInputMethodService] on API 31/32.
 *
 * API-33 references are guarded by `SDK_INT >= TIRAMISU`; below API 33 all operations use
 * [McpInputMethodService.instance].
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
 * **Return values**: API-33+ calls wrap the void accessibility methods as `true` when
 * dispatched. API 31/32 calls forward the classic input connection's Boolean result.
 */
class TypeInputControllerImpl
    @Inject
    constructor() : TypeInputController {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun getAccessibilityInputConnection(): AccessibilityInputConnection? =
            McpAccessibilityService.inputMethodInstance?.getCurrentInputConnection()

        private fun getClassicInputConnection(): InputConnection? = McpInputMethodService.instance?.currentConnection()

        override fun isReady(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                McpAccessibilityService.inputMethodInstance?.getCurrentInputStarted() == true
            } else {
                getClassicInputConnection() != null
            }

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = getAccessibilityInputConnection() ?: return false
                connection.commitText(text, newCursorPosition, null)
                true
            } else {
                getClassicInputConnection()?.commitText(text, newCursorPosition) ?: false
            }
        }

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = getAccessibilityInputConnection() ?: return false
                connection.setSelection(start, end)
                true
            } else {
                getClassicInputConnection()?.setSelection(start, end) ?: false
            }
        }

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getAccessibilityInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)
            } else {
                getClassicInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)
            }

        override fun performContextMenuAction(id: Int): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = getAccessibilityInputConnection() ?: return false
                connection.performContextMenuAction(id)
                true
            } else {
                getClassicInputConnection()?.performContextMenuAction(id) ?: false
            }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = getAccessibilityInputConnection() ?: return false
                connection.sendKeyEvent(event)
                true
            } else {
                getClassicInputConnection()?.sendKeyEvent(event) ?: false
            }
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = getAccessibilityInputConnection() ?: return false
                connection.deleteSurroundingText(beforeLength, afterLength)
                true
            } else {
                getClassicInputConnection()?.deleteSurroundingText(beforeLength, afterLength) ?: false
            }
        }
    }
