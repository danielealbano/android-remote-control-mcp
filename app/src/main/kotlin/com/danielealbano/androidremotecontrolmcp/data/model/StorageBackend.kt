package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Identifies the storage API backend for a storage location.
 * This is an internal implementation detail — not exposed to MCP clients.
 */
enum class StorageBackend {
    /** Storage Access Framework (user-picked directories via ACTION_OPEN_DOCUMENT_TREE). */
    SAF,

    /** Android MediaStore API (built-in collections: Downloads, Pictures, Movies, Music). */
    MEDIA_STORE,
}
