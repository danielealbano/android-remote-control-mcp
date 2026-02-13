package com.danielealbano.androidremotecontrolmcp.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Test utility MCP client for E2E tests.
 *
 * Uses the MCP SDK [Client] with [StreamableHttpClientTransport] over Ktor CIO.
 * Trusts all certificates (self-signed) — this is acceptable ONLY for testing.
 */
class McpClient(
    private val baseUrl: String,
    private val bearerToken: String,
) {
    private val httpClient = HttpClient(CIO) {
        install(SSE)
        engine {
            https {
                trustManager = TrustAllX509TrustManager()
            }
        }
    }
    private var client: Client? = null

    /**
     * Connect to the MCP server and complete the initialize handshake.
     */
    suspend fun connect() {
        val transport = StreamableHttpClientTransport(
            client = httpClient,
            url = "$baseUrl/mcp",
            requestBuilder = {
                headers.append("Authorization", "Bearer $bearerToken")
            },
        )
        val c = Client(
            clientInfo = Implementation(name = "e2e-test-client", version = "1.0.0"),
        )
        c.connect(transport)
        client = c
    }

    /**
     * List available MCP tools.
     */
    suspend fun listTools(): ListToolsResult = client!!.listTools()

    /**
     * Call an MCP tool by name with optional arguments.
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): CallToolResult = client!!.callTool(name, arguments)

    /**
     * Close the MCP client and HTTP client.
     */
    suspend fun close() {
        client?.close()
        httpClient.close()
    }
}

/**
 * Trust-all [X509TrustManager] for testing with self-signed HTTPS certificates.
 * MUST NOT be used in production.
 */
class TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
