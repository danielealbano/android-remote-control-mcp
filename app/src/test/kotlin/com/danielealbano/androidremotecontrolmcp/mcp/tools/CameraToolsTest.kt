@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.CameraInfo
import com.danielealbano.androidremotecontrolmcp.data.model.PhotoResolution
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.data.model.VideoResolution
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.camera.VideoRecordingResult
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("CameraTools")
class CameraToolsTest {
    private lateinit var cameraProvider: CameraProvider
    private lateinit var fileOperationProvider: FileOperationProvider
    private lateinit var mockUri: Uri

    @BeforeEach
    fun setUp() {
        cameraProvider = mockk()
        fileOperationProvider = mockk()
        mockUri = mockk()
    }

    private fun extractTextContent(result: CallToolResult): String {
        val textContent = result.content.filterIsInstance<TextContent>().first()
        return textContent.text
    }

    // ─────────────────────────────────────────────────────────────────────
    // ListCamerasHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ListCamerasHandler")
    inner class ListCamerasHandlerTests {
        private lateinit var handler: ListCamerasHandler

        @BeforeEach
        fun setUp() {
            handler = ListCamerasHandler(cameraProvider)
        }

        @Test
        @DisplayName("list_cameras returns camera info as JSON array")
        fun listCamerasReturnsCameraInfoAsJsonArray() =
            runTest {
                coEvery { cameraProvider.listCameras() } returns
                    listOf(
                        CameraInfo(
                            cameraId = "0",
                            facing = "back",
                            hasFlash = true,
                            supportsPhoto = true,
                            supportsVideo = true,
                        ),
                        CameraInfo(
                            cameraId = "1",
                            facing = "front",
                            hasFlash = false,
                            supportsPhoto = true,
                            supportsVideo = false,
                        ),
                    )

                val result = handler.execute(null)
                val text = extractTextContent(result)

                assertTrue(text.contains("\"camera_id\":\"0\""))
                assertTrue(text.contains("\"facing\":\"back\""))
                assertTrue(text.contains("\"has_flash\":true"))
                assertTrue(text.contains("\"camera_id\":\"1\""))
                assertTrue(text.contains("\"facing\":\"front\""))
                assertTrue(text.contains("\"has_flash\":false"))
            }

        @Test
        @DisplayName("list_cameras returns empty array when no cameras available")
        fun listCamerasReturnsEmptyArrayWhenNoCameras() =
            runTest {
                coEvery { cameraProvider.listCameras() } returns emptyList()

                val result = handler.execute(null)
                val text = extractTextContent(result)

                assertEquals("[]", text)
            }

        @Test
        @DisplayName("list_cameras propagates PermissionDenied from CameraProvider")
        fun listCamerasPropagatesPermissionDenied() =
            runTest {
                coEvery { cameraProvider.listCameras() } throws
                    McpToolException.PermissionDenied("Camera permission not granted")

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ListCameraPhotoResolutionsHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ListCameraPhotoResolutionsHandler")
    inner class ListCameraPhotoResolutionsHandlerTests {
        private lateinit var handler: ListCameraPhotoResolutionsHandler

        @BeforeEach
        fun setUp() {
            handler = ListCameraPhotoResolutionsHandler(cameraProvider)
        }

        @Test
        @DisplayName("list_camera_photo_resolutions returns resolutions for valid camera")
        fun listPhotoResolutionsReturnsResolutions() =
            runTest {
                coEvery { cameraProvider.listPhotoResolutions("0") } returns
                    listOf(
                        PhotoResolution(
                            width = 1920,
                            height = 1080,
                            megapixels = 2.1,
                            aspectRatio = "16:9",
                        ),
                        PhotoResolution(
                            width = 1280,
                            height = 720,
                            megapixels = 0.9,
                            aspectRatio = "16:9",
                        ),
                    )

                val params = buildJsonObject { put("camera_id", "0") }
                val result = handler.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("\"width\":1920"))
                assertTrue(text.contains("\"height\":1080"))
                assertTrue(text.contains("\"megapixels\":2.1"))
                assertTrue(text.contains("\"aspect_ratio\":\"16:9\""))
                assertTrue(text.contains("\"width\":1280"))
                assertTrue(text.contains("\"height\":720"))
            }

        @Test
        @DisplayName("list_camera_photo_resolutions throws InvalidParams when camera_id missing")
        fun listPhotoResolutionsThrowsWhenCameraIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }

        @Test
        @DisplayName("list_camera_photo_resolutions propagates ActionFailed for invalid camera")
        fun listPhotoResolutionsPropagatesActionFailed() =
            runTest {
                coEvery { cameraProvider.listPhotoResolutions("99") } throws
                    McpToolException.ActionFailed("Camera not found: 99")

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(buildJsonObject { put("camera_id", "99") })
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ListCameraVideoResolutionsHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ListCameraVideoResolutionsHandler")
    inner class ListCameraVideoResolutionsHandlerTests {
        private lateinit var handler: ListCameraVideoResolutionsHandler

        @BeforeEach
        fun setUp() {
            handler = ListCameraVideoResolutionsHandler(cameraProvider)
        }

        @Test
        @DisplayName("list_camera_video_resolutions returns resolutions for valid camera")
        fun listVideoResolutionsReturnsResolutions() =
            runTest {
                coEvery { cameraProvider.listVideoResolutions("0") } returns
                    listOf(
                        VideoResolution(
                            width = 1920,
                            height = 1080,
                            aspectRatio = "16:9",
                            qualityLabel = "FHD",
                        ),
                    )

                val params = buildJsonObject { put("camera_id", "0") }
                val result = handler.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("\"width\":1920"))
                assertTrue(text.contains("\"quality_label\":\"FHD\""))
            }

        @Test
        @DisplayName("list_camera_video_resolutions throws InvalidParams when camera_id missing")
        fun listVideoResolutionsThrowsWhenCameraIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TakeCameraPhotoHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TakeCameraPhotoHandler")
    inner class TakeCameraPhotoHandlerTests {
        private lateinit var handler: TakeCameraPhotoHandler

        @BeforeEach
        fun setUp() {
            handler = TakeCameraPhotoHandler(cameraProvider)
        }

        @Test
        @DisplayName("take_camera_photo returns ImageContent with base64 data")
        fun takeCameraPhotoReturnsImageContent() =
            runTest {
                val mockScreenshot =
                    ScreenshotData(
                        data = "base64data",
                        width = 1280,
                        height = 720,
                    )
                coEvery {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = null,
                        height = null,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns mockScreenshot

                val params = buildJsonObject { put("camera_id", "0") }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val imageContent = result.content[0] as ImageContent
                assertEquals("base64data", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }

        @Test
        @DisplayName("take_camera_photo with custom resolution parses WIDTHxHEIGHT correctly")
        fun takeCameraPhotoWithCustomResolution() =
            runTest {
                val mockScreenshot =
                    ScreenshotData(data = "data", width = 1920, height = 1080)
                coEvery {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = 1920,
                        height = 1080,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns mockScreenshot

                val params =
                    buildJsonObject {
                        put("camera_id", "0")
                        put("resolution", "1920x1080")
                    }
                handler.execute(params)

                coVerify {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = 1920,
                        height = 1080,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo with invalid resolution format throws InvalidParams")
        fun takeCameraPhotoWithInvalidResolutionFormat() =
            runTest {
                val invalidFormats = listOf("abc", "1280", "1280x", "x720", "1280xabc")
                invalidFormats.forEach { format ->
                    assertThrows<McpToolException.InvalidParams> {
                        handler.execute(
                            buildJsonObject {
                                put("camera_id", "0")
                                put("resolution", format)
                            },
                        )
                    }
                }
            }

        @Test
        @DisplayName("take_camera_photo with negative resolution values throws InvalidParams")
        fun takeCameraPhotoWithNegativeResolution() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("resolution", "-1280x720")
                        },
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo with zero resolution values throws InvalidParams")
        fun takeCameraPhotoWithZeroResolution() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("resolution", "0x720")
                        },
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo with quality 0 throws InvalidParams")
        fun takeCameraPhotoWithQualityZero() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("quality", 0)
                        },
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo with quality 101 throws InvalidParams")
        fun takeCameraPhotoWithQuality101() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("quality", 101)
                        },
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo with quality 1 succeeds (boundary)")
        fun takeCameraPhotoWithQuality1Succeeds() =
            runTest {
                coEvery {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = null,
                        height = null,
                        quality = 1,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns ScreenshotData(data = "data", width = 720, height = 480)

                val params =
                    buildJsonObject {
                        put("camera_id", "0")
                        put("quality", 1)
                    }
                val result = handler.execute(params)
                assertEquals(1, result.content.size)
            }

        @Test
        @DisplayName("take_camera_photo with quality 100 succeeds (boundary)")
        fun takeCameraPhotoWithQuality100Succeeds() =
            runTest {
                coEvery {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = null,
                        height = null,
                        quality = 100,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns ScreenshotData(data = "data", width = 720, height = 480)

                val params =
                    buildJsonObject {
                        put("camera_id", "0")
                        put("quality", 100)
                    }
                val result = handler.execute(params)
                assertEquals(1, result.content.size)
            }

        @Test
        @DisplayName("take_camera_photo with invalid flash_mode throws InvalidParams")
        fun takeCameraPhotoWithInvalidFlashMode() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("flash_mode", "turbo")
                        },
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo without optional params uses defaults")
        fun takeCameraPhotoWithoutOptionalParamsUsesDefaults() =
            runTest {
                coEvery {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = null,
                        height = null,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns ScreenshotData(data = "data", width = 720, height = 480)

                handler.execute(buildJsonObject { put("camera_id", "0") })

                coVerify {
                    cameraProvider.takePhoto(
                        cameraId = "0",
                        width = null,
                        height = null,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                }
            }

        @Test
        @DisplayName("take_camera_photo throws InvalidParams when camera_id missing")
        fun takeCameraPhotoThrowsWhenCameraIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject {})
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SaveCameraPhotoHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SaveCameraPhotoHandler")
    inner class SaveCameraPhotoHandlerTests {
        private lateinit var handler: SaveCameraPhotoHandler

        @BeforeEach
        fun setUp() {
            handler = SaveCameraPhotoHandler(cameraProvider, fileOperationProvider)
        }

        @Test
        @DisplayName("save_camera_photo saves to storage and returns success text")
        fun saveCameraPhotoReturnsSuccessText() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("loc1", "photos/photo.jpg", "image/jpeg")
                } returns mockUri
                coEvery {
                    cameraProvider.savePhoto(
                        cameraId = "0",
                        outputUri = mockUri,
                        width = null,
                        height = null,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns 12345L

                val params =
                    buildJsonObject {
                        put("camera_id", "0")
                        put("location_id", "loc1")
                        put("path", "photos/photo.jpg")
                    }
                val result = handler.execute(params)
                val text = extractTextContent(result)

                assertTrue(text.contains("Photo saved successfully"))
                assertTrue(text.contains("photos/photo.jpg"))
                assertTrue(text.contains("12345"))
            }

        @Test
        @DisplayName("save_camera_photo throws InvalidParams when camera_id missing")
        fun saveCameraPhotoThrowsWhenCameraIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("location_id", "loc1")
                            put("path", "photo.jpg")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_photo throws InvalidParams when location_id missing")
        fun saveCameraPhotoThrowsWhenLocationIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("path", "photo.jpg")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_photo throws InvalidParams when path missing")
        fun saveCameraPhotoThrowsWhenPathMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "loc1")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_photo propagates PermissionDenied from createFileUri when location unauthorized")
        fun saveCameraPhotoPropagatesPermissionDeniedUnauthorized() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("bad_loc", "photo.jpg", "image/jpeg")
                } throws McpToolException.PermissionDenied("Location not authorized: bad_loc")

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "bad_loc")
                            put("path", "photo.jpg")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_photo propagates PermissionDenied from createFileUri when write not allowed")
        fun saveCameraPhotoPropagatesPermissionDeniedWriteNotAllowed() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("readonly", "photo.jpg", "image/jpeg")
                } throws McpToolException.PermissionDenied("Write not permitted for location: readonly")

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "readonly")
                            put("path", "photo.jpg")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_photo calls createFileUri with image/jpeg mime type")
        fun saveCameraPhotoCallsCreateFileUriWithCorrectMimeType() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                } returns mockUri
                coEvery {
                    cameraProvider.savePhoto(
                        cameraId = "0",
                        outputUri = mockUri,
                        width = null,
                        height = null,
                        quality = CameraProvider.DEFAULT_QUALITY,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns 100L

                handler.execute(
                    buildJsonObject {
                        put("camera_id", "0")
                        put("location_id", "loc1")
                        put("path", "photo.jpg")
                    },
                )

                coVerify {
                    fileOperationProvider.createFileUri("loc1", "photo.jpg", "image/jpeg")
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SaveCameraVideoHandler
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SaveCameraVideoHandler")
    inner class SaveCameraVideoHandlerTests {
        private lateinit var handler: SaveCameraVideoHandler

        @BeforeEach
        fun setUp() {
            handler = SaveCameraVideoHandler(cameraProvider, fileOperationProvider)
        }

        private fun buildDefaultVideoParams(
            cameraId: String = "0",
            locationId: String = "loc1",
            path: String = "videos/video.mp4",
            duration: Int = 5,
        ) = buildJsonObject {
            put("camera_id", cameraId)
            put("location_id", locationId)
            put("path", path)
            put("duration", duration)
        }

        private fun mockSuccessfulVideoRecording(duration: Int = 5) {
            coEvery {
                fileOperationProvider.createFileUri("loc1", "videos/video.mp4", "video/mp4")
            } returns mockUri
            coEvery {
                cameraProvider.saveVideo(
                    cameraId = "0",
                    outputUri = mockUri,
                    durationSeconds = duration,
                    width = null,
                    height = null,
                    audio = true,
                    flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                )
            } returns
                VideoRecordingResult(
                    fileSizeBytes = 54321L,
                    durationMs = duration * 1000L,
                    thumbnailData = "thumbnailbase64",
                    thumbnailWidth = 320,
                    thumbnailHeight = 240,
                )
        }

        @Test
        @DisplayName("save_camera_video saves to storage and returns text with thumbnail")
        fun saveCameraVideoReturnsTextAndThumbnail() =
            runTest {
                mockSuccessfulVideoRecording()

                val result = handler.execute(buildDefaultVideoParams())

                assertEquals(2, result.content.size)
                val textContent = result.content[0] as TextContent
                assertTrue(textContent.text.contains("Video saved successfully"))
                assertTrue(textContent.text.contains("54321"))
                val imageContent = result.content[1] as ImageContent
                assertEquals("thumbnailbase64", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }

        @Test
        @DisplayName("save_camera_video with duration 1 succeeds (boundary)")
        fun saveCameraVideoWithDuration1Succeeds() =
            runTest {
                mockSuccessfulVideoRecording(duration = 1)

                val result = handler.execute(buildDefaultVideoParams(duration = 1))
                assertEquals(2, result.content.size)
            }

        @Test
        @DisplayName("save_camera_video with duration 30 succeeds (boundary)")
        fun saveCameraVideoWithDuration30Succeeds() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("loc1", "videos/video.mp4", "video/mp4")
                } returns mockUri
                coEvery {
                    cameraProvider.saveVideo(
                        cameraId = "0",
                        outputUri = mockUri,
                        durationSeconds = 30,
                        width = null,
                        height = null,
                        audio = true,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns
                    VideoRecordingResult(
                        fileSizeBytes = 100000L,
                        durationMs = 30000L,
                        thumbnailData = "thumb",
                        thumbnailWidth = 320,
                        thumbnailHeight = 240,
                    )

                val result = handler.execute(buildDefaultVideoParams(duration = 30))
                assertEquals(2, result.content.size)
            }

        @Test
        @DisplayName("save_camera_video with duration 0 throws InvalidParams")
        fun saveCameraVideoWithDuration0Throws() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildDefaultVideoParams(duration = 0))
                }
            }

        @Test
        @DisplayName("save_camera_video with duration 31 throws InvalidParams")
        fun saveCameraVideoWithDuration31Throws() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildDefaultVideoParams(duration = 31))
                }
            }

        @Test
        @DisplayName("save_camera_video with negative duration throws InvalidParams")
        fun saveCameraVideoWithNegativeDurationThrows() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildDefaultVideoParams(duration = -5))
                }
            }

        @Test
        @DisplayName("save_camera_video throws InvalidParams when camera_id missing")
        fun saveCameraVideoThrowsWhenCameraIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("location_id", "loc1")
                            put("path", "video.mp4")
                            put("duration", 5)
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video throws InvalidParams when location_id missing")
        fun saveCameraVideoThrowsWhenLocationIdMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("path", "video.mp4")
                            put("duration", 5)
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video throws InvalidParams when path missing")
        fun saveCameraVideoThrowsWhenPathMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "loc1")
                            put("duration", 5)
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video throws InvalidParams when duration missing")
        fun saveCameraVideoThrowsWhenDurationMissing() =
            runTest {
                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "loc1")
                            put("path", "video.mp4")
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video propagates PermissionDenied from createFileUri when location unauthorized")
        fun saveCameraVideoPropagatesPermissionDeniedUnauthorized() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("bad_loc", "video.mp4", "video/mp4")
                } throws McpToolException.PermissionDenied("Location not authorized: bad_loc")

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "bad_loc")
                            put("path", "video.mp4")
                            put("duration", 5)
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video propagates PermissionDenied from createFileUri when write not allowed")
        fun saveCameraVideoPropagatesPermissionDeniedWriteNotAllowed() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("readonly", "video.mp4", "video/mp4")
                } throws McpToolException.PermissionDenied("Write not permitted for location: readonly")

                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(
                        buildJsonObject {
                            put("camera_id", "0")
                            put("location_id", "readonly")
                            put("path", "video.mp4")
                            put("duration", 5)
                        },
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video with audio false calls saveVideo with audio=false")
        fun saveCameraVideoWithAudioFalse() =
            runTest {
                coEvery {
                    fileOperationProvider.createFileUri("loc1", "videos/video.mp4", "video/mp4")
                } returns mockUri
                coEvery {
                    cameraProvider.saveVideo(
                        cameraId = "0",
                        outputUri = mockUri,
                        durationSeconds = 5,
                        width = null,
                        height = null,
                        audio = false,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                } returns
                    VideoRecordingResult(
                        fileSizeBytes = 10000L,
                        durationMs = 5000L,
                        thumbnailData = "thumb",
                        thumbnailWidth = 320,
                        thumbnailHeight = 240,
                    )

                handler.execute(
                    buildJsonObject {
                        put("camera_id", "0")
                        put("location_id", "loc1")
                        put("path", "videos/video.mp4")
                        put("duration", 5)
                        put("audio", false)
                    },
                )

                coVerify {
                    cameraProvider.saveVideo(
                        cameraId = "0",
                        outputUri = mockUri,
                        durationSeconds = 5,
                        width = null,
                        height = null,
                        audio = false,
                        flashMode = CameraProvider.DEFAULT_FLASH_MODE,
                    )
                }
            }

        @Test
        @DisplayName("save_camera_video calls createFileUri with video/mp4 mime type")
        fun saveCameraVideoCallsCreateFileUriWithCorrectMimeType() =
            runTest {
                mockSuccessfulVideoRecording()

                handler.execute(buildDefaultVideoParams())

                coVerify {
                    fileOperationProvider.createFileUri("loc1", "videos/video.mp4", "video/mp4")
                }
            }
    }
}
