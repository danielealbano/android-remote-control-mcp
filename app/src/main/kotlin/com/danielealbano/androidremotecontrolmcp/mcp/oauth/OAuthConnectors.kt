package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.net.URI

/**
 * Hosted connector callbacks matched by pattern: `https` only, the raw authority must be exactly one of [hosts]
 * (so no userinfo and no port, not even an empty one; a URI without an authority is refused too), no query or
 * fragment, and the raw path must match [path].
 */
class HostedCallbackPattern(
    val hosts: Set<String>,
    val path: Regex,
) {
    fun matches(parsed: URI?): Boolean =
        parsed != null &&
            parsed.scheme == "https" &&
            parsed.rawAuthority in hosts &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null &&
            parsed.rawPath?.let { path.matches(it) } == true
}

/**
 * An app this server accepts OAuth clients from. The SAME entry gives the app's hosted callbacks to the DCR
 * redirect allowlist and its Client ID Metadata Document (CIMD) domains, so an app cannot be added to one without
 * the other ([OAuthPolicy.isAllowedRedirectUri], [OAuthPolicy.isAllowedMetadataDocumentRedirect]).
 *
 * @property name The app, for logs and tests.
 * @property documentDomains Domains its CIMD documents are fetched from (the domain itself or a subdomain); empty
 *   when the app does not use CIMD.
 * @property exactRedirectUris Hosted callbacks accepted as exact strings.
 * @property redirectPatterns Hosted callbacks accepted by pattern.
 * @property loopbackRedirects Whether its CIMD documents may use loopback redirects (DCR accepts loopback from any
 *   client).
 * @property iconHost The host whose favicon stands for the app when its callbacks are on a host without one (Gemini's
 *   Google relay); null to use the callback host.
 */
class OAuthConnector(
    val name: String,
    val documentDomains: Set<String> = emptySet(),
    val exactRedirectUris: Set<String> = emptySet(),
    val redirectPatterns: List<HostedCallbackPattern> = emptyList(),
    val loopbackRedirects: Boolean = false,
    val iconHost: String? = null,
) {
    /** True when [redirectUri] is one of this app's hosted callbacks. */
    fun ownsHostedCallback(
        redirectUri: String,
        parsed: URI?,
    ): Boolean = redirectUri in exactRedirectUris || redirectPatterns.any { it.matches(parsed) }

    /** True when [host] is one of [documentDomains] or a subdomain of one. */
    fun ownsDocumentHost(host: String): Boolean = documentDomains.any { host == it || host.endsWith(".$it") }
}

/** The apps this server accepts OAuth clients from. */
object OAuthConnectors {
    /**
     * ChatGPT also uses callback-id OAuth callback URLs on the exact `chatgpt.com` host: `/connector/oauth/` + one
     * callback id in the base64url alphabet (12 characters on a real device, e.g. `-BSmWqXatII2`). OpenAI does not
     * document the id's length, so only its alphabet is checked. The alphabet has no `.`, `/` or `%`, so dot
     * segments, extra segments and percent-encoding are refused.
     */
    private val CHATGPT_CALLBACK_ID =
        HostedCallbackPattern(setOf("chatgpt.com"), Regex("^/connector/oauth/[A-Za-z0-9_-]+$"))

    /**
     * Gemini custom-connector callbacks go through Google's OAuth redirect relay. Gemini registers six redirect URIs
     * in one DCR request (these three hosts x the `/r/` and `/a/` paths), all ending in the same per-connector id.
     * Only the custom-connector id (`user_bound_custom-mcp-` + 24 lowercase hex digits) passes: the same relay
     * delivers account-linking codes to any Google developer project under `/r/<project-id>`, so a wider pattern
     * would let any project receive this server's authorization codes.
     */
    private val GEMINI_CUSTOM_CONNECTOR =
        HostedCallbackPattern(
            setOf(
                "oauth-redirect.googleusercontent.com",
                "oauth-redirect-sandbox.googleusercontent.com",
                "oauth-redirect-test.googleusercontent.com",
            ),
            Regex("^/[ra]/user_bound_custom-mcp-[0-9a-f]{24}$"),
        )

    /**
     * Gemini needs DCR even where a server supports CIMD (Google's connected-apps MCP OAuth guide), and its
     * callbacks are on a domain where anyone can host files, so it has no document domain. VS Code's browser
     * callback (`https://vscode.dev/redirect`) is a relay that forwards to hosts others control, so it is not
     * accepted; desktop VS Code uses loopback.
     */
    val ALL: List<OAuthConnector> =
        listOf(
            OAuthConnector(
                name = "Claude",
                documentDomains = setOf("claude.ai"),
                exactRedirectUris = setOf(OAuthPolicy.CLAUDE_REDIRECT_URI),
            ),
            OAuthConnector(
                name = "ChatGPT",
                documentDomains = setOf("chatgpt.com"),
                exactRedirectUris = setOf(OAuthPolicy.CHATGPT_REDIRECT_URI),
                redirectPatterns = listOf(CHATGPT_CALLBACK_ID),
            ),
            OAuthConnector(
                name = "Kimi",
                documentDomains = setOf("kimi.com"),
                exactRedirectUris = setOf(OAuthPolicy.KIMI_REDIRECT_URI),
            ),
            OAuthConnector(
                name = "Gemini",
                redirectPatterns = listOf(GEMINI_CUSTOM_CONNECTOR),
                iconHost = "gemini.google.com",
            ),
            OAuthConnector(name = "Claude Code", documentDomains = setOf("claude.ai"), loopbackRedirects = true),
            OAuthConnector(name = "Codex", documentDomains = setOf("chatgpt.com"), loopbackRedirects = true),
            OAuthConnector(name = "VS Code", documentDomains = setOf("vscode.dev"), loopbackRedirects = true),
        )

    /** The [OAuthConnector.iconHost] of the app whose hosted callback [redirectUri] is, or null. */
    fun iconHostFor(redirectUri: String): String? {
        val parsed = runCatching { URI(redirectUri) }.getOrNull()
        return ALL.firstOrNull { it.iconHost != null && it.ownsHostedCallback(redirectUri, parsed) }?.iconHost
    }
}
