package com.danielealbano.androidremotecontrolmcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("InFlightToolCalls")
class InFlightToolCallsTest {
    private val calls = InFlightToolCalls()
    private val id7 = RequestId(7L)

    /** A tracked call, recording the reasons it is cancelled with; [running] false: its tool already ended. */
    private class Call(
        running: Boolean = true,
    ) {
        val reasons = mutableListOf<String?>()
        val cancel: (String?) -> Boolean = {
            reasons += it
            running
        }
        val isCancelled get() = reasons.isNotEmpty()
    }

    @Test
    fun `cancels the only running call of the caller with that id`() {
        val job = Call()
        calls.track("caller-a", id7, job.cancel)

        assertEquals(InFlightToolCalls.Outcome.CANCELLED, calls.cancel("caller-a", id7, "user stop"))
        assertEquals(listOf<String?>("user stop"), job.reasons)
    }

    @Test
    fun `the only call with that id whose tool already ended is not cancelled, so the id is unknown`() {
        val ended = Call(running = false)
        calls.track("caller-a", id7, ended.cancel)

        assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel("caller-a", id7, null))
    }

    @Test
    fun `an unknown id cancels nothing`() {
        val job = Call()
        calls.track("caller-a", id7, job.cancel)

        assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel("caller-a", RequestId(8L), null))
        assertFalse(job.isCancelled)
    }

    @Test
    fun `another caller cannot cancel the call`() {
        val job = Call()
        calls.track("caller-a", id7, job.cancel)

        assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel("caller-b", id7, null))
        assertFalse(job.isCancelled)
    }

    @Test
    fun `two running calls of the caller with the same id are ambiguous and none is cancelled`() {
        val first = Call()
        val second = Call()
        calls.track("caller-a", id7, first.cancel)
        calls.track("caller-a", id7, second.cancel)

        assertEquals(InFlightToolCalls.Outcome.AMBIGUOUS, calls.cancel("caller-a", id7, null))
        assertFalse(first.isCancelled)
        assertFalse(second.isCancelled)
    }

    @Test
    fun `a call no longer tracked cannot be cancelled, and the other one with its id becomes the only one`() {
        val first = Call()
        val second = Call()
        val untrackFirst = calls.track("caller-a", id7, first.cancel)
        val untrackSecond = calls.track("caller-a", id7, second.cancel)

        untrackFirst()
        assertEquals(InFlightToolCalls.Outcome.CANCELLED, calls.cancel("caller-a", id7, null))
        assertFalse(first.isCancelled)
        assertTrue(second.isCancelled)

        untrackSecond()
        assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel("caller-a", id7, null))
    }

    @Test
    fun `string and number request ids are different ids`() {
        val job = Call()
        calls.track("caller-a", RequestId("7"), job.cancel)

        assertEquals(InFlightToolCalls.Outcome.UNKNOWN, calls.cancel("caller-a", id7, null))
        assertEquals(InFlightToolCalls.Outcome.CANCELLED, calls.cancel("caller-a", RequestId("7"), null))
    }

    @Test
    fun `a bound session gives its caller until it is unbound`() {
        val caller = McpCaller.of("Bearer secret-token")
        val unbind = calls.bindSession("session-1", caller)

        assertEquals(caller, calls.callerOf("session-1"))
        assertEquals(null, calls.callerOf("session-2"))

        unbind()
        assertEquals(null, calls.callerOf("session-1"))
    }

    @Nested
    @DisplayName("McpCaller")
    inner class Caller {
        @Test
        fun `a bearer credential gives its SHA-256, never the credential itself`() {
            val caller = McpCaller.of("Bearer secret-token")

            assertEquals(SECRET_TOKEN_SHA256, caller.id)
            assertFalse(caller.id.contains("secret-token"))
        }

        @Test
        fun `the scheme is case-insensitive and the credential trimmed`() {
            assertEquals(McpCaller.of("Bearer secret-token").id, McpCaller.of("bearer   secret-token  ").id)
        }

        @Test
        fun `different credentials are different callers`() {
            assertNotEquals(McpCaller.of("Bearer a").id, McpCaller.of("Bearer b").id)
        }

        @Test
        fun `no header, another scheme or an empty credential is the open caller`() {
            for (header in listOf(null, "", "Basic dXNlcjpwYXNz", "Bearer ", "Bearer    ")) {
                assertEquals(McpCaller.OPEN, McpCaller.of(header).id, "header: $header")
            }
        }
    }

    private companion object {
        // printf '%s' secret-token | sha256sum
        const val SECRET_TOKEN_SHA256 = "930bbdc51b6aed5c2a5678fd6e28dee7a05e8a4b643cfc0b4427c3efb86c0d94"
    }
}
