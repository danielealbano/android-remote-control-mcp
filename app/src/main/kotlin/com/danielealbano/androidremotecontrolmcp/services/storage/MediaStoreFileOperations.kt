package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri

/**
 * File operations for built-in MediaStore storage locations.
 *
 * All methods mirror [FileOperationProvider] but are scoped to built-in locations.
 * Path traversal protection is enforced on all operations.
 */
interface MediaStoreFileOperations {
    suspend fun listFiles(locationId: String, path: String, offset: Int, limit: Int): FileListResult
    suspend fun readFile(locationId: String, path: String, offset: Int, limit: Int): FileReadResult
    suspend fun writeFile(locationId: String, path: String, content: String)
    suspend fun appendFile(locationId: String, path: String, content: String)
    suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult
    suspend fun downloadFromUrl(locationId: String, path: String, url: String): Long
    suspend fun deleteFile(locationId: String, path: String)
    suspend fun createFileUri(locationId: String, path: String, mimeType: String): Uri
}
