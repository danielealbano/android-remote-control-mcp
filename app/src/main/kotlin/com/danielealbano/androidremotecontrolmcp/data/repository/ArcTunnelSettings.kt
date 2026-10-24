package com.danielealbano.androidremotecontrolmcp.data.repository

/** Settings of the free E2E tunnel provider; [SettingsRepository] exposes them through delegation. */
interface ArcTunnelSettings {
    /** Updates the base address of the free E2E tunnel service. */
    suspend fun updateArcTunnelBaseAddress(baseAddress: String)

    /** Updates the Certificate Transparency check toggle of the free E2E tunnel. */
    suspend fun updateArcTunnelCtCheckEnabled(enabled: Boolean)

    /**
     * Debug/E2E only: points the tunnel client at [edgeOverride] (`host:port`, empty = none) trusting
     * [trustedCaPem].
     */
    suspend fun updateArcTunnelE2EOverride(
        edgeOverride: String,
        trustedCaPem: String,
    )
}
