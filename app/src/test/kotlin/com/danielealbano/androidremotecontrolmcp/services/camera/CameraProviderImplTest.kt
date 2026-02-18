@file:Suppress("TooManyFunctions", "LargeClass")

package com.danielealbano.androidremotecontrolmcp.services.camera

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for [CameraProviderImpl].
 *
 * These tests cover permission checks, parameter validation, and early-exit error paths —
 * logic that executes before any CameraX interaction and can be tested in a pure JVM environment.
 *
 * **Camera-operation tests are in E2E (`E2ECameraTest`):**
 * The following scenarios require a real CameraX camera pipeline (ProcessCameraProvider,
 * ImageCapture, VideoCapture, Recorder) which cannot be meaningfully mocked in JVM unit
 * tests — CameraX internally uses Android camera HAL, SurfaceTexture, and lifecycle
 * observers that are tightly coupled to the Android runtime. These are covered by E2E
 * tests running against a Docker Android emulator with emulated cameras:
 *
 * - `listCameras returns available cameras with correct facing`
 * - `listCameras returns empty list when no cameras available`
 * - `listPhotoResolutions returns sorted resolutions for valid camera`
 * - `listPhotoResolutions throws ActionFailed for invalid camera ID`
 * - `listVideoResolutions returns sorted resolutions for valid camera`
 * - `listVideoResolutions throws ActionFailed for invalid camera ID`
 * - `takePhoto returns base64 JPEG data for valid camera`
 * - `takePhoto applies flash mode correctly (off, on, auto)`
 * - `takePhoto caps resolution at 1920x1080 for inline return`
 * - `takePhoto uses default 720p resolution when none specified`
 * - `savePhoto writes to output URI`
 * - `savePhoto does not cap resolution (no 1920x1080 limit)` (needs storage location)
 * - `saveVideo records for specified duration` (needs storage location)
 * - `saveVideo returns thumbnail with correct dimensions` (needs storage location)
 * - `saveVideo records without audio when audio is false` (needs storage location)
 * - `saveVideo uses torch for flash on and auto modes` (needs storage location)
 * - `camera operation throws ActionFailed on mutex timeout` (requires timing control)
 * - `concurrent camera operations are serialized via mutex` (requires concurrency control)
 *
 * See: `e2e-tests/.../E2ECameraTest.kt` for camera-operation E2E tests.
 */
@ExtendWith(MockKExtension::class)
@DisplayName("CameraProviderImpl")
@ExperimentalCamera2Interop
class CameraProviderImplTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockScreenshotEncoder: ScreenshotEncoder

    @MockK
    private lateinit var mockContentResolver: ContentResolver

    private lateinit var cameraProvider: CameraProviderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        every { mockContext.contentResolver } returns mockContentResolver
        every { ContextCompat.getMainExecutor(mockContext) } returns
            java.util.concurrent.Executor { it.run() }

        cameraProvider = CameraProviderImpl(mockContext, mockScreenshotEncoder)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
        unmockkStatic(android.util.Log::class)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Permission checks
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isCameraPermissionGranted")
    inner class CameraPermissionGranted {
        @Test
        fun `returns true when camera permission is granted`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.CAMERA)
            } returns PackageManager.PERMISSION_GRANTED

            assertTrue(cameraProvider.isCameraPermissionGranted())
        }

        @Test
        fun `returns false when camera permission is denied`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.CAMERA)
            } returns PackageManager.PERMISSION_DENIED

            assertFalse(cameraProvider.isCameraPermissionGranted())
        }
    }

    @Nested
    @DisplayName("isMicrophonePermissionGranted")
    inner class MicrophonePermissionGranted {
        @Test
        fun `returns true when microphone permission is granted`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECORD_AUDIO)
            } returns PackageManager.PERMISSION_GRANTED

            assertTrue(cameraProvider.isMicrophonePermissionGranted())
        }

        @Test
        fun `returns false when microphone permission is denied`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECORD_AUDIO)
            } returns PackageManager.PERMISSION_DENIED

            assertFalse(cameraProvider.isMicrophonePermissionGranted())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // listCameras
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listCameras")
    inner class ListCameras {
        @Test
        fun `listCameras throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.listCameras()
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // listPhotoResolutions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listPhotoResolutions")
    inner class ListPhotoResolutions {
        @Test
        fun `listPhotoResolutions throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.listPhotoResolutions("0")
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // listVideoResolutions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listVideoResolutions")
    inner class ListVideoResolutions {
        @Test
        fun `listVideoResolutions throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.listVideoResolutions("0")
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // takePhoto
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("takePhoto")
    inner class TakePhoto {
        @Test
        fun `takePhoto throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.takePhoto("0")
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // savePhoto
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("savePhoto")
    inner class SavePhoto {
        @Test
        fun `savePhoto throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()
                val mockUri = mockk<Uri>()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.savePhoto("0", mockUri)
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // saveVideo
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveVideo")
    inner class SaveVideo {
        @Test
        fun `saveVideo throws PermissionDenied when camera permission not granted`() =
            runTest {
                setupCameraPermissionDenied()
                val mockUri = mockk<Uri>()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.saveVideo("0", mockUri, 5)
                    }
                assertTrue(exception.message!!.contains("CAMERA permission"))
            }

        @Test
        fun `saveVideo throws PermissionDenied when audio enabled but microphone permission not granted`() =
            runTest {
                setupCameraPermissionGranted()
                every {
                    ContextCompat.checkSelfPermission(
                        mockContext,
                        Manifest.permission.RECORD_AUDIO,
                    )
                } returns PackageManager.PERMISSION_DENIED
                val mockUri = mockk<Uri>()

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        cameraProvider.saveVideo("0", mockUri, 5, audio = true)
                    }
                assertTrue(exception.message!!.contains("RECORD_AUDIO"))
            }

        @Test
        fun `saveVideo throws InvalidParams when duration exceeds 30 seconds`() =
            runTest {
                setupCameraPermissionGranted()
                setupMicrophonePermissionGranted()
                val mockUri = mockk<Uri>()

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        cameraProvider.saveVideo("0", mockUri, 31)
                    }
                assertTrue(exception.message!!.contains("Duration must be between"))
            }

        @Test
        fun `saveVideo throws InvalidParams when duration is less than 1`() =
            runTest {
                setupCameraPermissionGranted()
                setupMicrophonePermissionGranted()
                val mockUri = mockk<Uri>()

                val exception =
                    assertThrows<McpToolException.InvalidParams> {
                        cameraProvider.saveVideo("0", mockUri, 0)
                    }
                assertTrue(exception.message!!.contains("Duration must be between"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────

    private fun setupCameraPermissionDenied() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_DENIED
    }

    private fun setupCameraPermissionGranted() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.CAMERA)
        } returns PackageManager.PERMISSION_GRANTED
    }

    private fun setupMicrophonePermissionGranted() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_GRANTED
    }
}
