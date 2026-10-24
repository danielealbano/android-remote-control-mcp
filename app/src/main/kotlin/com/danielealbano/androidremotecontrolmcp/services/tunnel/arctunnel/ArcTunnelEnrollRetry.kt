package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay
import java.io.IOException
import java.security.ProviderException

object ArcTunnelEnrollRetry {
    const val RETRIES = 3
    const val REFUSAL_WAIT_MS = 1_000L
    const val NETWORK_WAIT_MS = 2_000L
    const val KEYGEN_WAIT_MS = 3_000L
    private const val MS_PER_S = 1_000L

    /** The wait before the next attempt, or null when [t] is not retryable. */
    fun waitMs(t: Throwable): Long? =
        when (t) {
            is ArcTunnelApiException -> if (t.retryable) maxOf(t.retryAfterSec * MS_PER_S, REFUSAL_WAIT_MS) else null
            is IOException -> NETWORK_WAIT_MS
            is ProviderException -> KEYGEN_WAIT_MS
            else -> null
        }

    suspend fun <T> run(
        freshNonce: suspend () -> String,
        enroll: suspend (String) -> T,
        onRetry: (Int, Long, Throwable) -> Unit,
    ): T {
        var nonce: String? = null
        var retry = 0
        while (true) {
            val attempt = runCatching { enroll(nonce ?: freshNonce().also { nonce = it }) }
            val failure = attempt.exceptionOrNull() ?: return attempt.getOrThrow()
            val wait = waitMs(failure)
            if (wait == null || retry >= RETRIES) throw failure
            retry++
            onRetry(retry, wait, failure)
            delay(wait)
            if (failure !is ProviderException) nonce = null
        }
    }
}
