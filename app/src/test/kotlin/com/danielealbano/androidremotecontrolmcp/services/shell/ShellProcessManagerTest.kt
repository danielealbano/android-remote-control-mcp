package com.danielealbano.androidremotecontrolmcp.services.shell

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.model.ShellConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ShellLimit
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@EnabledOnOs(OS.LINUX)
@DisplayName("ShellProcessManagerImpl")
class ShellProcessManagerTest {
    @TempDir
    lateinit var tempDir: File

    private val log = RecordingServerLogRepository()
    private val fake = FakeShellBackend()
    private val app =
        AppShellBackend("/bin/sh") { pgid -> ProcessBuilder("kill", "-9", "--", "-$pgid").start().waitFor() }

    @Volatile
    private var config = ShellConfig()

    @Volatile
    private var selectElevated: () -> ShellBackend = { fake }

    @Volatile
    private var currentConfig: suspend () -> ShellConfig = { config }

    private lateinit var manager: ShellProcessManagerImpl

    private fun newManager(): ShellProcessManagerImpl =
        ShellProcessManagerImpl(
            app,
            { selectElevated() },
            listOf(fake),
            log,
            { currentConfig() },
            ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id"),
            Dispatchers.IO,
            System::currentTimeMillis,
        )

    @BeforeEach
    fun setUp() {
        manager = newManager().apply { open() }
    }

    @AfterEach
    fun tearDown() {
        manager.shutdown()
    }

    private fun launch(
        command: String,
        elevated: Boolean = false,
        timeoutMinutes: Int = 30,
    ): ShellLaunch =
        ShellLaunch(
            command,
            tempDir.absolutePath,
            emptyMap(),
            timeoutMinutes,
            if (elevated) ShellElevation("why", ElevationGrant.AUTO_ALLOWED) else null,
        )

    private suspend fun start(
        command: String,
        waitMs: Long = WAIT_MS,
        elevated: Boolean = false,
        timeoutMinutes: Int = 30,
        target: ShellProcessManagerImpl = manager,
    ): ShellProcessPage = target.startAndAwait(launch(command, elevated, timeoutMinutes), config, waitMs, MAX_BYTES)

    private suspend fun eventually(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < deadline) delay(POLL_MS)
        assertTrue(condition(), description)
    }

    private fun state(id: Int): ShellProcessState? = manager.list().firstOrNull { it.id == id }?.state

    private fun shellMessages(): List<String> {
        val entries = log.entries.filter { it.type == ServerLogEntry.Type.SHELL }
        return entries.map { it.message }
    }

    private fun hostOutput(vararg command: String): String =
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .readBytes()
            .toString(Charsets.UTF_8)
            .trim()

    private fun pidGone(pid: String): Boolean = ProcessBuilder("kill", "-0", pid).start().waitFor() != 0

    private fun withLimit(
        limit: ShellLimit,
        value: Int,
    ) {
        config = config.copy(limits = config.limits + (limit to value))
    }

    @Test
    fun `exec-style flow returns exit code and output`() =
        runBlocking {
            val page = start("echo hi; exit 4")

            assertEquals(ShellProcessState.EXITED, page.snapshot.state)
            assertEquals(4, page.snapshot.exitCode)
            assertEquals("hi\n", page.chunk.text)
            assertEquals(hostOutput("id", "-u").toInt(), page.snapshot.uid)
        }

    @Test
    fun `the starting call reads its process even when no finished process is kept`() =
        runBlocking {
            withLimit(ShellLimit.MAX_FINISHED_PROCESSES, 0)

            val page = start("exit 3")

            assertEquals(ShellProcessState.EXITED, page.snapshot.state)
            assertEquals(3, page.snapshot.exitCode)
            eventually("the finished process is trimmed") { manager.list().isEmpty() }
        }

    @Test
    fun `a page call keeps its process when retention drops it`() =
        runBlocking {
            withLimit(ShellLimit.MAX_FINISHED_PROCESSES, 0)
            val id = start("sleep 1; exit 3", waitMs = 0).snapshot.id

            val page = manager.page(id, 0, WAIT_MS, MAX_BYTES)

            assertEquals(ShellProcessState.EXITED, page.snapshot.state)
            assertEquals(3, page.snapshot.exitCode)
        }

    @Test
    fun `a kill keeps its process when retention drops it`() =
        runBlocking {
            withLimit(ShellLimit.MAX_FINISHED_PROCESSES, 0)
            val id = start("sleep 300", waitMs = 0).snapshot.id

            val snapshot = manager.killAndAwait(id, WAIT_MS)

            assertEquals(ShellProcessState.KILLED, snapshot.state)
        }

    @Test
    fun `waitMs 0 returns at once`() =
        runBlocking {
            assertEquals(ShellProcessState.RUNNING, start("sleep 30", waitMs = 0).snapshot.state)
        }

    @Test
    fun `startAndAwait gives up at waitMs`() =
        runBlocking {
            val started = System.nanoTime()

            val page = start("sleep 30", waitMs = 200)
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

            assertEquals(ShellProcessState.RUNNING, page.snapshot.state)
            assertTrue(elapsedMs in 200..WAIT_MS, "took $elapsedMs ms")
        }

    @Test
    fun `a cancelled wait kills the process`() =
        runBlocking {
            val call = async(start = CoroutineStart.UNDISPATCHED) { start("sleep 300", waitMs = 60_000) }

            call.cancel()

            eventually("process 1 is killed") { state(1) == ShellProcessState.KILLED }
        }

    @Test
    fun `page waits for new output`() =
        runBlocking {
            val id = start("sleep 1; echo x; sleep 30", waitMs = 0).snapshot.id
            val started = System.nanoTime()

            val page = manager.page(id, 0, WAIT_MS, MAX_BYTES)
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

            assertEquals("x\n", page.chunk.text)
            assertTrue(elapsedMs < WAIT_MS, "took $elapsedMs ms")
        }

    @Test
    fun `page refuses an offset outside the output`() =
        runBlocking {
            val page = start("echo hi")
            val total = page.chunk.totalBytes

            assertThrows<McpToolException.InvalidParams> {
                runBlocking { manager.page(page.snapshot.id, total + 1, 0, MAX_BYTES) }
            }
            Unit
        }

    @Test
    fun `a running process page keeps an incomplete UTF-8 tail`() =
        runBlocking {
            val id = start("print", waitMs = 0, elevated = true).snapshot.id
            val spawned = fake.spawned.getValue(id)
            spawned.writeHeader(77, 0)
            spawned.write(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
            eventually("two bytes are read") { manager.list().single().totalBytes == 2L }

            val partial = manager.page(id, 0, 0, MAX_BYTES)

            assertEquals("", partial.chunk.text)
            assertEquals(0L, partial.chunk.nextOffset)

            spawned.write(byteArrayOf(0xAC.toByte()))
            spawned.end(0)
            eventually("the process ends") { state(id) == ShellProcessState.EXITED }

            assertEquals("€", manager.page(id, 0, 0, MAX_BYTES).chunk.text)
        }

    @Test
    fun `killAndAwait stops the process and its group`() =
        runBlocking {
            val id = start("sleep 300 & echo \$!; wait", waitMs = 0).snapshot.id
            val child =
                manager
                    .page(id, 0, WAIT_MS, MAX_BYTES)
                    .chunk.text
                    .trim()

            val snapshot = manager.killAndAwait(id, WAIT_MS)

            assertEquals(ShellProcessState.KILLED, snapshot.state)
            eventually("child $child is gone") { pidGone(child) }
        }

    @Test
    fun `leftovers are killed at exit`() =
        runBlocking {
            val page = start("sleep 300 & echo \$!")
            val child = page.chunk.text.trim()

            assertEquals(ShellProcessState.EXITED, page.snapshot.state)
            eventually("child $child is gone") { pidGone(child) }
        }

    @Test
    fun `background timeout`() =
        runBlocking {
            val page = start("sleep 30", timeoutMinutes = 0)

            assertEquals(ShellProcessState.TIMED_OUT, page.snapshot.state)
        }

    @Test
    fun `a kill requested while spawning is applied when the header arrives`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            fake.gate = gate
            val call = async(start = CoroutineStart.UNDISPATCHED) { start("work", waitMs = 0, elevated = true) }

            manager.killAndAwait(1, 0)
            gate.complete(Unit)
            call.await()
            val spawned = fake.spawned.getValue(1)

            assertTrue(spawned.kills.isEmpty())

            spawned.writeHeader(77, 0)

            eventually("the group is killed") { spawned.kills.toList() == listOf<Int?>(77) }

            spawned.end(137)

            eventually("the process is killed") { state(1) == ShellProcessState.KILLED }
        }

    @Test
    fun `a kill right after a start stops the whole group`() =
        runBlocking {
            repeat(KILL_ROUNDS) { round ->
                val seconds = 7_000 + round
                val id = start("sleep $seconds & wait", waitMs = 0).snapshot.id

                val snapshot = manager.killAndAwait(id, WAIT_MS)

                assertEquals(ShellProcessState.KILLED, snapshot.state, "round $round")
                eventually("no sleep $seconds is left") { hostOutput("pgrep", "-f", "sleep $seconds").isEmpty() }
            }
        }

    @Test
    fun `a caller cancelled during the spawn gets its process killed`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            fake.gate = gate
            val call = async(start = CoroutineStart.UNDISPATCHED) { start("work", waitMs = 0, elevated = true) }

            call.cancel()
            gate.complete(Unit)

            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            val spawned = fake.spawned.getValue(1)
            spawned.writeHeader(77, 0)
            eventually("the group is killed") { spawned.kills.toList() == listOf<Int?>(77) }
            spawned.end(137)
            eventually("the process is killed") { state(1) == ShellProcessState.KILLED }
            eventually("the start and the end are logged") { shellMessages().size == 2 }
            assertTrue(shellMessages()[0].startsWith("Elevated command auto-allowed, process 1 via shizuku"))
            assertTrue(shellMessages()[1].startsWith("Elevated process 1 ended: killed"))
        }

    @Test
    fun `start failure marks failed, names the backend and is audited`() =
        runBlocking {
            fake.failure = IllegalStateException("boom")

            val error = assertThrows<McpToolException.ActionFailed> { runBlocking { start("boom", elevated = true) } }

            assertTrue(error.message.orEmpty().contains("through shizuku: boom"), error.message)
            assertEquals(ShellProcessState.FAILED, state(1))
            assertTrue(fake.released.contains(1))
            assertEquals(
                listOf(
                    "Elevated command auto-allowed, process 1 via shizuku | justification: why | command: boom",
                    "Elevated process 1 did not start: The command could not start through shizuku: boom",
                ),
                shellMessages(),
            )
        }

    @Test
    fun `no elevated backend is audited`() =
        runBlocking {
            selectElevated = { throw McpToolException.PermissionDenied("no backend") }

            assertThrows<McpToolException.PermissionDenied> { runBlocking { start("id", elevated = true) } }

            assertTrue(shellMessages().single().contains("not started (no backend)"), shellMessages().toString())
            assertTrue(manager.list().isEmpty())
        }

    @Test
    fun `elevated start and end are audited`() =
        runBlocking {
            start("id", waitMs = 0, elevated = true)
            val spawned = fake.spawned.getValue(1)

            spawned.writeHeader(77, 2000)
            spawned.end(0)

            eventually("the process ends") { state(1) == ShellProcessState.EXITED }
            eventually("the helper is released") { fake.released.contains(1) }
            assertEquals(
                listOf(
                    "Elevated command auto-allowed, process 1 via shizuku | justification: why | command: id",
                    "Elevated process 1 ended: exited, exit code 0",
                ),
                shellMessages(),
            )
        }

    @Test
    fun `a leftover outside the group does not hold the end`() =
        runBlocking {
            val started = System.nanoTime()

            val page = start("setsid sleep 7 & echo x", waitMs = 4_000)
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

            assertEquals(ShellProcessState.EXITED, page.snapshot.state)
            assertEquals("x\n", page.chunk.text)
            assertTrue(elapsedMs < 4_000, "took $elapsedMs ms")
        }

    @Test
    fun `normal commands are not audited`() =
        runBlocking {
            start("true")

            assertTrue(shellMessages().isEmpty())
        }

    @Test
    fun `backend failure exit is reported`() =
        runBlocking {
            start("id", waitMs = 0, elevated = true)

            fake.spawned.getValue(1).end(null, "Shizuku stopped")

            eventually("the process fails") { state(1) == ShellProcessState.FAILED }
            assertEquals("Shizuku stopped", manager.list().single().failure)
        }

    @Test
    fun `starts are refused before open and after shutdown`() =
        runBlocking {
            val other = newManager()
            try {
                val beforeOpen =
                    assertThrows<McpToolException.ActionFailed> { runBlocking { start("true", target = other) } }
                assertTrue(beforeOpen.message.orEmpty().contains("not running"), beforeOpen.message)

                other.open()
                assertEquals(ShellProcessState.EXITED, start("true", target = other).snapshot.state)

                other.shutdown()
                val afterShutdown =
                    assertThrows<McpToolException.ActionFailed> { runBlocking { start("true", target = other) } }
                assertTrue(afterShutdown.message.orEmpty().contains("not running"), afterShutdown.message)
            } finally {
                other.shutdown()
            }
        }

    @Test
    fun `shutdown kills, ends, audits and releases everything`() =
        runBlocking {
            start("sleep 7101", waitMs = 0)
            start("sleep 7102", waitMs = 0)
            eventually("both headers are read") { manager.list().all { it.uid != null } }
            start("id", waitMs = 0, elevated = true)
            val spawned = fake.spawned.getValue(3)
            spawned.writeHeader(77, 2000)
            eventually("the elevated header is read") { manager.list().single { it.id == 3 }.uid == 2000 }

            manager.shutdown()

            assertTrue(manager.list().isEmpty())
            eventually("the app processes are gone") {
                hostOutput("pgrep", "-f", "sleep 7101").isEmpty() && hostOutput("pgrep", "-f", "sleep 7102").isEmpty()
            }
            assertEquals(listOf<Int?>(77), spawned.kills.toList())
            assertEquals(1, fake.releaseAllCalls.get())
            assertEquals(
                1,
                shellMessages().count { it == "Elevated process 3 ended: killed, exit code none (The server stopped)" },
            )
        }

    @Test
    fun `shutdown during a spawn keeps the audit in order`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            fake.gate = gate
            val call = async(start = CoroutineStart.UNDISPATCHED) { start("work", waitMs = 0, elevated = true) }

            manager.shutdown()
            gate.complete(Unit)
            call.await()
            val spawned = fake.spawned.getValue(1)
            spawned.writeHeader(77, 0)

            eventually("the group is killed") { spawned.kills.toList() == listOf<Int?>(77) }

            spawned.end(137)
            delay(SETTLE_MS)

            assertEquals(
                listOf(
                    "Elevated command auto-allowed, process 1 via shizuku | justification: why | command: work",
                    "Elevated process 1 ended: killed, exit code none (The server stopped)",
                ),
                shellMessages(),
            )
        }

    @Test
    fun `a failing watcher is logged and later commands still work`() =
        runBlocking {
            mockkStatic(Log::class)
            try {
                val calls = AtomicInteger()
                val failure = IllegalStateException("config unavailable")
                currentConfig = { if (calls.incrementAndGet() == 1) throw failure else config }

                start("true")
                eventually("the watcher failure is logged") { calls.get() >= 1 }
                verify(timeout = WAIT_MS) {
                    Log.e("MCP:ShellProcessManager", "A shell process watcher failed", failure)
                }

                val page = start("echo ok")

                assertEquals(ShellProcessState.EXITED, page.snapshot.state)
                assertEquals("ok\n", page.chunk.text)
            } finally {
                unmockkStatic(Log::class)
            }
        }

    @Test
    fun `retention follows the live setting`() =
        runBlocking {
            withLimit(ShellLimit.MAX_FINISHED_PROCESSES, 5)
            repeat(3) { start("true") }

            withLimit(ShellLimit.MAX_FINISHED_PROCESSES, 1)
            val last = start("true").snapshot.id

            eventually("only the last process is kept") { manager.list().map { it.id } == listOf(last) }
        }

    @Test
    fun `running limit applies`() =
        runBlocking {
            withLimit(ShellLimit.MAX_RUNNING_PROCESSES, 1)
            start("sleep 30", waitMs = 0)

            assertThrows<McpToolException.ActionFailed> { runBlocking { start("true") } }
            Unit
        }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
        const val SETTLE_MS = 500L
        const val MAX_BYTES = 64 * 1024
        const val NANOS_PER_MS = 1_000_000L
        const val KILL_ROUNDS = 20
    }
}
