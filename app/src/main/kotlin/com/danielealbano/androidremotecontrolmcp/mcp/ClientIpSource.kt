package com.danielealbano.androidremotecontrolmcp.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import io.ktor.http.Headers
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.util.AttributeKey

/**
 * What a listener trusts for the address of the client behind a request. The address and a location looked up from it
 * show on the OAuth approval screen, so they MUST NOT come from anything the client can set. See "Client address" in
 * docs/PROJECT.md for what each tunnel was measured to deliver.
 */
enum class ClientIpSource {
    /** Nothing tells this listener who the client is, and its peer is always loopback: it reports no address. */
    UNKNOWN,

    /** The socket peer: nothing in front of this listener sets a header that can be trusted. */
    SOCKET_PEER,

    /** `CF-Connecting-IP`: Cloudflare sets it, and refuses a request from a client that sends its own. */
    CLOUDFLARE,

    /** The last `X-Forwarded-For` value: ngrok passes on what the client sent and adds the real address after it. */
    NGROK,
    ;

    companion object {
        /** The source for a plain HTTP listener, the only kind a Cloudflare or ngrok tunnel forwards to. */
        fun of(config: ServerConfig): ClientIpSource =
            when {
                !config.tunnelEnabled -> SOCKET_PEER
                config.tunnelProvider == TunnelProviderType.CLOUDFLARE -> CLOUDFLARE
                config.tunnelProvider == TunnelProviderType.NGROK -> NGROK
                else -> SOCKET_PEER
            }
    }
}

internal val ClientIpSourceKey = AttributeKey<ClientIpSource>("ClientIpSource")
private const val IPV4_LOOPBACK_PREFIX = "127."
private val IPV6_LOOPBACK = setOf("::1", "0:0:0:0:0:0:0:1")
private val IP_LITERAL = Regex("""(?=.*[:.])[0-9A-Fa-f:.]{2,45}""")

/** Sets what [clientIp] trusts on every request of this listener. Runs before anything can read the address. */
fun Application.markClientIpSource(source: ClientIpSource) {
    intercept(ApplicationCallPipeline.Setup) { call.attributes.put(ClientIpSourceKey, source) }
}

/**
 * The address of the client behind this request, or `null` when there is none. Headers count only from a loopback peer:
 * a tunnel's own process connects over loopback, and any other peer is the client itself.
 */
fun ApplicationCall.clientIp(): String? {
    val source = attributes.getOrNull(ClientIpSourceKey) ?: ClientIpSource.SOCKET_PEER
    val peer = request.origin.remoteAddress.takeIf { it.isNotEmpty() }
    return when {
        source == ClientIpSource.UNKNOWN -> null
        peer != null && isLoopback(peer) -> forwardedAddress(source, request.headers) ?: peer
        else -> peer
    }
}

private fun forwardedAddress(
    source: ClientIpSource,
    headers: Headers,
): String? =
    when (source) {
        ClientIpSource.UNKNOWN, ClientIpSource.SOCKET_PEER -> null
        ClientIpSource.CLOUDFLARE -> headers["CF-Connecting-IP"]
        ClientIpSource.NGROK -> headers.getAll("X-Forwarded-For")?.lastOrNull()?.substringAfterLast(',')
    }?.trim()?.takeIf { IP_LITERAL.matches(it) }

private fun isLoopback(address: String): Boolean {
    val host = address.substringBefore('%')
    return host.startsWith(IPV4_LOOPBACK_PREFIX) || host in IPV6_LOOPBACK
}
