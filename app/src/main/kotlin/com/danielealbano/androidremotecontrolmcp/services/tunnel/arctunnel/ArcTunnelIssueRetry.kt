package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.delay
import java.io.IOException
import java.security.ProviderException

object ArcTunnelIssueRetry {
    const val RETRIES = 3
    const val KEYGEN_RETRIES = 5
    const val KEYGEN_WAIT_MS = 2_000L
    private const val STEP_WAIT_MS = 5_000L
    private const val MS_PER_S = 1_000L

    fun waitMs(
        t: Throwable,
        retry: Int,
    ): Long? =
        when (t) {
            is ArcTunnelApiException -> apiWaitMs(t, retry)
            is IOException -> (retry + 1) * STEP_WAIT_MS
            else -> null
        }

    private fun apiWaitMs(
        t: ArcTunnelApiException,
        retry: Int,
    ): Long? =
        when {
            !t.retryable -> null
            t.retryAfterSec > 0 -> t.retryAfterSec * MS_PER_S
            else -> (retry + 1) * STEP_WAIT_MS
        }

    suspend fun run(
        nonce: String?,
        issue: suspend (String) -> Unit,
        freshNonce: suspend () -> String,
        onRetry: (Int, Long, Throwable) -> Unit,
    ) {
        var current: String? = nonce
        var retry = 0
        var keygen = 0
        while (true) {
            val attempt = runCatching { issue(current ?: freshNonce().also { current = it }) }
            val failure = attempt.exceptionOrNull() ?: return
            if (failure is ProviderException && keygen < KEYGEN_RETRIES) {
                keygen++
                onRetry(keygen, KEYGEN_WAIT_MS, failure)
                delay(KEYGEN_WAIT_MS)
            } else {
                val wait = waitMs(failure, retry)
                if (wait == null || retry >= RETRIES) throw failure
                onRetry(retry + 1, wait, failure)
                delay(wait)
                current = null
                retry++
            }
        }
    }
}
