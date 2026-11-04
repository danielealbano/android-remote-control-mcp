package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("ArcTunnel wire types")
class ArcTunnelWireTest {
    private fun open(json: String): ArcTunnelOpenPayload {
        val serializer = ArcTunnelOpenPayload.serializer()
        return ArcTunnelJson.decodeFromString(serializer, json)
    }

    @Test
    fun `OpenPayload isValid rules`() {
        val client = """"client_addr":"198.51.100.7:52144""""
        assertTrue(open("""{"token":"t","addr":"h:1","use_https":true,"use_mtls":true,"extra":1,$client}""").isValid())
        assertTrue(open("""{"token":"t","addr":"h:1",$client}""").isValid())
        assertFalse(open("""{"token":"","addr":"h:1",$client}""").isValid())
        assertFalse(open("""{"token":"t","addr":"",$client}""").isValid())
        assertFalse(open("""{"token":"t","addr":"h:1","use_https":false,"use_mtls":true,$client}""").isValid())
        assertFalse(open("""{"token":"t","addr":"h:1"}""").isValid(), "client_addr is required")
        assertFalse(open("""{"token":"t","addr":"h:1","client_addr":""}""").isValid(), "an empty one counts as none")
    }

    @ParameterizedTest
    @CsvSource(
        "198.51.100.7:52144, 198.51.100.7",
        "[2001:db8::7]:52144, 2001:db8::7",
        "[::ffff:198.51.100.7]:1, ::ffff:198.51.100.7",
        "[fe80::1%eth0]:5, fe80::1",
        "[::1%Verified by Google]:1, ::1",
    )
    fun `OpenPayload clientIp reads the host of client_addr`(
        clientAddr: String,
        clientIp: String,
    ) {
        assertEquals(clientIp, ArcTunnelOpenPayload(clientAddr = clientAddr).clientIp())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "198.51.100.7",
            "198.51.100.7:",
            "198.51.100.7:port",
            "198.51.100.7:65536",
            "999.1.1.1:1",
            "2001:db8::7:52144",
            "[198.51.100.7]:1",
            "[2001:db8::7]",
            "[2001:db8::7:1",
            "example.com:443",
            "Verified, safe:1",
            "[[::1]]:1",
            "198.51.100.7:junk:1",
            "[::1]:x]:1",
            "[::1]:",
            "%:1",
            "198.51.100.7:123456",
        ],
    )
    fun `OpenPayload clientIp refuses a malformed client_addr`(clientAddr: String) {
        assertNull(ArcTunnelOpenPayload(clientAddr = clientAddr).clientIp())
    }

    @Test
    fun `MovePayload decodes its addresses in order`() {
        val payload =
            ArcTunnelJson.decodeFromString(
                ArcTunnelMovePayload.serializer(),
                """{"addrs":["203.0.113.11:443","[2001:db8::1]:443"],"extra":1}""",
            )

        assertEquals(listOf("203.0.113.11:443", "[2001:db8::1]:443"), payload.addrs)
    }

    @Test
    fun `ApiErrorBody defaults`() {
        val body = ArcTunnelJson.decodeFromString(ArcTunnelApiErrorBody.serializer(), "{}")

        assertEquals("", body.reason)
        assertFalse(body.retryable)
        assertEquals(0, body.retryAfterSeconds)
    }

    @Test
    fun `IssueResponse ca defaults to empty`() {
        val response =
            ArcTunnelJson.decodeFromString(
                ArcTunnelIssueResponse.serializer(),
                """{"identity_cert":"i","public_cert":"p"}""",
            )

        assertEquals("", response.ca)
        assertEquals("i", response.identityCert)
        assertEquals("p", response.publicCert)
    }

    @Test
    fun `EnrollRequest serializes snake_case names`() {
        val json =
            ArcTunnelJson.encodeToString(
                ArcTunnelEnrollRequest.serializer(),
                ArcTunnelEnrollRequest(nonce = "n", attestationChain = "a", identityCsr = "c"),
            )

        assertEquals("""{"nonce":"n","attestation_chain":"a","identity_csr":"c"}""", json)
    }
}
