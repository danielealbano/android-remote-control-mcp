package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ReferenceCountedToolCallIndicator
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAwakeToolCallIndicatorTest {
    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `real reference counted chain holds until last completion independently of visual setting`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var awake = false
            val listener =
                ScreenAwakeToolCallIndicator(backgroundScope, 60) { awake = it }
            val indicator = ReferenceCountedToolCallIndicator(listener)
            indicator.setEnabled(false)
            indicator.onToolCallStarted("first")
            indicator.onToolCallStarted("second")
            runCurrent()
            assertTrue(awake)
            indicator.onToolCallFinished("first")
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(awake)
            indicator.onToolCallFinished("second")
            runCurrent()
            advanceTimeBy(59_999)
            assertTrue(awake)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(awake)
            listener.close()
            runCurrent()
        }

    @Test
    fun `closing rejects queued starts and late completions and releases active hold`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var awake = false
            val listener =
                ScreenAwakeToolCallIndicator(backgroundScope, 60) { awake = it }
            listener.onToolCallStarted("read")
            runCurrent()
            assertTrue(awake)
            listener.onToolCallStarted("queued")
            listener.close()
            listener.onToolCallFinished("read")
            runCurrent()
            assertFalse(awake)
            advanceTimeBy(60_000)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `finishing an overlapping call cannot rearm a manually discarded hold`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var awake = false
            val listener = ScreenAwakeToolCallIndicator(backgroundScope, 60) { awake = it }
            val indicator = ReferenceCountedToolCallIndicator(listener)
            indicator.onToolCallStarted("first")
            indicator.onToolCallStarted("second")
            runCurrent()
            assertTrue(awake)
            awake = false
            indicator.onToolCallFinished("second")
            runCurrent()
            assertFalse(awake)
            indicator.onToolCallStarted("fresh")
            runCurrent()
            assertTrue(awake)
            listener.close()
            runCurrent()
        }

    @Test
    fun `failure in delayed release is contained by the listener`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            mockkStatic(Log::class)
            every { Log.w(any<String>(), any<String>(), any()) } returns 0
            try {
                var releaseAttempts = 0
                val listener =
                    ScreenAwakeToolCallIndicator(backgroundScope, 60) {
                        if (!it) {
                            releaseAttempts++
                            error("window unavailable")
                        }
                    }
                listener.onToolCallStarted("read")
                listener.onToolCallFinished("read")
                runCurrent()
                advanceTimeBy(60000)
                runCurrent()
                assertEquals(1, releaseAttempts)
            } finally {
                unmockkStatic(Log::class)
            }
        }

    @Test
    fun `close releases the hold even after its owner was cancelled`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var awake = false
            val listener = ScreenAwakeToolCallIndicator(owner, 60) { awake = it }
            listener.onToolCallStarted("read")
            runCurrent()
            assertTrue(awake)
            owner.cancel()
            listener.close()
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `queued close still releases the hold if its owner is cancelled before dispatch`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var awake = false
            val listener = ScreenAwakeToolCallIndicator(owner, 60) { awake = it }
            listener.onToolCallStarted("read")
            runCurrent()
            assertTrue(awake)
            listener.close()
            owner.cancel()
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `disabled listener never requests an awake display`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var awake = false
            val listener =
                ScreenAwakeToolCallIndicator(backgroundScope, 0) { awake = it }
            listener.onToolCallStarted("read")
            listener.onToolCallFinished("read")
            runCurrent()
            assertFalse(awake)
            listener.close()
            runCurrent()
        }
}
