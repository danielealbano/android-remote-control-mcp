package com.danielealbano.androidremotecontrolmcp.services.storage

import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation

/**
 * Manages user-added storage locations.
 *
 * Users add locations via the SAF picker (ACTION_OPEN_DOCUMENT_TREE).
 * This provider handles persistence, URI permissions, and metadata enrichment
 * (e.g., available bytes from the underlying provider).
 */
interface StorageLocationProvider {
    /**
     * Returns all user-added storage locations, enriched with dynamic metadata
     * (e.g., [StorageLocation.availableBytes]).
     */
    suspend fun getAllLocations(): List<StorageLocation>

    /**
     * Adds a new storage location from a granted tree URI.
     *
     * Takes persistable URI permission (read + write), derives the location ID,
     * name, and path from the URI, and persists the location.
     *
     * @param treeUri The granted document tree URI from ACTION_OPEN_DOCUMENT_TREE.
     * @param description User-provided description.
     */
    suspend fun addLocation(
        treeUri: Uri,
        description: String,
    )

    /**
     * Removes a storage location and releases its persistent URI permission.
     *
     * @param locationId The storage location identifier.
     */
    suspend fun removeLocation(locationId: String)

    /**
     * Updates the description of an existing storage location.
     *
     * @param locationId The storage location identifier.
     * @param description The new description.
     */
    suspend fun updateLocationDescription(
        locationId: String,
        description: String,
    )

    /**
     * Checks if a storage location with the given ID exists and is authorized.
     */
    suspend fun isLocationAuthorized(locationId: String): Boolean

    /**
     * Returns the authorized tree URI for a location, or null if not found.
     */
    suspend fun getTreeUriForLocation(locationId: String): Uri?

    /**
     * Returns the [StorageLocation] for a given ID, or null if not found.
     */
    suspend fun getLocationById(locationId: String): StorageLocation?

    /**
     * Checks if a tree URI is already used by an existing location.
     * Used to prevent duplicate entries.
     *
     * @param treeUri The tree URI to check.
     * @return true if a location with this tree URI already exists.
     */
    suspend fun isDuplicateTreeUri(treeUri: Uri): Boolean

    companion object {
        /** Maximum allowed length for location descriptions. */
        const val MAX_DESCRIPTION_LENGTH = 500
    }
}
