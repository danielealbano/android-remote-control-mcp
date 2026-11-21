package com.danielealbano.androidremotecontrolmcp.e2e

import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.APPROVAL_ID_REGEX
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.CHALLENGE
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.CODE_REGEX
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.VERIFIER
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.issOf
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * E2E test: full self-contained OAuth 2.1 flow against the app running in the redroid container.
 *
 * Acts as the OAuth client over the container's forwarded HTTP port: DCR → authorize → (debug) approve
 * → token → connect the MCP SDK client with the issued access token → call a basic tool. Because the
 * test is its own client over plain HTTP and never follows the final redirect, an `http://localhost/callback`
 * redirect from the allowlist is fine (no tunnel needed), and so are ChatGPT's callback and Gemini's six relay
 * callbacks; the device checks them against the allowlist with its own `java.net.URI`.
 *
 * Container assumptions: the shared container must have OAuth enabled and the server (re)started so the
 * new auth model is live — driven via [AndroidContainerSetup.configureOAuthEnabled] + a server restart.
 * Requires rootful Podman on the host.
 *
 * This also exercises java-jwt + jackson on-device (covering the transitive-dependency runtime risk).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthFlowE2ETest {
    private val mcpUrl = SharedAndroidContainer.mcpServerUrl
    private val base = mcpUrl.removeSuffix("/mcp")

    @Test
    fun `full OAuth flow issues a token that authorizes a tool call`() {
        runFlow(redirectUris = listOf(REDIRECT), redirectUri = REDIRECT, clientName = "E2E")
    }

    @Test
    fun `ChatGPT connector registration and callback complete the OAuth flow`() {
        runFlow(redirectUris = listOf(CHATGPT_REDIRECT), redirectUri = CHATGPT_REDIRECT, clientName = "ChatGPT")
    }

    @Test
    fun `ChatGPT callbacks with a port or dot segments are refused at registration`() {
        waitForHealth()
        listOf(
            "https://chatgpt.com:443/connector/oauth/-BSmWqXatII2",
            "https://chatgpt.com/connector/oauth/../../evil",
        ).forEach { uri ->
            val (status, body) =
                httpPost(
                    "$base/register",
                    """{"redirect_uris":["$uri"],"token_endpoint_auth_method":"none"}""",
                    "application/json",
                )
            assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, status, "$uri: $body")
            assertTrue(body.contains("invalid_redirect_uri"), "$uri: $body")
        }
    }

    @Test
    fun `Gemini connector registration and callback complete the OAuth flow`() {
        runFlow(redirectUris = GEMINI_CALLBACKS, redirectUri = GEMINI_REDIRECT, clientName = "Google")
    }

    private fun runFlow(
        redirectUris: List<String>,
        redirectUri: String,
        clientName: String,
    ) {
        // OAuth is enabled by default, so the container's server already mounts the OAuth endpoints.
        waitForHealth()

        val issuer = metadataIssuer()

        // 1. Dynamic Client Registration
        val (regStatus, regBody) =
            httpPost(
                "$base/register",
                """{"redirect_uris":[${redirectUris.joinToString(",") { "\"$it\"" }}],""" +
                    """"token_endpoint_auth_method":"none",""" +
                    """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                    """"scope":"mcp","client_name":"$clientName","application_type":"web"}""",
                "application/json",
            )
        assertEquals(HttpURLConnection.HTTP_CREATED, regStatus, "register failed: $regBody")
        val registered = Json.parseToJsonElement(regBody).jsonObject
        assertEquals(redirectUris, registered["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
        val clientId = registered["client_id"]!!.jsonPrimitive.content

        // 2. Authorize (creates a pending approval). Parse the approval id from the consent page JS.
        // The optional `resource` param is omitted so the server defaults it to its own request-derived
        // canonical resource (avoids host/port ambiguity through the container port-forward); the token
        // aud and the /mcp check then use the SAME server-side derivation.
        val authorizeUrl =
            "$base/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}" +
                "&code_challenge=${enc(CHALLENGE)}&code_challenge_method=S256&state=xyz&scope=mcp"
        val (authStatus, authBody) = httpGet(authorizeUrl)
        assertEquals(HttpURLConnection.HTTP_OK, authStatus, "authorize failed: $authBody")
        val approvalId = APPROVAL_ID_REGEX.find(authBody)?.groupValues?.get(1)
        assertNotNull(approvalId, "could not parse approval id from consent page")

        // 3. Approve on-device (debug-only receiver).
        AndroidContainerSetup.approvePendingOAuth()

        // 4. Poll /authorize/status until approved, extract the code.
        val code = pollForCode(approvalId!!, issuer)
        assertNotNull(code, "did not receive an authorization code after approval")

        // 5. Exchange the code for tokens.
        val (tokenStatus, tokenBody) =
            httpPostForm(
                "$base/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code!!,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to VERIFIER,
                ),
            )
        assertEquals(HttpURLConnection.HTTP_OK, tokenStatus, "token failed: $tokenBody")
        val accessToken =
            Json
                .parseToJsonElement(tokenBody)
                .jsonObject["access_token"]!!
                .jsonPrimitive.content

        // 6. Connect the MCP client with the issued access token and call a basic tool.
        val client = McpClient(mcpUrl, accessToken)
        runBlocking {
            client.connect()
            try {
                val result = client.callTool("${AndroidContainerSetup.TOOL_NAME_PREFIX}get_screen_state")
                assertNotEquals(
                    true,
                    result.isError,
                    "tool call failed: ${(result.content.firstOrNull() as? TextContent)?.text}",
                )
                assertTrue(result.content.isNotEmpty(), "tool returned no content")
            } finally {
                client.close()
            }
        }
    }

    private fun waitForHealth() {
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val (status, _) = runCatching { httpGet("$base/health") }.getOrDefault(-1 to "")
            if (status == HttpURLConnection.HTTP_OK) return
            Thread.sleep(1_000)
        }
    }

    /** The metadata `issuer`, after checking the metadata advertises RFC 9207 issuer identification. */
    private fun metadataIssuer(): String {
        val (status, body) = httpGet("$base/.well-known/oauth-authorization-server")
        assertEquals(HttpURLConnection.HTTP_OK, status, body)
        val metadata = Json.parseToJsonElement(body).jsonObject
        assertEquals("true", metadata["authorization_response_iss_parameter_supported"]?.jsonPrimitive?.content)
        return metadata["issuer"]!!.jsonPrimitive.content
    }

    /** The code of the approved redirect, after checking the redirect carries `iss` = [issuer] (RFC 9207). */
    private fun pollForCode(
        approvalId: String,
        issuer: String,
    ): String? {
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        var settled: JsonObject? = null
        while (settled == null && System.currentTimeMillis() < deadline) {
            settled = settledApprovalStatus(approvalId)
            if (settled == null) Thread.sleep(1_000)
        }
        val approved = settled?.takeIf { it["state"]?.jsonPrimitive?.content == "approved" } ?: return null
        val redirect = approved["redirect"]!!.jsonPrimitive.content
        assertEquals(issuer, issOf(redirect), redirect)
        return CODE_REGEX.find(redirect)?.groupValues?.get(1)
    }

    /**
     * The approval status once it is approved, denied or expired; null while it is pending or the status endpoint
     * answers a non-200 status. A request that throws propagates.
     */
    private fun settledApprovalStatus(approvalId: String): JsonObject? {
        val (status, body) = httpGet("$base/authorize/status?id=${enc(approvalId)}")
        if (status != HttpURLConnection.HTTP_OK) return null
        return Json.parseToJsonElement(body).jsonObject.takeIf { it["state"]?.jsonPrimitive?.content in SETTLED_STATES }
    }

    private fun httpGet(urlStr: String): Pair<Int, String> {
        val conn =
            (URI(urlStr).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
        return conn.use()
    }

    private fun httpPost(
        urlStr: String,
        body: String,
        contentType: String,
    ): Pair<Int, String> {
        val conn =
            (URI(urlStr).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        return conn.use()
    }

    private fun httpPostForm(
        urlStr: String,
        form: Map<String, String>,
    ): Pair<Int, String> {
        val encoded = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return httpPost(urlStr, encoded, "application/x-www-form-urlencoded")
    }

    private fun HttpURLConnection.use(): Pair<Int, String> =
        try {
            val status = responseCode
            val stream = if (status in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            status to text
        } finally {
            disconnect()
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val REDIRECT = "http://localhost/callback"

        // The redirect URI ChatGPT registered on a real device.
        const val CHATGPT_REDIRECT = "https://chatgpt.com/connector/oauth/-BSmWqXatII2"

        // The six redirect URIs Gemini registered on a real device; it authorizes on the production /r/ one.
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
        const val HTTP_TIMEOUT_MS = 15_000
        const val STATUS_TIMEOUT_MS = 30_000L
        val SETTLED_STATES = setOf("approved", "denied", "expired")
    }
}
