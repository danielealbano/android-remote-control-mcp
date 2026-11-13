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

    /** ChatGPT also uses per-connector OAuth callback URLs under this exact host/path prefix. */
    private const val CHATGPT_REDIRECT_HOST = "chatgpt.com"
    private const val CHATGPT_REDIRECT_PATH_PREFIX = "/connector/oauth/"

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
     * ChatGPT's HTTPS callback namespace on the EXACT `chatgpt.com` host (path under `/connector/oauth/`), or
     * `http://` loopback (`localhost` / `127.0.0.1` / `[::1]`, any/no port) for local test clients
     * (MCP Inspector / mcp-remote / Claude Code). The host is compared via [URI.host] for EXACT equality —
     * deceptive hosts (`localhost.evil.com`, `chatgpt.com.evil.example`, `localhost@evil.com`), other loopback
     * IPs, `0.0.0.0`, non-`https` ChatGPT callbacks, and any other https host are rejected.
     */
    fun isAllowedRedirectUri(uri: String): Boolean {
        if (uri in ALLOWED_REDIRECT_URIS) return true

        val parsed = runCatching { URI(uri) }.getOrNull()
        val host = parsed?.host?.removePrefix("[")?.removeSuffix("]")
        val isChatGptConnectorCallback =
            parsed != null &&
                parsed.scheme == "https" &&
                host == CHATGPT_REDIRECT_HOST &&
                parsed.path?.startsWith(CHATGPT_REDIRECT_PATH_PREFIX) == true
        val isLoopback = parsed != null && parsed.scheme == "http" && host in LOOPBACK_HOSTS

        return isChatGptConnectorCallback || isLoopback
    }

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
