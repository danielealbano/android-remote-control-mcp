package com.danielealbano.androidremotecontrolmcp.debug

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Answer of the `E2E_ARC_TUNNEL_QUERY` broadcast (Base64 JSON in the result data). The e2e module keeps an
 * identical copy (`E2EArcTunnelStatus` in `e2e-tests`): both MUST change together.
 */
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
)
