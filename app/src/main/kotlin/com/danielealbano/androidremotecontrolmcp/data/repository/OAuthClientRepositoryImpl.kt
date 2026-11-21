package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.di.OAuthClientsDataStore
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ClientMetadataDocument
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthConnection
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.loggable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OAuthClientRepository] backed by a dedicated Preferences DataStore (`oauth_clients`). The full list
 * is kept as an in-memory [MutableStateFlow] snapshot (seeded once from disk under a [Mutex]); every
 * mutation is write-through (snapshot + disk). Reads are served from the snapshot — no per-call disk
 * decode on the `/mcp` hot path.
 */
@Singleton
class OAuthClientRepositoryImpl
    @Inject
    constructor(
        @OAuthClientsDataStore private val dataStore: DataStore<Preferences>,
        private val serverLog: ServerLogRepository,
    ) : OAuthClientRepository {
        private val json = Json { ignoreUnknownKeys = true }
        private val snapshot = MutableStateFlow<List<OAuthClient>>(emptyList())
        private val mutex = Mutex()
        private var seeded = false

        /** Loads the persisted list into the snapshot once. MUST be called with [mutex] held. */
        private suspend fun seedLocked() {
            if (seeded) return
            val stored = dataStore.data.first()[OAUTH_CLIENTS_KEY]
            snapshot.value = decodeClients(json, stored)
            seeded = true
        }

        /** Persists [list] to the snapshot and disk. MUST be called with [mutex] held. */
        private suspend fun persist(list: List<OAuthClient>) {
            snapshot.value = list
            dataStore.edit { it[OAUTH_CLIENTS_KEY] = json.encodeToString(list) }
        }

        override fun observeClients(): Flow<List<OAuthClient>> =
            flow {
                mutex.withLock { seedLocked() }
                emitAll(snapshot)
            }

        override suspend fun getClients(): List<OAuthClient> =
            mutex.withLock {
                seedLocked()
                snapshot.value
            }

        override suspend fun getClient(clientId: String): OAuthClient? =
            mutex.withLock {
                seedLocked()
                snapshot.value.firstOrNull { it.clientId == clientId }
            }

        override suspend fun register(
            clientName: String?,
            redirectUris: List<String>,
            applicationType: String?,
            logoUri: String?,
            nowMs: Long,
        ): OAuthClient =
            mutex.withLock {
                seedLocked()
                val newClient =
                    OAuthClient(
                        clientId = "arc-${UUID.randomUUID()}",
                        clientName = clientName,
                        redirectUris = redirectUris,
                        applicationType = applicationType,
                        logoUri = logoUri,
                        createdAtMs = nowMs,
                        lastUsedAtMs = nowMs,
                        currentRefreshJti = null,
                    )
                persist(snapshot.value.withinCap(newClient))
                newClient
            }

        override suspend fun addConnection(
            document: ClientMetadataDocument,
            iconUrl: String?,
            connectionId: String,
            refreshJti: String,
            nowMs: Long,
        ): OAuthClient =
            mutex.withLock {
                seedLocked()
                val clientId = document.clientId
                val clientName = document.clientName
                val connection = OAuthConnection(connectionId, refreshJti, createdAtMs = nowMs, lastUsedAtMs = nowMs)
                val existing = snapshot.value.firstOrNull { it.clientId == clientId }
                val client =
                    existing?.let {
                        val kept = it.connections.withinConnectionCap()
                        if (kept.size < it.connections.size) {
                            serverLog.log(
                                ServerLogEntry.Type.OAUTH,
                                "OAuth connection ended for ${loggable(clientName)}: " +
                                    "more than ${OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT} connections",
                            )
                        }
                        it.copy(
                            clientName = clientName,
                            redirectUris = document.redirectUris,
                            logoUri = document.logoUri,
                            lastUsedAtMs = nowMs,
                            connections = kept + connection,
                            iconUrl = iconUrl,
                        )
                    } ?: OAuthClient(
                        clientId = clientId,
                        clientName = clientName,
                        redirectUris = document.redirectUris,
                        logoUri = document.logoUri,
                        createdAtMs = nowMs,
                        lastUsedAtMs = nowMs,
                        connections = listOf(connection),
                        iconUrl = iconUrl,
                    )
                persist(snapshot.value.replacing(clientId) { client } ?: snapshot.value.withinCap(client))
                client
            }

        override suspend fun revoke(clientId: String) {
            mutex.withLock {
                seedLocked()
                val removed = snapshot.value.firstOrNull { it.clientId == clientId }
                val updated = snapshot.value.filterNot { it.clientId == clientId }
                if (updated.size != snapshot.value.size) {
                    persist(updated)
                    val name = loggable(removed?.clientName ?: clientId)
                    serverLog.log(ServerLogEntry.Type.OAUTH, "OAuth client revoked: $name")
                }
            }
        }

        override suspend fun touchLastUsed(
            clientId: String,
            nowMs: Long,
            connectionId: String?,
        ) {
            mutex.withLock {
                seedLocked()
                snapshot.value.replacing(clientId) { it.touched(nowMs, connectionId) }?.let { persist(it) }
            }
        }

        override suspend fun recordGrant(
            clientId: String,
            refreshJti: String,
            iconUrl: String?,
            nowMs: Long,
        ) {
            mutex.withLock {
                seedLocked()
                snapshot.value
                    .replacing(clientId) { client ->
                        client.withRefreshJti(refreshJti, null).copy(iconUrl = iconUrl, lastUsedAtMs = nowMs)
                    }?.let { persist(it) }
            }
        }

        override suspend fun rotateRefreshJti(
            clientId: String,
            connectionId: String?,
            expectedJti: String,
            newJti: String,
            nowMs: Long,
        ): Boolean =
            mutex.withLock {
                seedLocked()
                val client = snapshot.value.firstOrNull { it.clientId == clientId }
                val rotates = client != null && client.refreshJtiOf(connectionId) == expectedJti
                if (rotates) {
                    snapshot.value
                        .replacing(clientId) { it.withRefreshJti(newJti, connectionId).touched(nowMs, connectionId) }
                        ?.let { persist(it) }
                }
                rotates
            }

        private companion object {
            private val OAUTH_CLIENTS_KEY = stringPreferencesKey("oauth_clients")
        }
    }
