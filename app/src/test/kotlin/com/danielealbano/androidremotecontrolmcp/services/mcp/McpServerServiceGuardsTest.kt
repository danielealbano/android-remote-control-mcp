package com.danielealbano.androidremotecontrolmcp.services.mcp

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("isServerActive")
class McpServerServiceGuardsTest {
    @Test
    fun `isServerActive is true for Starting and Running only`() {
        assertTrue(isServerActive(ServerStatus.Starting))
        assertTrue(isServerActive(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")))
        assertFalse(isServerActive(ServerStatus.Stopped))
        assertFalse(isServerActive(ServerStatus.Stopping))
        assertFalse(isServerActive(ServerStatus.Error("boom")))
    }
}
