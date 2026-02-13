package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import javax.inject.Inject

/**
 * Production implementation of [ScreenCaptureProvider] that uses
 * AccessibilityService.takeScreenshot() (Android 11+) for capturing screenshots.
 *
 * This approach does NOT require user consent or additional permissions beyond
 * the accessibility service being enabled with canTakeScreenshot="true".
 *
 * This class is Hilt-injectable and stateless.
 */
class ScreenCaptureProviderImpl
    @Inject
    constructor(
        private val screenshotEncoder: ScreenshotEncoder,
    ) : ScreenCaptureProvider {
        private sealed class ServiceValidation {
            data class Valid(val service: McpAccessibilityService) : ServiceValidation()

            data class Invalid(val error: McpToolException) : ServiceValidation()
        }

        @SuppressLint("NewApi") // API 30 guard in validateService()
        @Suppress("ReturnCount")
        override suspend fun captureScreenshot(
            quality: Int,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<ScreenshotData> {
            val validation = validateService()
            if (validation is ServiceValidation.Invalid) {
                return Result.failure(validation.error)
            }
            val service = (validation as ServiceValidation.Valid).service

            val bitmap =
                service.takeScreenshotBitmap()
                    ?: return Result.failure(
                        McpToolException.ActionFailed("Screenshot capture failed or timed out"),
                    )

            var resizedBitmap: Bitmap? = null
            return try {
                resizedBitmap = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
                val screenshotData = screenshotEncoder.bitmapToScreenshotData(resizedBitmap, quality)
                Result.success(screenshotData)
            } finally {
                // resizeBitmapProportional returns the SAME bitmap if no resize needed,
                // or a NEW bitmap if resized. Recycle the resized one first (if different),
                // then always recycle the original.
                if (resizedBitmap != null && resizedBitmap !== bitmap) {
                    resizedBitmap.recycle()
                }
                bitmap.recycle()
            }
        }

        @Suppress("ReturnCount")
        private fun validateService(): ServiceValidation {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capture requires Android 11 (API 30) or higher",
                    ),
                )
            }
            val service =
                McpAccessibilityService.instance
                    ?: return ServiceValidation.Invalid(
                        McpToolException.PermissionDenied(
                            "Accessibility service not enabled. Please enable it in Android Settings.",
                        ),
                    )
            if (!service.canTakeScreenshot()) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capability not available on this device",
                    ),
                )
            }
            return ServiceValidation.Valid(service)
        }

        override fun isScreenCaptureAvailable(): Boolean {
            val service = McpAccessibilityService.instance ?: return false
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service.canTakeScreenshot()
        }
    }
