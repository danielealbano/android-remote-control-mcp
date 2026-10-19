package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.Context
import android.content.IntentFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ScreenStateObserverTest {
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
