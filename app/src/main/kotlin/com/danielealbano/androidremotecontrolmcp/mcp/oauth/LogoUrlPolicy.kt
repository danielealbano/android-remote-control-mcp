package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.net.URI

/**
 * SSRF guard for rendering a client `logo_uri` (DCR or CIMD). Returns true only for an `https` URL whose host is a
 * name, never an IP literal (OkHttp connects to an IP literal without asking its DNS, so `PublicOnlyDns` could not
 * filter it), and not `localhost`; names are further checked by that DNS when the logo is downloaded. The loader
 * MUST additionally not follow redirects and MUST cap size/timeout; this predicate is the host-allowlist part.
 */
object LogoUrlPolicy {
    private val IPV4_LITERAL = Regex("^[0-9.]+$")

    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET = 255
    private const val NET_10 = 10
    private const val NET_127 = 127
    private const val NET_172 = 172
    private const val NET_172_LO = 16
    private const val NET_172_HI = 31
    private const val NET_192 = 192
    private const val NET_192_SECOND = 168
    private const val NET_169 = 169
    private const val NET_169_SECOND = 254

    fun isSafeLogoUrl(url: String?): Boolean {
        val uri = url?.takeIf { it.isNotBlank() }?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme?.lowercase() == "https" && isPublicHost(uri.host) && !isIpLiteral(uri.host)
    }

    /** True when [host] is an IP literal (IPv6 with or without brackets, or dotted IPv4), not a name. */
    fun isIpLiteral(host: String?): Boolean = host != null && (':' in host || IPV4_LITERAL.matches(host))

    /**
     * True when [host] is a routable public host (not `localhost`, not a private/loopback/link-local IP).
     * Used to gate any outbound fetch keyed on a client-supplied host (logo or favicon).
     */
    fun isPublicHost(host: String?): Boolean {
        val normalized =
            host
                ?.lowercase()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.takeIf { it.isNotEmpty() } ?: return false
        return normalized != "localhost" && !isPrivateIp(normalized)
    }

    private fun isPrivateIp(host: String): Boolean = isPrivateIpv4(host) || isPrivateIpv6(host)

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != IPV4_OCTETS || octets.any { it !in 0..MAX_OCTET }) return false
        val a = octets[0]
        val b = octets[1]
        return a == NET_10 || // 10/8
            a == NET_127 || // 127/8 loopback
            (a == NET_172 && b in NET_172_LO..NET_172_HI) || // 172.16/12
            (a == NET_192 && b == NET_192_SECOND) || // 192.168/16
            (a == NET_169 && b == NET_169_SECOND) // 169.254/16 link-local
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (!host.contains(":")) return false
        val normalized = host.lowercase()
        return normalized == "::1" || // loopback
            normalized.startsWith("fc") || normalized.startsWith("fd") || // fc00::/7 ULA
            normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
            normalized.startsWith("fea") || normalized.startsWith("feb") // fe80::/10 link-local
    }
}
