package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.flow.StateFlow

/** The tunnel-start renewal as the UI sees it. */
sealed class ArcTunnelRenewalState {
    data object Idle : ArcTunnelRenewalState()

    data object Renewing : ArcTunnelRenewalState()

    data class Failed(
        val message: String,
    ) : ArcTunnelRenewalState()
}

/**
 * The tunnel-start certificate renewal, run by the tunnel session (a server stop cancels it like everything else,
 * a request in flight included) and mirrored into [state] for the UI.
 */
interface ArcTunnelRenewals {
    /** Idle → Renewing → Idle | Failed. */
    val state: StateFlow<ArcTunnelRenewalState>

    /** Issues the missing or expired public certificate and saves it; throws the failure (shown as [state]). */
    suspend fun renewForStart(identity: ArcTunnelIdentity)

    /** User Retry after a failed renewal: releases [awaitRetry]. */
    fun retry()

    /** Suspends until the user asks to retry the failed renewal. */
    suspend fun awaitRetry()

    /** The tunnel stopped: nothing is shown any more. */
    fun clear()
}
