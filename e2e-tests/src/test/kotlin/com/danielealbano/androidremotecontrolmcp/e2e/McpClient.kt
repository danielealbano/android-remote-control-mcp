package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Exception thrown when the MCP server returns an HTTP error (4xx, 5xx).
 */
class McpClientException(
    val statusCode: Int,
    val responseBody: String,
) : RuntimeException("MCP server returned HTTP $statusCode: $responseBody")

/**
 * Exception thrown when the MCP server returns a JSON-RPC error response.
 */
class McpRpcException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null,
) : RuntimeException("MCP RPC error $code: $message")

/**
 * Test utility MCP client for E2E tests.
 *
 * Uses OkHttp to make JSON-RPC 2.0 requests to the MCP server over HTTP (or HTTPS when enabled).
 * Trusts all certificates (self-signed) — this is acceptable ONLY for testing.
 */
class McpClient(
    private val baseUrl: String,
    private val bearerToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicLong(1)
    private val client: OkHttpClient

    init {
        // Create trust-all TrustManager for self-signed certificates (TEST ONLY)
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())

        client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Check the /health endpoint (unauthenticated).
     * Returns the parsed JSON response body.
     */
    fun healthCheck(): JsonObject {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()

        return executeRequest(request).jsonObject
    }

    /**
     * Initialize MCP session.
     * Returns the JSON-RPC result object.
     */
    fun initialize(): JsonObject {
        val body = buildJsonRpcRequest("initialize")
        return sendJsonRpc("$baseUrl/mcp/v1/initialize", body)
    }

    /**
     * List available MCP tools.
     * Returns the tools JsonArray from the result.
     */
    fun listTools(): JsonArray {
        val body = buildJsonRpcRequest("tools/list")
        val result = sendJsonRpc("$baseUrl/mcp/v1/tools/list", body)
        return result["tools"]?.jsonArray
            ?: throw McpRpcException(-1, "Response missing 'tools' field")
    }

    /**
     * Call an MCP tool by name with optional arguments.
     * Returns the result content from the JSON-RPC response.
     */
    fun callTool(name: String, arguments: Map<String, Any> = emptyMap()): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", name)
                putJsonObject("arguments") {
                    arguments.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Int -> put(key, value)
                            is Long -> put(key, value)
                            is Double -> put(key, value)
                            is Float -> put(key, value.toDouble())
                            is Boolean -> put(key, value)
                            is JsonElement -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                }
            }
        }

        return sendJsonRpc("$baseUrl/mcp/v1/tools/call", body)
    }

    /**
     * Build a JSON-RPC 2.0 request envelope with no params.
     */
    private fun buildJsonRpcRequest(method: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", method)
        }
    }

    /**
     * Send a JSON-RPC request to the given URL and parse the result.
     * Throws [McpRpcException] if the response contains a JSON-RPC error.
     */
    private fun sendJsonRpc(url: String, body: JsonObject): JsonObject {
        val mediaType = "application/json".toMediaType()
        val requestBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $bearerToken")
            .build()

        val response = executeRequest(request)
        val responseObj = response.jsonObject

        // Check for JSON-RPC error (guard against JsonNull)
        val error = responseObj["error"]
        if (error != null && error !is JsonNull) {
            val errorObj = error.jsonObject
            val code = errorObj["code"]?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            } ?: 0
            val message = errorObj["message"]?.let {
                (it as? JsonPrimitive)?.content ?: "Unknown error"
            } ?: "Unknown error"
            val data = errorObj["data"]
            throw McpRpcException(code, message, data)
        }

        // Return the result field (JsonNull is a valid JsonElement, not Kotlin null)
        val result = responseObj["result"]
        if (result == null || result is JsonNull) {
            return buildJsonObject { }
        }
        return result.jsonObject
    }

    /**
     * Execute an HTTP request and return parsed JSON.
     * Throws [McpClientException] for non-2xx responses.
     */
    private fun executeRequest(request: Request): JsonElement {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                System.err.println(
                    "[McpClient] HTTP ${response.code} ${response.message} " +
                        "for ${request.method} ${request.url} — body: $body"
                )
                throw McpClientException(response.code, body)
            }

            return json.parseToJsonElement(body)
        }
    }
}
