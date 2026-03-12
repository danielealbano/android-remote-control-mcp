<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 41: Built-in MediaStore Storage Locations

**Branch**: `feat/builtin-mediastore-storage-locations`
**PR Title**: feat: add built-in MediaStore storage locations with zero-config access
**Created**: 2026-03-12T12:53:48

## Overview

Add 4 built-in storage locations backed by Android MediaStore (no SAF picker needed). These complement existing SAF locations. The MCP client sees them as regular storage locations — the backend is not exposed.

| ID | Display Name | MediaStore Collection | Base Relative Path | READ_MEDIA_* Permission |
|---|---|---|---|---|
| `builtin:downloads` | Downloads - Only owned files | `MediaStore.Downloads.EXTERNAL_CONTENT_URI` | `Download/` | None (always owned-only) |
| `builtin:pictures` | Pictures - Only owned files | `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` | `Pictures/` | `READ_MEDIA_IMAGES` |
| `builtin:movies` | Movies - Only owned files | `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` | `Movies/` | `READ_MEDIA_VIDEO` |
| `builtin:music` | Music - Only owned files | `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` | `Music/` | `READ_MEDIA_AUDIO` |

**Performance note**: `getAllLocations()` reads built-in permission overrides from DataStore and checks `READ_MEDIA_*` permissions on every call. This is acceptable — DataStore caches preferences in-memory after the first read, and `ContextCompat.checkSelfPermission()` is O(1). No caching layer is needed. If profiling ever shows this as a bottleneck, caching can be added as a future improvement.

**Linting suppression convention**: `MediaStoreFileOperationsImpl` uses `@Suppress` annotations identical to those in the existing `FileOperationProviderImpl` (lines 1, 32, 94, 308, etc.). These are an established project pattern:
- `TooManyFunctions`: unavoidable — the class implements all 8 file operations + private helpers.
- `TooGenericExceptionCaught`, `SwallowedException`: required for catch-all error handling that converts exceptions to `McpToolException`.
- `LongMethod`, `CyclomaticComplexMethod`, `NestedBlockDepth`: `downloadFromUrl` has inherent complexity (URL validation → connection → streaming → size checking → IS_PENDING lifecycle) that cannot be decomposed without hurting readability.
- `ThrowsCount`: multiple validation steps each have distinct throw sites.
- `CustomX509TrustManager`, `TrustAllX509TrustManager`, `EmptyFunctionBlock`: genuinely unavoidable — the trust-all SSL pattern requires empty `checkClientTrusted`/`checkServerTrusted` implementations by design. This is a user-opt-in feature (`allowUnverifiedHttpsCerts`).

---

## User Story 1: Data Model & Enum Foundation

### Acceptance Criteria
- [x] `StorageBackend` enum exists with `SAF` and `MEDIA_STORE` values
- [x] `BuiltinStorageLocation` enum defines all 4 built-in locations with metadata
- [x] `BuiltinStorageLocation` companion includes path validation helper (rejects `..` segments, absolute paths)
- [x] `StorageLocation` has `backend` and `isBuiltin` fields
- [x] `BuiltinPermissions` data class exists in `data/model/`
- [x] `PermissionChecker` interface exists for abstracting runtime permission checks
- [x] Existing SAF code is unaffected (default values preserve current behavior)

### Task 1.1: Create StorageBackend enum

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageBackend.kt` — **create**

```kotlin
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
```

**Definition of Done**:
- [x] File created with enum

### Task 1.2: Create BuiltinStorageLocation enum

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocation.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

import android.net.Uri
import android.provider.MediaStore
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException

/**
 * Defines the built-in storage locations backed by MediaStore.
 *
 * These are always available without user setup (no SAF picker needed).
 * The app can write to these locations without any runtime permissions.
 * Reading non-owned files requires the corresponding [readMediaPermission].
 *
 * @property locationId Stable identifier used in MCP tool calls.
 * @property displayNameOwned Display name when only owned files are visible.
 * @property displayNameAll Display name when all files are visible (READ_MEDIA_* granted).
 * @property collectionUri MediaStore collection content URI for queries/inserts.
 * @property baseRelativePath The MediaStore RELATIVE_PATH prefix (e.g., "Download/").
 * @property readMediaPermission Runtime permission for "all files" access, or null if unavailable.
 */
enum class BuiltinStorageLocation(
    val locationId: String,
    val displayNameOwned: String,
    val displayNameAll: String,
    val collectionUri: Uri,
    val baseRelativePath: String,
    val readMediaPermission: String?,
) {
    DOWNLOADS(
        locationId = "builtin:downloads",
        displayNameOwned = "Downloads - Only owned files",
        displayNameAll = "Downloads - Only owned files",
        collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Download/",
        readMediaPermission = null,
    ),
    PICTURES(
        locationId = "builtin:pictures",
        displayNameOwned = "Pictures - Only owned files",
        displayNameAll = "Pictures - All files",
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Pictures/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_IMAGES,
    ),
    MOVIES(
        locationId = "builtin:movies",
        displayNameOwned = "Movies - Only owned files",
        displayNameAll = "Movies - All files",
        collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Movies/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_VIDEO,
    ),
    MUSIC(
        locationId = "builtin:music",
        displayNameOwned = "Music - Only owned files",
        displayNameAll = "Music - All files",
        collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        baseRelativePath = "Music/",
        readMediaPermission = android.Manifest.permission.READ_MEDIA_AUDIO,
    ),
    ;

    companion object {
        /** Prefix for all built-in location IDs. */
        const val ID_PREFIX = "builtin:"

        /** Returns the [BuiltinStorageLocation] for a given location ID, or null. */
        fun fromLocationId(locationId: String): BuiltinStorageLocation? =
            entries.find { it.locationId == locationId }

        /** Returns true if the given location ID is a built-in location. */
        fun isBuiltinId(locationId: String): Boolean =
            locationId.startsWith(ID_PREFIX)

        /**
         * Validates a relative path for use with built-in locations.
         * Rejects path traversal attempts (`..`), absolute paths, and control characters.
         *
         * @throws McpToolException.InvalidParams if the path is invalid.
         */
        fun validatePath(path: String) {
            if (path.startsWith("/")) {
                throw McpToolException.InvalidParams(
                    "Path must be relative, not absolute",
                )
            }
            val segments = path.split("/").filter { it.isNotEmpty() }
            for (segment in segments) {
                if (segment == "..") {
                    throw McpToolException.InvalidParams(
                        "Path must not contain '..' segments",
                    )
                }
                if (segment == ".") {
                    throw McpToolException.InvalidParams(
                        "Path must not contain '.' segments",
                    )
                }
                if (CONTROL_CHAR_REGEX.containsMatchIn(segment)) {
                    throw McpToolException.InvalidParams(
                        "Path must not contain control characters",
                    )
                }
            }
        }

        private val CONTROL_CHAR_REGEX = Regex("[\\p{Cntrl}]")
    }
}
```

**Definition of Done**:
- [x] File created with all 4 entries and companion helpers
- [x] `validatePath()` rejects `..`, `.`, absolute paths, control characters

### Task 1.3: Create BuiltinPermissions data class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinPermissions.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Permission overrides for a built-in storage location.
 * Persisted in DataStore as JSON. Missing entries default to read-only.
 */
data class BuiltinPermissions(
    val allowWrite: Boolean = false,
    val allowDelete: Boolean = false,
)
```

**Definition of Done**:
- [x] File created in `data/model/`

### Task 1.4: Create PermissionChecker interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/PermissionChecker.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

/**
 * Abstracts Android runtime permission checks for testability.
 */
interface PermissionChecker {
    /** Returns true if the given runtime permission is granted. */
    fun hasPermission(permission: String): Boolean
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/PermissionCheckerImpl.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermissionCheckerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PermissionChecker {
        override fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` — **modify**

Add binding in `ServiceModule`:
```kotlin
@Binds
@Singleton
abstract fun bindPermissionChecker(impl: PermissionCheckerImpl): PermissionChecker
```

**Definition of Done**:
- [x] Interface and impl created
- [x] DI binding added

### Task 1.5: Add backend and isBuiltin fields to StorageLocation

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageLocation.kt` — **modify**

```kotlin
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
```

Update KDoc to document `backend` and `isBuiltin`.

**Definition of Done**:
- [x] Fields added with defaults preserving SAF behavior
- [x] KDoc updated

### Task 1.6: Add built-in permission persistence to SettingsRepository

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` — **modify**

Add 3 methods (import `BuiltinPermissions` from `data.model`):

```kotlin
/** Returns permission overrides for all built-in locations. */
suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions>

/** Updates the allowWrite flag for a built-in location. */
suspend fun updateBuiltinLocationAllowWrite(locationId: String, allowWrite: Boolean)

/** Updates the allowDelete flag for a built-in location. */
suspend fun updateBuiltinLocationAllowDelete(locationId: String, allowDelete: Boolean)
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt` — **modify**

Add import and DataStore key:
```kotlin
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions

private val BUILTIN_LOCATION_PERMISSIONS_KEY = stringPreferencesKey("builtin_location_permissions")
```

Implement using `buildJsonObject` / `Json.parseToJsonElement` (matching the existing `serializeStoredLocationsJson` pattern):

```kotlin
override suspend fun getBuiltinLocationPermissions(): Map<String, BuiltinPermissions> {
    val prefs = dataStore.data.first()
    val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
    return try {
        val root = Json.parseToJsonElement(json).jsonObject
        root.entries.associate { (key, value) ->
            val obj = value.jsonObject
            key to BuiltinPermissions(
                allowWrite = obj["allowWrite"]?.jsonPrimitive?.booleanOrNull ?: false,
                allowDelete = obj["allowDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse builtin location permissions JSON", e)
        emptyMap()
    }
}

override suspend fun updateBuiltinLocationAllowWrite(locationId: String, allowWrite: Boolean) {
    dataStore.edit { prefs ->
        val current = getBuiltinLocationPermissionsInternal(prefs)
        val existing = current[locationId] ?: BuiltinPermissions()
        val updated = current + (locationId to existing.copy(allowWrite = allowWrite))
        prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
    }
}

override suspend fun updateBuiltinLocationAllowDelete(locationId: String, allowDelete: Boolean) {
    dataStore.edit { prefs ->
        val current = getBuiltinLocationPermissionsInternal(prefs)
        val existing = current[locationId] ?: BuiltinPermissions()
        val updated = current + (locationId to existing.copy(allowDelete = allowDelete))
        prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] = serializeBuiltinPermissions(updated)
    }
}

private fun getBuiltinLocationPermissionsInternal(prefs: Preferences): Map<String, BuiltinPermissions> {
    val json = prefs[BUILTIN_LOCATION_PERMISSIONS_KEY] ?: return emptyMap()
    return try {
        val root = Json.parseToJsonElement(json).jsonObject
        root.entries.associate { (key, value) ->
            val obj = value.jsonObject
            key to BuiltinPermissions(
                allowWrite = obj["allowWrite"]?.jsonPrimitive?.booleanOrNull ?: false,
                allowDelete = obj["allowDelete"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

private fun serializeBuiltinPermissions(perms: Map<String, BuiltinPermissions>): String =
    Json.encodeToString(
        buildJsonObject {
            for ((key, value) in perms) {
                put(
                    key,
                    buildJsonObject {
                        put("allowWrite", value.allowWrite)
                        put("allowDelete", value.allowDelete)
                    },
                )
            }
        },
    )
```

**Definition of Done**:
- [x] 3 new methods on interface and impl
- [x] `BUILTIN_LOCATION_PERMISSIONS_KEY` added
- [x] Uses `buildJsonObject`/`Json.parseToJsonElement` (matches existing serialization pattern)
- [x] Graceful handling of missing/malformed JSON

---

## User Story 2: StorageLocationProvider Built-in Integration

### Acceptance Criteria
- [x] `getAllLocations()` returns built-in locations (first) followed by SAF locations
- [x] `isLocationAuthorized()` returns true for valid `builtin:` IDs
- [x] `isWriteAllowed()` / `isDeleteAllowed()` check built-in permission overrides
- [x] `updateLocationAllowWrite()` / `updateLocationAllowDelete()` route to built-in persistence for `builtin:` IDs
- [x] `getLocationById()` returns built-in locations
- [x] Built-in name is dynamic: "Only owned files" / "All files" based on runtime permission
- [x] `isAllFilesMode()` added to interface and implemented

### Task 2.1: Update StorageLocationProvider interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProvider.kt` — **modify**

Add to interface:
```kotlin
/**
 * Checks whether the given location is in "all files" mode (has READ_MEDIA_* permission).
 * Only applicable to built-in MediaStore locations that have an optional read permission.
 * Returns false for SAF locations and for built-in locations without "all files" support.
 */
suspend fun isAllFilesMode(locationId: String): Boolean
```

**Definition of Done**:
- [x] Method added to interface

### Task 2.2: Update StorageLocationProviderImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt` — **modify**

1. Add imports:
   ```kotlin
   import android.os.Environment
   import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinPermissions
   import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
   import com.danielealbano.androidremotecontrolmcp.data.model.StorageBackend
   ```

2. Add `PermissionChecker` as constructor dependency:
   ```kotlin
   class StorageLocationProviderImpl
       @Inject
       constructor(
           @param:ApplicationContext private val context: Context,
           private val settingsRepository: SettingsRepository,
           private val permissionChecker: PermissionChecker,
       ) : StorageLocationProvider {
   ```

3. Add private `buildBuiltinLocations()`:

   ```kotlin
   private suspend fun buildBuiltinLocations(): List<StorageLocation> {
       val permOverrides = settingsRepository.getBuiltinLocationPermissions()
       val availableBytes = querySharedStorageAvailableBytes()
       return BuiltinStorageLocation.entries.map { entry ->
           val perms = permOverrides[entry.locationId] ?: BuiltinPermissions()
           val allFilesMode = entry.readMediaPermission != null &&
               permissionChecker.hasPermission(entry.readMediaPermission)
           val displayName = if (allFilesMode) entry.displayNameAll else entry.displayNameOwned
           StorageLocation(
               id = entry.locationId,
               name = displayName,
               path = "/${entry.baseRelativePath.trimEnd('/')}",
               description = "",
               treeUri = "",
               availableBytes = availableBytes,
               allowWrite = perms.allowWrite,
               allowDelete = perms.allowDelete,
               backend = StorageBackend.MEDIA_STORE,
               isBuiltin = true,
           )
       }
   }

   @Suppress("TooGenericExceptionCaught")
   private fun querySharedStorageAvailableBytes(): Long? =
       try {
           val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
           stat.availableBytes
       } catch (e: Exception) {
           Log.w(TAG, "Failed to query shared storage available bytes", e)
           null
       }
   ```

4. Modify `getAllLocations()`:
   ```kotlin
   override suspend fun getAllLocations(): List<StorageLocation> {
       val builtins = buildBuiltinLocations()
       val stored = settingsRepository.getStoredLocations()
       val safLocations = stored.map { loc ->
           StorageLocation(
               id = loc.id, name = loc.name, path = loc.path,
               description = loc.description, treeUri = loc.treeUri,
               availableBytes = queryAvailableBytes(loc.treeUri),
               allowWrite = loc.allowWrite, allowDelete = loc.allowDelete,
           )
       }
       return builtins + safLocations
   }
   ```

5. Route all methods for `builtin:` IDs (add at top of each existing method):
   ```kotlin
   override suspend fun isLocationAuthorized(locationId: String): Boolean {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           return BuiltinStorageLocation.fromLocationId(locationId) != null
       }
       return settingsRepository.getStoredLocations().any { it.id == locationId }
   }

   override suspend fun getTreeUriForLocation(locationId: String): Uri? {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) return null
       // ... existing SAF logic
   }

   override suspend fun getLocationById(locationId: String): StorageLocation? {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           return buildBuiltinLocations().find { it.id == locationId }
       }
       // ... existing SAF logic
   }

   override suspend fun isWriteAllowed(locationId: String): Boolean {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           return settingsRepository.getBuiltinLocationPermissions()[locationId]?.allowWrite ?: false
       }
       return settingsRepository.getStoredLocations().find { it.id == locationId }?.allowWrite ?: false
   }

   override suspend fun isDeleteAllowed(locationId: String): Boolean {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           return settingsRepository.getBuiltinLocationPermissions()[locationId]?.allowDelete ?: false
       }
       return settingsRepository.getStoredLocations().find { it.id == locationId }?.allowDelete ?: false
   }

   override suspend fun updateLocationAllowWrite(locationId: String, allowWrite: Boolean) {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           settingsRepository.updateBuiltinLocationAllowWrite(locationId, allowWrite)
           Log.i(TAG, "Updated allowWrite=$allowWrite for builtin: ${sanitizeLocationId(locationId)}")
           return
       }
       // ... existing SAF logic
   }

   override suspend fun updateLocationAllowDelete(locationId: String, allowDelete: Boolean) {
       if (BuiltinStorageLocation.isBuiltinId(locationId)) {
           settingsRepository.updateBuiltinLocationAllowDelete(locationId, allowDelete)
           Log.i(TAG, "Updated allowDelete=$allowDelete for builtin: ${sanitizeLocationId(locationId)}")
           return
       }
       // ... existing SAF logic
   }
   ```

6. Implement `isAllFilesMode()`:
   ```kotlin
   override suspend fun isAllFilesMode(locationId: String): Boolean {
       val builtin = BuiltinStorageLocation.fromLocationId(locationId) ?: return false
       val permission = builtin.readMediaPermission ?: return false
       return permissionChecker.hasPermission(permission)
   }
   ```

**Definition of Done**:
- [x] `PermissionChecker` injected (no direct `ContextCompat` calls)
- [x] `getAllLocations()` returns built-ins + SAF
- [x] All routing methods handle `builtin:` IDs
- [x] `isAllFilesMode()` implemented
- [x] No changes to SAF behavior

---

## User Story 3: MediaStore File Operations

### Acceptance Criteria
- [x] `MediaStoreFileOperations` interface and implementation created
- [x] `FileOperationProviderImpl` routes to MediaStore for `builtin:` locations
- [x] Path traversal protection on all operations (`..`, `.`, absolute paths, control chars)
- [x] All operations use `withContext(Dispatchers.IO)` for ContentResolver I/O
- [x] `listFiles` synthesizes directory structure from `RELATIVE_PATH`
- [x] `listFiles` respects "owned only" vs "all files" mode
- [x] `downloadFromUrl` uses `IS_PENDING` pattern
- [x] Subdirectory paths supported (e.g., `subdir/file.txt` → `RELATIVE_PATH = "Download/subdir/"`)

### Task 3.1: Create MediaStoreFileOperations interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperations.kt` — **create**

```kotlin
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
```

**Definition of Done**:
- [x] Interface created with all 8 methods

### Task 3.2: Create MediaStoreFileOperationsImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsImpl.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "SwallowedException")
class MediaStoreFileOperationsImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storageLocationProvider: StorageLocationProvider,
        private val settingsRepository: SettingsRepository,
    ) : MediaStoreFileOperations {

    // ─── listFiles ──────────────────────────────────────────────────────

    override suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        val targetRelativePath = buildRelativePathForDir(builtin, path)
        val isAllFiles = storageLocationProvider.isAllFilesMode(locationId)
        val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_LIST_ENTRIES)

        // Query files in the target directory and children (for directory synthesis)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        val selection = buildListSelection(targetRelativePath, isAllFiles)
        val selectionArgs = buildListSelectionArgs(targetRelativePath, isAllFiles)

        val entries = mutableListOf<FileInfo>()
        val seenDirs = mutableSetOf<String>()

        context.contentResolver.query(
            builtin.collectionUri, projection, selection, selectionArgs, null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val relPathIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val relPath = cursor.getString(relPathIdx) ?: continue
                val displayName = cursor.getString(nameIdx) ?: continue

                if (relPath == targetRelativePath) {
                    // Direct child file
                    val childRelPath = if (path.isEmpty()) displayName else "$path/$displayName"
                    entries.add(
                        FileInfo(
                            name = displayName,
                            path = "$locationId/$childRelPath",
                            isDirectory = false,
                            size = cursor.getLong(sizeIdx),
                            lastModified = cursor.getLong(dateIdx).takeIf { it > 0L }
                                ?.let { it * MILLIS_PER_SECOND },
                            mimeType = cursor.getString(mimeIdx),
                        ),
                    )
                } else if (relPath.length > targetRelativePath.length &&
                    relPath.startsWith(targetRelativePath)
                ) {
                    // Deeper child → synthesize directory
                    val remainder = relPath.removePrefix(targetRelativePath)
                    val dirName = remainder.split("/").firstOrNull { it.isNotEmpty() }
                    if (dirName != null && seenDirs.add(dirName)) {
                        val dirRelPath = if (path.isEmpty()) dirName else "$path/$dirName"
                        entries.add(
                            FileInfo(
                                name = dirName,
                                path = "$locationId/$dirRelPath",
                                isDirectory = true,
                                size = 0L,
                                lastModified = null,
                                mimeType = null,
                            ),
                        )
                    }
                }
            }
        }

        // Sort: directories first, then by name
        val sorted = entries.sortedWith(
            compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name },
        )
        val totalCount = sorted.size
        val paginated = sorted.drop(offset).take(cappedLimit)
        val hasMore = offset + cappedLimit < totalCount

        FileListResult(files = paginated, totalCount = totalCount, hasMore = hasMore)
    }

    private fun buildListSelection(targetRelativePath: String, isAllFiles: Boolean): String {
        val pathFilter = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        return if (isAllFiles) pathFilter else "$pathFilter AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    }

    private fun buildListSelectionArgs(targetRelativePath: String, isAllFiles: Boolean): Array<String> {
        // LIKE pattern: match exact dir and all children
        val pattern = "$targetRelativePath%"
        return if (isAllFiles) arrayOf(pattern) else arrayOf(pattern, context.packageName)
    }

    // ─── readFile ───────────────────────────────────────────────────────

    @Suppress("NestedBlockDepth")
    override suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult = withContext(Dispatchers.IO) {
        if (offset < 1) {
            throw McpToolException.InvalidParams("offset must be >= 1, got $offset")
        }
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)

        val uri = findFileOrThrow(locationId, builtin, path)
        checkFileSizeByUri(uri)

        val cappedLimit = limit.coerceAtMost(FileOperationProvider.MAX_READ_LINES)
        val bufferedLines = mutableListOf<String>()
        var totalLines = 0

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var lineNumber = 1
                var line: String? = reader.readLine()
                while (line != null) {
                    totalLines = lineNumber
                    if (lineNumber >= offset && bufferedLines.size < cappedLimit) {
                        bufferedLines.add(line)
                    }
                    lineNumber++
                    line = reader.readLine()
                }
            }
        } ?: throw McpToolException.ActionFailed("Failed to open file for reading: $path")

        val endLine = if (bufferedLines.isEmpty()) offset else offset + bufferedLines.size - 1

        FileReadResult(
            content = bufferedLines.joinToString("\n"),
            totalLines = totalLines,
            hasMore = endLine < totalLines,
            startLine = offset,
            endLine = endLine,
        )
    }

    // ─── writeFile ──────────────────────────────────────────────────────

    override suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkWritePermission(locationId)

        val config = settingsRepository.getServerConfig()
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        if (contentBytes.size.toLong() > limitBytes) {
            throw McpToolException.ActionFailed(
                "Content size exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.",
            )
        }

        val relativePath = buildRelativePathForDir(builtin, path)
        val displayName = extractDisplayName(path)
        val existingUri = findOwnedFile(builtin, relativePath, displayName)

        if (existingUri != null) {
            context.contentResolver.openOutputStream(existingUri, "wt")?.use { it.write(contentBytes) }
                ?: throw McpToolException.ActionFailed("Failed to open file for writing: $path")
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeUtils.guessMimeType(displayName))
            }
            val insertUri = context.contentResolver.insert(builtin.collectionUri, values)
                ?: throw McpToolException.ActionFailed("Failed to create file: $path")
            context.contentResolver.openOutputStream(insertUri, "wt")?.use { it.write(contentBytes) }
                ?: throw McpToolException.ActionFailed("Failed to write to new file: $path")
        }

        Log.d(TAG, "Wrote ${contentBytes.size} bytes to $locationId/$path")
    }

    // ─── appendFile ─────────────────────────────────────────────────────

    override suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkWritePermission(locationId)

        val config = settingsRepository.getServerConfig()
        val uri = findOwnedFileOrThrow(builtin, path)

        val existingSize = queryFileSize(uri)
        val newContentBytes = content.toByteArray(Charsets.UTF_8)
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        if (existingSize + newContentBytes.size.toLong() > limitBytes) {
            throw McpToolException.ActionFailed(
                "Appending would exceed the configured file size limit of ${config.fileSizeLimitMb} MB.",
            )
        }

        try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(newContentBytes) }
                ?: throw McpToolException.ActionFailed("Failed to open file for appending: $path")
        } catch (e: McpToolException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            throw McpToolException.ActionFailed(
                "This storage provider does not support append mode. Use write_file instead.",
            )
        } catch (e: IllegalArgumentException) {
            throw McpToolException.ActionFailed(
                "This storage provider does not support append mode. Use write_file instead.",
            )
        }

        Log.d(TAG, "Appended ${newContentBytes.size} bytes to $locationId/$path")
    }

    // ─── replaceInFile ──────────────────────────────────────────────────

    override suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkWritePermission(locationId)

        val uri = findOwnedFileOrThrow(builtin, path)
        checkFileSizeByUri(uri)

        val originalContent = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw McpToolException.ActionFailed("Failed to read file: $path")

        val occurrences = countOccurrences(originalContent, oldString)
        if (occurrences == 0) return@withContext FileReplaceResult(replacementCount = 0)

        val modifiedContent = if (replaceAll) {
            originalContent.replace(oldString, newString)
        } else {
            originalContent.replaceFirst(oldString, newString)
        }
        val replacementCount = if (replaceAll) occurrences else 1

        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(modifiedContent.toByteArray(Charsets.UTF_8))
        } ?: throw McpToolException.ActionFailed("Failed to write back file: $path")

        Log.d(TAG, "Replaced $replacementCount occurrence(s) in $locationId/$path")
        FileReplaceResult(replacementCount = replacementCount)
    }

    // ─── downloadFromUrl ────────────────────────────────────────────────

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "NestedBlockDepth")
    override suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkWritePermission(locationId)

        val config = settingsRepository.getServerConfig()
        val parsedUrl = parseAndValidateUrl(url, config)
        val relativePath = buildRelativePathForDir(builtin, path)
        val displayName = extractDisplayName(path)
        val timeoutMs = (config.downloadTimeoutSeconds * MILLIS_PER_SECOND).toInt()
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB

        // Create MediaStore entry with IS_PENDING = 1
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeUtils.guessMimeType(displayName))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val insertUri = context.contentResolver.insert(builtin.collectionUri, values)
            ?: throw McpToolException.ActionFailed("Failed to create download destination: $path")

        var connection: HttpURLConnection? = null
        try {
            connection = parsedUrl.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true

            if (config.allowUnverifiedHttpsCerts && connection is HttpsURLConnection) {
                SslUtils.configurePermissiveSsl(connection)
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_RANGE) {
                throw McpToolException.ActionFailed(
                    "Download failed with HTTP status $responseCode for URL: $url",
                )
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > 0 && contentLength > limitBytes) {
                throw McpToolException.ActionFailed(
                    "Server reports file size of $contentLength bytes, exceeds limit of ${config.fileSizeLimitMb} MB.",
                )
            }

            var totalBytesWritten = 0L
            context.contentResolver.openOutputStream(insertUri, "wt")?.use { outputStream ->
                connection.inputStream.use { inputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesWritten += bytesRead
                        if (totalBytesWritten > limitBytes) {
                            throw McpToolException.ActionFailed(
                                "Download exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.",
                            )
                        }
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            } ?: throw McpToolException.ActionFailed("Failed to open download destination for writing")

            // Clear IS_PENDING on success
            val updateValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(insertUri, updateValues, null, null)

            Log.i(TAG, "Downloaded $totalBytesWritten bytes from $url to $locationId/$path")
            totalBytesWritten
        } catch (e: McpToolException) {
            context.contentResolver.delete(insertUri, null, null)
            throw e
        } catch (e: Exception) {
            context.contentResolver.delete(insertUri, null, null)
            throw McpToolException.ActionFailed("Download failed: ${e.message ?: "Unknown error"}")
        } finally {
            connection?.disconnect()
        }
    }

    // ─── deleteFile ─────────────────────────────────────────────────────

    override suspend fun deleteFile(
        locationId: String,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkDeletePermission(locationId)

        val uri = findOwnedFileOrThrow(builtin, path)
        val deleted = context.contentResolver.delete(uri, null, null)
        if (deleted == 0) {
            throw McpToolException.ActionFailed("Failed to delete file: $path in location '$locationId'")
        }

        Log.d(TAG, "Deleted file: $locationId/$path")
    }

    // ─── createFileUri ──────────────────────────────────────────────────

    override suspend fun createFileUri(
        locationId: String,
        path: String,
        mimeType: String,
    ): Uri = withContext(Dispatchers.IO) {
        val builtin = resolveBuiltin(locationId)
        BuiltinStorageLocation.validatePath(path)
        checkWritePermission(locationId)

        val relativePath = buildRelativePathForDir(builtin, path)
        val displayName = extractDisplayName(path)

        // Return existing if found
        findOwnedFile(builtin, relativePath, displayName)?.let { return@withContext it }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        context.contentResolver.insert(builtin.collectionUri, values)
            ?: throw McpToolException.ActionFailed("Failed to create file: $path")
    }

    // ─── Private helpers ────────────────────────────────────────────────

    // Throws PermissionDenied (not ActionFailed) because this is only called after
    // BuiltinStorageLocation.isBuiltinId() routing — reaching here with an invalid ID
    // means the location is not authorized, matching the SAF checkAuthorization() pattern.
    private fun resolveBuiltin(locationId: String): BuiltinStorageLocation =
        BuiltinStorageLocation.fromLocationId(locationId)
            ?: throw McpToolException.PermissionDenied(
                "Storage location '$locationId' not found.",
            )

    /**
     * Builds the MediaStore RELATIVE_PATH for the directory containing the file.
     * E.g., builtin=DOWNLOADS, path="subdir/file.txt" → "Download/subdir/"
     * E.g., builtin=DOWNLOADS, path="file.txt" → "Download/"
     * E.g., builtin=DOWNLOADS, path="" → "Download/"
     */
    private fun buildRelativePathForDir(builtin: BuiltinStorageLocation, path: String): String {
        if (path.isEmpty()) return builtin.baseRelativePath
        val segments = path.split("/").filter { it.isNotEmpty() }
        val parentSegments = segments.dropLast(1)
        return if (parentSegments.isEmpty()) {
            builtin.baseRelativePath
        } else {
            "${builtin.baseRelativePath}${parentSegments.joinToString("/")}/"
        }
    }

    /**
     * Extracts the file name (last segment) from a relative path.
     * Throws if path is empty.
     */
    private fun extractDisplayName(path: String): String {
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            throw McpToolException.InvalidParams("File path cannot be empty")
        }
        return segments.last()
    }

    private fun findOwnedFile(
        builtin: BuiltinStorageLocation,
        relativePath: String,
        displayName: String,
    ): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(relativePath, displayName, context.packageName)
        return queryForUri(builtin.collectionUri, selection, args)
    }

    private fun findAnyFile(
        builtin: BuiltinStorageLocation,
        relativePath: String,
        displayName: String,
    ): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(relativePath, displayName)
        return queryForUri(builtin.collectionUri, selection, args)
    }

    private suspend fun findFile(
        locationId: String,
        builtin: BuiltinStorageLocation,
        relativePath: String,
        displayName: String,
    ): Uri? =
        if (storageLocationProvider.isAllFilesMode(locationId)) {
            findAnyFile(builtin, relativePath, displayName)
        } else {
            findOwnedFile(builtin, relativePath, displayName)
        }

    private suspend fun findFileOrThrow(
        locationId: String,
        builtin: BuiltinStorageLocation,
        path: String,
    ): Uri {
        val relativePath = buildRelativePathForDir(builtin, path)
        val displayName = extractDisplayName(path)
        return findFile(locationId, builtin, relativePath, displayName)
            ?: throw McpToolException.ActionFailed(
                "File not found: $path in location '$locationId'",
            )
    }

    private fun findOwnedFileOrThrow(
        builtin: BuiltinStorageLocation,
        path: String,
    ): Uri {
        val relativePath = buildRelativePathForDir(builtin, path)
        val displayName = extractDisplayName(path)
        return findOwnedFile(builtin, relativePath, displayName)
            ?: throw McpToolException.ActionFailed(
                "File not found: $path in location '${builtin.locationId}'",
            )
    }

    private fun queryForUri(collectionUri: Uri, selection: String, selectionArgs: Array<String>): Uri? {
        context.contentResolver.query(
            collectionUri,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }
        return null
    }

    private fun queryFileSize(uri: Uri): Long {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
            }
        }
        return 0L
    }

    private suspend fun checkFileSizeByUri(uri: Uri) {
        val config = settingsRepository.getServerConfig()
        val limitBytes = config.fileSizeLimitMb.toLong() * BYTES_PER_MB
        val fileSize = queryFileSize(uri)
        if (fileSize > limitBytes) {
            throw McpToolException.ActionFailed(
                "File size ($fileSize bytes) exceeds the configured limit of ${config.fileSizeLimitMb} MB.",
            )
        }
    }

    private suspend fun checkWritePermission(locationId: String) {
        if (!storageLocationProvider.isWriteAllowed(locationId)) {
            throw McpToolException.PermissionDenied("Write not allowed")
        }
    }

    private suspend fun checkDeletePermission(locationId: String) {
        if (!storageLocationProvider.isDeleteAllowed(locationId)) {
            throw McpToolException.PermissionDenied("Delete not allowed")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var startIndex = 0
        while (true) {
            val index = haystack.indexOf(needle, startIndex)
            if (index < 0) break
            count++
            startIndex = index + needle.length
        }
        return count
    }

    private fun parseAndValidateUrl(
        url: String,
        config: com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig,
    ): URL {
        val parsedUrl = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw McpToolException.ActionFailed("Invalid URL: $url")
        }
        if (parsedUrl.protocol == "http" && !config.allowHttpDownloads) {
            throw McpToolException.ActionFailed(
                "HTTP downloads are not allowed. Enable 'Allow HTTP Downloads' in settings, or use HTTPS.",
            )
        }
        if (parsedUrl.protocol != "http" && parsedUrl.protocol != "https") {
            throw McpToolException.ActionFailed(
                "Unsupported URL protocol: ${parsedUrl.protocol}. Only HTTP and HTTPS are supported.",
            )
        }
        return parsedUrl
    }

    companion object {
        private const val TAG = "MCP:MediaStoreFileOps"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MILLIS_PER_SECOND = 1000L
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}
```

**Definition of Done**:
- [x] Implementation created with all 8 methods
- [x] `BuiltinStorageLocation.validatePath()` called on every operation
- [x] All ContentResolver I/O wrapped in `withContext(Dispatchers.IO)`
- [x] `IS_PENDING` pattern for downloads with cleanup on failure
- [x] Directory synthesis in `listFiles`
- [x] Owned vs all-files filtering
- **Note**: Private helpers like `findOwnedFile()`/`findAnyFile()` are non-suspend functions that call `contentResolver.query()`. This is safe because they are always called from within `withContext(Dispatchers.IO)` blocks in the public methods.

### Task 3.3: Extract shared utilities (MimeTypeUtils + SslUtils)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MimeTypeUtils.kt` — **create**

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

/**
 * Shared MIME type mapping for file operations.
 * Used by both SAF ([FileOperationProviderImpl]) and MediaStore ([MediaStoreFileOperationsImpl]).
 */
object MimeTypeUtils {
    /**
     * Guesses a MIME type from a file name extension.
     * Falls back to "application/octet-stream" if the type cannot be determined.
     */
    fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_MIME[extension] ?: "application/octet-stream"
    }

    private val EXTENSION_TO_MIME =
        mapOf(
            // Text
            "txt" to "text/plain", "html" to "text/html", "htm" to "text/html",
            "css" to "text/css", "csv" to "text/csv", "xml" to "text/xml",
            // Application
            "json" to "application/json", "js" to "application/javascript",
            "pdf" to "application/pdf", "zip" to "application/zip",
            "gz" to "application/gzip", "tar" to "application/x-tar",
            // Image
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
            // Audio
            "mp3" to "audio/mpeg", "wav" to "audio/wav",
            // Video
            "mp4" to "video/mp4", "webm" to "video/webm",
            // Android
            "apk" to "application/vnd.android.package-archive",
            // Code / config
            "md" to "text/markdown", "kt" to "text/x-kotlin", "java" to "text/x-java",
            "py" to "text/x-python", "sh" to "application/x-sh",
            "yaml" to "text/yaml", "yml" to "text/yaml", "toml" to "text/toml",
            "ini" to "text/plain", "cfg" to "text/plain", "conf" to "text/plain",
            "log" to "text/plain", "properties" to "text/plain",
        )
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/SslUtils.kt` — **create**

Extract `configurePermissiveSsl` from `FileOperationProviderImpl` into a shared object (also used by `MediaStoreFileOperationsImpl`):

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared SSL utility for permissive HTTPS connections.
 * Used by both SAF and MediaStore download implementations when
 * the user has enabled `allowUnverifiedHttpsCerts`.
 *
 * The trust-all pattern inherently requires empty `checkClientTrusted`/`checkServerTrusted`
 * implementations — these suppressions are genuinely unavoidable.
 */
object SslUtils {
    fun configurePermissiveSsl(connection: HttpsURLConnection) {
        val trustAllManager =
            @Suppress("CustomX509TrustManager")
            object : X509TrustManager {
                @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { }

                @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt` — **modify**

- Replace private `EXTENSION_TO_MIME` + `guessMimeType()` with `MimeTypeUtils.guessMimeType()`
- Replace private `configurePermissiveSsl()` with `SslUtils.configurePermissiveSsl(connection)`
- Remove now-unused SSL imports

**Definition of Done**:
- [x] `MimeTypeUtils` object created with shared map and function
- [x] `SslUtils` object created with shared SSL helper
- [x] `FileOperationProviderImpl` delegates to both utilities
- [x] `MediaStoreFileOperationsImpl` uses both utilities
- [x] No behavior change

### Task 3.4: Integrate MediaStore routing in FileOperationProviderImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt` — **modify**

Add import and `MediaStoreFileOperations` as constructor dependency:
```kotlin
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
```
```kotlin
class FileOperationProviderImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storageLocationProvider: StorageLocationProvider,
        private val settingsRepository: SettingsRepository,
        private val mediaStoreFileOperations: MediaStoreFileOperations,
    ) : FileOperationProvider {
```

At the top of each of the 8 public methods, add routing:
```kotlin
if (BuiltinStorageLocation.isBuiltinId(locationId)) {
    return mediaStoreFileOperations.<method>(locationId, ...)
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` — **modify**

Add binding in `ServiceModule`:
```kotlin
@Binds
@Singleton
abstract fun bindMediaStoreFileOperations(impl: MediaStoreFileOperationsImpl): MediaStoreFileOperations
```

**Definition of Done**:
- [x] All 8 methods route to MediaStore for `builtin:` IDs
- [x] SAF code paths unchanged
- [x] DI binding added for interface → impl

---

## User Story 4: Android Permissions & Manifest

### Acceptance Criteria
- [x] Manifest declares READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO
- [x] `make grant-permissions` grants the 3 media permissions
- [x] Permissions are optional — app works without them

### Task 4.1: Add READ_MEDIA_* permissions to manifest

**File**: `app/src/main/AndroidManifest.xml` — **modify**

Add after the Camera/Audio block (after line 25):
```xml
<!-- Media read access (optional — enables "all files" mode for built-in storage locations) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

**Definition of Done**:
- [x] 3 permissions added

### Task 4.2: Update Makefile grant-permissions

**File**: `Makefile` — **modify**

Update target comment:
```
grant-permissions: ## Grant permissions via adb (accessibility + notifications + camera + microphone + notification listener + media)
```

Add 3 steps after step 5 (Notification Listener):
```makefile
	@echo ""
	@echo "6. Granting READ_MEDIA_IMAGES permission..."
	$(ADB) shell pm grant $(APP_ID_DEBUG) android.permission.READ_MEDIA_IMAGES
	@echo "   Done."
	@echo ""
	@echo "7. Granting READ_MEDIA_VIDEO permission..."
	$(ADB) shell pm grant $(APP_ID_DEBUG) android.permission.READ_MEDIA_VIDEO
	@echo "   Done."
	@echo ""
	@echo "8. Granting READ_MEDIA_AUDIO permission..."
	$(ADB) shell pm grant $(APP_ID_DEBUG) android.permission.READ_MEDIA_AUDIO
	@echo "   Done."
	@echo ""
```

**Definition of Done**:
- [x] 3 media permission grants added
- [x] Target comment updated

---

## User Story 5: UI — Built-in Locations & "All Files" Toggle

### Acceptance Criteria
- [x] Built-in locations appear first, before SAF locations
- [x] Built-in locations have no edit or delete buttons
- [x] Built-in locations have allowWrite and allowDelete toggles
- [x] Pictures, Movies, Music have "Access all files" button triggering runtime permission request
- [x] Downloads does NOT have the "all files" button
- [x] Button state reflects permission status (granted = disabled)
- [x] Location name updates dynamically after permission change

### Task 5.1: Update StorageSettingsScreen

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/StorageSettingsScreen.kt` — **modify**

1. Split location rendering into two sections:
   - **Built-in** (`storageLocations.filter { it.isBuiltin }`) with section title
   - **User** (`storageLocations.filter { !it.isBuiltin }`) with "Add" button + empty state

2. Create `BuiltinStorageLocationRow` composable:

   ```kotlin
   ```kotlin
   @Composable
   private fun BuiltinStorageLocationRow(
       location: StorageLocation,
       hasAllFilesPermission: Boolean,
       readMediaPermission: String?,
       onAllowWriteChange: (Boolean) -> Unit,
       onAllowDeleteChange: (Boolean) -> Unit,
       onRequestPermission: (String) -> Unit,
   ) {
       Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
           Text(text = location.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
           Text(text = location.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
           Row(
               modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
               verticalAlignment = Alignment.CenterVertically,
               horizontalArrangement = Arrangement.spacedBy(16.dp),
           ) {
               Row(
                   modifier = Modifier.toggleable(value = location.allowWrite, role = Role.Switch, onValueChange = onAllowWriteChange),
                   verticalAlignment = Alignment.CenterVertically,
               ) {
                   Text(text = stringResource(R.string.storage_location_allow_write_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                   Spacer(modifier = Modifier.width(4.dp))
                   Switch(checked = location.allowWrite, onCheckedChange = null)
               }
               Row(
                   modifier = Modifier.toggleable(value = location.allowDelete, role = Role.Switch, onValueChange = onAllowDeleteChange),
                   verticalAlignment = Alignment.CenterVertically,
               ) {
                   Text(text = stringResource(R.string.storage_location_allow_delete_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                   Spacer(modifier = Modifier.width(4.dp))
                   Switch(checked = location.allowDelete, onCheckedChange = null)
               }
           }
           if (readMediaPermission != null) {
               OutlinedButton(
                   onClick = { onRequestPermission(readMediaPermission) },
                   enabled = !hasAllFilesPermission,
                   modifier = Modifier.fillMaxWidth(),
               ) {
                   Text(stringResource(if (hasAllFilesPermission) R.string.storage_builtin_all_files_granted else R.string.storage_builtin_grant_all_files))
               }
           }
       }
   }
   ```

3. In `StorageSettingsScreen`, add permission launcher and render built-in section:

   ```kotlin
   // Add permission launcher (next to existing documentTreeLauncher):
   val permissionLauncher = rememberLauncherForActivityResult(
       contract = ActivityResultContracts.RequestPermission(),
   ) { _ -> viewModel.refreshStorageLocations() }

   // Split locations:
   val builtinLocations = storageLocations.filter { it.isBuiltin }
   val userLocations = storageLocations.filter { !it.isBuiltin }

   // Render built-in section FIRST in the scrollable Column:
   Text(text = stringResource(R.string.storage_builtin_locations_title), style = MaterialTheme.typography.titleLarge)
   Text(text = stringResource(R.string.storage_builtin_locations_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
   Spacer(modifier = Modifier.height(12.dp))
   builtinLocations.forEach { location ->
       val builtin = BuiltinStorageLocation.fromLocationId(location.id)
       val hasAllFiles = builtin?.readMediaPermission?.let {
           ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
       } ?: false
       BuiltinStorageLocationRow(
           location = location,
           hasAllFilesPermission = hasAllFiles,
           readMediaPermission = builtin?.readMediaPermission,
           onAllowWriteChange = { viewModel.updateLocationAllowWrite(location.id, it) },
           onAllowDeleteChange = { viewModel.updateLocationAllowDelete(location.id, it) },
           onRequestPermission = { permission -> permissionLauncher.launch(permission) },
       )
   }
   Spacer(modifier = Modifier.height(16.dp))
   HorizontalDivider()
   Spacer(modifier = Modifier.height(16.dp))
   // Then existing "User Locations" section with title updated to use storage_user_locations_title
   ```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt` — **modify**

Ensure `refreshStorageLocations()` is public (make it public if currently private, or add a public wrapper).

**File**: `app/src/main/res/values/strings.xml` — **modify**

Add strings:
- `storage_builtin_locations_title` → `"Built-in Locations"`
- `storage_builtin_locations_description` → `"Always available. Use MediaStore to read and write files without additional setup."`
- `storage_user_locations_title` → `"User Locations"`
- `storage_builtin_grant_all_files` → `"Grant access to all files"`
- `storage_builtin_all_files_granted` → `"All files access granted"`

**Definition of Done**:
- [x] Built-in section renders correctly
- [x] No edit/delete on built-ins
- [x] Write/delete toggles work
- [x] "Access all files" button on Pictures/Movies/Music only
- [x] Runtime permission request works
- [x] Name updates after permission change

---

## User Story 6: ADB Intent Configuration for Storage Permissions

### Acceptance Criteria
- [ ] `AdbConfigHandler` supports `storage_location_id`, `storage_allow_write`, `storage_allow_delete` extras
- [ ] Works for both built-in and SAF location IDs
- [ ] `E2EConfigReceiver` supports the same extras
- [ ] Missing extras silently ignored
- [ ] Invalid location IDs logged and ignored

### Task 6.1: Update AdbConfigHandler and AdbConfigReceiver

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt` — **modify**

1. Add `StorageLocationProvider` constructor parameter:
   ```kotlin
   class AdbConfigHandler(
       private val settingsRepository: SettingsRepository,
       private val storageLocationProvider: StorageLocationProvider,
   )
   ```

2. Add extras to companion:
   ```kotlin
   internal const val EXTRA_STORAGE_LOCATION_ID = "storage_location_id"
   internal const val EXTRA_STORAGE_ALLOW_WRITE = "storage_allow_write"
   internal const val EXTRA_STORAGE_ALLOW_DELETE = "storage_allow_delete"
   ```

3. Add `applyStorageLocationPermissions(intent)` called at end of `handleConfigure()`:
   ```kotlin
   private suspend fun applyStorageLocationPermissions(intent: Intent) {
       val locationId = intent.getStringExtra(EXTRA_STORAGE_LOCATION_ID) ?: return
       if (!storageLocationProvider.isLocationAuthorized(locationId)) {
           Log.w(TAG, "Ignoring storage permissions for unknown location: $locationId")
           return
       }
       if (intent.hasExtra(EXTRA_STORAGE_ALLOW_WRITE)) {
           val allowWrite = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_WRITE, false)
           storageLocationProvider.updateLocationAllowWrite(locationId, allowWrite)
           Log.i(TAG, "Storage location allowWrite updated to $allowWrite")
       }
       if (intent.hasExtra(EXTRA_STORAGE_ALLOW_DELETE)) {
           val allowDelete = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_DELETE, false)
           storageLocationProvider.updateLocationAllowDelete(locationId, allowDelete)
           Log.i(TAG, "Storage location allowDelete updated to $allowDelete")
       }
   }
   ```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigReceiver.kt` — **modify**

Add `StorageLocationProvider` injection and pass to handler:
```kotlin
@Inject
lateinit var storageLocationProvider: StorageLocationProvider
```

Update handler instantiation:
```kotlin
val handler = AdbConfigHandler(settingsRepository, storageLocationProvider)
```

**Definition of Done**:
- [ ] 3 new extras defined
- [ ] `applyStorageLocationPermissions` validates and applies
- [ ] `AdbConfigReceiver` injects and passes `StorageLocationProvider`
- [ ] Works for `builtin:` and SAF IDs

### Task 6.2: Update E2EConfigReceiver

**File**: `app/src/debug/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiver.kt` — **modify**

1. Inject `StorageLocationProvider`:
   ```kotlin
   @Inject
   lateinit var storageLocationProvider: StorageLocationProvider
   ```

2. Add extras to companion:
   ```kotlin
   private const val EXTRA_STORAGE_LOCATION_ID = "storage_location_id"
   private const val EXTRA_STORAGE_ALLOW_WRITE = "storage_allow_write"
   private const val EXTRA_STORAGE_ALLOW_DELETE = "storage_allow_delete"
   ```

3. In `handleConfigure()`, add at the end of the `scope.launch` block:

   ```kotlin
   val storageLocationId = intent.getStringExtra(EXTRA_STORAGE_LOCATION_ID)
   if (!storageLocationId.isNullOrEmpty()) {
       if (storageLocationProvider.isLocationAuthorized(storageLocationId)) {
           if (intent.hasExtra(EXTRA_STORAGE_ALLOW_WRITE)) {
               val allowWrite = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_WRITE, false)
               storageLocationProvider.updateLocationAllowWrite(storageLocationId, allowWrite)
               Log.i(TAG, "Storage location allowWrite=$allowWrite")
           }
           if (intent.hasExtra(EXTRA_STORAGE_ALLOW_DELETE)) {
               val allowDelete = intent.getBooleanExtra(EXTRA_STORAGE_ALLOW_DELETE, false)
               storageLocationProvider.updateLocationAllowDelete(storageLocationId, allowDelete)
               Log.i(TAG, "Storage location allowDelete=$allowDelete")
           }
       } else {
           Log.w(TAG, "Unknown storage location: $storageLocationId")
       }
   }
   ```

**Definition of Done**:
- [ ] E2E receiver handles storage permission extras
- [ ] `StorageLocationProvider` injected
- [ ] Same behavior as AdbConfigHandler

---

## User Story 7: Update list_storage_locations Tool Description

### Acceptance Criteria
- [ ] Tool description reflects built-in and user-added locations

### Task 7.1: Update ListStorageLocationsHandler

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt` — **modify**

In `ListStorageLocationsHandler.register()`, replace:
```kotlin
                description =
                    "Lists storage locations that the user has added in the app. " +
                        "Each location represents a directory the user granted access to. " +
                        "Use the location ID from this list for all file operations.",
```
with:
```kotlin
                description =
                    "Lists available storage locations. Includes built-in locations " +
                        "(always available, no setup required) and user-added locations. " +
                        "Use the location ID from this list for all file operations.",
```

Update class KDoc from `Lists user-added storage locations` to `Lists all available storage locations (built-in and user-added)`.

**Definition of Done**:
- [ ] Description updated
- [ ] KDoc updated

---

## User Story 8: Tests

### Acceptance Criteria
- [ ] Unit tests for `BuiltinStorageLocation` enum helpers + path validation
- [ ] Unit tests for `BuiltinPermissions` JSON serialization in `SettingsRepositoryImpl`
- [ ] Unit tests for `PermissionChecker` impl
- [ ] Unit tests for `StorageLocationProviderImpl` built-in integration
- [ ] Unit tests for `MediaStoreFileOperationsImpl` (all methods + edge cases)
- [ ] Unit tests for `FileOperationProviderImpl` routing
- [ ] Unit tests for `MimeTypeUtils`
- [ ] Unit tests for `AdbConfigHandler` storage permission extras
- [ ] Unit tests for `E2EConfigReceiver` storage permission extras
- [ ] Unit tests for `MainViewModel` refresh behavior
- [ ] Integration tests for file tools with built-in locations (mock at `FileOperationProvider` level)
- [ ] All existing tests pass
- **Note**: Compose UI tests are not included — the project does not have Compose UI tests in its test strategy. UI behavior (rendering, button visibility) is verified manually. ViewModel and provider logic IS tested.

### Task 8.1: Unit tests for BuiltinStorageLocation

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/BuiltinStorageLocationTest.kt` — **create**

**Setup**: No mocks — pure enum + validation tests.

| Test | Verifies |
|------|----------|
| `fromLocationId returns correct entry for each builtin` | All 4 IDs resolve |
| `fromLocationId returns null for unknown ID` | Non-builtin returns null |
| `isBuiltinId returns true for builtin prefix` | `"builtin:downloads"` → true |
| `isBuiltinId returns false for non-builtin ID` | SAF ID → false |
| `all entries have unique locationIds` | No duplicates |
| `downloads has no readMediaPermission` | null |
| `pictures movies music have readMediaPermission` | Non-null |
| `validatePath accepts valid relative path` | `"subdir/file.txt"` passes |
| `validatePath accepts empty path` | `""` passes |
| `validatePath rejects double-dot segments` | `"../secret"` throws InvalidParams |
| `validatePath rejects single-dot segments` | `"./file"` throws InvalidParams |
| `validatePath rejects absolute paths` | `"/etc/passwd"` throws InvalidParams |
| `validatePath rejects control characters` | Path with `\n` throws InvalidParams |
| `validatePath rejects nested traversal` | `"subdir/../../etc"` throws InvalidParams |

### Task 8.2: Unit tests for SettingsRepositoryImpl built-in permissions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` — **modify**

**Setup**: Existing test DataStore pattern.

| Test | Verifies |
|------|----------|
| `getBuiltinLocationPermissions returns empty map when no data` | Default state |
| `updateBuiltinLocationAllowWrite persists and reads back` | Write round-trip |
| `updateBuiltinLocationAllowDelete persists and reads back` | Delete round-trip |
| `multiple builtin permissions stored independently` | No cross-contamination |
| `malformed JSON returns empty map` | Graceful degradation |
| `serialized JSON matches expected format` | `{"builtin:x": {"allowWrite": true, "allowDelete": false}}` |

### Task 8.3: Unit tests for MimeTypeUtils and SslUtils

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MimeTypeUtilsTest.kt` — **create**

**Setup**: No mocks.

| Test | Verifies |
|------|----------|
| `guessMimeType returns correct type for known extensions` | jpg, png, pdf, json, etc. |
| `guessMimeType returns octet-stream for unknown extension` | `.xyz` → `application/octet-stream` |
| `guessMimeType handles no extension` | `"file"` → `application/octet-stream` |
| `guessMimeType is case insensitive` | `.JPG` same as `.jpg` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/SslUtilsTest.kt` — **create**

**Setup**: Mock `HttpsURLConnection` with MockK.

| Test | Verifies |
|------|----------|
| `configurePermissiveSsl sets SSLSocketFactory on connection` | Factory applied |
| `configurePermissiveSsl sets permissive HostnameVerifier` | Verifier accepts any hostname |

### Task 8.4: Unit tests for PermissionChecker

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/PermissionCheckerImplTest.kt` — **create**

**Setup**: Mock `Context` with MockK.

| Test | Verifies |
|------|----------|
| `hasPermission returns true when granted` | PERMISSION_GRANTED → true |
| `hasPermission returns false when denied` | PERMISSION_DENIED → false |

### Task 8.5: Unit tests for StorageLocationProviderImpl built-in integration

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt` — **modify**

**Setup**: Mock `SettingsRepository`, mock `PermissionChecker`.

| Test | Verifies |
|------|----------|
| `getAllLocations returns builtins before SAF locations` | Ordering |
| `getAllLocations returns 4 builtins when no SAF locations` | Synthesis |
| `isLocationAuthorized returns true for builtin IDs` | Authorization |
| `isLocationAuthorized returns false for unknown builtin ID` | `"builtin:invalid"` |
| `isWriteAllowed reads from builtin permissions` | Permission routing |
| `isDeleteAllowed reads from builtin permissions` | Permission routing |
| `updateLocationAllowWrite routes to builtin persistence` | Persistence routing |
| `updateLocationAllowDelete routes to builtin persistence` | Persistence routing |
| `getLocationById returns builtin location` | Lookup |
| `getTreeUriForLocation returns null for builtin` | No tree URI |
| `isAllFilesMode returns false when permission not granted` | Default |
| `isAllFilesMode returns true when permission granted` | With permission |
| `isAllFilesMode returns false for downloads` | No readMediaPermission |
| `builtin name shows Only owned files when permission not granted` | Dynamic name |
| `builtin name shows All files when permission granted` | Dynamic name |
| `getAllLocations returns null availableBytes when StatFs fails` | StatFs failure path |

### Task 8.6: Unit tests for MediaStoreFileOperationsImpl

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/MediaStoreFileOperationsTest.kt` — **create**

**Setup**: Mock `Context`, `ContentResolver`, `StorageLocationProvider`, `SettingsRepository`. MockK cursor mocking.

| Test | Verifies |
|------|----------|
| `listFiles returns files at root with empty path` | Root directory listing (`path=""`) |
| `listFiles returns files for owned entries` | Basic listing |
| `listFiles synthesizes directories from RELATIVE_PATH` | Directory synthesis |
| `listFiles applies pagination` | Offset/limit |
| `listFiles returns all files in all-files mode` | No owner filter |
| `listFiles throws for unknown builtin ID` | Error |
| `readFile reads owned file content` | Basic read |
| `readFile applies line pagination` | Offset/limit |
| `readFile throws when file not found` | Error |
| `readFile works in all-files mode for non-owned file` | Can read others' files |
| `writeFile creates new MediaStore entry` | Insert flow |
| `writeFile overwrites existing owned file` | Update flow |
| `writeFile throws when write not allowed` | Permission check |
| `writeFile rejects path traversal` | `"../secret"` throws |
| `writeFile respects file size limit` | Size validation |
| `appendFile appends to owned file` | Append mode |
| `appendFile throws when write not allowed` | Permission check |
| `replaceInFile performs replacement on owned file` | Read-modify-write |
| `replaceInFile throws for non-owned file` | Ownership constraint |
| `downloadFromUrl uses IS_PENDING pattern` | Pending flag lifecycle |
| `downloadFromUrl cleans up on failure` | Partial deletion |
| `deleteFile deletes owned file` | Delete flow |
| `deleteFile throws when delete not allowed` | Permission check |
| `createFileUri returns URI for new entry` | URI creation |
| `createFileUri returns existing URI when file exists` | Idempotent |
| `all methods reject empty path for file operations` | `readFile("")` throws |
| `all methods reject path traversal` | Comprehensive |

### Task 8.7: Unit tests for FileOperationProviderImpl routing

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderTest.kt` — **modify**

**Setup**: Mock `MediaStoreFileOperations` (the interface).

| Test | Verifies |
|------|----------|
| `listFiles routes to MediaStore for builtin ID` | Routing |
| `listFiles routes to SAF for non-builtin ID` | Routing |
| `readFile routes to MediaStore for builtin ID` | Routing |
| `writeFile routes to MediaStore for builtin ID` | Routing |
| `appendFile routes to MediaStore for builtin ID` | Routing |
| `replaceInFile routes to MediaStore for builtin ID` | Routing |
| `downloadFromUrl routes to MediaStore for builtin ID` | Routing |
| `deleteFile routes to MediaStore for builtin ID` | Routing |
| `createFileUri routes to MediaStore for builtin ID` | Routing |

### Task 8.8: Unit tests for AdbConfigHandler storage permissions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt` — **modify**

**Setup**: Mock `StorageLocationProvider`.

| Test | Verifies |
|------|----------|
| `configure with storage_location_id and storage_allow_write updates` | Write |
| `configure with storage_location_id and storage_allow_delete updates` | Delete |
| `configure with unknown storage_location_id logs and skips` | Validation |
| `configure without storage_location_id skips` | Optional |
| `configure with builtin location ID works` | Built-in routing |

### Task 8.9: Unit tests for E2EConfigReceiver storage permissions

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/debug/E2EConfigReceiverTest.kt` — **create** (or **modify** if exists)

**Setup**: Mock `SettingsRepository`, `StorageLocationProvider`.

| Test | Verifies |
|------|----------|
| `configure with storage extras updates permissions` | Happy path |
| `configure with unknown location logs and skips` | Validation |
| `configure without storage extras skips` | Optional |

### Task 8.10: Unit tests for MainViewModel refresh

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` — **modify**

**Setup**: Mock `StorageLocationProvider`.

| Test | Verifies |
|------|----------|
| `refreshStorageLocations updates storageLocations state` | State update |
| `updateLocationAllowWrite updates builtin location in state` | Builtin mutation |
| `updateLocationAllowDelete updates builtin location in state` | Builtin mutation |

### Task 8.11: Integration tests for file tools with built-in locations

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt` — **modify**

**Setup**: Use `McpIntegrationTestHelper`. Mock at `FileOperationProvider` interface level (the mock already handles routing; the integration test verifies the MCP stack calls through to the provider).

| Test | Verifies |
|------|----------|
| `list_storage_locations includes builtin locations` | Built-ins in response |
| `list_files with builtin location ID succeeds` | E2E routing |
| `read_file with builtin location ID succeeds` | E2E routing |
| `write_file with builtin location ID succeeds` | E2E routing |
| `download_from_url with builtin location ID succeeds` | E2E routing |
| `delete_file with builtin location ID succeeds` | E2E routing |

**Definition of Done**:
- [ ] All test files created/modified
- [ ] All tests pass
- [ ] No existing tests broken

---

## User Story 9: Final Verification

### Acceptance Criteria
- [ ] All code changes reviewed from the ground up
- [ ] All tests pass
- [ ] All linting passes
- [ ] Build succeeds without warnings
- [ ] No TODOs, no dead code, no temporary hacks
- [ ] SAF locations unaffected

### Task 9.1: Full verification checklist

1. [ ] Re-read every file modified/created in this plan
2. [ ] Run `make lint` — all clean
3. [ ] Run `make test-unit` — all pass
4. [ ] Run `make test-integration` — all pass
5. [ ] Run `./gradlew build` — no errors, no warnings
6. [ ] Verify `list_storage_locations` returns built-in locations
7. [ ] Verify all 8 file tools route correctly for `builtin:` IDs
8. [ ] Verify path traversal is rejected (`../`, `./`, absolute paths, control chars)
9. [ ] Verify ADB intent configuration works for storage permissions
10. [ ] Verify E2E debug receiver handles storage permission extras
11. [ ] Verify Makefile `grant-permissions` includes media permissions
12. [ ] Verify manifest declares READ_MEDIA_* permissions
13. [ ] Verify no changes to existing SAF behavior
14. [ ] Spawn `code-reviewer` subagent for plan compliance review
