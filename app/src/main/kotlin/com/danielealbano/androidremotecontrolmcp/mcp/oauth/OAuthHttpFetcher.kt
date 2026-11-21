package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHttp
import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
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
     * [maxBytes] or an empty body, or the call runs past [OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS].
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
        val refusal =
            when {
                httpUrl == null -> "not a URL"
                !httpUrl.isHttps -> "not https"
                isIpLiteral(httpUrl.host) -> "an IP-literal host"
                else -> null
            }
        return if (httpUrl == null || refusal != null) {
            FetchResult.Failed(refusal ?: "not a URL")
        } else {
            download(httpUrl, accept, maxBytes)
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
        return runCatching { ArcTunnelHttp.await(client.newCall(request)) { readCapped(it, maxBytes) } }
            .getOrElse {
                if (it is CancellationException) throw it
                Log.d(TAG, "Download from ${httpUrl.host} failed: ${it.javaClass.simpleName}")
                FetchResult.Failed(failureReason(it))
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

    private fun failureReason(failure: Throwable): String =
        when (failure) {
            is InterruptedIOException -> "timed out"
            is UnknownHostException -> failure.message ?: "unknown host"
            is SSLException -> "TLS failed"
            else -> failure.javaClass.simpleName
        }

    private fun isIpLiteral(host: String): Boolean = ':' in host || IPV4_LITERAL.matches(host)

    internal companion object {
        /** The production client settings; tests change only the DNS and the TLS trust. */
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
        private val IPV4_LITERAL = Regex("^[0-9.]+$")
    }
}

/** [Dns.SYSTEM] without the special-use addresses ([PublicAddresses]); a host left with none does not resolve. */
internal object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM
            .lookup(hostname)
            .filter(PublicAddresses::isPublic)
            .ifEmpty { throw UnknownHostException("$hostname has no public address") }
}
