package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.inject.Inject

/**
 * Manages the MediaProjection lifecycle for screen capture.
 *
 * Named `MediaProjectionHelper` to avoid shadowing the Android framework class
 * [MediaProjectionManager].
 *
 * Responsibilities:
 * - Initializes MediaProjection from activity result (resultCode + data Intent).
 * - Registers a [MediaProjection.Callback] to handle system-initiated projection stops.
 * - Provides projection state and access for [ScreenCaptureService].
 * - Handles cleanup on stop/destroy.
 *
 * This class follows the Single Responsibility Principle: it handles only
 * MediaProjection lifecycle, while [ScreenCaptureService] handles service
 * lifecycle and screenshot coordination.
 */
class MediaProjectionHelper
    @Inject
    constructor() {
        private val handler = Handler(Looper.getMainLooper())
        private var mediaProjection: MediaProjection? = null

        /**
         * Optional callback invoked when the projection is stopped by the system or user.
         * Set by [ScreenCaptureService] to trigger resource cleanup (ImageReader, VirtualDisplay).
         */
        var onProjectionStopped: (() -> Unit)? = null

        private val projectionCallback =
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system or user")
                    mediaProjection = null
                    onProjectionStopped?.invoke()
                }
            }

        /**
         * Initializes MediaProjection from the activity result obtained via
         * [MediaProjectionManager.createScreenCaptureIntent].
         *
         * Must be called after the user grants screen capture permission.
         *
         * @param context The application or service context to obtain the system [MediaProjectionManager].
         * @param resultCode The result code from the activity result (must be [android.app.Activity.RESULT_OK]).
         * @param data The intent data from the activity result containing the MediaProjection token.
         */
        fun setupProjection(
            context: Context,
            resultCode: Int,
            data: Intent,
        ) {
            Log.i(TAG, "Setting up MediaProjection")
            stopProjection()
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, handler)
            Log.i(TAG, "MediaProjection set up successfully")
        }

        /**
         * Stops and cleans up the MediaProjection.
         *
         * Safe to call multiple times or when no projection is active.
         */
        fun stopProjection() {
            Log.i(TAG, "Stopping MediaProjection")
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        }

        /**
         * Checks whether MediaProjection is currently active and ready for use.
         *
         * @return `true` if [setupProjection] has been called successfully and the projection
         *         has not been stopped.
         */
        fun isProjectionActive(): Boolean = mediaProjection != null

        /**
         * Returns the current MediaProjection instance, or null if not active.
         *
         * Used by [ScreenCaptureService] to create VirtualDisplay for screenshot capture.
         */
        fun getProjection(): MediaProjection? = mediaProjection

        companion object {
            private const val TAG = "MCP:MediaProjectionHlp"
        }
    }
