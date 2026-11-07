package com.danielealbano.androidremotecontrolmcp.mcp.tools

/**
 * Serializes overlapping tool-call transitions and keeps the delegate on the most recently started
 * active tool. Delegate failures are swallowed so an optional visual indicator remains best-effort.
 */
class ReferenceCountedToolCallIndicator(
    private val delegate: ToolCallIndicator,
) : ToolCallIndicator {
    private val lock = Any()
    private val activeTools = mutableListOf<String>()

    override fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            runCatching { delegate.setEnabled(enabled) }
        }
    }

    override fun onToolCallStarted(toolName: String) {
        synchronized(lock) {
            activeTools += toolName
            runCatching { delegate.onToolCallStarted(toolName) }
        }
    }

    override fun onToolCallFinished(toolName: String) {
        synchronized(lock) {
            val index = activeTools.indexOfLast { it == toolName }
            if (index >= 0) {
                val wasDisplayed = index == activeTools.lastIndex
                activeTools.removeAt(index)
                if (wasDisplayed) {
                    val nextTool = activeTools.lastOrNull()
                    runCatching {
                        if (nextTool == null) {
                            delegate.onToolCallFinished(toolName)
                        } else {
                            delegate.onActiveToolChanged(nextTool)
                        }
                    }
                }
            }
        }
    }
}
