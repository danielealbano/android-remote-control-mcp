package com.danielealbano.androidremotecontrolmcp.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
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
 * 2. Using MCP tools to interact with the Calculator app
 * 3. Verifying the calculation result via accessibility tree
 * 4. Capturing a screenshot and verifying it contains data
 *
 * Uses [SharedAndroidContainer] singleton to share the Docker container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 *
 * Requires Docker to be available on the host machine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2ECalculatorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val container = SharedAndroidContainer.container
    private val mcpClient = SharedAndroidContainer.mcpClient
    private val baseUrl = SharedAndroidContainer.mcpServerUrl

    companion object {
        /**
         * Maximum time to wait for element search before giving up.
         */
        private const val ELEMENT_WAIT_TIMEOUT_MS = 10_000L

        /**
         * Known calculator activity component names across different Android emulator images.
         * The first one that launches successfully will be used.
         */
        private val CALCULATOR_COMPONENTS = listOf(
            "com.google.android.calculator/com.android.calculator2.Calculator",
            "com.android.calculator2/.Calculator",
        )
    }

    @Test
    @Order(1)
    fun `MCP server health check returns healthy`() {
        val health = mcpClient.healthCheck()
        val status = health["status"]?.jsonPrimitive?.contentOrNull
        assertTrue(status == "healthy", "Health check should return 'healthy', got: $status")
    }

    @Test
    @Order(2)
    fun `MCP server lists all available tools`() {
        val tools = mcpClient.listTools()
        assertTrue(tools.size >= 29, "Expected at least 29 tools, got: ${tools.size}")
    }

    @Test
    @Order(3)
    @Disabled("Calculator app may not be installed in Docker Android emulator (budtmo/docker-android)")
    fun `calculate 7 plus 3 equals 10`() {
        // Step 1: Press home to ensure clean state
        mcpClient.callTool("press_home")
        Thread.sleep(1_000)

        // Step 2: Launch Calculator app via adb (more reliable than tapping launcher)
        // Try multiple known calculator component names for different emulator images
        var launched = false
        for (component in CALCULATOR_COMPONENTS) {
            val result = container.execInContainer(
                "adb", "shell", "am", "start", "-n", component
            )
            val output = result.stdout + result.stderr
            println("[E2E Calculator] am start -n $component => exit=${result.exitCode} output=$output")
            if (!output.contains("Error") && !output.contains("error")) {
                launched = true
                break
            }
        }
        assertTrue(launched, "Could not launch any known calculator app: $CALCULATOR_COMPONENTS")
        Thread.sleep(3_000)

        // Step 3: Verify Calculator is visible in accessibility tree
        val tree = mcpClient.callTool("get_accessibility_tree")
        val treeStr = tree.toString()
        println("[E2E Calculator] Accessibility tree excerpt: ${treeStr.take(1000)}")
        assertTrue(
            treeStr.contains("alculator", ignoreCase = true) ||
                treeStr.contains("digit", ignoreCase = true),
            "Accessibility tree should contain Calculator app. Tree excerpt: ${treeStr.take(500)}"
        )

        // Step 4: Find and click "7" button
        val button7 = findElementWithRetry("text", "7")
            ?: findElementWithRetry("content_desc", "7")
        assertNotNull(button7, "Could not find '7' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to button7!!))
        Thread.sleep(500)

        // Step 5: Find and click "+" button
        val buttonPlus = findElementWithRetry("text", "+")
            ?: findElementWithRetry("content_desc", "plus")
            ?: findElementWithRetry("content_desc", "+")
        assertNotNull(buttonPlus, "Could not find '+' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to buttonPlus!!))
        Thread.sleep(500)

        // Step 6: Find and click "3" button
        val button3 = findElementWithRetry("text", "3")
            ?: findElementWithRetry("content_desc", "3")
        assertNotNull(button3, "Could not find '3' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to button3!!))
        Thread.sleep(500)

        // Step 7: Find and click "=" button
        val buttonEquals = findElementWithRetry("text", "=")
            ?: findElementWithRetry("content_desc", "equals")
            ?: findElementWithRetry("content_desc", "=")
        assertNotNull(buttonEquals, "Could not find '=' button in Calculator")
        mcpClient.callTool("click_element", mapOf("element_id" to buttonEquals!!))
        Thread.sleep(1_000)

        // Step 8: Wait for UI to settle
        mcpClient.callTool("wait_for_idle", mapOf("timeout" to 3000))

        // Step 9: Verify result "10" in accessibility tree
        val resultTree = mcpClient.callTool("get_accessibility_tree")
        val resultTreeStr = resultTree.toString()
        assertTrue(
            resultTreeStr.contains("10"),
            "Result '10' should appear in accessibility tree after 7+3=. Tree excerpt: ${resultTreeStr.take(500)}"
        )
    }

    @Test
    @Order(4)
    @Disabled("MediaProjection requires user UI consent, unavailable in Docker emulator")
    fun `capture screenshot returns valid image data`() {
        val screenshot = mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))

        // Navigate the MCP content array: result -> content[0] -> fields
        val contentArray = screenshot["content"]?.jsonArray
        assertNotNull(contentArray, "Screenshot result should have 'content' array")
        assertTrue(contentArray!!.isNotEmpty(), "Screenshot content array should not be empty")

        val imageObj = contentArray[0].jsonObject

        val mimeType = imageObj["mimeType"]?.jsonPrimitive?.contentOrNull
        assertTrue(mimeType == "image/jpeg", "Screenshot mimeType should be 'image/jpeg', got: $mimeType")

        val data = imageObj["data"]?.jsonPrimitive?.contentOrNull
        assertNotNull(data, "Screenshot data should not be null")
        assertFalse(data!!.isEmpty(), "Screenshot data should not be empty")
        assertTrue(data.length > 100, "Screenshot data should be substantial (got ${data.length} chars)")

        val width = imageObj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val height = imageObj["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        assertNotNull(width, "Screenshot width should be present")
        assertNotNull(height, "Screenshot height should be present")
        assertTrue(width!! > 0, "Screenshot width should be > 0, got: $width")
        assertTrue(height!! > 0, "Screenshot height should be > 0, got: $height")
    }

    /**
     * Find an element by criteria, retrying up to ELEMENT_WAIT_TIMEOUT_MS.
     * Returns the element_id of the first match, or null if not found.
     *
     * The find_elements tool returns:
     * ```json
     * {"content": [{"type": "text", "text": "{\"elements\":[...]}"}]}
     * ```
     * The inner text is a JSON-serialized string containing the elements array.
     */
    private fun findElementWithRetry(by: String, value: String): String? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < ELEMENT_WAIT_TIMEOUT_MS) {
            try {
                val result = mcpClient.callTool(
                    "find_elements",
                    mapOf("by" to by, "value" to value, "exact_match" to true)
                )

                // Navigate content array to get the text field
                val contentArray = result["content"]?.jsonArray
                if (contentArray != null && contentArray.isNotEmpty()) {
                    val textContent = contentArray[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    if (textContent != null) {
                        // Parse the inner JSON string
                        val innerJson = json.parseToJsonElement(textContent).jsonObject
                        val elements = innerJson["elements"]?.jsonArray
                        if (elements != null && elements.isNotEmpty()) {
                            return elements[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        }
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
