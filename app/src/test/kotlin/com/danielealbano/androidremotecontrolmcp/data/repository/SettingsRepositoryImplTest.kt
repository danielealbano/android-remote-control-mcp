package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    @BeforeEach
    fun setUp() {
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope.backgroundScope,
                produceFile = { File(tempDir, "test_settings.preferences_pb") },
            )
        repository = SettingsRepositoryImpl(dataStore)
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
}
