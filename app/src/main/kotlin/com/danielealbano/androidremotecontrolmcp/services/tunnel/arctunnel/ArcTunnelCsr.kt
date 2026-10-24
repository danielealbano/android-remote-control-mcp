package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** PKCS#10 CSRs signed through a [ContentSigner] over a keystore key: the private bytes never leave the keystore. */
object ArcTunnelCsr {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Identity CSR: the server ignores the subject and assigns the name. */
    fun identityCsr(
        key: PrivateKey,
        pub: PublicKey,
    ): String = build(key, pub, "phone", null)

    /** TLS CSR: CN and one DNS SAN both equal to `<name>.<tunnel-domain>` (PROTOCOL.md §3). */
    fun tlsCsr(
        key: PrivateKey,
        pub: PublicKey,
        fqdn: String,
    ): String = build(key, pub, fqdn, fqdn)

    private fun build(
        key: PrivateKey,
        pub: PublicKey,
        cn: String,
        sanDns: String?,
    ): String {
        val algId: AlgorithmIdentifier = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WITHECDSA")
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(key) }
        val buffer = ByteArrayOutputStream()
        val signer =
            object : ContentSigner {
                override fun getAlgorithmIdentifier(): AlgorithmIdentifier = algId

                override fun getOutputStream(): OutputStream = buffer

                override fun getSignature(): ByteArray {
                    signature.update(buffer.toByteArray())
                    return signature.sign()
                }
            }
        val builder = JcaPKCS10CertificationRequestBuilder(X500Name("CN=$cn"), pub)
        if (sanDns != null) {
            val extensions = ExtensionsGenerator()
            extensions.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, sanDns)),
            )
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
        }
        return ArcTunnelPem.encode("CERTIFICATE REQUEST", builder.build(signer).encoded)
    }
}
