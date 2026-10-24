package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

@DisplayName("ArcTunnelPem")
class ArcTunnelPemTest {
    @Test
    fun `encode wraps at 64 chars`() {
        val pem = ArcTunnelPem.encode("TEST", ByteArray(200) { it.toByte() })
        val lines = pem.trimEnd('\n').split("\n")

        assertEquals("-----BEGIN TEST-----", lines.first())
        assertEquals("-----END TEST-----", lines.last())
        val body = lines.drop(1).dropLast(1)
        assertTrue(body.dropLast(1).all { it.length == 64 }, body.toString())
        assertTrue(body.last().length in 1..64)
    }

    @Test
    fun `chainPem and parseChain round-trip`() {
        val root =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName("root")
                .build()
        val leaf =
            HeldCertificate
                .Builder()
                .signedBy(root)
                .commonName("leaf")
                .build()
        val chain = listOf(leaf.certificate, root.certificate)

        val parsed = ArcTunnelPem.parseChain(ArcTunnelPem.chainPem(chain))

        assertEquals(chain, parsed)
        assertTrue(ArcTunnelPem.parseChain("").isEmpty())
    }

    @Test
    fun `hexToBytes`() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x00), ArcTunnelPem.hexToBytes("0aff00"))
        assertThrows<IllegalArgumentException> { ArcTunnelPem.hexToBytes("abc") }
        assertThrows<IllegalArgumentException> { ArcTunnelPem.hexToBytes("zz") }
        assertThrows<IllegalArgumentException> { ArcTunnelPem.hexToBytes("") }
    }

    @Test
    fun `publicKeySha256 matches MessageDigest`() {
        val key =
            HeldCertificate
                .Builder()
                .ecdsa256()
                .build()
                .keyPair.public
        val expected = MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }

        val actual = ArcTunnelPem.publicKeySha256(key)

        assertEquals(expected, actual)
        assertEquals(64, actual.length)
        assertTrue(actual.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
