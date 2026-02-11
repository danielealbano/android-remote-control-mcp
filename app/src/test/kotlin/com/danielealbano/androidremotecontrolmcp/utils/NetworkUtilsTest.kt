package com.danielealbano.androidremotecontrolmcp.utils

import java.net.ServerSocket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NetworkUtils")
class NetworkUtilsTest {

    @Nested
    @DisplayName("isPortAvailable")
    inner class IsPortAvailable {

        @Test
        fun `returns true for available port`() {
            // Arrange: find a free port by letting the system assign one
            val freePort = ServerSocket(0).use { it.localPort }

            // Act & Assert: port should be available after we closed it
            assertTrue(NetworkUtils.isPortAvailable(freePort))
        }

        @Test
        fun `returns false for port in use`() {
            // Arrange: bind a port
            val server = ServerSocket(0)
            val boundPort = server.localPort

            // Act & Assert: port should not be available
            try {
                assertFalse(NetworkUtils.isPortAvailable(boundPort))
            } finally {
                server.close()
            }
        }
    }

    @Nested
    @DisplayName("getNetworkInterfaces")
    inner class GetNetworkInterfaces {

        @Test
        fun `returns at least loopback interface`() {
            val interfaces = NetworkUtils.getNetworkInterfaces()

            assertTrue(interfaces.any { it.isLoopback })
        }

        @Test
        fun `loopback interface has 127 0 0 1 address`() {
            val interfaces = NetworkUtils.getNetworkInterfaces()
            val loopback = interfaces.first { it.isLoopback }

            assertEquals("127.0.0.1", loopback.address)
        }
    }

    @Nested
    @DisplayName("NetworkInterfaceInfo")
    inner class NetworkInterfaceInfoTest {

        @Test
        fun `data class equality works`() {
            val a = NetworkInterfaceInfo(name = "lo", address = "127.0.0.1", isLoopback = true)
            val b = NetworkInterfaceInfo(name = "lo", address = "127.0.0.1", isLoopback = true)

            assertEquals(a, b)
        }
    }
}
