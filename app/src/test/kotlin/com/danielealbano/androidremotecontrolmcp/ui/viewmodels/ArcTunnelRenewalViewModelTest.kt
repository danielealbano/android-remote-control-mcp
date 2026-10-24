package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelRenewalViewModel")
class ArcTunnelRenewalViewModelTest {
    private val renewal = MutableStateFlow<ArcTunnelRenewalState>(ArcTunnelRenewalState.Idle)
    private val renewals = mockk<ArcTunnelRenewals>(relaxed = true) { every { state } returns renewal }
    private val viewModel = ArcTunnelRenewalViewModel(renewals)

    @Test
    fun `renewal mirrors the tunnel-start renewal state`() {
        renewal.value = ArcTunnelRenewalState.Failed("acme_rate_limited")

        assertEquals(ArcTunnelRenewalState.Failed("acme_rate_limited"), viewModel.renewal.value)
    }

    @Test
    fun `retry delegates to the renewals`() {
        viewModel.retry()

        verify(exactly = 1) { renewals.retry() }
    }
}
