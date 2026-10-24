package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.io.UncheckedIOException
import java.net.Socket

/**
 * TCP keepalive for the tunnel's dial-back sockets. A dial-back carries no traffic of its own while a tool call runs,
 * so when its path dies silently (an expired NAT mapping, a dropped network) the close tunneld sends never arrives
 * and the stream, with the phone's local connection to the MCP server, would stay open for good. With keepalive the
 * kernel probes the idle connection and closes it after [COUNT] unanswered probes: the first after [IDLE_S] seconds
 * without traffic, then one every [INTERVAL_S] seconds (about 25 s), the timers tunneld uses on its public sockets.
 *
 * Android has no public API for the timers (`SO_KEEPALIVE` alone waits the kernel default of 2 hours) and
 * `OsConstants` lacks `TCP_KEEPIDLE` / `TCP_KEEPINTVL` / `TCP_KEEPCNT`, so they are set with `setsockopt` on the
 * socket's descriptor, by their Linux values.
 */
object ArcTunnelKeepalive {
    private const val TAG = "MCP:ArcTunnelKeepalive"
    private const val NOT_SET = "keepalive not set up on the dial-back socket"

    /** Seconds of idleness before the first probe. */
    const val IDLE_S = 10

    /** Seconds between probes. */
    const val INTERVAL_S = 5

    /** Unanswered probes before the connection is closed. */
    const val COUNT = 3

    // <linux/tcp.h>; stable kernel ABI values on every architecture.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    /**
     * Enables keepalive with the timers above on the connected [socket]. A failure is logged and never fails the
     * stream: a socket closed meanwhile is left as it is; when its descriptor cannot be duplicated or a timer is
     * refused, keepalive stays on with the timers already set, the kernel defaults for the others.
     */
    fun enable(socket: Socket) {
        try {
            socket.keepAlive = true
            // A duplicate of the descriptor, null when the socket has none: options set on it apply to the socket,
            // closing it leaves the socket open
            val duplicate = ParcelFileDescriptor.fromSocket(socket)
            if (duplicate == null) {
                Log.w(TAG, "$NOT_SET: the socket has no descriptor")
            } else {
                duplicate.use { setTimers(it.fileDescriptor) }
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, NOT_SET, e)
        } catch (e: IOException) {
            Log.w(TAG, NOT_SET, e)
        } catch (e: UncheckedIOException) {
            // fromSocket wraps a failed dup in it
            Log.w(TAG, NOT_SET, e)
        }
    }

    private fun setTimers(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, IDLE_S)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, INTERVAL_S)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, COUNT)
    }
}
