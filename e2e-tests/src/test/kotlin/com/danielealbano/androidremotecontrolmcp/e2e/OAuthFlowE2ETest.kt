package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * test is its own client over plain HTTP, an `http://localhost/callback` redirect from the allowlist is
 * fine (no tunnel needed).
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
        // OAuth is enabled by default, so the container's server already mounts the OAuth endpoints.
        waitForHealth()

        // 1. Dynamic Client Registration
        val (regStatus, regBody) =
            httpPost(
                "$base/register",
                """{"redirect_uris":["$REDIRECT"],"token_endpoint_auth_method":"none",""" +
                    """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                    """"scope":"mcp","client_name":"E2E","application_type":"web"}""",
                "application/json",
            )
        assertEquals(HttpURLConnection.HTTP_CREATED, regStatus, "register failed: $regBody")
        val clientId = Json.parseToJsonElement(regBody).jsonObject["client_id"]!!.jsonPrimitive.content

        // 2. Authorize (creates a pending approval). Parse the approval id from the consent page JS.
        // The optional `resource` param is omitted so the server defaults it to its own request-derived
        // canonical resource (avoids host/port ambiguity through the container port-forward); the token
        // aud and the /mcp check then use the SAME server-side derivation.
        val authorizeUrl =
            "$base/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT)}" +
                "&code_challenge=${enc(CHALLENGE)}&code_challenge_method=S256&state=xyz&scope=mcp"
        val (authStatus, authBody) = httpGet(authorizeUrl)
        assertEquals(HttpURLConnection.HTTP_OK, authStatus, "authorize failed: $authBody")
        val approvalId = APPROVAL_ID_REGEX.find(authBody)?.groupValues?.get(1)
        assertNotNull(approvalId, "could not parse approval id from consent page")

        // 3. Approve on-device (debug-only receiver).
        AndroidContainerSetup.approvePendingOAuth()

        // 4. Poll /authorize/status until approved, extract the code.
        val code = pollForCode(approvalId!!)
        assertNotNull(code, "did not receive an authorization code after approval")

        // 5. Exchange the code for tokens.
        val (tokenStatus, tokenBody) =
            httpPostForm(
                "$base/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code!!,
                    "redirect_uri" to REDIRECT,
                    "client_id" to clientId,
                    "code_verifier" to VERIFIER,
                ),
            )
        assertEquals(HttpURLConnection.HTTP_OK, tokenStatus, "token failed: $tokenBody")
        val accessToken = Json.parseToJsonElement(tokenBody).jsonObject["access_token"]!!.jsonPrimitive.content

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

    private fun pollForCode(approvalId: String): String? {
        val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val (status, body) = httpGet("$base/authorize/status?id=${enc(approvalId)}")
            if (status == HttpURLConnection.HTTP_OK) {
                val obj = Json.parseToJsonElement(body).jsonObject
                when (obj["state"]?.jsonPrimitive?.content) {
                    "approved" -> {
                        val redirect = obj["redirect"]!!.jsonPrimitive.content
                        return CODE_REGEX.find(redirect)?.groupValues?.get(1)
                    }
                    "denied", "expired" -> return null
                }
            }
            Thread.sleep(1_000)
        }
        return null
    }

    private fun httpGet(urlStr: String): Pair<Int, String> {
        val conn = (URI(urlStr).toURL().openConnection() as HttpURLConnection).apply {
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
        val conn = (URI(urlStr).toURL().openConnection() as HttpURLConnection).apply {
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

    private fun HttpURLConnection.use(): Pair<Int, String> {
        return try {
            val status = responseCode
            val stream = if (status in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            status to text
        } finally {
            disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val REDIRECT = "http://localhost/callback"
        const val HTTP_TIMEOUT_MS = 15_000
        const val STATUS_TIMEOUT_MS = 30_000L

        // RFC 7636 Appendix B PKCE test vector.
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val APPROVAL_ID_REGEX = Regex("""var id = "([^"]+)"""")
        val CODE_REGEX = Regex("""[?&]code=([^&]+)""")
    }
}
