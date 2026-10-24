package com.danielealbano.androidremotecontrolmcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who sent the MCP request being served: the SHA-256 of the bearer credential it carried (the static token or an
 * OAuth access token), or [OPEN] when it carried none. Only ever kept hashed, never logged.
 */
class McpCaller private constructor(
    val id: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpCaller> {
        /** The caller of a request without a bearer credential (possible only while authentication is off). */
        const val OPEN = "open"

        private const val BEARER_PREFIX = "Bearer "

        /** The caller of a request with this `Authorization` header. */
        fun of(authorizationHeader: String?): McpCaller {
            val credential =
                authorizationHeader
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                    .orEmpty()
            return McpCaller(if (credential.isEmpty()) OPEN else sha256Hex(credential))
        }

        private fun sha256Hex(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The MCP tool calls running right now, so a `notifications/cancelled` from the client that sent one cancels it.
 *
 * The transport is stateless: every POST is a session of its own, so the SDK cannot match a cancellation (another
 * POST) to the call it names. Calls are tracked here by their [McpCaller] and JSON-RPC request id. A request id is
 * unique only per client, and clients sharing the static token share a caller, so a cancellation acts only when
 * exactly one running call of that caller has that id; a call of another caller can never be cancelled.
 */
@Singleton
class InFlightToolCalls
    @Inject
    constructor() {
        /** What a cancellation did. */
        enum class Outcome {
            /** The only running call of the caller with that id was cancelled. */
            CANCELLED,

            /**
             * No running call of the caller has that id that a cancellation can still stop (it already ended, or an
             * earlier `notifications/cancelled` was already accepted for it; it never existed, or belongs to another
             * caller): nothing to do.
             */
            UNKNOWN,

            /** Several tracked calls of the caller have that id: none is cancelled, as none can be told apart. */
            AMBIGUOUS,
        }

        private data class CallKey(
            val caller: String,
            val requestId: RequestId,
        )

        private val lock = Any()
        private val calls = mutableMapOf<CallKey, MutableList<(String?) -> Boolean>>()
        private val sessionCallers = mutableMapOf<String, McpCaller>()

        /**
         * Binds the SDK session [sessionId] to the [caller] of the request it serves, while that request runs; the
         * returned function unbinds it. Every POST is a session of its own, so its tool calls find their caller here.
         */
        fun bindSession(
            sessionId: String,
            caller: McpCaller,
        ): () -> Unit {
            synchronized(lock) { sessionCallers[sessionId] = caller }
            return { synchronized(lock) { sessionCallers.remove(sessionId) } }
        }

        /** The caller of the request the SDK session [sessionId] serves, or null when no request binds it. */
        fun callerOf(sessionId: String): McpCaller? = synchronized(lock) { sessionCallers[sessionId] }

        /**
         * Tracks the call [requestId] of [caller]. [cancel] stops it given the client's reason, returning false when
         * it no longer accepts a client cancellation (its tool already ended, or an earlier `notifications/cancelled`
         * was already accepted for it, its tool possibly still unwinding): nothing is cancelled then. The returned
         * function stops tracking it.
         */
        fun track(
            caller: String,
            requestId: RequestId,
            cancel: (reason: String?) -> Boolean,
        ): () -> Unit {
            val key = CallKey(caller, requestId)
            synchronized(lock) { calls.getOrPut(key) { mutableListOf() }.add(cancel) }
            return {
                synchronized(lock) {
                    val cancels = calls[key]
                    if (cancels != null && cancels.remove(cancel) && cancels.isEmpty()) calls.remove(key)
                }
            }
        }

        /** Cancels the running call [requestId] of [caller] when it is the only one with that id. */
        fun cancel(
            caller: String,
            requestId: RequestId,
            reason: String?,
        ): Outcome {
            val cancels = synchronized(lock) { calls[CallKey(caller, requestId)]?.toList().orEmpty() }
            return when (cancels.size) {
                0 -> {
                    Outcome.UNKNOWN
                }

                1 -> {
                    if (cancels.single()(reason)) Outcome.CANCELLED else Outcome.UNKNOWN
                }

                else -> {
                    Outcome.AMBIGUOUS
                }
            }
        }
    }
