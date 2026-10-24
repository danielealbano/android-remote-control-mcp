package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

@DisplayName("DownloadHttpClientImpl")
class DownloadHttpClientImplTest {
    private lateinit var server: MockWebServer
    private val client = DownloadHttpClientImpl()
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val loopbackHost: String = checkNotNull(loopback.hostAddress)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(loopback, 0)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun ok(body: String = BODY): MockResponse =
        MockResponse
            .Builder()
            .code(OK)
            .body(body)
            .build()

    private fun redirectTo(location: String): MockResponse =
        MockResponse
            .Builder()
            .code(FOUND)
            .addHeader("Location", location)
            .build()

    /** A URL with the server's IP literal as host (MockWebServer's own `url()` uses the host name). */
    private fun url(
        path: String = "/file.bin",
        scheme: String = "http",
    ): URL = URL(scheme, loopbackHost, server.port, path)

    private fun useHttps() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("untrusted.example").build()
        server.close()
        server = MockWebServer()
        server.useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(held)
                .build()
                .sslSocketFactory(),
        )
        server.start(loopback, 0)
    }

    private fun withHttpAgent(
        value: String,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(HTTP_AGENT)
        System.setProperty(HTTP_AGENT, value)
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(HTTP_AGENT) else System.setProperty(HTTP_AGENT, previous)
        }
    }

    /** The full chain of [e] (cause and suppressed), since OkHttp may wrap a connect failure. */
    private fun chainOf(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }.toList()

    @Test
    fun `returns status, length and body with the block off`() {
        server.enqueue(ok())

        client.open(url(), ServerConfig()).use { response ->
            assertEquals(OK, response.code)
            assertEquals(BODY.length.toLong(), response.contentLength)
            assertEquals(BODY, response.body.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `returns a non-2xx response instead of throwing`() {
        server.enqueue(MockResponse.Builder().code(NOT_FOUND).build())

        client.open(url(), ServerConfig()).use { response -> assertEquals(NOT_FOUND, response.code) }
    }

    @Test
    fun `refuses an IP literal on the local network with the block on`() {
        server.enqueue(ok())

        val e = assertThrows<IOException> { client.open(url(), ServerConfig(blockLocalNetworkDownloads = true)) }

        assertTrue(chainOf(e).any { it is LocalNetworkDownloadBlockedException }, "unexpected: $e")
        // The callers report the top-level message: it must name the switch
        assertTrue(e.message!!.contains(SWITCH_NAME), e.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses a host name resolving to the local network with the block on`() {
        server.enqueue(ok())
        val byName = URL("http", "localhost", server.port, "/file.bin")

        val e = assertThrows<IOException> { client.open(byName, ServerConfig(blockLocalNetworkDownloads = true)) }

        assertTrue(chainOf(e).any { it is LocalNetworkDownloadBlockedException }, "unexpected: $e")
        // The callers report the top-level message: it must name the switch
        assertTrue(e.message!!.contains(SWITCH_NAME), e.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses a redirect to a local address with the block on`() {
        // 127.0.0.2 stands for a local target and the first server for a public one: the redirect hop is checked.
        val target = MockWebServer()
        target.start(InetAddress.getByName(BLOCKED_LOOPBACK), 0)
        try {
            target.enqueue(ok())
            server.enqueue(redirectTo(URL("http", BLOCKED_LOOPBACK, target.port, "/inner").toString()))
            val secondLoopbackBlocked = DownloadHttpClientImpl { it.hostAddress == BLOCKED_LOOPBACK }

            val e =
                assertThrows<IOException> {
                    secondLoopbackBlocked.open(url(), ServerConfig(blockLocalNetworkDownloads = true))
                }

            assertTrue(chainOf(e).any { it is LocalNetworkDownloadBlockedException }, "unexpected: $e")
            // The callers report the top-level message: it must name the switch for a redirect hop too
            assertTrue(e.message!!.contains(SWITCH_NAME), e.message)
            assertEquals(1, server.requestCount)
            assertEquals(0, target.requestCount)
        } finally {
            target.close()
        }
    }

    @Test
    fun `a connection opened with the block off is not reused with the block on`() {
        server.enqueue(ok())
        server.enqueue(ok())

        client.open(url(), ServerConfig()).use { it.body.readBytes() }
        val e = assertThrows<IOException> { client.open(url(), ServerConfig(blockLocalNetworkDownloads = true)) }

        assertTrue(chainOf(e).any { it is LocalNetworkDownloadBlockedException }, "unexpected: $e")
        // The callers report the top-level message: it must name the switch
        assertTrue(e.message!!.contains(SWITCH_NAME), e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `follows a redirect within the same scheme`() {
        server.enqueue(redirectTo(url("/final").toString()))
        server.enqueue(ok())

        client.open(url(), ServerConfig()).use { response ->
            assertEquals(OK, response.code)
            assertEquals(BODY, response.body.readBytes().toString(Charsets.UTF_8))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not follow a redirect from http to https`() {
        server.enqueue(redirectTo(url("/secure", scheme = "https").toString()))

        client.open(url(), ServerConfig()).use { response -> assertEquals(FOUND, response.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `does not follow a redirect from https to http`() {
        val plain = MockWebServer()
        plain.start(loopback, 0)
        try {
            plain.enqueue(ok())
            useHttps()
            server.enqueue(redirectTo(URL("http", loopbackHost, plain.port, "/plain").toString()))

            client.open(url(scheme = "https"), ServerConfig(allowUnverifiedHttpsCerts = true)).use { response ->
                assertEquals(FOUND, response.code)
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, plain.requestCount)
        } finally {
            plain.close()
        }
    }

    @Test
    fun `rejects an untrusted certificate unless unverified certificates are allowed`() {
        useHttps()
        server.enqueue(ok())

        assertThrows<IOException> { client.open(url(scheme = "https"), ServerConfig()) }
        client.open(url(scheme = "https"), ServerConfig(allowUnverifiedHttpsCerts = true)).use { response ->
            assertEquals(OK, response.code)
        }
    }

    @Test
    fun `downloads over HTTPS through the checked sockets when the address is allowed`() {
        useHttps()
        server.enqueue(ok())
        val checked = mutableListOf<InetAddress>()
        val nothingBlocked =
            DownloadHttpClientImpl { address ->
                checked += address
                false
            }
        val config = ServerConfig(allowUnverifiedHttpsCerts = true, blockLocalNetworkDownloads = true)

        nothingBlocked.open(url(scheme = "https"), config).use { response ->
            assertEquals(OK, response.code)
            assertEquals(BODY, response.body.readBytes().toString(Charsets.UTF_8))
        }
        assertEquals(listOf(loopback), checked)
    }

    @Test
    fun `refuses an HTTPS IP literal on the local network with the block on`() {
        useHttps()
        server.enqueue(ok())
        val config = ServerConfig(allowUnverifiedHttpsCerts = true, blockLocalNetworkDownloads = true)

        val e = assertThrows<IOException> { client.open(url(scheme = "https"), config) }

        assertTrue(chainOf(e).any { it is LocalNetworkDownloadBlockedException }, "unexpected: $e")
        assertTrue(e.message!!.contains(SWITCH_NAME), e.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sends the platform user agent when the platform defines one`() {
        withHttpAgent("Dalvik/2.1.0 (Linux; U; Android 14)") {
            server.enqueue(ok())

            client.open(url(), ServerConfig()).use { it.body.readBytes() }

            assertEquals("Dalvik/2.1.0 (Linux; U; Android 14)", server.takeRequest().headers["User-Agent"])
        }
    }

    @Test
    fun `replaces non-ASCII characters of the platform user agent instead of failing`() {
        withHttpAgent("Dalvik/2.1.0 (Linux; U; Android 14; \u5c0f\u7c73 Build/AB1)") {
            server.enqueue(ok())

            client.open(url(), ServerConfig()).use { it.body.readBytes() }

            val sent = server.takeRequest().headers["User-Agent"]
            assertEquals("Dalvik/2.1.0 (Linux; U; Android 14; ?? Build/AB1)", sent)
        }
    }

    @Test
    fun `header-safe ASCII keeps printable ASCII and replaces each other code point with one question mark`() {
        assertEquals("ok ~", DownloadHttpClientImpl.toHeaderSafeAscii("ok ~"))
        assertEquals("a?b", DownloadHttpClientImpl.toHeaderSafeAscii("a\tb"))
        assertEquals("a?b", DownloadHttpClientImpl.toHeaderSafeAscii("a\uD83D\uDE00b"))
    }

    @Test
    fun `connects directly with the block on and through the system proxy settings with it off`() {
        assertEquals(Proxy.NO_PROXY, client.buildClient(ServerConfig(blockLocalNetworkDownloads = true)).proxy)
        assertEquals(null, client.buildClient(ServerConfig()).proxy)
    }

    @Test
    fun `uses every TLS version and cipher suite the platform enables, over HTTP 1_1`() {
        val built = client.buildClient(ServerConfig())
        val tls = built.connectionSpecs.first()

        assertTrue(tls.isTls)
        assertEquals(null, tls.tlsVersions)
        assertEquals(null, tls.cipherSuites)
        assertTrue(built.connectionSpecs.contains(ConnectionSpec.CLEARTEXT))
        assertEquals(listOf(Protocol.HTTP_1_1), built.protocols)
    }

    @Test
    fun `applies the configured timeouts`() {
        val built = client.buildClient(ServerConfig(downloadTimeoutSeconds = TIMEOUT_SECONDS))

        assertEquals(TIMEOUT_SECONDS * MS_PER_S, built.connectTimeoutMillis)
        assertEquals(TIMEOUT_SECONDS * MS_PER_S, built.readTimeoutMillis)
        assertEquals(0, built.writeTimeoutMillis)
        assertTrue(built.followRedirects)
        assertTrue(!built.followSslRedirects)
    }

    private companion object {
        const val BODY = "hello"
        const val OK = 200
        const val FOUND = 302
        const val NOT_FOUND = 404
        const val BLOCKED_LOOPBACK = "127.0.0.2"
        const val SWITCH_NAME = "'Block Local Network Downloads' is enabled"
        const val HTTP_AGENT = "http.agent"
        const val TIMEOUT_SECONDS = 42
        const val MS_PER_S = 1000
    }
}
