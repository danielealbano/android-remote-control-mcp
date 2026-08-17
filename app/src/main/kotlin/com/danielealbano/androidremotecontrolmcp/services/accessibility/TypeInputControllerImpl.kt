package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.annotation.SuppressLint
import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.SurroundingText
import javax.inject.Inject

/**
 * Implementation of [TypeInputController] that delegates to the
 * [AccessibilityInputConnection] obtained from the [McpAccessibilityService]'s
 * [InputMethod] instance.
 *
 * **Android 12 (API 31/32) port**: `android.accessibilityservice.InputMethod` and
 * `AccessibilityInputConnection` only exist from API 33 on, and
 * [McpAccessibilityService.onCreateInputMethod] never creates the input method below 33,
 * so every method short-circuits to "not available" there. The typing tools surface this
 * as "input method not ready" instead of crashing. `@SuppressLint("NewApi")` is justified
 * because every API-33 reference is behind an explicit `SDK_INT >= TIRAMISU` guard, so no
 * API-33 symbol is ever resolved below 33.
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
@SuppressLint("NewApi")
class TypeInputControllerImpl
    @Inject
    constructor() : TypeInputController {
        private fun getInputConnection(): AccessibilityInputConnection? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return McpAccessibilityService.inputMethodInstance?.getCurrentInputConnection()
        }

        override fun isReady(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return McpAccessibilityService.inputMethodInstance?.getCurrentInputStarted() == true &&
                getInputConnection() != null
        }

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val ic = getInputConnection() ?: return false
            ic.commitText(text, newCursorPosition, null)
            return true
        }

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val ic = getInputConnection() ?: return false
            ic.setSelection(start, end)
            return true
        }

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return getInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val ic = getInputConnection() ?: return false
            ic.performContextMenuAction(id)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val ic = getInputConnection() ?: return false
            ic.sendKeyEvent(event)
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val ic = getInputConnection() ?: return false
            ic.deleteSurroundingText(beforeLength, afterLength)
            return true
        }
    }
