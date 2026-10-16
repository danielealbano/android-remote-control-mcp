package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.SystemClock
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityToolCallIndicatorTest {
    @BeforeEach
    fun setUp() {
        mockkObject(McpAccessibilityService.Companion)
        mockkStatic(SystemClock::class)
        every { McpAccessibilityService.setScreenAwake(any()) } returns Unit
        every { McpAccessibilityService.showToolCallIndicator(any()) } returns Unit
        every { McpAccessibilityService.hideToolCallIndicator() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(McpAccessibilityService.Companion)
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `indicator off still holds awake and live timeout change adjusts release`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
            val config = MutableStateFlow(ServerConfig())
            val settings = mockk<SettingsRepository>()
            every { settings.serverConfig } returns config
            val indicator = AccessibilityToolCallIndicator(backgroundScope, settings)
            indicator.setEnabled(false)
            indicator.onToolCallStarted("read")
            runCurrent()
            verify(exactly = 1) { McpAccessibilityService.setScreenAwake(true) }
            verify(exactly = 0) { McpAccessibilityService.showToolCallIndicator(any()) }
            indicator.onToolCallFinished("read")
            runCurrent()
            advanceTimeBy(90_000)
            verify(exactly = 0) { McpAccessibilityService.setScreenAwake(false) }
            config.value = config.value.copy(screenAwakeTimeoutSeconds = 60)
            runCurrent()
            verify(exactly = 1) { McpAccessibilityService.setScreenAwake(false) }
            indicator.close()
        }

    @Test
    fun `expiry uses main dispatcher even when server parent scope uses another dispatcher`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
            val settings = mockk<SettingsRepository>()
            every { settings.serverConfig } returns MutableStateFlow(ServerConfig())
            val otherDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val parent = CoroutineScope(backgroundScope.coroutineContext + otherDispatcher)
            val indicator = AccessibilityToolCallIndicator(parent, settings)
            indicator.onToolCallStarted("read")
            runCurrent()
            indicator.onToolCallFinished("read")
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            verify(exactly = 1) { McpAccessibilityService.setScreenAwake(false) }
            indicator.onToolCallStarted("next")
            runCurrent()
            verify(exactly = 2) { McpAccessibilityService.setScreenAwake(true) }
            indicator.close()
        }

    @Test
    fun `closing server rejects queued starts and late completions`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
            val settings = mockk<SettingsRepository>()
            every { settings.serverConfig } returns MutableStateFlow(ServerConfig())
            val indicator = AccessibilityToolCallIndicator(backgroundScope, settings)
            indicator.onToolCallStarted("read")
            indicator.close()
            indicator.onToolCallFinished("read")
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            verify(exactly = 0) { McpAccessibilityService.setScreenAwake(true) }
            verify(exactly = 1) { McpAccessibilityService.setScreenAwake(false) }
        }
}
