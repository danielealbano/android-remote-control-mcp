package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HeldCertificate
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@DisplayName("ArcTunnelEnroller")
class ArcTunnelEnrollerTest {
    private val keys = FakeArcTunnelKeyStore()
    private val serverLog = RecordingServerLogRepository()
    private val enroller = ArcTunnelEnroller(keys, serverLog)
    private val hosts = ArcTunnelTestServer.hosts
    private val prefix = ArcTunnelEnroller.aliasPrefix(hosts)
    private val service = Service()
    private val issuerRoot =
        HeldCertificate
            .Builder()
            .certificateAuthority(1)
            .commonName("issuer root")
            .build()
    private val issuerIntermediate =
        HeldCertificate
            .Builder()
            .certificateAuthority(0)
            .signedBy(issuerRoot)
            .commonName("issuer intermediate")
            .build()
    private val issuedLeaf =
        HeldCertificate
            .Builder()
            .signedBy(issuerIntermediate)
            .commonName("${ArcTunnelTestIdentity.NAME}.d.${ArcTunnelTestServer.BASE}")
            .build()
            .certificate
    private lateinit var server: MockWebServer
    private lateinit var net: ArcTunnelNet

    /** The tunnel service: nonces "0a01", "0a02", …; queued `/issue` answers first, then a valid one. */
    private inner class Service : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val issueAnswers = ConcurrentLinkedQueue<MockResponse>()
        private val nonceCounter = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return when (request.url.encodedPath) {
                NONCE_PATH -> {
                    ArcTunnelTestServer.json(nonceBody(NONCE_BASE + nonceCounter.incrementAndGet()))
                }

                ENROLL_PATH -> {
                    ArcTunnelTestServer.json(enrollBody())
                }

                ISSUE_PATH -> {
                    issueAnswers.poll() ?: ArcTunnelTestServer.json(issueBody(publicPem()))
                }

                else -> {
                    MockResponse
                        .Builder()
                        .code(404)
                        .build()
                }
            }
        }

        private fun nonceBody(value: Int): String = """{"nonce":"%04x"}""".format(value)

        fun count(path: String): Int = requests.count { it.url.encodedPath == path }

        fun bodies(path: String): List<String> {
            val matching = requests.filter { it.url.encodedPath == path }
            return matching.map { requireNotNull(it.body).utf8() }
        }
    }

    @BeforeEach
    fun setUp() {
        server = ArcTunnelTestServer.start()
        server.dispatcher = service
        net = ArcTunnelTestServer.net(server.port)
    }

    @AfterEach
    fun tearDown() {
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

    private fun publicPem(): String = pem("${ArcTunnelTestIdentity.NAME}.d.${ArcTunnelTestServer.BASE}")

    private fun enrollBody(): String =
        """{"name":"${ArcTunnelTestIdentity.NAME}","identity_cert":${quote(pem("id"))},"issue_nonce":"0b01"}"""

    private fun issueBody(publicCert: String): String =
        """{"identity_cert":${quote(pem("id"))},"public_cert":${quote(publicCert)},"ca":"letsencrypt"}"""

    private fun quote(value: String): String = "\"" + value.replace("\n", "\\n") + "\""

    private fun apiError(
        code: Int,
        retryable: Boolean,
        retryAfter: Int = 0,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body("""{"reason":"refused","retryable":$retryable,"retry_after_seconds":$retryAfter}""")
            .build()

    private fun certPem(cert: X509Certificate): String = ArcTunnelPem.encode("CERTIFICATE", cert.encoded)

    /** Trusts the mock server, to reach it, and [issuerRoot], to check what it issues. */
    private fun netTrustingIssuerRoot(): ArcTunnelNet =
        ArcTunnelNet(
            ArcTunnelTestServer.certPem + certPem(issuerRoot.certificate),
            ArcTunnelEdgeOverride("127.0.0.1", server.port),
        )

    private fun incompleteChainReports(): Int = serverLog.entries.count { it.message.contains("chain is incomplete") }

    private fun commonName(csrPem: String): String {
        val der =
            Base64.getDecoder().decode(
                csrPem
                    .lines()
                    .filterNot { it.startsWith("-----") }
                    .joinToString(""),
            )
        val rdn = PKCS10CertificationRequest(der).subject.getRDNs(BCStyle.CN).single()
        return rdn.first.value.toString()
    }

    @Test
    fun `enroll generates identity-0 attested over the nonce and sends chain and csr`() =
        runBlocking {
            val (identity, issueNonce) = enroller.enroll(net, hosts)

            val body = service.bodies(ENROLL_PATH).single()
            val request = ArcTunnelJson.decodeFromString(ArcTunnelEnrollRequest.serializer(), body)
            assertEquals("0a01", request.nonce)
            assertTrue(request.attestationChain.startsWith("-----BEGIN CERTIFICATE-----"))
            assertTrue(request.identityCsr.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
            val (alias, challenge) = keys.generated.single()
            assertEquals("${prefix}identity-0", alias)
            assertArrayEquals(ArcTunnelPem.hexToBytes("0a01"), challenge)
            val state = identity.state()
            assertEquals("${prefix}tls", state.tlsAlias)
            assertTrue(state.publicChain.isEmpty())
            assertEquals(0, state.generation)
            assertEquals(ArcTunnelTestIdentity.NAME, identity.name)
            assertEquals("0b01", issueNonce)
        }

    @Test
    fun `issue creates the TLS key once and a new identity key per generation`() =
        runBlocking {
            val (identity, issueNonce) = enroller.enroll(net, hosts)

            enroller.issue(identity, issueNonce)
            enroller.issue(identity, "0c01")

            assertEquals(1, keys.generated.count { it.first == "${prefix}tls" })
            assertNull(keys.generated.single { it.first == "${prefix}tls" }.second)
            val one = keys.generated.single { it.first == "${prefix}identity-1" }
            val two = keys.generated.single { it.first == "${prefix}identity-2" }
            assertArrayEquals(ArcTunnelPem.hexToBytes("0b01"), one.second)
            assertArrayEquals(ArcTunnelPem.hexToBytes("0c01"), two.second)
            assertEquals(2, identity.state().generation)
        }

    @Test
    fun `a renewal with a missing TLS key fails without touching the keystore`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            enroller.issue(identity, "0c01")
            keys.deleteKey(identity.state().tlsAlias)
            val generatedBefore = keys.generated.size

            val error = runCatching { enroller.issue(identity, "0c02") }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "error $error")
            assertTrue(error?.message.orEmpty().contains("new name"))
            assertEquals(generatedBefore, keys.generated.size)
        }

    @Test
    fun `issue requests the fqdn in the TLS CSR`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)

            enroller.issue(identity, "0c01")

            val body = service.bodies(ISSUE_PATH).single()
            val request = ArcTunnelJson.decodeFromString(ArcTunnelIssueRequest.serializer(), body)
            assertEquals("0c01", request.nonce)
            assertEquals(identity.fqdn(), commonName(request.tlsCsr))
        }

    @Test
    fun `issue installs chains and deletes the identity key two generations back`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)

            enroller.issue(identity, "0c01")
            assertFalse(keys.deleted.contains("${prefix}identity-0"))
            enroller.issue(identity, "0c02")

            assertTrue(keys.deleted.contains("${prefix}identity-0"))
            assertFalse(keys.deleted.contains("${prefix}identity-1"))
            val state = identity.state()
            assertEquals("${prefix}identity-2", state.identityAlias)
            assertEquals("${prefix}identity-1", state.prevIdentityAlias)
            assertEquals(1, state.publicChain.size)
            assertEquals("letsencrypt", state.caId)
        }

    @Test
    fun `failed issue drops the staged identity key and keeps the state`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val before = identity.state()
            service.issueAnswers += apiError(400, retryable = false)

            val error = runCatching { enroller.issue(identity, "0c01") }.exceptionOrNull()

            assertTrue(error is ArcTunnelApiException, "error $error")
            assertTrue(keys.deleted.contains("${prefix}identity-1"))
            assertFalse(keys.hasKey("${prefix}identity-1"))
            assertEquals(before, identity.state())
        }

    @Test
    fun `malformed public chain in the response drops the staged key`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val before = identity.state()
            service.issueAnswers += ArcTunnelTestServer.json(issueBody("garbage"))

            val error = runCatching { enroller.issue(identity, "0c01") }.exceptionOrNull()

            assertTrue(error != null)
            assertTrue(keys.deleted.contains("${prefix}identity-1"))
            assertEquals(before, identity.state())
        }

    @Test
    fun `an issued leaf alone is installed once, with no failure and no retry, and reported`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, netTrustingIssuerRoot(), withPublicChain = false)

            service.issueAnswers += ArcTunnelTestServer.json(issueBody(certPem(issuedLeaf)))
            enroller.issue(identity, "0c01")

            assertEquals(1, service.count(ISSUE_PATH))
            assertEquals(listOf(issuedLeaf), identity.state().publicChain)
            assertEquals(1, identity.state().generation)
            assertEquals(1, incompleteChainReports())
        }

    @Test
    fun `an issued bundle reaching a trusted root is installed whole and not reported`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, netTrustingIssuerRoot(), withPublicChain = false)
            val bundle = certPem(issuedLeaf) + "\n" + certPem(issuerIntermediate.certificate)

            service.issueAnswers += ArcTunnelTestServer.json(issueBody(bundle))
            enroller.issue(identity, "0c01")

            assertEquals(listOf(issuedLeaf, issuerIntermediate.certificate), identity.state().publicChain)
            assertEquals(0, incompleteChainReports())
        }

    @Test
    fun `a chain check that cannot run keeps the installed chain and reports nothing`() =
        runBlocking {
            val broken = spyk(net)
            every { broken.reachesTrustedRoot(any()) } throws KeyStoreException("no roots")
            val identity = ArcTunnelTestIdentity.create(keys, broken, withPublicChain = false)

            enroller.issue(identity, "0c01")

            assertEquals(1, identity.state().publicChain.size)
            assertEquals(1, service.count(ISSUE_PATH))
            assertEquals(0, incompleteChainReports())
        }

    @Test
    fun `a received certificate is announced once, then installed, then saved once`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val steps = CopyOnWriteArrayList<String>()

            enroller.issue(
                identity,
                "0c01",
                onCertificateReceived = { steps += "received at generation ${identity.state().generation}" },
                save = { steps += "saved at generation ${identity.state().generation}" },
            )

            assertEquals(listOf("received at generation 0", "saved at generation 1"), steps)
        }

    @Test
    fun `a refused issue announces nothing and saves nothing`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val steps = AtomicInteger()
            service.issueAnswers += apiError(400, retryable = false)

            val error =
                runCatching {
                    enroller.issue(identity, "0c01", { steps.incrementAndGet() }) { steps.incrementAndGet() }
                }.exceptionOrNull()

            assertTrue(error is ArcTunnelApiException, "error $error")
            assertEquals(0, steps.get())
        }

    @Test
    fun `a malformed answer announces nothing and saves nothing`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val steps = AtomicInteger()
            service.issueAnswers += ArcTunnelTestServer.json(issueBody("garbage"))

            val error =
                runCatching {
                    enroller.issue(identity, "0c01", { steps.incrementAndGet() }) { steps.incrementAndGet() }
                }.exceptionOrNull()

            assertTrue(error != null)
            assertEquals(0, steps.get())
            assertEquals(0, identity.state().generation)
        }

    @Test
    fun `a cancellation once the certificate is received still installs and saves it`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            val saved = AtomicReference<Int>()
            val caller = AtomicReference<Job>()
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    enroller.issue(identity, "0c01", { caller.get().cancel() }) {
                        saved.set(identity.state().generation)
                    }
                }
            caller.set(job)

            job.start()
            withTimeout(BUDGET_MS) { job.join() }

            assertTrue(job.isCancelled)
            assertEquals(1, saved.get())
            assertEquals(1, identity.state().generation)
            assertTrue(identity.state().publicChain.isNotEmpty())
            assertEquals(1, service.count(ISSUE_PATH))
        }

    @Test
    fun `a failing save never issues again`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)

            val error =
                runCatching {
                    enroller.issue(identity, "0c01") { throw IOException("disk full") }
                }.exceptionOrNull()

            assertTrue(error is IOException, "error $error")
            assertEquals(1, service.count(ISSUE_PATH))
            assertEquals(1, identity.state().generation)
            assertTrue(keys.hasKey(identity.state().identityAlias), "the installed identity must keep its key")
        }

    @Test
    fun `issue retries once over a fresh nonce`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(keys, net, withPublicChain = false)
            service.issueAnswers += apiError(503, retryable = true, retryAfter = 1)

            enroller.issue(identity, "0c01")

            assertEquals(1, service.count(NONCE_PATH))
            assertEquals(2, service.count(ISSUE_PATH))
            val nonces =
                service.bodies(ISSUE_PATH).map {
                    ArcTunnelJson.decodeFromString(ArcTunnelIssueRequest.serializer(), it).nonce
                }
            assertEquals(listOf("0c01", "0a01"), nonces)
            assertEquals(1, identity.state().generation)
        }

    private companion object {
        const val NONCE_PATH = "/api/v1/enroll/nonce"
        const val ENROLL_PATH = "/api/v1/enroll"
        const val ISSUE_PATH = "/api/v1/issue"
        const val NONCE_BASE = 0x0a00
        const val BUDGET_MS = 10_000L
    }
}
