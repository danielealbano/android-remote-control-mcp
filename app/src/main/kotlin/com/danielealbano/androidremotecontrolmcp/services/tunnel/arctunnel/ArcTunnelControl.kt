package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The outbound mTLS HTTP/2 control connection (PROTOCOL.md §3): one duplex `POST /api/v1/control`, PING→PONG,
 * OPEN → [ArcTunnelOpenHandler], RENEW_NUDGE → [ArcTunnelControlEvents.onRenewNudge] (single flight), MOVE → a new
 * control connection (§7) while the one that carried the MOVE keeps serving until the server closes it. A torn
 * stream reconnects with [ArcTunnelBackoff]. Every call in flight is cancelled by [stop] and, through a sibling
 * "canceller" coroutine, by the cancellation of the scope running [run], so a blocked read never outlives either.
 * Children (OPEN handlers, renewals, the watchdogs, the moved-away connections) run under a supervisor: one failing
 * child is reported to the scope's handler, the loop goes on.
 */
class ArcTunnelControl(
    client: OkHttpClient,
    private val net: ArcTunnelNet,
    private val controlHost: String,
    private val opens: ArcTunnelOpenHandler,
    private val events: ArcTunnelControlEvents,
) {
    private val running = AtomicBoolean(true)
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val renewalInFlight = AtomicBoolean(false)
    private val node = AtomicReference("unknown")

    /** Test seam: the watchdog's clock (nanoseconds). */
    internal var watchdogClock: () -> Long = System::nanoTime

    /** Test seam: a RENEW_NUDGE renewal is running (a nudge now is ignored). */
    internal val renewing: Boolean get() = renewalInFlight.get()
    private val client =
        client
            .newBuilder()
            .addNetworkInterceptor { chain ->
                chain
                    .connection()
                    ?.route()
                    ?.socketAddress
                    ?.address
                    ?.hostAddress
                    ?.let { node.set(it) }
                chain.proceed(chain.request())
            }.build()

    fun stop() {
        running.set(false)
        calls.forEach { it.cancel() }
    }

    /**
     * Serves control streams until [stop]; OPEN handlers, renewals, the watchdogs and the moved-away connections run as
     * children of this scope.
     * A failure ends the current stream only and the loop reconnects with backoff, as the reference client does. Each
     * failed round is reported to [ArcTunnelControlEvents.onAttemptFailed]; a MOVE address that refuses or fails and
     * the end of a moved-away connection are not.
     *
     * A MOVE is followed at once, except one answered by the DNS connection opened after a MOVE, which waits the
     * backoff. Following one reports no [ArcTunnelControlEvents.onUnbound]; the `200` that carried it has already
     * reported [ArcTunnelControlEvents.onBound], even from a draining node, as the reference client does. Only a
     * stream that ends without a MOVE resets the backoff: a draining node's `200` and MOVE is not a bind.
     */
    suspend fun run() =
        supervisorScope {
            val backoff = ArcTunnelBackoff()
            var moveTo: List<String>? = null
            while (running.get() && isActive) {
                val targets = moveTo
                val wasBound = AtomicBoolean(false)
                val outcome =
                    runCatching { withContext(Dispatchers.IO) { attempt(this@supervisorScope, targets, wasBound) } }
                // Cancellation (the provider stopping) and JVM errors are not stream failures.
                outcome.exceptionOrNull()?.let { if (it is CancellationException || it !is Exception) throw it }
                val attempt = outcome.getOrElse { Attempt(failure = failureReason(it)) }
                if (!running.get() || !isActive) break
                attempt.failure?.let(events::onAttemptFailed)
                moveTo = attempt.moveTo
                if (wasBound.get() && moveTo == null) backoff.reset()
                if (moveTo == null) events.onUnbound()
                if (moveTo == null || attempt.moveAfterBackoff) delay(backoff.next())
            }
        }

    /** How one round of [run] ended; [moveAfterBackoff]: [moveTo] came from the DNS connection that followed a MOVE. */
    private data class Attempt(
        val failure: String? = null,
        val moveTo: List<String>? = null,
        val moveAfterBackoff: Boolean = false,
    )

    /**
     * One round: the control host, or the [targets] of a MOVE. [wasBound] is set as soon as a stream is bound, so an
     * abrupt end still resets the backoff.
     */
    private fun attempt(
        scope: CoroutineScope,
        targets: List<String>?,
        wasBound: AtomicBoolean,
    ): Attempt =
        if (targets == null) {
            Connection(scope, dedicatedClient(), net.url(controlHost, CONTROL_PATH), wasBound).serve()
        } else {
            attemptMove(scope, targets, wasBound)
        }

    /** The first address that answers 200 is the new connection; when none does, a new one through DNS (§7). */
    private fun attemptMove(
        scope: CoroutineScope,
        addrs: List<String>,
        wasBound: AtomicBoolean,
    ): Attempt =
        addrs.firstNotNullOfOrNull { attemptMoveTarget(scope, it, wasBound) }
            ?: attempt(scope, null, wasBound).let { it.copy(moveAfterBackoff = it.moveTo != null) }

    /** Null when [addr] did not bind: invalid, refused or unreachable. */
    private fun attemptMoveTarget(
        scope: CoroutineScope,
        addr: String,
        wasBound: AtomicBoolean,
    ): Attempt? {
        val target = ArcTunnelMoveTarget.parse(addr) ?: return null
        val url = "https://$controlHost:${target.port}$CONTROL_PATH"
        return try {
            Connection(scope, dedicatedClient(target), url, wasBound).serve().takeIf { wasBound.get() }
        } catch (e: IOException) {
            // A bound stream that ended is a lost connection: the loop reconnects, the other addresses are stale.
            if (wasBound.get()) throw e
            Log.w(TAG, "control connection to the MOVE target $addr failed", e)
            null
        }
    }

    /**
     * A pool of its own: a control connection is always a new TCP + TLS connection, resolved when it is opened, and
     * never carries another control stream (§7).
     */
    private fun dedicatedClient(target: ArcTunnelMoveTarget? = null): OkHttpClient =
        client
            .newBuilder()
            .connectionPool(ConnectionPool())
            .apply {
                target?.let {
                    dns(Dns { _ -> listOf(it.address) })
                    // Bounds the TLS handshake, so a target that never completes it does not hold back the next
                    // addresses; OkHttp does not apply it to the duplex stream while its request body is open.
                    readTimeout(MOVE_READ_TIMEOUT_S, TimeUnit.SECONDS)
                }
            }.build()

    /** The reason [ArcTunnelControlEvents.onAttemptFailed] reports for a stream that ended with [e]. */
    private fun failureReason(e: Throwable): String =
        when (e) {
            is IOException -> {
                Log.w(TAG, "control connection ended", e)
                e.message ?: e.javaClass.simpleName
            }

            is IllegalArgumentException -> {
                Log.w(TAG, "control stream torn down on a malformed frame", e)
                MALFORMED_FRAME
            }

            else -> {
                Log.e(TAG, "unexpected control failure; reconnecting", e)
                e.message ?: e.javaClass.simpleName
            }
        }

    private fun onNudge(
        scope: CoroutineScope,
        payloadJson: String,
    ) {
        val nonce = ArcTunnelJson.decodeFromString(ArcTunnelRenewNudgePayload.serializer(), payloadJson).nonce
        when {
            nonce.isEmpty() -> Log.w(TAG, "RENEW_NUDGE without a nonce ignored")
            !renewalInFlight.compareAndSet(false, true) -> Log.i(TAG, "renewal in progress; nudge ignored")
            else -> scope.launch { renew(nonce) }
        }
    }

    private suspend fun renew(nonce: String) {
        try {
            events.onRenewNudge(nonce)
        } finally {
            renewalInFlight.set(false)
        }
    }

    private fun duplexBody(capture: (BufferedSink) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun isDuplex(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                capture(sink)
            }
        }

    /**
     * One control connection with its call, pool, dial-back sessions and watchdog. [serve] returns when the stream
     * ends or at its first MOVE; a stream that carried a MOVE goes on in a child coroutine until the server ends it.
     */
    private inner class Connection(
        private val scope: CoroutineScope,
        private val callClient: OkHttpClient,
        url: String,
        private val wasBound: AtomicBoolean,
    ) {
        private var sink: BufferedSink? = null
        private var response: Response? = null
        private val sessions = ArcTunnelSessions { Log.d(TAG, "dial-back session close failed", it) }
        private val lastFrameAt = AtomicLong(System.nanoTime())
        private val silent = AtomicBoolean(false)
        private val call =
            callClient.newCall(
                Request
                    .Builder()
                    .url(url)
                    .post(duplexBody { sink = it })
                    .build(),
            )

        /** Returns the refusal reason of a non-200 answer or the addresses of a MOVE, an empty [Attempt] otherwise. */
        fun serve(): Attempt {
            calls.add(call)
            if (!running.get()) call.cancel()
            // A cancelled scope (provider stop) cancels the blocking execute/read through the call: the sibling
            // below is cancelled with the scope and its finally runs on the way out. UNDISPATCHED: it reaches
            // awaitCancellation() before this function continues, so an already-cancelled scope still cancels.
            val canceller =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            val watchdog = scope.launch { ArcTunnelWatchdog.watch(call, lastFrameAt, silent, watchdogClock) }
            val siblings = listOf(canceller, watchdog)
            var movedAway = false
            return try {
                val r = call.execute()
                response = r
                val s = sink
                if (r.code == HttpURLConnection.HTTP_OK && s != null) {
                    wasBound.set(true)
                    events.onBound(node.get())
                    val source = r.body.source()
                    val moveTo = readFrames(source, s, canMove = true)
                    if (moveTo != null) {
                        movedAway = true
                        keepServing(source, s, siblings)
                    }
                    Attempt(moveTo = moveTo)
                } else {
                    Log.w(TAG, "control connect rejected: ${r.code}")
                    Attempt(failure = "the tunnel service refused the connection (HTTP ${r.code})")
                }
            } catch (e: IOException) {
                // The watchdog cancels a silent stream: name the cause instead of OkHttp's bare "Canceled".
                if (silent.get()) throw IOException(SILENT_REASON, e)
                throw e
            } finally {
                if (!movedAway) close(siblings)
            }
        }

        /**
         * The moved-away stream keeps serving its frames until the server closes it: closing it would end the
         * splices opened through it. Its end is not a lost connection, so nothing is reported. ATOMIC: the cleanup
         * runs even when the scope is already cancelled.
         */
        @OptIn(DelicateCoroutinesApi::class)
        private fun keepServing(
            source: BufferedSource,
            s: BufferedSink,
            siblings: List<Job>,
        ) {
            scope.launch(Dispatchers.IO, CoroutineStart.ATOMIC) {
                try {
                    readFrames(source, s, canMove = false)
                } catch (e: IOException) {
                    Log.w(TAG, "moved-away control connection ended", e)
                } finally {
                    close(siblings)
                }
            }
        }

        /** Until the end of the stream, or the addresses of its MOVE when [canMove]; a further MOVE is ignored. */
        private fun readFrames(
            source: BufferedSource,
            s: BufferedSink,
            canMove: Boolean,
        ): List<String>? {
            var frame = ArcTunnelFrames.read(source)
            while (frame != null && running.get()) {
                lastFrameAt.set(System.nanoTime())
                if (frame.first != ArcTunnelFrames.MOVE) {
                    dispatch(frame.first, frame.second, s)
                } else if (canMove) {
                    return ArcTunnelJson.decodeFromString(ArcTunnelMovePayload.serializer(), String(frame.second)).addrs
                } else {
                    Log.i(TAG, "MOVE on a connection that already moved ignored")
                }
                frame = if (running.get()) ArcTunnelFrames.read(source) else null
            }
            return null
        }

        /**
         * A malformed payload throws `SerializationException` (an `IllegalArgumentException`): the stream tears
         * down.
         */
        private fun dispatch(
            type: Int,
            payload: ByteArray,
            s: BufferedSink,
        ) {
            when (type) {
                ArcTunnelFrames.PING -> {
                    s.write(ArcTunnelFrames.PONG_FRAME)
                    s.flush()
                }

                ArcTunnelFrames.OPEN -> {
                    val open = ArcTunnelJson.decodeFromString(ArcTunnelOpenPayload.serializer(), String(payload))
                    scope.launch { opens.handle(open, sessions) }
                }

                ArcTunnelFrames.RENEW_NUDGE -> {
                    onNudge(scope, String(payload))
                }

                else -> {
                    Log.d(TAG, "unknown control frame type $type ignored")
                }
            }
        }

        private fun close(siblings: List<Job>) {
            // Close gracefully before cancelling the siblings: the canceller's finally cancels the call, which would
            // reset a stream whose request body is not closed yet. Meanwhile both stay active, so a scope cancellation
            // (the canceller) or the watchdog can still cancel the call while the close waits on the stream.
            try {
                closeGracefully()
            } finally {
                siblings.forEach { it.cancel() }
                calls.remove(call)
                callClient.connectionPool.evictAll()
            }
        }

        /** Ends a control stream cleanly: the dial-back sessions, the request body (END_STREAM), the response. */
        private fun closeGracefully() {
            sessions.closeAll()
            try {
                sink?.close()
            } catch (e: IOException) {
                Log.d(TAG, "control request body close failed", e)
            }
            response?.close()
        }
    }

    companion object {
        private const val TAG = "MCP:ArcTunnelControl"
        private const val CONTROL_PATH = "/api/v1/control"
        private const val MOVE_READ_TIMEOUT_S = 10L
        private const val MALFORMED_FRAME = "the tunnel service sent a malformed control frame"
        private const val SILENT_REASON = "no data from the tunnel service for 90 s"
    }
}
