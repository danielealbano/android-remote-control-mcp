package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A received certificate is spent: it is installed and saved whatever happens to the caller. */
@DisplayName("ArcTunnel received certificate")
class ArcTunnelReceivedCertificateTest {
    @TempDir
    lateinit var filesDir: File

    private val keys = FakeArcTunnelKeyStore()
    private val serverLog = RecordingServerLogRepository()
    private val context = mockk<Context>(relaxed = true)
    private val hosts = ArcTunnelTestServer.hosts
    private val issues = AtomicInteger()
    private val issueGate = AtomicReference<CountDownLatch>()
    private lateinit var enrollAnswer: () -> MockResponse
    private lateinit var server: MockWebServer
    private lateinit var env: ArcTunnelEnv
    private lateinit var impl: ArcTunnelEnrollmentImpl

    private val service =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    NONCE_PATH -> {
                        ArcTunnelTestServer.json("""{"nonce":"0a01"}""")
                    }

                    ENROLL_PATH -> {
                        enrollAnswer()
                    }

                    ISSUE_PATH -> {
                        issues.incrementAndGet()
                        issueGate.get()?.await(BUDGET_MS, TimeUnit.MILLISECONDS)
                        val identityCert = quote(pem("id"))
                        val publicCert = quote(pem("pub"))
                        ArcTunnelTestServer.json(
                            """{"identity_cert":$identityCert,"public_cert":$publicCert,"ca":"letsencrypt"}""",
                        )
                    }

                    else -> {
                        MockResponse
                            .Builder()
                            .code(404)
                            .build()
                    }
                }
        }

    @BeforeEach
    fun setUp() {
        every { context.filesDir } returns filesDir
        server = ArcTunnelTestServer.start()
        server.dispatcher = service
        enrollAnswer = { ArcTunnelTestServer.json(enrollBody()) }
        env = ArcTunnelEnv(ArcTunnelTestServer.BASE, "127.0.0.1:${server.port}", ArcTunnelTestServer.certPem)
        impl = ArcTunnelEnrollmentImpl(context, keys, serverLog)
    }

    @AfterEach
    fun tearDown() {
        issueGate.get()?.countDown()
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

    private fun enrollBody(): String =
        """{"name":"${ArcTunnelTestIdentity.NAME}","identity_cert":${quote(pem("id"))},"issue_nonce":"0b01"}"""

    private fun saveIdentity(): ArcTunnelIdentity {
        val identity = ArcTunnelTestIdentity.create(keys, impl.net(env), withPublicChain = false)
        impl.store(env).save(identity)
        return identity
    }

    /** Runs [block] with a way to cancel it from inside, and waits for it to end; returns its job. */
    private suspend fun cancellable(block: suspend (cancel: () -> Unit) -> Unit): Job =
        coroutineScope {
            val caller = AtomicReference<Job>()
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    block { caller.get().cancel() }
                }
            caller.set(job)
            job.start()
            withTimeout(BUDGET_MS) { job.join() }
            job
        }

    private fun assertSavedWithCertificate() {
        val saved = requireNotNull(impl.store(env).load())
        assertTrue(saved.publicChain.isNotEmpty(), "the certificate must be saved")
        assertEquals(ARC_TUNNEL_CERT_CHECK, saved.certCheck)
    }

    /** A net that cancels the caller right after the install, which is when this net is asked about the new chain. */
    private fun netCancellingAfterInstall(caller: AtomicReference<Job>): ArcTunnelNet =
        spyk(impl.net(env)).also {
            every { it.reachesTrustedRoot(any()) } answers {
                caller.get().cancel()
                true
            }
        }

    @Test
    fun `a renew cancelled once the certificate is received is saved all the same`() =
        runBlocking {
            val identity = saveIdentity()

            val job = cancellable { cancel -> impl.renew(identity, null) { cancel() } }

            assertTrue(job.isCancelled)
            assertSavedWithCertificate()
            assertEquals(1, requireNotNull(impl.store(env).load()).generation)
        }

    @Test
    fun `a renew cancelled before the certificate is received saves nothing`() =
        runBlocking {
            val identity = saveIdentity()
            val before = impl.store(env).load()
            val gate = CountDownLatch(1)
            issueGate.set(gate)
            val job = launch(Dispatchers.IO) { impl.renew(identity, null) }
            withTimeout(BUDGET_MS) { while (issues.get() == 0) delay(POLL_MS) }

            job.cancelAndJoin()
            gate.countDown()

            assertEquals(before, impl.store(env).load())
            assertEquals(0, identity.state().generation)
        }

    @Test
    fun `a renew cancelled once the certificate is received still clears a setup failure`() =
        runBlocking {
            enrollAnswer = {
                MockResponse
                    .Builder()
                    .code(401)
                    .body("""{"reason":"denied","retryable":false}""")
                    .build()
            }
            impl.execute(env, rotate = false)
            assertTrue(impl.state.value is ArcTunnelEnrollmentState.Failed, "state ${impl.state.value}")
            val identity = saveIdentity()

            cancellable { cancel -> impl.renew(identity, null) { cancel() } }

            assertEquals(ArcTunnelEnrollmentState.Idle, impl.state.value)
        }

    @Test
    fun `a renew whose save fails succeeds with the new certificate, logged, and issues once`() =
        runBlocking {
            val identity = saveIdentity()
            val before = impl.store(env).load()
            val storeDir = File(filesDir, ArcTunnelEnrollmentImpl.STORE_DIR)
            check(File(storeDir, "${hosts.envKey}.json.tmp").mkdirs()) { "could not block the save" }

            impl.renew(identity, null)

            assertEquals(1, issues.get())
            assertEquals(1, identity.state().generation)
            assertTrue(identity.state().publicChain.isNotEmpty(), "the new certificate must be in use")
            assertEquals(before, impl.store(env).load())
            assertEquals(1, serverLog.entries.count { it.message.contains("could not be saved") })
        }

    @Test
    fun `an enrollment whose save fails shows Failed and issues once`() =
        runBlocking {
            saveIdentity()
            val before = impl.store(env).load()
            val storeDir = File(filesDir, ArcTunnelEnrollmentImpl.STORE_DIR)
            check(File(storeDir, "${hosts.envKey}.json.tmp").mkdirs()) { "could not block the save" }

            impl.execute(env, rotate = false)

            assertEquals(1, issues.get())
            assertTrue(impl.state.value is ArcTunnelEnrollmentState.Failed, "state ${impl.state.value}")
            assertEquals(before, impl.store(env).load())
        }

    @Test
    fun `a first enrollment cancelled once the certificate is received is saved all the same`() =
        runBlocking {
            val flow = ArcTunnelEnrollmentFlow(keys, ArcTunnelEnroller(keys, serverLog))
            val caller = AtomicReference<Job>()
            val net = netCancellingAfterInstall(caller)
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    flow.enrollOrResume(env, impl.store(env), net)
                }
            caller.set(job)

            job.start()
            withTimeout(BUDGET_MS) { job.join() }

            assertTrue(job.isCancelled)
            assertSavedWithCertificate()
        }

    @Test
    fun `a resumed issuance cancelled once the certificate is received is saved all the same`() =
        runBlocking {
            saveIdentity()
            val flow = ArcTunnelEnrollmentFlow(keys, ArcTunnelEnroller(keys, serverLog))
            val caller = AtomicReference<Job>()
            val net = netCancellingAfterInstall(caller)
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    flow.enrollOrResume(env, impl.store(env), net)
                }
            caller.set(job)

            job.start()
            withTimeout(BUDGET_MS) { job.join() }

            assertTrue(job.isCancelled)
            assertSavedWithCertificate()
            assertEquals(1, issues.get())
        }

    private companion object {
        const val NONCE_PATH = "/api/v1/enroll/nonce"
        const val ENROLL_PATH = "/api/v1/enroll"
        const val ISSUE_PATH = "/api/v1/issue"
        const val BUDGET_MS = 10_000L
        const val POLL_MS = 10L
    }
}
