package com.danielealbano.androidremotecontrolmcp.services.shell

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShellProcess")
class ShellProcessTest {
    private val launch = ShellLaunch("cmd", "/", emptyMap(), 30)
    private val kills = CopyOnWriteArrayList<Int?>()

    private val backend = mockk<ShellBackend>(relaxed = true) { every { kind } returns ShellBackendKind.APP }

    private fun process(): ShellProcess = ShellProcess(1, launch, backend, ShellOutputBuffer(65_536), 0)

    private fun spawned(output: InputStream = ByteArrayInputStream(ByteArray(0))): SpawnedShell =
        SpawnedShell(output, CompletableDeferred()) { kills += it }

    @Test
    fun `a stop before the header waits for it`() {
        val process = process()

        process.stop(ShellProcessState.KILLED)
        process.attach(spawned())

        assertTrue(kills.isEmpty())

        process.headerArrived(ShellHeader(77, 0))

        assertEquals(listOf<Int?>(77), kills.toList())
    }

    @Test
    fun `a stop with no header kills the process once the pump is past it`() {
        val process = process()
        process.attach(spawned())

        process.stop(ShellProcessState.KILLED)
        process.headerArrived(null)

        assertEquals(listOf<Int?>(null), kills.toList())
    }

    @Test
    fun `the header is applied once`() {
        val process = process()
        process.attach(spawned())
        process.headerArrived(ShellHeader(77, 2000))

        process.headerArrived(null)

        assertEquals(2000, process.snapshot().uid)
        assertTrue(kills.isEmpty())
    }

    @Test
    fun `the output stays open until the header is in`() {
        val output = mockk<InputStream>(relaxed = true)
        val process = process()
        process.attach(spawned(output))

        process.closeOutput()

        verify(exactly = 0) { output.close() }

        process.headerArrived(ShellHeader(77, 0))
        process.closeOutput()

        verify(exactly = 1) { output.close() }
    }

    @Test
    fun `a stop after the header kills the group`() {
        val process = process()
        process.attach(spawned())
        process.headerArrived(ShellHeader(77, 0))

        process.stop(ShellProcessState.KILLED)

        assertEquals(listOf<Int?>(77), kills.toList())
    }

    @Test
    fun `stop is applied once`() {
        val stoppedTwice = process()
        stoppedTwice.attach(spawned())
        stoppedTwice.headerArrived(ShellHeader(77, 0))

        stoppedTwice.stop(ShellProcessState.KILLED)
        stoppedTwice.stop(ShellProcessState.TIMED_OUT)

        assertEquals(listOf<Int?>(77), kills.toList())

        kills.clear()
        val finished = process()
        finished.attach(spawned())
        finished.headerArrived(ShellHeader(78, 0))
        finished.finish(ShellExit(0), 1)

        finished.stop(ShellProcessState.KILLED)

        assertTrue(kills.isEmpty())
    }

    @Test
    fun `finish uses the stop reason`() {
        val process = process()
        process.stop(ShellProcessState.TIMED_OUT)

        process.finish(ShellExit(137), 5)

        val snapshot = process.snapshot()
        assertEquals(ShellProcessState.TIMED_OUT, snapshot.state)
        assertEquals(137, snapshot.exitCode)
        assertEquals(5L, snapshot.endedAtMs)
    }

    @Test
    fun `finish without a code is failed`() {
        val process = process()

        process.finish(ShellExit(null, "x"), 0)

        assertEquals(ShellProcessState.FAILED, process.snapshot().state)
        assertEquals("x", process.snapshot().failure)
    }

    @Test
    fun `finish happens once`() {
        val process = process()
        process.finish(ShellExit(1), 3)

        val again = process.finish(ShellExit(null, "late"), 9)

        assertFalse(again)
        val snapshot = process.snapshot()
        assertEquals(ShellProcessState.EXITED, snapshot.state)
        assertEquals(1, snapshot.exitCode)
        assertEquals(null, snapshot.failure)
        assertEquals(3L, snapshot.endedAtMs)
    }

    @Test
    fun `killLeftovers needs a group`() {
        val process = process()
        process.attach(spawned())

        process.killLeftovers()

        assertTrue(kills.isEmpty())

        process.headerArrived(ShellHeader(77, 0))
        process.killLeftovers()

        assertEquals(listOf<Int?>(77), kills.toList())
    }

    @Test
    fun `awaitOutput returns on new bytes or the end`() =
        runTest {
            val process = process()
            val bytes = async { process.awaitOutput(0) }
            runCurrent()

            assertFalse(bytes.isCompleted)

            process.append(byteArrayOf(1, 2, 3), 3)
            runCurrent()

            assertTrue(bytes.isCompleted)

            val end = async { process.awaitOutput(3) }
            runCurrent()

            assertFalse(end.isCompleted)

            process.finish(ShellExit(0), 1)
            runCurrent()

            assertTrue(end.isCompleted)
        }

    @Test
    fun `headerArrived records the uid`() {
        val process = process()

        process.headerArrived(ShellHeader(77, 2000))

        assertEquals(2000, process.snapshot().uid)
    }
}
