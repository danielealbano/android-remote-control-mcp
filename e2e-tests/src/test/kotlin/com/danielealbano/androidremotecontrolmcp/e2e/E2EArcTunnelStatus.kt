package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirror of the app's debug `E2EArcTunnelStatus` (same JSON contract; both MUST change together). */
@Serializable
data class E2EArcTunnelStatus(
    @SerialName("enrolled") val enrolled: Boolean,
    @SerialName("url") val url: String?,
    @SerialName("enrollment") val enrollment: String,
    @SerialName("tunnel") val tunnel: String,
    @SerialName("tunnel_url") val tunnelUrl: String?,
    @SerialName("server") val server: String,
    @SerialName("base_address") val baseAddress: String,
    @SerialName("edge_override") val edgeOverride: String,
    @SerialName("ct_check_enabled") val ctCheckEnabled: Boolean,
    @SerialName("tunnel_enabled") val tunnelEnabled: Boolean,
    @SerialName("tunnel_provider") val tunnelProvider: String,
    @SerialName("bearer_token_enabled") val bearerTokenEnabled: Boolean,
) {
    /** The tunnel-related settings of this status, to restore a device afterwards. */
    fun toSettings(): ArcTunnelE2ESettings =
        ArcTunnelE2ESettings(
            tunnelEnabled = tunnelEnabled,
            provider = tunnelProvider,
            baseAddress = baseAddress,
            ctCheckEnabled = ctCheckEnabled,
            edgeOverride = edgeOverride,
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): E2EArcTunnelStatus = json.decodeFromString(serializer(), text)
    }
}

/** Mirror of the app's debug `E2EArcTunnelChainProbe`, which says what its fields mean; both MUST change together. */
@Serializable
data class E2EArcTunnelChainProbe(
    @SerialName("saved_certificates") val savedCertificates: Int,
    @SerialName("factory_certificates") val factoryCertificates: Int,
    @SerialName("parsed_certificates") val parsedCertificates: Int,
    @SerialName("whole_reaches_root") val wholeReachesRoot: Boolean,
    @SerialName("leaf_reaches_root") val leafReachesRoot: Boolean,
) {
    companion object {
        fun parse(text: String): E2EArcTunnelChainProbe = Json.decodeFromString(serializer(), text)
    }
}

/** What [AndroidContainerSetup.configureArcTunnel] sends. */
data class ArcTunnelE2ESettings(
    val tunnelEnabled: Boolean,
    val provider: String,
    val baseAddress: String,
    val ctCheckEnabled: Boolean,
    val edgeOverride: String = "",
    val trustedCaPem: String = "",
) {
    companion object {
        const val ARC_PROVIDER = "android-remote-control-mcp-tunneld"
        const val CLOUDFLARE_PROVIDER = "CLOUDFLARE"
    }
}
