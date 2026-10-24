package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import java.security.cert.X509Certificate

object ArcTunnelTestIdentity {
    const val NAME = "abcdefghij"

    fun chainFor(
        keys: FakeArcTunnelKeyStore,
        alias: String,
        cn: String,
    ): List<X509Certificate> {
        val pair = java.security.KeyPair(keys.publicKey(alias), keys.privateKey(alias))
        val held =
            HeldCertificate
                .Builder()
                .keyPair(pair)
                .commonName(cn)
                .addSubjectAlternativeName(cn)
                .build()
        return listOf(held.certificate)
    }

    /** An enrolled identity: identity-0 + the fixed TLS key, with (or without) a public chain. */
    fun create(
        keys: FakeArcTunnelKeyStore,
        net: ArcTunnelNet,
        withPublicChain: Boolean = true,
        hosts: ArcTunnelHosts = ArcTunnelTestServer.hosts,
    ): ArcTunnelIdentity {
        val prefix = ArcTunnelEnroller.aliasPrefix(hosts)
        keys.generateEcKey("${prefix}identity-0")
        keys.generateEcKey("${prefix}tls")
        val state =
            ArcTunnelIdentity.State(
                identityAlias = "${prefix}identity-0",
                prevIdentityAlias = "",
                tlsAlias = "${prefix}tls",
                identityChain = chainFor(keys, "${prefix}identity-0", NAME),
                publicChain = if (withPublicChain) chainFor(keys, "${prefix}tls", hosts.fqdn(NAME)) else emptyList(),
                caId = "letsencrypt",
                generation = 0,
            )
        return ArcTunnelIdentity(net, keys, NAME, hosts, state)
    }
}
