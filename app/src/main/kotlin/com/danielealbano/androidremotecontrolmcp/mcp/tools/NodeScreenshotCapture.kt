package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Captures the screen once per read and crops it to the bounds of the nodes taken from that read. Privacy Mode masks
 * the read's flagged nodes before any crop, as `get_screen_state` does; the mask, the copy, the crops and the encodes
 * run on the Default dispatcher.
 */
internal class ShotCapturer(
    private val deps: NodeScreenshotDeps,
) {
    /** How many screenshots were taken. */
    var captures = 0
        private set

    /** The base64 JPEG of each of [bounds] in a screenshot of [read], null for a crop with nothing on screen. */
    suspend fun capture(
        read: FreshRead,
        bounds: List<BoundsData>,
    ): List<String?> {
        // Privacy failures propagate: the tool returns no device data then.
        val flagged = deps.privacyToolGate.tree(deps.webViewNodeMerger.merge(read.result)).flaggedBounds
        val screen = deps.accessibilityServiceProvider.getScreenInfo()
        val bitmap =
            deps.screenCaptureProvider.captureScreenshotBitmap(null, null).getOrElse { e ->
                Log.e(TAG, "Screenshot capture failed", e)
                throw McpToolException.ActionFailed("Screenshot capture failed")
            }
        captures++
        return withContext(Dispatchers.Default) { cropAll(bitmap, flagged, screen, bounds) }
    }

    private fun cropAll(
        bitmap: Bitmap,
        flagged: List<BoundsData>,
        screen: ScreenInfo,
        bounds: List<BoundsData>,
    ): List<String?> {
        var masked: Bitmap? = null
        var copy: Bitmap? = null
        try {
            return encoding {
                val maskedBitmap = deps.screenshotRedactor.mask(bitmap, flagged, screen.width, screen.height)
                masked = maskedBitmap
                // Nothing flagged leaves the HARDWARE capture: copy it once, so the crops do not each copy it.
                val source =
                    if (maskedBitmap.config == Bitmap.Config.HARDWARE) {
                        checkNotNull(maskedBitmap.copy(Bitmap.Config.ARGB_8888, false)) {
                            "Failed to copy the screenshot bitmap"
                        }.also { copy = it }
                    } else {
                        maskedBitmap
                    }
                bounds.map { deps.nodeImageCropper.crop(source, it, screen.width, screen.height)?.data }
            }
        } finally {
            copy?.takeIf { it !== masked }?.recycle()
            masked?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    /** Runs [block]; any failure but a cancellation becomes "Screenshot encoding failed", logged. */
    private inline fun <T> encoding(block: () -> T): T =
        runCatching(block).getOrElse { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Screenshot encoding failed", e)
            throw McpToolException.ActionFailed("Screenshot encoding failed")
        }

    companion object {
        private const val TAG = "MCP:NodeScreenshots"
    }
}
