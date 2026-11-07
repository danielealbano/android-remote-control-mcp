package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EShellShizukuTest {
    @BeforeAll
    fun setUp() {
        SharedAndroidContainer.ensureAccessibilityService()
        ShellE2E.restoreDefaults()
        ShellE2E.configure(*ShellE2E.testTimeouts())
        ShellE2E.grantNotifications()
        ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))
        ShizukuE2E.uninstall()
        ShizukuE2E.revoke()
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

    private fun forceStopApp() {
        AndroidContainerSetup.execAdb("shell", "am", "force-stop", AndroidContainerSetup.APP_PACKAGE)
    }

    private fun awaitProviderState(expected: String) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (ShizukuE2E.providerState() != expected && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals(expected, ShizukuE2E.providerState())
    }

    private fun running(args: String): Boolean = StorageE2E.shell("ps -A -o ARGS").lines().any { it.trim() == args }

    private fun appPid(): String = StorageE2E.shell("pidof ${AndroidContainerSetup.APP_PACKAGE} || true").trim()

    private fun restartAppFromShellSettings() {
        val before = appPid()
        AndroidContainerSetup.execAdb("shell", "am", "start", "-n", AndroidContainerSetup.MAIN_COMPONENT)
        DeviceUiE2E.tapWhenVisible("Settings")
        DeviceUiE2E.tapWhenVisible("Shell")
        DeviceUiE2E.tapWhenVisible("Restart app")
        val deadline = System.currentTimeMillis() + LONG_WAIT_MS
        while (appPid().let { it.isEmpty() || it == before } && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertNotEquals(before, appPid())
        AndroidContainerSetup.waitForServerReady(SharedAndroidContainer.mcpServerUrl)
        SharedAndroidContainer.refreshMcpClient()
    }

    private fun noProcessWithin(
        args: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && running(args)) Thread.sleep(POLL_MS)
        return !running(args)
    }

    private suspend fun elevatedId(): JsonObject = ShellE2E.call("shell_exec", ShellE2E.run("id -u", elevated = true))

    @Test
    @Order(1)
    fun `the provider follows the elevation switch and is restored at app start`() {
        assertEquals("enabled", ShizukuE2E.providerState())

        forceStopApp()
        ShizukuE2E.disableProviderBehindTheApp()

        assertEquals("disabled", ShizukuE2E.providerState())

        ShellE2E.restartServer()

        awaitProviderState("enabled")

        ShellE2E.configure(*ShellE2E.switches(elevation = false, autoAllow = true))

        assertEquals("disabled", ShizukuE2E.providerState())

        ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))

        assertEquals("enabled", ShizukuE2E.providerState())
    }

    @Test
    @Order(2)
    fun `Shizuku not installed`() =
        runBlocking {
            val error = ShellE2E.callError("shell_exec", ShellE2E.run("id -u", elevated = true))

            assertTrue(error.contains("Shizuku is not installed"), error)
            assertTrue(error.contains("su was not found"), error)
        }

    @Test
    @Order(3)
    fun `installed but not running`() =
        runBlocking {
            ShizukuE2E.install()

            ShellE2E.awaitElevatedError("Shizuku is not running")
            Unit
        }

    @Test
    @Order(4)
    fun `running but not allowed`() =
        runBlocking {
            ShizukuE2E.startAsShell()

            ShellE2E.awaitElevatedError("has not granted access")
            Unit
        }

    @Test
    @Order(5)
    fun `allowed - runs as the adb user`() =
        runBlocking {
            ShizukuE2E.grant()
            ShizukuE2E.startAsShell()
            ShellE2E.awaitElevatedReady()

            val result = elevatedId()

            assertEquals("shizuku", result.text("backend"))
            assertEquals(2000, result.number("uid"))
            assertEquals("2000", result.text("output").trim())
        }

    @Test
    @Order(6)
    fun `elevation turned on after the app started connects Shizuku after Restart app`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.switches(elevation = false, autoAllow = true))
            forceStopApp()
            ShellE2E.restartServer()
            ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))
            delay(CONNECT_WAIT_MS)

            val error = ShellE2E.callError("shell_exec", ShellE2E.run("id -u", elevated = true))

            assertTrue(error.contains("tap Restart app"), error)

            restartAppFromShellSettings()
            ShellE2E.awaitElevatedReady()
        }

    @Test
    @Order(7)
    fun `back-to-back elevated commands rebind the helper`() =
        runBlocking {
            repeat(BACK_TO_BACK) { round ->
                val result = elevatedId()

                assertEquals(0, result.getValue("exit_code").jsonPrimitive.intOrNull, "round $round")
                assertEquals("2000", result.text("output").trim(), "round $round")
            }
        }

    @Test
    @Order(8)
    fun `scratch folder is shared both ways`() =
        runBlocking {
            ShellE2E.call("shell_exec", ShellE2E.run("mkdir -p d1 && echo app > d1/a.txt"))

            val elevated =
                ShellE2E.call("shell_exec", ShellE2E.run("cat d1/a.txt && echo shell > d1/b.txt", elevated = true))
            val normal = ShellE2E.call("shell_exec", ShellE2E.run("cat d1/b.txt"))

            assertEquals("app\n", elevated.text("output"))
            assertEquals("shell\n", normal.text("output"))
        }

    @Test
    @Order(9)
    fun `binaries run from data local tmp through elevation`() =
        runBlocking {
            val command =
                "mkdir -p /data/local/tmp/e2e && cp /system/bin/toybox /data/local/tmp/e2e/toybox && " +
                    "/data/local/tmp/e2e/toybox echo ok; rm -rf /data/local/tmp/e2e"

            val result = ShellE2E.call("shell_exec", ShellE2E.run(command, elevated = true))

            assertEquals("ok\n", result.text("output"))
        }

    @Test
    @Order(10)
    fun `the helper stops after the job`() =
        runBlocking {
            ShellE2E.call("shell_exec", ShellE2E.run("true", elevated = true))

            ShizukuE2E.awaitHelperGone()
        }

    @Test
    @Order(11)
    fun `a background elevated process keeps the helper until killed`() =
        runBlocking {
            val id = ShellE2E.call("shell_start", ShellE2E.run("sleep 600", elevated = true)).number("process_id")

            assertTrue(ShizukuE2E.isHelperRunning())

            val killed = ShellE2E.call("shell_kill", mapOf("process_id" to id))

            assertEquals("killed", killed.text("state"))
            ShizukuE2E.awaitHelperGone()
        }

    @Test
    @Order(12)
    fun `the app dying stops its elevated processes`() =
        runBlocking {
            ShellE2E.call("shell_start", ShellE2E.run("sleep 600", elevated = true))

            assertTrue(ShizukuE2E.isHelperRunning())

            forceStopApp()

            assertTrue(noProcessWithin("sleep 600", LONG_WAIT_MS), "sleep 600 is still running")
            ShizukuE2E.awaitHelperGone()
            ShellE2E.restartServer()
            ShellE2E.awaitElevatedReady()
        }

    @Test
    @Order(13)
    fun `Shizuku stopping ends running elevated processes`() =
        runBlocking {
            val id = ShellE2E.call("shell_start", ShellE2E.run("sleep 600", elevated = true)).number("process_id")

            ShizukuE2E.stop()
            val end = ShellE2E.awaitEnd(id)

            val failed = end.text("state") == "failed" && end.text("failure") == "Shizuku stopped"
            val exitCode = end.getValue("exit_code").jsonPrimitive.intOrNull
            val exitedNonZero = end.text("state") == "exited" && exitCode != null && exitCode != 0
            assertTrue(failed || exitedNonZero, end.toString())
            assertTrue(noProcessWithin("sleep 600", LONG_WAIT_MS), "sleep 600 is still running")
            ShellE2E.awaitElevatedError("Shizuku is not running")
            Unit
        }

    @Test
    @Order(14)
    fun `root mode reports uid 0`() =
        runBlocking {
            ShizukuE2E.startAsRoot()
            ShellE2E.awaitElevatedReady()

            assertEquals(0, elevatedId().number("uid"))
        }

    private companion object {
        const val WAIT_MS = 15_000L
        const val LONG_WAIT_MS = 15_000L
        const val POLL_MS = 500L
        const val CONNECT_WAIT_MS = 5_000L
        const val BACK_TO_BACK = 5
    }
}
