package com.danielealbano.androidremotecontrolmcp.data.model

import android.net.Uri
import android.provider.MediaStore
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException

/**
 * Defines the built-in storage locations backed by MediaStore.
 *
 * These are always available without user setup (no SAF picker needed).
 * The app can write to these locations without any runtime permissions.
 * Reading non-owned files requires the corresponding [readMediaPermission].
 *
 * @property locationId Stable identifier used in MCP tool calls.
 * @property displayNameOwned Display name when only owned files are visible.
 * @property displayNameAll Display name when all files are visible (READ_MEDIA_* granted).
 * @property collectionUri MediaStore collection content URI for queries/inserts.
 * @property baseRelativePath The MediaStore RELATIVE_PATH prefix (e.g., "Download/").
 * @property readMediaPermission Runtime permission for "all files" access, or null if unavailable.
 */
enum class BuiltinStorageLocation(
    val locationId: String,
    val displayNameOwned: String,
    val displayNameAll: String,
    val collectionUri: Uri,
    val baseRelativePath: String,
    val readMediaPermission: String?,
) {
    DOWNLOADS(
        locationId = "builtin:downloads",
        displayNameOwned = "Downloads - Only owned files",
        displayNameAll = "Downloads - Only owned files",
        collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Download/",
        readMediaPermission = null,
    ),
    PICTURES(
        locationId = "builtin:pictures",
        displayNameOwned = "Pictures - Only owned files",
        displayNameAll = "Pictures - All files",
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Pictures/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_IMAGES,
    ),
    MOVIES(
        locationId = "builtin:movies",
        displayNameOwned = "Movies - Only owned files",
        displayNameAll = "Movies - All files",
        collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Movies/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_VIDEO,
    ),
    MUSIC(
        locationId = "builtin:music",
        displayNameOwned = "Music - Only owned files",
        displayNameAll = "Music - All files",
        collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Music/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_AUDIO,
    ),
    ;

    companion object {
        /** Prefix for all built-in location IDs. */
        const val ID_PREFIX = "builtin:"

        /** Returns the [BuiltinStorageLocation] for a given location ID, or null. */
        fun fromLocationId(locationId: String): BuiltinStorageLocation? =
            entries.find { it.locationId == locationId }

        /** Returns true if the given location ID is a built-in location. */
        fun isBuiltinId(locationId: String): Boolean =
            locationId.startsWith(ID_PREFIX)

        /**
         * Validates a relative path for use with built-in locations.
         * Rejects path traversal attempts (`..`), absolute paths, and control characters.
         *
         * @throws McpToolException.InvalidParams if the path is invalid.
         */
        fun validatePath(path: String) {
            if (path.startsWith("/")) {
                throw McpToolException.InvalidParams(
                    "Path must be relative, not absolute",
                )
            }
            val segments = path.split("/").filter { it.isNotEmpty() }
            for (segment in segments) {
                if (segment == "..") {
                    throw McpToolException.InvalidParams(
                        "Path must not contain '..' segments",
                    )
                }
                if (segment == ".") {
                    throw McpToolException.InvalidParams(
                        "Path must not contain '.' segments",
                    )
                }
                if (CONTROL_CHAR_REGEX.containsMatchIn(segment)) {
                    throw McpToolException.InvalidParams(
                        "Path must not contain control characters",
                    )
                }
            }
        }

        private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")
    }
}
