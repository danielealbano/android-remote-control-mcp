package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollService
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [AdbServiceTrampolineActivity].
 *
 * The ADB command contract (extra key and action values) is pinned by the constant tests; the action dispatch
 * is exercised through [AdbServiceTrampolineActivity.handle] on a spy (the mockable `Activity` is never created
 * through the framework). The lifecycle wrapper itself (`onCreate` + `finish`) is verified via on-device testing.
 */
@DisplayName("AdbServiceTrampolineActivity")
class AdbServiceTrampolineActivityTest {
    @Test
    fun `EXTRA_ACTION has expected value`() {
        assertEquals("action", AdbServiceTrampolineActivity.EXTRA_ACTION)
    }

    @Test
    fun `ACTION_VALUE_START has expected value`() {
        assertEquals("start", AdbServiceTrampolineActivity.ACTION_VALUE_START)
    }

    @Test
    fun `ACTION_VALUE_STOP has expected value`() {
        assertEquals("stop", AdbServiceTrampolineActivity.ACTION_VALUE_STOP)
    }

    @Test
    fun `ACTION_VALUE_ENROLL_TUNNEL has expected value`() {
        assertEquals("enroll-tunnel", AdbServiceTrampolineActivity.ACTION_VALUE_ENROLL_TUNNEL)
    }

    @Nested
    @DisplayName("handle")
    inner class Handle {
        private val activity = spyk<AdbServiceTrampolineActivity>()

        private fun withStatus(status: ServerStatus) {
            mockkObject(McpServerService.Companion)
            every { McpServerService.serverStatus } returns MutableStateFlow(status)
        }

        private fun prepare(status: ServerStatus = ServerStatus.Stopped) {
            every { activity.startForegroundService(any()) } returns null
            mockkObject(ArcTunnelEnrollService.Companion)
            every { ArcTunnelEnrollService.start(any(), any(), any()) } returns true
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
            withStatus(status)
        }

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        @Test
        fun `handle start starts the server service`() {
            prepare()

            activity.handle(AdbServiceTrampolineActivity.ACTION_VALUE_START)

            verify { anyConstructed<Intent>().setAction(McpServerService.ACTION_START) }
            verify(exactly = 1) { activity.startForegroundService(any()) }
        }

        @Test
        fun `handle enroll-tunnel starts the enrollment service`() {
            prepare()

            activity.handle(AdbServiceTrampolineActivity.ACTION_VALUE_ENROLL_TUNNEL)

            verify(exactly = 1) { ArcTunnelEnrollService.start(activity, null, false) }
        }

        @Test
        fun `handle enroll-tunnel is ignored while the server runs`() {
            prepare(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"))

            activity.handle(AdbServiceTrampolineActivity.ACTION_VALUE_ENROLL_TUNNEL)

            verify(exactly = 0) { ArcTunnelEnrollService.start(any(), any(), any()) }
        }

        @Test
        fun `handle unknown action does nothing`() {
            prepare()

            activity.handle("nope")

            verify(exactly = 0) { activity.startForegroundService(any()) }
            verify(exactly = 0) { ArcTunnelEnrollService.start(any(), any(), any()) }
        }
    }
}
