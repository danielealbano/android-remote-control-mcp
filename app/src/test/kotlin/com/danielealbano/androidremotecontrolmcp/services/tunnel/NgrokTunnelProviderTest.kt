package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.ngrok.Forwarder
import com.ngrok.HttpBuilder
import com.ngrok.Session
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URL

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NgrokTunnelProvider")
class NgrokTunnelProviderTest {
    private fun createProvider(): NgrokTunnelProvider = NgrokTunnelProvider()

    @BeforeEach
    fun setUp() {
        mockkObject(NgrokTunnelProvider.Companion)
        every { NgrokTunnelProvider.isSupportedAbi() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("start")
    inner class Start {
        @Test
        fun `start with empty authtoken sets error status`() =
            runTest {
                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals(
                    "ngrok authtoken is required",
                    (status as TunnelStatus.Error).message,
                )
            }

        @Test
        fun `start on unsupported ABI sets error status with helpful message`() =
            runTest {
                every { NgrokTunnelProvider.isSupportedAbi() } returns false
                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "test-token")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue(
                    (status as TunnelStatus.Error).message.contains("not supported"),
                )
                assertTrue(status.message.contains("Cloudflare"))
            }

        @Test
        fun `start with valid authtoken connects and returns URL`() =
            runTest {
                val mockForwarder =
                    mockk<Forwarder.Endpoint> {
                        every { url } returns "https://test-123.ngrok-free.app"
                        every { close() } just Runs
                    }
                val mockHttpBuilder =
                    mockk<HttpBuilder> {
                        every { forward(any<URL>()) } returns mockForwarder
                    }
                val mockSession =
                    mockk<Session> {
                        every { httpEndpoint() } returns mockHttpBuilder
                        every { close() } just Runs
                    }
                val mockBuilder =
                    mockk<Session.Builder> {
                        every { metadata(any()) } returns this
                        every { connect() } returns mockSession
                    }

                mockkStatic(Session::class)
                every { Session.withAuthtoken("test-token") } returns mockBuilder

                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "test-token")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Connected)
                assertEquals(
                    "https://test-123.ngrok-free.app",
                    (status as TunnelStatus.Connected).url,
                )

                verify { mockHttpBuilder.forward(URL("http://localhost:8080")) }
            }

        @Test
        fun `start with domain passes domain to builder`() =
            runTest {
                val mockForwarder =
                    mockk<Forwarder.Endpoint> {
                        every { url } returns "https://my-app.ngrok.io"
                        every { close() } just Runs
                    }
                val mockHttpBuilder =
                    mockk<HttpBuilder> {
                        every { domain("my-app.ngrok.io") } returns this
                        every { forward(any<URL>()) } returns mockForwarder
                    }
                val mockSession =
                    mockk<Session> {
                        every { httpEndpoint() } returns mockHttpBuilder
                        every { close() } just Runs
                    }
                val mockBuilder =
                    mockk<Session.Builder> {
                        every { metadata(any()) } returns this
                        every { connect() } returns mockSession
                    }

                mockkStatic(Session::class)
                every { Session.withAuthtoken("test-token") } returns mockBuilder

                val provider = createProvider()
                val config =
                    ServerConfig(
                        ngrokAuthtoken = "test-token",
                        ngrokDomain = "my-app.ngrok.io",
                    )

                provider.start(8080, config)

                verify { mockHttpBuilder.domain("my-app.ngrok.io") }
            }

        @Test
        fun `start without domain omits domain from builder`() =
            runTest {
                val mockForwarder =
                    mockk<Forwarder.Endpoint> {
                        every { url } returns "https://random.ngrok-free.app"
                        every { close() } just Runs
                    }
                val mockHttpBuilder =
                    mockk<HttpBuilder> {
                        every { forward(any<URL>()) } returns mockForwarder
                    }
                val mockSession =
                    mockk<Session> {
                        every { httpEndpoint() } returns mockHttpBuilder
                        every { close() } just Runs
                    }
                val mockBuilder =
                    mockk<Session.Builder> {
                        every { metadata(any()) } returns this
                        every { connect() } returns mockSession
                    }

                mockkStatic(Session::class)
                every { Session.withAuthtoken("test-token") } returns mockBuilder

                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "test-token", ngrokDomain = "")

                provider.start(8080, config)

                verify(exactly = 0) { mockHttpBuilder.domain(any()) }
            }

        @Test
        fun `start with invalid authtoken sets error status`() =
            runTest {
                val mockBuilder =
                    mockk<Session.Builder> {
                        every { metadata(any()) } returns this
                        every { connect() } throws java.io.IOException("authentication failed")
                    }

                mockkStatic(Session::class)
                every { Session.withAuthtoken("invalid-token") } returns mockBuilder

                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "invalid-token")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue(
                    (status as TunnelStatus.Error).message.contains("Failed to start ngrok"),
                )
            }
    }

    @Nested
    @DisplayName("stop")
    inner class Stop {
        @Test
        fun `stop when not running is no-op`() =
            runTest {
                val provider = createProvider()

                provider.stop()

                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }

        @Test
        fun `stop closes forwarder and session`() =
            runTest {
                val mockForwarder =
                    mockk<Forwarder.Endpoint> {
                        every { url } returns "https://test.ngrok-free.app"
                        every { close() } just Runs
                    }
                val mockHttpBuilder =
                    mockk<HttpBuilder> {
                        every { forward(any<URL>()) } returns mockForwarder
                    }
                val mockSession =
                    mockk<Session> {
                        every { httpEndpoint() } returns mockHttpBuilder
                        every { close() } just Runs
                    }
                val mockBuilder =
                    mockk<Session.Builder> {
                        every { metadata(any()) } returns this
                        every { connect() } returns mockSession
                    }

                mockkStatic(Session::class)
                every { Session.withAuthtoken("test-token") } returns mockBuilder

                val provider = createProvider()
                val config = ServerConfig(ngrokAuthtoken = "test-token")

                provider.start(8080, config)
                provider.stop()

                verify { mockForwarder.close() }
                verify { mockSession.close() }
                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }
    }

    @Nested
    @DisplayName("status")
    inner class Status {
        @Test
        fun `initial status is Disconnected`() {
            val provider = createProvider()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }
    }
}
