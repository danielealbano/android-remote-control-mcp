package com.danielealbano.androidremotecontrolmcp.services.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.utils.EmptyContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@DisplayName("LocalRequestReplayer")
class LocalRequestReplayerTest {
    private val captured = AtomicReference<HttpRequestData?>(null)
    private val calls = AtomicInteger(0)

    private fun replayer(
        maxResponseBytes: Long = 64,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): LocalRequestReplayer {
        val engine =
            MockEngine { request ->
                captured.set(request)
                calls.incrementAndGet()
                handler(request)
            }
        val client =
            HttpClient(engine) {
                followRedirects = false
                install(HttpTimeout)
            }
        return LocalRequestReplayer(client, timeoutMs = 1_000, maxResponseBytes = maxResponseBytes)
    }

    private fun frame(
        method: String = "post",
        path: String = "/mcp?x=1",
        headers: Map<String, List<String>> = emptyMap(),
        body: String = "",
    ) = RelayRequestFrame(
        id = "r1",
        method = method,
        path = path,
        headers = headers,
        body = Base64.getEncoder().encodeToString(body.toByteArray()),
    )

    private fun decodedBody(response: RelayResponseFrame): String = String(Base64.getDecoder().decode(response.body))

    private fun errorMessage(response: RelayResponseFrame): String =
        checkNotNull(Json.parseToJsonElement(decodedBody(response)).jsonObject["error"]).jsonPrimitive.content

    private fun headerNames(response: RelayResponseFrame): Set<String> = response.headers.keys.map { it.lowercase() }.toSet()

    @Test
    fun `replay targets loopback with method and raw path`() =
        runTest {
            val replayer = replayer { respond("ok") }

            replayer.replay(frame(), localPort = 8081)

            val request = checkNotNull(captured.get())
            assertEquals("http://127.0.0.1:8081/mcp?x=1", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
        }

    @Test
    fun `replay forwards allowed headers and drops hop-by-hop ones`() =
        runTest {
            val replayer = replayer { respond("ok") }
            val headers =
                mapOf(
                    "X-Test" to listOf("1"),
                    "Authorization" to listOf("Bearer abc"),
                    "Accept" to listOf("application/json"),
                    "Host" to listOf("relay.example.com"),
                    "Content-Length" to listOf("2"),
                    "Transfer-Encoding" to listOf("chunked"),
                    "Connection" to listOf("keep-alive"),
                    "Expect" to listOf("100-continue"),
                    "Accept-Encoding" to listOf("gzip"),
                )

            replayer.replay(frame(headers = headers, body = "{}"), localPort = 8081)

            val sent = checkNotNull(captured.get()).headers
            assertEquals("1", sent["X-Test"])
            assertEquals("Bearer abc", sent["Authorization"])
            assertEquals("application/json", sent["Accept"])
            listOf("Host", "Content-Length", "Transfer-Encoding", "Connection", "Expect", "Accept-Encoding").forEach {
                assertNull(sent[it], "header $it must not be forwarded")
            }
        }

    @Test
    fun `replay decodes body and applies content type`() =
        runTest {
            val replayer = replayer { respond("ok") }
            val headers = mapOf("Content-Type" to listOf("application/json"))

            replayer.replay(frame(headers = headers, body = """{"hello":"world"}"""), localPort = 8081)

            val body = checkNotNull(captured.get()).body
            assertTrue(body is OutgoingContent.ByteArrayContent)
            assertEquals("""{"hello":"world"}""", String((body as OutgoingContent.ByteArrayContent).bytes()))
            assertEquals(ContentType.Application.Json, body.contentType)
        }

    @Test
    fun `replay omits body when frame body is empty`() =
        runTest {
            val replayer = replayer { respond("ok") }

            replayer.replay(frame(method = "get", path = "/health"), localPort = 8081)

            val request = checkNotNull(captured.get())
            assertEquals(HttpMethod.Get, request.method)
            assertTrue(request.body is EmptyContent)
        }

    @Test
    fun `replay unparsable content type falls back to octet-stream`() =
        runTest {
            val replayer = replayer { respond("ok") }

            replayer.replay(frame(headers = mapOf("Content-Type" to listOf("???")), body = "x"), localPort = 8081)

            assertEquals(ContentType.Application.OctetStream, checkNotNull(captured.get()).body.contentType)
        }

    @Test
    fun `replay packs status headers and base64 body`() =
        runTest {
            val replayer =
                replayer {
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Mcp-Session-Id", "abc"),
                    )
                }

            val response = replayer.replay(frame(), localPort = 8081)

            assertEquals(201, response.status)
            assertEquals(listOf("abc"), response.headers["Mcp-Session-Id"])
            assertEquals("""{"ok":true}""", decodedBody(response))
        }

    @Test
    fun `replay drops content-length and transfer-encoding from response headers`() =
        runTest {
            val replayer =
                replayer {
                    respond(
                        content = "ok",
                        headers =
                            headersOf(
                                HttpHeaders.ContentLength to listOf("2"),
                                HttpHeaders.TransferEncoding to listOf("chunked"),
                                "X-Keep" to listOf("1"),
                            ),
                    )
                }

            val response = replayer.replay(frame(), localPort = 8081)

            val names = headerNames(response)
            assertFalse(names.contains("content-length"))
            assertFalse(names.contains("transfer-encoding"))
            assertTrue(names.contains("x-keep"))
        }

    @Test
    fun `replay passes a redirect through without following it`() =
        runTest {
            val replayer =
                replayer {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "/elsewhere"),
                    )
                }

            val response = replayer.replay(frame(method = "get", path = "/authorize?x=1"), localPort = 8081)

            assertEquals(302, response.status)
            assertEquals(listOf("/elsewhere"), response.headers[HttpHeaders.Location])
            assertEquals(1, calls.get())
        }

    @Test
    fun `replay rejects a path without leading slash`() =
        runTest {
            val replayer = replayer { respond("ok") }

            val response = replayer.replay(frame(path = "mcp"), localPort = 8081)

            assertEquals(400, response.status)
            assertEquals(0, calls.get())
        }

    @Test
    fun `replay rejects a protocol-relative path`() =
        runTest {
            val replayer = replayer { respond("ok") }

            val response = replayer.replay(frame(path = "//evil.example/x"), localPort = 8081)

            assertEquals(400, response.status)
            assertEquals(0, calls.get())
        }

    @Test
    fun `replay maps io failure to 502 json frame`() =
        runTest {
            val replayer = replayer { throw IOException("connection refused to 127.0.0.1:8081") }

            val response = replayer.replay(frame(), localPort = 8081)

            assertEquals(502, response.status)
            assertEquals(listOf("application/json"), response.headers["Content-Type"])
            assertEquals("local MCP server unreachable", errorMessage(response))
        }

    @Test
    fun `replay maps request timeout to 504`() =
        runTest {
            val replayer = replayer { throw HttpRequestTimeoutException("http://127.0.0.1:8081/mcp", 1_000L, null) }

            val response = replayer.replay(frame(), localPort = 8081)

            assertEquals(504, response.status)
            assertEquals("local MCP server did not answer in time", errorMessage(response))
        }

    @Test
    fun `replay maps invalid base64 body to 400`() =
        runTest {
            val replayer = replayer { respond("ok") }
            val invalid = RelayRequestFrame(id = "r1", method = "POST", path = "/mcp", body = "%%%")

            val response = replayer.replay(invalid, localPort = 8081)

            assertEquals(400, response.status)
            assertEquals(0, calls.get())
        }

    @Test
    fun `replay maps oversized response to 502`() =
        runTest {
            val replayer = replayer(maxResponseBytes = 64) { respond("a".repeat(65)) }

            val response = replayer.replay(frame(), localPort = 8081)

            assertEquals(502, response.status)
            assertTrue(errorMessage(response).contains("too large"))
        }

    @Test
    fun `replay accepts a response at the limit`() =
        runTest {
            val replayer = replayer(maxResponseBytes = 64) { respond("a".repeat(64)) }

            val response = replayer.replay(frame(), localPort = 8081)

            assertEquals(200, response.status)
            assertEquals("a".repeat(64), decodedBody(response))
        }

    @Test
    fun `sanitizedPath strips the query and redacts capability links`() {
        assertEquals("/mcp", LocalRequestReplayer.sanitizedPath("/mcp?x=1"))
        assertEquals("/s/<redacted>", LocalRequestReplayer.sanitizedPath("/s/abc?x=1"))
    }
}
