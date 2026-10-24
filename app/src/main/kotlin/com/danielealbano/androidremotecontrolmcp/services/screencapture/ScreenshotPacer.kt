package com.danielealbano.androidremotecontrolmcp.services.screencapture

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces out `AccessibilityService.takeScreenshot` requests. Android rejects a request that arrives 333 ms or less
 * after the last request it accepted (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`, interval from the hidden
 * `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`), so captures run one at a time and each waits until
 * [intervalMs] have passed since the previous one ended: on success, on an error answer and on timeout alike. An
 * accepted request was recorded by the system before its answer, so waiting from the end is always enough; for a
 * request the system rejected or never received it only waits longer than needed. [clock] is monotonic, in
 * milliseconds.
 */
class ScreenshotPacer(
    private val clock: () -> Long,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private var lastEndedAtMs: Long? = null

    /** Runs [capture] once the interval since the previous capture has passed, then records when it ended. */
    suspend fun <T> paced(capture: suspend () -> T): T =
        mutex.withLock {
            lastEndedAtMs?.let { endedAt ->
                val waitMs = endedAt + intervalMs - clock()
                if (waitMs > 0) delay(waitMs)
            }
            try {
                capture()
            } finally {
                lastEndedAtMs = clock()
            }
        }

    companion object {
        /** One more than the platform's 333 ms: the platform also rejects a request exactly 333 ms later. */
        const val DEFAULT_INTERVAL_MS = 334L
    }
}
