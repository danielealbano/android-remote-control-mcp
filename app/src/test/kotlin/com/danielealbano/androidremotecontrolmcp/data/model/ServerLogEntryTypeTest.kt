package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ServerLogEntry.Type")
class ServerLogEntryTypeTest {
    @Test
    fun `type ids are pinned to their on-disk values`() {
        assertEquals(0.toByte(), ServerLogEntry.Type.TOOL_CALL.id)
        assertEquals(1.toByte(), ServerLogEntry.Type.TUNNEL.id)
        assertEquals(2.toByte(), ServerLogEntry.Type.SERVER.id)
        assertEquals(3.toByte(), ServerLogEntry.Type.OAUTH.id)
        assertEquals(4.toByte(), ServerLogEntry.Type.AUTH.id)
        assertEquals(5.toByte(), ServerLogEntry.Type.CHANNEL.id)
        assertEquals(6.toByte(), ServerLogEntry.Type.SETTINGS.id)
    }

    @Test
    fun `SHELL keeps id 8`() {
        assertEquals(8.toByte(), ServerLogEntry.Type.SHELL.id)
        assertEquals(ServerLogEntry.Type.SHELL, ServerLogEntry.Type.fromId(8.toByte()))
    }

    @Test
    fun `fromId maps every id and returns null for unknown`() {
        ServerLogEntry.Type.entries.forEach { type ->
            assertEquals(type, ServerLogEntry.Type.fromId(type.id))
        }
        assertNull(ServerLogEntry.Type.fromId(99.toByte()))
    }
}
