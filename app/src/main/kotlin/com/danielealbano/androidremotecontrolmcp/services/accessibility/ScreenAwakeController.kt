package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread lifecycle with a fixed startup timeout. Coroutine delay is the only uptime clock. */
internal class ScreenAwakeController(
    private val scope: CoroutineScope,
    timeoutSeconds: Int,
    private val setAwake: (Boolean) -> Unit,
) {
    private val timeoutMillis =
        (timeoutSeconds.takeIf(ServerConfig::isValidScreenAwakeTimeout) ?: 0) * MILLIS_PER_SECOND
    private var active = false
    private var releaseJob: Job? = null

    fun started() {
        releaseJob?.cancel()
        releaseJob = null
        active = timeoutMillis > 0
        if (active) setAwake(true)
    }

    fun finished() {
        if (!active) return
        active = false
        releaseJob =
            scope.launch {
                delay(timeoutMillis)
                releaseJob = null
                setAwake(false)
            }
    }

    fun stop() {
        releaseJob?.cancel()
        releaseJob = null
        active = false
        setAwake(false)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
