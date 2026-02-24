@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Default implementation of [FileOperationProvider] using the Storage Access Framework.
 *
 * All file operations work through [android.content.ContentResolver] and
 * [DocumentFile] for SAF compatibility with both local and virtual storage providers.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
class FileOperationProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storageLocationProvider: StorageLocationProvider,
        private val settingsRepository: SettingsRepository,
    ) : FileOperationProvider {
        // ─────────────────────────────────────────────────────────────────────
        // listFiles
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun listFiles(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileListResult {
            val directory =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed(
                        "Directory not found: $path in location '$locationId'",
                    )

            if (!directory.isDirectory) {
                throw McpToolException.ActionFailed(
                    "Path is not a directory: $path in location '$locationId'",
                )
            }

            val children = directory.listFiles()
            val totalCount = children.size
            val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)

            val paginatedChildren =
                children
                    .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name })
                    .drop(offset)
                    .take(cappedLimit)

            val files =
                paginatedChildren.map { child ->
                    val childName = child.name ?: "unknown"
                    val relativePath = if (path.isEmpty()) childName else "$path/$childName"
                    FileInfo(
                        name = childName,
                        path = "$locationId/$relativePath",
                        isDirectory = child.isDirectory,
                        size = if (child.isFile) child.length() else 0L,
                        lastModified = child.lastModified().takeIf { it > 0L },
                        mimeType = child.type,
                    )
                }

            val hasMore = offset + cappedLimit < totalCount
            return FileListResult(files = files, totalCount = totalCount, hasMore = hasMore)
        }

        // ─────────────────────────────────────────────────────────────────────
        // readFile
        // ─────────────────────────────────────────────────────────────────────

        @Suppress("NestedBlockDepth")
        override suspend fun readFile(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileReadResult {
            require(offset >= 1) { "offset must be >= 1, got $offset" }
            val documentFile =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed(
                        "File not found: $path in location '$locationId'",
                    )

            if (!documentFile.isFile) {
                throw McpToolException.ActionFailed(
                    "Path is not a file: $path in location '$locationId'",
                )
            }

            checkFileSize(documentFile)

            val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_READ_LINES)
            val bufferedLines = mutableListOf<String>()
            var totalLines = 0
            val startLine = offset

            // Stream line-by-line to avoid loading the entire file into memory
            val uri = documentFile.uri
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var lineNumber = 1
                    var line: String? = reader.readLine()
                    while (line != null) {
                        totalLines = lineNumber
                        if (lineNumber >= offset && bufferedLines.size < cappedLimit) {
                            bufferedLines.add(line)
                        }
                        lineNumber++
                        line = reader.readLine()
                    }
                }
            } ?: throw McpToolException.ActionFailed(
                "Failed to open file for reading: $path in location '$locationId'",
            )

            val endLine = if (bufferedLines.isEmpty()) startLine else startLine + bufferedLines.size - 1
            val hasMore = endLine < totalLines

            return FileReadResult(
                content = bufferedLines.joinToString("\n"),
                totalLines = totalLines,
                hasMore = hasMore,
                startLine = startLine,
                endLine = endLine,
            )
        }

        // ─────────────────────────────────────────────────────────────────────
        // writeFile
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun writeFile(
            locationId: String,
            path: String,
            content: String,
        ) {
            val config = settingsRepository.getServerConfig()
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB

            if (contentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Content size exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val documentFile = ensureParentDirectoriesAndCreateFile(locationId, path)

            context.contentResolver.openOutputStream(documentFile.uri, "w")?.use { outputStream ->
                outputStream.write(contentBytes)
            } ?: throw McpToolException.ActionFailed(
                "Failed to open file for writing: $path in location '$locationId'",
            )

            Log.d(TAG, "Wrote ${contentBytes.size} bytes to $locationId/$path")
        }

        // ─────────────────────────────────────────────────────────────────────
        // appendFile
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun appendFile(
            locationId: String,
            path: String,
            content: String,
        ) {
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val config = settingsRepository.getServerConfig()
            val documentFile =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed(
                        "File not found: $path in location '$locationId'",
                    )

            if (!documentFile.isFile) {
                throw McpToolException.ActionFailed(
                    "Path is not a file: $path in location '$locationId'",
                )
            }

            val existingSize = documentFile.length()
            val newContentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB

            if (existingSize + newContentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Appending this content would exceed the configured file size limit of " +
                        "${config.fileSizeLimitMb} MB. Current file size: $existingSize bytes, " +
                        "content to append: ${newContentBytes.size} bytes.",
                )
            }

            try {
                context.contentResolver.openOutputStream(documentFile.uri, "wa")?.use { outputStream ->
                    outputStream.write(newContentBytes)
                } ?: throw McpToolException.ActionFailed(
                    "Failed to open file for appending: $path in location '$locationId'",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. " +
                        "Use write_file to write the entire file content instead.",
                )
            } catch (e: IllegalArgumentException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. " +
                        "Use write_file to write the entire file content instead.",
                )
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Failed to append to file: ${e.message ?: "Unknown error"}",
                )
            }

            Log.d(TAG, "Appended ${newContentBytes.size} bytes to $locationId/$path")
        }

        // ─────────────────────────────────────────────────────────────────────
        // replaceInFile
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun replaceInFile(
            locationId: String,
            path: String,
            oldString: String,
            newString: String,
            replaceAll: Boolean,
        ): FileReplaceResult {
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val documentFile =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed(
                        "File not found: $path in location '$locationId'",
                    )

            if (!documentFile.isFile) {
                throw McpToolException.ActionFailed(
                    "Path is not a file: $path in location '$locationId'",
                )
            }

            checkFileSize(documentFile)

            // Read the full content
            val originalContent =
                context.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw McpToolException.ActionFailed(
                    "Failed to open file for reading: $path in location '$locationId'",
                )

            // Count occurrences and perform replacement
            val occurrences = countOccurrences(originalContent, oldString)
            if (occurrences == 0) {
                return FileReplaceResult(replacementCount = 0)
            }

            val modifiedContent =
                if (replaceAll) {
                    originalContent.replace(oldString, newString)
                } else {
                    originalContent.replaceFirst(oldString, newString)
                }

            val replacementCount = if (replaceAll) occurrences else 1

            // Try to acquire advisory lock, then write back
            writeWithOptionalLock(documentFile.uri, modifiedContent)

            Log.d(TAG, "Replaced $replacementCount occurrence(s) in $locationId/$path")
            return FileReplaceResult(replacementCount = replacementCount)
        }

        // ─────────────────────────────────────────────────────────────────────
        // downloadFromUrl
        // ─────────────────────────────────────────────────────────────────────

        @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "NestedBlockDepth")
        override suspend fun downloadFromUrl(
            locationId: String,
            path: String,
            url: String,
        ): Long {
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val config = settingsRepository.getServerConfig()

            // Validate URL and open connection
            val (parsedUrl, rawConnection) = openUrlConnection(url)

            // Check HTTP permission
            if (parsedUrl.protocol == "http" && !config.allowHttpDownloads) {
                throw McpToolException.ActionFailed(
                    "HTTP downloads are not allowed. Enable 'Allow HTTP Downloads' in the " +
                        "app settings, or use an HTTPS URL.",
                )
            }

            if (parsedUrl.protocol != "http" && parsedUrl.protocol != "https") {
                throw McpToolException.ActionFailed(
                    "Unsupported URL protocol: ${parsedUrl.protocol}. Only HTTP and HTTPS are supported.",
                )
            }

            val timeoutMs = config.downloadTimeoutSeconds * MILLIS_PER_SECOND
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            var connection: HttpURLConnection? = null

            try {
                connection = rawConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = true

                // Configure permissive SSL if setting is enabled
                if (config.allowUnverifiedHttpsCerts && connection is HttpsURLConnection) {
                    configurePermissiveSsl(connection)
                }

                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE) {
                    throw McpToolException.ActionFailed(
                        "Download failed with HTTP status $responseCode for URL: $url",
                    )
                }

                // Pre-check Content-Length header
                val contentLength = connection.contentLengthLong
                if (contentLength > 0 && contentLength > limitBytes) {
                    throw McpToolException.ActionFailed(
                        "Server reports file size of $contentLength bytes, which exceeds the " +
                            "configured limit of ${config.fileSizeLimitMb} MB.",
                    )
                }

                // Ensure parent directories and create the destination file
                val documentFile = ensureParentDirectoriesAndCreateFile(locationId, path)

                // Stream to file, checking cumulative size.
                // Wrap in try-catch to clean up the orphan file on any error.
                var totalBytesWritten = 0L
                try {
                    context.contentResolver.openOutputStream(documentFile.uri, "w")?.use { outputStream ->
                        connection.inputStream.use { inputStream ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                totalBytesWritten += bytesRead
                                if (totalBytesWritten > limitBytes) {
                                    // Clean up partial file
                                    outputStream.close()
                                    documentFile.delete()
                                    throw McpToolException.ActionFailed(
                                        "Download exceeds the configured file size limit of " +
                                            "${config.fileSizeLimitMb} MB.",
                                    )
                                }
                                outputStream.write(buffer, 0, bytesRead)
                            }
                        }
                    } ?: throw McpToolException.ActionFailed(
                        "Failed to open file for writing: $path in location '$locationId'",
                    )
                } catch (e: McpToolException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    documentFile.delete()
                    throw e
                }

                Log.i(TAG, "Downloaded $totalBytesWritten bytes from $url to $locationId/$path")
                return totalBytesWritten
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException.ActionFailed(
                    "Download failed: ${e.message ?: "Unknown error"}",
                )
            } finally {
                connection?.disconnect()
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // deleteFile
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun deleteFile(
            locationId: String,
            path: String,
        ) {
            checkAuthorization(locationId)
            checkDeletePermission(locationId)
            val documentFile =
                resolveDocumentFile(locationId, path)
                    ?: throw McpToolException.ActionFailed(
                        "File not found: $path in location '$locationId'",
                    )

            if (documentFile.isDirectory) {
                throw McpToolException.ActionFailed(
                    "Cannot delete a directory. Only single files can be deleted. " +
                        "Path: $path in location '$locationId'",
                )
            }

            val deleted = documentFile.delete()
            if (!deleted) {
                throw McpToolException.ActionFailed(
                    "Failed to delete file: $path in location '$locationId'",
                )
            }

            Log.d(TAG, "Deleted file: $locationId/$path")
        }

        // ─────────────────────────────────────────────────────────────────────
        // createFileUri
        // ─────────────────────────────────────────────────────────────────────

        override suspend fun createFileUri(
            locationId: String,
            path: String,
            mimeType: String,
        ): Uri {
            checkAuthorization(locationId)
            checkWritePermission(locationId)
            val documentFile = ensureParentDirectoriesAndCreateFile(locationId, path, mimeType)
            return documentFile.uri
        }

        // ─────────────────────────────────────────────────────────────────────
        // Private helpers
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Checks that the given location is authorized.
         * Throws [McpToolException.PermissionDenied] if not.
         */
        private suspend fun checkAuthorization(locationId: String) {
            if (!storageLocationProvider.isLocationAuthorized(locationId)) {
                throw McpToolException.PermissionDenied(
                    "Storage location '$locationId' not found. " +
                        "Please add it in the app settings.",
                )
            }
        }

        /**
         * Checks that write operations are allowed for the given location.
         * Throws [McpToolException.PermissionDenied] if not.
         */
        private suspend fun checkWritePermission(locationId: String) {
            if (!storageLocationProvider.isWriteAllowed(locationId)) {
                Log.w(TAG, "Write permission denied for location: ${sanitizeLocationId(locationId)}")
                throw McpToolException.PermissionDenied("Write not allowed")
            }
        }

        /**
         * Checks that delete operations are allowed for the given location.
         * Throws [McpToolException.PermissionDenied] if not.
         */
        private suspend fun checkDeletePermission(locationId: String) {
            if (!storageLocationProvider.isDeleteAllowed(locationId)) {
                Log.w(TAG, "Delete permission denied for location: ${sanitizeLocationId(locationId)}")
                throw McpToolException.PermissionDenied("Delete not allowed")
            }
        }

        /**
         * Resolves a [DocumentFile] for the given virtual path within an authorized location.
         *
         * @return The resolved [DocumentFile], or null if not found.
         * @throws McpToolException.PermissionDenied if the location is not authorized.
         */
        @Suppress("ReturnCount")
        private suspend fun resolveDocumentFile(
            locationId: String,
            path: String,
        ): DocumentFile? {
            checkAuthorization(locationId)

            val treeUri =
                storageLocationProvider.getTreeUriForLocation(locationId)
                    ?: throw McpToolException.ActionFailed(
                        "Could not retrieve tree URI for location '$locationId'",
                    )

            val rootDocument =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw McpToolException.ActionFailed(
                        "Could not create DocumentFile from tree URI for location '$locationId'",
                    )

            if (path.isEmpty()) {
                return rootDocument
            }

            // Navigate path segments
            var current: DocumentFile? = rootDocument
            val segments = path.split("/").filter { it.isNotEmpty() }
            for (segment in segments) {
                current = current?.findFile(segment)
                if (current == null) {
                    return null
                }
            }

            return current
        }

        /**
         * Ensures parent directories exist and creates (or finds) the target file.
         *
         * @return The [DocumentFile] for the target file.
         */
        @Suppress("ThrowsCount")
        private suspend fun ensureParentDirectoriesAndCreateFile(
            locationId: String,
            path: String,
            explicitMimeType: String? = null,
        ): DocumentFile {
            val treeUri =
                storageLocationProvider.getTreeUriForLocation(locationId)
                    ?: throw McpToolException.ActionFailed(
                        "Could not retrieve tree URI for location '$locationId'",
                    )

            val rootDocument =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw McpToolException.ActionFailed(
                        "Could not create DocumentFile from tree URI for location '$locationId'",
                    )

            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                throw McpToolException.InvalidParams("File path cannot be empty")
            }

            val parentSegments = segments.dropLast(1)
            val fileName = segments.last()

            // Create parent directories as needed
            var current: DocumentFile = rootDocument
            for (dirName in parentSegments) {
                val existing = current.findFile(dirName)
                current =
                    if (existing != null && existing.isDirectory) {
                        existing
                    } else {
                        current.createDirectory(dirName)
                            ?: throw McpToolException.ActionFailed(
                                "Failed to create directory '$dirName' in path '$path'",
                            )
                    }
            }

            // Find or create the file
            val existingFile = current.findFile(fileName)
            if (existingFile != null && existingFile.isFile) {
                return existingFile
            }

            // Use explicit MIME type if provided, otherwise infer from extension
            val resolvedMimeType = explicitMimeType ?: guessMimeType(fileName)
            return current.createFile(resolvedMimeType, fileName)
                ?: throw McpToolException.ActionFailed(
                    "Failed to create file '$fileName' in path '$path'",
                )
        }

        /**
         * Checks file size against the configured limit.
         * Throws [McpToolException.ActionFailed] if the file exceeds the limit.
         */
        private suspend fun checkFileSize(documentFile: DocumentFile) {
            val config = settingsRepository.getServerConfig()
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            val fileSize = documentFile.length()

            if (fileSize > limitBytes) {
                throw McpToolException.ActionFailed(
                    "File size ($fileSize bytes) exceeds the configured limit of " +
                        "${config.fileSizeLimitMb} MB.",
                )
            }
        }

        /**
         * Counts non-overlapping occurrences of [needle] in [haystack].
         */
        private fun countOccurrences(
            haystack: String,
            needle: String,
        ): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var startIndex = 0
            while (true) {
                val index = haystack.indexOf(needle, startIndex)
                if (index < 0) break
                count++
                startIndex = index + needle.length
            }
            return count
        }

        /**
         * Writes content to a file URI, attempting advisory file lock for local providers.
         * Falls back to a plain write if locking is unsupported by the provider.
         */
        @Suppress("NestedBlockDepth")
        private fun writeWithOptionalLock(
            uri: Uri,
            content: String,
        ) {
            val contentBytes = content.toByteArray(Charsets.UTF_8)

            // Try to use FileDescriptor-based locking
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val fileChannel =
                        FileChannel.open(
                            java.nio.file.Paths
                                .get("/proc/self/fd/${pfd.fd}"),
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        )
                    fileChannel.use { channel ->
                        var lock: java.nio.channels.FileLock? = null
                        try {
                            lock = channel.tryLock()
                        } catch (_: Exception) {
                            // Locking not supported — proceed without lock
                        }
                        try {
                            channel.write(java.nio.ByteBuffer.wrap(contentBytes))
                        } finally {
                            lock?.release()
                        }
                    }
                    return
                }
            } catch (_: Exception) {
                // FileDescriptor approach not supported — fall back to OutputStream
            }

            // Fallback: plain OutputStream write
            context.contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                outputStream.write(contentBytes)
            } ?: throw McpToolException.ActionFailed(
                "Failed to open file for writing",
            )
        }

        /**
         * Parses a URL string and opens an [HttpURLConnection].
         * Extracted for testability (constructor mocking of [URL] is not supported on JDK 17).
         */
        internal fun openUrlConnection(urlString: String): Pair<URL, HttpURLConnection> {
            val parsedUrl =
                try {
                    URL(urlString)
                } catch (e: MalformedURLException) {
                    throw McpToolException.ActionFailed("Invalid URL: $urlString")
                }
            return parsedUrl to (parsedUrl.openConnection() as HttpURLConnection)
        }

        /**
         * Configures permissive SSL settings on an HTTPS connection
         * (accepts all certificates and hostnames).
         */
        private fun configurePermissiveSsl(connection: HttpsURLConnection) {
            val trustAllManager =
                @Suppress("CustomX509TrustManager")
                object : X509TrustManager {
                    @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                    override fun checkClientTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String,
                    ) { }

                    @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                    override fun checkServerTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String,
                    ) { }

                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        /**
         * Guesses a MIME type from a file name extension.
         * Falls back to "application/octet-stream" if the type cannot be determined.
         */
        private fun guessMimeType(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return EXTENSION_TO_MIME[extension] ?: "application/octet-stream"
        }

        companion object {
            private const val TAG = "MCP:FileOpsProvider"
            private const val MAX_LOCATION_ID_LOG_LENGTH = 200
            private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")

            private fun sanitizeLocationId(locationId: String): String =
                locationId.take(MAX_LOCATION_ID_LOG_LENGTH).replace(CONTROL_CHAR_REGEX, "")

            private const val BYTES_PER_MB = 1024L * 1024L
            private const val MILLIS_PER_SECOND = 1000
            private const val DOWNLOAD_BUFFER_SIZE = 8192
            private val HTTP_SUCCESS_RANGE = 200..299

            /**
             * Common file extension to MIME type mapping.
             */
            private val EXTENSION_TO_MIME =
                mapOf(
                    "txt" to "text/plain",
                    "html" to "text/html",
                    "htm" to "text/html",
                    "css" to "text/css",
                    "csv" to "text/csv",
                    "xml" to "text/xml",
                    "json" to "application/json",
                    "js" to "application/javascript",
                    "pdf" to "application/pdf",
                    "zip" to "application/zip",
                    "gz" to "application/gzip",
                    "tar" to "application/x-tar",
                    "jpg" to "image/jpeg",
                    "jpeg" to "image/jpeg",
                    "png" to "image/png",
                    "gif" to "image/gif",
                    "webp" to "image/webp",
                    "svg" to "image/svg+xml",
                    "mp3" to "audio/mpeg",
                    "wav" to "audio/wav",
                    "mp4" to "video/mp4",
                    "webm" to "video/webm",
                    "apk" to "application/vnd.android.package-archive",
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
    }
