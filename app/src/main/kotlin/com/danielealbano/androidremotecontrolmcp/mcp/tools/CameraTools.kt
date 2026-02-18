@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.camera.CameraProvider
import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ─────────────────────────────────────────────────────────────────────────────
// list_cameras
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_cameras`.
 *
 * Lists all available cameras on the device with their capabilities.
 */
class ListCamerasHandler(
    private val cameraProvider: CameraProvider,
) {
    @Suppress("UnusedParameter")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        Log.d(TAG, "Executing list_cameras")
        val cameras = cameraProvider.listCameras()

        val jsonResult =
            buildJsonArray {
                cameras.forEach { camera ->
                    add(
                        buildJsonObject {
                            put("camera_id", camera.cameraId)
                            put("facing", camera.facing)
                            put("has_flash", camera.hasFlash)
                            put("supports_photo", camera.supportsPhoto)
                            put("supports_video", camera.supportsVideo)
                        },
                    )
                }
            }

        return McpToolUtils.textResult(jsonResult.toString())
    }

    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists all available cameras on the device with their capabilities " +
                    "(facing direction, flash support, photo/video support).",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_cameras"
        private const val TAG = "MCP:ListCamerasHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_camera_photo_resolutions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_camera_photo_resolutions`.
 *
 * Lists supported photo resolutions for a specific camera.
 */
class ListCameraPhotoResolutionsHandler(
    private val cameraProvider: CameraProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val cameraId = McpToolUtils.requireString(arguments, "camera_id")
        Log.d(TAG, "Executing list_camera_photo_resolutions for camera: $cameraId")

        val resolutions = cameraProvider.listPhotoResolutions(cameraId)

        val jsonResult =
            buildJsonArray {
                resolutions.forEach { res ->
                    add(
                        buildJsonObject {
                            put("width", res.width)
                            put("height", res.height)
                            put("megapixels", res.megapixels)
                            put("aspect_ratio", res.aspectRatio)
                        },
                    )
                }
            }

        return McpToolUtils.textResult(jsonResult.toString())
    }

    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists supported photo resolutions for a specific camera. " +
                    "Use the camera_id from list_cameras.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("camera_id") {
                                put("type", "string")
                                put("description", "Camera identifier from list_cameras")
                            }
                        },
                    required = listOf("camera_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_camera_photo_resolutions"
        private const val TAG = "MCP:ListPhotoResHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// list_camera_video_resolutions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `list_camera_video_resolutions`.
 *
 * Lists supported video resolutions for a specific camera.
 */
class ListCameraVideoResolutionsHandler(
    private val cameraProvider: CameraProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val cameraId = McpToolUtils.requireString(arguments, "camera_id")
        Log.d(TAG, "Executing list_camera_video_resolutions for camera: $cameraId")

        val resolutions = cameraProvider.listVideoResolutions(cameraId)

        val jsonResult =
            buildJsonArray {
                resolutions.forEach { res ->
                    add(
                        buildJsonObject {
                            put("width", res.width)
                            put("height", res.height)
                            put("aspect_ratio", res.aspectRatio)
                            put("quality_label", res.qualityLabel)
                        },
                    )
                }
            }

        return McpToolUtils.textResult(jsonResult.toString())
    }

    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Lists supported video resolutions for a specific camera. " +
                    "Use the camera_id from list_cameras.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("camera_id") {
                                put("type", "string")
                                put("description", "Camera identifier from list_cameras")
                            }
                        },
                    required = listOf("camera_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "list_camera_video_resolutions"
        private const val TAG = "MCP:ListVideoResHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// take_camera_photo
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `take_camera_photo`.
 *
 * Captures a photo from the specified camera and returns it as base64-encoded JPEG.
 */
class TakeCameraPhotoHandler(
    private val cameraProvider: CameraProvider,
) {
    @Suppress("CyclomaticComplexMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val cameraId = McpToolUtils.requireString(arguments, "camera_id")
        val resolution = parseOptionalResolution(arguments, "resolution")
        val quality =
            McpToolUtils.optionalInt(
                arguments,
                "quality",
                CameraProvider.DEFAULT_QUALITY,
            )
        val flashMode =
            McpToolUtils.optionalString(
                arguments,
                "flash_mode",
                CameraProvider.DEFAULT_FLASH_MODE,
            )

        validateQuality(quality)
        validateFlashMode(flashMode)

        Log.d(
            TAG,
            "Executing take_camera_photo: camera=$cameraId, " +
                "resolution=${resolution?.first}x${resolution?.second}, " +
                "quality=$quality, flash=$flashMode",
        )

        val result =
            cameraProvider.takePhoto(
                cameraId = cameraId,
                width = resolution?.first,
                height = resolution?.second,
                quality = quality,
                flashMode = flashMode,
            )

        return McpToolUtils.imageResult(result.data, "image/jpeg")
    }

    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Captures a photo from the specified camera and returns it as a " +
                    "base64-encoded JPEG image. Default resolution is 720p (closest " +
                    "available match). Maximum resolution is capped at 1920x1080 to " +
                    "prevent excessively large responses. Use save_camera_photo for " +
                    "higher resolutions.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("camera_id") {
                                put("type", "string")
                                put("description", "Camera identifier from list_cameras")
                            }
                            putJsonObject("resolution") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Resolution as WIDTHxHEIGHT (e.g., '1280x720'). " +
                                        "Default: 720p closest match. Max: 1920x1080.",
                                )
                            }
                            putJsonObject("quality") {
                                put("type", "integer")
                                put("description", "JPEG quality (1-100). Default: 80.")
                            }
                            putJsonObject("flash_mode") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Flash mode: 'off', 'on', or 'auto'. Default: 'auto'.",
                                )
                            }
                        },
                    required = listOf("camera_id"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "take_camera_photo"
        private const val TAG = "MCP:TakePhotoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// save_camera_photo
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `save_camera_photo`.
 *
 * Captures a photo from the specified camera and saves it to a storage location.
 */
class SaveCameraPhotoHandler(
    private val cameraProvider: CameraProvider,
    private val fileOperationProvider: FileOperationProvider,
) {
    @Suppress("CyclomaticComplexMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val cameraId = McpToolUtils.requireString(arguments, "camera_id")
        val locationId = McpToolUtils.requireString(arguments, "location_id")
        val path = McpToolUtils.requireString(arguments, "path")
        val resolution = parseOptionalResolution(arguments, "resolution")
        val quality =
            McpToolUtils.optionalInt(
                arguments,
                "quality",
                CameraProvider.DEFAULT_QUALITY,
            )
        val flashMode =
            McpToolUtils.optionalString(
                arguments,
                "flash_mode",
                CameraProvider.DEFAULT_FLASH_MODE,
            )

        validateQuality(quality)
        validateFlashMode(flashMode)

        Log.d(TAG, "Executing save_camera_photo: camera=$cameraId, location=$locationId, path=$path")

        val outputUri = fileOperationProvider.createFileUri(locationId, path, "image/jpeg")
        val fileSizeBytes =
            cameraProvider.savePhoto(
                cameraId = cameraId,
                outputUri = outputUri,
                width = resolution?.first,
                height = resolution?.second,
                quality = quality,
                flashMode = flashMode,
            )

        return McpToolUtils.textResult("Photo saved successfully: $path ($fileSizeBytes bytes)")
    }

    @Suppress("LongMethod")
    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Captures a photo from the specified camera and saves it to a storage " +
                    "location. Requires write permission on the storage location. " +
                    "Default resolution is 720p (closest available match).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("camera_id") {
                                put("type", "string")
                                put("description", "Camera identifier from list_cameras")
                            }
                            putJsonObject("location_id") {
                                put("type", "string")
                                put("description", "Storage location identifier")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Relative file path (e.g., 'photos/photo.jpg')")
                            }
                            putJsonObject("resolution") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Resolution as WIDTHxHEIGHT (e.g., '1280x720'). " +
                                        "Default: 720p closest match.",
                                )
                            }
                            putJsonObject("quality") {
                                put("type", "integer")
                                put("description", "JPEG quality (1-100). Default: 80.")
                            }
                            putJsonObject("flash_mode") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Flash mode: 'off', 'on', or 'auto'. Default: 'auto'.",
                                )
                            }
                        },
                    required = listOf("camera_id", "location_id", "path"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "save_camera_photo"
        private const val TAG = "MCP:SavePhotoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// save_camera_video
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `save_camera_video`.
 *
 * Records video from the specified camera and saves it to a storage location.
 * Returns a thumbnail of the first frame.
 */
class SaveCameraVideoHandler(
    private val cameraProvider: CameraProvider,
    private val fileOperationProvider: FileOperationProvider,
) {
    @Suppress("CyclomaticComplexMethod")
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val cameraId = McpToolUtils.requireString(arguments, "camera_id")
        val locationId = McpToolUtils.requireString(arguments, "location_id")
        val path = McpToolUtils.requireString(arguments, "path")
        val duration = McpToolUtils.requireInt(arguments, "duration")
        val resolution = parseOptionalResolution(arguments, "resolution")
        val audio = McpToolUtils.optionalBoolean(arguments, "audio", true)
        val flashMode =
            McpToolUtils.optionalString(
                arguments,
                "flash_mode",
                CameraProvider.DEFAULT_FLASH_MODE,
            )

        validateDuration(duration)
        validateFlashMode(flashMode)

        Log.d(
            TAG,
            "Executing save_camera_video: camera=$cameraId, location=$locationId, " +
                "path=$path, duration=${duration}s",
        )

        val outputUri = fileOperationProvider.createFileUri(locationId, path, "video/mp4")
        val result =
            cameraProvider.saveVideo(
                cameraId = cameraId,
                outputUri = outputUri,
                durationSeconds = duration,
                width = resolution?.first,
                height = resolution?.second,
                audio = audio,
                flashMode = flashMode,
            )

        val text =
            "Video saved successfully: $path " +
                "(${result.fileSizeBytes} bytes, ${result.durationMs}ms)"

        return McpToolUtils.textAndImageResult(text, result.thumbnailData, "image/jpeg")
    }

    @Suppress("LongMethod")
    fun register(
        server: Server,
        toolNamePrefix: String,
    ) {
        server.addTool(
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Records a video from the specified camera and saves it to a storage " +
                    "location. Maximum duration is 30 seconds. Includes audio by default. " +
                    "Requires write permission on the storage location. Returns a " +
                    "thumbnail of the first frame. Default resolution is 720p " +
                    "(closest available match).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("camera_id") {
                                put("type", "string")
                                put("description", "Camera identifier from list_cameras")
                            }
                            putJsonObject("location_id") {
                                put("type", "string")
                                put("description", "Storage location identifier")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Relative file path (e.g., 'videos/video.mp4')")
                            }
                            putJsonObject("duration") {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Recording duration in seconds (1-30).",
                                )
                            }
                            putJsonObject("resolution") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Resolution as WIDTHxHEIGHT (e.g., '1280x720'). " +
                                        "Default: 720p closest match.",
                                )
                            }
                            putJsonObject("audio") {
                                put("type", "boolean")
                                put(
                                    "description",
                                    "Whether to record audio. Default: true. " +
                                        "Requires RECORD_AUDIO permission.",
                                )
                            }
                            putJsonObject("flash_mode") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Flash mode: 'off', 'on', or 'auto'. Default: 'auto'.",
                                )
                            }
                        },
                    required = listOf("camera_id", "location_id", "path", "duration"),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "save_camera_video"
        private const val TAG = "MCP:SaveVideoHandler"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Parses an optional resolution string in "WIDTHxHEIGHT" format.
 *
 * @return Pair of (width, height) or null if the parameter is not present.
 * @throws McpToolException.InvalidParams if the format is invalid.
 */
@Suppress("ThrowsCount")
private fun parseOptionalResolution(
    arguments: JsonObject?,
    paramName: String,
): Pair<Int, Int>? {
    val rawValue = arguments?.get(paramName) ?: return null
    val resolutionStr =
        (rawValue as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: throw McpToolException.InvalidParams(
                "Parameter '$paramName' must be a string in 'WIDTHxHEIGHT' format " +
                    "(e.g., '1280x720')",
            )

    val parts = resolutionStr.split("x")
    if (parts.size != 2) {
        throw McpToolException.InvalidParams(
            "Parameter '$paramName' must be in 'WIDTHxHEIGHT' format (e.g., '1280x720'), " +
                "got: '$resolutionStr'",
        )
    }

    val width =
        parts[0].toIntOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$paramName' width must be a positive integer, got: '${parts[0]}'",
            )
    val height =
        parts[1].toIntOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$paramName' height must be a positive integer, got: '${parts[1]}'",
            )

    if (width <= 0) {
        throw McpToolException.InvalidParams(
            "Parameter '$paramName' width must be positive, got: $width",
        )
    }
    if (height <= 0) {
        throw McpToolException.InvalidParams(
            "Parameter '$paramName' height must be positive, got: $height",
        )
    }

    return Pair(width, height)
}

private fun validateQuality(quality: Int) {
    if (quality < 1 || quality > MAX_QUALITY) {
        throw McpToolException.InvalidParams(
            "Parameter 'quality' must be between 1 and $MAX_QUALITY, got: $quality",
        )
    }
}

private fun validateFlashMode(flashMode: String) {
    if (flashMode !in CameraProvider.VALID_FLASH_MODES) {
        throw McpToolException.InvalidParams(
            "Parameter 'flash_mode' must be one of: ${CameraProvider.VALID_FLASH_MODES.joinToString()}, " +
                "got: '$flashMode'",
        )
    }
}

private fun validateDuration(duration: Int) {
    if (duration < 1 || duration > CameraProvider.MAX_VIDEO_DURATION_SECONDS) {
        throw McpToolException.InvalidParams(
            "Parameter 'duration' must be between 1 and " +
                "${CameraProvider.MAX_VIDEO_DURATION_SECONDS} seconds, got: $duration",
        )
    }
}

private const val MAX_QUALITY = 100

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all camera tools with the given [Server].
 */
fun registerCameraTools(
    server: Server,
    cameraProvider: CameraProvider,
    fileOperationProvider: FileOperationProvider,
    toolNamePrefix: String,
) {
    ListCamerasHandler(cameraProvider).register(server, toolNamePrefix)
    ListCameraPhotoResolutionsHandler(cameraProvider).register(server, toolNamePrefix)
    ListCameraVideoResolutionsHandler(cameraProvider).register(server, toolNamePrefix)
    TakeCameraPhotoHandler(cameraProvider).register(server, toolNamePrefix)
    SaveCameraPhotoHandler(cameraProvider, fileOperationProvider).register(server, toolNamePrefix)
    SaveCameraVideoHandler(cameraProvider, fileOperationProvider).register(server, toolNamePrefix)
}
