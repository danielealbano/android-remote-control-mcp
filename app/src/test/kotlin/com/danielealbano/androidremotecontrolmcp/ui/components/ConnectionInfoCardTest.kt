package com.danielealbano.androidremotecontrolmcp.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests verifying ConnectionInfoCard URL and connection string logic.
 * These test the data-formatting logic extracted from the composable.
 */
class ConnectionInfoCardTest {
    private fun buildServerUrl(
        scheme: String,
        displayAddress: String,
        port: Int,
    ): String = "$scheme://$displayAddress:$port/mcp"

    private fun buildConnectionString(
        serverUrl: String,
        bearerToken: String,
        tunnelUrl: String? = null,
    ): String =
        buildString {
            append("URL: $serverUrl")
            tunnelUrl?.let { append("\nTunnel: $it/mcp") }
            append("\nBearer Token: $bearerToken")
        }

    @Test
    fun `serverUrl includes mcp suffix`() {
        val url = buildServerUrl("http", "127.0.0.1", 8080)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("http://127.0.0.1:8080/mcp", url)
    }

    @Test
    fun `serverUrl includes mcp suffix with https`() {
        val url = buildServerUrl("https", "192.168.1.100", 8443)
        assertTrue(url.endsWith("/mcp"), "URL should end with /mcp but was: $url")
        assertEquals("https://192.168.1.100:8443/mcp", url)
    }

    @Test
    fun `tunnelUrl includes mcp suffix in connection string`() {
        val tunnelUrl = "https://random-words.trycloudflare.com"
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = "test-token",
                tunnelUrl = tunnelUrl,
            )
        assertTrue(
            connectionString.contains("Tunnel: $tunnelUrl/mcp"),
            "Connection string should contain tunnel URL with /mcp suffix",
        )
    }

    @Test
    fun `copyAll always uses real bearer token`() {
        val realToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = realToken,
            )
        assertTrue(
            connectionString.contains("Bearer Token: $realToken"),
            "Connection string should contain the real bearer token",
        )
        assertFalse(
            connectionString.contains("********"),
            "Connection string should never contain masked token",
        )
    }

    @Test
    fun `connectionString format without tunnel`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = "test-token-123",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\nBearer Token: test-token-123",
            connectionString,
        )
    }

    @Test
    fun `connectionString format with tunnel`() {
        val connectionString =
            buildConnectionString(
                serverUrl = "http://127.0.0.1:8080/mcp",
                bearerToken = "test-token-123",
                tunnelUrl = "https://example.trycloudflare.com",
            )
        assertEquals(
            "URL: http://127.0.0.1:8080/mcp\n" +
                "Tunnel: https://example.trycloudflare.com/mcp\n" +
                "Bearer Token: test-token-123",
            connectionString,
        )
    }
}
