package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.AuthorizeOptions
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.OVERRIDE
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.assertConnectorFlow
import com.danielealbano.androidremotecontrolmcp.integration.OAuthTestClient.authorize
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

@DisplayName("OAuth ChatGPT Connector Integration Tests")
class OAuthChatGptConnectorIntegrationTest {
    @BeforeEach
    fun setUp() = McpIntegrationTestHelper.mockAndroidLog()

    @AfterEach
    fun tearDown() = McpIntegrationTestHelper.unmockAndroidLog()

    @Test
    @DisplayName("callback-id callback registered, approval shown as ChatGPT, tool call")
    fun chatGptConnectorFlowSucceeds() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)
            McpIntegrationTestHelper.withOAuthTestApplication(deps = deps, publicUrlOverride = OVERRIDE) { ctx ->
                assertConnectorFlow(
                    ctx,
                    redirectUris = listOf(CHATGPT_REDIRECT),
                    redirectUri = CHATGPT_REDIRECT,
                    clientName = "ChatGPT",
                    redirectHost = "chatgpt.com",
                )
            }
        }

    @Test
    @DisplayName("register rejects a ChatGPT callback with dot segments")
    fun registerRejectsChatGptDotSegmentCallback() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication { ctx ->
                val resp = registerResponse(client, listOf(DOT_SEGMENT_CALLBACK), clientName = "ChatGPT")
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("invalid_redirect_uri"))
                assertTrue(ctx.clientRepository.getClients().isEmpty())
            }
        }

    @Test
    @DisplayName("authorize refuses a stored ChatGPT callback that the allowlist no longer accepts")
    fun authorizeRejectsStoredCallbackOutsideTheRule() =
        runTest {
            McpIntegrationTestHelper.withOAuthTestApplication(publicUrlOverride = OVERRIDE) { ctx ->
                // A client stored before the rule was tightened, when any path under /connector/oauth/ passed.
                val stored =
                    ctx.clientRepository.register(
                        clientName = "ChatGPT",
                        redirectUris = listOf(DOT_SEGMENT_CALLBACK),
                        applicationType = "web",
                        logoUri = null,
                        nowMs = System.currentTimeMillis(),
                    )
                val resp = authorize(client, stored.clientId, AuthorizeOptions(redirectUri = DOT_SEGMENT_CALLBACK))
                assertEquals(HttpStatusCode.BadRequest, resp.status)
                assertTrue(resp.bodyAsText().contains("Invalid client_id or redirect_uri."))
                assertTrue(
                    ctx.approvalCoordinator
                        .observePending()
                        .value
                        .isEmpty(),
                )
            }
        }

    private companion object {
        // The redirect URI ChatGPT registered on a real device.
        const val CHATGPT_REDIRECT = "https://chatgpt.com/connector/oauth/-BSmWqXatII2"
        const val DOT_SEGMENT_CALLBACK = "https://chatgpt.com/connector/oauth/../../evil"
    }
}
