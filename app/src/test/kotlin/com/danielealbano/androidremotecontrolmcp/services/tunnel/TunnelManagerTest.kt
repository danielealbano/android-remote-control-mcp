package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TunnelManager")
class TunnelManagerTest {
    private val mockSettingsRepository = mockk<SettingsRepository>()
    private val mockCloudflareProvider = mockk<CloudflareTunnelProvider>(relaxed = true)
    private val mockNgrokProvider = mockk<NgrokTunnelProvider>(relaxed = true)

    private val cloudflareFactory =
        mockk<Provider<CloudflareTunnelProvider>> {
            every { get() } returns mockCloudflareProvider
        }

    private val ngrokFactory =
        mockk<Provider<NgrokTunnelProvider>> {
            every { get() } returns mockNgrokProvider
        }

    private fun createManager(): TunnelManager =
        TunnelManager(
            settingsRepository = mockSettingsRepository,
            cloudflareTunnelProviderFactory = cloudflareFactory,
            ngrokTunnelProviderFactory = ngrokFactory,
        )

    @Nested
    @DisplayName("start")
    inner class Start {
        @Test
        fun `start with tunnel enabled and Cloudflare provider starts Cloudflare tunnel`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockCloudflareProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(8080)

                coVerify { mockCloudflareProvider.start(8080, config) }
            }

        @Test
        fun `start with tunnel enabled and ngrok provider starts ngrok tunnel`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        tunnelProvider = TunnelProviderType.NGROK,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockNgrokProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockNgrokProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(8080)

                coVerify { mockNgrokProvider.start(8080, config) }
            }

        @Test
        fun `start with tunnel disabled is no-op`() =
            runTest {
                val config = ServerConfig(tunnelEnabled = false)
                every { mockSettingsRepository.serverConfig } returns flowOf(config)

                val manager = createManager()
                manager.start(8080)

                coVerify(exactly = 0) { mockCloudflareProvider.start(any(), any()) }
                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
            }

        @Test
        fun `start relays provider status to tunnelStatus`() =
            runTest {
                val providerStatus =
                    MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockCloudflareProvider.status } returns providerStatus
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(8080)

                // Give the relay coroutine (on Dispatchers.IO) time to start collecting
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)

                // Simulate the provider reporting Connected
                providerStatus.value =
                    TunnelStatus.Connected(
                        url = "https://test.trycloudflare.com",
                        providerType = TunnelProviderType.CLOUDFLARE,
                    )

                // Give the relay time to propagate
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)

                val status = manager.tunnelStatus.value
                assertEquals(
                    TunnelStatus.Connected(
                        url = "https://test.trycloudflare.com",
                        providerType = TunnelProviderType.CLOUDFLARE,
                    ),
                    status,
                )
            }
    }

    @Nested
    @DisplayName("stop")
    inner class Stop {
        @Test
        fun `stop stops active provider and resets status`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockCloudflareProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs
                coEvery { mockCloudflareProvider.stop() } just Runs

                val manager = createManager()
                manager.start(8080)
                manager.stop()

                coVerify { mockCloudflareProvider.stop() }
                assertEquals(TunnelStatus.Disconnected, manager.tunnelStatus.value)
            }

        @Test
        fun `stop when no active tunnel is no-op`() =
            runTest {
                val manager = createManager()

                manager.stop()

                assertEquals(TunnelStatus.Disconnected, manager.tunnelStatus.value)
            }
    }

    @Nested
    @DisplayName("status")
    inner class Status {
        @Test
        fun `status defaults to Disconnected`() {
            val manager = createManager()
            assertEquals(TunnelStatus.Disconnected, manager.tunnelStatus.value)
        }
    }

    companion object {
        /** Time to wait for the relay coroutine on Dispatchers.IO to propagate state. */
        private const val RELAY_PROPAGATION_DELAY_MS = 100L
    }
}
