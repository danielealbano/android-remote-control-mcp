package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class E2EShellPermissionTest {
    @BeforeAll
    fun setUp() =
        runBlocking {
            SharedAndroidContainer.ensureAccessibilityService()
            ShellE2E.restoreDefaults()
            ShellE2E.configure(*ShellE2E.testTimeouts())
            ShellE2E.grantNotifications()
            ShizukuE2E.install()
            ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))
            ShizukuE2E.grant()
            ShizukuE2E.startAsShell()
            ShellE2E.restartServer()
            ShellE2E.awaitElevatedReady()
            ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = false))
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

    private fun elevatedId(): Map<String, Any?> = ShellE2E.run("id -u", elevated = true)

    @Test
    @Order(1)
    fun `elevation off refuses`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.switches(elevation = false, autoAllow = false))
            try {
                val error = ShellE2E.callError("shell_exec", elevatedId())

                assertTrue(error.contains("disabled"), error)
            } finally {
                ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = false))
            }
        }

    @Test
    @Order(2)
    fun `auto-allow runs without asking`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = true))
            try {
                val result = ShellE2E.call("shell_exec", elevatedId())

                assertEquals(
                    "2000",
                    result
                        .getValue("output")
                        .jsonPrimitive.content
                        .trim(),
                )
            } finally {
                ShellE2E.configure(*ShellE2E.switches(elevation = true, autoAllow = false))
            }
        }

    @Test
    @Order(3)
    fun `blocklist beats auto-allow, also after a separator`() =
        runBlocking {
            ShellE2E.configure(
                *ShellE2E.switches(elevation = true, autoAllow = true),
                *ShellE2E.patterns("shell_blocklist", listOf("*reboot*")),
            )
            try {
                val error = ShellE2E.callError("shell_exec", ShellE2E.run("id; echo reboot", elevated = true))

                assertTrue(error.contains("*reboot*"), error)
            } finally {
                ShellE2E.configure(
                    *ShellE2E.switches(elevation = true, autoAllow = false),
                    *ShellE2E.patterns("shell_blocklist", emptyList()),
                )
            }
        }

    @Test
    @Order(4)
    fun `allowlist match runs without asking`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.patterns("shell_allowlist", listOf("id -u")))
            try {
                val started = System.currentTimeMillis()

                val result = ShellE2E.call("shell_exec", elevatedId())
                val elapsedMs = System.currentTimeMillis() - started

                assertEquals(
                    "2000",
                    result
                        .getValue("output")
                        .jsonPrimitive.content
                        .trim(),
                )
                assertTrue(elapsedMs < QUICK_MS, "took $elapsedMs ms")
            } finally {
                ShellE2E.configure(*ShellE2E.patterns("shell_allowlist", emptyList()))
            }
        }

    @Test
    @Order(5)
    fun `approve from the notification action`() =
        runBlocking {
            val call = async(Dispatchers.IO) { ShellE2E.call("shell_exec", elevatedId()) }

            DeviceUiE2E.expandNotifications()
            DeviceUiE2E.tapWhenVisible("Approve")
            val result = call.await()
            DeviceUiE2E.collapseNotifications()

            assertEquals(2000, result.getValue("uid").jsonPrimitive.int)
        }

    @Test
    @Order(6)
    fun `deny from the modal`() =
        runBlocking {
            val call = async(Dispatchers.IO) { ShellE2E.callError("shell_exec", elevatedId()) }

            DeviceUiE2E.expandNotifications()
            DeviceUiE2E.tapWhenVisible("Approve an elevated command?")
            DeviceUiE2E.awaitVisible("Reason given by the client")
            DeviceUiE2E.tapWhenVisible("Deny")
            val error = call.await()

            assertTrue(error.contains("denied"), error)
        }

    @Test
    @Order(7)
    fun `dismissing the notification denies`() =
        runBlocking {
            val started = System.currentTimeMillis()
            val call = async(Dispatchers.IO) { ShellE2E.callError("shell_exec", elevatedId()) }

            DeviceUiE2E.expandNotifications()
            DeviceUiE2E.swipeAwayWhenVisible("Approve an elevated command?")
            val error = call.await()
            val elapsedMs = System.currentTimeMillis() - started
            DeviceUiE2E.collapseNotifications()

            assertTrue(error.contains("denied"), error)
            assertTrue(elapsedMs < ShellE2E.TEST_APPROVAL_SECONDS * MILLIS_PER_SECOND, "took $elapsedMs ms")
        }

    @Test
    @Order(8)
    fun `stopping the server answers a pending approval`() =
        runBlocking {
            val call = async(Dispatchers.IO) { runCatching { ShellE2E.callError("shell_exec", elevatedId()) } }

            DeviceUiE2E.expandNotifications()
            DeviceUiE2E.awaitVisible("Approve an elevated command?")
            DeviceUiE2E.collapseNotifications()
            AndroidContainerSetup.stopMcpServer(SharedAndroidContainer.mcpServerUrl)
            val outcome = withTimeout(QUICK_MS) { call.await() }

            val answered = outcome.getOrNull()?.contains("server is stopping") == true
            assertTrue(answered || outcome.isFailure, outcome.toString())
            ShellE2E.restartServer()
            Unit
        }

    @Test
    @Order(9)
    fun `separator in the command needs approval, unanswered request times out`() =
        runBlocking {
            ShellE2E.configure(*ShellE2E.patterns("shell_allowlist", listOf("ls -* /data")))
            try {
                val error = ShellE2E.callError("shell_exec", ShellE2E.run("ls -d; id -u /data", elevated = true))

                assertTrue(
                    error.contains(
                        "did not answer the approval request within ${ShellE2E.TEST_APPROVAL_SECONDS} seconds",
                    ),
                    error,
                )
            } finally {
                ShellE2E.configure(*ShellE2E.patterns("shell_allowlist", emptyList()))
            }
        }

    @Test
    @Order(10)
    fun `notifications off refuses at once`() =
        runBlocking {
            ShellE2E.revokeNotifications()
            ShellE2E.restartServer()
            try {
                val started = System.currentTimeMillis()

                val error = ShellE2E.callError("shell_exec", elevatedId())
                val elapsedMs = System.currentTimeMillis() - started

                assertTrue(error.contains("notifications are off"), error)
                assertTrue(elapsedMs < AT_ONCE_MS, "took $elapsedMs ms")
            } finally {
                ShellE2E.grantNotifications()
                ShellE2E.restartServer()
            }
        }

    private companion object {
        const val QUICK_MS = 10_000L
        const val AT_ONCE_MS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
