package com.danielealbano.androidremotecontrolmcp.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * E2E test: Error handling and authentication verification.
 *
 * Verifies that the MCP server correctly handles:
 * - Authentication errors (missing token, empty header, invalid/wrong token)
 * - Correct token (should succeed)
 * - Unknown tool names
 * - Invalid tool parameters
 * - Element not found errors
 *
 * Uses [SharedAndroidContainer] singleton to share the Docker container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EErrorHandlingTest {

    private val mcpClient = SharedAndroidContainer.mcpClient
    private val baseUrl = SharedAndroidContainer.mcpServerUrl

    @Test
    fun `missing bearer token returns 401 Unauthorized`() {
        // Create client with empty token — sends "Authorization: Bearer " with empty value
        val noAuthClient = McpClient(baseUrl, "")

        try {
            noAuthClient.callTool("press_home")
            fail("Expected McpClientException with status 401")
        } catch (e: McpClientException) {
            assertEquals(
                401,
                e.statusCode,
                "Missing/empty bearer token should return 401, got: ${e.statusCode}"
            )
        }
    }

    @Test
    fun `invalid bearer token returns 401 Unauthorized`() {
        val badAuthClient = McpClient(baseUrl, "invalid-token-that-does-not-match")

        try {
            badAuthClient.callTool("press_home")
            fail("Expected McpClientException with status 401")
        } catch (e: McpClientException) {
            assertEquals(
                401,
                e.statusCode,
                "Invalid bearer token should return 401, got: ${e.statusCode}"
            )
        }
    }

    @Test
    fun `correct bearer token returns successful response`() {
        // The shared mcpClient already has the correct token; verify a simple tool call succeeds
        val result = mcpClient.callTool("press_home")
        // If we get here without exception, the auth succeeded
        assertTrue(true, "Correct bearer token should allow tool call to succeed")
    }

    @Test
    fun `unknown tool name returns JSON-RPC error -32601`() {
        try {
            mcpClient.callTool("nonexistent_tool_name")
            fail("Expected McpRpcException with code -32601")
        } catch (e: McpRpcException) {
            assertEquals(
                -32601,
                e.code,
                "Unknown tool should return error code -32601, got: ${e.code}"
            )
        }
    }

    @Test
    fun `invalid params returns JSON-RPC error -32602`() {
        // Call tap without required x,y coordinates
        try {
            mcpClient.callTool("tap", emptyMap())
            fail("Expected McpRpcException with code -32602")
        } catch (e: McpRpcException) {
            assertEquals(
                -32602,
                e.code,
                "Missing required params should return error code -32602, got: ${e.code}"
            )
        }
    }

    @Test
    fun `click on non-existent element returns JSON-RPC error -32002`() {
        try {
            mcpClient.callTool(
                "click_element",
                mapOf("element_id" to "nonexistent_element_id_12345")
            )
            fail("Expected McpRpcException with code -32002")
        } catch (e: McpRpcException) {
            assertEquals(
                -32002,
                e.code,
                "Non-existent element should return error code -32002, got: ${e.code}"
            )
        }
    }

    @Test
    fun `health endpoint is accessible without authentication`() {
        // Health check should work even with a bad token client
        val noAuthClient = McpClient(baseUrl, "wrong-token")
        val health = noAuthClient.healthCheck()

        val status = health["status"]?.toString()?.removeSurrounding("\"")
        assertTrue(
            status == "healthy",
            "Health endpoint should be accessible without auth and return 'healthy', got: $status"
        )
    }
}
