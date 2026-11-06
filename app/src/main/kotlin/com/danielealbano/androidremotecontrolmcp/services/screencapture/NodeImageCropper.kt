package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Crops a node's screen bounds out of a full-resolution screenshot and encodes it as a JPEG of at most
 * [MAX_IMAGE_SIZE] px on its longest side (never scaled up), with no padding.
 */
class NodeImageCropper
    @Inject
    constructor(
        private val screenshotEncoder: ScreenshotEncoder,
    ) {
        /** A crop rectangle in bitmap pixels. Pure data, not `android.graphics.Rect`. */
        internal data class CropRect(
            val left: Int,
            val top: Int,
            val width: Int,
            val height: Int,
        )

        /**
         * [bounds] (screen coordinates) scaled to a bitmap of [bitmapWidth] x [bitmapHeight] and clamped to it; null
         * when a screen side is ≤ 0 or nothing is left.
         */
        internal fun cropRect(
            bounds: BoundsData,
            bitmapWidth: Int,
            bitmapHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
        ): CropRect? {
            if (screenWidth <= 0 || screenHeight <= 0) return null
            val scaleX = bitmapWidth.toFloat() / screenWidth
            val scaleY = bitmapHeight.toFloat() / screenHeight
            val left = floor(bounds.left * scaleX).toInt().coerceIn(0, bitmapWidth)
            val top = floor(bounds.top * scaleY).toInt().coerceIn(0, bitmapHeight)
            val right = ceil(bounds.right * scaleX).toInt().coerceIn(0, bitmapWidth)
            val bottom = ceil(bounds.bottom * scaleY).toInt().coerceIn(0, bitmapHeight)
            return if (right > left && bottom > top) CropRect(left, top, right - left, bottom - top) else null
        }

        /**
         * The JPEG of [bounds] cropped from [bitmap], or null when nothing of it is on the bitmap. Never recycles
         * [bitmap]; recycles an intermediate only when it is not its input, as `createBitmap` returns an immutable
         * source itself for a full-bitmap rect and `resizeBitmapProportional` returns its input when no resize is
         * needed.
         */
        fun crop(
            bitmap: Bitmap,
            bounds: BoundsData,
            screenWidth: Int,
            screenHeight: Int,
        ): ScreenshotData? {
            val rect = cropRect(bounds, bitmap.width, bitmap.height, screenWidth, screenHeight) ?: return null
            val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            var resized: Bitmap? = null
            try {
                resized = screenshotEncoder.resizeBitmapProportional(cropped, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)
                return screenshotEncoder.bitmapToScreenshotData(resized, ScreenCaptureProvider.DEFAULT_QUALITY)
            } finally {
                if (resized != null && resized !== cropped) resized.recycle()
                if (cropped !== bitmap) cropped.recycle()
            }
        }

        companion object {
            /** The longest side of a node image, as the `get_screen_state` screenshot. */
            const val MAX_IMAGE_SIZE = 700
        }
    }
