package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("ArcTunnelCtMonitor")
class ArcTunnelCtMonitorTest {
    @TempDir
    lateinit var root: File

    private val keys = FakeArcTunnelKeyStore()
    private val alerts = mockk<ArcTunnelCtAlerts>(relaxed = true)
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.IO)
    private val server = MockWebServer()
    private lateinit var store: ArcTunnelIdentityStore
    private lateinit var identity: ArcTunnelIdentity
    private lateinit var ownKey: String

    @BeforeEach
    fun setUp() {
        server.start()
        identity = ArcTunnelTestIdentity.create(keys, ArcTunnelNet(null, null))
        store = ArcTunnelIdentityStore(root, identity.hosts, keys)
        store.save(identity)
        ownKey = ArcTunnelPem.publicKeySha256(keys.publicKey(identity.state().tlsAlias))
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun monitor(
        enabled: Boolean = true,
        minIntervalMs: Long = 0,
    ) = ArcTunnelCtMonitor(
        ArcTunnelCtMonitor.Deps(identity, store, keys, alerts),
        scope,
        enabled,
        ArcTunnelCtMonitor.Options(server.url("/").toString(), ZeroRandom, minIntervalMs),
    )

    /** Waits for every check [ArcTunnelCtMonitor.onRequest] launched (none launched returns at once). */
    private suspend fun awaitChecks() {
        withTimeout(BUDGET_MS) { parent.children.toList().joinAll() }
    }

    private fun issuances(vararg entries: Pair<String, String>): MockResponse {
        val items = entries.joinToString(",") { (id, key) -> """{"id":"$id","pubkey_sha256":"$key"}""" }
        return ArcTunnelTestServer.json("[$items]")
    }

    private fun status(code: Int): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .build()

    /** Holds every answer until [release] is counted down; records the arrivals. */
    private inner class HeldDispatcher(
        private val answer: () -> MockResponse,
    ) : Dispatcher() {
        val release = CountDownLatch(1)
        val arrivals = CopyOnWriteArrayList<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            arrivals += request
            release.await(HOLD_S, TimeUnit.SECONDS)
            return answer()
        }
    }

    private suspend fun awaitThat(condition: () -> Boolean) {
        withTimeout(BUDGET_MS) { while (!condition()) delay(POLL_MS) }
    }

    @Test
    fun `request path and query`() =
        runBlocking {
            server.enqueue(issuances())

            monitor().check()

            val url = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).url
            assertEquals("/v1/issuances", url.encodedPath)
            assertEquals(identity.fqdn(), url.queryParameter("domain"))
            assertEquals("true", url.queryParameter("match_wildcards"))
            assertEquals("dns_names", url.queryParameter("expand"))
            assertNull(url.queryParameter("after"))
        }

    @Test
    fun `own key raises nothing and advances the cursor`() =
        runBlocking {
            server.enqueue(issuances("5" to ownKey))

            monitor().check()

            verify(exactly = 0) { alerts.raise(any()) }
            assertEquals("5", requireNotNull(store.load()).ctLastId)
        }

    @Test
    fun `foreign key raises once and is remembered`() =
        runBlocking {
            server.enqueue(issuances("5" to "f00d"))
            server.enqueue(issuances("5" to "f00d"))
            val m = monitor()

            m.check()
            m.check()

            verify(exactly = 1) { alerts.raise(ArcTunnelCtAlert(identity.fqdn(), "f00d")) }
            val saved = requireNotNull(store.load())
            assertEquals(listOf("f00d"), saved.ctFlagged)
            assertEquals("f00d", saved.ctAlertPubkey)
        }

    @Test
    fun `after cursor is sent on the next check`() =
        runBlocking {
            server.enqueue(issuances("5" to ownKey))
            server.enqueue(issuances())
            val m = monitor()

            m.check()
            m.check()

            server.takeRequest(5, TimeUnit.SECONDS)
            val second = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("5", second.url.queryParameter("after"))
        }

    @Test
    fun `http error keeps the cursor`() =
        runBlocking {
            server.enqueue(status(429))

            monitor().check()

            val saved = requireNotNull(store.load())
            assertEquals("", saved.ctLastId)
            assertTrue(saved.ctLastCheckMs > 0)
            verify(exactly = 0) { alerts.raise(any()) }
        }

    @Test
    fun `malformed body keeps the cursor`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.json("not json"))

            monitor().check()

            assertEquals("", requireNotNull(store.load()).ctLastId)
            verify(exactly = 0) { alerts.raise(any()) }
        }

    @Test
    fun `empty list keeps the cursor`() =
        runBlocking {
            store.mergeCtCheck("3", emptyList(), null, 1L)
            server.enqueue(issuances())

            monitor().check()

            assertEquals("3", requireNotNull(store.load()).ctLastId)
        }

    @Test
    fun `the minimum interval survives a restart`() =
        runBlocking {
            store.mergeCtCheck("", emptyList(), null, System.currentTimeMillis())
            server.enqueue(issuances())

            monitor(minIntervalMs = ArcTunnelCtTrigger.MIN_INTERVAL_MS).onRequest()
            awaitChecks()

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `check persists the check time`() =
        runBlocking {
            server.enqueue(issuances())
            val before = System.currentTimeMillis()

            monitor().check()

            assertTrue(requireNotNull(store.load()).ctLastCheckMs >= before)
        }

    @Test
    fun `a check in flight is cancelled with the scope`() =
        runBlocking {
            val held = HeldDispatcher { issuances() }
            server.dispatcher = held
            val m = monitor()
            try {
                m.onRequest()
                awaitThat { held.arrivals.size == 1 }

                parent.cancelChildren()
                awaitChecks()
                m.onRequest()

                awaitThat { held.arrivals.size == 2 }
            } finally {
                held.release.countDown()
            }
        }

    @Test
    fun `a dismissal during a check is not undone`() =
        runBlocking {
            store.mergeCtCheck("", listOf("f00d"), "f00d", 1L)
            val held = HeldDispatcher { issuances("6" to ownKey) }
            server.dispatcher = held
            val m = monitor()
            val check = launch(Dispatchers.IO) { m.check() }
            awaitThat { held.arrivals.size == 1 }

            store.clearCtAlert()
            held.release.countDown()
            check.join()

            val saved = requireNotNull(store.load())
            assertEquals("", saved.ctAlertPubkey)
            assertEquals("6", saved.ctLastId)
        }

    @Test
    fun `restoreAlert re-raises the persisted alert without a notification`() {
        store.mergeCtCheck("", listOf("f00d"), "f00d", 1L)

        monitor().restoreAlert()

        verify(exactly = 1) { alerts.restore(ArcTunnelCtAlert(identity.fqdn(), "f00d")) }
        verify(exactly = 0) { alerts.raise(any()) }
    }

    @Test
    fun `onRequest runs at most one check at a time`() =
        runBlocking {
            val held = HeldDispatcher { issuances() }
            server.dispatcher = held
            val m = monitor()
            try {
                val callers = List(CONCURRENT_REQUESTS) { scope.launch { m.onRequest() } }
                awaitThat { held.arrivals.size == 1 }
                callers.joinAll()

                // Every launched check is a child of the scope: only the held one exists.
                assertEquals(1, parent.children.count())
                assertEquals(1, held.arrivals.size)
            } finally {
                held.release.countDown()
            }
        }

    @Test
    fun `onRequest does nothing when disabled`() =
        runBlocking {
            server.enqueue(issuances())

            monitor(enabled = false).onRequest()
            awaitChecks()

            assertEquals(0, server.requestCount)
        }

    private companion object {
        const val HOLD_S = 10L
        const val BUDGET_MS = 5_000L
        const val POLL_MS = 20L
        const val CONCURRENT_REQUESTS = 20
    }
}
