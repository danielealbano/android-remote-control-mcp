package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Bound foreground service that manages MediaProjection for on-demand screenshot capture.
 *
 * This service:
 * - Runs as a foreground service (Android requirement for MediaProjection).
 * - Uses the bound service pattern (LocalBinder) for inter-service communication.
 * - Provides thread-safe screenshot capture via [captureScreenshot] using a [Mutex].
 * - Delegates MediaProjection lifecycle to [MediaProjectionHelper] (SRP).
 * - Stores a singleton instance for cross-service access.
 *
 * Lifecycle:
 * 1. McpServerService calls `startForegroundService()` THEN `bindService()` (order matters!).
 * 2. `onStartCommand()` calls `startForeground()` within 5 seconds.
 * 3. MainActivity calls [setupMediaProjection] with the activity result.
 * 4. MCP tool calls invoke [captureScreenshot] to capture the current screen.
 * 5. On service destruction, all resources are released.
 *
 * IMPORTANT: The notification channel (`screen_capture_channel`) MUST be created in
 * `McpApplication.onCreate()` BEFORE this service starts. This service does NOT create
 * its own notification channel.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class ScreenCaptureService : Service() {
    private val binder = LocalBinder()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    @Inject
    lateinit var screenshotEncoder: ScreenshotEncoder

    @Inject
    lateinit var mediaProjectionHelper: MediaProjectionHelper

    /**
     * Binder class that provides access to this service instance.
     */
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionHelper.onProjectionStopped = {
            Log.i(TAG, "MediaProjection stopped, releasing capture resources")
            releaseProjectionResources()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.i(TAG, "ScreenCaptureService starting")
        startForeground(NOTIFICATION_ID, createNotification())
        instance = this
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ScreenCaptureService destroying")
        releaseProjectionResources()
        mediaProjectionHelper.stopProjection()
        coroutineScope.cancel()
        instance = null
        super.onDestroy()
        Log.i(TAG, "ScreenCaptureService destroyed")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "Trim memory requested at level $level")
    }

    /**
     * Initializes MediaProjection from the activity result obtained via
     * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
     *
     * Delegates to [MediaProjectionHelper.setupProjection].
     * Must be called after the user grants screen capture permission.
     *
     * @param resultCode The result code from the activity result (must be [android.app.Activity.RESULT_OK]).
     * @param data The intent data from the activity result containing the MediaProjection token.
     */
    fun setupMediaProjection(
        resultCode: Int,
        data: Intent,
    ) {
        mediaProjectionHelper.setupProjection(this, resultCode, data)
    }

    /**
     * Checks whether MediaProjection is currently active and ready for screenshot capture.
     *
     * @return `true` if [setupMediaProjection] has been called successfully and the projection
     *         has not been stopped.
     */
    fun isMediaProjectionActive(): Boolean = mediaProjectionHelper.isProjectionActive()

    /**
     * Captures a screenshot of the current screen.
     *
     * This method is thread-safe: concurrent calls are serialized via a [Mutex].
     * Only one screenshot capture can happen at a time.
     *
     * @param quality JPEG compression quality (1-100). Default is [DEFAULT_QUALITY].
     * @return [Result.success] with [ScreenshotData] on successful capture,
     *         or [Result.failure] with a descriptive exception on error.
     */
    suspend fun captureScreenshot(quality: Int = DEFAULT_QUALITY): Result<ScreenshotData> =
        captureMutex.withLock {
            captureScreenshotInternal(quality)
        }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun captureScreenshotInternal(quality: Int): Result<ScreenshotData> {
        if (!mediaProjectionHelper.isProjectionActive()) {
            return Result.failure(
                IllegalStateException(
                    "MediaProjection is not active. Grant screen capture permission first.",
                ),
            )
        }

        return try {
            updateScreenMetrics()
            setupImageReader()
            setupVirtualDisplay()

            val image = awaitImage()

            if (image == null) {
                releaseProjectionResources()
                return Result.failure(
                    IllegalStateException(
                        "Screenshot capture timed out after ${CAPTURE_TIMEOUT_MS}ms",
                    ),
                )
            }

            val screenshotData =
                withContext(Dispatchers.Default) {
                    try {
                        val bitmap = screenshotEncoder.imageToBitmap(image)
                        try {
                            screenshotEncoder.bitmapToScreenshotData(bitmap, quality)
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        image.close()
                    }
                }

            releaseProjectionResources()
            Result.success(screenshotData)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed", e)
            releaseProjectionResources()
            Result.failure(e)
        }
    }

    private suspend fun awaitImage(): android.media.Image? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                imageReader?.setOnImageAvailableListener(
                    { reader ->
                        val img = reader.acquireLatestImage()
                        if (img != null) {
                            if (continuation.isActive) {
                                imageReader?.setOnImageAvailableListener(null, null)
                                continuation.resume(img)
                            } else {
                                img.close()
                            }
                        }
                    },
                    handler,
                )

                continuation.invokeOnCancellation {
                    imageReader?.setOnImageAvailableListener(null, null)
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun updateScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val displayMetrics = android.util.DisplayMetrics()
            display.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        val displayMetrics = resources.displayMetrics
        screenDensity = displayMetrics.densityDpi
    }

    private fun setupImageReader() {
        imageReader?.close()
        imageReader =
            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_MAX_IMAGES,
            )
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay =
            mediaProjectionHelper.getProjection()?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler,
            )
    }

    private fun releaseProjectionResources() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun createNotification(): Notification {
        val channelId = getString(R.string.notification_channel_screen_capture_id)
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_screen_capture_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MCP:ScreenCaptureService"
        const val NOTIFICATION_ID = 2002
        const val DEFAULT_QUALITY = 80

        private const val IMAGE_READER_MAX_IMAGES = 2
        private const val VIRTUAL_DISPLAY_NAME = "McpScreenCapture"
        private const val CAPTURE_TIMEOUT_MS = 5000L

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }
}
