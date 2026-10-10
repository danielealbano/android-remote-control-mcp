package com.danielealbano.androidremotecontrolmcp.services.tunnel

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RelayFrameCodec")
class RelayFrameCodecTest {
    @Test
    fun `decode request frame yields all fields`() {
        val text =
            """{"type":"request","id":"r1","method":"POST","path":"/mcp?x=1",""" +
                """"headers":{"X-Test":["1","2"]},"body":"aGk="}"""

        val inbound = RelayFrameCodec.decode(text)

        assertTrue(inbound is RelayInbound.Request)
        val frame = (inbound as RelayInbound.Request).frame
        assertEquals("r1", frame.id)
        assertEquals("POST", frame.method)
        assertEquals("/mcp?x=1", frame.path)
        assertEquals(mapOf("X-Test" to listOf("1", "2")), frame.headers)
        assertEquals("aGk=", frame.body)
    }

    @Test
    fun `decode request without headers and body uses defaults`() {
        val inbound = RelayFrameCodec.decode("""{"type":"request","id":"r1","method":"GET","path":"/health"}""")

        val frame = (inbound as RelayInbound.Request).frame
        assertEquals(emptyMap<String, List<String>>(), frame.headers)
        assertEquals("", frame.body)
    }

    @Test
    fun `decode ping yields Ping`() {
        assertEquals(RelayInbound.Ping, RelayFrameCodec.decode("""{"type":"ping"}"""))
    }

    @Test
    fun `decode pong yields Pong`() {
        assertEquals(RelayInbound.Pong, RelayFrameCodec.decode("""{"type":"pong"}"""))
    }

    @Test
    fun `decode unknown type yields Unknown with the type`() {
        assertEquals(RelayInbound.Unknown("hello"), RelayFrameCodec.decode("""{"type":"hello"}"""))
    }

    @Test
    fun `decode non-object json yields Malformed`() {
        assertTrue(RelayFrameCodec.decode("[1,2]") is RelayInbound.Malformed)
        assertTrue(RelayFrameCodec.decode("\"x\"") is RelayInbound.Malformed)
    }

    @Test
    fun `decode invalid json yields Malformed`() {
        assertTrue(RelayFrameCodec.decode("not json") is RelayInbound.Malformed)
    }

    @Test
    fun `decode request missing required field yields Malformed`() {
        assertTrue(RelayFrameCodec.decode("""{"type":"request","id":"r1","method":"GET"}""") is RelayInbound.Malformed)
    }

    @Test
    fun `encode response round-trips through Json`() {
        val frame =
            RelayResponseFrame(
                id = "r1",
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = "e30=",
            )

        val decoded = Json.decodeFromString(RelayResponseFrame.serializer(), RelayFrameCodec.encode(frame))

        assertEquals(frame, decoded)
        assertEquals("response", decoded.type)
    }

    @Test
    fun `encodePong emits pong control frame`() {
        assertEquals("""{"type":"pong"}""", RelayFrameCodec.encodePong())
    }
}
