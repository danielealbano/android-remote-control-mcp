package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.PendingElevation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ElevationApprovalViewModel")
class ElevationApprovalViewModelTest {
    private val requests = MutableStateFlow(listOf(PendingElevation(4, "id", "why")))
    private val coordinator = mockk<ElevationApprovalCoordinator>(relaxed = true) { every { pending } returns requests }
    private val viewModel = ElevationApprovalViewModel(coordinator)

    @Test
    fun `exposes the coordinator's pending requests`() {
        assertEquals(requests.value, viewModel.pending.value)
    }

    @Test
    fun `approve and deny resolve the request`() {
        viewModel.approve(4)
        viewModel.deny(4)

        verify(exactly = 1) { coordinator.resolve(4, true) }
        verify(exactly = 1) { coordinator.resolve(4, false) }
    }
}
