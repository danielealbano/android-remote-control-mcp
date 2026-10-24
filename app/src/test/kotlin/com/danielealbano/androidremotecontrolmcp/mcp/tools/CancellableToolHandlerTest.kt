package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.InFlightToolCalls
import com.danielealbano.androidremotecontrolmcp.mcp.McpCaller
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.CoroutineContext

@DisplayName("cancellableToolHandler")
class CancellableToolHandlerTest {
    private val request = mockk<CallToolRequest>()
    private val calls = InFlightToolCalls()
    private val caller = McpCaller.of("Bearer client-token")
    private val requestId = RequestId(42L)
    private val currentRequestId: suspend () -> RequestId? = { requestId }
    private val ok = CallToolResult(content = listOf(TextContent(text = "ok")))

    /** A handler that runs until cancelled, reporting when it started and whether it was cancelled. */
    private class Blocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        val handler: suspend (CallToolRequest) -> CallToolResult = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
    }

    @Test
    fun `a notifications cancelled for the call stops it and answers with an error result`() =
        runTest {
            val blocking = Blocking()
            val handler = cancellableToolHandler(calls, currentRequestId, blocking.handler)

            val result = async { withContext(caller) { handler(request) } }
            blocking.started.await()
            assertEquals(InFlightToolCalls.Outcome.CANCELLED, calls.cancel(caller.id, requestId, "user stop"))

            val answer = result.await()
            assertEquals(true, answer.isError)
            assertEquals(CANCELLED_BY_CLIENT_MESSAGE, (answer.content.single() as TextContent).text)
            assertTrue(blocking.cancelled.isCompleted)
        }

    @Test
    fun `a tool turning the cancellation into an error of its own still answers with the cancelled result`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val handler =
                cancellableToolHandler(calls, currentRequestId) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        throw McpToolException.ActionFailed("Failed to act: ${e.message}", e)
                    }
                }

            val result = async { withContext(caller) { handler(request) } }
            started.await()
            calls.cancel(caller.id, requestId, "user stop")

            val answer = result.await()
            assertEquals(true, answer.isError)
            assertEquals(CANCELLED_BY_CLIENT_MESSAGE, (answer.content.single() as TextContent).text)
        }

    @Test
    fun `a second cancellation of a call already being cancelled changes nothing`() =
        runTest {
            val blocking = Blocking()
            val handler = cancellableToolHandler(calls, currentRequestId, blocking.handler)

            val result = async { withContext(caller) { handler(request) } }
            blocking.started.await()
            assertEquals(InFlightToolCalls.Outcome.CANCELLED, calls.cancel(caller.id, requestId, "first"))
            // Still tracked: its tool has not unwound yet
            assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel(caller.id, requestId, "again"))

            assertEquals(CANCELLED_BY_CLIENT_RESULT, result.await())
            assertTrue(blocking.cancelled.isCompleted)
        }

    @Test
    fun `a cancelled request propagates the cancellation instead of answering`() =
        runTest {
            val blocking = Blocking()
            val handler = cancellableToolHandler(calls, currentRequestId, blocking.handler)
            var outcome: Throwable? = null

            val call =
                launch {
                    try {
                        withContext(caller) { handler(request) }
                    } catch (e: CancellationException) {
                        outcome = e
                        throw e
                    }
                }
            blocking.started.await()
            call.cancelAndJoin()

            assertTrue(outcome is CancellationException, "$outcome")
            assertTrue(blocking.cancelled.isCompleted)
        }

    /** Runs every dispatched coroutine at once, inside the call that dispatches it. */
    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = block.run()
    }

    @Test
    fun `the call is tracked before the tool runs`() =
        runTest {
            var outcome: InFlightToolCalls.Outcome? = null
            val handler =
                cancellableToolHandler(calls, currentRequestId) {
                    outcome = calls.cancel(caller.id, requestId, "at once")
                    ok
                }

            // A coroutine started on this dispatcher runs the tool before the code that started it goes on
            val answer = withContext(ImmediateDispatcher + caller) { handler(request) }

            assertEquals(InFlightToolCalls.Outcome.CANCELLED, outcome)
            assertEquals(CANCELLED_BY_CLIENT_RESULT, answer)
        }

    @Test
    fun `a cancellation arriving once the tool has ended keeps the tool's outcome`() =
        runTest {
            val spied = spyk(InFlightToolCalls())
            val cancel = slot<(String?) -> Boolean>()
            val lateCancelTookEffect = mutableListOf<Boolean>()
            // The cancellation lands as the call stops being tracked, after its tool ended
            every { spied.track(any(), any(), capture(cancel)) } answers {
                val untrack = callOriginal()
                val cancelThenUntrack: () -> Unit = {
                    lateCancelTookEffect += cancel.captured("late")
                    untrack()
                }
                cancelThenUntrack
            }
            val failing =
                cancellableToolHandler(spied, currentRequestId) { throw McpToolException.ActionFailed("boom") }
            val succeeding = cancellableToolHandler(spied, currentRequestId) { ok }

            assertThrows<McpToolException.ActionFailed> { withContext(caller) { failing(request) } }
            assertEquals(ok, withContext(caller) { succeeding(request) })
            assertEquals(listOf(false, false), lateCancelTookEffect)
        }

    @Test
    fun `the call is tracked only while it runs`() =
        runTest {
            val handler = cancellableToolHandler(calls, currentRequestId) { ok }

            assertEquals(ok, withContext(caller) { handler(request) })

            assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel(caller.id, requestId, null))
        }

    @Test
    fun `a failure of the tool propagates unchanged and stops the tracking`() =
        runTest {
            val handler =
                cancellableToolHandler(calls, currentRequestId) { throw McpToolException.ActionFailed("boom") }

            val e = assertThrows<McpToolException.ActionFailed> { withContext(caller) { handler(request) } }

            assertEquals("boom", e.message)
            assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel(caller.id, requestId, null))
        }

    @Test
    fun `outside the MCP transport (no caller or no request id) the handler just runs`() =
        runTest {
            val withoutRequestId = cancellableToolHandler(calls) { ok }
            val withRequestId = cancellableToolHandler(calls, currentRequestId) { ok }

            // No request is being handled, so the SDK context holds no request id
            assertEquals(ok, withContext(caller) { withoutRequestId(request) })
            assertEquals(ok, withRequestId(request))
        }
}
