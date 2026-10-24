package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.tools.CANCELLED_BY_CLIENT_MESSAGE
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Tool call cancellation over MCP HTTP")
class ToolCallCancellationIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    /** A device operation that runs until cancelled, exposing its job and whether it was cancelled. */
    private class BlockingOperation {
        val job = CompletableDeferred<Job>()
        val cancelled = CompletableDeferred<Unit>()

        suspend fun run(): Nothing {
            job.complete(currentCoroutineContext().job)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
    }

    /**
     * Mock dependencies whose registry reports the outcome of every cancellation once it has processed it: the SDK
     * answers a notification with 202 before handling it, so the 202 alone does not tell that it was handled.
     */
    private class Recorded {
        val outcomes = Channel<InFlightToolCalls.Outcome>(Channel.UNLIMITED)
        val deps =
            McpIntegrationTestHelper.createMockDependencies().copy(
                inFlightToolCalls =
                    spyk(InFlightToolCalls()).also { calls ->
                        every { calls.cancel(any(), any(), any()) } answers {
                            callOriginal().also { outcomes.trySend(it) }
                        }
                    },
            )
    }

    /** A tap that runs until cancelled. */
    private fun blockingTap(deps: MockDependencies) =
        BlockingOperation().also { tap -> coEvery { deps.actionExecutor.tap(any(), any()) } coAnswers { tap.run() } }

    private fun toolsCall(
        id: Int,
        name: String = "android_tap",
        arguments: String = """{"x":10,"y":20}""",
    ) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""

    private fun cancelled(id: Int) =
        """{"jsonrpc":"2.0","method":"notifications/cancelled",""" +
            """"params":{"requestId":$id,"reason":"test"}}"""

    private suspend fun ApplicationTestBuilder.postMcp(
        body: String,
        token: String,
    ): HttpResponse =
        client.post("/mcp") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `notifications cancelled from the client stops its running tool call`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val tap = blockingTap(deps)

            McpIntegrationTestHelper.withRawTestApplication(deps) { _ ->
                coroutineScope {
                    val call = async { postMcp(toolsCall(7), McpIntegrationTestHelper.TEST_BEARER_TOKEN) }
                    tap.job.await()

                    val cancel = postMcp(cancelled(7), McpIntegrationTestHelper.TEST_BEARER_TOKEN)

                    assertEquals(HttpStatusCode.Accepted, cancel.status)
                    val response = call.await()
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertTrue(body.contains(CANCELLED_BY_CLIENT_MESSAGE), body)
                    assertTrue(body.contains("\"isError\":true"), body)
                    assertTrue(tap.cancelled.isCompleted)
                    val logged = deps.serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).single { it.toolName == "tap" }
                    assertEquals("cancelled", logged.message)
                }
            }
        }

    @Test
    fun `a call in a batch that initializes the session can be cancelled too`() =
        runTest {
            val recorded = Recorded()
            val tap = blockingTap(recorded.deps)
            // notifications/initialized makes the session launch the call in its own scope instead of inline
            val batch = """[{"jsonrpc":"2.0","method":"notifications/initialized"},${toolsCall(7)}]"""

            McpIntegrationTestHelper.withRawTestApplication(recorded.deps) { _ ->
                coroutineScope {
                    val token = McpIntegrationTestHelper.TEST_BEARER_TOKEN
                    val call = async { postMcp(batch, token) }
                    tap.job.await()

                    postMcp(cancelled(7), token)

                    assertEquals(InFlightToolCalls.Outcome.CANCELLED, recorded.outcomes.receive())
                    val body = call.await().bodyAsText()
                    assertTrue(body.contains(CANCELLED_BY_CLIENT_MESSAGE), body)
                }
            }
        }

    @Test
    fun `a tool turning the cancellation into an error of its own still answers with the cancelled result`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            // list_files turns any exception of the listing into a "Failed to list files" error
            val listing = BlockingOperation()
            coEvery { deps.fileOperationProvider.listFiles(any(), any(), any(), any()) } coAnswers { listing.run() }

            McpIntegrationTestHelper.withRawTestApplication(deps) { _ ->
                coroutineScope {
                    val token = McpIntegrationTestHelper.TEST_BEARER_TOKEN
                    val listFiles = toolsCall(7, "android_list_files", """{"location_id":"loc1"}""")
                    val call = async { postMcp(listFiles, token) }
                    listing.job.await()

                    postMcp(cancelled(7), token)

                    val body = call.await().bodyAsText()
                    assertTrue(body.contains(CANCELLED_BY_CLIENT_MESSAGE), body)
                    assertTrue(listing.cancelled.isCompleted)
                }
            }
        }

    @Test
    fun `another client cannot cancel the call, its own client can`() =
        runTest {
            val recorded = Recorded()
            val tap = blockingTap(recorded.deps)

            // Authentication off, so two different credentials both reach the transport as two callers
            McpIntegrationTestHelper.withRawTestApplication(recorded.deps, bearerTokenEnabled = false) { _ ->
                coroutineScope {
                    val call = async { postMcp(toolsCall(7), "client-a") }
                    val toolJob = tap.job.await()

                    postMcp(cancelled(7), "client-b")

                    assertEquals(InFlightToolCalls.Outcome.UNKNOWN, recorded.outcomes.receive())
                    assertFalse(toolJob.isCancelled)

                    postMcp(cancelled(7), "client-a")

                    assertEquals(InFlightToolCalls.Outcome.CANCELLED, recorded.outcomes.receive())
                    assertTrue(call.await().bodyAsText().contains(CANCELLED_BY_CLIENT_MESSAGE))
                }
            }
        }

    @Test
    fun `two running calls of the client with the same id are ambiguous and neither is cancelled`() =
        runTest {
            val recorded = Recorded()
            val gate = CompletableDeferred<Unit>()
            val jobs = Channel<Job>(Channel.UNLIMITED)
            coEvery { recorded.deps.actionExecutor.tap(any(), any()) } coAnswers {
                jobs.send(currentCoroutineContext().job)
                gate.await()
                Result.success(Unit)
            }

            McpIntegrationTestHelper.withRawTestApplication(recorded.deps) { _ ->
                coroutineScope {
                    val token = McpIntegrationTestHelper.TEST_BEARER_TOKEN
                    val calls = List(2) { async { postMcp(toolsCall(7), token) } }
                    val toolJobs = List(2) { jobs.receive() }

                    val cancel = postMcp(cancelled(7), token)

                    assertEquals(HttpStatusCode.Accepted, cancel.status)
                    assertEquals(InFlightToolCalls.Outcome.AMBIGUOUS, recorded.outcomes.receive())
                    assertTrue(toolJobs.none { it.isCancelled })
                    // Logged by the server's thread right after the outcome reached this test
                    verify(timeout = LOG_WAIT_MS) { android.util.Log.w("MCP:StatelessTransport", any<String>()) }
                    gate.complete(Unit)
                    for (call in calls) assertTrue(call.await().bodyAsText().contains("Tap executed"))
                }
            }
        }

    @Test
    fun `a cancellation for an id nobody is running changes nothing`() =
        runTest {
            val recorded = Recorded()

            McpIntegrationTestHelper.withRawTestApplication(recorded.deps) { _ ->
                val cancel = postMcp(cancelled(99), McpIntegrationTestHelper.TEST_BEARER_TOKEN)

                assertEquals(HttpStatusCode.Accepted, cancel.status)
                assertEquals(InFlightToolCalls.Outcome.UNKNOWN, recorded.outcomes.receive())
            }
        }

    @Test
    fun `GET and DELETE are refused with 405 and Allow POST`() =
        runTest {
            McpIntegrationTestHelper.withRawTestApplication { _ ->
                val auth = "Bearer ${McpIntegrationTestHelper.TEST_BEARER_TOKEN}"
                for (response in listOf(
                    client.get("/mcp") { header(HttpHeaders.Authorization, auth) },
                    client.delete("/mcp") { header(HttpHeaders.Authorization, auth) },
                )) {
                    assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
                    assertEquals("POST", response.headers[HttpHeaders.Allow])
                    assertTrue(response.bodyAsText().contains("Method not allowed."), response.bodyAsText())
                }
            }
        }

    private companion object {
        const val LOG_WAIT_MS = 5_000L
    }
}
