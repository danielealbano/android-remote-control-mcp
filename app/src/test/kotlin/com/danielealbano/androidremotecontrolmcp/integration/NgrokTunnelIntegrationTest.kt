package com.danielealbano.androidremotecontrolmcp.integration

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.NgrokTunnelProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Integration test that starts a real ngrok tunnel.
 *
 * Requires:
 * - The `NGROK_AUTHTOKEN` environment variable set with a valid authtoken.
 * - The ngrok native library for the host platform (linux-x86_64) on the test classpath.
 *
 * The test FAILs (not skips) when `NGROK_AUTHTOKEN` is not set.
 *
 * The test starts a simple standalone HTTP test server on a random local port,
 * creates an ngrok tunnel to it, and verifies the tunnel URL returns the expected
 * response.
 */
@DisplayName("NgrokTunnelIntegrationTest")
@Timeout(value = NGROK_TUNNEL_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class NgrokTunnelIntegrationTest {
    private lateinit var provider: NgrokTunnelProvider
    private var testServer: ServerSocket? = null
    private var testServerThread: Thread? = null

    @BeforeEach
    fun setUp() {
        provider = NgrokTunnelProvider()
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            @Suppress("TooGenericExceptionCaught")
            try {
                provider.stop()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }

        testServerThread?.interrupt()
        testServer?.close()
    }

    @Test
    fun `tunnel connects and proxies traffic to local test server`() =
        runBlocking {
            val server = ServerSocket(0)
            testServer = server
            val localPort = server.localPort
            testServerThread =
                Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            val socket: Socket = server.accept()
                            handleTestRequest(socket)
                        } catch (_: java.net.SocketException) {
                            break
                        } catch (_: Exception) {
                            // Continue accepting
                        }
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }

            val authtoken = System.getenv("NGROK_AUTHTOKEN")!!
            val config = ServerConfig(ngrokAuthtoken = authtoken)
            provider.start(localPort, config)

            // Fail fast if start() ended in error (e.g. native library issue)
            val statusAfterStart = provider.status.value
            if (statusAfterStart is TunnelStatus.Error) {
                fail<Unit>("ngrok provider.start() failed: ${statusAfterStart.message}")
            }

            val connectedStatus =
                withTimeout(NGROK_TUNNEL_CONNECT_TIMEOUT_MS) {
                    provider.status.first { it is TunnelStatus.Connected }
                }

            assertTrue(connectedStatus is TunnelStatus.Connected)
            val connected = connectedStatus as TunnelStatus.Connected
            assertTrue(connected.url.startsWith("https://"))
            assertEquals(TunnelProviderType.NGROK, connected.providerType)

            val response = fetchUrlWithRetry(connected.url)
            assertTrue(
                response.contains(EXPECTED_RESPONSE_BODY),
                "Expected response to contain '$EXPECTED_RESPONSE_BODY' but got: $response",
            )

            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }

    /**
     * Handles a single HTTP request on the test server.
     *
     * Reads the request (discarding it), then responds with a fixed body.
     * This is a minimal HTTP/1.1 responder — enough for ngrok to proxy.
     */
    private fun handleTestRequest(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = PrintWriter(s.getOutputStream(), true)

            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }

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
     * ngrok tunnels can take a moment to become fully routable after the
     * URL is reported. Retries handle transient 502/503 responses.
     */
    private fun fetchUrlWithRetry(url: String): String {
        var lastException: Exception? = null
        repeat(FETCH_MAX_RETRIES) { attempt ->
            @Suppress("TooGenericExceptionCaught")
            try {
                val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
                connection.readTimeout = FETCH_READ_TIMEOUT_MS
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode == HTTP_OK) {
                    return connection.inputStream.bufferedReader().readText()
                }

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
        @Suppress("UnreachableCode")
        return ""
    }

    companion object {
        private const val EXPECTED_RESPONSE_BODY = "ngrok-tunnel-test-ok"
        private const val FETCH_MAX_RETRIES = 5
        private const val FETCH_CONNECT_TIMEOUT_MS = 10_000
        private const val FETCH_READ_TIMEOUT_MS = 10_000
        private const val FETCH_RETRY_DELAY_MS = 2_000L
        private const val HTTP_OK = 200
        private val RETRYABLE_HTTP_CODES = listOf(502, 503)

        @JvmStatic
        @BeforeAll
        fun checkPrerequisitesAndMockAndroidApis() {
            val authtoken = System.getenv("NGROK_AUTHTOKEN")
            if (authtoken.isNullOrEmpty()) {
                fail<Unit>(
                    "NGROK_AUTHTOKEN environment variable is not set. " +
                        "Set it to a valid ngrok authtoken to run this integration test.",
                )
            }

            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0

            mockkObject(NgrokTunnelProvider.Companion)
            every { NgrokTunnelProvider.isSupportedAbi() } returns true
        }

        @JvmStatic
        @AfterAll
        fun unmockAndroidApis() {
            unmockkAll()
        }
    }
}

/** Timeout for the entire test class (seconds). */
private const val NGROK_TUNNEL_TEST_TIMEOUT_SECONDS = 90L

/** Timeout waiting for the tunnel to connect (milliseconds). */
private const val NGROK_TUNNEL_CONNECT_TIMEOUT_MS = 60_000L
