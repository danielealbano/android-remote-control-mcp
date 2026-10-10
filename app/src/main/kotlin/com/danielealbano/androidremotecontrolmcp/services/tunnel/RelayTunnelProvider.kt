package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Result of one relay session: how long it stayed connected (0 = handshake failed) and why it ended. */
private data class SessionOutcome(
    val connectedForMs: Long,
    val reason: String,
    val replaced: Boolean,
)

/**
 * Self-hosted WebSocket relay tunnel provider.
 *
 * The device opens an OUTBOUND WebSocket to `<relayUrl>/device` (bearer-authenticated); [RelaySession] pumps
 * it. The provider owns the HTTP client, the reconnect loop (exponential backoff 1 s … 30 s, reset after a
 * session of at least [MIN_STABLE_SESSION_MS]) and the published [status]; it redials a socket silent for
 * [idleTimeoutMs] and stops only on [stop] or when a pending [start] is cancelled.
 */
class RelayTunnelProvider
    @Inject
    constructor() : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val mutex = Mutex()
        private val exceptionHandler =
            CoroutineExceptionHandler { _, e ->
                Logger.e(TAG, "Relay tunnel loop failed", e)
                _status.value = TunnelStatus.Error("Relay tunnel failed: ${e.message}")
            }
        private val replaySlots = Semaphore(MAX_CONCURRENT_REPLAYS)
        private var sessionScope: CoroutineScope? = null
        private var client: HttpClient? = null
        private var connectionJob: Job? = null

        /** Overridable for tests; production builds an OkHttp client (no redirects, no silent retries). */
        internal var clientProvider: () -> HttpClient = ::createClient

        /** Overridable for tests. */
        internal var idleTimeoutMs: Long = IDLE_TIMEOUT_MS

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(connectionJob == null) { "Tunnel is already running" }
                val target =
                    RelayTarget.resolve(config).getOrElse { failure ->
                        _status.value = TunnelStatus.Error(failure.message ?: "Invalid relay configuration")
                        return
                    }
                _status.value = TunnelStatus.Connecting
                val httpClient = clientProvider()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
                client = httpClient
                sessionScope = scope
                connectionJob = scope.launch { connectionLoop(httpClient, target, localPort) }
            }
            try {
                _status.first { it !is TunnelStatus.Connecting }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stop() }
                throw e
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                val job = connectionJob ?: return
                job.cancelAndJoin()
                client?.cancel()
                client?.close()
                sessionScope?.cancel()
                connectionJob = null
                client = null
                sessionScope = null
                _status.value = TunnelStatus.Disconnected
                Logger.i(TAG, "Relay tunnel stopped")
            }
        }

        private suspend fun connectionLoop(
            httpClient: HttpClient,
            target: RelayTarget,
            localPort: Int,
        ) {
            val replayer = LocalRequestReplayer(httpClient)
            var failures = 0
            while (currentCoroutineContext().isActive) {
                val outcome = runSession(httpClient, target, localPort, replayer)
                failures = nextFailureCount(failures, outcome.connectedForMs)
                val delayMs = if (outcome.replaced) MAX_BACKOFF_MS else backoffMs(failures)
                _status.value = TunnelStatus.Error("${outcome.reason}; reconnecting in ${delayMs / MS_PER_SECOND}s")
                delay(delayMs)
            }
        }

        private suspend fun runSession(
            httpClient: HttpClient,
            target: RelayTarget,
            localPort: Int,
            replayer: LocalRequestReplayer,
        ): SessionOutcome {
            var connectedAt = 0L
            return try {
                val session =
                    httpClient.webSocketSession(target.deviceUrl) {
                        header(HttpHeaders.Authorization, "Bearer ${target.token}")
                    }
                connectedAt = System.currentTimeMillis()
                _status.value =
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint(url = target.publicUrl, valid = true)),
                        providerType = TunnelProviderType.RELAY,
                    )
                Logger.i(TAG, "Relay connected; public URL ${target.publicUrl}")
                val end = RelaySession(session, localPort, replayer, replaySlots, idleTimeoutMs).pump()
                SessionOutcome(connectedFor(connectedAt), end.reason, end.replaced)
            } catch (e: IOException) {
                failedOutcome(connectedAt, e)
            } catch (e: IllegalArgumentException) {
                failedOutcome(connectedAt, e)
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                failedOutcome(connectedAt, e)
            }
        }

        private fun failedOutcome(
            connectedAt: Long,
            e: Exception,
        ): SessionOutcome {
            Logger.w(TAG, "Relay session ended with error", e)
            return SessionOutcome(connectedFor(connectedAt), "Relay connection failed: ${e.message}", replaced = false)
        }

        private fun createClient(): HttpClient =
            HttpClient(OkHttp) {
                followRedirects = false
                engine {
                    config {
                        dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
                        pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                        retryOnConnectionFailure(false)
                    }
                }
                install(WebSockets)
                install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MS }
            }

        companion object {
            private const val TAG = "MCP:RelayTunnel"
            private const val MS_PER_SECOND = 1_000L
            private const val CONNECT_TIMEOUT_MS = 15_000L
            private const val INITIAL_BACKOFF_MS = 1_000L
            private const val MAX_BACKOFF_SHIFT = 5
            private const val WS_PING_INTERVAL_SECONDS = 30L
            private const val MAX_CONCURRENT_REPLAYS = 8

            /**
             * OkHttp's per-host dispatcher limit defaults to 5 and would silently queue replays past it; the device
             * socket's call also stays running for its lifetime, so one extra slot covers relay and local server
             * sharing a host (tests, `adb reverse`).
             */
            private const val MAX_REQUESTS_PER_HOST = MAX_CONCURRENT_REPLAYS + 1

            /** Longest wait between redials; also used after the relay replaced this device (close code 4000). */
            internal const val MAX_BACKOFF_MS = 30_000L

            /** A session shorter than this does not reset the backoff (avoids connect/drop storms). */
            internal const val MIN_STABLE_SESSION_MS = 10_000L

            /** A socket that delivers nothing (not even a relay ping) for this long is closed and redialled. */
            internal const val IDLE_TIMEOUT_MS = 90_000L

            /** 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s… for the n-th consecutive failure (n ≥ 1). */
            internal fun backoffMs(failures: Int): Long {
                val shift = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
                return (INITIAL_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
            }

            /** Consecutive-failure counter: a stable session restarts the ladder at 1. */
            internal fun nextFailureCount(
                previous: Int,
                connectedForMs: Long,
            ): Int = if (connectedForMs >= MIN_STABLE_SESSION_MS) 1 else previous + 1

            private fun connectedFor(connectedAt: Long): Long {
                if (connectedAt == 0L) return 0L
                return System.currentTimeMillis() - connectedAt
            }
        }
    }
