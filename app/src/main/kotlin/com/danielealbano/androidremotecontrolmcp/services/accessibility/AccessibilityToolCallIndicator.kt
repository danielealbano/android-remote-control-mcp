package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.SystemClock
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Displays a touch-through accessibility overlay while an MCP tool is executing. */
class AccessibilityToolCallIndicator(
    scope: CoroutineScope,
    settingsRepository: SettingsRepository,
) : ToolCallIndicator {
    private val mainScope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    @Volatile
    private var enabled = true

    @Volatile
    private var closed = false

    private val awake =
        ScreenAwakeController(mainScope, SystemClock::elapsedRealtime, McpAccessibilityService::setScreenAwake)
    private val settingsJob =
        mainScope.launch {
            settingsRepository.serverConfig.collect { config -> awake.updateTimeout(config.screenAwakeTimeoutSeconds) }
        }

    override fun onToolCallStarted(toolName: String) {
        mainScope.launch {
            if (!closed) {
                awake.started()
                if (enabled) McpAccessibilityService.showToolCallIndicator(toolName)
            }
        }
    }

    override fun onToolCallFinished(toolName: String) {
        mainScope.launch {
            if (!closed) {
                awake.finished()
                McpAccessibilityService.hideToolCallIndicator()
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) McpAccessibilityService.hideToolCallIndicator()
    }

    /** Called on the main thread before the server and its coroutine scope are torn down. */
    fun close() {
        closed = true
        settingsJob.cancel()
        awake.stop()
        McpAccessibilityService.hideToolCallIndicator()
    }
}
