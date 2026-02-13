package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a single log entry from the MCP server, displayed in the
 * server logs viewer UI.
 *
 * @property timestamp The epoch milliseconds when the event occurred.
 * @property type The category of this log entry.
 * @property message A human-readable message describing the event.
 * @property toolName The MCP tool name (only for [Type.TOOL_CALL] entries).
 * @property params The request parameters, truncated to [MAX_PARAMS_LENGTH] characters (only for [Type.TOOL_CALL]).
 * @property durationMs The request processing duration in milliseconds (only for [Type.TOOL_CALL]).
 */
data class ServerLogEntry(
    val timestamp: Long,
    val type: Type,
    val message: String,
    val toolName: String? = null,
    val params: String? = null,
    val durationMs: Long? = null,
) {
    /** Categorizes log entries for display. */
    enum class Type {
        /** An MCP tool call (has toolName, params, durationMs). */
        TOOL_CALL,

        /** A tunnel lifecycle event (connected, disconnected, error). */
        TUNNEL,

        /** A general server event (started, stopped, etc.). */
        SERVER,
    }

    companion object {
        const val MAX_PARAMS_LENGTH = 100
    }
}
