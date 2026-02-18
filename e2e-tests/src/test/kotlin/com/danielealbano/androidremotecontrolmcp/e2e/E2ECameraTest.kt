package com.danielealbano.androidremotecontrolmcp.e2e

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * E2E test: Camera tools verification.
 *
 * These tests verify the camera MCP tools against a real Android emulator
 * with emulated cameras (`-camera-back emulated -camera-front emulated`).
 *
 * Camera-operation tests live here rather than in JVM unit tests because
 * CameraX internally uses Android camera HAL, SurfaceTexture, and lifecycle
 * observers that are tightly coupled to the Android runtime and cannot be
 * meaningfully mocked in a pure JVM environment.
 *
 * **Covered scenarios:**
 * - Camera enumeration (list_cameras)
 * - Photo resolution listing (list_camera_photo_resolutions)
 * - Video resolution listing (list_camera_video_resolutions)
 * - Inline photo capture (take_camera_photo) — returns base64 JPEG
 * - Invalid camera ID error handling
 * - Flash mode parameter handling
 *
 * **Not covered (require SAF storage location setup):**
 * - save_camera_photo (needs location_id)
 * - save_camera_video (needs location_id)
 *
 * Uses [SharedAndroidContainer] singleton to share the Docker container
 * across all E2E test classes, avoiding ~2-4 minute container boot per class.
 *
 * CAMERA and RECORD_AUDIO permissions are granted during container setup
 * via [AndroidContainerSetup.grantCameraPermissions].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2ECameraTest {

    companion object {
        private const val TOOL_PREFIX = AndroidContainerSetup.TOOL_NAME_PREFIX
    }

    private val mcpClient = SharedAndroidContainer.mcpClient

    /**
     * Cached camera ID for the back camera, discovered in the first test
     * and reused by subsequent tests.
     */
    private var backCameraId: String? = null

    // ─────────────────────────────────────────────────────────────────────
    // list_cameras
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `list_cameras returns at least one camera with correct fields`() = runBlocking {
        val result = mcpClient.callTool("${TOOL_PREFIX}list_cameras")

        assertNotEquals(true, result.isError, "list_cameras should not return an error")
        assertTrue(result.content.isNotEmpty(), "Result should have content")

        val textContent = result.content[0] as TextContent
        val cameras = Json.parseToJsonElement(textContent.text).jsonArray

        assertTrue(cameras.size >= 1, "Emulator should have at least 1 camera, got: ${cameras.size}")

        val firstCamera = cameras[0].jsonObject
        assertNotNull(firstCamera["camera_id"], "Camera should have camera_id")
        assertNotNull(firstCamera["facing"], "Camera should have facing")
        assertNotNull(firstCamera["has_flash"], "Camera should have has_flash")
        assertNotNull(firstCamera["supports_photo"], "Camera should have supports_photo")
        assertNotNull(firstCamera["supports_video"], "Camera should have supports_video")

        val facing = firstCamera["facing"]!!.jsonPrimitive.content
        assertTrue(
            facing in setOf("front", "back", "external"),
            "Camera facing should be front, back, or external, got: $facing",
        )

        // Find and cache the back camera ID for subsequent tests
        for (camera in cameras) {
            val cameraObj = camera.jsonObject
            if (cameraObj["facing"]!!.jsonPrimitive.content == "back") {
                backCameraId = cameraObj["camera_id"]!!.jsonPrimitive.content
                break
            }
        }

        // If no back camera, use the first camera available
        if (backCameraId == null) {
            backCameraId = firstCamera["camera_id"]!!.jsonPrimitive.content
        }

        println("Discovered ${cameras.size} camera(s), using camera_id=$backCameraId for subsequent tests")
    }

    @Test
    @Order(2)
    fun `list_cameras returns cameras with correct facing values`() = runBlocking {
        val result = mcpClient.callTool("${TOOL_PREFIX}list_cameras")

        assertNotEquals(true, result.isError)
        val cameras = Json.parseToJsonElement((result.content[0] as TextContent).text).jsonArray

        val facings = cameras.map { it.jsonObject["facing"]!!.jsonPrimitive.content }.toSet()

        // The emulator with -camera-back emulated -camera-front emulated should have
        // at least a back camera. Front camera availability depends on the emulator AVD.
        assertTrue(
            facings.contains("back") || facings.contains("front"),
            "Should have at least a back or front camera, got facings: $facings",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // list_camera_photo_resolutions
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    fun `list_camera_photo_resolutions returns sorted resolutions for valid camera`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_camera_photo_resolutions",
            mapOf("camera_id" to cameraId),
        )

        assertNotEquals(true, result.isError, "Should not return error for valid camera_id")

        val resolutions = Json.parseToJsonElement((result.content[0] as TextContent).text).jsonArray
        assertTrue(resolutions.isNotEmpty(), "Camera should support at least one photo resolution")

        // Verify structure of first resolution
        val first = resolutions[0].jsonObject
        assertNotNull(first["width"], "Resolution should have width")
        assertNotNull(first["height"], "Resolution should have height")
        assertNotNull(first["megapixels"], "Resolution should have megapixels")
        assertNotNull(first["aspect_ratio"], "Resolution should have aspect_ratio")

        // Verify sorted by megapixels descending
        val megapixels = resolutions.map {
            it.jsonObject["megapixels"]!!.jsonPrimitive.content.toDouble()
        }
        for (i in 0 until megapixels.size - 1) {
            assertTrue(
                megapixels[i] >= megapixels[i + 1],
                "Photo resolutions should be sorted by megapixels descending: " +
                    "${megapixels[i]} < ${megapixels[i + 1]}",
            )
        }

        println("Camera $cameraId supports ${resolutions.size} photo resolutions")
    }

    @Test
    @Order(4)
    fun `list_camera_photo_resolutions with invalid camera_id returns error`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_camera_photo_resolutions",
            mapOf("camera_id" to "nonexistent_camera_99"),
        )

        assertEquals(true, result.isError, "Invalid camera_id should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("not found", ignoreCase = true) ||
                text.contains("nonexistent_camera_99"),
            "Error should mention invalid camera, got: $text",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // list_camera_video_resolutions
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `list_camera_video_resolutions returns sorted resolutions for valid camera`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_camera_video_resolutions",
            mapOf("camera_id" to cameraId),
        )

        assertNotEquals(true, result.isError, "Should not return error for valid camera_id")

        val resolutions = Json.parseToJsonElement((result.content[0] as TextContent).text).jsonArray
        assertTrue(resolutions.isNotEmpty(), "Camera should support at least one video resolution")

        // Verify structure
        val first = resolutions[0].jsonObject
        assertNotNull(first["width"], "Resolution should have width")
        assertNotNull(first["height"], "Resolution should have height")
        assertNotNull(first["aspect_ratio"], "Resolution should have aspect_ratio")
        assertNotNull(first["quality_label"], "Resolution should have quality_label")

        val qualityLabel = first["quality_label"]!!.jsonPrimitive.content
        assertTrue(
            qualityLabel in setOf("SD", "HD", "FHD", "UHD"),
            "Quality label should be SD, HD, FHD, or UHD, got: $qualityLabel",
        )

        // Verify sorted by width descending
        val widths = resolutions.map {
            it.jsonObject["width"]!!.jsonPrimitive.content.toInt()
        }
        for (i in 0 until widths.size - 1) {
            assertTrue(
                widths[i] >= widths[i + 1],
                "Video resolutions should be sorted by width descending: ${widths[i]} < ${widths[i + 1]}",
            )
        }

        println("Camera $cameraId supports ${resolutions.size} video resolutions")
    }

    @Test
    @Order(6)
    fun `list_camera_video_resolutions with invalid camera_id returns error`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}list_camera_video_resolutions",
            mapOf("camera_id" to "nonexistent_camera_99"),
        )

        assertEquals(true, result.isError, "Invalid camera_id should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("not found", ignoreCase = true) ||
                text.contains("nonexistent_camera_99"),
            "Error should mention invalid camera, got: $text",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // take_camera_photo
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `take_camera_photo returns valid ImageContent with base64 JPEG`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf("camera_id" to cameraId),
        )

        assertNotEquals(true, result.isError, "take_camera_photo should not return error")
        assertTrue(result.content.isNotEmpty(), "Result should have content")

        val imageContent = result.content.filterIsInstance<ImageContent>().firstOrNull()
        assertNotNull(imageContent, "Result should contain ImageContent")

        assertEquals("image/jpeg", imageContent!!.mimeType, "Image should be JPEG")
        assertFalse(imageContent.data.isEmpty(), "Image data should not be empty")

        println("take_camera_photo: received ${imageContent.data.length} chars of base64 data")
    }

    @Test
    @Order(8)
    fun `take_camera_photo with custom resolution returns ImageContent`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "resolution" to "1280x720",
            ),
        )

        assertNotEquals(true, result.isError, "take_camera_photo with resolution should not return error")

        val imageContent = result.content.filterIsInstance<ImageContent>().firstOrNull()
        assertNotNull(imageContent, "Result should contain ImageContent")
        assertEquals("image/jpeg", imageContent!!.mimeType)
        assertFalse(imageContent.data.isEmpty(), "Image data should not be empty")

        println("take_camera_photo (1280x720): received ${imageContent.data.length} chars of base64 data")
    }

    @Test
    @Order(9)
    fun `take_camera_photo with flash_mode off returns ImageContent`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "flash_mode" to "off",
            ),
        )

        assertNotEquals(true, result.isError, "take_camera_photo with flash_mode=off should succeed")

        val imageContent = result.content.filterIsInstance<ImageContent>().firstOrNull()
        assertNotNull(imageContent, "Result should contain ImageContent")
        assertEquals("image/jpeg", imageContent!!.mimeType)
    }

    @Test
    @Order(10)
    fun `take_camera_photo with quality 50 returns ImageContent`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "quality" to 50,
            ),
        )

        assertNotEquals(true, result.isError, "take_camera_photo with quality=50 should succeed")

        val imageContent = result.content.filterIsInstance<ImageContent>().firstOrNull()
        assertNotNull(imageContent, "Result should contain ImageContent")
    }

    @Test
    @Order(11)
    fun `take_camera_photo with missing camera_id returns error`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            emptyMap(),
        )

        assertEquals(true, result.isError, "Missing camera_id should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("camera_id", ignoreCase = true),
            "Error should mention camera_id, got: $text",
        )
    }

    @Test
    @Order(12)
    fun `take_camera_photo with invalid camera_id returns error`() = runBlocking {
        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf("camera_id" to "nonexistent_camera_99"),
        )

        assertEquals(true, result.isError, "Invalid camera_id should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("not found", ignoreCase = true) ||
                text.contains("nonexistent_camera_99") ||
                text.contains("failed", ignoreCase = true),
            "Error should mention camera issue, got: $text",
        )
    }

    @Test
    @Order(13)
    fun `take_camera_photo with invalid resolution format returns error`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "resolution" to "invalid",
            ),
        )

        assertEquals(true, result.isError, "Invalid resolution format should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("WIDTHxHEIGHT", ignoreCase = true) ||
                text.contains("resolution", ignoreCase = true),
            "Error should mention resolution format, got: $text",
        )
    }

    @Test
    @Order(14)
    fun `take_camera_photo with invalid flash_mode returns error`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "flash_mode" to "strobe",
            ),
        )

        assertEquals(true, result.isError, "Invalid flash_mode should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("flash_mode", ignoreCase = true),
            "Error should mention flash_mode, got: $text",
        )
    }

    @Test
    @Order(15)
    fun `take_camera_photo with quality 0 returns error`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "quality" to 0,
            ),
        )

        assertEquals(true, result.isError, "Quality 0 should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("quality", ignoreCase = true),
            "Error should mention quality, got: $text",
        )
    }

    @Test
    @Order(16)
    fun `take_camera_photo with quality 101 returns error`() = runBlocking {
        val cameraId = requireCameraId()

        val result = mcpClient.callTool(
            "${TOOL_PREFIX}take_camera_photo",
            mapOf(
                "camera_id" to cameraId,
                "quality" to 101,
            ),
        )

        assertEquals(true, result.isError, "Quality 101 should return error")
        val text = (result.content[0] as TextContent).text
        assertTrue(
            text.contains("quality", ignoreCase = true),
            "Error should mention quality, got: $text",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the cached back camera ID, running list_cameras first if needed.
     */
    private suspend fun requireCameraId(): String {
        if (backCameraId != null) return backCameraId!!

        // Discovery: call list_cameras to find a camera
        val result = mcpClient.callTool("${TOOL_PREFIX}list_cameras")
        assertNotEquals(true, result.isError, "list_cameras should succeed")

        val cameras = Json.parseToJsonElement((result.content[0] as TextContent).text).jsonArray
        assertTrue(cameras.isNotEmpty(), "Emulator should have at least 1 camera")

        // Prefer back camera, fall back to first available
        for (camera in cameras) {
            val cameraObj = camera.jsonObject
            if (cameraObj["facing"]!!.jsonPrimitive.content == "back") {
                backCameraId = cameraObj["camera_id"]!!.jsonPrimitive.content
                return backCameraId!!
            }
        }

        backCameraId = cameras[0].jsonObject["camera_id"]!!.jsonPrimitive.content
        return backCameraId!!
    }
}
