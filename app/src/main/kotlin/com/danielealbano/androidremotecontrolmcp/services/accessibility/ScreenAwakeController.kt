package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread lifecycle: active calls hold the screen; the final completion starts the idle grace. */
internal class ScreenAwakeController(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val setAwake: (Boolean) -> Unit,
) {
    private var timeoutSeconds = ServerConfig.DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS
    private var active = false
    private var finishedAt: Long? = null
    private var releaseJob: Job? = null

    fun started() {
        releaseJob?.cancel()
        active = true
        finishedAt = null
        setAwake(true)
    }

    fun finished() {
        if (active) {
            active = false
            finishedAt = nowMillis()
            scheduleRelease()
        }
    }

    fun updateTimeout(seconds: Int) {
        val valid =
            seconds.takeIf { it >= ServerConfig.MIN_SCREEN_AWAKE_TIMEOUT_SECONDS }
                ?: ServerConfig.DEFAULT_SCREEN_AWAKE_TIMEOUT_SECONDS
        if (valid != timeoutSeconds) {
            timeoutSeconds = valid
            if (!active && finishedAt != null) scheduleRelease()
        }
    }

    fun stop() {
        releaseJob?.cancel()
        releaseJob = null
        active = false
        finishedAt = null
        setAwake(false)
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        val elapsed = (nowMillis() - (finishedAt ?: return)).coerceAtLeast(0)
        val remaining = timeoutSeconds * MILLIS_PER_SECOND - elapsed
        if (remaining <= 0) {
            stop()
        } else {
            releaseJob =
                scope.launch {
                    delay(remaining)
                    finishedAt = null
                    setAwake(false)
                }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
