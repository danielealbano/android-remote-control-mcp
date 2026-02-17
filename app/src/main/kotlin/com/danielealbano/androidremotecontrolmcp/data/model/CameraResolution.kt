package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a supported photo resolution for a camera.
 *
 * @property width Width in pixels.
 * @property height Height in pixels.
 * @property megapixels Approximate megapixels (width * height / 1_000_000, rounded to 1 decimal).
 * @property aspectRatio Human-readable aspect ratio (e.g., "16:9", "4:3").
 */
data class PhotoResolution(
    val width: Int,
    val height: Int,
    val megapixels: Double,
    val aspectRatio: String,
)

/**
 * Represents a supported video resolution for a camera.
 *
 * @property width Width in pixels.
 * @property height Height in pixels.
 * @property aspectRatio Human-readable aspect ratio (e.g., "16:9", "4:3").
 * @property qualityLabel Human-readable quality label (e.g., "HD", "FHD", "UHD").
 */
data class VideoResolution(
    val width: Int,
    val height: Int,
    val aspectRatio: String,
    val qualityLabel: String,
)
