package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.AuthorizeOptions
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.CANONICAL
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.OVERRIDE
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.VERIFIER
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.authorize
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.tokenRequest
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.FetchedBody
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Client ID Metadata Document (CIMD) Integration Tests")
class OAuthMetadataDocumentIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    private class Tokens(
        val access: String,
        val refresh: String,
    )

    @Test
    @DisplayName("Claude's hosted flow: document, approval named with its host, embedded logo, tokens, /mcp, refresh")
    fun hostedFlow() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc(), LOGO to png()))
            withServer(fetcher, deps) { ctx ->
                val page = authorize(client, CLAUDE_DOC_URL)
                assertEquals(HttpStatusCode.OK, page.status)
                val html = page.bodyAsText()
                assertTrue(html.contains("Claude (claude.ai)"), html)
                assertTrue(html.contains("data:image/png;base64,"), html)
                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                assertEquals("Claude (claude.ai)", approval.clientName)
                assertEquals("claude.ai", approval.redirectHost)
                assertEquals(LOGO, approval.iconUrl)
                assertFalse(approval.loopbackRedirect)
                assertFalse(html.contains(LOOPBACK_WARNING_START), html)
                ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
                val redirect = statusRedirect(client, approval.id)
                assertTrue(redirect.startsWith("${OAuthPolicy.CLAUDE_REDIRECT_URI}?"), redirect)
                assertEquals("xyz", Url(redirect).parameters["state"])
                assertEquals(OVERRIDE, Url(redirect).parameters["iss"])

                val code = Url(redirect).parameters["code"]!!
                val tokens = redeem(client, CLAUDE_DOC_URL, code, OAuthPolicy.CLAUDE_REDIRECT_URI)
                val row = ctx.clientRepository.getClients().single()
                assertEquals(CLAUDE_DOC_URL, row.clientId)
                assertEquals("Claude", row.clientName)
                assertEquals(1, row.connections.size)
                // The clients list shows what the approval showed.
                assertEquals(LOGO, row.iconUrl)
                assertNotEquals(HttpStatusCode.Unauthorized, mcpStatus(client, tokens.access))

                val refreshed = refreshRequest(client, CLAUDE_DOC_URL, tokens.refresh)
                assertEquals(HttpStatusCode.OK, refreshed.status)
                assertEquals(HttpStatusCode.BadRequest, refreshRequest(client, CLAUDE_DOC_URL, tokens.refresh).status)
                val messages = deps.serverLog.ofType(ServerLogEntry.Type.OAUTH).map { it.message }
                assertTrue(messages.contains("OAuth tokens issued to Claude (claude.ai)"), messages.toString())
                assertTrue(messages.contains("OAuth token refreshed for Claude (claude.ai)"), messages.toString())
            }
        }

    @Test
    @DisplayName("two logins of the same app are two connections of one row that do not break each other")
    fun twoConnections() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                val first = login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                val second = login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                assertEquals(
                    2,
                    ctx.clientRepository
                        .getClients()
                        .single()
                        .connections.size,
                )
                assertEquals(HttpStatusCode.OK, refreshRequest(client, CLAUDE_DOC_URL, first.refresh).status)
                assertEquals(HttpStatusCode.OK, refreshRequest(client, CLAUDE_DOC_URL, second.refresh).status)
                assertNotEquals(HttpStatusCode.Unauthorized, mcpStatus(client, first.access))
                assertNotEquals(HttpStatusCode.Unauthorized, mcpStatus(client, second.access))
                // No cache: every login reads the document again.
                assertEquals(2, fetcher.requested.count { it == CLAUDE_DOC_URL })
            }
        }

    @Test
    @DisplayName("a refresh token of the other client kind never rotates, and the stored jti stays")
    fun refreshNeedsTheRightKind() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                val connection =
                    ctx.clientRepository
                        .getClient(CLAUDE_DOC_URL)!!
                        .connections
                        .single()
                // Signed by the server itself, but without the connection the CIMD tokens always carry.
                val noConnection = ctx.tokenService.issueRefreshToken(CLAUDE_DOC_URL, connection.refreshJti, CANONICAL)
                assertEquals(HttpStatusCode.BadRequest, refreshRequest(client, CLAUDE_DOC_URL, noConnection).status)

                val dcr = registerWithLogo(client, "https://claude.ai/logo.png")
                ctx.clientRepository.recordGrant(dcr, "dcr-jti", null, System.currentTimeMillis())
                val withConnection = ctx.tokenService.issueRefreshToken(dcr, "dcr-jti", CANONICAL, connection.id)
                assertEquals(HttpStatusCode.BadRequest, refreshRequest(client, dcr, withConnection).status)

                assertEquals(
                    connection,
                    ctx.clientRepository
                        .getClient(CLAUDE_DOC_URL)!!
                        .connections
                        .single(),
                )
                assertEquals("dcr-jti", ctx.clientRepository.getClient(dcr)?.currentRefreshJti)
            }
        }

    @Test
    @DisplayName("a bad parameter on a valid CIMD request redirects the error to the document's callback, with iss")
    fun badParameterRedirectsWithIss() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                val noRedirect = createClient { followRedirects = false }
                val resp = authorize(noRedirect, CLAUDE_DOC_URL, AuthorizeOptions(challengeMethod = "plain"))
                assertEquals(HttpStatusCode.Found, resp.status)
                val location = Url(resp.headers["Location"]!!)
                assertTrue(location.toString().startsWith("${OAuthPolicy.CLAUDE_REDIRECT_URI}?"), location.toString())
                assertEquals("invalid_request", location.parameters["error"])
                assertEquals("xyz", location.parameters["state"])
                assertEquals(OVERRIDE, location.parameters["iss"])
                assertTrue(
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .isEmpty(),
                )
            }
        }

    @Test
    @DisplayName("revoking the app ends every connection at once")
    fun revokeEndsAllConnections() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                val first = login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                val second = login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                ctx.clientRepository.revoke(CLAUDE_DOC_URL)
                assertEquals(HttpStatusCode.Unauthorized, mcpStatus(client, first.access))
                assertEquals(HttpStatusCode.Unauthorized, mcpStatus(client, second.access))
                assertEquals(HttpStatusCode.BadRequest, refreshRequest(client, CLAUDE_DOC_URL, second.refresh).status)
            }
        }

    @Test
    @DisplayName("beyond 10 logins the least recently used connection ends: its tokens stop working")
    fun eleventhLoginEndsTheOldest() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                val logins =
                    (0..OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT).map {
                        login(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                    }
                assertEquals(
                    OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT,
                    ctx.clientRepository
                        .getClients()
                        .single()
                        .connections.size,
                )
                assertEquals(HttpStatusCode.Unauthorized, mcpStatus(client, logins.first().access))
                val firstRefresh = refreshRequest(client, CLAUDE_DOC_URL, logins.first().refresh)
                assertEquals(HttpStatusCode.BadRequest, firstRefresh.status)
                assertNotEquals(HttpStatusCode.Unauthorized, mcpStatus(client, logins.last().access))
            }
        }

    @Test
    @DisplayName("Claude Code's real document: a loopback redirect on any port, with the loopback warning")
    fun claudeCodeLoopback() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_CODE_URL to FakeOAuthHttpFetcher.json(CLAUDE_CODE_DOC)))
            withServer(fetcher) { ctx ->
                val redirect = "http://localhost:53682/callback"
                val page = authorize(client, CLAUDE_CODE_URL, AuthorizeOptions(redirectUri = redirect)).bodyAsText()
                assertTrue(page.contains(LOOPBACK_WARNING_START), page)
                val warned =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                assertTrue(warned.loopbackRedirect)
                assertEquals("Claude Code (claude.ai)", warned.clientName)
                assertEquals("localhost", warned.redirectHost)
                // No logo in Claude Code's document: the favicon comes from its document host, not from localhost.
                assertEquals(CLAUDE_FAVICON, warned.iconUrl)
                assertTrue(page.contains("https://icons.duckduckgo.com/ip3/claude.ai.ico"), page)
                ctx.approvalCoordinator.deny(warned.id)
                val tokens = login(ctx, CLAUDE_CODE_URL, redirect)
                assertNotEquals(HttpStatusCode.Unauthorized, mcpStatus(client, tokens.access))
                assertEquals("Claude Code", ctx.clientRepository.getClient(CLAUDE_CODE_URL)?.clientName)
                assertEquals(CLAUDE_FAVICON, ctx.clientRepository.getClient(CLAUDE_CODE_URL)?.iconUrl)
            }
        }

    @Test
    @DisplayName("a DCR client on a loopback redirect gets the loopback warning, a hosted one does not")
    fun dcrLoopbackWarning() =
        runTest {
            withServer(FakeOAuthHttpFetcher()) { ctx ->
                val loopback = registerDcr(client, "http://localhost:3000/cb", logoUri = null)
                val options = AuthorizeOptions(redirectUri = "http://localhost:3000/cb")
                val page = authorize(client, loopback, options).bodyAsText()
                assertTrue(page.contains(LOOPBACK_WARNING_START), page)
                val warned =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                assertTrue(warned.loopbackRedirect)
                assertNull(warned.iconUrl)
                ctx.approvalCoordinator.deny(warned.id)

                val hosted = registerDcr(client, OAuthPolicy.CLAUDE_REDIRECT_URI, logoUri = null)
                val hostedPage = authorize(client, hosted).bodyAsText()
                assertFalse(hostedPage.contains(LOOPBACK_WARNING_START), hostedPage)
                assertFalse(
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                        .loopbackRedirect,
                )
            }
        }

    @Test
    @DisplayName("a DCR login keeps the icon its approval showed on the client row")
    fun dcrLoginKeepsIcon() =
        runTest {
            withServer(FakeOAuthHttpFetcher()) { ctx ->
                val dcr = registerDcr(client, OAuthPolicy.CLAUDE_REDIRECT_URI, logoUri = null)
                login(ctx, dcr, OAuthPolicy.CLAUDE_REDIRECT_URI)
                assertEquals(CLAUDE_FAVICON, ctx.clientRepository.getClient(dcr)?.iconUrl)
            }
        }

    @Test
    @DisplayName("a refused document answers the error page, never redirects, and logs why")
    fun refusals() =
        runTest {
            val claude = OAuthPolicy.CLAUDE_REDIRECT_URI
            val chatGpt = OAuthPolicy.CHATGPT_REDIRECT_URI
            val chatGptCallbackInClaudeDoc = claudeDoc(redirects = """["$claude","$chatGpt"]""")
            val bodies =
                mapOf(
                    CLAUDE_DOC_URL to chatGptCallbackInClaudeDoc,
                    MISMATCH_URL to claudeDoc(),
                )
            val cases =
                listOf(
                    Triple("https://evil.example/client.json", claude, "not on a supported app domain"),
                    Triple(
                        "https://claude.ai/oauth/missing.json",
                        claude,
                        "could not be downloaded: ${FakeOAuthHttpFetcher.NOT_SERVED}",
                    ),
                    Triple(MISMATCH_URL, claude, "its client_id is not its URL"),
                    Triple(
                        CLAUDE_DOC_URL,
                        "http://localhost:3000/callback",
                        "redirect URI http://localhost:3000/callback is not in it",
                    ),
                    Triple(CLAUDE_DOC_URL, chatGpt, "redirect URI $chatGpt is not allowed for claude.ai"),
                )
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val fetcher = FakeOAuthHttpFetcher(bodies)
            withServer(fetcher, deps) { ctx ->
                val noRedirect = createClient { followRedirects = false }
                cases.forEach { (clientId, redirectUri, reason) ->
                    val resp = authorize(noRedirect, clientId, AuthorizeOptions(redirectUri = redirectUri))
                    assertEquals(HttpStatusCode.BadRequest, resp.status, clientId)
                    assertNull(resp.headers["Location"], clientId)
                    assertTrue(resp.bodyAsText().contains("Invalid client_id or redirect_uri."), clientId)
                    assertEquals(
                        "OAuth client metadata document refused ($clientId): $reason",
                        deps.serverLog
                            .ofType(ServerLogEntry.Type.OAUTH)
                            .last()
                            .message,
                    )
                }
                assertTrue(
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .isEmpty(),
                )
                assertFalse(fetcher.requested.contains("https://evil.example/client.json"))
                assertTrue(ctx.clientRepository.getClients().isEmpty())
            }
        }

    @Test
    @DisplayName("the code is only redeemed with the document URL it was issued to")
    fun tokenNeedsTheDocumentUrl() =
        runTest {
            val fetcher = FakeOAuthHttpFetcher(mapOf(CLAUDE_DOC_URL to claudeDoc()))
            withServer(fetcher) { ctx ->
                val code = loginCode(ctx, CLAUDE_DOC_URL, OAuthPolicy.CLAUDE_REDIRECT_URI)
                val resp =
                    tokenRequest(
                        client,
                        "https://claude.ai/oauth/other",
                        code,
                        verifier = VERIFIER,
                        includeResource = true,
                        redirectUri = OAuthPolicy.CLAUDE_REDIRECT_URI,
                    )
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(ctx.clientRepository.getClients().isEmpty())
            }
        }

    @Test
    @DisplayName("a DCR logo is downloaded and embedded only from an app domain")
    fun dcrLogoOnlyFromAppDomains() =
        runTest {
            val claudeLogo = "https://claude.ai/dcr-logo.png"
            val evilLogo = "https://evil.example/logo.png"
            val fetcher = FakeOAuthHttpFetcher(mapOf(claudeLogo to png(), evilLogo to png()))
            withServer(fetcher) { ctx ->
                val evilPage = authorize(client, registerWithLogo(client, evilLogo)).bodyAsText()
                val evilApproval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                // The evil logo is not used: the approval shows the favicon of the redirect host.
                assertEquals(CLAUDE_FAVICON, evilApproval.iconUrl)
                assertFalse(evilPage.contains("data:image/png"), evilPage)
                assertTrue(evilPage.contains("https://icons.duckduckgo.com/ip3/claude.ai.ico"), evilPage)
                assertFalse(fetcher.requested.contains(evilLogo))

                val claudePage = authorize(client, registerWithLogo(client, claudeLogo)).bodyAsText()
                assertTrue(claudePage.contains("data:image/png;base64,"), claudePage)
                assertTrue(fetcher.requested.contains(claudeLogo))
            }
        }

    private suspend fun withServer(
        fetcher: FakeOAuthHttpFetcher,
        deps: MockDependencies = McpIntegrationTestHelper.createMockDependencies(),
        block: suspend ApplicationTestBuilder.(McpIntegrationTestHelper.OAuthTestContext) -> Unit,
    ) = McpIntegrationTestHelper.withOAuthTestApplication(
        deps,
        publicUrlOverride = OVERRIDE,
        httpFetcher = fetcher,
        testBlock = block,
    )

    private fun png() = FetchedBody("image/png", PNG)

    /** Authorizes [clientId] on [redirectUri], approves it on the device and returns the code. */
    private suspend fun ApplicationTestBuilder.loginCode(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        clientId: String,
        redirectUri: String,
    ): String {
        assertEquals(HttpStatusCode.OK, authorize(client, clientId, AuthorizeOptions(redirectUri = redirectUri)).status)
        val approval =
            ctx.approvalCoordinator
                .observePending()
                .value
                .single()
        ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
        return Url(statusRedirect(client, approval.id)).parameters["code"]!!
    }

    private suspend fun ApplicationTestBuilder.login(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        clientId: String,
        redirectUri: String,
    ): Tokens = redeem(client, clientId, loginCode(ctx, clientId, redirectUri), redirectUri)

    private suspend fun redeem(
        client: HttpClient,
        clientId: String,
        code: String,
        redirectUri: String,
    ): Tokens {
        val resp =
            tokenRequest(client, clientId, code, verifier = VERIFIER, includeResource = true, redirectUri = redirectUri)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return Tokens(obj["access_token"]!!.jsonPrimitive.content, obj["refresh_token"]!!.jsonPrimitive.content)
    }

    private suspend fun statusRedirect(
        client: HttpClient,
        approvalId: String,
    ): String =
        Json
            .parseToJsonElement(client.get("/authorize/status?id=$approvalId").bodyAsText())
            .jsonObject["redirect"]!!
            .jsonPrimitive.content

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

    private suspend fun mcpStatus(
        client: HttpClient,
        accessToken: String,
    ): HttpStatusCode =
        client
            .post("/mcp") {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                )
            }.status

    /** Registers a DCR client on Claude's callback with [logoUri]; returns its client id. */
    private suspend fun registerWithLogo(
        client: HttpClient,
        logoUri: String,
    ): String = registerDcr(client, OAuthPolicy.CLAUDE_REDIRECT_URI, logoUri)

    /** Registers a DCR client on [redirectUri], with [logoUri] when given; returns its client id. */
    private suspend fun registerDcr(
        client: HttpClient,
        redirectUri: String,
        logoUri: String?,
    ): String {
        val logo = logoUri?.let { ""","logo_uri":"$it"""" }.orEmpty()
        val resp =
            client.post("/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"redirect_uris":["$redirectUri"],"client_name":"Logo"$logo}""")
            }
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["client_id"]!!
            .jsonPrimitive.content
    }

    /** A hosted-Claude shaped document at [CLAUDE_DOC_URL] (its real URL is not documented). */
    private fun claudeDoc(redirects: String = """["${OAuthPolicy.CLAUDE_REDIRECT_URI}"]"""): FetchedBody =
        FakeOAuthHttpFetcher.json(
            """{"client_id":"$CLAUDE_DOC_URL","client_name":"Claude","redirect_uris":$redirects,""" +
                """"token_endpoint_auth_method":"none","logo_uri":"$LOGO"}""",
        )

    private companion object {
        const val CLAUDE_DOC_URL = "https://claude.ai/oauth/test-client-metadata"
        const val MISMATCH_URL = "https://claude.ai/oauth/mismatch"
        const val LOOPBACK_WARNING_START = "The access goes to an app on the computer where this page is open."
        const val CLAUDE_FAVICON = "https://icons.duckduckgo.com/ip3/claude.ai.ico"
        const val LOGO = "https://claude.ai/images/claude-logo.png"
        val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13)

        // Claude Code's real document, read on 2026-11-21.
        const val CLAUDE_CODE_URL = "https://claude.ai/oauth/claude-code-client-metadata"
        const val CLAUDE_CODE_DOC =
            """{"client_id":"https://claude.ai/oauth/claude-code-client-metadata","client_name":"Claude Code",""" +
                """"client_uri":"https://claude.ai",""" +
                """"redirect_uris":["http://localhost/callback","http://127.0.0.1/callback"],""" +
                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                """"token_endpoint_auth_method":"none"}"""
    }
}
