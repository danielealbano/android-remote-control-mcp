package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig

/** Everything the tunnel client needs from [ServerConfig]: the base address plus the debug-only overrides. */
data class ArcTunnelEnv(
    val baseAddress: String,
    val edgeOverride: String = "",
    val trustedCaPem: String = "",
) {
    val hosts: ArcTunnelHosts = ArcTunnelHosts(baseAddress)

    companion object {
        /** The overrides are honoured in debug builds only (defence in depth: release never redirects the client). */
        fun from(
            config: ServerConfig,
            debug: Boolean = BuildConfig.DEBUG,
        ): ArcTunnelEnv =
            if (debug) {
                ArcTunnelEnv(config.arcTunnelBaseAddress, config.arcTunnelEdgeOverride, config.arcTunnelTrustedCaPem)
            } else {
                ArcTunnelEnv(config.arcTunnelBaseAddress)
            }
    }
}

/** `host:port` the client dials instead of resolving the service hosts (debug/E2E, see `ArcTunnelNet`). */
data class ArcTunnelEdgeOverride(
    val host: String,
    val port: Int,
) {
    companion object {
        private const val MAX_PORT = 65535

        fun parse(value: String): ArcTunnelEdgeOverride? {
            val sep = value.lastIndexOf(':')
            val port = if (sep > 0) value.substring(sep + 1).toIntOrNull() else null
            val valid = port != null && port in 1..MAX_PORT
            return if (valid) ArcTunnelEdgeOverride(value.substring(0, sep), requireNotNull(port)) else null
        }
    }
}
