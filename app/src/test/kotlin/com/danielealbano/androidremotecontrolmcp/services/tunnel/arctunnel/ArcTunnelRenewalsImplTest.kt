package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStoreException
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
            coEvery { enrollment.renew(identity, null, any()) } coAnswers {
                seen.set(renewals.state.value)
                thirdArg<() -> Unit>().invoke()
            }

            renewals.renewForStart(identity)

            assertEquals(ArcTunnelRenewalState.Renewing, seen.get())
            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
            assertTrue(serverLog.entries.any { it.message.contains("renewed at start") })
        }

    @Test
    fun `a failed start renewal shows the reason and throws it`() =
        runBlocking {
            val failure = ArcTunnelApiException("acme_rate_limited", true, 300, 503)
            coEvery { enrollment.renew(identity, null, any()) } throws failure

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
            coEvery { enrollment.renew(identity, null, any()) } coAnswers {
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

    /** A saved identity whose chain [complete] says reaches a trusted root, as an app from before the check left it. */
    private fun savedChain(
        root: File,
        complete: Boolean,
    ): Pair<ArcTunnelIdentity, ArcTunnelIdentityStore> {
        val keys = FakeArcTunnelKeyStore()
        val net = mockk<ArcTunnelNet>()
        every { net.reachesTrustedRoot(any()) } returns complete
        val saved = ArcTunnelTestIdentity.create(keys, net)
        val store = ArcTunnelIdentityStore(root, saved.hosts, keys)
        store.save(saved)
        val file = File(root, "${saved.hosts.envKey}.json")
        file.writeText(file.readText().replace(Regex(""","cert_check":\d+"""), ""))
        every { enrollment.store(any()) } returns store
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            store.save(saved)
        }
        return saved to store
    }

    private fun logged(text: String): Int = serverLog.entries.count { it.message.contains(text) }

    @Test
    fun `a complete saved chain is marked without a renewal and logged`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = true)

        renewals.checkSavedChain(saved)

        coVerify(exactly = 0) { enrollment.renew(any(), any(), any()) }
        assertEquals(ARC_TUNNEL_CERT_CHECK, store.load()?.certCheck)
        assertEquals(1, logged("chain checked: complete"))
    }

    @Test
    fun `an incomplete saved chain is renewed showing Renewing, and logged`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        val seen = AtomicReference<ArcTunnelRenewalState>()
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            seen.set(renewals.state.value)
            thirdArg<() -> Unit>().invoke()
            store.save(saved)
        }

        renewals.checkSavedChain(saved)

        coVerify(exactly = 1) { enrollment.renew(saved, null, any()) }
        assertEquals(ArcTunnelRenewalState.Renewing, seen.get())
        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(1, logged("chain is incomplete"))
        assertEquals(1, logged("replace the incomplete chain"))
    }

    @Test
    fun `a marked certificate is not checked or renewed again`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        store.markCertChecked()

        renewals.checkSavedChain(saved)

        coVerify(exactly = 0) { enrollment.renew(any(), any(), any()) }
        verify(exactly = 0) { saved.net.reachesTrustedRoot(any()) }
        assertTrue(serverLog.entries.isEmpty())
    }

    @Test
    fun `a chain still incomplete after the renewal is not renewed at the next check`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, _) = savedChain(root, complete = false)

        renewals.checkSavedChain(saved)
        renewals.checkSavedChain(saved)

        coVerify(exactly = 1) { enrollment.renew(saved, null, any()) }
    }

    @Test
    fun `a failed renewal does not throw, stays unmarked for the next start, and never asks for Retry`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        val failure = ArcTunnelApiException("acme_rate_limited", true, 300, 503)
        coEvery { enrollment.renew(saved, null, any()) } throws failure

        renewals.checkSavedChain(saved)

        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(0, store.load()?.certCheck)
        assertEquals(1, logged("check failed, starting with the current certificate: ${failure.message}"))
        assertEquals(0, logged("replace the incomplete chain"))
    }

    @Test
    fun `a check that cannot run does not throw and stays unmarked`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        every { saved.net.reachesTrustedRoot(any()) } throws KeyStoreException("no roots")

        renewals.checkSavedChain(saved)

        coVerify(exactly = 0) { enrollment.renew(any(), any(), any()) }
        assertEquals(0, store.load()?.certCheck)
        assertEquals(1, logged("check failed, starting with the current certificate: no roots"))
    }

    @Test
    fun `a renewal over the time limit is given up without throwing`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        renewals.chainRenewalTimeoutMs = NOTHING_MS
        coEvery { enrollment.renew(saved, null, any()) } coAnswers { awaitCancellation() }

        withTimeout(BUDGET_MS) { renewals.checkSavedChain(saved) }

        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(0, store.load()?.certCheck)
        assertEquals(1, logged("no certificate within"))
    }

    @Test
    fun `cancelling the caller cancels the chain renewal, throws and stays unmarked`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        val entered = CompletableDeferred<Unit>()
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val thrown = AtomicReference<Throwable>()
        val caller =
            launch(Dispatchers.IO) {
                try {
                    renewals.checkSavedChain(saved)
                } catch (e: CancellationException) {
                    thrown.set(e)
                    throw e
                }
            }
        withTimeout(BUDGET_MS) { entered.await() }

        caller.cancelAndJoin()

        assertTrue(thrown.get() is CancellationException, "the cancellation must reach the caller")
        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(0, store.load()?.certCheck)
        assertEquals(0, logged("check failed"))
    }

    @Test
    fun `a start renewal shows Installing once the certificate is received`() =
        runBlocking {
            val seen = AtomicReference<ArcTunnelRenewalState>()
            coEvery { enrollment.renew(identity, null, any()) } coAnswers {
                thirdArg<() -> Unit>().invoke()
                seen.set(renewals.state.value)
            }

            renewals.renewForStart(identity)

            assertEquals(ArcTunnelRenewalState.Installing, seen.get())
            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        }

    @Test
    fun `a chain renewal shows Installing once the certificate is received`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        val seen = AtomicReference<ArcTunnelRenewalState>()
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            seen.set(renewals.state.value)
            store.save(saved)
        }

        renewals.checkSavedChain(saved)

        assertEquals(ArcTunnelRenewalState.Installing, seen.get())
        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
    }

    @Test
    fun `a time limit landing after the certificate was received counts as renewed`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, store) = savedChain(root, complete = false)
        renewals.chainRenewalTimeoutMs = NOTHING_MS
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            store.save(saved)
            awaitCancellation()
        }

        withTimeout(BUDGET_MS) { renewals.checkSavedChain(saved) }

        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(1, logged("replace the incomplete chain"))
        assertEquals(0, logged("check failed"))
    }

    @Test
    fun `a chain renewal stopped once the certificate is received has logged the outcome`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, _) = savedChain(root, complete = false)
        val received = CompletableDeferred<Unit>()
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            received.complete(Unit)
            awaitCancellation()
        }
        val caller = launch(Dispatchers.IO) { renewals.checkSavedChain(saved) }
        withTimeout(BUDGET_MS) { received.await() }

        caller.cancelAndJoin()

        assertEquals(1, logged("replace the incomplete chain"))
        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
    }

    @Test
    fun `a chain renewal throwing after the certificate was received does not stay Installing or throw`(
        @TempDir root: File,
    ) = runBlocking {
        val (saved, _) = savedChain(root, complete = false)
        coEvery { enrollment.renew(saved, null, any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            throw IllegalStateException("broken")
        }

        renewals.checkSavedChain(saved)

        assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        assertEquals(1, logged("check failed, starting with the current certificate: broken"))
    }

    @Test
    fun `a start renewal throwing after the certificate was received does not stay Installing`() =
        runBlocking {
            coEvery { enrollment.renew(identity, null, any()) } coAnswers {
                thirdArg<() -> Unit>().invoke()
                throw IllegalStateException("broken")
            }

            val error = runCatching { renewals.renewForStart(identity) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "error $error")
            assertEquals(ArcTunnelRenewalState.Failed("broken"), renewals.state.value)
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
            coEvery { enrollment.renew(identity, null, any()) } throws IllegalStateException("refused")
            runCatching { renewals.renewForStart(identity) }

            renewals.clear()

            assertEquals(ArcTunnelRenewalState.Idle, renewals.state.value)
        }

    private companion object {
        const val BUDGET_MS = 5_000L
        const val NOTHING_MS = 200L
    }
}
