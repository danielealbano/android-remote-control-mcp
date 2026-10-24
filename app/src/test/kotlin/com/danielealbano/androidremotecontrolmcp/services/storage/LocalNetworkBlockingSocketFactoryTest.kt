package com.danielealbano.androidremotecontrolmcp.services.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

@DisplayName("LocalNetworkBlockingSocketFactory")
class LocalNetworkBlockingSocketFactoryTest {
    private lateinit var listener: ServerSocket
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val loopbackHost: String = checkNotNull(loopback.hostAddress)

    @BeforeEach
    fun setUp() {
        listener = ServerSocket(0, 1, loopback)
    }

    @AfterEach
    fun tearDown() {
        listener.close()
    }

    @Test
    fun `connect to a blocked address is refused before any connection`() {
        val socket = LocalNetworkBlockingSocketFactory().createSocket()

        // An address without a host name, as an IP literal URL gives
        val literal = InetAddress.getByAddress(loopback.address)
        val exception =
            assertThrows<LocalNetworkDownloadBlockedException> {
                socket.connect(InetSocketAddress(literal, listener.localPort), CONNECT_TIMEOUT_MS)
            }

        assertFalse(socket.isConnected)
        assertTrue(exception.message!!.contains("$loopbackHost:${listener.localPort}"))
        assertTrue(exception.message!!.contains("Block Local Network Downloads"))
        socket.close()
    }

    @Test
    fun `connect to an allowed address goes through`() {
        LocalNetworkBlockingSocketFactory { false }.createSocket().use { socket ->
            socket.connect(InetSocketAddress(loopback, listener.localPort), CONNECT_TIMEOUT_MS)

            assertTrue(socket.isConnected)
        }
    }

    @Test
    fun `connect without a timeout is checked too`() {
        val socket = LocalNetworkBlockingSocketFactory().createSocket()

        assertThrows<LocalNetworkDownloadBlockedException> {
            socket.connect(InetSocketAddress(loopback, listener.localPort))
        }
        socket.close()
    }

    @Test
    fun `a refused host name is reported by its name, not by the address it resolved to`() {
        val socket = LocalNetworkBlockingSocketFactory().createSocket()
        val resolved = InetAddress.getByAddress("intranet.example", byteArrayOf(192.toByte(), 168.toByte(), 1, 50))

        val e =
            assertThrows<LocalNetworkDownloadBlockedException> {
                socket.connect(InetSocketAddress(resolved, HTTP_PORT), CONNECT_TIMEOUT_MS)
            }

        assertTrue(e.message!!.contains("connection to intranet.example:$HTTP_PORT refused"), e.message)
        assertFalse(e.message!!.contains("192.168.1.50"), e.message)
        socket.close()
    }

    @Test
    fun `an unresolved endpoint is refused as unchecked, not as a local address`() {
        val socket = LocalNetworkBlockingSocketFactory { false }.createSocket()

        val e =
            assertThrows<LocalNetworkDownloadBlockedException> {
                socket.connect(InetSocketAddress.createUnresolved("example.com", HTTPS_PORT), CONNECT_TIMEOUT_MS)
            }
        assertEquals(
            "connection to example.com:$HTTPS_PORT refused: its address could not be checked while " +
                "'Block Local Network Downloads' is enabled in the storage settings",
            e.message,
        )
        socket.close()
    }

    @Test
    fun `the checked address is the resolved one`() {
        val checked = mutableListOf<InetAddress>()
        val factory =
            LocalNetworkBlockingSocketFactory { address ->
                checked += address
                false
            }
        factory.createSocket().use { socket ->
            socket.connect(InetSocketAddress("localhost", listener.localPort), CONNECT_TIMEOUT_MS)
        }

        assertEquals(1, checked.size)
        assertTrue(checked.single().isLoopbackAddress)
    }

    @Test
    fun `the connecting overloads refuse a blocked address`() {
        val factory = LocalNetworkBlockingSocketFactory()

        assertThrows<LocalNetworkDownloadBlockedException> { factory.createSocket(loopback, listener.localPort) }
        assertThrows<LocalNetworkDownloadBlockedException> {
            factory.createSocket(loopbackHost, listener.localPort)
        }
        assertThrows<LocalNetworkDownloadBlockedException> {
            factory.createSocket(loopback, listener.localPort, loopback, 0)
        }
        assertThrows<LocalNetworkDownloadBlockedException> {
            factory.createSocket(loopbackHost, listener.localPort, loopback, 0)
        }
    }

    @Test
    fun `the connecting overloads connect to an allowed address`() {
        LocalNetworkBlockingSocketFactory { false }.createSocket(loopback, listener.localPort).use { socket ->
            assertTrue(socket.isConnected)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
    }
}
