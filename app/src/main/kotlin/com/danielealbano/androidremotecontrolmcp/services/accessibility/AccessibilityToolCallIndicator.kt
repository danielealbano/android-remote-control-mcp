package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.Handler
import android.os.Looper
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator

/** Displays a touch-through accessibility overlay while an MCP tool is executing. */
class AccessibilityToolCallIndicator(
    private val dispatch: (() -> Unit) -> Unit = ::dispatchAccessibilityAction,
) : ToolCallIndicator {
    private var enabled = true

    @Volatile
    private var closed = false

    override fun onToolCallStarted(toolName: String) {
        dispatch { if (!closed && enabled) McpAccessibilityService.showToolCallIndicator(toolName) }
    }

    override fun onToolCallFinished(toolName: String) {
        dispatch { if (!closed) McpAccessibilityService.hideToolCallIndicator() }
    }

    override fun setEnabled(enabled: Boolean) {
        dispatch {
            this.enabled = enabled
            if (!enabled) McpAccessibilityService.hideToolCallIndicator()
        }
    }

    fun close() {
        closed = true
        dispatch { McpAccessibilityService.hideToolCallIndicator() }
    }
}

/** Serializes accessibility operations and avoids a second queued hop from Main. */
internal fun dispatchAccessibilityAction(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
    } else {
        Handler(Looper.getMainLooper()).post { action() }
    }
}
