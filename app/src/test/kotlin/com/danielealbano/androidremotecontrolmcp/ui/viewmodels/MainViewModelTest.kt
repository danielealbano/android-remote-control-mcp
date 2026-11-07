package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.ListenerSettingsCheck
import com.danielealbano.androidremotecontrolmcp.mcp.McpListenerRules
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.PendingApproval
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import com.danielealbano.androidremotecontrolmcp.services.power.BatteryOptimizationManager
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var tunnelManager: TunnelManager
    private lateinit var storageLocationProvider: StorageLocationProvider
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager
    private lateinit var approvalCoordinator: OAuthApprovalCoordinator
    private lateinit var listenerRules: McpListenerRules
    private lateinit var configFlow: MutableStateFlow<ServerConfig>
    private lateinit var tunnelStatusFlow: MutableStateFlow<TunnelStatus>
    private lateinit var pendingApprovalsFlow: MutableStateFlow<List<PendingApproval>>
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        configFlow =
            MutableStateFlow(
                ServerConfig(
                    port = 8080,
                    bindingAddress = BindingAddress.LOCALHOST,
                    bearerToken = "test-token-123",
                    autoStartOnBoot = false,
                    httpsEnabled = false,
                    certificateSource = CertificateSource.AUTO_GENERATED,
                    certificateHostname = "android-mcp.local",
                ),
            )

        tunnelStatusFlow = MutableStateFlow(TunnelStatus.Disconnected)

        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.serverConfig } returns configFlow
        every { settingsRepository.validateScreenAwakeTimeout(any()) } answers {
            val seconds = firstArg<Int>()
            if (ServerConfig.isValidScreenAwakeTimeout(seconds)) {
                Result.success(seconds)
            } else {
                Result.failure(IllegalArgumentException("Invalid screen-awake timeout"))
            }
        }

        tunnelManager = mockk(relaxed = true)
        every { tunnelManager.tunnelStatus } returns tunnelStatusFlow

        storageLocationProvider = mockk(relaxed = true)

        batteryOptimizationManager = mockk(relaxed = true)

        pendingApprovalsFlow = MutableStateFlow(emptyList())
        approvalCoordinator = mockk(relaxed = true)
        every { approvalCoordinator.observePending() } returns pendingApprovalsFlow

        listenerRules = mockk()
        every { listenerRules.check(any()) } returns ListenerSettingsCheck()

        viewModel =
            MainViewModel(
                settingsRepository,
                tunnelManager,
                storageLocationProvider,
                batteryOptimizationManager,
                testDispatcher,
                approvalCoordinator,
                listenerRules,
            )
    }

    @Test
    fun `screen awake input validates without writing invalid values`() =
        runTest {
            advanceUntilIdle()
            listOf("", "abc", "-1", "59", "172801", "2147483648").forEach { input ->
                viewModel.updateScreenAwakeTimeout(input)
                advanceUntilIdle()
                assertTrue(viewModel.screenAwakeTimeoutError.value != null)
            }
            coVerify(exactly = 0) { settingsRepository.updateScreenAwakeTimeout(any()) }
            viewModel.updateScreenAwakeTimeout("60")
            advanceUntilIdle()
            assertNull(viewModel.screenAwakeTimeoutError.value)
            coVerify(exactly = 1) { settingsRepository.updateScreenAwakeTimeout(60) }
        }

    @Test
    fun `screen awake input follows persisted config updates`() =
        runTest {
            advanceUntilIdle()
            assertEquals("0", viewModel.screenAwakeTimeoutInput.value)
            configFlow.value = configFlow.value.copy(screenAwakeTimeoutSeconds = 300)
            advanceUntilIdle()
            assertEquals("300", viewModel.screenAwakeTimeoutInput.value)
        }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun pendingApproval(id: String): PendingApproval =
        PendingApproval(
            id = id,
            clientName = "client-$id",
            redirectHost = "example.com",
            matchCode = "42",
            expiresAtMs = 0L,
        )

    @Test
    fun `pendingApprovalCount is zero when no pending approvals`() =
        runTest {
            viewModel.pendingApprovalCount.test {
                assertEquals(0, expectMostRecentItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pendingApprovalCount reflects list size`() =
        runTest {
            viewModel.pendingApprovalCount.test {
                assertEquals(0, awaitItem())
                pendingApprovalsFlow.value = listOf(pendingApproval("a"), pendingApproval("b"))
                assertEquals(2, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pendingApprovalCount updates on coordinator emission`() =
        runTest {
            viewModel.pendingApprovalCount.test {
                assertEquals(0, awaitItem())
                pendingApprovalsFlow.value = listOf(pendingApproval("a"))
                assertEquals(1, awaitItem())
                pendingApprovalsFlow.value = listOf(pendingApproval("a"), pendingApproval("b"))
                assertEquals(2, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `initial state loads from repository`() =
        runTest {
            advanceUntilIdle()

            assertEquals(8080, viewModel.serverConfig.value.port)
            assertEquals(BindingAddress.LOCALHOST, viewModel.serverConfig.value.bindingAddress)
            assertEquals("test-token-123", viewModel.serverConfig.value.bearerToken)
            assertEquals("8080", viewModel.portInput.value)
            assertEquals("android-mcp.local", viewModel.hostnameInput.value)
        }

    @Test
    fun `initial server status is Stopped`() =
        runTest {
            assertEquals(ServerStatus.Stopped, viewModel.serverStatus.value)
        }

    @Test
    fun `a retry that fails again shows the error, never a Starting the service did not publish`() =
        runTest {
            val failed = ServerStatus.Error("Cannot listen on 0.0.0.0:8080: Address already in use")
            val serviceStatus = MutableStateFlow<ServerStatus>(failed)
            mockkObject(McpServerService.Companion)
            every { McpServerService.serverStatus } returns serviceStatus
            try {
                viewModel =
                    MainViewModel(
                        settingsRepository,
                        tunnelManager,
                        storageLocationProvider,
                        batteryOptimizationManager,
                        testDispatcher,
                        approvalCoordinator,
                        listenerRules,
                    )
                advanceUntilIdle()

                viewModel.startServer(mockk<Context>(relaxed = true))
                // The retry fails again before the status is collected: Starting and the same Error back to back
                serviceStatus.value = ServerStatus.Starting
                serviceStatus.value = failed
                advanceUntilIdle()

                assertEquals(failed, viewModel.serverStatus.value)
            } finally {
                unmockkObject(McpServerService.Companion)
            }
        }

    @Test
    fun `serverConfig collection sets the specific address and tunnel port inputs`() =
        runTest {
            configFlow.value = configFlow.value.copy(specificBindAddress = "192.168.1.20", tunnelLocalPort = 49200)
            advanceUntilIdle()

            assertEquals("192.168.1.20", viewModel.specificBindAddressInput.value)
            assertEquals("49200", viewModel.tunnelLocalPortInput.value)
        }

    @Test
    fun `updateSpecificBindAddress saves the address as typed`() =
        runTest {
            viewModel.updateSpecificBindAddress(" 192.168.")
            advanceUntilIdle()

            assertEquals(" 192.168.", viewModel.specificBindAddressInput.value)
            coVerify { settingsRepository.updateSpecificBindAddress(" 192.168.") }
        }

    @Test
    fun `updateTunnelLocalPort saves a valid port`() =
        runTest {
            every { settingsRepository.validatePort(49200) } returns Result.success(49200)

            viewModel.updateTunnelLocalPort("49200")
            advanceUntilIdle()

            assertNull(viewModel.tunnelLocalPortError.value)
            coVerify { settingsRepository.updateTunnelLocalPort(49200) }
        }

    @Test
    fun `updateTunnelLocalPort reports an invalid port and saves nothing`() =
        runTest {
            every { settingsRepository.validatePort(70000) } returns
                Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))

            viewModel.updateTunnelLocalPort("70000")
            assertEquals("Port must be between 1 and 65535", viewModel.tunnelLocalPortError.value)
            viewModel.updateTunnelLocalPort("")
            assertEquals("Port is required", viewModel.tunnelLocalPortError.value)
            viewModel.updateTunnelLocalPort("49x")
            assertEquals("Port must be a number", viewModel.tunnelLocalPortError.value)
            advanceUntilIdle()

            assertEquals("49x", viewModel.tunnelLocalPortInput.value)
            coVerify(exactly = 0) { settingsRepository.updateTunnelLocalPort(any()) }
        }

    @Test
    fun `the listener check follows the config and is refreshed on demand`() =
        runTest {
            val blocked = ListenerSettingsCheck(specificAddressError = "10.0.0.9 is not an address of this device")
            every { listenerRules.check(match { it.specificBindAddress == "10.0.0.9" }) } returns blocked

            configFlow.value = configFlow.value.copy(specificBindAddress = "10.0.0.9")
            advanceUntilIdle()
            assertEquals(blocked, viewModel.listenerCheck.value)

            // The device got the address meanwhile
            every { listenerRules.check(any()) } returns ListenerSettingsCheck()
            viewModel.refreshListenerCheck()
            advanceUntilIdle()
            assertEquals(ListenerSettingsCheck(), viewModel.listenerCheck.value)
        }

    @Test
    fun `updatePort with valid port clears error and saves`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("9090")
            advanceUntilIdle()

            assertEquals("9090", viewModel.portInput.value)
            assertNull(viewModel.portError.value)
            coVerify { settingsRepository.updatePort(9090) }
        }

    @Test
    fun `updatePort with port too low sets error`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("0")
            advanceUntilIdle()

            assertEquals("0", viewModel.portInput.value)
            assertEquals("Port must be between 1 and 65535", viewModel.portError.value)
            coVerify(exactly = 0) { settingsRepository.updatePort(0) }
        }

    @Test
    fun `updatePort with port too high sets error`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("65536")
            advanceUntilIdle()

            assertEquals("65536", viewModel.portInput.value)
            assertEquals("Port must be between 1 and 65535", viewModel.portError.value)
            coVerify(exactly = 0) { settingsRepository.updatePort(65536) }
        }

    @Test
    fun `updatePort with non-numeric input sets error`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("abc")
            advanceUntilIdle()

            assertEquals("abc", viewModel.portInput.value)
            assertEquals("Port must be a number", viewModel.portError.value)
        }

    @Test
    fun `updatePort with empty input sets error`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("")
            advanceUntilIdle()

            assertEquals("", viewModel.portInput.value)
            assertEquals("Port is required", viewModel.portError.value)
        }

    @Test
    fun `updatePort with min valid port succeeds`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("1")
            advanceUntilIdle()

            assertNull(viewModel.portError.value)
            coVerify { settingsRepository.updatePort(1) }
        }

    @Test
    fun `updatePort with max valid port succeeds`() =
        runTest {
            advanceUntilIdle()

            viewModel.updatePort("65535")
            advanceUntilIdle()

            assertNull(viewModel.portError.value)
            coVerify { settingsRepository.updatePort(65535) }
        }

    @Test
    fun `updateBindingAddress calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateBindingAddress(BindingAddress.NETWORK)
            advanceUntilIdle()

            coVerify { settingsRepository.updateBindingAddress(BindingAddress.NETWORK) }
        }

    @Test
    fun `updateAutoStartOnBoot calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateAutoStartOnBoot(true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateAutoStartOnBoot(true) }
        }

    @Test
    fun `updateHideFromRecents calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateHideFromRecents(true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateHideFromRecents(true) }
        }

    @Test
    fun `updateBlockLocalNetworkDownloads calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateBlockLocalNetworkDownloads(true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateBlockLocalNetworkDownloads(true) }
        }

    @Test
    fun `updateHttpsEnabled calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateHttpsEnabled(true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateHttpsEnabled(true) }
        }

    @Test
    fun `updateCertificateSource calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCertificateSource(CertificateSource.CUSTOM)
            advanceUntilIdle()

            coVerify { settingsRepository.updateCertificateSource(CertificateSource.CUSTOM) }
        }

    @Test
    fun `updateCertificateHostname with valid hostname clears error and saves`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCertificateHostname("my-device.local")
            advanceUntilIdle()

            assertEquals("my-device.local", viewModel.hostnameInput.value)
            assertNull(viewModel.hostnameError.value)
            coVerify { settingsRepository.updateCertificateHostname("my-device.local") }
        }

    @Test
    fun `updateCertificateHostname with empty hostname sets error`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateCertificateHostname("") } returns
                Result.failure(IllegalArgumentException("Certificate hostname must not be empty"))
            viewModel.updateCertificateHostname("")
            advanceUntilIdle()

            assertEquals("", viewModel.hostnameInput.value)
            assertEquals("Certificate hostname must not be empty", viewModel.hostnameError.value)
            coVerify(exactly = 0) { settingsRepository.updateCertificateHostname("") }
        }

    @Test
    fun `updateCertificateHostname with invalid format sets error`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateCertificateHostname("-invalid") } returns
                Result.failure(
                    IllegalArgumentException(
                        "Certificate hostname contains invalid characters. " +
                            "Use only letters, digits, hyphens, and dots.",
                    ),
                )
            viewModel.updateCertificateHostname("-invalid")
            advanceUntilIdle()

            assertEquals("-invalid", viewModel.hostnameInput.value)
            assertEquals(
                "Certificate hostname contains invalid characters. " +
                    "Use only letters, digits, hyphens, and dots.",
                viewModel.hostnameError.value,
            )
            coVerify(exactly = 0) { settingsRepository.updateCertificateHostname("-invalid") }
        }

    @Test
    fun `updateCertificateHostname with single label hostname succeeds`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCertificateHostname("mydevice")
            advanceUntilIdle()

            assertNull(viewModel.hostnameError.value)
            coVerify { settingsRepository.updateCertificateHostname("mydevice") }
        }

    @Test
    fun `updateTunnelEnabled calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateTunnelEnabled(true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateTunnelEnabled(true) }
        }

    @Test
    fun `updateTunnelProvider calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateTunnelProvider(TunnelProviderType.NGROK)
            advanceUntilIdle()

            coVerify { settingsRepository.updateTunnelProvider(TunnelProviderType.NGROK) }
        }

    @Test
    fun `updateNgrokAuthtoken calls repository and updates input state`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateNgrokAuthtoken("test-token")
            advanceUntilIdle()

            assertEquals("test-token", viewModel.ngrokAuthtokenInput.value)
            coVerify { settingsRepository.updateNgrokAuthtoken("test-token") }
        }

    @Test
    fun `updateNgrokDomain calls repository and updates input state`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateNgrokDomain("my-domain.ngrok.app")
            advanceUntilIdle()

            assertEquals("my-domain.ngrok.app", viewModel.ngrokDomainInput.value)
            coVerify { settingsRepository.updateNgrokDomain("my-domain.ngrok.app") }
        }

    @Test
    fun `tunnelStatus reflects TunnelManager status`() =
        runTest {
            advanceUntilIdle()

            tunnelStatusFlow.value =
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            advanceUntilIdle()

            assertEquals(
                TunnelStatus.Connected(
                    endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                    providerType = TunnelProviderType.CLOUDFLARE,
                ),
                viewModel.tunnelStatus.value,
            )
        }

    @Test
    fun `serverConfig collection sets ngrok input fields`() =
        runTest {
            // Set ngrok fields BEFORE creating ViewModel so initial load picks them up
            configFlow.value =
                configFlow.value.copy(
                    ngrokAuthtoken = "my-authtoken",
                    ngrokDomain = "my.ngrok.app",
                )
            viewModel =
                MainViewModel(
                    settingsRepository,
                    tunnelManager,
                    storageLocationProvider,
                    batteryOptimizationManager,
                    testDispatcher,
                    approvalCoordinator,
                    listenerRules,
                )
            advanceUntilIdle()

            assertEquals("my-authtoken", viewModel.ngrokAuthtokenInput.value)
            assertEquals("my.ngrok.app", viewModel.ngrokDomainInput.value)
        }

    @Test
    fun `updateCloudflareTunnelMode calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCloudflareTunnelMode(CloudflareTunnelMode.TOKEN)
            advanceUntilIdle()

            coVerify { settingsRepository.updateCloudflareTunnelMode(CloudflareTunnelMode.TOKEN) }
        }

    @Test
    fun `updateCloudflareTunnelToken calls repository and updates input state`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCloudflareTunnelToken("cf-token")
            advanceUntilIdle()

            assertEquals("cf-token", viewModel.cloudflareTokenInput.value)
            coVerify { settingsRepository.updateCloudflareTunnelToken("cf-token") }
        }

    @Test
    fun `updateCloudflareTunnelExtraArgs calls repository and updates input state`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCloudflareTunnelExtraArgs("--edge region1.v2.argotunnel.com:7844")
            advanceUntilIdle()

            assertEquals("--edge region1.v2.argotunnel.com:7844", viewModel.cloudflareExtraArgsInput.value)
            coVerify { settingsRepository.updateCloudflareTunnelExtraArgs("--edge region1.v2.argotunnel.com:7844") }
        }

    @Test
    fun `serverConfig collection sets cloudflare token input`() =
        runTest {
            configFlow.value = configFlow.value.copy(cloudflareTunnelToken = "seeded-token")
            viewModel =
                MainViewModel(
                    settingsRepository,
                    tunnelManager,
                    storageLocationProvider,
                    batteryOptimizationManager,
                    testDispatcher,
                    approvalCoordinator,
                    listenerRules,
                )
            advanceUntilIdle()

            assertEquals("seeded-token", viewModel.cloudflareTokenInput.value)
        }

    // ─── Storage Location Tests ─────────────────────────────────────────

    @Test
    fun `refreshStorageLocations updates storageLocations state`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "auth/root1",
                        name = "Downloads",
                        path = "/",
                        description = "Test location",
                        treeUri = "content://auth/tree/root1",
                        availableBytes = null,
                        allowWrite = true,
                        allowDelete = true,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            assertEquals(1, viewModel.storageLocations.value.size)
            assertEquals("auth/root1", viewModel.storageLocations.value[0].id)
        }

    @Test
    fun `addLocation calls provider and refreshes`() =
        runTest {
            advanceUntilIdle()

            val mockUri = mockk<Uri>()
            coEvery { storageLocationProvider.addLocation(mockUri, "desc") } just Runs
            coEvery { storageLocationProvider.getAllLocations() } returns emptyList()

            viewModel.addLocation(mockUri, "desc")
            advanceUntilIdle()

            coVerify { storageLocationProvider.addLocation(mockUri, "desc") }
        }

    @Test
    fun `addLocation emits storageError on failure`() =
        runTest {
            advanceUntilIdle()

            val mockUri = mockk<Uri>()
            coEvery {
                storageLocationProvider.addLocation(mockUri, "desc")
            } throws RuntimeException("Test error")

            val errors = mutableListOf<String>()
            val job =
                launch {
                    viewModel.storageError.collect { errors.add(it) }
                }

            viewModel.addLocation(mockUri, "desc")
            advanceUntilIdle()

            assertTrue(errors.any { it.contains("Test error") })
            job.cancel()
        }

    @Test
    fun `removeLocation calls provider and refreshes`() =
        runTest {
            advanceUntilIdle()

            coEvery { storageLocationProvider.removeLocation("loc1") } just Runs
            coEvery { storageLocationProvider.getAllLocations() } returns emptyList()

            viewModel.removeLocation("loc1")
            advanceUntilIdle()

            coVerify { storageLocationProvider.removeLocation("loc1") }
        }

    @Test
    fun `removeLocation emits storageError on failure`() =
        runTest {
            advanceUntilIdle()

            coEvery {
                storageLocationProvider.removeLocation("loc1")
            } throws RuntimeException("Remove error")

            val errors = mutableListOf<String>()
            val job =
                launch {
                    viewModel.storageError.collect { errors.add(it) }
                }

            viewModel.removeLocation("loc1")
            advanceUntilIdle()

            assertTrue(errors.any { it.contains("Remove error") })
            job.cancel()
        }

    @Test
    fun `updateLocationDescription calls provider and refreshes`() =
        runTest {
            advanceUntilIdle()

            coEvery {
                storageLocationProvider.updateLocationDescription("loc1", "new desc")
            } just Runs
            coEvery { storageLocationProvider.getAllLocations() } returns emptyList()

            viewModel.updateLocationDescription("loc1", "new desc")
            advanceUntilIdle()

            coVerify {
                storageLocationProvider.updateLocationDescription("loc1", "new desc")
            }
        }

    @Test
    fun `updateLocationDescription emits storageError on failure`() =
        runTest {
            advanceUntilIdle()

            coEvery {
                storageLocationProvider.updateLocationDescription("loc1", "desc")
            } throws RuntimeException("Update error")

            val errors = mutableListOf<String>()
            val job =
                launch {
                    viewModel.storageError.collect { errors.add(it) }
                }

            viewModel.updateLocationDescription("loc1", "desc")
            advanceUntilIdle()

            assertTrue(errors.any { it.contains("Update error") })
            job.cancel()
        }

    @Test
    fun `isDuplicateTreeUri delegates to provider`() =
        runTest {
            advanceUntilIdle()

            val mockUri = mockk<Uri>()
            coEvery { storageLocationProvider.isDuplicateTreeUri(mockUri) } returns true

            val result = viewModel.isDuplicateTreeUri(mockUri)

            assertTrue(result)
            coVerify { storageLocationProvider.isDuplicateTreeUri(mockUri) }
        }

    // ─── Allow Write / Allow Delete Tests ─────────────────────────────

    @Test
    fun `updateLocationAllowWrite calls provider and updates state in-place`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://auth/tree/root1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowWrite("loc1", true) } just Runs

            viewModel.updateLocationAllowWrite("loc1", true)
            advanceUntilIdle()

            coVerify { storageLocationProvider.updateLocationAllowWrite("loc1", true) }
            assertEquals(true, viewModel.storageLocations.value[0].allowWrite)
            assertEquals(false, viewModel.storageLocations.value[0].allowDelete)
            coVerify(exactly = 0) { storageLocationProvider.getAllLocations() }
        }

    @Test
    fun `updateLocationAllowWrite emits error and refreshes on failure`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://auth/tree/root1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery {
                storageLocationProvider.updateLocationAllowWrite("loc1", true)
            } throws RuntimeException("test error")
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            val errors = mutableListOf<String>()
            val job =
                launch {
                    viewModel.storageError.collect { errors.add(it) }
                }

            viewModel.updateLocationAllowWrite("loc1", true)
            advanceUntilIdle()

            assertTrue(errors.any { it.contains("Failed to update write permission") })
            coVerify(exactly = 1) { storageLocationProvider.getAllLocations() }
            job.cancel()
        }

    @Test
    fun `updateLocationAllowDelete calls provider and updates state in-place`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://auth/tree/root1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowDelete("loc1", true) } just Runs

            viewModel.updateLocationAllowDelete("loc1", true)
            advanceUntilIdle()

            coVerify { storageLocationProvider.updateLocationAllowDelete("loc1", true) }
            assertEquals(true, viewModel.storageLocations.value[0].allowDelete)
            assertEquals(false, viewModel.storageLocations.value[0].allowWrite)
            coVerify(exactly = 0) { storageLocationProvider.getAllLocations() }
        }

    @Test
    fun `updateLocationAllowDelete emits error and refreshes on failure`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://auth/tree/root1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery {
                storageLocationProvider.updateLocationAllowDelete("loc1", true)
            } throws RuntimeException("test error")
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            val errors = mutableListOf<String>()
            val job =
                launch {
                    viewModel.storageError.collect { errors.add(it) }
                }

            viewModel.updateLocationAllowDelete("loc1", true)
            advanceUntilIdle()

            assertTrue(errors.any { it.contains("Failed to update delete permission") })
            coVerify(exactly = 1) { storageLocationProvider.getAllLocations() }
            job.cancel()
        }

    @Test
    fun `updateLocationAllowWrite with non-existent locationId is a no-op`() =
        runTest {
            // Arrange - populate state
            coEvery { storageLocationProvider.getAllLocations() } returns
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Test",
                        path = "/",
                        description = "",
                        treeUri = "content://test/tree/loc1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            viewModel.refreshStorageLocations()
            advanceUntilIdle()
            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowWrite("nonexistent", true) } just Runs

            // Act
            viewModel.updateLocationAllowWrite("nonexistent", true)
            advanceUntilIdle()

            // Assert - state unchanged
            assertEquals(1, viewModel.storageLocations.value.size)
            assertEquals(false, viewModel.storageLocations.value[0].allowWrite)
            assertEquals(false, viewModel.storageLocations.value[0].allowDelete)
        }

    @Test
    fun `updateLocationAllowDelete with non-existent locationId is a no-op`() =
        runTest {
            // Arrange - populate state
            coEvery { storageLocationProvider.getAllLocations() } returns
                listOf(
                    StorageLocation(
                        id = "loc1",
                        name = "Test",
                        path = "/",
                        description = "",
                        treeUri = "content://test/tree/loc1",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            viewModel.refreshStorageLocations()
            advanceUntilIdle()
            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowDelete("nonexistent", true) } just Runs

            // Act
            viewModel.updateLocationAllowDelete("nonexistent", true)
            advanceUntilIdle()

            // Assert - state unchanged
            assertEquals(1, viewModel.storageLocations.value.size)
            assertEquals(false, viewModel.storageLocations.value[0].allowWrite)
            assertEquals(false, viewModel.storageLocations.value[0].allowDelete)
        }

    @Test
    fun `updateFileSizeLimit validates and persists valid value`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateFileSizeLimit(100) } returns Result.success(100)

            viewModel.updateFileSizeLimit("100")
            advanceUntilIdle()

            assertEquals("100", viewModel.fileSizeLimitInput.value)
            assertNull(viewModel.fileSizeLimitError.value)
            coVerify { settingsRepository.updateFileSizeLimit(100) }
        }

    @Test
    fun `updateFileSizeLimit sets error for invalid value`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateFileSizeLimit(0) } returns
                Result.failure(IllegalArgumentException("File size limit must be between 1 and 500 MB"))

            viewModel.updateFileSizeLimit("0")
            advanceUntilIdle()

            assertEquals("0", viewModel.fileSizeLimitInput.value)
            assertEquals("File size limit must be between 1 and 500 MB", viewModel.fileSizeLimitError.value)
            coVerify(exactly = 0) { settingsRepository.updateFileSizeLimit(any()) }
        }

    @Test
    fun `updateFileSizeLimit sets error for blank input`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateFileSizeLimit("")
            advanceUntilIdle()

            assertEquals("", viewModel.fileSizeLimitInput.value)
            assertEquals("File size limit is required", viewModel.fileSizeLimitError.value)
        }

    @Test
    fun `updateFileSizeLimit sets error for non-numeric input`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateFileSizeLimit("abc")
            advanceUntilIdle()

            assertEquals("abc", viewModel.fileSizeLimitInput.value)
            assertEquals("Must be a number", viewModel.fileSizeLimitError.value)
        }

    @Test
    fun `updateDownloadTimeout validates and persists valid value`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateDownloadTimeout(120) } returns Result.success(120)

            viewModel.updateDownloadTimeout("120")
            advanceUntilIdle()

            assertEquals("120", viewModel.downloadTimeoutInput.value)
            assertNull(viewModel.downloadTimeoutError.value)
            coVerify { settingsRepository.updateDownloadTimeout(120) }
        }

    @Test
    fun `updateDownloadTimeout sets error for invalid value`() =
        runTest {
            advanceUntilIdle()

            every { settingsRepository.validateDownloadTimeout(5) } returns
                Result.failure(IllegalArgumentException("Download timeout must be between 10 and 300 seconds"))

            viewModel.updateDownloadTimeout("5")
            advanceUntilIdle()

            assertEquals("5", viewModel.downloadTimeoutInput.value)
            assertEquals(
                "Download timeout must be between 10 and 300 seconds",
                viewModel.downloadTimeoutError.value,
            )
            coVerify(exactly = 0) { settingsRepository.updateDownloadTimeout(any()) }
        }

    @Test
    fun `updateDownloadTimeout sets error for blank input`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateDownloadTimeout("")
            advanceUntilIdle()

            assertEquals("", viewModel.downloadTimeoutInput.value)
            assertEquals("Download timeout is required", viewModel.downloadTimeoutError.value)
        }

    // ─── Device Slug Tests ──────────────────────────────────────────────

    @Test
    fun `updateDeviceSlug with valid slug clears error and saves`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("pixel7") } returns Result.success("pixel7")

            viewModel.updateDeviceSlug("pixel7")
            advanceUntilIdle()

            assertEquals("pixel7", viewModel.deviceSlugInput.value)
            assertNull(viewModel.deviceSlugError.value)
            coVerify { settingsRepository.updateDeviceSlug("pixel7") }
        }

    @Test
    fun `updateDeviceSlug with empty slug clears error and saves`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("") } returns Result.success("")

            viewModel.updateDeviceSlug("")
            advanceUntilIdle()

            assertEquals("", viewModel.deviceSlugInput.value)
            assertNull(viewModel.deviceSlugError.value)
            coVerify { settingsRepository.updateDeviceSlug("") }
        }

    @Test
    fun `updateDeviceSlug with invalid slug sets error and does not save`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("work-phone") } returns
                Result.failure(
                    IllegalArgumentException(
                        "Device slug can only contain letters, digits, and underscores",
                    ),
                )

            viewModel.updateDeviceSlug("work-phone")
            advanceUntilIdle()

            assertEquals("work-phone", viewModel.deviceSlugInput.value)
            assertEquals(
                "Device slug can only contain letters, digits, and underscores",
                viewModel.deviceSlugError.value,
            )
            coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
        }

    @Test
    fun `updateDeviceSlug with too long slug sets error and does not save`() =
        runTest {
            val longSlug = "a".repeat(21)
            every { settingsRepository.validateDeviceSlug(longSlug) } returns
                Result.failure(IllegalArgumentException("Device slug must be at most 20 characters"))

            viewModel.updateDeviceSlug(longSlug)
            advanceUntilIdle()

            assertEquals(longSlug, viewModel.deviceSlugInput.value)
            assertEquals("Device slug must be at most 20 characters", viewModel.deviceSlugError.value)
            coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
        }

    @Test
    fun `initial state loads deviceSlug from repository`() =
        runTest {
            // Set deviceSlug BEFORE creating ViewModel so initial load picks it up
            configFlow.value = configFlow.value.copy(deviceSlug = "test_device")
            viewModel =
                MainViewModel(
                    settingsRepository,
                    tunnelManager,
                    storageLocationProvider,
                    batteryOptimizationManager,
                    testDispatcher,
                    approvalCoordinator,
                    listenerRules,
                )
            advanceUntilIdle()

            assertEquals("test_device", viewModel.deviceSlugInput.value)
        }

    // ─── Notification Listener Permission Tests ─────────────────────────

    @Test
    fun `refreshPermissionStatus updates isNotificationListenerEnabled when service is enabled`() =
        runTest {
            advanceUntilIdle()

            val context = mockk<Context>()
            mockkObject(PermissionUtils)
            try {
                every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns false
                every { PermissionUtils.isNotificationPermissionGranted(context) } returns false
                every { PermissionUtils.isCameraPermissionGranted(context) } returns false
                every { PermissionUtils.isMicrophonePermissionGranted(context) } returns false
                every { PermissionUtils.isLocationPermissionGranted(context) } returns false
                every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns true
                coEvery { storageLocationProvider.getAllLocations() } returns emptyList()
                mockkStatic(androidx.core.content.ContextCompat::class)
                every {
                    androidx.core.content.ContextCompat
                        .checkSelfPermission(context, any())
                } returns android.content.pm.PackageManager.PERMISSION_DENIED

                viewModel.refreshPermissionStatus(context)
                advanceUntilIdle()

                assertEquals(true, viewModel.isNotificationListenerEnabled.value)
            } finally {
                unmockkStatic(androidx.core.content.ContextCompat::class)
                unmockkObject(PermissionUtils)
            }
        }

    @Test
    fun `refreshPermissionStatus updates isNotificationListenerEnabled when service is not enabled`() =
        runTest {
            advanceUntilIdle()

            val context = mockk<Context>()
            mockkObject(PermissionUtils)
            try {
                every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns false
                every { PermissionUtils.isNotificationPermissionGranted(context) } returns false
                every { PermissionUtils.isCameraPermissionGranted(context) } returns false
                every { PermissionUtils.isMicrophonePermissionGranted(context) } returns false
                every { PermissionUtils.isLocationPermissionGranted(context) } returns false
                every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns false
                coEvery { storageLocationProvider.getAllLocations() } returns emptyList()
                mockkStatic(androidx.core.content.ContextCompat::class)
                every {
                    androidx.core.content.ContextCompat
                        .checkSelfPermission(context, any())
                } returns android.content.pm.PackageManager.PERMISSION_DENIED

                viewModel.refreshPermissionStatus(context)
                advanceUntilIdle()

                assertEquals(false, viewModel.isNotificationListenerEnabled.value)
            } finally {
                unmockkStatic(androidx.core.content.ContextCompat::class)
                unmockkObject(PermissionUtils)
            }
        }

    // ─── Battery Optimization Tests ─────────────────────────────────────

    @Test
    fun `refreshPermissionStatus reflects not-exempt`() =
        runTest {
            advanceUntilIdle()

            val context = mockk<Context>()
            mockkObject(PermissionUtils)
            try {
                every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns false
                every { PermissionUtils.isNotificationPermissionGranted(context) } returns false
                every { PermissionUtils.isCameraPermissionGranted(context) } returns false
                every { PermissionUtils.isMicrophonePermissionGranted(context) } returns false
                every { PermissionUtils.isLocationPermissionGranted(context) } returns false
                every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns false
                coEvery { storageLocationProvider.getAllLocations() } returns emptyList()
                every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false

                viewModel.refreshPermissionStatus(context)
                advanceUntilIdle()

                assertEquals(false, viewModel.isBatteryOptimizationIgnored.value)
            } finally {
                unmockkObject(PermissionUtils)
            }
        }

    @Test
    fun `refreshPermissionStatus reflects exempt after grant`() =
        runTest {
            advanceUntilIdle()

            val context = mockk<Context>()
            mockkObject(PermissionUtils)
            try {
                every { PermissionUtils.isAccessibilityServiceEnabled(context, any()) } returns false
                every { PermissionUtils.isNotificationPermissionGranted(context) } returns false
                every { PermissionUtils.isCameraPermissionGranted(context) } returns false
                every { PermissionUtils.isMicrophonePermissionGranted(context) } returns false
                every { PermissionUtils.isLocationPermissionGranted(context) } returns false
                every { PermissionUtils.isNotificationListenerEnabled(context, any()) } returns false
                coEvery { storageLocationProvider.getAllLocations() } returns emptyList()

                every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns false
                viewModel.refreshPermissionStatus(context)
                advanceUntilIdle()
                assertEquals(false, viewModel.isBatteryOptimizationIgnored.value)

                every { batteryOptimizationManager.isIgnoringBatteryOptimizations() } returns true
                viewModel.refreshPermissionStatus(context)
                advanceUntilIdle()
                assertEquals(true, viewModel.isBatteryOptimizationIgnored.value)
            } finally {
                unmockkObject(PermissionUtils)
            }
        }

    @Test
    fun `requestBatteryOptimizationExemption delegates to manager`() =
        runTest {
            advanceUntilIdle()

            viewModel.requestBatteryOptimizationExemption()

            verify { batteryOptimizationManager.requestExemption() }
        }

    // --- Tool Permissions Tests ---

    @Test
    fun `updateLocationAllowWrite updates builtin location in state`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "builtin:downloads",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://media/external/downloads",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) } just Runs

            viewModel.updateLocationAllowWrite("builtin:downloads", true)
            advanceUntilIdle()

            coVerify { storageLocationProvider.updateLocationAllowWrite("builtin:downloads", true) }
            assertEquals(true, viewModel.storageLocations.value[0].allowWrite)
            assertEquals(false, viewModel.storageLocations.value[0].allowDelete)
            coVerify(exactly = 0) { storageLocationProvider.getAllLocations() }
        }

    @Test
    fun `updateLocationAllowDelete updates builtin location in state`() =
        runTest {
            advanceUntilIdle()

            val locations =
                listOf(
                    StorageLocation(
                        id = "builtin:downloads",
                        name = "Downloads",
                        path = "/",
                        description = "",
                        treeUri = "content://media/external/downloads",
                        availableBytes = null,
                        allowWrite = false,
                        allowDelete = false,
                    ),
                )
            coEvery { storageLocationProvider.getAllLocations() } returns locations

            viewModel.refreshStorageLocations()
            advanceUntilIdle()

            clearMocks(storageLocationProvider, answers = false, recordedCalls = true)
            coEvery { storageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) } just Runs

            viewModel.updateLocationAllowDelete("builtin:downloads", true)
            advanceUntilIdle()

            coVerify { storageLocationProvider.updateLocationAllowDelete("builtin:downloads", true) }
            assertEquals(true, viewModel.storageLocations.value[0].allowDelete)
            assertEquals(false, viewModel.storageLocations.value[0].allowWrite)
            coVerify(exactly = 0) { storageLocationProvider.getAllLocations() }
        }

    @Test
    fun `updateToolEnabled false delegates to repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateToolEnabled("tap", false)
            advanceUntilIdle()

            coVerify { settingsRepository.updateToolEnabled("tap", false) }
        }

    @Test
    fun `updateToolEnabled true delegates to repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateToolEnabled("tap", true)
            advanceUntilIdle()

            coVerify { settingsRepository.updateToolEnabled("tap", true) }
        }

    @Test
    fun `updateParamEnabled delegates to repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateParamEnabled("get_screen_state", "include_screenshot", false)
            advanceUntilIdle()

            coVerify { settingsRepository.updateParamEnabled("get_screen_state", "include_screenshot", false) }
        }

    @Test
    fun `toolPermissionsConfig emits updated config`() =
        runTest {
            advanceUntilIdle()

            val values = mutableListOf<ToolPermissionsConfig>()
            val job = launch { viewModel.toolPermissionsConfig.collect { values.add(it) } }
            advanceUntilIdle()

            val updatedPerms = ToolPermissionsConfig(disabledTools = setOf("tap"))
            configFlow.value = configFlow.value.copy(toolPermissionsConfig = updatedPerms)
            advanceUntilIdle()

            assertEquals(updatedPerms, values.last())
            job.cancel()
        }

    @Test
    fun `hideFromRecents emits updated config`() =
        runTest {
            advanceUntilIdle()

            val values = mutableListOf<Boolean>()
            val job = launch { viewModel.hideFromRecents.collect { values.add(it) } }
            advanceUntilIdle()

            configFlow.value = configFlow.value.copy(hideFromRecents = true)
            advanceUntilIdle()

            assertEquals(true, values.last())
            job.cancel()
        }
}
