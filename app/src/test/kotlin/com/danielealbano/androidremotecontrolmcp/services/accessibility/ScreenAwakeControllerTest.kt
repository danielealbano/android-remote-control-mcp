package com.danielealbano.androidremotecontrolmcp.services.accessibility

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ReferenceCountedToolCallIndicator
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallIndicator
import com.danielealbano.androidremotecontrolmcp.mcp.tools.loggedToolHandler
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAwakeControllerTest {
    @Test
    fun `cancelled request still finishes through real tool wrapper and releases after grace`() =
        runTest {
            var awake = false
            val controller = ScreenAwakeController(backgroundScope, 180) { awake = it }
            val indicator =
                ReferenceCountedToolCallIndicator(
                    object : ToolCallIndicator {
                        override fun onToolCallStarted(toolName: String) = controller.started()

                        override fun onToolCallFinished(toolName: String) = controller.finished()
                    },
                )
            val handler = loggedToolHandler(RecordingServerLogRepository(), "read", indicator) { awaitCancellation() }
            val request = launch { handler(mockk<CallToolRequest>()) }
            runCurrent()
            assertTrue(awake)
            request.cancelAndJoin()
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `active action never expires and final completion receives full configured grace`() =
        runTest {
            var awake = false
            val controller = ScreenAwakeController(backgroundScope, 180) { awake = it }
            controller.started()
            advanceTimeBy(400_000)
            assertTrue(awake)
            controller.finished()
            runCurrent()
            advanceTimeBy(179_999)
            assertTrue(awake)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `new action cancels previous expiry and renews grace after it finishes`() =
        runTest {
            var awake = false
            val controller = ScreenAwakeController(backgroundScope, 180) { awake = it }
            controller.started()
            controller.finished()
            runCurrent()
            advanceTimeBy(100_000)
            controller.started()
            advanceTimeBy(200_000)
            runCurrent()
            assertTrue(awake)
            controller.finished()
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `stop releases active work and late completion cannot renew it`() =
        runTest {
            var awake = false
            val controller = ScreenAwakeController(backgroundScope, 180) { awake = it }
            controller.started()
            assertTrue(awake)
            controller.stop()
            assertFalse(awake)
            controller.finished()
            assertFalse(awake)
            advanceTimeBy(180_000)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `overlapping tools retain awake state until final completion`() =
        runTest {
            var awake = false
            val controller = ScreenAwakeController(backgroundScope, 180) { awake = it }
            val indicator =
                ReferenceCountedToolCallIndicator(
                    object : ToolCallIndicator {
                        override fun onToolCallStarted(toolName: String) = controller.started()

                        override fun onToolCallFinished(toolName: String) = controller.finished()
                    },
                )
            indicator.onToolCallStarted("first")
            indicator.onToolCallStarted("second")
            indicator.onToolCallFinished("second")
            advanceTimeBy(200_000)
            assertTrue(awake)
            indicator.onToolCallFinished("first")
            runCurrent()
            advanceTimeBy(180_000)
            runCurrent()
            assertFalse(awake)
        }

    @Test
    fun `disabled default and invalid or excessive timeouts never hold screen`() =
        runTest {
            var awake = false
            listOf(0, -1, 59, ServerConfig.MAX_SCREEN_AWAKE_TIMEOUT_SECONDS + 1, Int.MAX_VALUE).forEach {
                val controller = ScreenAwakeController(backgroundScope, it) { awake = it }
                controller.started()
                assertFalse(awake)
                controller.finished()
                runCurrent()
                assertFalse(awake)
            }
            val controller =
                ScreenAwakeController(backgroundScope, ServerConfig.MAX_SCREEN_AWAKE_TIMEOUT_SECONDS) {
                    awake = it
                }
            controller.started()
            assertTrue(awake)
            controller.finished()
            runCurrent()
            advanceTimeBy(ServerConfig.MAX_SCREEN_AWAKE_TIMEOUT_SECONDS * 1000L - 1)
            assertTrue(awake)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(awake)
        }
}
