package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@DisplayName("ShellProcessHost")
class ShellProcessHostTest {
    @TempDir
    lateinit var tempDir: File

    private val host = ShellProcessHost("/bin/sh") { pgid -> killGroup(pgid) }
    private val scripts = ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id")
    private val groups = ConcurrentLinkedQueue<Int>()

    private class Exits(
        private val accept: Boolean = true,
    ) {
        val codes = ConcurrentLinkedQueue<Int>()
        val done = CountDownLatch(1)

        fun onExit(code: Int): Boolean {
            codes.add(code)
            done.countDown()
            return accept
        }

        fun await(): Boolean = done.await(WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private class Output {
        val input = PipedInputStream(PIPE_BYTES)
        val output = PipedOutputStream(input)
        val reader: BufferedReader = input.bufferedReader()
    }

    @AfterEach
    fun tearDown() {
        host.killAll()
        groups.forEach(::killGroup)
    }

    private fun killGroup(pgid: Int) {
        ProcessBuilder("kill", "-9", "--", "-$pgid").start().waitFor()
    }

    private fun script(command: String): String = scripts.build(command, tempDir.absolutePath, emptyMap())

    private fun start(
        id: Int,
        command: String,
        exits: Exits,
    ): Output {
        val output = Output()
        host.start(id, script(command), output.output, exits::onExit)
        return output
    }

    private fun Output.readHeader(): ShellHeader {
        val line = reader.readLine()
        val parts = line.split(' ')
        return ShellHeader(parts[0].toInt(), parts[1].toInt()).also { groups.add(it.pgid) }
    }

    private fun gone(check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return check()
    }

    private fun pidGone(pid: String): Boolean = ProcessBuilder("kill", "-0", pid).start().waitFor() != 0

    private fun groupEmpty(pgid: Int): Boolean =
        ProcessBuilder("pgrep", "-g", pgid.toString())
            .start()
            .inputStream
            .readBytes()
            .isEmpty()

    @Test
    fun `forwards header and output and reports the exit`() {
        val exits = Exits()
        val output = start(1, "echo out; exit 4", exits)

        val text = output.input.readBytes().toString(Charsets.UTF_8)

        assertTrue(exits.await())
        assertTrue(Regex("^\\d+ \\d+\nout\n$").matches(text), text)
        assertEquals(listOf(4), exits.codes.toList())
    }

    @Test
    fun `kills leftovers at exit`() {
        val exits = Exits()
        val output = start(1, "sleep 300 & echo \$!", exits)
        output.readHeader()
        val child = output.reader.readLine().trim()

        assertTrue(exits.await())

        assertTrue(gone { pidGone(child) }, "child $child still alive")
    }

    @Test
    fun `kill by id stops the group`() {
        val exits = Exits()
        val output = start(1, "sleep 300", exits)
        output.readHeader()

        host.kill(1)

        assertTrue(exits.await())
    }

    @Test
    fun `a kill before the header is applied when it arrives`() {
        val exits = Exits()
        val output = start(1, "sleep 300 & wait", exits)

        host.kill(1)
        val header = output.readHeader()

        assertTrue(exits.await())
        assertTrue(gone { groupEmpty(header.pgid) }, "group ${header.pgid} still has processes")
    }

    @Test
    fun `killAll stops every process`() {
        val first = Exits()
        val second = Exits()
        start(1, "sleep 300", first).readHeader()
        start(2, "sleep 300", second).readHeader()

        host.killAll()

        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun `closing the reader stops the process`() {
        val exits = Exits()
        val output = start(1, "yes", exits)
        output.readHeader()

        output.input.close()

        assertTrue(exits.await())
    }

    @Test
    fun `an exit that cannot be delivered stops every other process`() {
        val running = Exits()
        start(1, "sleep 300", running).readHeader()
        val undelivered = Exits(accept = false)

        start(2, "exit 0", undelivered).readHeader()

        assertTrue(undelivered.await())
        assertTrue(running.await())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
        const val PIPE_BYTES = 64 * 1024
    }
}
