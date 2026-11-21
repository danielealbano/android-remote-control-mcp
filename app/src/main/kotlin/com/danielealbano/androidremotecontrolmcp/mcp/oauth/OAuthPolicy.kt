package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.net.URI

/**
 * Security policy constants and predicates for the self-contained OAuth authorization server:
 * the redirect-URI allowlist (a CLOSED set), resource matching (RFC 8707), and the locked TTL/cap
 * values agreed for tokens, codes, approvals, and the client registry.
 */
object OAuthPolicy {
    /** The fixed Claude.ai connector callback. */
    const val CLAUDE_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback"

    /**
     * Legacy/fixed ChatGPT connector callback used by already-published ChatGPT MCP integrations.
     * Ported from the fix/chatgpt-oauth-redirect fork by GitHub user ciel051130.
     */
    const val CHATGPT_REDIRECT_URI = "https://chatgpt.com/connector_platform_oauth_redirect"

    /** The fixed Kimi (kimi.com) connector callback, used by Kimi custom plugins (hosted remote MCP). */
    const val KIMI_REDIRECT_URI = "https://www.kimi.com/mcp-oauth-callback"

    /** ChatGPT also uses callback-id OAuth callback URLs on this exact host (see [CHATGPT_REDIRECT_PATH]). */
    private val CHATGPT_REDIRECT_HOSTS = setOf("chatgpt.com")

    /**
     * `/connector/oauth/` + one callback id in the base64url alphabet (12 characters on a real device, e.g.
     * `-BSmWqXatII2`). OpenAI does not document the id's length, so only its alphabet is checked. The alphabet has no
     * `.`, `/` or `%`, so dot segments, extra segments and percent-encoding are refused.
     */
    private val CHATGPT_REDIRECT_PATH = Regex("^/connector/oauth/[A-Za-z0-9_-]+$")

    /**
     * Gemini custom-connector callbacks go through Google's OAuth redirect relay. Gemini registers six redirect URIs
     * in one DCR request (these three hosts x the `/r/` and `/a/` paths), all ending in the same per-connector id.
     */
    private val GEMINI_REDIRECT_HOSTS =
        setOf(
            "oauth-redirect.googleusercontent.com",
            "oauth-redirect-sandbox.googleusercontent.com",
            "oauth-redirect-test.googleusercontent.com",
        )

    /**
     * Only the custom-connector id (`user_bound_custom-mcp-` + 24 lowercase hex digits). The same relay delivers
     * account-linking codes to any Google developer project under `/r/<project-id>`, so a wider pattern would let any
     * project receive this server's authorization codes.
     */
    private val GEMINI_REDIRECT_PATH = Regex("^/[ra]/user_bound_custom-mcp-[0-9a-f]{24}$")

    /** Exact non-loopback redirect URIs accepted by the allowlist (add other hosted connectors here). */
    val ALLOWED_REDIRECT_URIS = setOf(CLAUDE_REDIRECT_URI, CHATGPT_REDIRECT_URI, KIMI_REDIRECT_URI)

    /** Maximum persisted OAuth clients before eviction. */
    const val MAX_OAUTH_CLIENTS = 20

    /** Access-token lifetime (24h). */
    const val ACCESS_TOKEN_TTL_SECONDS = 86_400L

    /** Refresh-token lifetime (90d). */
    const val REFRESH_TOKEN_TTL_SECONDS = 7_776_000L

    /** Authorization-code lifetime (60s). */
    const val AUTH_CODE_TTL_MS = 60_000L

    /** On-device approval window (5 minutes). */
    const val APPROVAL_WINDOW_MS = 300_000L

    /** Match-code modulo: a 2-digit, zero-padded confirmation code. */
    const val MATCH_CODE_MODULO = 100

    /** Maximum concurrently-pending approvals (caps notification flooding). */
    const val MAX_PENDING_APPROVALS = 10

    /** Idle gap after which a client's next authenticated request is logged as a new session (30 min). */
    const val IDLE_SESSION_LOG_THRESHOLD_MS = 1_800_000L

    private const val SCHEME_SEPARATOR = "://"
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

    /**
     * CLOSED redirect policy (the security boundary). Returns true ONLY for a URI in [ALLOWED_REDIRECT_URIS],
     * ChatGPT's HTTPS callback-id callbacks on the EXACT `chatgpt.com` host (see [CHATGPT_REDIRECT_PATH]),
     * Gemini's HTTPS custom-connector callbacks on the three EXACT Google relay hosts (see [GEMINI_REDIRECT_PATH]),
     * or `http://` loopback (`localhost` / `127.0.0.1` / `[::1]`, any/no port) for local test clients
     * (MCP Inspector / mcp-remote / Claude Code). Loopback hosts are compared via [URI.host] for EXACT equality;
     * ChatGPT and Gemini compare [URI.getRawAuthority] with their hosts (see [isHostedCallback]). Deceptive hosts
     * (`localhost.evil.com`, `chatgpt.com.evil.example`, `localhost@evil.com`), other loopback IPs, `0.0.0.0`,
     * non-`https` ChatGPT/Gemini callbacks, and any other https host are rejected.
     */
    fun isAllowedRedirectUri(uri: String): Boolean {
        if (uri in ALLOWED_REDIRECT_URIS) return true

        val parsed = runCatching { URI(uri) }.getOrNull()
        val host = parsed?.host?.removePrefix("[")?.removeSuffix("]")
        val isLoopback = parsed != null && parsed.scheme == "http" && host in LOOPBACK_HOSTS

        return isHostedCallback(parsed, CHATGPT_REDIRECT_HOSTS, CHATGPT_REDIRECT_PATH) ||
            isHostedCallback(parsed, GEMINI_REDIRECT_HOSTS, GEMINI_REDIRECT_PATH) ||
            isLoopback
    }

    /**
     * Hosted connector callback: `https` only, the raw authority must be exactly one of [hosts] (so no userinfo and no
     * port, not even an empty one; a URI without an authority is refused too), no query or fragment, and the raw path
     * must match [path].
     */
    private fun isHostedCallback(
        parsed: URI?,
        hosts: Set<String>,
        path: Regex,
    ): Boolean =
        parsed != null &&
            parsed.scheme == "https" &&
            parsed.rawAuthority in hosts &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null &&
            parsed.rawPath?.let { path.matches(it) } == true

    /**
     * RFC 8707 resource matching: case-insensitive on scheme+host, trailing-slash-insensitive, exact
     * otherwise. Returns false when [requested] is blank.
     */
    fun resourceMatches(
        requested: String,
        canonical: String,
    ): Boolean {
        if (requested.isBlank()) return false
        return normalize(requested) == normalize(canonical)
    }

    private fun normalize(resource: String): String {
        val trimmed = resource.trim().removeSuffix("/")
        val schemeSep = trimmed.indexOf(SCHEME_SEPARATOR)
        if (schemeSep < 0) return trimmed.lowercase()
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        val rest = trimmed.substring(schemeSep + SCHEME_SEPARATOR.length)
        val slash = rest.indexOf('/')
        return if (slash < 0) {
            "$scheme://${rest.lowercase()}"
        } else {
            "$scheme://${rest.substring(0, slash).lowercase()}${rest.substring(slash)}"
        }
    }
}
