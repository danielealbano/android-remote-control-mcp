package com.danielealbano.androidremotecontrolmcp.debug

import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelNet
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelPem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory

/**
 * Answer of the `E2E_ARC_TUNNEL_CHAIN_PROBE` broadcast (Base64 JSON in the result data): the certificate chain handling
 * on this device's own Conscrypt, inside the real app process, which no JVM test can reach. The e2e module keeps an
 * identical copy of the fields (`E2EArcTunnelChainProbe` in `e2e-tests`): both MUST change together.
 *
 * [factoryCertificates] and [parsedCertificates] read the saved chain written with a blank line between its
 * certificates, as Let's Encrypt sends it: the first through `generateCertificates`, the second through
 * [ArcTunnelPem.parseChain]. [wholeReachesRoot] is checked before [leafReachesRoot], so a trust manager shared between
 * the checks would remember the intermediates and pass the leaf.
 */
@Serializable
data class E2EArcTunnelChainProbe(
    @SerialName("saved_certificates") val savedCertificates: Int,
    @SerialName("factory_certificates") val factoryCertificates: Int,
    @SerialName("parsed_certificates") val parsedCertificates: Int,
    @SerialName("whole_reaches_root") val wholeReachesRoot: Boolean,
    @SerialName("leaf_reaches_root") val leafReachesRoot: Boolean,
) {
    companion object {
        fun of(
            savedChainPem: String,
            net: ArcTunnelNet,
        ): E2EArcTunnelChainProbe {
            val saved = ArcTunnelPem.parseChain(savedChainPem)
            val bundle = saved.joinToString("\n") { ArcTunnelPem.encode("CERTIFICATE", it.encoded) }
            val factory = CertificateFactory.getInstance("X.509")
            return E2EArcTunnelChainProbe(
                savedCertificates = saved.size,
                factoryCertificates = factory.generateCertificates(ByteArrayInputStream(bundle.toByteArray())).size,
                parsedCertificates = ArcTunnelPem.parseChain(bundle).size,
                wholeReachesRoot = net.reachesTrustedRoot(saved),
                leafReachesRoot = net.reachesTrustedRoot(saved.take(1)),
            )
        }
    }
}
