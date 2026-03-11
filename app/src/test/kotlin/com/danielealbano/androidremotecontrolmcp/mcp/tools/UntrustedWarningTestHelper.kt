package com.danielealbano.androidremotecontrolmcp.mcp.tools

/**
 * Strips the [McpToolUtils.UNTRUSTED_CONTENT_WARNING] prefix from tool response text.
 * Use in tests that need to assert on the actual content after the warning line.
 */
fun stripUntrustedWarning(text: String): String = text.removePrefix(McpToolUtils.UNTRUSTED_CONTENT_WARNING + "\n")
