package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import java.security.MessageDigest

/** Host names of a free E2E tunnel service, all derived from one base address. */
data class ArcTunnelHosts(
    val baseAddress: String,
) {
    val enrollHost: String = "enroll.$baseAddress"
    val controlHost: String = "connect.$baseAddress"
    val tunnelDomain: String = "d.$baseAddress"

    /** Stable per-environment key (store file name + keystore alias prefix). */
    val envKey: String =
        "e" +
            MessageDigest
                .getInstance("SHA-256")
                .digest("$enrollHost\n$controlHost\n$tunnelDomain".toByteArray())
                .take(ENV_KEY_BYTES)
                .joinToString("") { "%02x".format(it) }

    fun fqdn(name: String): String = "$name.$tunnelDomain"

    fun publicUrl(name: String): String = "https://${fqdn(name)}"

    companion object {
        private const val ENV_KEY_BYTES = 6
        private const val MAX_LENGTH = 253
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** Lowercase DNS name with at least two labels, no scheme, no port, no trailing dot. */
        fun isValidBaseAddress(value: String): Boolean {
            val labels = value.split('.')
            return value.length <= MAX_LENGTH && labels.size >= 2 && labels.all { LABEL.matches(it) }
        }
    }
}
