package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps an unlocked display awake independently of the visual tool-call indicator. */
internal class ScreenAwakeToolCallIndicator(
    scope: CoroutineScope,
    timeoutSeconds: Int,
    setAwake: (Boolean) -> Unit = McpAccessibilityService::setScreenAwake,
) : ToolCallIndicator {
    private val mainScope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    private val controller = ScreenAwakeController(mainScope, timeoutSeconds) { safely { setAwake(it) } }

    @Volatile
    private var closed = false

    override fun onToolCallStarted(toolName: String) {
        mainScope.launch { if (!closed) safely { controller.started() } }
    }

    override fun onToolCallFinished(toolName: String) {
        mainScope.launch { if (!closed) safely { controller.finished() } }
    }

    override fun onActiveToolChanged(toolName: String) = Unit

    @OptIn(DelicateCoroutinesApi::class)
    fun close() {
        closed = true
        // ATOMIC guarantees entry after owner cancellation; this bounded cleanup never suspends.
        mainScope.launch(start = CoroutineStart.ATOMIC) { safely { controller.stop() } }
    }

    private fun safely(action: () -> Unit) {
        runCatching(action).onFailure { Log.w(TAG, "Screen-awake operation failed", it) }
    }

    private companion object {
        const val TAG = "MCP:ScreenAwake"
    }
}
