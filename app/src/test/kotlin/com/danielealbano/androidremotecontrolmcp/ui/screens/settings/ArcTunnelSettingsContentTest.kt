package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ArcTunnelUrlState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelSettingsContent visibility rules")
class ArcTunnelSettingsContentTest {
    private val ready =
        ArcTunnelSettingsState(
            tunnelEnabled = true,
            enabled = true,
            ctCheckEnabled = true,
            baseAddress = "arc-tunnel.io",
            baseAddressValid = true,
            url = ArcTunnelUrlState.NotEnrolled,
            enrollmentState = ArcTunnelEnrollmentState.Idle,
            enrollmentRequested = true,
        )

    @Test
    fun `dialog needs request tunnel on not enrolled idle and server stopped`() {
        assertTrue(arcTunnelEnrollDialogVisible(ready))
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(enrollmentRequested = false)))
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(tunnelEnabled = false)))
    }

    @Test
    fun `no dialog while loading`() {
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(url = ArcTunnelUrlState.Loading)))
    }

    @Test
    fun `no dialog when enrolled`() {
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(url = ArcTunnelUrlState.Enrolled(URL))))
    }

    @Test
    fun `no dialog while enrolling`() {
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(enrollmentState = ArcTunnelEnrollmentState.Enrolling)))
    }

    @Test
    fun `dialog allowed after a failure when still requested`() {
        assertTrue(arcTunnelEnrollDialogVisible(ready.copy(enrollmentState = ArcTunnelEnrollmentState.Failed("x"))))
    }

    @Test
    fun `no dialog while the server runs`() {
        assertFalse(arcTunnelEnrollDialogVisible(ready.copy(enabled = false)))
    }

    @Test
    fun `set up button shows when enabled not enrolled and idle`() {
        assertTrue(arcTunnelSetUpVisible(ready.copy(enrollmentRequested = false)))
    }

    @Test
    fun `set up button hides while loading enrolled enrolling or failed`() {
        listOf(
            ready.copy(url = ArcTunnelUrlState.Loading),
            ready.copy(url = ArcTunnelUrlState.Enrolled(URL)),
            ready.copy(enrollmentState = ArcTunnelEnrollmentState.Enrolling),
            ready.copy(enrollmentState = ArcTunnelEnrollmentState.Failed("x")),
        ).forEach { assertFalse(arcTunnelSetUpVisible(it), it.toString()) }
    }

    private companion object {
        const val URL = "https://abcdefghij.d.arc-tunnel.io"
    }
}
