package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ServerStatus")
class ServerStatusTest {

    @Nested
    @DisplayName("Stopped")
    inner class StoppedTest {

        @Test
        fun `Stopped is a singleton`() {
            assertSame(ServerStatus.Stopped, ServerStatus.Stopped)
        }

        @Test
        fun `Stopped is a ServerStatus`() {
            assertInstanceOf(ServerStatus::class.java, ServerStatus.Stopped)
        }
    }

    @Nested
    @DisplayName("Starting")
    inner class StartingTest {

        @Test
        fun `Starting is a singleton`() {
            assertSame(ServerStatus.Starting, ServerStatus.Starting)
        }

        @Test
        fun `Starting is a ServerStatus`() {
            assertInstanceOf(ServerStatus::class.java, ServerStatus.Starting)
        }
    }

    @Nested
    @DisplayName("Running")
    inner class RunningTest {

        @Test
        fun `Running carries port and binding address`() {
            val status = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
            assertEquals(8080, status.port)
            assertEquals("127.0.0.1", status.bindingAddress)
        }

        @Test
        fun `Running defaults httpsEnabled to false`() {
            val status = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
            assertFalse(status.httpsEnabled)
        }

        @Test
        fun `Running equality is based on fields`() {
            val a = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
            val b = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
            assertEquals(a, b)
        }

        @Test
        fun `Running with different port are not equal`() {
            val a = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")
            val b = ServerStatus.Running(port = 9090, bindingAddress = "127.0.0.1")
            assertNotEquals(a, b)
        }
    }

    @Nested
    @DisplayName("Stopping")
    inner class StoppingTest {

        @Test
        fun `Stopping is a singleton`() {
            assertSame(ServerStatus.Stopping, ServerStatus.Stopping)
        }
    }

    @Nested
    @DisplayName("Error")
    inner class ErrorTest {

        @Test
        fun `Error carries message`() {
            val status = ServerStatus.Error(message = "Port in use")
            assertEquals("Port in use", status.message)
        }

        @Test
        fun `Error equality is based on message`() {
            val a = ServerStatus.Error(message = "Port in use")
            val b = ServerStatus.Error(message = "Port in use")
            assertEquals(a, b)
        }

        @Test
        fun `Errors with different messages are not equal`() {
            val a = ServerStatus.Error(message = "Port in use")
            val b = ServerStatus.Error(message = "Permission denied")
            assertNotEquals(a, b)
        }
    }
}
