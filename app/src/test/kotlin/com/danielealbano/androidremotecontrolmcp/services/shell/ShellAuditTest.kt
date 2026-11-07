package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShellAudit")
class ShellAuditTest {
    private val log = RecordingServerLogRepository()
    private val audit = ShellAudit(log)
    private val backend = mockk<ShellBackend>(relaxed = true) { every { kind } returns ShellBackendKind.SHIZUKU }

    private fun launch(grant: ElevationGrant? = ElevationGrant.ALLOWLISTED): ShellLaunch =
        ShellLaunch("cmd", "/", emptyMap(), 30, grant?.let { ShellElevation("j", it) })

    private fun process(launch: ShellLaunch = launch()): ShellProcess =
        ShellProcess(7, launch, backend, ShellOutputBuffer(1024), 0)

    private fun messages(): List<String> = log.entries.filter { it.type == ServerLogEntry.Type.SHELL }.map { it.message }

    @Test
    fun `normal commands are never logged`() {
        val normal = launch(grant = null)
        val process = process(normal).apply { finish(ShellExit(0), 1) }

        audit.notStarted(normal, "reason")
        audit.starting(process)
        audit.failedToStart(process, "reason")
        audit.ended(process)

        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `starting`() {
        audit.starting(process())

        assertEquals(
            listOf("Elevated command allowlisted, process 7 via shizuku | justification: j | command: cmd"),
            messages(),
        )
    }

    @Test
    fun `failed to start`() {
        audit.failedToStart(process(), "boom")

        assertEquals(listOf("Elevated process 7 did not start: boom"), messages())
    }

    @Test
    fun `not started`() {
        audit.notStarted(launch(ElevationGrant.APPROVED), "no backend")

        assertEquals(
            listOf(
                "Elevated command approved by the user but not started (no backend) | justification: j | " +
                    "command: cmd",
            ),
            messages(),
        )
    }

    @Test
    fun `ended`() {
        val killed = process().apply { stop(ShellProcessState.KILLED) }
        killed.finish(ShellExit(null, "The server stopped"), 1)
        val exited = process().apply { finish(ShellExit(0), 1) }

        audit.ended(killed)
        audit.ended(exited)

        assertEquals(
            listOf(
                "Elevated process 7 ended: killed, exit code none (The server stopped)",
                "Elevated process 7 ended: exited, exit code 0",
            ),
            messages(),
        )
    }
}
