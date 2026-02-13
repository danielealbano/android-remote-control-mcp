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
 * 3. Verifying the calculation result via accessibility tree
 * 4. Capturing a screenshot and verifying it contains data
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
        assertTrue(result.tools.size >= 29, "Expected at least 29 tools, got: ${result.tools.size}")
    }

    @Test
    @Order(2)
    fun `calculate 7 plus 3 equals 10`() = runBlocking {
        // Step 1: Press home to ensure clean state
        mcpClient.callTool("press_home")
        Thread.sleep(1_000)

        // Step 2: Launch Simple Calculator app via monkey command
        AndroidContainerSetup.launchCalculator(container)
        Thread.sleep(2_000)

        // Step 3: Verify Calculator is visible in accessibility tree
        val tree = mcpClient.callTool("get_accessibility_tree")
        val treeStr = (tree.content[0] as TextContent).text
        println("[E2E Calculator] Accessibility tree excerpt: ${treeStr.take(1000)}")
        assertTrue(
            treeStr.contains("Calculator", ignoreCase = true) ||
                treeStr.contains(CALCULATOR_PACKAGE, ignoreCase = true),
            "Accessibility tree should contain Calculator app. Tree excerpt: ${treeStr.take(500)}",
        )

        // Step 4: Find and click "7" button
        val button7 = findElementWithRetry("text", "7")
        assertNotNull(button7, "Could not find '7' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to button7!!))
        Thread.sleep(500)

        // Step 5: Find and click "+" button
        val buttonPlus = findElementWithRetry("text", "+")
        assertNotNull(buttonPlus, "Could not find '+' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to buttonPlus!!))
        Thread.sleep(500)

        // Step 6: Find and click "3" button
        val button3 = findElementWithRetry("text", "3")
        assertNotNull(button3, "Could not find '3' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to button3!!))
        Thread.sleep(500)

        // Step 7: Find and click "=" button
        val buttonEquals = findElementWithRetry("text", "=")
        assertNotNull(buttonEquals, "Could not find '=' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to buttonEquals!!))
        Thread.sleep(1_000)

        // Step 8: Wait for UI to settle
        mcpClient.callTool("wait_for_idle", mapOf("timeout" to 3000))

        // Step 9: Verify result "10" in accessibility tree
        val resultTree = mcpClient.callTool("get_accessibility_tree")
        val resultTreeStr = (resultTree.content[0] as TextContent).text
        assertTrue(
            resultTreeStr.contains("10"),
            "Result '10' should appear in accessibility tree after 7+3=. Tree excerpt: ${resultTreeStr.take(500)}",
        )
    }

    @Test
    @Order(3)
    fun `capture screenshot returns valid image data`() = runBlocking {
        val screenshot = mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))

        val content = screenshot.content
        assertTrue(content.isNotEmpty(), "Screenshot content should not be empty")

        val imageContent = content[0] as ImageContent

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
                    "find_elements",
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
