package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.SystemClock
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps an unlocked display awake independently of the visual tool-call indicator. */
internal class ScreenAwakeToolCallIndicator(
    scope: CoroutineScope,
    timeoutSeconds: Int,
    nowMillis: () -> Long = SystemClock::uptimeMillis,
    setAwake: (Boolean) -> Unit = McpAccessibilityService::setScreenAwake,
) : ToolCallIndicator {
    private val mainScope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    private val controller = ScreenAwakeController(mainScope, nowMillis, setAwake)

    @Volatile
    private var closed = false

    init {
        controller.updateTimeout(timeoutSeconds)
    }

    override fun onToolCallStarted(toolName: String) {
        mainScope.launch { if (!closed) safely { controller.started() } }
    }

    override fun onToolCallFinished(toolName: String) {
        mainScope.launch { if (!closed) safely { controller.finished() } }
    }

    fun close() {
        closed = true
        mainScope.launch { safely { controller.stop() } }
    }

    private fun safely(action: () -> Unit) {
        runCatching(action).onFailure { Log.w(TAG, "Screen-awake operation failed", it) }
    }

    private companion object {
        const val TAG = "MCP:ScreenAwake"
    }
}
