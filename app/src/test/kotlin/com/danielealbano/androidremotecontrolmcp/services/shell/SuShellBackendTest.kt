package com.danielealbano.androidremotecontrolmcp.services.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

@EnabledOnOs(OS.LINUX)
@DisplayName("SuShellBackend")
class SuShellBackendTest {
    @TempDir
    lateinit var tempDir: File

    private val scripts = ShellScriptBuilder("/bin/sh", "/usr/bin/setsid", "/usr/bin/id")

    private fun fakeSu(
        directory: File = tempDir,
        content: String = FAKE_SU,
    ): File =
        File(directory.apply { mkdirs() }, "su").apply {
            writeText(content)
            setExecutable(true)
        }

    private fun backend(
        directories: List<File> = listOf(tempDir),
        probeTimeoutSeconds: Long = 1,
    ): SuShellBackend = SuShellBackend({ directories.map { it.path } }, probeTimeoutSeconds)

    private fun script(command: String): String = scripts.build(command, tempDir.absolutePath, emptyMap())

    private fun calls(): String = File(tempDir, "calls.log").takeIf { it.isFile }?.readText().orEmpty()

    @Test
    fun `locate finds an executable su`() {
        val notExecutable = File(tempDir, "plain").apply { mkdirs() }
        File(notExecutable, "su").writeText("#!/bin/sh\n")
        val directory = File(tempDir, "dir").apply { mkdirs() }
        File(directory, "su").mkdirs()
        val fake = fakeSu(File(tempDir, "real"))

        val located = backend(listOf(notExecutable, directory, File(tempDir, "real"))).locate()

        assertEquals(fake.absolutePath, located)
    }

    @Test
    fun `locate returns null when absent`() {
        assertNull(backend().locate())
    }

    @Test
    fun `spawn runs the script through su -c`() =
        runBlocking {
            fakeSu()
            val command = script("echo ok")

            val shell = backend().spawn(1, command)
            val output = async(Dispatchers.IO) { shell.output.readBytes().toString(Charsets.UTF_8) }
            val exit = withTimeout(WAIT_MS) { shell.exit.await() }

            assertEquals(ShellExit(0), exit)
            assertTrue(Regex("^\\d+ \\d+\nok\n$").matches(output.await()), output.await())
            assertEquals(command + "\n", calls())
        }

    @Test
    fun `spawn without su fails`() {
        val error = assertThrows<IllegalStateException> { runBlocking { backend().spawn(1, script("echo ok")) } }

        assertEquals("su was not found", error.message)
    }

    @Test
    fun `kill asks su to kill the group`() =
        runBlocking {
            fakeSu()
            val shell = backend().spawn(1, script("sleep 300"))
            val header = ShellScriptBuilder.readHeader(shell.output).header
            assertNotNull(header)

            shell.kill(header!!.pgid)
            withTimeout(WAIT_MS) { shell.exit.await() }

            assertTrue(calls().contains("kill -9 -${header.pgid}"), calls())
        }

    @Test
    fun `probe works when su prints 0`() =
        runBlocking {
            fakeSu(content = "#!/bin/sh\necho 0\n")

            assertEquals(SuProbeResult.Works, backend().probe())
        }

    @Test
    fun `probe fails on another uid`() =
        runBlocking {
            fakeSu(content = "#!/bin/sh\necho 2000\n")

            val result = backend().probe()

            assertTrue(result is SuProbeResult.Fails && result.reason.contains("2000"), result.toString())
        }

    @Test
    fun `probe fails when su hangs`() =
        runBlocking {
            fakeSu(content = "#!/bin/sh\nexec sleep 30\n")
            val started = System.nanoTime()

            val result = backend(probeTimeoutSeconds = 1).probe()
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS

            assertEquals(SuProbeResult.Fails("su did not answer within 1 seconds"), result)
            assertTrue(elapsedMs in 900..WAIT_MS, "took $elapsedMs ms")
        }

    @Test
    fun `probe fails when su cannot start`() =
        runBlocking {
            val su = fakeSu(content = "#!/nonexistent\n")

            val result = backend().probe()

            assertTrue(
                result is SuProbeResult.Fails && result.reason.contains("Cannot run program \"${su.absolutePath}\""),
                result.toString(),
            )
        }

    @Test
    fun `defaultSuDirectories keeps PATH order then fallbacks without duplicates`() {
        val path =
            System
                .getenv("PATH")
                .orEmpty()
                .split(':')
                .filter { it.isNotEmpty() }
                .distinct()
        val fallbacks = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")

        val directories = defaultSuDirectories()

        assertEquals(path, directories.take(path.size))
        assertEquals(fallbacks.filterNot { it in path }, directories.drop(path.size))
        assertEquals(directories.distinct(), directories)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val NANOS_PER_MS = 1_000_000L
        val FAKE_SU =
            """
            #!/bin/sh
            [ "${'$'}1" = "-c" ] || exit 2
            printf '%s\n' "${'$'}2" >> "${'$'}(dirname "${'$'}0")/calls.log"
            exec /bin/sh -c "${'$'}2"
            """.trimIndent() + "\n"
    }
}
