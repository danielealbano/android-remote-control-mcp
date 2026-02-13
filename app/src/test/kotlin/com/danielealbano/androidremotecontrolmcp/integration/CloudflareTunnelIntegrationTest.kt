package com.danielealbano.androidremotecontrolmcp.integration

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.CloudflaredBinaryResolver
import com.danielealbano.androidremotecontrolmcp.services.tunnel.CloudflareTunnelProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Integration test that starts a real Cloudflare Quick Tunnel.
 *
 * Requires the `cloudflared` binary to be installed on the host machine.
 * This test FAILs (not skips) if the binary is not found.
 *
 * The test starts a simple standalone HTTP test server on a random local port,
 * creates a tunnel to it, and verifies the tunnel URL returns the expected
 * response.
 */
@DisplayName("CloudflareTunnelIntegrationTest")
@Timeout(value = TUNNEL_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class CloudflareTunnelIntegrationTest {
    private lateinit var provider: CloudflareTunnelProvider
    private var testServer: ServerSocket? = null
    private var testServerThread: Thread? = null

    @BeforeEach
    fun setUp() {
        val binaryPath =
            HostCloudflareBinaryResolver().resolve()
                ?: fail(
                    "cloudflared binary not found on host. " +
                        "Install it: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/",
                )

        provider =
            CloudflareTunnelProvider(
                binaryResolver =
                    object : CloudflaredBinaryResolver {
                        override fun resolve(): String = binaryPath
                    },
            )
    }

    @AfterEach
    fun tearDown() {
        // Stop the tunnel (best-effort)
        kotlinx.coroutines.runBlocking {
            try {
                provider.stop()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }

        // Stop the test server
        testServerThread?.interrupt()
        testServer?.close()
    }

    @Test
    fun `tunnel connects and proxies traffic to local test server`() =
        kotlinx.coroutines.runBlocking {
            // Start a simple HTTP test server on a random local port
            val server = ServerSocket(0)
            testServer = server
            val localPort = server.localPort
            testServerThread =
                Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val socket: Socket = server.accept()
                            handleTestRequest(socket)
                        } catch (_: java.net.SocketException) {
                            break
                        } catch (_: Exception) {
                            // Continue accepting
                        }
                    }
                }.also { it.isDaemon = true; it.start() }

            // Start the tunnel
            provider.start(localPort, ServerConfig())

            // Wait for the tunnel to report Connected status
            val connectedStatus =
                withTimeout(TUNNEL_CONNECT_TIMEOUT_MS) {
                    provider.status.first { it is TunnelStatus.Connected }
                }

            assertTrue(connectedStatus is TunnelStatus.Connected)
            val connected = connectedStatus as TunnelStatus.Connected
            assertTrue(connected.url.startsWith("https://"))
            assertTrue(connected.url.contains(".trycloudflare.com"))
            assertEquals(TunnelProviderType.CLOUDFLARE, connected.providerType)

            // Make an HTTP GET request through the tunnel
            val tunnelUrl = connected.url
            val response = fetchUrlWithRetry(tunnelUrl)
            assertTrue(
                response.contains(EXPECTED_RESPONSE_BODY),
                "Expected response to contain '$EXPECTED_RESPONSE_BODY' but got: $response",
            )

            // Stop the tunnel and verify status returns to Disconnected
            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }

    /**
     * Handles a single HTTP request on the test server.
     *
     * Reads the request (discarding it), then responds with a fixed body.
     * This is a minimal HTTP/1.1 responder — enough for cloudflared to proxy.
     */
    private fun handleTestRequest(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = PrintWriter(s.getOutputStream(), true)

            // Read the request line and headers (discard them)
            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }

            // Send an HTTP response
            val body = EXPECTED_RESPONSE_BODY
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: text/plain\r\n")
            writer.print("Content-Length: ${body.length}\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.print(body)
            writer.flush()
        }
    }

    /**
     * Fetches a URL with retries.
     *
     * Cloudflare Quick Tunnels can take a moment to become fully routable
     * after the URL is reported. Retries handle transient 502/503 responses.
     */
    private fun fetchUrlWithRetry(url: String): String {
        var lastException: Exception? = null
        repeat(FETCH_MAX_RETRIES) { attempt ->
            try {
                val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
                connection.readTimeout = FETCH_READ_TIMEOUT_MS
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode == HTTP_OK) {
                    return connection.inputStream.bufferedReader().readText()
                }

                // Retry on 502/503 (tunnel not yet fully routed)
                if (responseCode in RETRYABLE_HTTP_CODES) {
                    Thread.sleep(FETCH_RETRY_DELAY_MS * (attempt + 1))
                    return@repeat
                }

                fail<String>("Unexpected HTTP response code: $responseCode")
            } catch (e: Exception) {
                lastException = e
                Thread.sleep(FETCH_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        fail<String>(
            "Failed to fetch tunnel URL after $FETCH_MAX_RETRIES attempts: ${lastException?.message}",
        )
        // Unreachable but satisfies the compiler
        return ""
    }

    companion object {
        private const val EXPECTED_RESPONSE_BODY = "tunnel-test-ok"
        private const val FETCH_MAX_RETRIES = 5
        private const val FETCH_CONNECT_TIMEOUT_MS = 10_000
        private const val FETCH_READ_TIMEOUT_MS = 10_000
        private const val FETCH_RETRY_DELAY_MS = 2_000L
        private const val HTTP_OK = 200
        private val RETRYABLE_HTTP_CODES = listOf(502, 503)

        @JvmStatic
        @BeforeAll
        fun mockAndroidLog() {
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }

        @JvmStatic
        @AfterAll
        fun unmockAndroidLog() {
            unmockkStatic(Log::class)
        }
    }
}

/** Timeout for the entire test class (seconds). */
private const val TUNNEL_TEST_TIMEOUT_SECONDS = 90L

/** Timeout waiting for the tunnel to connect (milliseconds). */
private const val TUNNEL_CONNECT_TIMEOUT_MS = 60_000L

/**
 * Resolves the cloudflared binary from the host system's PATH.
 *
 * Used in JVM integration tests where the Android-specific
 * [com.danielealbano.androidremotecontrolmcp.services.tunnel.AndroidCloudflareBinaryResolver]
 * cannot be used.
 */
class HostCloudflareBinaryResolver : CloudflaredBinaryResolver {
    override fun resolve(): String? {
        return try {
            val result = ProcessBuilder("which", "cloudflared").start()
            val path = result.inputStream.bufferedReader().readText().trim()
            if (result.waitFor() == 0 && path.isNotEmpty()) path else null
        } catch (_: Exception) {
            null
        }
    }
}
