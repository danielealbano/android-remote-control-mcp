package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: Screenshot capture verification.
 *
 * Verifies that the get_screen_state MCP tool with include_screenshot=true
 * returns both compact TSV text and valid JPEG screenshot data.
 *
 * Uses [SharedAndroidContainer] singleton to share the Docker container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EScreenshotTest {

    private val mcpClient = SharedAndroidContainer.mcpClient

    @Test
    @Order(1)
    fun `get_screen_state with screenshot returns valid JPEG data`() = runBlocking {
        // Navigate to home screen first
        mcpClient.callTool("android_press_home")
        Thread.sleep(1_000)

        val result = mcpClient.callTool(
            "android_get_screen_state",
            mapOf("include_screenshot" to true),
        )

        assertNotNull(result.content, "Result should have content")
        assertTrue(
            result.content.size >= 2,
            "Result should have at least 2 content items (text + image), got: ${result.content.size}",
        )

        // Verify first content item is TextContent with compact TSV
        val textContent = result.content[0]
        assertTrue(textContent is TextContent, "First content item should be TextContent")
        val text = (textContent as TextContent).text
        assertTrue(text.contains("note:"), "Text should contain note line")
        assertTrue(text.contains("app:"), "Text should contain app line")
        assertTrue(
            text.contains("note:flags: on=onscreen off=offscreen"),
            "Text should contain flags legend note line",
        )
        assertTrue(
            text.contains("note:offscreen items require scroll_to_element before interaction"),
            "Text should contain offscreen hint note line",
        )
        // Negative assertions: old single-char flags must not appear
        assertFalse(
            text.contains("\tvcn") || text.contains("\tvn"),
            "Text should NOT contain old single-char flag format",
        )

        // Verify second content item is ImageContent with JPEG data
        val imageContent = result.content[1]
        assertTrue(imageContent is ImageContent, "Second content item should be ImageContent")

        val image = imageContent as ImageContent
        assertTrue(
            image.mimeType == "image/jpeg",
            "MimeType should be 'image/jpeg', got: ${image.mimeType}",
        )
        assertNotNull(image.data, "Screenshot data should not be null")
        assertFalse(image.data.isEmpty(), "Screenshot data should not be empty")

        println("Screenshot: mimeType=${image.mimeType}, data size=${image.data.length} chars")
    }
}
