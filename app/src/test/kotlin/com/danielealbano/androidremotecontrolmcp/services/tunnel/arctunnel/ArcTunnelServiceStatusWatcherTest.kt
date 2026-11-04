package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Tunnel service status watcher")
class ArcTunnelServiceStatusWatcherTest {
    private val status = mockk<ArcTunnelServiceStatus>()
    private val alerts = mockk<ArcTunnelServiceAlerts>()
    private val watcher = ArcTunnelServiceStatusWatcher(status, alerts)

    @BeforeEach
    fun setUp() {
        every { alerts.show(any()) } returns true
        every { alerts.clear() } just runs
    }

    @Test
    fun `no problem posts nothing`() =
        runTest {
            coEvery { status.currentProblem() } returns null

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS * 3)
            runCurrent()

            verify(exactly = 0) { alerts.show(any()) }
            job.cancel()
        }

    @Test
    fun `a problem posts once, not at every check`() =
        runTest {
            coEvery { status.currentProblem() } returns PROBLEM

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS * 3)
            runCurrent()

            verify(exactly = 1) { alerts.show(PROBLEM) }
            job.cancel()
        }

    @Test
    fun `a problem that could not be posted is posted once it can be, then not again`() =
        runTest {
            coEvery { status.currentProblem() } returns PROBLEM
            every { alerts.show(any()) } returnsMany listOf(false, true)

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS * 3)
            runCurrent()

            verify(exactly = 2) { alerts.show(PROBLEM) }
            job.cancel()
        }

    @Test
    fun `a changed problem updates the notification`() =
        runTest {
            val worse = ArcTunnelServiceProblem("downtime", listOf("Tunnels", "Database"))
            coEvery { status.currentProblem() } returnsMany listOf(PROBLEM, worse)

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS + 1)

            verifyOrder {
                alerts.show(PROBLEM)
                alerts.show(worse)
            }
            job.cancel()
        }

    @Test
    fun `a change that could not be posted still removes the old notification when the problem ends`() =
        runTest {
            val worse = ArcTunnelServiceProblem("downtime", listOf("Tunnels", "Database"))
            coEvery { status.currentProblem() } returnsMany listOf(PROBLEM, worse, null)
            every { alerts.show(any()) } returnsMany listOf(true, false)

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS + 1)
            advanceTimeBy(REFRESH_MS)

            verify(exactly = 1) { alerts.clear() }
            job.cancel()
        }

    @Test
    fun `back to operational removes the notification, and a new problem posts again`() =
        runTest {
            coEvery { status.currentProblem() } returnsMany listOf(PROBLEM, null, PROBLEM)

            val job = launch { watcher.run() }
            runCurrent()
            advanceTimeBy(REFRESH_MS + 1)
            advanceTimeBy(REFRESH_MS)

            verifyOrder {
                alerts.show(PROBLEM)
                alerts.clear()
                alerts.show(PROBLEM)
            }
            job.cancel()
        }

    @Test
    fun `the session ending removes the notification`() =
        runTest {
            coEvery { status.currentProblem() } returns PROBLEM
            val job = launch { watcher.run() }
            runCurrent()

            job.cancel()
            runCurrent()

            verify(exactly = 1) { alerts.clear() }
        }

    private companion object {
        const val REFRESH_MS = 300_000L
        val PROBLEM = ArcTunnelServiceProblem("degraded", listOf("Tunnels"))
    }
}
