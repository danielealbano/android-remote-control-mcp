package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: Calculator App (7 + 3 = 10)
 *
 * This test verifies the entire MCP stack by:
 * 1. Using the shared Docker Android emulator (via [SharedAndroidContainer])
 * 2. Using MCP tools to interact with the Simple Calculator app
 * 3. Verifying the calculation result via get_screen_state compact TSV output
 * 4. Verifying get_screen_state with include_screenshot returns valid image data
 *
 * Uses [SharedAndroidContainer] singleton to share the Docker container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 *
 * The Simple Calculator app (com.simplemobiletools.calculator) is installed
 * during container setup from test resources.
 *
 * Requires Docker to be available on the host machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2ECalculatorTest {

    private val container = SharedAndroidContainer.container
    private val mcpClient = SharedAndroidContainer.mcpClient

    companion object {
        /**
         * Maximum time to wait for element search before giving up.
         */
        private const val ELEMENT_WAIT_TIMEOUT_MS = 10_000L

        /**
         * Simple Calculator package name (installed from test resources).
         */
        private const val CALCULATOR_PACKAGE = "com.simplemobiletools.calculator"
    }

    @Test
    @Order(1)
    fun `MCP server lists all available tools`() = runBlocking {
        val result = mcpClient.listTools()
        assertTrue(result.tools.size >= 27, "Expected at least 27 tools, got: ${result.tools.size}")
    }

    @Test
    @Order(2)
    fun `calculate 7 plus 3 equals 10`() = runBlocking {
        // Step 1: Press home to ensure clean state
        mcpClient.callTool("android_press_home")
        Thread.sleep(1_000)

        // Step 2: Launch Simple Calculator app via monkey command
        AndroidContainerSetup.launchCalculator(container)
        Thread.sleep(2_000)

        // Step 3: Verify Calculator is visible in screen state
        val tree = mcpClient.callTool("android_get_screen_state")
        val treeStr = (tree.content[0] as TextContent).text
        println("[E2E Calculator] Screen state excerpt: ${treeStr.take(1000)}")
        assertTrue(
            treeStr.contains("Calculator", ignoreCase = true) ||
                treeStr.contains(CALCULATOR_PACKAGE, ignoreCase = true),
            "Screen state should contain Calculator app. Excerpt: ${treeStr.take(500)}",
        )
        // Verify new note lines
        assertTrue(
            treeStr.contains("note:flags: on=onscreen off=offscreen"),
            "Screen state should contain flags legend note line",
        )
        assertTrue(
            treeStr.contains("note:offscreen items require scroll_to_element before interaction"),
            "Screen state should contain offscreen hint note line",
        )
        // Verify new comma-separated flag format (at least one element should be on-screen + clickable + enabled)
        assertTrue(
            treeStr.contains("on,clk") || treeStr.contains("on,ena"),
            "Screen state flags should use comma-separated abbreviations",
        )
        // Negative assertions: old single-char format must not appear
        assertFalse(
            treeStr.contains("\tvcn") || treeStr.contains("\tvclfsen") || treeStr.contains("\tvn"),
            "Screen state should NOT contain old single-char flag format",
        )

        // Step 4: Find and click "7" button
        val button7 = findElementWithRetry("text", "7")
        assertNotNull(button7, "Could not find '7' button in Calculator")
        mcpClient.callTool("android_click_element", mapOf("element_id" to button7!!))
        Thread.sleep(500)

        // Step 5: Find and click "+" button
        val buttonPlus = findElementWithRetry("text", "+")
        assertNotNull(buttonPlus, "Could not find '+' button in Calculator")
        mcpClient.callTool("android_click_element", mapOf("element_id" to buttonPlus!!))
        Thread.sleep(500)

        // Step 6: Find and click "3" button
        val button3 = findElementWithRetry("text", "3")
        assertNotNull(button3, "Could not find '3' button in Calculator")
        mcpClient.callTool("android_click_element", mapOf("element_id" to button3!!))
        Thread.sleep(500)

        // Step 7: Find and click "=" button
        val buttonEquals = findElementWithRetry("text", "=")
        assertNotNull(buttonEquals, "Could not find '=' button in Calculator")
        mcpClient.callTool("android_click_element", mapOf("element_id" to buttonEquals!!))
        Thread.sleep(1_000)

        // Step 8: Wait for UI to settle
        mcpClient.callTool("android_wait_for_idle", mapOf("timeout" to 3000))

        // Step 9: Verify result "10" in screen state
        val resultTree = mcpClient.callTool("android_get_screen_state")
        val resultTreeStr = (resultTree.content[0] as TextContent).text
        assertTrue(
            resultTreeStr.contains("10"),
            "Result '10' should appear in screen state after 7+3=. Excerpt: ${resultTreeStr.take(500)}",
        )
    }

    @Test
    @Order(3)
    fun `get_screen_state with screenshot returns valid image data`() = runBlocking {
        val result = mcpClient.callTool(
            "android_get_screen_state",
            mapOf("include_screenshot" to true),
        )

        val content = result.content
        assertTrue(
            content.size >= 2,
            "Result should have at least 2 content items (text + image), got: ${content.size}",
        )

        // First item is TextContent with compact TSV
        assertTrue(content[0] is TextContent, "First content item should be TextContent")

        // Second item is ImageContent with JPEG data
        val imageContent = content[1] as ImageContent

        val mimeType = imageContent.mimeType
        assertTrue(mimeType == "image/jpeg", "Screenshot mimeType should be 'image/jpeg', got: $mimeType")

        val data = imageContent.data
        assertNotNull(data, "Screenshot data should not be null")
        assertFalse(data.isEmpty(), "Screenshot data should not be empty")
        assertTrue(data.length > 100, "Screenshot data should be substantial (got ${data.length} chars)")
    }

    /**
     * Find an element by criteria, retrying up to ELEMENT_WAIT_TIMEOUT_MS.
     * Returns the element_id of the first match, or null if not found.
     *
     * The find_elements tool returns a [CallToolResult] where the first content
     * item is a [TextContent] containing a JSON-serialized string with the
     * elements array:
     * ```json
     * {"elements":[...]}
     * ```
     */
    private suspend fun findElementWithRetry(by: String, value: String): String? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < ELEMENT_WAIT_TIMEOUT_MS) {
            try {
                val result = mcpClient.callTool(
                    "android_find_elements",
                    mapOf("by" to by, "value" to value, "exact_match" to true),
                )

                val textContent = (result.content[0] as? TextContent)?.text
                if (textContent != null) {
                    // Parse the inner JSON string
                    val innerJson = Json.parseToJsonElement(textContent).jsonObject
                    val elements = innerJson["elements"]?.jsonArray
                    if (elements != null && elements.isNotEmpty()) {
                        return elements[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    }
                }
            } catch (_: Exception) {
                // Element not found yet, retry
            }
            Thread.sleep(500)
        }

        return null
    }
}
