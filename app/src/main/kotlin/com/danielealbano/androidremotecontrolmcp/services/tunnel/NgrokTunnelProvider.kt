package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.os.Build
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.ngrok.Forwarder
import com.ngrok.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject

/**
 * ngrok tunnel provider.
 *
 * Uses the ngrok-java library (in-process, JNI-based) to create an
 * HTTPS tunnel. Requires an authtoken and optionally supports a
 * custom domain.
 *
 * Only available on `arm64-v8a` devices — graceful error on
 * unsupported ABIs.
 */
class NgrokTunnelProvider
    @Inject
    constructor() : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val mutex = Mutex()
        private var session: Session? = null
        private var forwarder: Forwarder.Endpoint? = null

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(session == null) { "Tunnel is already running" }

                if (!isSupportedAbi()) {
                    val abis = Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"
                    _status.value =
                        TunnelStatus.Error(
                            "ngrok is not supported on this device architecture " +
                                "($abis). Use Cloudflare instead.",
                        )
                    return
                }

                if (config.ngrokAuthtoken.isEmpty()) {
                    _status.value = TunnelStatus.Error("ngrok authtoken is required")
                    return
                }

                _status.value = TunnelStatus.Connecting

                try {
                    withContext(Dispatchers.IO) {
                        val sess =
                            Session
                                .withAuthtoken(config.ngrokAuthtoken)
                                .metadata("android-remote-control-mcp")
                                .connect()
                        session = sess

                        val httpBuilder = sess.httpEndpoint()
                        if (config.ngrokDomain.isNotEmpty()) {
                            httpBuilder.domain(config.ngrokDomain)
                        }

                        val fwd = httpBuilder.forward(URL("http://localhost:$localPort"))
                        forwarder = fwd

                        val url = fwd.url
                        Log.i(TAG, "ngrok tunnel URL: $url")
                        _status.value =
                            TunnelStatus.Connected(
                                url = url,
                                providerType = TunnelProviderType.NGROK,
                            )
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.e(TAG, "Failed to start ngrok tunnel", e)
                    _status.value = TunnelStatus.Error("Failed to start ngrok: ${e.message}")
                    cleanup()
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                if (session == null) return

                @Suppress("TooGenericExceptionCaught")
                try {
                    withContext(Dispatchers.IO) {
                        forwarder?.close()
                        session?.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing ngrok session", e)
                } finally {
                    cleanup()
                    _status.value = TunnelStatus.Disconnected
                }
            }
        }

        private fun cleanup() {
            forwarder = null
            session = null
        }

        companion object {
            private const val TAG = "MCP:NgrokTunnel"
            private const val SUPPORTED_ABI = "arm64-v8a"

            internal fun isSupportedAbi(): Boolean = Build.SUPPORTED_ABIS.any { it == SUPPORTED_ABI }
        }
    }
