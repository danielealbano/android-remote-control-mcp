package com.danielealbano.androidremotecontrolmcp.services.storage

/**
 * Shared MIME type mapping for file operations.
 * Used by both SAF ([FileOperationProviderImpl]) and MediaStore ([MediaStoreFileOperationsImpl]).
 */
object MimeTypeUtils {
    /**
     * Guesses a MIME type from a file name extension.
     * Falls back to "application/octet-stream" if the type cannot be determined.
     */
    fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_MIME[extension] ?: "application/octet-stream"
    }

    private val EXTENSION_TO_MIME =
        mapOf(
            // Text
            "txt" to "text/plain",
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "csv" to "text/csv",
            "xml" to "text/xml",
            // Application
            "json" to "application/json",
            "js" to "application/javascript",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            // Image
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            // Audio
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            // Video
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            // Android
            "apk" to "application/vnd.android.package-archive",
            // Code / config
            "md" to "text/markdown",
            "kt" to "text/x-kotlin",
            "java" to "text/x-java",
            "py" to "text/x-python",
            "sh" to "application/x-sh",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
            "toml" to "text/toml",
            "ini" to "text/plain",
            "cfg" to "text/plain",
            "conf" to "text/plain",
            "log" to "text/plain",
            "properties" to "text/plain",
        )
}
