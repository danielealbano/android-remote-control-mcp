package com.danielealbano.androidremotecontrolmcp.services.screencapture

import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import javax.inject.Inject

/**
 * Production implementation of [ScreenCaptureProvider] that delegates
 * to [McpServerService.instance?.getScreenCaptureService()].
 *
 * This class is Hilt-injectable and stateless.
 */
class ScreenCaptureProviderImpl
    @Inject
    constructor() : ScreenCaptureProvider {
        override suspend fun captureScreenshot(quality: Int): Result<ScreenshotData> {
            val service =
                McpServerService.instance?.getScreenCaptureService()
                    ?: return Result.failure(
                        McpToolException.PermissionDenied("Screen capture service not available"),
                    )
            return service.captureScreenshot(quality)
        }

        override fun isMediaProjectionActive(): Boolean =
            McpServerService.instance?.getScreenCaptureService()?.isMediaProjectionActive() == true
    }
