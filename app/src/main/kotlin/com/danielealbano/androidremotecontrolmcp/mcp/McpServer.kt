package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.auth.BearerTokenAuthPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
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
        // JSON serialization — required by StreamableHttpServerTransport
        // which uses call.respond(JSONRPCResponse/Error) internally
        install(ContentNegotiation) {
            json(McpJson)
        }

        // Global bearer token authentication (all requests)
        install(BearerTokenAuthPlugin) {
            expectedToken = config.bearerToken
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
