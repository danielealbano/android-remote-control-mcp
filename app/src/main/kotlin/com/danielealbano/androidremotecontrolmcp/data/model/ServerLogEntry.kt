package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a single log entry from the MCP server, displayed in the
 * server logs viewer UI.
 *
 * @property timestamp The epoch milliseconds when the request was received.
 * @property toolName The MCP tool or HTTP endpoint name invoked.
 * @property params The request parameters, truncated to [MAX_PARAMS_LENGTH] characters for display.
 * @property durationMs The request processing duration in milliseconds.
 */
data class ServerLogEntry(
    val timestamp: Long,
    val toolName: String,
    val params: String,
    val durationMs: Long,
) {
    companion object {
        const val MAX_PARAMS_LENGTH = 100
    }
}
