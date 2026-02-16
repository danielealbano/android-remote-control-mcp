# Plan 21: Storage Location Read/Write/Delete Permissions

## Summary

Add per-location `allowWrite` and `allowDelete` permission flags to storage locations. All MCP file tools that perform write or delete operations must enforce these flags, returning a generic error when denied. The `list_storage_locations` MCP tool exposes the permission state. Users manage permissions via inline toggles in the UI.

## Agreed Design Decisions

| Decision | Resolution |
|----------|-----------|
| Default for **new** locations | `allowWrite=false`, `allowDelete=false` (read-only by default) |
| Default for **migrated** (existing) locations | `allowWrite=true`, `allowDelete=true` (preserve existing behavior) |
| `allowRead` | Always `true`, implicit, not stored |
| Error messages | Generic: `"Write not allowed"` / `"Delete not allowed"` — no guidance to prevent model self-granting |
| Permission gating on `list_files` / `read_file` | None — always allowed |
| `file_replace` classification | Requires `allowWrite` |
| Directory creation gating | Not separately gated — covered by `allowWrite` |
| Permission toggles in Add dialog | No — permissions editable only from the location row |
| Permissions editable while server running | Yes — no need to stop server |
| Adding/removing locations while server running | Yes — no need to stop server |
| MCP output | Include `allow_read`, `allow_write`, `allow_delete` in `list_storage_locations` |
| Enforcement point | `FileOperationProviderImpl` (single enforcement point) |
| Permission update methods | Individual setters: `updateLocationAllowWrite()`, `updateLocationAllowDelete()` |

## Tool Classification

| Permission Required | MCP Tools |
|---|---|
| None (always allowed) | `list_storage_locations`, `list_files`, `read_file` |
| `allowWrite` | `write_file`, `append_file`, `file_replace`, `download_from_url` |
| `allowDelete` | `delete_file` |

## Files Impacted

| File | Change Type |
|---|---|
| `app/src/main/kotlin/.../data/model/StorageLocation.kt` | Add fields |
| `app/src/main/kotlin/.../data/repository/SettingsRepository.kt` | Add fields + methods |
| `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt` | Serialization, migration, new methods |
| `app/src/main/kotlin/.../services/storage/StorageLocationProvider.kt` | Add methods |
| `app/src/main/kotlin/.../services/storage/StorageLocationProviderImpl.kt` | Implement methods, map fields |
| `app/src/main/kotlin/.../services/storage/FileOperationProviderImpl.kt` | Permission checks |
| `app/src/main/kotlin/.../mcp/tools/FileTools.kt` | MCP output |
| `app/src/main/kotlin/.../ui/components/StorageLocationsSection.kt` | UI toggles |
| `app/src/main/kotlin/.../ui/screens/HomeScreen.kt` | Wire callbacks |
| `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt` | ViewModel methods |
| `app/src/main/res/values/strings.xml` | String resources |
| `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt` | Tests |
| `app/src/test/kotlin/.../services/storage/StorageLocationProviderTest.kt` | Tests |
| `app/src/test/kotlin/.../services/storage/FileOperationProviderTest.kt` | Tests |
| `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` | Tests |
| `app/src/test/kotlin/.../ui/viewmodels/MainViewModelTest.kt` | Tests |
| `docs/MCP_TOOLS.md` | Documentation |
| `docs/PROJECT.md` | Documentation |

---

## User Story 1: Data Model, Persistence & Service Layer

**Description**: Add `allowWrite` and `allowDelete` permission fields to the storage location data model, persistence layer, and service layer. Include migration for existing stored locations.

**Acceptance Criteria / Definition of Done**:
- [x] `StorageLocation` data class has `allowWrite: Boolean` and `allowDelete: Boolean` fields
- [x] `StoredLocation` data class has `allowWrite: Boolean` and `allowDelete: Boolean` fields
- [x] `SettingsRepositoryImpl` serializes/deserializes the new fields
- [x] Migration: existing stored locations without permission fields default to `allowWrite=true, allowDelete=true`
- [x] `SettingsRepository` has `updateLocationAllowWrite()` and `updateLocationAllowDelete()` methods
- [x] `StorageLocationProvider` has `isWriteAllowed()`, `isDeleteAllowed()`, `updateLocationAllowWrite()`, `updateLocationAllowDelete()` methods
- [x] `StorageLocationProviderImpl` implements all new methods and maps fields in `getAllLocations()` and `getLocationById()`
- [x] All targeted tests pass: `./gradlew :app:testDebugUnitTest --tests "*.SettingsRepositoryImplTest"` and `./gradlew :app:testDebugUnitTest --tests "*.StorageLocationProviderTest"`
- [x] Lint clean on changed files

---

### Task 1.1: Add permission fields to StorageLocation data class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageLocation.kt`

**Action 1.1.1**: Add `allowWrite` and `allowDelete` fields to the `StorageLocation` data class.

```diff
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
+ * @property allowWrite Whether MCP tools are permitted to write/modify files in this location.
+ * @property allowDelete Whether MCP tools are permitted to delete files in this location.
  */
 data class StorageLocation(
     val id: String,
     val name: String,
     val path: String,
     val description: String,
     val treeUri: String,
     val availableBytes: Long?,
+    val allowWrite: Boolean = false,
+    val allowDelete: Boolean = false,
 )
```

**Rationale**: The domain model must carry permission flags so they can be propagated to MCP output and UI layers. Default values of `false` (most restrictive) are provided to prevent cross-user-story build breakage — files that construct `StorageLocation` but are updated in later user stories (US3, US4) will compile with safe defaults. All explicit mapping sites (e.g., `StorageLocationProviderImpl`) pass the actual values from `StoredLocation`, so the defaults are only a compilation safety net.

---

### Task 1.2: Add permission fields to StoredLocation and SettingsRepository interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

**Action 1.2.1**: Add `allowWrite` and `allowDelete` fields to the `StoredLocation` data class.

```diff
+    * @property allowWrite Whether write operations are allowed for this location.
+    * @property allowDelete Whether delete operations are allowed for this location.
     */
     data class StoredLocation(
         val id: String,
         val name: String,
         val path: String,
         val description: String,
         val treeUri: String,
+        val allowWrite: Boolean = false,
+        val allowDelete: Boolean = false,
     )
```

Note: The KDoc block for `StoredLocation` (above the `data class` line) must be updated to include the two new `@property` entries. Default values of `false` are provided as a compilation safety net (same rationale as `StorageLocation`).

**Action 1.2.2**: Add `updateLocationAllowWrite()` and `updateLocationAllowDelete()` methods to the `SettingsRepository` interface. Add them after the existing `updateLocationDescription()` method.

```diff
     suspend fun updateLocationDescription(
         locationId: String,
         description: String,
     )
+
+    /**
+     * Updates whether write operations are allowed for a storage location.
+     *
+     * @param locationId The storage location identifier.
+     * @param allowWrite Whether write operations are allowed.
+     */
+    suspend fun updateLocationAllowWrite(
+        locationId: String,
+        allowWrite: Boolean,
+    )
+
+    /**
+     * Updates whether delete operations are allowed for a storage location.
+     *
+     * @param locationId The storage location identifier.
+     * @param allowDelete Whether delete operations are allowed.
+     */
+    suspend fun updateLocationAllowDelete(
+        locationId: String,
+        allowDelete: Boolean,
+    )
```

---

### Task 1.3: Update SettingsRepositoryImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**Action 1.3.1**: Update `parseStoredLocationsJson()` to read `allowWrite` and `allowDelete` from JSON. Use `jsonPrimitive.booleanOrNull` for type-safe parsing. Distinguish between "field missing" (migration → `true`) and "field present but invalid/corrupted" (→ `false`, deny).

First, add the required import at the top of the file (after the existing `import kotlinx.serialization.json.jsonPrimitive`):

```diff
 import kotlinx.serialization.json.Json
+import kotlinx.serialization.json.booleanOrNull
 import kotlinx.serialization.json.buildJsonArray
```

Note: `booleanOrNull` sorts alphabetically after `Json` and before `buildJsonArray`. The actual import block is alphabetically ordered (lines 17-23 of the file). Alternatively, add the import anywhere and let `ktlintFormat` auto-sort.

Then, in `parseStoredLocationsJson`, inside the `mapNotNull` block where `StoredLocation` is constructed:

```diff
                         val description = obj["description"]?.jsonPrimitive?.content ?: ""
+                        val allowWriteElement = obj["allowWrite"]
+                        val allowWrite = if (allowWriteElement == null) true else allowWriteElement.jsonPrimitive.booleanOrNull ?: false
+                        val allowDeleteElement = obj["allowDelete"]
+                        val allowDelete = if (allowDeleteElement == null) true else allowDeleteElement.jsonPrimitive.booleanOrNull ?: false
                         SettingsRepository.StoredLocation(
                             id = id,
                             name = name,
                             path = path,
                             description = description,
                             treeUri = treeUri,
+                            allowWrite = allowWrite,
+                            allowDelete = allowDelete,
                         )
```

Note: `booleanOrNull` reads native JSON booleans directly without string conversion. When the field is **missing** (migration from old format), we default to `true` (preserve existing behavior). When the field is **present but invalid/corrupted**, we default to `false` (deny — fail-closed for security). The `import kotlinx.serialization.json.booleanOrNull` must be added at the top of the file.

Also update the old-format migration block (the `catch` branch that parses `{"locationId": "treeUri"}`):

```diff
                     jsonObject.map { (key, value) ->
                         SettingsRepository.StoredLocation(
                             id = key,
                             name = key.substringAfterLast("/"),
                             path = "/",
                             description = "",
                             treeUri = value.jsonPrimitive.content,
+                            allowWrite = true,
+                            allowDelete = true,
                         )
                     }
```

**Action 1.3.2**: Update `serializeStoredLocationsJson()` to write `allowWrite` and `allowDelete` to JSON as native JSON booleans.

```diff
                                 put("description", loc.description)
                                 put("treeUri", loc.treeUri)
+                                put("allowWrite", loc.allowWrite)
+                                put("allowDelete", loc.allowDelete)
```

Note: The indentation is 32 spaces (inside `buildJsonArray { add(buildJsonObject { ... }) }`). The `put(key, Boolean)` overload (from the already-imported `kotlinx.serialization.json.put`) writes native JSON booleans (`true`/`false`). This is consistent with both the existing serialization style (e.g., `put("id", loc.id)`) and how `booleanOrNull` reads them back.

**Action 1.3.3**: Implement `updateLocationAllowWrite()` and `updateLocationAllowDelete()`. Add them after the existing `updateLocationDescription()` method. Follow the same pattern.

```kotlin
    override suspend fun updateLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    ) {
        dataStore.edit { prefs ->
            val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
            val index = existing.indexOfFirst { it.id == locationId }
            if (index >= 0) {
                existing[index] = existing[index].copy(allowWrite = allowWrite)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            } else {
                Log.w(TAG, "updateLocationAllowWrite: location $locationId not found, no-op")
            }
        }
    }

    override suspend fun updateLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    ) {
        dataStore.edit { prefs ->
            val existing = parseStoredLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY]).toMutableList()
            val index = existing.indexOfFirst { it.id == locationId }
            if (index >= 0) {
                existing[index] = existing[index].copy(allowDelete = allowDelete)
                prefs[AUTHORIZED_LOCATIONS_KEY] = serializeStoredLocationsJson(existing)
            } else {
                Log.w(TAG, "updateLocationAllowDelete: location $locationId not found, no-op")
            }
        }
    }
```

---

### Task 1.4: Update StorageLocationProvider interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProvider.kt`

**Action 1.4.1**: Add `isWriteAllowed()`, `isDeleteAllowed()`, `updateLocationAllowWrite()`, `updateLocationAllowDelete()` methods. Add them after the existing `getLocationById()` method, before `isDuplicateTreeUri()`.

```diff
     suspend fun getLocationById(locationId: String): StorageLocation?

+    /**
+     * Checks whether write operations are allowed for the given storage location.
+     *
+     * @return true if the location exists and has allowWrite=true; false otherwise.
+     */
+    suspend fun isWriteAllowed(locationId: String): Boolean
+
+    /**
+     * Checks whether delete operations are allowed for the given storage location.
+     *
+     * @return true if the location exists and has allowDelete=true; false otherwise.
+     */
+    suspend fun isDeleteAllowed(locationId: String): Boolean
+
+    /**
+     * Updates whether write operations are allowed for a storage location.
+     *
+     * @param locationId The storage location identifier.
+     * @param allowWrite Whether write operations are allowed.
+     */
+    suspend fun updateLocationAllowWrite(
+        locationId: String,
+        allowWrite: Boolean,
+    )
+
+    /**
+     * Updates whether delete operations are allowed for a storage location.
+     *
+     * @param locationId The storage location identifier.
+     * @param allowDelete Whether delete operations are allowed.
+     */
+    suspend fun updateLocationAllowDelete(
+        locationId: String,
+        allowDelete: Boolean,
+    )
+
     suspend fun isDuplicateTreeUri(treeUri: Uri): Boolean
```

---

### Task 1.5: Update StorageLocationProviderImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt`

**Action 1.5.1**: Update `getAllLocations()` to map `allowWrite` and `allowDelete` from `StoredLocation` to `StorageLocation`.

```diff
             return stored.map { loc ->
                 StorageLocation(
                     id = loc.id,
                     name = loc.name,
                     path = loc.path,
                     description = loc.description,
                     treeUri = loc.treeUri,
                     availableBytes = queryAvailableBytes(loc.treeUri),
+                    allowWrite = loc.allowWrite,
+                    allowDelete = loc.allowDelete,
                 )
             }
```

**Action 1.5.2**: Update `addLocation()` to set `allowWrite=false` and `allowDelete=false` for new locations. In the `StoredLocation` constructor inside `addLocation()`:

```diff
                 val storedLocation =
                     SettingsRepository.StoredLocation(
                         id = locationId,
                         name = name,
                         path = path,
                         description = trimmedDescription,
                         treeUri = treeUri.toString(),
+                        allowWrite = false,
+                        allowDelete = false,
                     )
```

**Action 1.5.3**: Update `getLocationById()` to map `allowWrite` and `allowDelete`.

```diff
             return StorageLocation(
                 id = stored.id,
                 name = stored.name,
                 path = stored.path,
                 description = stored.description,
                 treeUri = stored.treeUri,
                 availableBytes = queryAvailableBytes(stored.treeUri),
+                allowWrite = stored.allowWrite,
+                allowDelete = stored.allowDelete,
             )
```

**Action 1.5.4**: Implement `isWriteAllowed()`, `isDeleteAllowed()`, `updateLocationAllowWrite()`, `updateLocationAllowDelete()`. Add them after `getLocationById()`, before `isDuplicateTreeUri()`.

```kotlin
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
```

---

### Task 1.6: Update and add SettingsRepositoryImpl tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

**Action 1.6.1**: Update ALL existing test helper/factory code that constructs `StoredLocation` to include `allowWrite` and `allowDelete` fields. Since default values `= false` were added to `StoredLocation`, existing constructor calls will compile without changes, but tests that rely on locations being writable/deletable need explicit values.

**Constructor call sites that MUST be updated** (add `allowWrite = true, allowDelete = true` unless the test specifically tests permission behavior):
- Line ~670 (`addStoredLocation appends to existing list`)
- Line ~693 (`addStoredLocation works on empty list`)
- Line ~717, ~725 (`removeStoredLocation removes by id`)
- Line ~746 (`removeStoredLocation with non-existent id leaves list unchanged`)
- Line ~770 (`updateLocationDescription updates description`)
- Line ~793 (`updateLocationDescription is no-op for non-existent ID`)

Each must include `allowWrite = true, allowDelete = true` to preserve existing test semantics (these tests predate permissions and assume full access).

**Action 1.6.2**: Add tests for migration behavior — stored locations JSON without `allowWrite`/`allowDelete` fields should default to `true`. Add inside the existing `StoredLocations` nested class (or create one if it doesn't exist):

Test: `migration from stored location without permission fields defaults to allowWrite=true and allowDelete=true`
- Arrange: Manually write a JSON array to DataStore with a location that has `id`, `name`, `path`, `description`, `treeUri` but NO `allowWrite`/`allowDelete` fields.
- Act: Call `repository.getStoredLocations()`.
- Assert: The returned location has `allowWrite=true` and `allowDelete=true`.

Test: `migration from stored location with corrupted permission fields defaults to allowWrite=false and allowDelete=false`
- Arrange: Manually write a JSON array to DataStore with a location that has `"allowWrite": "invalid"` and `"allowDelete": 123` (non-boolean values).
- Act: Call `repository.getStoredLocations()`.
- Assert: The returned location has `allowWrite=false` and `allowDelete=false` (fail-closed).

**Action 1.6.2a**: Update any existing raw-JSON-based tests that write stored location JSON directly to DataStore (without `allowWrite`/`allowDelete` fields) and then read back — add assertions verifying the migration defaults (`allowWrite=true, allowDelete=true`). This ensures existing tests validate the migration path.

**Action 1.6.2b**: Update the existing `addStoredLocation` tests (`appends to existing list` at line ~660 and `works on empty list` at line ~688) to also assert `allowWrite` and `allowDelete` values on the returned `StoredLocation` after round-trip. This validates that the new fields are correctly serialized and deserialized in the add workflow.

**Action 1.6.3**: Add tests for serialization round-trip with permission fields.

Test: `stored location with permissions serializes and deserializes correctly`
- Arrange: Add a `StoredLocation` with `allowWrite=false, allowDelete=true`.
- Act: Call `repository.addStoredLocation()`, then `repository.getStoredLocations()`.
- Assert: The returned location has `allowWrite=false, allowDelete=true`.

Test: `stored location with both permissions false serializes correctly`
- Same pattern with `allowWrite=false, allowDelete=false`.

**Action 1.6.4**: Add tests for `updateLocationAllowWrite()` and `updateLocationAllowDelete()`.

Test: `updateLocationAllowWrite updates the flag`
- Arrange: Add a location with `allowWrite=false`.
- Act: Call `repository.updateLocationAllowWrite(locationId, true)`.
- Assert: `getStoredLocations()` returns the location with `allowWrite=true`.

Test: `updateLocationAllowDelete updates the flag`
- Same pattern for `allowDelete`.

Test: `updateLocationAllowWrite for non-existent location does nothing`
- Arrange: Add a location with id "loc1".
- Act: Call `repository.updateLocationAllowWrite("non-existent", true)`.
- Assert: The existing location is unchanged.

Test: `updateLocationAllowDelete for non-existent location does nothing`
- Same pattern for `allowDelete`.

---

### Task 1.7: Update and add StorageLocationProvider tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt`

**Action 1.7.1**: Update ALL existing test helper/factory code that constructs `StoredLocation` to include `allowWrite` and `allowDelete` fields. Since default values `= false` were added to `StoredLocation`, existing constructor calls will compile without changes, but tests that rely on locations being writable/deletable need explicit values.

**Constructor call sites that MUST be updated** (add `allowWrite = true, allowDelete = true` unless the test specifically tests permission behavior):
- Line ~89 (`getAllLocations returns stored locations with enriched metadata`)
- Line ~163 (`getAllLocations returns locations with null availableBytes when queryAvailableBytes fails`)
- Line ~499 (`addLocation rejects duplicate tree URI`)
- Line ~574 (`removeLocation removes entry and releases permission`)
- Line ~614 (`removeLocation handles already-revoked permission gracefully`)
- Line ~699 (`isLocationAuthorized returns true for existing locations`)
- Line ~742 (`getLocationById returns location for known ID`)
- Line ~809 (`getTreeUriForLocation returns URI for known location`)
- Line ~859 (`isDuplicateTreeUri returns true for existing tree URI`)

Each must include `allowWrite = true, allowDelete = true` to preserve existing test semantics.

**Action 1.7.2**: Add tests for `isWriteAllowed()` and `isDeleteAllowed()`. Add a new `@Nested` class `IsWriteAllowed` and `IsDeleteAllowed`.

Tests for `isWriteAllowed`:
- `returns true when location exists and allowWrite is true`
- `returns false when location exists and allowWrite is false`
- `returns false when location does not exist`

Tests for `isDeleteAllowed`:
- `returns true when location exists and allowDelete is true`
- `returns false when location exists and allowDelete is false`
- `returns false when location does not exist`

**Action 1.7.3**: Add tests for `updateLocationAllowWrite()` and `updateLocationAllowDelete()`.

Test: `updateLocationAllowWrite delegates to settings repository`
- Arrange: Mock `settingsRepository.updateLocationAllowWrite()`.
- Act: Call `provider.updateLocationAllowWrite("loc1", true)`.
- Assert: Verify `settingsRepository.updateLocationAllowWrite("loc1", true)` was called.

Test: `updateLocationAllowDelete delegates to settings repository`
- Same pattern for `allowDelete`.

**Action 1.7.4**: Update `getAllLocations` and `getLocationById` tests to verify the new fields are mapped through from `StoredLocation` to `StorageLocation`.

**Action 1.7.5**: Add a test verifying that `addLocation()` creates a `StoredLocation` with `allowWrite=false` and `allowDelete=false`.

Test: `addLocation creates StoredLocation with allowWrite=false and allowDelete=false`
- Arrange: Set up mocks for `DocumentsContract`, `DocumentFile`, `contentResolver.takePersistableUriPermission()`, and `settingsRepository.addStoredLocation()` using a `match {}` block.
- Act: Call `provider.addLocation(treeUri, "test description")`.
- Assert: In the `match {}` block of `addStoredLocation`, verify that the captured `StoredLocation` has `allowWrite == false` and `allowDelete == false`.

---

## User Story 2: Permission Enforcement in FileOperationProvider

**Description**: Add write and delete permission checks in `FileOperationProviderImpl`. Write operations (`writeFile`, `appendFile`, `replaceInFile`, `downloadFromUrl`) check `allowWrite`. Delete operations (`deleteFile`) check `allowDelete`. Denied operations throw `McpToolException.PermissionDenied` with a generic message.

**Acceptance Criteria / Definition of Done**:
- [x] `checkWritePermission(locationId)` method added to `FileOperationProviderImpl`
- [x] `checkDeletePermission(locationId)` method added to `FileOperationProviderImpl`
- [x] `writeFile` calls `checkAuthorization` then `checkWritePermission` before proceeding
- [x] `appendFile` has explicit `checkAuthorization` then `checkWritePermission` at the top (standardized — previously relied on `resolveDocumentFile` for authorization)
- [x] `replaceInFile` has explicit `checkAuthorization` then `checkWritePermission` at the top (standardized — previously relied on `resolveDocumentFile` for authorization)
- [x] `downloadFromUrl` calls `checkAuthorization` then `checkWritePermission` before proceeding
- [x] `deleteFile` has explicit `checkAuthorization` then `checkDeletePermission` at the top (standardized — previously relied on `resolveDocumentFile` for authorization)
- [x] All 5 methods have consistent check ordering: authorization → permission → operation
- [x] All existing `FileOperationProviderTest` tests updated and passing
- [x] Permission denial tests added for all 5 operations
- [x] All targeted tests pass: `./gradlew :app:testDebugUnitTest --tests "*.FileOperationProviderTest"`
- [x] Lint clean on changed files

---

### Task 2.1: Add permission check methods to FileOperationProviderImpl

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt`

**Action 2.1.1**: Add `checkWritePermission()` and `checkDeletePermission()` private methods. Add them after the existing `checkAuthorization()` method (around line 447).

```kotlin
    /**
     * Checks that write operations are allowed for the given location.
     * Throws [McpToolException.PermissionDenied] if not.
     */
    private suspend fun checkWritePermission(locationId: String) {
        if (!storageLocationProvider.isWriteAllowed(locationId)) {
            Log.w(TAG, "Write permission denied for location: $locationId")
            throw McpToolException.PermissionDenied("Write not allowed")
        }
    }

    /**
     * Checks that delete operations are allowed for the given location.
     * Throws [McpToolException.PermissionDenied] if not.
     */
    private suspend fun checkDeletePermission(locationId: String) {
        if (!storageLocationProvider.isDeleteAllowed(locationId)) {
            Log.w(TAG, "Delete permission denied for location: $locationId")
            throw McpToolException.PermissionDenied("Delete not allowed")
        }
    }
```

---

### Task 2.2: Add write permission checks to write operations

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt`

**Action 2.2.1**: In `writeFile()`, add `checkWritePermission(locationId)` immediately after the existing `checkAuthorization(locationId)` call (line ~170).

```diff
         checkAuthorization(locationId)
+        checkWritePermission(locationId)
         val documentFile = ensureParentDirectoriesAndCreateFile(locationId, path)
```

**Action 2.2.2**: In `appendFile()`, add `checkAuthorization(locationId)` and `checkWritePermission(locationId)` as the first two lines of the method body, before `val config = ...` (line ~191). This standardizes the check ordering: authorization first, then permission — consistent with `writeFile` and `downloadFromUrl`.

```diff
     override suspend fun appendFile(
         locationId: String,
         path: String,
         content: String,
     ) {
+        checkAuthorization(locationId)
+        checkWritePermission(locationId)
         val config = settingsRepository.getServerConfig()
```

Note: `appendFile` previously relied on `resolveDocumentFile` for the authorization check. Adding `checkAuthorization` explicitly here ensures consistent error ordering across all operations: an unauthorized location always returns "not found" before "write not allowed". The subsequent `resolveDocumentFile` call will still call `checkAuthorization` internally (idempotent, no harm).

**Action 2.2.3**: In `replaceInFile()`, add `checkAuthorization(locationId)` and `checkWritePermission(locationId)` as the first two lines of the method body, before `val documentFile = resolveDocumentFile(...)` (line ~254). Same rationale as `appendFile`.

```diff
     override suspend fun replaceInFile(
         locationId: String,
         path: String,
         oldString: String,
         newString: String,
         replaceAll: Boolean,
     ): FileReplaceResult {
+        checkAuthorization(locationId)
+        checkWritePermission(locationId)
         val documentFile =
             resolveDocumentFile(locationId, path)
```

**Action 2.2.4**: In `downloadFromUrl()`, add `checkWritePermission(locationId)` immediately after the existing `checkAuthorization(locationId)` call (line ~308).

```diff
         checkAuthorization(locationId)
+        checkWritePermission(locationId)
         val config = settingsRepository.getServerConfig()
```

---

### Task 2.3: Add delete permission check to deleteFile

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt`

**Action 2.3.1**: In `deleteFile()`, add `checkAuthorization(locationId)` and `checkDeletePermission(locationId)` as the first two lines of the method body, before `val documentFile = resolveDocumentFile(...)` (line ~409). Same rationale: standardize ordering to authorization-first, then permission.

```diff
     override suspend fun deleteFile(
         locationId: String,
         path: String,
     ) {
+        checkAuthorization(locationId)
+        checkDeletePermission(locationId)
         val documentFile =
             resolveDocumentFile(locationId, path)
```

---

### Task 2.4: Update existing FileOperationProvider tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderTest.kt`

**Action 2.4.1**: The cleanest approach is to extend the `setupAuthorizedLocation()` helper (line ~93) to also set up default `isWriteAllowed` and `isDeleteAllowed` mocks returning `true`. This automatically covers **all** existing tests that use `setupAuthorizedLocation()`:

```diff
     private fun setupAuthorizedLocation(locationId: String) {
         coEvery { mockStorageLocationProvider.isLocationAuthorized(locationId) } returns true
         coEvery { mockStorageLocationProvider.getTreeUriForLocation(locationId) } returns mockTreeUri
+        coEvery { mockStorageLocationProvider.isWriteAllowed(locationId) } returns true
+        coEvery { mockStorageLocationProvider.isDeleteAllowed(locationId) } returns true
     }
```

This approach covers all ~24 call sites including:
- `setupReplaceFile()` (line ~630), which calls `setupAuthorizedLocation("loc1")` — used by 4 `replaceInFile` tests (lines ~659, ~680, ~701, and the helper)
- All `appendFile` tests including failure tests (`appendFile throws ActionFailed when resulting size exceeds limit` at line ~568, `appendFile throws ActionFailed with hint when provider does not support append` at line ~538) — these MUST have `isWriteAllowed` mocked because after the plan changes, `checkWritePermission` runs **before** the size check and append-support check
- All `downloadFromUrl` tests
- All `deleteFile` tests

**CRITICAL**: The `appendFile`, `replaceInFile`, and `deleteFile` failure-path tests (size limit, unsupported append, etc.) that previously didn't need write/delete permission mocks now DO, because the permission check runs before those failure conditions.

**Action 2.4.2**: Tests that test **authorization failure** itself (using `setupUnauthorizedLocation()`) do NOT need the permission mock — authorization fails before reaching the permission check. No changes needed for those tests.

**Important**: For `appendFile`, `replaceInFile`, and `deleteFile`, the execution order changes because the plan adds explicit `checkAuthorization` + permission check at the top of these methods. Previously, authorization was checked inside `resolveDocumentFile` (which ran after `getServerConfig` for `appendFile`). Now authorization runs first. This means:
- Tests that previously set up an authorized location AND reached file operations will now also need the `isWriteAllowed`/`isDeleteAllowed` mock — handled by the `setupAuthorizedLocation()` helper update.
- Tests that test authorization failure itself don't need the permission mock (authorization fails before reaching the permission check).

---

### Task 2.5: Add permission denial tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderTest.kt`

**Action 2.5.1**: Add a new `@Nested` class `WritePermissionDenied` with tests for each write operation.

Tests (all follow Arrange-Act-Assert pattern). All tests mock `isLocationAuthorized` → `true` because the standardized ordering checks authorization first — the test must pass authorization to reach the permission check.

1. `writeFile throws PermissionDenied when write not allowed`
   - Arrange: Mock `settingsRepository.getServerConfig()` → valid `ServerConfig` (needed because `writeFile` calls `getServerConfig()` at line 160 **before** `checkAuthorization` at line 170 — without this mock, strict MockK throws before reaching the permission check). Mock `isLocationAuthorized` → `true`, `isWriteAllowed` → `false`. Provide content small enough to pass the size-limit check.
   - Act/Assert: Call `writeFile(...)`, assertThrows `McpToolException.PermissionDenied`, message is `"Write not allowed"`

2. `appendFile throws PermissionDenied when write not allowed`
   - Arrange: Mock `isLocationAuthorized` → `true`, `isWriteAllowed` → `false`
   - Act/Assert: Call `appendFile(...)`, assertThrows `McpToolException.PermissionDenied`, message is `"Write not allowed"`

3. `replaceInFile throws PermissionDenied when write not allowed`
   - Arrange: Mock `isLocationAuthorized` → `true`, `isWriteAllowed` → `false`
   - Act/Assert: Call `replaceInFile(...)`, assertThrows `McpToolException.PermissionDenied`, message is `"Write not allowed"`

4. `downloadFromUrl throws PermissionDenied when write not allowed`
   - Arrange: Mock `isLocationAuthorized` → `true`, `isWriteAllowed` → `false`
   - Act/Assert: Call `downloadFromUrl(...)`, assertThrows `McpToolException.PermissionDenied`, message is `"Write not allowed"`

**Action 2.5.2**: Add a new `@Nested` class `DeletePermissionDenied`.

Test: `deleteFile throws PermissionDenied when delete not allowed`
   - Arrange: Mock `isLocationAuthorized` → `true`, `isDeleteAllowed` → `false`
   - Act/Assert: Call `deleteFile(...)`, assertThrows `McpToolException.PermissionDenied`, message is `"Delete not allowed"`

---

## User Story 3: MCP Tool Output & Integration Tests

**Description**: Update the `list_storage_locations` MCP tool output to include `allow_read`, `allow_write`, `allow_delete` fields. Add integration tests for permission enforcement error propagation.

**Acceptance Criteria / Definition of Done**:
- [ ] `list_storage_locations` MCP output includes `allow_read: true`, `allow_write: true/false`, `allow_delete: true/false` for each location
- [ ] Existing `list_storage_locations` integration test updated to verify new fields
- [ ] Integration tests added for write permission denial (all 4 write tools)
- [ ] Integration test added for delete permission denial
- [ ] All targeted tests pass: `./gradlew :app:testDebugUnitTest --tests "*.FileToolsIntegrationTest"`
- [ ] Lint clean on changed files

---

### Task 3.1: Update ListStorageLocationsHandler MCP output

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt`

**Action 3.1.1**: In `ListStorageLocationsHandler.execute()`, add `allow_read`, `allow_write`, `allow_delete` fields to each location JSON object. Add after the `available_bytes` field.

```diff
                                 if (location.availableBytes != null) {
                                     put("available_bytes", location.availableBytes)
                                 } else {
                                     put("available_bytes", JsonNull)
                                 }
+                                put("allow_read", true)
+                                put("allow_write", location.allowWrite)
+                                put("allow_delete", location.allowDelete)
```

---

### Task 3.2: Update existing list_storage_locations integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`

**Action 3.2.1**: Update the `list_storage_locations returns available locations` test. The mock `StorageLocation` constructor call needs to include `allowWrite` and `allowDelete` fields. Add assertions for the new fields in the response.

Update the `StorageLocation` mock data:
```diff
                     StorageLocation(
                         id = "loc1",
                         name = "Downloads",
                         path = "/",
                         description = "Downloaded files",
                         treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
                         availableBytes = 1024000L,
+                        allowWrite = true,
+                        allowDelete = false,
                     ),
```

Add assertions:
```kotlin
assertTrue(text.contains("\"allow_read\":true"))
assertTrue(text.contains("\"allow_write\":true"))
assertTrue(text.contains("\"allow_delete\":false"))
```

---

### Task 3.3: Add integration tests for write permission denial

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`

**Action 3.3.1**: Add integration tests for each write tool that verify the MCP client receives an error when the `FileOperationProvider` throws `McpToolException.PermissionDenied("Write not allowed")`.

Test 1: `write_file returns error when write not allowed`
- Arrange: Mock `fileOperationProvider.writeFile(any(), any(), any())` to throw `McpToolException.PermissionDenied("Write not allowed")`
- Act: Call tool `android_write_file` with valid arguments
- Assert: `result.isError == true`, content text contains `"Write not allowed"`

Test 2: `append_file returns error when write not allowed`
- Arrange: Mock `fileOperationProvider.appendFile(any(), any(), any())` to throw `McpToolException.PermissionDenied("Write not allowed")`
- Act: Call tool `android_append_file` with valid arguments
- Assert: `result.isError == true`, content text contains `"Write not allowed"`

Test 3: `file_replace returns error when write not allowed`
- Arrange: Mock `fileOperationProvider.replaceInFile(any(), any(), any(), any(), any())` to throw `McpToolException.PermissionDenied("Write not allowed")`
- Act: Call tool `android_file_replace` with valid arguments
- Assert: `result.isError == true`, content text contains `"Write not allowed"`

Test 4: `download_from_url returns error when write not allowed`
- Arrange: Mock `fileOperationProvider.downloadFromUrl(any(), any(), any())` to throw `McpToolException.PermissionDenied("Write not allowed")`
- Act: Call tool `android_download_from_url` with valid arguments
- Assert: `result.isError == true`, content text contains `"Write not allowed"`

---

### Task 3.4: Add integration test for delete permission denial

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`

**Action 3.4.1**: Add integration test for delete permission denial.

Test: `delete_file returns error when delete not allowed`
- Arrange: Mock `fileOperationProvider.deleteFile(any(), any())` to throw `McpToolException.PermissionDenied("Delete not allowed")`
- Act: Call tool `android_delete_file` with valid arguments
- Assert: `result.isError == true`, content text contains `"Delete not allowed"`

---

## User Story 4: UI for Permission Management

**Description**: Add inline permission toggles (Allow Write, Allow Delete) to each storage location row in the UI. Users can toggle these while the server is running. Permissions are NOT set in the Add Location dialog — only editable from the location row.

**Acceptance Criteria / Definition of Done**:
- [ ] Each storage location row shows "Allow Write" and "Allow Delete" toggles (Switch composables)
- [ ] Toggles reflect the current permission state from `StorageLocation`
- [ ] Toggling calls `MainViewModel.updateLocationAllowWrite()` / `updateLocationAllowDelete()`
- [ ] Toggles are always enabled (no dependency on server running state)
- [ ] String resources added for toggle labels
- [ ] `MainViewModel` has `updateLocationAllowWrite()` and `updateLocationAllowDelete()` methods
- [ ] `HomeScreen` wires up the new callbacks
- [ ] `MainViewModelTest` updated with tests for the new ViewModel methods
- [ ] Lint clean on changed files

---

### Task 4.1: Add string resources

**File**: `app/src/main/res/values/strings.xml`

**Action 4.1.1**: Add string resources for the permission toggle labels. Add them after the existing `storage_location_delete_dialog_cancel` entry.

```xml
    <string name="storage_location_allow_write_label">Allow Write</string>
    <string name="storage_location_allow_delete_label">Allow Delete</string>
```

---

### Task 4.2: Update StorageLocationsSection UI

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/StorageLocationsSection.kt`

**Action 4.2.1**: Add `onAllowWriteChange` and `onAllowDeleteChange` callback parameters to `StorageLocationsSection`.

```diff
 fun StorageLocationsSection(
     storageLocations: List<StorageLocation>,
     ...
     onEditDescription: (StorageLocation) -> Unit,
     onDeleteLocation: (StorageLocation) -> Unit,
+    onAllowWriteChange: (StorageLocation, Boolean) -> Unit,
+    onAllowDeleteChange: (StorageLocation, Boolean) -> Unit,
     onFileSizeLimitChange: (String) -> Unit,
```

**Action 4.2.2**: Pass the new callbacks through to `StorageLocationRow`. Update the `forEach` call:

```diff
                 storageLocations.forEach { location ->
                     StorageLocationRow(
                         location = location,
                         onEdit = { onEditDescription(location) },
                         onDelete = { onDeleteLocation(location) },
+                        onAllowWriteChange = { enabled -> onAllowWriteChange(location, enabled) },
+                        onAllowDeleteChange = { enabled -> onAllowDeleteChange(location, enabled) },
                     )
                 }
```

**Action 4.2.3**: Update `StorageLocationRow` to accept and render permission toggles. This requires a **structural change**: the current outer `Row` must become a `Column` so the info row and toggles row can stack vertically.

First, add the required import at the top of the file. Alphabetically, `Arrangement` sorts before `Column` (line 5):

```diff
+import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Column
```

Note: `ktlintFormat` will auto-sort imports if placed elsewhere, but the correct alphabetical position is before `Column`.

Then, replace the entire `StorageLocationRow` function. The current implementation:

```kotlin
@Composable
private fun StorageLocationRow(
    location: StorageLocation,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = location.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = location.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (location.description.isNotEmpty()) {
                Text(
                    text = location.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.storage_location_edit_dialog_title),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.storage_location_delete_dialog_title),
            )
        }
    }
}
```

Becomes:

```kotlin
@Suppress("LongMethod")
@Composable
private fun StorageLocationRow(
    location: StorageLocation,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAllowWriteChange: (Boolean) -> Unit,
    onAllowDeleteChange: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = location.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = location.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (location.description.isNotEmpty()) {
                    Text(
                        text = location.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.storage_location_edit_dialog_title),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.storage_location_delete_dialog_title),
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_write_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowWrite,
                    onCheckedChange = onAllowWriteChange,
                    modifier = Modifier.height(24.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_location_allow_delete_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = location.allowDelete,
                    onCheckedChange = onAllowDeleteChange,
                    modifier = Modifier.height(24.dp),
                )
            }
        }
    }
}
```

Key structural changes:
- Outer container changed from `Row` to `Column` (for vertical stacking of info row + toggles row).
- The `padding(vertical = 4.dp)` moved to the outer `Column`.
- The original `Row` with name/path/description and edit/delete icons is now a nested `Row` inside the `Column`, with `modifier = Modifier.fillMaxWidth()`.
- A new `Row` with `Arrangement.spacedBy(16.dp)` is added below for the permission toggles.
- The `import Arrangement` must be added at the top of the file (see diff above).

**Action 4.2.4**: Update the `@Preview` composable to include the new fields in `StorageLocation` and the new callbacks.

Update BOTH `StorageLocation` constructor calls in preview (lines ~249 and ~257):

First location (Internal Storage):
```diff
                     StorageLocation(
                         id = "com.android.externalstorage/primary",
                         name = "Internal Storage",
                         path = "/",
                         description = "",
                         treeUri = "content://com.android.externalstorage.documents/tree/primary%3A",
                         availableBytes = null,
+                        allowWrite = true,
+                        allowDelete = false,
                     ),
```

Second location (Downloads):
```diff
                     StorageLocation(
                         id = "com.android.providers.downloads.documents/downloads",
                         name = "Downloads",
                         path = "/",
                         description = "Downloaded files",
                         treeUri = "content://com.android.providers.downloads.documents/tree/downloads",
                         availableBytes = null,
+                        allowWrite = false,
+                        allowDelete = false,
                     ),
```

Add no-op callbacks:
```diff
             onDeleteLocation = {},
+            onAllowWriteChange = { _, _ -> },
+            onAllowDeleteChange = { _, _ -> },
             onFileSizeLimitChange = {},
```

---

### Task 4.3: Add ViewModel methods

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

**Action 4.3.1**: Add `updateLocationAllowWrite()` and `updateLocationAllowDelete()` methods. Add them after the existing `updateLocationDescription()` method.

```kotlin
    @Suppress("TooGenericExceptionCaught")
    fun updateLocationAllowWrite(
        locationId: String,
        allowWrite: Boolean,
    ) {
        viewModelScope.launch(ioDispatcher) {
            try {
                storageLocationProvider.updateLocationAllowWrite(locationId, allowWrite)
                _storageLocations.value =
                    _storageLocations.value.map { loc ->
                        if (loc.id == locationId) loc.copy(allowWrite = allowWrite) else loc
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update allowWrite for $locationId", e)
                refreshStorageLocations()
                _storageError.tryEmit("Failed to update write permission: ${e.message}")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun updateLocationAllowDelete(
        locationId: String,
        allowDelete: Boolean,
    ) {
        viewModelScope.launch(ioDispatcher) {
            try {
                storageLocationProvider.updateLocationAllowDelete(locationId, allowDelete)
                _storageLocations.value =
                    _storageLocations.value.map { loc ->
                        if (loc.id == locationId) loc.copy(allowDelete = allowDelete) else loc
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update allowDelete for $locationId", e)
                refreshStorageLocations()
                _storageError.tryEmit("Failed to update delete permission: ${e.message}")
            }
        }
    }
```

**Performance rationale**: Using optimistic in-place update instead of `refreshStorageLocations()` avoids re-querying `queryAvailableBytes()` (ContentResolver I/O) for ALL locations when only a boolean changed. On success, only the changed field is updated in the existing list. On failure, a full refresh is performed to ensure consistency.

---

### Task 4.4: Wire up callbacks in HomeScreen

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`

**Action 4.4.1**: Add `onAllowWriteChange` and `onAllowDeleteChange` callbacks to the `StorageLocationsSection` call. Add them after `onDeleteLocation`.

```diff
             StorageLocationsSection(
                 ...
                 onDeleteLocation = { location ->
                     deleteDialogLocation = location
                     showDeleteDialog = true
                 },
+                onAllowWriteChange = { location, enabled ->
+                    viewModel.updateLocationAllowWrite(location.id, enabled)
+                },
+                onAllowDeleteChange = { location, enabled ->
+                    viewModel.updateLocationAllowDelete(location.id, enabled)
+                },
                 onFileSizeLimitChange = viewModel::updateFileSizeLimit,
```

---

### Task 4.5: Add ViewModel tests for permission update methods

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`

**Targeted test command**: `./gradlew :app:testDebugUnitTest --tests "*.MainViewModelTest"`

**Action 4.5.1**: Update existing test code that constructs `StorageLocation` to include `allowWrite` and `allowDelete` fields. Since default values `= false` were added to `StorageLocation`, compilation won't break, but for test correctness, update:
- Line ~435 (`refreshStorageLocations updates storageLocations state`): Add `allowWrite = true, allowDelete = true` (or appropriate values for the test scenario).

**Action 4.5.2**: Add tests for `updateLocationAllowWrite()`.

Test: `updateLocationAllowWrite calls provider and optimistically updates state`
- Arrange: Mock `storageLocationProvider.getAllLocations()` → list with one `StorageLocation(id="loc1", allowWrite=false, allowDelete=false, ...)`. Mock `storageLocationProvider.updateLocationAllowWrite()` to succeed. Call `viewModel.refreshStorageLocations()` + `advanceUntilIdle()` to populate `_storageLocations` (private field). Then clear recorded mock calls: `clearMocks(storageLocationProvider, answers = false, recordedCalls = true)` (keeps stubs, clears call records for clean verification).
- Act: Call `viewModel.updateLocationAllowWrite("loc1", true)` + `advanceUntilIdle()`.
- Assert: Verify `storageLocationProvider.updateLocationAllowWrite("loc1", true)` was called. Verify `storageLocations` state contains the location with `allowWrite=true` (optimistic update). Verify `storageLocationProvider.getAllLocations()` was NOT called (no full refresh on success — verified cleanly after clearing recorded calls).

Test: `updateLocationAllowWrite emits error and refreshes on failure`
- Arrange: Mock `storageLocationProvider.getAllLocations()` → list with location. Call `refreshStorageLocations()` + `advanceUntilIdle()` to populate state. Then clear recorded mock calls. Mock `storageLocationProvider.updateLocationAllowWrite()` to throw `RuntimeException("test error")`. Re-mock `storageLocationProvider.getAllLocations()` for the fallback refresh.
- Act: Call `viewModel.updateLocationAllowWrite("loc1", true)` + `advanceUntilIdle()`.
- Assert: Verify `storageError` emits a message containing "Failed to update write permission". Verify `storageLocationProvider.getAllLocations()` was called exactly once (full refresh on failure).

**Action 4.5.3**: Add tests for `updateLocationAllowDelete()`.

Test: `updateLocationAllowDelete calls provider and optimistically updates state`
- Same pattern as `updateLocationAllowWrite` but for `allowDelete`.

Test: `updateLocationAllowDelete emits error and refreshes on failure`
- Same pattern as `updateLocationAllowWrite` error test but for `allowDelete`.

---

## User Story 5: Documentation

**Description**: Update MCP_TOOLS.md and PROJECT.md to document the permission model and updated `list_storage_locations` output format.

**Acceptance Criteria / Definition of Done**:
- [ ] `MCP_TOOLS.md` updated: `list_storage_locations` output includes `allow_read`, `allow_write`, `allow_delete` fields
- [ ] `MCP_TOOLS.md` updated: write/delete tools document the permission denied error
- [ ] `PROJECT.md` updated: documents the storage location permission model (new section after SAF documentation)
- [ ] `PROJECT.md` updated: Permission Security section references the per-location permission model
- [ ] Documentation is accurate and matches implementation

---

### Task 5.1: Update MCP_TOOLS.md

**File**: `docs/MCP_TOOLS.md`

**Action 5.1.1**: Update the `android_list_storage_locations` example response to include the new fields.

Update the example JSON in the response section (around line 1678):
```diff
-        "text": "[{\"id\":\"com.android.externalstorage.documents/primary:\",\"name\":\"Internal Storage\",\"path\":\"/\",\"description\":\"Main device storage\",\"available_bytes\":52428800000}]"
+        "text": "[{\"id\":\"com.android.externalstorage.documents/primary:\",\"name\":\"Internal Storage\",\"path\":\"/\",\"description\":\"Main device storage\",\"available_bytes\":52428800000,\"allow_read\":true,\"allow_write\":false,\"allow_delete\":false}]"
```

**Action 5.1.2**: Add a description of the permission fields after the example response, before the Error Cases section.

```markdown
**Response Fields** (per location):
| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique location identifier |
| `name` | string | Display name of the directory |
| `path` | string | Human-readable path |
| `description` | string | User-provided description |
| `available_bytes` | number \| null | Available space in bytes, or null if unknown |
| `allow_read` | boolean | Always `true` — read access is always permitted |
| `allow_write` | boolean | Whether write operations are permitted (write_file, append_file, file_replace, download_from_url) |
| `allow_delete` | boolean | Whether delete operations are permitted (delete_file) |
```

**Action 5.1.3**: Update the Error Cases section for write tools (`android_write_file`, `android_append_file`, `android_file_replace`, `android_download_from_url`) to include the permission denied error.

Add to each write tool's Error Cases:
```markdown
- **Permission denied**: Write not allowed
```

**Action 5.1.4**: Update the Error Cases section for `android_delete_file` to include the permission denied error.

```markdown
- **Permission denied**: Delete not allowed
```

Note: The error case descriptions match the exact error messages returned by `McpToolException.PermissionDenied` — `"Write not allowed"` and `"Delete not allowed"` (no additional suffix).

---

### Task 5.2: Update PROJECT.md

**File**: `docs/PROJECT.md`

**Action 5.2.1**: Add a section documenting the storage location permission model. Insert it after the "Known constraint — Android URI permission limit" paragraph (line ~349 of `docs/PROJECT.md`) and before the `### Background Restrictions & Memory Management` heading (line ~351). This places it within the existing Storage Access Framework documentation block.

```markdown
### Storage Location Permissions

Each storage location has per-location permission flags controlling what MCP tools can do:

| Permission | Default (new) | Default (migrated) | Description |
|---|---|---|---|
| Read | Always `true` | Always `true` | List files and read file content. Cannot be disabled. |
| Write | `false` | `true` | Write, append, replace file content, download files. |
| Delete | `false` | `true` | Delete files. |

- Permissions are managed per-location via toggle switches in the UI.
- Permissions can be changed while the MCP server is running.
- When a tool attempts an operation not permitted by the location's permissions, a generic error is returned ("Write not allowed" or "Delete not allowed") with no guidance on how to enable the permission.
- The `list_storage_locations` MCP tool exposes `allow_read`, `allow_write`, `allow_delete` for each location.
```

**Action 5.2.2**: Update the `### Permission Security` section (line ~557 of `docs/PROJECT.md`) to mention the per-location permission model. Append to the existing paragraph:

```diff
 Only necessary permissions: `INTERNET`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES` (app listing), `KILL_BACKGROUND_PROCESSES` (app closing), Accessibility Service (user-granted via Settings), SAF tree URI permissions (user-granted per storage location via system file picker). Display clear explanations before requesting.
+Per-location read/write/delete permissions are enforced by `FileOperationProvider` — see the Storage Location Permissions section above.
```

---

## User Story 6: Full Verification

**Description**: Run the complete test suite, linting, and build. Review all changes from the ground up to verify correctness and completeness.

**Acceptance Criteria / Definition of Done**:
- [ ] `make lint` passes with no new warnings or errors
- [ ] `make test-unit` passes (includes both unit and JVM integration tests)
- [ ] `./gradlew build` succeeds with no errors or warnings
- [ ] Manual review: all 18 files changed match the plan
- [ ] Manual review: no TODOs, no placeholder code, no dead code
- [ ] Manual review: permission fields propagate correctly: DataStore → StoredLocation → StorageLocation → MCP output
- [ ] Manual review: permission checks enforce correctly: write tools check `allowWrite`, delete tool checks `allowDelete`
- [ ] Manual review: existing tests updated wherever `StorageLocation` or `StoredLocation` is constructed
- [ ] Manual review: error messages are generic ("Write not allowed" / "Delete not allowed")
- [ ] Manual review: UI toggles work without requiring server stop
- [ ] Manual review: documentation is accurate
