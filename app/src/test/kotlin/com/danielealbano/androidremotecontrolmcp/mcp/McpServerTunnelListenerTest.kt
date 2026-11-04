package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelTlsMaterial
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@DisplayName("McpServer tunnel listener")
class McpServerTunnelListenerTest {
    private val held =
        HeldCertificate
            .Builder()
            .ecdsa256()
            .commonName("127.0.0.1")
            .build()
    private val material = TunnelTlsMaterial(held.keyPair.private, listOf(held.certificate))
    private lateinit var server: McpServer
    private val serverLog = RecordingServerLogRepository()

    @BeforeEach
    fun setUp() {
        server =
            McpServer(
                ServerConfig(port = freePort(), bindingAddress = BindingAddress.LOCALHOST, bearerToken = TOKEN),
                null,
                mockk<Server>(relaxed = true),
                mockk(relaxed = true),
                OAuthServerDeps(
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                ),
                serverLog,
                InFlightToolCalls(),
                null,
            )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0, STOP_TIMEOUT_MS)
    }

    @Test
    fun `startTlsListener binds a random loopback port and serves health`() {
        server.start()

        val port = server.startTlsListener(material, null) {}

        assertTrue(port in RANDOM_PORT_RANGE, "port $port")
        assertEquals(200, healthStatus(port))
    }

    @Test
    fun `onRequest runs once per request`() {
        server.start()
        val counter = AtomicInteger()
        val port = server.startTlsListener(material, null) { counter.incrementAndGet() }

        repeat(3) { healthStatus(port) }

        assertEquals(3, counter.get())
    }

    @Test
    fun `requested port is honoured`() {
        server.start()
        val requested = freePort()

        assertEquals(requested, server.startTlsListener(material, requested) {})
        assertEquals(200, healthStatus(requested))
    }

    @Test
    fun `random ports differ across restarts`() {
        server.start()

        val ports =
            List(3) {
                val port = server.startTlsListener(material, null) {}
                server.stopTlsListener()
                port
            }

        assertTrue(ports.toSet().size > 1, "ports $ports")
    }

    @Test
    fun `random port bind failure is retried with another port`() {
        server.start()
        ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)).use { blocker ->
            val free = freePort()
            val candidates = ArrayDeque(listOf(blocker.localPort, free))
            server.tunnelPortChooser = { candidates.removeFirst() }

            assertEquals(free, server.startTlsListener(material, null) {})
        }
    }

    @Test
    fun `a held fixed port fails after the attempts`() {
        server.start()
        ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)).use { blocker ->
            val startedAt = System.nanoTime()

            val error = assertThrows<IllegalStateException> { server.startTlsListener(material, blocker.localPort) {} }

            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
            assertTrue(error.cause is IOException, "cause ${error.cause}")
            assertTrue(elapsedMs < FIXED_PORT_BUDGET_MS, "took $elapsedMs ms")
        }
    }

    @Test
    fun `startTlsListener while running throws`() {
        server.start()
        server.startTlsListener(material, null) {}

        assertThrows<IllegalStateException> { server.startTlsListener(material, null) {} }
    }

    @Test
    fun `startTlsListener without a running server throws`() {
        assertThrows<IllegalStateException> { server.startTlsListener(material, null) {} }
    }

    @Test
    fun `stopTlsListener is idempotent`() {
        server.start()
        server.startTlsListener(material, null) {}

        assertDoesNotThrow {
            server.stopTlsListener()
            server.stopTlsListener()
        }
    }

    @Test
    fun `stop also stops the tunnel listener`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        server.stop(0, STOP_TIMEOUT_MS)

        assertFalse(accepts(port))
    }

    @Test
    fun `HTTP-2 is negotiated over ALPN`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        okHttpGet(port, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), HEALTH).use {
            assertEquals(200, it.code)
            assertEquals(Protocol.HTTP_2, it.protocol)
        }
    }

    @Test
    fun `HTTP-1-1 is served when ALPN offers only http-1-1`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        okHttpGet(port, listOf(Protocol.HTTP_1_1), HEALTH).use {
            assertEquals(200, it.code)
            assertEquals(Protocol.HTTP_1_1, it.protocol)
        }
    }

    @Test
    fun `a client without ALPN is served HTTP-1-1`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val response = rawGet(port, HEALTH)

        assertTrue(response.startsWith("HTTP/1.1 200"), response)
    }

    @Test
    fun `HTTP-2 requests see the https scheme`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val body = http2Body(port, PROTECTED_RESOURCE)

        assertTrue(body.contains("\"resource\":\"https://$LOOPBACK:$port/mcp\""), body)
    }

    /** What a public client sends through the tunnel on port 443: an authority with no port. */
    @Test
    fun `an HTTP-2 authority with no port gives URLs with no port`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val metadata = http2Body(port, PROTECTED_RESOURCE, PUBLIC_HOST)

        assertTrue(metadata.contains("\"resource\":\"https://$PUBLIC_HOST/mcp\""), metadata)
        assertTrue(metadata.contains("\"authorization_servers\":[\"https://$PUBLIC_HOST\"]"), metadata)
    }

    @Test
    fun `an HTTP-2 authority naming port 443 gives URLs with no port`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val metadata = http2Body(port, PROTECTED_RESOURCE, "$PUBLIC_HOST:$HTTPS_DEFAULT_PORT")

        assertTrue(metadata.contains("\"resource\":\"https://$PUBLIC_HOST/mcp\""), metadata)
    }

    @Test
    fun `an HTTP-2 authority with a port keeps that port`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val metadata = http2Body(port, PROTECTED_RESOURCE, "$PUBLIC_HOST:$PUBLIC_PORT")

        assertTrue(metadata.contains("\"resource\":\"https://$PUBLIC_HOST:$PUBLIC_PORT/mcp\""), metadata)
    }

    @Test
    fun `HTTP-1-1 and HTTP-2 give the same URLs for the same host`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        for (host in listOf(PUBLIC_HOST, "$PUBLIC_HOST:$PUBLIC_PORT", "$LOOPBACK:$port")) {
            val overHttp1 =
                okHttpGet(port, listOf(Protocol.HTTP_1_1), PROTECTED_RESOURCE, host).use { it.body.string() }

            assertEquals(overHttp1, http2Body(port, PROTECTED_RESOURCE, host), host)
        }
    }

    /** Nothing on the way to this listener sets or strips these headers, so any client could put them there. */
    @Test
    fun `a client cannot choose the address it shows through the tunnel listener`() {
        server.start()
        val port = server.startTlsListener(material, null) {}
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

        for (protocol in listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) {
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .protocols(listOf(protocol, Protocol.HTTP_1_1).distinct())
                    .build()
            val request =
                Request
                    .Builder()
                    .url("https://$LOOPBACK:$port/mcp")
                    .header("CF-Connecting-IP", SPOOFED)
                    .header("X-Forwarded-For", SPOOFED)
                    .build()

            client.newCall(request).execute().use {
                assertEquals(protocol, it.protocol)
                assertEquals(HTTP_UNAUTHORIZED, it.code)
            }

            assertEquals(
                "Authentication failed from unknown",
                serverLog.entries.last { it.type == ServerLogEntry.Type.AUTH }.message,
                "$protocol",
            )
        }
    }

    @Test
    fun `HTTP-1-1 requests see the https scheme`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val body = okHttpGet(port, listOf(Protocol.HTTP_1_1), PROTECTED_RESOURCE).use { it.body.string() }

        assertTrue(body.contains("\"resource\":\"https://$LOOPBACK:$port/mcp\""), body)
    }

    @Test
    fun `requests without ALPN see the https scheme`() {
        server.start()
        val port = server.startTlsListener(material, null) {}

        val response = rawGet(port, PROTECTED_RESOURCE)

        assertTrue(response.contains("\"resource\":\"https://$LOOPBACK:$port/mcp\""), response)
    }

    @Test
    fun `a key that cannot be exported serves TLS`() {
        server.start()
        val key = OpaqueEcPrivateKey(held.keyPair.private as ECPrivateKey)
        val opaque = TunnelTlsMaterial(key, listOf(held.certificate))

        val port = server.startTlsListener(opaque, null) {}

        assertEquals(200, healthStatus(port))
    }

    private fun okHttpGet(
        port: Int,
        protocols: List<Protocol>,
        path: String,
        publicHost: String? = null,
    ): Response {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client =
            OkHttpClient
                .Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .hostnameVerifier { _, _ -> true }
                .protocols(protocols)
                .build()
        val request =
            Request
                .Builder()
                .url("https://$LOOPBACK:$port$path")
                // OkHttp sends this as `Host` over HTTP/1.1 and as `:authority` over HTTP/2.
                .apply { publicHost?.let { header("Host", it) } }
                .build()
        return client.newCall(request).execute()
    }

    /** The body of a GET that really went over HTTP/2, so `:authority` is what the server read. */
    private fun http2Body(
        port: Int,
        path: String,
        publicHost: String? = null,
    ): String =
        okHttpGet(port, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), path, publicHost).use {
            assertEquals(Protocol.HTTP_2, it.protocol)
            it.body.string()
        }

    /** One HTTP/1.1 GET over a TLS socket that offers no ALPN; returns the raw response. */
    private fun rawGet(
        port: Int,
        path: String,
    ): String =
        (trustAllContext().socketFactory.createSocket(LOOPBACK, port) as SSLSocket).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val request = "GET $path HTTP/1.1\r\nHost: $LOOPBACK:$port\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray())
            socket.outputStream.flush()
            socket.inputStream.readNBytes(READ_LIMIT).decodeToString()
        }

    private fun healthStatus(port: Int): Int {
        val connection = URI("https://127.0.0.1:$port/health").toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = trustAllContext().socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun accepts(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), TIMEOUT_MS) }
            true
        } catch (_: IOException) {
            false
        }

    private fun trustAllContext(): SSLContext {
        val trustAll =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
    }

    private fun freePort(): Int = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    /** An EC key that cannot be exported, like an AndroidKeyStore key: no encoding, no format. */
    private class OpaqueEcPrivateKey(
        private val delegate: ECPrivateKey,
    ) : ECPrivateKey {
        override fun getS(): BigInteger = delegate.s

        override fun getParams(): ECParameterSpec = delegate.params

        override fun getAlgorithm(): String = "EC"

        override fun getFormat(): String? = null

        override fun getEncoded(): ByteArray? = null
    }

    private companion object {
        const val PUBLIC_HOST = "name.d.tunnel.example"
        const val PUBLIC_PORT = 8443
        const val HTTPS_DEFAULT_PORT = 443
        const val TOKEN = "tunnel-listener-test-token"
        const val SPOOFED = "198.51.100.6"
        const val HTTP_UNAUTHORIZED = 401
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 50
        const val TIMEOUT_MS = 5_000
        const val STOP_TIMEOUT_MS = 1_000L
        const val FIXED_PORT_BUDGET_MS = 2_000L
        const val NANOS_PER_MS = 1_000_000L
        const val READ_LIMIT = 4096
        const val HEALTH = "/health"
        const val PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource"
        val RANDOM_PORT_RANGE = 49152..65535
    }
}
