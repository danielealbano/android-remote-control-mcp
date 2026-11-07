package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import javax.inject.Inject

class ElevatedShellSelector
    @Inject
    constructor(
        private val shizukuStatus: ShizukuStatusProvider,
        private val shizuku: ShizukuShellBackend,
        private val su: SuShell,
    ) {
        val backends: List<ShellBackend>
            get() = listOf(shizuku, su)

        fun select(): ShellBackend {
            val state = shizukuStatus.state()
            return when {
                state is ShizukuState.Ready -> shizuku
                su.locate() != null -> su
                else -> throw McpToolException.PermissionDenied(noBackendMessage(state))
            }
        }
    }

private fun noBackendMessage(state: ShizukuState): String =
    "Elevated commands cannot run: ${shizukuUnavailableReason(state)}, and su was not found."
