package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/** JSON GET/POST on the IO dispatcher; a call is cancelled together with the calling coroutine. */
object ArcTunnelHttp {
    private const val TAG = "MCP:ArcTunnelHttp"
    private const val ENROLL_TIMEOUT_S = 300L
    private const val ISSUE_TIMEOUT_S = 400L
    private const val MALFORMED = "malformed_response"
    private val JSON = "application/json".toMediaType()

    suspend fun <T> get(
        serializer: KSerializer<T>,
        client: OkHttpClient,
        url: String,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return decode(serializer, execute(client, request))
    }

    suspend fun <T> post(
        serializer: KSerializer<T>,
        client: OkHttpClient,
        url: String,
        body: String,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()
        return decode(serializer, execute(client, request))
    }

    /**
     * Per-read timeout only, as the reference client: a total-call timeout would also count DNS, connect, the
     * handshake and the upload, and could abandon an enrollment the server is still completing.
     */
    fun enrollClient(client: OkHttpClient): OkHttpClient =
        client
            .newBuilder()
            .readTimeout(ENROLL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** Per-read timeout only (see [enrollClient]): the server may answer `/issue` up to 390 s later. */
    fun issueClient(client: OkHttpClient): OkHttpClient =
        client
            .newBuilder()
            .readTimeout(ISSUE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** Executes and returns the body of a 200; any other status is thrown as [ArcTunnelApiException]. */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
    ): String {
        val (code, body) = await(client.newCall(request))
        if (code != HttpURLConnection.HTTP_OK) fail(code, body)
        return body
    }

    /**
     * Runs [call] synchronously on the IO dispatcher, as the reference client does, and returns the status and
     * the body. Every exception reaches the caller: OkHttp's asynchronous path rethrows a non-I/O exception on
     * its own thread, which would crash the app. Cancelling the coroutine cancels the call, which aborts a
     * blocked connect, upload or body read.
     */
    suspend fun await(call: Call): Pair<Int, String> =
        coroutineScope {
            // UNDISPATCHED: the canceller is parked before the call starts, so a cancellation is never missed.
            val canceller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            try {
                withContext(Dispatchers.IO) { call.execute().use { it.code to it.body.string() } }
            } catch (e: IOException) {
                // A call cancelled by our own cancellation fails with an IOException: report the cancellation.
                ensureActive()
                throw e
            } finally {
                canceller.cancel()
            }
        }

    fun fail(
        status: Int,
        body: String,
    ): Nothing {
        val error =
            try {
                ArcTunnelJson.decodeFromString(ArcTunnelApiErrorBody.serializer(), body)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "undecodable error body (status $status): ${e.javaClass.simpleName}")
                ArcTunnelApiErrorBody()
            }
        val reason = error.reason.ifEmpty { "http_$status" }
        throw ArcTunnelApiException(reason, error.retryable, error.retryAfterSeconds, status)
    }

    /** A malformed 200 body is reported WITHOUT its content (it may carry a nonce or a chain). */
    private fun <T> decode(
        serializer: KSerializer<T>,
        body: String,
    ): T =
        try {
            ArcTunnelJson.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "malformed answer from the tunnel service: ${e.javaClass.simpleName}")
            throw ArcTunnelApiException(MALFORMED, false, 0, HttpURLConnection.HTTP_OK)
        }
}
