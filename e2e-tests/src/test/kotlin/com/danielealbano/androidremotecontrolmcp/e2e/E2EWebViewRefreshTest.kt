package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: WebView Accessibility Tree Refresh
 *
 * Verifies that the MCP server returns fresh accessibility tree data for
 * WebView-based apps. WebView creates virtual accessibility nodes via
 * AccessibilityNodeProvider for each DOM element. These nodes can go stale
 * when page content changes via JavaScript.
 *
 * This test validates that the viewIdResourceName == null branch of the
 * conditional refresh in AccessibilityTreeParser catches WebView virtual
 * nodes (which lack resource IDs and don't have Compose extra data).
 *
 * Test flow:
 * 1. Launch a WebView activity that displays "Number: 0"
 * 2. Verify the initial value via get_screen_state
 * 3. Send intents to update the number 5 times via evaluateJavascript
 * 4. After each change, verify the new value is visible in get_screen_state
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EWebViewRefreshTest {

    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
        private const val APP_LAUNCH_TIMEOUT_MS = 30_000L
        private const val NUMBER_UPDATE_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val UPDATE_COUNT = 5
    }

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    @Order(1)
    fun `webview shows initial value zero`() = runBlocking {
        mcpClient.callTool("${TOOL_PREFIX}press_home")
        Thread.sleep(1_000)

        AndroidContainerSetup.launchWebViewTestApp()

        val screenText = waitForTextInScreenState("Number: 0", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(
            screenText,
            "WebView test app should show 'Number: 0' within ${APP_LAUNCH_TIMEOUT_MS}ms",
        )
    }

    @Test
    @Order(2)
    fun `accessibility tree reflects webview dom changes via javascript`() = runBlocking {
        AndroidContainerSetup.launchWebViewTestApp()
        val initialScreen = waitForTextInScreenState("Number:", APP_LAUNCH_TIMEOUT_MS)
        assertNotNull(initialScreen, "WebView test app should be visible")

        for (i in 1..UPDATE_COUNT) {
            AndroidContainerSetup.sendWebViewTestNumber(i)

            // Allow time for JS execution and accessibility tree update
            Thread.sleep(1_000)

            val expectedText = "Number: $i"
            val screenText = waitForTextInScreenState(expectedText, NUMBER_UPDATE_TIMEOUT_MS)

            if (screenText == null) {
                val logcat = AndroidContainerSetup.dumpWebViewTestAppLogs()
                println("[E2E WebViewRefresh] Logcat from WebViewTestApp:\n$logcat")

                val diagnosticResult = mcpClient.callTool("${TOOL_PREFIX}get_screen_state")
                val diagnosticText = (diagnosticResult.content[0] as? TextContent)?.text ?: "N/A"
                fail<Unit>(
                    "Accessibility tree did not reflect '$expectedText' within " +
                        "${NUMBER_UPDATE_TIMEOUT_MS}ms (update $i of $UPDATE_COUNT). " +
                        "Logcat:\n$logcat\n" +
                        "Screen state excerpt: ${diagnosticText.take(1000)}",
                )
            }

            println("[E2E WebViewRefresh] Update $i/$UPDATE_COUNT: '$expectedText' confirmed in tree")
        }
    }

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
