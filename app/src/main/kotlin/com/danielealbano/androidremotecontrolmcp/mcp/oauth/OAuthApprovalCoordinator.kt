package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.geo.GeoLocation
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle state of an on-device authorization approval. */
enum class ApprovalState { PENDING, APPROVED, DENIED, EXPIRED }

/**
 * A pending on-device approval surfaced to the user.
 *
 * @property id High-entropy, unguessable token (the `/authorize/status` poll key).
 * @property clientName Resolved display name (never null).
 * @property redirectHost Host of the client's redirect URI (display).
 * @property matchCode 2-digit confirmation code shown in the browser and the app (unique among pending).
 * @property expiresAtMs Approval window deadline.
 * @property logoUri DCR `logo_uri` of the requesting client (SSRF-guarded at render), else null.
 * @property clientIp Source IP of the request, from what its listener trusts (`ClientIpSource`), else null.
 * @property clientGeo Offline geolocation of [clientIp] (country/city), else null.
 */
data class PendingApproval(
    val id: String,
    val clientName: String,
    val redirectHost: String,
    val matchCode: String,
    val expiresAtMs: Long,
    val logoUri: String? = null,
    val clientIp: String? = null,
    val clientGeo: GeoLocation? = null,
)

/** The requesting-client identity captured at `/authorize` and surfaced on the approval. */
data class ApprovalRequest(
    val clientName: String,
    val redirectHost: String,
    val logoUri: String?,
    val clientIp: String?,
    val clientGeo: GeoLocation?,
)

/**
 * Coordinates the number-match on-device approval: `/authorize` registers a pending request with a
 * 2-digit code, the user approves/denies in-app, and `/authorize/status` polls the resulting state.
 */
interface OAuthApprovalCoordinator {
    /** The currently-PENDING approvals (drives the in-app screen + notification). */
    fun observePending(): StateFlow<List<PendingApproval>>

    suspend fun createPending(
        request: ApprovalRequest,
        nowMs: Long,
    ): PendingApproval

    /**
     * Approves a still-PENDING request. A request whose window has lapsed ([nowMs] >= its deadline) is
     * marked EXPIRED instead of APPROVED — expiry MUST win over a late approval (security).
     */
    suspend fun approve(
        id: String,
        nowMs: Long,
    )

    suspend fun deny(id: String)

    suspend fun stateOf(
        id: String,
        nowMs: Long,
    ): ApprovalState
}
