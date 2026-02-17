package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import javax.inject.Inject

/**
 * Annotates a screenshot bitmap with bounding boxes and element ID labels
 * for on-screen UI elements.
 *
 * Used by [GetScreenStateHandler] when `include_screenshot=true` to help
 * vision-language models identify and reference UI elements.
 *
 * Drawing style follows Set-of-Mark prompting conventions:
 * - Red dashed bounding boxes (2px scaled)
 * - Semi-transparent red pill labels with white bold text
 * - Labels show the element ID hash (without `node_` prefix)
 */
class ScreenshotAnnotator
    @Inject
    constructor() {
        /**
         * Pure-Kotlin data class for scaled element bounds.
         * Avoids [RectF] (Android framework) in testable methods so unit tests
         * on JVM with `isReturnDefaultValues = true` work correctly.
         */
        internal data class ScaledBounds(
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
        )

        /**
         * Annotates a screenshot with bounding boxes and labels for the given elements.
         *
         * Creates a mutable copy of the input bitmap and draws on it. The input bitmap
         * is NOT modified or recycled -- the caller retains ownership of both.
         *
         * Paint objects are created ONCE before the element loop for efficiency.
         *
         * @param bitmap The resized screenshot bitmap.
         * @param elements Flat list of on-screen elements to annotate (already filtered).
         * @param screenWidth Original screen width in pixels (for coordinate mapping).
         * @param screenHeight Original screen height in pixels (for coordinate mapping).
         * @return A new annotated [Bitmap]. Caller must recycle when done.
         * @throws IllegalStateException if the bitmap cannot be copied.
         * @throws IllegalArgumentException if [screenWidth] or [screenHeight] is not positive.
         */
        @Suppress("LongMethod", "TooGenericExceptionCaught")
        fun annotate(
            bitmap: Bitmap,
            elements: List<AccessibilityNodeData>,
            screenWidth: Int,
            screenHeight: Int,
        ): Bitmap {
            require(screenWidth > 0) { "screenWidth must be positive, was $screenWidth" }
            require(screenHeight > 0) { "screenHeight must be positive, was $screenHeight" }

            // Always use ARGB_8888 for the mutable copy -- HARDWARE bitmaps cannot be mutable
            val copy =
                checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) {
                    "Failed to create mutable bitmap copy for annotation"
                }
            if (elements.isEmpty()) return copy

            try {
                val canvas = Canvas(copy)
                val scaleX = copy.width.toFloat() / screenWidth.toFloat()
                val scaleY = copy.height.toFloat() / screenHeight.toFloat()
                val density = copy.width.toFloat() / REFERENCE_WIDTH_DP

                // Create Paint objects ONCE before the loop
                val boxPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = BOX_STROKE_WIDTH_DP * density
                        pathEffect =
                            DashPathEffect(
                                floatArrayOf(DASH_LENGTH_DP * density, DASH_GAP_DP * density),
                                0f,
                            )
                    }
                val labelBgPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(LABEL_BG_ALPHA, COLOR_CHANNEL_MAX, 0, 0)
                        style = Paint.Style.FILL
                    }
                val labelTextPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = LABEL_TEXT_SIZE_DP * density
                        typeface = Typeface.DEFAULT_BOLD
                    }
                val padding = LABEL_PADDING_DP * density

                for (element in elements) {
                    val scaled =
                        computeScaledBounds(
                            element,
                            scaleX,
                            scaleY,
                            copy.width,
                            copy.height,
                        ) ?: continue

                    // Convert ScaledBounds to RectF for Canvas drawing
                    val scaledRect = RectF(scaled.left, scaled.top, scaled.right, scaled.bottom)

                    // Draw bounding box
                    canvas.drawRect(scaledRect, boxPaint)

                    // Draw label
                    val label = extractLabel(element.id)
                    val textWidth = labelTextPaint.measureText(label)
                    val textHeight = labelTextPaint.textSize
                    val labelRect =
                        RectF(
                            scaledRect.left,
                            scaledRect.top - textHeight - padding * 2,
                            scaledRect.left + textWidth + padding * 2,
                            scaledRect.top,
                        )
                    // Shift label inside box if it would go above bitmap
                    if (labelRect.top < 0) {
                        labelRect.offset(0f, -labelRect.top)
                    }
                    canvas.drawRoundRect(labelRect, padding, padding, labelBgPaint)
                    canvas.drawText(
                        label,
                        labelRect.left + padding,
                        labelRect.bottom - padding,
                        labelTextPaint,
                    )
                }

                return copy
            } catch (e: Exception) {
                copy.recycle()
                throw e
            }
        }

        /**
         * Computes the scaled bounding rectangle for an element on the bitmap.
         *
         * Returns a pure-Kotlin [ScaledBounds] (not [RectF]) so this method is
         * fully testable on JVM without Android framework stubs.
         *
         * @return Scaled [ScaledBounds] clamped to bitmap bounds, or null if fully outside.
         */
        internal fun computeScaledBounds(
            element: AccessibilityNodeData,
            scaleX: Float,
            scaleY: Float,
            bitmapWidth: Int,
            bitmapHeight: Int,
        ): ScaledBounds? {
            val bounds = element.bounds
            val left = (bounds.left * scaleX).coerceIn(0f, bitmapWidth.toFloat())
            val top = (bounds.top * scaleY).coerceIn(0f, bitmapHeight.toFloat())
            val right = (bounds.right * scaleX).coerceIn(0f, bitmapWidth.toFloat())
            val bottom = (bounds.bottom * scaleY).coerceIn(0f, bitmapHeight.toFloat())

            // Skip if fully collapsed after clamping
            if (right <= left || bottom <= top) return null

            return ScaledBounds(left, top, right, bottom)
        }

        /**
         * Extracts the display label from an element ID by stripping the `node_` prefix.
         */
        internal fun extractLabel(elementId: String): String =
            if (elementId.startsWith(NODE_ID_PREFIX)) {
                elementId.removePrefix(NODE_ID_PREFIX)
            } else {
                elementId
            }

        companion object {
            internal const val NODE_ID_PREFIX = "node_"
            private const val BOX_STROKE_WIDTH_DP = 2f
            private const val DASH_LENGTH_DP = 6f
            private const val DASH_GAP_DP = 3f
            private const val LABEL_TEXT_SIZE_DP = 10f
            private const val LABEL_PADDING_DP = 2f
            private const val LABEL_BG_ALPHA = 180
            private const val COLOR_CHANNEL_MAX = 255
            private const val REFERENCE_WIDTH_DP = 360f
        }
    }
