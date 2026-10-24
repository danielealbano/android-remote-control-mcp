package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.security.PrivateKey
import java.security.PublicKey

/** Non-exportable EC P-256 keys of the tunnel client; only CSRs and attestation chains ever leave the store. */
interface ArcTunnelKeyStore {
    /** Generates a P-256 key under [alias]; with a [challenge] the key is hardware-attested over it. */
    fun generateEcKey(
        alias: String,
        challenge: ByteArray? = null,
    )

    fun hasKey(alias: String): Boolean

    fun privateKey(alias: String): PrivateKey

    fun publicKey(alias: String): PublicKey

    fun deleteKey(alias: String)

    fun deleteAll(prefix: String)

    /** PEM bundle of the attestation chain (leaf first) the server's seven-point verifier checks. */
    fun attestationChainPem(alias: String): String
}
