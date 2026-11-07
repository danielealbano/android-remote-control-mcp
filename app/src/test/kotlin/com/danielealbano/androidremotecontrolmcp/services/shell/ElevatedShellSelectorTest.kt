package com.danielealbano.androidremotecontrolmcp.services.shell

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ElevatedShellSelector")
class ElevatedShellSelectorTest {
    private val shizukuStatus = mockk<ShizukuStatusProvider>()
    private val shizuku = mockk<ShizukuShellBackend>()
    private val su = mockk<SuShell>()
    private val selector = ElevatedShellSelector(shizukuStatus, shizuku, su)

    private val notReady =
        listOf(
            ShizukuState.NotInstalled,
            ShizukuState.NotRunning,
            ShizukuState.Outdated,
            ShizukuState.NotGranted,
        )

    @Test
    fun `Shizuku ready wins over su`() {
        every { shizukuStatus.state() } returns ShizukuState.Ready(2000)
        every { su.locate() } returns "/system/bin/su"

        assertSame(shizuku, selector.select())
    }

    @Test
    fun `falls back to su when Shizuku is not ready`() {
        every { su.locate() } returns "/system/bin/su"

        notReady.forEach { state ->
            every { shizukuStatus.state() } returns state

            assertSame(su, selector.select(), state.toString())
        }
    }

    @Test
    fun `fails with the Shizuku reason when su is missing`() {
        every { su.locate() } returns null

        notReady.forEach { state ->
            every { shizukuStatus.state() } returns state

            val error = assertThrows<McpToolException.PermissionDenied> { selector.select() }

            assertTrue(error.message.orEmpty().contains(shizukuUnavailableReason(state)), error.message)
            assertTrue(error.message.orEmpty().contains("su was not found"), error.message)
        }
    }

    @Test
    fun `backends lists Shizuku and su`() {
        assertEquals(listOf(shizuku, su), selector.backends)
    }
}
