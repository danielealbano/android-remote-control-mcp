package com.danielealbano.androidremotecontrolmcp.services.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

@EnabledOnOs(OS.LINUX)
@DisplayName("AppShellBackend")
class AppShellBackendTest {
    @TempDir
    lateinit var tempDir: File

    private val backend =
        AppShellBackend("/bin/sh") { pgid -> ProcessBuilder("kill", "-9", "--", "-$pgid").start().waitFor() }
    private val scripts = ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id")

    private fun script(command: String): String = scripts.build(command, tempDir.absolutePath, emptyMap())

    private fun CoroutineScope.readAll(shell: SpawnedShell): Deferred<String> =
        async(Dispatchers.IO) { shell.output.readBytes().toString(Charsets.UTF_8) }

    private fun alive(pid: String): Boolean = ProcessBuilder("kill", "-0", pid).start().waitFor() == 0

    private fun awaitGone(pid: String): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (alive(pid) && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return !alive(pid)
    }

    @Test
    fun `runs the script and reports the exit code`() =
        runBlocking {
            val shell = backend.spawn(1, script("exit 3"))
            val output = readAll(shell)

            val exit = withTimeout(WAIT_MS) { shell.exit.await() }

            assertEquals(ShellExit(3), exit)
            assertTrue(Regex("^\\d+ \\d+\n$").matches(output.await()), output.await())
        }

    @Test
    fun `combined output keeps order`() =
        runBlocking {
            val shell = backend.spawn(1, script("echo a; echo b >&2; echo c"))
            val output = readAll(shell)

            withTimeout(WAIT_MS) { shell.exit.await() }

            assertEquals("a\nb\nc\n", output.await().substringAfter('\n'))
        }

    @Test
    fun `kill with pgid stops the whole group`() =
        runBlocking {
            val shell = backend.spawn(1, script("sleep 300 & echo \$!; sleep 300"))
            val header = ShellScriptBuilder.readHeader(shell.output).header
            assertNotNull(header)
            val child = shell.output.bufferedReader().readLine().trim()

            shell.kill(header!!.pgid)
            withTimeout(WAIT_MS) { shell.exit.await() }

            assertTrue(awaitGone(child), "child $child still alive")
        }

    @Test
    fun `kill without pgid destroys the process`() =
        runBlocking {
            val shell = backend.spawn(1, script("sleep 300"))
            val header = ShellScriptBuilder.readHeader(shell.output).header
            assertNotNull(header)

            try {
                shell.kill(null)

                withTimeout(WAIT_MS) { shell.exit.await() }
            } finally {
                ProcessBuilder("kill", "-9", "--", "-${header!!.pgid}").start().waitFor()
            }
        }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
