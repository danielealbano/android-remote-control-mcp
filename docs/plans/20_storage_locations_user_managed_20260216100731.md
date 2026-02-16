# Plan 20 — Storage Locations: User-Managed Flow

**Created**: 2026-02-16  
**Status**: Draft  
**Summary**: Replace the SAF discovery-based storage location flow with a fully user-managed model. Users add locations manually via an "Add" modal + SAF picker, can edit descriptions, and can delete locations with confirmation. Drop the discovery mechanism entirely.

---

## Context & Decisions

The current flow discovers SAF document providers via `queryIntentContentProviders` and displays them in the UI for toggle-based authorization. This fails silently on many devices/emulators, leaving users with "No storage locations found" and no way to proceed.

**Agreed changes:**
- Drop SAF discovery entirely — no more `queryIntentContentProviders` or root queries
- User-managed model: users add locations via "Add Storage Location" button → modal with description field + Browse button → SAF picker
- Users can edit descriptions (modal dialog)
- Users can delete locations (with confirmation dialog)
- Prevent duplicate tree URIs (both UI-side and defense-in-depth inside `addLocation`)
- `list_storage_locations` MCP output changes: drop `authorized` field, add `path` and `description` fields, retain `available_bytes`
- `available_bytes` obtained dynamically by querying the provider's roots at list time
- `id` format: `{authority}/{documentId}` derived from the tree URI
- `name`: whatever `DocumentFile.fromTreeUri().getName()` returns
- Storage data model changes: `StorageLocation` simplified, `SettingsRepository` stores richer data per location
- Description field max length: 500 characters (enforced in UI + provider)
- Tree URI validation: `content://` scheme, `isTreeUri`, non-null authority
- Permission leak protection: release permission if `addLocation` fails after `takePersistableUriPermission`
- Android persistable URI permission limit (~128-512) documented as known constraint

---

## Files Impacted

| File | Action |
|------|--------|
| `app/src/main/kotlin/.../data/model/StorageLocation.kt` | Rewrite |
| `app/src/main/kotlin/.../services/storage/StorageLocationProvider.kt` | Rewrite interface |
| `app/src/main/kotlin/.../services/storage/StorageLocationProviderImpl.kt` | Rewrite implementation |
| `app/src/main/kotlin/.../data/repository/SettingsRepository.kt` | Modify storage methods |
| `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt` | Modify storage methods |
| `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt` | Modify storage location methods |
| `app/src/main/kotlin/.../ui/components/StorageLocationsSection.kt` | Rewrite location list UI |
| `app/src/main/kotlin/.../ui/screens/HomeScreen.kt` | Update storage interaction flow |
| `app/src/main/kotlin/.../mcp/tools/FileTools.kt` | Update `ListStorageLocationsHandler` output |
| `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` | Update `checkAuthorization` error message |
| `app/src/main/res/values/strings.xml` | Update/add string resources |
| `app/src/test/kotlin/.../services/storage/StorageLocationProviderTest.kt` | Rewrite tests |
| `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt` | Create new tests for storage methods + migration |
| `app/src/test/kotlin/.../ui/viewmodels/MainViewModelTest.kt` | Update storage tests |
| `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` | Update `list_storage_locations` test |
| `docs/MCP_TOOLS.md` | Update `list_storage_locations` section |
| `docs/PROJECT.md` | Update SAF authorization section and file tools section |

---

## User Story 1: Refactor Data Model & Storage Layer

**As a developer**, I need the data model and storage layer to support user-managed storage locations (add/remove/edit description, no discovery) so the rest of the app can be built on top.

### Acceptance Criteria
- [ ] `StorageLocation` data class uses new field set: `id`, `name`, `path`, `description`, `treeUri`, `availableBytes`
- [ ] `StorageLocationProvider` interface has methods: `getAllLocations`, `addLocation`, `removeLocation`, `updateLocationDescription`, `isLocationAuthorized`, `getTreeUriForLocation`, `getLocationById`, `isDuplicateTreeUri`, `MAX_DESCRIPTION_LENGTH`
- [ ] `SettingsRepository` stores full location objects (not just `id → treeUri` map)
- [ ] `StorageLocationProviderImpl` does not use SAF discovery; manages a user-driven list
- [ ] `addLocation` validates URI (scheme, isTreeUri, authority), checks duplicates, truncates description, releases permission on failure
- [ ] `available_bytes` is fetched dynamically when listing locations
- [ ] `getLocationById` only enriches the target location (not all)
- [ ] Migration from old JSON format works (old `{"id": "treeUri"}` → new array format)
- [ ] All existing tests rewritten/updated and passing
- [ ] New `SettingsRepositoryImplTest` covering storage methods and migration
- [ ] Linting passes (`./gradlew ktlintCheck`, `./gradlew detekt`)
- [ ] Targeted tests pass: `./gradlew :app:testDebugUnitTest --tests "*.StorageLocationProviderTest"` and `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryImplTest"`

---

### Task 1.1: Update `StorageLocation` data class

**Definition of Done**: New data class compiles, all fields present, no discovery-related fields remain.

- [x] **Action 1.1.1**: Replace `StorageLocation` data class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageLocation.kt`

**What changes**: Replace the entire data class. Remove fields: `providerName`, `authority`, `rootId`, `rootDocumentId`, `isAuthorized`, `iconUri`. Add field: `description`. Keep: `id`, `name`, `treeUri`, `availableBytes`. Add: `path`. Change `treeUri` from `String?` to `String` (all user-added locations have a treeUri).

**Current** (lines 1-31):
```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a storage location discovered via the Storage Access Framework.
 * ...
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
```

**New**:
```kotlin
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
 */
data class StorageLocation(
    val id: String,
    val name: String,
    val path: String,
    val description: String,
    val treeUri: String,
    val availableBytes: Long?,
)
```

---

### Task 1.2: Update `SettingsRepository` storage methods

**Definition of Done**: Interface and implementation store/retrieve full location objects (id, name, path, description, treeUri) as JSON. Old `Map<String, String>` format replaced.

- [x] **Action 1.2.1**: Update `SettingsRepository` interface — storage methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

**What changes**: Replace the three methods at lines 155-177. The new methods work with a list of serializable location records instead of a simple `Map<String, String>`. Add an `updateLocationDescription` method.

**Current** (lines 155-177):
```kotlin
    /**
     * Returns the map of authorized storage location IDs to their tree URI strings.
     */
    suspend fun getAuthorizedLocations(): Map<String, String>

    /**
     * Adds an authorized storage location with its tree URI.
     *
     * @param locationId The storage location identifier ("{authority}/{rootId}").
     * @param treeUri The granted document tree URI string.
     */
    suspend fun addAuthorizedLocation(
        locationId: String,
        treeUri: String,
    )

    /**
     * Removes an authorized storage location.
     *
     * @param locationId The storage location identifier.
     */
    suspend fun removeAuthorizedLocation(locationId: String)
```

**New**:
```kotlin
    /**
     * Data class representing a stored storage location record.
     * This is the persistence format; the full [StorageLocation] includes
     * dynamic fields like [StorageLocation.availableBytes].
     *
     * @property id Unique identifier: "{authority}/{documentId}".
     * @property name Display name of the directory.
     * @property path Human-readable path within the provider.
     * @property description User-provided description.
     * @property treeUri The granted persistent tree URI string.
     */
    data class StoredLocation(
        val id: String,
        val name: String,
        val path: String,
        val description: String,
        val treeUri: String,
    )

    /**
     * Returns all stored storage locations.
     */
    suspend fun getStoredLocations(): List<StoredLocation>

    /**
     * Adds a storage location.
     */
    suspend fun addStoredLocation(location: StoredLocation)

    /**
     * Removes a storage location by ID.
     */
    suspend fun removeStoredLocation(locationId: String)

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
```

- [x] **Action 1.2.2**: Update `SettingsRepositoryImpl` — storage methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**What changes**: Replace `getAuthorizedLocations`, `addAuthorizedLocation`, `removeAuthorizedLocation` (lines 196-221) with `getStoredLocations`, `addStoredLocation`, `removeStoredLocation`, `updateLocationDescription`. Replace `parseAuthorizedLocationsJson` and `serializeAuthorizedLocationsJson` (lines 300-317) with methods that serialize/deserialize `List<StoredLocation>` as a JSON array. The DataStore key `AUTHORIZED_LOCATIONS_KEY` (line 336) stays the same key name for storage continuity but the format changes from a JSON object to a JSON array. The old format (`{"id": "treeUri"}`) should be handled in a migration path: if parsing as array fails, try parsing as old format and convert (the next mutation will persist in the new format). Add a `TAG` constant to the companion object for logging.

**Current methods to replace** (lines 196-221):
```kotlin
override suspend fun getAuthorizedLocations(): Map<String, String> { ... }
override suspend fun addAuthorizedLocation(locationId: String, treeUri: String) { ... }
override suspend fun removeAuthorizedLocation(locationId: String) { ... }
```

**New methods**:
```kotlin
override suspend fun getStoredLocations(): List<StoredLocation> {
    val prefs = dataStore.data.first()
    val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyList()
    return parseStoredLocationsJson(jsonString)
}

override suspend fun addStoredLocation(location: StoredLocation) {
    dataStore.edit { prefs ->
        val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
        existing.add(location)
        prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
    }
}

override suspend fun removeStoredLocation(locationId: String) {
    dataStore.edit { prefs ->
        val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
        existing.removeAll { it.id == locationId }
        prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
    }
}

override suspend fun updateLocationDescription(locationId: String, description: String) {
    dataStore.edit { prefs ->
        val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
        val index = existing.indexOfFirst { it.id == locationId }
        if (index >= 0) {
            existing[index] = existing[index].copy(description = description)
            prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
        }
    }
}
```

**Replace helper methods** (lines 300-317):
```kotlin
// Current:
private fun parseAuthorizedLocationsJson(json: String?): Map<String, String> { ... }
private fun serializeAuthorizedLocationsJson(map: Map<String, String>): String { ... }

// New:
@Suppress("SwallowedException")
private fun parseStoredLocationsJson(json: String?): List<StoredLocation> {
    if (json == null) return emptyList()
    return try {
        val jsonArray = Json.parseToJsonElement(json).jsonArray
        jsonArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val treeUri = obj["treeUri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.content ?: ""
                StoredLocation(
                    id = id,
                    name = name,
                    path = path,
                    description = description,
                    treeUri = treeUri,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed stored location entry", e)
                null
            }
        }
    } catch (_: Exception) {
        // Migration: try parsing old format (JSON object: {"locationId": "treeUri"})
        try {
            val jsonObject = Json.parseToJsonElement(json).jsonObject
            jsonObject.map { (key, value) ->
                StoredLocation(
                    id = key,
                    name = key.substringAfterLast("/"),
                    path = "/",
                    description = "",
                    treeUri = value.jsonPrimitive.content,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored locations JSON, returning empty list", e)
            emptyList()
        }
    }
}

private fun serializeStoredLocationsJson(locations: List<StoredLocation>): String =
    Json.encodeToString(
        buildJsonArray {
            for (loc in locations) {
                add(
                    buildJsonObject {
                        put("id", loc.id)
                        put("name", loc.name)
                        put("path", loc.path)
                        put("description", loc.description)
                        put("treeUri", loc.treeUri)
                    },
                )
            }
        },
    )
```

**Additional imports needed** in `SettingsRepositoryImpl.kt`: `kotlinx.serialization.json.jsonArray`, `kotlinx.serialization.json.buildJsonArray`

**Add `TAG` to companion object**: Add `private const val TAG = "MCP:SettingsRepo"` to the existing `companion object` block in `SettingsRepositoryImpl`.

---

### Task 1.3: Rewrite `StorageLocationProvider` interface

**Definition of Done**: Interface reflects user-managed model with no discovery. All methods documented.

- [x] **Action 1.3.1**: Replace `StorageLocationProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProvider.kt`

**Current** (lines 1-58): Interface with `getAvailableLocations`, `getAuthorizedLocations`, `authorizeLocation`, `deauthorizeLocation`, `isLocationAuthorized`, `getLocationById`, `getTreeUriForLocation`.

**New**:
```kotlin
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
```

---

### Task 1.4: Rewrite `StorageLocationProviderImpl`

**Definition of Done**: Implementation uses `SettingsRepository` for storage, derives ID/name/path from tree URI, fetches `available_bytes` dynamically, no SAF discovery.

- [x] **Action 1.4.1**: Replace `StorageLocationProviderImpl` entirely

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt`

**What changes**: Complete rewrite. Remove all `queryIntentContentProviders` / roots-query logic. The new implementation:

1. `getAllLocations()`: reads stored locations from `SettingsRepository`, enriches each with `availableBytes` by querying the provider's roots (best-effort, null if unavailable).
2. `addLocation(treeUri, description)`: returns `Unit` (no return value — the caller refreshes the full list after adding). Validates URI (content:// scheme, isTreeUri, non-null authority), validates description length (truncates to 500 chars), checks for duplicate tree URI (defense-in-depth), takes persistable URI permission, derives `id` from `{authority}/{treeDocumentId}` (extracted from the tree URI), gets `name` via `DocumentFile.fromTreeUri().getName()`, derives `path` from the document ID (for physical storage: extract the path after the colon; for virtual/opaque: "/"), persists via `SettingsRepository.addStoredLocation()`. If any step after `takePersistableUriPermission` fails, releases the permission to prevent leaks.
3. `removeLocation(locationId)`: releases persistable URI permission, removes from `SettingsRepository`.
4. `updateLocationDescription(locationId, description)`: delegates to `SettingsRepository.updateLocationDescription()`.
5. `isLocationAuthorized(locationId)`: checks if ID exists in stored locations.
6. `getTreeUriForLocation(locationId)`: looks up treeUri from stored locations.
7. `getLocationById(locationId)`: looks up from stored locations (only enriches the target location, not all).
8. `isDuplicateTreeUri(treeUri)`: checks if any stored location has the same treeUri string.

**New** (full replacement):
```kotlin
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
            if (isDuplicateTreeUri(treeUri)) {
                throw IllegalStateException(
                    "A storage location with this directory already exists",
                )
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

                val storedLocation = SettingsRepository.StoredLocation(
                    id = locationId,
                    name = name,
                    path = path,
                    description = trimmedDescription,
                    treeUri = treeUri.toString(),
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
            val stored = settingsRepository.getStoredLocations().find { it.id == locationId }
                ?: return null
            return StorageLocation(
                id = stored.id,
                name = stored.name,
                path = stored.path,
                description = stored.description,
                treeUri = stored.treeUri,
                availableBytes = queryAvailableBytes(stored.treeUri),
            )
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
        @Suppress("TooGenericExceptionCaught", "SwallowedException", "NestedBlockDepth")
        private fun queryAvailableBytes(treeUriString: String): Long? {
            return try {
                val treeUri = Uri.parse(treeUriString)
                val authority = treeUri.authority ?: return null
                val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

                // Extract root ID from document ID (portion before the colon)
                val rootId = treeDocumentId.substringBefore(":")

                val rootsUri = DocumentsContract.buildRootsUri(authority)
                val cursor = context.contentResolver.query(
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
```

---

### Task 1.5: Rewrite `StorageLocationProviderTest`

**Definition of Done**: All tests updated for new interface. Discovery tests removed. Tests cover: `getAllLocations`, `addLocation`, `removeLocation`, `updateLocationDescription`, `isLocationAuthorized`, `getTreeUriForLocation`, `isDuplicateTreeUri`. All pass.

- [x] **Action 1.5.1**: Rewrite `StorageLocationProviderTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt`

**What changes**: Complete rewrite. Remove all discovery-related mocks (`PackageManager`, `Cursor`, `queryIntentContentProviders`). New tests mock `SettingsRepository.getStoredLocations()`, `addStoredLocation()`, `removeStoredLocation()`, `updateLocationDescription()`. Mock `ContentResolver` for `takePersistableUriPermission`/`releasePersistableUriPermission`. Mock `DocumentsContract.getTreeDocumentId()` and `DocumentFile.fromTreeUri()` for `addLocation` tests.

**New test structure**:
```
StorageLocationProviderImpl
  ├── GetAllLocations
  │   ├── getAllLocations returns stored locations with enriched metadata
  │   ├── getAllLocations returns empty list when no locations stored
  │   └── getAllLocations returns locations with null availableBytes when queryAvailableBytes fails
  ├── AddLocation
  │   ├── addLocation persists location, takes permission, and returns Unit
  │   ├── addLocation derives path from physical storage document ID (e.g., "primary:Documents/MyProject" → "/Documents/MyProject")
  │   ├── addLocation derives root path for storage root (e.g., "primary:" → "/")
  │   ├── addLocation derives root path for virtual provider with opaque document ID (no colon → "/")
  │   ├── addLocation falls back to document ID when DocumentFile.getName() returns null
  │   ├── addLocation truncates description to MAX_DESCRIPTION_LENGTH
  │   ├── addLocation rejects non-content URI scheme
  │   ├── addLocation rejects non-tree URI
  │   ├── addLocation rejects URI with null authority
  │   ├── addLocation rejects duplicate tree URI
  │   └── addLocation releases permission if persistence fails after takePersistableUriPermission
  ├── RemoveLocation
  │   ├── removeLocation removes entry and releases permission
  │   └── removeLocation handles already-revoked permission gracefully
  ├── UpdateLocationDescription
  │   ├── updateLocationDescription delegates to settings repository
  │   └── updateLocationDescription with non-existent locationId is a no-op
  ├── IsLocationAuthorized
  │   ├── isLocationAuthorized returns true for existing locations
  │   └── isLocationAuthorized returns false for unknown locations
  ├── GetLocationById
  │   ├── getLocationById returns location for known ID (without enriching all locations)
  │   └── getLocationById returns null for unknown ID
  ├── GetTreeUriForLocation
  │   ├── getTreeUriForLocation returns URI for known location
  │   └── getTreeUriForLocation returns null for unknown location
  └── IsDuplicateTreeUri
      ├── isDuplicateTreeUri returns true for existing tree URI
      └── isDuplicateTreeUri returns false for new tree URI
```

Each test follows Arrange-Act-Assert pattern with MockK. Key mock setup notes:
- `DocumentFile.fromTreeUri()` requires `mockkStatic(DocumentFile::class)` in `@BeforeEach` and `unmockkStatic(DocumentFile::class)` in `@AfterEach`
- `DocumentsContract.isTreeUri()` and `DocumentsContract.getTreeDocumentId()` require `mockkStatic(DocumentsContract::class)`
- Permission leak test: mock `settingsRepository.addStoredLocation()` to throw, verify `releasePersistableUriPermission` is called

- [ ] **Action 1.5.2**: Run targeted tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProviderTest"
```

---

### Task 1.6: Create `SettingsRepositoryImplTest` for storage methods

**Definition of Done**: Tests cover all new storage methods and the migration path. All pass.

- [x] **Action 1.6.1**: Create `SettingsRepositoryImplTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` (NEW FILE)

**What**: Create a new test file that uses a real DataStore (in-memory or temp file) to test the storage methods end-to-end. Alternatively, the JSON parsing/serialization helpers can be tested by extracting them or by testing through the public interface.

**Test structure**:
```
SettingsRepositoryImpl — Storage Location Methods
  ├── GetStoredLocations
  │   ├── getStoredLocations returns empty list when no data stored
  │   ├── getStoredLocations returns stored locations
  │   └── getStoredLocations handles corrupt JSON gracefully (returns empty list)
  ├── AddStoredLocation
  │   ├── addStoredLocation appends to existing list
  │   └── addStoredLocation works on empty list
  ├── RemoveStoredLocation
  │   ├── removeStoredLocation removes matching entry
  │   └── removeStoredLocation is no-op for non-existent ID
  ├── UpdateLocationDescription
  │   ├── updateLocationDescription updates matching entry
  │   └── updateLocationDescription is no-op for non-existent ID
  └── Migration
      ├── getStoredLocations migrates old JSON object format to new array format
      ├── getStoredLocations preserves data during migration (id, treeUri)
      └── getStoredLocations returns empty list for completely invalid JSON
```

**Key implementation notes**:
- Use `PreferencesDataStoreFactory.create` with a temp file for testing (standard DataStore testing pattern)
- Or use the approach from existing `SettingsRepositoryTest` if one exists (it doesn't currently — this is a new file)
- Follow JUnit 5 + `runTest` pattern consistent with the rest of the test suite

- [ ] **Action 1.6.2**: Run targeted tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepositoryImplTest"
```

---

## User Story 2: Update MCP Tool & File Operations

**As an MCP client**, I need `list_storage_locations` to return the new format (with `path`, `description`, no `authorized`) and file operations to work with the new storage model.

### Acceptance Criteria
- [ ] `list_storage_locations` returns JSON: `[{"id": "...", "name": "...", "path": "...", "description": "...", "available_bytes": ...}]`
- [ ] `list_storage_locations` tool description updated
- [ ] `FileOperationProviderImpl.checkAuthorization` error message updated (says "add it in the app settings" instead of "authorize")
- [ ] Integration test updated and passing
- [ ] Linting passes
- [ ] Targeted tests pass: `./gradlew :app:testDebugUnitTest --tests "*.FileToolsIntegrationTest"` and `./gradlew :app:testDebugUnitTest --tests "*.FileOperationProviderTest"`

---

### Task 2.1: Update `ListStorageLocationsHandler` in `FileTools.kt`

**Definition of Done**: MCP output matches new schema. No `authorized` or `provider` fields. `path` and `description` fields present.

- [x] **Action 2.1.1**: Update `ListStorageLocationsHandler.execute()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt`

**What changes** (lines 35-92): 
1. Change `storageLocationProvider.getAvailableLocations()` → `storageLocationProvider.getAllLocations()` (line 43)
2. Update the JSON output to remove `provider` and `authorized`, add `path` and `description`
3. Update the tool description string (lines 77-80) to remove mention of "authorization status" and "must be authorized"
4. Update the KDoc comment (lines 25-34)

**Current JSON output** (lines 48-58):
```kotlin
buildJsonObject {
    put("id", location.id)
    put("name", location.name)
    put("provider", location.providerName)
    put("authorized", location.isAuthorized)
    if (location.availableBytes != null) {
        put("available_bytes", location.availableBytes)
    } else {
        put("available_bytes", JsonNull)
    }
}
```

**New JSON output**:
```kotlin
buildJsonObject {
    put("id", location.id)
    put("name", location.name)
    put("path", location.path)
    put("description", location.description)
    if (location.availableBytes != null) {
        put("available_bytes", location.availableBytes)
    } else {
        put("available_bytes", JsonNull)
    }
}
```

**New tool description** (replacing lines 77-80):
```kotlin
description =
    "Lists storage locations that the user has added in the app. " +
        "Each location represents a directory the user granted access to. " +
        "Use the location ID from this list for all file operations.",
```

**New KDoc** (replacing lines 25-34):
```kotlin
/**
 * MCP tool handler for `list_storage_locations`.
 *
 * Lists user-added storage locations with their metadata.
 *
 * **Input**: `{}` (no parameters)
 * **Output**: `{ "content": [{ "type": "text", "text": "[{\"id\":\"...\", ...}]" }] }`
 * **Errors**:
 *   - ActionFailed if listing storage locations fails
 */
```

---

### Task 2.2: Update `FileOperationProviderImpl` error message

**Definition of Done**: Error message references "add" instead of "authorize".

- [x] **Action 2.2.1**: Update `checkAuthorization` error message

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt`

**What changes** (lines 440-447): Update the error message string.

**Current** (lines 442-444):
```kotlin
throw McpToolException.PermissionDenied(
    "Storage location '$locationId' is not authorized. " +
        "Please authorize it in the app settings.",
)
```

**New**:
```kotlin
throw McpToolException.PermissionDenied(
    "Storage location '$locationId' not found. " +
        "Please add it in the app settings.",
)
```

---

### Task 2.3: Update `FileToolsIntegrationTest`

**Definition of Done**: `list_storage_locations` test uses new `StorageLocation` data class and verifies new output format.

- [x] **Action 2.3.1**: Update `list_storage_locations returns available locations` test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`

**What changes** (lines 36-63): Update the mock to use `getAllLocations()` instead of `getAvailableLocations()`, update the `StorageLocation` constructor to match the new data class.

**Current** (lines 40-53):
```kotlin
coEvery { deps.storageLocationProvider.getAvailableLocations() } returns
    listOf(
        StorageLocation(
            id = "loc1",
            name = "Downloads",
            providerName = "com.android.providers.downloads",
            authority = "com.android.providers.downloads.documents",
            rootId = "downloads",
            rootDocumentId = "downloads",
            treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
            isAuthorized = true,
            availableBytes = 1024000L,
            iconUri = null,
        ),
    )
```

**New**:
```kotlin
coEvery { deps.storageLocationProvider.getAllLocations() } returns
    listOf(
        StorageLocation(
            id = "loc1",
            name = "Downloads",
            path = "/",
            description = "Downloaded files",
            treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
            availableBytes = 1024000L,
        ),
    )
```

Also add assertions for the new fields:
```kotlin
assertTrue(text.contains("Downloads"))
assertTrue(text.contains("Downloaded files"))
assertTrue(text.contains("path"))
assertFalse(text.contains("authorized"))
assertFalse(text.contains("provider"))
```

- [x] **Action 2.3.2**: Update other integration tests and mock helpers

1. Search all tests in `FileToolsIntegrationTest.kt` — the `list_files with unauthorized location` test (around line 103) and `read_file with unauthorized location` test (around line 251) use `McpToolException.PermissionDenied` with message "not authorized". Update the mock exception messages and assertions to match the new error text: "not found".

2. Verify `McpIntegrationTestHelper.createMockDependencies()` — it creates a `relaxed = true` mock for `storageLocationProvider`. Since it's relaxed, calls to the old `getAvailableLocations()` would return a default value without error. However, the method no longer exists in the interface after Task 1.3, so any test calling it would fail at compile time. No explicit change needed in the helper — the relaxed mock will automatically support the new `getAllLocations()` method. Verify no other integration test files call `getAvailableLocations()` on the mocked provider.

- [ ] **Action 2.3.3**: Run targeted tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.FileToolsIntegrationTest"
```

---

### Task 2.4: Update `FileOperationProviderTest`

**Definition of Done**: Tests referencing "not authorized" updated to "not found".

- [x] **Action 2.4.1**: Update authorization error message assertions in `FileOperationProviderTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderTest.kt`

**What changes**: Any test asserting on the string "not authorized" in error messages needs to assert "not found" instead. Specifically:
- Line 181: `assertTrue(exception.message!!.contains("not authorized"))` → `assertTrue(exception.message!!.contains("not found"))`
- Line 362: `assertTrue(exception.message!!.contains("not authorized"))` → `assertTrue(exception.message!!.contains("not found"))`

- [ ] **Action 2.4.2**: Run targeted tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProviderTest"
```

---

## User Story 3: Update UI for User-Managed Storage Locations

**As a user**, I need to add storage locations via a modal + SAF picker, edit their descriptions, and delete them with confirmation, so I can manage which directories the MCP server can access.

### Acceptance Criteria
- [ ] "Add Storage Location" button visible in the storage section (always, even when list is empty)
- [ ] Tapping "Add" opens a modal with: description text field, "Browse" button, Cancel/Add buttons
- [ ] "Browse" launches `ACTION_OPEN_DOCUMENT_TREE`, result appears in modal
- [ ] "Add" button disabled until a directory is picked
- [ ] Duplicate tree URIs are rejected with a message
- [ ] Each location row shows: name, path, description, edit and delete icons
- [ ] Edit taps open a modal with pre-filled description
- [ ] Delete taps show confirmation dialog
- [ ] Empty state shows "No storage locations added. Tap + to add one."
- [ ] `MainViewModel` has methods: `addLocation`, `removeLocation`, `updateLocationDescription`, `isDuplicateTreeUri`
- [ ] `MainViewModel` exposes `storageError: SharedFlow<String>` for error feedback to the UI
- [ ] `HomeScreen` collects `storageError` and shows `Snackbar` messages
- [ ] Storage location Add/Edit/Delete buttons remain enabled when MCP server is running (locations are looked up dynamically)
- [ ] Old discovery-related methods removed from `MainViewModel`
- [ ] Linting passes
- [ ] All tests pass: `./gradlew :app:testDebugUnitTest --tests "*.MainViewModelTest"`

---

### Task 3.1: Update `MainViewModel` storage location methods

**Definition of Done**: Discovery-related methods removed. New methods for add/remove/edit description. `refreshStorageLocations` updated to use `getAllLocations()`.

- [x] **Action 3.1.1**: Remove old methods and state, add new methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

**What changes**:

1. Remove `_pendingAuthorizationLocationId` and `pendingAuthorizationLocationId` (lines 103-104).
2. Update `refreshStorageLocations()` (lines 284-293): change `storageLocationProvider.getAvailableLocations()` → `storageLocationProvider.getAllLocations()`.
3. Remove `requestLocationAuthorization()` (lines 295-297).
4. Remove `onLocationAuthorized()` (lines 299-315).
5. Remove `onLocationAuthorizationCancelled()` (lines 317-319).
6. Remove `deauthorizeLocation()` (lines 321-331).
7. Remove `getInitialPickerUri()` (lines 414-423).
8. Add a `SharedFlow` for surfacing storage operation errors to the UI:

```kotlin
private val _storageError = MutableSharedFlow<String>(extraBufferCapacity = 1)
val storageError: SharedFlow<String> = _storageError.asSharedFlow()
```

Import: `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`.

9. Add new methods (note: `addLocation` and `removeLocation` emit to `_storageError` on failure):

```kotlin
@Suppress("TooGenericExceptionCaught")
fun addLocation(treeUri: Uri, description: String) {
    viewModelScope.launch(ioDispatcher) {
        try {
            storageLocationProvider.addLocation(treeUri, description)
            refreshStorageLocations()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add storage location", e)
            _storageError.tryEmit("Failed to add storage location: ${e.message}")
        }
    }
}

@Suppress("TooGenericExceptionCaught")
fun removeLocation(locationId: String) {
    viewModelScope.launch(ioDispatcher) {
        try {
            storageLocationProvider.removeLocation(locationId)
            refreshStorageLocations()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove storage location $locationId", e)
            _storageError.tryEmit("Failed to remove storage location: ${e.message}")
        }
    }
}

@Suppress("TooGenericExceptionCaught")
fun updateLocationDescription(locationId: String, description: String) {
    viewModelScope.launch(ioDispatcher) {
        try {
            storageLocationProvider.updateLocationDescription(locationId, description)
            refreshStorageLocations()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update description for $locationId", e)
            _storageError.tryEmit("Failed to update description: ${e.message}")
        }
    }
}

suspend fun isDuplicateTreeUri(treeUri: Uri): Boolean =
    storageLocationProvider.isDuplicateTreeUri(treeUri)
```

---

### Task 3.2: Update string resources

**Definition of Done**: All new strings added, outdated strings updated.

- [x] **Action 3.2.1**: Update `strings.xml`

**File**: `app/src/main/res/values/strings.xml`

**What changes**: Update existing storage strings and add new ones. Modify lines 92-98:

**Current** (lines 92-98):
```xml
<!-- Storage Locations -->
<string name="storage_locations_title">Storage Locations</string>
<string name="storage_locations_description">Authorize storage locations for file operations via MCP tools. Each location requires confirmation through the system file picker.</string>
<string name="storage_location_authorize">Authorize</string>
<string name="storage_location_authorized">Authorized</string>
<string name="storage_location_provider_label">Provider: %1$s</string>
<string name="storage_location_no_locations">No storage locations found on this device</string>
```

**New**:
```xml
<!-- Storage Locations -->
<string name="storage_locations_title">Storage Locations</string>
<string name="storage_locations_description">Add storage locations to enable file operations via MCP tools. Each location grants access to a directory you choose.</string>
<string name="storage_location_no_locations">No storage locations added. Tap + to add one.</string>
<string name="storage_location_path_label">Path: %1$s</string>
<string name="storage_location_add_button">Add Storage Location</string>
<string name="storage_location_add_dialog_title">Add Storage Location</string>
<string name="storage_location_add_dialog_description_label">Description (optional)</string>
<string name="storage_location_add_dialog_description_hint">e.g., Project files, Downloads</string>
<string name="storage_location_add_dialog_browse">Browse</string>
<string name="storage_location_add_dialog_selected">Selected: %1$s</string>
<string name="storage_location_add_dialog_no_selection">No directory selected</string>
<string name="storage_location_add_dialog_add">Add</string>
<string name="storage_location_add_dialog_cancel">Cancel</string>
<string name="storage_location_add_dialog_duplicate">This directory is already added.</string>
<string name="storage_location_description_counter">%1$d / %2$d</string>
<string name="storage_location_edit_dialog_title">Edit Description</string>
<string name="storage_location_edit_dialog_save">Save</string>
<string name="storage_location_delete_dialog_title">Remove Storage Location</string>
<string name="storage_location_delete_dialog_message">Remove \"%1$s\"? The MCP server will no longer be able to access files in this location.</string>
<string name="storage_location_delete_dialog_confirm">Remove</string>
<string name="storage_location_delete_dialog_cancel">Cancel</string>
```

Remove the now-unused strings:
- `storage_location_authorize`
- `storage_location_authorized`
- `storage_location_provider_label`

---

### Task 3.3: Update `StorageLocationsSection` composable

**Definition of Done**: Section shows "Add" button, location rows with edit/delete icons, new empty state. No toggle switches.

- [x] **Action 3.3.1**: Rewrite `StorageLocationsSection` composable

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/StorageLocationsSection.kt`

**What changes**: Replace the location list rendering and the `StorageLocationRow` composable. Remove toggle/switch-based UI. Add:
- "Add Storage Location" button (always visible, at the top of the location list area)
- Location rows showing: name, path, description, edit icon button, delete icon button
- Updated empty state text

**Parameter changes for `StorageLocationsSection`**:
- Remove: `onToggleLocation: (StorageLocation) -> Unit`
- Add: `onAddLocation: () -> Unit`, `onEditDescription: (StorageLocation) -> Unit`, `onDeleteLocation: (StorageLocation) -> Unit`

**Parameter changes for `StorageLocationRow`**:
- Remove: `onToggle: () -> Unit`
- Add: `onEdit: () -> Unit`, `onDelete: () -> Unit`

The row should show:
- Name (bodyLarge, bold)
- Path (bodySmall, onSurfaceVariant)
- Description if non-empty (bodySmall, onSurfaceVariant, italic)
- Row of icon buttons: Edit (pencil icon), Delete (trash icon)

Remove the green/red icon and Switch.

Update the `@Preview` function to match the new `StorageLocation` data class.

Update imports: add `Icons.Default.Edit`, `Icons.Default.Delete`, `Icons.Default.Add`, `IconButton`, `OutlinedButton`. Remove `Icons.Default.CheckCircle`, `Icons.Default.Error`, `Switch`. Remove the `AuthorizedColor` and `UnauthorizedColor` constants.

---

### Task 3.4: Update `HomeScreen` — storage interaction flow

**Definition of Done**: HomeScreen uses new modal-based add flow, passes edit/delete callbacks. Old discovery-toggle flow removed.

- [x] **Action 3.4.1**: Rewrite storage location interaction in `HomeScreen`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`

**What changes**:

1. Add state variables for the add modal, edit modal, and delete confirmation:
```kotlin
var showAddDialog by remember { mutableStateOf(false) }
var addDialogDescription by remember { mutableStateOf("") }
var addDialogSelectedUri by remember { mutableStateOf<Uri?>(null) }
var addDialogSelectedName by remember { mutableStateOf<String?>(null) }
var addDialogDuplicateError by remember { mutableStateOf(false) }

var showEditDialog by remember { mutableStateOf(false) }
var editDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }
var editDialogDescription by remember { mutableStateOf("") }

var showDeleteDialog by remember { mutableStateOf(false) }
var deleteDialogLocation by remember { mutableStateOf<StorageLocation?>(null) }
```

2. Change the `documentTreeLauncher` callback (lines 79-91) to update the add dialog state instead of calling `viewModel.onLocationAuthorized`:
```kotlin
val documentTreeLauncher =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            addDialogSelectedUri = uri
            val docFile = DocumentFile.fromTreeUri(context, uri)
            addDialogSelectedName = docFile?.name ?: uri.lastPathSegment ?: "Unknown"
            // Check for duplicates
            scope.launch {
                addDialogDuplicateError = viewModel.isDuplicateTreeUri(uri)
            }
        }
    }
```

Note: need `val scope = rememberCoroutineScope()` and import `DocumentFile`, `rememberCoroutineScope`, `mutableStateOf`, `Uri`.

3. Collect `storageError` from the ViewModel and display it in a `Snackbar` via `SnackbarHostState`:
```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(Unit) {
    viewModel.storageError.collect { message ->
        snackbarHostState.showSnackbar(message)
    }
}
```
Pass `snackbarHostState` to the `Scaffold`'s `snackbarHost` parameter:
```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    // ... existing parameters
)
```
Import: `androidx.compose.material3.SnackbarHost`, `androidx.compose.material3.SnackbarHostState`.

4. Update the `StorageLocationsSection` call (lines 159-181) to use new callbacks:
```kotlin
StorageLocationsSection(
    storageLocations = storageLocations,
    fileSizeLimitInput = fileSizeLimitInput,
    fileSizeLimitError = fileSizeLimitError,
    downloadTimeoutInput = downloadTimeoutInput,
    downloadTimeoutError = downloadTimeoutError,
    allowHttpDownloads = serverConfig.allowHttpDownloads,
    allowUnverifiedHttpsCerts = serverConfig.allowUnverifiedHttpsCerts,
    isServerRunning = isServerRunning,
    onAddLocation = {
        addDialogDescription = ""
        addDialogSelectedUri = null
        addDialogSelectedName = null
        addDialogDuplicateError = false
        showAddDialog = true
    },
    onEditDescription = { location ->
        editDialogLocation = location
        editDialogDescription = location.description
        showEditDialog = true
    },
    onDeleteLocation = { location ->
        deleteDialogLocation = location
        showDeleteDialog = true
    },
    onFileSizeLimitChange = viewModel::updateFileSizeLimit,
    onDownloadTimeoutChange = viewModel::updateDownloadTimeout,
    onAllowHttpDownloadsChange = viewModel::updateAllowHttpDownloads,
    onAllowUnverifiedHttpsCertsChange = viewModel::updateAllowUnverifiedHttpsCerts,
)
```

5. Add the three dialogs **outside** the `Scaffold`'s `content` lambda, at the top level of the `HomeScreen` composable (dialogs are overlays and must not be placed inside the `Column` or `Scaffold` content):

**Add Dialog**: `AlertDialog` (shown when `showAddDialog` is true) with description `OutlinedTextField` (with character counter, max 500 chars), Browse `OutlinedButton`, display of selected directory name, duplicate error text, Cancel and Add buttons. Add button disabled when `addDialogSelectedUri` is null or `addDialogDuplicateError` is true.

**Edit Dialog**: `AlertDialog` (shown when `showEditDialog` is true) with description `OutlinedTextField` pre-filled (with character counter, max 500 chars), Cancel and Save buttons.

**Delete Dialog**: `AlertDialog` (shown when `showDeleteDialog` is true) with confirmation message including location name, Cancel and Remove buttons.

**`isServerRunning` interaction**: The Add, Edit, and Delete buttons for storage locations remain **enabled** regardless of whether the MCP server is running. Unlike server configuration settings (port, binding address, etc.) that require a restart to take effect, storage locations are looked up dynamically on each MCP tool call, so changes take effect immediately. This is consistent with how `isServerRunning` currently only disables configuration fields in the UI, not the storage section.

---

### Task 3.5: Update `MainViewModelTest` — storage location tests

**Definition of Done**: Old tests for `requestLocationAuthorization`, `onLocationAuthorized`, `onLocationAuthorizationCancelled`, `deauthorizeLocation` removed. New tests for `addLocation`, `removeLocation`, `updateLocationDescription`, `isDuplicateTreeUri` added. All pass.

- [x] **Action 3.5.1**: Rewrite storage location tests in `MainViewModelTest.kt`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`

**What changes**: Replace the storage location test block (approximately lines 422-500). Remove tests for old methods. Add tests for:
- `refreshStorageLocations updates storageLocations state` — uses `getAllLocations()` instead of `getAvailableLocations()`
- `addLocation calls provider and refreshes` — verifies `storageLocationProvider.addLocation()` called, list refreshed
- `addLocation emits storageError on failure` — mock `addLocation` to throw, verify `storageError` emits the error message
- `removeLocation calls provider and refreshes` — verifies `storageLocationProvider.removeLocation()` called, list refreshed
- `removeLocation emits storageError on failure` — mock `removeLocation` to throw, verify `storageError` emits
- `updateLocationDescription calls provider and refreshes` — verifies delegation
- `updateLocationDescription emits storageError on failure` — mock `updateLocationDescription` to throw, verify `storageError` emits
- `isDuplicateTreeUri delegates to provider` — verifies return value

Update `StorageLocation` constructor calls in the test to match the new data class (remove `providerName`, `authority`, `rootId`, `rootDocumentId`, `isAuthorized`, `iconUri`; add `path`, `description`; change `treeUri` from `String?` to `String`).

Remove the `pendingAuthorizationLocationId` import/usage if it was referenced.

- [ ] **Action 3.5.2**: Run targeted tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModelTest"
```

---

## User Story 4: Update Documentation

**As a developer/user**, I need the documentation to accurately reflect the new storage location model.

### Acceptance Criteria
- [ ] `docs/MCP_TOOLS.md` `list_storage_locations` section updated with new output format
- [ ] `docs/PROJECT.md` SAF section updated, file tools section updated
- [ ] Linting passes
- [ ] All tests pass: `./gradlew :app:test`

---

### Task 4.1: Update `docs/MCP_TOOLS.md`

**Definition of Done**: `list_storage_locations` section reflects new output format.

- [ ] **Action 4.1.1**: Update `list_storage_locations` section

**File**: `docs/MCP_TOOLS.md`

**What changes** (lines 1643-1688): Update tool description, example response, remove mention of "authorization status".

**New tool description** (line 1645):
```
Lists user-added storage locations with their metadata. Each location represents a directory the user granted access to via the app settings. Use the location ID from this list for all file operations.
```

**New example response** (replacing lines 1670-1683):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "[{\"id\":\"com.android.externalstorage.documents/primary:\",\"name\":\"Internal Storage\",\"path\":\"/\",\"description\":\"Main device storage\",\"available_bytes\":52428800000}]"
      }
    ]
  }
}
```

Also update the error messages section for all file tools (lines 1740-2071): change "Storage location not authorized" to "Storage location not found" in all error case descriptions.

---

### Task 4.2: Update `docs/PROJECT.md`

**Definition of Done**: SAF section and file tools section reflect user-managed model.

- [ ] **Action 4.2.1**: Update SAF authorization section

**File**: `docs/PROJECT.md`

**What changes** (lines 325-341): Replace the entire "Storage Access Framework (SAF) Authorization" section.

**New content**:
```markdown
### Storage Access Framework (SAF) — User-Managed Locations

The application uses Android's Storage Access Framework (SAF) for unified, secure access to both physical and virtual storage (device storage, SD cards, Google Drive, Dropbox, etc.).

**User-managed model**: Storage locations are entirely user-driven — no automatic discovery.

**Add flow**:
1. The user taps "Add Storage Location" in the UI
2. A dialog appears with a description field and a "Browse" button
3. Tapping "Browse" launches the system file picker via `ACTION_OPEN_DOCUMENT_TREE`
4. The user selects a directory (mandatory Android security requirement — apps cannot self-grant storage access)
5. The granted tree URI is persisted via `ContentResolver.takePersistableUriPermission()` (read + write)
6. The location metadata (ID, name, path, description, tree URI) is stored in DataStore (via `SettingsRepository`)

**Location ID format**: `{authority}/{treeDocumentId}` derived from the tree URI (e.g., `com.android.externalstorage.documents/primary:Documents`). This provides a stable, cross-session identifier.

**Edit**: Users can update the description of any location via the UI.

**Delete**: Users can remove a location. The persistent URI permission is released via `ContentResolver.releasePersistableUriPermission()` and the entry is removed from DataStore. A confirmation dialog is shown before deletion.

**Duplicate prevention**: The app prevents adding the same tree URI twice (both in the UI and as a defense-in-depth check inside the provider).

**Description**: Each location has an optional user-provided description (max 500 characters) to give context/hints to MCP clients.

**Known constraint — Android URI permission limit**: Android enforces a limit on the number of persistable URI permissions per app (typically 128-512 depending on OEM/version). Once the limit is reached, oldest permissions may be silently evicted. In practice, users will have far fewer storage locations than this limit, but it is a known platform constraint.
```

- [ ] **Action 4.2.2**: Update file tools section

**File**: `docs/PROJECT.md`

**What changes** (lines 267-286): Update the table and descriptions. Remove "authorization" references, update `list_storage_locations` description.

**New table row for `list_storage_locations`**:
```
| `android_list_storage_locations` | List user-added storage locations with metadata | — | — |
```

**Update line 280**:
```
**Virtual path system**: All file tools use virtual paths: `{location_id}/{relative_path}` where `location_id` is `{authority}/{treeDocumentId}`. The location must be added by the user via the UI before file operations can be performed.
```

**Update line 286**:
```
**Errors**: Returns `CallToolResult(isError = true)` if location not found, file not found, file exceeds size limit, or operation fails. ...
```

---

## User Story 5: Full Verification

**As a developer**, I need to verify the entire implementation is correct, consistent, and passing all quality gates.

### Acceptance Criteria
- [ ] Full lint passes: `make lint`
- [ ] Full build passes: `./gradlew build`
- [ ] Full test suite passes (unit + integration): `make test`
- [ ] Code review: every changed file reviewed for consistency with the plan
- [ ] No TODOs, no commented-out dead code, no stubs
- [ ] All documentation is consistent with the implementation

---

### Task 5.1: Run full quality gates

- [ ] **Action 5.1.1**: Run linting
```bash
make lint
```

- [ ] **Action 5.1.2**: Run full test suite
```bash
make test
```

- [ ] **Action 5.1.3**: Run build
```bash
./gradlew build
```

---

### Task 5.2: Full implementation review from the ground up

- [ ] **Action 5.2.1**: Review every changed file against this plan

Systematically verify each file changed in the plan:
1. `StorageLocation.kt` — all fields match plan, no old fields remain
2. `SettingsRepository.kt` — `StoredLocation` data class present, all new methods present, old methods removed
3. `SettingsRepositoryImpl.kt` — serialization uses JSON array format, migration handles old format, `updateLocationDescription` works
4. `StorageLocationProvider.kt` — all methods match plan, no discovery methods
5. `StorageLocationProviderImpl.kt` — no `queryIntentContentProviders`, no cursor/roots queries in main flow, `addLocation` correctly derives ID/name/path, `queryAvailableBytes` works as best-effort
6. `FileTools.kt` — `ListStorageLocationsHandler` output matches plan (id, name, path, description, available_bytes), tool description updated
7. `FileOperationProviderImpl.kt` — error message says "not found" not "not authorized"
8. `MainViewModel.kt` — old methods removed, new methods present, `refreshStorageLocations` uses `getAllLocations()`
9. `StorageLocationsSection.kt` — Add button, edit/delete actions, no toggles/switches for locations
10. `HomeScreen.kt` — Add/Edit/Delete dialogs present, SAF picker integrated into add flow
11. `strings.xml` — all new strings present, unused strings removed
12. All test files — tests match new interfaces, all pass
13. `docs/MCP_TOOLS.md` — `list_storage_locations` section matches new output
14. `docs/PROJECT.md` — SAF section matches new model

- [ ] **Action 5.2.2**: Verify no references to old methods/fields remain in the codebase

Search for stale references:
- `getAvailableLocations` — should NOT appear anywhere except possibly in comments/docs
- `getAuthorizedLocations` (on `StorageLocationProvider`) — should NOT appear
- `authorizeLocation` — should NOT appear
- `deauthorizeLocation` — should NOT appear
- `isAuthorized` (on `StorageLocation`) — should NOT appear
- `providerName` (on `StorageLocation`) — should NOT appear
- `rootId` (on `StorageLocation`) — should NOT appear
- `rootDocumentId` (on `StorageLocation`) — should NOT appear
- `iconUri` (on `StorageLocation`) — should NOT appear
- `requestLocationAuthorization` — should NOT appear
- `onLocationAuthorized` — should NOT appear
- `onLocationAuthorizationCancelled` — should NOT appear
- `pendingAuthorizationLocationId` — should NOT appear
- `getInitialPickerUri` — should NOT appear
- `queryIntentContentProviders` — should NOT appear
