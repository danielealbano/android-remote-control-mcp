package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShellLifecycleImpl")
class ShellLifecycleTest {
    private val processManager = mockk<ShellProcessManager>(relaxed = true)
    private val approvals = mockk<ElevationApprovalCoordinator>(relaxed = true)
    private val scratchDirectory = mockk<ShellScratchDirectory>(relaxed = true)
    private val lifecycle = ShellLifecycleImpl(processManager, approvals, scratchDirectory)

    @Test
    fun `start wipes the scratch folder and opens approvals and the manager`() {
        lifecycle.onServerStart()

        verifyOrder {
            scratchDirectory.reset()
            approvals.open()
            processManager.open()
        }
    }

    @Test
    fun `stopping closes the approvals only`() {
        lifecycle.onServerStopping()

        verify(exactly = 1) { approvals.close() }
        verify(exactly = 0) { processManager.shutdown() }
    }

    @Test
    fun `stop closes the approvals and shuts the manager down`() {
        lifecycle.onServerStop()

        verifyOrder {
            approvals.close()
            processManager.shutdown()
        }
    }
}
