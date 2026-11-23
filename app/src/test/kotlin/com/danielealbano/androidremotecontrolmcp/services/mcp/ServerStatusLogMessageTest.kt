package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("serverStatusLogMessage / tunnelStatusLogMessage")
class ServerStatusLogMessageTest {
    @Test
    fun `messages for all five statuses`() {
        assertEquals("Server starting", serverStatusLogMessage(ServerStatus.Starting))
        assertEquals(
            "Server started on 127.0.0.1:8080",
            serverStatusLogMessage(
                ServerStatus.Running(
                    port = 8080,
                    bindingAddress = "127.0.0.1",
                    oauthEnabled = true,
                    publicUrlOverride = "",
                ),
            ),
        )
        assertEquals("Server stopping", serverStatusLogMessage(ServerStatus.Stopping))
        assertEquals("Server stopped", serverStatusLogMessage(ServerStatus.Stopped))
        assertEquals("Server error: boom", serverStatusLogMessage(ServerStatus.Error("boom")))
    }

    @Test
    fun `an IPv6 address is bracketed`() {
        assertEquals(
            "Server started on [2001:db8::20]:8080",
            serverStatusLogMessage(
                ServerStatus.Running(
                    port = 8080,
                    bindingAddress = "2001:db8::20",
                    oauthEnabled = true,
                    publicUrlOverride = "",
                ),
            ),
        )
    }

    @Test
    fun `tunnel messages for connecting connected error`() {
        assertEquals("Tunnel connecting…", tunnelStatusLogMessage(TunnelStatus.Connecting))
        assertEquals(
            "Tunnel connected: https://a.example.com, https://b.example.com",
            tunnelStatusLogMessage(
                TunnelStatus.Connected(
                    endpoints =
                        listOf(
                            TunnelEndpoint(url = "https://a.example.com", valid = true),
                            TunnelEndpoint(url = "https://b.example.com", valid = true),
                        ),
                    providerType = TunnelProviderType.CLOUDFLARE,
                ),
            ),
        )
        assertEquals("Tunnel error: nope", tunnelStatusLogMessage(TunnelStatus.Error("nope")))
    }

    @Test
    fun `tunnel disconnected produces no observer message`() {
        assertNull(tunnelStatusLogMessage(TunnelStatus.Disconnected))
    }
}
