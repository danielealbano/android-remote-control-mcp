package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for tunnel providers that expose a local server to the internet.
 *
 * Implementations create a tunnel from a public HTTPS URL to a local port.
 * The tunnel lifecycle is managed by [TunnelManager], which reads the
 * [ServerConfig] and passes it to the provider — providers do NOT read
 * settings directly.
 */
interface TunnelProvider {
    /** Observable tunnel status. */
    val status: StateFlow<TunnelStatus>

    /**
     * Starts the tunnel, forwarding traffic from a public URL to [localPort] on localhost.
     *
     * Returns once the tunnel is established (status [TunnelStatus.Connected]) or failed
     * (status [TunnelStatus.Error]) — or, for a provider that bootstraps asynchronously
     * (`ArcTunnelProvider`), once the start was accepted with status [TunnelStatus.Connecting].
     * A second start while running is either ignored (logged) or rejected with
     * [IllegalStateException], depending on the provider; it never alters a live status.
     *
     * @param localPort The local port to forward traffic to (the free E2E tunnel ignores it: it serves through its
     *                  own loopback TLS listener).
     * @param config The current server configuration (providers extract their own
     *               relevant fields, e.g. ngrok reads [ServerConfig.ngrokAuthtoken]
     *               and [ServerConfig.ngrokDomain]).
     * @throws IllegalStateException if a provider rejects a second start while running.
     */
    suspend fun start(
        localPort: Int,
        config: ServerConfig,
    )

    /**
     * Stops the tunnel and releases all resources.
     *
     * After calling stop, the status will be [TunnelStatus.Disconnected].
     * Safe to call even if the tunnel is not running (no-op in that case).
     */
    suspend fun stop()
}
