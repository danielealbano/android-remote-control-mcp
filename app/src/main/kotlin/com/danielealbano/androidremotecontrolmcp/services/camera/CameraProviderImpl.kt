@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.data.model.CameraInfo
import com.danielealbano.androidremotecontrolmcp.data.model.PhotoResolution
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.data.model.VideoResolution
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * CameraX-based implementation of [CameraProvider].
 *
 * Uses [ProcessCameraProvider] for camera access, [ImageCapture] for photos,
 * [VideoCapture] with [Recorder] for video, and [MediaMetadataRetriever]
 * for thumbnail extraction.
 *
 * All camera operations are serialized via [Mutex] with timeout to prevent
 * concurrent camera access and indefinite blocking.
 */
@Suppress("LargeClass", "TooGenericExceptionCaught", "SwallowedException")
class CameraProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val screenshotEncoder: ScreenshotEncoder,
    ) : CameraProvider {
        private val cameraMutex = Mutex()
        private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

        // ─────────────────────────────────────────────────────────────────────
        // Permission checks
        // ─────────────────────────────────────────────────────────────────────

        override fun isCameraPermissionGranted(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

        override fun isMicrophonePermissionGranted(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        // ─────────────────────────────────────────────────────────────────────
        // Camera enumeration
        // ─────────────────────────────────────────────────────────────────────

        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun listCameras(): List<CameraInfo> {
            requireCameraPermission()
            val provider = getCameraProvider()
            return provider.availableCameraInfos.map { cameraInfo ->
                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                val cameraId = camera2Info.cameraId
                val lensFacing =
                    camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_FACING,
                    )
                val facing =
                    when (lensFacing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                        else -> "unknown"
                    }
                val hasFlash = cameraInfo.hasFlashUnit()
                val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
                val supportsVideo =
                    videoCapabilities
                        .getSupportedQualities(
                            androidx.camera.core.DynamicRange.SDR,
                        ).isNotEmpty()

                CameraInfo(
                    cameraId = cameraId,
                    facing = facing,
                    hasFlash = hasFlash,
                    supportsPhoto = true,
                    supportsVideo = supportsVideo,
                )
            }
        }

        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun listPhotoResolutions(cameraId: String): List<PhotoResolution> {
            requireCameraPermission()
            val provider = getCameraProvider()
            val cameraInfo = findCameraInfo(provider, cameraId)
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val streamConfigMap =
                camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                ) ?: throw McpToolException.ActionFailed(
                    "Could not query stream configuration for camera '$cameraId'",
                )

            val outputSizes =
                streamConfigMap.getOutputSizes(ImageFormat.JPEG)
                    ?: return emptyList()

            return outputSizes
                .map { size ->
                    val mp = (size.width.toLong() * size.height.toLong()) / MEGAPIXEL_DIVISOR
                    val megapixels = (mp * MEGAPIXEL_ROUNDING).roundToInt() / MEGAPIXEL_ROUNDING
                    PhotoResolution(
                        width = size.width,
                        height = size.height,
                        megapixels = megapixels,
                        aspectRatio = computeAspectRatio(size.width, size.height),
                    )
                }.sortedByDescending { it.megapixels }
        }

        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun listVideoResolutions(cameraId: String): List<VideoResolution> {
            requireCameraPermission()
            val provider = getCameraProvider()
            val cameraInfo = findCameraInfo(provider, cameraId)
            val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
            val supportedQualities =
                videoCapabilities.getSupportedQualities(
                    androidx.camera.core.DynamicRange.SDR,
                )

            return supportedQualities
                .mapNotNull { quality ->
                    val size = qualityToSize(quality) ?: return@mapNotNull null
                    VideoResolution(
                        width = size.width,
                        height = size.height,
                        aspectRatio = computeAspectRatio(size.width, size.height),
                        qualityLabel = qualityToLabel(quality),
                    )
                }.sortedByDescending { it.width }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Photo capture (inline base64)
        // ─────────────────────────────────────────────────────────────────────

        @Suppress("CyclomaticComplexMethod")
        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun takePhoto(
            cameraId: String,
            width: Int?,
            height: Int?,
            quality: Int,
            flashMode: String,
        ): ScreenshotData {
            requireCameraPermission()

            val cappedWidth = width?.coerceAtMost(CameraProvider.MAX_INLINE_PHOTO_WIDTH)
            val cappedHeight = height?.coerceAtMost(CameraProvider.MAX_INLINE_PHOTO_HEIGHT)

            return withTimeout(CameraProvider.PHOTO_OPERATION_TIMEOUT_MS) {
                cameraMutex.withLock {
                    val lifecycleOwner = ServiceLifecycleOwner()
                    try {
                        withContext(Dispatchers.Main) {
                            val provider = getCameraProvider()
                            val selector = buildCameraSelector(provider, cameraId)
                            val imageCapture =
                                buildImageCapture(
                                    cappedWidth,
                                    cappedHeight,
                                    flashMode,
                                )

                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                            lifecycleOwner.start()

                            val imageProxy = captureImage(imageCapture)
                            try {
                                val bitmap = imageProxyToBitmap(imageProxy)
                                try {
                                    withContext(Dispatchers.Default) {
                                        screenshotEncoder.bitmapToScreenshotData(
                                            bitmap,
                                            quality,
                                        )
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            } finally {
                                imageProxy.close()
                            }
                        }
                    } catch (e: McpToolException) {
                        throw e
                    } catch (e: Exception) {
                        throw McpToolException.ActionFailed(
                            "Photo capture failed: ${e.message ?: "Unknown error"}",
                        )
                    } finally {
                        withContext(Dispatchers.Main) {
                            lifecycleOwner.stop()
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Photo capture (save to URI)
        // ─────────────────────────────────────────────────────────────────────

        @Suppress("CyclomaticComplexMethod")
        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun savePhoto(
            cameraId: String,
            outputUri: Uri,
            width: Int?,
            height: Int?,
            quality: Int,
            flashMode: String,
        ): Long {
            requireCameraPermission()

            return withTimeout(CameraProvider.PHOTO_OPERATION_TIMEOUT_MS) {
                cameraMutex.withLock {
                    val lifecycleOwner = ServiceLifecycleOwner()
                    try {
                        withContext(Dispatchers.Main) {
                            val provider = getCameraProvider()
                            val selector = buildCameraSelector(provider, cameraId)
                            val imageCapture = buildImageCapture(width, height, flashMode)

                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
                            lifecycleOwner.start()

                            val imageProxy = captureImage(imageCapture)
                            var outputStream: java.io.OutputStream? = null
                            try {
                                val bitmap = imageProxyToBitmap(imageProxy)
                                try {
                                    val jpegBytes =
                                        withContext(Dispatchers.Default) {
                                            screenshotEncoder.encodeBitmapToJpeg(bitmap, quality)
                                        }
                                    outputStream = context.contentResolver.openOutputStream(
                                        outputUri,
                                    ) ?: throw McpToolException.ActionFailed(
                                        "Failed to open output URI for writing",
                                    )
                                    outputStream.write(jpegBytes)
                                } finally {
                                    bitmap.recycle()
                                }
                            } finally {
                                outputStream?.close()
                                imageProxy.close()
                            }
                        }

                        getFileSize(outputUri)
                    } catch (e: McpToolException) {
                        throw e
                    } catch (e: Exception) {
                        throw McpToolException.ActionFailed(
                            "Photo save failed: ${e.message ?: "Unknown error"}",
                        )
                    } finally {
                        withContext(Dispatchers.Main) {
                            lifecycleOwner.stop()
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Video recording (save to URI)
        // ─────────────────────────────────────────────────────────────────────

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        override suspend fun saveVideo(
            cameraId: String,
            outputUri: Uri,
            durationSeconds: Int,
            width: Int?,
            height: Int?,
            audio: Boolean,
            flashMode: String,
        ): VideoRecordingResult {
            requireCameraPermission()
            if (audio && !isMicrophonePermissionGranted()) {
                throw McpToolException.PermissionDenied(
                    "RECORD_AUDIO permission not granted. Required for video recording with audio.",
                )
            }
            if (durationSeconds < 1 || durationSeconds > CameraProvider.MAX_VIDEO_DURATION_SECONDS) {
                throw McpToolException.InvalidParams(
                    "Duration must be between 1 and ${CameraProvider.MAX_VIDEO_DURATION_SECONDS} " +
                        "seconds, got: $durationSeconds",
                )
            }

            val timeoutMs =
                CameraProvider.VIDEO_OPERATION_BASE_TIMEOUT_MS +
                    durationSeconds * MILLIS_PER_SECOND

            return withTimeout(timeoutMs) {
                cameraMutex.withLock {
                    val lifecycleOwner = ServiceLifecycleOwner()
                    var pfd: android.os.ParcelFileDescriptor? = null
                    try {
                        val recordingResult =
                            withContext(Dispatchers.Main) {
                                val provider = getCameraProvider()
                                val selector = buildCameraSelector(provider, cameraId)

                                val qualitySelector = buildVideoQualitySelector(width, height)
                                val recorder =
                                    Recorder
                                        .Builder()
                                        .setQualitySelector(qualitySelector)
                                        .build()
                                val videoCapture = VideoCapture.withOutput(recorder)

                                provider.unbindAll()
                                val camera =
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        selector,
                                        videoCapture,
                                    )
                                lifecycleOwner.start()

                                if (flashMode == "on" || flashMode == "auto") {
                                    camera.cameraControl.enableTorch(true)
                                }

                                val openedPfd =
                                    context.contentResolver.openFileDescriptor(outputUri, "w")
                                        ?: throw McpToolException.ActionFailed(
                                            "Failed to open output URI for video recording",
                                        )
                                pfd = openedPfd

                                val outputOptions =
                                    FileDescriptorOutputOptions
                                        .Builder(openedPfd)
                                        .build()

                                val pendingRecording =
                                    if (audio) {
                                        @Suppress("MissingPermission")
                                        recorder
                                            .prepareRecording(context, outputOptions)
                                            .withAudioEnabled()
                                    } else {
                                        recorder.prepareRecording(context, outputOptions)
                                    }

                                val startTimeMs = System.currentTimeMillis()

                                val recording =
                                    pendingRecording.start(mainExecutor) { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            Log.d(TAG, "Video recording finalized")
                                        }
                                    }

                                delay(durationSeconds * MILLIS_PER_SECOND)
                                recording.stop()

                                val actualDurationMs = System.currentTimeMillis() - startTimeMs

                                if (flashMode == "on" || flashMode == "auto") {
                                    camera.cameraControl.enableTorch(false)
                                }

                                actualDurationMs
                            }

                        pfd?.close()
                        pfd = null

                        val fileSize = getFileSize(outputUri)
                        val thumbnail = extractVideoThumbnail(outputUri)

                        VideoRecordingResult(
                            fileSizeBytes = fileSize,
                            durationMs = recordingResult,
                            thumbnailData = thumbnail.data,
                            thumbnailWidth = thumbnail.width,
                            thumbnailHeight = thumbnail.height,
                        )
                    } catch (e: McpToolException) {
                        throw e
                    } catch (e: Exception) {
                        throw McpToolException.ActionFailed(
                            "Video recording failed: ${e.message ?: "Unknown error"}",
                        )
                    } finally {
                        pfd?.close()
                        withContext(Dispatchers.Main) {
                            lifecycleOwner.stop()
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Private helpers
        // ─────────────────────────────────────────────────────────────────────

        private fun requireCameraPermission() {
            if (!isCameraPermissionGranted()) {
                throw McpToolException.PermissionDenied(
                    "CAMERA permission not granted. Please grant camera permission in the app.",
                )
            }
        }

        private suspend fun getCameraProvider(): ProcessCameraProvider =
            suspendCancellableCoroutine { continuation ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    { continuation.resume(future.get()) },
                    mainExecutor,
                )
            }

        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        private fun findCameraInfo(
            provider: ProcessCameraProvider,
            cameraId: String,
        ): androidx.camera.core.CameraInfo {
            for (cameraInfo in provider.availableCameraInfos) {
                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                if (camera2Info.cameraId == cameraId) {
                    return cameraInfo
                }
            }
            throw McpToolException.ActionFailed("Camera not found: '$cameraId'")
        }

        @androidx.camera.camera2.interop.ExperimentalCamera2Interop
        private fun buildCameraSelector(
            provider: ProcessCameraProvider,
            cameraId: String,
        ): CameraSelector {
            val cameraInfo = findCameraInfo(provider, cameraId)
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val lensFacing =
                camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING,
                )

            val selectorFacing =
                when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.LENS_FACING_BACK
                    else -> CameraSelector.LENS_FACING_BACK
                }

            return CameraSelector
                .Builder()
                .requireLensFacing(selectorFacing)
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == cameraId
                    }
                }.build()
        }

        private fun buildImageCapture(
            width: Int?,
            height: Int?,
            flashMode: String,
        ): ImageCapture {
            val targetWidth = width ?: CameraProvider.DEFAULT_RESOLUTION_WIDTH
            val targetHeight = height ?: CameraProvider.DEFAULT_RESOLUTION_HEIGHT

            val resolutionStrategy =
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            val resolutionSelector =
                ResolutionSelector
                    .Builder()
                    .setResolutionStrategy(resolutionStrategy)
                    .build()

            val captureFlashMode =
                when (flashMode) {
                    "off" -> ImageCapture.FLASH_MODE_OFF
                    "on" -> ImageCapture.FLASH_MODE_ON
                    "auto" -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_AUTO
                }

            return ImageCapture
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setFlashMode(captureFlashMode)
                .build()
        }

        @Suppress("UnusedParameter")
        private fun buildVideoQualitySelector(
            width: Int?,
            height: Int?,
        ): QualitySelector {
            val targetHeight = height ?: CameraProvider.DEFAULT_RESOLUTION_HEIGHT
            val quality =
                when {
                    targetHeight >= UHD_HEIGHT -> Quality.UHD
                    targetHeight >= FHD_HEIGHT -> Quality.FHD
                    targetHeight >= HD_HEIGHT -> Quality.HD
                    else -> Quality.SD
                }
            return QualitySelector.from(
                quality,
                androidx.camera.video.FallbackStrategy
                    .higherQualityOrLowerThan(quality),
            )
        }

        private suspend fun captureImage(imageCapture: ImageCapture): ImageProxy =
            suspendCancellableCoroutine { continuation ->
                imageCapture.takePicture(
                    mainExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            continuation.resume(image)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(
                                McpToolException.ActionFailed(
                                    "Image capture failed: ${exception.message}",
                                ),
                            )
                        }
                    },
                )
            }

        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw McpToolException.ActionFailed("Failed to decode captured image to bitmap")
        }

        private fun getFileSize(uri: Uri): Long =
            context.contentResolver
                .openFileDescriptor(uri, "r")
                ?.use { it.statSize }
                ?: 0L

        @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ThrowsCount")
        private fun extractVideoThumbnail(uri: Uri): ScreenshotData {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame =
                    retriever.getFrameAtTime(0)
                        ?: throw McpToolException.ActionFailed(
                            "Failed to extract video thumbnail",
                        )
                try {
                    val resized =
                        screenshotEncoder.resizeBitmapProportional(
                            frame,
                            THUMBNAIL_MAX_WIDTH,
                            null,
                        )
                    try {
                        return screenshotEncoder.bitmapToScreenshotData(
                            resized,
                            THUMBNAIL_QUALITY,
                        )
                    } finally {
                        if (resized !== frame) {
                            resized.recycle()
                        }
                    }
                } finally {
                    frame.recycle()
                }
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to extract video thumbnail: ${e.message ?: "Unknown error"}",
                )
            } finally {
                retriever.close()
            }
        }

        private fun computeAspectRatio(
            width: Int,
            height: Int,
        ): String {
            val gcd = gcd(width, height)
            return "${width / gcd}:${height / gcd}"
        }

        private fun gcd(
            a: Int,
            b: Int,
        ): Int = if (b == 0) a else gcd(b, a % b)

        private fun qualityToSize(quality: Quality): Size? =
            when (quality) {
                Quality.UHD -> Size(UHD_WIDTH, UHD_HEIGHT)
                Quality.FHD -> Size(FHD_WIDTH, FHD_HEIGHT)
                Quality.HD -> Size(HD_WIDTH, HD_HEIGHT)
                Quality.SD -> Size(SD_WIDTH, SD_HEIGHT)
                else -> null
            }

        private fun qualityToLabel(quality: Quality): String =
            when (quality) {
                Quality.UHD -> "UHD"
                Quality.FHD -> "FHD"
                Quality.HD -> "HD"
                Quality.SD -> "SD"
                else -> "Unknown"
            }

        companion object {
            private const val TAG = "MCP:CameraProvider"
            private const val MEGAPIXEL_DIVISOR = 1_000_000.0
            private const val MEGAPIXEL_ROUNDING = 10.0
            private const val MILLIS_PER_SECOND = 1000L
            private const val THUMBNAIL_MAX_WIDTH = 720
            private const val THUMBNAIL_QUALITY = 80
            private const val UHD_WIDTH = 3840
            private const val UHD_HEIGHT = 2160
            private const val FHD_WIDTH = 1920
            private const val FHD_HEIGHT = 1080
            private const val HD_WIDTH = 1280
            private const val HD_HEIGHT = 720
            private const val SD_WIDTH = 720
            private const val SD_HEIGHT = 480
        }
    }
