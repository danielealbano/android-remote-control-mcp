package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewals
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelRenewalViewModel")
class ArcTunnelRenewalViewModelTest {
    private val renewal = MutableStateFlow<ArcTunnelRenewalState>(ArcTunnelRenewalState.Idle)
    private val renewals = mockk<ArcTunnelRenewals>(relaxed = true) { every { state } returns renewal }
    private lateinit var viewModel: ArcTunnelRenewalViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = ArcTunnelRenewalViewModel(renewals)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `renewal mirrors the tunnel-start renewal state`() {
        renewal.value = ArcTunnelRenewalState.Failed("acme_rate_limited")

        assertEquals(ArcTunnelRenewalState.Failed("acme_rate_limited"), viewModel.renewal.value)
    }

    @Test
    fun `the modal shows only while renewing`() {
        assertFalse(viewModel.modalVisible.value)

        renewal.value = ArcTunnelRenewalState.Renewing
        assertTrue(viewModel.modalVisible.value)

        renewal.value = ArcTunnelRenewalState.Failed("acme_rate_limited")
        assertFalse(viewModel.modalVisible.value)
    }

    @Test
    fun `hiding the modal lasts for the renewal and the next renewal shows it again`() {
        renewal.value = ArcTunnelRenewalState.Renewing

        viewModel.hideModal()
        assertFalse(viewModel.modalVisible.value)
        assertEquals(ArcTunnelRenewalState.Renewing, viewModel.renewal.value)

        renewal.value = ArcTunnelRenewalState.Idle
        renewal.value = ArcTunnelRenewalState.Renewing
        assertTrue(viewModel.modalVisible.value)
    }

    @Test
    fun `the modal stays while the certificate is installed, and hidden if it was`() {
        renewal.value = ArcTunnelRenewalState.Renewing

        renewal.value = ArcTunnelRenewalState.Installing
        assertTrue(viewModel.modalVisible.value)

        renewal.value = ArcTunnelRenewalState.Renewing
        viewModel.hideModal()
        renewal.value = ArcTunnelRenewalState.Installing
        assertFalse(viewModel.modalVisible.value)
    }

    @Test
    fun `a retry after a failed renewal shows the modal even if it was hidden`() {
        renewal.value = ArcTunnelRenewalState.Renewing
        viewModel.hideModal()

        renewal.value = ArcTunnelRenewalState.Failed("acme_rate_limited")
        renewal.value = ArcTunnelRenewalState.Renewing

        assertTrue(viewModel.modalVisible.value)
    }

    @Test
    fun `retry delegates to the renewals`() {
        viewModel.retry()

        verify(exactly = 1) { renewals.retry() }
    }
}
