package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EShellTest {
    @BeforeAll
    fun setUp() {
        SharedAndroidContainer.ensureAccessibilityService()
        ShellE2E.restoreDefaults()
        ShellE2E.configure(*ShellE2E.testTimeouts())
        ShellE2E.grantNotifications()
        ShellE2E.restartServer()
    }

    @AfterAll
    fun tearDown() {
        ShizukuE2E.uninstall()
        ShizukuE2E.revoke()
        TestSuE2E.remove()
        ShellE2E.grantNotifications()
        ShellE2E.restoreDefaults()
        ShellE2E.restartServer()
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String): Int = getValue(name).jsonPrimitive.int

    private fun noProcessWithin(
        args: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && running(args)) Thread.sleep(POLL_MS)
        return !running(args)
    }

    private fun running(args: String): Boolean = StorageE2E.shell("ps -A -o ARGS").lines().any { it.trim() == args }

    @Test
    @Order(1)
    fun `exec runs as the app user`() =
        runBlocking {
            val result = ShellE2E.call("shell_exec", ShellE2E.run("id -u"))

            assertEquals("exited", result.text("state"))
            assertEquals(0, result.number("exit_code"))
            assertEquals("app", result.text("backend"))
            assertEquals(ShellE2E.appUid(), result.number("uid"))
            assertEquals(ShellE2E.appUid().toString(), result.text("output").trim())
        }

    @Test
    @Order(2)
    fun `exit codes pass through`() =
        runBlocking {
            assertEquals(7, ShellE2E.call("shell_exec", ShellE2E.run("exit 7")).number("exit_code"))
        }

    @Test
    @Order(3)
    fun `workdir and envs`() =
        runBlocking {
            val result =
                ShellE2E.call("shell_exec", ShellE2E.run("pwd; printf %s \"\$FOO\"", envs = mapOf("FOO" to "a b'c")))

            assertEquals("${ShellE2E.scratch}\na b'c", result.text("output"))
        }

    @Test
    @Order(4)
    fun `missing workdir parameter is an error`() =
        runBlocking {
            val error = ShellE2E.callError("shell_exec", mapOf("command" to "id -u"))

            assertTrue(error.contains("workdir"), error)
        }

    @Test
    @Order(5)
    fun `exec moves to the background at the foreground limit`() =
        runBlocking {
            ShellE2E.configure("--ei", "shell_foreground_wait_seconds", "10")
            try {
                val started = System.currentTimeMillis()

                val result = ShellE2E.call("shell_exec", ShellE2E.run("sleep 15; echo late"))
                val elapsedMs = System.currentTimeMillis() - started

                assertEquals("running", result.text("state"))
                assertTrue(elapsedMs in 9_000..14_000, "returned after $elapsedMs ms")
                val end = ShellE2E.awaitEnd(result.number("process_id"))
                assertEquals("exited", end.text("state"))
                assertEquals("late\n", end.text("output"))
            } finally {
                ShellE2E.configure(*ShellE2E.testTimeouts())
            }
        }

    @Test
    @Order(6)
    fun `start, list and kill`() =
        runBlocking {
            val started = ShellE2E.call("shell_start", ShellE2E.run("sleep 600"))
            val id = started.number("process_id")

            assertEquals("running", started.text("state"))
            val listed =
                ShellE2E
                    .call("shell_list")
                    .getValue("processes")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("process_id")
                            .jsonPrimitive.int
                    }
            assertTrue(listed.contains(id), listed.toString())
            assertEquals("killed", ShellE2E.call("shell_kill", mapOf("process_id" to id)).text("state"))
        }

    @Test
    @Order(7)
    fun `output pages by offset`() =
        runBlocking {
            val first = ShellE2E.call("shell_exec", ShellE2E.run("head -c 200000 /dev/zero | tr '\\0' a"))

            assertEquals(PAGE_BYTES, first.text("output").length)
            assertTrue(first.getValue("has_more_output").jsonPrimitive.boolean)

            val next =
                ShellE2E.call(
                    "shell_output",
                    mapOf(
                        "process_id" to first.number("process_id"),
                        "offset" to first.getValue("next_offset").jsonPrimitive.long,
                    ),
                )

            assertEquals(PAGE_BYTES.toLong(), next.getValue("output_offset").jsonPrimitive.long)
            assertEquals(PAGE_BYTES, next.text("output").length)
        }

    @Test
    @Order(8)
    fun `scratch folder is noexec, scripts run`() =
        runBlocking {
            val binary = ShellE2E.call("shell_exec", ShellE2E.run("cp /system/bin/toybox ./tb && ./tb echo x"))
            val script = ShellE2E.call("shell_exec", ShellE2E.run("printf 'echo ok' > s.sh && sh s.sh"))

            assertEquals(126, binary.number("exit_code"))
            assertEquals("ok\n", script.text("output"))
        }

    @Test
    @Order(9)
    fun `leftovers are stopped at exit`() =
        runBlocking {
            val result = ShellE2E.call("shell_exec", ShellE2E.run("sleep 300 & echo \$!"))
            val pid = result.text("output").trim()

            assertEquals("exited", result.text("state"))
            val deadline = System.currentTimeMillis() + WAIT_MS
            var state = StorageE2E.shell("[ -d /proc/$pid ] && echo alive || echo gone").trim()
            while (state != "gone" && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS)
                state = StorageE2E.shell("[ -d /proc/$pid ] && echo alive || echo gone").trim()
            }
            assertEquals("gone", state)
        }

    @Test
    @Order(10)
    fun `blocklist applies to normal commands`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.patterns("shell_blocklist", listOf("*reboot*")))
            try {
                val error = ShellE2E.callError("shell_exec", ShellE2E.run("echo x; reboot"))

                assertTrue(error.contains("*reboot*"), error)
            } finally {
                ShellE2E.configure(*ShellE2E.patterns("shell_blocklist", emptyList()))
            }
        }

    @Test
    @Order(11)
    fun `running limit`() =
        runBlocking {
            ShellE2E.configure("--ei", "shell_max_running_processes", "1")
            try {
                val id = ShellE2E.call("shell_start", ShellE2E.run("sleep 600")).number("process_id")

                val error = ShellE2E.callError("shell_start", ShellE2E.run("sleep 600"))

                val killed = ShellE2E.call("shell_kill", mapOf("process_id" to id))

                assertTrue(error.contains("1 shell processes are already running"), error)
                assertEquals("killed", killed.text("state"))
            } finally {
                ShellE2E.configure("--ei", "shell_max_running_processes", "16")
            }
        }

    @Test
    @Order(12)
    fun `a server stop kills running commands and the start empties the scratch folder`() =
        runBlocking {
            ShellE2E.call("shell_exec", ShellE2E.run("echo keep > keep.txt"))
            val id = ShellE2E.call("shell_start", ShellE2E.run("sleep 600")).number("process_id")

            ShellE2E.restartServer()

            assertTrue(noProcessWithin("sleep 600", WAIT_MS), "sleep 600 is still running")
            val error = ShellE2E.callError("shell_output", mapOf("process_id" to id))
            assertTrue(error.contains("Unknown process_id"), error)
            assertEquals("", ShellE2E.call("shell_exec", ShellE2E.run("ls -A")).text("output"))
        }

    @Test
    @Order(13)
    fun `elevated command refused while elevation is off`() =
        runBlocking {
            val error = ShellE2E.callError("shell_exec", ShellE2E.run("id -u", elevated = true))

            assertTrue(error.contains("disabled in the app's Shell settings"), error)
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 500L
        const val PAGE_BYTES = 65_536
    }
}
