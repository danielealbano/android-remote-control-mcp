package com.danielealbano.androidremotecontrolmcp.e2e

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
 * E2E test: Screenshot capture verification.
 *
 * Verifies that the capture_screenshot MCP tool returns valid JPEG data
 * with correct format, dimensions, and quality-dependent sizes.
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
    fun `capture screenshot of home screen returns valid JPEG data`() {
        // Navigate to home screen first
        mcpClient.callTool("press_home")
        Thread.sleep(1_000)

        val screenshot = mcpClient.callTool("capture_screenshot", mapOf("quality" to 80))

        // Navigate the MCP content array: result -> content[0] -> fields
        val contentArray = screenshot["content"]?.jsonArray
        assertNotNull(contentArray, "Screenshot result should have 'content' array")
        assertTrue(contentArray!!.isNotEmpty(), "Screenshot content array should not be empty")

        val imageObj = contentArray[0].jsonObject

        val mimeType = imageObj["mimeType"]?.jsonPrimitive?.contentOrNull
        assertTrue(mimeType == "image/jpeg", "MimeType should be 'image/jpeg', got: $mimeType")

        val data = imageObj["data"]?.jsonPrimitive?.contentOrNull
        assertNotNull(data, "Screenshot data should not be null")
        assertFalse(data!!.isEmpty(), "Screenshot data should not be empty")

        val width = imageObj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val height = imageObj["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        assertNotNull(width, "Width should be present")
        assertNotNull(height, "Height should be present")
        assertTrue(width!! > 0, "Width should be > 0")
        assertTrue(height!! > 0, "Height should be > 0")

        println("Screenshot: ${width}x${height}, mimeType=$mimeType, data size=${data.length} chars")
    }

    @Test
    @Order(2)
    fun `higher quality produces larger screenshot data`() {
        // Wait for accessibility service to recover from previous test's screenshot
        Thread.sleep(3_000)

        // Capture at low quality
        val lowQuality = mcpClient.callTool("capture_screenshot", mapOf("quality" to 10))
        val lowContentArray = lowQuality["content"]?.jsonArray
        assertNotNull(lowContentArray, "Low quality screenshot should have 'content' array")
        val lowData = lowContentArray!![0].jsonObject["data"]?.jsonPrimitive?.contentOrNull
        assertNotNull(lowData, "Low quality screenshot data should not be null")

        // Delay between captures - accessibility service needs time to recover
        Thread.sleep(2_000)

        // Capture at high quality
        val highQuality = mcpClient.callTool("capture_screenshot", mapOf("quality" to 95))
        val highContentArray = highQuality["content"]?.jsonArray
        assertNotNull(highContentArray, "High quality screenshot should have 'content' array")
        val highData = highContentArray!![0].jsonObject["data"]?.jsonPrimitive?.contentOrNull
        assertNotNull(highData, "High quality screenshot data should not be null")

        println("Low quality (10) data size: ${lowData!!.length} chars")
        println("High quality (95) data size: ${highData!!.length} chars")

        assertTrue(
            highData.length > lowData.length,
            "High quality screenshot (${highData.length} chars) should be larger than " +
                "low quality (${lowData.length} chars)"
        )
    }
}
