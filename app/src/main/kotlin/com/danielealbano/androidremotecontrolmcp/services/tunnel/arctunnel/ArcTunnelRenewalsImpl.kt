package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArcTunnelRenewalsImpl
    @Inject
    constructor(
        private val enrollment: ArcTunnelEnrollment,
        private val serverLog: ServerLogRepository,
    ) : ArcTunnelRenewals {
        private val retries = Channel<Unit>(Channel.CONFLATED)
        private val _state = MutableStateFlow<ArcTunnelRenewalState>(ArcTunnelRenewalState.Idle)
        override val state: StateFlow<ArcTunnelRenewalState> = _state.asStateFlow()

        override suspend fun renewForStart(identity: ArcTunnelIdentity) {
            // A Retry pressed before this attempt started is stale.
            retries.tryReceive()
            _state.value = ArcTunnelRenewalState.Renewing
            val result = runCatching { enrollment.renew(identity, null) }
            result.onSuccess {
                _state.value = ArcTunnelRenewalState.Idle
                serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel certificate renewed at start")
            }
            result.onFailure {
                if (it is CancellationException) {
                    _state.value = ArcTunnelRenewalState.Idle
                } else {
                    val reason = it.message ?: it.javaClass.simpleName
                    Log.e(TAG, "tunnel-start certificate renewal failed", it)
                    _state.value = ArcTunnelRenewalState.Failed(reason)
                    serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel certificate renewal failed: $reason")
                }
                throw it
            }
        }

        override fun retry() {
            retries.trySend(Unit)
        }

        override suspend fun awaitRetry() {
            retries.receive()
        }

        override fun clear() {
            _state.value = ArcTunnelRenewalState.Idle
        }

        private companion object {
            const val TAG = "MCP:ArcTunnelRenewals"
        }
    }
