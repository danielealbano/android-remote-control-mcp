package com.danielealbano.androidremotecontrolmcp.services.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@DisplayName("ServerStartTracker")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ServerStartTrackerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = ServerStartTracker(scope, JOIN_TIMEOUT_MS)

    /** Only for the timeout itself: every other test must not depend on how fast the start ends. */
    private val impatientTracker = ServerStartTracker(scope, SHORT_JOIN_TIMEOUT_MS)
    private val entered = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `no start returns at once`() {
        assertTrue(tracker.cancelAndAwait())
    }

    @Test
    fun `a finished start returns at once`() {
        val done = CountDownLatch(1)
        tracker.launch { done.countDown() }
        done.await()

        assertTrue(tracker.cancelAndAwait())
    }

    @Test
    fun `a start in progress is cancelled at its next suspension point and waited for`() {
        val ranPastBlockingStep = AtomicBoolean(false)
        val reachedEnd = AtomicBoolean(false)
        tracker.launch {
            entered.countDown()
            // A blocking step (like the Netty bind) that only ends once the cancellation was requested.
            while (currentCoroutineContext().isActive) Thread.onSpinWait()
            ranPastBlockingStep.set(true)
            awaitCancellation()
            reachedEnd.set(true)
        }
        entered.await()

        assertTrue(tracker.cancelAndAwait())

        assertTrue(ranPastBlockingStep.get())
        assertFalse(reachedEnd.get())
    }

    @Test
    fun `a start that does not end in time reports it`() {
        val release = CountDownLatch(1)
        impatientTracker.launch {
            entered.countDown()
            release.await()
        }
        entered.await()

        try {
            assertFalse(impatientTracker.cancelAndAwait())
        } finally {
            release.countDown()
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 5_000L
        const val SHORT_JOIN_TIMEOUT_MS = 200L
    }
}
