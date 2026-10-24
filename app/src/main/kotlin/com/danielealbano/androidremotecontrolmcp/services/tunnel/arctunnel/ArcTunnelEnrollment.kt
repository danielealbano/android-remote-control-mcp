package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.flow.StateFlow

sealed class ArcTunnelEnrollmentState {
    data object Idle : ArcTunnelEnrollmentState()

    data object Enrolling : ArcTunnelEnrollmentState()

    data class Failed(
        val message: String,
    ) : ArcTunnelEnrollmentState()
}

/** A certificate for the tunnel name whose key is not the phone's TLS key. */
data class ArcTunnelCtAlert(
    val fqdn: String,
    val pubkeySha256: String,
)

/** Owns the per-environment identity stores and serializes enrollment, rotation, forget and renewal. */
interface ArcTunnelEnrollment {
    /**
     * Idle → Enrolling → Idle | Failed; a renewal that saves the certificate turns Failed into Idle. Observed by
     * the UI.
     */
    val state: StateFlow<ArcTunnelEnrollmentState>

    fun store(env: ArcTunnelEnv): ArcTunnelIdentityStore

    fun net(env: ArcTunnelEnv): ArcTunnelNet

    /** The public URL of a saved identity that HAS a certificate (UI display, no keystore access); null otherwise. */
    fun savedUrl(env: ArcTunnelEnv): String?

    /** The persisted, undismissed CT alert of the environment, if any. */
    fun savedCtAlert(env: ArcTunnelEnv): ArcTunnelCtAlert?

    /**
     * Starts the enrollment foreground service to make the environment enrolled (see [execute]
     * with `rotate = false`).
     * When Android refuses the foreground start (app in the background), [state] becomes `Failed`.
     */
    fun requestEnroll(env: ArcTunnelEnv)

    /** Starts the enrollment foreground service to forget the saved identity and enroll anew (refresh icon). */
    fun requestRotate(env: ArcTunnelEnv)

    /**
     * One request, run by the service: `Enrolling` → `Idle` | `Failed`. Without [rotate]: a saved identity with a
     * valid certificate is left as is, one without (or with an expired) certificate only issues (same name),
     * nothing saved enrolls (Phase 1 + Phase 2). With [rotate]: forget, then enroll. A request while one runs
     * returns at once.
     */
    suspend fun execute(
        env: ArcTunnelEnv,
        rotate: Boolean,
    )

    /**
     * The saved identity, restored without network access after an in-flight request ends; null when nothing is
     * saved. A missing or expired public certificate is renewed by the caller through [ArcTunnelRenewals].
     */
    suspend fun readyIdentity(env: ArcTunnelEnv): ArcTunnelIdentity?

    /**
     * One renewal (nudge nonce or fresh nonce), saved only while [identity] is still the saved one; a saved
     * certificate also clears a `Failed` [state].
     */
    suspend fun renew(
        identity: ArcTunnelIdentity,
        nonce: String?,
    )

    /** Clears the persisted undismissed CT alert of the environment (the UI dismissed it). */
    suspend fun dismissCtAlert(env: ArcTunnelEnv)
}
