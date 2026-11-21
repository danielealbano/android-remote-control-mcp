package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ClientMetadataDocument
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
import kotlinx.coroutines.flow.Flow

/**
 * Persisted, revocable registry of OAuth clients: DCR registrations and CIMD clients ([OAuthClient]). Backed by a
 * DEDICATED Preferences DataStore (not the shared settings store). An in-memory snapshot serves `/mcp` hot-path
 * lookups so no per-request JSON decode from disk is needed.
 */
interface OAuthClientRepository {
    /** Observes the current client list (in-memory snapshot, write-through to disk). */
    fun observeClients(): Flow<List<OAuthClient>>

    /** Returns the current client list. */
    suspend fun getClients(): List<OAuthClient>

    /** Returns the client with [clientId], or null. */
    suspend fun getClient(clientId: String): OAuthClient?

    /**
     * Registers a new client and returns it. Enforces the cap by evicting the oldest-by-last-used
     * client that never completed a token grant (no refresh jti, no connection); only when every client
     * completed one does it evict the overall oldest — so registration spam cannot evict an approved client.
     */
    suspend fun register(
        clientName: String?,
        redirectUris: List<String>,
        applicationType: String?,
        logoUri: String?,
        nowMs: Long,
    ): OAuthClient

    /**
     * Adds a login of the CIMD client of [document] and returns the client. The first login creates its row (under the
     * same cap as [register]); later ones refresh its name, redirect URIs and logo from the latest document, and its
     * [iconUrl] to the one this approval showed. Beyond
     * [com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT] connections the
     * least recently used other one is dropped.
     */
    suspend fun addConnection(
        document: ClientMetadataDocument,
        iconUrl: String?,
        connectionId: String,
        refreshJti: String,
        nowMs: Long,
    ): OAuthClient

    /** Removes the client (its tokens stop validating immediately); for CIMD, all its connections. */
    suspend fun revoke(clientId: String)

    /** Updates the client's last-used timestamp, and the connection's when [connectionId] is given (CIMD). */
    suspend fun touchLastUsed(
        clientId: String,
        nowMs: Long,
        connectionId: String? = null,
    )

    /**
     * Records a DCR client's code grant: [refreshJti] becomes its single valid refresh jti, [iconUrl] the icon this
     * approval showed, and [nowMs] its last use.
     */
    suspend fun recordGrant(
        clientId: String,
        refreshJti: String,
        iconUrl: String?,
        nowMs: Long,
    )

    /**
     * Refresh rotation in one step: when the current refresh jti — the client's (DCR, [connectionId] null) or the
     * [connectionId] connection's (CIMD) — is [expectedJti], replaces it with [newJti], updates last use and returns
     * true. Returns false and changes nothing otherwise: the token was already rotated (or is used concurrently), the
     * client or connection is gone, or the kind does not match (a CIMD client has no own jti, a DCR one has no
     * connections).
     */
    suspend fun rotateRefreshJti(
        clientId: String,
        connectionId: String?,
        expectedJti: String,
        newJti: String,
        nowMs: Long,
    ): Boolean
}
