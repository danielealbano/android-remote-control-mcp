package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepositoryImpl
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
            repository.recordGrant(ids[0], "jti", null, 1L)
            register((OAuthPolicy.MAX_OAUTH_CLIENTS + 1).toLong())

            assertNotNull(repository.getClient(ids[0]), "approved oldest must survive")
            assertNull(repository.getClient(ids[1]), "oldest unapproved must be evicted")
        }

    @Test
    @DisplayName("evicts overall oldest when all approved")
    fun evictsOverallOldestWhenAllApproved() =
        testScope.runTest {
            val ids = (1..OAuthPolicy.MAX_OAUTH_CLIENTS).map { i -> register(i.toLong()).clientId }
            ids.forEachIndexed { index, id -> repository.recordGrant(id, "jti", null, index + 1L) }
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
    @DisplayName("touchLastUsed and recordGrant update; recordGrant keeps the icon the approval showed")
    fun touchAndRecordGrantUpdate() =
        testScope.runTest {
            val created =
                repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.touchLastUsed(created.clientId, 999L)
            assertEquals(999L, repository.getClient(created.clientId)?.lastUsedAtMs)
            repository.recordGrant(created.clientId, "jti-1", ICON, 1000L)
            val updated = repository.getClient(created.clientId)
            assertEquals(1000L, updated?.lastUsedAtMs)
            assertEquals("jti-1", updated?.currentRefreshJti)
            assertEquals(ICON, updated?.iconUrl)
        }

    @Test
    @DisplayName("addConnection creates a CIMD row on the first login, then adds connections and refreshes its details")
    fun addConnectionCreatesThenAdds() =
        testScope.runTest {
            val first = connect("Claude Code", REDIRECTS, null, "c1", "j1", 10L)
            assertEquals(listOf("c1"), first.connections.map { it.id })
            assertEquals(10L, first.createdAtMs)
            val second = connect("Claude Code 2", OTHER_REDIRECTS, LOGO, "c2", "j2", 20L)
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
                connect("Claude Code", REDIRECTS, null, "c$it", "j$it", it.toLong())
            }
            // c1 is the oldest login but was used last: c2 is now the least recently used.
            repository.touchLastUsed(DOC, 500L, "c1")
            val after = connect("Claude Code", REDIRECTS, null, "c11", "j11", 600L)
            assertEquals(OAuthPolicy.MAX_CONNECTIONS_PER_CLIENT, after.connections.size)
            assertFalse(after.connections.any { it.id == "c2" })
            assertTrue(after.connections.any { it.id == "c1" })
            assertTrue(after.connections.any { it.id == "c11" })
            assertEquals(
                "OAuth connection ended for Claude Code: more than 10 connections",
                serverLog.ofType(ServerLogEntry.Type.OAUTH).single().message,
            )
        }

    @Test
    @DisplayName("touchLastUsed with a connection changes only that connection")
    fun connectionUpdates() =
        testScope.runTest {
            connect("Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            connect("Claude Code", REDIRECTS, null, "c2", "j2", 2L)
            repository.touchLastUsed(DOC, 77L, "c1")
            val client = repository.getClient(DOC)
            val byId = client?.connections?.associateBy { it.id }
            assertEquals("j1", byId?.get("c1")?.refreshJti)
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
            connect("Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            connect("Claude Code", REDIRECTS, null, "c2", "j2", 2L)
            repository.revoke(DOC)
            assertNull(repository.getClient(DOC))
            val logged = serverLog.ofType(ServerLogEntry.Type.OAUTH).last().message
            assertEquals("OAuth client revoked: Claude Code", logged)
        }

    @Test
    @DisplayName("a CIMD row counts as approved: registration spam evicts unapproved DCR clients first")
    fun cimdRowIsApproved() =
        testScope.runTest {
            connect("Claude Code", REDIRECTS, null, "c1", "j1", 0L)
            (1L..OAuthPolicy.MAX_OAUTH_CLIENTS.toLong()).forEach { register(it) }
            assertNotNull(repository.getClient(DOC))
            assertEquals(OAuthPolicy.MAX_OAUTH_CLIENTS, repository.getClients().size)
        }

    @Test
    @DisplayName("the first CIMD login into a full registry evicts the least recently used unapproved DCR client")
    fun cimdLoginIntoFullRegistry() =
        testScope.runTest {
            val ids = (1L..OAuthPolicy.MAX_OAUTH_CLIENTS.toLong()).map { register(it).clientId }
            repository.recordGrant(ids[0], "jti", null, 1L)
            connect("Claude Code", REDIRECTS, null, "c1", "j1", 100L)
            assertEquals(OAuthPolicy.MAX_OAUTH_CLIENTS, repository.getClients().size)
            assertNotNull(repository.getClient(DOC))
            assertNotNull(repository.getClient(ids[0]), "the approved oldest must survive")
            assertNull(repository.getClient(ids[1]), "the oldest unapproved must be evicted")
        }

    @Test
    @DisplayName("connections persist across a new instance")
    fun connectionsPersist() =
        testScope.runTest {
            connect("Claude Code", REDIRECTS, null, "c1", "j1", 1L)
            val reloaded = OAuthClientRepositoryImpl(dataStore, serverLog)
            assertEquals(listOf(OAuthConnection("c1", "j1", 1L, 1L)), reloaded.getClient(DOC)?.connections)
        }

    @Test
    @DisplayName("a list stored before CIMD (no connections field) loads through the repository")
    fun legacyListLoads() =
        testScope.runTest {
            dataStore.edit {
                it[stringPreferencesKey("oauth_clients")] =
                    """[{"clientId":"arc-1","redirectUris":["https://claude.ai/api/mcp/auth_callback"],""" +
                    """"createdAtMs":1,"lastUsedAtMs":2,"currentRefreshJti":"j"}]"""
            }
            val legacy = OAuthClientRepositoryImpl(dataStore, serverLog).getClient("arc-1")
            assertEquals(emptyList<OAuthConnection>(), legacy?.connections)
            assertEquals("j", legacy?.currentRefreshJti)
            assertEquals(true, legacy?.isApproved)
        }

    @Test
    @DisplayName("rotateRefreshJti swaps only the expected jti of the right kind and touches last use")
    fun rotateRefreshJti() =
        testScope.runTest {
            val dcr = repository.register("Claude", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, 1L)
            repository.recordGrant(dcr.clientId, "j0", null, 1L)
            assertTrue(repository.rotateRefreshJti(dcr.clientId, null, "j0", "j1", 50L))
            assertFalse(repository.rotateRefreshJti(dcr.clientId, null, "j0", "j2", 60L))
            assertFalse(repository.rotateRefreshJti(dcr.clientId, "c1", "j1", "j3", 60L))
            assertEquals("j1", repository.getClient(dcr.clientId)?.currentRefreshJti)
            assertEquals(50L, repository.getClient(dcr.clientId)?.lastUsedAtMs)

            connect("Claude Code", REDIRECTS, null, "c1", "k0", 1L)
            assertFalse(repository.rotateRefreshJti(DOC, null, "k0", "k1", 70L))
            assertFalse(repository.rotateRefreshJti(DOC, "c9", "k0", "k1", 70L))
            assertTrue(repository.rotateRefreshJti(DOC, "c1", "k0", "k1", 80L))
            val connection = repository.getClient(DOC)?.connections?.single()
            assertEquals("k1", connection?.refreshJti)
            assertEquals(80L, connection?.lastUsedAtMs)
            assertFalse(repository.rotateRefreshJti("https://claude.ai/oauth/gone", "c1", "k1", "k2", 90L))
        }

    @Test
    @DisplayName("concurrent rotations of the same refresh jti: exactly one wins")
    fun concurrentRotationsOneWins() =
        testScope.runTest {
            connect("Claude Code", REDIRECTS, null, "c1", "k0", 1L)
            val results =
                withContext(Dispatchers.Default) {
                    (1..8)
                        .map { attempt -> async { repository.rotateRefreshJti(DOC, "c1", "k0", "k$attempt", 2L) } }
                        .awaitAll()
                }
            assertEquals(1, results.count { it })
        }

    @Test
    @DisplayName("addConnection keeps the icon of the latest approval on the CIMD row")
    fun addConnectionKeepsIcon() =
        testScope.runTest {
            assertEquals(ICON, connect("Claude Code", REDIRECTS, null, "c1", "j1", 1L, ICON).iconUrl)
            assertEquals(
                "https://claude.ai/other.png",
                connect("Claude Code", REDIRECTS, null, "c2", "j2", 2L, "https://claude.ai/other.png").iconUrl,
            )
            assertEquals("https://claude.ai/other.png", repository.getClient(DOC)?.iconUrl)
        }

    @Test
    @DisplayName("a CIMD client never rotates a token without a connection, even with an own jti stored")
    fun cimdNeverRotatesWithoutConnection() =
        testScope.runTest {
            connect("Claude Code", REDIRECTS, null, "c1", "k0", 1L)
            // Only a stray write could give a CIMD row its own jti; it must still not rotate.
            repository.recordGrant(DOC, "own", null, 2L)
            assertFalse(repository.rotateRefreshJti(DOC, null, "own", "own2", 3L))
        }

    /** A login of the Claude Code document [DOC]. */
    private suspend fun connect(
        name: String,
        redirects: List<String>,
        logo: String?,
        id: String,
        jti: String,
        nowMs: Long,
        icon: String? = null,
    ) = repository.addConnection(ClientMetadataDocument(DOC, name, redirects, logo), icon, id, jti, nowMs)

    private suspend fun register(nowMs: Long) =
        repository.register("c$nowMs", listOf("https://claude.ai/api/mcp/auth_callback"), "web", null, nowMs)

    private companion object {
        const val DOC = "https://claude.ai/oauth/claude-code-client-metadata"
        const val LOGO = "https://claude.ai/logo.png"
        const val ICON = "https://icons.duckduckgo.com/ip3/claude.ai.ico"
        val REDIRECTS = listOf("http://localhost/callback")
        val OTHER_REDIRECTS = listOf("http://127.0.0.1/callback")
    }
}
