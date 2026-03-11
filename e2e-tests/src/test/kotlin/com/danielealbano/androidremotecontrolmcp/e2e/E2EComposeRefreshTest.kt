package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: Compose Accessibility Tree Refresh
 *
 * Verifies that the MCP server returns fresh accessibility tree data for
 * Jetpack Compose apps. This test catches stale-node issues where the
 * accessibility framework caches node snapshots and fails to reflect
 * UI changes made by Compose recomposition.
 *
 * Test flow:
 * 1. Launch a minimal Compose app that displays "Number: 0"
 * 2. Verify the initial value via get_screen_state
 * 3. Send broadcast intents to change the number 5 times
 * 4. After each change, verify the new value is visible in get_screen_state
 *
 * The compose test app (com.danielealbano.composetestapp) is built from
 * the compose-test-app module and installed during container setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EComposeRefreshTest {

    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val COMPOSE_TEST_PACKAGE = "com.danielealbano.composetestapp"
        private const val APP_LAUNCH_TIMEOUT_MS = 30_000L

        /**
         * Maximum time to wait for the accessibility tree to reflect a number change.
         */
        private const val NUMBER_UPDATE_TIMEOUT_MS = 15_000L

        /**
         * Polling interval when waiting for tree updates.
         */
        private const val POLL_INTERVAL_MS = 500L

        /**
         * Number of sequential updates to test.
         */
        private const val UPDATE_COUNT = 5
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    @Order(1)
    fun `compose app shows initial value zero`() = runBlocking {
        // Press home first to ensure clean state
        mcpClient.callTool("${TOOL_PREFIX}press_home")
        Thread.sleep(1_000)

        // Launch the compose test app
        AndroidContainerSetup.launchComposeTestApp()

        // Wait for the app to be visible with initial value
        val screenText = waitForTextInScreenState("Number: 0", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(
            screenText,
            "Compose test app should show 'Number: 0' within ${APP_LAUNCH_TIMEOUT_MS}ms",
        )
    }

    @Test
    @Order(2)
    fun `accessibility tree reflects compose state changes via broadcast`() = runBlocking {
        // Ensure the compose test app is in the foreground
        AndroidContainerSetup.launchComposeTestApp()
        val initialScreen = waitForTextInScreenState("Number:", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(initialScreen, "Compose test app should be visible")

        for (i in 1..UPDATE_COUNT) {
            // Send broadcast to update the number
            AndroidContainerSetup.sendComposeTestNumber(i)

            // Small delay to allow Compose recomposition to occur
            Thread.sleep(500)

            // Verify the new number is reflected in the accessibility tree
            val expectedText = "Number: $i"
            val screenText = waitForTextInScreenState(expectedText, NUMBER_UPDATE_TIMEOUT_MS)

            if (screenText == null) {
                // Dump logcat from the compose test app for diagnostics
                val logcat = AndroidContainerSetup.dumpComposeTestAppLogs()
                println("[E2E ComposeRefresh] Logcat from ComposeTestApp:\n$logcat")

                // Get one more screen state for diagnostic output
                val diagnosticResult = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                val diagnosticText = (diagnosticResult.content[0] as? TextContent)?.text ?: "N/A"
                fail<Unit>(
                    "Accessibility tree did not reflect '$expectedText' within " +
                        "${NUMBER_UPDATE_TIMEOUT_MS}ms (update $i of $UPDATE_COUNT). " +
                        "Logcat:\n$logcat\n" +
                        "Screen state excerpt: ${diagnosticText.take(1000)}",
                )
            }

            println("[E2E ComposeRefresh] Update $i/$UPDATE_COUNT: '$expectedText' confirmed in tree")
        }
    }

    /**
     * Polls get_screen_state until [expectedText] appears in the output,
     * or [timeoutMs] elapses.
     *
     * @return the full screen state text if found, null if timed out
     */
    private suspend fun waitForTextInScreenState(
        expectedText: String,
        timeoutMs: Long,
    ): String? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val result = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                if (result.isError != true) {
                    val text = (result.content[0] as? TextContent)?.text ?: ""
                    if (text.contains(expectedText)) {
                        return text
                    }
                }
            } catch (_: Exception) {
                // Transient error, retry
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }
}
