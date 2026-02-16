package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a user-managed storage location added via the Storage Access Framework.
 *
 * Each location corresponds to a directory the user selected via ACTION_OPEN_DOCUMENT_TREE.
 * The app holds a persistent URI permission for the granted tree URI.
 *
 * @property id Unique identifier derived from the tree URI: "{authority}/{documentId}".
 * @property name Display name of the picked directory (from DocumentFile.getName()).
 * @property path Human-readable path within the provider (e.g., "/Documents/MyProject"),
 *   or "/" when the location is a provider root. Derived from the document ID for
 *   physical storage providers; "/" for virtual providers with opaque document IDs.
 * @property description User-provided description to give context/hints to MCP clients.
 * @property treeUri The granted persistent tree URI string.
 * @property availableBytes Available space in bytes, or null if unknown/virtual.
 * @property allowWrite Whether MCP tools are permitted to write/modify files in this location.
 * @property allowDelete Whether MCP tools are permitted to delete files in this location.
 */
data class StorageLocation(
    val id: String,
    val name: String,
    val path: String,
    val description: String,
    val treeUri: String,
    val availableBytes: Long?,
    val allowWrite: Boolean = false,
    val allowDelete: Boolean = false,
)
