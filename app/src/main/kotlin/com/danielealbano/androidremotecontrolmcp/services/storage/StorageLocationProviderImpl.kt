package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * User-managed implementation of [StorageLocationProvider].
 *
 * Users add storage locations via the SAF picker. This class manages
 * persistent URI permissions and metadata enrichment. No SAF discovery
 * is performed — the location list is entirely user-driven.
 */
class StorageLocationProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) : StorageLocationProvider {
        override suspend fun getAllLocations(): List<StorageLocation> {
            val stored = settingsRepository.getStoredLocations()
            return stored.map { loc ->
                StorageLocation(
                    id = loc.id,
                    name = loc.name,
                    path = loc.path,
                    description = loc.description,
                    treeUri = loc.treeUri,
                    availableBytes = queryAvailableBytes(loc.treeUri),
                    allowWrite = loc.allowWrite,
                    allowDelete = loc.allowDelete,
                )
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun addLocation(
            treeUri: Uri,
            description: String,
        ) {
            // Validate tree URI
            require(treeUri.scheme == "content") {
                "Invalid URI scheme: expected content://, got ${treeUri.scheme}"
            }
            require(DocumentsContract.isTreeUri(treeUri)) {
                "URI is not a valid document tree URI"
            }
            val authority = treeUri.authority
            requireNotNull(authority) {
                "URI has no authority component"
            }

            // Validate description length
            val trimmedDescription = description.take(StorageLocationProvider.MAX_DESCRIPTION_LENGTH)

            // Defense-in-depth duplicate check
            check(!isDuplicateTreeUri(treeUri)) {
                "A storage location with this directory already exists"
            }

            val permissionFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, permissionFlags)

            try {
                val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                val locationId = "$authority/$treeDocumentId"

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                val name = docFile?.name ?: treeDocumentId

                val path = deriveHumanReadablePath(treeDocumentId)

                val storedLocation =
                    SettingsRepository.StoredLocation(
                        id = locationId,
                        name = name,
                        path = path,
                        description = trimmedDescription,
                        treeUri = treeUri.toString(),
                        allowWrite = false,
                        allowDelete = false,
                    )
                settingsRepository.addStoredLocation(storedLocation)
                Log.i(TAG, "Added storage location: $locationId ($name)")
            } catch (e: Exception) {
                // Release permission if anything after takePersistableUriPermission fails
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        treeUri,
                        permissionFlags,
                    )
                } catch (releaseEx: Exception) {
                    Log.w(TAG, "Failed to release permission after addLocation failure", releaseEx)
                }
                throw e
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun removeLocation(locationId: String) {
            val stored = settingsRepository.getStoredLocations()
            val location = stored.find { it.id == locationId }
            if (location != null) {
                try {
                    val uri = Uri.parse(location.treeUri)
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release permission for location=$locationId", e)
                }
            }
            settingsRepository.removeStoredLocation(locationId)
            Log.i(TAG, "Removed storage location: $locationId")
        }

        override suspend fun updateLocationDescription(
            locationId: String,
            description: String,
        ) {
            val trimmedDescription = description.take(StorageLocationProvider.MAX_DESCRIPTION_LENGTH)
            settingsRepository.updateLocationDescription(locationId, trimmedDescription)
            Log.i(TAG, "Updated description for location: $locationId")
        }

        override suspend fun isLocationAuthorized(locationId: String): Boolean =
            settingsRepository.getStoredLocations().any { it.id == locationId }

        override suspend fun getTreeUriForLocation(locationId: String): Uri? {
            val stored = settingsRepository.getStoredLocations()
            val location = stored.find { it.id == locationId }
            return location?.let { Uri.parse(it.treeUri) }
        }

        override suspend fun getLocationById(locationId: String): StorageLocation? {
            val stored =
                settingsRepository.getStoredLocations().find { it.id == locationId }
                    ?: return null
            return StorageLocation(
                id = stored.id,
                name = stored.name,
                path = stored.path,
                description = stored.description,
                treeUri = stored.treeUri,
                availableBytes = queryAvailableBytes(stored.treeUri),
                allowWrite = stored.allowWrite,
                allowDelete = stored.allowDelete,
            )
        }

        override suspend fun isWriteAllowed(locationId: String): Boolean =
            settingsRepository.getStoredLocations().find { it.id == locationId }?.allowWrite ?: false

        override suspend fun isDeleteAllowed(locationId: String): Boolean =
            settingsRepository.getStoredLocations().find { it.id == locationId }?.allowDelete ?: false

        override suspend fun updateLocationAllowWrite(
            locationId: String,
            allowWrite: Boolean,
        ) {
            settingsRepository.updateLocationAllowWrite(locationId, allowWrite)
            Log.i(TAG, "Updated allowWrite=$allowWrite for location: $locationId")
        }

        override suspend fun updateLocationAllowDelete(
            locationId: String,
            allowDelete: Boolean,
        ) {
            settingsRepository.updateLocationAllowDelete(locationId, allowDelete)
            Log.i(TAG, "Updated allowDelete=$allowDelete for location: $locationId")
        }

        override suspend fun isDuplicateTreeUri(treeUri: Uri): Boolean {
            val treeUriString = treeUri.toString()
            return settingsRepository.getStoredLocations().any { it.treeUri == treeUriString }
        }

        /**
         * Derives a human-readable path from a SAF document ID.
         *
         * For physical storage providers, the document ID format is typically
         * "{rootId}:{path}" (e.g., "primary:Documents/MyProject"). This method
         * extracts the path portion after the colon.
         *
         * For virtual providers (Google Drive, etc.) the document ID is opaque,
         * so this returns "/".
         */
        private fun deriveHumanReadablePath(documentId: String): String {
            val colonIndex = documentId.indexOf(':')
            if (colonIndex < 0) return "/"
            val pathPart = documentId.substring(colonIndex + 1)
            return if (pathPart.isEmpty()) "/" else "/$pathPart"
        }

        /**
         * Queries available bytes for a location by querying the provider's roots.
         * Returns null if the query fails or the provider doesn't report this info.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "NestedBlockDepth", "ReturnCount")
        private fun queryAvailableBytes(treeUriString: String): Long? {
            return try {
                val treeUri = Uri.parse(treeUriString)
                val authority = treeUri.authority ?: return null
                val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

                // Extract root ID from document ID (portion before the colon)
                val rootId = treeDocumentId.substringBefore(":")

                val rootsUri = DocumentsContract.buildRootsUri(authority)
                val cursor =
                    context.contentResolver.query(
                        rootsUri,
                        arrayOf(
                            DocumentsContract.Root.COLUMN_ROOT_ID,
                            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                        ),
                        null,
                        null,
                        null,
                    )
                cursor?.use {
                    val rootIdIdx = it.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                    val bytesIdx = it.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                    while (it.moveToNext()) {
                        val curRootId = it.getString(rootIdIdx)
                        if (curRootId == rootId && bytesIdx >= 0 && !it.isNull(bytesIdx)) {
                            return it.getLong(bytesIdx)
                        }
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query available bytes for $treeUriString", e)
                null
            }
        }

        companion object {
            private const val TAG = "MCP:StorageProvider"
        }
    }
