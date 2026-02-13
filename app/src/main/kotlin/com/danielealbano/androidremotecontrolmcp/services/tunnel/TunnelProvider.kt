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
     * This is a suspend function that returns once the tunnel is established
     * (status becomes [TunnelStatus.Connected]) or fails (status becomes [TunnelStatus.Error]).
     *
     * @param localPort The local port to forward traffic to.
     * @param config The current server configuration (providers extract their own
     *               relevant fields, e.g. ngrok reads [ServerConfig.ngrokAuthtoken]
     *               and [ServerConfig.ngrokDomain]).
     * @throws IllegalStateException if the tunnel is already running.
     */
    suspend fun start(localPort: Int, config: ServerConfig)

    /**
     * Stops the tunnel and releases all resources.
     *
     * After calling stop, the status will be [TunnelStatus.Disconnected].
     * Safe to call even if the tunnel is not running (no-op in that case).
     */
    suspend fun stop()
}
