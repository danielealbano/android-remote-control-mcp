package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real sockets and real time: the provider dials a Netty [RelayTestServer] through the production OkHttp
 * WebSocket engine. Waits are bounded (5 s) and every expected delay is at most 2 s.
 */
@DisplayName("RelayTunnelProvider")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RelayTunnelProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withRelay(
        deviceHandler: suspend DefaultWebSocketServerSession.() -> Unit,
        localRoutes: Route.() -> Unit = {},
        handshakeDelayMs: Long = 0,
        block: suspend (RelayTestServer, RelayTunnelProvider) -> Unit,
    ) = runBlocking {
        val server = RelayTestServer(deviceHandler, localRoutes, handshakeDelayMs)
        server.start()
        val provider = RelayTunnelProvider()
        try {
            block(server, provider)
        } finally {
            provider.stop()
            server.stop()
        }
    }

    private suspend fun awaitStatus(
        provider: RelayTunnelProvider,
        predicate: (TunnelStatus) -> Boolean,
    ): TunnelStatus = withTimeout(WAIT_MS) { provider.status.first(predicate) }

    private suspend fun awaitError(provider: RelayTunnelProvider): String =
        (awaitStatus(provider) { it is TunnelStatus.Error } as TunnelStatus.Error).message

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) {
                delay(POLL_MS)
            }
        }
    }

    private fun requestJson(
        id: String,
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = "",
    ): String =
        json.encodeToString(
            RelayRequestFrame.serializer(),
            RelayRequestFrame(
                id = id,
                method = method,
                path = path,
                headers = headers,
                body = Base64.getEncoder().encodeToString(body.toByteArray()),
            ),
        )

    private fun decodeResponse(text: String) = json.decodeFromString(RelayResponseFrame.serializer(), text)

    private fun decodedBody(response: RelayResponseFrame): String = String(Base64.getDecoder().decode(response.body))

    private suspend fun DefaultWebSocketServerSession.nextText(): String =
        withTimeout(WAIT_MS) {
            var text: String? = null
            while (text == null) {
                text = (incoming.receive() as? Frame.Text)?.readText()
            }
            text
        }

    /** Keeps the device socket open until the client closes it. */
    private suspend fun DefaultWebSocketServerSession.awaitClientClose() {
        var open = true
        while (open) {
            open = incoming.receiveCatching().isSuccess
        }
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `initial status is Disconnected`() {
        assertEquals(TunnelStatus.Disconnected, RelayTunnelProvider().status.value)
    }

    @Test
    fun `start with empty relay url sets error`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, ServerConfig())

            assertEquals(TunnelStatus.Error("Relay URL is required"), provider.status.value)
            assertEquals(0, server.handshakeAttempts.get())
        }

    @Test
    fun `start with empty token sets error`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config(token = " "))

            assertEquals(TunnelStatus.Error("Relay device token is required"), provider.status.value)
        }

    @Test
    fun `start with short token sets error`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config(token = "0123456789abcde"))

            assertTrue((provider.status.value as TunnelStatus.Error).message.contains("16"))
        }

    @Test
    fun `start with https url sets error`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            val config = ServerConfig(relayUrl = "https://127.0.0.1", relayDeviceToken = RELAY_TEST_TOKEN)
            provider.start(server.port, config)

            assertTrue((provider.status.value as TunnelStatus.Error).message.contains("ws://"))
        }

    @Test
    fun `start connects with bearer and publishes http public url`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config())

            assertEquals(
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("http://127.0.0.1:${server.port}", valid = true)),
                    providerType = TunnelProviderType.RELAY,
                ),
                provider.status.value,
            )
            assertEquals(listOf("Bearer $RELAY_TEST_TOKEN"), server.authorizationHeaders)
        }

    @Test
    fun `request frame is replayed to local server and answered`() {
        val reply = CompletableDeferred<String>()
        val request =
            requestJson(
                id = "r1",
                method = "POST",
                path = "/mcp?x=1",
                headers = mapOf("X-Test" to listOf("1"), "Content-Type" to listOf("application/json")),
                body = """{"hello":"world"}""",
            )
        withRelay(
            deviceHandler = {
                send(Frame.Text(request))
                reply.complete(nextText())
                awaitClientClose()
            },
            localRoutes = {
                post("/mcp") {
                    call.response.header("Mcp-Session-Id", "abc")
                    val echo = call.receiveText() + " via " + call.request.header("X-Test")
                    call.respondText(echo, ContentType.Application.Json)
                }
            },
        ) { server, provider ->
            provider.start(server.port, server.config())

            val response = decodeResponse(withTimeout(WAIT_MS) { reply.await() })
            assertEquals("r1", response.id)
            assertEquals(200, response.status)
            assertEquals(listOf("abc"), response.headers["Mcp-Session-Id"])
            assertFalse(response.headers.keys.any { it.equals("content-length", ignoreCase = true) })
            assertEquals("""{"hello":"world"} via 1""", decodedBody(response))
        }
    }

    @Test
    fun `redirect from local server is passed through`() {
        val reply = CompletableDeferred<String>()
        withRelay(
            deviceHandler = {
                send(Frame.Text(requestJson("r1", "GET", "/redirect")))
                reply.complete(nextText())
                awaitClientClose()
            },
            localRoutes = { get("/redirect") { call.respondRedirect("/elsewhere") } },
        ) { server, provider ->
            provider.start(server.port, server.config())

            val response = decodeResponse(withTimeout(WAIT_MS) { reply.await() })
            assertEquals(302, response.status)
            assertEquals(listOf("/elsewhere"), response.headers["Location"])
        }
    }

    @Test
    fun `ping frame is answered with pong`() {
        val reply = CompletableDeferred<String>()
        withRelay(
            deviceHandler = {
                send(Frame.Text("""{"type":"ping"}"""))
                reply.complete(nextText())
                awaitClientClose()
            },
        ) { server, provider ->
            provider.start(server.port, server.config())

            assertEquals("""{"type":"pong"}""", withTimeout(WAIT_MS) { reply.await() })
        }
    }

    @Test
    fun `malformed and unknown frames are ignored and session stays alive`() {
        val reply = CompletableDeferred<String>()
        withRelay(
            deviceHandler = {
                send(Frame.Text("not json"))
                send(Frame.Text("""{"type":"hello"}"""))
                send(Frame.Text("""{"type":"ping"}"""))
                reply.complete(nextText())
                awaitClientClose()
            },
        ) { server, provider ->
            provider.start(server.port, server.config())

            assertEquals("""{"type":"pong"}""", withTimeout(WAIT_MS) { reply.await() })
            assertTrue(provider.status.value is TunnelStatus.Connected)
        }
    }

    @Test
    fun `request to unreachable local port yields 502 frame`() {
        val reply = CompletableDeferred<String>()
        withRelay(
            deviceHandler = {
                send(Frame.Text(requestJson("r1", "GET", "/health")))
                reply.complete(nextText())
                awaitClientClose()
            },
        ) { server, provider ->
            provider.start(closedPort(), server.config())

            assertEquals(502, decodeResponse(withTimeout(WAIT_MS) { reply.await() }).status)
        }
    }

    @Test
    fun `ninth concurrent request gets 503 while eight are in flight`() {
        val replies = Channel<RelayResponseFrame>(Channel.UNLIMITED)
        val sendNinth = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val routeHits = AtomicInteger(0)
        withRelay(
            deviceHandler = {
                repeat(SLOTS) { send(Frame.Text(requestJson("r$it", "GET", "/slow"))) }
                val reader =
                    launch {
                        for (frame in incoming) {
                            replies.send(decodeResponse((frame as Frame.Text).readText()))
                        }
                    }
                sendNinth.await()
                send(Frame.Text(requestJson("r$SLOTS", "GET", "/slow")))
                reader.join()
            },
            localRoutes = {
                get("/slow") {
                    routeHits.incrementAndGet()
                    release.await()
                    call.respondText("done")
                }
            },
        ) { server, provider ->
            provider.start(server.port, server.config())
            awaitCondition { routeHits.get() == SLOTS }
            sendNinth.complete(Unit)

            val refused = withTimeout(WAIT_MS) { replies.receive() }
            assertEquals("r$SLOTS", refused.id)
            assertEquals(503, refused.status)

            release.complete(Unit)
            repeat(SLOTS) { assertEquals(200, withTimeout(WAIT_MS) { replies.receive() }.status) }
        }
    }

    @Test
    fun `handshake rejected with 401 reports error and retries`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config(token = "wrong-token-0123456789"))

            val message = awaitError(provider)
            assertTrue(message.contains("401"), message)
            assertTrue(message.contains("reconnecting"), message)
            awaitCondition { server.handshakeAttempts.get() >= 2 }
        }

    @Test
    fun `relay closing the socket triggers reconnect`() =
        withRelay(deviceHandler = { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }) { server, provider ->
            provider.start(server.port, server.config())

            val message = awaitError(provider)
            assertTrue(message.contains("Relay connection closed"), message)
            assertTrue(message.contains("reconnecting in 1s"), message)
            awaitCondition { server.handshakeAttempts.get() >= 2 }
        }

    @Test
    fun `relay replacing the device waits the maximum backoff`() =
        withRelay(
            deviceHandler = { close(CloseReason(RelaySession.CLOSE_CODE_REPLACED, "replaced")) },
        ) { server, provider ->
            provider.start(server.port, server.config())

            val message = awaitError(provider)
            assertTrue(message.contains("replaced"), message)
            assertTrue(message.contains("reconnecting in 30s"), message)
            delay(SETTLE_MS)
            assertEquals(1, server.handshakeAttempts.get())
        }

    @Test
    fun `silent relay is redialled after idle timeout`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.idleTimeoutMs = 300
            provider.start(server.port, server.config())

            val message = awaitError(provider)
            assertTrue(message.contains("silent"), message)
            awaitCondition { server.handshakeAttempts.get() >= 2 }
        }

    @Test
    fun `connection failure through injected client reports error`() =
        runBlocking {
            val provider = RelayTunnelProvider()
            provider.clientProvider = {
                HttpClient(MockEngine { throw IOException("boom") }) { install(WebSockets) }
            }
            try {
                provider.start(1, ServerConfig(relayUrl = "ws://127.0.0.1:1", relayDeviceToken = RELAY_TEST_TOKEN))

                val message = awaitError(provider)
                assertTrue(message.contains("boom"), message)
                assertTrue(message.contains("reconnecting"), message)
            } finally {
                provider.stop()
            }
        }

    @Test
    fun `start twice throws`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config())

            val error = runCatching { provider.start(server.port, server.config()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("Tunnel is already running", error?.message)
        }

    @Test
    fun `stop closes session and sets Disconnected`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.start(server.port, server.config())
            awaitCondition { server.openSessions.get() == 1 }

            provider.stop()

            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            awaitCondition { server.openSessions.get() == 0 }
        }

    @Test
    fun `stop during handshake leaves no open device socket`() =
        withRelay(deviceHandler = { awaitClientClose() }, handshakeDelayMs = 1_000) { server, provider ->
            val starter = launch { provider.start(server.port, server.config()) }
            delay(START_GRACE_MS)

            provider.stop()
            starter.join()

            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            delay(SETTLE_MS)
            assertEquals(0, server.openSessions.get())
            assertEquals(1, server.handshakeAttempts.get())
        }

    @Test
    fun `cancelling a pending start stops the provider`() =
        withRelay(deviceHandler = { awaitClientClose() }, handshakeDelayMs = 1_000) { server, provider ->
            val starter = launch { provider.start(server.port, server.config()) }
            delay(START_GRACE_MS)

            starter.cancelAndJoin()

            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            delay(SETTLE_MS)
            assertEquals(1, server.handshakeAttempts.get())
        }

    @Test
    fun `stop when not running is no-op`() =
        withRelay(deviceHandler = { awaitClientClose() }) { server, provider ->
            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)

            provider.start(server.port, server.config())
            provider.stop()
            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }

    @Test
    fun `backoff ladder`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { RelayTunnelProvider.backoffMs(it) },
        )
    }

    @Test
    fun `failure count resets only after a stable session`() {
        assertEquals(5, RelayTunnelProvider.nextFailureCount(4, 0))
        assertEquals(5, RelayTunnelProvider.nextFailureCount(4, RelayTunnelProvider.MIN_STABLE_SESSION_MS - 1))
        assertEquals(1, RelayTunnelProvider.nextFailureCount(4, RelayTunnelProvider.MIN_STABLE_SESSION_MS))
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
        const val SETTLE_MS = 2_000L
        const val START_GRACE_MS = 200L
        const val SLOTS = 8
    }
}
