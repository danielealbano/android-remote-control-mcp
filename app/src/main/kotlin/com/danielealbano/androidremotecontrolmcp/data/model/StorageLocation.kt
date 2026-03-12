package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a storage location available for file operations.
 *
 * For SAF locations, each corresponds to a directory the user selected via
 * ACTION_OPEN_DOCUMENT_TREE. For built-in MediaStore locations, these are
 * synthesized at runtime and always available.
 *
 * @property id Unique identifier. SAF: "{authority}/{documentId}". Built-in: "builtin:{name}".
 * @property name Display name of the location.
 * @property path Human-readable path within the provider (e.g., "/Documents/MyProject"),
 *   or "/" when the location is a provider root. Derived from the document ID for
 *   physical storage providers; "/" for virtual providers with opaque document IDs.
 * @property description User-provided description to give context/hints to MCP clients.
 * @property treeUri The granted persistent tree URI string. Empty for built-in locations.
 * @property availableBytes Available space in bytes, or null if unknown/virtual.
 * @property allowWrite Whether MCP tools are permitted to write/modify files in this location.
 * @property allowDelete Whether MCP tools are permitted to delete files in this location.
 * @property backend Internal implementation detail — identifies which storage API to use.
 *   Not exposed to MCP clients.
 * @property isBuiltin True for built-in MediaStore locations that are always available.
 *   Built-in locations cannot be removed or renamed.
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
    val backend: StorageBackend = StorageBackend.SAF,
    val isBuiltin: Boolean = false,
)
