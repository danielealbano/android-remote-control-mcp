package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OAuthClientRepositoryImpl")
class OAuthClientRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: OAuthClientRepositoryImpl
    private val serverLog = RecordingServerLogRepository()
    private var counter = 0

    @BeforeEach
    fun setUp() {
        counter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "oauth_clients_$counter.preferences_pb") },
            )
        repository = OAuthClientRepositoryImpl(dataStore, serverLog)
    }

    @Test
    @DisplayName("register then get round-trips fields")
    fun registerRoundTrips() =
        testScope.runTest {
            val created =
                repository.register(
                    clientName = "Claude",
                    redirectUris = listOf("https://claude.ai/api/mcp/auth_callback"),
                    applicationType = "web",
                    logoUri = "https://logo.example/l.png",
                    nowMs = 100L,
                )
            val fetched = repository.getClient(created.clientId)
            assertNotNull(fetched)
            assertEquals("Claude", fetched?.clientName)
            assertEquals(listOf("https://claude.ai/api/mcp/auth_callback"), fetched?.redirectUris)
            assertEquals("web", fetched?.applicationType)
            assertEquals("https://logo.example/l.png", fetched?.logoUri)
            assertEquals(100L, fetched?.createdAtMs)
            assertEquals(100L, fetched?.lastUsedAtMs)
        }

    @Test
    @DisplayName("persists across new instance")
    fun persistsAcrossInstance() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            val newRepo = OAuthClientRepositoryImpl(dataStore, serverLog)
            assertNotNull(newRepo.getClient(created.clientId))
        }

    @Test
    @DisplayName("getClient served from snapshot after first load")
    fun getClientFromSnapshot() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            assertEquals(repository.getClient(created.clientId), repository.getClient(created.clientId))
        }

    @Test
    @DisplayName("evicts oldest unapproved beyond cap")
    fun evictsOldestUnapproved() =
        testScope.runTest {
            val ids = (1..OAuthPolicy.MAX_OAUTH_CLIENTS).map { i -> register(i.toLong()).clientId }
            // Mark the overall-oldest (first) as approved; the oldest UNAPPROVED is the second.
            repository.setRefreshJti(ids[0], "jti")
            register((OAuthPolicy.MAX_OAUTH_CLIENTS + 1).toLong())

            assertNotNull(repository.getClient(ids[0]), "approved oldest must survive")
            assertNull(repository.getClient(ids[1]), "oldest unapproved must be evicted")
        }

    @Test
    @DisplayName("evicts overall oldest when all approved")
    fun evictsOverallOldestWhenAllApproved() =
        testScope.runTest {
            val ids = (1..OAuthPolicy.MAX_OAUTH_CLIENTS).map { i -> register(i.toLong()).clientId }
            ids.forEach { repository.setRefreshJti(it, "jti") }
            val newest = register((OAuthPolicy.MAX_OAUTH_CLIENTS + 1).toLong())

            assertNull(repository.getClient(ids[0]), "overall oldest must be evicted")
            assertNotNull(repository.getClient(newest.clientId), "newcomer must be kept")
        }

    @Test
    @DisplayName("revoke removes client")
    fun revokeRemoves() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.revoke(created.clientId)
            assertNull(repository.getClient(created.clientId))
        }

    @Test
    @DisplayName("revoke logs client name; unknown id logs nothing")
    fun revokeLogsClientName() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)

            repository.revoke("does-not-exist")
            assertEquals(0, serverLog.ofType(ServerLogEntry.Type.OAUTH).size)

            repository.revoke(created.clientId)
            val entries = serverLog.ofType(ServerLogEntry.Type.OAUTH)
            assertEquals(1, entries.size)
            assertEquals("OAuth client revoked: Claude", entries.first().message)
        }

    @Test
    @DisplayName("touchLastUsed and setRefreshJti update")
    fun touchAndSetJtiUpdate() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.touchLastUsed(created.clientId, 999L)
            repository.setRefreshJti(created.clientId, "jti-1")
            val updated = repository.getClient(created.clientId)
            assertEquals(999L, updated?.lastUsedAtMs)
            assertEquals("jti-1", updated?.currentRefreshJti)
        }

    @Test
    @DisplayName("addConnection creates a CIMD row on the first login, then adds connections and refreshes its details")
    fun addConnectionCreatesThenAdds() =
        testScope.runTest {
            val first = repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c1", "j1", 10L)
            assertEquals(listOf("c1"), first.connections.map { it.id })
            assertEquals(10L, first.createdAtMs)
            val second = repository.addConnection(DOC, "Claude Code 2", OTHER_REDIRECTS, LOGO, "c2", "j2", 20L)
            assertEquals(listOf("c1", "c2"), second.connections.map { it.id })
            assertEquals("Claude Code 2", second.clientName)
            assertEquals(OTHER_REDIRECTS, second.redirectUris)
            assertEquals(LOGO, second.logoUri)
            assertEquals(10L, second.createdAtMs)
            assertEquals(20L, second.lastUsedAtMs)
            assertEquals(1, repository.getClients().size)
            assertNull(second.currentRefreshJti)
        }

    @Test
    @DisplayName("beyond 10 connections the least recently used other one is dropped and logged")
    fun dropsLeastRecentlyUsedConnection() =
        testScope.runTest {
            (1..OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT).forEach {
                repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c$it", "j$it", it.toLong())
            }
            // c1 is the oldest login but was used last: c2 is now the least recently used.
            repository.touchLastUsed(DOC, 500L, "c1")
            val after = repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c11", "j11", 600L)
            assertEquals(OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT, after.connections.size)
            assertFalse(after.connections.any { it.id == "c2" })
            assertTrue(after.connections.any { it.id == "c1" })
            assertTrue(after.connections.any { it.id == "c11" })
            assertEquals(
                "OAuth connection ended for Claude Code (claude.ai): more than 10 connections",
                serverLog.ofType(ServerLogEntry.Type.OAUTH).single().message,
            )
        }

    @Test
    @DisplayName("setRefreshJti and touchLastUsed with a connection change only that connection")
    fun connectionUpdates() =
        testScope.runTest {
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c2", "j2", 2L)
            repository.setRefreshJti(DOC, "j1b", "c1")
            repository.touchLastUsed(DOC, 77L, "c1")
            val client = repository.getClient(DOC)
            val byId = client?.connections?.associateBy { it.id }
            assertEquals("j1b", byId?.get("c1")?.refreshJti)
            assertEquals(77L, byId?.get("c1")?.lastUsedAtMs)
            assertEquals("j2", byId?.get("c2")?.refreshJti)
            assertEquals(2L, byId?.get("c2")?.lastUsedAtMs)
            assertEquals(77L, client?.lastUsedAtMs)
            assertNull(client?.currentRefreshJti)
        }

    @Test
    @DisplayName("revoking a CIMD client removes every connection and logs its display name")
    fun revokeCimdClient() =
        testScope.runTest {
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c2", "j2", 2L)
            repository.revoke(DOC)
            assertNull(repository.getClient(DOC))
            val logged = serverLog.ofType(ServerLogEntry.Type.OAUTH).last().message
            assertEquals("OAuth client revoked: Claude Code (claude.ai)", logged)
        }

    @Test
    @DisplayName("a CIMD row counts as approved: registration spam evicts unapproved DCR clients first")
    fun cimdRowIsApproved() =
        testScope.runTest {
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c1", "j1", 0L)
            (1L..OAuthPolicy.MAX_OAUTH_CLIENTS.toLong()).forEach { register(it) }
            assertNotNull(repository.getClient(DOC))
            assertEquals(OAuthPolicy.MAX_OAUTH_CLIENTS, repository.getClients().size)
        }

    @Test
    @DisplayName("connections persist across a new instance, and a stored list without them still loads")
    fun connectionsPersist() =
        testScope.runTest {
            repository.addConnection(DOC, "Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            val reloaded = OAuthClientRepositoryImpl(dataStore, serverLog)
            assertEquals(listOf(OAuthConnection("c1", "j1", 1L, 1L)), reloaded.getClient(DOC)?.connections)
            val legacy =
                kotlinx.serialization.json.Json.decodeFromString<List<OAuthClient>>(
                    """[{"clientId":"arc-1","redirectUris":["https://claude.ai/api/mcp/auth_callback"],""" +
                        """"createdAtMs":1,"lastUsedAtMs":2,"currentRefreshJti":"j"}]""",
                )
            assertEquals(emptyList<OAuthConnection>(), legacy.single().connections)
            assertTrue(legacy.single().isApproved)
        }

    private suspend fun register(nowMs: Long) =
        repository.register("c$nowMs", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, nowMs)

    private companion object {
        const val DOC = "https://claude.ai/oauth/claude-code-client-metadata"
        const val LOGO = "https://claude.ai/logo.png"
        val REDIRECTS = listOf("http://localhost/callback")
        val OTHER_REDIRECTS = listOf("http://127.0.0.1/callback")
    }
}
