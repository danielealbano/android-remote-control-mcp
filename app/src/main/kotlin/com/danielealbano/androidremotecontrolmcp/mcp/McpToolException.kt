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
) : Exception(message) {
    class InvalidParams(
        message: String,
    ) : McpToolException(message)

    class InternalError(
        message: String,
    ) : McpToolException(message)

    class PermissionDenied(
        message: String,
    ) : McpToolException(message)

    class ElementNotFound(
        message: String,
    ) : McpToolException(message)

    class ActionFailed(
        message: String,
    ) : McpToolException(message)

    class Timeout(
        message: String,
    ) : McpToolException(message)
}
