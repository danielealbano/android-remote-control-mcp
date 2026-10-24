package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
 * Uses [SharedAndroidContainer] singleton to share the redroid container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EScreenshotTest {

    companion object {
        /**
         * Tool name prefix for E2E tests. Centralized from [AndroidContainerSetup.TOOL_NAME_PREFIX]
         * to document the empty device_slug assumption.
         */
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX

        /**
         * Maximum number of retry attempts for screenshot capture that may fail
         * transiently on slow CI emulators (accessibility service unavailable, etc.).
         */
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * Delay between retry attempts in milliseconds.
         */
        private const val RETRY_DELAY_MS = 5_000L

        /** Simultaneous screenshot requests sent at once to check the server spaces them out. */
        private const val CONCURRENT_SCREENSHOTS = 3
    }

    private val mcpClient = SharedAndroidContainer.mcpClient

    @BeforeEach
    fun ensureAccessibility() {
        SharedAndroidContainer.ensureAccessibilityService()
    }

    @Test
    @Order(1)
    fun `get_screen_state with screenshot returns valid JPEG data`() = runBlocking {
        // Launch calculator so there is an active app context (the home screen
        // launcher does not always produce an "app:" line in the compact TSV output).
        AndroidContainerSetup.launchCalculator()
        Thread.sleep(1_000)

        // Retry screenshot capture — accessibility service screenshot can be transiently
        // unavailable on slow CI emulators right after camera operations or app startup.
        var result = mcpClient.callTool(
            "${TOOL_PREFIX}get_screen_state",
            mapOf("include_screenshot" to true),
        )

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            if (result.isError != true && result.content.size >= 2) break
            val errorText = (result.content.firstOrNull() as? TextContent)?.text ?: "unknown"
            println("[E2E Screenshot] Attempt $attempt failed (isError=${result.isError}, " +
                "content.size=${result.content.size}): $errorText — retrying after ${RETRY_DELAY_MS}ms")
            Thread.sleep(RETRY_DELAY_MS)
            result = mcpClient.callTool(
                "${TOOL_PREFIX}get_screen_state",
                mapOf("include_screenshot" to true),
            )
        }

        assertNotEquals(
            true,
            result.isError,
            "get_screen_state should not return error: " +
                (result.content.firstOrNull() as? TextContent)?.text,
        )

        assertNotNull(result.content, "Result should have content")
        assertTrue(
            result.content.size >= 2,
            "Result should have at least 2 content items (text + image), got: ${result.content.size}. " +
                "Content[0]: ${(result.content.firstOrNull() as? TextContent)?.text?.take(200)}",
        )

        // Verify first content item is TextContent with compact TSV
        val textContent = result.content[0]
        assertTrue(textContent is TextContent, "First content item should be TextContent")
        val text = (textContent as TextContent).text
        assertTrue(text.contains("note:"), "Text should contain note line")
        assertTrue(text.contains("screen:"), "Text should contain screen info line")
        assertTrue(
            text.contains("--- window:") && text.contains("type:APPLICATION"),
            "Text should contain an APPLICATION window header",
        )
        assertTrue(
            text.contains("note:flags: on=onscreen off=offscreen"),
            "Text should contain flags legend note line",
        )
        assertTrue(
            text.contains("note:offscreen items require scroll_to_node before interaction"),
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

    @Test
    @Order(2)
    fun `screenshots requested at the same time all succeed`() = runBlocking {
        AndroidContainerSetup.launchCalculator()
        Thread.sleep(1_000)

        // Android rejects takeScreenshot requests 333 ms or less apart: simultaneous requests must be
        // spaced out by the server, not fail.
        val results = (1..CONCURRENT_SCREENSHOTS).map {
            async { mcpClient.callTool("${TOOL_PREFIX}get_screen_state", mapOf("include_screenshot" to true)) }
        }.awaitAll()

        results.forEachIndexed { index, result ->
            assertNotEquals(
                true,
                result.isError,
                "request $index failed: ${(result.content.firstOrNull() as? TextContent)?.text}",
            )
            assertTrue(
                result.content.size >= 2 && result.content[1] is ImageContent,
                "request $index returned no screenshot",
            )
        }
    }

    @Test
    @Order(3)
    fun `get_screen_state with annotate false returns the plain screenshot`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}get_screen_state",
            mapOf("include_screenshot" to true, "annotate" to false),
        )

        assertNotEquals(true, result.isError, (result.content.firstOrNull() as? TextContent)?.text)
        val image = result.content[1] as ImageContent
        assertTrue(image.mimeType == "image/jpeg", "MimeType should be 'image/jpeg', got: ${image.mimeType}")
        assertFalse(image.data.isEmpty(), "Screenshot data should not be empty")
    }

    @Test
    @Order(4)
    fun `get_screen_state without offscreen nodes and hierarchy`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}get_screen_state",
            mapOf("include_offscreen" to false, "include_hierarchy" to false),
        )

        assertNotEquals(true, result.isError, (result.content.firstOrNull() as? TextContent)?.text)
        val text = (result.content[0] as TextContent).text
        val rows = text.lines().filter { it.startsWith("node_") && it.contains('\t') && !it.startsWith("node_id\t") }
        assertTrue(rows.isNotEmpty(), "expected node rows: $text")
        assertTrue(rows.none { it.substringAfterLast('\t').startsWith("off") }, "offscreen rows listed: $text")
        assertTrue(text.contains("note:offscreen nodes are not listed (include_offscreen=false)"), text)
        assertFalse(text.lines().contains("hierarchy:"), text)
    }
}
