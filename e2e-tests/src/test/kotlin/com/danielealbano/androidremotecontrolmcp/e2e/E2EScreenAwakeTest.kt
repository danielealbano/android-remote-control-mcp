package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Exercises the real keep-awake window on the suite's disposable redroid container. */
class E2EScreenAwakeTest {
    private val baseUrl = SharedAndroidContainer.mcpServerUrl
    private lateinit var client: McpClient
    private var screenTimeout: String? = null

    @BeforeEach
    fun prepare() {
        SharedAndroidContainer.ensureAccessibilityService()
        screenTimeout = adb("settings", "get", "system", "screen_off_timeout")
        adb("settings", "put", "system", "screen_off_timeout", "15000")
        unlock()
        stop()
        visualIndicator(false)
    }

    @AfterEach
    fun restore() {
        val cleanup = listOf<() -> Unit>(
            {
                try {
                    stop()
                } catch (failure: Throwable) {
                    runCatching { adb("am", "force-stop", PACKAGE) }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            },
            { configure(0) },
            { visualIndicator(true) },
            {
                screenTimeout?.let {
                    if (it == "null") adb("settings", "delete", "system", "screen_off_timeout")
                    else adb("settings", "put", "system", "screen_off_timeout", it)
                }
            },
            ::unlock,
            ::enableAccessibility,
            ::start,
        )
        val failures = cleanup.mapNotNull { runCatching(it).exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    @Test
    fun `disabled setting is applied only after restart and active hold is hidden from introspection`() = runBlocking {
        configure(0)
        start()
        screenState()
        assertWindow(false)
        configure(GRACE_SECONDS)
        screenState()
        assertWindow(false)
        stop()
        start()

        val call = async(Dispatchers.IO) {
            client.callTool(
                "${PREFIX}wait_for_node",
                mapOf("by" to "text", "value" to "screen-awake-absent-node", "timeout" to 10000),
            )
        }
        awaitCondition("Window must be present while the tool is running") { windowPresent() }
        assertFalse(call.isCompleted, "The window must be observed during the call, before grace starts")
        assertFalse(call.await().isError == true)
        assertWindow(true)
        val text = screenState()
        assertTrue(text.isNotBlank(), "Introspection must return real screen content")
        assertFalse(
            text.lineSequence().any { "ACCESSIBILITY_OVERLAY" in it && PACKAGE in it },
            "The keep-awake window must not add an accessibility window of our package",
        )
        stop()
        assertWindow(false)
    }

    @Test
    fun `grace remains active before deadline and expires without another tool call`() = runBlocking {
        configure(GRACE_SECONDS)
        start()
        screenState()
        assertWindow(true)
        Thread.sleep(BEFORE_EXPIRY_MS)
        assertWindow(true)
        awaitCondition("Grace must expire without further activity", EXPIRY_WAIT_MS) { !windowPresent() }
    }

    @Test
    fun `manual lock discards the old hold across unlock and accessibility reconnect`() = runBlocking {
        configure(GRACE_SECONDS)
        start()
        screenState()
        assertWindow(true)
        adb("input", "keyevent", "KEYCODE_SLEEP")
        awaitCondition("The display must actually sleep") { "mWakefulness=Asleep" in adb("dumpsys", "power") }
        assertWindow(false)
        Thread.sleep(1000)
        unlock()
        assertWindow(false)
        screenState()
        assertWindow(true)
        val graceStarted = System.nanoTime()
        adb("settings", "put", "secure", "enabled_accessibility_services", "null")
        awaitCondition("Accessibility service must disconnect") { !accessibilityBound() }
        adb("input", "keyevent", "KEYCODE_SLEEP")
        awaitCondition("The disconnected display must actually sleep") {
            "mWakefulness=Asleep" in adb("dumpsys", "power")
        }
        unlock()
        enableAccessibility()
        assertWindow(false)
        assertTrue(
            System.nanoTime() - graceStarted < (GRACE_SECONDS - 5) * 1000000000L,
            "Reconnect must complete before normal grace expiry could hide a retained-hold bug",
        )
        screenState()
        assertWindow(true)
    }

    private suspend fun screenState(): String {
        val result = client.callTool("${PREFIX}get_screen_state")
        assertFalse(result.isError == true, "Screen introspection failed: $result")
        return result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    }

    private fun configure(seconds: Int) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", "$SOURCE_PACKAGE.ADB_CONFIGURE",
            "-n", "$PACKAGE/$SOURCE_PACKAGE.services.mcp.AdbConfigReceiver",
            "--ei", "screen_awake_timeout_seconds", seconds.toString(),
        )
    }

    private fun visualIndicator(enabled: Boolean) {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-a", AndroidContainerSetup.E2E_CONFIGURE_ACTION,
            "-n", AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
            "--ez", "tool_call_indicator_enabled", enabled.toString(),
        )
        // The shared debug receiver persists its settings asynchronously.
        Thread.sleep(1000)
    }

    private fun start() {
        AndroidContainerSetup.execAdb("shell", "am", "start", "-W", "-n", AndroidContainerSetup.MAIN_COMPONENT)
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "-a", AndroidContainerSetup.E2E_START_ACTION,
            "-n", AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
        )
        awaitCondition("MCP server must start", SERVER_WAIT_MS) { healthy() }
        client = SharedAndroidContainer.refreshMcpClient()
    }

    private fun stop() {
        AndroidContainerSetup.execAdb(
            "shell", "am", "broadcast", "-a", AndroidContainerSetup.E2E_STOP_ACTION,
            "-n", AndroidContainerSetup.E2E_RECEIVER_COMPONENT,
        )
        awaitCondition("MCP server must stop", SERVER_WAIT_MS) { !healthy() }
        assertWindow(false)
    }

    private fun healthy(): Boolean {
        val connection = URI("$baseUrl/health").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun unlock() {
        adb("input", "keyevent", "KEYCODE_WAKEUP")
        adb("wm", "dismiss-keyguard")
        awaitCondition("The display must be awake and unlocked") {
            "mWakefulness=Awake" in adb("dumpsys", "power") &&
                Regex("(?m)^\\s+showing=false\\s*$").containsMatchIn(adb("dumpsys", "window", "policy"))
        }
    }

    private fun adb(vararg args: String): String = AndroidContainerSetup.execAdb("shell", *args)

    private fun accessibilityBound(): Boolean {
        // Accessibility dumps identify bound services by label, while ActivityManager identifies the component.
        val bound = Regex("Bound services:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
            .find(adb("dumpsys", "accessibility"))?.groupValues?.get(1)
            ?.contains("label=Android Remote Control MCP") == true
        val component = "$PACKAGE/$SOURCE_PACKAGE.services.accessibility.McpAccessibilityService"
        return bound && "hasBound=true" in adb("dumpsys", "activity", "services", component)
    }

    private fun enableAccessibility() {
        AndroidContainerSetup.enableAccessibilityService()
        awaitCondition("Accessibility service must be bound") { accessibilityBound() }
    }

    private fun windowPresent(): Boolean = "MCP screen awake" in adb("dumpsys", "window", "windows")

    private fun assertWindow(expected: Boolean) =
        awaitCondition("Expected keep-awake window present=$expected") { windowPresent() == expected }

    private fun awaitCondition(message: String, timeoutMs: Long = 10000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1000000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue(condition(), message)
    }

    private companion object {
        const val SOURCE_PACKAGE = "com.danielealbano.androidremotecontrolmcp"
        const val PACKAGE = AndroidContainerSetup.APP_PACKAGE
        const val PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        const val GRACE_SECONDS = 60
        const val BEFORE_EXPIRY_MS = 45000L
        const val EXPIRY_WAIT_MS = 20000L
        const val SERVER_WAIT_MS = 30000L
    }
}
