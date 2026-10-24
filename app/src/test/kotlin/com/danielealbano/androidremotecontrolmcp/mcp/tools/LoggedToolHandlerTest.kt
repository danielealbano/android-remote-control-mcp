package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("loggedToolHandler")
class LoggedToolHandlerTest {
    private val request = mockk<CallToolRequest>()

    private class RecordingToolCallIndicator : ToolCallIndicator {
        val events = mutableListOf<String>()

        override fun onToolCallStarted(toolName: String) {
            events += "started:$toolName"
        }

        override fun onToolCallFinished(toolName: String) {
            events += "finished:$toolName"
        }

        override fun setEnabled(enabled: Boolean) {
            events += "enabled:$enabled"
        }
    }

    @Test
    fun `success logs empty message with duration`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("", entry.message)
            assertEquals("tap", entry.toolName)
            assertTrue((entry.durationMs ?: -1L) >= 0L)
        }

    @Test
    fun `tool invocation shows and hides the visual indicator`() =
        runTest {
            val indicator = RecordingToolCallIndicator()
            val handler =
                loggedToolHandler(RecordingServerLogRepository(), "tap", indicator) {
                    assertEquals(listOf("started:tap"), indicator.events)
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            handler(request)

            assertEquals(listOf("started:tap", "finished:tap"), indicator.events)
        }

    @Test
    fun `thrown tool invocation still hides the visual indicator`() =
        runTest {
            val indicator = RecordingToolCallIndicator()
            val handler =
                loggedToolHandler(RecordingServerLogRepository(), "tap", indicator) {
                    throw McpToolException.InvalidParams("boom")
                }

            runCatching { handler(request) }

            assertEquals(listOf("started:tap", "finished:tap"), indicator.events)
        }

    @Test
    fun `overlapping calls keep the visual indicator visible until the last call finishes`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.onToolCallStarted("swipe")
        indicator.onToolCallFinished("tap")
        assertEquals(listOf("started:tap", "started:swipe"), delegate.events)

        indicator.onToolCallFinished("swipe")
        assertEquals(listOf("started:tap", "started:swipe", "finished:swipe"), delegate.events)
    }

    @Test
    fun `finishing newest overlapping call restores the previous tool name`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.onToolCallStarted("swipe")
        indicator.onToolCallFinished("swipe")

        assertEquals(listOf("started:tap", "started:swipe", "started:tap"), delegate.events)
        indicator.onToolCallFinished("tap")
        assertEquals(
            listOf("started:tap", "started:swipe", "started:tap", "finished:tap"),
            delegate.events,
        )
    }

    @Test
    fun `set enabled is serialized with tool transitions`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.setEnabled(false)
        indicator.onToolCallFinished("tap")

        assertEquals(
            listOf("started:tap", "enabled:false", "finished:tap"),
            delegate.events,
        )
    }

    @Test
    fun `indicator failures never change tool execution or logging`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            var executed = false
            val throwingIndicator =
                object : ToolCallIndicator {
                    override fun onToolCallStarted(toolName: String) = error("start failed")

                    override fun onToolCallFinished(toolName: String) = error("finish failed")
                }
            val handler =
                loggedToolHandler(recorder, "tap", throwingIndicator) {
                    executed = true
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            val result = handler(request)

            assertTrue(executed)
            assertTrue(result.isError != true)
            assertEquals("tap", recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single().toolName)
        }

    @Test
    fun `isError result logs the constant failed marker`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "secret 500 detail")), isError = true)
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `thrown McpToolException logs failed marker and propagates`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    throw McpToolException.InvalidParams("boom 500")
                }

            val error = runCatching { handler(request) }.exceptionOrNull()

            assertTrue(error is McpToolException.InvalidParams)
            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `cancelled invocation logs the constant cancelled marker`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    delay(10_000)
                    CallToolResult(content = listOf(TextContent(text = "late")))
                }

            val job = launch { handler(request) }
            advanceTimeBy(100)
            job.cancelAndJoin()

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("cancelled", entry.message)
            assertEquals("tap", entry.toolName)
        }

    @Test
    fun `cancelled invocation logs the cancelled marker even when the tool turns the cancellation into a result`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    val waited = runCatching { delay(10_000) }
                    CallToolResult(content = listOf(TextContent(text = "gave up")), isError = waited.isFailure)
                }

            val job = launch { handler(request) }
            advanceTimeBy(100)
            job.cancelAndJoin()

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("cancelled", entry.message)
            assertEquals("tap", entry.toolName)
        }
}
