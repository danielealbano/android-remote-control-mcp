package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation

/**
 * Provides discovery and authorization management for storage locations.
 *
 * Uses Android's Storage Access Framework to enumerate document provider roots
 * and manage persistent URI permissions for authorized locations.
 */
interface StorageLocationProvider {
    /**
     * Returns all discovered storage locations (both authorized and unauthorized).
     * Queries all installed document providers for their roots.
     */
    suspend fun getAvailableLocations(): List<StorageLocation>

    /**
     * Returns only locations that have been authorized by the user.
     */
    suspend fun getAuthorizedLocations(): List<StorageLocation>

    /**
     * Persists a granted tree URI for the given location ID.
     * Takes persistable URI permission (read + write) via ContentResolver.
     *
     * @param locationId The storage location identifier ("{authority}/{rootId}").
     * @param treeUri The granted document tree URI from ACTION_OPEN_DOCUMENT_TREE.
     */
    suspend fun authorizeLocation(locationId: String, treeUri: Uri)

    /**
     * Removes authorization for a location.
     * Releases persistable URI permission via ContentResolver.
     *
     * @param locationId The storage location identifier.
     */
    suspend fun deauthorizeLocation(locationId: String)

    /**
     * Checks if a location is authorized.
     */
    suspend fun isLocationAuthorized(locationId: String): Boolean

    /**
     * Returns the StorageLocation for a given ID, or null if not found.
     */
    suspend fun getLocationById(locationId: String): StorageLocation?

    /**
     * Returns the authorized tree URI for a location, or null if not authorized.
     */
    suspend fun getTreeUriForLocation(locationId: String): Uri?
}
