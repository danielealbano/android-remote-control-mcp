package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents the network binding address for the MCP server.
 *
 * @property address The IP address string to bind to.
 */
enum class BindingAddress(val address: String) {
    /** Bind to localhost only. Requires ADB port forwarding for access. */
    LOCALHOST("127.0.0.1"),

    /** Bind to all network interfaces. Accessible on the local network. */
    NETWORK("0.0.0.0"),
    ;

    companion object {
        /** Returns the [BindingAddress] matching the given [address] string, or [LOCALHOST] if not found. */
        fun fromAddress(address: String): BindingAddress = entries.firstOrNull { it.address == address } ?: LOCALHOST
    }
}
