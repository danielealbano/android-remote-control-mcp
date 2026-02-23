package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.CertificateSource
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AdbConfigHandler], the logic delegate behind [AdbConfigReceiver].
 *
 * Tests verify that each intent extra is correctly parsed, validated,
 * and applied to the [SettingsRepository]. Android framework class [Intent]
 * is mocked via MockK. The handler is tested directly (no Hilt or
 * BroadcastReceiver lifecycle involved).
 */
@DisplayName("AdbConfigHandler")
class AdbConfigHandlerTest {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var handler: AdbConfigHandler
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        settingsRepository = mockk(relaxUnitFun = true)
        every { settingsRepository.validatePort(any()) } answers {
            val port = firstArg<Int>()
            if (port in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT) {
                Result.success(port)
            } else {
                Result.failure(IllegalArgumentException("Port out of range"))
            }
        }
        every { settingsRepository.validateFileSizeLimit(any()) } answers {
            val limit = firstArg<Int>()
            if (limit in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
                Result.success(limit)
            } else {
                Result.failure(IllegalArgumentException("File size limit out of range"))
            }
        }
        every { settingsRepository.validateDownloadTimeout(any()) } answers {
            val seconds = firstArg<Int>()
            if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
                Result.success(seconds)
            } else {
                Result.failure(IllegalArgumentException("Timeout out of range"))
            }
        }
        every { settingsRepository.validateCertificateHostname(any()) } answers {
            val hostname = firstArg<String>()
            if (hostname.isNotBlank()) Result.success(hostname) else Result.failure(IllegalArgumentException("Blank"))
        }
        every { settingsRepository.validateDeviceSlug(any()) } answers {
            val slug = firstArg<String>()
            if (slug.length <= ServerConfig.MAX_DEVICE_SLUG_LENGTH && ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
                Result.success(slug)
            } else {
                Result.failure(IllegalArgumentException("Invalid slug"))
            }
        }

        context = mockk(relaxed = true)
        handler = AdbConfigHandler(settingsRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /**
     * Creates a mock [Intent] with the given action and extras applied via the [block] lambda.
     * By default, [Intent.hasExtra] returns false for any key not explicitly set.
     */
    private fun createIntent(
        action: String,
        block: IntentBuilder.() -> Unit = {},
    ): Intent {
        val builder = IntentBuilder()
        builder.block()
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action

        // Default: hasExtra returns false for all keys
        every { intent.hasExtra(any()) } returns false
        every { intent.getStringExtra(any()) } returns null
        every { intent.getIntExtra(any(), any()) } answers { secondArg() }
        every { intent.getBooleanExtra(any(), any()) } answers { secondArg() }

        // Apply overrides from builder
        for ((key, value) in builder.stringExtras) {
            every { intent.getStringExtra(key) } returns value
            every { intent.hasExtra(key) } returns true
        }
        for ((key, value) in builder.intExtras) {
            every { intent.getIntExtra(key, any()) } returns value
            every { intent.hasExtra(key) } returns true
        }
        for ((key, value) in builder.booleanExtras) {
            every { intent.getBooleanExtra(key, any()) } returns value
            every { intent.hasExtra(key) } returns true
        }

        return intent
    }

    // ─────────────────────────────────────────────────────────────────────
    // Configure action — individual setting tests
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ACTION_CONFIGURE")
    inner class ConfigureTests {
        @Test
        @DisplayName("bearer_token is applied when provided")
        fun bearerTokenApplied() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BEARER_TOKEN, "my-test-token")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateBearerToken("my-test-token") }
            }

        @Test
        @DisplayName("empty bearer_token is ignored")
        fun emptyBearerTokenIgnored() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BEARER_TOKEN, "")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateBearerToken(any()) }
            }

        @Test
        @DisplayName("binding_address 0.0.0.0 maps to NETWORK")
        fun bindingAddressNetwork() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BINDING_ADDRESS, "0.0.0.0")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateBindingAddress(BindingAddress.NETWORK) }
            }

        @Test
        @DisplayName("binding_address 127.0.0.1 maps to LOCALHOST")
        fun bindingAddressLocalhost() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BINDING_ADDRESS, "127.0.0.1")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateBindingAddress(BindingAddress.LOCALHOST) }
            }

        @Test
        @DisplayName("valid port is applied")
        fun validPortApplied() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_PORT, 9090)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updatePort(9090) }
            }

        @Test
        @DisplayName("invalid port is rejected")
        fun invalidPortRejected() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_PORT, 0)
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updatePort(any()) }
            }

        @Test
        @DisplayName("auto_start_on_boot true is applied")
        fun autoStartTrue() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_AUTO_START_ON_BOOT, true)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAutoStartOnBoot(true) }
            }

        @Test
        @DisplayName("auto_start_on_boot false is applied")
        fun autoStartFalse() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_AUTO_START_ON_BOOT, false)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAutoStartOnBoot(false) }
            }

        @Test
        @DisplayName("auto_start_on_boot is not applied when extra is absent")
        fun autoStartAbsent() =
            runTest {
                val intent = createIntent(AdbConfigReceiver.ACTION_CONFIGURE)
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateAutoStartOnBoot(any()) }
            }

        @Test
        @DisplayName("https_enabled is applied")
        fun httpsEnabled() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_HTTPS_ENABLED, true)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateHttpsEnabled(true) }
            }

        @Test
        @DisplayName("certificate_source AUTO_GENERATED is applied")
        fun certificateSourceAutoGenerated() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_CERTIFICATE_SOURCE, "AUTO_GENERATED")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateCertificateSource(CertificateSource.AUTO_GENERATED) }
            }

        @Test
        @DisplayName("certificate_source CUSTOM is applied")
        fun certificateSourceCustom() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_CERTIFICATE_SOURCE, "CUSTOM")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateCertificateSource(CertificateSource.CUSTOM) }
            }

        @Test
        @DisplayName("invalid certificate_source is rejected")
        fun invalidCertificateSource() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_CERTIFICATE_SOURCE, "INVALID")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateCertificateSource(any()) }
            }

        @Test
        @DisplayName("valid certificate_hostname is applied")
        fun validCertificateHostname() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_CERTIFICATE_HOSTNAME, "my-host.local")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateCertificateHostname("my-host.local") }
            }

        @Test
        @DisplayName("blank certificate_hostname is rejected")
        fun blankCertificateHostname() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_CERTIFICATE_HOSTNAME, "")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateCertificateHostname(any()) }
            }

        @Test
        @DisplayName("tunnel_enabled is applied")
        fun tunnelEnabled() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_TUNNEL_ENABLED, true)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateTunnelEnabled(true) }
            }

        @Test
        @DisplayName("tunnel_provider CLOUDFLARE is applied")
        fun tunnelProviderCloudflare() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_TUNNEL_PROVIDER, "CLOUDFLARE")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateTunnelProvider(TunnelProviderType.CLOUDFLARE) }
            }

        @Test
        @DisplayName("tunnel_provider NGROK is applied")
        fun tunnelProviderNgrok() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_TUNNEL_PROVIDER, "NGROK")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateTunnelProvider(TunnelProviderType.NGROK) }
            }

        @Test
        @DisplayName("invalid tunnel_provider is rejected")
        fun invalidTunnelProvider() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_TUNNEL_PROVIDER, "WIREGUARD")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateTunnelProvider(any()) }
            }

        @Test
        @DisplayName("ngrok_authtoken is applied")
        fun ngrokAuthtoken() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_NGROK_AUTHTOKEN, "2abc_ngrok_token")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateNgrokAuthtoken("2abc_ngrok_token") }
            }

        @Test
        @DisplayName("ngrok_domain is applied")
        fun ngrokDomain() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_NGROK_DOMAIN, "my-app.ngrok-free.app")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateNgrokDomain("my-app.ngrok-free.app") }
            }

        @Test
        @DisplayName("valid file_size_limit_mb is applied")
        fun validFileSizeLimit() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_FILE_SIZE_LIMIT_MB, 100)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateFileSizeLimit(100) }
            }

        @Test
        @DisplayName("invalid file_size_limit_mb is rejected")
        fun invalidFileSizeLimit() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_FILE_SIZE_LIMIT_MB, 9999)
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateFileSizeLimit(any()) }
            }

        @Test
        @DisplayName("allow_http_downloads is applied")
        fun allowHttpDownloads() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_ALLOW_HTTP_DOWNLOADS, true)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAllowHttpDownloads(true) }
            }

        @Test
        @DisplayName("allow_unverified_https_certs is applied")
        fun allowUnverifiedHttpsCerts() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS, true)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAllowUnverifiedHttpsCerts(true) }
            }

        @Test
        @DisplayName("valid download_timeout_seconds is applied")
        fun validDownloadTimeout() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_DOWNLOAD_TIMEOUT_SECONDS, 120)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateDownloadTimeout(120) }
            }

        @Test
        @DisplayName("invalid download_timeout_seconds is rejected")
        fun invalidDownloadTimeout() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        int(AdbConfigHandler.EXTRA_DOWNLOAD_TIMEOUT_SECONDS, 5)
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateDownloadTimeout(any()) }
            }

        @Test
        @DisplayName("valid device_slug is applied")
        fun validDeviceSlug() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_DEVICE_SLUG, "my_pixel_9")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateDeviceSlug("my_pixel_9") }
            }

        @Test
        @DisplayName("invalid device_slug is rejected")
        fun invalidDeviceSlug() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_DEVICE_SLUG, "has spaces!")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
            }

        @Test
        @DisplayName("empty device_slug is applied (clears the slug)")
        fun emptyDeviceSlug() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_DEVICE_SLUG, "")
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateDeviceSlug("") }
            }

        @Test
        @DisplayName("multiple settings are applied in single broadcast")
        fun multipleSettings() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BEARER_TOKEN, "token-123")
                        string(AdbConfigHandler.EXTRA_BINDING_ADDRESS, "0.0.0.0")
                        int(AdbConfigHandler.EXTRA_PORT, 3000)
                        boolean(AdbConfigHandler.EXTRA_AUTO_START_ON_BOOT, true)
                        boolean(AdbConfigHandler.EXTRA_TUNNEL_ENABLED, true)
                        string(AdbConfigHandler.EXTRA_TUNNEL_PROVIDER, "NGROK")
                        string(AdbConfigHandler.EXTRA_NGROK_AUTHTOKEN, "ngrok-token")
                    }
                handler.handle(context, intent)

                coVerify { settingsRepository.updateBearerToken("token-123") }
                coVerify { settingsRepository.updateBindingAddress(BindingAddress.NETWORK) }
                coVerify { settingsRepository.updatePort(3000) }
                coVerify { settingsRepository.updateAutoStartOnBoot(true) }
                coVerify { settingsRepository.updateTunnelEnabled(true) }
                coVerify { settingsRepository.updateTunnelProvider(TunnelProviderType.NGROK) }
                coVerify { settingsRepository.updateNgrokAuthtoken("ngrok-token") }
            }

        @Test
        @DisplayName("no extras leaves all settings unchanged")
        fun noExtras() =
            runTest {
                val intent = createIntent(AdbConfigReceiver.ACTION_CONFIGURE)
                handler.handle(context, intent)

                coVerify(exactly = 0) { settingsRepository.updateBearerToken(any()) }
                coVerify(exactly = 0) { settingsRepository.updateBindingAddress(any()) }
                coVerify(exactly = 0) { settingsRepository.updatePort(any()) }
                coVerify(exactly = 0) { settingsRepository.updateAutoStartOnBoot(any()) }
                coVerify(exactly = 0) { settingsRepository.updateHttpsEnabled(any()) }
                coVerify(exactly = 0) { settingsRepository.updateCertificateSource(any()) }
                coVerify(exactly = 0) { settingsRepository.updateCertificateHostname(any()) }
                coVerify(exactly = 0) { settingsRepository.updateTunnelEnabled(any()) }
                coVerify(exactly = 0) { settingsRepository.updateTunnelProvider(any()) }
                coVerify(exactly = 0) { settingsRepository.updateNgrokAuthtoken(any()) }
                coVerify(exactly = 0) { settingsRepository.updateNgrokDomain(any()) }
                coVerify(exactly = 0) { settingsRepository.updateFileSizeLimit(any()) }
                coVerify(exactly = 0) { settingsRepository.updateAllowHttpDownloads(any()) }
                coVerify(exactly = 0) { settingsRepository.updateAllowUnverifiedHttpsCerts(any()) }
                coVerify(exactly = 0) { settingsRepository.updateDownloadTimeout(any()) }
                coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
            }

        @Test
        @DisplayName("unrecognized binding_address is rejected")
        fun unrecognizedBindingAddressRejected() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_BINDING_ADDRESS, "192.168.1.100")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateBindingAddress(any()) }
            }

        @Test
        @DisplayName("empty ngrok_authtoken is ignored")
        fun emptyNgrokAuthtokenIgnored() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_NGROK_AUTHTOKEN, "")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateNgrokAuthtoken(any()) }
            }

        @Test
        @DisplayName("absent ngrok_domain does not update setting")
        fun absentNgrokDomain() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        string(AdbConfigHandler.EXTRA_NGROK_AUTHTOKEN, "some-token")
                    }
                handler.handle(context, intent)
                coVerify(exactly = 0) { settingsRepository.updateNgrokDomain(any()) }
            }

        @Test
        @DisplayName("https_enabled false is applied")
        fun httpsEnabledFalse() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_HTTPS_ENABLED, false)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateHttpsEnabled(false) }
            }

        @Test
        @DisplayName("tunnel_enabled false is applied")
        fun tunnelEnabledFalse() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_TUNNEL_ENABLED, false)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateTunnelEnabled(false) }
            }

        @Test
        @DisplayName("allow_http_downloads false is applied")
        fun allowHttpDownloadsFalse() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_ALLOW_HTTP_DOWNLOADS, false)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAllowHttpDownloads(false) }
            }

        @Test
        @DisplayName("allow_unverified_https_certs false is applied")
        fun allowUnverifiedHttpsCertsFalse() =
            runTest {
                val intent =
                    createIntent(AdbConfigReceiver.ACTION_CONFIGURE) {
                        boolean(AdbConfigHandler.EXTRA_ALLOW_UNVERIFIED_HTTPS_CERTS, false)
                    }
                handler.handle(context, intent)
                coVerify { settingsRepository.updateAllowUnverifiedHttpsCerts(false) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Start / Stop server actions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ACTION_START_SERVER")
    inner class StartServerTests {
        @Test
        @DisplayName("start server sends startForegroundService intent")
        fun startServerSendsIntent() =
            runTest {
                val intent = createIntent(AdbConfigReceiver.ACTION_START_SERVER)
                handler.handle(context, intent)
                verify(exactly = 1) { context.startForegroundService(any()) }
                verify(exactly = 0) { context.startService(any()) }
            }
    }

    @Nested
    @DisplayName("ACTION_STOP_SERVER")
    inner class StopServerTests {
        @Test
        @DisplayName("stop server sends startService intent")
        fun stopServerSendsIntent() =
            runTest {
                val intent = createIntent(AdbConfigReceiver.ACTION_STOP_SERVER)
                handler.handle(context, intent)
                verify(exactly = 1) { context.startService(any()) }
                verify(exactly = 0) { context.startForegroundService(any()) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Unknown action
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Unknown action")
    inner class UnknownActionTests {
        @Test
        @DisplayName("unknown action is ignored without side effects")
        fun unknownActionIgnored() =
            runTest {
                val intent = createIntent("com.example.UNKNOWN_ACTION")
                handler.handle(context, intent)

                coVerify(exactly = 0) { settingsRepository.updateBearerToken(any()) }
                verify(exactly = 0) { context.startForegroundService(any()) }
                verify(exactly = 0) { context.startService(any()) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test helpers
    // ─────────────────────────────────────────────────────────────────────

    private class IntentBuilder {
        val stringExtras = mutableMapOf<String, String>()
        val intExtras = mutableMapOf<String, Int>()
        val booleanExtras = mutableMapOf<String, Boolean>()

        fun string(
            key: String,
            value: String,
        ) {
            stringExtras[key] = value
        }

        fun int(
            key: String,
            value: Int,
        ) {
            intExtras[key] = value
        }

        fun boolean(
            key: String,
            value: Boolean,
        ) {
            booleanExtras[key] = value
        }
    }
}
