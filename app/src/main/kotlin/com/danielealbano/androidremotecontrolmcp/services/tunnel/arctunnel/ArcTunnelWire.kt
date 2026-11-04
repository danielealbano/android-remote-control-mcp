package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import io.netty.util.NetUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_PORT = 65535

// [0-9], not \d: on Android \d also matches digits of other scripts, and toInt() throws on some of them.
private val BRACKETED_HOST_PORT = Regex("""\[([^\[\]]+)]:([0-9]{1,5})""")
private val PLAIN_HOST_PORT = Regex("""([^:\[\]]+):([0-9]{1,5})""")

val ArcTunnelJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

@Serializable
data class ArcTunnelNonceResponse(
    val nonce: String,
)

@Serializable
data class ArcTunnelEnrollRequest(
    val nonce: String,
    @SerialName("attestation_chain") val attestationChain: String,
    @SerialName("identity_csr") val identityCsr: String,
)

@Serializable
data class ArcTunnelEnrollResponse(
    val name: String,
    @SerialName("identity_cert") val identityCert: String,
    @SerialName("issue_nonce") val issueNonce: String,
)

@Serializable
data class ArcTunnelIssueRequest(
    val nonce: String,
    @SerialName("attestation_chain") val attestationChain: String,
    @SerialName("identity_csr") val identityCsr: String,
    @SerialName("tls_csr") val tlsCsr: String,
)

@Serializable
data class ArcTunnelIssueResponse(
    @SerialName("identity_cert") val identityCert: String,
    @SerialName("public_cert") val publicCert: String,
    val ca: String = "",
)

@Serializable
data class ArcTunnelApiErrorBody(
    val reason: String = "",
    val retryable: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long = 0,
)

@Serializable
data class ArcTunnelOpenPayload(
    val token: String = "",
    val addr: String = "",
    @SerialName("use_https") val useHttps: Boolean = false,
    @SerialName("use_mtls") val useMtls: Boolean = false,
    @SerialName("client_addr") val clientAddr: String = "",
) {
    /**
     * The address in [clientAddr] (`1.2.3.4:5` or `[::1]:5`), or null when it is missing or malformed. The zone a
     * link-local peer comes with (`[fe80::1%eth0]:5`) is dropped: an address is all that may reach the approval screen.
     */
    fun clientIp(): String? {
        val ipv6 = BRACKETED_HOST_PORT.matchEntire(clientAddr)
        val ipv4 = PLAIN_HOST_PORT.matchEntire(clientAddr)
        val host = ipv6?.groupValues?.get(1)?.substringBefore('%') ?: ipv4?.groupValues?.get(1)
        val port = (ipv6 ?: ipv4)?.groupValues?.get(2)?.toInt()
        val isIp =
            if (ipv6 != null) NetUtil.isValidIpV6Address(host.orEmpty()) else NetUtil.isValidIpV4Address(host.orEmpty())
        return host.takeIf { isIp && port != null && port <= MAX_PORT }
    }

    /**
     * PROTOCOL.md section 4: `client_addr` is required, and an `OPEN` without it or with an empty one is invalid and
     * ignored. A malformed one is treated the same.
     */
    fun isValid(): Boolean = token.isNotEmpty() && addr.isNotEmpty() && clientIp() != null && (useHttps || !useMtls)
}

@Serializable
data class ArcTunnelRenewNudgePayload(
    val nonce: String = "",
)

@Serializable
data class ArcTunnelMovePayload(
    val addrs: List<String> = emptyList(),
)

/** Structured server error (PROTOCOL.md §2–§3): `{reason, retryable, retry_after_seconds}` + HTTP status. */
class ArcTunnelApiException(
    val reason: String,
    val retryable: Boolean,
    val retryAfterSec: Long,
    val status: Int,
) : RuntimeException("tunnel service: $reason (status $status)")
