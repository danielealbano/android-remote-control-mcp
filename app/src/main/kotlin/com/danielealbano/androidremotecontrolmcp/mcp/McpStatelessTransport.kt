package com.danielealbano.androidremotecontrolmcp.mcp

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

private const val TAG = "MCP:StatelessTransport"

/**
 * Installs the MCP stateless Streamable HTTP transport at `/mcp` (JSON responses, no SSE), together with a
 * per-request [RequestBaseUrlElement] scoped to the `/mcp` route so MCP tool handlers can build absolute links
 * (e.g. ephemeral file links).
 *
 * Each POST is served by a fresh SDK session, bound in [inFlightToolCalls] to the POST's [McpCaller] while it runs: a
 * tool handler finds its caller through its session, whether the session runs it inline in the request coroutine
 * (never initialized) or launches it in the session's own scope (a batch with `notifications/initialized`). The
 * endpoint is ours rather than the SDK's `mcpStatelessStreamableHttp` (which it otherwise mirrors: the same
 * transport configuration, a session per POST closed after it, GET and DELETE refused) so that a running tool call
 * is stopped when its client gives up:
 * - when the client closes the connection or resets its HTTP/2 stream, the request is cancelled
 *   ([cancelledOnClientDisconnect]);
 * - `notifications/cancelled` cancels the call it names through [inFlightToolCalls]: the per-POST session the SDK
 *   creates could never find a call of another POST, so the session gets a handler matching the call by the
 *   [McpCaller] of this POST and its request id.
 *
 * DNS-rebinding protection is not installed: requests arrive via tunnels, so the `Host` header is the tunnel
 * hostname (not localhost) and the SDK's localhost-default validation would reject all legitimate traffic.
 * Bearer/OAuth authentication is the compensating control. ContentNegotiation with `McpJson`, which the transport
 * needs, is installed by [installMcpBasePlugins].
 *
 * Shared by [McpServer] and the integration tests so production and test wiring cannot drift.
 *
 * @param publicUrlOverride Pins the base URL host; empty means derive it from the request.
 * @param inFlightToolCalls The running tool calls; MUST be the instance the tools are registered with.
 * @param block Provider for the MCP [Server] instance to serve each request.
 */
fun Application.installMcpStatelessTransport(
    publicUrlOverride: String = "",
    inFlightToolCalls: InFlightToolCalls,
    block: () -> Server,
) {
    routing {
        route("/mcp") {
            intercept(ApplicationCallPipeline.Plugins) {
                withContext(RequestBaseUrlElement(effectiveBaseUrl(call, publicUrlOverride))) {
                    proceed()
                }
            }
            post { serveStatelessRequest(call, block(), inFlightToolCalls) }
            get { call.rejectUnsupportedMethod() }
            delete { call.rejectUnsupportedMethod() }
        }
    }
}

private suspend fun serveStatelessRequest(
    call: RoutingCall,
    server: Server,
    inFlightToolCalls: InFlightToolCalls,
) {
    val caller = McpCaller.of(call.request.headers[HttpHeaders.Authorization])
    val transport =
        StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
            .also { it.setSessionIdGenerator(null) }
    val session = server.createSession(transport)
    val unbind = inFlightToolCalls.bindSession(session.sessionId, caller)
    session.setNotificationHandler<CancelledNotification>(Method.Defined.NotificationsCancelled) { cancelled ->
        val outcome = inFlightToolCalls.cancel(caller.id, cancelled.params.requestId, cancelled.params.reason)
        if (outcome == InFlightToolCalls.Outcome.AMBIGUOUS) {
            Log.w(TAG, "Cancellation ignored: several running calls of this client have its request id")
        }
        CompletableDeferred(Unit)
    }
    try {
        cancelledOnClientDisconnect(nettyChannelOf(call)) { transport.handleRequest(null, call) }
    } finally {
        unbind()
        // A stateless session serves exactly one request: close it so the server's session registry does not grow.
        session.close()
    }
}

/**
 * Runs [block] (the request), cancelling it when the request's [channel] closes, as it does at once when the client
 * closes the connection or resets its HTTP/2 stream: Ktor does not cancel a call whose client went away, so a running
 * tool would otherwise go on to its end for nobody. Without a Netty channel (other engines, tests) [block] just runs.
 */
internal suspend fun cancelledOnClientDisconnect(
    channel: Channel?,
    block: suspend () -> Unit,
) {
    if (channel == null) return block()
    val request = currentCoroutineContext().job
    val onClose = ChannelFutureListener { request.cancel(CancellationException("The request's channel closed")) }
    channel.closeFuture().addListener(onClose)
    try {
        block()
    } finally {
        channel.closeFuture().removeListener(onClose)
    }
}

/** The Netty channel of [call]'s request (the HTTP/2 stream channel for an HTTP/2 request), or null off Netty. */
private fun nettyChannelOf(call: RoutingCall): Channel? {
    val engineCall = call.pipelineCall.engineCall as? NettyApplicationCall
    return engineCall?.context?.channel()
}

/** A stateless endpoint serves POST only, offering neither an SSE stream to open nor a session to delete. */
private suspend fun ApplicationCall.rejectUnsupportedMethod() {
    response.header(HttpHeaders.Allow, HttpMethod.Post.value)
    val error =
        JSONRPCError(id = null, error = RPCError(code = RPCError.ErrorCode.CONNECTION_CLOSED, "Method not allowed."))
    respondText(McpJson.encodeToString(error), ContentType.Application.Json, HttpStatusCode.MethodNotAllowed)
}
