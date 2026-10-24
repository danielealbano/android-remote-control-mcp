package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArcTunnelEnrollmentImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val keys: ArcTunnelKeyStore,
        private val serverLog: ServerLogRepository,
    ) : ArcTunnelEnrollment {
        private val _state = MutableStateFlow<ArcTunnelEnrollmentState>(ArcTunnelEnrollmentState.Idle)
        override val state: StateFlow<ArcTunnelEnrollmentState> = _state.asStateFlow()
        private val mutex = Mutex()
        private val requestInFlight = AtomicBoolean(false)
        private val stores = ConcurrentHashMap<String, ArcTunnelIdentityStore>()
        private val nets = ConcurrentHashMap<ArcTunnelEnv, ArcTunnelNet>()
        private val enroller = ArcTunnelEnroller(keys)
        private val flow = ArcTunnelEnrollmentFlow(keys, enroller)

        /** One store instance per environment, so its `@Synchronized` methods really serialize all writers. */
        override fun store(env: ArcTunnelEnv): ArcTunnelIdentityStore =
            stores.getOrPut(env.hosts.envKey) {
                ArcTunnelIdentityStore(File(context.filesDir, STORE_DIR), env.hosts, keys)
            }

        /** One network layer (one connection pool) per environment, shared by requests and sessions. */
        override fun net(env: ArcTunnelEnv): ArcTunnelNet =
            nets.getOrPut(env) { ArcTunnelNet(env.trustedCaPem, ArcTunnelEdgeOverride.parse(env.edgeOverride)) }

        override fun savedUrl(env: ArcTunnelEnv): String? =
            store(env).load()?.takeIf { it.publicChain.isNotEmpty() }?.let { env.hosts.publicUrl(it.name) }

        override fun savedCtAlert(env: ArcTunnelEnv): ArcTunnelCtAlert? =
            store(env).load()?.takeIf { it.ctAlertPubkey.isNotEmpty() }?.let {
                ArcTunnelCtAlert(env.hosts.fqdn(it.name), it.ctAlertPubkey)
            }

        override fun requestEnroll(env: ArcTunnelEnv) {
            if (!ArcTunnelEnrollService.start(context, env, rotate = false)) {
                _state.value = ArcTunnelEnrollmentState.Failed(START_REFUSED)
            }
        }

        override fun requestRotate(env: ArcTunnelEnv) {
            if (!ArcTunnelEnrollService.start(context, env, rotate = true)) {
                _state.value = ArcTunnelEnrollmentState.Failed(START_REFUSED)
            }
        }

        override suspend fun execute(
            env: ArcTunnelEnv,
            rotate: Boolean,
        ) {
            if (!requestInFlight.compareAndSet(false, true)) return
            try {
                mutex.withLock {
                    _state.value = ArcTunnelEnrollmentState.Enrolling
                    val result =
                        runCatching {
                            val store = store(env)
                            if (rotate) flow.forget(env, store)
                            flow.enrollOrResume(env, store, net(env))
                        }
                    result.onSuccess {
                        _state.value = ArcTunnelEnrollmentState.Idle
                        serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel enrolled: ${it.publicUrl()}")
                    }
                    result.onFailure {
                        if (it is CancellationException) {
                            _state.value = ArcTunnelEnrollmentState.Idle
                            throw it
                        }
                        Log.e(TAG, "enrollment failed", it)
                        val reason = it.message ?: it.javaClass.simpleName
                        _state.value = ArcTunnelEnrollmentState.Failed(reason)
                        serverLog.log(ServerLogEntry.Type.TUNNEL, "Free E2E tunnel enrollment failed: $reason")
                    }
                }
            } finally {
                requestInFlight.set(false)
            }
        }

        override suspend fun readyIdentity(env: ArcTunnelEnv): ArcTunnelIdentity? =
            mutex.withLock {
                val record = withContext(Dispatchers.IO) { store(env).load() } ?: return@withLock null
                check(!identityCertificateExpired(record)) { "identity certificate expired; get a new name" }
                restoreArcTunnelIdentity(env, record, keys, net(env))
            }

        override suspend fun renew(
            identity: ArcTunnelIdentity,
            nonce: String?,
        ) {
            mutex.withLock {
                val store = store(ArcTunnelEnv(identity.hosts.baseAddress))
                check(store.holds(identity)) { "identity ${identity.name} was forgotten; renewal skipped" }
                enroller.issue(identity, nonce)
                if (store.holds(identity)) {
                    withContext(Dispatchers.IO) { store.save(identity) }
                    // The saved name has a certificate now: a setup failure still shown for it is over.
                    val failed = _state.value as? ArcTunnelEnrollmentState.Failed
                    if (failed != null) _state.compareAndSet(failed, ArcTunnelEnrollmentState.Idle)
                }
            }
        }

        override suspend fun dismissCtAlert(env: ArcTunnelEnv) {
            withContext(Dispatchers.IO) { store(env).clearCtAlert() }
        }

        companion object {
            private const val TAG = "MCP:ArcTunnelEnrollment"
            const val STORE_DIR = "arc-tunnel"
            const val START_REFUSED = "Open the app and try again (background start refused)"
        }
    }
