package com.danielealbano.androidremotecontrolmcp.mcp

/**
 * Sealed exception hierarchy for MCP tool errors.
 *
 * When thrown from [ToolHandler.execute], the [McpProtocolHandler] catches
 * this exception and produces the appropriate JSON-RPC error response
 * with the given [code] and [message].
 *
 * Each subclass maps to a specific MCP/JSON-RPC error code.
 *
 * @property code The MCP/JSON-RPC error code.
 */
@Suppress("MagicNumber")
sealed class McpToolException(message: String, val code: Int) : Exception(message) {
    /** Invalid parameters (error code -32602). */
    class InvalidParams(message: String) : McpToolException(message, -32602)

    /** Internal server error (error code -32603). */
    class InternalError(message: String) : McpToolException(message, -32603)

    /** Permission not granted (error code -32001). */
    class PermissionDenied(message: String) : McpToolException(message, -32001)

    /** Element not found by ID or criteria (error code -32002). */
    class ElementNotFound(message: String) : McpToolException(message, -32002)

    /** Accessibility action execution failed (error code -32003). */
    class ActionFailed(message: String) : McpToolException(message, -32003)

    /** Operation timed out (error code -32004). */
    class Timeout(message: String) : McpToolException(message, -32004)
}
