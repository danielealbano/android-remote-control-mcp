package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelHttp
import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A downloaded body: at most the byte limit it was fetched with. */
class FetchedBody(
    val contentType: String?,
    val bytes: ByteArray,
)

/**
 * Downloads what the OAuth server needs from the internet: Client ID Metadata Documents and client logos. Anyone who
 * can reach `/authorize` decides what is downloaded, so every download is guarded against server-side request forgery.
 */
interface OAuthHttpFetcher {
    /**
     * GETs [url] with `Accept: [accept]`. Returns null on any failure: not `https`, an IP-literal host, a host with no
     * public address, a status other than 200 (redirects are never followed), more than [maxBytes], or the call
     * running past [OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS]. An empty body counts as a failure too.
     */
    suspend fun get(
        url: String,
        accept: String,
        maxBytes: Int,
    ): FetchedBody?
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
    ): FetchedBody? {
        val httpUrl = url.toHttpUrlOrNull()?.takeIf { it.isHttps && !isIpLiteral(it.host) } ?: return null
        val request =
            Request
                .Builder()
                .url(httpUrl)
                .header("Accept", accept)
                .build()
        return runCatching { ArcTunnelHttp.await(client.newCall(request)) { readCapped(it, maxBytes) } }
            .onFailure {
                if (it is CancellationException) throw it
                Log.d(TAG, "Download from ${httpUrl.host} failed: ${it.javaClass.simpleName}")
            }.getOrNull()
            ?.takeIf { it.bytes.isNotEmpty() }
    }

    /** The body, or an empty one standing for a refusal: a status other than 200, or more than [maxBytes]. */
    private fun readCapped(
        response: Response,
        maxBytes: Int,
    ): FetchedBody {
        val source = response.body.source()
        // Asking for one byte more than the limit: getting it means the body is too large.
        val fits = response.code == HTTP_OK && !source.request(maxBytes + 1L)
        return if (fits) FetchedBody(response.header("Content-Type"), source.buffer.readByteArray()) else EMPTY
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
        private val EMPTY = FetchedBody(null, ByteArray(0))
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
