# Plan 18: File Operations and App Management MCP Tools

## Overview

Implement 11 new MCP tools across 2 new tool categories:

**File Tools** (8 tools):
- `list_storage_locations` — Enumerate available SAF storage roots with authorization status
- `list_files` — List files/directories with offset/limit (max 200)
- `read_file` — Read text file with offset/limit (max 200 lines)
- `write_file` — Write/create text file (auto-creates directories, empty content = empty file)
- `append_file` — Append to text file (try native, fail with hint if unsupported)
- `file_replace` — Literal string replacement with replace_all boolean
- `download_from_url` — Download a file from a URL to an authorized storage location
- `delete_file` — Delete a single file (not directories)

**App Management Tools** (3 tools):
- `open_app` — Open an application by package ID
- `list_apps` — List installed apps with optional filtering (user/system/all, name query)
- `close_app` — Kill background app process (tool description hints to use `press_home` first for foreground apps)

**UI Changes**:
- New "Storage Locations" section in main screen showing available SAF roots with toggle switches
- Configurable file size limit setting (default 50 MB, applies to all file operations)
- SAF `ACTION_OPEN_DOCUMENT_TREE` authorization flow per location

---

## Decisions Log

All decisions agreed upon during discussion:

| # | Topic | Decision |
|---|-------|----------|
| 1 | File access approach | Android Storage Access Framework (SAF) for unified access to physical and virtual storage |
| 2 | Virtual path system | `{location_id}/{relative_path}` — location_id is `authority/rootId` |
| 3 | Authorization flow | Toggle in UI → system file picker (mandatory Android security requirement) → persistent URI permission |
| 4 | Append on virtual providers | Try native `"wa"` mode; if fails, return exception with hint to write entire file |
| 5 | String replace locking | Try advisory file lock for local providers; best-effort without lock for virtual providers |
| 6 | String replace type | Literal only (no regex), with `replace_all` boolean |
| 7 | Close app approach | `killBackgroundProcesses()` only; tool description says to use `press_home` first for foreground apps |
| 8 | Max limits | 200 for both `read_file` (lines) and `list_files` (entries) |
| 9 | File size limit | 50 MB default, configurable in UI, one setting for all operations |
| 10 | Delete scope | Single files only, no directories |
| 11 | Write file behavior | Creates file if not exists, creates parent directories, overwrites if exists, empty content = empty file |
| 12 | Storage UI placement | Inline in main screen (refactor later) |
| 13 | Android permissions | Add `QUERY_ALL_PACKAGES` and `KILL_BACKGROUND_PROCESSES` |
| 14 | Encoding | UTF-8 for text operations |
| 15 | Binary read/write | Scrapped — base64 in MCP transport burns too many tokens |
| 16 | Download from URL | Added as alternative to binary write; downloads file from URL to storage location |
| 17 | Download HTTP settings | Two user-gated settings: allow HTTP downloads (default off), allow unverified HTTPS certs (default off) |
| 18 | Download timeout | 60 seconds default, configurable in UI (range 10-300 seconds) |
| 19 | Download implementation | `java.net.HttpURLConnection` (no new dependencies needed) |

---

## Git Workflow

1. **Branch creation**:
   ```bash
   git checkout main && git pull origin main
   git checkout -b feat/file-operations-and-app-management-tools
   ```

2. **Commits** (ordered, logical sequence):
   1. `feat: add data models for storage locations, file info, and app info`
   2. `feat: add storage location provider interface and implementation`
   3. `feat: add file operation provider interface and implementation`
   4. `feat: add app manager interface and implementation`
   5. `feat: update settings for file size limit and authorized locations`
   6. `feat: update manifest permissions and DI module bindings`
   7. `feat: add McpToolUtils helper methods for int and boolean params`
   8. `feat: add storage locations UI section with authorization flow`
   9. `feat: implement file operation MCP tools`
   10. `feat: implement app management MCP tools`
   11. `test: add unit tests for storage, file, and app providers`
   12. `test: add integration tests for file and app management tools`
   13. `docs: update MCP_TOOLS.md and PROJECT.md with new tools`

3. **Pull Request**: After all quality gates pass:
   ```bash
   git push -u origin HEAD
   gh pr create --title "feat: file operations and app management MCP tools" --body "..."
   gh pr edit <PR#> --add-reviewer copilot
   ```

4. **AI attribution prohibition**: No references to Claude, Anthropic, or AI tooling in commits or PR.

---

## User Story 1: Storage & App Management Infrastructure

**As a developer**, I want the infrastructure layer (data models, service interfaces, implementations, DI, permissions) in place so that MCP tools and UI can be built on top of it.

**Acceptance Criteria**:
- All data models created with correct fields and documentation
- All service interfaces defined with suspend functions returning Result types
- All implementations created with correct Android API usage
- SettingsRepository extended for file size limit and authorized locations
- DI modules updated with new bindings
- AndroidManifest updated with required permissions
- McpToolUtils extended with `optionalInt` and `optionalBoolean` helpers
- Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 1.1: Create Data Models

**Definition of Done**: Four new data model files exist with correct fields, package, and documentation.

#### Action 1.1.1: Create `StorageLocation.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/StorageLocation.kt`
- **Type**: New file
- **Description**: Data class representing a storage location (SAF document root).

```kotlin
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
```

#### Action 1.1.2: Create `FileInfo.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/FileInfo.kt`
- **Type**: New file
- **Description**: Data class for file/directory listing entries.

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents a file or directory entry returned by file listing operations.
 *
 * @property name The file/directory name.
 * @property path The full virtual path: "{location_id}/{relative_path}".
 * @property isDirectory True if this entry is a directory, false if a file.
 * @property size File size in bytes (0 for directories).
 * @property lastModified Last modification timestamp in milliseconds since epoch, or null if unknown.
 * @property mimeType The MIME type of the file, or null for directories/unknown.
 */
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long?,
    val mimeType: String?,
)
```

#### Action 1.1.3: Create `AppInfo.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/AppInfo.kt`
- **Type**: New file
- **Description**: Data class for installed application entries.

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents an installed application on the device.
 *
 * @property packageId The application package name (e.g., "com.example.app").
 * @property name The user-visible application label.
 * @property versionName The version name string (e.g., "1.2.3"), or null if unavailable.
 * @property versionCode The numeric version code.
 * @property isSystemApp True if this is a pre-installed system application.
 */
data class AppInfo(
    val packageId: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean,
)
```

#### Action 1.1.4: Create `AppFilter.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/AppFilter.kt`
- **Type**: New file
- **Description**: Enum for app listing filter options.

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Filter options for listing installed applications.
 */
enum class AppFilter {
    /** Include all installed applications. */
    ALL,

    /** Include only user-installed applications. */
    USER,

    /** Include only pre-installed system applications. */
    SYSTEM,
}
```

---

### Task 1.2: Create StorageLocationProvider Interface and Implementation

**Definition of Done**: Interface and implementation exist for enumerating SAF document provider roots and managing authorization state. Implementation queries `DocumentsContract` for available roots and reads/writes authorization state from DataStore via SettingsRepository.

#### Action 1.2.1: Create `StorageLocationProvider.kt` interface

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProvider.kt`
- **Type**: New file
- **Description**: Interface for storage location discovery and authorization management.

```kotlin
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

// NOTE: buildInitialPickerUri is NOT on this interface.
// The ViewModel computes the initial picker URI directly from its cached
// storageLocations StateFlow using DocumentsContract.buildDocumentUri(),
// avoiding the need for a synchronous lookup or internal caching in the provider.
```

#### Action 1.2.2: Create `StorageLocationProviderImpl.kt` implementation

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderImpl.kt`
- **Type**: New file
- **Description**: Implementation that queries `DocumentsContract` for provider roots and manages authorization via `SettingsRepository`.

Key implementation details:
- Constructor: `@Inject constructor(@ApplicationContext private val context: Context, private val settingsRepository: SettingsRepository)`
- `getAvailableLocations()`:
  1. Get all installed document providers via `packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)`
  2. For each provider, query `DocumentsContract.buildRootsUri(authority)` using `contentResolver.query()`
  3. Read columns: `Root.COLUMN_ROOT_ID`, `Root.COLUMN_TITLE`, `Root.COLUMN_DOCUMENT_ID`, `Root.COLUMN_AVAILABLE_BYTES`, `Root.COLUMN_ICON`
  4. Build `StorageLocation` for each root with `id = "$authority/$rootId"`
  5. Cross-reference with authorized locations from SettingsRepository
  6. Return combined list
- `authorizeLocation()`:
  1. Take persistable URI permission: `contentResolver.takePersistableUriPermission(treeUri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`
  2. Call `settingsRepository.addAuthorizedLocation(locationId, treeUri.toString())`
- `deauthorizeLocation()`:
  1. Get stored tree URI from SettingsRepository
  2. Release persistable URI permission: `contentResolver.releasePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`
  3. Call `settingsRepository.removeAuthorizedLocation(locationId)`
- Provider name resolution: Use `packageManager.getApplicationLabel()` for the provider's package
- NOTE: `buildInitialPickerUri` is NOT in this class — the ViewModel computes the initial picker URI directly from its cached state (see Task 2.2)

---

### Task 1.3: Create FileOperationProvider Interface and Implementation

**Definition of Done**: Interface and implementation exist for all file operations through SAF. Implementation uses `ContentResolver` and `DocumentFile` APIs.

#### Action 1.3.1: Create `FileOperationProvider.kt` interface

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProvider.kt`
- **Type**: New file
- **Description**: Interface for file operations via SAF.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.data.model.FileInfo

/**
 * Result from a file listing operation.
 *
 * @property files The list of file entries in the requested range.
 * @property totalCount The total number of entries in the directory.
 * @property hasMore Whether there are more entries beyond the requested range.
 */
data class FileListResult(
    val files: List<FileInfo>,
    val totalCount: Int,
    val hasMore: Boolean,
)

/**
 * Result from a file read operation.
 *
 * @property content The text content of the requested lines.
 * @property totalLines The total number of lines in the file.
 * @property hasMore Whether there are more lines beyond the requested range.
 * @property startLine The 1-based line number of the first returned line.
 * @property endLine The 1-based line number of the last returned line.
 */
data class FileReadResult(
    val content: String,
    val totalLines: Int,
    val hasMore: Boolean,
    val startLine: Int,
    val endLine: Int,
)

/**
 * Result from a file string replacement operation.
 *
 * @property replacementCount The number of replacements made.
 */
data class FileReplaceResult(
    val replacementCount: Int,
)

/**
 * Provides file operations via the Storage Access Framework.
 *
 * All operations work with virtual paths: "{location_id}/{relative_path}".
 * The location_id must reference an authorized storage location.
 *
 * All operations check the configured file size limit before proceeding.
 */
interface FileOperationProvider {
    /**
     * Lists files in a directory.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path within the location (empty string for root).
     * @param offset Number of entries to skip (0-based).
     * @param limit Maximum number of entries to return (capped at MAX_LIST_ENTRIES).
     * @return [FileListResult] with file entries and pagination info.
     */
    suspend fun listFiles(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileListResult

    /**
     * Reads a text file with line-based pagination.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param offset 1-based line number to start reading from.
     * @param limit Maximum number of lines to return (capped at MAX_READ_LINES).
     * @return [FileReadResult] with content and pagination info.
     */
    suspend fun readFile(
        locationId: String,
        path: String,
        offset: Int,
        limit: Int,
    ): FileReadResult

    /**
     * Writes text content to a file. Creates the file and parent directories
     * if they don't exist. Overwrites existing content.
     * Empty content creates an empty file.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to write.
     */
    suspend fun writeFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Appends text content to a file. Tries native append mode ("wa") first.
     * If the provider does not support append mode, throws an ActionFailed
     * exception with a hint to use write_file instead.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param content The text content to append.
     */
    suspend fun appendFile(
        locationId: String,
        path: String,
        content: String,
    )

    /**
     * Performs literal string replacement in a text file.
     * Uses read-modify-write pattern. Tries advisory file lock on local providers.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     * @param oldString The literal string to find.
     * @param newString The replacement string.
     * @param replaceAll If true, replace all occurrences; otherwise replace only the first.
     * @return [FileReplaceResult] with the number of replacements made.
     */
    suspend fun replaceInFile(
        locationId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): FileReplaceResult

    /**
     * Downloads a file from a URL and saves it to an authorized storage location.
     * Creates the file and parent directories if they don't exist. Overwrites
     * existing content. Subject to the configured file size limit.
     *
     * Respects user-configured settings:
     * - If the URL is HTTP (not HTTPS) and `allowHttpDownloads` is false, throws ActionFailed.
     * - If `allowUnverifiedHttpsCerts` is true, skips HTTPS certificate verification.
     *
     * Uses the user-configured download timeout. Follows HTTP redirects.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path for the destination file.
     * @param url The URL to download from.
     * @return The size of the downloaded file in bytes.
     */
    suspend fun downloadFromUrl(
        locationId: String,
        path: String,
        url: String,
    ): Long

    /**
     * Deletes a single file. Does not delete directories.
     *
     * @param locationId The authorized storage location identifier.
     * @param path Relative path to the file.
     */
    suspend fun deleteFile(
        locationId: String,
        path: String,
    )

    companion object {
        /** Maximum entries returned by list_files. */
        const val MAX_LIST_ENTRIES = 200

        /** Maximum lines returned by read_file. */
        const val MAX_READ_LINES = 200

        // File size limit constants are defined in ServerConfig
        // (DEFAULT_FILE_SIZE_LIMIT_MB, MIN_FILE_SIZE_LIMIT_MB, MAX_FILE_SIZE_LIMIT_MB).
        // Use ServerConfig constants — do NOT duplicate them here.
    }
}
```

#### Action 1.3.2: Create `FileOperationProviderImpl.kt` implementation

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderImpl.kt`
- **Type**: New file
- **Description**: Implementation using `ContentResolver`, `DocumentFile`, and `DocumentsContract`.

Key implementation details:
- Constructor: `@Inject constructor(@ApplicationContext private val context: Context, private val storageLocationProvider: StorageLocationProvider, private val settingsRepository: SettingsRepository)`
- **Path resolution**: Private helper `resolveDocumentFile(locationId, path)`:
  1. Check location is authorized via `storageLocationProvider.isLocationAuthorized(locationId)`
  2. If not authorized, throw `McpToolException.PermissionDenied("Storage location '$locationId' is not authorized. Please authorize it in the app settings.")`
  3. Get tree URI via `storageLocationProvider.getTreeUriForLocation(locationId)`
  4. Create `DocumentFile.fromTreeUri(context, treeUri)`
  5. Navigate path segments using `DocumentFile.findFile(segment)` for each segment
  6. Return the resolved `DocumentFile` or null
- **Directory creation**: Private helper `ensureParentDirectories(locationId, path)`:
  1. Split path into parent segments and file name
  2. Starting from tree root, create missing directories via `DocumentFile.createDirectory(name)`
- **File size check**: Private helper `checkFileSize(documentFile)`:
  1. Get current file size limit from `settingsRepository.getServerConfig().fileSizeLimitMb`
  2. Compare file size against limit (limit * 1024 * 1024)
  3. If exceeded, throw `McpToolException.ActionFailed("File size exceeds the configured limit of ${limitMb} MB.")`
- **listFiles()**: Query children of the resolved DocumentFile directory, apply offset/limit, build `FileInfo` list with full virtual paths
- **readFile()**:
  1. Resolve the DocumentFile for the target file
  2. **Check file size against the configured limit** via `checkFileSize(documentFile)` — reject before opening
  3. Open input stream and **stream** line-by-line (do NOT load all lines into memory):
     - Skip lines until `offset` is reached (counting but not buffering)
     - Buffer lines from `offset` to `offset + limit` (capped at MAX_READ_LINES)
     - Continue counting remaining lines to determine `totalLines`
  4. Return `FileReadResult` with buffered content, `totalLines`, `hasMore` flag, `startLine`, `endLine`
  5. This streaming approach prevents OOM for very large files
- **writeFile()**:
  1. **Check content size against the configured file size limit**: `content.toByteArray(Charsets.UTF_8).size` must not exceed `config.fileSizeLimitMb * 1024 * 1024`. If exceeded, throw `McpToolException.ActionFailed("Content size exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.")`
  2. Ensure parent directories, create/find file, open output stream with mode `"w"`, write content as UTF-8
- **appendFile()**:
  1. Resolve existing file, **check that the existing file size plus the new content size does not exceed the configured limit**. Get existing size from `documentFile.length()`, get new content size from `content.toByteArray(Charsets.UTF_8).size`. If the sum exceeds `config.fileSizeLimitMb * 1024 * 1024`, throw `McpToolException.ActionFailed("Appending this content would exceed the configured file size limit of ${config.fileSizeLimitMb} MB. Current file size: ${existingSize} bytes, content to append: ${newSize} bytes.")`
  2. Try `contentResolver.openOutputStream(uri, "wa")`. If it throws `UnsupportedOperationException` or similar, throw `McpToolException.ActionFailed("This storage provider does not support append mode. Use write_file to write the entire file content instead.")`
- **replaceInFile()**:
  1. Resolve the DocumentFile for the target file
  2. **Check file size against the configured limit** via `checkFileSize(documentFile)` — reject before reading
  3. Read full content, try advisory lock via `contentResolver.openFileDescriptor(uri, "rw")?.fileDescriptor` → `FileChannel.lock()` (catch if unsupported), perform literal replacement (first or all), write back, release lock
- **downloadFromUrl()**:
  1. Check authorization via `storageLocationProvider.isLocationAuthorized(locationId)`
  2. Get settings: `val config = settingsRepository.getServerConfig()`
  3. Parse URL and validate format
  4. If URL scheme is `http` and `!config.allowHttpDownloads`, throw `McpToolException.ActionFailed("HTTP downloads are not allowed. Enable 'Allow HTTP Downloads' in the app settings, or use an HTTPS URL.")`
  5. Create `HttpURLConnection` from URL
  6. If `config.allowUnverifiedHttpsCerts` and connection is `HttpsURLConnection`:
     - Create `TrustManager` that accepts all certificates
     - Create `SSLContext.getInstance("TLS")`, init with permissive trust manager
     - Set `sslSocketFactory` and `hostnameVerifier` on connection
  7. Set `connectTimeout = config.downloadTimeoutSeconds * 1000`, `readTimeout = config.downloadTimeoutSeconds * 1000`
  8. Set `instanceFollowRedirects = true`
  9. Connect and check response code (200-299 success)
  10. **Pre-check Content-Length header**: If the server provides a `Content-Length` header and its value exceeds `config.fileSizeLimitMb * 1024 * 1024`, abort immediately (before downloading) and throw `McpToolException.ActionFailed("Server reports file size of ${contentLength} bytes, which exceeds the configured limit of ${config.fileSizeLimitMb} MB.")`
  11. Stream response body to file via ContentResolver, **also checking cumulative size** against `config.fileSizeLimitMb * 1024 * 1024` during download (for servers that omit or misreport Content-Length)
  12. If size limit exceeded during streaming, abort download, delete partial file, throw `McpToolException.ActionFailed("Download exceeds the configured file size limit of ${config.fileSizeLimitMb} MB.")`
  13. Return downloaded file size in bytes
- **deleteFile()**: Resolve file, verify it's not a directory, call `DocumentsContract.deleteDocument(contentResolver, documentUri)` or `documentFile.delete()`

---

### Task 1.4: Create AppManager Interface and Implementation

**Definition of Done**: Interface and implementation exist for listing, opening, and closing apps.

#### Action 1.4.1: Create `AppManager.kt` interface

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/apps/AppManager.kt`
- **Type**: New file
- **Description**: Interface for application management operations.

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.apps

import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import com.danielealbano.androidremotecontrolmcp.data.model.AppInfo

/**
 * Manages application lifecycle operations: listing, launching, and closing apps.
 */
interface AppManager {
    /**
     * Lists installed applications with optional filtering.
     *
     * @param filter Filter by app type (ALL, USER, SYSTEM).
     * @param nameQuery Optional case-insensitive substring to filter by app name.
     * @return List of matching [AppInfo] entries sorted by name.
     */
    suspend fun listInstalledApps(
        filter: AppFilter = AppFilter.ALL,
        nameQuery: String? = null,
    ): List<AppInfo>

    /**
     * Launches an application by its package ID.
     *
     * @param packageId The application package name (e.g., "com.example.app").
     * @return [Result.success] if the app was launched, [Result.failure] if not found
     *         or the app has no launchable activity.
     */
    suspend fun openApp(packageId: String): Result<Unit>

    /**
     * Kills a background application process.
     *
     * Uses [android.app.ActivityManager.killBackgroundProcesses].
     * This only works for apps that are currently in the background.
     * For foreground apps, use the `press_home` MCP tool first to send
     * the app to the background, then call this method.
     *
     * @param packageId The application package name.
     * @return [Result.success] always (killBackgroundProcesses is fire-and-forget).
     */
    suspend fun closeApp(packageId: String): Result<Unit>
}
```

#### Action 1.4.2: Create `AppManagerImpl.kt` implementation

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/apps/AppManagerImpl.kt`
- **Type**: New file
- **Description**: Implementation using `PackageManager` and `ActivityManager`.

Key implementation details:
- Constructor: `@Inject constructor(@ApplicationContext private val context: Context)`
- **listInstalledApps()**:
  1. `val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)`
  2. Filter by `filter`:
     - `ALL`: no filter
     - `USER`: exclude `(appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0`
     - `SYSTEM`: include only `(appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0`
  3. Filter by `nameQuery`: case-insensitive `contains` on `packageManager.getApplicationLabel(appInfo).toString()`
  4. Map to `AppInfo`:
     - `packageId = appInfo.packageName`
     - `name = packageManager.getApplicationLabel(appInfo).toString()`
     - `versionName = packageManager.getPackageInfo(appInfo.packageName, 0).versionName`
     - `versionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(appInfo.packageName, 0))`
     - `isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0`
  5. Sort by `name` (case-insensitive)
- **openApp()**:
  1. `val intent = context.packageManager.getLaunchIntentForPackage(packageId)`
  2. If null, return `Result.failure(IllegalArgumentException("No launchable activity found for package '$packageId'"))`
  3. `intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`
  4. `context.startActivity(intent)`
  5. Return `Result.success(Unit)`
- **closeApp()**:
  1. `val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager`
  2. `activityManager.killBackgroundProcesses(packageId)`
  3. Return `Result.success(Unit)`

---

### Task 1.5: Update ServerConfig and SettingsRepository for File Size Limit and Authorized Locations

**Definition of Done**: `ServerConfig` has `fileSizeLimitMb`, `allowHttpDownloads`, `allowUnverifiedHttpsCerts`, and `downloadTimeoutSeconds` fields. `SettingsRepository` has methods for file size limit, download security settings, download timeout, and authorized location management. `SettingsRepositoryImpl` has new DataStore keys and method implementations.

#### Action 1.5.1: Add `fileSizeLimitMb` to `ServerConfig`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`
- **Type**: Modify
- **Diff**:

```diff
 data class ServerConfig(
     val port: Int = DEFAULT_PORT,
     val bindingAddress: BindingAddress = BindingAddress.LOCALHOST,
     val bearerToken: String = "",
     val autoStartOnBoot: Boolean = false,
     val httpsEnabled: Boolean = false,
     val certificateSource: CertificateSource = CertificateSource.AUTO_GENERATED,
     val certificateHostname: String = DEFAULT_CERTIFICATE_HOSTNAME,
     val tunnelEnabled: Boolean = false,
     val tunnelProvider: TunnelProviderType = TunnelProviderType.CLOUDFLARE,
     val ngrokAuthtoken: String = "",
     val ngrokDomain: String = "",
+    val fileSizeLimitMb: Int = DEFAULT_FILE_SIZE_LIMIT_MB,
+    val allowHttpDownloads: Boolean = false,
+    val allowUnverifiedHttpsCerts: Boolean = false,
+    val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
 ) {
     companion object {
         /** Default server port. */
         const val DEFAULT_PORT = 8080

         /** Minimum valid port number. */
         const val MIN_PORT = 1

         /** Maximum valid port number. */
         const val MAX_PORT = 65535

         /** Default hostname for auto-generated certificates. */
         const val DEFAULT_CERTIFICATE_HOSTNAME = "android-mcp.local"
+
+        /** Default file size limit in megabytes. */
+        const val DEFAULT_FILE_SIZE_LIMIT_MB = 50
+
+        /** Minimum file size limit in megabytes. */
+        const val MIN_FILE_SIZE_LIMIT_MB = 1
+
+        /** Maximum file size limit in megabytes. */
+        const val MAX_FILE_SIZE_LIMIT_MB = 500
+
+        /** Default download timeout in seconds. */
+        const val DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 60
+
+        /** Minimum download timeout in seconds. */
+        const val MIN_DOWNLOAD_TIMEOUT_SECONDS = 10
+
+        /** Maximum download timeout in seconds. */
+        const val MAX_DOWNLOAD_TIMEOUT_SECONDS = 300
     }
 }
```

#### Action 1.5.2: Add methods to `SettingsRepository` interface

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`
- **Type**: Modify
- **Diff**: Add the following methods at the end of the interface (before the closing `}`):

```diff
+    /** Updates the file size limit for file operations (in MB). */
+    suspend fun updateFileSizeLimit(limitMb: Int)
+
+    /**
+     * Validates a file size limit value.
+     *
+     * @return [Result.success] with the validated limit, or [Result.failure] with an [IllegalArgumentException].
+     */
+    fun validateFileSizeLimit(limitMb: Int): Result<Int>
+
+    /**
+     * Returns the map of authorized storage location IDs to their tree URI strings.
+     */
+    suspend fun getAuthorizedLocations(): Map<String, String>
+
+    /**
+     * Adds an authorized storage location with its tree URI.
+     *
+     * @param locationId The storage location identifier ("{authority}/{rootId}").
+     * @param treeUri The granted document tree URI string.
+     */
+    suspend fun addAuthorizedLocation(locationId: String, treeUri: String)
+
+    /**
+     * Removes an authorized storage location.
+     *
+     * @param locationId The storage location identifier.
+     */
+    suspend fun removeAuthorizedLocation(locationId: String)
+
+    /** Updates whether HTTP (non-HTTPS) downloads are allowed. */
+    suspend fun updateAllowHttpDownloads(enabled: Boolean)
+
+    /** Updates whether unverified HTTPS certificates are accepted for downloads. */
+    suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean)
+
+    /** Updates the download timeout in seconds. */
+    suspend fun updateDownloadTimeout(seconds: Int)
+
+    /**
+     * Validates a download timeout value.
+     *
+     * @return [Result.success] with the validated timeout, or [Result.failure] with an [IllegalArgumentException].
+     */
+    fun validateDownloadTimeout(seconds: Int): Result<Int>
```

Add import for `kotlinx.coroutines.flow.Flow` if not already present (it is).

#### Action 1.5.3: Implement new methods in `SettingsRepositoryImpl`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`
- **Type**: Modify
- **Changes**:

1. Add new DataStore keys to the companion object:
```diff
+            private val FILE_SIZE_LIMIT_KEY = intPreferencesKey("file_size_limit_mb")
+            private val ALLOW_HTTP_DOWNLOADS_KEY = booleanPreferencesKey("allow_http_downloads")
+            private val ALLOW_UNVERIFIED_HTTPS_KEY = booleanPreferencesKey("allow_unverified_https_certs")
+            private val DOWNLOAD_TIMEOUT_KEY = intPreferencesKey("download_timeout_seconds")
+            private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")
```

2. Add import for `kotlinx.serialization.json.*`:
```diff
+import kotlinx.serialization.json.Json
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
```

3. Update `mapPreferencesToServerConfig` to include the new fields:
```diff
             return ServerConfig(
                 // ... existing fields ...
                 ngrokDomain = prefs[NGROK_DOMAIN_KEY] ?: "",
+                fileSizeLimitMb = prefs[FILE_SIZE_LIMIT_KEY] ?: ServerConfig.DEFAULT_FILE_SIZE_LIMIT_MB,
+                allowHttpDownloads = prefs[ALLOW_HTTP_DOWNLOADS_KEY] ?: false,
+                allowUnverifiedHttpsCerts = prefs[ALLOW_UNVERIFIED_HTTPS_KEY] ?: false,
+                downloadTimeoutSeconds = prefs[DOWNLOAD_TIMEOUT_KEY] ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
             )
```

4. Implement the new methods:
```kotlin
    override suspend fun updateFileSizeLimit(limitMb: Int) {
        dataStore.edit { prefs ->
            prefs[FILE_SIZE_LIMIT_KEY] = limitMb
        }
    }

    override fun validateFileSizeLimit(limitMb: Int): Result<Int> =
        if (limitMb in ServerConfig.MIN_FILE_SIZE_LIMIT_MB..ServerConfig.MAX_FILE_SIZE_LIMIT_MB) {
            Result.success(limitMb)
        } else {
            Result.failure(
                IllegalArgumentException(
                    "File size limit must be between ${ServerConfig.MIN_FILE_SIZE_LIMIT_MB} and " +
                        "${ServerConfig.MAX_FILE_SIZE_LIMIT_MB} MB",
                ),
            )
        }

    override suspend fun getAuthorizedLocations(): Map<String, String> {
        val prefs = dataStore.data.first()
        val jsonString = prefs[AUTHORIZED_LOCATIONS_KEY] ?: return emptyMap()
        return try {
            val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
            jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override suspend fun addAuthorizedLocation(locationId: String, treeUri: String) {
        dataStore.edit { prefs ->
            val existing = parseAuthorizedLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY])
            val updated = existing.toMutableMap()
            updated[locationId] = treeUri
            prefs[AUTHORIZED_LOCATIONS_KEY] = serializeAuthorizedLocationsJson(updated)
        }
    }

    override suspend fun removeAuthorizedLocation(locationId: String) {
        dataStore.edit { prefs ->
            val existing = parseAuthorizedLocationsJson(prefs[AUTHORIZED_LOCATIONS_KEY])
            val updated = existing.toMutableMap()
            updated.remove(locationId)
            prefs[AUTHORIZED_LOCATIONS_KEY] = serializeAuthorizedLocationsJson(updated)
        }
    }

    override suspend fun updateAllowHttpDownloads(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ALLOW_HTTP_DOWNLOADS_KEY] = enabled
        }
    }

    override suspend fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ALLOW_UNVERIFIED_HTTPS_KEY] = enabled
        }
    }

    override suspend fun updateDownloadTimeout(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[DOWNLOAD_TIMEOUT_KEY] = seconds
        }
    }

    override fun validateDownloadTimeout(seconds: Int): Result<Int> =
        if (seconds in ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS..ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS) {
            Result.success(seconds)
        } else {
            Result.failure(
                IllegalArgumentException(
                    "Download timeout must be between ${ServerConfig.MIN_DOWNLOAD_TIMEOUT_SECONDS} and " +
                        "${ServerConfig.MAX_DOWNLOAD_TIMEOUT_SECONDS} seconds",
                ),
            )
        }

    private fun parseAuthorizedLocationsJson(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        return try {
            val jsonObject = Json.parseToJsonElement(json).jsonObject
            jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeAuthorizedLocationsJson(map: Map<String, String>): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                map.forEach { (key, value) -> put(key, value) }
            },
        )
```

---

### Task 1.6: Update DI Modules

**Definition of Done**: All new interfaces bound to implementations via Hilt.

#### Action 1.6.1: Add new bindings to `AppModule.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`
- **Type**: Modify
- **Changes**: Add new imports and bindings to `ServiceModule`:

```diff
+import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
+import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProviderImpl
+import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
+import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProviderImpl
+import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
+import com.danielealbano.androidremotecontrolmcp.services.apps.AppManagerImpl
```

```diff
 @Module
 @InstallIn(SingletonComponent::class)
 abstract class ServiceModule {
     @Binds
     @Singleton
     abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

     @Binds
     @Singleton
     abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

     @Binds
     @Singleton
     abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider

     @Binds
     abstract fun bindCloudflareBinaryResolver(impl: AndroidCloudflareBinaryResolver): CloudflaredBinaryResolver
+
+    @Binds
+    @Singleton
+    abstract fun bindStorageLocationProvider(impl: StorageLocationProviderImpl): StorageLocationProvider
+
+    @Binds
+    @Singleton
+    abstract fun bindFileOperationProvider(impl: FileOperationProviderImpl): FileOperationProvider
+
+    @Binds
+    @Singleton
+    abstract fun bindAppManager(impl: AppManagerImpl): AppManager
 }
```

---

### Task 1.7: Update AndroidManifest.xml

**Definition of Done**: New permissions added for app management features.

#### Action 1.7.1: Add `QUERY_ALL_PACKAGES` and `KILL_BACKGROUND_PROCESSES` permissions

- [x] **File**: `app/src/main/AndroidManifest.xml`
- **Type**: Modify
- **Diff**:

```diff
     <!-- Notifications (Android 13+) -->
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

+    <!-- App Management -->
+    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
+    <uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" />
+
     <application
```

---

### Task 1.8: Add McpToolUtils Helper Methods

**Definition of Done**: `optionalInt` and `optionalBoolean` utility methods added to `McpToolUtils`.

#### Action 1.8.1: Add `optionalInt` to `McpToolUtils.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtils.kt`
- **Type**: Modify
- **Changes**: Add after the existing `optionalLong` method:

```kotlin
    /**
     * Extracts an optional integer value from [params],
     * returning [default] if not present.
     *
     * Rejects string-encoded numbers, fractional values, and values outside Int range.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a valid integer.
     */
    @Suppress("ThrowsCount")
    fun optionalInt(
        params: JsonObject?,
        name: String,
        default: Int,
    ): Int {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got string: '${primitive.content}'",
            )
        }
        val doubleVal =
            primitive.content.toDoubleOrNull()
                ?: throw McpToolException.InvalidParams(
                    "Parameter '$name' must be a number, got: '${primitive.content}'",
                )
        val intVal = doubleVal.toInt()
        if (doubleVal != intVal.toDouble()) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be an integer, got: '${primitive.content}'",
            )
        }
        return intVal
    }
```

#### Action 1.8.2: Add `optionalBoolean` to `McpToolUtils.kt`

- [x] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtils.kt`
- **Type**: Modify
- **Changes**: Add after the new `optionalInt` method:

```kotlin
    /**
     * Extracts an optional boolean value from [params],
     * returning [default] if not present.
     *
     * Accepts JSON booleans (true/false). Rejects string-encoded booleans.
     *
     * @throws McpToolException.InvalidParams if the parameter is present but not a boolean.
     */
    fun optionalBoolean(
        params: JsonObject?,
        name: String,
        default: Boolean,
    ): Boolean {
        val element = params?.get(name) ?: return default
        val primitive =
            element as? JsonPrimitive
                ?: throw McpToolException.InvalidParams("Parameter '$name' must be a boolean")
        if (primitive.isString) {
            throw McpToolException.InvalidParams(
                "Parameter '$name' must be a boolean (true/false), got string: '${primitive.content}'",
            )
        }
        return primitive.content.toBooleanStrictOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a boolean (true/false), got: '${primitive.content}'",
            )
    }
```

---

### Task 1.9: Infrastructure Unit Tests

**Definition of Done**: Unit tests for StorageLocationProvider, FileOperationProvider, and AppManager covering standard cases, edge cases, and failure modes.

#### Action 1.9.1: Create `StorageLocationProviderTest.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/StorageLocationProviderTest.kt`
- **Type**: New file
- **Tests**:
  - `getAvailableLocations returns discovered roots`
  - `getAvailableLocations returns empty list when no providers found`
  - `authorizeLocation persists tree URI and takes permission`
  - `deauthorizeLocation removes entry and releases permission`
  - `isLocationAuthorized returns true for authorized locations`
  - `isLocationAuthorized returns false for unauthorized locations`
  - `getLocationById returns null for unknown location`
  - `getTreeUriForLocation returns null for unauthorized location`

Mock `Context`, `ContentResolver`, `PackageManager` via MockK. Mock `SettingsRepository` for authorized locations storage.

#### Action 1.9.2: Create `FileOperationProviderTest.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/storage/FileOperationProviderTest.kt`
- **Type**: New file
- **Tests**:
  - `listFiles returns entries for authorized location`
  - `listFiles throws PermissionDenied for unauthorized location`
  - `listFiles respects offset and limit`
  - `listFiles caps limit at MAX_LIST_ENTRIES`
  - `readFile returns lines with pagination info`
  - `readFile caps limit at MAX_READ_LINES`
  - `readFile includes hasMore hint when more lines exist`
  - `readFile throws PermissionDenied for unauthorized location`
  - `readFile throws ActionFailed when file exceeds size limit`
  - `readFile streams lines without loading entire file into memory`
  - `writeFile creates file with content`
  - `writeFile creates parent directories`
  - `writeFile with empty content creates empty file`
  - `writeFile throws ActionFailed when content exceeds size limit`
  - `appendFile appends content to existing file`
  - `appendFile throws ActionFailed with hint when provider doesn't support append`
  - `appendFile throws ActionFailed when resulting size exceeds limit`
  - `replaceInFile replaces first occurrence`
  - `replaceInFile replaces all occurrences when replaceAll is true`
  - `replaceInFile returns zero count when old_string not found`
  - `replaceInFile throws ActionFailed when file exceeds size limit`
  - `downloadFromUrl downloads file and saves to location`
  - `downloadFromUrl throws ActionFailed when HTTP not allowed`
  - `downloadFromUrl throws ActionFailed when Content-Length exceeds size limit (pre-check)`
  - `downloadFromUrl throws ActionFailed when streamed size exceeds size limit`
  - `downloadFromUrl uses permissive SSL when unverified certs allowed`
  - `downloadFromUrl throws ActionFailed for invalid URL`
  - `deleteFile deletes the file`
  - `deleteFile throws ActionFailed when path is a directory`

Mock `Context`, `ContentResolver`, `StorageLocationProvider`, `SettingsRepository`. Use `mockk<DocumentFile>()` for DocumentFile interactions.

#### Action 1.9.3: Create `AppManagerTest.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/apps/AppManagerTest.kt`
- **Type**: New file
- **Tests**:
  - `listInstalledApps returns all apps when filter is ALL`
  - `listInstalledApps returns only user apps when filter is USER`
  - `listInstalledApps returns only system apps when filter is SYSTEM`
  - `listInstalledApps filters by name query (case-insensitive)`
  - `listInstalledApps returns empty list when no matches`
  - `listInstalledApps results are sorted by name`
  - `openApp launches app with correct intent flags`
  - `openApp returns failure when package not found`
  - `openApp returns failure when no launchable activity`
  - `closeApp calls killBackgroundProcesses`

Mock `Context`, `PackageManager`, `ActivityManager` via MockK.

**Run targeted tests**: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.services.storage.*" --tests "com.danielealbano.androidremotecontrolmcp.services.apps.*"`

---

## User Story 2: Storage Locations UI

**As a user**, I want to see available storage locations in the app's main screen and authorize/deauthorize them with toggle switches, so that MCP tools can access files in authorized locations.

**Acceptance Criteria**:
- New "Storage Locations" section visible in main screen
- Available SAF roots displayed with name, provider, and toggle switch
- Toggle off by default for all locations
- Toggling on launches system file picker for authorization
- Toggling off revokes persistent URI permission
- File size limit setting visible with validation (1-500 MB)
- Download timeout setting visible with validation (10-300 seconds, default 60)
- Allow HTTP Downloads toggle visible (default off)
- Allow Unverified HTTPS toggle visible (default off)
- All download settings disabled when server running
- All new string resources defined
- Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 2.1: Add String Resources

**Definition of Done**: All UI strings for storage locations section are defined.

#### Action 2.1.1: Add string resources to `strings.xml`

- [ ] **File**: `app/src/main/res/values/strings.xml`
- **Type**: Modify
- **Changes**: Add before `</resources>`:

```xml
    <!-- Storage Locations -->
    <string name="storage_locations_title">Storage Locations</string>
    <string name="storage_locations_description">Authorize storage locations for file operations via MCP tools. Each location requires confirmation through the system file picker.</string>
    <string name="storage_location_authorize">Authorize</string>
    <string name="storage_location_authorized">Authorized</string>
    <string name="storage_location_provider_label">Provider: %1$s</string>
    <string name="storage_location_no_locations">No storage locations found on this device</string>
    <string name="storage_file_size_limit_label">File Size Limit (MB)</string>
    <string name="storage_file_size_limit_error">Must be between %1$d and %2$d MB</string>
    <string name="storage_download_timeout_label">Download Timeout (seconds)</string>
    <string name="storage_download_timeout_error">Must be between %1$d and %2$d seconds</string>
    <string name="storage_allow_http_downloads_label">Allow HTTP Downloads</string>
    <string name="storage_allow_http_downloads_description">Allow downloading files from unencrypted HTTP URLs (less secure)</string>
    <string name="storage_allow_unverified_https_label">Allow Unverified HTTPS</string>
    <string name="storage_allow_unverified_https_description">Accept unverified HTTPS certificates for downloads (less secure)</string>
```

---

### Task 2.2: Update MainViewModel

**Definition of Done**: ViewModel exposes storage locations state and provides methods for authorization/deauthorization and file size limit updates.

#### Action 2.2.1: Add storage location state and methods to `MainViewModel.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`
- **Type**: Modify
- **Changes**:

1. Add imports:
```diff
+import android.net.Uri
+import android.provider.DocumentsContract
+import com.danielealbano.androidremotecontrolmcp.data.model.StorageLocation
+import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
```

2. Add `StorageLocationProvider` to constructor:
```diff
     @Inject
     constructor(
         private val settingsRepository: SettingsRepository,
         private val tunnelManager: TunnelManager,
+        private val storageLocationProvider: StorageLocationProvider,
         @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
     ) : ViewModel() {
```

3. Add state flows (after existing state flows):
```kotlin
        private val _storageLocations = MutableStateFlow<List<StorageLocation>>(emptyList())
        val storageLocations: StateFlow<List<StorageLocation>> = _storageLocations.asStateFlow()

        private val _fileSizeLimitInput = MutableStateFlow("")
        val fileSizeLimitInput: StateFlow<String> = _fileSizeLimitInput.asStateFlow()

        private val _fileSizeLimitError = MutableStateFlow<String?>(null)
        val fileSizeLimitError: StateFlow<String?> = _fileSizeLimitError.asStateFlow()

        private val _downloadTimeoutInput = MutableStateFlow("")
        val downloadTimeoutInput: StateFlow<String> = _downloadTimeoutInput.asStateFlow()

        private val _downloadTimeoutError = MutableStateFlow<String?>(null)
        val downloadTimeoutError: StateFlow<String?> = _downloadTimeoutError.asStateFlow()

        private val _pendingAuthorizationLocationId = MutableStateFlow<String?>(null)
        val pendingAuthorizationLocationId: StateFlow<String?> = _pendingAuthorizationLocationId.asStateFlow()
```

4. In the `init` block, add collector for serverConfig to update fileSizeLimitInput:
```diff
             viewModelScope.launch(ioDispatcher) {
                 settingsRepository.serverConfig.collect { config ->
                     _serverConfig.value = config
                     _portInput.value = config.port.toString()
                     _hostnameInput.value = config.certificateHostname
                     _ngrokAuthtokenInput.value = config.ngrokAuthtoken
                     _ngrokDomainInput.value = config.ngrokDomain
+                    _fileSizeLimitInput.value = config.fileSizeLimitMb.toString()
+                    _downloadTimeoutInput.value = config.downloadTimeoutSeconds.toString()
                 }
             }
```

5. Add new methods:
```kotlin
        fun refreshStorageLocations() {
            viewModelScope.launch(ioDispatcher) {
                _storageLocations.value = storageLocationProvider.getAvailableLocations()
            }
        }

        fun requestLocationAuthorization(locationId: String) {
            _pendingAuthorizationLocationId.value = locationId
        }

        fun onLocationAuthorized(treeUri: Uri) {
            val locationId = _pendingAuthorizationLocationId.value ?: return
            _pendingAuthorizationLocationId.value = null
            viewModelScope.launch(ioDispatcher) {
                storageLocationProvider.authorizeLocation(locationId, treeUri)
                _storageLocations.value = storageLocationProvider.getAvailableLocations()
            }
        }

        fun onLocationAuthorizationCancelled() {
            _pendingAuthorizationLocationId.value = null
        }

        fun deauthorizeLocation(locationId: String) {
            viewModelScope.launch(ioDispatcher) {
                storageLocationProvider.deauthorizeLocation(locationId)
                _storageLocations.value = storageLocationProvider.getAvailableLocations()
            }
        }

        @Suppress("ReturnCount")
        fun updateFileSizeLimit(limitString: String) {
            _fileSizeLimitInput.value = limitString

            if (limitString.isBlank()) {
                _fileSizeLimitError.value = "File size limit is required"
                return
            }

            val limit = limitString.toIntOrNull()
            if (limit == null) {
                _fileSizeLimitError.value = "Must be a number"
                return
            }

            val result = settingsRepository.validateFileSizeLimit(limit)
            if (result.isFailure) {
                _fileSizeLimitError.value = result.exceptionOrNull()?.message
                return
            }

            _fileSizeLimitError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateFileSizeLimit(limit)
            }
        }

        /**
         * Computes the initial URI for the SAF document tree picker.
         * Uses the ViewModel's cached storageLocations state to avoid
         * synchronous content provider queries on the main thread.
         */
        fun getInitialPickerUri(locationId: String): Uri? {
            val location = _storageLocations.value.find { it.id == locationId } ?: return null
            return DocumentsContract.buildDocumentUri(location.authority, location.rootDocumentId)
        }

        @Suppress("ReturnCount")
        fun updateDownloadTimeout(timeoutString: String) {
            _downloadTimeoutInput.value = timeoutString

            if (timeoutString.isBlank()) {
                _downloadTimeoutError.value = "Download timeout is required"
                return
            }

            val timeout = timeoutString.toIntOrNull()
            if (timeout == null) {
                _downloadTimeoutError.value = "Must be a number"
                return
            }

            val result = settingsRepository.validateDownloadTimeout(timeout)
            if (result.isFailure) {
                _downloadTimeoutError.value = result.exceptionOrNull()?.message
                return
            }

            _downloadTimeoutError.value = null
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateDownloadTimeout(timeout)
            }
        }

        fun updateAllowHttpDownloads(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateAllowHttpDownloads(enabled)
            }
        }

        fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateAllowUnverifiedHttpsCerts(enabled)
            }
        }
```

6. Update `refreshPermissionStatus` to also refresh storage locations:
```diff
         fun refreshPermissionStatus(context: Context) {
             _isAccessibilityEnabled.value =
                 PermissionUtils.isAccessibilityServiceEnabled(
                     context,
                     McpAccessibilityService::class.java,
                 )
             _isNotificationPermissionGranted.value =
                 PermissionUtils.isNotificationPermissionGranted(context)
+            refreshStorageLocations()
         }
```

---

### Task 2.3: Create StorageLocationsSection Composable

**Definition of Done**: UI section displays available storage locations with toggles and file size limit input.

#### Action 2.3.1: Create `StorageLocationsSection.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/StorageLocationsSection.kt`
- **Type**: New file
- **Description**: Composable displaying storage location toggles and file size limit setting.

Key composable structure:
- `StorageLocationsSection` (public):
  - Parameters: `storageLocations: List<StorageLocation>`, `fileSizeLimitInput: String`, `fileSizeLimitError: String?`, `downloadTimeoutInput: String`, `downloadTimeoutError: String?`, `allowHttpDownloads: Boolean`, `allowUnverifiedHttpsCerts: Boolean`, `isServerRunning: Boolean`, `onToggleLocation: (StorageLocation) -> Unit`, `onFileSizeLimitChange: (String) -> Unit`, `onDownloadTimeoutChange: (String) -> Unit`, `onAllowHttpDownloadsChange: (Boolean) -> Unit`, `onAllowUnverifiedHttpsCertsChange: (Boolean) -> Unit`
  - `ElevatedCard` with `Column`:
    - Title: "Storage Locations" (`MaterialTheme.typography.titleLarge`)
    - Description text (`MaterialTheme.typography.bodyMedium`, color `onSurfaceVariant`)
    - `Spacer(12.dp)`
    - If `storageLocations` is empty: "No storage locations found" text
    - Else: For each location, `StorageLocationRow`
    - `Spacer(16.dp)` + `HorizontalDivider()`
    - File size limit `OutlinedTextField`:
      - Label: "File Size Limit (MB)"
      - `KeyboardType.Number`, singleLine
      - Error support text
      - Disabled when server running
    - `Spacer(12.dp)`
    - Download Timeout `OutlinedTextField`:
      - Label: "Download Timeout (seconds)"
      - `KeyboardType.Number`, singleLine
      - Error support text
      - Disabled when server running
    - `Spacer(12.dp)`
    - Allow HTTP Downloads toggle (`Switch`):
      - Label: "Allow HTTP Downloads"
      - Description: "Allow downloading files from unencrypted HTTP URLs (less secure)"
      - Disabled when server running
    - `Spacer(8.dp)`
    - Allow Unverified HTTPS toggle (`Switch`):
      - Label: "Allow Unverified HTTPS"
      - Description: "Accept unverified HTTPS certificates for downloads (less secure)"
      - Disabled when server running
- `StorageLocationRow` (private):
  - Parameters: `location: StorageLocation`, `onToggle: () -> Unit`
  - `Row` with `Modifier.fillMaxWidth().padding(vertical = 4.dp)`:
    - Status icon: `CheckCircle` (green) if authorized, `Error` (red) if not
    - `Column(Modifier.weight(1f))`:
      - Location name (`bodyLarge`)
      - Provider name (`bodySmall`, color `onSurfaceVariant`)
    - `Switch` checked = `location.isAuthorized`, onCheckedChange = onToggle

---

### Task 2.4: Update HomeScreen

**Definition of Done**: StorageLocationsSection integrated into the main screen with SAF launcher.

#### Action 2.4.1: Add StorageLocationsSection to `HomeScreen.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`
- **Type**: Modify
- **Changes**:

1. Add imports (note: `android.content.Intent` is already imported in the existing file — do NOT duplicate it):
```diff
+import android.net.Uri
+import androidx.activity.compose.rememberLauncherForActivityResult
+import androidx.activity.result.contract.ActivityResultContracts
+import com.danielealbano.androidremotecontrolmcp.ui.components.StorageLocationsSection
```

2. Add state collectors (after existing collectors):
```diff
+        val storageLocations by viewModel.storageLocations.collectAsStateWithLifecycle()
+        val fileSizeLimitInput by viewModel.fileSizeLimitInput.collectAsStateWithLifecycle()
+        val fileSizeLimitError by viewModel.fileSizeLimitError.collectAsStateWithLifecycle()
+        val downloadTimeoutInput by viewModel.downloadTimeoutInput.collectAsStateWithLifecycle()
+        val downloadTimeoutError by viewModel.downloadTimeoutError.collectAsStateWithLifecycle()
```

3. Add the SAF document tree launcher (after the state collectors, before `DisposableEffect`):
```kotlin
    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Permission is taken inside StorageLocationProviderImpl.authorizeLocation()
            // — do NOT call takePersistableUriPermission here to avoid a double call.
            viewModel.onLocationAuthorized(uri)
        } else {
            viewModel.onLocationAuthorizationCancelled()
        }
    }
```

4. Add the `StorageLocationsSection` composable call (after `ConfigurationSection` and before `RemoteAccessSection` in the Column):
```diff
+            StorageLocationsSection(
+                storageLocations = storageLocations,
+                fileSizeLimitInput = fileSizeLimitInput,
+                fileSizeLimitError = fileSizeLimitError,
+                downloadTimeoutInput = downloadTimeoutInput,
+                downloadTimeoutError = downloadTimeoutError,
+                allowHttpDownloads = serverConfig.allowHttpDownloads,
+                allowUnverifiedHttpsCerts = serverConfig.allowUnverifiedHttpsCerts,
+                isServerRunning = isServerRunning,
+                onToggleLocation = { location ->
+                    if (location.isAuthorized) {
+                        viewModel.deauthorizeLocation(location.id)
+                    } else {
+                        viewModel.requestLocationAuthorization(location.id)
+                        val initialUri = viewModel.getInitialPickerUri(location.id)
+                        documentTreeLauncher.launch(initialUri)
+                    }
+                },
+                onFileSizeLimitChange = viewModel::updateFileSizeLimit,
+                onDownloadTimeoutChange = viewModel::updateDownloadTimeout,
+                onAllowHttpDownloadsChange = viewModel::updateAllowHttpDownloads,
+                onAllowUnverifiedHttpsCertsChange = viewModel::updateAllowUnverifiedHttpsCerts,
+            )

             RemoteAccessSection(
```

---

### Task 2.5: ViewModel Unit Tests

**Definition of Done**: Unit tests for new ViewModel methods.

#### Action 2.5.1: Update `MainViewModelTest.kt` (or create if not exists)

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`
- **Type**: Modify (or create)
- **Tests**:
  - `refreshStorageLocations updates storageLocations state`
  - `requestLocationAuthorization sets pendingAuthorizationLocationId`
  - `onLocationAuthorized calls authorizeLocation and refreshes`
  - `onLocationAuthorizationCancelled clears pendingAuthorizationLocationId`
  - `deauthorizeLocation calls deauthorizeLocation and refreshes`
  - `updateFileSizeLimit validates and persists valid value`
  - `updateFileSizeLimit sets error for invalid value`
  - `updateFileSizeLimit sets error for blank input`
  - `updateFileSizeLimit sets error for non-numeric input`
  - `updateDownloadTimeout validates and persists valid value`
  - `updateDownloadTimeout sets error for invalid value`
  - `updateDownloadTimeout sets error for blank input`

Mock `SettingsRepository`, `TunnelManager`, `StorageLocationProvider`.

**Run targeted tests**: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModelTest"`

---

## User Story 3: File Operation MCP Tools

**As an MCP client**, I want file operation tools to list, read, write, append, replace, and delete files across authorized storage locations, so that I can manage files on the Android device.

**Acceptance Criteria**:
- All 8 file tools implemented following existing tool patterns
- All tools check storage authorization before operations
- All tools enforce file size limit
- `read_file` capped at 200 lines with `hasMore` hint
- `list_files` capped at 200 entries with `hasMore` hint
- `write_file` creates directories and supports empty content
- `append_file` fails gracefully with hint for unsupported providers
- `file_replace` uses literal matching with optional `replace_all`
- Tools registered in McpServerService
- Integration tests pass
- Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 3.1: Implement FileTools.kt

**Definition of Done**: All 8 file tool handlers created with correct schemas, parameter validation, and execution logic.

#### Action 3.1.1: Create `FileTools.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt`
- **Type**: New file
- **Description**: Contains all 8 file operation tool handlers and the `registerFileTools()` function.

**Tool handlers (each follows the existing pattern: `@Inject constructor`, `execute()`, `register()`, companion `TOOL_NAME`)**:

1. **`ListStorageLocationsHandler`**
   - TOOL_NAME: `"list_storage_locations"`
   - Description: `"Lists available storage locations on the device with their authorization status. Locations must be authorized in the app settings before file operations can be performed on them."`
   - Input schema: `{}` (no parameters)
   - Execute: Call `storageLocationProvider.getAvailableLocations()`, serialize to JSON array with fields: `id`, `name`, `provider`, `authorized`, `available_bytes`
   - Dependencies: `StorageLocationProvider`

2. **`ListFilesHandler`**
   - TOOL_NAME: `"list_files"`
   - Description: `"Lists files and directories in a storage location. The location must be authorized. Returns name, path, type, size, last_modified, and mime_type for each entry."`
   - Input schema:
     - `location_id` (string, required): Storage location identifier from list_storage_locations
     - `path` (string, optional, default `""`): Relative path within the location
     - `offset` (integer, optional, default `0`): Number of entries to skip
     - `limit` (integer, optional, default `200`): Max entries to return (capped at 200)
   - Execute: Call `fileOperationProvider.listFiles()`, serialize result to JSON with `files` array, `total_count`, `has_more`
   - Dependencies: `FileOperationProvider`

3. **`ReadFileHandler`**
   - TOOL_NAME: `"read_file"`
   - Description: `"Reads a text file from an authorized storage location with line-based pagination. Returns content with line numbers. Maximum 200 lines per call."`
   - Input schema:
     - `location_id` (string, required)
     - `path` (string, required): Relative path to the file
     - `offset` (integer, optional, default `1`): 1-based line number to start from
     - `limit` (integer, optional, default `200`): Max lines to return (capped at 200)
   - Execute: Call `fileOperationProvider.readFile()`, format result with numbered lines, add hint at end if `hasMore`: `"\n--- More lines available. Use offset={endLine+1} to continue reading. Total lines: {totalLines} ---"`
   - Dependencies: `FileOperationProvider`

4. **`WriteFileHandler`**
   - TOOL_NAME: `"write_file"`
   - Description: `"Writes text content to a file in an authorized storage location. Creates the file if it doesn't exist. Creates parent directories automatically. Overwrites existing content. Pass empty content to create an empty file."`
   - Input schema:
     - `location_id` (string, required)
     - `path` (string, required)
     - `content` (string, required): Text content to write (can be empty)
   - Execute: Call `fileOperationProvider.writeFile()`, return success message
   - Dependencies: `FileOperationProvider`

5. **`AppendFileHandler`**
   - TOOL_NAME: `"append_file"`
   - Description: `"Appends text content to an existing file in an authorized storage location. If the storage provider does not support append mode, an error is returned with a hint to use write_file instead."`
   - Input schema:
     - `location_id` (string, required)
     - `path` (string, required)
     - `content` (string, required): Text content to append
   - Execute: Call `fileOperationProvider.appendFile()`, return success message
   - Dependencies: `FileOperationProvider`

6. **`FileReplaceHandler`**
   - TOOL_NAME: `"file_replace"`
   - Description: `"Performs literal string replacement in a text file in an authorized storage location. Reads the file, performs replacement, and writes back. Uses advisory file locking when supported by the provider."`
   - Input schema:
     - `location_id` (string, required)
     - `path` (string, required)
     - `old_string` (string, required): Literal string to find
     - `new_string` (string, required): Replacement string
     - `replace_all` (boolean, optional, default `false`): If true, replace all occurrences
   - Execute: Call `fileOperationProvider.replaceInFile()`, return message with replacement count: `"Replaced {count} occurrence(s) of the specified string."`
   - Dependencies: `FileOperationProvider`

7. **`DownloadFromUrlHandler`**
   - TOOL_NAME: `"download_from_url"`
   - Description: `"Downloads a file from a URL and saves it to an authorized storage location. Creates the file and parent directories if they don't exist. Overwrites existing content. Subject to the configured file size limit and download timeout. HTTP downloads and unverified HTTPS certificates must be explicitly allowed in the app settings."`
   - Input schema:
     - `location_id` (string, required): Storage location identifier
     - `path` (string, required): Destination path within the location
     - `url` (string, required): URL to download from (HTTP or HTTPS)
   - Execute:
     1. `val locationId = McpToolUtils.requireString(arguments, "location_id")`
     2. `val path = McpToolUtils.requireString(arguments, "path")`
     3. `val url = McpToolUtils.requireString(arguments, "url")`
     4. `val downloadedBytes = fileOperationProvider.downloadFromUrl(locationId, path, url)`
     5. Return `McpToolUtils.textResult("File downloaded successfully. Size: ${downloadedBytes} bytes. Saved to: $locationId/$path")`
   - Dependencies: `FileOperationProvider`

8. **`DeleteFileHandler`**
   - TOOL_NAME: `"delete_file"`
   - Description: `"Deletes a single file from an authorized storage location. Cannot delete directories."`
   - Input schema:
     - `location_id` (string, required)
     - `path` (string, required)
   - Execute: Call `fileOperationProvider.deleteFile()`, return success message
   - Dependencies: `FileOperationProvider`

9. **`registerFileTools()` function** (top-level):
```kotlin
fun registerFileTools(
    server: Server,
    storageLocationProvider: StorageLocationProvider,
    fileOperationProvider: FileOperationProvider,
) {
    ListStorageLocationsHandler(storageLocationProvider).register(server)
    ListFilesHandler(fileOperationProvider).register(server)
    ReadFileHandler(fileOperationProvider).register(server)
    WriteFileHandler(fileOperationProvider).register(server)
    AppendFileHandler(fileOperationProvider).register(server)
    FileReplaceHandler(fileOperationProvider).register(server)
    DownloadFromUrlHandler(fileOperationProvider).register(server)
    DeleteFileHandler(fileOperationProvider).register(server)
}
```

---

### Task 3.2: Register File Tools in McpServerService

**Definition of Done**: File tools registered during server startup.

#### Action 3.2.1: Update `McpServerService.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`
- **Type**: Modify
- **Changes**:

1. Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerFileTools
+import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
+import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
```

2. Add injected dependencies:
```diff
     @Inject lateinit var tunnelManager: TunnelManager
+
+    @Inject lateinit var storageLocationProvider: StorageLocationProvider
+
+    @Inject lateinit var fileOperationProvider: FileOperationProvider
```

3. Update `registerAllTools()`:
```diff
     private fun registerAllTools(server: Server) {
         // ... existing registrations ...
         registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider)
+        registerFileTools(server, storageLocationProvider, fileOperationProvider)
     }
```

---

### Task 3.3: Update McpIntegrationTestHelper

**Definition of Done**: Test helper updated with new mock dependencies and tool registrations.

#### Action 3.3.1: Update `McpIntegrationTestHelper.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`
- **Type**: Modify
- **Changes**:

1. Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerFileTools
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerAppManagementTools
+import com.danielealbano.androidremotecontrolmcp.services.storage.StorageLocationProvider
+import com.danielealbano.androidremotecontrolmcp.services.storage.FileOperationProvider
+import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
```

2. Update `createMockDependencies()`:
```diff
     fun createMockDependencies(): MockDependencies =
         MockDependencies(
             actionExecutor = mockk(relaxed = true),
             accessibilityServiceProvider = mockk(relaxed = true),
             screenCaptureProvider = mockk(relaxed = true),
             treeParser = mockk(relaxed = true),
             elementFinder = mockk(relaxed = true),
+            storageLocationProvider = mockk(relaxed = true),
+            fileOperationProvider = mockk(relaxed = true),
+            appManager = mockk(relaxed = true),
         )
```

3. Update `registerAllTools()`:
```diff
     fun registerAllTools(
         server: Server,
         deps: MockDependencies,
     ) {
         // ... existing registrations ...
         registerUtilityTools(...)
+        registerFileTools(server, deps.storageLocationProvider, deps.fileOperationProvider)
+        registerAppManagementTools(server, deps.appManager)
     }
```

4. Update `MockDependencies` data class:
```diff
 data class MockDependencies(
     val actionExecutor: ActionExecutor,
     val accessibilityServiceProvider: AccessibilityServiceProvider,
     val screenCaptureProvider: ScreenCaptureProvider,
     val treeParser: AccessibilityTreeParser,
     val elementFinder: ElementFinder,
+    val storageLocationProvider: StorageLocationProvider,
+    val fileOperationProvider: FileOperationProvider,
+    val appManager: AppManager,
 )
```

---

### Task 3.4: File Tools Integration Tests

**Definition of Done**: Integration tests for all 8 file tools covering success, error, and edge cases.

#### Action 3.4.1: Create `FileToolsIntegrationTest.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/FileToolsIntegrationTest.kt`
- **Type**: New file
- **Tests** (each uses `McpIntegrationTestHelper.withTestApplication` and MCP SDK client):

  - `list_storage_locations returns available locations`
  - `list_files with valid location returns file entries`
  - `list_files with unauthorized location returns permission denied error`
  - `list_files with offset and limit returns paginated results`
  - `read_file returns text content with line numbers`
  - `read_file with offset reads from specified line`
  - `read_file adds hasMore hint when more lines exist`
  - `read_file with unauthorized location returns permission denied error`
  - `write_file creates file successfully`
  - `write_file with empty content creates empty file`
  - `append_file appends content successfully`
  - `append_file returns error with hint when append unsupported`
  - `file_replace replaces first occurrence`
  - `file_replace with replace_all replaces all occurrences`
  - `file_replace returns zero count when string not found`
  - `download_from_url downloads file successfully`
  - `download_from_url returns error when HTTP not allowed`
  - `download_from_url returns error when Content-Length exceeds size limit`
  - `download_from_url returns error when streamed size exceeds size limit`
  - `download_from_url returns error for invalid URL`
  - `delete_file deletes file successfully`
  - `delete_file returns error for directory`

For each test:
1. Set up mocks on `deps.fileOperationProvider` or `deps.storageLocationProvider`
2. Call tool via `client.callTool(name = "tool_name", arguments = mapOf(...))`
3. Assert result content (TextContent text) and `isError` flag

**Run targeted tests**: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.FileToolsIntegrationTest"`

---

## User Story 4: App Management MCP Tools

**As an MCP client**, I want to list, open, and close applications on the Android device, so that I can manage the device's application state.

**Acceptance Criteria**:
- All 3 app management tools implemented following existing tool patterns
- `open_app` launches app by package ID
- `list_apps` supports filtering by type and name query
- `close_app` kills background processes with clear description about limitations
- Tools registered in McpServerService
- Integration tests pass
- Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 4.1: Implement AppManagementTools.kt

**Definition of Done**: All 3 app management tool handlers created.

#### Action 4.1.1: Create `AppManagementTools.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/AppManagementTools.kt`
- **Type**: New file
- **Description**: Contains all 3 app management tool handlers and the `registerAppManagementTools()` function.

**Tool handlers**:

1. **`OpenAppHandler`**
   - TOOL_NAME: `"open_app"`
   - Description: `"Opens (launches) an application by its package ID. The app must be installed and have a launchable activity."`
   - Input schema:
     - `package_id` (string, required): Application package name (e.g., "com.example.app")
   - Execute:
     1. `val packageId = McpToolUtils.requireString(arguments, "package_id")`
     2. `val result = appManager.openApp(packageId)`
     3. On success: `McpToolUtils.textResult("Application '$packageId' launched successfully.")`
     4. On failure: throw `McpToolException.ActionFailed("Failed to open application '$packageId': ${e.message}")`
   - Dependencies: `AppManager`

2. **`ListAppsHandler`**
   - TOOL_NAME: `"list_apps"`
   - Description: `"Lists installed applications on the device. Can filter by type (user, system, all) and by name substring."`
   - Input schema:
     - `filter` (string, optional, default `"all"`, enum: `["all", "user", "system"]`): Filter by app type
     - `name_query` (string, optional): Case-insensitive substring to filter by app name
   - Execute:
     1. Parse filter string to `AppFilter` enum (validate against allowed values)
     2. `val nameQuery = McpToolUtils.optionalString(arguments, "name_query", "")`
     3. `val apps = appManager.listInstalledApps(filter, nameQuery.ifEmpty { null })`
     4. Serialize to JSON array with fields: `package_id`, `name`, `version_name`, `version_code`, `is_system`
     5. Return text result with JSON
   - Dependencies: `AppManager`

3. **`CloseAppHandler`**
   - TOOL_NAME: `"close_app"`
   - Description: `"Kills a background application process. This only works for apps that are in the background. For foreground apps that are hung or unresponsive, first use the 'press_home' tool to send the app to the background, wait briefly, then call this tool. Note: some system processes may restart automatically after being killed."`
   - Input schema:
     - `package_id` (string, required): Application package name
   - Execute:
     1. `val packageId = McpToolUtils.requireString(arguments, "package_id")`
     2. `val result = appManager.closeApp(packageId)`
     3. On success: `McpToolUtils.textResult("Kill signal sent for application '$packageId'. Note: this only affects background processes.")`
     4. On failure: throw `McpToolException.ActionFailed(...)`
   - Dependencies: `AppManager`

4. **`registerAppManagementTools()` function** (top-level):
```kotlin
fun registerAppManagementTools(
    server: Server,
    appManager: AppManager,
) {
    OpenAppHandler(appManager).register(server)
    ListAppsHandler(appManager).register(server)
    CloseAppHandler(appManager).register(server)
}
```

---

### Task 4.2: Register App Management Tools in McpServerService

**Definition of Done**: App management tools registered during server startup.

#### Action 4.2.1: Update `McpServerService.kt`

- [ ] **File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`
- **Type**: Modify
- **Changes**:

1. Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerAppManagementTools
+import com.danielealbano.androidremotecontrolmcp.services.apps.AppManager
```

2. Add injected dependency:
```diff
     @Inject lateinit var fileOperationProvider: FileOperationProvider
+
+    @Inject lateinit var appManager: AppManager
```

3. Update `registerAllTools()`:
```diff
     private fun registerAllTools(server: Server) {
         // ... existing registrations ...
         registerFileTools(server, storageLocationProvider, fileOperationProvider)
+        registerAppManagementTools(server, appManager)
     }
```

---

### Task 4.3: App Management Integration Tests

**Definition of Done**: Integration tests for all 3 app management tools.

#### Action 4.3.1: Create `AppManagementToolsIntegrationTest.kt`

- [ ] **File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/AppManagementToolsIntegrationTest.kt`
- **Type**: New file
- **Tests**:

  - `open_app with valid package_id launches app successfully`
  - `open_app with unknown package_id returns error`
  - `open_app with missing package_id returns invalid params error`
  - `list_apps with no filter returns all apps`
  - `list_apps with user filter returns only user apps`
  - `list_apps with system filter returns only system apps`
  - `list_apps with name_query filters by name`
  - `list_apps with invalid filter returns error`
  - `close_app sends kill signal successfully`
  - `close_app with missing package_id returns invalid params error`

For each test:
1. Set up mocks on `deps.appManager`
2. Call tool via `client.callTool(name = "tool_name", arguments = mapOf(...))`
3. Assert result content and `isError` flag

**Run targeted tests**: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.AppManagementToolsIntegrationTest"`

---

## User Story 5: Documentation & Final Verification

**As a developer**, I want all documentation updated and all quality gates passing, so that the implementation is complete and maintainable.

**Acceptance Criteria**:
- MCP_TOOLS.md updated with all 11 new tools (schema, description, examples)
- PROJECT.md updated with new architecture, permissions, and file operation details
- Full build passes: `./gradlew build`
- Linting passes: `make lint`
- All tests pass: `make test`
- Final review confirms implementation matches this plan

---

### Task 5.1: Update MCP_TOOLS.md

**Definition of Done**: All 11 new tools documented with full schemas, descriptions, and examples.

#### Action 5.1.1: Add File Tools section to `MCP_TOOLS.md`

- [ ] **File**: `docs/MCP_TOOLS.md`
- **Type**: Modify
- **Changes**: Add a new "File Tools" section with documentation for all 8 file tools:
  - `list_storage_locations`: Schema, description, example response
  - `list_files`: Schema, description, pagination details, example
  - `read_file`: Schema, description, line numbering, hasMore hint, example
  - `write_file`: Schema, description, directory creation behavior, example
  - `append_file`: Schema, description, provider limitation note, example
  - `file_replace`: Schema, description, literal-only note, example
  - `download_from_url`: Schema, description, HTTP/HTTPS settings note, size limit, example
  - `delete_file`: Schema, description, file-only restriction, example

#### Action 5.1.2: Add App Management Tools section to `MCP_TOOLS.md`

- [ ] **File**: `docs/MCP_TOOLS.md`
- **Type**: Modify
- **Changes**: Add a new "App Management Tools" section with documentation for all 3 tools:
  - `open_app`: Schema, description, example
  - `list_apps`: Schema, description, filter options, example response
  - `close_app`: Schema, description, **limitations prominently noted**, `press_home` workaround, example

---

### Task 5.2: Update PROJECT.md

**Definition of Done**: Architecture documentation reflects new tools, providers, and permissions.

#### Action 5.2.1: Update PROJECT.md

- [ ] **File**: `docs/PROJECT.md`
- **Type**: Modify
- **Changes**:
  - Add `StorageLocationProvider`, `FileOperationProvider`, `AppManager` to the service/interface inventory
  - Add `StorageLocation`, `FileInfo`, `AppInfo`, `AppFilter` to data model inventory
  - Document new permissions: `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`
  - Document the SAF authorization flow
  - Document the virtual path system (`{location_id}/{relative_path}`)
  - Update the tool count (from 27 to 38 tools across 9 categories)
  - Document the file size limit setting
  - Document the download HTTP/HTTPS security settings
  - Add `StorageLocationsSection` to UI component inventory

---

### Task 5.3: Full Build, Lint, and Test Verification

**Definition of Done**: All quality gates pass.

#### Action 5.3.1: Run full lint check

- [ ] **Command**: `make lint`
- Expected: 0 warnings, 0 errors

#### Action 5.3.2: Run full test suite

- [ ] **Command**: `make test`
- Expected: All tests pass

#### Action 5.3.3: Run full build

- [ ] **Command**: `./gradlew build`
- Expected: BUILD SUCCESSFUL, no warnings

---

### Task 5.4: Final Review Against Plan

**Definition of Done**: Every action in this plan is checked, every file is verified, and the implementation is complete with no gaps.

#### Action 5.4.1: Review all implementations from the ground up

- [ ] Verify all 4 data models exist with correct fields
- [ ] Verify `StorageLocationProvider` interface and implementation
- [ ] Verify `FileOperationProvider` interface and implementation
- [ ] Verify `AppManager` interface and implementation
- [ ] Verify `ServerConfig.fileSizeLimitMb` field and constants
- [ ] Verify `SettingsRepository` and `SettingsRepositoryImpl` changes
- [ ] Verify DI module bindings in `AppModule.kt`
- [ ] Verify `AndroidManifest.xml` permissions
- [ ] Verify `McpToolUtils` new methods (`optionalInt`, `optionalBoolean`)
- [ ] Verify `StorageLocationsSection.kt` composable
- [ ] Verify `MainViewModel.kt` state flows and methods
- [ ] Verify `HomeScreen.kt` integration with SAF launcher
- [ ] Verify `FileTools.kt` — all 8 tool handlers registered with correct schemas
- [ ] Verify `AppManagementTools.kt` — all 3 tool handlers registered with correct schemas
- [ ] Verify `McpServerService.kt` registers both new tool categories
- [ ] Verify `McpIntegrationTestHelper.kt` updated with new mock dependencies
- [ ] Verify `takePersistableUriPermission` is called ONLY in `StorageLocationProviderImpl.authorizeLocation()` — NOT in HomeScreen
- [ ] Verify `readFile` streams line-by-line (does NOT load all lines into memory)
- [ ] Verify file size limit is checked in `readFile`, `writeFile`, `appendFile`, `replaceInFile`, `downloadFromUrl`
- [ ] Verify `downloadFromUrl` pre-checks Content-Length header before streaming
- [ ] Verify file size limit constants are defined ONLY in `ServerConfig` (not duplicated in `FileOperationProvider`)
- [ ] Verify `getInitialPickerUri` in ViewModel computes URI from cached state (not from provider method)
- [ ] Verify all unit tests exist and pass
- [ ] Verify all integration tests exist and pass
- [ ] Verify `MCP_TOOLS.md` documents all 11 new tools
- [ ] Verify `PROJECT.md` updated with new architecture details
- [ ] Verify no TODOs, no commented-out code, no placeholder stubs
- [ ] Verify no lint warnings or errors
- [ ] Verify build completes without warnings or errors
