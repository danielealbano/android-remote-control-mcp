package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.OVERRIDE
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.assertConnectorFlow
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.registerResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
                assertConnectorFlow(
                    ctx,
                    redirectUris = GEMINI_CALLBACKS,
                    redirectUri = GEMINI_REDIRECT,
                    clientName = "Google",
                    redirectHost = "oauth-redirect.googleusercontent.com",
                )
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
