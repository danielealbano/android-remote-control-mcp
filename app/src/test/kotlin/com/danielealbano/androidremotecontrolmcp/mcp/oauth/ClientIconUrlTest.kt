package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ClientIconUrl")
class ClientIconUrlTest {
    @Test
    @DisplayName("gives the favicon service URL for a public host")
    fun faviconForPublicHost() {
        assertEquals("https://icons.duckduckgo.com/ip3/claude.ai.ico", ClientIconUrl.favicon("claude.ai"))
        assertEquals("https://icons.duckduckgo.com/ip3/claude.ai.ico", ClientIconUrl.favicon(" Claude.AI "))
    }

    @Test
    @DisplayName("returns null for loopback / private / blank hosts")
    fun nullForNonPublicHost() {
        assertNull(ClientIconUrl.favicon("localhost"))
        assertNull(ClientIconUrl.favicon("127.0.0.1"))
        assertNull(ClientIconUrl.favicon("192.168.1.10"))
        assertNull(ClientIconUrl.favicon("::1"))
        assertNull(ClientIconUrl.favicon(""))
        assertNull(ClientIconUrl.favicon(null))
    }
}
