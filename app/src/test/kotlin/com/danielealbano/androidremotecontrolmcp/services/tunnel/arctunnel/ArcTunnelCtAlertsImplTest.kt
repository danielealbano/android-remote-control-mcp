package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelCtAlertsImpl")
class ArcTunnelCtAlertsImplTest {
    private val context = mockk<Context>(relaxed = true)
    private val alert = ArcTunnelCtAlert("abc.d.example.test", "deadbeef")
    private lateinit var alerts: ArcTunnelCtAlertsImpl

    @BeforeEach
    fun setUp() {
        mockkObject(ArcTunnelCtNotifier)
        every { ArcTunnelCtNotifier.post(any(), any()) } just runs
        every { ArcTunnelCtNotifier.cancel(any()) } just runs
        alerts = ArcTunnelCtAlertsImpl(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ArcTunnelCtNotifier)
    }

    @Test
    fun `raise sets the flow and posts`() {
        alerts.raise(alert)

        assertEquals(alert, alerts.alert.value)
        verify(exactly = 1) { ArcTunnelCtNotifier.post(context, alert.fqdn) }
    }

    @Test
    fun `restore sets the flow without posting`() {
        alerts.restore(alert)

        assertEquals(alert, alerts.alert.value)
        verify(exactly = 0) { ArcTunnelCtNotifier.post(any(), any()) }
    }

    @Test
    fun `dismiss clears and cancels`() {
        alerts.raise(alert)

        alerts.dismiss()

        assertNull(alerts.alert.value)
        verify(exactly = 1) { ArcTunnelCtNotifier.cancel(context) }
    }
}
