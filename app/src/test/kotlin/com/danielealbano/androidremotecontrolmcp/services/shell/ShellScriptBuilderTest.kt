package com.danielealbano.androidremotecontrolmcp.services.shell

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
@DisplayName("ShellScriptBuilder")
class ShellScriptBuilderTest {
    @TempDir
    lateinit var tempDir: File

    private val builder = ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id")

    private class Run(
        val exitCode: Int,
        val header: ShellHeader?,
        val output: String,
    )

    private fun start(
        command: String,
        workdir: String = tempDir.absolutePath,
        envs: Map<String, String> = emptyMap(),
    ): Process =
        ProcessBuilder("/bin/sh", "-c", builder.build(command, workdir, envs))
            .redirectErrorStream(true)
            .start()

    private fun run(
        command: String,
        workdir: String = tempDir.absolutePath,
        envs: Map<String, String> = emptyMap(),
    ): Run {
        val process = start(command, workdir, envs)
        val read = ShellScriptBuilder.readHeader(process.inputStream)
        val output = read.leftover.toString(Charsets.UTF_8) + process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return Run(process.exitValue(), read.header, output)
    }

    private fun hostOutput(vararg command: String): String =
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .readBytes()
            .toString(Charsets.UTF_8)
            .trim()

    @Test
    fun `first line is pgid and uid`() {
        val process = start("sleep 5")
        try {
            val header = ShellScriptBuilder.readHeader(process.inputStream).header

            assertNotNull(header)
            assertEquals(header!!.pgid.toString(), hostOutput("ps", "-o", "pgid=", "-p", header.pgid.toString()))
            assertEquals(hostOutput("id", "-u"), header.uid.toString())
            hostOutput("kill", "-9", "--", "-${header.pgid}")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `header survives a PATH override`() {
        val run = run("ls", envs = mapOf("PATH" to "/nonexistent"))

        assertNotNull(run.header)
        assertTrue(run.output.contains("not found"), run.output)
    }

    @Test
    fun `command with quotes, dollars, backticks and newlines runs verbatim`() {
        val literals = run("printf '%s\\n' \"it's\" '\$HOME' '`x`'")
        val twoLines = run("echo one\necho two")

        assertEquals("it's\n\$HOME\n`x`\n", literals.output)
        assertEquals("one\ntwo\n", twoLines.output)
    }

    @Test
    fun `workdir with spaces and quotes is entered`() {
        val workdir = File(tempDir, "a 'b'").apply { mkdirs() }

        val run = run("pwd", workdir = workdir.absolutePath)

        assertEquals(workdir.absolutePath + "\n", run.output)
    }

    @Test
    fun `missing workdir exits 126 after the header`() {
        val run = run("echo never", workdir = File(tempDir, "missing").absolutePath)

        assertEquals(126, run.exitCode)
        assertNotNull(run.header)
        assertFalse(run.output.contains("never"))
    }

    @Test
    fun `env values are exported verbatim`() {
        val value = "a b'c\$d"

        val run = run("printf %s \"\$FOO\"", envs = mapOf("FOO" to value))

        assertEquals(value, run.output)
    }

    @Test
    fun `umask 0007 applies to the command`() {
        assertEquals("0007\n", run("umask").output)
    }

    @Test
    fun `stdin is closed`() {
        val run = run("cat")

        assertEquals(0, run.exitCode)
        assertEquals("", run.output)
    }

    @Test
    fun `isValidEnvName`() {
        assertTrue(ShellScriptBuilder.isValidEnvName("A"))
        assertTrue(ShellScriptBuilder.isValidEnvName("_x1"))
        assertFalse(ShellScriptBuilder.isValidEnvName("1A"))
        assertFalse(ShellScriptBuilder.isValidEnvName("A-B"))
        assertFalse(ShellScriptBuilder.isValidEnvName(""))
    }

    @Test
    fun `readHeader on a non-header first line keeps the bytes`() {
        val read = ShellScriptBuilder.readHeader(ByteArrayInputStream("hello\nrest".toByteArray()))

        assertNull(read.header)
        assertArrayEquals("hello\n".toByteArray(), read.leftover)
    }

    @Test
    fun `readHeader on a too long first line keeps the bytes`() {
        val read = ShellScriptBuilder.readHeader(ByteArrayInputStream("x".repeat(40).toByteArray()))

        assertNull(read.header)
        assertEquals(33, read.leftover.size)
    }

    @Test
    fun `readHeader on EOF`() {
        val read = ShellScriptBuilder.readHeader(ByteArrayInputStream(ByteArray(0)))

        assertNull(read.header)
        assertEquals(0, read.leftover.size)
    }
}
