package com.danielealbano.androidremotecontrolmcp.e2e

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/** A throw-away P-256 CA (cert + PKCS#8 key, PEM) for the tunnel server's `--ca-cert` / `--ca-key`. */
object ArcTunnelTestCa {
    private const val VALIDITY_MS = 24L * 60 * 60 * 1000
    private const val PEM_LINE = 64

    data class Material(
        val certPem: String,
        val keyPem: String,
    )

    fun generate(): Material {
        Security.addProvider(BouncyCastleProvider())
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val keyPair = generator.generateKeyPair()
        val name = X500Name("CN=arc-tunnel-e2e-ca")
        val now = System.currentTimeMillis()
        val builder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date(now - VALIDITY_MS),
                Date(now + VALIDITY_MS),
                name,
                keyPair.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Material(pem("CERTIFICATE", cert.encoded), pem("PRIVATE KEY", keyPair.private.encoded))
    }

    fun pem(
        type: String,
        der: ByteArray,
    ): String {
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }
}
