package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHttp
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.net.ssl.SSLException

/** A downloaded body: at most the byte limit it was fetched with, never empty. */
class FetchedBody(
    val contentType: String?,
    val bytes: ByteArray,
)

/** The outcome of an [OAuthHttpFetcher] download. */
sealed interface FetchResult {
    class Fetched(
        val body: FetchedBody,
    ) : FetchResult

    /** [reason] says why, for the server log (e.g. `HTTP 403`, `larger than 5120 bytes`, `timed out`). */
    class Failed(
        val reason: String,
    ) : FetchResult
}

/**
 * Downloads what the OAuth server needs from the internet: Client ID Metadata Documents and client logos. Anyone who
 * can reach `/authorize` decides what is downloaded, so every download is guarded against server-side request forgery.
 */
interface OAuthHttpFetcher {
    /**
     * GETs [url] with `Accept: [accept]`. Fails, with the reason, when it is not `https`, has an IP-literal host or a
     * host with no public address, answers a status other than 200 (redirects are never followed), sends more than
     * [maxBytes] or an empty body, or the call, its address lookup included, runs past
     * [OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS].
     */
    suspend fun get(
        url: String,
        accept: String,
        maxBytes: Int,
    ): FetchResult
}

/**
 * [OAuthHttpFetcher] over OkHttp. It connects only to the addresses [PublicOnlyDns] returns, so a host resolving to a
 * special-use address (the phone's LAN, loopback...) is never contacted, and no proxy is used so the check cannot be
 * bypassed. No cookies, no cache.
 */
class OkHttpOAuthHttpFetcher internal constructor(
    clientFactory: () -> OkHttpClient,
) : OAuthHttpFetcher {
    @Inject
    constructor() : this({ clientBuilder().build() })

    private val client by lazy(clientFactory)

    override suspend fun get(
        url: String,
        accept: String,
        maxBytes: Int,
    ): FetchResult {
        val httpUrl = url.toHttpUrlOrNull()
        return when {
            httpUrl == null -> FetchResult.Failed("not a URL")
            !httpUrl.isHttps -> FetchResult.Failed("not https")
            LogoUrlPolicy.isIpLiteral(httpUrl.host) -> FetchResult.Failed("an IP-literal host")
            else -> download(httpUrl, accept, maxBytes)
        }
    }

    private suspend fun download(
        httpUrl: HttpUrl,
        accept: String,
        maxBytes: Int,
    ): FetchResult {
        val request =
            Request
                .Builder()
                .url(httpUrl)
                .header("Accept", accept)
                .build()
        val startedNs = System.nanoTime()
        return try {
            ArcTunnelHttp.await(client.newCall(request)) { readCapped(it, maxBytes) }
        } catch (e: IOException) {
            Log.d(TAG, "Download from ${httpUrl.host} failed: ${e.javaClass.simpleName}")
            // Whatever failed once the call timeout had run out, the download timed out.
            val limitNs = TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMillis.toLong())
            val timedOut = limitNs > 0 && System.nanoTime() - startedNs >= limitNs
            FetchResult.Failed(failureReason(e, timedOut))
        }
    }

    /** The body when the status is 200 and it has 1 to [maxBytes] bytes, else why not. */
    private fun readCapped(
        response: Response,
        maxBytes: Int,
    ): FetchResult {
        val source = response.body.source()
        return when {
            response.code != HTTP_OK -> FetchResult.Failed("HTTP ${response.code}")

            // Asking for one byte more than the limit: getting it means the body is too large.
            source.request(maxBytes + 1L) -> FetchResult.Failed("larger than $maxBytes bytes")

            source.buffer.size == 0L -> FetchResult.Failed("empty")

            else -> FetchResult.Fetched(FetchedBody(response.header("Content-Type"), source.buffer.readByteArray()))
        }
    }

    private fun failureReason(
        failure: IOException,
        timedOut: Boolean,
    ): String =
        when {
            timedOut || failure is SocketTimeoutException -> "timed out"
            failure is UnknownHostException -> failure.message ?: "unknown host"
            failure is SSLException -> "TLS failed"
            failure is InterruptedIOException -> "interrupted"
            else -> failure.javaClass.simpleName
        }

    internal companion object {
        /** The production client settings, which the tests build on. */
        fun clientBuilder(): OkHttpClient.Builder =
            OkHttpClient
                .Builder()
                .dns(PublicOnlyDns)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        private const val TAG = "MCP:OAuthFetch"
        private const val HTTP_OK = 200
    }
}

/** [PublicDns] over [Dns.SYSTEM], given up after [OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS]. */
internal object PublicOnlyDns : Dns by PublicDns(Dns.SYSTEM, OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS)

/**
 * [resolver] without the special-use addresses ([PublicAddresses]); a host left with none does not resolve. The lookup
 * runs on a thread of its own and is given up after [timeoutMs], because OkHttp's call timeout cannot interrupt a
 * lookup: a stalled resolver would otherwise hold the request past it.
 */
internal class PublicDns(
    private val resolver: Dns,
    private val timeoutMs: Long,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        resolve(hostname)
            .filter(PublicAddresses::isPublic)
            .ifEmpty { throw UnknownHostException("$hostname has no public address") }

    private fun resolve(hostname: String): List<InetAddress> {
        val lookup = LOOKUPS.submit(Callable { resolver.lookup(hostname) })
        val failure: Throwable =
            try {
                return lookup.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                // What the resolver threw, as if it had run on this thread.
                e.cause ?: e
            } catch (e: TimeoutException) {
                UnknownHostException("$hostname did not resolve in $timeoutMs ms").apply { initCause(e) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                InterruptedIOException("lookup of $hostname interrupted").apply { initCause(e) }
            }
        lookup.cancel(true)
        throw failure
    }

    private companion object {
        const val LOOKUP_THREADS = 4
        const val IDLE_THREAD_SECONDS = 30L

        /** Shared by every lookup; idle threads end, so nothing stays running between logins. */
        val LOOKUPS =
            ThreadPoolExecutor(
                LOOKUP_THREADS,
                LOOKUP_THREADS,
                IDLE_THREAD_SECONDS,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
            ) { Thread(it, "MCP-OAuthDns").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }
    }
}
