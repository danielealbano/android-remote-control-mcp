package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.FileDescriptor
import java.io.IOException
import java.io.UncheckedIOException
import java.net.Socket

@DisplayName("ArcTunnelKeepalive")
class ArcTunnelKeepaliveTest {
    private val socket = Socket()
    private val duplicate = mockk<ParcelFileDescriptor>(relaxed = true)
    private val fd = FileDescriptor()

    @BeforeEach
    fun setUp() {
        mockkStatic(ParcelFileDescriptor::class, Os::class, Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { ParcelFileDescriptor.fromSocket(socket) } returns duplicate
        every { duplicate.fileDescriptor } returns fd
        every { Os.setsockoptInt(any(), any(), any(), any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ParcelFileDescriptor::class, Os::class, Log::class)
        socket.close()
    }

    @Test
    fun `enables keepalive and sets the idle, interval and count timers on a duplicate it then closes`() {
        ArcTunnelKeepalive.enable(socket)

        assertTrue(socket.keepAlive)
        verify { Os.setsockoptInt(fd, any(), TCP_KEEPIDLE, ArcTunnelKeepalive.IDLE_S) }
        verify { Os.setsockoptInt(fd, any(), TCP_KEEPINTVL, ArcTunnelKeepalive.INTERVAL_S) }
        verify { Os.setsockoptInt(fd, any(), TCP_KEEPCNT, ArcTunnelKeepalive.COUNT) }
        verify { duplicate.close() }
    }

    @Test
    fun `a refused timer leaves keepalive on with the kernel defaults and does not throw`() {
        every { Os.setsockoptInt(any(), any(), any(), any()) } throws ErrnoException("setsockopt", EPERM)

        ArcTunnelKeepalive.enable(socket)

        assertTrue(socket.keepAlive)
        verify { duplicate.close() }
        verify { Log.w(any(), any<String>(), any<ErrnoException>()) }
    }

    @Test
    fun `a socket without a descriptor keeps keepalive on, sets no timer and does not throw`() {
        every { ParcelFileDescriptor.fromSocket(socket) } returns null

        ArcTunnelKeepalive.enable(socket)

        assertTrue(socket.keepAlive)
        verify(exactly = 0) { Os.setsockoptInt(any(), any(), any(), any()) }
        verify { Log.w(any(), any<String>()) }
    }

    @Test
    fun `a descriptor that cannot be duplicated sets no timer and does not throw`() {
        every { ParcelFileDescriptor.fromSocket(socket) } throws UncheckedIOException(IOException("EMFILE"))

        ArcTunnelKeepalive.enable(socket)

        assertTrue(socket.keepAlive)
        verify(exactly = 0) { Os.setsockoptInt(any(), any(), any(), any()) }
        verify { Log.w(any(), any<String>(), any<UncheckedIOException>()) }
    }

    @Test
    fun `a socket closed meanwhile is left alone and does not throw`() {
        socket.close()

        ArcTunnelKeepalive.enable(socket)

        assertTrue(socket.isClosed)
        verify(exactly = 0) { ParcelFileDescriptor.fromSocket(any()) }
        verify { Log.w(any(), any<String>(), any<IOException>()) }
    }

    private companion object {
        const val TCP_KEEPIDLE = 4
        const val TCP_KEEPINTVL = 5
        const val TCP_KEEPCNT = 6
        const val EPERM = 1
    }
}
