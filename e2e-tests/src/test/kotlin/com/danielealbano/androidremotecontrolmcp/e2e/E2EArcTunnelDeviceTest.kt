package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * Device-gated E2E against the PRODUCTION tunnel service: real hardware attestation, real ACME certificate,
 * WebPKI trust. Skipped when no phone is attached (CI). Prerequisite: the local debug signer is allowlisted
 * on the server. The phone's tunnel settings (except the debug-only trusted CA PEM, which is not queryable and
 * is cleared by the restore) and bearer-enforcement flag are snapshotted and restored;
 * the bearer token is random per run and replaced by another random one afterwards (the public URL lands in
 * CT logs within minutes, so the committed E2E token is never exposed, and the previous token cannot be read
 * back). The phone keeps its enrollment (an enrolled phone is a no-op for `execute`); a server already running
 * on it is stopped first and left stopped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EArcTunnelDeviceTest {
    private var serial: String? = null
    private var before: E2EArcTunnelStatus? = null
    private lateinit var status: E2EArcTunnelStatus
    private val token = UUID.randomUUID().toString()

    @BeforeAll
    fun enrollAndStart() {
        val found = DeviceAdb.attachedSerial()
        assumeTrue(found != null, "No phone attached: skipping the production tunnel test")
        val device = requireNotNull(found)
        serial = device
        val rootDir = System.getProperty("project.rootDir") ?: error("System property 'project.rootDir' not set")
        DeviceAdb.install(device, "$rootDir/app/build/outputs/apk/gms/debug/app-gms-debug.apk")
        AndroidContainerSetup.stopMcpServerOn(device)
        before = AndroidContainerSetup.queryArcTunnel(device)
        setBearer(device, token, enabled = true)
        AndroidContainerSetup.configureArcTunnel(
            ArcTunnelE2ESettings(
                tunnelEnabled = true,
                provider = ArcTunnelE2ESettings.ARC_PROVIDER,
                baseAddress = PRODUCTION_BASE_ADDRESS,
                ctCheckEnabled = true,
            ),
            serial = device,
        )
        AndroidContainerSetup.enrollArcTunnel(device)
        AndroidContainerSetup.awaitArcTunnel(device, ENROLL_TIMEOUT_MS) { it.enrolled && it.enrollment == "Idle" }
        AndroidContainerSetup.startMcpServerOn(device)
        status = AndroidContainerSetup.awaitArcTunnel(device, CONNECT_TIMEOUT_MS) { it.tunnel == "Connected" }
    }

    @AfterAll
    fun restoreDevice() {
        val device = serial ?: return
        AndroidContainerSetup.stopMcpServerOn(device)
        val previous = before
        if (previous != null) {
            AndroidContainerSetup.configureArcTunnel(previous.toSettings(), serial = device)
            setBearer(device, UUID.randomUUID().toString(), enabled = previous.bearerTokenEnabled)
        }
    }

    @Test
    fun `tools are listed through the production tunnel with WebPKI trust`() =
        runBlocking {
            val url = requireNotNull(status.url) { "no URL in $status" }
            val client = ArcTunnelPublicClient.systemTrustMcpClient(url, token)
            try {
                client.connect()
                val names = client.listTools().tools.map { it.name }
                // The phone may carry a device slug, which changes the tool prefix: match the suffix only.
                assertTrue(names.any { it.endsWith("press_home") }, names.toString())
            } finally {
                client.close()
            }
        }

    /** The token never reaches a log: a failed broadcast is reported without the command line. */
    private fun setBearer(
        device: String,
        value: String,
        enabled: Boolean,
    ) {
        try {
            AndroidContainerSetup.execAdbOn(
                device, "shell", "am", "broadcast", "--include-stopped-packages",
                "-a", AndroidContainerSetup.E2E_CONFIGURE_ACTION, "-n", AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
                "--es", "bearer_token", value,
                "--ez", "bearer_token_enabled", enabled.toString(),
            )
        } catch (_: IllegalStateException) {
            throw IllegalStateException("bearer token broadcast failed on $device")
        }
        AndroidContainerSetup.awaitArcTunnel(device, 30_000L) { it.bearerTokenEnabled == enabled }
    }

    companion object {
        /** The production base address: confirmed with the user before the quality gates run. */
        private const val PRODUCTION_BASE_ADDRESS = "arc-tunnel.io"
        private const val ENROLL_TIMEOUT_MS = 180_000L
        private const val CONNECT_TIMEOUT_MS = 120_000L
    }
}
