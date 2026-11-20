package com.danielealbano.androidremotecontrolmcp.integration

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

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
}
