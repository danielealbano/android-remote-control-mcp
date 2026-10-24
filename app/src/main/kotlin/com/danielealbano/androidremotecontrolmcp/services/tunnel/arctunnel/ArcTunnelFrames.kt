package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okio.BufferedSource
import java.io.IOException

object ArcTunnelFrames {
    const val OPEN = 1
    const val PING = 2
    const val PONG = 3
    const val RENEW_NUDGE = 4
    private const val MAX_PAYLOAD = 1 shl 20
    private const val BYTE_MASK = 0xff

    val PONG_FRAME: ByteArray = byteArrayOf(PONG.toByte(), 0, 0, 0, 0)

    /** One frame as (type, payload), or null at a clean end of stream. */
    fun read(source: BufferedSource): Pair<Int, ByteArray>? {
        if (source.exhausted()) return null
        val type = source.readByte().toInt() and BYTE_MASK
        val length = source.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("control frame too large: $length")
        return type to (if (length == 0) ByteArray(0) else source.readByteArray(length.toLong()))
    }
}
