package com.danielealbano.androidremotecontrolmcp.mcp

/**
 * Sealed exception hierarchy for MCP tool errors.
 *
 * When thrown from a tool's `execute` method, the SDK catches
 * this exception and returns it as `CallToolResult(isError = true)`.
 *
 * Each subclass classifies a specific failure mode.
 */
sealed class McpToolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidParams(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class InternalError(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class PermissionDenied(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class ElementNotFound(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    class ActionFailed(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)

    /**
     * Thrown when a tool operation exceeds its time limit.
     *
     * Note: The SDK wraps all tool exceptions as `CallToolResult(isError = true)` with the
     * message in `TextContent`, so the specific subclass is used for logging granularity
     * and internal classification, not wire-level error codes.
     */
    class Timeout(
        message: String,
        cause: Throwable? = null,
    ) : McpToolException(message, cause)
}
