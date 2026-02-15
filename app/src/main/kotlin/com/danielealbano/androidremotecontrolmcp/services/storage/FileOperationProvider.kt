package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo

/**
 * Result from a file listing operation.
 *
 * @property files The list of file entries in the requested range.
 * @property totalCount The total number of entries in the directory.
 * @property hasMore Whether there are more entries beyond the requested range.
 */
data class FileListResult(
    val files: List<FileInfo>,
    val totalCount: Int,
    val hasMore: Boolean,
)

/**
 * Result from a file read operation.
 *
 * @property content The text content of the requested lines.
 * @property totalLines The total number of lines in the file.
 * @property hasMore Whether there are more lines beyond the requested range.
 * @property startLine The 1-based line number of the first returned line.
 * @property endLine The 1-based line number of the last returned line.
 */
data class FileReadResult(
    val content: String,
    val totalLines: Int,
    val hasMore: Boolean,
    val startLine: Int,
    val endLine: Int,
)

/**
 * Result from a file string replacement operation.
 *
 * @property replacementCount The number of replacements made.
 */
data class FileReplaceResult(
    val replacementCount: Int,
)

/**
 * Provides file operations via the Storage Access Framework.
 *
 * All operations work with virtual paths: "{location_id}/{relative_path}".
 * The location_id must reference an authorized storage location.
 *
 * All operations check the configured file size limit before proceeding.
 */
interface FileOperationProvider {
    /**
     * Lists files in a directory.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path within the location (empty string for root).
     * @param offset Number of entries to skip (0-based).
     * @param limit Maximum number of entries to return (capped at [MAX_LIST_ENTRIES]).
     * @return [FileListResult] with file entries and pagination info.
     */
    suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult

    /**
     * Reads a text file with line-based pagination.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param offset 1-based line number to start reading from.
     * @param limit Maximum number of lines to return (capped at [MAX_READ_LINES]).
     * @return [FileReadResult] with content and pagination info.
     */
    suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult

    /**
     * Writes text content to a file. Creates the file and parent directories
     * if they don't exist. Overwrites existing content.
     * Empty content creates an empty file.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to write.
     */
    suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Appends text content to a file. Tries native append mode ("wa") first.
     * If the provider does not support append mode, throws an ActionFailed
     * exception with a hint to use write_file instead.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to append.
     */
    suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Performs literal string replacement in a text file.
     * Uses read-modify-write pattern. Tries advisory file lock on local providers.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param oldString The literal string to find.
     * @param newString The replacement string.
     * @param replaceAll If true, replace all occurrences; otherwise replace only the first.
     * @return [FileReplaceResult] with the number of replacements made.
     */
    suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult

    /**
     * Downloads a file from a URL and saves it to an authorized storage location.
     * Creates the file and parent directories if they don't exist. Overwrites
     * existing content. Subject to the configured file size limit.
     *
     * Respects user-configured settings:
     * - If the URL is HTTP (not HTTPS) and `allowHttpDownloads` is false, throws ActionFailed.
     * - If `allowUnverifiedHttpsCerts` is true, skips HTTPS certificate verification.
     *
     * Uses the user-configured download timeout. Follows HTTP redirects.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path for the destination file.
     * @param url The URL to download from.
     * @return The size of the downloaded file in bytes.
     */
    suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long

    /**
     * Deletes a single file. Does not delete directories.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     */
    suspend fun deleteFile(
        locationId: String,
        path: String,
    )

    companion object {
        /** Maximum entries returned by list_files. */
        const val MAX_LIST_ENTRIES = 200

        /** Maximum lines returned by read_file. */
        const val MAX_READ_LINES = 200

        // File size limit constants are defined in ServerConfig
        // (DEFAULT_FILE_SIZE_LIMIT_MB, MIN_FILE_SIZE_LIMIT_MB, MAX_FILE_SIZE_LIMIT_MB).
        // Use ServerConfig constants — do NOT duplicate them here.
    }
}
