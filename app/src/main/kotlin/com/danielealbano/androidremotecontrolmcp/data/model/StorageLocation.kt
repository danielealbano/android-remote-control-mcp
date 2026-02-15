package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a storage location discovered via the Storage Access Framework.
 *
 * Each location corresponds to a document provider root (e.g., Downloads,
 * SD card, Dropbox, Google Drive).
 *
 * @property id Unique identifier: "{authority}/{rootId}" (stable across sessions).
 * @property name Display name from the provider (e.g., "Downloads", "Dropbox").
 * @property providerName Display name of the document provider package.
 * @property authority The content provider authority string.
 * @property rootId The root ID within the provider.
 * @property rootDocumentId The document ID of the root document (for building URIs).
 * @property treeUri The granted persistent tree URI string, or null if not authorized.
 * @property isAuthorized Whether the user has granted access to this location.
 * @property availableBytes Available space in bytes, or null if unknown/virtual.
 * @property iconUri Icon URI from the provider, or null if unavailable.
 */
data class StorageLocation(
    val id: String,
    val name: String,
    val providerName: String,
    val authority: String,
    val rootId: String,
    val rootDocumentId: String,
    val treeUri: String?,
    val isAuthorized: Boolean,
    val availableBytes: Long?,
    val iconUri: String?,
)
