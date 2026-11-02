package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

@DisplayName("ArcTunnelMoveTarget")
class ArcTunnelMoveTargetTest {
    @ParameterizedTest
    @ValueSource(strings = ["203.0.113.2:443", "203.0.113.2:1", "203.0.113.2:65535"])
    fun `an IPv4 host and port are accepted`(value: String) {
        val target = ArcTunnelMoveTarget.parse(value)

        assertEquals(InetAddress.getByName("203.0.113.2"), target?.address)
        assertEquals(value.substringAfter(':').toInt(), target?.port)
    }

    @ParameterizedTest
    @ValueSource(strings = ["[2001:db8::1]:443"])
    fun `a bracketed IPv6 host and port are accepted`(value: String) {
        assertEquals(ArcTunnelMoveTarget(InetAddress.getByName("2001:db8::1"), 443), ArcTunnelMoveTarget.parse(value))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not-an-address",
            ":443",
            "203.0.113.2:",
            "203.0.113.2:0",
            "203.0.113.2:70000",
            "203.0.113.2:http",
            "2001:db8::1:443",
            "a b:443",
        ],
    )
    fun `anything else is not a valid address`(value: String) {
        assertNull(ArcTunnelMoveTarget.parse(value))
    }
}
