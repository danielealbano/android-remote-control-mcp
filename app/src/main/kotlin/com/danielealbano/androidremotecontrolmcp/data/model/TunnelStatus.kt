package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents the current state of the tunnel connection.
 *
 * Used as [kotlinx.coroutines.flow.StateFlow] value, observed by the UI
 * to display tunnel status and public URL.
 */
sealed class TunnelStatus {
    /** Tunnel is not active. */
    data object Disconnected : TunnelStatus()

    /** Tunnel is establishing a connection. */
    data object Connecting : TunnelStatus()

    /**
     * Tunnel is connected and serving traffic.
     *
     * @property url The public HTTPS URL (e.g., "https://xxx.trycloudflare.com" or "https://xxx.ngrok-free.app").
     * @property providerType The provider that created this tunnel.
     */
    data class Connected(
        val url: String,
        val providerType: TunnelProviderType,
    ) : TunnelStatus()

    /**
     * Tunnel encountered an error.
     *
     * @property message A human-readable error description.
     */
    data class Error(val message: String) : TunnelStatus()
}
