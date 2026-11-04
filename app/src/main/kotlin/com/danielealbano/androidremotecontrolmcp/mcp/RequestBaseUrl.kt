package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.util.AttributeKey
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Derivation of the public base URL the client actually connected on, used by every absolute URL the
 * server hands out (OAuth metadata, share-content links). The host is taken from `X-Forwarded-Host` /
 * `Host` / the HTTP/2 `:authority` and the scheme from `X-Forwarded-Proto` / the listener scheme, so the
 * URL is reachable across all topologies (cloudflared, ngrok, Tailscale Funnel, router/DDNS, the free E2E
 * tunnel).
 *
 * **Security rationale (forwarded-header trust) — by design:** the derivation trusts the client-settable
 * `X-Forwarded-*` headers, but the response is PER-CONNECTION, so a spoofed host only changes the
 * response sent back to the spoofer (no cross-client/cached poisoning): a token minted with a spoofed
 * `aud` is rejected at the real `/mcp` (aud-binding), and share links are returned to the calling
 * agent over the same connection. A non-empty `publicUrlOverride` pins the host and ignores forwarded
 * headers entirely.
 */

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val SCHEME_SEPARATOR = "://"

/** The `:authority` of an HTTP/2 request, saved by [captureHttp2Authority]. */
internal val Http2Authority = AttributeKey<String>("Http2Authority")

/**
 * Saves the `:authority` of every HTTP/2 request for [deriveBaseUrl]. HTTP/2 normally carries it instead of `Host`, and
 * `request.port()` cannot replace it: with no port in `:authority` it answers the local socket's port, so a client that
 * named no port and one that named that very port look the same. Runs in the first phase of the pipeline, because
 * [Http2RequestAuthority] needs the engine's own request and routes see a wrapper.
 */
fun Application.captureHttp2Authority() {
    intercept(ApplicationCallPipeline.Setup) {
        Http2RequestAuthority.of(call.request)?.let { call.attributes.put(Http2Authority, it) }
    }
}

/** Returns a normalized `scheme://host[:port]` from the forwarded/Host headers or the HTTP/2 `:authority`. */
fun deriveBaseUrl(call: ApplicationCall): String {
    val scheme = firstValue(call.request.headers["X-Forwarded-Proto"]) ?: call.request.origin.scheme
    val forwardedHost = firstValue(call.request.headers["X-Forwarded-Host"])
    // Use the raw Host header authority verbatim: it carries a port ONLY when the client actually
    // sent one. Reconstructing via host():port() would synthesize the local connection's scheme-default
    // port (e.g. http→80) when Host has none, producing a bogus `https://host:80` behind an HTTPS tunnel
    // (cloudflared/ngrok terminate TLS and forward plaintext, so the local scheme is http).
    val rawHost = firstValue(call.request.headers["Host"])
    val hostPort =
        forwardedHost
            ?: rawHost
            ?: firstValue(call.attributes.getOrNull(Http2Authority))
            ?: "${call.request.host()}:${call.request.port()}"
    return normalizeBaseUrl("$scheme://$hostPort")
}

/** The first of a possibly comma-separated value, or `null` when there is none. */
private fun firstValue(value: String?): String? = value?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Lowercases scheme + host, strips a trailing `/`, and omits the port when it equals the scheme default
 * (80 http / 443 https). Returns `"$scheme://$hostport"`.
 */
fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().removeSuffix("/")
    val schemeSep = trimmed.indexOf(SCHEME_SEPARATOR)
    if (schemeSep < 0) return trimmed.lowercase()
    val scheme = trimmed.substring(0, schemeSep).lowercase()
    val authority = trimmed.substring(schemeSep + SCHEME_SEPARATOR.length)
    val colon = authority.lastIndexOf(':')
    val host: String
    val port: Int?
    if (colon >= 0) {
        val portPart = authority.substring(colon + 1)
        val parsedPort = portPart.toIntOrNull()
        if (parsedPort != null) {
            host = authority.substring(0, colon).lowercase()
            port = parsedPort
        } else {
            host = authority.lowercase()
            port = null
        }
    } else {
        host = authority.lowercase()
        port = null
    }
    val defaultPort = if (scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    return if (port == null || port == defaultPort) {
        "$scheme://$host"
    } else {
        "$scheme://$host:$port"
    }
}

/** The override (when non-empty) wins over the request-derived base URL (also acts as a hostname pin). */
fun effectiveBaseUrl(
    call: ApplicationCall,
    override: String,
): String = override.trim().ifEmpty { null }?.let { normalizeBaseUrl(it) } ?: deriveBaseUrl(call)

/** Coroutine-context element carrying the per-request base URL into MCP tool handlers. */
class RequestBaseUrlElement(
    val baseUrl: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestBaseUrlElement>
}

/** Reads the request base URL from the coroutine context, falling back when no request context is present. */
suspend fun currentRequestBaseUrl(fallback: suspend () -> String): String {
    val element = coroutineContext[RequestBaseUrlElement]
    return element?.baseUrl ?: fallback()
}

/** The canonical MCP resource identifier (`<base>/mcp`) used as the OAuth `aud`/`resource`. */
fun canonicalResource(baseUrl: String): String = "$baseUrl/mcp"
