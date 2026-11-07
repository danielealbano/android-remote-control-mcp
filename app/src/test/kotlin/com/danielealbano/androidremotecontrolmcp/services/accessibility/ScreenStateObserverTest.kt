package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScreenStateObserverTest {
    @Test
    fun `screen off clears the request before refresh and unlock only refreshes`() {
        val receiver = slot<BroadcastReceiver>()
        val context = mockk<Context>(relaxUnitFun = true)
        every {
            context.registerReceiver(capture(receiver), any<IntentFilter>(), Context.RECEIVER_NOT_EXPORTED)
        } returns null
        val calls = mutableListOf<String>()
        val observer = ScreenStateObserver(context, { calls += "clear" }) { calls += "refresh" }
        observer.start()
        val screenOff = mockk<Intent> { every { action } returns Intent.ACTION_SCREEN_OFF }
        receiver.captured.onReceive(context, screenOff)
        assertEquals(listOf("clear", "refresh"), calls)
        calls.clear()
        val unlocked = mockk<Intent> { every { action } returns Intent.ACTION_USER_PRESENT }
        receiver.captured.onReceive(context, unlocked)
        assertEquals(listOf("refresh"), calls)
        observer.close()
    }

    @Test
    fun `repeated connection setup registers once and destruction unregisters once`() {
        val context = mockk<Context>(relaxUnitFun = true)
        every { context.registerReceiver(any(), any<IntentFilter>(), Context.RECEIVER_NOT_EXPORTED) } returns null
        val observer = ScreenStateObserver(context) {}
        observer.start()
        observer.start()
        verify(exactly = 1) { context.registerReceiver(any(), any<IntentFilter>(), Context.RECEIVER_NOT_EXPORTED) }
        observer.close()
        observer.close()
        verify(exactly = 1) { context.unregisterReceiver(any()) }
        observer.start()
        verify(exactly = 2) { context.registerReceiver(any(), any<IntentFilter>(), Context.RECEIVER_NOT_EXPORTED) }
        observer.close()
        verify(exactly = 2) { context.unregisterReceiver(any()) }
    }
}
