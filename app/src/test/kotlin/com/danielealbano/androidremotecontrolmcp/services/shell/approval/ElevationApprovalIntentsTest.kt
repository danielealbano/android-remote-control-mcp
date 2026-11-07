package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ElevationApprovalIntents")
class ElevationApprovalIntentsTest {
    private val coordinator = mockk<ElevationApprovalCoordinator>(relaxed = true)

    private fun intent(
        id: Int?,
        approved: Boolean?,
    ): Intent =
        mockk {
            every { getIntExtra(ElevationApprovalIntents.EXTRA_REQUEST_ID, any()) } answers { id ?: secondArg() }
            every { getBooleanExtra(ElevationApprovalIntents.EXTRA_APPROVED, any()) } answers {
                approved ?: secondArg()
            }
        }

    @Test
    fun `approve extra resolves as approved`() {
        ElevationApprovalIntents.resolve(intent(3, true), coordinator)

        verify(exactly = 1) { coordinator.resolve(3, true) }
    }

    @Test
    fun `deny or missing extra resolves as denied`() {
        ElevationApprovalIntents.resolve(intent(3, false), coordinator)
        ElevationApprovalIntents.resolve(intent(3, null), coordinator)

        verify(exactly = 2) { coordinator.resolve(3, false) }
    }

    @Test
    fun `missing or zero id is ignored`() {
        ElevationApprovalIntents.resolve(intent(0, true), coordinator)
        ElevationApprovalIntents.resolve(intent(null, true), coordinator)

        verify(exactly = 0) { coordinator.resolve(any(), any()) }
    }
}
