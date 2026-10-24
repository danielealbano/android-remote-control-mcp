package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel.ArcTunnelProvider
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    private val mockArcProvider = mockk<ArcTunnelProvider>(relaxed = true)

    private val arcFactory =
        mockk<Provider<ArcTunnelProvider>> {
            every { get() } returns mockArcProvider
        }

    private val listenerHost = mockk<TunnelListenerHost>()

    private val serverLog = RecordingServerLogRepository()

    private fun createManager(): TunnelManager =
        TunnelManager(
            settingsRepository = mockSettingsRepository,
            cloudflareTunnelProviderFactory = cloudflareFactory,
            ngrokTunnelProviderFactory = ngrokFactory,
            arcTunnelProviderFactory = arcFactory,
            serverLogRepository = serverLog,
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
                manager.start(8080, listenerHost)

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
                manager.start(8080, listenerHost)

                coVerify { mockNgrokProvider.start(8080, config) }
            }

        @Test
        fun `start with tunnel disabled is no-op`() =
            runTest {
                val config = ServerConfig(tunnelEnabled = false)
                every { mockSettingsRepository.serverConfig } returns flowOf(config)

                val manager = createManager()
                manager.start(8080, listenerHost)

                coVerify(exactly = 0) { mockCloudflareProvider.start(any(), any()) }
                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
            }

        @Test
        fun `start with https enabled does not start tunnel`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        httpsEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)

                val manager = createManager()
                manager.start(8080, listenerHost)

                coVerify(exactly = 0) { mockCloudflareProvider.start(any(), any()) }
                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
            }

        @Test
        fun `start with ARC_TUNNEL binds the listener host and starts the provider`() =
            runTest {
                val config = ServerConfig(tunnelEnabled = true, tunnelProvider = TunnelProviderType.ARC_TUNNEL)
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockArcProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(8080, listenerHost)

                coVerifyOrder {
                    mockArcProvider.bind(listenerHost)
                    mockArcProvider.start(8080, config)
                }
            }

        @Test
        fun `start with ARC_TUNNEL and HTTPS enabled still starts`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        httpsEnabled = true,
                        tunnelProvider = TunnelProviderType.ARC_TUNNEL,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                every { mockArcProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(8080, listenerHost)

                verify { mockArcProvider.bind(listenerHost) }
                coVerify { mockArcProvider.start(8080, config) }
            }

        @Test
        fun `start with NGROK and HTTPS enabled does nothing`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        httpsEnabled = true,
                        tunnelProvider = TunnelProviderType.NGROK,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)

                createManager().start(8080, listenerHost)

                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
                coVerify(exactly = 0) { mockArcProvider.start(any(), any()) }
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
                manager.start(8080, listenerHost)

                // Give the relay coroutine (on Dispatchers.IO) time to start collecting
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)

                // Simulate the provider reporting Connected
                providerStatus.value =
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                        providerType = TunnelProviderType.CLOUDFLARE,
                    )

                // Give the relay time to propagate
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)

                val status = manager.tunnelStatus.value
                assertEquals(
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
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
                manager.start(8080, listenerHost)
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

        @Test
        fun `stop logs Tunnel stopped once when tunnel active`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                    )
                every { mockSettingsRepository.serverConfig } returns flowOf(config)
                val providerStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
                every { mockCloudflareProvider.status } returns providerStatus
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs
                coEvery { mockCloudflareProvider.stop() } just Runs

                val manager = createManager()
                manager.start(8080, listenerHost)
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)
                providerStatus.value =
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                        providerType = TunnelProviderType.CLOUDFLARE,
                    )
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)
                manager.stop()

                val tunnelEntries = serverLog.ofType(ServerLogEntry.Type.TUNNEL)
                assertEquals(1, tunnelEntries.size)
                assertEquals("Tunnel stopped", tunnelEntries.first().message)
            }

        @Test
        fun `stop logs nothing when already disconnected`() =
            runTest {
                val manager = createManager()

                manager.stop()

                assertEquals(0, serverLog.ofType(ServerLogEntry.Type.TUNNEL).size)
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
