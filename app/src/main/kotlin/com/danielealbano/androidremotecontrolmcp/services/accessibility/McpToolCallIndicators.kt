package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator
import kotlinx.coroutines.CoroutineScope

/** Fans out activity to independently owned visual and screen-awake components. */
internal class McpToolCallIndicators(
    scope: CoroutineScope,
    timeoutSeconds: Int,
) : ToolCallIndicator {
    private val visual = AccessibilityToolCallIndicator()
    private val awake = ScreenAwakeToolCallIndicator(scope, timeoutSeconds)

    override fun onToolCallStarted(toolName: String) {
        awake.onToolCallStarted(toolName)
        visual.onToolCallStarted(toolName)
    }

    override fun onToolCallFinished(toolName: String) {
        awake.onToolCallFinished(toolName)
        visual.onToolCallFinished(toolName)
    }

    override fun setEnabled(enabled: Boolean) = visual.setEnabled(enabled)

    override fun onActiveToolChanged(toolName: String) = visual.onActiveToolChanged(toolName)

    fun close() {
        awake.close()
        visual.close()
    }
}
