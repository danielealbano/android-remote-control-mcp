package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthServerDeps
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import com.danielealbano.androidremotecontrolmcp.utils.NetworkUtils
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore

/** The main listener and the plain HTTP loopback listener a Cloudflare or ngrok tunnel forwards to. */
@DisplayName("McpServer listeners")
class McpServerListenersTest {
    private val held =
        HeldCertificate
            .Builder()
            .ecdsa256()
            .addSubjectAlternativeName(LOOPBACK)
            .build()

    // Both taken at once, so they differ
    private val ports = freePorts(2)
    private val mainPort = ports[0]
    private val tunnelPort = ports[1]
    private var server: McpServer? = null
    private val serverLog = RecordingServerLogRepository()
    private val client = OkHttpClient()

    @AfterEach
    fun tearDown() {
        server?.stop(0, STOP_TIMEOUT_MS)
    }

    private fun start(
        localhostTunnelPort: Int?,
        https: Boolean = false,
        oauth: Boolean = false,
        tunnel: TunnelProviderType? = null,
    ): McpServer = newServer(localhostTunnelPort, https, oauth = oauth, tunnel = tunnel).also { it.start() }

    private fun newServer(
        localhostTunnelPort: Int?,
        https: Boolean = false,
        specificAddress: String? = null,
        oauth: Boolean = false,
        tunnel: TunnelProviderType? = null,
    ): McpServer {
        val config =
            ServerConfig(
                port = mainPort,
                bindingAddress = if (specificAddress == null) BindingAddress.LOCALHOST else BindingAddress.SPECIFIC,
                specificBindAddress = specificAddress.orEmpty(),
                bearerToken = TOKEN,
                bearerTokenEnabled = true,
                oauthEnabled = oauth,
                httpsEnabled = https,
                tunnelEnabled = tunnel != null,
                tunnelProvider = tunnel ?: TunnelProviderType.ARC_TUNNEL,
            )
        return McpServer(
            config,
            if (https) httpsMaterial() else null,
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
            localhostTunnelPort,
        ).also { server = it }
    }

    @Test
    fun `the tunnel listener serves the same application with the same authentication`() {
        start(localhostTunnelPort = tunnelPort)

        for (port in listOf(mainPort, tunnelPort)) {
            assertEquals(HTTP_OK, status(client, "http://$LOOPBACK:$port/health"), "port $port")
            assertEquals(HTTP_UNAUTHORIZED, status(client, "http://$LOOPBACK:$port/mcp"), "port $port")
            // Authenticated, the request reaches the MCP route, which serves POST only
            assertEquals(
                HTTP_METHOD_NOT_ALLOWED,
                status(client, "http://$LOOPBACK:$port/mcp", "Bearer $TOKEN"),
                "port $port",
            )
        }
    }

    @Test
    fun `an HTTPS main listener and a plain HTTP tunnel listener run together`() {
        start(localhostTunnelPort = tunnelPort, https = true)
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val builder = client.newBuilder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager)
        val httpsClient = builder.build()

        assertEquals(HTTP_OK, status(httpsClient, "https://$LOOPBACK:$mainPort/health"))
        assertEquals(HTTP_OK, status(client, "http://$LOOPBACK:$tunnelPort/health"))
        assertEquals(HTTP_UNAUTHORIZED, status(client, "http://$LOOPBACK:$tunnelPort/mcp"))
    }

    @Test
    fun `a main listener on another address and the tunnel listener share the port`() {
        // The IPv6 loopback: another address of the loopback interface, on every platform
        newServer(localhostTunnelPort = mainPort, specificAddress = IPV6_LOOPBACK).start()

        assertEquals(HTTP_OK, status(client, "http://[$IPV6_LOOPBACK]:$mainPort/health"))
        assertEquals(HTTP_OK, status(client, "http://$LOOPBACK:$mainPort/health"))
    }

    @Test
    fun `without a tunnel listener only the main port listens`() {
        start(localhostTunnelPort = null)

        assertEquals(HTTP_OK, status(client, "http://$LOOPBACK:$mainPort/health"))
        assertTrue(NetworkUtils.isPortAvailable(tunnelPort, LOOPBACK))
    }

    @Test
    fun `a taken tunnel port fails the start naming it, before anything listens`() {
        ServerSocket(tunnelPort, BACKLOG, InetAddress.getByName(LOOPBACK)).use {
            val error = assertThrows<IllegalStateException> { start(localhostTunnelPort = tunnelPort) }

            assertTrue(error.message!!.startsWith("Cannot listen on $LOOPBACK:$tunnelPort: "), error.message)
            assertFalse(server!!.isRunning())
            assertTrue(NetworkUtils.isPortAvailable(mainPort, LOOPBACK))
        }
    }

    @Test
    fun `a tunnel port taken once the main listener started fails the start and stops the main listener`() {
        val taken = mutableListOf<ServerSocket>()
        val mcpServer = newServer(localhostTunnelPort = tunnelPort)
        // Taken between the bind probes and the start of the listeners
        mcpServer.afterBindProbes = { taken += ServerSocket(tunnelPort, BACKLOG, InetAddress.getByName(LOOPBACK)) }

        try {
            val error = assertThrows<IllegalStateException> { mcpServer.start() }

            assertTrue(error.message!!.startsWith("Cannot listen on $LOOPBACK:$tunnelPort: "), error.message)
            assertFalse(mcpServer.isRunning())
            assertTrue(NetworkUtils.isPortAvailable(mainPort, LOOPBACK))
        } finally {
            taken.forEach { it.close() }
        }
    }

    @Test
    fun `a main port taken between the probe and the bind fails the start naming it`() {
        val taken = mutableListOf<ServerSocket>()
        val mcpServer = newServer(localhostTunnelPort = tunnelPort)
        mcpServer.afterBindProbes = { taken += ServerSocket(mainPort, BACKLOG, InetAddress.getByName(LOOPBACK)) }

        try {
            val error = assertThrows<IllegalStateException> { mcpServer.start() }

            assertTrue(error.message!!.startsWith("Cannot listen on $LOOPBACK:$mainPort: "), error.message)
            assertFalse(mcpServer.isRunning())
            assertTrue(NetworkUtils.isPortAvailable(tunnelPort, LOOPBACK))
        } finally {
            taken.forEach { it.close() }
        }
    }

    @Test
    fun `a taken main port fails the start naming it`() {
        ServerSocket(mainPort, BACKLOG, InetAddress.getByName(LOOPBACK)).use {
            val error = assertThrows<IllegalStateException> { start(localhostTunnelPort = tunnelPort) }

            assertTrue(error.message!!.startsWith("Cannot listen on $LOOPBACK:$mainPort: "), error.message)
            assertTrue(NetworkUtils.isPortAvailable(tunnelPort, LOOPBACK))
        }
    }

    @Test
    fun `stop closes the tunnel listener too`() {
        start(localhostTunnelPort = tunnelPort).stop(0, STOP_TIMEOUT_MS)

        assertTrue(NetworkUtils.isPortAvailable(mainPort, LOOPBACK))
        assertTrue(NetworkUtils.isPortAvailable(tunnelPort, LOOPBACK))
    }

    /** A router forwarding public port 443 to the phone's HTTPS port: the client names no port. */
    @Test
    fun `an HTTPS main listener gives URLs with no port to a host with no port, on both HTTP versions`() {
        start(localhostTunnelPort = null, https = true, oauth = true)

        for (protocol in listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) {
            val metadata = httpsMetadata(protocol, PUBLIC_HOST)

            assertTrue(metadata.contains("\"resource\":\"https://$PUBLIC_HOST/mcp\""), "$protocol: $metadata")
        }
    }

    /** A client connecting directly names the port, here the listener's own, and needs it back. */
    @Test
    fun `an HTTPS main listener keeps a port the client names, on both HTTP versions`() {
        start(localhostTunnelPort = null, https = true, oauth = true)

        for (protocol in listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) {
            val metadata = httpsMetadata(protocol, "$LAN_ADDRESS:$mainPort")

            assertTrue(
                metadata.contains("\"resource\":\"https://$LAN_ADDRESS:$mainPort/mcp\""),
                "$protocol: $metadata",
            )
        }
    }

    @Test
    fun `behind Cloudflare the logged address is CF-Connecting-IP`() {
        start(localhostTunnelPort = null, tunnel = TunnelProviderType.CLOUDFLARE)

        failAuthenticationWithSpoofedHeaders("http://$LOOPBACK:$mainPort/mcp", client)

        assertEquals("Authentication failed from $SPOOFED_CF", lastAuthFailure())
    }

    @Test
    fun `behind ngrok the logged address is the last X-Forwarded-For value`() {
        start(localhostTunnelPort = null, tunnel = TunnelProviderType.NGROK)

        failAuthenticationWithSpoofedHeaders("http://$LOOPBACK:$mainPort/mcp", client)

        assertEquals("Authentication failed from $NGROK_ADDED", lastAuthFailure())
    }

    @Test
    fun `with no tunnel the logged address is the socket peer`() {
        start(localhostTunnelPort = null)

        failAuthenticationWithSpoofedHeaders("http://$LOOPBACK:$mainPort/mcp", client)

        assertEquals("Authentication failed from $LOOPBACK", lastAuthFailure())
    }

    @Test
    fun `the free tunnel selected does not make the main listener trust a header`() {
        start(localhostTunnelPort = null, tunnel = TunnelProviderType.ARC_TUNNEL)

        failAuthenticationWithSpoofedHeaders("http://$LOOPBACK:$mainPort/mcp", client)

        assertEquals("Authentication failed from $LOOPBACK", lastAuthFailure())
    }

    @Test
    fun `an HTTPS main listener trusts no header, as no tunnel forwards to it`() {
        start(localhostTunnelPort = tunnelPort, https = true, tunnel = TunnelProviderType.CLOUDFLARE)
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val httpsClient =
            client.newBuilder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).build()

        failAuthenticationWithSpoofedHeaders("https://$LOOPBACK:$mainPort/mcp", httpsClient)
        assertEquals("Authentication failed from $LOOPBACK", lastAuthFailure())

        failAuthenticationWithSpoofedHeaders("http://$LOOPBACK:$tunnelPort/mcp", client)
        assertEquals("Authentication failed from $SPOOFED_CF", lastAuthFailure())
    }

    /** What ngrok delivers: what the client sent, then the real address on a line of its own. */
    private fun failAuthenticationWithSpoofedHeaders(
        url: String,
        httpClient: OkHttpClient,
    ) {
        val request =
            Request
                .Builder()
                .url(url)
                .header("CF-Connecting-IP", SPOOFED_CF)
                .addHeader("X-Forwarded-For", SPOOFED_XFF)
                .addHeader("X-Forwarded-For", NGROK_ADDED)
                .build()
        assertEquals(HTTP_UNAUTHORIZED, httpClient.newCall(request).execute().use { it.code })
    }

    private fun lastAuthFailure(): String = serverLog.entries.last { it.type == ServerLogEntry.Type.AUTH }.message

    /** OkHttp sends the host as `Host` over HTTP/1.1 and as `:authority` over HTTP/2. */
    private fun httpsMetadata(
        protocol: Protocol,
        host: String,
    ): String {
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val httpsClient =
            client
                .newBuilder()
                .sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager)
                .protocols(listOf(protocol, Protocol.HTTP_1_1).distinct())
                .build()
        val request =
            Request
                .Builder()
                .url("https://$LOOPBACK:$mainPort/.well-known/oauth-protected-resource")
                .header("Host", host)
                .build()
        return httpsClient.newCall(request).execute().use {
            assertEquals(protocol, it.protocol)
            it.body.string()
        }
    }

    private fun status(
        httpClient: OkHttpClient,
        url: String,
        authorization: String? = null,
    ): Int {
        val request =
            Request
                .Builder()
                .url(url)
                .apply { authorization?.let { header("Authorization", it) } }
                .build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private fun httpsMaterial(): HttpsMaterial {
        val password = PASSWORD.toCharArray()
        val keyStore =
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(CertificateManager.KEY_ALIAS, held.keyPair.private, password, arrayOf(held.certificate))
            }
        return HttpsMaterial(keyStore, password)
    }

    private fun freePorts(count: Int): List<Int> {
        val sockets = List(count) { ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)) }
        return try {
            sockets.map { it.localPort }
        } finally {
            sockets.forEach { it.close() }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val IPV6_LOOPBACK = "::1"
        const val PUBLIC_HOST = "myphone.ddns.example"
        const val LAN_ADDRESS = "192.168.1.5"
        const val SPOOFED_CF = "198.51.100.6"
        const val SPOOFED_XFF = "198.51.100.7"
        const val NGROK_ADDED = "203.0.113.10"
        const val BACKLOG = 50
        const val STOP_TIMEOUT_MS = 1_000L
        const val TOKEN = "listener-test-token"
        const val PASSWORD = "listener-test"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}
