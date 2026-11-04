package com.danielealbano.androidremotecontrolmcp.services.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TunnelClientAddressesTest {
    private val addresses = TunnelClientAddresses()

    @Test
    fun `an address is known only while it is held`() {
        assertNull(addresses.clientIpOf(PORT))

        val hold = addresses.hold(PORT, FIRST)
        assertEquals(FIRST, addresses.clientIpOf(PORT))

        hold.close()
        assertNull(addresses.clientIpOf(PORT))
    }

    @Test
    fun `closing an old hold leaves the connection that reused its port alone`() {
        val old = addresses.hold(PORT, FIRST)
        val reused = addresses.hold(PORT, SECOND)

        old.close()
        assertEquals(SECOND, addresses.clientIpOf(PORT))

        reused.close()
        assertNull(addresses.clientIpOf(PORT))
    }

    @Test
    fun `closing an old hold leaves a newer one for the same client alone`() {
        val old = addresses.hold(PORT, FIRST)
        val reused = addresses.hold(PORT, FIRST)

        old.close()
        assertEquals(FIRST, addresses.clientIpOf(PORT))

        reused.close()
        assertNull(addresses.clientIpOf(PORT))
    }

    private companion object {
        const val PORT = 52144
        const val FIRST = "198.51.100.7"
        const val SECOND = "198.51.100.8"
    }
}
