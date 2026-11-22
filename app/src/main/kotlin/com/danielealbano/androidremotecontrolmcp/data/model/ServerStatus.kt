package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents the current state of the MCP server lifecycle.
 *
 * Used as [kotlinx.coroutines.flow.StateFlow] value in ViewModels
 * and observed by the UI to display server status.
 */
sealed class ServerStatus {
    /** The MCP server is stopped and not listening for connections. */
    data object Stopped : ServerStatus()

    /** The MCP server is in the process of starting up. */
    data object Starting : ServerStatus()

    /**
     * The MCP server is running and accepting connections.
     *
     * @property port The port the server is listening on.
     * @property bindingAddress The IP address the server is bound to.
     * @property httpsEnabled Whether HTTPS is enabled (optional, disabled by default).
     * @property oauthEnabled Whether the server started with OAuth; a change in the settings applies on restart.
     * @property publicUrlOverride The public URL override the server started with; a change applies on restart.
     */
    data class Running(
        val port: Int,
        val bindingAddress: String,
        val httpsEnabled: Boolean = false,
        val oauthEnabled: Boolean = true,
        val publicUrlOverride: String = "",
    ) : ServerStatus()

    /** The MCP server is in the process of shutting down. */
    data object Stopping : ServerStatus()

    /**
     * The MCP server encountered an error.
     *
     * @property message A human-readable description of the error.
     */
    data class Error(
        val message: String,
    ) : ServerStatus()
}
