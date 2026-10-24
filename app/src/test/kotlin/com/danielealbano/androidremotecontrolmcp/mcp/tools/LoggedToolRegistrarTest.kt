package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpCaller
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private typealias ToolHandler = suspend (CallToolRequest) -> CallToolResult

/** The handler [LoggedToolRegistrar.addTool] registers: logging around cancellation, as the SDK invokes it. */
@DisplayName("LoggedToolRegistrar")
class LoggedToolRegistrarTest {
    private val sdk =
        Server(
            Implementation(name = "test", version = "1"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
    private val serverLog = RecordingServerLogRepository()
    private val calls = InFlightToolCalls()
    private val caller = McpCaller.of("Bearer client-token")
    private val requestId = RequestId(42L)
    private val request = mockk<CallToolRequest>()
    private val connection = mockk<ClientConnection>(relaxed = true).also { every { it.sessionId } returns SESSION_ID }
    private val ok = CallToolResult(content = listOf(TextContent(text = "ok")))

    // The MCP transport binds every request's session to its caller
    private val unbind = calls.bindSession(SESSION_ID, caller)

    /** Registers [tool] and returns the handler the SDK holds for it, as the request's session invokes it. */
    private fun register(tool: ToolHandler): ToolHandler {
        LoggedToolRegistrar(sdk, serverLog, ToolCallIndicator.NONE, calls) { requestId }
            .addTool("tap", "android_tap", "taps", ToolSchema(), tool)
        val registered = sdk.tools.getValue("android_tap").handler
        return { registered(connection, it) }
    }

    private fun loggedMarkers() = serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).map { it.message }

    @Test
    fun `a call cancelled by the client is answered and logged as cancelled`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val handler =
                register {
                    started.complete(Unit)
                    awaitCancellation()
                }

            // No caller in the context: the call finds it through its session
            val answer = async { handler(request) }
            started.await()
            calls.cancel(caller.id, requestId, "user stop")

            assertEquals(CANCELLED_BY_CLIENT_RESULT, answer.await())
            assertEquals(listOf("cancelled"), loggedMarkers())
        }

    @Test
    fun `a request cancelled before its tool runs is still logged as cancelled`() =
        runTest {
            val handler = register { ok }

            val call =
                launch {
                    cancel()
                    handler(request)
                }
            call.join()

            assertEquals(listOf("cancelled"), loggedMarkers())
        }

    @Test
    fun `a request cancelled while its tool runs is logged as cancelled`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val handler =
                register {
                    started.complete(Unit)
                    awaitCancellation()
                }

            val call = launch { handler(request) }
            started.await()
            call.cancelAndJoin()

            assertEquals(listOf("cancelled"), loggedMarkers())
        }

    @Test
    fun `a call that ends by itself is logged as before`() =
        runTest {
            val handler = register { ok }

            assertEquals(ok, handler(request))

            assertEquals(listOf(""), loggedMarkers())
        }

    @Test
    fun `a call of a session no request binds runs without being tracked`() =
        runTest {
            var outcome: InFlightToolCalls.Outcome? = null
            val handler =
                register {
                    outcome = calls.cancel(caller.id, requestId, "user stop")
                    ok
                }
            unbind()

            assertEquals(ok, handler(request))

            assertEquals(InFlightToolCalls.Outcome.UNKNOWN, outcome)
            assertEquals(listOf(""), loggedMarkers())
        }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
