package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelWatchdog")
class ArcTunnelWatchdogTest {
    private val cancelled = AtomicBoolean(false)
    private val call =
        mockk<Call> {
            every { isCanceled() } answers { cancelled.get() }
            every { cancel() } answers { cancelled.set(true) }
        }

    @Test
    fun `cancels the call after 90s of silence and sets silent`() =
        runTest {
            val silent = AtomicBoolean(false)
            val lastFrameAt = AtomicLong(0)
            val now = { testScheduler.currentTime * NANOS }
            val job = launch { ArcTunnelWatchdog.watch(call, lastFrameAt, silent, now) }

            advanceTimeBy(95_000)
            runCurrent()

            verify(exactly = 1) { call.cancel() }
            assertTrue(silent.get())
            job.join()
        }

    @Test
    fun `fresh frames keep the call alive`() =
        runTest {
            val silent = AtomicBoolean(false)
            val lastFrameAt = AtomicLong(0)
            val now = { testScheduler.currentTime * NANOS }
            val job = launch { ArcTunnelWatchdog.watch(call, lastFrameAt, silent, now) }

            repeat(10) {
                advanceTimeBy(30_000)
                lastFrameAt.set(testScheduler.currentTime * NANOS)
            }
            runCurrent()

            verify(exactly = 0) { call.cancel() }
            assertFalse(silent.get())
            cancelled.set(true)
            advanceTimeBy(ArcTunnelWatchdog.POLL_MS)
            job.join()
        }

    @Test
    fun `stops polling once the call is cancelled`() =
        runTest {
            cancelled.set(true)

            ArcTunnelWatchdog.watch(call, AtomicLong(0), AtomicBoolean(false)) { testScheduler.currentTime * NANOS }

            verify(exactly = 0) { call.cancel() }
        }

    private companion object {
        const val NANOS = 1_000_000L
    }
}
