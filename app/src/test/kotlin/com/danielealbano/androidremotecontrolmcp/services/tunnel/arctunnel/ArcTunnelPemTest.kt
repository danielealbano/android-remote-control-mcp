package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

@DisplayName("ArcTunnelPem")
class ArcTunnelPemTest {
    private val root =
        HeldCertificate
            .Builder()
            .certificateAuthority(1)
            .commonName("root")
            .build()
    private val intermediate =
        HeldCertificate
            .Builder()
            .certificateAuthority(0)
            .signedBy(root)
            .commonName("intermediate")
            .build()
    private val leaf =
        HeldCertificate
            .Builder()
            .signedBy(intermediate)
            .commonName("leaf")
            .build()
    private val chain = listOf(leaf.certificate, intermediate.certificate, root.certificate)

    private fun pem(certificate: X509Certificate): String = ArcTunnelPem.encode("CERTIFICATE", certificate.encoded)

    /** A real certificate block whose body is not a certificate. */
    private fun corrupted(certificate: X509Certificate): String =
        ArcTunnelPem.encode("CERTIFICATE", certificate.encoded.copyOf(CORRUPTED_LENGTH))

    /** The start of a certificate block: whole Base64 lines, no END. */
    private fun cutShort(certificate: X509Certificate): String =
        pem(certificate).lines().take(CUT_SHORT_LINES).joinToString("\n", postfix = "\n")

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
    fun `parseChain keeps every certificate with a blank line between them`() {
        val bundle = chain.joinToString("\n") { pem(it) }

        assertEquals(chain, ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain keeps every certificate with CRLF line endings and a blank line between them`() {
        val bundle = chain.joinToString("\n") { pem(it) }.replace("\n", "\r\n")

        assertEquals(chain, ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain ignores what is around and between the certificates`() {
        val bundle = "\nleaf\n${pem(chain[0])}Bag Attributes\n${pem(chain[1])}\n${pem(chain[2])}trailing\n"

        assertEquals(chain, ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain skips a corrupted later certificate and keeps the others`() {
        val bundle = pem(chain[0]) + corrupted(chain[1]) + pem(chain[2])

        assertEquals(listOf(chain[0], chain[2]), ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain skips a block cut short and keeps the certificate after it`() {
        val bundle = pem(chain[0]) + cutShort(chain[1]) + pem(chain[2])

        assertEquals(listOf(chain[0], chain[2]), ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain skips a last block cut short`() {
        val bundle = pem(chain[0]) + pem(chain[1]) + cutShort(chain[2])

        assertEquals(listOf(chain[0], chain[1]), ArcTunnelPem.parseChain(bundle))
    }

    @Test
    fun `parseChain throws on a leaf cut short`() {
        val bundle = cutShort(chain[0]) + pem(chain[1])

        assertThrows<CertificateException> { ArcTunnelPem.parseChain(bundle) }
    }

    @Test
    fun `parseChain throws on a corrupted leaf`() {
        val bundle = corrupted(chain[0]) + pem(chain[1])

        assertThrows<CertificateException> { ArcTunnelPem.parseChain(bundle) }
    }

    @Test
    fun `parseChain throws when the input holds no certificate`() {
        assertThrows<CertificateException> { ArcTunnelPem.parseChain("garbage") }
        assertThrows<CertificateException> { ArcTunnelPem.parseChain(ArcTunnelPem.encode("PRIVATE KEY", ByteArray(8))) }
    }

    @Test
    fun `parseChain returns an empty chain for a blank input`() {
        assertTrue(ArcTunnelPem.parseChain("").isEmpty())
        assertTrue(ArcTunnelPem.parseChain(" \n\r\n ").isEmpty())
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

    private companion object {
        const val CORRUPTED_LENGTH = 16
        const val CUT_SHORT_LINES = 3
    }
}
