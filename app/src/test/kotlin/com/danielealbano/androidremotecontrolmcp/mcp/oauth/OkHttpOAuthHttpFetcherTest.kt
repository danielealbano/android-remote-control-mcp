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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Proxy
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
            val body = fetcher.get(url("/doc"), "application/json", MAX)
            assertNotNull(body)
            assertEquals("application/json", body?.contentType)
            assertArrayEquals("{}".encodeToByteArray(), body?.bytes)
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
            assertNull(fetcher.get(url("/doc"), "application/json", MAX))
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
                assertNull(fetcher.get(url("/doc"), "application/json", MAX), "$code")
            }
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("")
                    .build(),
            )
            assertNull(fetcher.get(url("/doc"), "application/json", MAX))
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
            assertEquals(MAX, fetcher.get(url("/doc"), "application/json", MAX)?.bytes?.size)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("x".repeat(MAX + 1))
                    .build(),
            )
            assertNull(fetcher.get(url("/doc"), "application/json", MAX))
        }

    @Test
    @DisplayName("refuses plain http, IP-literal hosts and malformed URLs without connecting")
    fun refusesBeforeConnecting() =
        runTest {
            listOf(
                "http://$HOST:${server.port}/doc",
                "https://127.0.0.1:${server.port}/doc",
                "https://[::1]:${server.port}/doc",
                "https://8.8.8.8/doc",
                "not a url",
            ).forEach { assertNull(fetcher.get(it, "application/json", MAX), it) }
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
