package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollment
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnrollmentState
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelEnv
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("E2EConfigReceiver")
class E2EConfigReceiverTest {
    private lateinit var mockContext: Context
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockStorageLocationProvider: StorageLocationProvider
    private lateinit var mockArcTunnelEnrollment: ArcTunnelEnrollment
    private lateinit var receiver: E2EConfigReceiver

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockContext = mockk(relaxed = true)
        mockSettingsRepository = mockk(relaxed = true)
        mockStorageLocationProvider = mockk(relaxed = true)

        receiver = E2EConfigReceiver()
        receiver.settingsRepository = mockSettingsRepository
        receiver.storageLocationProvider = mockStorageLocationProvider
        receiver.tunnelManager = mockk { every { tunnelStatus } returns MutableStateFlow(TunnelStatus.Disconnected) }
        mockArcTunnelEnrollment = mockk(relaxed = true)
        receiver.arcTunnelEnrollment = mockArcTunnelEnrollment
        mockkStatic(Base64::class)
        withServerStatus(ServerStatus.Stopped)

        // Bypass Hilt injection by marking the receiver as already injected.
        // The Hilt-generated base class has a volatile boolean `injected` field
        // that gates the inject(context) call in onReceive(). Setting it to true
        // prevents the Hilt injection from running, so our manually-set mocks are used.
        val hiltBaseClass = checkNotNull(receiver.javaClass.superclass) { "E2EConfigReceiver has no Hilt base class" }
        val injectedField = hiltBaseClass.getDeclaredField("injected")
        injectedField.isAccessible = true
        injectedField.setBoolean(receiver, true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun withServerStatus(status: ServerStatus) {
        mockkObject(McpServerService.Companion)
        every { McpServerService.serverStatus } returns MutableStateFlow(status)
    }

    /** A relaxed configure intent: every string extra is `""` (absent) unless stubbed, every flag absent. */
    private fun configureIntent(): Intent =
        mockk<Intent>(relaxed = true).also {
            every { it.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { it.getIntExtra(any(), any()) } returns -1
        }

    private fun actionIntent(action: String): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        return intent
    }

    @Test
    fun `ACTION_E2E_CONFIGURE constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_CONFIGURE",
            E2EConfigReceiver.ACTION_E2E_CONFIGURE,
        )
    }

    @Test
    fun `ACTION_E2E_START_SERVER constant is defined`() {
        assertEquals(
            "com.danielealbano.androidremotecontrolmcp.debug.E2E_START_SERVER",
            E2EConfigReceiver.ACTION_E2E_START_SERVER,
        )
    }

    @Test
    fun `handleConfigure updates bearer token`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns "test-token-abc"
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateBearerToken("test-token-abc") } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateBearerToken("test-token-abc") }
        }

    @Test
    fun `handleConfigure updates binding address to NETWORK`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns "0.0.0.0"
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateBindingAddress(BindingAddress.NETWORK) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateBindingAddress(BindingAddress.NETWORK) }
        }

    @Test
    fun `handleConfigure updates port`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns 9090
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updatePort(9090) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updatePort(9090) }
        }

    @Test
    fun `handleConfigure updates auto start on boot`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns true
            every { intent.getBooleanExtra("auto_start_on_boot", false) } returns true
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateAutoStartOnBoot(true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateAutoStartOnBoot(true) }
        }

    @Test
    fun `handleConfigure updates hide from recents`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.hasExtra("hide_from_recents") } returns true
            every { intent.getBooleanExtra("hide_from_recents", false) } returns true
            every { intent.getStringExtra("storage_location_id") } returns null

            coEvery { mockSettingsRepository.updateHideFromRecents(true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockSettingsRepository.updateHideFromRecents(true) }
        }

    @Test
    fun `handleConfigure updates storage location write permission`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "builtin:downloads"
            every { intent.hasExtra("storage_allow_write") } returns true
            every { intent.getBooleanExtra("storage_allow_write", false) } returns true
            every { intent.hasExtra("storage_allow_delete") } returns false

            coEvery { mockStorageLocationProvider.isLocationAuthorized("builtin:downloads") } returns true
            coEvery { mockStorageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockStorageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) }
        }

    @Test
    fun `handleConfigure updates storage location delete permission`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "builtin:downloads"
            every { intent.hasExtra("storage_allow_write") } returns false
            every { intent.hasExtra("storage_allow_delete") } returns true
            every { intent.getBooleanExtra("storage_allow_delete", false) } returns true

            coEvery { mockStorageLocationProvider.isLocationAuthorized("builtin:downloads") } returns true
            coEvery { mockStorageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) } just Runs

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify { mockStorageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) }
        }

    @Test
    fun `handleConfigure skips unknown storage location`() =
        runTest {
            val intent = mockk<Intent>(relaxed = true)
            every { intent.action } returns E2EConfigReceiver.ACTION_E2E_CONFIGURE
            every { intent.getStringExtra("bearer_token") } returns null
            every { intent.getStringExtra("binding_address") } returns null
            every { intent.getIntExtra("port", -1) } returns -1
            every { intent.hasExtra("auto_start_on_boot") } returns false
            every { intent.getStringExtra("storage_location_id") } returns "unknown:location"
            every { intent.hasExtra("storage_allow_write") } returns true
            every { intent.getBooleanExtra("storage_allow_write", false) } returns true

            coEvery { mockStorageLocationProvider.isLocationAuthorized("unknown:location") } returns false

            receiver.onReceive(mockContext, intent)
            Thread.sleep(COROUTINE_WAIT_MS)

            coVerify(exactly = 0) { mockStorageLocationProvider.updateLocationAllowWrite(any(), any()) }
        }

    @Test
    fun `handleConfigure runs the migration before any write`() {
        val intent = configureIntent()
        every { intent.getStringExtra("bearer_token") } returns "t"

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateBearerToken("t") }
        coVerifyOrder {
            mockSettingsRepository.ensureAuthModelMigrated()
            mockSettingsRepository.updateBearerToken("t")
        }
    }

    @Test
    fun `handleConfigure applies the tunnel settings`() {
        val intent = configureIntent()
        every { intent.hasExtra("tunnel_enabled") } returns true
        every { intent.getBooleanExtra("tunnel_enabled", false) } returns true
        every { intent.getStringExtra("tunnel_provider") } returns TunnelProviderType.ARC_TUNNEL.adbValue
        every { intent.getStringExtra("arc_tunnel_base_address") } returns "example.test"
        every { intent.hasExtra("arc_tunnel_ct_check_enabled") } returns true
        every { intent.getBooleanExtra("arc_tunnel_ct_check_enabled", true) } returns false

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateArcTunnelCtCheckEnabled(false) }
        coVerify(exactly = 1) { mockSettingsRepository.updateTunnelEnabled(true) }
        coVerify(exactly = 1) { mockSettingsRepository.updateTunnelProvider(TunnelProviderType.ARC_TUNNEL) }
        coVerify(exactly = 1) { mockSettingsRepository.updateArcTunnelBaseAddress("example.test") }
    }

    @Test
    fun `handleConfigure updates block local network downloads`() {
        val intent = configureIntent()
        every { intent.hasExtra("block_local_network_downloads") } returns true
        every { intent.getBooleanExtra("block_local_network_downloads", false) } returns true

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateBlockLocalNetworkDownloads(true) }
    }

    @Test
    fun `handleConfigure leaves block local network downloads untouched when absent`() {
        val intent = configureIntent()

        receiver.onReceive(mockContext, intent)
        verify(timeout = VERIFY_TIMEOUT_MS) { Log.i(any(), CONFIGURE_DONE) }

        coVerify(exactly = 0) { mockSettingsRepository.updateBlockLocalNetworkDownloads(any()) }
    }

    @Test
    fun `handleConfigure ignores an unknown provider and an invalid base address`() {
        val intent = configureIntent()
        every { intent.getStringExtra("tunnel_provider") } returns "nope"
        every { intent.getStringExtra("arc_tunnel_base_address") } returns "x"

        receiver.onReceive(mockContext, intent)
        verify(timeout = VERIFY_TIMEOUT_MS) { Log.i(any(), CONFIGURE_DONE) }

        coVerify(exactly = 0) { mockSettingsRepository.updateTunnelProvider(any()) }
        coVerify(exactly = 0) { mockSettingsRepository.updateArcTunnelBaseAddress(any()) }
    }

    @Test
    fun `handleConfigure stores the override and the decoded PEM`() {
        coEvery { mockSettingsRepository.getServerConfig() } returns ServerConfig()
        every { Base64.decode(PEM_B64, Base64.DEFAULT) } returns PEM.toByteArray()
        val intent = configureIntent()
        every { intent.getStringExtra("arc_tunnel_edge_override") } returns EDGE
        every { intent.getStringExtra("arc_tunnel_trusted_ca_pem_b64") } returns PEM_B64

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateArcTunnelE2EOverride(EDGE, PEM) }
    }

    @Test
    fun `handleConfigure clears the override and the PEM with the sentinel`() {
        coEvery { mockSettingsRepository.getServerConfig() } returns stored()
        val intent = configureIntent()
        every { intent.getStringExtra("arc_tunnel_edge_override") } returns "-"
        every { intent.getStringExtra("arc_tunnel_trusted_ca_pem_b64") } returns "-"

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateArcTunnelE2EOverride("", "") }
    }

    @Test
    fun `handleConfigure keeps the stored PEM on invalid Base64`() {
        coEvery { mockSettingsRepository.getServerConfig() } returns stored()
        every { Base64.decode("!!", Base64.DEFAULT) } throws IllegalArgumentException("bad base-64")
        val intent = configureIntent()
        every { intent.getStringExtra("arc_tunnel_trusted_ca_pem_b64") } returns "!!"

        receiver.onReceive(mockContext, intent)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { mockSettingsRepository.updateArcTunnelE2EOverride(EDGE, PEM) }
    }

    @Test
    fun `handleConfigure leaves the override untouched when its extras are empty`() {
        val intent = configureIntent()
        every { intent.getStringExtra("bearer_token") } returns "t"

        receiver.onReceive(mockContext, intent)

        verify(timeout = VERIFY_TIMEOUT_MS) { Log.i(any(), CONFIGURE_DONE) }
        coVerify(exactly = 1) { mockSettingsRepository.updateBearerToken("t") }
        coVerify(exactly = 0) { mockSettingsRepository.updateArcTunnelE2EOverride(any(), any()) }
    }

    @Test
    fun `stop server sends ACTION_STOP`() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }

        receiver.onReceive(mockContext, actionIntent(E2EConfigReceiver.ACTION_E2E_STOP_SERVER))

        verify(exactly = 1) { anyConstructed<Intent>().setAction(McpServerService.ACTION_STOP) }
        verify(exactly = 1) { mockContext.startForegroundService(any()) }
    }

    @Test
    fun `enroll requests enrollment with the current env`() {
        val config = ServerConfig(arcTunnelBaseAddress = "example.test")
        coEvery { mockSettingsRepository.getServerConfig() } returns config

        receiver.onReceive(mockContext, actionIntent(E2EConfigReceiver.ACTION_E2E_ARC_TUNNEL_ENROLL))

        verify(timeout = VERIFY_TIMEOUT_MS) { mockArcTunnelEnrollment.requestEnroll(ArcTunnelEnv.from(config)) }
    }

    @Test
    fun `enroll is ignored while the server runs`() {
        withServerStatus(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"))

        receiver.onReceive(mockContext, actionIntent(E2EConfigReceiver.ACTION_E2E_ARC_TUNNEL_ENROLL))

        verify(exactly = 1) { Log.w(any(), "E2E enroll ignored: stop the MCP server first") }
        verify(exactly = 0) { mockArcTunnelEnrollment.requestEnroll(any()) }
    }

    @Test
    fun `query answers Base64 JSON in the result data`() {
        coEvery { mockSettingsRepository.getServerConfig() } returns
            ServerConfig(tunnelProvider = TunnelProviderType.ARC_TUNNEL)
        every { mockArcTunnelEnrollment.state } returns MutableStateFlow(ArcTunnelEnrollmentState.Idle)
        every { mockArcTunnelEnrollment.savedUrl(any()) } returns null
        var encodedJson = ""
        every { Base64.encodeToString(any(), Base64.NO_WRAP) } answers {
            encodedJson = String(firstArg<ByteArray>())
            MARKER
        }
        val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        val spy = spyk(receiver)
        every { spy.goAsync() } returns pending

        spy.onReceive(mockContext, actionIntent(E2EConfigReceiver.ACTION_E2E_ARC_TUNNEL_QUERY))

        verify(timeout = VERIFY_TIMEOUT_MS) { pending.finish() }
        verifyOrder {
            pending.setResultData(MARKER)
            pending.finish()
        }
        assertTrue(encodedJson.contains("\"tunnel_provider\":\"android-remote-control-mcp-tunneld\""), encodedJson)
        assertTrue(encodedJson.contains("\"bearer_token_enabled\""), encodedJson)
    }

    private fun stored(): ServerConfig = ServerConfig(arcTunnelEdgeOverride = EDGE, arcTunnelTrustedCaPem = PEM)

    companion object {
        private const val COROUTINE_WAIT_MS = 200L
        private const val VERIFY_TIMEOUT_MS = 5_000L
        private const val CONFIGURE_DONE = "E2E configuration applied successfully"
        private const val EDGE = "10.88.0.1:18443"
        private const val PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
        private const val PEM_B64 = "UEVN"
        private const val MARKER = "marker"
    }
}
