package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
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
    private val running =
        ServerStatus.Running(
            port = 8080,
            bindingAddress = "127.0.0.1",
            oauthEnabled = true,
            publicUrlOverride = "",
        )

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
    @DisplayName("a phone name with reserved or non-ASCII characters stays one value in Claude's link")
    fun claudeUrlEncodesReservedCharacters() {
        val url = RegisterLinks.claudeUrl("A+B & C #1 ü", "https://abc.d.arc-tunnel.io/mcp")
        assertTrue(url.contains("&connectorName=A%2BB%20%26%20C%20%231%20%C3%BC&connectorUrl="), url)
    }

    @Test
    @DisplayName("each button opens its own page: Claude's filled-in dialog, ChatGPT's plugins, Gemini's connectors")
    fun linkPerTarget() {
        val mcpUrl = "https://abc.d.arc-tunnel.io/mcp"
        val name = "work - Android Remote Control"
        assertEquals(RegisterLinks.claudeUrl(name, mcpUrl), RegisterLinks.link(RegisterTarget.CLAUDE, mcpUrl) { name })
        assertEquals("https://chatgpt.com/plugins", RegisterLinks.link(RegisterTarget.CHATGPT, mcpUrl) { name })
        assertEquals(
            "https://gemini.google.com/customize/connectors",
            RegisterLinks.link(RegisterTarget.GEMINI, mcpUrl) { name },
        )
    }

    @Test
    @DisplayName("the connector name is asked for only by Claude's link")
    fun connectorNameOnlyForClaude() {
        var asked = 0
        val name = {
            asked++
            "work - Android Remote Control"
        }
        RegisterLinks.link(RegisterTarget.CHATGPT, "https://a/mcp", name)
        RegisterLinks.link(RegisterTarget.GEMINI, "https://a/mcp", name)
        assertEquals(0, asked)
        RegisterLinks.link(RegisterTarget.CLAUDE, "https://a/mcp", name)
        assertEquals(1, asked)
    }

    @Test
    @DisplayName("only ChatGPT and Gemini copy the URL and say where to paste it; no app shows its own message")
    fun copyAndMessages() {
        assertFalse(RegisterTarget.CLAUDE.copiesUrl)
        assertTrue(RegisterTarget.CHATGPT.copiesUrl)
        assertTrue(RegisterTarget.GEMINI.copiesUrl)
        assertEquals(null, RegisterLinks.messageAfterOpen(RegisterTarget.CLAUDE, opened = true))
        assertEquals(R.string.register_chatgpt_steps, RegisterLinks.messageAfterOpen(RegisterTarget.CHATGPT, true))
        assertEquals(R.string.register_gemini_steps, RegisterLinks.messageAfterOpen(RegisterTarget.GEMINI, true))
        RegisterTarget.entries.forEach {
            assertEquals(R.string.register_no_app, RegisterLinks.messageAfterOpen(it, opened = false), it.name)
        }
    }

    @Test
    @DisplayName("the hints say why the buttons are disabled, one per reason, and none when they work")
    fun hints() {
        val url = TunnelUrl.Known("https://a/mcp")
        assertEquals(listOf(R.string.register_card_no_tunnel), RegisterCardState.NoTunnel.hintsRes)
        assertEquals(
            listOf(R.string.register_card_no_url, R.string.register_card_oauth_off),
            RegisterCardState.TunnelEnabled(TunnelUrl.Missing.SERVER_STOPPED, false, false).hintsRes,
        )
        val restart = RegisterCardState.TunnelEnabled(url, oauthEnabled = true, restartNeeded = true)
        assertEquals(listOf(R.string.register_card_restart), restart.hintsRes)
        // With OAuth off, turning it back on is the whole fix: no restart hint on top.
        val oauthOff = RegisterCardState.TunnelEnabled(url, oauthEnabled = false, restartNeeded = true)
        assertEquals(listOf(R.string.register_card_oauth_off), oauthOff.hintsRes)
        val ready = RegisterCardState.TunnelEnabled(url, oauthEnabled = true, restartNeeded = false)
        assertEquals(emptyList<Int>(), ready.hintsRes)
        assertTrue(ready.canRegister)
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
        val state = RegisterLinks.cardState(ServerConfig(), connected(endpoint), running)
        assertEquals(RegisterCardState.NoTunnel, state)
    }

    @Test
    @DisplayName("without a URL the card says to start the server, to wait for the tunnel, or that the tunnel failed")
    fun missingUrl() {
        // A tunnel still shown connected after a stop never counts: the server's state decides first.
        val leftBehind = connected(TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true))
        listOf(ServerStatus.Stopped, ServerStatus.Stopping, ServerStatus.Error("x")).forEach {
            assertEquals(TunnelUrl.Missing.SERVER_STOPPED, urlOf(TunnelStatus.Disconnected, it), it.toString())
            assertEquals(TunnelUrl.Missing.SERVER_STOPPED, urlOf(leftBehind, it), it.toString())
        }
        assertEquals(TunnelUrl.Missing.WAITING, urlOf(leftBehind, ServerStatus.Starting))
        listOf(TunnelStatus.Disconnected, TunnelStatus.Connecting).forEach {
            assertEquals(TunnelUrl.Missing.WAITING, urlOf(it, ServerStatus.Starting), it.toString())
            assertEquals(TunnelUrl.Missing.WAITING, urlOf(it, running), it.toString())
        }
        val noRoute = connected(TunnelEndpoint("https://wrong.example.com", valid = false))
        listOf(TunnelStatus.Error("cloudflared binary not found"), connected(), noRoute).forEach {
            assertEquals(TunnelUrl.Missing.TUNNEL_FAILED, urlOf(it, running), it.toString())
        }
        val state = RegisterLinks.cardState(tunnelOn, TunnelStatus.Connecting, running)
        assertFalse((state as RegisterCardState.TunnelEnabled).canRegister)
        assertEquals(null, state.mcpUrl)
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
        val state = RegisterLinks.cardState(tunnelOn, status, running) as RegisterCardState.TunnelEnabled
        assertEquals(TunnelUrl.Known("https://mcp.example.com/mcp"), state.url)
        assertEquals("https://mcp.example.com/mcp", state.mcpUrl)
        assertTrue(state.canRegister)
    }

    @Test
    @DisplayName("a public URL override replaces the tunnel URL once the tunnel is connected")
    fun overrideWins() {
        val config = tunnelOn.copy(publicUrlOverride = " https://phone.example.org/ ")
        val started = running.copy(publicUrlOverride = "https://phone.example.org")
        val endpoint = TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true)
        val live = RegisterLinks.cardState(config, connected(endpoint), started) as RegisterCardState.TunnelEnabled
        assertEquals("https://phone.example.org/mcp", live.mcpUrl)
        assertFalse(live.restartNeeded)
        assertEquals(TunnelUrl.Missing.WAITING, urlOf(TunnelStatus.Connecting, started, config))
    }

    @Test
    @DisplayName("a running server started with another OAuth toggle or override needs a restart first")
    fun restartNeeded() {
        val endpoint = connected(TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true))

        fun stateOf(
            config: ServerConfig,
            server: ServerStatus,
        ) = RegisterLinks.cardState(config, endpoint, server) as RegisterCardState.TunnelEnabled
        val oauthTurnedOn = stateOf(tunnelOn, running.copy(oauthEnabled = false))
        assertTrue(oauthTurnedOn.restartNeeded)
        assertFalse(oauthTurnedOn.canRegister)
        assertTrue(stateOf(tunnelOn.copy(publicUrlOverride = "https://new.example.org"), running).restartNeeded)
        assertTrue(stateOf(tunnelOn, running.copy(publicUrlOverride = "https://old.example.org")).restartNeeded)
        // The same override written differently is no change; and only a running server can need a restart.
        val same = tunnelOn.copy(publicUrlOverride = "HTTPS://Phone.example.org/")
        assertFalse(stateOf(same, running.copy(publicUrlOverride = "https://phone.example.org")).restartNeeded)
        assertFalse(stateOf(tunnelOn, ServerStatus.Starting).restartNeeded)
        assertTrue(stateOf(tunnelOn, running).canRegister)
    }

    @Test
    @DisplayName("with OAuth off the buttons stay disabled even with a URL")
    fun oauthOff() {
        val endpoint = TunnelEndpoint("https://abc.d.arc-tunnel.io", valid = true)
        val config = tunnelOn.copy(oauthEnabled = false)
        val state = RegisterLinks.cardState(config, connected(endpoint), running.copy(oauthEnabled = false))
        val expected = RegisterCardState.TunnelEnabled(TunnelUrl.Known("https://abc.d.arc-tunnel.io/mcp"), false, false)
        assertEquals(expected, state)
        assertFalse((state as RegisterCardState.TunnelEnabled).canRegister)
    }

    private fun urlOf(
        tunnelStatus: TunnelStatus,
        serverStatus: ServerStatus,
        config: ServerConfig = tunnelOn,
    ): TunnelUrl = (RegisterLinks.cardState(config, tunnelStatus, serverStatus) as RegisterCardState.TunnelEnabled).url
}
