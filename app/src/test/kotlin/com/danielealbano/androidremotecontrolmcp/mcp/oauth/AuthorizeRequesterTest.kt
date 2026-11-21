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

    @Test
    @DisplayName("loggable drops control, line separator and format characters and caps the length")
    fun loggableStripsHiddenCharacters() {
        val sent = "https://x/\u2028OAuth client revoked: Claude\n\u202Eabc\u2069\u200Bd\u2029e\u0007"
        assertEquals("https://x/OAuth client revoked: Claudeabcde", loggable(sent))
        assertEquals(200, loggable("a".repeat(500)).length)
        assertEquals("Claude (claude.ai) é ✓", loggable("Claude (claude.ai) é ✓"))
        // Invisible tag characters (outside the BMP) are format characters too.
        assertEquals("ab", loggable("a\uDB40\uDC41\uDB40\uDC01b"))
        // The cap counts code points and never cuts a surrogate pair.
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(200), loggable(emoji.repeat(300)))
    }
}
