package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.ProviderException

@DisplayName("ArcTunnelDeviceProps")
class ArcTunnelDevicePropsTest {
    private val calls = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `success with the device properties runs no fallback and does not log one`() {
        ArcTunnelDeviceProps.withFallback({ calls += "with" }, { calls += "without" })

        assertEquals(listOf("with"), calls)
        verify(exactly = 0) { Log.i(any(), any<String>(), any()) }
    }

    @Test
    fun `a ProviderException runs the attempt without the device properties and logs it at Info`() {
        val refusal = ProviderException("Failed to generate key pair")

        ArcTunnelDeviceProps.withFallback(
            {
                calls += "with"
                throw refusal
            },
            { calls += "without" },
        )

        assertEquals(listOf("with", "without"), calls)
        verify(exactly = 1) { Log.i(any(), any<String>(), refusal) }
    }

    @Test
    fun `a failure of the attempt without the device properties reaches the caller`() {
        val second = ProviderException("still failing")

        val thrown =
            assertThrows<ProviderException> {
                ArcTunnelDeviceProps.withFallback({ throw ProviderException("first") }, { throw second })
            }

        assertSame(second, thrown)
    }

    @Test
    fun `other failures reach the caller without a fallback or its log`() {
        val failure = IllegalStateException("keystore locked")

        val thrown =
            assertThrows<IllegalStateException> {
                ArcTunnelDeviceProps.withFallback({ throw failure }, { calls += "without" })
            }

        assertSame(failure, thrown)
        assertEquals(emptyList<String>(), calls)
        verify(exactly = 0) { Log.i(any(), any<String>(), any()) }
    }
}
