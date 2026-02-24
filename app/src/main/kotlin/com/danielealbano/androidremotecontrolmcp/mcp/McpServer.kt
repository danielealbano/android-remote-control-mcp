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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor-based MCP server (HTTP by default, optional HTTPS).
 *
 * Configures and runs an embedded Netty server with:
 * - HTTP by default, optional HTTPS when enabled in settings
 * - JSON content negotiation (required by SDK's StreamableHttpServerTransport)
 * - Global bearer token authentication
 * - MCP Streamable HTTP transport at `/mcp` (JSON-only mode, no SSE)
 *
 * @param config The server configuration (port, binding address, bearer token).
 * @param keyStore The SSL KeyStore for HTTPS (null when HTTPS is disabled).
 * @param keyStorePassword The KeyStore password (null when HTTPS is disabled).
 * @param mcpSdkServer The MCP SDK Server instance with registered tools.
 */
class McpServer(
    private val config: ServerConfig,
    private val keyStore: KeyStore?,
    private val keyStorePassword: CharArray?,
    private val mcpSdkServer: io.modelcontextprotocol.kotlin.sdk.server.Server,
) {
    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val running = AtomicBoolean(false)

    /**
     * Starts the server. Non-blocking — the server runs on its own threads.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Server is already running, ignoring start request")
            return
        }

        Log.i(TAG, "Starting MCP server on ${config.bindingAddress.address}:${config.port}")

        try {
            server =
                if (config.httpsEnabled && keyStore != null && keyStorePassword != null) {
                    createHttpsServer()
                } else {
                    createHttpServer()
                }

            server?.start(wait = false)
            Log.i(TAG, "MCP server started successfully")
        } catch (e: Exception) {
            server = null
            running.set(false)
            Log.e(TAG, "Failed to start MCP server", e)
            throw e
        }
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
        if (!running.compareAndSet(true, false)) {
            Log.w(TAG, "Server is not running, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping MCP server (grace=${gracePeriodMillis}ms, timeout=${timeoutMillis}ms)")
        try {
            runBlocking { withTimeout(timeoutMillis) { mcpSdkServer.close() } }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "MCP SDK server close did not complete within ${timeoutMillis}ms", e)
        }
        server?.stop(gracePeriodMillis, timeoutMillis)
        server = null
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

    private fun createHttpsServer(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val ks = requireNotNull(keyStore) { "KeyStore must not be null when HTTPS is enabled" }
        val ksPassword = requireNotNull(keyStorePassword) { "KeyStore password must not be null when HTTPS is enabled" }
        return embeddedServer(
            factory = Netty,
            configure = {
                sslConnector(
                    keyStore = ks,
                    keyAlias = CertificateManager.KEY_ALIAS,
                    keyStorePassword = { ksPassword },
                    privateKeyPassword = { ksPassword },
                ) {
                    host = config.bindingAddress.address
                    port = config.port
                }
            },
            module = { configureApplication() },
        )
    }

    private fun io.ktor.server.application.Application.configureApplication() {
        // JSON serialization — required by StreamableHttpServerTransport
        // which uses call.respond(JSONRPCResponse/Error) internally
        install(ContentNegotiation) {
            json(McpJson)
        }

        // Global bearer token authentication (all requests except /health)
        install(BearerTokenAuthPlugin) {
            expectedToken = config.bearerToken
            excludedPaths = setOf("/health")
        }

        // Health check endpoint — unauthenticated, installed before MCP routes.
        // BearerTokenAuthPlugin skips paths matching "/health" (no auth required).
        routing {
            get("/health") {
                call.respondText(
                    """{"status":"healthy","version":"${BuildConfig.VERSION_NAME}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
        }

        // MCP Streamable HTTP transport at /mcp (JSON-only mode, no SSE)
        mcpStreamableHttp {
            mcpSdkServer
        }
    }

    companion object {
        private const val TAG = "MCP:McpServer"
        private const val DEFAULT_GRACE_PERIOD_MS = 1000L
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }
}
