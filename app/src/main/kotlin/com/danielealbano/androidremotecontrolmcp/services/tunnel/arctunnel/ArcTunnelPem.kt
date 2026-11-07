package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object ArcTunnelPem {
    private const val TAG = "MCP:ArcTunnelPem"
    private const val LINE_LENGTH = 64
    private const val HEX_RADIX = 16
    private const val HEX_SHIFT = 4
    private const val BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----"
    private const val END_CERTIFICATE = "-----END CERTIFICATE-----"

    fun encode(
        type: String,
        der: ByteArray,
    ): String {
        val b64 =
            Base64
                .getEncoder()
                .encodeToString(der)
                .chunked(LINE_LENGTH)
                .joinToString("\n")
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    fun chainPem(chain: List<X509Certificate>): String = chain.joinToString("") { encode("CERTIFICATE", it.encoded) }

    /**
     * Parses each certificate of [pem] on its own, leaf first. `generateCertificates` over a whole bundle stops without
     * an error at what it cannot read and keeps the rest out: a corrupted block, and on older Conscrypt a blank line,
     * which Let's Encrypt writes between its certificates, so those phones got the leaf alone.
     *
     * A blank [pem] is an empty chain. No certificate at all, or a first one that does not parse, throws; a later one
     * that does not parse is logged and skipped. A block that lost its END gets one and is judged the same way, so a
     * body cut short does not parse and a whole one is kept. Text outside the blocks is ignored.
     */
    fun parseChain(pem: String): List<X509Certificate> {
        if (pem.isBlank()) return emptyList()
        val blocks =
            pem.split(BEGIN_CERTIFICATE).drop(1).map {
                BEGIN_CERTIFICATE + it.substringBefore(END_CERTIFICATE) + END_CERTIFICATE
            }
        if (blocks.isEmpty()) throw CertificateException("no PEM certificate found")
        val factory = CertificateFactory.getInstance("X.509")
        val rest =
            blocks.drop(1).mapIndexedNotNull { index, block ->
                try {
                    parseCertificate(factory, block)
                } catch (e: CertificateException) {
                    Log.w(TAG, "certificate ${index + 2} of ${blocks.size} of the bundle skipped", e)
                    null
                }
            }
        return listOf(parseCertificate(factory, blocks.first())) + rest
    }

    private fun parseCertificate(
        factory: CertificateFactory,
        block: String,
    ): X509Certificate = factory.generateCertificate(ByteArrayInputStream(block.toByteArray())) as X509Certificate

    fun hexToBytes(hex: String): ByteArray {
        require(hex.isNotEmpty() && hex.length % 2 == 0) { "bad hex" }
        return ByteArray(hex.length / 2) {
            val hi = Character.digit(hex[it * 2], HEX_RADIX)
            val lo = Character.digit(hex[it * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            ((hi shl HEX_SHIFT) + lo).toByte()
        }
    }

    /** Lowercase hex SHA-256 of the DER SubjectPublicKeyInfo — the form Cert Spotter reports as `pubkey_sha256`. */
    fun publicKeySha256(key: PublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }
}
