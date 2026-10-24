package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

/** Callbacks of [ArcTunnelControl]; invoked on the control loop's coroutines (never the main thread). */
interface ArcTunnelControlEvents {
    fun onBound(node: String)

    fun onUnbound()

    /** One renewal at a time; a nudge arriving while one runs is ignored. */
    suspend fun onRenewNudge(nonce: String)

    fun onDialBackFailed(message: String)

    /** A control stream attempt failed or ended on an error (bound or not); [reason] is user-facing. */
    fun onAttemptFailed(reason: String)
}
