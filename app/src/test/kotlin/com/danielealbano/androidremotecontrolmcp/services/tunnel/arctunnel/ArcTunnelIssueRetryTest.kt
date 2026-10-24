package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.ProviderException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ArcTunnelIssueRetry")
class ArcTunnelIssueRetryTest {
    private val fresh = mutableListOf<String>()
    private val attempts = mutableListOf<Pair<String, Long>>()

    private fun TestScope.issue(failures: List<Throwable>): suspend (String) -> Unit {
        val queue = ArrayDeque(failures)
        return { nonce ->
            attempts += nonce to currentTime
            queue.removeFirstOrNull()?.let { throw it }
        }
    }

    private suspend fun runWith(
        nonce: String?,
        issue: suspend (String) -> Unit,
    ) = ArcTunnelIssueRetry.run(nonce, issue, { "f${fresh.size}".also { fresh += it } }) { _, _, _ -> }

    private fun refusal(
        retryable: Boolean,
        retryAfterSec: Long = 0,
    ) = ArcTunnelApiException("busy", retryable, retryAfterSec, 503)

    @Test
    fun `uses the given nonce first then fresh ones`() =
        runTest {
            runWith("given", issue(listOf(IOException("a"), IOException("b"))))

            assertEquals(listOf("given", "f0", "f1"), attempts.map { it.first })
        }

    @Test
    fun `waits 5 10 15 seconds without retry_after`() =
        runTest {
            runWith("given", issue(listOf(refusal(true), refusal(true), refusal(true))))

            assertEquals(listOf(0L, 5_000L, 15_000L, 30_000L), attempts.map { it.second })
        }

    @Test
    fun `waits retry_after when given`() =
        runTest {
            runWith("given", issue(listOf(refusal(true, 7))))

            assertEquals(listOf(0L, 7_000L), attempts.map { it.second })
        }

    @Test
    fun `ProviderException regenerates over the same nonce up to 5 times without spending retries`() =
        runTest {
            runWith("given", issue(List(5) { ProviderException("keygen") }))

            assertEquals(List(6) { "given" }, attempts.map { it.first })
            assertTrue(fresh.isEmpty())
            assertEquals(10_000L, currentTime)
        }

    @Test
    fun `sixth ProviderException gives up`() =
        runTest {
            val failures = List(6) { ProviderException("keygen $it") }

            val thrown = runCatching { runWith("given", issue(failures)) }.exceptionOrNull()

            assertSame(failures[5], thrown)
            assertEquals(6, attempts.size)
        }

    @Test
    fun `non-retryable api error stops`() =
        runTest {
            val failure = refusal(false)

            val thrown = runCatching { runWith("given", issue(listOf(failure))) }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(1, attempts.size)
        }

    @Test
    fun `gives up after 3 retries`() =
        runTest {
            val failures = List(5) { IOException("down $it") }

            val thrown = runCatching { runWith(null, issue(failures)) }.exceptionOrNull()

            assertSame(failures[3], thrown)
            assertEquals(4, attempts.size)
        }
}
