package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Phase 1 enrollment, Phase 2 issuance and every renewal (PROTOCOL.md §2–§3) with one TLS key per name. */
class ArcTunnelEnroller(
    private val keys: ArcTunnelKeyStore,
) {
    /**
     * Phase 1: attested identity key + bootstrap identity cert; returns the identity (no public cert yet) +
     * issue nonce.
     */
    suspend fun enroll(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
    ): Pair<ArcTunnelIdentity, String> =
        ArcTunnelEnrollRetry.run(
            freshNonce = { freshNonce(net, hosts) },
            enroll = { nonce -> withContext(Dispatchers.IO) { phase1Once(net, hosts, nonce) } },
        ) { n, wait, t -> Log.w(TAG, "enrollment failed; retry $n in ${wait}ms", t) }

    /**
     * Phase 2 / renewal: [nonce] is the Phase-1 issue nonce or the RENEW_NUDGE nonce (null = fetch a fresh one).
     * Serialized per identity; retries per PROTOCOL.md §3.
     */
    suspend fun issue(
        identity: ArcTunnelIdentity,
        nonce: String?,
    ) {
        identity.issueMutex.withLock {
            ArcTunnelIssueRetry.run(
                nonce = nonce,
                issue = { n -> withContext(Dispatchers.IO) { issueOnce(identity, n) } },
                freshNonce = { freshNonce(identity.net, identity.hosts) },
            ) { n, wait, t -> Log.w(TAG, "issuance failed; retry $n in ${wait}ms", t) }
        }
    }

    suspend fun freshNonce(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
    ): String =
        withContext(Dispatchers.IO) {
            val url = net.url(hosts.enrollHost, NONCE_PATH)
            ArcTunnelHttp.get(ArcTunnelNonceResponse.serializer(), net.serverClient(), url).nonce
        }

    private suspend fun phase1Once(
        net: ArcTunnelNet,
        hosts: ArcTunnelHosts,
        nonce: String,
    ): Pair<ArcTunnelIdentity, String> {
        val identityAlias = "${aliasPrefix(hosts)}identity-0"
        keys.generateEcKey(identityAlias, ArcTunnelPem.hexToBytes(nonce))
        val request =
            ArcTunnelEnrollRequest(
                nonce = nonce,
                attestationChain = keys.attestationChainPem(identityAlias),
                identityCsr = ArcTunnelCsr.identityCsr(keys.privateKey(identityAlias), keys.publicKey(identityAlias)),
            )
        val response =
            ArcTunnelHttp.post(
                ArcTunnelEnrollResponse.serializer(),
                ArcTunnelHttp.enrollClient(net.serverClient()),
                net.url(hosts.enrollHost, ENROLL_PATH),
                ArcTunnelJson.encodeToString(ArcTunnelEnrollRequest.serializer(), request),
            )
        val state =
            ArcTunnelIdentity.State(
                identityAlias = identityAlias,
                prevIdentityAlias = "",
                tlsAlias = tlsAlias(hosts),
                identityChain = ArcTunnelPem.parseChain(response.identityCert),
                publicChain = emptyList(),
                caId = "",
                generation = 0,
            )
        return ArcTunnelIdentity(net, keys, response.name, hosts, state) to response.issueNonce
    }

    /** One `/issue` attempt: fresh attested identity key, the FIXED TLS key (created on first use), then install. */
    private suspend fun issueOnce(
        identity: ArcTunnelIdentity,
        nonce: String,
    ) {
        val base = identity.state()
        val generation = base.generation + 1
        val nextIdentityAlias = "${aliasPrefix(identity.hosts)}identity-$generation"
        // The TLS key is created with the first issuance of a name and never again; a later loss means
        // the name is unusable (the UI offers a new name).
        if (base.generation == 0) {
            if (!keys.hasKey(base.tlsAlias)) keys.generateEcKey(base.tlsAlias)
        } else {
            check(keys.hasKey(base.tlsAlias)) { "the TLS key of ${identity.name} is missing; get a new name" }
        }
        keys.generateEcKey(nextIdentityAlias, ArcTunnelPem.hexToBytes(nonce))
        val installed = runCatching { postIssueAndInstall(identity, base, nonce, nextIdentityAlias, generation) }
        // Any failure before install (HTTP error, network, malformed response/chain) drops the staged key.
        val stale =
            installed.getOrElse {
                keys.deleteKey(nextIdentityAlias)
                throw it
            }
        if (stale.isNotEmpty() && stale != nextIdentityAlias) keys.deleteKey(stale)
    }

    private suspend fun postIssueAndInstall(
        identity: ArcTunnelIdentity,
        base: ArcTunnelIdentity.State,
        nonce: String,
        nextIdentityAlias: String,
        generation: Int,
    ): String {
        val identityKey = keys.privateKey(nextIdentityAlias)
        val tlsKey = keys.privateKey(base.tlsAlias)
        val request =
            ArcTunnelIssueRequest(
                nonce = nonce,
                attestationChain = keys.attestationChainPem(nextIdentityAlias),
                identityCsr = ArcTunnelCsr.identityCsr(identityKey, keys.publicKey(nextIdentityAlias)),
                tlsCsr = ArcTunnelCsr.tlsCsr(tlsKey, keys.publicKey(base.tlsAlias), identity.fqdn()),
            )
        val response =
            ArcTunnelHttp.post(
                ArcTunnelIssueResponse.serializer(),
                ArcTunnelHttp.issueClient(identity.mtlsClient()),
                identity.net.url(identity.hosts.controlHost, ISSUE_PATH),
                ArcTunnelJson.encodeToString(ArcTunnelIssueRequest.serializer(), request),
            )
        return identity.install(
            nextIdentityAlias,
            ArcTunnelPem.parseChain(response.identityCert),
            ArcTunnelPem.parseChain(response.publicCert),
            response.ca.ifEmpty { base.caId },
            generation,
        )
    }

    companion object {
        private const val TAG = "MCP:ArcTunnelEnroller"
        private const val NONCE_PATH = "/api/v1/enroll/nonce"
        private const val ENROLL_PATH = "/api/v1/enroll"
        private const val ISSUE_PATH = "/api/v1/issue"

        fun aliasPrefix(hosts: ArcTunnelHosts): String = "arc-tunnel-${hosts.envKey}-"

        fun tlsAlias(hosts: ArcTunnelHosts): String = "${aliasPrefix(hosts)}tls"
    }
}
