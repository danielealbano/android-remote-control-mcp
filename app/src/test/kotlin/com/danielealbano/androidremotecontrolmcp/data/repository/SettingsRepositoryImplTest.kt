package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsRepositoryImpl")
class SettingsRepositoryImplTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepositoryImpl

    private var testFileCounter = 0

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        testFileCounter++
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "test_settings_$testFileCounter.preferences_pb") },
            )
        repository = SettingsRepositoryImpl(dataStore)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Nested
    @DisplayName("getServerConfig")
    inner class GetServerConfig {
        @Test
        fun `returns default values when no settings stored`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertEquals(ServerConfig.DEFAULT_PORT, config.port)
                assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
                assertFalse(config.autoStartOnBoot)
                assertFalse(config.httpsEnabled)
                assertEquals(CertificateSource.AUTO_GENERATED, config.certificateSource)
                assertEquals(ServerConfig.DEFAULT_CERTIFICATE_HOSTNAME, config.certificateHostname)
                assertFalse(config.tunnelEnabled)
                assertEquals(TunnelProviderType.CLOUDFLARE, config.tunnelProvider)
                assertEquals("", config.ngrokAuthtoken)
                assertEquals("", config.ngrokDomain)
                assertEquals("", config.deviceSlug)
            }

        @Test
        fun `auto-generates bearer token when empty`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertTrue(config.bearerToken.isNotEmpty())
            }

        @Test
        fun `auto-generated bearer token is UUID format`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                val uuidPattern =
                    Regex(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                    )
                assertTrue(uuidPattern.matches(config.bearerToken))
            }

        @Test
        fun `auto-generated bearer token is persisted`() =
            testScope.runTest {
                val firstRead = repository.getServerConfig()
                val secondRead = repository.getServerConfig()

                assertEquals(firstRead.bearerToken, secondRead.bearerToken)
            }
    }

    @Nested
    @DisplayName("updatePort")
    inner class UpdatePort {
        @Test
        fun `updates port value`() =
            testScope.runTest {
                repository.updatePort(9090)
                val config = repository.getServerConfig()

                assertEquals(9090, config.port)
            }
    }

    @Nested
    @DisplayName("updateBindingAddress")
    inner class UpdateBindingAddress {
        @Test
        fun `updates binding address to NETWORK`() =
            testScope.runTest {
                repository.updateBindingAddress(BindingAddress.NETWORK)
                val config = repository.getServerConfig()

                assertEquals(BindingAddress.NETWORK, config.bindingAddress)
            }

        @Test
        fun `updates binding address back to LOCALHOST`() =
            testScope.runTest {
                repository.updateBindingAddress(BindingAddress.NETWORK)
                repository.updateBindingAddress(BindingAddress.LOCALHOST)
                val config = repository.getServerConfig()

                assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
            }
    }

    @Nested
    @DisplayName("updateBearerToken")
    inner class UpdateBearerToken {
        @Test
        fun `updates bearer token`() =
            testScope.runTest {
                repository.updateBearerToken("custom-token-123")
                val config = repository.getServerConfig()

                assertEquals("custom-token-123", config.bearerToken)
            }
    }

    @Nested
    @DisplayName("generateNewBearerToken")
    inner class GenerateNewBearerToken {
        @Test
        fun `generates new UUID token`() =
            testScope.runTest {
                val token = repository.generateNewBearerToken()

                val uuidPattern =
                    Regex(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                    )
                assertTrue(uuidPattern.matches(token))
            }

        @Test
        fun `persists the generated token`() =
            testScope.runTest {
                val token = repository.generateNewBearerToken()
                val config = repository.getServerConfig()

                assertEquals(token, config.bearerToken)
            }

        @Test
        fun `generates different token each time`() =
            testScope.runTest {
                val token1 = repository.generateNewBearerToken()
                val token2 = repository.generateNewBearerToken()

                assertNotEquals(token1, token2)
            }
    }

    @Nested
    @DisplayName("updateAutoStartOnBoot")
    inner class UpdateAutoStartOnBoot {
        @Test
        fun `enables auto start on boot`() =
            testScope.runTest {
                repository.updateAutoStartOnBoot(true)
                val config = repository.getServerConfig()

                assertTrue(config.autoStartOnBoot)
            }

        @Test
        fun `disables auto start on boot`() =
            testScope.runTest {
                repository.updateAutoStartOnBoot(true)
                repository.updateAutoStartOnBoot(false)
                val config = repository.getServerConfig()

                assertFalse(config.autoStartOnBoot)
            }
    }

    @Nested
    @DisplayName("updateHttpsEnabled")
    inner class UpdateHttpsEnabled {
        @Test
        fun `enables HTTPS`() =
            testScope.runTest {
                repository.updateHttpsEnabled(true)
                val config = repository.getServerConfig()

                assertTrue(config.httpsEnabled)
            }

        @Test
        fun `disables HTTPS`() =
            testScope.runTest {
                repository.updateHttpsEnabled(true)
                repository.updateHttpsEnabled(false)
                val config = repository.getServerConfig()

                assertFalse(config.httpsEnabled)
            }
    }

    @Nested
    @DisplayName("updateCertificateSource")
    inner class UpdateCertificateSource {
        @Test
        fun `updates certificate source to CUSTOM`() =
            testScope.runTest {
                repository.updateCertificateSource(CertificateSource.CUSTOM)
                val config = repository.getServerConfig()

                assertEquals(CertificateSource.CUSTOM, config.certificateSource)
            }
    }

    @Nested
    @DisplayName("updateCertificateHostname")
    inner class UpdateCertificateHostname {
        @Test
        fun `updates certificate hostname`() =
            testScope.runTest {
                repository.updateCertificateHostname("my-device.local")
                val config = repository.getServerConfig()

                assertEquals("my-device.local", config.certificateHostname)
            }
    }

    @Nested
    @DisplayName("validatePort")
    inner class ValidatePort {
        @Test
        fun `valid port returns success`() {
            assertTrue(repository.validatePort(8080).isSuccess)
        }

        @Test
        fun `port 1 is valid`() {
            assertTrue(repository.validatePort(1).isSuccess)
        }

        @Test
        fun `port 65535 is valid`() {
            assertTrue(repository.validatePort(65535).isSuccess)
        }

        @Test
        fun `port 0 is invalid`() {
            assertTrue(repository.validatePort(0).isFailure)
        }

        @Test
        fun `port 65536 is invalid`() {
            assertTrue(repository.validatePort(65536).isFailure)
        }

        @Test
        fun `negative port is invalid`() {
            assertTrue(repository.validatePort(-1).isFailure)
        }
    }

    @Nested
    @DisplayName("validateCertificateHostname")
    inner class ValidateCertificateHostname {
        @Test
        fun `valid hostname returns success`() {
            assertTrue(repository.validateCertificateHostname("android-mcp.local").isSuccess)
        }

        @Test
        fun `single label hostname is valid`() {
            assertTrue(repository.validateCertificateHostname("localhost").isSuccess)
        }

        @Test
        fun `empty hostname is invalid`() {
            assertTrue(repository.validateCertificateHostname("").isFailure)
        }

        @Test
        fun `blank hostname is invalid`() {
            assertTrue(repository.validateCertificateHostname("   ").isFailure)
        }

        @Test
        fun `hostname with spaces is invalid`() {
            assertTrue(repository.validateCertificateHostname("my host").isFailure)
        }

        @Test
        fun `hostname with underscore is invalid`() {
            assertTrue(repository.validateCertificateHostname("my_host.local").isFailure)
        }
    }

    @Nested
    @DisplayName("serverConfig Flow")
    inner class ServerConfigFlow {
        @Test
        fun `emits default config initially`() =
            testScope.runTest {
                repository.serverConfig.test {
                    val config = awaitItem()
                    assertEquals(ServerConfig.DEFAULT_PORT, config.port)
                    assertEquals(BindingAddress.LOCALHOST, config.bindingAddress)
                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `emits updated config after port change`() =
            testScope.runTest {
                repository.serverConfig.test {
                    awaitItem() // initial emission

                    repository.updatePort(9090)
                    val updated = awaitItem()
                    assertEquals(9090, updated.port)

                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `emits updated config after binding address change`() =
            testScope.runTest {
                repository.serverConfig.test {
                    awaitItem() // initial emission

                    repository.updateBindingAddress(BindingAddress.NETWORK)
                    val updated = awaitItem()
                    assertEquals(BindingAddress.NETWORK, updated.bindingAddress)

                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `Flow does not auto-generate bearer token when empty`() =
            testScope.runTest {
                // The Flow should NOT have side effects — it simply maps preferences.
                // Auto-generation only happens via getServerConfig().
                repository.serverConfig.test {
                    val config = awaitItem()
                    assertTrue(config.bearerToken.isEmpty())

                    cancelAndIgnoreRemainingEvents()
                }
            }

        @Test
        fun `Flow reflects token after getServerConfig auto-generates it`() =
            testScope.runTest {
                // getServerConfig() triggers auto-generation and persists it
                val generated = repository.getServerConfig()
                assertTrue(generated.bearerToken.isNotEmpty())

                // Flow should now emit the persisted token
                repository.serverConfig.test {
                    val config = awaitItem()
                    assertEquals(generated.bearerToken, config.bearerToken)

                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("updateTunnelEnabled")
    inner class UpdateTunnelEnabled {
        @Test
        fun `enables tunnel`() =
            testScope.runTest {
                repository.updateTunnelEnabled(true)
                val config = repository.getServerConfig()

                assertTrue(config.tunnelEnabled)
            }

        @Test
        fun `disables tunnel`() =
            testScope.runTest {
                repository.updateTunnelEnabled(true)
                repository.updateTunnelEnabled(false)
                val config = repository.getServerConfig()

                assertFalse(config.tunnelEnabled)
            }
    }

    @Nested
    @DisplayName("updateTunnelProvider")
    inner class UpdateTunnelProvider {
        @Test
        fun `updates tunnel provider to NGROK`() =
            testScope.runTest {
                repository.updateTunnelProvider(TunnelProviderType.NGROK)
                val config = repository.getServerConfig()

                assertEquals(TunnelProviderType.NGROK, config.tunnelProvider)
            }

        @Test
        fun `updates tunnel provider to CLOUDFLARE`() =
            testScope.runTest {
                repository.updateTunnelProvider(TunnelProviderType.NGROK)
                repository.updateTunnelProvider(TunnelProviderType.CLOUDFLARE)
                val config = repository.getServerConfig()

                assertEquals(TunnelProviderType.CLOUDFLARE, config.tunnelProvider)
            }
    }

    @Nested
    @DisplayName("updateNgrokAuthtoken")
    inner class UpdateNgrokAuthtoken {
        @Test
        fun `updates ngrok authtoken`() =
            testScope.runTest {
                repository.updateNgrokAuthtoken("test-authtoken-abc123")
                val config = repository.getServerConfig()

                assertEquals("test-authtoken-abc123", config.ngrokAuthtoken)
            }

        @Test
        fun `reads persisted ngrok authtoken`() =
            testScope.runTest {
                repository.updateNgrokAuthtoken("persisted-token")
                val config1 = repository.getServerConfig()
                val config2 = repository.getServerConfig()

                assertEquals(config1.ngrokAuthtoken, config2.ngrokAuthtoken)
            }
    }

    @Nested
    @DisplayName("updateNgrokDomain")
    inner class UpdateNgrokDomain {
        @Test
        fun `updates ngrok domain`() =
            testScope.runTest {
                repository.updateNgrokDomain("my-app.ngrok-free.app")
                val config = repository.getServerConfig()

                assertEquals("my-app.ngrok-free.app", config.ngrokDomain)
            }

        @Test
        fun `reads persisted ngrok domain`() =
            testScope.runTest {
                repository.updateNgrokDomain("test-domain.ngrok.io")
                val config1 = repository.getServerConfig()
                val config2 = repository.getServerConfig()

                assertEquals(config1.ngrokDomain, config2.ngrokDomain)
            }
    }

    @Nested
    @DisplayName("updateDeviceSlug")
    inner class UpdateDeviceSlug {
        @Test
        fun `persists device slug`() =
            testScope.runTest {
                repository.updateDeviceSlug("pixel7")
                val config = repository.getServerConfig()
                assertEquals("pixel7", config.deviceSlug)
            }

        @Test
        fun `persists empty device slug`() =
            testScope.runTest {
                repository.updateDeviceSlug("test_device")
                repository.updateDeviceSlug("")
                val config = repository.getServerConfig()
                assertEquals("", config.deviceSlug)
            }

        @Test
        fun `emits updated config via flow`() =
            testScope.runTest {
                repository.serverConfig.test {
                    val initial = awaitItem()
                    assertEquals("", initial.deviceSlug)

                    repository.updateDeviceSlug("my_phone")
                    val updated = awaitItem()
                    assertEquals("my_phone", updated.deviceSlug)

                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("validateDeviceSlug")
    inner class ValidateDeviceSlug {
        @Test
        fun `accepts empty slug`() {
            assertTrue(repository.validateDeviceSlug("").isSuccess)
        }

        @Test
        fun `accepts valid slug with letters and digits`() {
            assertTrue(repository.validateDeviceSlug("pixel7").isSuccess)
        }

        @Test
        fun `accepts valid slug with underscores`() {
            assertTrue(repository.validateDeviceSlug("work_phone_1").isSuccess)
        }

        @Test
        fun `accepts valid slug with uppercase letters`() {
            assertTrue(repository.validateDeviceSlug("MyPhone").isSuccess)
        }

        @Test
        fun `accepts slug with only underscores`() {
            assertTrue(repository.validateDeviceSlug("___").isSuccess)
        }

        @Test
        fun `accepts slug at max length`() {
            val slug = "a".repeat(ServerConfig.MAX_DEVICE_SLUG_LENGTH)
            assertTrue(repository.validateDeviceSlug(slug).isSuccess)
        }

        @Test
        fun `rejects slug exceeding max length`() {
            val slug = "a".repeat(ServerConfig.MAX_DEVICE_SLUG_LENGTH + 1)
            val result = repository.validateDeviceSlug(slug)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("at most") == true)
        }

        @Test
        fun `rejects slug with hyphens`() {
            val result = repository.validateDeviceSlug("work-phone")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("letters, digits, and underscores") == true)
        }

        @Test
        fun `rejects slug with spaces`() {
            val result = repository.validateDeviceSlug("my phone")
            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects slug with special characters`() {
            val result = repository.validateDeviceSlug("phone@1")
            assertTrue(result.isFailure)
        }
    }

    @Nested
    @DisplayName("Storage Location Methods")
    inner class StorageLocationMethods {
        private val locationsKey = stringPreferencesKey("authorized_storage_locations")

        @Nested
        @DisplayName("getStoredLocations")
        inner class GetStoredLocations {
            @Test
            fun `returns empty list when no data stored`() =
                testScope.runTest {
                    val result = repository.getStoredLocations()

                    assertTrue(result.isEmpty())
                }

            @Test
            fun `returns stored locations`() =
                testScope.runTest {
                    val json =
                        "[{\"id\":\"loc1\",\"name\":\"Downloads\"," +
                            "\"path\":\"/Downloads\",\"description\":\"My downloads\"," +
                            "\"treeUri\":\"content://com.android.externalstorage.documents" +
                            "/tree/primary%3ADownloads\"}]"
                    dataStore.edit { prefs -> prefs[locationsKey] = json }

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertEquals("Downloads", result[0].name)
                    assertEquals("/Downloads", result[0].path)
                    assertEquals("My downloads", result[0].description)
                    assertEquals(
                        "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
                        result[0].treeUri,
                    )
                    assertTrue(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }

            @Test
            fun `handles corrupt JSON gracefully and returns empty list`() =
                testScope.runTest {
                    dataStore.edit { prefs -> prefs[locationsKey] = "not-valid-json{{{" }

                    val result = repository.getStoredLocations()

                    assertTrue(result.isEmpty())
                }
        }

        @Nested
        @DisplayName("addStoredLocation")
        inner class AddStoredLocation {
            @Test
            fun `appends to existing list`() =
                testScope.runTest {
                    val existing =
                        "[{\"id\":\"loc1\",\"name\":\"Downloads\"," +
                            "\"path\":\"/\",\"description\":\"\"," +
                            "\"treeUri\":\"content://test/tree/1\"}]"
                    dataStore.edit { prefs -> prefs[locationsKey] = existing }

                    val newLocation =
                        SettingsRepository.StoredLocation(
                            id = "loc2",
                            name = "Documents",
                            path = "/Documents",
                            description = "Work docs",
                            treeUri = "content://test/tree/2",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(newLocation)

                    val result = repository.getStoredLocations()
                    assertEquals(2, result.size)
                    assertEquals("loc1", result[0].id)
                    assertTrue(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                    assertEquals("loc2", result[1].id)
                    assertEquals("Documents", result[1].name)
                    assertEquals("/Documents", result[1].path)
                    assertEquals("Work docs", result[1].description)
                    assertEquals("content://test/tree/2", result[1].treeUri)
                    assertTrue(result[1].allowWrite)
                    assertTrue(result[1].allowDelete)
                }

            @Test
            fun `works on empty list`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/Downloads",
                            description = "First location",
                            treeUri = "content://test/tree/1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertEquals("Downloads", result[0].name)
                    assertEquals("First location", result[0].description)
                    assertTrue(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }
        }

        @Nested
        @DisplayName("removeStoredLocation")
        inner class RemoveStoredLocation {
            @Test
            fun `removes matching entry`() =
                testScope.runTest {
                    val loc1 =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    val loc2 =
                        SettingsRepository.StoredLocation(
                            id = "loc2",
                            name = "Documents",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/2",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(loc1)
                    repository.addStoredLocation(loc2)

                    repository.removeStoredLocation("loc1")

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("loc2", result[0].id)
                }

            @Test
            fun `is no-op for non-existent ID`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.removeStoredLocation("non-existent-id")

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                }
        }

        @Nested
        @DisplayName("updateLocationDescription")
        inner class UpdateLocationDescription {
            @Test
            fun `updates matching entry`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "Old description",
                            treeUri = "content://test/tree/1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationDescription("loc1", "New description")

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("New description", result[0].description)
                    assertEquals("loc1", result[0].id)
                    assertEquals("Downloads", result[0].name)
                    assertEquals("content://test/tree/1", result[0].treeUri)
                }

            @Test
            fun `is no-op for non-existent ID`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "Original",
                            treeUri = "content://test/tree/1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationDescription("non-existent-id", "New description")

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("Original", result[0].description)
                }
        }

        @Nested
        @DisplayName("Migration")
        inner class Migration {
            @Test
            fun `getStoredLocations migrates old JSON object format to new array format`() =
                testScope.runTest {
                    val oldFormatJson =
                        "{\"com.android.externalstorage.documents/tree/primary\":" +
                            "\"content://com.android.externalstorage.documents" +
                            "/tree/primary%3A\"}"
                    dataStore.edit { prefs -> prefs[locationsKey] = oldFormatJson }

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals(
                        "com.android.externalstorage.documents/tree/primary",
                        result[0].id,
                    )
                    assertEquals(
                        "content://com.android.externalstorage.documents/tree/primary%3A",
                        result[0].treeUri,
                    )
                    assertTrue(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }

            @Test
            fun `getStoredLocations preserves data during migration`() =
                testScope.runTest {
                    val oldFormatJson =
                        "{\"com.test/primary\":" +
                            "\"content://com.test/tree/primary%3A\"," +
                            "\"com.test/secondary\":" +
                            "\"content://com.test/tree/secondary%3A\"}"
                    dataStore.edit { prefs -> prefs[locationsKey] = oldFormatJson }

                    val result = repository.getStoredLocations()

                    assertEquals(2, result.size)

                    val loc1 = result.find { it.id == "com.test/primary" }
                    val loc2 = result.find { it.id == "com.test/secondary" }

                    assertTrue(loc1 != null)
                    assertEquals("content://com.test/tree/primary%3A", loc1!!.treeUri)
                    assertEquals("primary", loc1.name)
                    assertEquals("/", loc1.path)
                    assertEquals("", loc1.description)
                    assertTrue(loc1.allowWrite)
                    assertTrue(loc1.allowDelete)

                    assertTrue(loc2 != null)
                    assertEquals("content://com.test/tree/secondary%3A", loc2!!.treeUri)
                    assertEquals("secondary", loc2.name)
                    assertEquals("/", loc2.path)
                    assertEquals("", loc2.description)
                    assertTrue(loc2.allowWrite)
                    assertTrue(loc2.allowDelete)
                }

            @Test
            fun `getStoredLocations returns empty list for completely invalid JSON`() =
                testScope.runTest {
                    dataStore.edit { prefs -> prefs[locationsKey] = "<<<completely-invalid>>>" }

                    val result = repository.getStoredLocations()

                    assertTrue(result.isEmpty())
                }

            @Test
            @Suppress("MaxLineLength")
            fun `migration from stored location without permission fields defaults to allowWrite=true and allowDelete=true`() =
                testScope.runTest {
                    val json =
                        "[{\"id\":\"loc1\",\"name\":\"Downloads\"," +
                            "\"path\":\"/Downloads\",\"description\":\"My downloads\"," +
                            "\"treeUri\":\"content://com.android.externalstorage.documents" +
                            "/tree/primary%3ADownloads\"}]"
                    dataStore.edit { prefs -> prefs[locationsKey] = json }

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertTrue(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }

            @Test
            @Suppress("MaxLineLength")
            fun `migration from stored location with corrupted permission fields defaults to allowWrite=false and allowDelete=false`() =
                testScope.runTest {
                    val json =
                        "[{\"id\":\"loc1\",\"name\":\"Downloads\"," +
                            "\"path\":\"/Downloads\",\"description\":\"My downloads\"," +
                            "\"treeUri\":\"content://com.android.externalstorage.documents" +
                            "/tree/primary%3ADownloads\"," +
                            "\"allowWrite\":\"invalid\",\"allowDelete\":123}]"
                    dataStore.edit { prefs -> prefs[locationsKey] = json }

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertFalse(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
                }
        }

        @Nested
        @DisplayName("Permission Serialization")
        inner class PermissionSerialization {
            @Test
            fun `stored location with permissions serializes and deserializes correctly`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/Downloads",
                            description = "Test location",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertFalse(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }

            @Test
            fun `stored location with both permissions false serializes correctly`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/Downloads",
                            description = "Test location",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = false,
                        )
                    repository.addStoredLocation(location)

                    val result = repository.getStoredLocations()

                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertFalse(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
                }
        }

        @Nested
        @DisplayName("updateLocationAllowWrite")
        inner class UpdateLocationAllowWrite {
            @Test
            fun `updateLocationAllowWrite updates the flag`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = false,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowWrite("loc1", true)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertTrue(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
                }

            @Test
            fun `updateLocationAllowWrite for non-existent location does nothing`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = false,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowWrite("non-existent", true)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertFalse(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
                }
        }

        @Nested
        @DisplayName("updateLocationAllowDelete")
        inner class UpdateLocationAllowDelete {
            @Test
            fun `updateLocationAllowDelete updates the flag`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = false,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowDelete("loc1", true)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertFalse(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
                }

            @Test
            fun `updateLocationAllowDelete for non-existent location does nothing`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Downloads",
                            path = "/",
                            description = "",
                            treeUri = "content://test/tree/1",
                            allowWrite = false,
                            allowDelete = false,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowDelete("non-existent", true)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertEquals("loc1", result[0].id)
                    assertFalse(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
                }
        }
    }
}
