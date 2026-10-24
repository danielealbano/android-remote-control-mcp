@file:Suppress("TooManyFunctions")

package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.data.model.MediaCollection
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * MediaStore implementation of [MediaStoreFileOperations]. Every operation runs its blocking I/O (content resolver,
 * network) on [ioDispatcher], never on the caller's dispatcher (the MCP request handling threads).
 */
@Suppress("SwallowedException")
class MediaStoreFileOperationsImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storageLocationProvider: StorageLocationProvider,
        private val settingsRepository: SettingsRepository,
        private val permissionChecker: PermissionChecker,
        downloadHttpClient: DownloadHttpClient,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : MediaStoreFileOperations {
        private val downloader = MediaStoreDownloader(context, downloadHttpClient)

        // ─── listFiles ──────────────────────────────────────────────────────

        override suspend fun listFiles(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileListResult =
            withContext(ioDispatcher) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                val targetRelativePath = buildRelativePathForListing(builtin, path)
                val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)

                // Query files in the target directory and children (for directory synthesis)
                val projection =
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.MIME_TYPE,
                    )

                val entries = mutableListOf<FileInfo>()
                val seenDirs = mutableSetOf<String>()

                for (collection in builtin.collections) {
                    val includeNonOwned = hasNonOwnedReadAccess(collection)
                    val selection = buildListSelection(includeNonOwned)
                    val selectionArgs = buildListSelectionArgs(targetRelativePath, includeNonOwned)
                    context.contentResolver
                        .query(
                            collection.uri,
                            projection,
                            selection,
                            selectionArgs,
                            null,
                        )?.use { cursor ->
                            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            val relPathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                            while (cursor.moveToNext()) {
                                processCursorRow(
                                    cursor,
                                    nameIdx,
                                    relPathIdx,
                                    sizeIdx,
                                    dateIdx,
                                    mimeIdx,
                                    targetRelativePath,
                                    locationId,
                                    path,
                                    entries,
                                    seenDirs,
                                )
                            }
                        }
                }

                // Sort: directories first, then by name
                val sorted =
                    entries.sortedWith(
                        compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name },
                    )
                val totalCount = sorted.size
                val paginated = sorted.drop(offset).take(cappedLimit)
                val hasMore = offset + cappedLimit < totalCount

                FileListResult(files = paginated, totalCount = totalCount, hasMore = hasMore)
            }

        private fun processCursorRow(
            cursor: android.database.Cursor,
            nameIdx: Int,
            relPathIdx: Int,
            sizeIdx: Int,
            dateIdx: Int,
            mimeIdx: Int,
            targetRelativePath: String,
            locationId: String,
            path: String,
            entries: MutableList<FileInfo>,
            seenDirs: MutableSet<String>,
        ) {
            val relPath = cursor.getString(relPathIdx) ?: return
            val displayName = cursor.getString(nameIdx) ?: return

            if (relPath == targetRelativePath) {
                // Direct child file
                val childRelPath = if (path.isEmpty()) displayName else "$path/$displayName"
                entries.add(
                    FileInfo(
                        name = displayName,
                        path = "$locationId/$childRelPath",
                        isDirectory = false,
                        size = cursor.getLong(sizeIdx),
                        lastModified =
                            cursor
                                .getLong(dateIdx)
                                .takeIf { it > 0L }
                                ?.let { it * MILLIS_PER_SECOND },
                        mimeType = cursor.getString(mimeIdx),
                    ),
                )
            } else if (relPath.length > targetRelativePath.length &&
                relPath.startsWith(targetRelativePath)
            ) {
                // Deeper child -> synthesize directory
                val remainder = relPath.removePrefix(targetRelativePath)
                val dirName = remainder.split("/").firstOrNull { it.isNotEmpty() }
                if (dirName != null && seenDirs.add(dirName)) {
                    val dirRelPath = if (path.isEmpty()) dirName else "$path/$dirName"
                    entries.add(
                        FileInfo(
                            name = dirName,
                            path = "$locationId/$dirRelPath",
                            isDirectory = true,
                            size = 0L,
                            lastModified = null,
                            mimeType = null,
                        ),
                    )
                }
            }
        }

        private fun buildListSelection(includeNonOwned: Boolean): String {
            val pathFilter = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            return if (includeNonOwned) {
                pathFilter
            } else {
                "$pathFilter AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            }
        }

        private fun buildListSelectionArgs(
            targetRelativePath: String,
            includeNonOwned: Boolean,
        ): Array<String> {
            // LIKE pattern: match exact dir and all children; escape the literal prefix only
            val pattern = "${escapeLikePattern(targetRelativePath)}%"
            return if (includeNonOwned) arrayOf(pattern) else arrayOf(pattern, context.packageName)
        }

        // ─── readFile ───────────────────────────────────────────────────────

        override suspend fun readFile(
            locationId: String,
            path: String,
            offset: Int,
            limit: Int,
        ): FileReadResult =
            withContext(ioDispatcher) {
                if (offset < 1) {
                    throw McpToolException.InvalidParams("offset must be >= 1, got $offset")
                }
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)

                val uri = findFileOrThrow(builtin, path)
                checkFileSizeByUri(uri)

                val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_READ_LINES)
                val bufferedLines = mutableListOf<String>()
                var totalLines = 0

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
                } ?: throw McpToolException.ActionFailed("Failed to open file for reading: $path")

                val endLine = if (bufferedLines.isEmpty()) offset else offset + bufferedLines.size - 1

                FileReadResult(
                    content = bufferedLines.joinToString("\n"),
                    totalLines = totalLines,
                    hasMore = endLine < totalLines,
                    startLine = offset,
                    endLine = endLine,
                )
            }

        // ─── readFileBytes ──────────────────────────────────────────────────

        override suspend fun readFileBytes(
            locationId: String,
            path: String,
            maxBytes: Long,
        ): FileBytesResult =
            withContext(ioDispatcher) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                val uri = findFileOrThrow(builtin, path)
                readFileBytesFromUri(
                    context.contentResolver,
                    uri,
                    extractDisplayName(path),
                    queryFileSize(uri),
                    maxBytes,
                )
            }

        // ─── writeFile ──────────────────────────────────────────────────────

        override suspend fun writeFile(
            locationId: String,
            path: String,
            content: String,
        ) = withContext(ioDispatcher) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkWritePermission(locationId)

            val config = settingsRepository.getServerConfig()
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            if (contentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Content size exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            val mimeType = MimeTypeUtils.guessMimeType(displayName)
            val collection = selectCollectionForMimeType(builtin, mimeType)
            val existingUri = findFileInCollection(collection, relativePath, displayName, ownedOnly = true)

            if (existingUri != null) {
                context.contentResolver.openOutputStream(existingUri, "wt")?.use { it.write(contentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to open file for writing: $path")
            } else {
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    }
                val insertUri =
                    context.contentResolver.insert(collection.uri, values)
                        ?: throw McpToolException.ActionFailed("Failed to create file: $path")
                context.contentResolver.openOutputStream(insertUri, "wt")?.use { it.write(contentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to write to new file: $path")
            }

            Log.d(TAG, "Wrote ${contentBytes.size} bytes to $locationId/$path")
            Unit
        }

        // ─── appendFile ─────────────────────────────────────────────────────

        override suspend fun appendFile(
            locationId: String,
            path: String,
            content: String,
        ) = withContext(ioDispatcher) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkWritePermission(locationId)

            val config = settingsRepository.getServerConfig()
            val uri = findOwnedFileOrThrow(builtin, path)

            val existingSize = queryFileSize(uri)
            val newContentBytes = content.toByteArray(Charsets.UTF_8)
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            if (existingSize + newContentBytes.size.toLong() > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Appending would exceed the configured file size limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            try {
                context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(newContentBytes) }
                    ?: throw McpToolException.ActionFailed("Failed to open file for appending: $path")
            } catch (e: McpToolException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. Use write_file instead.",
                )
            } catch (e: IllegalArgumentException) {
                throw McpToolException.ActionFailed(
                    "This storage provider does not support append mode. Use write_file instead.",
                )
            }

            Log.d(TAG, "Appended ${newContentBytes.size} bytes to $locationId/$path")
            Unit
        }

        // ─── replaceInFile ──────────────────────────────────────────────────

        override suspend fun replaceInFile(
            locationId: String,
            path: String,
            oldString: String,
            newString: String,
            replaceAll: Boolean,
        ): FileReplaceResult =
            withContext(ioDispatcher) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val uri = findOwnedFileOrThrow(builtin, path)
                checkFileSizeByUri(uri)

                val originalContent =
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw McpToolException.ActionFailed("Failed to read file: $path")

                val occurrences = countOccurrences(originalContent, oldString)
                if (occurrences == 0) return@withContext FileReplaceResult(replacementCount = 0)

                val modifiedContent =
                    if (replaceAll) {
                        originalContent.replace(oldString, newString)
                    } else {
                        originalContent.replaceFirst(oldString, newString)
                    }
                val replacementCount = if (replaceAll) occurrences else 1

                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(modifiedContent.toByteArray(Charsets.UTF_8))
                } ?: throw McpToolException.ActionFailed("Failed to write back file: $path")

                Log.d(TAG, "Replaced $replacementCount occurrence(s) in $locationId/$path")
                FileReplaceResult(replacementCount = replacementCount)
            }

        // ─── downloadFromUrl ────────────────────────────────────────────────

        override suspend fun downloadFromUrl(
            locationId: String,
            path: String,
            url: String,
        ): Long =
            withContext(ioDispatcher) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val config = settingsRepository.getServerConfig()
                val parsedUrl = parseAndValidateDownloadUrl(url, config)
                val relativePath = buildRelativePathForDir(builtin, path)
                val displayName = extractDisplayName(path)
                val mimeType = MimeTypeUtils.guessMimeType(displayName)
                val collection = selectCollectionForMimeType(builtin, mimeType)

                // Create MediaStore entry with IS_PENDING = 1
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val insertUri =
                    context.contentResolver.insert(collection.uri, values)
                        ?: throw McpToolException.ActionFailed("Failed to create download destination: $path")

                downloader.downloadToPendingUri(insertUri, parsedUrl, url, config, "$locationId/$path")
            }

        // ─── deleteFile ─────────────────────────────────────────────────────

        override suspend fun deleteFile(
            locationId: String,
            path: String,
        ) = withContext(ioDispatcher) {
            val builtin = resolveBuiltin(locationId)
            BuiltinStorageLocation.validatePath(path)
            checkDeletePermission(locationId)

            val uri = findOwnedFileOrThrow(builtin, path)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) {
                throw McpToolException.ActionFailed(
                    "Failed to delete file: $path in location '$locationId'",
                )
            }

            Log.d(TAG, "Deleted file: $locationId/$path")
            Unit
        }

        // ─── createFileUri ──────────────────────────────────────────────────

        override suspend fun createFileUri(
            locationId: String,
            path: String,
            mimeType: String,
        ): Uri =
            withContext(ioDispatcher) {
                val builtin = resolveBuiltin(locationId)
                BuiltinStorageLocation.validatePath(path)
                checkWritePermission(locationId)

                val relativePath = buildRelativePathForDir(builtin, path)
                val displayName = extractDisplayName(path)
                val collection = selectCollectionForMimeType(builtin, mimeType)

                // Return existing if found
                findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
                    ?.let { return@withContext it }

                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    }
                context.contentResolver.insert(collection.uri, values)
                    ?: throw McpToolException.ActionFailed("Failed to create file: $path")
            }

        // ─── Private helpers ────────────────────────────────────────────────

        // Throws PermissionDenied (not ActionFailed) because this is only called after
        // BuiltinStorageLocation.isBuiltinId() routing — reaching here with an invalid ID
        // means the location is not authorized, matching the SAF checkAuthorization() pattern.
        private fun resolveBuiltin(locationId: String): BuiltinStorageLocation =
            BuiltinStorageLocation.fromLocationId(locationId)
                ?: throw McpToolException.PermissionDenied(
                    "Storage location '$locationId' not found.",
                )

        /**
         * True when MediaStore itself scopes what this app can read in [collection]:
         * either the full read permission is granted, or the user granted a visual-media
         * selection (READ_MEDIA_VISUAL_USER_SELECTED). In both cases the app-side owner
         * filter must be dropped so provider-visible non-owned rows are returned.
         */
        private fun hasNonOwnedReadAccess(collection: MediaCollection): Boolean =
            collection.readMediaPermission?.let { permission ->
                permissionChecker.hasPermission(permission) ||
                    (
                        collection.isVisual &&
                            permissionChecker.hasPermission(
                                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            )
                    )
            } == true

        private fun selectCollectionForMimeType(
            builtin: BuiltinStorageLocation,
            mimeType: String,
        ): MediaCollection =
            builtin.collections.firstOrNull { collection ->
                collection.mimeTypePrefix == null || mimeType.startsWith(collection.mimeTypePrefix)
            } ?: throw McpToolException.InvalidParams(
                "File type '$mimeType' is not supported by location '${builtin.locationId}'. " +
                    "Accepted types: ${builtin.collections.joinToString(", ") { it.typeLabel }}.",
            )

        private fun findFileInCollection(
            collection: MediaCollection,
            relativePath: String,
            displayName: String,
            ownedOnly: Boolean,
        ): Uri? {
            val selection =
                buildString {
                    append("${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ")
                    append("${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
                    if (ownedOnly) append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
                }
            val args =
                if (ownedOnly) {
                    arrayOf(relativePath, displayName, context.packageName)
                } else {
                    arrayOf(relativePath, displayName)
                }
            return queryForUri(collection.uri, selection, args)
        }

        /**
         * Builds the MediaStore RELATIVE_PATH for the directory containing the file.
         * E.g., builtin=DOWNLOADS, path="subdir/file.txt" → "Download/subdir/"
         * E.g., builtin=DOWNLOADS, path="file.txt" → "Download/"
         * E.g., builtin=DOWNLOADS, path="" → "Download/"
         */
        private fun buildRelativePathForDir(
            builtin: BuiltinStorageLocation,
            path: String,
        ): String {
            if (path.isEmpty()) return builtin.baseRelativePath
            val segments = path.split("/").filter { it.isNotEmpty() }
            val parentSegments = segments.dropLast(1)
            return if (parentSegments.isEmpty()) {
                builtin.baseRelativePath
            } else {
                "${builtin.baseRelativePath}${parentSegments.joinToString("/")}/"
            }
        }

        /**
         * Builds the MediaStore RELATIVE_PATH for a directory itself (all segments kept).
         * E.g., builtin=PICTURES, path="DCIM/Camera" → "Pictures/DCIM/Camera/"
         * E.g., builtin=PICTURES, path="" → "Pictures/"
         */
        private fun buildRelativePathForListing(
            builtin: BuiltinStorageLocation,
            path: String,
        ): String {
            if (path.isEmpty()) return builtin.baseRelativePath
            val segments = path.split("/").filter { it.isNotEmpty() }
            return "${builtin.baseRelativePath}${segments.joinToString("/")}/"
        }

        /** Escapes LIKE wildcards so the target path matches literally ('\' MUST be replaced first). */
        private fun escapeLikePattern(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        /**
         * Extracts the file name (last segment) from a relative path.
         * Throws if path is empty.
         */
        private fun extractDisplayName(path: String): String {
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                throw McpToolException.InvalidParams("File path cannot be empty")
            }
            return segments.last()
        }

        private fun findOwnedFile(
            builtin: BuiltinStorageLocation,
            relativePath: String,
            displayName: String,
        ): Uri? =
            builtin.collections.firstNotNullOfOrNull { collection ->
                findFileInCollection(collection, relativePath, displayName, ownedOnly = true)
            }

        private fun findFile(
            builtin: BuiltinStorageLocation,
            relativePath: String,
            displayName: String,
        ): Uri? =
            builtin.collections.firstNotNullOfOrNull { collection ->
                findFileInCollection(
                    collection,
                    relativePath,
                    displayName,
                    ownedOnly = !hasNonOwnedReadAccess(collection),
                )
            }

        private fun findFileOrThrow(
            builtin: BuiltinStorageLocation,
            path: String,
        ): Uri {
            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            return findFile(builtin, relativePath, displayName)
                ?: throw McpToolException.ActionFailed(
                    "File not found: $path in location '${builtin.locationId}'",
                )
        }

        private fun findOwnedFileOrThrow(
            builtin: BuiltinStorageLocation,
            path: String,
        ): Uri {
            val relativePath = buildRelativePathForDir(builtin, path)
            val displayName = extractDisplayName(path)
            return findOwnedFile(builtin, relativePath, displayName)
                ?: throw McpToolException.ActionFailed(
                    "File not found: $path in location '${builtin.locationId}'",
                )
        }

        private fun queryForUri(
            collectionUri: Uri,
            selection: String,
            selectionArgs: Array<String>,
        ): Uri? {
            context.contentResolver
                .query(
                    collectionUri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),
                            )
                        return Uri.withAppendedPath(collectionUri, id.toString())
                    }
                }
            return null
        }

        private fun queryFileSize(uri: Uri): Long {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE),
                        )
                    }
                }
            return 0L
        }

        private suspend fun checkFileSizeByUri(uri: Uri) {
            val config = settingsRepository.getServerConfig()
            val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
            val fileSize = queryFileSize(uri)
            if (fileSize > limitBytes) {
                throw McpToolException.ActionFailed(
                    "File size ($fileSize bytes) exceeds the configured limit of " +
                        "${config.fileSizeLimitMb} MB.",
                )
            }
        }

        private suspend fun checkWritePermission(locationId: String) {
            if (!storageLocationProvider.isWriteAllowed(locationId)) {
                throw McpToolException.PermissionDenied("Write not allowed")
            }
        }

        private suspend fun checkDeletePermission(locationId: String) {
            if (!storageLocationProvider.isDeleteAllowed(locationId)) {
                throw McpToolException.PermissionDenied("Delete not allowed")
            }
        }

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

        companion object {
            private const val TAG = "MCP:MediaStoreFileOps"
            private const val BYTES_PER_MB = 1024L * 1024L
            private const val MILLIS_PER_SECOND = 1000L
        }
    }
