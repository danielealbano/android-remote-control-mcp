package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@DisplayName("ArcTunnelFrames")
class ArcTunnelFramesTest {
    private fun header(
        type: Int,
        length: Int,
    ): Buffer = Buffer().writeByte(type).writeInt(length)

    @Test
    fun `reads type and payload`() {
        val source = Buffer().write(ArcTunnelTestServer.frame(ArcTunnelFrames.OPEN, """{"token":"t"}"""))

        val frame = ArcTunnelFrames.read(source)

        assertEquals(ArcTunnelFrames.OPEN, frame?.first)
        assertEquals("""{"token":"t"}""", frame?.second?.decodeToString())
    }

    @Test
    fun `empty payload`() {
        val frame = ArcTunnelFrames.read(Buffer().write(ArcTunnelTestServer.frame(ArcTunnelFrames.PING)))

        assertEquals(ArcTunnelFrames.PING, frame?.first)
        assertEquals(0, frame?.second?.size)
    }

    @Test
    fun `oversized frame throws IOException`() {
        assertThrows<IOException> { ArcTunnelFrames.read(header(ArcTunnelFrames.OPEN, (1 shl 20) + 1)) }
    }

    @Test
    fun `a frame of exactly 1 MiB is accepted`() {
        val source = header(ArcTunnelFrames.OPEN, 1 shl 20).write(ByteArray(1 shl 20))

        assertEquals(1 shl 20, ArcTunnelFrames.read(source)?.second?.size)
    }

    @Test
    fun `EOF returns null`() {
        assertNull(ArcTunnelFrames.read(Buffer()))
    }
}
