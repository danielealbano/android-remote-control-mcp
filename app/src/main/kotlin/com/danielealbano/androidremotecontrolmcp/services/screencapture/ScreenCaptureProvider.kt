package com.danielealbano.androidremotecontrolmcp.services.screencapture

import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData

/**
 * Abstracts access to screenshot capture functionality.
 *
 * Production implementation uses AccessibilityService.takeScreenshot() (Android 11+).
 * Tools use this interface to enable JVM-based testing with mock implementations.
 */
interface ScreenCaptureProvider {
    companion object {
        const val DEFAULT_QUALITY = 80
    }

    suspend fun captureScreenshot(quality: Int = DEFAULT_QUALITY): Result<ScreenshotData>

    fun isScreenCaptureAvailable(): Boolean
}
