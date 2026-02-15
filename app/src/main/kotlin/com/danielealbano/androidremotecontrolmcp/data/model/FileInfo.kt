package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a file or directory entry returned by file listing operations.
 *
 * @property name The file/directory name.
 * @property path The full virtual path: "{location_id}/{relative_path}".
 * @property isDirectory True if this entry is a directory, false if a file.
 * @property size File size in bytes (0 for directories).
 * @property lastModified Last modification timestamp in milliseconds since epoch, or null if unknown.
 * @property mimeType The MIME type of the file, or null for directories/unknown.
 */
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long?,
    val mimeType: String?,
)
