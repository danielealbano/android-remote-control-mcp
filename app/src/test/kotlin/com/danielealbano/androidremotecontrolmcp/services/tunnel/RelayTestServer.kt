package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal const val RELAY_TEST_TOKEN = "device-token-0123456789"

/**
 * In-process stand-in for the relay AND the local MCP server: a Netty server on an ephemeral port that
 * answers the bearer-gated `/device` WebSocket with [deviceHandler] and serves [localRoutes] over plain HTTP.
 * One server plays both roles so a test can pass the same port as `localPort` the provider replays to.
 * A `/device` handshake without the expected bearer is answered `401` before the upgrade; an accepted
 * handshake is delayed by [handshakeDelayMs] (0 = none) so cancellation mid-handshake can be exercised.
 * Real sockets (like `EventDispatcherImplTest`) so the production OkHttp WebSocket engine is what gets tested.
 */
internal class RelayTestServer(
    private val deviceHandler: suspend DefaultWebSocketServerSession.() -> Unit,
    private val localRoutes: Route.() -> Unit = {},
    private val handshakeDelayMs: Long = 0,
) {
    val handshakeAttempts = AtomicInteger(0)
    val openSessions = AtomicInteger(0)
    val authorizationHeaders = CopyOnWriteArrayList<String?>()

    var port: Int = 0
        private set

    private val server =
        embeddedServer(Netty, port = 0) {
            install(WebSockets)
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() == RelayTarget.DEVICE_PATH) {
                    handshakeAttempts.incrementAndGet()
                    val authorization = call.request.header(HttpHeaders.Authorization)
                    authorizationHeaders += authorization
                    if (authorization != "Bearer $RELAY_TEST_TOKEN") {
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                    } else if (handshakeDelayMs > 0) {
                        delay(handshakeDelayMs)
                    }
                }
            }
            routing {
                webSocket(RelayTarget.DEVICE_PATH) {
                    openSessions.incrementAndGet()
                    try {
                        deviceHandler()
                    } finally {
                        openSessions.decrementAndGet()
                    }
                }
                localRoutes()
            }
        }

    suspend fun start() {
        server.start(wait = false)
        port =
            server.engine
                .resolvedConnectors()
                .first()
                .port
    }

    fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)

    fun config(token: String = RELAY_TEST_TOKEN) =
        ServerConfig(
            relayUrl = "ws://127.0.0.1:$port",
            relayDeviceToken = token,
        )
}
