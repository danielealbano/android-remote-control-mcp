package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.AuthorizeOptions
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.OVERRIDE
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.VERIFIER
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.authorize
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.connectMcp
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.registerResponse
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.tokenRequest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth Gemini Connector Integration Tests")
class OAuthGeminiConnectorIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    @Test
    @DisplayName("six callbacks registered, approval on the production /r/ callback, tool call")
    fun geminiConnectorFlowSucceeds() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                val registerResp = registerResponse(client, GEMINI_CALLBACKS, clientName = "Google")
                assertEquals(HttpStatusCode.Created, registerResp.status)
                val registered = Json.parseToJsonElement(registerResp.bodyAsText()).jsonObject
                assertEquals(GEMINI_CALLBACKS, registered["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
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
                val mcpClient = connectMcp(this, access)
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

    private companion object {
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
    }
}
