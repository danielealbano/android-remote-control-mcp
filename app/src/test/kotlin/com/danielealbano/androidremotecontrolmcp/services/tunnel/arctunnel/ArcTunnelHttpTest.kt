package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("ArcTunnelHttp")
class ArcTunnelHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var net: ArcTunnelNet

    @BeforeEach
    fun setUp() {
        server = ArcTunnelTestServer.start()
        net = ArcTunnelTestServer.net(server.port)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private suspend fun getNonce(client: OkHttpClient = net.serverClient()): ArcTunnelNonceResponse =
        ArcTunnelHttp.get(
            ArcTunnelNonceResponse.serializer(),
            client,
            net.url(ArcTunnelTestServer.ENROLL_HOST, "/api/v1/enroll/nonce"),
        )

    /** Counts down [headersRead] once the client has the response headers and [canceled] once its call is cancelled. */
    private fun observed(
        headersRead: CountDownLatch = CountDownLatch(1),
        canceled: CountDownLatch = CountDownLatch(1),
    ): OkHttpClient =
        net
            .serverClient()
            .newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun responseHeadersEnd(
                        call: Call,
                        response: Response,
                    ) {
                        headersRead.countDown()
                    }

                    override fun canceled(call: Call) {
                        canceled.countDown()
                    }
                },
            ).build()

    private fun status(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build()

    @Test
    fun `get decodes json`() =
        runBlocking {
            server.enqueue(ArcTunnelTestServer.json("""{"nonce":"ab"}"""))

            assertEquals("ab", getNonce().nonce)
        }

    @Test
    fun `non-200 throws with body fields`() {
        server.enqueue(status(503, """{"reason":"x","retryable":true,"retry_after_seconds":7}"""))

        val error = assertThrows<ArcTunnelApiException> { runBlocking { getNonce() } }

        assertEquals("x", error.reason)
        assertTrue(error.retryable)
        assertEquals(7, error.retryAfterSec)
        assertEquals(503, error.status)
    }

    @Test
    fun `non-json error body maps to http_status`() {
        server.enqueue(status(500, "oops"))

        val error = assertThrows<ArcTunnelApiException> { runBlocking { getNonce() } }

        assertEquals("http_500", error.reason)
        assertFalse(error.retryable)
    }

    @Test
    fun `enrollClient and issueClient set a per-read timeout and no total-call timeout`() {
        val enroll = ArcTunnelHttp.enrollClient(net.serverClient())
        val issue = ArcTunnelHttp.issueClient(net.serverClient())

        assertEquals(300_000, enroll.readTimeoutMillis)
        assertEquals(0, enroll.callTimeoutMillis)
        assertEquals(400_000, issue.readTimeoutMillis)
        assertEquals(0, issue.callTimeoutMillis)
    }

    @Test
    fun `an unexpected exception reaches the caller`() {
        val failing =
            net
                .serverClient()
                .newBuilder()
                .addInterceptor { throw IllegalStateException("boom") }
                .build()
        val url = net.url(ArcTunnelTestServer.ENROLL_HOST, "/api/v1/enroll/nonce")

        val error =
            assertThrows<IllegalStateException> {
                runBlocking { ArcTunnelHttp.get(ArcTunnelNonceResponse.serializer(), failing, url) }
            }

        assertEquals("boom", error.message)
    }

    @Test
    fun `cancelling the coroutine cancels the call`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    release.await(BLOCK_S, TimeUnit.SECONDS)
                    return ArcTunnelTestServer.json("""{"nonce":"late"}""")
                }
            }
        val canceled = CountDownLatch(1)
        val client = observed(canceled = canceled)
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) { getNonce(client) }
                assertTrue(arrived.await(BLOCK_S, TimeUnit.SECONDS), "the request never reached the server")

                job.cancel()
                withTimeout(CANCEL_BUDGET_MS) { job.join() }

                assertTrue(job.isCancelled)
                assertTrue(canceled.await(CANCEL_BUDGET_MS, TimeUnit.MILLISECONDS), "the call was not cancelled")
                assertEquals(0, client.dispatcher.runningCallsCount())
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancelling the coroutine aborts a stalled body read`() {
        val streamEnded = CountDownLatch(1)
        server.enqueue(
            ArcTunnelTestServer.answer(200) { socket ->
                try {
                    // Headers are out; the body trickles until the client resets the stream (the write fails).
                    val sink = socket.sink.buffer()
                    sink.writeUtf8("{")
                    sink.flush()
                    repeat(TRICKLE_STEPS) {
                        Thread.sleep(TRICKLE_MS)
                        sink.writeUtf8(" ")
                        sink.flush()
                    }
                } catch (e: IOException) {
                    System.err.println("stalled body stream ended: $e")
                } finally {
                    streamEnded.countDown()
                }
            },
        )
        val headersRead = CountDownLatch(1)
        val client = observed(headersRead = headersRead)
        runBlocking {
            val job = launch(Dispatchers.IO) { getNonce(client) }
            assertTrue(headersRead.await(BLOCK_S, TimeUnit.SECONDS), "the response headers never arrived")

            job.cancel()
            withTimeout(CANCEL_BUDGET_MS) { job.join() }

            assertTrue(job.isCancelled)
        }
        assertTrue(streamEnded.await(CANCEL_BUDGET_MS, TimeUnit.MILLISECONDS), "the server stream was not aborted")
    }

    @Test
    fun `malformed 200 body maps to malformed_response`() {
        server.enqueue(ArcTunnelTestServer.json("{"))

        val error = assertThrows<ArcTunnelApiException> { runBlocking { getNonce() } }

        assertEquals("malformed_response", error.reason)
        assertFalse(error.retryable)
        assertEquals(0, error.retryAfterSec)
        assertEquals(200, error.status)
        assertFalse(error.message.orEmpty().contains("{"))
    }

    private companion object {
        const val BLOCK_S = 30L
        const val CANCEL_BUDGET_MS = 1_000L
        const val TRICKLE_STEPS = 300
        const val TRICKLE_MS = 100L
    }
}
