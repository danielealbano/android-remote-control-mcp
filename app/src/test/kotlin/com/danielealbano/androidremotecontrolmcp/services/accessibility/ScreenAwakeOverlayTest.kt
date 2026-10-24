package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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
    fun `server observer cancels a hold when sleep occurs during accessibility disconnect`() {
        val receiver = slot<BroadcastReceiver>()
        val context = mockk<Context>(relaxUnitFun = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.POWER_SERVICE) } returns power
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } returns keyguard
        every {
            context.registerReceiver(capture(receiver), any<IntentFilter>(), Context.RECEIVER_NOT_EXPORTED)
        } returns null
        val connection = ScreenAwakeConnection({ it() }, canRequest = { false })
        val observer = connection.observeScreenState(context)
        observer.start()
        connection.connect(overlay)
        connection.setRequested(true)
        connection.disconnect(overlay)
        val screenOff = mockk<Intent> { every { action } returns Intent.ACTION_SCREEN_OFF }
        receiver.captured.onReceive(context, screenOff)
        connection.connect(overlay)
        verify(exactly = 1) { windows.addView(view, any()) }
        observer.close()
    }

    @Test
    fun `new activity while disconnected and asleep cannot reappear after unlock`() {
        var eligible = false
        val connection = ScreenAwakeConnection({ it() }, canRequest = { eligible })
        connection.setRequested(true)
        eligible = true
        connection.connect(overlay)
        verify(exactly = 0) { windows.addView(any(), any()) }
        connection.setRequested(true)
        verify(exactly = 1) { windows.addView(view, any()) }
    }

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
    fun `manual sleep discards old request so unlocking cannot restore the window`() {
        overlay.setRequested(true)
        every { power.isInteractive } returns false
        overlay.refresh()
        verify(exactly = 1) { windows.removeView(view) }
        every { power.isInteractive } returns true
        every { keyguard.isKeyguardLocked } returns true
        overlay.refresh()
        every { keyguard.isKeyguardLocked } returns false
        overlay.refresh()
        verify(exactly = 1) { windows.addView(view, any()) }
        overlay.setRequested(true)
        verify(exactly = 2) { windows.addView(view, any()) }
    }

    @Test
    fun `screen off and later keyguard changes remove existing window`() {
        overlay.setRequested(true)
        every { power.isInteractive } returns false
        overlay.refresh()
        verify(exactly = 1) { windows.removeView(view) }
        every { power.isInteractive } returns true
        overlay.setRequested(true)
        every { keyguard.isKeyguardLocked } returns true
        overlay.refresh()
        verify(exactly = 2) { windows.addView(view, any()) }
        verify(exactly = 2) { windows.removeView(view) }
    }

    @Test
    fun `sleep cancels the connection request across a later reconnect`() {
        val connection = ScreenAwakeConnection({ it() }, canRequest = { true })
        connection.connect(overlay)
        connection.setRequested(true)
        every { power.isInteractive } returns false
        connection.refresh()
        connection.disconnect(overlay)
        every { power.isInteractive } returns true
        connection.connect(overlay)
        verify(exactly = 1) { windows.addView(view, any()) }
        connection.setRequested(true)
        verify(exactly = 2) { windows.addView(view, any()) }
    }

    @Test
    fun `request made while locked is not revived on unlock`() {
        val connection = ScreenAwakeConnection({ it() }, canRequest = { true })
        connection.connect(overlay)
        every { keyguard.isKeyguardLocked } returns true
        connection.setRequested(true)
        every { keyguard.isKeyguardLocked } returns false
        connection.refresh()
        verify(exactly = 0) { windows.addView(any(), any()) }
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
        val connection = ScreenAwakeConnection({ it() }, canRequest = { true })
        val replacement = mockk<ScreenAwakeOverlay>(relaxUnitFun = true)
        every { replacement.setRequested(any()) } answers { firstArg() }
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
        val connection = ScreenAwakeConnection({ pending += it }, canRequest = { true })
        val replacement = mockk<ScreenAwakeOverlay>(relaxUnitFun = true)
        every { replacement.setRequested(any()) } answers { firstArg() }
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
