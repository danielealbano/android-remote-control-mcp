package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@DisplayName("ArcTunnelCsr")
class ArcTunnelCsrTest {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun parse(pem: String): PKCS10CertificationRequest {
        val body =
            pem
                .lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
        return PKCS10CertificationRequest(Base64.getDecoder().decode(body))
    }

    private fun commonName(csr: PKCS10CertificationRequest): String {
        val rdn = csr.subject.getRDNs(BCStyle.CN).single()
        return rdn.first.value.toString()
    }

    private fun sanNames(csr: PKCS10CertificationRequest): List<GeneralName> {
        val attribute = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest).singleOrNull()
        val extensions = attribute?.let { Extensions.getInstance(it.attrValues.getObjectAt(0)) }
        val san = extensions?.let { GeneralNames.fromExtensions(it, Extension.subjectAlternativeName) }
        return san?.names?.toList().orEmpty()
    }

    @Test
    fun `identity CSR has CN phone and no SAN`() {
        val csr = parse(ArcTunnelCsr.identityCsr(pair.private, pair.public))

        assertEquals("phone", commonName(csr))
        assertTrue(sanNames(csr).isEmpty())
    }

    @Test
    fun `tls CSR has CN fqdn and exactly one DNS SAN equal to fqdn`() {
        val fqdn = "abcdefghij.d.example.test"

        val csr = parse(ArcTunnelCsr.tlsCsr(pair.private, pair.public, fqdn))

        assertEquals(fqdn, commonName(csr))
        val names = sanNames(csr)
        assertEquals(1, names.size)
        assertEquals(GeneralName.dNSName, names.single().tagNo)
        assertEquals(fqdn, names.single().name.toString())
    }

    @Test
    fun `CSR signature verifies with the public key`() {
        val csr = parse(ArcTunnelCsr.tlsCsr(pair.private, pair.public, "a.d.example.test"))

        assertTrue(csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(pair.public)))
    }
}
