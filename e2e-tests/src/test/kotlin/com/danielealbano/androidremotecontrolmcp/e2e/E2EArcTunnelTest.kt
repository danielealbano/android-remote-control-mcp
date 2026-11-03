package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E: the free E2E tunnel against a local tunnel server stack ([ArcTunnelStack]). The redroid phone enrolls
 * (attestation optional on the server: redroid's AndroidKeyStore is a software KeyMint), the MCP server starts
 * with the tunnel, and the JVM reaches `/health` (HTTP/2 over ALPN, and HTTP/1.1 from a client without ALPN), the
 * OAuth metadata (which must name the `https` URL over HTTP/1.1 too) and `tools/list` THROUGH the tunnel with the
 * Pebble root as trust anchor. It also runs the certificate chain handling on redroid's own Conscrypt. The node drain
 * runs last, because it stops the stack's first node. The shared container is restored (tunnel off, server restarted,
 * client refreshed) afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EArcTunnelTest {
    private lateinit var stack: ArcTunnelStack
    private lateinit var status: E2EArcTunnelStatus
    private val baseUrl = SharedAndroidContainer.mcpServerUrl

    @BeforeAll
    fun startStackAndTunnel() {
        SharedAndroidContainer.ensureAccessibilityService()
        val gateway = AndroidContainerSetup.containerGatewayIp()
        stack = ArcTunnelStack(publicIp = gateway)
        stack.start()

        // Enrollment is refused while the MCP server runs.
        AndroidContainerSetup.stopMcpServer(baseUrl)
        AndroidContainerSetup.configureArcTunnel(
            ArcTunnelE2ESettings(
                tunnelEnabled = true,
                provider = ArcTunnelE2ESettings.ARC_PROVIDER,
                baseAddress = ArcTunnelStack.BASE_ADDRESS,
                ctCheckEnabled = false,
                edgeOverride = stack.edgeOverride(gateway),
                trustedCaPem = stack.issuingRootPem,
            ),
        )
        AndroidContainerSetup.enrollArcTunnel()
        AndroidContainerSetup.awaitArcTunnel(timeoutMs = ENROLL_TIMEOUT_MS) { it.enrolled && it.enrollment == "Idle" }
        AndroidContainerSetup.startMcpServer()
        AndroidContainerSetup.waitForServerReady(baseUrl)
        status = AndroidContainerSetup.awaitArcTunnel(timeoutMs = CONNECT_TIMEOUT_MS) { it.tunnel == "Connected" }
    }

    @AfterAll
    fun restoreSharedContainer() {
        try {
            AndroidContainerSetup.stopMcpServer(baseUrl)
            AndroidContainerSetup.configureArcTunnel(
                ArcTunnelE2ESettings(
                    tunnelEnabled = false,
                    provider = ArcTunnelE2ESettings.CLOUDFLARE_PROVIDER,
                    baseAddress = ArcTunnelStack.BASE_ADDRESS,
                    ctCheckEnabled = true,
                ),
            )
            AndroidContainerSetup.startMcpServer()
            AndroidContainerSetup.waitForServerReady(baseUrl)
            SharedAndroidContainer.refreshMcpClient()
        } finally {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    fun `the public URL is the enrolled name under the tunnel domain`() {
        val url = requireNotNull(status.url) { "no URL in $status" }
        assertTrue(url.startsWith("https://") && url.endsWith(".${ArcTunnelStack.TUNNEL_DOMAIN}"), url)
        assertEquals(url, status.tunnelUrl)
    }

    @Test
    fun `health is served through the tunnel to a client without ALPN`() {
        val response = ArcTunnelPublicClient.get(stack, fqdn(), HEALTH)
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.contains("\"status\""), response)
    }

    @Test
    fun `OAuth metadata served over HTTP-1-1 names the https URL`() {
        val response = ArcTunnelPublicClient.get(stack, fqdn(), PROTECTED_RESOURCE)
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.contains("\"resource\":\"https://${fqdn()}/mcp\""), response)
    }

    @Test
    fun `health is served through the tunnel over HTTP-2`() {
        val request =
            Request
                .Builder()
                .url("${ArcTunnelPublicClient.stackUrl(fqdn())}$HEALTH")
                .build()

        ArcTunnelPublicClient.stackOkHttp(stack).newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals(Protocol.HTTP_2, it.protocol)
        }
    }

    @Test
    fun `tools are listed through the tunnel`() =
        runBlocking {
            val client = ArcTunnelPublicClient.stackMcpClient(stack, fqdn(), AndroidContainerSetup.E2E_BEARER_TOKEN)
            try {
                client.connect()
                val tools = client.listTools()
                assertTrue(tools.tools.any { it.name == "${AndroidContainerSetup.TOOL_NAME_PREFIX}press_home" })
            } finally {
                client.close()
            }
        }

    @Test
    fun `a second connection reuses the bound control stream`() {
        val first = ArcTunnelPublicClient.get(stack, fqdn(), HEALTH)
        val second = ArcTunnelPublicClient.get(stack, fqdn(), HEALTH)
        assertTrue(first.startsWith("HTTP/1.1 200") && second.startsWith("HTTP/1.1 200"), "$first\n$second")
        assertEquals("Connected", AndroidContainerSetup.queryArcTunnel().tunnel)
    }

    /** Redroid's own Conscrypt has the bug, and nothing else in this stack sends it a blank line. */
    @Test
    fun `the real parser keeps a bundle with blank lines whole and the real trust manager needs the intermediates`() {
        val probe = AndroidContainerSetup.probeArcTunnelChain()

        assertTrue(probe.savedCertificates >= 2, "$probe")
        assertTrue(
            probe.factoryCertificates < probe.savedCertificates,
            "this Conscrypt no longer cuts a bundle at a blank line, so this test no longer covers that bug: $probe",
        )
        assertEquals(probe.savedCertificates, probe.parsedCertificates, "$probe")
        assertTrue(probe.wholeReachesRoot, "$probe")
        assertFalse(probe.leafReachesRoot, "a leaf must not pass after its intermediates did: $probe")
    }

    /**
     * What a phone updated from an older app meets at its next tunnel start: the marker missing and, on the phones
     * affected, the chain cut to the leaf. Runs before the node drain, which stops the stack's first node.
     */
    @Test
    @Order(Int.MAX_VALUE - 1)
    fun `a saved chain cut to the leaf is renewed once at tunnel start and a whole one is only marked`() {
        val whole = ArcTunnelPublicClient.servedChain(stack, fqdn())
        assertTrue(whole.size >= 2, "the stack must issue a chain to cut: ${whole.size} certificates")

        restartTunnel { ArcTunnelSavedRecord.withoutCertCheck(ArcTunnelSavedRecord.withLeafOnly(it)) }

        val renewed = ArcTunnelPublicClient.servedChain(stack, fqdn())
        assertEquals(whole.size, renewed.size)
        assertNotEquals(whole.first(), renewed.first(), "the chain must come from a new certificate")
        assertTrue(ArcTunnelSavedRecord.certCheck(ArcTunnelStack.BASE_ADDRESS) > 0)

        restartTunnel { it }
        assertEquals(renewed.first(), ArcTunnelPublicClient.servedChain(stack, fqdn()).first(), "marked: no renewal")

        restartTunnel { ArcTunnelSavedRecord.withoutCertCheck(it) }
        assertEquals(renewed.first(), ArcTunnelPublicClient.servedChain(stack, fqdn()).first(), "whole: no renewal")
        assertTrue(ArcTunnelSavedRecord.certCheck(ArcTunnelStack.BASE_ADDRESS) > 0)
    }

    private fun restartTunnel(editSavedRecord: (JsonObject) -> JsonObject) {
        AndroidContainerSetup.stopMcpServer(baseUrl)
        ArcTunnelSavedRecord.edit(ArcTunnelStack.BASE_ADDRESS, editSavedRecord)
        AndroidContainerSetup.startMcpServer()
        AndroidContainerSetup.waitForServerReady(baseUrl)
        AndroidContainerSetup.awaitArcTunnel(timeoutMs = CONNECT_TIMEOUT_MS) { it.tunnel == "Connected" }
    }

    /**
     * The phone's edge override points at the first node only, so the phone can only reach the second node through a
     * MOVE: the tunnel must still serve through the second node once the first one is gone.
     */
    @Test
    @Order(Int.MAX_VALUE)
    fun `a draining node moves the phone to the other node and its connections finish`() {
        stack.startSecondReplica()
        val second = ArcTunnelStack.SECOND_EDGE_HOST_PORT
        ArcTunnelPublicClient.KeepAlive(stack, fqdn(), ArcTunnelStack.EDGE_HOST_PORT).use { inFlight ->
            assertOk(inFlight.get(HEALTH))
            val connectionsBeforeDrain = stack.secondReplicaConnections()

            stack.stopFirstReplicaGracefully()

            awaitTrue("the phone to open a connection to the second node on MOVE") {
                stack.secondReplicaConnections() > connectionsBeforeDrain
            }
            assertOk(inFlight.get(HEALTH))
            assertOk(ArcTunnelPublicClient.get(stack, fqdn(), HEALTH))
            assertOk(healthThrough(second))

            val beforeDrop = stack.secondReplicaConnections()
            stack.dropSecondReplicaConnections()
            awaitTrue("the phone, reconnecting to the draining node, to move to the second node again") {
                stack.secondReplicaConnections() > beforeDrop
            }
            awaitTrue("the tunnel to serve through the second node after the second move") {
                healthThrough(second).startsWith(HTTP_OK)
            }
            assertOk(inFlight.get(HEALTH))
        }

        awaitTrue("the first node to finish its drain and exit") { !stack.firstReplicaRunning() }
        assertOk(healthThrough(second))
        assertEquals("Connected", AndroidContainerSetup.queryArcTunnel().tunnel)
    }

    private fun healthThrough(edgePort: Int): String =
        runCatching { ArcTunnelPublicClient.get(stack, fqdn(), HEALTH, edgePort) }.getOrElse { it.toString() }

    private fun assertOk(response: String) {
        assertTrue(response.startsWith(HTTP_OK), response)
    }

    private fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + DRAIN_STEP_TIMEOUT_MS
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for $what")
            Thread.sleep(POLL_MS)
        }
    }

    private fun fqdn(): String = requireNotNull(status.url).removePrefix("https://")

    companion object {
        private const val ENROLL_TIMEOUT_MS = 180_000L
        private const val CONNECT_TIMEOUT_MS = 120_000L
        private const val DRAIN_STEP_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 250L
        private const val HTTP_OK = "HTTP/1.1 200"
        private const val HEALTH = "/health"
        private const val PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource"
    }
}
