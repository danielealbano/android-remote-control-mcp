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

    /** Exact non-loopback redirect URIs accepted by the allowlist (add hosted connectors to [OAuthConnectors.ALL]). */
    val ALLOWED_REDIRECT_URIS: Set<String> = OAuthConnectors.ALL.flatMapTo(HashSet()) { it.exactRedirectUris }

    /** Maximum persisted OAuth clients before eviction. */
    const val MAX_OAUTH_CLIENTS = 20

    /** Maximum connections (logins) one CIMD client keeps; beyond it the least recently used one is dropped. */
    const val MAX_CONNECTIONS_PER_CLIENT = 10

    /** The most of a CIMD document that is read (draft-ietf-oauth-client-id-metadata-document section 10.11). */
    const val MAX_METADATA_DOCUMENT_BYTES = 5 * 1024

    /** The most of a client logo that is read to embed it in the consent page. */
    const val MAX_LOGO_BYTES = 256 * 1024

    /** Whole-call limit for downloading a CIMD document or a client logo. */
    const val OUTBOUND_FETCH_TIMEOUT_MS = 5_000L

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

    /** A lowercase DNS host name with at least two labels. */
    private val HOST_NAME = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /**
     * CLOSED redirect policy (the security boundary). Returns true ONLY for a hosted callback of an app in
     * [OAuthConnectors.ALL] (an exact URI, ChatGPT's callback-id callbacks on the EXACT `chatgpt.com` host, Gemini's
     * custom-connector callbacks on the three EXACT Google relay hosts; see [HostedCallbackPattern]), or `http://`
     * loopback (`localhost` / `127.0.0.1` / `[::1]`, any/no port) for local clients (MCP Inspector / mcp-remote /
     * Claude Code / Codex / VS Code). Loopback hosts are compared via [URI.host] for EXACT equality; hosted patterns
     * compare [URI.getRawAuthority] with their hosts. Deceptive hosts (`localhost.evil.com`,
     * `chatgpt.com.evil.example`, `localhost@evil.com`), other loopback IPs, `0.0.0.0`, non-`https` hosted callbacks,
     * and any other https host are rejected.
     */
    fun isAllowedRedirectUri(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull()
        return parsed.isLoopbackRedirect() || OAuthConnectors.ALL.any { it.ownsHostedCallback(uri, parsed) }
    }

    /** True when [clientId] is a Client ID Metadata Document URL (DCR client ids are `arc-<uuid>`). */
    fun isMetadataDocumentClientId(clientId: String): Boolean = clientId.startsWith("https://")

    /**
     * The host a CIMD [clientId] is fetched from, or null when it must be refused (draft-ietf-oauth-client-id-metadata-
     * document section 3, plus the app list): `https`, a raw authority that is a lowercase host name under a document
     * domain of [OAuthConnectors.ALL] (so no userinfo, no port, no trailing dot), a path other than `/` without dot
     * segments or percent-encoding, and no query or fragment. The client id is never normalized: the document must
     * name exactly this string.
     */
    fun metadataDocumentHost(clientId: String): String? {
        val parsed = runCatching { URI(clientId) }.getOrNull()
        val host = parsed?.rawAuthority
        val path = parsed?.rawPath.orEmpty()
        val valid =
            parsed != null &&
                host != null &&
                parsed.scheme == "https" &&
                HOST_NAME.matches(host) &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null &&
                path.length > 1 &&
                '%' !in path &&
                path.split('/').none { it == "." || it == ".." } &&
                OAuthConnectors.ALL.any { it.ownsDocumentHost(host) }
        return host.takeIf { valid }
    }

    /**
     * True when a CIMD client whose document came from [documentHost] may use [redirectUri]: a hosted callback of an
     * app owning that domain, or a loopback redirect when such an app uses loopback. So a document on claude.ai can
     * only send codes to Claude's callback or to loopback (Claude Code), never to another app's callback.
     */
    fun isAllowedMetadataDocumentRedirect(
        documentHost: String,
        redirectUri: String,
    ): Boolean {
        val parsed = runCatching { URI(redirectUri) }.getOrNull()
        val loopback = parsed.isLoopbackRedirect()
        return OAuthConnectors.ALL.any {
            it.ownsDocumentHost(documentHost) &&
                (it.ownsHostedCallback(redirectUri, parsed) || (loopback && it.loopbackRedirects))
        }
    }

    /**
     * True when [requested] is one of a CIMD document's [listed] redirect URIs: the exact string, or for a loopback
     * redirect the same URI with the port ignored (RFC 8252 section 7.3). Claude Code and Codex list
     * `http://localhost/callback` without a port and listen on a different one each time.
     */
    fun isListedRedirectUri(
        requested: String,
        listed: List<String>,
    ): Boolean {
        val key = loopbackKey(requested)
        return requested in listed || (key != null && listed.any { loopbackKey(it) == key })
    }

    /**
     * The logo to show for a client, or null for the favicon or initials. A CIMD logo comes from a document fetched
     * from the app's own domain, so any SSRF-safe one is used. A DCR client chooses its `logo_uri` freely, and the
     * phone downloading it would tell that server the phone's real address, so only one under a document domain of
     * [OAuthConnectors.ALL] is used.
     */
    fun displayLogoUri(
        clientId: String,
        logoUri: String?,
    ): String? {
        val host =
            logoUri
                ?.takeIf { LogoUrlPolicy.isSafeLogoUrl(it) }
                ?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        val allowed =
            host != null &&
                (isMetadataDocumentClientId(clientId) || OAuthConnectors.ALL.any { it.ownsDocumentHost(host) })
        return logoUri.takeIf { allowed }
    }

    /**
     * The name a client is shown with. A CIMD client's own name is self-asserted, so the host its document came from
     * follows it: `Claude (claude.ai)`. A DCR client shows its `client_name`; null when it sent none.
     */
    fun clientDisplayName(
        clientId: String,
        clientName: String?,
    ): String? {
        if (!isMetadataDocumentClientId(clientId)) return clientName
        val host = runCatching { URI(clientId).rawAuthority }.getOrNull()
        return if (clientName != null && host != null) "$clientName ($host)" else clientName ?: host
    }

    /** True when [uri] is an `http` loopback redirect (`localhost`, `127.0.0.1`, `[::1]`): an app on the computer. */
    fun isLoopbackRedirectUri(uri: String): Boolean = runCatching { URI(uri) }.getOrNull().isLoopbackRedirect()

    /** A loopback redirect without its port, for [isListedRedirectUri]; null for anything else. */
    private fun loopbackKey(uri: String): List<String?>? =
        runCatching { URI(uri) }
            .getOrNull()
            ?.takeIf { it.isLoopbackRedirect() && it.rawUserInfo == null && it.rawFragment == null }
            ?.let { listOf(it.host, it.rawPath, it.rawQuery) }

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

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

/** An `http` redirect to `localhost`, `127.0.0.1` or `[::1]` (any or no port), compared via [URI.host] exactly. */
private fun URI?.isLoopbackRedirect(): Boolean {
    val host = this?.host?.removePrefix("[")?.removeSuffix("]")
    return this != null && scheme == "http" && host in LOOPBACK_HOSTS
}
