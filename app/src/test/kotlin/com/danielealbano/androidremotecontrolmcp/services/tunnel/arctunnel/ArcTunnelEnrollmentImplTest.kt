package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("ArcTunnelEnrollmentImpl")
class ArcTunnelEnrollmentImplTest {
    @TempDir
    lateinit var filesDir: File

    private val keys = FakeArcTunnelKeyStore()
    private val serverLog = RecordingServerLogRepository()
    private val service = Service()
    private val context = mockk<Context>(relaxed = true)
    private val hosts = ArcTunnelTestServer.hosts
    private val prefix = ArcTunnelEnroller.aliasPrefix(hosts)
    private lateinit var server: MockWebServer
    private lateinit var env: ArcTunnelEnv
    private lateinit var impl: ArcTunnelEnrollmentImpl

    /** The tunnel service; [enrollGate]/[issueGate] hold an answer until released, [names] are handed out in order. */
    private inner class Service : Dispatcher() {
        val requests = CopyOnWriteArrayList<String>()
        val names = ArrayDeque(listOf("klmnopqrst", "uvwxyzabcd"))
        val nonce = AtomicInteger()

        @Volatile
        var enrollAnswer: MockResponse? = null

        @Volatile
        var enrollGate: CountDownLatch? = null

        @Volatile
        var issueGate: CountDownLatch? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            requests += path
            return when (path) {
                NONCE_PATH -> {
                    ArcTunnelTestServer.json(nonceBody(NONCE_BASE + nonce.incrementAndGet()))
                }

                ENROLL_PATH -> {
                    enrollGate?.await(GATE_S, TimeUnit.SECONDS)
                    enrollAnswer ?: ArcTunnelTestServer.json(enrollBody(synchronized(names) { names.removeFirst() }))
                }

                ISSUE_PATH -> {
                    issueGate?.await(GATE_S, TimeUnit.SECONDS)
                    ArcTunnelTestServer.json(issueBody())
                }

                else -> {
                    MockResponse
                        .Builder()
                        .code(404)
                        .build()
                }
            }
        }

        fun count(path: String): Int = requests.count { it == path }

        private fun nonceBody(value: Int): String = """{"nonce":"%04x"}""".format(value)
    }

    @BeforeEach
    fun setUp() {
        every { context.filesDir } returns filesDir
        every { context.startForegroundService(any()) } returns null
        server = ArcTunnelTestServer.start()
        server.dispatcher = service
        env = ArcTunnelEnv(ArcTunnelTestServer.BASE, "127.0.0.1:${server.port}", ArcTunnelTestServer.certPem)
        impl = ArcTunnelEnrollmentImpl(context, keys, serverLog)
    }

    @AfterEach
    fun tearDown() {
        service.enrollGate?.countDown()
        service.issueGate?.countDown()
        server.close()
    }

    private fun pem(cn: String): String =
        ArcTunnelPem.encode(
            "CERTIFICATE",
            HeldCertificate
                .Builder()
                .commonName(cn)
                .build()
                .certificate.encoded,
        )

    private fun quote(value: String): String = "\"" + value.replace("\n", "\\n") + "\""

    private fun enrollBody(name: String): String {
        val identityCert = quote(pem("id"))
        return """{"name":"$name","identity_cert":$identityCert,"issue_nonce":"0b01"}"""
    }

    private fun issueBody(): String {
        val identityCert = quote(pem("id"))
        val publicCert = quote(pem("pub"))
        return """{"identity_cert":$identityCert,"public_cert":$publicCert,"ca":"letsencrypt"}"""
    }

    private fun expiredChain(
        alias: String,
        cn: String,
    ): List<X509Certificate> {
        val now = System.currentTimeMillis()
        val held =
            HeldCertificate
                .Builder()
                .keyPair(KeyPair(keys.publicKey(alias), keys.privateKey(alias)))
                .commonName(cn)
                .validityInterval(now - 2 * DAY_MS, now - DAY_MS)
                .build()
        return listOf(held.certificate)
    }

    /** Saves the test identity; the chains of the base state can be replaced through [adjust]. */
    private fun saveIdentity(
        withPublicChain: Boolean,
        adjust: (ArcTunnelIdentity.State) -> ArcTunnelIdentity.State = { it },
    ): ArcTunnelIdentity {
        val created = ArcTunnelTestIdentity.create(keys, impl.net(env), withPublicChain)
        val identity = ArcTunnelIdentity(created.net, keys, created.name, hosts, adjust(created.state()))
        impl.store(env).save(identity)
        return identity
    }

    private fun failedMessage(): String =
        (impl.state.value as? ArcTunnelEnrollmentState.Failed)?.message ?: error("state ${impl.state.value}")

    private suspend fun awaitRequest(path: String) {
        withTimeout(GATE_S * MS_PER_S) { while (service.count(path) == 0) delay(POLL_MS) }
    }

    @Test
    fun `savedUrl is null without a saved identity`() {
        assertNull(impl.savedUrl(env))
    }

    @Test
    fun `savedUrl is null for a certificate-less identity`() {
        saveIdentity(withPublicChain = false)

        assertNull(impl.savedUrl(env))
    }

    @Test
    fun `savedUrl derives from the saved name`() {
        saveIdentity(withPublicChain = true)

        assertEquals("https://${ArcTunnelTestIdentity.NAME}.d.example.test", impl.savedUrl(env))
    }

    @Test
    fun `execute runs phase 1 then issue and saves twice`() =
        runBlocking {
            val seen = CopyOnWriteArrayList<ArcTunnelEnrollmentState>()
            val collector = launch(Dispatchers.Unconfined) { impl.state.collect { seen += it } }

            impl.execute(env, rotate = false)
            collector.cancel()

            val saved = requireNotNull(impl.store(env).load())
            assertEquals("klmnopqrst", saved.name)
            assertTrue(saved.publicChain.isNotEmpty())
            assertTrue(seen.contains(ArcTunnelEnrollmentState.Enrolling))
            assertEquals(ArcTunnelEnrollmentState.Idle, impl.state.value)
            assertTrue(serverLog.entries.any { it.message.contains("Free E2E tunnel enrolled") })
            assertEquals(listOf(NONCE_PATH, ENROLL_PATH, ISSUE_PATH), service.requests.toList())
        }

    @Test
    fun `execute on a certificate-less identity only issues`() =
        runBlocking {
            saveIdentity(withPublicChain = false)

            impl.execute(env, rotate = false)

            assertEquals(0, service.count(ENROLL_PATH))
            assertEquals(1, service.count(ISSUE_PATH))
            val saved = requireNotNull(impl.store(env).load())
            assertEquals(ArcTunnelTestIdentity.NAME, saved.name)
            assertTrue(saved.publicChain.isNotEmpty())
        }

    @Test
    fun `execute on an identity with a valid certificate is a no-op`() =
        runBlocking {
            saveIdentity(withPublicChain = true)

            impl.execute(env, rotate = false)

            assertTrue(service.requests.isEmpty())
            assertEquals(ArcTunnelTestIdentity.NAME, requireNotNull(impl.store(env).load()).name)
            assertEquals(ArcTunnelEnrollmentState.Idle, impl.state.value)
        }

    @Test
    fun `execute on an expired certificate issues again`() =
        runBlocking {
            saveIdentity(withPublicChain = true) { it.copy(publicChain = expiredChain(it.tlsAlias, "pub")) }

            impl.execute(env, rotate = false)

            assertEquals(1, service.count(ISSUE_PATH))
            assertEquals(0, service.count(ENROLL_PATH))
        }

    @Test
    fun `execute failure sets Failed`() =
        runBlocking {
            service.enrollAnswer =
                MockResponse
                    .Builder()
                    .code(401)
                    .body("""{"reason":"denied","retryable":false}""")
                    .build()

            impl.execute(env, rotate = false)

            assertTrue(failedMessage().contains("denied"), failedMessage())
            assertTrue(serverLog.entries.any { it.message.contains("enrollment failed") })
        }

    @Test
    fun `execute while one runs returns at once`() =
        runBlocking {
            val gate = CountDownLatch(1)
            service.enrollGate = gate
            val first = launch(Dispatchers.IO) { impl.execute(env, rotate = false) }
            awaitRequest(ENROLL_PATH)

            withTimeout(QUICK_MS) { impl.execute(env, rotate = false) }
            gate.countDown()
            first.join()

            assertEquals(1, service.count(ENROLL_PATH))
        }

    @Test
    fun `execute with rotate forgets then enrolls a new name`() =
        runBlocking {
            val old = saveIdentity(withPublicChain = true).state()

            impl.execute(env, rotate = true)

            assertTrue(keys.deleted.containsAll(listOf(old.identityAlias, old.tlsAlias)))
            val saved = requireNotNull(impl.store(env).load())
            assertEquals("klmnopqrst", saved.name)
            assertNotEquals(ArcTunnelTestIdentity.NAME, saved.name)
        }

    @Test
    fun `requestEnroll starts the foreground service with the env extras`() {
        mockkConstructor(Intent::class)
        try {
            every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }

            impl.requestEnroll(env)

            verify { anyConstructed<Intent>().putExtra("base_address", env.baseAddress) }
            verify { anyConstructed<Intent>().putExtra("edge_override", env.edgeOverride) }
            verify { anyConstructed<Intent>().putExtra("trusted_ca_pem", env.trustedCaPem) }
            verify { anyConstructed<Intent>().putExtra("rotate", false) }
            verify(exactly = 1) { context.startForegroundService(any()) }
        } finally {
            unmockkConstructor(Intent::class)
        }
    }

    @Test
    fun `requestRotate starts the service with rotate`() {
        mockkConstructor(Intent::class)
        try {
            every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }

            impl.requestRotate(env)

            verify { anyConstructed<Intent>().putExtra("rotate", true) }
            verify(exactly = 1) { context.startForegroundService(any()) }
        } finally {
            unmockkConstructor(Intent::class)
        }
    }

    @Test
    fun `a refused foreground start sets Failed`() {
        every { context.startForegroundService(any()) } throws ForegroundServiceStartNotAllowedException("refused")

        impl.requestEnroll(env)

        assertEquals(ArcTunnelEnrollmentState.Failed(ArcTunnelEnrollmentImpl.START_REFUSED), impl.state.value)
    }

    @Test
    fun `readyIdentity is null without a saved identity`() =
        runBlocking {
            assertNull(impl.readyIdentity(env))
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `readyIdentity restores without issuing`() =
        runBlocking {
            saveIdentity(withPublicChain = false)

            val restored = requireNotNull(impl.readyIdentity(env))

            assertEquals(ArcTunnelTestIdentity.NAME, restored.name)
            assertNull(restored.publicLeaf())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `renew issues and saves the public certificate`() =
        runBlocking {
            val identity = saveIdentity(withPublicChain = false)

            impl.renew(identity, null)

            assertEquals(1, service.count(ISSUE_PATH))
            assertTrue(requireNotNull(impl.store(env).load()).publicChain.isNotEmpty())
        }

    @Test
    fun `renew clears a setup failure once the certificate is saved`() =
        runBlocking {
            service.enrollAnswer =
                MockResponse
                    .Builder()
                    .code(401)
                    .body("""{"reason":"denied","retryable":false}""")
                    .build()
            impl.execute(env, rotate = false)
            assertTrue(failedMessage().contains("denied"), failedMessage())
            val identity = saveIdentity(withPublicChain = false)

            impl.renew(identity, null)

            assertEquals(ArcTunnelEnrollmentState.Idle, impl.state.value)
        }

    @Test
    fun `a failed renew leaves a setup failure shown`() =
        runBlocking {
            service.enrollAnswer =
                MockResponse
                    .Builder()
                    .code(401)
                    .body("""{"reason":"denied","retryable":false}""")
                    .build()
            impl.execute(env, rotate = false)
            val identity = saveIdentity(withPublicChain = false)
            impl.store(env).forget()

            runCatching { impl.renew(identity, null) }

            assertTrue(failedMessage().contains("denied"), failedMessage())
        }

    @Test
    fun `readyIdentity waits for an in-flight request`() =
        runBlocking {
            saveIdentity(withPublicChain = false)
            val gate = CountDownLatch(1)
            service.issueGate = gate
            val request = launch(Dispatchers.IO) { impl.execute(env, rotate = false) }
            awaitRequest(ISSUE_PATH)

            val ready = async(Dispatchers.IO) { impl.readyIdentity(env) }
            delay(WAIT_CHECK_MS)
            assertFalse(ready.isCompleted)
            gate.countDown()

            val identity = requireNotNull(ready.await())
            request.join()
            assertTrue(identity.publicLeaf() != null)
            assertEquals(1, service.count(ISSUE_PATH))
        }

    @Test
    fun `renew after forget throws and saves nothing`() =
        runBlocking {
            val identity = saveIdentity(withPublicChain = true)
            impl.store(env).forget()

            val error = runCatching { impl.renew(identity, null) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "error $error")
            assertNull(impl.store(env).load())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `execute starts over when the identity key is gone and no certificate was issued`() =
        runBlocking {
            val old = saveIdentity(withPublicChain = false).state()
            keys.deleteKey(old.identityAlias)

            impl.execute(env, rotate = false)

            assertEquals(1, service.count(ENROLL_PATH))
            assertTrue(keys.deleted.contains(old.tlsAlias))
            assertEquals("klmnopqrst", requireNotNull(impl.store(env).load()).name)
        }

    @Test
    fun `execute fails when the identity key is gone but a certificate exists`() =
        runBlocking {
            val old = saveIdentity(withPublicChain = true).state()
            keys.deleteKey(old.identityAlias)

            impl.execute(env, rotate = false)

            assertTrue(failedMessage().contains("new name"), failedMessage())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `execute starts over when the identity certificate expired and no public certificate exists`() =
        runBlocking {
            saveIdentity(withPublicChain = false) {
                it.copy(identityChain = expiredChain(it.identityAlias, ArcTunnelTestIdentity.NAME))
            }

            impl.execute(env, rotate = false)

            assertEquals(1, service.count(ENROLL_PATH))
            assertEquals("klmnopqrst", requireNotNull(impl.store(env).load()).name)
        }

    @Test
    fun `execute fails when the identity certificate expired but a public certificate exists`() =
        runBlocking {
            saveIdentity(withPublicChain = true) {
                it.copy(identityChain = expiredChain(it.identityAlias, ArcTunnelTestIdentity.NAME))
            }

            impl.execute(env, rotate = false)

            assertTrue(failedMessage().contains("new name"), failedMessage())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `readyIdentity fails on an expired identity certificate`() =
        runBlocking {
            saveIdentity(withPublicChain = true) {
                it.copy(identityChain = expiredChain(it.identityAlias, ArcTunnelTestIdentity.NAME))
            }

            val error = runCatching { impl.readyIdentity(env) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "error $error")
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `a fresh enrollment deletes leftover keys first`() =
        runBlocking {
            val tlsAlias = "${prefix}tls"
            keys.generateEcKey(tlsAlias)
            val leftover = keys.publicKey(tlsAlias)
            File(File(filesDir, ArcTunnelEnrollmentImpl.STORE_DIR), "${hosts.envKey}.json").apply {
                parentFile?.mkdirs()
                writeText("garbage")
            }

            impl.execute(env, rotate = false)

            assertTrue(keys.deleted.contains(tlsAlias))
            assertNotEquals(leftover, keys.publicKey(tlsAlias))
            assertEquals(ArcTunnelEnrollmentState.Idle, impl.state.value)
        }

    @Test
    fun `store returns one instance per environment`() {
        assertSame(impl.store(env), impl.store(env))
    }

    @Test
    fun `net returns one instance per environment`() {
        assertSame(impl.net(env), impl.net(env))
        assertNotSame(impl.net(env), impl.net(env.copy(edgeOverride = "127.0.0.1:1")))
    }

    @Test
    fun `savedCtAlert and dismissCtAlert round-trip`() =
        runBlocking {
            val identity = saveIdentity(withPublicChain = true)
            impl.store(env).mergeCtCheck("id", listOf("abc"), "abc", 42L)

            assertEquals(ArcTunnelCtAlert(identity.fqdn(), "abc"), impl.savedCtAlert(env))
            impl.dismissCtAlert(env)

            assertNull(impl.savedCtAlert(env))
            assertEquals(42L, requireNotNull(impl.store(env).load()).ctLastCheckMs)
        }

    private companion object {
        const val NONCE_PATH = "/api/v1/enroll/nonce"
        const val ENROLL_PATH = "/api/v1/enroll"
        const val ISSUE_PATH = "/api/v1/issue"
        const val NONCE_BASE = 0x0a00
        const val GATE_S = 10L
        const val MS_PER_S = 1_000L
        const val POLL_MS = 20L
        const val QUICK_MS = 1_000L
        const val WAIT_CHECK_MS = 300L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
