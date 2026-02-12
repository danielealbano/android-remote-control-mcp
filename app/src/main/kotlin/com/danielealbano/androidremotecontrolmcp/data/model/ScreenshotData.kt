package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a captured screenshot ready for MCP response serialization.
 *
 * @property format The image encoding format (always "jpeg").
 * @property data The base64-encoded JPEG image data.
 * @property width The screenshot width in pixels.
 * @property height The screenshot height in pixels.
 */
@Serializable
data class ScreenshotData(
    val format: String = FORMAT_JPEG,
    val data: String,
    val width: Int,
    val height: Int,
) {
    companion object {
        const val FORMAT_JPEG = "jpeg"
    }
}
