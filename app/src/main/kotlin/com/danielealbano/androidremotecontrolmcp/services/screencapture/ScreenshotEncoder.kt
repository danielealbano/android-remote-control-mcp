package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.media.Image
import android.util.Base64
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Utility class for converting captured screen images to base64-encoded JPEG data.
 *
 * Handles the full encoding pipeline:
 * 1. Convert [Image] (from ImageReader) to [Bitmap]
 * 2. Compress [Bitmap] to JPEG byte array
 * 3. Base64-encode the JPEG bytes
 *
 * The caller is responsible for recycling any [Bitmap] objects returned by [imageToBitmap].
 */
class ScreenshotEncoder
    @Inject
    constructor() {
        /**
         * Converts an [Image] from ImageReader to a [Bitmap].
         *
         * Handles row padding correctly when `rowStride != width * pixelStride`,
         * which can occur due to memory alignment in the image buffer.
         *
         * The caller is responsible for calling [Bitmap.recycle] on the returned bitmap
         * when it is no longer needed.
         *
         * @param image The captured image from ImageReader. Must have RGBA_8888 format.
         * @return A [Bitmap] containing the image pixels.
         */
        fun imageToBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            return if (rowPadding > 0) {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                croppedBitmap
            } else {
                bitmap
            }
        }

        /**
         * Compresses a [Bitmap] to JPEG format with the specified quality.
         *
         * @param bitmap The bitmap to compress.
         * @param quality JPEG quality (1-100). Values outside this range are clamped.
         * @return The JPEG-encoded bytes.
         */
        fun encodeBitmapToJpeg(
            bitmap: Bitmap,
            quality: Int,
        ): ByteArray {
            val clampedQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
            val outputStream = ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, outputStream)
            check(success) { "Failed to compress bitmap to JPEG" }
            return outputStream.toByteArray()
        }

        /**
         * Base64-encodes a byte array.
         *
         * Uses [Base64.NO_WRAP] to produce a single-line output without line breaks.
         *
         * @param bytes The bytes to encode.
         * @return The base64-encoded string.
         */
        fun encodeToBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        /**
         * Full pipeline: compresses a [Bitmap] to JPEG, base64-encodes the result,
         * and returns a [ScreenshotData] with the image dimensions.
         *
         * Does NOT recycle the input bitmap -- the caller retains ownership.
         *
         * @param bitmap The bitmap to encode.
         * @param quality JPEG quality (1-100). Values outside this range are clamped.
         * @return A [ScreenshotData] containing the base64-encoded JPEG and image metadata.
         */
        fun bitmapToScreenshotData(
            bitmap: Bitmap,
            quality: Int,
        ): ScreenshotData {
            val jpegBytes = encodeBitmapToJpeg(bitmap, quality)
            val base64Data = encodeToBase64(jpegBytes)
            return ScreenshotData(
                data = base64Data,
                width = bitmap.width,
                height = bitmap.height,
            )
        }

        /**
         * Resizes a [Bitmap] proportionally to fit within the specified dimensions.
         *
         * - If both [maxWidth] and [maxHeight] are null, returns the original bitmap unchanged.
         * - If only [maxWidth] is specified, scales to exactly that width with proportional height.
         * - If only [maxHeight] is specified, scales to exactly that height with proportional width.
         * - If both are specified, scales to fit within the bounding box while maintaining aspect ratio.
         *
         * Does NOT recycle the input bitmap. If a new bitmap is returned (resize was needed),
         * the caller is responsible for recycling both the original and the resized bitmap.
         *
         * @param bitmap The source bitmap.
         * @param maxWidth Target maximum width in pixels, or null.
         * @param maxHeight Target maximum height in pixels, or null.
         * @return The original bitmap if no resize is needed, or a new scaled bitmap.
         */
        @Suppress("ReturnCount")
        fun resizeBitmapProportional(
            bitmap: Bitmap,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Bitmap {
            if (maxWidth == null && maxHeight == null) return bitmap

            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            val (targetWidth, targetHeight) =
                when {
                    maxWidth != null && maxHeight != null -> {
                        // Fit within bounding box: scale based on the side that needs more reduction
                        val widthRatio = maxWidth.toFloat() / originalWidth
                        val heightRatio = maxHeight.toFloat() / originalHeight
                        val scale = minOf(widthRatio, heightRatio)
                        Pair((originalWidth * scale).toInt(), (originalHeight * scale).toInt())
                    }
                    maxWidth != null -> {
                        // Scale to exact width, proportional height
                        val scale = maxWidth.toFloat() / originalWidth
                        Pair(maxWidth, (originalHeight * scale).toInt())
                    }
                    else -> {
                        // maxHeight != null: scale to exact height, proportional width
                        val scale = maxHeight!!.toFloat() / originalHeight
                        Pair((originalWidth * scale).toInt(), maxHeight)
                    }
                }

            // Guard: ensure dimensions are at least 1
            val safeWidth = targetWidth.coerceAtLeast(1)
            val safeHeight = targetHeight.coerceAtLeast(1)

            if (safeWidth == originalWidth && safeHeight == originalHeight) return bitmap

            return Bitmap.createScaledBitmap(bitmap, safeWidth, safeHeight, true)
        }

        companion object {
            private const val MIN_QUALITY = 1
            private const val MAX_QUALITY = 100
        }
    }
