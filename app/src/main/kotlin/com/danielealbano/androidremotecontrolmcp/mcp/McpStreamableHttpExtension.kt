package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MCP:StreamableHttp"
private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

/**
 * Local implementation of the Streamable HTTP Ktor extension (JSON-only mode).
 *
 * The MCP Kotlin SDK v0.8.3 ships [StreamableHttpServerTransport] but does not
 * yet include a Ktor convenience extension for it (only available on the SDK
 * `main` branch). This function bridges the gap.
 *
 * Uses `enableJsonResponse = true` so all responses are standard HTTP JSON —
 * no SSE is used. This ensures compatibility with cloudflared tunnels and
 * standard HTTP reverse proxies.
 *
 * Sets up three endpoints at `/mcp`:
 * - **POST** — creates a new session (first request) or dispatches to an existing one; returns JSON
 * - **GET** — returns 405 Method Not Allowed (SSE not supported in JSON-only mode)
 * - **DELETE** — terminates a session
 *
 * **Prerequisite:** Caller MUST install `ContentNegotiation` with `json(McpJson)` before calling
 * this function. The `StreamableHttpServerTransport` uses `call.respond(JSONRPCResponse/Error)`
 * internally, which requires ContentNegotiation for serialization.
 *
 * @param block Factory that creates a [Server] instance. Called once per new session.
 */
fun Application.mcpStreamableHttp(block: () -> Server) {
    val transports = ConcurrentHashMap<String, StreamableHttpServerTransport>()

    routing {
        route("/mcp") {
            // POST — new session or message to existing session (JSON responses)
            post {
                val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
                val transport: StreamableHttpServerTransport

                if (sessionId != null) {
                    // Existing session
                    transport = transports[sessionId] ?: run {
                        call.respondText(
                            "Session not found",
                            status = HttpStatusCode.NotFound,
                        )
                        return@post
                    }
                } else {
                    // New session — create transport + server
                    transport = StreamableHttpServerTransport(enableJsonResponse = true)

                    transport.setOnSessionInitialized { initializedSessionId ->
                        transports[initializedSessionId] = transport
                        Log.d(TAG, "Session initialized: $initializedSessionId")
                    }

                    transport.setOnSessionClosed { closedSessionId ->
                        transports.remove(closedSessionId)
                        Log.d(TAG, "Session closed: $closedSessionId")
                    }

                    val server = block()
                    server.onClose {
                        transport.sessionId?.let { transports.remove(it) }
                        Log.d(TAG, "Server connection closed for sessionId: ${transport.sessionId}")
                    }
                    server.createSession(transport)
                }

                // session=null because we're in JSON-only mode (no ServerSSESession)
                transport.handlePostRequest(null, call)
            }

            // GET — not supported in JSON-only mode (no SSE)
            get {
                call.respondText(
                    "Method Not Allowed: SSE not supported, use POST",
                    status = HttpStatusCode.MethodNotAllowed,
                )
            }

            // DELETE — terminate session
            delete {
                val transport = lookupTransport(call, transports) ?: return@delete
                transport.handleDeleteRequest(call)
            }
        }
    }
}

private suspend fun lookupTransport(
    call: ApplicationCall,
    transports: ConcurrentHashMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? {
    val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    if (sessionId.isNullOrEmpty()) {
        call.respondText(
            "Bad Request: No valid session ID provided",
            status = HttpStatusCode.BadRequest,
        )
        return null
    }
    return transports[sessionId] ?: run {
        call.respondText(
            "Session not found",
            status = HttpStatusCode.NotFound,
        )
        null
    }
}
