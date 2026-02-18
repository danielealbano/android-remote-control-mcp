package com.danielealbano.androidremotecontrolmcp.services.camera

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.CameraInfo
import com.danielealbano.androidremotecontrolmcp.data.model.PhotoResolution
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.data.model.VideoResolution

/**
 * Abstracts access to device cameras for photo capture and video recording.
 *
 * Production implementation uses CameraX (Jetpack).
 * Tools use this interface to enable JVM-based testing with mock implementations.
 */
interface CameraProvider {
    companion object {
        /** Default JPEG quality for photo capture. */
        const val DEFAULT_QUALITY = 80

        /** Default flash mode. */
        const val DEFAULT_FLASH_MODE = "auto"

        /** Maximum video recording duration in seconds. */
        const val MAX_VIDEO_DURATION_SECONDS = 30

        /** Default resolution target (720p) width. */
        const val DEFAULT_RESOLUTION_WIDTH = 1280

        /** Default resolution target (720p) height. */
        const val DEFAULT_RESOLUTION_HEIGHT = 720

        /** Maximum resolution for inline (base64) photo capture to prevent large responses. */
        const val MAX_INLINE_PHOTO_WIDTH = 1920

        /** Maximum resolution for inline (base64) photo capture to prevent large responses. */
        const val MAX_INLINE_PHOTO_HEIGHT = 1080

        /** Mutex timeout for photo operations in milliseconds. */
        const val PHOTO_OPERATION_TIMEOUT_MS = 45_000L

        /** Base mutex timeout for video operations in milliseconds (added to duration). */
        const val VIDEO_OPERATION_BASE_TIMEOUT_MS = 45_000L

        /** Valid flash modes. */
        val VALID_FLASH_MODES = setOf("off", "on", "auto")
    }

    /**
     * Lists all available cameras on the device.
     *
     * @return List of [CameraInfo] for each available camera.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if camera enumeration fails.
     */
    suspend fun listCameras(): List<CameraInfo>

    /**
     * Lists supported photo resolutions for a specific camera.
     *
     * @param cameraId Camera identifier from [listCameras].
     * @return List of [PhotoResolution] sorted by megapixels descending.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if camera not found or query fails.
     */
    suspend fun listPhotoResolutions(cameraId: String): List<PhotoResolution>

    /**
     * Lists supported video resolutions for a specific camera.
     *
     * @param cameraId Camera identifier from [listCameras].
     * @return List of [VideoResolution] sorted by width descending.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if camera not found or query fails.
     */
    suspend fun listVideoResolutions(cameraId: String): List<VideoResolution>

    /**
     * Captures a photo and returns it as base64-encoded JPEG.
     *
     * Resolution is capped at [MAX_INLINE_PHOTO_WIDTH]x[MAX_INLINE_PHOTO_HEIGHT]
     * (1920x1080) to prevent excessively large base64 JSON responses.
     * If a higher resolution is requested, it is clamped to this maximum.
     * Use [savePhoto] for full-resolution captures.
     *
     * @param cameraId Camera identifier from [listCameras].
     * @param width Requested width in pixels, or null for default (720p closest match).
     *        Capped at [MAX_INLINE_PHOTO_WIDTH].
     * @param height Requested height in pixels, or null for default (720p closest match).
     *        Capped at [MAX_INLINE_PHOTO_HEIGHT].
     * @param quality JPEG quality (1-100).
     * @param flashMode Flash mode: "off", "on", or "auto".
     * @return [ScreenshotData] containing the base64 JPEG data and dimensions.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if capture fails or operation times out.
     */
    suspend fun takePhoto(
        cameraId: String,
        width: Int? = null,
        height: Int? = null,
        quality: Int = DEFAULT_QUALITY,
        flashMode: String = DEFAULT_FLASH_MODE,
    ): ScreenshotData

    /**
     * Captures a photo and saves it to the specified URI.
     *
     * @param cameraId Camera identifier from [listCameras].
     * @param outputUri SAF document URI to write the photo to.
     * @param width Requested width in pixels, or null for default (720p closest match).
     * @param height Requested height in pixels, or null for default (720p closest match).
     * @param quality JPEG quality (1-100).
     * @param flashMode Flash mode: "off", "on", or "auto".
     * @return File size in bytes of the saved photo.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if capture, save fails, or operation times out.
     */
    @Suppress("LongParameterList")
    suspend fun savePhoto(
        cameraId: String,
        outputUri: Uri,
        width: Int? = null,
        height: Int? = null,
        quality: Int = DEFAULT_QUALITY,
        flashMode: String = DEFAULT_FLASH_MODE,
    ): Long

    /**
     * Records a video and saves it to the specified URI.
     *
     * @param cameraId Camera identifier from [listCameras].
     * @param outputUri SAF document URI to write the video to.
     * @param durationSeconds Recording duration in seconds (1 to [MAX_VIDEO_DURATION_SECONDS]).
     * @param width Requested width in pixels, or null for default (720p closest match).
     * @param height Requested height in pixels, or null for default (720p closest match).
     * @param audio Whether to record audio (requires RECORD_AUDIO permission).
     * @param flashMode Flash mode: "off", "on", or "auto" (torch mode for video).
     * @return [VideoRecordingResult] containing file size and thumbnail data.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.PermissionDenied
     *         if camera (or microphone when audio=true) permission not granted.
     * @throws com.danielealbano.androidremotecontrolmcp.mcp.McpToolException.ActionFailed
     *         if recording fails or operation times out.
     */
    @Suppress("LongParameterList")
    suspend fun saveVideo(
        cameraId: String,
        outputUri: Uri,
        durationSeconds: Int,
        width: Int? = null,
        height: Int? = null,
        audio: Boolean = true,
        flashMode: String = DEFAULT_FLASH_MODE,
    ): VideoRecordingResult

    /**
     * Checks whether the CAMERA runtime permission is granted.
     */
    fun isCameraPermissionGranted(): Boolean

    /**
     * Checks whether the RECORD_AUDIO runtime permission is granted.
     */
    fun isMicrophonePermissionGranted(): Boolean
}

/**
 * Result of a video recording operation.
 *
 * @property fileSizeBytes Size of the recorded video file in bytes.
 * @property durationMs Actual recording duration in milliseconds.
 * @property thumbnailData Base64-encoded JPEG thumbnail (first frame).
 * @property thumbnailWidth Thumbnail width in pixels.
 * @property thumbnailHeight Thumbnail height in pixels.
 */
data class VideoRecordingResult(
    val fileSizeBytes: Long,
    val durationMs: Long,
    val thumbnailData: String,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
)
