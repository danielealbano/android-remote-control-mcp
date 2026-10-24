package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

@DisplayName("ArcTunnelRenewalsImpl")
class ArcTunnelRenewalsImplTest {
    private val enrollment = mockk<ArcTunnelEnrollment>(relaxed = true)
    private val identity = mockk<ArcTunnelIdentity>(relaxed = true)
    private val serverLog = RecordingServerLogRepository()
    private val renewals = ArcTunnelRenewalsImpl(enrollment, serverLog)

    @Test
    fun `a start renewal shows Renewing then Idle and logs it`() =
        runBlocking {
            val seen = AtomicReference<ArcTunnelRenewalState>()
            coEvery { enrollment.renew(identity, null) } coAnswers { seen.set(renewals.state.value) }

            renewals.renewForStart(identity)

            assertEquals(ArcTunnelRenewalState.Renewing, seen.get())
            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
            assertTrue(serverLog.entries.any { it.message.contains("renewed at start") })
        }

    @Test
    fun `a failed start renewal shows the reason and throws it`() =
        runBlocking {
            val failure = ArcTunnelApiException("acme_rate_limited", true, 300, 503)
            coEvery { enrollment.renew(identity, null) } throws failure

            val error = runCatching { renewals.renewForStart(identity) }.exceptionOrNull()

            assertEquals(failure, error)
            assertEquals(ArcTunnelRenewalState.Failed(failure.message.orEmpty()), renewals.state.value)
            assertTrue(serverLog.entries.any { it.message.contains("renewal failed") })
        }

    @Test
    fun `cancelling the caller cancels the renewal and shows nothing`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { enrollment.renew(identity, null) } coAnswers {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val caller = launch(Dispatchers.IO) { renewals.renewForStart(identity) }
            withTimeout(BUDGET_MS) { entered.await() }

            caller.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
            assertFalse(serverLog.entries.any { it.message.contains("renewal failed") })
        }

    @Test
    fun `retry releases awaitRetry`() =
        runBlocking {
            val waiting = async(Dispatchers.IO) { renewals.awaitRetry() }

            renewals.retry()

            withTimeout(BUDGET_MS) { waiting.await() }
        }

    @Test
    fun `a retry pressed before an attempt is dropped`() =
        runBlocking {
            renewals.retry()

            renewals.renewForStart(identity)

            assertNull(withTimeoutOrNull(NOTHING_MS) { renewals.awaitRetry() })
        }

    @Test
    fun `clear hides a failure`() =
        runBlocking {
            coEvery { enrollment.renew(identity, null) } throws IllegalStateException("refused")
            runCatching { renewals.renewForStart(identity) }

            renewals.clear()

            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        }

    private companion object {
        const val BUDGET_MS = 5_000L
        const val NOTHING_MS = 200L
    }
}
