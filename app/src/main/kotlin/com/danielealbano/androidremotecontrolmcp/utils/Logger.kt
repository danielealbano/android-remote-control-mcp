package com.danielealbano.androidremotecontrolmcp.utils

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig

/**
 * Logging wrapper that sanitizes sensitive data and respects build type.
 *
 * **Why this exists instead of raw `android.util.Log` calls:**
 * 1. **Sanitization** -- Bearer tokens (UUID format) are automatically masked
 *    with `[REDACTED]` before reaching the log output, preventing accidental
 *    credential exposure. Every log call passes through [sanitize] so developers
 *    cannot accidentally log a token in plaintext.
 * 2. **Build-type filtering** -- Debug-level messages are suppressed in release
 *    builds (`BuildConfig.DEBUG` gate), reducing log noise in production without
 *    requiring callers to check the build type themselves.
 *
 * All services and utilities should use this logger instead of calling
 * `android.util.Log` directly. Log tags should follow the `MCP:<Component>`
 * convention (e.g., `MCP:ServerService`, `MCP:AccessibilityService`).
 *
 * - **Debug builds**: All log levels are emitted (verbose, debug, info, warn, error).
 * - **Release builds**: Only info, warn, and error are emitted.
 */
object Logger {
    /**
     * Regex matching UUID strings (standard 8-4-4-4-12 hex format).
     * Used to detect and sanitize bearer tokens in log messages.
     */
    private val UUID_PATTERN =
        Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )

    private const val REDACTED_TOKEN = "[REDACTED]"

    /**
     * Logs a debug-level message. Only emitted in debug builds.
     *
     * @param tag Log tag identifying the source component.
     * @param message The message to log. Bearer tokens are auto-sanitized.
     */
    fun d(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, sanitize(message))
        }
    }

    /**
     * Logs an info-level message.
     *
     * @param tag Log tag identifying the source component.
     * @param message The message to log. Bearer tokens are auto-sanitized.
     */
    fun i(
        tag: String,
        message: String,
    ) {
        Log.i(tag, sanitize(message))
    }

    /**
     * Logs a warning-level message.
     *
     * @param tag Log tag identifying the source component.
     * @param message The message to log. Bearer tokens are auto-sanitized.
     * @param throwable Optional throwable to include in the log.
     */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.w(tag, sanitize(message), throwable)
        } else {
            Log.w(tag, sanitize(message))
        }
    }

    /**
     * Logs an error-level message.
     *
     * @param tag Log tag identifying the source component.
     * @param message The message to log. Bearer tokens are auto-sanitized.
     * @param throwable Optional throwable to include in the log.
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.e(tag, sanitize(message), throwable)
        } else {
            Log.e(tag, sanitize(message))
        }
    }

    /**
     * Sanitizes a log message by replacing UUID-format strings with [REDACTED_TOKEN].
     *
     * This prevents bearer tokens (which are UUIDs) from appearing in logs.
     *
     * @param message The raw log message.
     * @return The sanitized message with UUIDs replaced.
     */
    internal fun sanitize(message: String): String = UUID_PATTERN.replace(message, REDACTED_TOKEN)
}
