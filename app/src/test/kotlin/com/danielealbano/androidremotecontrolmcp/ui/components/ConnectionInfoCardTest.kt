package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType.ARC_TUNNEL
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType.CLOUDFLARE
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelRenewalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests verifying ConnectionInfoCard URL/connection-string logic and the
 * Public URL row content mapping ([tunnelRowContent]). These exercise the pure
 * logic extracted from the composable without a Compose runtime.
 */
class ConnectionInfoCardTest {
    private fun buildServerUrl(
        scheme: String,
        displayAddress: String,
        port: Int,
    ): String = "$scheme://$displayAddress:$port/mcp"

    private val running = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")

    private fun ep(
        url: String,
        valid: Boolean = true,
    ) = TunnelEndpoint(url, valid)

    private fun connected(vararg urls: String) = TunnelStatus.Connected(urls.map { ep(it) }, CLOUDFLARE)

    @Test
    fun `serverUrl includes mcp suffix`() {
        val url = buildServerUrl("http", "127.0.0.1", 8080)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("http://127.0.0.1:8080/mcp", url)
    }

    @Test
    fun `serverUrl includes mcp suffix with https`() {
        val url = buildServerUrl("https", "192.168.1.100", 8443)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("https://192.168.1.100:8443/mcp", url)
    }

    @Test
    fun `tunnelUrl includes mcp suffix in connection string`() {
        val tunnelUrl = "https://random-words.trycloudflare.com"
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = listOf(ep(tunnelUrl)),
                bearerToken = "test-token",
            )
        assertTrue(
            connectionString.contains("Tunnel: $tunnelUrl/mcp"),
            "Connection string should contain tunnel URL with /mcp suffix",
        )
    }

    @Test
    fun `copyAll always uses real bearer token`() {
        val realToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = emptyList(),
                bearerToken = realToken,
            )
        assertTrue(
            connectionString.contains("Bearer Token: $realToken"),
            "Connection string should contain the real bearer token",
        )
        assertFalse(
            connectionString.contains("********"),
            "Connection string should never contain masked token",
        )
    }

    @Test
    fun `connectionString format without tunnel`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = emptyList(),
                bearerToken = "test-token-123",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\nBearer Token: test-token-123",
            connectionString,
        )
    }

    @Test
    fun `connectionString format with tunnel`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = listOf(ep("https://example.trycloudflare.com")),
                bearerToken = "test-token-123",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\n" +
                "Tunnel: https://example.trycloudflare.com/mcp\n" +
                "Bearer Token: test-token-123",
            connectionString,
        )
    }

    @Test
    fun `buildConnectionString includes one line per endpoint including invalid ones`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = listOf(ep("https://a.example.com"), ep("https://b.example.com", valid = false)),
                bearerToken = "test-token-123",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\n" +
                "Tunnel: https://a.example.com/mcp\n" +
                "Tunnel: https://b.example.com/mcp\n" +
                "Bearer Token: test-token-123",
            connectionString,
        )
    }

    @Test
    fun `connection string includes tunnel line only when connected`() {
        val url = "https://random-words.trycloudflare.com"
        val withTunnel =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = listOf(ep(url)),
                bearerToken = "test-token",
            )
        assertTrue(withTunnel.contains("\nTunnel: $url/mcp"))
        val withoutTunnel =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = emptyList(),
                bearerToken = "test-token",
            )
        assertFalse(withoutTunnel.contains("Tunnel:"))
    }

    @Test
    fun `connection string omits bearer token when empty`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                tunnelEndpoints = emptyList(),
                bearerToken = "",
            )
        assertEquals("URL: http://127.0.0.1:8080/mcp", connectionString)
        assertFalse(connectionString.contains("Bearer Token"))
    }

    @Test
    fun `tunnelRowContent null when remote access disabled`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = false,
                serverStatus = running,
                tunnelStatus = connected("https://x.trycloudflare.com"),
            )
        assertNull(result)
    }

    @Test
    fun `tunnelRowContent null when server stopped`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = ServerStatus.Stopped,
                tunnelStatus = TunnelStatus.Connecting,
            )
        assertNull(result)
    }

    @Test
    fun `tunnelRowContent null when server stopping`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = ServerStatus.Stopping,
                tunnelStatus = connected("https://x.trycloudflare.com"),
            )
        assertNull(result)
    }

    @Test
    fun `tunnelRowContent null when server error`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = ServerStatus.Error("x"),
                tunnelStatus = connected("https://x.trycloudflare.com"),
            )
        assertNull(result)
    }

    @Test
    fun `tunnelRowContent loading when starting and disconnected`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = ServerStatus.Starting,
                tunnelStatus = TunnelStatus.Disconnected,
            )
        assertEquals(TunnelRowContent.Loading, result)
    }

    @Test
    fun `tunnelRowContent loading when running and connecting`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = TunnelStatus.Connecting,
            )
        assertEquals(TunnelRowContent.Loading, result)
    }

    @Test
    fun `tunnelRowContent loading when running and disconnected`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = TunnelStatus.Disconnected,
            )
        assertEquals(TunnelRowContent.Loading, result)
    }

    @Test
    fun `tunnelRowContent connected exposes endpoint list when running`() {
        val url = "https://random-words.trycloudflare.com"
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = connected(url),
            )
        assertEquals(TunnelRowContent.Connected(listOf(ep(url))), result)
    }

    @Test
    fun `tunnelRowContent maps Connected to endpoint list preserving validity`() {
        val status =
            TunnelStatus.Connected(
                endpoints = listOf(ep("https://a.example.com"), ep("https://b.example.com", valid = false)),
                providerType = CLOUDFLARE,
            )
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = status,
            )
        assertEquals(
            TunnelRowContent.Connected(
                listOf(ep("https://a.example.com"), ep("https://b.example.com", valid = false)),
            ),
            result,
        )
    }

    @Test
    fun `tunnelRowContent connected with no endpoints when route not configured`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus =
                    TunnelStatus.Connected(
                        endpoints = emptyList(),
                        providerType = CLOUDFLARE,
                    ),
            )
        assertEquals(TunnelRowContent.Connected(emptyList()), result)
    }

    @Test
    fun `tunnelRowContent connected when starting and already connected`() {
        val url = "https://random-words.trycloudflare.com"
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = ServerStatus.Starting,
                tunnelStatus = connected(url),
            )
        assertEquals(TunnelRowContent.Connected(listOf(ep(url))), result)
    }

    @Test
    fun `tunnelRowContent failed exposes message when running`() {
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = TunnelStatus.Error("boom"),
            )
        assertEquals(TunnelRowContent.Failed("boom"), result)
    }

    @Test
    fun `tunnelRowContent and connection string carry the free E2E tunnel endpoint`() {
        val url = "https://name.d.arc-tunnel.io"
        val result =
            tunnelRowContent(
                tunnelEnabled = true,
                serverStatus = running,
                tunnelStatus = TunnelStatus.Connected(listOf(ep(url)), ARC_TUNNEL),
            )
        val connectionString =
            buildConnectionString(
                serverUrl = "https://127.0.0.1:8080/mcp",
                tunnelEndpoints = listOf(ep(url)),
                bearerToken = "",
            )

        assertEquals(TunnelRowContent.Connected(listOf(ep(url))), result)
        assertEquals("URL: https://127.0.0.1:8080/mcp\nTunnel: $url/mcp", connectionString)
    }

    @Test
    fun `tunnelRowContent shows the start renewal over the tunnel status`() {
        assertEquals(
            TunnelRowContent.Renewing,
            tunnelRowContent(true, running, TunnelStatus.Connecting, ArcTunnelRenewalState.Renewing),
        )
        assertEquals(
            TunnelRowContent.RenewalFailed("acme_rate_limited"),
            tunnelRowContent(true, running, TunnelStatus.Error("x"), ArcTunnelRenewalState.Failed("acme_rate_limited")),
        )
    }

    @Test
    fun `tunnelRowContent hides the start renewal while the server is stopped`() {
        val renewing = ArcTunnelRenewalState.Renewing
        assertNull(tunnelRowContent(true, ServerStatus.Stopped, TunnelStatus.Connecting, renewing))
    }
}
