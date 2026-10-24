package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object ArcTunnelPem {
    private const val LINE_LENGTH = 64
    private const val HEX_RADIX = 16
    private const val HEX_SHIFT = 4

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

    fun parseChain(pem: String): List<X509Certificate> =
        if (pem.isBlank()) {
            emptyList()
        } else {
            CertificateFactory
                .getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .map { it as X509Certificate }
        }

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
