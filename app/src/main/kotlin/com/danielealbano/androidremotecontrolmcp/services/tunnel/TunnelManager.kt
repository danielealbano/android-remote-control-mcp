package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Orchestrates tunnel provider lifecycle based on user settings.
 *
 * This is a singleton that receives factory providers (via [Provider])
 * so fresh provider instances are created per tunnel session.
 */
@Singleton
class TunnelManager
    @Inject
    constructor(
        private val cloudflareTunnelProviderFactory: Provider<CloudflareTunnelProvider>,
        private val ngrokTunnelProviderFactory: Provider<NgrokTunnelProvider>,
        private val arcTunnelProviderFactory: Provider<ArcTunnelProvider>,
        private val serverLogRepository: ServerLogRepository,
    ) {
        private val _tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var activeProvider: TunnelProvider? = null
        private var statusRelayJob: Job? = null

        /**
         * Starts the tunnel of [config], the configuration the server started with (its listeners follow it too):
         * Cloudflare and ngrok forward to its tunnel local port, the free E2E tunnel serves through [listenerHost].
         */
        suspend fun start(
            config: ServerConfig,
            listenerHost: TunnelListenerHost,
        ) {
            mutex.withLock {
                if (!config.tunnelEnabled) return

                val provider =
                    when (config.tunnelProvider) {
                        TunnelProviderType.ARC_TUNNEL -> arcTunnelProviderFactory.get().also { it.bind(listenerHost) }
                        TunnelProviderType.CLOUDFLARE -> cloudflareTunnelProviderFactory.get()
                        TunnelProviderType.NGROK -> ngrokTunnelProviderFactory.get()
                    }

                statusRelayJob =
                    scope.launch {
                        provider.status.collect { _tunnelStatus.value = it }
                    }

                try {
                    provider.start(config.tunnelLocalPort, config)
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception,
                ) {
                    statusRelayJob?.cancel()
                    statusRelayJob = null
                    throw e
                }
                activeProvider = provider
            }
        }

        suspend fun stop() {
            mutex.withLock {
                if (_tunnelStatus.value !is TunnelStatus.Disconnected) {
                    serverLogRepository.log(ServerLogEntry.Type.TUNNEL, "Tunnel stopped")
                }

                statusRelayJob?.cancel()
                statusRelayJob = null

                // In a finally: a stop cancelled by the caller's timeout must still leave the tunnel shown as gone.
                try {
                    activeProvider?.stop()
                } finally {
                    activeProvider = null
                    _tunnelStatus.value = TunnelStatus.Disconnected
                }
            }
        }
    }
