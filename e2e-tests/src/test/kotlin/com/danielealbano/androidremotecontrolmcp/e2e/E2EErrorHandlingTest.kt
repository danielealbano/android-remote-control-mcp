package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

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
    fun `missing bearer token returns 401 Unauthorized`() = runBlocking {
        val noAuthClient = McpClient(baseUrl, "")
        try {
            noAuthClient.connect()
            fail("Expected exception for missing bearer token")
        } catch (_: Exception) {
            // Expected: server rejects unauthenticated requests
        } finally {
            noAuthClient.close()
        }
    }

    @Test
    fun `invalid bearer token returns 401 Unauthorized`() = runBlocking {
        val badAuthClient = McpClient(baseUrl, "invalid-token-that-does-not-match")
        try {
            badAuthClient.connect()
            fail("Expected exception for invalid bearer token")
        } catch (_: Exception) {
            // Expected: server rejects unauthenticated requests
        } finally {
            badAuthClient.close()
        }
    }

    @Test
    fun `correct bearer token returns successful response`() = runBlocking {
        val result = mcpClient.callTool("press_home")
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
        val result = mcpClient.callTool("tap", emptyMap())
        assertEquals(true, result.isError)
    }

    @Test
    fun `click on non-existent element returns error result`() = runBlocking {
        val result = mcpClient.callTool(
            "click_element",
            mapOf("element_id" to "nonexistent_element_id_12345"),
        )
        assertEquals(true, result.isError)
        val text = (result.content[0] as TextContent).text
        assertTrue(text.contains("nonexistent_element_id_12345"))
    }
}
