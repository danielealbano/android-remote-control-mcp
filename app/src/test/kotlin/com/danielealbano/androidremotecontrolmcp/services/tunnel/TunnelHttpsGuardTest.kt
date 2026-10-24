package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("tunnelAllowedWithHttps")
class TunnelHttpsGuardTest {
    @Test
    fun `tunnelAllowedWithHttps allows every provider without HTTPS`() {
        TunnelProviderType.entries.forEach {
            assertTrue(tunnelAllowedWithHttps(ServerConfig(httpsEnabled = false, tunnelProvider = it)), it.name)
        }
    }

    @Test
    fun `tunnelAllowedWithHttps allows only ARC_TUNNEL with HTTPS`() {
        val allowed =
            TunnelProviderType.entries.associateWith {
                tunnelAllowedWithHttps(ServerConfig(httpsEnabled = true, tunnelProvider = it))
            }

        assertEquals(
            mapOf(
                TunnelProviderType.ARC_TUNNEL to true,
                TunnelProviderType.CLOUDFLARE to false,
                TunnelProviderType.NGROK to false,
            ),
            allowed,
        )
    }
}
