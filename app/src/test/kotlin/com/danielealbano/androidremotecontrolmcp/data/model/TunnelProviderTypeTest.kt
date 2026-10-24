package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TunnelProviderType")
class TunnelProviderTypeTest {
    @Test
    fun `ARC_TUNNEL is the first entry`() {
        assertEquals(TunnelProviderType.ARC_TUNNEL, TunnelProviderType.entries.first())
    }

    @Test
    fun `fromAdbValue resolves the hyphenated value`() {
        assertEquals(
            TunnelProviderType.ARC_TUNNEL,
            TunnelProviderType.fromAdbValue("android-remote-control-mcp-tunneld"),
        )
    }

    @Test
    fun `fromAdbValue resolves CLOUDFLARE and NGROK`() {
        assertEquals(TunnelProviderType.CLOUDFLARE, TunnelProviderType.fromAdbValue("CLOUDFLARE"))
        assertEquals(TunnelProviderType.NGROK, TunnelProviderType.fromAdbValue("NGROK"))
    }

    @Test
    fun `fromAdbValue rejects enum name ARC_TUNNEL and unknown values`() {
        assertNull(TunnelProviderType.fromAdbValue("ARC_TUNNEL"))
        assertNull(TunnelProviderType.fromAdbValue("x"))
    }
}
