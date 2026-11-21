package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RegisterLinks")
class RegisterLinksTest {
    private val tunnelOn = ServerConfig(tunnelEnabled = true)

    private fun connected(vararg endpoints: TunnelEndpoint): TunnelStatus {
        val provider = TunnelProviderType.ARC_TUNNEL
        return TunnelStatus.Connected(endpoints.toList(), provider)
    }

    @Test
    @DisplayName("Claude's link fills in the name and the URL, percent-encoded with %20 for spaces")
    fun claudeUrl() {
        assertEquals(
            "https://claude.ai/customize/connectors?modal=add-custom-connector" +
                "&connectorName=realme%20Note%2070T%20-%20Android%20Remote%20Control" +
                "&connectorUrl=https%3A%2F%2Fabc.d.arc-tunnel.io%2Fmcp",
            RegisterLinks.claudeUrl("realme Note 70T - Android Remote Control", "https://abc.d.arc-tunnel.io/mcp"),
        )
    }

    @Test
    @DisplayName("the connector name is the device slug when set, else the phone name, then the app name")
    fun connectorName() {
        assertEquals("work - Android Remote Control", RegisterLinks.connectorName("work", "realme Note 70T"))
        assertEquals("realme Note 70T - Android Remote Control", RegisterLinks.connectorName(" ", "realme Note 70T"))
    }

    @Test
    @DisplayName("the phone name is the system device name, else the manufacturer and the model")
    fun phoneName() {
        assertEquals("realme Note 70T", RegisterLinks.phoneName(" realme Note 70T ", "realme", "RMX5313"))
        assertEquals("realme RMX5313", RegisterLinks.phoneName(null, "realme", "RMX5313"))
        assertEquals("realme RMX5313", RegisterLinks.phoneName("", "realme", "RMX5313"))
        assertEquals("Google Pixel 8", RegisterLinks.phoneName(null, "Google", "Pixel 8"))
        assertEquals("Samsung Galaxy S24", RegisterLinks.phoneName(null, "samsung", "Samsung Galaxy S24"))
    }

    @Test
    @DisplayName("the links open in the default browser only when Android resolves to a browser")
    fun browserPackage() {
        val browsers = listOf("com.android.chrome", "org.mozilla.firefox")
        assertEquals("org.mozilla.firefox", RegisterLinks.browserPackage("org.mozilla.firefox", browsers))
        assertEquals(null, RegisterLinks.browserPackage("android", browsers))
        assertEquals(null, RegisterLinks.browserPackage(null, browsers))
    }

    @Test
    @DisplayName("without a tunnel the card only asks for one")
    fun noTunnel() {
        val endpoint = TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true)
        assertEquals(RegisterCardState.NoTunnel, RegisterLinks.cardState(ServerConfig(), connected(endpoint)))
    }

    @Test
    @DisplayName("with a tunnel the buttons wait for its public URL")
    fun noUrlYet() {
        listOf(TunnelStatus.Disconnected, TunnelStatus.Connecting, TunnelStatus.Error("x"), connected()).forEach {
            val state = RegisterLinks.cardState(tunnelOn, it)
            assertEquals(RegisterCardState.TunnelEnabled(mcpUrl = null, oauthEnabled = true), state, it.toString())
            assertFalse((state as RegisterCardState.TunnelEnabled).canRegister)
        }
    }

    @Test
    @DisplayName("the MCP URL is the first valid endpoint, normalized, with /mcp")
    fun firstValidEndpoint() {
        val status =
            connected(
                TunnelEndpoint("https://wrong.example.com", valid = false),
                TunnelEndpoint("https://MCP.Example.com/", valid = true),
                TunnelEndpoint("https://other.example.com", valid = true),
            )
        val state = RegisterLinks.cardState(tunnelOn, status) as RegisterCardState.TunnelEnabled
        assertEquals("https://mcp.example.com/mcp", state.mcpUrl)
        assertTrue(state.canRegister)
        val noneValid = connected(TunnelEndpoint("https://wrong.example.com", valid = false))
        assertEquals(RegisterCardState.TunnelEnabled(null, true), RegisterLinks.cardState(tunnelOn, noneValid))
    }

    @Test
    @DisplayName("a public URL override replaces the tunnel URL once the tunnel is connected")
    fun overrideWins() {
        val config = tunnelOn.copy(publicUrlOverride = " https://phone.example.org/ ")
        val endpoint = TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true)
        val live = RegisterLinks.cardState(config, connected(endpoint)) as RegisterCardState.TunnelEnabled
        assertEquals("https://phone.example.org/mcp", live.mcpUrl)
        val connecting = RegisterLinks.cardState(config, TunnelStatus.Connecting)
        assertEquals(RegisterCardState.TunnelEnabled(null, true), connecting)
    }

    @Test
    @DisplayName("with OAuth off the buttons stay disabled even with a URL")
    fun oauthOff() {
        val endpoint = TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true)
        val state = RegisterLinks.cardState(tunnelOn.copy(oauthEnabled = false), connected(endpoint))
        assertEquals(RegisterCardState.TunnelEnabled("https://abc.d.arc-tunnel.io/mcp", oauthEnabled = false), state)
        assertFalse((state as RegisterCardState.TunnelEnabled).canRegister)
    }
}
