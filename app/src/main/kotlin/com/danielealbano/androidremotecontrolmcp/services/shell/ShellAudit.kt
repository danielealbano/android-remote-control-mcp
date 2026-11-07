package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository

internal class ShellAudit(
    private val serverLog: ServerLogRepository,
) {
    fun notStarted(
        launch: ShellLaunch,
        reason: String,
    ) {
        val elevation = launch.elevation ?: return
        serverLog.log(
            ServerLogEntry.Type.SHELL,
            "Elevated command ${elevation.grant.wireName} but not started ($reason) | justification: " +
                "${elevation.justification} | command: ${launch.command}",
        )
    }

    fun starting(process: ShellProcess) {
        val elevation = process.launch.elevation ?: return
        serverLog.log(
            ServerLogEntry.Type.SHELL,
            "Elevated command ${elevation.grant.wireName}, process ${process.id} via " +
                "${process.backend.kind.wireName} | justification: ${elevation.justification} | " +
                "command: ${process.launch.command}",
        )
    }

    fun failedToStart(
        process: ShellProcess,
        reason: String,
    ) {
        if (!process.launch.elevated) return
        serverLog.log(ServerLogEntry.Type.SHELL, "Elevated process ${process.id} did not start: $reason")
    }

    fun ended(process: ShellProcess) {
        if (!process.launch.elevated) return
        val snapshot = process.snapshot()
        val reason = snapshot.failure?.let { " ($it)" }.orEmpty()
        serverLog.log(
            ServerLogEntry.Type.SHELL,
            "Elevated process ${snapshot.id} ended: ${snapshot.state.wireName}, exit code " +
                "${snapshot.exitCode ?: "none"}$reason",
        )
    }
}
