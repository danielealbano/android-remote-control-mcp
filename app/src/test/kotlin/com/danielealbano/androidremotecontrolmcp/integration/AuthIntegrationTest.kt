package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Auth Integration Tests")
class AuthIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `valid bearer token allows SDK client to connect`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                // If we get here, the SDK client connected successfully
                // (initialize handshake completed with valid token)
                val tools = client.listTools()
                assertEquals(EXPECTED_TOOL_COUNT, tools.tools.size)
            }
        }

    @Test
    fun `invalid bearer token on POST mcp returns 401`() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                    }

                val response =
                    client.post("/mcp") {
                        header("Authorization", "Bearer wrong-token")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `missing Authorization header on POST mcp returns 401`() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                    }

                val response =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `malformed Authorization header on POST mcp returns 401`() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "initialize")
                    }

                val response =
                    client.post("/mcp") {
                        header("Authorization", "Basic abc123")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    companion object {
        private const val EXPECTED_TOOL_COUNT = 29
    }
}
