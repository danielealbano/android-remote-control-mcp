package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents an available camera on the device.
 *
 * @property cameraId Unique identifier for the camera (from CameraX).
 * @property facing Camera facing direction: "front", "back", or "external".
 * @property hasFlash Whether the camera has a flash unit.
 * @property supportsPhoto Whether the camera supports photo capture.
 * @property supportsVideo Whether the camera supports video recording.
 */
data class CameraInfo(
    val cameraId: String,
    val facing: String,
    val hasFlash: Boolean,
    val supportsPhoto: Boolean,
    val supportsVideo: Boolean,
)
