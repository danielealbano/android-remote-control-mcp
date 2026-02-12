package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.auth.BearerTokenAuthPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor-based MCP server (HTTP by default, optional HTTPS).
 *
 * Configures and runs an embedded Netty server with:
 * - HTTP by default, optional HTTPS when enabled in settings
 * - JSON content negotiation (kotlinx.serialization)
 * - Bearer token authentication on `/mcp` routes
 * - Health check endpoint (unauthenticated)
 * - MCP JSON-RPC 2.0 protocol routes
 * - Status pages for error handling
 *
 * @param config The server configuration (port, binding address, bearer token).
 * @param keyStore The SSL KeyStore for HTTPS (null when HTTPS is disabled).
 * @param keyStorePassword The KeyStore password (null when HTTPS is disabled).
 * @param protocolHandler The MCP protocol handler for JSON-RPC processing.
 */
class McpServer(
    private val config: ServerConfig,
    private val keyStore: KeyStore?,
    private val keyStorePassword: CharArray?,
    private val protocolHandler: McpProtocolHandler,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val running = AtomicBoolean(false)

    /**
     * Starts the server. Non-blocking — the server runs on its own threads.
     */
    fun start() {
        if (running.get()) {
            Log.w(TAG, "Server is already running, ignoring start request")
            return
        }

        Log.i(TAG, "Starting MCP server on ${config.bindingAddress.address}:${config.port}")

        server =
            if (config.httpsEnabled && keyStore != null && keyStorePassword != null) {
                createHttpsServer()
            } else {
                createHttpServer()
            }

        server?.start(wait = false)
        running.set(true)
        Log.i(TAG, "MCP server started successfully")
    }

    /**
     * Stops the server gracefully, waiting for in-flight requests.
     *
     * @param gracePeriodMillis Grace period before force-stopping connections.
     * @param timeoutMillis Maximum time to wait for shutdown.
     */
    fun stop(
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MS,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ) {
        if (!running.get()) {
            Log.w(TAG, "Server is not running, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping MCP server (grace=${gracePeriodMillis}ms, timeout=${timeoutMillis}ms)")
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
        running.set(false)
        Log.i(TAG, "MCP server stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun createHttpServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            factory = Netty,
            port = config.port,
            host = config.bindingAddress.address,
            module = { configureApplication() },
        )

    @Suppress("SpreadOperator")
    private fun createHttpsServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            factory = Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore!!,
                    keyAlias = CertificateManager.KEY_ALIAS,
                    keyStorePassword = { keyStorePassword!! },
                    privateKeyPassword = { keyStorePassword!! },
                ) {
                    host = config.bindingAddress.address
                    port = config.port
                }
            },
            module = { configureApplication() },
        )

    private fun io.ktor.server.application.Application.configureApplication() {
        configurePlugins()
        configureRouting()
    }

    private fun io.ktor.server.application.Application.configurePlugins() {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = false
                    isLenient = false
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "Unhandled exception in request handler", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    buildJsonObject {
                        put("error", "internal_server_error")
                        put("message", cause.message ?: "Unknown error")
                    },
                )
            }
        }
    }

    private fun io.ktor.server.application.Application.configureRouting() {
        routing {
            // Health endpoint — unauthenticated
            get("/health") {
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text =
                        Json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("status", "healthy")
                                put("version", BuildConfig.VERSION_NAME)
                                put("server", "running")
                            },
                        ),
                )
            }

            // MCP routes — authenticated
            route("/mcp") {
                install(BearerTokenAuthPlugin) {
                    expectedToken = config.bearerToken
                }

                route("/v1") {
                    post("/initialize") {
                        val request = call.receive<JsonRpcRequest>()
                        val response = protocolHandler.handleRequest(request)
                        call.respond(response)
                    }

                    get("/tools/list") {
                        val idParam = call.request.queryParameters["id"]
                        val requestId: kotlinx.serialization.json.JsonElement? =
                            idParam?.let { JsonPrimitive(it) }
                        val request =
                            JsonRpcRequest(
                                method = McpProtocolHandler.METHOD_TOOLS_LIST,
                                id = requestId,
                            )
                        val response = protocolHandler.handleRequest(request)
                        call.respond(response)
                    }

                    post("/tools/call") {
                        val request = call.receive<JsonRpcRequest>()
                        val response = protocolHandler.handleRequest(request)
                        call.respond(response)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MCP:McpServer"
        private const val DEFAULT_GRACE_PERIOD_MS = 1000L
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }
}
