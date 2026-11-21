package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates an OAuth access token for `/mcp`: signature + type (via [JwtTokenService]), `aud` match
 * against the canonical resource, and the client still being in the registry (instant revocation) — for a
 * CIMD client, its connection too. On success it updates the client's (and connection's) last-used timestamp,
 * DEBOUNCED so quick successive `/mcp` calls
 * do not write to DataStore on every request. Extracted (not inlined in the server) so it is unit
 * testable and shared by production and the test helper.
 */
class OAuthAccessValidator(
    private val tokenService: JwtTokenService,
    private val clientRepository: OAuthClientRepository,
    private val serverLog: ServerLogRepository,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lastTouched = ConcurrentHashMap<String, Long>()

    suspend fun validate(
        token: String,
        canonicalResource: String,
    ): Boolean {
        val claims = tokenService.verifyAccessToken(token) ?: return false
        val client = clientRepository.getClient(claims.clientId)
        val valid =
            client != null &&
                isLiveConnection(client, claims.connectionId) &&
                OAuthPolicy.resourceMatches(claims.audience, canonicalResource)
        if (valid) {
            val now = nowMs()
            var wonDebounce = false
            lastTouched.compute(debounceKey(claims.clientId, claims.connectionId)) { _, previous ->
                if (previous == null || now - previous >= debounceMs) {
                    wonDebounce = true
                    now
                } else {
                    previous
                }
            }
            if (wonDebounce) {
                val idleMs = now - client.lastUsedAtMs
                if (idleMs >= OAuthPolicy.IDLE_SESSION_LOG_THRESHOLD_MS) {
                    val name = OAuthPolicy.clientDisplayName(client.clientId, client.clientName) ?: client.clientId
                    serverLog.log(
                        ServerLogEntry.Type.OAUTH,
                        "OAuth client '$name' active again after " +
                            "${idleMs / MILLIS_PER_MINUTE} min",
                    )
                }
                clientRepository.touchLastUsed(claims.clientId, now, claims.connectionId)
                pruneDebounceMap()
            }
        }
        return valid
    }

    /** A CIMD client's token must name one of its connections; a DCR client's names none. */
    private fun isLiveConnection(
        client: OAuthClient,
        connectionId: String?,
    ): Boolean =
        if (connectionId == null) {
            !OAuthPolicy.isMetadataDocumentClientId(client.clientId)
        } else {
            client.connections.any { it.id == connectionId }
        }

    private fun debounceKey(
        clientId: String,
        connectionId: String?,
    ): String = if (connectionId == null) clientId else "$clientId#$connectionId"

    /** Bounds the debounce map so revoked/re-registered clients (each a new id) cannot grow it forever. */
    private suspend fun pruneDebounceMap() {
        if (lastTouched.size <= MAX_DEBOUNCE_KEYS) return
        val live =
            clientRepository.getClients().flatMapTo(HashSet()) { client ->
                if (client.connections.isEmpty()) {
                    listOf(client.clientId)
                } else {
                    client.connections.map { debounceKey(client.clientId, it.id) }
                }
            }
        lastTouched.keys.retainAll(live)
        if (lastTouched.size > MAX_DEBOUNCE_KEYS) lastTouched.clear()
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 60_000L
        const val MAX_DEBOUNCE_KEYS = OAuthPolicy.MAX_OAUTH_CLIENTS * OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
