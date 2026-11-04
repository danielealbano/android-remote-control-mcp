package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import io.netty.util.NetUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_PORT = 65535

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
    /** The address in [clientAddr] (`1.2.3.4:5` or `[::1]:5`), or null when it is missing or malformed. */
    fun clientIp(): String? {
        val isIpv6 = clientAddr.startsWith("[")
        val host =
            if (isIpv6) {
                clientAddr.substringAfter('[').substringBefore("]:", "")
            } else {
                clientAddr.substringBefore(':', "")
            }
        val port = clientAddr.substringAfterLast(':', "").toIntOrNull()
        val isIp = if (isIpv6) NetUtil.isValidIpV6Address(host) else NetUtil.isValidIpV4Address(host)
        return host.takeIf { isIp && port != null && port in 0..MAX_PORT }
    }

    /** PROTOCOL.md section 4: `client_addr` is required, and an `OPEN` without it is invalid and ignored. */
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
