package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** An open download response, whatever its status; the caller MUST close it. */
interface DownloadResponse : Closeable {
    /** The HTTP status code. */
    val code: Int

    /** The length from the response headers, or -1 when unknown. */
    val contentLength: Long

    /** The response body. */
    val body: InputStream
}

/** Sends the GET request of `download_from_url`, applying the download settings of a [ServerConfig]. */
interface DownloadHttpClient {
    /**
     * Requests [url] and returns the response once its headers arrived. Throws [IOException] when no
     * response could be obtained, including [LocalNetworkDownloadBlockedException] when
     * [ServerConfig.blockLocalNetworkDownloads] refused a connection.
     */
    fun open(
        url: URL,
        config: ServerConfig,
    ): DownloadResponse
}

/**
 * OkHttp implementation. Each download gets its own client, so its own connection pool: a connection opened while
 * the local network block was off can never be reused once it is on. With the block on, downloads connect directly,
 * never through a system proxy, so every address connected to is the real one and can be checked.
 *
 * Otherwise it behaves as the platform `HttpURLConnection` did before: redirects followed within the same scheme only
 * (http to https and back are not), every TLS version and cipher suite the platform enables, HTTP/1.1, and the
 * platform User-Agent.
 */
class DownloadHttpClientImpl
    internal constructor(
        /** The addresses "Block Local Network Downloads" refuses; another predicate is for tests only. */
        private val isBlockedAddress: (InetAddress) -> Boolean,
    ) : DownloadHttpClient {
        @Inject
        constructor() : this(::isLocalNetworkAddress)

        override fun open(
            url: URL,
            config: ServerConfig,
        ): DownloadResponse {
            val client = buildClient(config)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { platformUserAgent()?.let { header(USER_AGENT_HEADER, it) } }
                    .build()
            val response =
                try {
                    client.newCall(request).execute()
                } catch (e: IOException) {
                    client.connectionPool.evictAll()
                    throw e
                }
            return OkHttpDownloadResponse(response, client)
        }

        internal fun buildClient(config: ServerConfig): OkHttpClient {
            val timeoutSeconds = config.downloadTimeoutSeconds.toLong()
            return OkHttpClient
                .Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                // No write timeout, as HttpURLConnection (OkHttp's own default is 10 s)
                .writeTimeout(0, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .connectionSpecs(listOf(PLATFORM_TLS, ConnectionSpec.CLEARTEXT))
                .protocols(listOf(Protocol.HTTP_1_1))
                .apply {
                    if (config.allowUnverifiedHttpsCerts) SslUtils.trustAllCertificates(this)
                    if (config.blockLocalNetworkDownloads) {
                        proxy(Proxy.NO_PROXY)
                        socketFactory(LocalNetworkBlockingSocketFactory(isBlockedAddress))
                    }
                }.build()
        }

        /** The User-Agent `HttpURLConnection` sends on Android (`Dalvik/...`), kept instead of OkHttp's own. */
        private fun platformUserAgent(): String? =
            System.getProperty(HTTP_AGENT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::toHeaderSafeAscii)

        private class OkHttpDownloadResponse(
            private val response: Response,
            private val client: OkHttpClient,
        ) : DownloadResponse {
            override val code: Int get() = response.code
            override val contentLength: Long get() = response.body.contentLength()
            override val body: InputStream get() = response.body.byteStream()

            override fun close() {
                response.close()
                client.connectionPool.evictAll()
            }
        }

        internal companion object {
            private const val USER_AGENT_HEADER = "User-Agent"
            private const val HTTP_AGENT_PROPERTY = "http.agent"
            private const val FIRST_PRINTABLE_ASCII = 0x20
            private const val LAST_PRINTABLE_ASCII = 0x7e

            /**
             * The TLS setup of the platform `HttpURLConnection`: every enabled version and suite, with extensions
             * (kept from [ConnectionSpec.MODERN_TLS]).
             */
            private val PLATFORM_TLS =
                ConnectionSpec
                    .Builder(ConnectionSpec.MODERN_TLS)
                    .allEnabledCipherSuites()
                    .allEnabledTlsVersions()
                    .build()

            /**
             * Replaces every code point outside printable ASCII with `?`, as `HttpURLConnection` does with
             * `http.agent`: OkHttp rejects such header values, and a device model may contain them.
             */
            internal fun toHeaderSafeAscii(value: String): String =
                buildString {
                    value.codePoints().forEach { codePoint ->
                        val printable = codePoint in FIRST_PRINTABLE_ASCII..LAST_PRINTABLE_ASCII
                        if (printable) appendCodePoint(codePoint) else append('?')
                    }
                }
        }
    }
