package com.danielealbano.androidremotecontrolmcp.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.CloudflareTunnelMode
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        val changeLogger =
            SettingsChangeLogger(
                RecordingServerLogRepository(),
                testDispatcher,
                SettingsChangeLogger.COALESCE_WINDOW_MS,
            )
        repository =
            SettingsRepositoryImpl(
                dataStore,
                changeLogger,
                EventChannelSettingsImpl(dataStore, changeLogger),
                RelaySettingsImpl(dataStore, changeLogger),
            )
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
                assertFalse(config.hideFromRecents)
                assertTrue(config.toolCallIndicatorEnabled)
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
        fun `getServerConfig generates token on first call when none stored`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertTrue(config.bearerToken.isNotEmpty())
                val initialized = dataStore.data.first()[booleanPreferencesKey("bearer_token_initialized")]
                assertEquals(true, initialized)
            }

        @Test
        fun `getServerConfig preserves existing token on upgrade path`() =
            testScope.runTest {
                // Simulate an upgrade: a non-empty token was persisted by a previous
                // app version that did not yet write the BEARER_TOKEN_INITIALIZED flag.
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("bearer_token")] = "legacy-token"
                }

                val config = repository.getServerConfig()

                assertEquals("legacy-token", config.bearerToken)
                val initialized = dataStore.data.first()[booleanPreferencesKey("bearer_token_initialized")]
                assertEquals(true, initialized)
            }

        @Test
        fun `getServerConfig returns empty token after explicit clear`() =
            testScope.runTest {
                // First call generates the token and sets the initialized flag.
                val generated = repository.getServerConfig()
                assertTrue(generated.bearerToken.isNotEmpty())

                // Explicit clear via updateBearerToken("").
                repository.updateBearerToken("")

                // Second call must return the empty token AS-IS — no regeneration.
                val cleared = repository.getServerConfig()
                assertEquals("", cleared.bearerToken)

                // The initialized flag remains true.
                val initialized = dataStore.data.first()[booleanPreferencesKey("bearer_token_initialized")]
                assertEquals(true, initialized)
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
    @DisplayName("updateHideFromRecents")
    inner class UpdateHideFromRecents {
        @Test
        fun `enables hide from recents`() =
            testScope.runTest {
                repository.updateHideFromRecents(true)
                val config = repository.getServerConfig()

                assertTrue(config.hideFromRecents)
            }

        @Test
        fun `disables hide from recents`() =
            testScope.runTest {
                repository.updateHideFromRecents(true)
                repository.updateHideFromRecents(false)
                val config = repository.getServerConfig()

                assertFalse(config.hideFromRecents)
            }
    }

    @Nested
    @DisplayName("updateToolCallIndicatorEnabled")
    inner class UpdateToolCallIndicatorEnabled {
        @Test
        fun `disables tool call indicator`() =
            testScope.runTest {
                repository.updateToolCallIndicatorEnabled(false)

                assertFalse(repository.getServerConfig().toolCallIndicatorEnabled)
            }

        @Test
        fun `enables tool call indicator`() =
            testScope.runTest {
                repository.updateToolCallIndicatorEnabled(false)
                repository.updateToolCallIndicatorEnabled(true)

                assertTrue(repository.getServerConfig().toolCallIndicatorEnabled)
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

        @Test
        fun `updates tunnel provider to RELAY`() =
            testScope.runTest {
                repository.updateTunnelProvider(TunnelProviderType.RELAY)
                val config = repository.getServerConfig()

                assertEquals(TunnelProviderType.RELAY, config.tunnelProvider)
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
    @DisplayName("updateRelayUrl")
    inner class UpdateRelayUrl {
        @Test
        fun `updates relay url trimmed`() =
            testScope.runTest {
                repository.updateRelayUrl("  wss://relay.example.com ")
                val config = repository.getServerConfig()

                assertEquals("wss://relay.example.com", config.relayUrl)
            }

        @Test
        fun `clears relay url with empty string`() =
            testScope.runTest {
                repository.updateRelayUrl("wss://relay.example.com")
                repository.updateRelayUrl("")
                val config = repository.getServerConfig()

                assertEquals("", config.relayUrl)
            }
    }

    @Nested
    @DisplayName("updateRelayDeviceToken")
    inner class UpdateRelayDeviceToken {
        @Test
        fun `updates relay device token trimmed`() =
            testScope.runTest {
                repository.updateRelayDeviceToken(" 0123456789abcdef ")
                val config = repository.getServerConfig()

                assertEquals("0123456789abcdef", config.relayDeviceToken)
            }
    }

    @Nested
    @DisplayName("validateRelayUrl")
    inner class ValidateRelayUrl {
        @Test
        fun `empty url is valid and normalizes to empty`() {
            assertEquals("", repository.validateRelayUrl("  ").getOrNull())
        }

        @Test
        fun `ws url with host is valid`() {
            assertEquals("ws://127.0.0.1:8081", repository.validateRelayUrl("ws://127.0.0.1:8081").getOrNull())
        }

        @Test
        fun `wss url with port is valid`() {
            assertTrue(repository.validateRelayUrl("wss://relay.example.com:8443").isSuccess)
        }

        @Test
        fun `trailing slash is stripped`() {
            assertEquals("wss://relay.example.com", repository.validateRelayUrl("wss://relay.example.com/").getOrNull())
        }

        @Test
        fun `trimmed value is returned`() {
            val result = repository.validateRelayUrl(" wss://relay.example.com ")

            assertEquals("wss://relay.example.com", result.getOrNull())
        }

        @Test
        fun `https scheme is rejected`() {
            val result = repository.validateRelayUrl("https://relay.example.com")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("ws://"),
            )
        }

        @Test
        fun `missing host is rejected`() {
            assertTrue(repository.validateRelayUrl("wss://").isFailure)
        }

        @Test
        fun `user info is rejected`() {
            assertTrue(repository.validateRelayUrl("wss://user:pw@relay.example.com").isFailure)
        }

        @Test
        fun `path is rejected`() {
            val result = repository.validateRelayUrl("wss://relay.example.com/base")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("path"),
            )
        }

        @Test
        fun `query is rejected`() {
            assertTrue(repository.validateRelayUrl("wss://relay.example.com/?x=1").isFailure)
        }

        @Test
        fun `fragment is rejected`() {
            assertTrue(repository.validateRelayUrl("wss://relay.example.com/#frag").isFailure)
        }

        @Test
        fun `unparsable url is rejected`() {
            val result = repository.validateRelayUrl("wss://relay example.com")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .startsWith("Invalid relay URL"),
            )
        }
    }

    @Nested
    @DisplayName("validateRelayDeviceToken")
    inner class ValidateRelayDeviceToken {
        @Test
        fun `empty token is valid`() {
            assertEquals("", repository.validateRelayDeviceToken("").getOrNull())
        }

        @Test
        fun `token of 16 characters is valid and trimmed`() {
            assertEquals("0123456789abcdef", repository.validateRelayDeviceToken(" 0123456789abcdef ").getOrNull())
        }

        @Test
        fun `token shorter than 16 characters is rejected`() {
            val result = repository.validateRelayDeviceToken("0123456789abcde")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("16"),
            )
        }
    }

    @Nested
    @DisplayName("isRelayUrlInsecure")
    inner class IsRelayUrlInsecure {
        @Test
        fun `wss url is not insecure`() {
            assertFalse(repository.isRelayUrlInsecure("wss://relay.example.com"))
        }

        @Test
        fun `ws url to public host is insecure`() {
            assertTrue(repository.isRelayUrlInsecure("ws://relay.example.com"))
        }

        @Test
        fun `ws url to loopback and private hosts is not insecure`() {
            val privateUrls =
                listOf(
                    "ws://127.0.0.1:8080",
                    "ws://localhost",
                    "ws://[::1]:8080",
                    "ws://192.168.1.5",
                    "ws://10.0.0.2",
                    "ws://172.16.0.9",
                )

            privateUrls.forEach { assertFalse(repository.isRelayUrlInsecure(it), it) }
        }

        @Test
        fun `unparsable url is not insecure`() {
            assertFalse(repository.isRelayUrlInsecure("ws://a b"))
        }
    }

    @Nested
    @DisplayName("updateCloudflareTunnelMode")
    inner class UpdateCloudflareTunnelMode {
        @Test
        fun `defaults to FREE when unset`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertEquals(CloudflareTunnelMode.FREE, config.cloudflareTunnelMode)
            }

        @Test
        fun `persists TOKEN mode`() =
            testScope.runTest {
                repository.updateCloudflareTunnelMode(CloudflareTunnelMode.TOKEN)
                val config = repository.getServerConfig()

                assertEquals(CloudflareTunnelMode.TOKEN, config.cloudflareTunnelMode)
            }

        @Test
        fun `unknown stored mode falls back to FREE`() =
            testScope.runTest {
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("cloudflare_tunnel_mode")] = "NOT_A_MODE"
                }
                val config = repository.getServerConfig()

                assertEquals(CloudflareTunnelMode.FREE, config.cloudflareTunnelMode)
            }
    }

    @Nested
    @DisplayName("updateCloudflareTunnelToken")
    inner class UpdateCloudflareTunnelToken {
        @Test
        fun `defaults to empty when unset`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertEquals("", config.cloudflareTunnelToken)
            }

        @Test
        fun `persists token`() =
            testScope.runTest {
                repository.updateCloudflareTunnelToken("cf-token-xyz789")
                val config = repository.getServerConfig()

                assertEquals("cf-token-xyz789", config.cloudflareTunnelToken)
            }
    }

    @Nested
    @DisplayName("updateCloudflareTunnelExtraArgs")
    inner class UpdateCloudflareTunnelExtraArgs {
        @Test
        fun `defaults to empty when unset`() =
            testScope.runTest {
                val config = repository.getServerConfig()

                assertEquals("", config.cloudflareTunnelExtraArgs)
            }

        @Test
        fun `persists extra args`() =
            testScope.runTest {
                repository.updateCloudflareTunnelExtraArgs("--edge region1.v2.argotunnel.com:7844")
                val config = repository.getServerConfig()

                assertEquals("--edge region1.v2.argotunnel.com:7844", config.cloudflareTunnelExtraArgs)
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
            fun `migration from stored location with null permission fields defaults to allowWrite=true and allowDelete=true`() =
                testScope.runTest {
                    val json =
                        "[{\"id\":\"loc1\",\"name\":\"Downloads\"," +
                            "\"path\":\"/Downloads\",\"description\":\"My downloads\"," +
                            "\"treeUri\":\"content://com.android.externalstorage.documents" +
                            "/tree/primary%3ADownloads\",\"allowWrite\":null,\"allowDelete\":null}]"
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
            fun `permission flag round-trip true to false to true`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Test",
                            path = "/test",
                            description = "Test location",
                            treeUri = "content://test/tree/loc1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowWrite("loc1", false)
                    var result = repository.getStoredLocations()
                    assertFalse(result[0].allowWrite)

                    repository.updateLocationAllowWrite("loc1", true)
                    result = repository.getStoredLocations()
                    assertTrue(result[0].allowWrite)
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
            fun `updateLocationAllowWrite updates flag from true to false`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Test",
                            path = "/test",
                            description = "Test location",
                            treeUri = "content://test/tree/loc1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowWrite("loc1", false)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertFalse(result[0].allowWrite)
                    assertTrue(result[0].allowDelete)
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
            fun `updateLocationAllowDelete updates flag from true to false`() =
                testScope.runTest {
                    val location =
                        SettingsRepository.StoredLocation(
                            id = "loc1",
                            name = "Test",
                            path = "/test",
                            description = "Test location",
                            treeUri = "content://test/tree/loc1",
                            allowWrite = true,
                            allowDelete = true,
                        )
                    repository.addStoredLocation(location)

                    repository.updateLocationAllowDelete("loc1", false)

                    val result = repository.getStoredLocations()
                    assertEquals(1, result.size)
                    assertTrue(result[0].allowWrite)
                    assertFalse(result[0].allowDelete)
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

    @Nested
    @DisplayName("tool permissions")
    inner class ToolPermissions {
        @Test
        fun `updateToolPermissionsConfig with disabled tools persists`() =
            runTest {
                val config = ToolPermissionsConfig(disabledTools = setOf("tap", "swipe"))
                repository.updateToolPermissionsConfig(config)

                val result = repository.getServerConfig()
                assertEquals(setOf("tap", "swipe"), result.toolPermissionsConfig.disabledTools)
            }

        @Test
        fun `updateToolPermissionsConfig with disabled params persists`() =
            runTest {
                val config =
                    ToolPermissionsConfig(
                        disabledParams = mapOf("get_screen_state" to setOf("include_screenshot")),
                    )
                repository.updateToolPermissionsConfig(config)

                val result = repository.getServerConfig()
                assertEquals(
                    mapOf("get_screen_state" to setOf("include_screenshot")),
                    result.toolPermissionsConfig.disabledParams,
                )
            }

        @Test
        fun `empty config round-trip`() =
            runTest {
                repository.updateToolPermissionsConfig(ToolPermissionsConfig())

                val result = repository.getServerConfig()
                assertEquals(ToolPermissionsConfig(), result.toolPermissionsConfig)
            }

        @Test
        fun `corrupt JSON in DataStore falls back to default`() =
            runTest {
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("tool_permissions")] = "not-json"
                }

                val result = repository.getServerConfig()
                assertEquals(ToolPermissionsConfig(), result.toolPermissionsConfig)
            }

        @Test
        fun `updateToolEnabled toggle on then off`() =
            runTest {
                repository.updateToolEnabled("tap", false)
                var result = repository.getServerConfig()
                assertTrue(result.toolPermissionsConfig.disabledTools.contains("tap"))

                repository.updateToolEnabled("tap", true)
                result = repository.getServerConfig()
                assertFalse(result.toolPermissionsConfig.disabledTools.contains("tap"))
            }

        @Test
        fun `updateParamEnabled toggle removes empty key`() =
            runTest {
                repository.updateParamEnabled("get_screen_state", "include_screenshot", false)
                var result = repository.getServerConfig()
                assertTrue(
                    result.toolPermissionsConfig.disabledParams.containsKey("get_screen_state"),
                )

                repository.updateParamEnabled("get_screen_state", "include_screenshot", true)
                result = repository.getServerConfig()
                assertFalse(
                    result.toolPermissionsConfig.disabledParams.containsKey("get_screen_state"),
                )
            }
    }

    @Nested
    @DisplayName("Builtin Location Permissions")
    inner class BuiltinLocationPermissionsTests {
        @Test
        fun `getBuiltinLocationPermissions returns empty map when no data`() =
            testScope.runTest {
                val result = repository.getBuiltinLocationPermissions()
                assertTrue(result.isEmpty())
            }

        @Test
        fun `updateBuiltinLocationAllowWrite persists and reads back`() =
            testScope.runTest {
                repository.updateBuiltinLocationAllowWrite("builtin:downloads", true)

                val result = repository.getBuiltinLocationPermissions()
                assertEquals(true, result["builtin:downloads"]?.allowWrite)
                assertEquals(false, result["builtin:downloads"]?.allowDelete)
            }

        @Test
        fun `updateBuiltinLocationAllowDelete persists and reads back`() =
            testScope.runTest {
                repository.updateBuiltinLocationAllowDelete("builtin:pictures", true)

                val result = repository.getBuiltinLocationPermissions()
                assertEquals(false, result["builtin:pictures"]?.allowWrite)
                assertEquals(true, result["builtin:pictures"]?.allowDelete)
            }

        @Test
        fun `multiple builtin permissions stored independently`() =
            testScope.runTest {
                repository.updateBuiltinLocationAllowWrite("builtin:downloads", true)
                repository.updateBuiltinLocationAllowDelete("builtin:pictures", true)
                repository.updateBuiltinLocationAllowWrite("builtin:music", true)
                repository.updateBuiltinLocationAllowDelete("builtin:music", true)

                val result = repository.getBuiltinLocationPermissions()
                assertEquals(3, result.size)
                assertEquals(BuiltinPermissions(allowWrite = true, allowDelete = false), result["builtin:downloads"])
                assertEquals(BuiltinPermissions(allowWrite = false, allowDelete = true), result["builtin:pictures"])
                assertEquals(BuiltinPermissions(allowWrite = true, allowDelete = true), result["builtin:music"])
            }

        @Test
        fun `malformed JSON returns empty map`() =
            testScope.runTest {
                // Write malformed JSON directly to the DataStore key
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey("builtin_location_permissions")] = "not valid json{{"
                }

                val result = repository.getBuiltinLocationPermissions()
                assertTrue(result.isEmpty())
            }

        @Test
        fun `serialized JSON matches expected format`() =
            testScope.runTest {
                repository.updateBuiltinLocationAllowWrite("builtin:downloads", true)

                // Read raw JSON from DataStore
                val prefs = dataStore.data.first()
                val json = prefs[stringPreferencesKey("builtin_location_permissions")]

                // Verify the JSON structure matches expected format
                assertTrue(json != null)
                assertTrue(json!!.contains("\"builtin:downloads\""))
                assertTrue(json.contains("\"allowWrite\":true"))
                assertTrue(json.contains("\"allowDelete\":false"))
            }
    }

    @Nested
    @DisplayName("auth model (oauth/bearer flags, signing secret, public URL override)")
    inner class AuthModel {
        private val bearerTokenKey = stringPreferencesKey("bearer_token")
        private val bearerTokenInitializedKey = booleanPreferencesKey("bearer_token_initialized")
        private val bearerTokenEnabledKey = booleanPreferencesKey("bearer_token_enabled")
        private val bearerTokenEnabledInitializedKey = booleanPreferencesKey("bearer_token_enabled_initialized")

        @Test
        fun `fresh install enables bearer with generated token and oauth enabled`() =
            testScope.runTest {
                val config = repository.getServerConfig()
                assertTrue(config.bearerTokenEnabled)
                assertTrue(config.bearerToken.isNotEmpty())
                assertTrue(config.oauthEnabled)
            }

        @Test
        fun `migration preserves enabled when existing token present`() =
            testScope.runTest {
                dataStore.edit { prefs ->
                    prefs[bearerTokenInitializedKey] = true
                    prefs[bearerTokenKey] = "existing-token"
                }
                assertTrue(repository.getServerConfig().bearerTokenEnabled)
            }

        @Test
        fun `migration disables when previously cleared`() =
            testScope.runTest {
                dataStore.edit { prefs ->
                    prefs[bearerTokenInitializedKey] = true
                    prefs[bearerTokenKey] = ""
                }
                val config = repository.getServerConfig()
                assertFalse(config.bearerTokenEnabled)
                assertEquals("", config.bearerToken)
            }

        @Test
        fun `migration is idempotent`() =
            testScope.runTest {
                dataStore.edit { prefs ->
                    prefs[bearerTokenEnabledInitializedKey] = true
                    prefs[bearerTokenEnabledKey] = false
                }
                assertFalse(repository.getServerConfig().bearerTokenEnabled)
            }

        @Test
        fun `migration with token but no initialized flag preserves token and enables`() =
            testScope.runTest {
                dataStore.edit { prefs -> prefs[bearerTokenKey] = "legacy-token" }
                val config = repository.getServerConfig()
                assertTrue(config.bearerTokenEnabled)
                assertEquals("legacy-token", config.bearerToken)
            }

        @Test
        fun `updateBearerTokenEnabled(true) generates token when empty`() =
            testScope.runTest {
                repository.updateBearerTokenEnabled(true)
                val config = repository.serverConfig.first()
                assertTrue(config.bearerTokenEnabled)
                assertTrue(config.bearerToken.isNotEmpty())
            }

        @Test
        fun `updateBearerTokenEnabled(false) keeps value`() =
            testScope.runTest {
                repository.updateBearerToken("keep-me")
                repository.updateBearerTokenEnabled(false)
                val config = repository.serverConfig.first()
                assertFalse(config.bearerTokenEnabled)
                assertEquals("keep-me", config.bearerToken)
            }

        @Test
        fun `re-enabling bearer with existing value does not regenerate`() =
            testScope.runTest {
                repository.updateBearerToken("original")
                repository.updateBearerTokenEnabled(false)
                repository.updateBearerTokenEnabled(true)
                assertEquals("original", repository.serverConfig.first().bearerToken)
            }

        @Test
        fun `getOrCreateJwtSigningSecret is stable`() =
            testScope.runTest {
                val first = repository.getOrCreateJwtSigningSecret()
                val second = repository.getOrCreateJwtSigningSecret()
                assertTrue(first.isNotEmpty())
                assertEquals(first, second)
            }

        @Test
        fun `updateOauthEnabled persists`() =
            testScope.runTest {
                repository.updateOauthEnabled(true)
                assertTrue(repository.serverConfig.first().oauthEnabled)
            }

        @Test
        fun `publicUrlOverride round-trips and validates`() =
            testScope.runTest {
                repository.updatePublicUrlOverride("https://example.com")
                assertEquals("https://example.com", repository.serverConfig.first().publicUrlOverride)

                assertEquals("", repository.validatePublicUrlOverride("").getOrNull())
                assertTrue(repository.validatePublicUrlOverride("https://host.example").isSuccess)
                assertTrue(repository.validatePublicUrlOverride("ftp://host.example").isFailure)
                assertTrue(repository.validatePublicUrlOverride("not a url").isFailure)
            }
    }

    @Nested
    @DisplayName("Privacy Mode")
    inner class PrivacyMode {
        @Test
        fun `privacy config defaults when unset`() =
            testScope.runTest {
                assertEquals(PrivacyModeConfig(), repository.getServerConfig().privacyModeConfig)
            }

        @Test
        fun `updatePrivacyModeEnabled persists`() =
            testScope.runTest {
                repository.updatePrivacyModeEnabled(true)
                assertTrue(
                    repository.serverConfig
                        .first()
                        .privacyModeConfig.enabled,
                )
                repository.updatePrivacyModeEnabled(false)
                assertFalse(
                    repository.serverConfig
                        .first()
                        .privacyModeConfig.enabled,
                )
            }

        @Test
        fun `updatePrivacyCategoryEnabled adds and removes from disabled set`() =
            testScope.runTest {
                repository.updatePrivacyCategoryEnabled(PiiCategory.NAMES, false)
                assertFalse(
                    repository.serverConfig
                        .first()
                        .privacyModeConfig
                        .isCategoryEnabled(PiiCategory.NAMES),
                )

                repository.updatePrivacyCategoryEnabled(PiiCategory.NAMES, true)
                assertTrue(
                    repository.serverConfig
                        .first()
                        .privacyModeConfig
                        .isCategoryEnabled(PiiCategory.NAMES),
                )
            }

        @Test
        fun `updatePrivacyRedactionMode and placeholder format persist`() =
            testScope.runTest {
                repository.updatePrivacyRedactionMode(RedactionMode.REDACT)
                repository.updatePrivacyPlaceholderFormat(PlaceholderFormat.NUMBERED)

                val config = repository.serverConfig.first().privacyModeConfig
                assertEquals(RedactionMode.REDACT, config.redactionMode)
                assertEquals(PlaceholderFormat.NUMBERED, config.placeholderFormat)
            }

        @Test
        fun `privacy benchmark estimate persists`() =
            testScope.runTest {
                assertEquals(null, repository.privacyBenchmarkEstimateSeconds.first())
                repository.updatePrivacyBenchmarkEstimateSeconds(1.5)
                assertEquals(1.5, repository.privacyBenchmarkEstimateSeconds.first())
            }

        @Test
        fun `privacy card dismissal persists and defaults to false`() =
            testScope.runTest {
                assertFalse(repository.privacyModeCardDismissed.first())
                repository.updatePrivacyModeCardDismissed(true)
                assertTrue(repository.privacyModeCardDismissed.first())
            }
    }
}
