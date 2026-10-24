package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** Rebuilds an identity from its saved record; throws when its identity key is missing from the keystore. */
internal fun restoreArcTunnelIdentity(
    env: ArcTunnelEnv,
    saved: ArcTunnelSavedIdentity,
    keys: ArcTunnelKeyStore,
    net: ArcTunnelNet,
): ArcTunnelIdentity {
    check(keys.hasKey(saved.identityAlias)) { "saved identity ${saved.name} has no usable identity key" }
    val state =
        ArcTunnelIdentity.State(
            identityAlias = saved.identityAlias,
            prevIdentityAlias = saved.prevIdentityAlias,
            tlsAlias = saved.tlsAlias,
            identityChain = ArcTunnelPem.parseChain(saved.identityChain),
            publicChain = ArcTunnelPem.parseChain(saved.publicChain),
            caId = saved.ca,
            generation = saved.generation,
        )
    return ArcTunnelIdentity(net, keys, saved.name, env.hosts, state)
}

/** True when the identity has no public certificate or an expired one (issuance needed). */
internal fun certificateMissingOrExpired(identity: ArcTunnelIdentity): Boolean {
    val leaf = identity.publicLeaf()
    return leaf == null || leaf.notAfter.before(Date())
}

/**
 * True when the saved identity certificate (the mTLS client certificate) is missing or expired: the name can
 * no longer be renewed (PROTOCOL.md §3), only re-enrolled.
 */
internal fun identityCertificateExpired(saved: ArcTunnelSavedIdentity): Boolean {
    val leaf = ArcTunnelPem.parseChain(saved.identityChain).firstOrNull()
    return leaf == null || leaf.notAfter.before(Date())
}

/** Enroll / resume / forget workflows of [ArcTunnelEnrollmentImpl]; every call runs under the service mutex. */
internal class ArcTunnelEnrollmentFlow(
    private val keys: ArcTunnelKeyStore,
    private val enroller: ArcTunnelEnroller,
) {
    /**
     * Idempotent "make enrolled": a saved identity with a valid certificate is returned untouched; one with a
     * missing/expired certificate only issues (same name); nothing saved → fresh two-phase enrollment.
     */
    suspend fun enrollOrResume(
        env: ArcTunnelEnv,
        store: ArcTunnelIdentityStore,
        net: ArcTunnelNet,
    ): ArcTunnelIdentity {
        val saved = withContext(Dispatchers.IO) { store.load() }
        if (saved != null && keys.hasKey(saved.identityAlias) && !identityCertificateExpired(saved)) {
            val resumed = restoreArcTunnelIdentity(env, saved, keys, net)
            if (certificateMissingOrExpired(resumed)) {
                enroller.issue(resumed, null)
                withContext(Dispatchers.IO) { store.save(resumed) }
            }
            return resumed
        }
        // An unusable record (identity key gone or identity certificate expired): worthless without a public
        // certificate (start over below); with one the user must pick a new name (the URL shows the icon).
        check(saved == null || saved.publicChain.isEmpty()) { "identity ${saved?.name} is unusable; get a new name" }
        forget(env, store)
        val (identity, issueNonce) = enroller.enroll(net, env.hosts)
        withContext(Dispatchers.IO) { store.save(identity) }
        enroller.issue(identity, issueNonce)
        withContext(Dispatchers.IO) { store.save(identity) }
        return identity
    }

    /** Deletes the saved identity and every key of the environment. */
    suspend fun forget(
        env: ArcTunnelEnv,
        store: ArcTunnelIdentityStore,
    ) {
        withContext(Dispatchers.IO) {
            store.forget()
            keys.deleteAll(ArcTunnelEnroller.aliasPrefix(env.hosts))
        }
    }
}
