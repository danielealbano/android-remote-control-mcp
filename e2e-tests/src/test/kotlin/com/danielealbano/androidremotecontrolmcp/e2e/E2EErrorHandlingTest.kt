package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection
import java.net.URI

/**
 * E2E test: Error handling and authentication verification.
 *
 * Verifies that the MCP server correctly handles:
 * - Authentication errors (missing token, invalid/wrong token)
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
        // Use raw HTTP to assert the exact 401 status code, avoiding false positives
        // from unrelated exceptions (network errors, TLS failures, etc.)
        val conn = URI("$baseUrl/mcp").toURL()
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Content-Type", "application/json")
            // Intentionally omit Authorization header
            conn.doOutput = true
            conn.outputStream.use {
                it.write("""{"jsonrpc":"2.0","method":"ping","id":1}""".toByteArray())
            }
            assertEquals(401, conn.responseCode, "Missing token should return HTTP 401")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `invalid bearer token returns 401 Unauthorized`() {
        // Use raw HTTP to assert the exact 401 status code, avoiding false positives
        // from unrelated exceptions (network errors, TLS failures, etc.)
        val conn = URI("$baseUrl/mcp").toURL()
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer invalid-token-that-does-not-match")
            conn.doOutput = true
            conn.outputStream.use {
                it.write("""{"jsonrpc":"2.0","method":"ping","id":1}""".toByteArray())
            }
            assertEquals(401, conn.responseCode, "Invalid token should return HTTP 401")
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `correct bearer token returns successful response`() = runBlocking {
        val result = mcpClient.callTool("android_press_home")
        assertNotEquals(true, result.isError)
    }

    @Test
    fun `unknown tool name returns error result`() = runBlocking {
        val result = mcpClient.callTool("nonexistent_tool_name")
        assertEquals(true, result.isError)
        val text = (result.content[0] as TextContent).text
        assertTrue(text.contains("not found", ignoreCase = true))
    }

    @Test
    fun `invalid params returns error result`() = runBlocking {
        // Call tap without required x,y coordinates
        val result = mcpClient.callTool("android_tap", emptyMap())
        assertEquals(true, result.isError)
    }

    @Test
    fun `click on non-existent element returns error result`() = runBlocking {
        val result = mcpClient.callTool(
            "android_click_element",
            mapOf("element_id" to "nonexistent_element_id_12345"),
        )
        assertEquals(true, result.isError)
        val text = (result.content[0] as TextContent).text
        assertTrue(text.contains("nonexistent_element_id_12345"))
    }
}
