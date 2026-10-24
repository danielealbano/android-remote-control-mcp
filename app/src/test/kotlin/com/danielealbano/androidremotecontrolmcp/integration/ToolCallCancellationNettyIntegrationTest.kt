package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.installMcpBasePlugins
import com.danielealbano.androidremotecontrolmcp.mcp.installMcpStatelessTransport
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LoggedToolRegistrar
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * The transport on the real Netty engine: a client giving up on its request (closing the connection, or resetting
 * its HTTP/2 stream) cancels the tool call the request runs. The in-process test engine has no connection to close.
 */
@DisplayName("Tool call cancellation on the Netty engine")
class ToolCallCancellationNettyIntegrationTest {
    private val inFlightToolCalls = InFlightToolCalls()
    private val started = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private lateinit var sdk: Server
    private val serverLog = RecordingServerLogRepository()

    @AfterEach
    fun tearDown() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
    }

    private fun startServer(h2c: Boolean): Int {
        sdk =
            Server(
                Implementation(name = "test", version = "1"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            )
        val registrar = LoggedToolRegistrar(sdk, serverLog, inFlightToolCalls = inFlightToolCalls)
        registrar.addTool("slow", "slow", "runs until cancelled", ToolSchema()) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
        registrar.addTool("fast", "fast", "answers at once", ToolSchema()) {
            CallToolResult(content = listOf(TextContent(text = "done")))
        }
        val engine =
            embeddedServer(
                Netty,
                configure = {
                    connector {
                        host = LOOPBACK
                        port = 0
                    }
                    if (h2c) {
                        enableHttp2 = true
                        enableH2c = true
                    }
                },
            ) {
                installMcpBasePlugins { bearerTokenEnabled = false }
                installMcpStatelessTransport(inFlightToolCalls = inFlightToolCalls) { sdk }
            }.start(wait = false)
        server = engine
        return runBlocking {
            engine.engine
                .resolvedConnectors()
                .first()
                .port
        }
    }

    private fun call(
        client: OkHttpClient,
        port: Int,
        tool: String,
    ): Call =
        client.newCall(
            Request
                .Builder()
                .url("http://$LOOPBACK:$port/mcp")
                .header("Accept", "application/json, text/event-stream")
                .post(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}"""
                        .toRequestBody("application/json".toMediaType()),
                ).build(),
        )

    /** Starts the slow call, waits for the tool to run, cancels the HTTP call, and waits for the tool to stop. */
    private fun cancelRunningCall(
        client: OkHttpClient,
        port: Int,
    ) {
        val slow = call(client, port, "slow")
        thread { runCatching { slow.execute().close() } }
        runBlocking { withTimeout(WAIT_MS) { started.await() } }
        // The running request's session, which must be gone once the request is cancelled
        assertEquals(1, sdk.sessions.size)

        slow.cancel()

        runBlocking { withTimeout(WAIT_MS) { cancelled.await() } }
    }

    /** The cancelled call is logged as cancelled once it has unwound. */
    private fun assertSlowLoggedAsCancelled() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (slowEntries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals(listOf("cancelled"), slowEntries().map { it.message })
    }

    private fun slowEntries() = serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).filter { it.toolName == "slow" }

    /** Every request's session is closed once the request ends, a cancelled one included: none is left behind. */
    private fun assertNoSessionLeft() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (sdk.sessions.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals(emptyMap<String, Any>(), sdk.sessions)
    }

    @Test
    fun `a client closing its HTTP 1_1 connection cancels the running tool call`() {
        val port = startServer(h2c = false)

        cancelRunningCall(OkHttpClient(), port)

        assertTrue(cancelled.isCompleted)
        assertSlowLoggedAsCancelled()
        assertNoSessionLeft()
    }

    @Test
    fun `a client resetting its HTTP 2 stream cancels the running tool call and keeps the connection usable`() {
        val port = startServer(h2c = true)
        val client = OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()

        cancelRunningCall(client, port)

        call(client, port, "fast").execute().use { response ->
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol)
            assertTrue(response.body.string().contains("done"))
        }
        assertEquals(1, client.connectionPool.connectionCount())
        assertNoSessionLeft()
    }

    @Test
    fun `requests that complete are answered and leave no session behind`() {
        val port = startServer(h2c = false)
        val client = OkHttpClient()

        call(client, port, "fast").execute().use { response -> assertTrue(response.body.string().contains("done")) }
        client.connectionPool.evictAll()

        call(client, port, "fast").execute().use { response -> assertTrue(response.body.string().contains("done")) }
        assertNoSessionLeft()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
        const val STOP_GRACE_MS = 100L
        const val STOP_TIMEOUT_MS = 1_000L
    }
}
