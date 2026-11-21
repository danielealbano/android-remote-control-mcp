package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthPolicy CIMD rules and the app table")
class OAuthMetadataDocumentPolicyTest {
    @Test
    @DisplayName("metadataDocumentHost accepts the real document URLs of the supported apps")
    fun acceptsRealDocumentUrls() {
        assertEquals("claude.ai", OAuthPolicy.metadataDocumentHost(CLAUDE_DOC))
        assertEquals("claude.ai", OAuthPolicy.metadataDocumentHost(CLAUDE_CODE_DOC))
        assertEquals("chatgpt.com", OAuthPolicy.metadataDocumentHost(CHATGPT_DOC))
        assertEquals("chatgpt.com", OAuthPolicy.metadataDocumentHost(CODEX_DOC))
        assertEquals("vscode.dev", OAuthPolicy.metadataDocumentHost(VSCODE_DOC))
    }

    @Test
    @DisplayName("metadataDocumentHost accepts other paths and subdomains of the app domains")
    fun acceptsOtherUrlsOnAppDomains() {
        // OpenAI documents this shape for servers without RFC 9207; Kimi publishes no document URL.
        val callbackIdDoc = "https://chatgpt.com/oauth/$CALLBACK_ID/client.json"
        assertEquals("chatgpt.com", OAuthPolicy.metadataDocumentHost(callbackIdDoc))
        assertEquals("www.kimi.com", OAuthPolicy.metadataDocumentHost("https://www.kimi.com/oauth/client.json"))
        assertEquals("kimi.com", OAuthPolicy.metadataDocumentHost("https://kimi.com/oauth/client.json"))
        val insiders = "https://insiders.vscode.dev/oauth/c.json"
        assertEquals("insiders.vscode.dev", OAuthPolicy.metadataDocumentHost(insiders))
    }

    @Test
    @DisplayName("metadataDocumentHost refuses other domains and malformed or deceptive URLs")
    fun refusesOtherOrMalformedUrls() {
        listOf(
            "http://claude.ai/oauth/claude-code-client-metadata",
            "https://evil.example/client.json",
            "https://claude.ai.evil.example/client.json",
            "https://evilclaude.ai/client.json",
            "https://user@claude.ai/client.json",
            "https://claude.ai@evil.example/client.json",
            "https://claude.ai:443/client.json",
            "https://claude.ai:/client.json",
            "https://Claude.ai/client.json",
            "https://claude.ai./client.json",
            "https://claude.ai/client.json?x=1",
            "https://claude.ai/client.json#x",
            "https://claude.ai/",
            "https://claude.ai",
            "https://claude.ai/oauth/../client.json",
            "https://claude.ai/./client.json",
            "https://claude.ai/oauth/%2e%2e/client.json",
            "https://claude.ai/client%2Ejson",
            "https://127.0.0.1/client.json",
            "https://[::1]/client.json",
            "https://oauth-redirect.googleusercontent.com/client.json",
            "https://vscode.dev.evil.example/oauth/client-metadata.json",
            "not a url",
            "",
        ).forEach { assertNull(OAuthPolicy.metadataDocumentHost(it), it) }
    }

    @Test
    @DisplayName("a document on claude.ai may use Claude's callback and loopback only")
    fun claudeDomainRedirects() {
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", OAuthPolicy.CLAUDE_REDIRECT_URI))
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", "http://localhost:41234/callback"))
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", "http://127.0.0.1:41234/callback"))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", OAuthPolicy.CHATGPT_REDIRECT_URI))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", OAuthPolicy.KIMI_REDIRECT_URI))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("claude.ai", "https://evil.example/cb"))
    }

    @Test
    @DisplayName("a document on chatgpt.com may use ChatGPT's callbacks and loopback only")
    fun chatGptDomainRedirects() {
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("chatgpt.com", OAuthPolicy.CHATGPT_REDIRECT_URI))
        val callbackId = "https://chatgpt.com/connector/oauth/$CALLBACK_ID"
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("chatgpt.com", callbackId))
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("chatgpt.com", "http://localhost:1455/auth/callback"))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("chatgpt.com", OAuthPolicy.CLAUDE_REDIRECT_URI))
    }

    @Test
    @DisplayName("Kimi gets its callback but no loopback, VS Code loopback but not its browser relay")
    fun kimiAndVsCodeRedirects() {
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("www.kimi.com", OAuthPolicy.KIMI_REDIRECT_URI))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("www.kimi.com", "http://localhost:3000/callback"))
        assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect("vscode.dev", "http://127.0.0.1:33418/"))
        assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect("vscode.dev", "https://vscode.dev/redirect"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://vscode.dev/redirect"))
    }

    @Test
    @DisplayName("Gemini's callbacks are never allowed for a CIMD document")
    fun geminiCallbacksNotForCimd() {
        val gemini = "https://oauth-redirect.googleusercontent.com/r/user_bound_custom-mcp-8569b7990f18a8201022f919"
        assertTrue(OAuthPolicy.isAllowedRedirectUri(gemini))
        listOf("claude.ai", "chatgpt.com", "kimi.com", "vscode.dev", "oauth-redirect.googleusercontent.com").forEach {
            assertFalse(OAuthPolicy.isAllowedMetadataDocumentRedirect(it, gemini), it)
        }
    }

    @Test
    @DisplayName("isListedRedirectUri matches exactly, and loopback with any port")
    fun listedRedirectUris() {
        val claudeCode = listOf("http://localhost/callback", "http://127.0.0.1/callback")
        assertTrue(OAuthPolicy.isListedRedirectUri("http://localhost:41234/callback", claudeCode))
        assertTrue(OAuthPolicy.isListedRedirectUri("http://127.0.0.1:9/callback", claudeCode))
        assertTrue(OAuthPolicy.isListedRedirectUri("http://localhost/callback", claudeCode))
        assertTrue(OAuthPolicy.isListedRedirectUri("http://[::1]:5/cb", listOf("http://[::1]/cb")))
        val claude = listOf(OAuthPolicy.CLAUDE_REDIRECT_URI)
        assertTrue(OAuthPolicy.isListedRedirectUri(OAuthPolicy.CLAUDE_REDIRECT_URI, claude))
        listOf(
            "http://localhost:41234/other",
            "http://localhost:41234/callback?x=1",
            "http://localhost:41234/callback#x",
            "http://user@localhost:41234/callback",
            "https://localhost:41234/callback",
            "http://[::1]:41234/callback",
        ).forEach { assertFalse(OAuthPolicy.isListedRedirectUri(it, claudeCode), it) }
        assertFalse(
            OAuthPolicy.isListedRedirectUri("https://claude.ai:8443/api/mcp/auth_callback", claude),
        )
    }

    @Test
    @DisplayName("isLoopbackRedirectUri is true only for http loopback redirects")
    fun loopbackRedirects() {
        listOf("http://localhost:1/cb", "http://127.0.0.1/cb", "http://[::1]:5/cb").forEach {
            assertTrue(OAuthPolicy.isLoopbackRedirectUri(it), it)
        }
        val notLoopback =
            listOf(OAuthPolicy.CLAUDE_REDIRECT_URI, "https://localhost/cb", "http://localhost.evil.example/cb")
        (notLoopback + "nope").forEach {
            assertFalse(OAuthPolicy.isLoopbackRedirectUri(it), it)
        }
    }

    @Test
    @DisplayName("displayLogoUri keeps a CIMD logo, and a DCR logo only on an app domain")
    fun displayLogoUri() {
        val cdnLogo = "https://persistent.oaistatic.com/sonic/misc/openai-logo.png"
        assertEquals(cdnLogo, OAuthPolicy.displayLogoUri(CHATGPT_DOC, cdnLogo))
        assertNull(OAuthPolicy.displayLogoUri("arc-1", cdnLogo))
        assertEquals("https://claude.ai/logo.png", OAuthPolicy.displayLogoUri("arc-1", "https://claude.ai/logo.png"))
        assertEquals("https://cdn.claude.ai/l.png", OAuthPolicy.displayLogoUri("arc-1", "https://cdn.claude.ai/l.png"))
        assertNull(OAuthPolicy.displayLogoUri("arc-1", "https://claude.ai@evil.example/l.png"))
        assertNull(OAuthPolicy.displayLogoUri("arc-1", "https://claude.ai.evil.example/l.png"))
        assertNull(OAuthPolicy.displayLogoUri(CHATGPT_DOC, "http://persistent.oaistatic.com/l.png"))
        assertNull(OAuthPolicy.displayLogoUri(CHATGPT_DOC, "https://192.168.1.1/l.png"))
        assertNull(OAuthPolicy.displayLogoUri(CHATGPT_DOC, "https://[::ffff:192.168.1.1]:8443/x.png"))
        assertNull(OAuthPolicy.displayLogoUri(CHATGPT_DOC, "https://100.64.0.1/x.png"))
        assertNull(OAuthPolicy.displayLogoUri(CHATGPT_DOC, null))
    }

    @Test
    @DisplayName("clientDisplayName is the client_name, with the document host only for a nameless CIMD client")
    fun clientDisplayName() {
        assertEquals("Claude Code", OAuthPolicy.clientDisplayName(CLAUDE_CODE_DOC, "Claude Code"))
        assertEquals("claude.ai", OAuthPolicy.clientDisplayName(CLAUDE_CODE_DOC, null))
        assertEquals("Claude", OAuthPolicy.clientDisplayName("arc-1", "Claude"))
        assertNull(OAuthPolicy.clientDisplayName("arc-1", null))
        assertTrue(OAuthPolicy.isMetadataDocumentClientId(CLAUDE_CODE_DOC))
        assertFalse(OAuthPolicy.isMetadataDocumentClientId("arc-1"))
    }

    @Test
    @DisplayName("sameAppDomain compares at domain level within one app's document domains")
    fun sameAppDomain() {
        assertTrue(OAuthConnectors.sameAppDomain("claude.ai", "claude.ai"))
        assertTrue(OAuthConnectors.sameAppDomain("kimi.com", "www.kimi.com"))
        assertTrue(OAuthConnectors.sameAppDomain("www.kimi.com", "kimi.com"))
        assertFalse(OAuthConnectors.sameAppDomain("claude.ai", "chatgpt.com"))
        assertFalse(OAuthConnectors.sameAppDomain("claude.ai", "localhost"))
        assertFalse(OAuthConnectors.sameAppDomain("claude.ai", "evilclaude.ai"))
    }

    @Test
    @DisplayName("the app table keeps the redirect allowlist and the document domains together")
    fun appTableInSync() {
        assertEquals(
            OAuthConnectors.ALL.flatMap { it.exactRedirectUris }.toSet(),
            OAuthPolicy.ALLOWED_REDIRECT_URIS,
        )
        // Every app with a document domain can receive codes somewhere, and every hosted callback is reachable
        // through the same table the CIMD check reads.
        OAuthConnectors.ALL.filter { it.documentDomains.isNotEmpty() }.forEach {
            val hosted = it.exactRedirectUris.isNotEmpty() || it.redirectPatterns.isNotEmpty()
            val receivesCodes = hosted || it.loopbackRedirects
            assertTrue(receivesCodes, it.name)
        }
        OAuthConnectors.ALL.forEach { connector ->
            connector.exactRedirectUris.forEach { assertTrue(OAuthPolicy.isAllowedRedirectUri(it), it) }
            connector.exactRedirectUris.forEach { uri ->
                connector.documentDomains.forEach { domain ->
                    assertTrue(OAuthPolicy.isAllowedMetadataDocumentRedirect(domain, uri), "$domain $uri")
                }
            }
        }
        assertEquals(
            listOf("Claude", "ChatGPT", "Kimi", "Gemini", "Claude Code", "Codex", "VS Code"),
            OAuthConnectors.ALL.map { it.name },
        )
    }

    private companion object {
        // The real document URLs, read from each app on 2026-11-21.
        const val CLAUDE_DOC = "https://claude.ai/oauth/mcp-oauth-client-metadata"
        const val CLAUDE_CODE_DOC = "https://claude.ai/oauth/claude-code-client-metadata"
        const val CHATGPT_DOC = "https://chatgpt.com/oauth/client.json"
        const val CODEX_DOC = "https://chatgpt.com/oauth/codex/client.json"
        const val VSCODE_DOC = "https://vscode.dev/oauth/client-metadata.json"
        const val CALLBACK_ID = "-BSmWqXatII2"
    }
}
