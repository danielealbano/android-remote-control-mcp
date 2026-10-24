package com.danielealbano.androidremotecontrolmcp.services.screencapture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ScreenshotPacer")
class ScreenshotPacerTest {
    private fun TestScope.pacer(): ScreenshotPacer = ScreenshotPacer(clock = { currentTime })

    @Test
    fun `the first capture runs at once`() =
        runTest {
            val pacer = pacer()

            val startedAt = pacer.paced { currentTime }

            assertEquals(0L, startedAt)
        }

    @Test
    fun `a capture right after another waits the interval from the previous end`() =
        runTest {
            val pacer = pacer()
            pacer.paced { delay(CAPTURE_MS) }

            val startedAt = pacer.paced { currentTime }

            assertEquals(CAPTURE_MS + ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `no wait when the interval has already passed`() =
        runTest {
            val pacer = pacer()
            pacer.paced { }
            delay(ScreenshotPacer.DEFAULT_INTERVAL_MS + 1)

            val startedAt = pacer.paced { currentTime }

            assertEquals(ScreenshotPacer.DEFAULT_INTERVAL_MS + 1, startedAt)
        }

    @Test
    fun `only the remaining part of the interval is waited`() =
        runTest {
            val pacer = pacer()
            pacer.paced { }
            delay(PARTIAL_MS)

            val startedAt = pacer.paced { currentTime }

            assertEquals(ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `a failed capture still counts as the previous one`() =
        runTest {
            val pacer = pacer()
            val failure = runCatching { pacer.paced { error("capture failed") } }.exceptionOrNull()

            val startedAt = pacer.paced { currentTime }

            assertTrue(failure is IllegalStateException)

            assertEquals(ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `a capture that returned nothing (error answer or timeout) still counts as the previous one`() =
        runTest {
            val pacer = pacer()
            pacer.paced<String?> { null }

            val startedAt = pacer.paced { currentTime }

            assertEquals(ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `a caller cancelled while waiting releases the lock and records no end`() =
        runTest {
            val pacer = pacer()
            pacer.paced { }
            val waiting = launch { pacer.paced { } }
            advanceTimeBy(PARTIAL_MS)
            waiting.cancelAndJoin()

            val startedAt = pacer.paced { currentTime }

            // Still spaced from the first capture's end (0), not from the cancellation (100).
            assertEquals(ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `a caller cancelled during the capture records its end`() =
        runTest {
            val pacer = pacer()
            val capturing = launch { pacer.paced { delay(CAPTURE_MS) } }
            advanceTimeBy(PARTIAL_MS)
            capturing.cancelAndJoin()

            val startedAt = pacer.paced { currentTime }

            assertEquals(PARTIAL_MS + ScreenshotPacer.DEFAULT_INTERVAL_MS, startedAt)
        }

    @Test
    fun `concurrent captures run one at a time, each spaced from the previous end`() =
        runTest {
            val pacer = pacer()

            val first = async { pacer.paced { currentTime.also { delay(CAPTURE_MS) } } }
            val second = async { pacer.paced { currentTime.also { delay(CAPTURE_MS) } } }
            val third = async { pacer.paced { currentTime } }

            assertEquals(0L, first.await())
            assertEquals(CAPTURE_MS + ScreenshotPacer.DEFAULT_INTERVAL_MS, second.await())
            assertEquals(2 * (CAPTURE_MS + ScreenshotPacer.DEFAULT_INTERVAL_MS), third.await())
        }

    @Test
    fun `the default interval is one more than the platform's 333 ms`() {
        assertEquals(PLATFORM_INTERVAL_MS + 1, ScreenshotPacer.DEFAULT_INTERVAL_MS)
    }

    private companion object {
        const val CAPTURE_MS = 200L
        const val PARTIAL_MS = 100L
        const val PLATFORM_INTERVAL_MS = 333L
    }
}
