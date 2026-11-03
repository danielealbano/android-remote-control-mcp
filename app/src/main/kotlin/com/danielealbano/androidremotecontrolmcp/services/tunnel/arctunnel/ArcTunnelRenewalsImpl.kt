package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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

        /** Test seam: how long [checkSavedChain] waits for the certificate before the tunnel starts without it. */
        internal var chainRenewalTimeoutMs: Long = CHAIN_RENEWAL_TIMEOUT_MS

        override suspend fun renewForStart(identity: ArcTunnelIdentity) {
            // A Retry pressed before this attempt started is stale.
            retries.tryReceive()
            _state.value = ArcTunnelRenewalState.Renewing
            val result = runCatching { enrollment.renew(identity, null) { certificateReceived(RENEWED_AT_START) } }
            result.onSuccess { _state.value = ArcTunnelRenewalState.Idle }
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

        override suspend fun checkSavedChain(identity: ArcTunnelIdentity) {
            val failure = runCatching { checkAndRenewChain(identity) }.exceptionOrNull() ?: return
            if (failure is CancellationException) throw failure
            val reason = failure.message ?: failure.javaClass.simpleName
            Log.e(TAG, "certificate chain check failed; starting with the current certificate", failure)
            serverLog.log(
                ServerLogEntry.Type.TUNNEL,
                "Free E2E tunnel certificate chain check failed, starting with the current certificate: $reason",
            )
        }

        private suspend fun checkAndRenewChain(identity: ArcTunnelIdentity) =
            withContext(Dispatchers.IO) {
                val store = enrollment.store(ArcTunnelEnv(identity.hosts.baseAddress))
                if (store.load()?.certCheck != ARC_TUNNEL_CERT_CHECK) {
                    if (identity.net.reachesTrustedRoot(identity.state().publicChain)) {
                        store.markCertChecked()
                        Log.i(TAG, CHAIN_COMPLETE)
                        serverLog.log(ServerLogEntry.Type.TUNNEL, CHAIN_COMPLETE)
                    } else {
                        renewIncompleteChain(identity)
                    }
                }
            }

        private suspend fun renewIncompleteChain(identity: ArcTunnelIdentity) {
            Log.w(TAG, CHAIN_INCOMPLETE)
            serverLog.log(ServerLogEntry.Type.TUNNEL, CHAIN_INCOMPLETE)
            _state.value = ArcTunnelRenewalState.Renewing
            try {
                val received = AtomicBoolean(false)
                val renewed =
                    withTimeoutOrNull(chainRenewalTimeoutMs) {
                        enrollment.renew(identity, null) {
                            received.set(true)
                            certificateReceived(CHAIN_RENEWED)
                        }
                    }
                check(renewed != null || received.get()) {
                    "no certificate within ${chainRenewalTimeoutMs / MS_PER_S} s"
                }
            } finally {
                _state.value = ArcTunnelRenewalState.Idle
            }
        }

        /** Logged here: the certificate is installed and saved from now on, and a stop would skip anything later. */
        private fun certificateReceived(message: String) {
            _state.value = ArcTunnelRenewalState.Installing
            Log.i(TAG, message)
            serverLog.log(ServerLogEntry.Type.TUNNEL, message)
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
            const val MS_PER_S = 1_000L

            /** Above the 400 s PROTOCOL.md §3 asks a phone to allow for one `/issue` request. */
            const val CHAIN_RENEWAL_TIMEOUT_MS = 480_000L
            const val RENEWED_AT_START = "Free E2E tunnel certificate renewed at start"
            const val CHAIN_COMPLETE = "Free E2E tunnel certificate chain checked: complete"
            const val CHAIN_INCOMPLETE = "Free E2E tunnel certificate chain is incomplete: renewing the certificate"
            const val CHAIN_RENEWED = "Free E2E tunnel certificate renewed to replace the incomplete chain"
        }
    }
