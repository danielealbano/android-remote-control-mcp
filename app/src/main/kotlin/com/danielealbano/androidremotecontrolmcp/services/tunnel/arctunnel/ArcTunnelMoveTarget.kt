package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/** One address of a `MOVE` frame (PROTOCOL.md §7): a literal `host:port`, dialed as given. */
data class ArcTunnelMoveTarget(
    val address: InetAddress,
    val port: Int,
) {
    companion object {
        private const val TAG = "MCP:ArcTunnelMoveTarget"
        private const val MAX_PORT = 65535

        /** Null when [value] is not a valid `host:port`: such an address is skipped. */
        fun parse(value: String): ArcTunnelMoveTarget? =
            try {
                val uri = URI("tcp://$value")
                require("${uri.host}:${uri.port}" == value && uri.port in 1..MAX_PORT) { "not host:port" }
                ArcTunnelMoveTarget(InetAddress.getByName(uri.host.removeSurrounding("[", "]")), uri.port)
            } catch (e: URISyntaxException) {
                skipped(value, e)
            } catch (e: IllegalArgumentException) {
                skipped(value, e)
            } catch (e: UnknownHostException) {
                skipped(value, e)
            }

        private fun skipped(
            value: String,
            cause: Exception,
        ): ArcTunnelMoveTarget? {
            Log.w(TAG, "invalid MOVE address skipped: $value", cause)
            return null
        }
    }
}
