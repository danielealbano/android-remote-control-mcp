package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ElevationApprovalCoordinatorImpl")
class ElevationApprovalCoordinatorImplTest {
    private val notifier = mockk<ElevationApprovalNotifier>(relaxed = true)
    private val coordinator = ElevationApprovalCoordinatorImpl(notifier)

    @BeforeEach
    fun setUp() {
        coordinator.open()
    }

    @Test
    fun `approve`() =
        runTest {
            val verdict = async { coordinator.request("id", "why", TIMEOUT_MS) }
            runCurrent()

            coordinator.resolve(1, true)

            assertEquals(ElevationVerdict.APPROVED, verdict.await())
            assertTrue(coordinator.pending.value.isEmpty())
            verifyOrder {
                notifier.post(PendingElevation(1, "id", "why"))
                notifier.cancel(1)
            }
        }

    @Test
    fun `deny`() =
        runTest {
            val verdict = async { coordinator.request("id", "why", TIMEOUT_MS) }
            runCurrent()

            coordinator.resolve(1, false)

            assertEquals(ElevationVerdict.DENIED, verdict.await())
        }

    @Test
    fun `timeout`() =
        runTest {
            val verdict = async { coordinator.request("id", "why", TIMEOUT_MS) }
            runCurrent()

            advanceTimeBy(TIMEOUT_MS + 1)

            assertEquals(ElevationVerdict.TIMED_OUT, verdict.await())
            verify(exactly = 1) { notifier.cancel(1) }
        }

    @Test
    fun `two concurrent requests get distinct ids and notifications`() =
        runTest {
            val first = async { coordinator.request("a", "why", TIMEOUT_MS) }
            val second = async { coordinator.request("b", "why", TIMEOUT_MS) }
            runCurrent()

            val pending = coordinator.pending.value

            assertEquals(listOf(1, 2), pending.map { it.id })
            verify(exactly = 1) { notifier.post(PendingElevation(1, "a", "why")) }
            verify(exactly = 1) { notifier.post(PendingElevation(2, "b", "why")) }

            coordinator.resolve(1, true)
            coordinator.resolve(2, false)

            assertEquals(ElevationVerdict.APPROVED, first.await())
            assertEquals(ElevationVerdict.DENIED, second.await())
        }

    @Test
    fun `resolve of an unknown id cancels its notification`() {
        coordinator.resolve(9, true)

        verify(exactly = 1) { notifier.cancel(9) }
        verify(exactly = 0) { notifier.post(any()) }
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun `close cancels every pending request`() =
        runTest {
            val first = async { coordinator.request("a", "why", TIMEOUT_MS) }
            val second = async { coordinator.request("b", "why", TIMEOUT_MS) }
            runCurrent()

            coordinator.close()

            assertEquals(ElevationVerdict.CANCELLED, first.await())
            assertEquals(ElevationVerdict.CANCELLED, second.await())
            assertTrue(coordinator.pending.value.isEmpty())
            verify(exactly = 1) { notifier.cancel(1) }
            verify(exactly = 1) { notifier.cancel(2) }
        }

    @Test
    fun `requests are cancelled at once while closed`() =
        runTest {
            val closed = ElevationApprovalCoordinatorImpl(notifier)

            assertEquals(ElevationVerdict.CANCELLED, closed.request("a", "why", TIMEOUT_MS))

            closed.open()
            closed.close()

            assertEquals(ElevationVerdict.CANCELLED, closed.request("b", "why", TIMEOUT_MS))
            verify(exactly = 0) { notifier.post(any()) }
        }

    @Test
    fun `a failing post does not leave the request behind`() =
        runTest {
            every { notifier.post(any()) } throws IllegalStateException("no channel")

            val error = runCatching { coordinator.request("a", "why", TIMEOUT_MS) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, error.toString())
            assertTrue(coordinator.pending.value.isEmpty())
            verify(exactly = 1) { notifier.cancel(1) }
        }

    @Test
    fun `caller cancellation removes the request`() =
        runTest {
            val call = async { coordinator.request("a", "why", TIMEOUT_MS) }
            runCurrent()

            call.cancel()
            runCurrent()

            assertTrue(coordinator.pending.value.isEmpty())
            verify(exactly = 1) { notifier.cancel(1) }
        }

    @Test
    fun `displayCommand truncates at 500 characters`() {
        val long = "a".repeat(501)
        val exact = "b".repeat(500)

        assertEquals("a".repeat(500) + "…", PendingElevation(1, long, "j").displayCommand)
        assertEquals(exact, PendingElevation(2, exact, "j").displayCommand)
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
