package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
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
 * Verifies that the capture_screenshot MCP tool returns valid JPEG data
 * with correct format and quality-dependent sizes.
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
    fun `capture screenshot of home screen returns valid JPEG data`() = runBlocking {
        // Navigate to home screen first
        mcpClient.callTool("press_home")
        Thread.sleep(1_000)

        val result = mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))

        assertNotNull(result.content, "Screenshot result should have content")
        assertTrue(result.content.isNotEmpty(), "Screenshot content should not be empty")

        val imageContent = result.content[0]
        assertTrue(imageContent is ImageContent, "First content item should be ImageContent")

        val image = imageContent as ImageContent
        assertTrue(image.mimeType == "image/jpeg", "MimeType should be 'image/jpeg', got: ${image.mimeType}")
        assertNotNull(image.data, "Screenshot data should not be null")
        assertFalse(image.data.isEmpty(), "Screenshot data should not be empty")

        println("Screenshot: mimeType=${image.mimeType}, data size=${image.data.length} chars")
    }

    @Test
    @Order(2)
    fun `higher quality produces larger screenshot data`() = runBlocking {
        // Wait for accessibility service to recover from previous test's screenshot
        Thread.sleep(3_000)

        // Capture at low quality
        val lowResult = mcpClient.callTool("capture_screenshot", mapOf("quality" to 10))
        assertNotNull(lowResult.content, "Low quality screenshot should have content")
        assertTrue(lowResult.content.isNotEmpty(), "Low quality screenshot content should not be empty")
        val lowImage = lowResult.content[0] as ImageContent
        assertNotNull(lowImage.data, "Low quality screenshot data should not be null")

        // Delay between captures - accessibility service needs time to recover
        Thread.sleep(2_000)

        // Capture at high quality
        val highResult = mcpClient.callTool("capture_screenshot", mapOf("quality" to 95))
        assertNotNull(highResult.content, "High quality screenshot should have content")
        assertTrue(highResult.content.isNotEmpty(), "High quality screenshot content should not be empty")
        val highImage = highResult.content[0] as ImageContent
        assertNotNull(highImage.data, "High quality screenshot data should not be null")

        println("Low quality (10) data size: ${lowImage.data.length} chars")
        println("High quality (95) data size: ${highImage.data.length} chars")

        assertTrue(
            highImage.data.length > lowImage.data.length,
            "High quality screenshot (${highImage.data.length} chars) should be larger than " +
                "low quality (${lowImage.data.length} chars)",
        )
    }
}
