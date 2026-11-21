package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@DisplayName("OkHttpOAuthHttpFetcher")
class OkHttpOAuthHttpFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: OkHttpOAuthHttpFetcher

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        server =
            MockWebServer().also {
                it.useHttps(serverCerts.sslSocketFactory())
                it.start(InetAddress.getLoopbackAddress(), 0)
            }
        // The production settings, with only the DNS (the test server is on loopback) and the TLS trust changed.
        fetcher =
            OkHttpOAuthHttpFetcher {
                OkHttpOAuthHttpFetcher
                    .clientBuilder()
                    .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                    .dns { listOf(InetAddress.getLoopbackAddress()) }
                    .build()
            }
    }

    @AfterEach
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    private fun url(path: String) = "https://$HOST:${server.port}$path"

    private fun fetched(result: FetchResult): FetchedBody {
        val fetched = assertInstanceOf(FetchResult.Fetched::class.java, result)
        return fetched.body
    }

    private fun failure(result: FetchResult): String = assertInstanceOf(FetchResult.Failed::class.java, result).reason

    @Test
    @DisplayName("returns the body and content type of a 200, asking with the given Accept")
    fun returnsBody() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("{}")
                    .build(),
            )
            val body = fetched(fetcher.get(url("/doc"), "application/json", MAX))
            assertEquals("application/json", body.contentType)
            assertArrayEquals("{}".encodeToByteArray(), body.bytes)
            assertEquals("application/json", server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("Accept"))
        }

    @Test
    @DisplayName("never follows a redirect")
    fun doesNotFollowRedirects() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", url("/other"))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("{}")
                    .build(),
            )
            assertEquals("HTTP 302", failure(fetcher.get(url("/doc"), "application/json", MAX)))
            assertEquals(1, server.requestCount)
        }

    @Test
    @DisplayName("refuses any status other than 200 and an empty body")
    fun refusesOtherStatuses() =
        runTest {
            listOf(201, 203, 404, 500).forEach { code ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(code)
                        .body("{}")
                        .build(),
                )
                assertEquals("HTTP $code", failure(fetcher.get(url("/doc"), "application/json", MAX)))
            }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("")
                    .build(),
            )
            assertEquals("empty", failure(fetcher.get(url("/doc"), "application/json", MAX)))
        }

    @Test
    @DisplayName("reads a body of exactly the limit and refuses a larger one")
    fun capsTheBody() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("x".repeat(MAX))
                    .build(),
            )
            assertEquals(MAX, fetched(fetcher.get(url("/doc"), "application/json", MAX)).bytes.size)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("x".repeat(MAX + 1))
                    .build(),
            )
            assertEquals("larger than $MAX bytes", failure(fetcher.get(url("/doc"), "application/json", MAX)))
        }

    @Test
    @DisplayName("refuses plain http, IP-literal hosts and malformed URLs without connecting")
    fun refusesBeforeConnecting() =
        runTest {
            mapOf(
                "http://$HOST:${server.port}/doc" to "not https",
                "https://127.0.0.1:${server.port}/doc" to "an IP-literal host",
                "https://[::1]:${server.port}/doc" to "an IP-literal host",
                "https://8.8.8.8/doc" to "an IP-literal host",
                "not a url" to "not a URL",
            ).forEach { (url, reason) -> assertEquals(reason, failure(fetcher.get(url, "application/json", MAX)), url) }
            assertEquals(0, server.requestCount)
        }

    @Test
    @DisplayName("reports a call running past its limit as timed out")
    fun reportsTimeout() =
        runTest {
            val quick =
                OkHttpOAuthHttpFetcher {
                    OkHttpOAuthHttpFetcher
                        .clientBuilder()
                        .callTimeout(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                        .dns { listOf(InetAddress.getLoopbackAddress()) }
                        .build()
                }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("{}")
                    .headersDelay(SHORT_TIMEOUT_MS * 10, TimeUnit.MILLISECONDS)
                    .build(),
            )
            assertEquals("timed out", failure(quick.get(url("/doc"), "application/json", MAX)))
        }

    @Test
    @DisplayName("reports an untrusted certificate as a TLS failure")
    fun reportsTlsFailure() =
        runTest {
            // The production trust: the test server's self-signed certificate is not trusted.
            val defaultTrust =
                OkHttpOAuthHttpFetcher {
                    OkHttpOAuthHttpFetcher
                        .clientBuilder()
                        .dns { listOf(InetAddress.getLoopbackAddress()) }
                        .build()
                }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("{}")
                    .build(),
            )
            assertEquals("TLS failed", failure(defaultTrust.get(url("/doc"), "application/json", MAX)))
        }

    @Test
    @DisplayName("reports any other failure by its exception, e.g. a refused connection")
    fun reportsOtherFailures() =
        runTest {
            val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            val result = fetcher.get("https://$HOST:$closedPort/doc", "application/json", MAX)
            assertEquals("ConnectException", failure(result))
        }

    @Test
    @DisplayName("the production fetcher never connects to a host with only special-use addresses")
    fun productionRefusesLoopbackHost() =
        runTest {
            val result = OkHttpOAuthHttpFetcher().get("https://localhost:${server.port}/doc", "application/json", MAX)
            assertEquals("localhost has no public address", failure(result))
            assertEquals(0, server.requestCount)
        }

    @Test
    @DisplayName("the production client follows no redirects, uses no proxy, filters DNS and limits the call")
    fun productionSettings() {
        val client = OkHttpOAuthHttpFetcher.clientBuilder().build()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertSame(PublicOnlyDns, client.dns)
        assertEquals(OAuthPolicy.OUTBOUND_FETCH_TIMEOUT_MS.toInt(), client.callTimeoutMillis)
    }

    @Test
    @DisplayName("PublicOnlyDns gives no address for a host with only special-use ones")
    fun publicOnlyDnsRefusesLoopback() {
        assertThrows(UnknownHostException::class.java) { PublicOnlyDns.lookup("localhost") }
    }

    private companion object {
        const val HOST = "docs.example.test"
        const val MAX = 64
        const val SHORT_TIMEOUT_MS = 200L
        val held: HeldCertificate =
            HeldCertificate
                .Builder()
                .commonName(HOST)
                .addSubjectAlternativeName(HOST)
                .build()
        val serverCerts: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts: HandshakeCertificates =
            HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
    }
}
