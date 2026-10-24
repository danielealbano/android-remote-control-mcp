package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates an OAuth access token for `/mcp`: signature + type (via [JwtTokenService]), `aud` match
 * against the canonical resource, and the client still being in the registry (instant revocation).
 * On success it updates the client's last-used timestamp, DEBOUNCED so quick successive `/mcp` calls
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
        val valid = client != null && OAuthPolicy.resourceMatches(claims.audience, canonicalResource)
        if (valid) {
            val now = nowMs()
            var wonDebounce = false
            lastTouched.compute(claims.clientId) { _, previous ->
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
                    serverLog.log(
                        ServerLogEntry.Type.OAUTH,
                        "OAuth client '${client.clientName ?: client.clientId}' active again after " +
                            "${idleMs / MILLIS_PER_MINUTE} min",
                    )
                }
                clientRepository.touchLastUsed(claims.clientId, now)
                pruneDebounceMap()
            }
        }
        return valid
    }

    /** Bounds the debounce map so revoked/re-registered clients (each a new id) cannot grow it forever. */
    private suspend fun pruneDebounceMap() {
        if (lastTouched.size <= OAuthPolicy.MAX_OAUTH_CLIENTS) return
        val live = clientRepository.getClients().mapTo(HashSet()) { it.clientId }
        lastTouched.keys.retainAll(live)
        if (lastTouched.size > OAuthPolicy.MAX_OAUTH_CLIENTS) lastTouched.clear()
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 60_000L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
