package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpCaller
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.currentRequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val NANOS_PER_MILLI = 1_000_000L
private const val FAILED_MARKER = "failed"
private const val CANCELLED_MARKER = "cancelled"

// The states of a call in cancellableToolHandler: its tool runs, ended without a client cancellation (returned,
// failed, or its request was cancelled), or was cancelled by the client
private const val RUNNING = 0
private const val ENDED = 1
private const val CANCELLED_BY_CLIENT = 2

/** The text of the answer to a call the client cancelled with `notifications/cancelled` (the client ignores it). */
internal const val CANCELLED_BY_CLIENT_MESSAGE = "The client cancelled this tool call"

/** The one answer to a call the client cancelled; [loggedToolHandler] recognizes it by identity. */
internal val CANCELLED_BY_CLIENT_RESULT =
    CallToolResult(content = listOf(TextContent(text = CANCELLED_BY_CLIENT_MESSAGE)), isError = true)

/**
 * Runs [handler] so that a `notifications/cancelled` naming this call cancels it: while it runs, the call is tracked in
 * [inFlightToolCalls] by its [McpCaller] and request id. A call cancelled that way is answered with
 * [CANCELLED_BY_CLIENT_RESULT], so its request still gets an answer, whatever the tool made of the cancellation (many
 * tools turn any exception into an error of their own); a cancellation arriving once the tool has ended changes
 * nothing. A failure without such a cancellation propagates unchanged, and when the request itself is cancelled (the
 * client closed the connection, or the server stops) the cancellation propagates. Without a caller or request id
 * (outside the MCP transport) [handler] just runs. [currentRequestId] reads the JSON-RPC id of the request being
 * handled, which the SDK puts in the coroutine context.
 */
internal fun cancellableToolHandler(
    inFlightToolCalls: InFlightToolCalls,
    currentRequestId: suspend () -> RequestId? = { currentRequestHandlerExtra()?.requestId },
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        val caller = currentCoroutineContext()[McpCaller]
        val requestId = currentRequestId()
        if (caller == null || requestId == null) {
            handler(request)
        } else {
            // A supervisor scope, so the tool's failure stays in its Deferred instead of failing the scope
            supervisorScope {
                val state = AtomicInteger(RUNNING)
                // Undispatched: the child tracks the call and starts the tool at once in this thread, as the tool ran
                // before, with no dispatch delay in which a cancellation could miss it
                val call =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val self = currentCoroutineContext().job
                        val untrack =
                            inFlightToolCalls.track(caller.id, requestId) { reason ->
                                // Only a running tool is cancelled: one that already ended, or was already
                                // cancelled by the client, keeps its outcome
                                val cancelling = state.compareAndSet(RUNNING, CANCELLED_BY_CLIENT)
                                if (cancelling) {
                                    val because = reason ?: "no reason"
                                    self.cancel(CancellationException("Cancelled by the client: $because"))
                                }
                                cancelling
                            }
                        try {
                            handler(request)
                        } finally {
                            state.compareAndSet(RUNNING, ENDED)
                            untrack()
                        }
                    }
                // Throws only when the request itself is cancelled, letting that through
                call.join()
                if (state.get() == CANCELLED_BY_CLIENT) CANCELLED_BY_CLIENT_RESULT else call.await()
            }
        }
    }

/**
 * Wraps a tool handler so every invocation records a TOOL_CALL server-log entry: un-prefixed tool
 * name + duration, plus the constant NON-SENSITIVE [FAILED_MARKER] on failure (isError result or
 * thrown exception) or [CANCELLED_MARKER] when the invocation was cancelled: its request was (its
 * coroutine is no longer active in the finally block), or the client cancelled the call (the
 * answer is [CANCELLED_BY_CLIENT_RESULT]). Parameters and free-form error text are NEVER logged
 * (tool errors can echo device-derived data such as URLs). try/finally keeps the wrapper fully
 * transparent — NO exception is caught (and no linting suppression is needed).
 */
internal fun loggedToolHandler(
    serverLog: ServerLogRepository,
    toolName: String,
    toolCallIndicator: ToolCallIndicator = ToolCallIndicator.NONE,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        val startNs = System.nanoTime()
        var completed: CallToolResult? = null
        runCatching { toolCallIndicator.onToolCallStarted(toolName) }
        try {
            val result = handler(request)
            completed = result
            result
        } finally {
            runCatching { toolCallIndicator.onToolCallFinished(toolName) }
            val result = completed
            serverLog.log(
                type = ServerLogEntry.Type.TOOL_CALL,
                message =
                    when {
                        !currentCoroutineContext().isActive || result === CANCELLED_BY_CLIENT_RESULT -> CANCELLED_MARKER
                        result == null || result.isError == true -> FAILED_MARKER
                        else -> ""
                    },
                toolName = toolName,
                durationMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI,
            )
        }
    }

/**
 * Registers tools on [server], wrapping every handler with per-call TOOL_CALL logging and inside it cancellation
 * through [inFlightToolCalls], which MUST be the instance the MCP transport cancels from: the log entry is decided
 * from the call's answer, so a cancelled call is logged as cancelled, however it was cancelled.
 * [currentRequestId] reads the JSON-RPC id of the request being handled (see [cancellableToolHandler]).
 * A class method is used instead of a `Server` extension so the registration signature stays
 * within detekt's default `LongParameterList` function cap (5): `addTool` below has exactly 5
 * value parameters, and swapping `server` → `registrar` keeps every `registerXxxTools` function
 * at its current parameter count.
 */
class LoggedToolRegistrar internal constructor(
    private val server: Server,
    private val serverLog: ServerLogRepository,
    private val toolCallIndicator: ToolCallIndicator,
    private val inFlightToolCalls: InFlightToolCalls,
    private val currentRequestId: suspend () -> RequestId?,
) {
    constructor(
        server: Server,
        serverLog: ServerLogRepository,
        toolCallIndicator: ToolCallIndicator = ToolCallIndicator.NONE,
        inFlightToolCalls: InFlightToolCalls,
    ) : this(server, serverLog, toolCallIndicator, inFlightToolCalls, { currentRequestHandlerExtra()?.requestId })

    /**
     * [Server.addTool] with per-call TOOL_CALL logging, cancellable by `notifications/cancelled` (see
     * [cancellableToolHandler]). [toolName] is the un-prefixed display name.
     */
    fun addTool(
        toolName: String,
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        val cancellable = cancellableToolHandler(inFlightToolCalls, currentRequestId, handler)
        server.addTool(name = name, description = description, inputSchema = inputSchema) { request ->
            // The caller is bound to this request's session, whether the session runs the call inline or launches it
            val caller = inFlightToolCalls.callerOf(sessionId)
            // Logging outermost: withContext refuses to start in a cancelled request, which is still logged then
            loggedToolHandler(serverLog, toolName, toolCallIndicator) { call ->
                if (caller == null) cancellable(call) else withContext(caller) { cancellable(call) }
            }(request)
        }
    }
}
