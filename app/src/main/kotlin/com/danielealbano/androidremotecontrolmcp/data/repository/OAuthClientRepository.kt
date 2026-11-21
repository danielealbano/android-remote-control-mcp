package com.danielealbano.androidremotecontrolmcp.data.repository

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
     * Adds a login of the CIMD client [clientId] and returns the client. The first login creates its row (under the
     * same cap as [register]); later ones refresh its name, redirect URIs and logo from the latest document. Beyond
     * [com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT] connections the
     * least recently used other one is dropped.
     */
    suspend fun addConnection(
        clientId: String,
        clientName: String,
        redirectUris: List<String>,
        logoUri: String?,
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

    /** Sets the current refresh-token jti (rotation): the client's (DCR), or the [connectionId] connection's (CIMD). */
    suspend fun setRefreshJti(
        clientId: String,
        jti: String,
        connectionId: String? = null,
    )
}
