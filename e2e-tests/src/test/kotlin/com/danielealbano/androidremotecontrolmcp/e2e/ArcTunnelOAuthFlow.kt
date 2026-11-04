package com.danielealbano.androidremotecontrolmcp.e2e

import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.APPROVAL_ID_REGEX
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.CHALLENGE
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.CODE_REGEX
import com.danielealbano.androidremotecontrolmcp.e2e.OAuthE2EVectors.VERIFIER
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URLEncoder

/**
 * The OAuth flow as a public connector runs it through the tunnel: a backend on HTTP/1.1 reads the metadata, registers
 * and redeems the code, while the user's browser opens `/authorize` on HTTP/2. Every request names the public host with
 * no port, as a client on port 443 does, even though the test reaches the edge on the relay's port: that port-less
 * `:authority` is what the listener must not turn into a URL with its own loopback port.
 */
class ArcTunnelOAuthFlow(
    stack: ArcTunnelStack,
    private val fqdn: String,
) {
    private val client: OkHttpClient =
        ArcTunnelPublicClient
            .stackOkHttp(stack)
            .newBuilder()
            .followRedirects(false)
            .build()
    private val http1 = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private val http2 = client.newBuilder().protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)).build()
    private val edge = ArcTunnelPublicClient.stackUrl(fqdn)
    private val base = "https://$fqdn"
    private val resource = "$base/mcp"

    private class Answer(
        val code: Int,
        val body: String,
        val protocol: Protocol,
        val challenge: String?,
        val location: String?,
    )

    private fun send(
        http: OkHttpClient,
        path: String,
        post: RequestBody? = null,
        token: String? = null,
    ): Answer {
        val request =
            Request
                .Builder()
                .url("$edge$path")
                .header("Host", fqdn)
                .header("Accept", "application/json, text/event-stream")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .apply { post?.let { post(it) } }
                .build()
        return http.newCall(request).execute().use {
            Answer(it.code, it.body.string(), it.protocol, it.header("WWW-Authenticate"), it.header("Location"))
        }
    }

    private fun json(text: String): RequestBody = text.toRequestBody("application/json".toMediaType())

    /** The metadata and the 401 challenge name the public URL on both HTTP versions. */
    fun assertPublicUrlsOnBothProtocols() {
        for ((http, protocol) in listOf(http1 to Protocol.HTTP_1_1, http2 to Protocol.HTTP_2)) {
            val metadata = send(http, "/.well-known/oauth-protected-resource")
            assertEquals(protocol, metadata.protocol)
            assertTrue(metadata.body.contains("\"resource\":\"$resource\""), metadata.body)
            assertTrue(metadata.body.contains("\"authorization_servers\":[\"$base\"]"), metadata.body)
            val server = send(http, "/.well-known/oauth-authorization-server")
            assertTrue(server.body.contains("\"issuer\":\"$base\""), server.body)
            val unauthorized = send(http, "/mcp", json("{}"))
            assertEquals(UNAUTHORIZED, unauthorized.code)
            assertEquals(
                "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource/mcp\"",
                unauthorized.challenge,
            )
        }
    }

    /** Registers, authorizes with the advertised `resource`, approves on the device and redeems the code. */
    fun obtainAccessToken(): String {
        val clientId = register()
        val approvalId = authorizeInBrowser(clientId)
        AndroidContainerSetup.approvePendingOAuth()
        return redeem(clientId, awaitCode(approvalId))
    }

    /** `tools/list` with [token] answers on both HTTP versions, so the token's audience matches both. */
    fun assertToolsListedOnBothProtocols(token: String) {
        val list = json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        for ((http, protocol) in listOf(http1 to Protocol.HTTP_1_1, http2 to Protocol.HTTP_2)) {
            val answer = send(http, "/mcp", list, token)
            assertEquals(protocol, answer.protocol)
            assertEquals(OK, answer.code, answer.body)
            assertTrue(answer.body.contains("press_home"), answer.body)
        }
    }

    private fun register(): String {
        val body =
            """{"redirect_uris":["$REDIRECT"],"token_endpoint_auth_method":"none",""" +
                """"grant_types":["authorization_code","refresh_token"],"response_types":["code"],""" +
                """"scope":"mcp","client_name":"E2E public client"}"""
        val answer = send(http1, "/register", json(body))
        assertEquals(CREATED, answer.code, answer.body)
        return field(answer.body, "client_id")
    }

    private fun authorizeInBrowser(clientId: String): String {
        val query =
            "response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT)}" +
                "&code_challenge=${enc(CHALLENGE)}&code_challenge_method=S256&state=xyz&scope=mcp" +
                "&resource=${enc(resource)}"
        val answer = send(http2, "/authorize?$query")
        assertEquals(Protocol.HTTP_2, answer.protocol)
        // A refusal such as invalid_target is a redirect with the error in its location.
        assertEquals(OK, answer.code, "location: ${answer.location}, body: ${answer.body}")
        return requireNotNull(APPROVAL_ID_REGEX.find(answer.body)?.groupValues?.get(1)) {
            "no approval id: ${answer.body}"
        }
    }

    private fun awaitCode(approvalId: String): String {
        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        var state = "not read"
        while (System.currentTimeMillis() < deadline) {
            val status = send(http2, "/authorize/status?id=${enc(approvalId)}")
            state = if (status.code == OK) field(status.body, "state") else "HTTP ${status.code}"
            if (state == "approved") {
                val redirect = field(status.body, "redirect")
                return requireNotNull(CODE_REGEX.find(redirect)?.groupValues?.get(1)) { "no code in $redirect" }
            }
            check(state != "denied" && state != "expired") { "the authorization ended as $state" }
            Thread.sleep(POLL_MS)
        }
        error("the authorization did not settle within $APPROVAL_TIMEOUT_MS ms, last state: $state")
    }

    private fun redeem(
        clientId: String,
        code: String,
    ): String {
        val form =
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT)
                .add("client_id", clientId)
                .add("code_verifier", VERIFIER)
                .add("resource", resource)
                .build()
        val answer = send(http1, "/token", form)
        assertEquals(OK, answer.code, answer.body)
        return field(answer.body, "access_token")
    }

    private fun field(
        body: String,
        name: String,
    ): String =
        requireNotNull(Json.parseToJsonElement(body).jsonObject[name]) { "no $name in $body" }
            .jsonPrimitive.content

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect"
        const val OK = 200
        const val CREATED = 201
        const val UNAUTHORIZED = 401
        const val APPROVAL_TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L
    }
}
