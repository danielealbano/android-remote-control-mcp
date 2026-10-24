package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnel wire types")
class ArcTunnelWireTest {
    private fun open(json: String): ArcTunnelOpenPayload {
        val serializer = ArcTunnelOpenPayload.serializer()
        return ArcTunnelJson.decodeFromString(serializer, json)
    }

    @Test
    fun `OpenPayload isValid rules`() {
        assertTrue(open("""{"token":"t","addr":"h:1","use_https":true,"use_mtls":true,"extra":1}""").isValid())
        assertTrue(open("""{"token":"t","addr":"h:1"}""").isValid())
        assertFalse(open("""{"token":"","addr":"h:1"}""").isValid())
        assertFalse(open("""{"token":"t","addr":""}""").isValid())
        assertFalse(open("""{"token":"t","addr":"h:1","use_https":false,"use_mtls":true}""").isValid())
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
