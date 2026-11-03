package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate

/** Phase 1 enrollment, Phase 2 issuance and every renewal (PROTOCOL.md §2–§3) with one TLS key per name. */
class ArcTunnelEnroller(
    private val keys: ArcTunnelKeyStore,
    private val serverLog: ServerLogRepository,
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
     *
     * A received certificate is spent, and losing it would cost another against the issuance cap: from
     * [onCertificateReceived] on it is installed and [save]d whatever happens to the caller, a cancellation included.
     * [save] runs outside the retries, so a failing save does not issue again here.
     */
    suspend fun issue(
        identity: ArcTunnelIdentity,
        nonce: String?,
        onCertificateReceived: () -> Unit = {},
        save: () -> Unit = {},
    ) {
        identity.issueMutex.withLock {
            val generation = identity.state().generation
            try {
                ArcTunnelIssueRetry.run(
                    nonce = nonce,
                    issue = { n -> withContext(Dispatchers.IO) { issueOnce(identity, n, onCertificateReceived) } },
                    freshNonce = { freshNonce(identity.net, identity.hosts) },
                ) { n, wait, t -> Log.w(TAG, "issuance failed; retry $n in ${wait}ms", t) }
            } finally {
                if (identity.state().generation != generation) {
                    withContext(NonCancellable + Dispatchers.IO) { save() }
                }
            }
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
        onCertificateReceived: () -> Unit,
    ) {
        val base = identity.state()
        val nextIdentityAlias = "${aliasPrefix(identity.hosts)}identity-${base.generation + 1}"
        // The TLS key is created with the first issuance of a name and never again; a later loss means
        // the name is unusable (the UI offers a new name).
        if (base.generation == 0) {
            if (!keys.hasKey(base.tlsAlias)) keys.generateEcKey(base.tlsAlias)
        } else {
            check(keys.hasKey(base.tlsAlias)) { "the TLS key of ${identity.name} is missing; get a new name" }
        }
        keys.generateEcKey(nextIdentityAlias, ArcTunnelPem.hexToBytes(nonce))
        val installed =
            runCatching { postIssueAndInstall(identity, base, nonce, nextIdentityAlias, onCertificateReceived) }
        // Any failure before install (HTTP error, network, malformed response/chain) drops the staged key.
        val stale =
            installed.getOrElse {
                keys.deleteKey(nextIdentityAlias)
                throw it
            }
        // The new chain is in place: a leftover old key must not turn the renewal into a failure.
        if (stale.isNotEmpty() && stale != nextIdentityAlias) {
            runCatching { keys.deleteKey(stale) }
                .onFailure { Log.w(TAG, "could not delete the old identity key $stale", it) }
        }
    }

    private suspend fun postIssueAndInstall(
        identity: ArcTunnelIdentity,
        base: ArcTunnelIdentity.State,
        nonce: String,
        nextIdentityAlias: String,
        onCertificateReceived: () -> Unit,
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
        val identityChain = ArcTunnelPem.parseChain(response.identityCert)
        val publicChain = ArcTunnelPem.parseChain(response.publicCert)
        onCertificateReceived()
        val stale =
            identity.install(
                nextIdentityAlias,
                identityChain,
                publicChain,
                response.ca.ifEmpty { base.caId },
                base.generation + 1,
            )
        reportIfIncomplete(identity, publicChain)
        return stale
    }

    /**
     * PROTOCOL.md §3: an incomplete chain is still a good issuance, the certificate is spent and retrying would spend
     * another, so it stays installed and is only reported. A check that cannot run reports nothing.
     */
    private fun reportIfIncomplete(
        identity: ArcTunnelIdentity,
        publicChain: List<X509Certificate>,
    ) {
        val complete =
            runCatching { identity.net.reachesTrustedRoot(publicChain) }
                .onFailure { Log.w(TAG, "could not check the issued chain of ${identity.name}", it) }
                .getOrDefault(true)
        if (complete) return
        val count = publicChain.size
        Log.w(TAG, "the issued chain of ${identity.name} (certificates: $count) does not reach a trusted root")
        serverLog.log(
            ServerLogEntry.Type.TUNNEL,
            "Free E2E tunnel certificate chain is incomplete (certificates: $count): clients that do not fetch " +
                "intermediates may refuse it until the next renewal",
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
