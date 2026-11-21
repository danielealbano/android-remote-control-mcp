package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/**
 * Resolves the best icon URL to render for a requesting OAuth client.
 *
 * Priority: an SSRF-safe client `logo_uri` ([LogoUrlPolicy.isSafeLogoUrl]; callers pass the one
 * [OAuthPolicy.displayLogoUri] allows, DCR or CIMD) wins; otherwise, for a routable
 * public redirect host, a third-party favicon-service URL (higher-resolution than a bare `/favicon.ico`).
 * Loopback / private / link-local hosts (local test clients) resolve to `null` so the UI falls back to an
 * initials avatar and no host is leaked to the favicon service.
 */
object ClientIconUrl {
    /** DuckDuckGo icon service: returns the site's real (apple-touch-icon-grade) icon, not a 16px favicon. */
    private const val FAVICON_SERVICE_PREFIX = "https://icons.duckduckgo.com/ip3/"
    private const val FAVICON_SERVICE_SUFFIX = ".ico"

    fun resolve(
        logoUri: String?,
        host: String?,
    ): String? {
        if (LogoUrlPolicy.isSafeLogoUrl(logoUri)) return logoUri
        val publicHost = host?.trim()?.lowercase()?.takeIf { LogoUrlPolicy.isPublicHost(it) }
        return publicHost?.let { "$FAVICON_SERVICE_PREFIX$it$FAVICON_SERVICE_SUFFIX" }
    }
}
