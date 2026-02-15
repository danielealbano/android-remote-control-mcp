package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Default implementation of [StorageLocationProvider] using the Storage Access Framework.
 *
 * Discovers document provider roots and manages persistent URI permissions
 * through [SettingsRepository] for authorization state persistence.
 */
class StorageLocationProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : StorageLocationProvider {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun getAvailableLocations(): List<StorageLocation> {
        val authorizedLocations = settingsRepository.getAuthorizedLocations()
        val locations = mutableListOf<StorageLocation>()

        val intent = Intent(DocumentsContract.PROVIDER_INTERFACE)
        val providers = context.packageManager.queryIntentContentProviders(
            intent,
            PackageManager.MATCH_ALL,
        )

        for (providerInfo in providers) {
            val authority = providerInfo.providerInfo.authority
            val providerName = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(
                        providerInfo.providerInfo.packageName,
                        0,
                    ),
                ).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not resolve provider name for ${providerInfo.providerInfo.packageName}", e)
                providerInfo.providerInfo.packageName
            }

            val rootsUri = DocumentsContract.buildRootsUri(authority)
            val cursor = try {
                context.contentResolver.query(
                    rootsUri,
                    arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_AVAILABLE_BYTES,
                        Root.COLUMN_ICON,
                    ),
                    null,
                    null,
                    null,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException querying roots for authority=$authority, skipping", e)
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query roots for authority=$authority, skipping", e)
                continue
            }

            if (cursor == null) {
                Log.w(TAG, "Null cursor for roots query on authority=$authority")
                continue
            }

            try {
                val rootIdIdx = cursor.getColumnIndex(Root.COLUMN_ROOT_ID)
                val titleIdx = cursor.getColumnIndex(Root.COLUMN_TITLE)
                val documentIdIdx = cursor.getColumnIndex(Root.COLUMN_DOCUMENT_ID)
                val availableBytesIdx = cursor.getColumnIndex(Root.COLUMN_AVAILABLE_BYTES)
                val iconIdx = cursor.getColumnIndex(Root.COLUMN_ICON)

                while (cursor.moveToNext()) {
                    val rootId = cursor.getString(rootIdIdx) ?: continue
                    val title = cursor.getString(titleIdx) ?: rootId
                    val documentId = cursor.getString(documentIdIdx) ?: continue
                    val availableBytes = if (availableBytesIdx >= 0 && !cursor.isNull(availableBytesIdx)) {
                        cursor.getLong(availableBytesIdx)
                    } else {
                        null
                    }
                    val iconResId = if (iconIdx >= 0 && !cursor.isNull(iconIdx)) {
                        cursor.getInt(iconIdx)
                    } else {
                        0
                    }

                    val locationId = "$authority/$rootId"
                    val storedTreeUri = authorizedLocations[locationId]
                    val isAuthorized = storedTreeUri != null

                    val iconUri = if (iconResId != 0) {
                        Uri.Builder()
                            .scheme("android.resource")
                            .authority(providerInfo.providerInfo.packageName)
                            .appendPath(iconResId.toString())
                            .build()
                            .toString()
                    } else {
                        null
                    }

                    locations.add(
                        StorageLocation(
                            id = locationId,
                            name = title,
                            providerName = providerName,
                            authority = authority,
                            rootId = rootId,
                            rootDocumentId = documentId,
                            treeUri = storedTreeUri,
                            isAuthorized = isAuthorized,
                            availableBytes = availableBytes,
                            iconUri = iconUri,
                        ),
                    )
                }
            } finally {
                cursor.close()
            }
        }

        Log.d(TAG, "Discovered ${locations.size} storage locations from ${providers.size} providers")
        return locations.sortedBy { it.name }
    }

    override suspend fun getAuthorizedLocations(): List<StorageLocation> {
        return getAvailableLocations().filter { it.isAuthorized }
    }

    override suspend fun authorizeLocation(locationId: String, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settingsRepository.addAuthorizedLocation(locationId, treeUri.toString())
        Log.i(TAG, "Authorized storage location: $locationId")
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun deauthorizeLocation(locationId: String) {
        val authorizedLocations = settingsRepository.getAuthorizedLocations()
        val treeUriString = authorizedLocations[locationId]
        if (treeUriString != null) {
            try {
                val uri = Uri.parse(treeUriString)
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission already revoked for location=$locationId", e)
            }
        }
        settingsRepository.removeAuthorizedLocation(locationId)
        Log.i(TAG, "Deauthorized storage location: $locationId")
    }

    override suspend fun isLocationAuthorized(locationId: String): Boolean {
        return settingsRepository.getAuthorizedLocations().containsKey(locationId)
    }

    override suspend fun getLocationById(locationId: String): StorageLocation? {
        return getAvailableLocations().find { it.id == locationId }
    }

    override suspend fun getTreeUriForLocation(locationId: String): Uri? {
        val treeUriString = settingsRepository.getAuthorizedLocations()[locationId]
        return treeUriString?.let { Uri.parse(it) }
    }

    companion object {
        private const val TAG = "MCP:StorageProvider"
    }
}
