package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthConnection
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.loggable
import kotlinx.serialization.json.Json

/** The persisted client list, or none when [stored] is absent or unreadable. */
internal fun decodeClients(
    json: Json,
    stored: String?,
): List<OAuthClient> =
    stored
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { json.decodeFromString<List<OAuthClient>>(it) }.getOrNull() }
        .orEmpty()

/**
 * This list plus [newcomer] within [OAuthPolicy.MAX_OAUTH_CLIENTS]. The victim is chosen among the EXISTING clients
 * only (the newcomer is always kept): the oldest unapproved one (never completed a token grant), or the overall oldest
 * only when every existing client is approved — so registration spam (which adds unapproved entries) can never evict
 * an approved client. The evicted client, if any, is handed to [onEvicted].
 */
internal fun List<OAuthClient>.withinCap(
    newcomer: OAuthClient,
    onEvicted: (OAuthClient) -> Unit,
): List<OAuthClient> {
    val combined = this + newcomer
    val victim =
        filterNot { it.isApproved }
            .ifEmpty { this }
            .minByOrNull { it.lastUsedAtMs }
            .takeIf { combined.size > OAuthPolicy.MAX_OAUTH_CLIENTS }
    return if (victim != null) {
        onEvicted(victim)
        combined - victim
    } else {
        combined
    }
}

/**
 * Logs that [victim] was evicted to make room, when it was approved: its tokens (for CIMD, every connection's) stop
 * working. An unapproved victim is not logged, so registration spam does not fill the log too.
 */
internal fun ServerLogRepository.logEvicted(victim: OAuthClient) {
    if (victim.isApproved) {
        log(
            ServerLogEntry.Type.OAUTH,
            "OAuth client removed (more than ${OAuthPolicy.MAX_OAUTH_CLIENTS} clients): " +
                loggable(victim.clientName ?: victim.clientId),
        )
    }
}

/** This list with the client [clientId] replaced by [change] of it, or null when it is not in the list. */
internal fun List<OAuthClient>.replacing(
    clientId: String,
    change: (OAuthClient) -> OAuthClient,
): List<OAuthClient>? {
    val found = any { it.clientId == clientId }
    return if (found) map { if (it.clientId == clientId) change(it) else it } else null
}

/** These connections with the one [connectionId] replaced by [change] of it; unchanged when there is none. */
internal fun List<OAuthConnection>.updating(
    connectionId: String?,
    change: (OAuthConnection) -> OAuthConnection,
): List<OAuthConnection> = map { if (it.id == connectionId) change(it) else it }

/**
 * These connections with room for one more within [OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT]: the least recently used
 * one is dropped when they are already at the limit.
 */
internal fun List<OAuthConnection>.withinConnectionCap(): List<OAuthConnection> =
    if (size < OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT) this else this - minBy { it.lastUsedAtMs }

/**
 * The refresh jti this client accepts: its own (DCR, [connectionId] null) or the connection's (CIMD). A CIMD client
 * never accepts a token without a connection, whatever is stored.
 */
internal fun OAuthClient.refreshJtiOf(connectionId: String?): String? =
    when {
        connectionId != null -> connections.firstOrNull { it.id == connectionId }?.refreshJti
        OAuthPolicy.isMetadataDocumentClientId(clientId) -> null
        else -> currentRefreshJti
    }

/** This client with the refresh jti set to [jti]: its own (DCR, [connectionId] null) or the connection's (CIMD). */
internal fun OAuthClient.withRefreshJti(
    jti: String,
    connectionId: String?,
): OAuthClient =
    if (connectionId == null) {
        copy(currentRefreshJti = jti)
    } else {
        copy(connections = connections.updating(connectionId) { it.copy(refreshJti = jti) })
    }

/** This client used at [nowMs], and its [connectionId] connection too (CIMD). */
internal fun OAuthClient.touched(
    nowMs: Long,
    connectionId: String?,
): OAuthClient {
    val touchedConnections = connections.updating(connectionId) { it.copy(lastUsedAtMs = nowMs) }
    return copy(lastUsedAtMs = nowMs, connections = touchedConnections)
}
