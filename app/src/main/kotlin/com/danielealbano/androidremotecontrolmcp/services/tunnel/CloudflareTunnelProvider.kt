package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * Cloudflare Quick Tunnel provider.
 *
 * Runs the `cloudflared` binary as a child process to create a temporary
 * tunnel with a random `*.trycloudflare.com` URL. No account or
 * configuration is needed.
 */
class CloudflareTunnelProvider
    @Inject
    constructor(
        private val binaryResolver: CloudflaredBinaryResolver,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var stderrReaderJob: Job? = null
        private var processMonitorJob: Job? = null

        @Suppress("UNUSED_PARAMETER")
        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(process == null) { "Tunnel is already running" }

                val binaryPath =
                    binaryResolver.resolve() ?: run {
                        _status.value = TunnelStatus.Error("cloudflared binary not found")
                        return
                    }

                _status.value = TunnelStatus.Connecting

                try {
                    val pb =
                        ProcessBuilder(
                            binaryPath,
                            "tunnel",
                            "--url",
                            "http://localhost:$localPort",
                            "--output",
                            "json",
                        )
                    pb.redirectErrorStream(false)
                    val proc = pb.start()
                    process = proc

                    startStderrReader(proc)
                    startProcessMonitor(proc)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start cloudflared process", e)
                    _status.value = TunnelStatus.Error("Failed to start cloudflared: ${e.message}")
                    process = null
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                val proc = process ?: return
                stderrReaderJob?.cancel()
                stderrReaderJob = null
                processMonitorJob?.cancel()
                processMonitorJob = null

                proc.destroy()
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    proc.waitFor()
                } ?: proc.destroyForcibly()

                process = null
                _status.value = TunnelStatus.Disconnected
            }
        }

        private fun startStderrReader(proc: Process) {
            stderrReaderJob =
                scope.launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(proc.errorStream))
                        var line: String?
                        while (isActive) {
                            @Suppress("BlockingMethodInNonBlockingContext")
                            line = reader.readLine()
                            if (line == null) break

                            Log.d(TAG, "cloudflared: $line")

                            val match = TUNNEL_URL_REGEX.find(line)
                            if (match != null && _status.value is TunnelStatus.Connecting) {
                                val url = match.value
                                Log.i(TAG, "Cloudflare tunnel URL: $url")
                                _status.value =
                                    TunnelStatus.Connected(
                                        url = url,
                                        providerType = TunnelProviderType.CLOUDFLARE,
                                    )
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Error reading cloudflared stderr", e)
                        }
                    }
                }
        }

        private fun startProcessMonitor(proc: Process) {
            processMonitorJob =
                scope.launch {
                    // Give the process a moment to start before monitoring exit
                    delay(PROCESS_MONITOR_INITIAL_DELAY_MS)

                    @Suppress("BlockingMethodInNonBlockingContext")
                    val exitCode = proc.waitFor()

                    if (isActive && _status.value !is TunnelStatus.Disconnected) {
                        Log.w(TAG, "cloudflared process exited unexpectedly with code $exitCode")
                        _status.value =
                            TunnelStatus.Error(
                                "cloudflared process exited unexpectedly (code $exitCode)",
                            )
                        mutex.withLock {
                            process = null
                        }
                    }
                }
        }

        companion object {
            private const val TAG = "MCP:CloudflareTunnel"
            internal val TUNNEL_URL_REGEX =
                Regex("https://[-a-zA-Z0-9]+\\.trycloudflare\\.com")
            internal const val TUNNEL_READY_TIMEOUT_MS = 30_000L
            internal const val SHUTDOWN_TIMEOUT_MS = 5_000L
            private const val PROCESS_MONITOR_INITIAL_DELAY_MS = 1_000L
        }
    }
