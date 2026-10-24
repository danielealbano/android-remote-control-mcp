package com.danielealbano.androidremotecontrolmcp.services.tunnel

import java.security.PrivateKey
import java.security.cert.X509Certificate

/** TLS material of the tunnel listener: the (keystore-backed) private key and the issued chain, leaf first. */
class TunnelTlsMaterial(
    val privateKey: PrivateKey,
    val chain: List<X509Certificate>,
)

/** A host able to serve the MCP application on a loopback TLS listener for a tunnel provider. */
interface TunnelListenerHost {
    /**
     * Starts the listener on `127.0.0.1`: on [port] when given, otherwise on a random free port. [onRequest]
     * runs once per HTTP request received through it. Returns the bound port.
     *
     * @throws IllegalStateException when the server is not running, a listener already runs or no port could be bound.
     */
    fun startTlsListener(
        material: TunnelTlsMaterial,
        port: Int?,
        onRequest: () -> Unit,
    ): Int

    /** Stops the listener; no-op when none runs. Bounded: 100 ms grace, 500 ms timeout. */
    fun stopTlsListener()
}
