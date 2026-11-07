package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import javax.inject.Inject

interface ShellLifecycle {
    fun onServerStart()

    fun onServerStopping()

    fun onServerStop()
}

class ShellLifecycleImpl
    @Inject
    constructor(
        private val processManager: ShellProcessManager,
        private val approvals: ElevationApprovalCoordinator,
        private val scratchDirectory: ShellScratchDirectory,
    ) : ShellLifecycle {
        override fun onServerStart() {
            scratchDirectory.reset()
            approvals.open()
            processManager.open()
        }

        override fun onServerStopping() {
            approvals.close()
        }

        override fun onServerStop() {
            approvals.close()
            processManager.shutdown()
        }
    }
