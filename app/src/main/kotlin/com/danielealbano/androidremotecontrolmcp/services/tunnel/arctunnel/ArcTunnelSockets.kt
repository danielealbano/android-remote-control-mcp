package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.io.IOException
import java.net.Socket

object ArcTunnelSockets {
    private const val TAG = "MCP:ArcTunnelSockets"

    fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.d(TAG, "socket close failed", e)
        }
    }
}
