package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@DisplayName("ArcTunnelNet")
class ArcTunnelNetTest {
    private lateinit var server: MockWebServer
    private lateinit var net: ArcTunnelNet

    @BeforeEach
    fun setUp() {
        server = ArcTunnelTestServer.start()
        net = ArcTunnelTestServer.net(server.port)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun get(url: String): Int {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return net
            .serverClient()
            .newCall(request)
            .execute()
            .use { it.code }
    }

    /** root -> intermediate -> leaf, with [trusted] as the only trust anchor. */
    private class TrustFixture {
        val root: HeldCertificate =
            HeldCertificate
                .Builder()
                .certificateAuthority(1)
                .commonName("root")
                .build()
        val intermediate: HeldCertificate =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .signedBy(root)
                .commonName("intermediate")
                .build()
        val leaf: X509Certificate =
            HeldCertificate
                .Builder()
                .signedBy(intermediate)
                .commonName("leaf")
                .build()
                .certificate
        val trusted: ArcTunnelNet = ArcTunnelNet(ArcTunnelPem.encode("CERTIFICATE", root.certificate.encoded), null)
    }

    @Test
    fun `a chain with its intermediate reaches the trusted root`() {
        val t = TrustFixture()

        assertTrue(t.trusted.reachesTrustedRoot(listOf(t.leaf, t.intermediate.certificate)))
        assertTrue(t.trusted.reachesTrustedRoot(listOf(t.leaf, t.intermediate.certificate, t.root.certificate)))
    }

    @Test
    fun `a leaf whose intermediate is missing does not reach the trusted root`() {
        val t = TrustFixture()

        assertFalse(t.trusted.reachesTrustedRoot(listOf(t.leaf)))
    }

    @Test
    fun `a bare leaf still fails after the whole chain passed`() {
        val t = TrustFixture()

        assertTrue(t.trusted.reachesTrustedRoot(listOf(t.leaf, t.intermediate.certificate)))

        assertFalse(t.trusted.reachesTrustedRoot(listOf(t.leaf)))
    }

    @Test
    fun `an empty chain and a chain under another root do not reach the trusted root`() {
        val t = TrustFixture()
        val other = TrustFixture()

        assertFalse(t.trusted.reachesTrustedRoot(emptyList()))
        assertFalse(t.trusted.reachesTrustedRoot(listOf(other.leaf, other.intermediate.certificate)))
    }

    @Test
    fun `every root of a trust bundle with blank lines between them is trusted`() {
        val first = TrustFixture()
        val second = TrustFixture()
        val bundle =
            listOf(first.root, second.root).joinToString("\n") {
                ArcTunnelPem.encode("CERTIFICATE", it.certificate.encoded)
            }
        val trusting = ArcTunnelNet(bundle, null)

        assertTrue(trusting.reachesTrustedRoot(listOf(first.leaf, first.intermediate.certificate)))
        assertTrue(trusting.reachesTrustedRoot(listOf(second.leaf, second.intermediate.certificate)))
    }

    @Test
    fun `url without override is https host path`() {
        assertEquals("https://enroll.example.test/x", ArcTunnelNet(null, null).url("enroll.example.test", "/x"))
    }

    @Test
    fun `url with override carries the port and keeps the host`() {
        assertEquals("https://enroll.example.test:${server.port}/x", net.url("enroll.example.test", "/x"))
    }

    @Test
    fun `clients resolve the override address`() {
        server.enqueue(ArcTunnelTestServer.json("{}"))

        assertEquals(200, get(net.url(ArcTunnelTestServer.ENROLL_HOST, "/probe")))
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/probe", recorded?.url?.encodedPath)
        assertEquals(ArcTunnelTestServer.ENROLL_HOST, recorded?.url?.host)
    }

    @Test
    fun `dialBack allows local targets only with an override`() =
        runBlocking {
            val identity = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), net)
            ArcTunnelTestPeers().use { peers ->
                val target = ArcTunnelDialBack.Target("t", "127.0.0.1:${peers.dial.localPort}", false, false)
                val noOverride = ArcTunnelNet(null, null).dialBack(ArcTunnelTestServer.CONTROL_HOST, identity)

                val refused = runCatching { noOverride.open(target, ArcTunnelSessions {}) }.exceptionOrNull()

                assertTrue(refused is IllegalArgumentException, "refused with $refused")
                val accepted = AtomicReference<Socket>()
                val upgraded = CountDownLatch(1)
                peers.acceptUpgrade(accepted, upgraded)
                val conn = net.dialBack(ArcTunnelTestServer.CONTROL_HOST, identity).open(target, ArcTunnelSessions {})
                assertTrue(upgraded.await(5, TimeUnit.SECONDS))
                conn.socket.close()
            }
        }

    @Test
    fun `controlClient has no read timeout`() {
        val identity = ArcTunnelTestIdentity.create(FakeArcTunnelKeyStore(), net)

        assertEquals(0, net.controlClient(identity).readTimeoutMillis)
    }

    @Test
    fun `DynamicIdentityKeyManager picks the current identity per handshake`() {
        val keys = FakeArcTunnelKeyStore()
        val identity = ArcTunnelTestIdentity.create(keys, net)
        val manager = ArcTunnelNet.DynamicIdentityKeyManager(identity)

        val first = manager.chooseClientAlias(null, null, null)
        val firstChain = manager.getCertificateChain(first)
        val firstKey = manager.getPrivateKey(first)
        val state = identity.state()
        val nextAlias = "${ArcTunnelEnroller.aliasPrefix(identity.hosts)}identity-1"
        keys.generateEcKey(nextAlias)
        val nextChain = ArcTunnelTestIdentity.chainFor(keys, nextAlias, identity.name)
        identity.install(nextAlias, nextChain, state.publicChain, state.caId, 1)
        val second = manager.chooseClientAlias(null, null, null)

        assertNotEquals(first, second)
        assertEquals(nextAlias, second)
        assertArrayEquals(nextChain.toTypedArray(), manager.getCertificateChain(second))
        assertSame(keys.privateKey(nextAlias), manager.getPrivateKey(second))
        assertArrayEquals(state.identityChain.toTypedArray(), firstChain)
        assertSame(keys.privateKey(state.identityAlias), firstKey)
    }

    @Test
    fun `close evicts and keeps the client usable`() {
        server.enqueue(ArcTunnelTestServer.json("{}"))
        server.enqueue(ArcTunnelTestServer.json("{}"))
        assertEquals(200, get(net.url(ArcTunnelTestServer.ENROLL_HOST, "/a")))

        net.close()

        assertEquals(200, get(net.url(ArcTunnelTestServer.ENROLL_HOST, "/b")))
    }
}
