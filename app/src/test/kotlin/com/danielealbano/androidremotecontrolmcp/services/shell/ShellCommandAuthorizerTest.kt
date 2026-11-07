package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationApprovalNotifier
import com.danielealbano.androidremotecontrolmcp.services.shell.approval.ElevationVerdict
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShellCommandAuthorizerImpl")
class ShellCommandAuthorizerTest {
    private val approvals = mockk<ElevationApprovalCoordinator>()
    private val notifier = mockk<ElevationApprovalNotifier>(relaxed = true) { every { canNotify() } returns true }
    private val log = RecordingServerLogRepository()
    private val authorizer = ShellCommandAuthorizerImpl(approvals, notifier, log)
    private val elevationOn = ShellConfig(elevationEnabled = true)

    private fun request(
        command: String,
        elevated: Boolean = true,
    ): ShellAuthorizationRequest = ShellAuthorizationRequest(command, if (elevated) "j" else "", elevated)

    private suspend fun refusal(
        request: ShellAuthorizationRequest,
        config: ShellConfig,
        budgetMs: Long = BUDGET_MS,
    ): String {
        val error = runCatching { authorizer.authorize(request, config, budgetMs) }.exceptionOrNull()
        assertTrue(error is McpToolException.PermissionDenied, error.toString())
        return error?.message.orEmpty()
    }

    private fun shellMessages(): List<String> {
        val entries = log.entries.filter { it.type == ServerLogEntry.Type.SHELL }
        return entries.map { it.message }
    }

    @Test
    fun `normal command allowed without elevation`() =
        runTest {
            assertNull(authorizer.authorize(request("ls", elevated = false), ShellConfig(), BUDGET_MS))
            assertTrue(log.entries.isEmpty())
        }

    @Test
    fun `blocklist refuses a normal command without logging`() =
        runTest {
            val message = refusal(request("reboot", elevated = false), ShellConfig(blocklist = listOf("*reboot*")))

            assertTrue(message.contains("'*reboot*'"), message)
            assertTrue(shellMessages().isEmpty())
        }

    @Test
    fun `blocklist beats auto-allow for elevated commands and is logged`() =
        runTest {
            val config = elevationOn.copy(autoAllowElevation = true, blocklist = listOf("*reboot*"))

            refusal(request("id; reboot"), config)

            assertEquals(
                listOf(
                    "Elevated command refused (the command matches the shell blocklist pattern '*reboot*'.) | " +
                        "justification: j | command: id; reboot",
                ),
                shellMessages(),
            )
        }

    @Test
    fun `elevation disabled`() =
        runTest {
            val message = refusal(request("id"), ShellConfig())

            assertTrue(message.contains("Shell settings"), message)
            assertEquals(1, shellMessages().size)
        }

    @Test
    fun `auto-allow`() =
        runTest {
            val grant = authorizer.authorize(request("id"), elevationOn.copy(autoAllowElevation = true), BUDGET_MS)

            assertEquals(ElevationGrant.AUTO_ALLOWED, grant)
            coVerify(exactly = 0) { approvals.request(any(), any(), any()) }
        }

    @Test
    fun `allowlist match`() =
        runTest {
            val config = elevationOn.copy(allowlist = listOf("ls -* /data"))

            val grant = authorizer.authorize(request("ls -la /data"), config, BUDGET_MS)

            assertEquals(ElevationGrant.ALLOWLISTED, grant)
            coVerify(exactly = 0) { approvals.request(any(), any(), any()) }
        }

    @Test
    fun `allowlist does not cross separators`() =
        runTest {
            coEvery { approvals.request(any(), any(), any()) } returns ElevationVerdict.APPROVED
            val config = elevationOn.copy(allowlist = listOf("ls -* /data"))

            authorizer.authorize(request("ls -la; id /data"), config, BUDGET_MS)

            coVerify(exactly = 1) { approvals.request("ls -la; id /data", "j", any()) }
        }

    @Test
    fun `approval approved`() =
        runTest {
            coEvery { approvals.request(any(), any(), any()) } returns ElevationVerdict.APPROVED

            val grant = authorizer.authorize(request("id"), elevationOn, BUDGET_MS)

            assertEquals(ElevationGrant.APPROVED, grant)
            coVerify(exactly = 1) { approvals.request("id", "j", 60_000) }
        }

    @Test
    fun `approval budget caps the timeout`() =
        runTest {
            coEvery { approvals.request(any(), any(), any()) } returns ElevationVerdict.APPROVED

            authorizer.authorize(request("id"), elevationOn, 15_000)

            coVerify(exactly = 1) { approvals.request("id", "j", 15_000) }
        }

    @Test
    fun `approval denied, timed out or cancelled`() =
        runTest {
            coEvery { approvals.request(any(), any(), any()) } returnsMany
                listOf(ElevationVerdict.DENIED, ElevationVerdict.TIMED_OUT, ElevationVerdict.CANCELLED)

            val denied = refusal(request("id"), elevationOn)
            val timedOut = refusal(request("id"), elevationOn)
            val cancelled = refusal(request("id"), elevationOn)

            assertEquals("Refused: the user denied this elevated command.", denied)
            assertEquals("Refused: the user did not answer the approval request within 60 seconds.", timedOut)
            assertEquals("Refused: the MCP server is stopping, so the approval was cancelled.", cancelled)
            assertEquals(3, shellMessages().size)
        }

    @Test
    fun `notifications off`() =
        runTest {
            every { notifier.canNotify() } returns false

            val message = refusal(request("id"), elevationOn)

            assertTrue(message.contains("notifications are off"), message)
            coVerify(exactly = 0) { approvals.request(any(), any(), any()) }
        }

    private companion object {
        const val BUDGET_MS = 90_000L
    }
}
