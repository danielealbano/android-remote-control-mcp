package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ReferenceCountedToolCallIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
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
                ScreenAwakeToolCallIndicator(backgroundScope, 60, { testScheduler.currentTime }) { awake = it }
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
                ScreenAwakeToolCallIndicator(backgroundScope, 60, { testScheduler.currentTime }) { awake = it }
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
    fun `disabled listener never requests an awake display`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var awake = false
            val listener =
                ScreenAwakeToolCallIndicator(backgroundScope, 0, { testScheduler.currentTime }) { awake = it }
            listener.onToolCallStarted("read")
            listener.onToolCallFinished("read")
            runCurrent()
            assertFalse(awake)
            listener.close()
            runCurrent()
        }
}
