package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.net.URI

/**
 * Remote-sent text for the server log, at most [MAX_LOGGED_CODE_POINTS] code points (a surrogate pair is never cut):
 * without control characters, Unicode line and paragraph separators, or format characters of any plane (bidi
 * controls, tag characters...), so it cannot fake a log line, reorder one or hide text in it.
 */
internal fun loggable(value: String): String {
    val kept =
        value
            .codePoints()
            .filter { !Character.isISOControl(it) && Character.getType(it) !in HIDDEN_TYPES }
            .limit(MAX_LOGGED_CODE_POINTS)
            .toArray()
    return String(kept, 0, kept.size)
}

/** The host of [uri], or null. */
internal fun host(uri: String): String? = runCatching { URI(uri).host }.getOrNull()

private const val MAX_LOGGED_CODE_POINTS = 200L

private val HIDDEN_TYPES =
    setOf(Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT).map(Byte::toInt).toSet()
