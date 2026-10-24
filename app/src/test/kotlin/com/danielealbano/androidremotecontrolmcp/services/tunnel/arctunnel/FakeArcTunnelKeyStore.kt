package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import okhttp3.tls.HeldCertificate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/** In-memory P-256 keys for JVM tests; records every generation so tests can assert alias lifecycles. */
class FakeArcTunnelKeyStore : ArcTunnelKeyStore {
    private val keys = ConcurrentHashMap<String, KeyPair>()
    val generated = mutableListOf<Pair<String, ByteArray?>>()
    val deleted = mutableListOf<String>()

    override fun generateEcKey(
        alias: String,
        challenge: ByteArray?,
    ) {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        keys[alias] = generator.generateKeyPair()
        generated += alias to challenge
    }

    override fun hasKey(alias: String): Boolean = keys.containsKey(alias)

    override fun privateKey(alias: String): PrivateKey = checkNotNull(keys[alias]) { "no key $alias" }.private

    override fun publicKey(alias: String): PublicKey = checkNotNull(keys[alias]) { "no key $alias" }.public

    override fun deleteKey(alias: String) {
        keys.remove(alias)
        deleted += alias
    }

    override fun deleteAll(prefix: String) {
        keys.keys.filter { it.startsWith(prefix) }.forEach { deleteKey(it) }
    }

    override fun attestationChainPem(alias: String): String {
        val pair = checkNotNull(keys[alias]) { "no key $alias" }
        val cert =
            HeldCertificate
                .Builder()
                .keyPair(pair)
                .commonName(alias)
                .build()
                .certificate
        return ArcTunnelPem.encode("CERTIFICATE", cert.encoded)
    }
}
