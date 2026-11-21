package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AuthorizeRequester")
class AuthorizeRequesterTest {
    @Test
    @DisplayName("otherDomainHost names the document host only for another domain and a non-loopback redirect")
    fun otherDomainHost() {
        fun cimd(clientId: String) =
            AuthorizeRequester("App", null, ClientMetadataDocument(clientId, "App", listOf("x"), null), host(clientId))
        val chatGptDoc = cimd("https://chatgpt.com/oauth/client.json")
        assertEquals("chatgpt.com", chatGptDoc.otherDomainHost(OAuthPolicy.CLAUDE_REDIRECT_URI))
        assertNull(chatGptDoc.otherDomainHost(OAuthPolicy.CHATGPT_REDIRECT_URI))
        assertNull(cimd("https://kimi.com/oauth/client.json").otherDomainHost(OAuthPolicy.KIMI_REDIRECT_URI))
        val claudeCode = cimd("https://claude.ai/oauth/claude-code-client-metadata")
        assertNull(claudeCode.otherDomainHost("http://localhost:5/callback"))
        assertNull(AuthorizeRequester("Claude", null, null).otherDomainHost(OAuthPolicy.CHATGPT_REDIRECT_URI))
    }
}
