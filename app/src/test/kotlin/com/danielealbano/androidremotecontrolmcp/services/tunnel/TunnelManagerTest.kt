package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TunnelManager")
class TunnelManagerTest {
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
                every { mockCloudflareProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(config, listenerHost)

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
                every { mockNgrokProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockNgrokProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(config, listenerHost)

                coVerify { mockNgrokProvider.start(8080, config) }
            }

        @Test
        fun `start with tunnel disabled is no-op`() =
            runTest {
                val config = ServerConfig(tunnelEnabled = false)

                val manager = createManager()
                manager.start(config, listenerHost)

                coVerify(exactly = 0) { mockCloudflareProvider.start(any(), any()) }
                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
            }

        @Test
        fun `start with HTTPS enabled starts Cloudflare on the tunnel local port`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        httpsEnabled = true,
                        tunnelProvider = TunnelProviderType.CLOUDFLARE,
                        tunnelLocalPort = 49200,
                    )
                every { mockCloudflareProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(config, listenerHost)

                coVerify { mockCloudflareProvider.start(49200, config) }
                coVerify(exactly = 0) { mockNgrokProvider.start(any(), any()) }
            }

        @Test
        fun `start with ARC_TUNNEL binds the listener host and starts the provider`() =
            runTest {
                val config = ServerConfig(tunnelEnabled = true, tunnelProvider = TunnelProviderType.ARC_TUNNEL)
                every { mockArcProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(config, listenerHost)

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
                every { mockArcProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(config, listenerHost)

                verify { mockArcProvider.bind(listenerHost) }
                coVerify { mockArcProvider.start(8080, config) }
            }

        @Test
        fun `start with NGROK and HTTPS enabled starts ngrok on the tunnel local port`() =
            runTest {
                val config =
                    ServerConfig(
                        tunnelEnabled = true,
                        httpsEnabled = true,
                        tunnelProvider = TunnelProviderType.NGROK,
                        tunnelLocalPort = 49200,
                    )
                every { mockNgrokProvider.status } returns MutableStateFlow(TunnelStatus.Connecting)

                createManager().start(config, listenerHost)

                coVerify { mockNgrokProvider.start(49200, config) }
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
                every { mockCloudflareProvider.status } returns providerStatus
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs

                val manager = createManager()
                manager.start(config, listenerHost)

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
                every { mockCloudflareProvider.status } returns
                    MutableStateFlow(TunnelStatus.Disconnected)
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs
                coEvery { mockCloudflareProvider.stop() } just Runs

                val manager = createManager()
                manager.start(config, listenerHost)
                manager.stop()

                coVerify { mockCloudflareProvider.stop() }
                assertEquals(TunnelStatus.Disconnected, manager.tunnelStatus.value)
            }

        @Test
        fun `a stop cut short by a timeout still shows the tunnel as gone`() =
            runTest {
                val providerStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
                val config = ServerConfig(tunnelEnabled = true, tunnelProvider = TunnelProviderType.CLOUDFLARE)
                every { mockCloudflareProvider.status } returns providerStatus
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs
                coEvery { mockCloudflareProvider.stop() } coAnswers { awaitCancellation() }
                val manager = createManager()
                manager.start(config, listenerHost)
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)
                val connected =
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint("https://test.trycloudflare.com", valid = true)),
                        providerType = TunnelProviderType.CLOUDFLARE,
                    )
                providerStatus.value = connected
                Thread.sleep(RELAY_PROPAGATION_DELAY_MS)
                assertEquals(connected, manager.tunnelStatus.value)

                assertNull(withTimeoutOrNull(STOP_TIMEOUT_MS) { manager.stop() })

                assertEquals(TunnelStatus.Disconnected, manager.tunnelStatus.value)
                // The provider was let go: a later stop does not wait on it again.
                manager.stop()
                coVerify(exactly = 1) { mockCloudflareProvider.stop() }
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
                val providerStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
                every { mockCloudflareProvider.status } returns providerStatus
                coEvery { mockCloudflareProvider.start(8080, config) } just Runs
                coEvery { mockCloudflareProvider.stop() } just Runs

                val manager = createManager()
                manager.start(config, listenerHost)
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
        private const val STOP_TIMEOUT_MS = 3_000L
    }
}
