package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthPolicy")
class OAuthPolicyTest {
    @Test
    @DisplayName("allows allowlisted uris and http loopback")
    fun allowsAllowlistedAndLoopback() {
        assertTrue(OAuthPolicy.isAllowedRedirectUri(OAuthPolicy.CLAUDE_REDIRECT_URI))
        assertTrue(OAuthPolicy.isAllowedRedirectUri(OAuthPolicy.CHATGPT_REDIRECT_URI))
        OAuthPolicy.ALLOWED_REDIRECT_URIS.forEach { assertTrue(OAuthPolicy.isAllowedRedirectUri(it)) }
        assertTrue(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com/connector/oauth/abc123"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://localhost/callback"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://localhost:8080/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.1:1234/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://[::1]/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://[::1]:9000/cb"))
    }

    @Test
    @DisplayName("rejects other redirect uris")
    fun rejectsOther() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://evil.example/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("not a uri"))
    }

    @Test
    @DisplayName("rejects deceptive hosted callback hosts and paths")
    fun rejectsDeceptiveHostedCallbacks() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com.evil.example/connector/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com@evil.com/connector/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com/evil/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://chatgpt.com/connector/oauth/abc"))
    }

    @Test
    @DisplayName("allows the six Gemini connector callbacks")
    fun allowsGeminiConnectorCallbacks() {
        GEMINI_CALLBACKS.forEach { assertTrue(OAuthPolicy.isAllowedRedirectUri(it), it) }
    }

    @Test
    @DisplayName("rejects Gemini relay paths that are not a custom-connector id")
    fun rejectsGeminiRelayOtherPaths() {
        listOf(
            "/r/my-gcp-project",
            "/a/my-gcp-project",
            "/r/user_bound_custom-mcp-8569b7990f18a8201022f91",
            "/r/user_bound_custom-mcp-8569b7990f18a8201022f9190",
            "/r/user_bound_custom-mcp-8569B7990F18A8201022F919",
            "/r/user_bound_custom-mcp-8569b7990f18a8201022f91g",
            "/r/user_bound_custom-mcq-8569b7990f18a8201022f919",
            "/r/",
            "/r",
            "/",
            "",
            "/x/$GEMINI_ID",
            "/r/$GEMINI_ID/",
            "/r/$GEMINI_ID/extra",
            "/r//$GEMINI_ID",
            "/r/../a/$GEMINI_ID",
            "/r/%75ser_bound_custom-mcp-8569b7990f18a8201022f919",
        ).forEach {
            val uri = "https://$GEMINI_HOST$it"
            assertFalse(OAuthPolicy.isAllowedRedirectUri(uri), uri)
        }
    }

    @Test
    @DisplayName("rejects deceptive Gemini callback hosts, schemes and suffixes")
    fun rejectsDeceptiveGeminiCallbacks() {
        listOf(
            "http://$GEMINI_HOST/r/$GEMINI_ID",
            "https://$GEMINI_HOST.evil.example/r/$GEMINI_ID",
            "https://$GEMINI_HOST@evil.example/r/$GEMINI_ID",
            "https://evil@$GEMINI_HOST/r/$GEMINI_ID",
            "https://evil.example/r/$GEMINI_ID",
            "https://other.googleusercontent.com/r/$GEMINI_ID",
            "https://googleusercontent.com/r/$GEMINI_ID",
            "https://OAUTH-REDIRECT.googleusercontent.com/r/$GEMINI_ID",
            "https://$GEMINI_HOST./r/$GEMINI_ID",
            "https://$GEMINI_HOST:443/r/$GEMINI_ID",
            "https://$GEMINI_HOST:8443/r/$GEMINI_ID",
            "https://$GEMINI_HOST/r/$GEMINI_ID?x=1",
            "https://$GEMINI_HOST/r/$GEMINI_ID?",
            "https://$GEMINI_HOST/r/$GEMINI_ID#frag",
            "https://$GEMINI_HOST/r/$GEMINI_ID#",
        ).forEach { assertFalse(OAuthPolicy.isAllowedRedirectUri(it), it) }
    }

    @Test
    @DisplayName("rejects deceptive localhost-like hosts")
    fun rejectsDeceptiveHosts() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://localhost.evil.com/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.1.evil.com/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://localhost@evil.com/cb"))
    }

    @Test
    @DisplayName("rejects non-loopback and wildcard hosts")
    fun rejectsNonLoopbackAndWildcard() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://0.0.0.0/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.2/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://[::2]/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://localhost/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://[::1]/cb"))
    }

    @Test
    @DisplayName("resourceMatches is host-case and trailing-slash insensitive")
    fun resourceMatches() {
        assertTrue(OAuthPolicy.resourceMatches("HTTPS://Host/mcp/", "https://host/mcp"))
        assertTrue(OAuthPolicy.resourceMatches("https://host/mcp", "https://host/mcp"))
        assertFalse(OAuthPolicy.resourceMatches("https://host/other", "https://host/mcp"))
        assertFalse(OAuthPolicy.resourceMatches("", "https://host/mcp"))
    }

    private companion object {
        const val GEMINI_HOST = "oauth-redirect.googleusercontent.com"
        const val GEMINI_ID = "user_bound_custom-mcp-8569b7990f18a8201022f919"

        // The six redirect URIs Gemini registered on a real device, in the order it sent them.
        val GEMINI_CALLBACKS =
            listOf(
                "https://oauth-redirect-sandbox.googleusercontent.com/r/$GEMINI_ID",
                "https://oauth-redirect-test.googleusercontent.com/r/$GEMINI_ID",
                "https://oauth-redirect.googleusercontent.com/r/$GEMINI_ID",
                "https://oauth-redirect-sandbox.googleusercontent.com/a/$GEMINI_ID",
                "https://oauth-redirect-test.googleusercontent.com/a/$GEMINI_ID",
                "https://oauth-redirect.googleusercontent.com/a/$GEMINI_ID",
            )
    }
}
