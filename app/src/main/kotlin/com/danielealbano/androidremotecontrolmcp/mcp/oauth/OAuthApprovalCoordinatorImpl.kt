package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, thread-safe [OAuthApprovalCoordinator]. Pending approvals are bounded
 * ([OAuthPolicy.MAX_PENDING_APPROVALS]) and match codes are unique among pending entries (possible
 * since the cap is below [OAuthPolicy.MATCH_CODE_MODULO]).
 */
@Singleton
class OAuthApprovalCoordinatorImpl
    @Inject
    constructor(
        private val serverLog: ServerLogRepository,
    ) : OAuthApprovalCoordinator {
        private class Entry(
            val approval: PendingApproval,
            var state: ApprovalState,
        )

        private val mutex = Mutex()
        private val entries = LinkedHashMap<String, Entry>()
        private val pending = MutableStateFlow<List<PendingApproval>>(emptyList())
        private val secureRandom = SecureRandom()

        override fun observePending(): StateFlow<List<PendingApproval>> = pending.asStateFlow()

        override suspend fun createPending(
            request: ApprovalRequest,
            nowMs: Long,
        ): PendingApproval =
            mutex.withLock {
                purgeExpiredLocked(nowMs)
                dropOldestPendingIfAtCapLocked()
                val matchCode = drawUniqueMatchCodeLocked()
                val approval =
                    PendingApproval(
                        id = randomId(),
                        clientName = request.clientName,
                        redirectHost = request.redirectHost,
                        matchCode = matchCode,
                        expiresAtMs = nowMs + OAuthPolicy.APPROVAL_WINDOW_MS,
                        logoUri = request.logoUri,
                        clientIp = request.clientIp,
                        clientGeo = request.clientGeo,
                        loopbackRedirect = request.loopbackRedirect,
                    )
                entries[approval.id] = Entry(approval, ApprovalState.PENDING)
                refreshPendingLocked()
                approval
            }

        override suspend fun approve(
            id: String,
            nowMs: Long,
        ) {
            mutex.withLock {
                entries[id]?.takeIf { it.state == ApprovalState.PENDING }?.let { entry ->
                    // Expiry wins over a late approval: never hand out an authorization past the window.
                    entry.state =
                        if (nowMs >= entry.approval.expiresAtMs) ApprovalState.EXPIRED else ApprovalState.APPROVED
                    if (entry.state == ApprovalState.APPROVED) {
                        serverLog.log(
                            ServerLogEntry.Type.OAUTH,
                            "OAuth authorization approved for ${entry.approval.clientName}",
                        )
                    } else {
                        serverLog.log(
                            ServerLogEntry.Type.OAUTH,
                            "OAuth authorization for ${entry.approval.clientName} expired",
                        )
                    }
                }
                refreshPendingLocked()
            }
        }

        override suspend fun deny(id: String) {
            mutex.withLock {
                entries[id]?.takeIf { it.state == ApprovalState.PENDING }?.let {
                    it.state = ApprovalState.DENIED
                    serverLog.log(
                        ServerLogEntry.Type.OAUTH,
                        "OAuth authorization denied for ${it.approval.clientName}",
                    )
                }
                refreshPendingLocked()
            }
        }

        override suspend fun stateOf(
            id: String,
            nowMs: Long,
        ): ApprovalState =
            mutex.withLock {
                val entry = entries[id] ?: return@withLock ApprovalState.EXPIRED
                if (entry.state == ApprovalState.PENDING && nowMs >= entry.approval.expiresAtMs) {
                    entry.state = ApprovalState.EXPIRED
                    serverLog.log(
                        ServerLogEntry.Type.OAUTH,
                        "OAuth authorization for ${entry.approval.clientName} expired",
                    )
                    refreshPendingLocked()
                }
                entry.state
            }

        private fun purgeExpiredLocked(nowMs: Long) {
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMs >= entry.approval.expiresAtMs) {
                    if (entry.state == ApprovalState.PENDING) {
                        serverLog.log(
                            ServerLogEntry.Type.OAUTH,
                            "OAuth authorization for ${entry.approval.clientName} expired",
                        )
                    }
                    iterator.remove()
                }
            }
        }

        private fun dropOldestPendingIfAtCapLocked() {
            val pendingEntries = entries.values.filter { it.state == ApprovalState.PENDING }
            if (pendingEntries.size >= OAuthPolicy.MAX_PENDING_APPROVALS) {
                // LinkedHashMap preserves insertion order, so the first pending entry is the oldest.
                val oldest = pendingEntries.first()
                entries.remove(oldest.approval.id)
            }
        }

        private fun drawUniqueMatchCodeLocked(): String {
            val used =
                entries.values
                    .filter { it.state == ApprovalState.PENDING }
                    .map { it.approval.matchCode }
                    .toSet()
            var code: String
            do {
                code = "%02d".format(secureRandom.nextInt(OAuthPolicy.MATCH_CODE_MODULO))
            } while (code in used)
            return code
        }

        private fun refreshPendingLocked() {
            pending.value = entries.values.filter { it.state == ApprovalState.PENDING }.map { it.approval }
        }

        private fun randomId(): String {
            val bytes = ByteArray(ID_BYTES).also { secureRandom.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val ID_BYTES = 16
        }
    }
