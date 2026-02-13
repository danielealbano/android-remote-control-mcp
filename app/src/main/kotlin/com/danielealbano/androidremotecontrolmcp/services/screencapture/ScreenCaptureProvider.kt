package com.danielealbano.androidremotecontrolmcp.services.screencapture

import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData

/**
 * Abstracts access to the screen capture service.
 *
 * Tools use this interface instead of accessing [McpServerService.instance?.getScreenCaptureService()]
 * directly, enabling JVM-based testing with mock implementations.
 */
interface ScreenCaptureProvider {
    suspend fun captureScreenshot(quality: Int = ScreenCaptureService.DEFAULT_QUALITY): Result<ScreenshotData>

    fun isMediaProjectionActive(): Boolean
}
