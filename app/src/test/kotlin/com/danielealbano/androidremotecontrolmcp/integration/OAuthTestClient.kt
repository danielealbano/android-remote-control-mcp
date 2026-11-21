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
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** The OAuth client side of the OAuth integration tests: DCR, authorize, token, and an MCP client on the token. */
internal object OAuthTestClient {
    const val REDIRECT = "https://claude.ai/api/mcp/auth_callback"
    const val OVERRIDE = "https://test.host"
    const val CANONICAL = "https://test.host/mcp"

    // RFC 7636 Appendix B PKCE test vector.
    const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    data class AuthorizeOptions(
        val redirectUri: String = REDIRECT,
        val challengeMethod: String = "S256",
        val resourceOverride: String? = null,
        val includeResource: Boolean = true,
        val state: String = "xyz",
    )

    suspend fun registerResponse(
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

    suspend fun authorize(
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

    suspend fun tokenRequest(
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

    /** A client that does not follow redirects, to read an authorization redirect's `Location`. */
    fun ApplicationTestBuilder.createNoRedirectClient(): HttpClient =
        createClient {
            followRedirects = false
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(io.modelcontextprotocol.kotlin.sdk.types.McpJson)
            }
        }

    suspend fun connectMcp(
        app: ApplicationTestBuilder,
        accessToken: String,
    ): Client {
        val httpClient =
            app.createClient {
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

    /**
     * A hosted connector's whole flow: DCR with [redirectUris] as [clientName], authorization on [redirectUri], the
     * on-device approval showing [clientName] and [redirectHost], the code and the RFC 9207 `iss` on [redirectUri],
     * the token, and an `android_tap` call with the access token (the caller stubs the tap and sets the public URL
     * to [OVERRIDE]).
     */
    suspend fun ApplicationTestBuilder.assertConnectorFlow(
        ctx: McpIntegrationTestHelper.OAuthTestContext,
        redirectUris: List<String>,
        redirectUri: String,
        clientName: String,
        redirectHost: String,
    ) {
        val registerResp = registerResponse(client, redirectUris, clientName)
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val registered = Json.parseToJsonElement(registerResp.bodyAsText()).jsonObject
        assertEquals(redirectUris, registered["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
        val clientId = registered["client_id"]!!.jsonPrimitive.content

        authorize(client, clientId, AuthorizeOptions(redirectUri = redirectUri))
        val approval =
            ctx.approvalCoordinator
                .observePending()
                .value
                .single()
        assertEquals(clientName, approval.clientName)
        assertEquals(redirectHost, approval.redirectHost)
        ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
        val redirect =
            Json
                .parseToJsonElement(client.get("/authorize/status?id=${approval.id}").bodyAsText())
                .jsonObject["redirect"]!!
                .jsonPrimitive.content
        assertTrue(redirect.startsWith("$redirectUri?"), redirect)
        assertEquals("xyz", Url(redirect).parameters["state"])
        assertEquals(OVERRIDE, Url(redirect).parameters["iss"])

        val tokenResp =
            tokenRequest(
                client,
                clientId,
                Url(redirect).parameters["code"]!!,
                verifier = VERIFIER,
                includeResource = true,
                redirectUri = redirectUri,
            )
        assertEquals(HttpStatusCode.OK, tokenResp.status)
        val access =
            Json
                .parseToJsonElement(tokenResp.bodyAsText())
                .jsonObject["access_token"]!!
                .jsonPrimitive.content
        val mcpClient = connectMcp(this, access)
        try {
            val result = mcpClient.callTool(name = "android_tap", arguments = mapOf("x" to 1, "y" to 1))
            assertNotEquals(true, result.isError)
        } finally {
            mcpClient.close()
        }
    }
}
