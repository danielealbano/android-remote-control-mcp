package com.danielealbano.androidremotecontrolmcp.integration

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.CameraInfo
import com.danielealbano.androidremotecontrolmcp.data.model.PhotoResolution
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.data.model.VideoResolution
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.camera.VideoRecordingResult
import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
@DisplayName("Camera Tools Integration Tests")
class CameraToolsIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    // ─────────────────────────────────────────────────────────────────────
    // list_cameras
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `list_cameras returns JSON array through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listCameras() } returns
                listOf(
                    CameraInfo(
                        cameraId = "0",
                        facing = "back",
                        hasFlash = true,
                        supportsPhoto = true,
                        supportsVideo = true,
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_cameras",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"camera_id\":\"0\""))
                assertTrue(text.contains("\"facing\":\"back\""))
                assertTrue(text.contains("\"has_flash\":true"))
            }
        }

    @Test
    fun `list_cameras with no cameras returns empty array through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listCameras() } returns emptyList()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_cameras",
                        arguments = emptyMap(),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertEquals("[]", text)
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // list_camera_photo_resolutions
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `list_camera_photo_resolutions returns resolutions through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listPhotoResolutions("0") } returns
                listOf(
                    PhotoResolution(
                        width = 1920,
                        height = 1080,
                        megapixels = 2.1,
                        aspectRatio = "16:9",
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_camera_photo_resolutions",
                        arguments = mapOf("camera_id" to "0"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"width\":1920"))
                assertTrue(text.contains("\"height\":1080"))
            }
        }

    @Test
    fun `list_camera_photo_resolutions with invalid camera_id returns error through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listPhotoResolutions("invalid") } throws
                McpToolException.ActionFailed("Camera not found: invalid")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_camera_photo_resolutions",
                        arguments = mapOf("camera_id" to "invalid"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Camera not found"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // list_camera_video_resolutions
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `list_camera_video_resolutions returns resolutions through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listVideoResolutions("0") } returns
                listOf(
                    VideoResolution(
                        width = 1920,
                        height = 1080,
                        aspectRatio = "16:9",
                        qualityLabel = "FHD",
                    ),
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_camera_video_resolutions",
                        arguments = mapOf("camera_id" to "0"),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("\"width\":1920"))
                assertTrue(text.contains("\"quality_label\":\"FHD\""))
            }
        }

    @Test
    fun `list_camera_video_resolutions with invalid camera_id returns error through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listVideoResolutions("invalid") } throws
                McpToolException.ActionFailed("Camera not found: invalid")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_camera_video_resolutions",
                        arguments = mapOf("camera_id" to "invalid"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Camera not found"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // take_camera_photo
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `take_camera_photo returns ImageContent through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.cameraProvider.takePhoto(
                    cameraId = "0",
                    width = null,
                    height = null,
                    quality = any(),
                    flashMode = any(),
                )
            } returns ScreenshotData(data = "base64imagedata", width = 1280, height = 720)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_take_camera_photo",
                        arguments = mapOf("camera_id" to "0"),
                    )
                assertNotEquals(true, result.isError)
                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("base64imagedata", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }
        }

    @Test
    fun `take_camera_photo with invalid resolution returns error through MCP protocol`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_take_camera_photo",
                        arguments = mapOf("camera_id" to "0", "resolution" to "invalid"),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("WIDTHxHEIGHT"))
            }
        }

    @Test
    fun `take_camera_photo with missing camera_id returns error through MCP protocol`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_take_camera_photo",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("camera_id"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // save_camera_photo
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `save_camera_photo returns success text through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockUri = mockk<Uri>()
            coEvery {
                deps.fileOperationProvider.createFileUri("loc1", "photo.jpg", "image/jpeg")
            } returns mockUri
            coEvery {
                deps.cameraProvider.savePhoto(
                    cameraId = "0",
                    outputUri = mockUri,
                    width = null,
                    height = null,
                    quality = any(),
                    flashMode = any(),
                )
            } returns 12345L

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_photo",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "loc1",
                                "path" to "photo.jpg",
                            ),
                    )
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Photo saved successfully"))
                assertTrue(text.contains("12345"))
            }
        }

    @Test
    fun `save_camera_photo with write not allowed returns error through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.createFileUri("readonly", "photo.jpg", "image/jpeg")
            } throws McpToolException.PermissionDenied("Write not permitted for location: readonly")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_photo",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "readonly",
                                "path" to "photo.jpg",
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not permitted"))
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // save_camera_video
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `save_camera_video returns text and image content through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            val mockUri = mockk<Uri>()
            coEvery {
                deps.fileOperationProvider.createFileUri("loc1", "video.mp4", "video/mp4")
            } returns mockUri
            coEvery {
                deps.cameraProvider.saveVideo(
                    cameraId = "0",
                    outputUri = mockUri,
                    durationSeconds = 5,
                    width = null,
                    height = null,
                    audio = true,
                    flashMode = any(),
                )
            } returns
                VideoRecordingResult(
                    fileSizeBytes = 54321L,
                    durationMs = 5000L,
                    thumbnailData = "thumbdata",
                    thumbnailWidth = 320,
                    thumbnailHeight = 240,
                )

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_video",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "loc1",
                                "path" to "video.mp4",
                                "duration" to 5,
                            ),
                    )
                assertNotEquals(true, result.isError)
                assertEquals(2, result.content.size)
                val textContent = result.content[0] as TextContent
                assertTrue(textContent.text.contains("Video saved successfully"))
                assertTrue(textContent.text.contains("54321"))
                val imageContent = result.content[1] as ImageContent
                assertEquals("thumbdata", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }
        }

    @Test
    fun `save_camera_video with duration exceeding 30 returns error through MCP protocol`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_video",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "loc1",
                                "path" to "video.mp4",
                                "duration" to 31,
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("duration"))
            }
        }

    @Test
    fun `save_camera_video with write not allowed returns error through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery {
                deps.fileOperationProvider.createFileUri("readonly", "video.mp4", "video/mp4")
            } throws McpToolException.PermissionDenied("Write not permitted for location: readonly")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_save_camera_video",
                        arguments =
                            mapOf(
                                "camera_id" to "0",
                                "location_id" to "readonly",
                                "path" to "video.mp4",
                                "duration" to 5,
                            ),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Write not permitted"))
            }
        }

    @Test
    fun `camera tool with missing camera permission returns error through MCP protocol`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.cameraProvider.listCameras() } throws
                McpToolException.PermissionDenied("Camera permission not granted")

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result =
                    client.callTool(
                        name = "android_list_cameras",
                        arguments = emptyMap(),
                    )
                assertEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Camera permission"))
            }
        }
}
