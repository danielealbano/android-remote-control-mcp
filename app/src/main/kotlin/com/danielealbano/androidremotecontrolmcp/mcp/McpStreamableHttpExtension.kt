package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MCP:StreamableHttp"
private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MAX_SESSIONS = 100
private const val SESSION_CLEANUP_INTERVAL_MS = 60_000L
private const val SESSION_IDLE_TIMEOUT_MS = 300_000L

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
 * @param block Provider that returns the [Server] instance for a session.
 *   May return the same instance across sessions.
 */
@Suppress("LongMethod")
fun Application.mcpStreamableHttp(block: () -> Server) {
    val transports = ConcurrentHashMap<String, StreamableHttpServerTransport>()
    val lastActivityTimes = ConcurrentHashMap<String, Long>()
    val activeSessionCount = AtomicInteger(0)
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Cancel cleanup scope when the Ktor application stops
    monitor.subscribe(ApplicationStopped) {
        cleanupScope.cancel()
    }

    // Background coroutine to clean up idle sessions
    cleanupScope.launch {
        while (true) {
            delay(SESSION_CLEANUP_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val staleSessionIds =
                lastActivityTimes.entries
                    .filter { (_, lastActivity) -> now - lastActivity > SESSION_IDLE_TIMEOUT_MS }
                    .map { it.key }

            for (sessionId in staleSessionIds) {
                val transport = transports.remove(sessionId)
                lastActivityTimes.remove(sessionId)
                if (transport != null) {
                    activeSessionCount.decrementAndGet()
                    Log.i(TAG, "Closing idle session: $sessionId")
                    transport.close()
                }
            }
        }
    }

    routing {
        route("/mcp") {
            // POST — new session or message to existing session (JSON responses)
            post {
                val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
                val transport: StreamableHttpServerTransport

                if (sessionId != null) {
                    // Existing session — update activity timestamp
                    transport = transports[sessionId] ?: run {
                        call.respondText(
                            """{"error":"Session not found"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound,
                        )
                        return@post
                    }
                    lastActivityTimes[sessionId] = System.currentTimeMillis()
                } else {
                    // Atomically reserve a session slot to prevent TOCTOU race
                    if (activeSessionCount.incrementAndGet() > MAX_SESSIONS) {
                        activeSessionCount.decrementAndGet()
                        Log.w(TAG, "Session limit reached ($MAX_SESSIONS), rejecting new session")
                        call.respondText(
                            """{"error":"Too many active sessions"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.ServiceUnavailable,
                        )
                        return@post
                    }

                    // New session — create transport + server
                    transport = StreamableHttpServerTransport(enableJsonResponse = true)

                    transport.setOnSessionInitialized { initializedSessionId ->
                        transports[initializedSessionId] = transport
                        lastActivityTimes[initializedSessionId] = System.currentTimeMillis()
                        Log.d(TAG, "Session initialized: $initializedSessionId")
                    }

                    transport.setOnSessionClosed { closedSessionId ->
                        transports.remove(closedSessionId)
                        lastActivityTimes.remove(closedSessionId)
                        activeSessionCount.decrementAndGet()
                        Log.d(TAG, "Session closed: $closedSessionId")
                    }

                    val server = block()
                    server.onClose {
                        transport.sessionId?.let {
                            if (transports.remove(it) != null) {
                                lastActivityTimes.remove(it)
                                activeSessionCount.decrementAndGet()
                            }
                        }
                        Log.d(TAG, "Server connection closed for sessionId: ${transport.sessionId}")
                    }
                    server.createSession(transport)
                }

                // session=null because we're in JSON-only mode (no ServerSSESession)
                transport.handlePostRequest(null, call)
            }

            // GET — not supported in JSON-only mode (no SSE)
            get {
                call.response.header(HttpHeaders.Allow, "POST, DELETE")
                call.respondText(
                    """{"error":"Method Not Allowed: SSE not supported, use POST"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.MethodNotAllowed,
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
            """{"error":"Bad Request: No valid session ID provided"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return null
    }
    return transports[sessionId] ?: run {
        call.respondText(
            """{"error":"Session not found"}""",
            ContentType.Application.Json,
            HttpStatusCode.NotFound,
        )
        null
    }
}
