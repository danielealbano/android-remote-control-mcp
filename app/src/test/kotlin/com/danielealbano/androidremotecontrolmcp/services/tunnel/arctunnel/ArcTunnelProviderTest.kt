package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelListenerHost
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HeldCertificate
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@DisplayName("ArcTunnelProvider")
class ArcTunnelProviderTest {
    @TempDir
    lateinit var root: File

    private val keys = FakeArcTunnelKeyStore()
    private val enrollment = mockk<ArcTunnelEnrollment>(relaxed = true)
    private val renewals = mockk<ArcTunnelRenewals>(relaxed = true)
    private val host = mockk<TunnelListenerHost>()
    private val ctAlerts = mockk<ArcTunnelCtAlerts>(relaxed = true)
    private val serverLog = RecordingServerLogRepository()
    private val peers = ArcTunnelTestPeers()
    private val onRequest = AtomicReference<() -> Unit>()
    private val listenerStarts = AtomicInteger()
    private lateinit var server: MockWebServer
    private lateinit var identity: ArcTunnelIdentity
    private lateinit var store: ArcTunnelIdentityStore
    private lateinit var provider: ArcTunnelProvider

    private val config =
        ServerConfig(
            tunnelEnabled = true,
            tunnelProvider = TunnelProviderType.ARC_TUNNEL,
            arcTunnelBaseAddress = ArcTunnelTestServer.BASE,
            arcTunnelCtCheckEnabled = false,
        )

    @BeforeEach
    fun setUp() {
        server = ArcTunnelTestServer.start()
        identity = ArcTunnelTestIdentity.create(keys, ArcTunnelTestServer.net(server.port))
        store = ArcTunnelIdentityStore(root, identity.hosts, keys)
        store.save(identity)
        coEvery { enrollment.readyIdentity(any()) } returns identity
        every { enrollment.store(any()) } returns store
        every { host.startTlsListener(any(), any(), any()) } answers {
            listenerStarts.incrementAndGet()
            onRequest.set(thirdArg())
            secondArg<Int?>() ?: LISTENER_PORT
        }
        every { host.stopTlsListener() } just runs
        provider = ArcTunnelProvider(enrollment, renewals, keys, ctAlerts, serverLog)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { provider.stop() }
        peers.close()
        server.close()
    }

    /** A bound control stream that writes [frames] once, then holds until the client ends it. */
    private fun streamWith(vararg frames: ByteArray): MockResponse =
        ArcTunnelTestServer.answer(200) { socket ->
            try {
                val sink = socket.sink.buffer()
                frames.forEach { sink.write(it) }
                sink.flush()
                socket.source.buffer().readByteArray()
            } catch (e: IOException) {
                System.err.println("provider test stream ended: $e")
            }
        }

    private fun nudge(): ByteArray = ArcTunnelTestServer.frame(ArcTunnelFrames.RENEW_NUDGE, """{"nonce":"0a01"}""")

    private suspend fun startBound() {
        provider.bind(host)
        provider.start(LOCAL_PORT, config)
        awaitConnected()
    }

    private suspend fun awaitConnected(): TunnelStatus.Connected =
        withTimeout(BUDGET_MS) { provider.status.first { it is TunnelStatus.Connected } as TunnelStatus.Connected }

    private suspend fun awaitError(): TunnelStatus.Error =
        withTimeout(BUDGET_MS) { provider.status.first { it is TunnelStatus.Error } as TunnelStatus.Error }

    private suspend fun awaitThat(condition: () -> Boolean) {
        withTimeout(BUDGET_MS) { while (!condition()) delay(POLL_MS) }
    }

    /** The first error recorded after the first Connected, if any. */
    private fun errorAfterConnected(statuses: List<TunnelStatus>): TunnelStatus.Error? {
        val connectedAt = statuses.indexOfFirst { it is TunnelStatus.Connected }
        if (connectedAt < 0) return null
        return statuses.drop(connectedAt).filterIsInstance<TunnelStatus.Error>().firstOrNull()
    }

    /** A saved identity whose public certificate expired yesterday. */
    private fun expiredIdentity(): ArcTunnelIdentity {
        val keyStore = FakeArcTunnelKeyStore()
        val base = ArcTunnelTestIdentity.create(keyStore, identity.net)
        val state = base.state()
        val now = System.currentTimeMillis()
        val leaf =
            HeldCertificate
                .Builder()
                .keyPair(KeyPair(keyStore.publicKey(state.tlsAlias), keyStore.privateKey(state.tlsAlias)))
                .commonName(base.fqdn())
                .addSubjectAlternativeName(base.fqdn())
                .validityInterval(now - 2 * DAY_MS, now - DAY_MS)
                .build()
        val expiredState = state.copy(publicChain = listOf(leaf.certificate))
        return ArcTunnelIdentity(base.net, keyStore, base.name, base.hosts, expiredState)
    }

    private fun logged(text: String): Int = serverLog.entries.count { it.message.contains(text) }

    private suspend fun stopMs(): Long {
        val startedAt = System.nanoTime()
        provider.stop()
        return (System.nanoTime() - startedAt) / NANOS_PER_MS
    }

    @Test
    fun `start without bind reports Error`() =
        runBlocking {
            provider.start(LOCAL_PORT, config)

            assertTrue(awaitError().message.contains("bind()"))
        }

    @Test
    fun `start without a saved identity reports Error`() =
        runBlocking {
            coEvery { enrollment.readyIdentity(any()) } returns null
            provider.bind(host)

            provider.start(LOCAL_PORT, config)

            assertTrue(awaitError().message.contains("enrolled"))
        }

    @Test
    fun `start returns Connecting at once then Connected after the stream binds`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.hold())
            provider.bind(host)

            val startedAt = System.nanoTime()
            provider.start(LOCAL_PORT, config)
            val startMs = (System.nanoTime() - startedAt) / NANOS_PER_MS

            assertTrue(startMs < QUICK_MS, "start took $startMs ms")
            val connected = awaitConnected()
            assertEquals("https://${ArcTunnelTestIdentity.NAME}.d.example.test", connected.endpoints.single().url)
            assertEquals(TunnelProviderType.ARC_TUNNEL, connected.providerType)
            coVerify(exactly = 1) { enrollment.readyIdentity(any()) }
            verify(exactly = 1) { host.startTlsListener(any(), null, any()) }
        }

    @Test
    fun `start while running is ignored and keeps the status`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.hold())
            startBound()

            provider.start(LOCAL_PORT, config)

            coVerify(exactly = 1) { enrollment.readyIdentity(any()) }
            assertTrue(provider.status.value is TunnelStatus.Connected)
        }

    @Test
    fun `missing TLS key reports Error`() =
        runBlocking {
            keys.deleteKey(identity.state().tlsAlias)
            provider.bind(host)

            provider.start(LOCAL_PORT, config)

            assertTrue(awaitError().message.contains("TLS key"))
        }

    @Test
    fun `listener failure reports Error and leaves nothing running`() =
        runBlocking {
            every { host.startTlsListener(any(), any(), any()) } throws IllegalStateException("no loopback port")
            provider.bind(host)

            provider.start(LOCAL_PORT, config)

            assertTrue(awaitError().message.contains("no loopback port"))
            verify(timeout = BUDGET_MS) { host.stopTlsListener() }
            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `control drop reports Connecting and the next bind reports Connected again`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.answer(200) { socket -> socket.cancel() })
            server.enqueue(ArcTunnelTestServer.hold())
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            awaitThat { statuses.count { it is TunnelStatus.Connected } >= 2 }
            collector.cancel()

            val firstConnected = statuses.indexOfFirst { it is TunnelStatus.Connected }
            val reconnecting = statuses.drop(firstConnected + 1).indexOfFirst { it == TunnelStatus.Connecting }
            assertTrue(reconnecting >= 0, "statuses $statuses")
        }

    @Test
    fun `no bind within the timeout reports Error with the last reason and keeps retrying`() =
        runBlocking {
            provider.bindTimeoutMs = SHORT_BIND_TIMEOUT_MS
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(503)
                        .doNotReadRequestBody()
                        .build(),
                )
            }
            server.enqueue(ArcTunnelTestServer.hold())
            provider.bind(host)

            provider.start(LOCAL_PORT, config)

            assertTrue(awaitError().message.startsWith("Not connected after"))
            withTimeout(BUDGET_MS) {
                provider.status.first { it is TunnelStatus.Error && it.message.contains("HTTP 503") }
            }
            assertEquals(TunnelProviderType.ARC_TUNNEL, awaitConnected().providerType)
        }

    @Test
    fun `a drop with no rebind reports Error after the timeout`() =
        runBlocking {
            provider.bindTimeoutMs = SHORT_BIND_TIMEOUT_MS
            // Bound once, then dropped; the next control request is never answered.
            server.enqueue(ArcTunnelTestServer.answer(200) { socket -> socket.cancel() })
            // Every status is recorded: the short-lived Connected is conflated away for a plain first { }.
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            // A slow first bind may show the timeout error before Connected: wait for the one after the drop.
            awaitThat { errorAfterConnected(statuses) != null }
            collector.cancel()

            val error = requireNotNull(errorAfterConnected(statuses))
            assertTrue(error.message.startsWith("Not connected after"), error.message)
        }

    @Test
    fun `the bind-timeout error never names a failure from before the last bind`() =
        runBlocking {
            provider.bindTimeoutMs = SHORT_BIND_TIMEOUT_MS
            // Refused once, then bound and ended cleanly (no failure of its own); the next request is never answered.
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .doNotReadRequestBody()
                    .build(),
            )
            server.enqueue(ArcTunnelTestServer.answer(200) { socket -> socket.sink.close() })
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            awaitThat { errorAfterConnected(statuses) != null }
            collector.cancel()

            val error = requireNotNull(errorAfterConnected(statuses))
            assertEquals("Not connected after 0 s; still retrying", error.message)
        }

    @Test
    fun `a bind after the bind timeout fired restores Connected`() =
        runBlocking {
            provider.bindTimeoutMs = SHORT_BIND_TIMEOUT_MS
            // The first control request is answered only once the timeout has turned the status into the error.
            val release = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun peek(): MockResponse = ArcTunnelTestServer.hold()

                    override fun dispatch(request: RecordedRequest): MockResponse {
                        release.await(BUDGET_MS, TimeUnit.MILLISECONDS)
                        return ArcTunnelTestServer.hold()
                    }
                }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            try {
                assertTrue(awaitError().message.startsWith("Not connected after"))
            } finally {
                release.countDown()
            }

            assertEquals(TunnelProviderType.ARC_TUNNEL, awaitConnected().providerType)
        }

    @Test
    fun `a stop during the start renewal cancels it and shows no error`() =
        runBlocking {
            val bare = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), identity.net, withPublicChain = false)
            coEvery { enrollment.readyIdentity(any()) } returns bare
            val renewing = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { renewals.renewForStart(any()) } coAnswers {
                renewing.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            withTimeout(BUDGET_MS) { renewing.await() }
            provider.stop()
            collector.cancel()

            assertTrue(cancelled.isCompleted)
            assertTrue(statuses.none { it is TunnelStatus.Error }, "statuses $statuses")
            coVerify(exactly = 0) { renewals.awaitRetry() }
            verify(exactly = 1) { renewals.clear() }
            verify(exactly = 0) { host.startTlsListener(any(), any(), any()) }
        }

    @Test
    fun `a missing certificate is renewed before the listener starts`() =
        runBlocking {
            val bare = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), identity.net, withPublicChain = false)
            coEvery { enrollment.readyIdentity(any()) } returns bare
            server.enqueue(ArcTunnelTestServer.hold())
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            awaitConnected()

            coVerifyOrder {
                renewals.renewForStart(bare)
                host.startTlsListener(any(), null, any())
            }
        }

    @Test
    fun `an expired certificate is renewed before the listener starts`() =
        runBlocking {
            val expired = expiredIdentity()
            coEvery { enrollment.readyIdentity(any()) } returns expired
            server.enqueue(ArcTunnelTestServer.hold())
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            awaitConnected()

            coVerifyOrder {
                renewals.renewForStart(expired)
                host.startTlsListener(any(), null, any())
            }
        }

    @Test
    fun `a valid certificate starts without a renewal`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.hold())

            startBound()

            coVerify(exactly = 0) { renewals.renewForStart(any()) }
        }

    @Test
    fun `a failed start renewal shows the reason and runs again on Retry`() =
        runBlocking {
            val bare = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), identity.net, withPublicChain = false)
            coEvery { enrollment.readyIdentity(any()) } returns bare
            val attempts = AtomicInteger()
            coEvery { renewals.renewForStart(any()) } coAnswers {
                check(attempts.incrementAndGet() > 1) { "acme_rate_limited" }
            }
            val retry = CompletableDeferred<Unit>()
            coEvery { renewals.awaitRetry() } coAnswers { retry.await() }
            server.enqueue(ArcTunnelTestServer.hold())
            provider.bind(host)

            provider.start(LOCAL_PORT, config)

            assertEquals("acme_rate_limited", awaitError().message)
            verify(exactly = 0) { host.startTlsListener(any(), any(), any()) }
            retry.complete(Unit)
            awaitConnected()
            assertEquals(2, attempts.get())
        }

    @Test
    fun `stop tears down control scope and listener`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.hold())
            startBound()

            provider.stop()
            delay(RECONNECT_WINDOW_MS)

            verify(exactly = 1) { host.stopTlsListener() }
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `stop is a no-op when not started`() =
        runBlocking {
            provider.stop()

            assertEquals(TunnelStatus.Disconnected, provider.status.value)
            verify(exactly = 0) { host.stopTlsListener() }
        }

    @Test
    fun `stop completes within 2 seconds while a renewal is blocked`() =
        runBlocking {
            val renewing = CompletableDeferred<Unit>()
            coEvery { enrollment.renew(any(), any()) } coAnswers {
                renewing.complete(Unit)
                withContext(NonCancellable) { delay(BLOCKED_MS) }
            }
            server.enqueue(streamWith(nudge()))
            startBound()
            withTimeout(BUDGET_MS) { renewing.await() }

            val elapsed = stopMs()

            assertTrue(elapsed < STOP_BUDGET_MS, "stop took $elapsed ms")
        }

    @Test
    fun `stop completes within 2 seconds while a dial-back is blocked`() =
        runBlocking {
            SilentPeer(peers.dial).use { peer ->
                server.enqueue(streamWith(peers.openFrame()))
                startBound()
                assertTrue(peer.awaitRequest())

                val elapsed = stopMs()

                assertTrue(elapsed < STOP_BUDGET_MS, "stop took $elapsed ms")
                assertTrue(peer.seesEof())
            }
        }

    @Test
    fun `stop completes within 2 seconds while a CT check is blocked`() =
        runBlocking {
            SilentPeer(ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())).use { ct ->
                provider.ctOptions = ArcTunnelCtMonitor.Options("http://127.0.0.1:${ct.port}/", ZeroRandom, 0)
                server.enqueue(ArcTunnelTestServer.hold())
                provider.bind(host)
                provider.start(LOCAL_PORT, config.copy(arcTunnelCtCheckEnabled = true))
                awaitConnected()
                requireNotNull(onRequest.get()).invoke()
                assertTrue(ct.awaitRequest())

                val elapsed = stopMs()

                assertTrue(elapsed < STOP_BUDGET_MS, "stop took $elapsed ms")
                assertTrue(ct.seesEof())
            }
        }

    @Test
    fun `renew nudge schedules a swap and the idle swap restarts the listener on the same port`() =
        runBlocking {
            provider.swapPollMs = SWAP_POLL_MS
            server.enqueue(streamWith(nudge()))
            startBound()

            coVerify(timeout = BUDGET_MS) { enrollment.renew(identity, "0a01") }
            awaitThat { logged(RENEWED) == 1 }

            verify(exactly = 1) { host.stopTlsListener() }
            verify(exactly = 1) { host.startTlsListener(any(), LISTENER_PORT, any()) }
        }

    @Test
    fun `renewal failure keeps the current listener`() =
        runBlocking {
            provider.swapPollMs = SWAP_POLL_MS
            coEvery { enrollment.renew(any(), any()) } throws ArcTunnelApiException("refused", false, 0, 403)
            server.enqueue(streamWith(nudge()))
            startBound()

            coVerify(timeout = BUDGET_MS) { enrollment.renew(any(), any()) }
            awaitThat { logged("renewal failed") == 1 }
            delay(RECONNECT_WINDOW_MS)

            assertEquals(1, listenerStarts.get())
            verify(exactly = 0) { host.stopTlsListener() }
        }

    @Test
    fun `a failed swap reports Error and the next poll recovers`() =
        runBlocking {
            provider.swapPollMs = SWAP_POLL_MS
            every { host.startTlsListener(any(), any(), any()) } answers {
                val n = listenerStarts.incrementAndGet()
                onRequest.set(thirdArg())
                check(n != 2) { "busy port" }
                secondArg<Int?>() ?: LISTENER_PORT
            }
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            server.enqueue(streamWith(nudge()))
            startBound()

            awaitThat { logged(RENEWED) == 1 }
            awaitConnected()
            collector.cancel()

            val errorAt = statuses.indexOfFirst { it is TunnelStatus.Error }
            assertTrue(errorAt >= 0, "statuses $statuses")
            assertTrue(statuses.drop(errorAt).any { it is TunnelStatus.Connected }, "statuses $statuses")
            assertEquals(3, listenerStarts.get())
            assertEquals(1, logged(RENEWED))
        }

    @Test
    fun `a failed swap while unbound leaves the reconnect status`() =
        runBlocking {
            provider.swapPollMs = SWAP_POLL_MS
            every { host.startTlsListener(any(), any(), any()) } answers {
                val n = listenerStarts.incrementAndGet()
                onRequest.set(thirdArg())
                check(n != 2) { "busy port" }
                secondArg<Int?>() ?: LISTENER_PORT
            }
            // The stream delivers the nudge, then drops once the renewal asks for it; the renewal returns (and so
            // schedules the swap) only after the provider reports the reconnect. The next request is never answered.
            val drop = CountDownLatch(1)
            server.enqueue(
                ArcTunnelTestServer.answer(200) { socket ->
                    val sink = socket.sink.buffer()
                    sink.write(nudge())
                    sink.flush()
                    drop.await(BUDGET_MS, TimeUnit.MILLISECONDS)
                    socket.cancel()
                },
            )
            coEvery { enrollment.renew(any(), any()) } coAnswers {
                drop.countDown()
                awaitThat { provider.status.value == TunnelStatus.Connecting }
            }
            val statuses = CopyOnWriteArrayList<TunnelStatus>()
            val collector = launch(Dispatchers.Unconfined) { provider.status.collect { statuses += it } }
            provider.bind(host)

            provider.start(LOCAL_PORT, config)
            awaitThat { listenerStarts.get() == 3 }
            collector.cancel()

            assertTrue(statuses.none { it is TunnelStatus.Error }, "statuses $statuses")
            assertEquals(TunnelStatus.Connecting, provider.status.value)
        }

    @Test
    fun `restoreAlert re-raises a persisted alert at start`() =
        runBlocking {
            store.mergeCtCheck("", listOf("f00d"), "f00d", 1L)
            server.enqueue(ArcTunnelTestServer.hold())

            startBound()

            verify(timeout = BUDGET_MS) { ctAlerts.restore(ArcTunnelCtAlert(identity.fqdn(), "f00d")) }
        }

    /**
     * A loopback peer that accepts one connection, waits for the first request byte and never answers;
     * [seesEof] drains the rest of the request and reports whether the client closed the connection.
     */
    private class SilentPeer(
        private val listener: ServerSocket,
    ) : AutoCloseable {
        private val accepted = AtomicReference<Socket>()
        private val arrived = CountDownLatch(1)
        val port: Int = listener.localPort

        init {
            val acceptor =
                Thread {
                    try {
                        val socket = listener.accept()
                        accepted.set(socket)
                        socket.getInputStream().read()
                        arrived.countDown()
                    } catch (e: IOException) {
                        System.err.println("silent peer ended: $e")
                    }
                }
            acceptor.isDaemon = true
            acceptor.start()
        }

        fun awaitRequest(): Boolean = arrived.await(BUDGET_MS, TimeUnit.MILLISECONDS)

        fun seesEof(): Boolean {
            val socket = requireNotNull(accepted.get())
            socket.soTimeout = EOF_BUDGET_MS
            val drained = runCatching { socket.getInputStream().readBytes() }
            return drained.exceptionOrNull() !is SocketTimeoutException
        }

        override fun close() {
            accepted.get()?.close()
            listener.close()
        }
    }

    private companion object {
        const val LOCAL_PORT = 8080
        const val LISTENER_PORT = 50_000
        const val BACKLOG = 50
        const val BUDGET_MS = 30_000L
        const val QUICK_MS = 1_000L
        const val STOP_BUDGET_MS = 2_000L
        const val BLOCKED_MS = 10_000L
        const val RECONNECT_WINDOW_MS = 1_500L
        const val DAY_MS = 86_400_000L
        const val SWAP_POLL_MS = 200L
        const val SHORT_BIND_TIMEOUT_MS = 300L
        const val POLL_MS = 20L
        const val EOF_BUDGET_MS = 1_000
        const val NANOS_PER_MS = 1_000_000L
        const val RENEWED = "certificate renewed and installed"
    }
}
