package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `valid bearer token on tools-call returns 200`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "get_screen_info")
                            },
                        )
                    }

                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        header(
                            "Authorization",
                            "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}",
                        )
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `invalid bearer token on tools-call returns 401`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "get_screen_info")
                            },
                        )
                    }

                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        header("Authorization", "Bearer wrong-token")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `missing Authorization header on tools-call returns 401`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "get_screen_info")
                            },
                        )
                    }

                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `malformed Authorization header on tools-call returns 401`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val body =
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "get_screen_info")
                            },
                        )
                    }

                val response =
                    client.post(McpIntegrationTestHelper.TOOLS_CALL_PATH) {
                        header("Authorization", "Basic abc123")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(JsonObject.serializer(), body))
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }

    @Test
    fun `health endpoint accessible without bearer token`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response = client.get(McpIntegrationTestHelper.HEALTH_PATH)

                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `health endpoint returns JSON with status healthy`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { _ ->
                val response = client.get(McpIntegrationTestHelper.HEALTH_PATH)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                assertEquals("healthy", body["status"]?.jsonPrimitive?.content)
                assertEquals("running", body["server"]?.jsonPrimitive?.content)
            }
        }
}
