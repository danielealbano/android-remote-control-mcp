package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.AuthorizeOptions
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.OVERRIDE
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.REDIRECT
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.authorize
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.createNoRedirectClient
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.registerResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Issuer Identification (RFC 9207) Integration Tests")
class OAuthIssuerIdentificationIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    @Test
    @DisplayName("the code redirect carries the request-derived issuer the metadata names")
    fun codeRedirectCarriesMetadataIssuer() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { ctx ->
                val issuer = metadataIssuer(client)
                val clientId = register(client)
                // No public URL override, so the request-derived resource is the one to match.
                authorize(client, clientId, AuthorizeOptions(includeResource = false))
                val approval =
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .single()
                ctx.approvalCoordinator.approve(approval.id, System.currentTimeMillis())
                val redirect =
                    Json
                        .parseToJsonElement(client.get("/authorize/status?id=${approval.id}").bodyAsText())
                        .jsonObject["redirect"]!!
                        .jsonPrimitive.content
                assertTrue(redirect.startsWith("$REDIRECT?"), redirect)
                assertEquals(issuer, Url(redirect).parameters["iss"])
            }
        }

    @Test
    @DisplayName("an error redirect carries the request-derived issuer the metadata names")
    fun errorRedirectCarriesMetadataIssuer() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { _ ->
                val issuer = metadataIssuer(client)
                val clientId = register(client)
                val resp =
                    authorize(
                        createNoRedirectClient(),
                        clientId,
                        AuthorizeOptions(challengeMethod = "plain", includeResource = false),
                    )
                assertEquals(HttpStatusCode.Found, resp.status)
                val location = Url(resp.headers["Location"]!!)
                assertEquals("invalid_request", location.parameters["error"])
                assertEquals("xyz", location.parameters["state"])
                assertEquals(issuer, location.parameters["iss"])
            }
        }

    @Test
    @DisplayName("with a public URL override the metadata issuer and the error redirect iss are the override")
    fun issuerFollowsPublicUrlOverride() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { _ ->
                assertEquals(OVERRIDE, metadataIssuer(client))
                val clientId = register(client)
                val resp =
                    authorize(
                        createNoRedirectClient(),
                        clientId,
                        AuthorizeOptions(resourceOverride = "https://other.host/mcp"),
                    )
                assertEquals(HttpStatusCode.Found, resp.status)
                val location = Url(resp.headers["Location"]!!)
                assertEquals("invalid_target", location.parameters["error"])
                assertEquals(OVERRIDE, location.parameters["iss"])
            }
        }

    /** The metadata `issuer`, after checking the metadata advertises RFC 9207 issuer identification. */
    private suspend fun metadataIssuer(client: HttpClient): String {
        val metadata =
            Json.parseToJsonElement(client.get("/.well-known/oauth-authorization-server").bodyAsText()).jsonObject
        assertTrue(metadata["authorization_response_iss_parameter_supported"]!!.jsonPrimitive.boolean)
        return metadata["issuer"]!!.jsonPrimitive.content
    }

    private suspend fun register(client: HttpClient): String {
        val resp = registerResponse(client, listOf(REDIRECT), clientName = "Test")
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["client_id"]!!
            .jsonPrimitive.content
    }
}
