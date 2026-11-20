package com.danielealbano.androidremotecontrolmcp.integration

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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Flow Integration Tests")
class OAuthFlowIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    // ── discovery / metadata ────────────────────────────────────────────────

    @Test
    @DisplayName("unauthenticated /mcp returns 401 with WWW-Authenticate when oauth on")
    fun unauthenticatedReturns401WithHeader() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
                val header = resp.headers["WWW-Authenticate"]
                assertNotNull(header)
                assertTrue(header!!.contains("/.well-known/oauth-protected-resource/mcp"))
            }
        }

    @Test
    @DisplayName("401 has no WWW-Authenticate when only bearer enabled")
    fun bearerOnly401NoHeader() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
                assertNull(resp.headers["WWW-Authenticate"])
            }
        }

    @Test
    @DisplayName("serves PRM at path-suffixed and bare paths")
    fun servesPrmBothPaths() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val suffixed = client.get("/.well-known/oauth-protected-resource/mcp")
                val bare = client.get("/.well-known/oauth-protected-resource")
                assertEquals(HttpStatusCode.OK, suffixed.status)
                assertEquals(HttpStatusCode.OK, bare.status)
                val resource =
                    Json
                        .parseToJsonElement(suffixed.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertTrue(resource.endsWith("/mcp"))
            }
        }

    @Test
    @DisplayName("serves AS metadata at bare, openid-configuration, and path-suffixed")
    fun servesAsMetadata() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                for (
                path in
                listOf(
                    "/.well-known/oauth-authorization-server",
                    "/.well-known/openid-configuration",
                    "/.well-known/oauth-authorization-server/mcp",
                )
                ) {
                    val resp = client.get(path)
                    assertEquals(HttpStatusCode.OK, resp.status, "expected 200 for $path")
                    val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    assertEquals(
                        listOf("S256"),
                        obj["code_challenge_methods_supported"]!!.jsonObjectArray(),
                    )
                }
            }
        }

    @Test
    @DisplayName("metadata reflects X-Forwarded-Host and -Proto")
    fun metadataReflectsForwarded() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = "") { _ ->
                val resp =
                    client.get("/.well-known/oauth-protected-resource/mcp") {
                        header("X-Forwarded-Host", "tunnel.example")
                        header("X-Forwarded-Proto", "https")
                    }
                val resource =
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertEquals("https://tunnel.example/mcp", resource)
            }
        }

    @Test
    @DisplayName("metadata honors publicUrlOverride")
    fun metadataHonorsOverride() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = "https://pinned.host") { _ ->
                val resp =
                    client.get("/.well-known/oauth-protected-resource/mcp") {
                        header("X-Forwarded-Host", "tunnel.example")
                        header("X-Forwarded-Proto", "https")
                    }
                val resource =
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["resource"]!!
                        .jsonPrimitive.content
                assertEquals("https://pinned.host/mcp", resource)
            }
        }

    // ── full dance + token grants ───────────────────────────────────────────

    @Test
    @DisplayName("full DCR to authorize to approve to token to /mcp succeeds")
    fun fullDanceSucceeds() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val access = tokens.access

                val mcpClient = connectMcp(access)
                try {
                    val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
                    assertNotEquals(true, result.isError)
                } finally {
                    mcpClient.close()
                }
            }
        }

    @Test
    @DisplayName("register rejects disallowed redirect_uri")
    fun registerRejectsDisallowedRedirect() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp =
                    client.post("/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"redirect_uris":["https://evil.example/cb"],"token_endpoint_auth_method":"none"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_redirect_uri"))
            }
        }

    @Test
    @DisplayName("register accepts ChatGPT connector redirect_uri")
    fun registerAcceptsChatGptRedirect() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val resp =
                    client.post("/register") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"redirect_uris":["https://chatgpt.com/connector/oauth/abc123"],""" +
                                """"token_endpoint_auth_method":"none"}""",
                        )
                    }
                assertEquals(HttpStatusCode.Created, resp.status)
                assertTrue(resp.bodyAsText().contains("https://chatgpt.com/connector/oauth/abc123"))
            }
        }

    @Test
    @DisplayName("Gemini connector: six callbacks registered, approval on the production /r/ callback, tool call")
    fun geminiConnectorFlowSucceeds() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val registerResp = registerResponse(client, GEMINI_CALLBACKS, clientName = "Google")
                assertEquals(HttpStatusCode.Created, registerResp.status)
                val registered = Json.parseToJsonElement(registerResp.bodyAsText()).jsonObject
                assertEquals(GEMINI_CALLBACKS, registered["redirect_uris"]!!.jsonObjectArray())
                val clientId = registered["client_id"]!!.jsonPrimitive.content

                authorize(client, clientId, AuthorizeOptions(redirectUri = GEMINI_REDIRECT))
                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                assertEquals("Google", approval.clientName)
                assertEquals("oauth-redirect.googleusercontent.com", approval.redirectHost)
                ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
                val redirect =
                    Json
                        .parseToJsonElement(client.get("/authorize/status?id=${approval.id}").bodyAsText())
                        .jsonObject["redirect"]!!
                        .jsonPrimitive.content
                assertTrue(redirect.startsWith("$GEMINI_REDIRECT?"), redirect)
                assertEquals("xyz", Url(redirect).parameters["state"])

                val tokenResp =
                    tokenRequest(
                        client,
                        clientId,
                        Url(redirect).parameters["code"]!!,
                        verifier = VERIFIER,
                        includeResource = true,
                        redirectUri = GEMINI_REDIRECT,
                    )
                assertEquals(HttpStatusCode.OK, tokenResp.status)
                val access =
                    Json
                        .parseToJsonElement(tokenResp.bodyAsText())
                        .jsonObject["access_token"]!!
                        .jsonPrimitive.content
                val mcpClient = connectMcp(access)
                try {
                    val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
                    assertNotEquals(true, result.isError)
                } finally {
                    mcpClient.close()
                }
            }
        }

    @Test
    @DisplayName("register rejects a Gemini batch that also carries a Google project relay callback")
    fun registerRejectsGeminiBatchWithProjectRelay() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { ctx ->
                val projectRelay = "https://oauth-redirect.googleusercontent.com/r/my-gcp-project"
                val resp = registerResponse(client, GEMINI_CALLBACKS + projectRelay, clientName = "Google")
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_redirect_uri"))
                assertTrue(ctx.clientRepository.getClients().isEmpty())
            }
        }

    @Test
    @DisplayName("authorize rejects non-S256 challenge method")
    fun authorizeRejectsNonS256() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                val noRedirect = createNoRedirectClient()
                val resp = authorize(noRedirect, clientId, AuthorizeOptions(challengeMethod = "plain"))
                assertEquals(HttpStatusCode.Found, resp.status)
                assertTrue(resp.headers["Location"]!!.contains("error="))
            }
        }

    @Test
    @DisplayName("token rejects wrong PKCE verifier")
    fun tokenRejectsWrongVerifier() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp = tokenRequest(client, clientId, code, verifier = "wrong-verifier", includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("token rejects client_id mismatch against code")
    fun tokenRejectsClientMismatch() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp = tokenRequest(client, "arc-other", code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("token rejects missing client_id")
    fun tokenRejectsMissingClientId() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp =
                    client.post("/token") {
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", REDIRECT)
                                    append("code_verifier", VERIFIER)
                                },
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_request"))
            }
        }

    @Test
    @DisplayName("replayed auth code rejected")
    fun replayedCodeRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val first = tokenRequest(client, clientId, code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.OK, first.status)
                val second = tokenRequest(client, clientId, code, verifier = VERIFIER, includeResource = true)
                assertEquals(HttpStatusCode.BadRequest, second.status)
                assertTrue(second.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("refresh_token rotates and old jti rejected")
    fun refreshRotates() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                val refreshed = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.OK, refreshed.status)
                val reused = refreshRequest(client, clientId, tokens.refresh)
                assertEquals(HttpStatusCode.BadRequest, reused.status)
                assertTrue(reused.bodyAsText().contains("invalid_grant"))
                // The client is NOT revoked: it still exists in the registry.
                assertNotNull(ctx.clientRepository.getClient(clientId))
            }
        }

    @Test
    @DisplayName("revoked client token rejected on /mcp")
    fun revokedClientRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = true)
                ctx.clientRepository.revoke(clientId)
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer ${tokens.access}")
                        mcpInitializeBody()
                    }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("wrong-aud access token rejected on /mcp")
    fun wrongAudRejected() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val registered = ctx.clientRepository.register("Claude", listOf(REDIRECT), "web", null, 0L)
                val token = ctx.tokenService.issueAccessToken(registered.clientId, "https://other.host/mcp")
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer $token")
                        mcpInitializeBody()
                    }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("static bearer token still accepted (dual-accept)")
    fun staticBearerAccepted() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(
                bearerTokenEnabled = true,
                bearerToken = "static-tok",
                publicUrlOverride = OVERRIDE,
            ) { _ ->
                val resp =
                    client.post("/mcp") {
                        header("Authorization", "Bearer static-tok")
                        mcpInitializeBody()
                    }
                assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("open when both disabled")
    fun openWhenBothDisabled() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication(bearerTokenEnabled = false) { _ ->
                val resp = client.post("/mcp") { mcpInitializeBody() }
                assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
            }
        }

    @Test
    @DisplayName("authorize rejects present-but-mismatched resource")
    fun authorizeRejectsMismatchedResource() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                val clientId = register(client)
                val noRedirect = createNoRedirectClient()
                val resp =
                    authorize(noRedirect, clientId, AuthorizeOptions(resourceOverride = "https://wrong.host/mcp"))
                assertEquals(HttpStatusCode.Found, resp.status)
                assertTrue(resp.headers["Location"]!!.contains("error="))
            }
        }

    @Test
    @DisplayName("authorize omits resource defaults to canonical")
    fun authorizeOmitsResource() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val tokens = danceToTokens(ctx, clientId, includeResource = false)
                val mcpClient = connectMcp(tokens.access)
                try {
                    val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
                    assertNotEquals(true, result.isError)
                } finally {
                    mcpClient.close()
                }
            }
        }

    @Test
    @DisplayName("token rejects present-but-mismatched resource")
    fun tokenRejectsMismatchedResource() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val code = danceToCode(ctx, clientId, includeResource = true)
                val resp =
                    client.post("/token") {
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", REDIRECT)
                                    append("client_id", clientId)
                                    append("code_verifier", VERIFIER)
                                    append("resource", "https://wrong.host/mcp")
                                },
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_grant"))
            }
        }

    @Test
    @DisplayName("authorize with disallowed redirect_uri returns 400 HTML no Location")
    fun authorizeDisallowedRedirect400() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                // Register with a localhost redirect, then request authorize for the Claude callback (not registered).
                val clientId = register(client, redirectUri = "http://localhost/cb")
                val noRedirect = createNoRedirectClient()
                val resp = authorize(noRedirect, clientId, AuthorizeOptions(redirectUri = REDIRECT))
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertNull(resp.headers["Location"])
                assertTrue(resp.headers["Content-Type"]?.contains("html") == true)
            }
        }

    @Test
    @DisplayName("last-used updated once then throttled across two /mcp calls")
    fun lastUsedDebounced() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                // Mint a token directly (no /token grant) so the only touches come from the /mcp path.
                val registered = ctx.clientRepository.register("Claude", listOf(REDIRECT), "web", null, 0L)
                val token = ctx.tokenService.issueAccessToken(registered.clientId, CANONICAL)
                repeat(2) {
                    client.post("/mcp") {
                        header("Authorization", "Bearer $token")
                        mcpInitializeBody()
                    }
                }
                coVerify(exactly = 1) { ctx.clientRepository.touchLastUsed(registered.clientId, any()) }
            }
        }

    @Test
    @DisplayName("authorize redirect percent-encodes state")
    fun authorizeEncodesState() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                val clientId = register(client)
                val rawState = "a&b=c d"
                authorize(client, clientId, AuthorizeOptions(state = rawState))
                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
                val status = client.get("/authorize/status?id=${approval.id}")
                val redirect =
                    Json
                        .parseToJsonElement(status.bodyAsText())
                        .jsonObject["redirect"]!!
                        .jsonPrimitive.content
                assertEquals(rawState, Url(redirect).parameters["state"])
            }
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private data class Tokens(
        val access: String,
        val refresh: String,
    )

    private suspend fun register(
        client: HttpClient,
        redirectUri: String = REDIRECT,
    ): String =
        Json
            .parseToJsonElement(registerResponse(client, listOf(redirectUri), clientName = "Claude").bodyAsText())
            .jsonObject["client_id"]!!
            .jsonPrimitive.content

    private suspend fun registerResponse(
        client: HttpClient,
        redirectUris: List<String>,
        clientName: String,
    ): HttpResponse =
        client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"redirect_uris":[${redirectUris.joinToString(",") { "\"$it\"" }}],""" +
                    """"token_endpoint_auth_method":"none",""" +
                    """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                    """"scope":"mcp","client_name":"$clientName","application_type":"web"}""",
            )
        }

    private data class AuthorizeOptions(
        val redirectUri: String = REDIRECT,
        val challengeMethod: String = "S256",
        val resourceOverride: String? = null,
        val includeResource: Boolean = true,
        val state: String = "xyz",
    )

    private suspend fun authorize(
        client: HttpClient,
        clientId: String,
        options: AuthorizeOptions = AuthorizeOptions(),
    ): HttpResponse =
        client.get("/authorize") {
            url {
                parameters.append("response_type", "code")
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", options.redirectUri)
                parameters.append("code_challenge", CHALLENGE)
                parameters.append("code_challenge_method", options.challengeMethod)
                parameters.append("state", options.state)
                parameters.append("scope", "mcp")
                if (options.resourceOverride != null) {
                    parameters.append("resource", options.resourceOverride)
                } else if (options.includeResource) {
                    parameters.append("resource", CANONICAL)
                }
            }
        }

    private suspend fun danceToCode(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        clientId: String,
        includeResource: Boolean,
    ): String {
        authorize(ctx.httpClient, clientId, AuthorizeOptions(includeResource = includeResource))
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
        includeResource: Boolean,
    ): Tokens {
        val code = danceToCode(ctx, clientId, includeResource)
        val resp = tokenRequest(ctx.httpClient, clientId, code, verifier = VERIFIER, includeResource = includeResource)
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return Tokens(obj["access_token"]!!.jsonPrimitive.content, obj["refresh_token"]!!.jsonPrimitive.content)
    }

    private suspend fun tokenRequest(
        client: HttpClient,
        clientId: String,
        code: String,
        verifier: String,
        includeResource: Boolean,
        redirectUri: String = REDIRECT,
    ): HttpResponse =
        client.post("/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("client_id", clientId)
                        append("code_verifier", verifier)
                        if (includeResource) append("resource", CANONICAL)
                    },
                ),
            )
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

    private suspend fun ApplicationTestBuilder.connectMcp(accessToken: String): Client {
        val httpClient =
            createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(io.modelcontextprotocol.kotlin.sdk.types.McpJson)
                }
                install(io.ktor.client.plugins.sse.SSE)
            }
        val transport =
            StreamableHttpClientTransport(
                client = httpClient,
                url = "/mcp",
                requestBuilder = { header("Authorization", "Bearer $accessToken") },
            )
        val mcpClient = Client(clientInfo = Implementation(name = "oauth-test-client", version = "1.0.0"))
        mcpClient.connect(transport)
        return mcpClient
    }

    private fun ApplicationTestBuilder.createNoRedirectClient(): HttpClient =
        createClient {
            followRedirects = false
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(io.modelcontextprotocol.kotlin.sdk.types.McpJson)
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.mcpInitializeBody() {
        header("Accept", "application/json")
        contentType(ContentType.Application.Json)
        setBody(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectArray(): List<String> =
        (this as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }

    private companion object {
        const val REDIRECT = "https://claude.ai/api/mcp/auth_callback"
        const val OVERRIDE = "https://test.host"
        const val CANONICAL = "https://test.host/mcp"

        // The six redirect URIs Gemini registered on a real device, in the order it sent them; it authorizes on
        // the production /r/ one.
        const val GEMINI_ID = "user_bound_custom-mcp-8569b7990f18a8201022f919"
        const val GEMINI_REDIRECT = "https://oauth-redirect.googleusercontent.com/r/$GEMINI_ID"
        val GEMINI_CALLBACKS =
            listOf(
                "https://oauth-redirect-sandbox.googleusercontent.com/r/$GEMINI_ID",
                "https://oauth-redirect-test.googleusercontent.com/r/$GEMINI_ID",
                GEMINI_REDIRECT,
                "https://oauth-redirect-sandbox.googleusercontent.com/a/$GEMINI_ID",
                "https://oauth-redirect-test.googleusercontent.com/a/$GEMINI_ID",
                "https://oauth-redirect.googleusercontent.com/a/$GEMINI_ID",
            )

        // RFC 7636 Appendix B PKCE test vector.
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}
