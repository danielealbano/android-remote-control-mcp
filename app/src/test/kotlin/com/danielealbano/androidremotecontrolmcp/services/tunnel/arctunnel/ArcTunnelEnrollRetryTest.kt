package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.ProviderException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelEnrollRetry")
class ArcTunnelEnrollRetryTest {
    private val nonces = mutableListOf<String>()
    private val attempts = mutableListOf<Pair<String, Long>>()

    private fun TestScope.attempt(failures: List<Throwable>): suspend (String) -> String {
        val queue = ArrayDeque(failures)
        return { nonce ->
            attempts += nonce to currentTime
            queue.removeFirstOrNull()?.let { throw it }
            "ok"
        }
    }

    private suspend fun freshNonce(): String = "n${nonces.size}".also { nonces += it }

    private suspend fun runWith(enroll: suspend (String) -> String): String =
        ArcTunnelEnrollRetry.run({ freshNonce() }, enroll) { _, _, _ -> }

    private fun refusal(
        retryable: Boolean,
        retryAfterSec: Long = 0,
    ) = ArcTunnelApiException("busy", retryable, retryAfterSec, 503)

    @Test
    fun `success on first attempt fetches one nonce`() =
        runTest {
            assertEquals("ok", runWith(attempt(emptyList())))
            assertEquals(listOf("n0"), nonces)
        }

    @Test
    fun `retryable refusal waits retry_after at least 1s with a fresh nonce`() =
        runTest {
            runWith(attempt(listOf(refusal(true, 0), refusal(true, 5))))

            assertEquals(listOf("n0" to 0L, "n1" to 1_000L, "n2" to 6_000L), attempts)
        }

    @Test
    fun `network error waits 2s with a fresh nonce`() =
        runTest {
            runWith(attempt(listOf(IOException("down"))))

            assertEquals(listOf("n0" to 0L, "n1" to 2_000L), attempts)
        }

    @Test
    fun `ProviderException waits 3s and keeps the nonce`() =
        runTest {
            runWith(attempt(listOf(ProviderException("keygen"))))

            assertEquals(listOf("n0" to 0L, "n0" to 3_000L), attempts)
            assertEquals(listOf("n0"), nonces)
        }

    @Test
    fun `non-retryable stops at once`() =
        runTest {
            val failure = refusal(false)

            val thrown = runCatching { runWith(attempt(listOf(failure))) }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(listOf("n0" to 0L), attempts)
        }

    @Test
    fun `gives up after 3 retries`() =
        runTest {
            val failures = List(5) { IOException("down $it") }

            val thrown = runCatching { runWith(attempt(failures)) }.exceptionOrNull()

            assertSame(failures[3], thrown)
            assertEquals(4, attempts.size)
        }

    @Test
    fun `cancellation is not retried`() =
        runTest {
            val cancellation = CancellationException("stop")

            val thrown = runCatching { runWith(attempt(listOf(cancellation))) }.exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(1, attempts.size)
            assertEquals(0L, currentTime)
        }
}
