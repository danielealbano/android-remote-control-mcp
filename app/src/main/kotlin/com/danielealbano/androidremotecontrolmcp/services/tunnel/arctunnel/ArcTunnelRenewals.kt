package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.flow.StateFlow

/** The tunnel-start renewals as the UI sees them. */
sealed class ArcTunnelRenewalState {
    data object Idle : ArcTunnelRenewalState()

    data object Renewing : ArcTunnelRenewalState()

    data class Failed(
        val message: String,
    ) : ArcTunnelRenewalState()
}

/**
 * The tunnel-start certificate renewals, run by the tunnel session (a server stop cancels them like everything else,
 * a request in flight included) and mirrored into [state] for the UI.
 */
interface ArcTunnelRenewals {
    /** Idle → Renewing → Idle | Failed. */
    val state: StateFlow<ArcTunnelRenewalState>

    /** Issues the missing or expired public certificate and saves it; throws the failure (shown as [state]). */
    suspend fun renewForStart(identity: ArcTunnelIdentity)

    /**
     * The once-only check of a saved certificate not marked with [ARC_TUNNEL_CERT_CHECK]: a chain that reaches a trusted
     * root is only marked, any other is renewed (shown as Renewing) and saved marked, all of it logged. Older Conscrypt
     * saved incomplete chains, and clients that do not fetch intermediates refuse those.
     *
     * Never fails the tunnel start, unlike [renewForStart], because the certificate it holds is still valid: a failure, or
     * no certificate within the time limit, is logged, the certificate stays, and the next tunnel start checks again.
     */
    suspend fun checkSavedChain(identity: ArcTunnelIdentity)

    /** User Retry after a failed renewal: releases [awaitRetry]. */
    fun retry()

    /** Suspends until the user asks to retry the failed renewal. */
    suspend fun awaitRetry()

    /** The tunnel stopped: nothing is shown any more. */
    fun clear()
}
