package com.danielealbano.androidremotecontrolmcp.data.model

// On-disk byte ids for ServerLogEntry.Type — NEVER renumber (constants, not literals, for detekt MagicNumber).
private const val TYPE_ID_TOOL_CALL: Byte = 0
private const val TYPE_ID_TUNNEL: Byte = 1
private const val TYPE_ID_SERVER: Byte = 2
private const val TYPE_ID_OAUTH: Byte = 3
private const val TYPE_ID_AUTH: Byte = 4
private const val TYPE_ID_CHANNEL: Byte = 5
private const val TYPE_ID_SETTINGS: Byte = 6
private const val TYPE_ID_PRIVACY: Byte = 7
private const val TYPE_ID_SHELL: Byte = 8

/**
 * Represents a single log entry from the MCP server, displayed in the
 * server logs viewer UI.
 *
 * @property timestamp The epoch milliseconds when the event occurred.
 * @property type The category of this log entry.
 * @property message A human-readable message describing the event.
 * @property toolName The MCP tool name (only for [Type.TOOL_CALL] entries).
 * @property durationMs The request processing duration in milliseconds (only for [Type.TOOL_CALL]).
 */
data class ServerLogEntry(
    val timestamp: Long,
    val type: Type,
    val message: String,
    val toolName: String? = null,
    val durationMs: Long? = null,
) {
    /** Categorizes log entries for display. Ids are the on-disk byte encoding — NEVER renumber. */
    enum class Type(
        val id: Byte,
    ) {
        /** An MCP tool call (has toolName, durationMs; message holds a failure marker or is empty). */
        TOOL_CALL(TYPE_ID_TOOL_CALL),

        /** A tunnel lifecycle event (connecting, connected, stopped, error). */
        TUNNEL(TYPE_ID_TUNNEL),

        /** A general server event (starting, started, stopping, stopped, error). */
        SERVER(TYPE_ID_SERVER),

        /** An OAuth event (registration, approval lifecycle, token grants, idle-session, revocation). */
        OAUTH(TYPE_ID_OAUTH),

        /** An authentication failure on the MCP endpoint. */
        AUTH(TYPE_ID_AUTH),

        /** An event-channel lifecycle or delivery event. */
        CHANNEL(TYPE_ID_CHANNEL),

        /** A settings change (UI or ADB). */
        SETTINGS(TYPE_ID_SETTINGS),

        /** A Privacy Mode lifecycle event (self-check result at server start). */
        PRIVACY(TYPE_ID_PRIVACY),
        SHELL(TYPE_ID_SHELL),

        ;

        companion object {
            fun fromId(id: Byte): Type? = entries.firstOrNull { it.id == id }
        }
    }
}
