package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
import com.danielealbano.androidremotecontrolmcp.services.tunnel.TunnelManager
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
    private lateinit var configFlow: MutableStateFlow<ServerConfig>
    private lateinit var tunnelStatusFlow: MutableStateFlow<TunnelStatus>
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

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

        tunnelManager = mockk(relaxed = true)
        every { tunnelManager.tunnelStatus } returns tunnelStatusFlow

        storageLocationProvider = mockk(relaxed = true)

        viewModel = MainViewModel(settingsRepository, tunnelManager, storageLocationProvider, testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
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
    fun `generateNewBearerToken calls repository`() =
        runTest {
            advanceUntilIdle()

            viewModel.generateNewBearerToken()
            advanceUntilIdle()

            coVerify { settingsRepository.generateNewBearerToken() }
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

            viewModel.updateCertificateHostname("")
            advanceUntilIdle()

            assertEquals("", viewModel.hostnameInput.value)
            assertEquals("Hostname is required", viewModel.hostnameError.value)
            coVerify(exactly = 0) { settingsRepository.updateCertificateHostname("") }
        }

    @Test
    fun `updateCertificateHostname with invalid format sets error`() =
        runTest {
            advanceUntilIdle()

            viewModel.updateCertificateHostname("-invalid")
            advanceUntilIdle()

            assertEquals("-invalid", viewModel.hostnameInput.value)
            assertEquals("Invalid hostname format", viewModel.hostnameError.value)
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
    fun `addServerLogEntry adds entry to logs`() =
        runTest {
            advanceUntilIdle()

            val entry =
                ServerLogEntry(
                    timestamp = 1000L,
                    type = ServerLogEntry.Type.TOOL_CALL,
                    message = "screen_tap",
                    toolName = "screen_tap",
                    params = "x=100, y=200",
                    durationMs = 42L,
                )
            viewModel.addServerLogEntry(entry)

            assertEquals(1, viewModel.serverLogs.value.size)
            assertEquals(entry, viewModel.serverLogs.value[0])
        }

    @Test
    fun `addServerLogEntry trims list to max 100 entries`() =
        runTest {
            advanceUntilIdle()

            repeat(105) { i ->
                viewModel.addServerLogEntry(
                    ServerLogEntry(
                        timestamp = i.toLong(),
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = "tool_$i",
                        toolName = "tool_$i",
                        params = "",
                        durationMs = i.toLong(),
                    ),
                )
            }

            assertEquals(100, viewModel.serverLogs.value.size)
            assertEquals("tool_5", viewModel.serverLogs.value[0].toolName)
            assertEquals("tool_104", viewModel.serverLogs.value[99].toolName)
        }

    @Test
    fun `initial server logs list is empty`() =
        runTest {
            assertEquals(emptyList<ServerLogEntry>(), viewModel.serverLogs.value)
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
                    url = "https://test.trycloudflare.com",
                    providerType = TunnelProviderType.CLOUDFLARE,
                )
            advanceUntilIdle()

            assertEquals(
                TunnelStatus.Connected(
                    url = "https://test.trycloudflare.com",
                    providerType = TunnelProviderType.CLOUDFLARE,
                ),
                viewModel.tunnelStatus.value,
            )
        }

    @Test
    fun `serverConfig collection sets ngrok input fields`() =
        runTest {
            advanceUntilIdle()

            configFlow.value =
                configFlow.value.copy(
                    ngrokAuthtoken = "my-authtoken",
                    ngrokDomain = "my.ngrok.app",
                )
            advanceUntilIdle()

            assertEquals("my-authtoken", viewModel.ngrokAuthtokenInput.value)
            assertEquals("my.ngrok.app", viewModel.ngrokDomainInput.value)
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
    fun `updateLocationAllowWrite calls provider and optimistically updates state`() =
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
    fun `updateLocationAllowDelete calls provider and optimistically updates state`() =
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
            configFlow.value = configFlow.value.copy(deviceSlug = "test_device")
            advanceUntilIdle()

            assertEquals("test_device", viewModel.deviceSlugInput.value)
        }
}
