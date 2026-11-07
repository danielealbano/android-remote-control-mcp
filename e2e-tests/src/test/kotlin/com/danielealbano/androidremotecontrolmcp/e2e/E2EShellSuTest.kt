package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
class E2EShellSuTest {
    @BeforeAll
    fun setUp() {
        SharedAndroidContainer.ensureAccessibilityService()
        ShellE2E.restoreDefaults()
        ShellE2E.configure(*ShellE2E.testTimeouts())
        ShellE2E.grantNotifications()
        ShizukuE2E.uninstall()
        TestSuE2E.install()
        ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))
        // Shizuku reaches only an app process started with elevation on (its provider enabled).
        AndroidContainerSetup.execAdb("shell", "am", "force-stop", AndroidContainerSetup.APP_PACKAGE)
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

    private fun elevated(command: String): Map<String, Any?> = ShellE2E.run(command, elevated = true, workdir = ROOT)

    private fun running(args: String): Boolean = StorageE2E.shell("ps -A -o ARGS").lines().any { it.trim() == args }

    @Test
    @Order(1)
    fun `su is used when Shizuku is absent`() =
        runBlocking {
            val result = ShellE2E.call("shell_exec", elevated("echo via-su"))

            assertEquals("su", result.text("backend"))
            assertEquals("via-su\n", result.text("output"))
            assertEquals(0, result.number("exit_code"))
        }

    @Test
    @Order(2)
    fun `exit codes pass through su`() =
        runBlocking {
            assertEquals(7, ShellE2E.call("shell_exec", elevated("exit 7")).number("exit_code"))
        }

    @Test
    @Order(3)
    fun `killing a su process stops it`() =
        runBlocking {
            val id = ShellE2E.call("shell_start", elevated("sleep 600")).number("process_id")

            val killed = ShellE2E.call("shell_kill", mapOf("process_id" to id))

            assertEquals("killed", killed.text("state"))
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (running("sleep 600") && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            assertTrue(!running("sleep 600"), "sleep 600 is still running")
        }

    @Test
    @Order(4)
    fun `Shizuku has priority over su`() =
        runBlocking {
            ShizukuE2E.install()
            ShizukuE2E.grant()
            ShizukuE2E.startAsShell()
            ShellE2E.restartServer()
            ShellE2E.awaitElevatedReady(backend = "shizuku", workdir = ROOT)

            assertEquals("shizuku", ShellE2E.call("shell_exec", elevated("id -u")).text("backend"))
        }

    private companion object {
        const val ROOT = "/"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 500L
    }
}
