package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        private val settingsRepository: SettingsRepository,
        private val cloudflareTunnelProviderFactory: Provider<CloudflareTunnelProvider>,
        private val ngrokTunnelProviderFactory: Provider<NgrokTunnelProvider>,
    ) {
        private val _tunnelStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var activeProvider: TunnelProvider? = null
        private var statusRelayJob: Job? = null

        suspend fun start(localPort: Int) {
            mutex.withLock {
                val config = settingsRepository.serverConfig.first()

                if (!config.tunnelEnabled) return

                val provider =
                    when (config.tunnelProvider) {
                        TunnelProviderType.CLOUDFLARE -> cloudflareTunnelProviderFactory.get()
                        TunnelProviderType.NGROK -> ngrokTunnelProviderFactory.get()
                    }

                statusRelayJob =
                    scope.launch {
                        provider.status.collect { _tunnelStatus.value = it }
                    }

                try {
                    provider.start(localPort, config)
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
                statusRelayJob?.cancel()
                statusRelayJob = null

                activeProvider?.stop()
                activeProvider = null

                _tunnelStatus.value = TunnelStatus.Disconnected
            }
        }
    }
