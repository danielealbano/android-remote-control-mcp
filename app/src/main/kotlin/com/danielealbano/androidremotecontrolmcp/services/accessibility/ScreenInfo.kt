package com.danielealbano.androidremotecontrolmcp.services.accessibility

import kotlinx.serialization.Serializable

/**
 * Represents the device screen dimensions, density, and orientation.
 *
 * Produced by [McpAccessibilityService.getScreenInfo] and returned
 * by the `get_screen_info` MCP tool.
 *
 * @property width Screen width in pixels.
 * @property height Screen height in pixels.
 * @property densityDpi Screen density in dots per inch.
 * @property orientation Screen orientation: "portrait" or "landscape".
 */
@Serializable
data class ScreenInfo(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val orientation: String,
) {
    companion object {
        const val ORIENTATION_PORTRAIT = "portrait"
        const val ORIENTATION_LANDSCAPE = "landscape"
    }
}
