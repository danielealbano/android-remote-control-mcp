package com.danielealbano.androidremotecontrolmcp.mcp.tools

/**
 * Surfaces MCP tool activity to the device user without exposing tool arguments or results.
 * Implementations must be non-blocking so they never delay a tool invocation.
 */
interface ToolCallIndicator {
    fun onToolCallStarted(toolName: String)

    fun onToolCallFinished(toolName: String)

    /** Changes the displayed label without representing a new tool invocation. */
    fun onActiveToolChanged(toolName: String) = onToolCallStarted(toolName)

    fun setEnabled(enabled: Boolean) = Unit

    companion object {
        val NONE: ToolCallIndicator =
            object : ToolCallIndicator {
                override fun onToolCallStarted(toolName: String) = Unit

                override fun onToolCallFinished(toolName: String) = Unit
            }
    }
}
