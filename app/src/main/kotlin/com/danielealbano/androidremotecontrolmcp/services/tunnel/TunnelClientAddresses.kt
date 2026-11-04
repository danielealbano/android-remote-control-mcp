package com.danielealbano.androidremotecontrolmcp.services.tunnel

import java.util.concurrent.ConcurrentHashMap

/**
 * The public client behind each connection of the free E2E tunnel's listener, keyed by the local port of the socket
 * that splices it. Every connection comes from loopback, and the relay names the client in the `OPEN` frame on the
 * control connection, where no client can touch it.
 */
class TunnelClientAddresses {
    private class Held(
        val clientIp: String,
    )

    private val held = ConcurrentHashMap<Int, Held>()

    /** A connection from [localPort] comes from [clientIp] until the handle closes. A later hold on the port wins. */
    fun hold(
        localPort: Int,
        clientIp: String,
    ): AutoCloseable {
        val entry = Held(clientIp)
        held[localPort] = entry
        return AutoCloseable { held.remove(localPort, entry) }
    }

    fun clientIpOf(localPort: Int): String? = held[localPort]?.clientIp
}
