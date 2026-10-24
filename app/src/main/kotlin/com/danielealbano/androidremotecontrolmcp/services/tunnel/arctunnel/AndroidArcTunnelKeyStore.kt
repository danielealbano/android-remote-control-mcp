package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

class AndroidArcTunnelKeyStore
    @Inject
    constructor() : ArcTunnelKeyStore {
        private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

        override fun generateEcKey(
            alias: String,
            challenge: ByteArray?,
        ) {
            keyStore().deleteEntry(alias)
            val builder =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
            if (challenge != null) builder.setAttestationChallenge(challenge)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(builder.build())
                generateKeyPair()
            }
        }

        override fun hasKey(alias: String): Boolean = keyStore().containsAlias(alias)

        override fun privateKey(alias: String): PrivateKey =
            checkNotNull(keyStore().getKey(alias, null) as? PrivateKey) { "no private key under alias $alias" }

        override fun publicKey(alias: String): PublicKey =
            checkNotNull(keyStore().getCertificate(alias) as? X509Certificate) { "no key under alias $alias" }.publicKey

        override fun deleteKey(alias: String) = keyStore().deleteEntry(alias)

        override fun deleteAll(prefix: String) {
            val ks = keyStore()
            ks
                .aliases()
                .toList()
                .filter { it.startsWith(prefix) }
                .forEach { ks.deleteEntry(it) }
        }

        override fun attestationChainPem(alias: String): String {
            val chain = checkNotNull(keyStore().getCertificateChain(alias)) { "no attestation chain for $alias" }
            return chain.joinToString("") { ArcTunnelPem.encode("CERTIFICATE", it.encoded) }
        }

        private companion object {
            const val PROVIDER = "AndroidKeyStore"
        }
    }
