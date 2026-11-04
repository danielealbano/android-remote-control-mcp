package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Logging Integration Tests")
class OAuthLoggingIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    @Test
    @DisplayName("register logs client registered")
    fun registerLogs() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { _ ->
                register(client)
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).any {
                        it.message.contains("OAuth client registered")
                    },
                )
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).none {
                        it.message.contains("rejected redirect URI(s)")
                    },
                )
            }
        }

    @Test
    @DisplayName("rejected redirect uri logs the offending uri")
    fun rejectedRedirectLogs() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { _ ->
                val resp =
                    client.post("/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"redirect_uris":["https://evil.example/cb"],"token_endpoint_auth_method":"none"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_redirect_uri"))
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).any {
                        it.message.contains("rejected redirect URI(s)") &&
                            it.message.contains("https://evil.example/cb")
                    },
                )
            }
        }

    @Test
    @DisplayName("authorize shows and logs no address a client set in its headers")
    fun authorizeIgnoresAddressesInHeaders() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)

                authorize(client, clientId, spoofedAddress = SPOOFED_ADDRESS)

                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                assertNotEquals(SPOOFED_ADDRESS, approval.clientIp)
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).none { it.message.contains(SPOOFED_ADDRESS) },
                )
            }
        }

    @Test
    @DisplayName("authorize logs authorization requested")
    fun authorizeLogs() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                authorize(client, clientId)
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).any {
                        it.message.contains("authorization requested by Claude")
                    },
                )
            }
        }

    @Test
    @DisplayName("authorization_code grant logs tokens issued")
    fun tokensIssuedLogs() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                danceToTokens(ctx, clientId)
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).any {
                        it.message.contains("tokens issued to Claude")
                    },
                )
            }
        }

    @Test
    @DisplayName("refresh grant logs token refreshed")
    fun refreshLogs() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId)
                refreshRequest(client, clientId, tokens.refresh)
                assertTrue(
                    deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).any {
                        it.message.contains("token refreshed for Claude")
                    },
                )
            }
        }

    @Test
    @DisplayName("token values never appear in entries")
    fun noTokenValues() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId)
                refreshRequest(client, clientId, tokens.refresh)
                assertTrue(deps.serverLog.entries.none { it.message.contains("eyJ") })
            }
        }

    // ── helpers (mirror OAuthFlowIntegrationTest) ────────────────────────────

    private data class Tokens(
        val access: String,
        val refresh: String,
    )

    private suspend fun register(client: HttpClient): String {
        val resp =
            client.post("/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"redirect_uris":["$REDIRECT"],"token_endpoint_auth_method":"none",""" +
                        """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                        """"scope":"mcp","client_name":"Claude","application_type":"web"}""",
                )
            }
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["client_id"]!!
            .jsonPrimitive.content
    }

    private suspend fun authorize(
        client: HttpClient,
        clientId: String,
        spoofedAddress: String? = null,
    ): HttpResponse =
        client.get("/authorize") {
            spoofedAddress?.let {
                header("CF-Connecting-IP", it)
                header("X-Forwarded-For", it)
            }
            url {
                parameters.append("response_type", "code")
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", REDIRECT)
                parameters.append("code_challenge", CHALLENGE)
                parameters.append("code_challenge_method", "S256")
                parameters.append("state", "xyz")
                parameters.append("scope", "mcp")
                parameters.append("resource", CANONICAL)
            }
        }

    private suspend fun danceToCode(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        clientId: String,
    ): String {
        authorize(ctx.httpClient, clientId)
        val approval =
            ctx.approvalCoordinator
                .observePending()
                .value
                .single()
        ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
        val status = ctx.httpClient.get("/authorize/status?id=${approval.id}")
        val redirect =
            Json
                .parseToJsonElement(status.bodyAsText())
                .jsonObject["redirect"]!!
                .jsonPrimitive.content
        return Url(redirect).parameters["code"]!!
    }

    private suspend fun danceToTokens(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        clientId: String,
    ): Tokens {
        val code = danceToCode(ctx, clientId)
        val resp =
            ctx.httpClient.post("/token") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("redirect_uri", REDIRECT)
                            append("client_id", clientId)
                            append("code_verifier", VERIFIER)
                            append("resource", CANONICAL)
                        },
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return Tokens(obj["access_token"]!!.jsonPrimitive.content, obj["refresh_token"]!!.jsonPrimitive.content)
    }

    private suspend fun refreshRequest(
        client: HttpClient,
        clientId: String,
        refreshToken: String,
    ): HttpResponse =
        client.post("/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                        append("client_id", clientId)
                    },
                ),
            )
        }

    private companion object {
        const val REDIRECT = "https://claude.ai/api/mcp/auth_callback"
        const val OVERRIDE = "https://test.host"
        const val SPOOFED_ADDRESS = "198.51.100.6"
        const val CANONICAL = "https://test.host/mcp"

        // RFC 7636 Appendix B PKCE test vector.
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}
