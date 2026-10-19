package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScreenAwakeOverlayTest {
    private val windows = mockk<WindowManager>(relaxUnitFun = true)
    private val power = mockk<PowerManager>()
    private val keyguard = mockk<KeyguardManager>()
    private val view = mockk<View>()
    private val overlay = ScreenAwakeOverlay(windows, power, keyguard) { view }

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { power.isInteractive } returns true
        every { keyguard.isKeyguardLocked } returns false
    }

    @AfterEach
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `locked or sleeping device never adds a hold window`() {
        every { keyguard.isKeyguardLocked } returns true
        overlay.setRequested(true)
        every { keyguard.isKeyguardLocked } returns false
        every { power.isInteractive } returns false
        overlay.refresh()
        verify(exactly = 0) { windows.addView(any(), any()) }
    }

    @Test
    fun `screen off and later keyguard changes remove existing window`() {
        overlay.setRequested(true)
        every { power.isInteractive } returns false
        overlay.refresh()
        verify(exactly = 1) { windows.removeView(view) }
        every { power.isInteractive } returns true
        overlay.refresh()
        every { keyguard.isKeyguardLocked } returns true
        overlay.refresh()
        verify(exactly = 2) { windows.addView(view, any()) }
        verify(exactly = 2) { windows.removeView(view) }
    }

    @Test
    fun `failed add remains retryable and destroy removes window once`() {
        every { windows.addView(view, any()) } throws IllegalStateException("unavailable") andThen Unit
        overlay.setRequested(true)
        overlay.refresh()
        verify(exactly = 2) { windows.addView(view, any()) }
        overlay.close()
        overlay.close()
        verify(exactly = 1) { windows.removeView(view) }
    }

    @Test
    fun `reconnect reapplies a pending hold and old destruction cannot detach replacement`() {
        val connection = ScreenAwakeConnection { it() }
        val replacement = mockk<ScreenAwakeOverlay>(relaxUnitFun = true)
        connection.setRequested(true)
        connection.connect(overlay)
        connection.disconnect(overlay)
        connection.connect(replacement)
        verify(exactly = 1) { replacement.setRequested(true) }
        connection.disconnect(overlay)
        connection.setRequested(false)
        verify(exactly = 1) { replacement.setRequested(false) }
    }

    @Test
    fun `reconnect reapplies active request and stale queued update cannot change replacement`() {
        val pending = mutableListOf<() -> Unit>()
        val connection = ScreenAwakeConnection { pending += it }
        val replacement = mockk<ScreenAwakeOverlay>(relaxUnitFun = true)
        connection.connect(overlay)
        connection.setRequested(true)
        pending.removeAt(0).invoke()
        verify(exactly = 1) { windows.addView(view, any()) }
        connection.setRequested(false)
        connection.connect(replacement)
        pending.removeAt(0).invoke()
        verify(exactly = 0) { replacement.setRequested(true) }
        verify(exactly = 1) { replacement.setRequested(false) }
        connection.disconnect(overlay)
        connection.setRequested(false)
        pending.removeAt(0).invoke()
        verify(exactly = 2) { replacement.setRequested(false) }
    }
}
