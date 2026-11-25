package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import java.security.ProviderException

/**
 * The attested identity key is generated with the device properties (brand, device, product, manufacturer and
 * model) in its attestation, so the tunnel service learns the phone's model. A keystore that cannot attest them
 * refuses the whole generation with a [ProviderException], the type of every keystore failure, a transient one
 * included; on any of them the key is generated again without the properties.
 */
object ArcTunnelDeviceProps {
    private const val TAG = "MCP:ArcTunnelDevProps"

    /**
     * Runs [withProps]. On a [ProviderException] logs it at Info and runs [withoutProps], whose failure reaches the
     * caller. Any other failure of [withProps] reaches the caller unchanged, without running [withoutProps].
     */
    fun withFallback(
        withProps: () -> Unit,
        withoutProps: () -> Unit,
    ) {
        try {
            withProps()
        } catch (e: ProviderException) {
            Log.i(TAG, "generating the key with the device properties failed; generating it again without them", e)
            withoutProps()
        }
    }
}
