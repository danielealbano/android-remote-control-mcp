package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("runningStatus")
class RunningStatusTest {
    @Test
    @DisplayName("carries where the server listens and the settings it started with")
    fun carriesTheStartedSettings() {
        val config =
            ServerConfig(
                port = 9090,
                bindingAddress = BindingAddress.SPECIFIC,
                specificBindAddress = "192.168.1.20",
                httpsEnabled = true,
                oauthEnabled = false,
                publicUrlOverride = "https://phone.example.org",
            )

        assertEquals(
            ServerStatus.Running(
                port = 9090,
                bindingAddress = "192.168.1.20",
                httpsEnabled = true,
                oauthEnabled = false,
                publicUrlOverride = "https://phone.example.org",
            ),
            runningStatus(config),
        )
    }

    @Test
    @DisplayName("with the default settings: OAuth on, no override, HTTPS off")
    fun defaultSettings() {
        val status = runningStatus(ServerConfig())

        assertEquals(ServerConfig.DEFAULT_PORT, status.port)
        assertEquals(ServerConfig().bindHost, status.bindingAddress)
        assertEquals(true, status.oauthEnabled)
        assertEquals("", status.publicUrlOverride)
        assertEquals(false, status.httpsEnabled)
    }
}
