package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelTlsMaterial
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Atomic {alias, chain, key} snapshot of the live identity, read by the mTLS key manager per handshake. */
class ArcTunnelCred(
    val alias: String,
    val chain: List<X509Certificate>,
    val key: PrivateKey,
)

/**
 * An enrolled tunnel identity: the server-assigned name, the rotating identity key/cert, and the TLS key
 * (fixed per name) with its issued public chain. State is guarded by `lock`; issuances are serialized by
 * [issueMutex].
 */
class ArcTunnelIdentity(
    val net: ArcTunnelNet,
    val keys: ArcTunnelKeyStore,
    val name: String,
    val hosts: ArcTunnelHosts,
    initial: State,
) {
    data class State(
        val identityAlias: String,
        val prevIdentityAlias: String,
        val tlsAlias: String,
        val identityChain: List<X509Certificate>,
        val publicChain: List<X509Certificate>,
        val caId: String,
        val generation: Int,
    )

    private val lock = Any()
    val issueMutex = Mutex()
    private var state = initial

    fun state(): State = synchronized(lock) { state }

    fun identityCred(): ArcTunnelCred =
        synchronized(lock) {
            ArcTunnelCred(state.identityAlias, state.identityChain, keys.privateKey(state.identityAlias))
        }

    fun tlsMaterial(): TunnelTlsMaterial =
        synchronized(lock) {
            check(keys.hasKey(state.tlsAlias)) { "the TLS key of $name is missing from the keystore; get a new name" }
            TunnelTlsMaterial(keys.privateKey(state.tlsAlias), state.publicChain)
        }

    fun publicLeaf(): X509Certificate? = synchronized(lock) { state.publicChain.firstOrNull() }

    fun fqdn(): String = hosts.fqdn(name)

    fun publicUrl(): String = hosts.publicUrl(name)

    fun mtlsClient(): OkHttpClient = net.mtlsClient(this)

    /**
     * Installs a rotated identity + renewed public chain; returns the identity alias two generations back
     * (to delete).
     */
    fun install(
        nextIdentityAlias: String,
        identityChain: List<X509Certificate>,
        publicChain: List<X509Certificate>,
        caId: String,
        generation: Int,
    ): String =
        synchronized(lock) {
            val stale = state.prevIdentityAlias
            state =
                state.copy(
                    prevIdentityAlias = state.identityAlias,
                    identityAlias = nextIdentityAlias,
                    identityChain = identityChain,
                    publicChain = publicChain,
                    caId = caId,
                    generation = generation,
                )
            stale
        }
}
