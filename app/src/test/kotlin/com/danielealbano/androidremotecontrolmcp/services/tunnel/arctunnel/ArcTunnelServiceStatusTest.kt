package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit

@DisplayName("Tunnel service status")
class ArcTunnelServiceStatusTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun page(
        state: String = """"degraded"""",
        included: String = """[${resource("Tunnels", "degraded")},${resource("Database", "operational")}]""",
    ) = """{"data":{"type":"status_page","attributes":{"aggregate_state":$state}},"included":$included}"""

    private fun resource(
        name: String,
        status: String,
    ) = """{"type":"status_page_resource","attributes":{"public_name":"$name","status":"$status"}}"""

    private var nowMs = START_MS

    private fun status() = ArcTunnelServiceStatusImpl(server.url("/index.json").toString(), Dispatchers.IO) { nowMs }

    @Test
    fun `it reads the public status page`() {
        assertEquals("https://status.arc-tunnel.io/", ArcTunnelStatusPage.PAGE_URL)
        assertEquals("https://status.arc-tunnel.io/index.json", ArcTunnelStatusPage.JSON_URL)
    }

    @Test
    fun `operational is no problem, even if one service says otherwise`() {
        assertNull(ArcTunnelStatusPage.parseProblem(page(""""operational"""")))
    }

    @Test
    fun `any other state is a problem, with the services not operational`() {
        val problem = ArcTunnelStatusPage.parseProblem(page())

        assertEquals(ArcTunnelServiceProblem("degraded", listOf("Tunnels")), problem)
    }

    @Test
    fun `a state not seen before is still a problem, shown readably`() {
        assertEquals("partial outage", ArcTunnelStatusPage.parseProblem(page(""""partial_outage"""", "[]"))?.state)
    }

    @Test
    fun `a service that is not monitored is not listed as affected`() {
        val included = """[${resource("Tunnels", "downtime")},${resource("Fleet", "not_monitored")}]"""

        assertEquals(listOf("Tunnels"), ArcTunnelStatusPage.parseProblem(page(included = included))?.affected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not json",
            "[]",
            "null",
            "<html>maintenance</html>",
            "{}",
            """{"data":null}""",
            """{"data":[]}""",
            """{"data":{}}""",
            """{"data":{"attributes":null}}""",
            """{"data":{"attributes":{}}}""",
            """{"data":{"attributes":{"aggregate_state":null}}}""",
            """{"data":{"attributes":{"aggregate_state":""}}}""",
            """{"data":{"attributes":{"aggregate_state":"  "}}}""",
            """{"data":{"attributes":{"aggregate_state":5}}}""",
            """{"data":{"attributes":{"aggregate_state":true}}}""",
            """{"data":{"attributes":{"aggregate_state":{"a":1}}}}""",
            """{"aggregate_state":"degraded"}""",
        ],
    )
    fun `without a readable overall state nothing is reported`(body: String) {
        assertNull(ArcTunnelStatusPage.parseProblem(body))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "null",
            "{}",
            "5",
            "[5,null,{}]",
            """[{"type":"status_page_resource"}]""",
            """[{"type":"status_page_resource","attributes":null}]""",
            """[{"type":"status_page_resource","attributes":{"status":"degraded"}}]""",
            """[{"type":"status_page_resource","attributes":{"public_name":"Tunnels"}}]""",
            """[{"type":"status_page_resource","attributes":{"public_name":5,"status":"degraded"}}]""",
            """[{"type":"other","attributes":{"public_name":"Tunnels","status":"degraded"}}]""",
        ],
    )
    fun `details that cannot be read lose only the details`(included: String) {
        val problem = ArcTunnelStatusPage.parseProblem(page(included = included))

        assertEquals(ArcTunnelServiceProblem("degraded", emptyList()), problem)
    }

    @Test
    fun `a missing list of services loses only the details`() {
        val body = """{"data":{"attributes":{"aggregate_state":"degraded"}}}"""

        assertEquals(ArcTunnelServiceProblem("degraded", emptyList()), ArcTunnelStatusPage.parseProblem(body))
    }

    @Test
    fun `the page is read as JSON whatever content type it is served with`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(page())
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .build(),
            )

            assertEquals(ArcTunnelServiceProblem("degraded", listOf("Tunnels")), status().currentProblem())
        }

    @Test
    fun `an HTTP error reports nothing`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(HTTP_ERROR)
                    .body(page())
                    .build(),
            )

            assertNull(status().currentProblem())
        }

    @Test
    fun `a server that cannot be reached reports nothing`() =
        runBlocking {
            val status = status()
            server.close()

            assertNull(status.currentProblem())
        }

    @Test
    fun `a malformed URL reports nothing`() =
        runBlocking {
            assertNull(ArcTunnelServiceStatusImpl("not a url", Dispatchers.IO) { nowMs }.currentProblem())
        }

    @Test
    fun `a body too large to read reports nothing`() =
        runBlocking {
            val tooLong = page().replace("degraded", "degraded" + " ".repeat(TOO_LARGE))
            server.enqueue(MockResponse.Builder().body(tooLong).build())

            assertNull(status().currentProblem())
        }

    @Test
    fun `the page is asked once per interval, however often the answer is wanted`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().body(page()).build())
            val status = status()

            repeat(3) { assertEquals("degraded", status.currentProblem()?.state) }
            nowMs += ArcTunnelStatusPage.CHECK_INTERVAL_MS - 1
            assertEquals("degraded", status.currentProblem()?.state)

            assertEquals(1, server.requestCount)
        }

    @Test
    fun `the page is asked again once the interval has passed`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().body(page()).build())
            server.enqueue(MockResponse.Builder().body(page(""""operational"""")).build())
            val status = status()
            assertEquals("degraded", status.currentProblem()?.state)

            nowMs += ArcTunnelStatusPage.CHECK_INTERVAL_MS

            assertNull(status.currentProblem())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a failed check also waits for the interval, so a broken page is not hammered`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(HTTP_ERROR).build())
            val status = status()

            repeat(3) { assertNull(status.currentProblem()) }

            assertEquals(1, server.requestCount)
        }

    @Test
    fun `callers at the same time make one request and get the same answer`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(page())
                    .headersDelay(SLOW_RESPONSE_MS, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val status = status()

            val answers = List(CALLERS) { async(Dispatchers.IO) { status.currentProblem() } }.awaitAll()

            assertEquals(List(CALLERS) { ArcTunnelServiceProblem("degraded", listOf("Tunnels")) }, answers)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `cancelling ends the request at once, and it still counts towards the limit`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(page())
                    .headersDelay(NEVER_ANSWERS_S, TimeUnit.SECONDS)
                    .build(),
            )
            val status = status()
            val job = launch(Dispatchers.IO) { status.currentProblem() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(REQUEST_BUDGET_S, TimeUnit.SECONDS)) }

            withTimeout(CANCEL_BUDGET_MS) { job.cancelAndJoin() }

            assertTrue(job.isCancelled)
            assertNull(status.currentProblem())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a cancelled new request keeps the last answer until the next one is due`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().body(page()).build())
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(page(""""operational""""))
                    .headersDelay(NEVER_ANSWERS_S, TimeUnit.SECONDS)
                    .build(),
            )
            val status = status()
            val degraded = ArcTunnelServiceProblem("degraded", listOf("Tunnels"))
            assertEquals(degraded, status.currentProblem())
            nowMs += ArcTunnelStatusPage.CHECK_INTERVAL_MS
            val job = launch(Dispatchers.IO) { status.currentProblem() }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(REQUEST_BUDGET_S, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(REQUEST_BUDGET_S, TimeUnit.SECONDS))
            }

            withTimeout(CANCEL_BUDGET_MS) { job.cancelAndJoin() }

            assertEquals(degraded, status.currentProblem())
            assertEquals(2, server.requestCount)
        }

    private companion object {
        const val START_MS = 1_000L
        const val CALLERS = 4
        const val SLOW_RESPONSE_MS = 200L
        const val NEVER_ANSWERS_S = 5L
        const val CANCEL_BUDGET_MS = 2_000L
        const val REQUEST_BUDGET_S = 5L
        const val HTTP_ERROR = 503
        const val TOO_LARGE = 1_100_000
    }
}
