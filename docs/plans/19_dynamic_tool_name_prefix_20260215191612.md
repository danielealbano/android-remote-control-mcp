# Plan 19: Dynamic Tool Name Prefix (`android_` + optional device slug)

## Overview

Add a configurable `android_` prefix to all MCP tool names, with an optional device slug that allows
differentiating multiple connected Android devices. When the slug is empty, tools are named
`android_<tool_name>`. When a slug is set (e.g., `pixel7`), tools become `android_<slug>_<tool_name>`.

The MCP server name (`Implementation.name`) also includes the slug when set:
- No slug: `"android-remote-control-mcp"`
- Slug `pixel7`: `"android-remote-control-mcp-pixel7"`

## Pre-condition

**This plan MUST be implemented AFTER the `feat/file-operations-and-app-management-tools` branch is merged into `main`.** That branch adds `FileTools.kt` (8 tools) and `AppManagementTools.kt` (3 tools), bringing the total tool count from 27 to 38. This plan covers all 38 tools.

**Pre-implementation check**: Before starting, verify that `AuthIntegrationTest.kt` has its `EXPECTED_TOOL_COUNT` updated to `38` (the file-operations branch should handle this). If it is still `27`, fix it as part of this plan's first commit.

## Design Decisions (Agreed)

- **Base prefix**: `android_` (always present)
- **Device slug**: Optional string stored in DataStore settings
- **Slug validation**: Letters (`a-z`, `A-Z`), digits (`0-9`), underscores (`_`) only. Max 20 characters. Empty is valid (no slug).
- **UI placement**: In `ConfigurationSection`, alongside port and binding address
- **Server restart**: Manual restart required. The slug text field is disabled while the server is running.
- **MCP server name**: Includes the slug when set
- **Test helper**: Accepts `deviceSlug` and derives the prefix internally. Dedicated test(s) ensure slug mechanism works.

## Scope

### Files Modified (production code)
| # | File | Description |
|---|------|-------------|
| 1 | `app/src/main/kotlin/.../data/model/ServerConfig.kt` | Add `deviceSlug` property + validation constants |
| 2 | `app/src/main/kotlin/.../data/repository/SettingsRepository.kt` | Add `updateDeviceSlug()`, `validateDeviceSlug()` |
| 3 | `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt` | DataStore key, impl methods, mapping |
| 4 | `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt` | Add `buildToolNamePrefix()` and `buildServerName()` utilities |
| 5 | `app/src/main/kotlin/.../mcp/tools/TouchActionTools.kt` | Add `toolNamePrefix` param to `register()` and `registerTouchActionTools()` |
| 6 | `app/src/main/kotlin/.../mcp/tools/ElementActionTools.kt` | Add `toolNamePrefix` param |
| 7 | `app/src/main/kotlin/.../mcp/tools/GestureTools.kt` | Add `toolNamePrefix` param |
| 8 | `app/src/main/kotlin/.../mcp/tools/ScreenIntrospectionTools.kt` | Add `toolNamePrefix` param |
| 9 | `app/src/main/kotlin/.../mcp/tools/SystemActionTools.kt` | Add `toolNamePrefix` param |
| 10 | `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt` | Add `toolNamePrefix` param |
| 11 | `app/src/main/kotlin/.../mcp/tools/UtilityTools.kt` | Add `toolNamePrefix` param |
| 12 | `app/src/main/kotlin/.../mcp/tools/FileTools.kt` | Add `toolNamePrefix` param |
| 13 | `app/src/main/kotlin/.../mcp/tools/AppManagementTools.kt` | Add `toolNamePrefix` param |
| 14 | `app/src/main/kotlin/.../services/mcp/McpServerService.kt` | Compute prefix from config, pass to tools, update server name |
| 15 | `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt` | Add slug state flows and `updateDeviceSlug()` |
| 16 | `app/src/main/kotlin/.../ui/screens/HomeScreen.kt` | Wire slug input/error state |
| 17 | `app/src/main/kotlin/.../ui/components/ConfigurationSection.kt` | Add slug `OutlinedTextField` |
| 18 | `app/src/main/res/values/strings.xml` | Add string resources for slug field |

### Files Modified (test code)
| # | File | Description |
|---|------|-------------|
| 19 | `app/src/test/kotlin/.../data/model/ServerConfigTest.kt` | Default value + companion constant tests for `deviceSlug` |
| 20 | `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt` | Slug persistence + validation tests |
| 21 | `app/src/test/kotlin/.../ui/viewmodels/MainViewModelTest.kt` | Slug update tests |
| 22 | `app/src/test/kotlin/.../mcp/tools/McpToolUtilsTest.kt` | `buildToolNamePrefix()` + `buildServerName()` tests |
| 23 | `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` | Accept `deviceSlug`, derive prefix internally, pass to registration |
| 24 | `app/src/test/kotlin/.../integration/McpProtocolIntegrationTest.kt` | Update expected tool names with prefix |
| 25 | `app/src/test/kotlin/.../integration/TouchActionIntegrationTest.kt` | Update tool names |
| 26 | `app/src/test/kotlin/.../integration/ElementActionIntegrationTest.kt` | Update tool names |
| 27 | `app/src/test/kotlin/.../integration/GestureIntegrationTest.kt` | Update tool names |
| 28 | `app/src/test/kotlin/.../integration/ScreenIntrospectionIntegrationTest.kt` | Update tool names |
| 29 | `app/src/test/kotlin/.../integration/SystemActionIntegrationTest.kt` | Update tool names |
| 30 | `app/src/test/kotlin/.../integration/TextInputIntegrationTest.kt` | Update tool names |
| 31 | `app/src/test/kotlin/.../integration/UtilityIntegrationTest.kt` | Update tool names |
| 32 | `app/src/test/kotlin/.../integration/FileToolsIntegrationTest.kt` | Update tool names |
| 33 | `app/src/test/kotlin/.../integration/AppManagementToolsIntegrationTest.kt` | Update tool names |
| 34 | `app/src/test/kotlin/.../integration/ErrorHandlingIntegrationTest.kt` | Update tool names |

### Files Modified (documentation)
| # | File | Description |
|---|------|-------------|
| 35 | `docs/MCP_TOOLS.md` | Add prefix documentation, update all tool name references |
| 36 | `docs/PROJECT.md` | Add `deviceSlug` setting documentation |

---

## User Story 1: Data Layer — Device Slug Setting

**As a** developer,
**I want** a `deviceSlug` setting stored in DataStore,
**So that** it can be used to construct dynamic tool name prefixes.

### Acceptance Criteria / Definition of Done
- [x] `ServerConfig.deviceSlug` exists with default `""`, max length `20`, and regex validation constant
- [x] `ServerConfigTest` has tests for `deviceSlug` default value and new companion constants
- [x] `SettingsRepository` has `updateDeviceSlug(slug: String)` and `validateDeviceSlug(slug: String): Result<String>`
- [x] `SettingsRepositoryImpl` persists slug via DataStore and maps it in `mapPreferencesToServerConfig()`
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.data.*"`
- [x] Lint passes on changed files

---

### Task 1.1: Add `deviceSlug` property and constants to `ServerConfig`

**Acceptance Criteria**: `ServerConfig` has `deviceSlug: String = ""` property and companion constants `MAX_DEVICE_SLUG_LENGTH = 20`, `DEVICE_SLUG_PATTERN` regex.

#### Action 1.1.1: Add `deviceSlug` property to `ServerConfig` data class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`

**Context**: Add after the `downloadTimeoutSeconds` property (line 41), before the closing `)`.

```diff
     val allowUnverifiedHttpsCerts: Boolean = false,
     val downloadTimeoutSeconds: Int = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
+    val deviceSlug: String = "",
 ) {
```

**Explanation**: New optional string property defaulting to empty (no slug). Added as the last property in the data class.

#### Action 1.1.2: Add KDoc for `deviceSlug` property

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`

**Context**: Add a `@property deviceSlug` line in the KDoc block (after `@property downloadTimeoutSeconds`, line 24).

```diff
  * @property downloadTimeoutSeconds Download timeout in seconds.
+ * @property deviceSlug Optional device identifier slug for tool name prefix (letters, digits, underscores; max 20 chars).
  */
```

#### Action 1.1.3: Add validation constants to companion object

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt`

**Context**: Add after `MAX_DOWNLOAD_TIMEOUT_SECONDS` (line 72), before closing `}` of companion.

```diff
         /** Maximum download timeout in seconds. */
         const val MAX_DOWNLOAD_TIMEOUT_SECONDS = 300
+
+        /** Maximum length for device slug. */
+        const val MAX_DEVICE_SLUG_LENGTH = 20
+
+        /** Pattern for valid device slug characters (letters, digits, underscores). */
+        val DEVICE_SLUG_PATTERN = Regex("^[a-zA-Z0-9_]*$")
     }
```

**Explanation**: Regex allows empty string (due to `*` quantifier). Letters (both cases), digits, and underscores only. Max length enforced separately by `validateDeviceSlug()`.

---

### Task 1.2: Add `updateDeviceSlug()` and `validateDeviceSlug()` to `SettingsRepository`

**Acceptance Criteria**: Interface has both methods with proper KDoc.

#### Action 1.2.1: Add `updateDeviceSlug()` method to `SettingsRepository` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

**Context**: Add after `updateDownloadTimeout` (line 123), before `validateDownloadTimeout`.

```diff
     /** Updates the download timeout in seconds. */
     suspend fun updateDownloadTimeout(seconds: Int)
+
+    /**
+     * Updates the device slug used for tool name prefix.
+     *
+     * @param slug The new device slug. Must pass [validateDeviceSlug] first.
+     */
+    suspend fun updateDeviceSlug(slug: String)
 
     /**
      * Validates a download timeout value.
```

#### Action 1.2.2: Add `validateDeviceSlug()` method to `SettingsRepository` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt`

**Context**: Add after `validateDownloadTimeout` (line 133), before `getAuthorizedLocations`.

```diff
     fun validateDownloadTimeout(seconds: Int): Result<Int>
+
+    /**
+     * Validates a device slug string.
+     *
+     * Valid slugs contain only letters (a-z, A-Z), digits (0-9), and underscores.
+     * Maximum length is [ServerConfig.MAX_DEVICE_SLUG_LENGTH] characters. Empty is valid.
+     *
+     * This is a pure validation function with no I/O; it is intentionally
+     * non-suspending so callers are not forced into a coroutine context.
+     *
+     * @return [Result.success] with the validated slug, or [Result.failure] with an [IllegalArgumentException].
+     */
+    fun validateDeviceSlug(slug: String): Result<String>
 
     /**
      * Returns the map of authorized storage location IDs to their tree URI strings.
```

---

### Task 1.3: Implement slug persistence in `SettingsRepositoryImpl`

**Acceptance Criteria**: DataStore key exists, `updateDeviceSlug()` persists, `validateDeviceSlug()` validates, `mapPreferencesToServerConfig()` reads slug.

#### Action 1.3.1: Add DataStore key for device slug

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**Context**: Add in companion object after `AUTHORIZED_LOCATIONS_KEY` (line 309).

```diff
             private val AUTHORIZED_LOCATIONS_KEY = stringPreferencesKey("authorized_storage_locations")
+            private val DEVICE_SLUG_KEY = stringPreferencesKey("device_slug")
```

#### Action 1.3.2: Implement `updateDeviceSlug()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**Context**: Add after `updateDownloadTimeout()` (line 157), before `validateDownloadTimeout()`.

```diff
         override suspend fun updateDownloadTimeout(seconds: Int) {
             dataStore.edit { prefs ->
                 prefs[DOWNLOAD_TIMEOUT_KEY] = seconds
             }
         }
+
+        override suspend fun updateDeviceSlug(slug: String) {
+            dataStore.edit { prefs ->
+                prefs[DEVICE_SLUG_KEY] = slug
+            }
+        }
 
         override fun validateDownloadTimeout(seconds: Int): Result<Int> =
```

#### Action 1.3.3: Implement `validateDeviceSlug()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**Context**: Add after `validateDownloadTimeout()` (after line 169), before `getAuthorizedLocations()`.

```diff
             )
         }
+
+        @Suppress("ReturnCount")
+        override fun validateDeviceSlug(slug: String): Result<String> {
+            if (slug.length > ServerConfig.MAX_DEVICE_SLUG_LENGTH) {
+                return Result.failure(
+                    IllegalArgumentException(
+                        "Device slug must be at most ${ServerConfig.MAX_DEVICE_SLUG_LENGTH} characters",
+                    ),
+                )
+            }
+            if (!ServerConfig.DEVICE_SLUG_PATTERN.matches(slug)) {
+                return Result.failure(
+                    IllegalArgumentException(
+                        "Device slug can only contain letters, digits, and underscores",
+                    ),
+                )
+            }
+            return Result.success(slug)
+        }
 
         override suspend fun getAuthorizedLocations(): Map<String, String> {
```

#### Action 1.3.4: Add `deviceSlug` to `mapPreferencesToServerConfig()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

**Context**: In `mapPreferencesToServerConfig()`, add after `downloadTimeoutSeconds` mapping (line 265), before closing `)`.

```diff
                 downloadTimeoutSeconds =
                     prefs[DOWNLOAD_TIMEOUT_KEY]
                         ?: ServerConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
+                deviceSlug = prefs[DEVICE_SLUG_KEY] ?: "",
             )
```

---

### Task 1.4: Add unit tests for device slug in `SettingsRepositoryImplTest`

**Acceptance Criteria**: Tests cover persistence, default value, validation (valid slug, too long, invalid chars, empty).

#### Action 1.4.1: Add test for default deviceSlug value

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

**Context**: In the `GetServerConfig` nested class, in the `returns default values when no settings stored` test, add assertion for `deviceSlug`.

```diff
+                assertEquals("", config.deviceSlug)
```

**Explanation**: Add this assertion alongside existing default checks in the existing test.

#### Action 1.4.2: Add nested class for `deviceSlug` tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

**Context**: Add a new `@Nested` class at the end of the test class (before the final closing `}`), following the pattern of existing nested classes.

```kotlin
    @Nested
    @DisplayName("updateDeviceSlug")
    inner class UpdateDeviceSlug {
        @Test
        fun `persists device slug`() =
            testScope.runTest {
                repository.updateDeviceSlug("pixel7")
                val config = repository.getServerConfig()
                assertEquals("pixel7", config.deviceSlug)
            }

        @Test
        fun `persists empty device slug`() =
            testScope.runTest {
                repository.updateDeviceSlug("test_device")
                repository.updateDeviceSlug("")
                val config = repository.getServerConfig()
                assertEquals("", config.deviceSlug)
            }

        @Test
        fun `emits updated config via flow`() =
            testScope.runTest {
                repository.serverConfig.test {
                    val initial = awaitItem()
                    assertEquals("", initial.deviceSlug)

                    repository.updateDeviceSlug("my_phone")
                    val updated = awaitItem()
                    assertEquals("my_phone", updated.deviceSlug)

                    cancelAndIgnoreRemainingEvents()
                }
            }
    }

    @Nested
    @DisplayName("validateDeviceSlug")
    inner class ValidateDeviceSlug {
        @Test
        fun `accepts empty slug`() {
            assertTrue(repository.validateDeviceSlug("").isSuccess)
        }

        @Test
        fun `accepts valid slug with letters and digits`() {
            assertTrue(repository.validateDeviceSlug("pixel7").isSuccess)
        }

        @Test
        fun `accepts valid slug with underscores`() {
            assertTrue(repository.validateDeviceSlug("work_phone_1").isSuccess)
        }

        @Test
        fun `accepts valid slug with uppercase letters`() {
            assertTrue(repository.validateDeviceSlug("MyPhone").isSuccess)
        }

        @Test
        fun `accepts slug with only underscores`() {
            assertTrue(repository.validateDeviceSlug("___").isSuccess)
        }

        @Test
        fun `accepts slug at max length`() {
            val slug = "a".repeat(ServerConfig.MAX_DEVICE_SLUG_LENGTH)
            assertTrue(repository.validateDeviceSlug(slug).isSuccess)
        }

        @Test
        fun `rejects slug exceeding max length`() {
            val slug = "a".repeat(ServerConfig.MAX_DEVICE_SLUG_LENGTH + 1)
            val result = repository.validateDeviceSlug(slug)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("at most") == true)
        }

        @Test
        fun `rejects slug with hyphens`() {
            val result = repository.validateDeviceSlug("work-phone")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("letters, digits, and underscores") == true)
        }

        @Test
        fun `rejects slug with spaces`() {
            val result = repository.validateDeviceSlug("my phone")
            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects slug with special characters`() {
            val result = repository.validateDeviceSlug("phone@1")
            assertTrue(result.isFailure)
        }
    }
```

**Explanation**: Follows the same `@Nested` / `@DisplayName` pattern used throughout the test file. Tests cover persistence (including flow emission), empty string, valid patterns, max length boundary, and invalid character rejection.

---

### Task 1.5: Add unit tests for device slug in `ServerConfigTest`

**Acceptance Criteria**: Tests cover `deviceSlug` default value and new companion constants (`MAX_DEVICE_SLUG_LENGTH`, `DEVICE_SLUG_PATTERN`).

#### Action 1.5.1: Add default value test for `deviceSlug`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt`

**Context**: In the `DefaultValues` nested class (after the last existing default value test), add:

```kotlin
        @Test
        fun `default deviceSlug is empty`() {
            val config = ServerConfig()
            assertEquals("", config.deviceSlug)
        }
```

#### Action 1.5.2: Add companion constant tests for `MAX_DEVICE_SLUG_LENGTH` and `DEVICE_SLUG_PATTERN`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfigTest.kt`

**Context**: In the `CompanionConstants` nested class (after the last existing constant test), add:

```kotlin
        @Test
        fun `MAX_DEVICE_SLUG_LENGTH is 20`() {
            assertEquals(20, ServerConfig.MAX_DEVICE_SLUG_LENGTH)
        }

        @Test
        fun `DEVICE_SLUG_PATTERN matches valid slugs`() {
            assertTrue(ServerConfig.DEVICE_SLUG_PATTERN.matches("pixel7"))
            assertTrue(ServerConfig.DEVICE_SLUG_PATTERN.matches("work_phone"))
            assertTrue(ServerConfig.DEVICE_SLUG_PATTERN.matches(""))
            assertTrue(ServerConfig.DEVICE_SLUG_PATTERN.matches("ABC_123"))
        }

        @Test
        fun `DEVICE_SLUG_PATTERN rejects invalid slugs`() {
            assertFalse(ServerConfig.DEVICE_SLUG_PATTERN.matches("work-phone"))
            assertFalse(ServerConfig.DEVICE_SLUG_PATTERN.matches("has space"))
            assertFalse(ServerConfig.DEVICE_SLUG_PATTERN.matches("phone@1"))
        }
```

**Note**: Add `import org.junit.jupiter.api.Assertions.assertTrue` if not already imported.

---

## User Story 2: Tool Name Prefix Infrastructure

**As a** developer,
**I want** all MCP tool names to be dynamically prefixed,
**So that** models can distinguish this server's tools from other MCP servers.

### Acceptance Criteria / Definition of Done
- [x] `McpToolUtils.buildToolNamePrefix()` correctly constructs prefix strings (`"android_"` or `"android_<slug>_"`)
- [x] `McpToolUtils.buildServerName()` correctly constructs server names
- [x] All 9 `register*Tools()` functions accept a `toolNamePrefix: String` parameter
- [x] All individual tool `register()` methods accept a `toolNamePrefix: String` parameter and use it
- [x] `McpServerService.registerAllTools()` computes the prefix from `config.deviceSlug` and passes it
- [x] MCP server name includes the slug when set
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtilsTest"`
- [x] Lint passes on changed files

---

### Task 2.1: Add `buildToolNamePrefix()` and `buildServerName()` utilities to `McpToolUtils`

**Acceptance Criteria**: `buildToolNamePrefix()` returns `"android_"` or `"android_<slug>_"`. `buildServerName()` returns the server implementation name with optional slug.

#### Action 2.1.1: Add utility functions to `McpToolUtils`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtils.kt`

**Context**: Add at the end of the `McpToolUtils` object, before the closing `}` (before line 342).

```diff
     /** Maximum duration in milliseconds for any gesture/action. */
     const val MAX_DURATION_MS = 60000L
+
+    /** Base prefix for all MCP tool names. */
+    private const val TOOL_NAME_BASE_PREFIX = "android"
+
+    /**
+     * Builds the tool name prefix string from the device slug.
+     *
+     * - Empty slug: `"android_"`
+     * - Non-empty slug: `"android_<slug>_"` (e.g., `"android_pixel7_"`)
+     *
+     * The returned prefix is intended to be concatenated with the tool's base name
+     * (e.g., `"${prefix}tap"` → `"android_tap"` or `"android_pixel7_tap"`).
+     *
+     * @param deviceSlug The optional device slug (empty string means no slug).
+     * @return The prefix string ending with `_`.
+     */
+    fun buildToolNamePrefix(deviceSlug: String): String =
+        if (deviceSlug.isEmpty()) {
+            "${TOOL_NAME_BASE_PREFIX}_"
+        } else {
+            "${TOOL_NAME_BASE_PREFIX}_${deviceSlug}_"
+        }
+
+    /**
+     * Builds the MCP server implementation name, optionally including the device slug.
+     *
+     * - Empty slug: `"android-remote-control-mcp"`
+     * - Non-empty slug: `"android-remote-control-mcp-<slug>"` (e.g., `"android-remote-control-mcp-pixel7"`)
+     *
+     * @param deviceSlug The optional device slug (empty string means no slug).
+     * @return The server implementation name.
+     */
+    fun buildServerName(deviceSlug: String): String =
+        if (deviceSlug.isEmpty()) {
+            "android-remote-control-mcp"
+        } else {
+            "android-remote-control-mcp-$deviceSlug"
+        }
 }
```

#### Action 2.1.2: Add unit tests for `buildToolNamePrefix()` and `buildServerName()`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtilsTest.kt`

**Context**: Add new `@Nested` test classes in the existing test class, following the existing `@Nested @DisplayName` pattern.

```kotlin
    @Nested
    @DisplayName("buildToolNamePrefix")
    inner class BuildToolNamePrefixTests {
        @Test
        @DisplayName("returns android_ for empty slug")
        fun returnsAndroidPrefixForEmptySlug() {
            assertEquals("android_", McpToolUtils.buildToolNamePrefix(""))
        }

        @Test
        @DisplayName("returns android_slug_ for non-empty slug")
        fun returnsAndroidSlugPrefixForNonEmptySlug() {
            assertEquals("android_pixel7_", McpToolUtils.buildToolNamePrefix("pixel7"))
        }

        @Test
        @DisplayName("handles slug with underscores")
        fun handlesSlugWithUnderscores() {
            assertEquals("android_work_phone_", McpToolUtils.buildToolNamePrefix("work_phone"))
        }
    }

    @Nested
    @DisplayName("buildServerName")
    inner class BuildServerNameTests {
        @Test
        @DisplayName("returns default name for empty slug")
        fun returnsDefaultNameForEmptySlug() {
            assertEquals("android-remote-control-mcp", McpToolUtils.buildServerName(""))
        }

        @Test
        @DisplayName("includes slug in server name")
        fun includesSlugInServerName() {
            assertEquals("android-remote-control-mcp-pixel7", McpToolUtils.buildServerName("pixel7"))
        }

        @Test
        @DisplayName("handles slug with underscores")
        fun handlesSlugWithUnderscores() {
            assertEquals("android-remote-control-mcp-work_phone", McpToolUtils.buildServerName("work_phone"))
        }
    }
```

---

### Task 2.2: Add `toolNamePrefix` parameter to all tool registration

**Acceptance Criteria**: Every tool `register()` method and every `register*Tools()` function accept and use a `toolNamePrefix: String` parameter. Tool names use `"$toolNamePrefix$TOOL_NAME"`.

The same pattern applies to all 9 tool files and all 38 tool classes. The pattern is:

**For each tool class** (e.g., `TapTool`):

```diff
-    fun register(server: Server) {
+    fun register(server: Server, toolNamePrefix: String) {
         server.addTool(
-            name = TOOL_NAME,
+            name = "$toolNamePrefix$TOOL_NAME",
```

**For each `register*Tools()` function** (e.g., `registerTouchActionTools()`):

```diff
 fun registerTouchActionTools(
     server: Server,
     actionExecutor: ActionExecutor,
+    toolNamePrefix: String,
 ) {
-    TapTool(actionExecutor).register(server)
+    TapTool(actionExecutor).register(server, toolNamePrefix)
```

Below are the exact changes for each file.

#### Action 2.2.1: Update `TouchActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TouchActionTools.kt`

**Tool classes to update** (5 tools): `TapTool`, `LongPressTool`, `DoubleTapTool`, `SwipeTool`, `ScrollTool`

For each tool class, change `fun register(server: Server)` to `fun register(server: Server, toolNamePrefix: String)` and change `name = TOOL_NAME` to `name = "$toolNamePrefix$TOOL_NAME"`.

For `registerTouchActionTools()`:
```diff
 fun registerTouchActionTools(
     server: Server,
     actionExecutor: ActionExecutor,
+    toolNamePrefix: String,
 ) {
-    TapTool(actionExecutor).register(server)
-    LongPressTool(actionExecutor).register(server)
-    DoubleTapTool(actionExecutor).register(server)
-    SwipeTool(actionExecutor).register(server)
-    ScrollTool(actionExecutor).register(server)
+    TapTool(actionExecutor).register(server, toolNamePrefix)
+    LongPressTool(actionExecutor).register(server, toolNamePrefix)
+    DoubleTapTool(actionExecutor).register(server, toolNamePrefix)
+    SwipeTool(actionExecutor).register(server, toolNamePrefix)
+    ScrollTool(actionExecutor).register(server, toolNamePrefix)
 }
```

#### Action 2.2.2: Update `ElementActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Tool classes to update** (5 tools): `FindElementsTool`, `ClickElementTool`, `LongClickElementTool`, `SetTextTool`, `ScrollToElementTool`

Same pattern: add `toolNamePrefix: String` to each `register()` and use `"$toolNamePrefix$TOOL_NAME"`.

For `registerElementActionTools()`:
```diff
 fun registerElementActionTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     elementFinder: ElementFinder,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    toolNamePrefix: String,
 ) {
```
Pass `toolNamePrefix` to each tool's `.register(server, toolNamePrefix)`.

#### Action 2.2.3: Update `GestureTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GestureTools.kt`

**Tool classes to update** (2 tools): `PinchTool`, `CustomGestureTool`

For `registerGestureTools()`:
```diff
 fun registerGestureTools(
     server: Server,
     actionExecutor: ActionExecutor,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.4: Update `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Tool classes to update** (1 tool): `GetScreenStateTool`

For `registerScreenIntrospectionTools()`:
```diff
 fun registerScreenIntrospectionTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     accessibilityServiceProvider: AccessibilityServiceProvider,
     screenCaptureProvider: ScreenCaptureProvider,
     compactTreeFormatter: CompactTreeFormatter,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.5: Update `SystemActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt`

**Tool classes to update** (6 tools): `PressBackTool`, `PressHomeTool`, `PressRecentsTool`, `OpenNotificationsTool`, `OpenQuickSettingsTool`, `GetDeviceLogsTool`

For `registerSystemActionTools()`:
```diff
 fun registerSystemActionTools(
     server: Server,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.6: Update `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

**Tool classes to update** (3 tools): `InputTextTool`, `ClearTextTool`, `PressKeyTool`

For `registerTextInputTools()`:
```diff
 fun registerTextInputTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.7: Update `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Tool classes to update** (5 tools): `GetClipboardTool`, `SetClipboardTool`, `WaitForElementTool`, `WaitForIdleTool`, `GetElementDetailsTool`

For `registerUtilityTools()`:
```diff
 fun registerUtilityTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     elementFinder: ElementFinder,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.8: Update `FileTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/FileTools.kt`

**Tool classes to update** (8 tools): `ListStorageLocationsTool`, `ListFilesTool`, `ReadFileTool`, `WriteFileTool`, `AppendFileTool`, `FileReplaceTool`, `DownloadFromUrlTool`, `DeleteFileTool`

For `registerFileTools()`:
```diff
 fun registerFileTools(
     server: Server,
     storageLocationProvider: StorageLocationProvider,
     fileOperationProvider: FileOperationProvider,
+    toolNamePrefix: String,
 ) {
```

#### Action 2.2.9: Update `AppManagementTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/AppManagementTools.kt`

**Tool classes to update** (3 tools): `OpenAppTool`, `ListAppsTool`, `CloseAppTool`

For `registerAppManagementTools()`:
```diff
 fun registerAppManagementTools(
     server: Server,
     appManager: AppManager,
+    toolNamePrefix: String,
 ) {
```

---

### Task 2.3: Update `McpServerService` to compute and pass prefix

**Acceptance Criteria**: Server reads `deviceSlug` from config, computes tool name prefix using `McpToolUtils.buildToolNamePrefix()`, passes prefix to `registerAllTools()` which passes to each `register*Tools()` function. Server name includes slug via `McpToolUtils.buildServerName()`.

#### Action 2.3.1: Update `registerAllTools()` to accept and pass `toolNamePrefix`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

```diff
-    private fun registerAllTools(server: Server) {
+    private fun registerAllTools(server: Server, toolNamePrefix: String) {
         registerScreenIntrospectionTools(
             server,
             treeParser,
             accessibilityServiceProvider,
             screenCaptureProvider,
             compactTreeFormatter,
+            toolNamePrefix,
         )
-        registerSystemActionTools(server, actionExecutor, accessibilityServiceProvider)
-        registerTouchActionTools(server, actionExecutor)
-        registerGestureTools(server, actionExecutor)
-        registerElementActionTools(server, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)
-        registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider)
-        registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider)
-        registerFileTools(server, storageLocationProvider, fileOperationProvider)
-        registerAppManagementTools(server, appManager)
+        registerSystemActionTools(server, actionExecutor, accessibilityServiceProvider, toolNamePrefix)
+        registerTouchActionTools(server, actionExecutor, toolNamePrefix)
+        registerGestureTools(server, actionExecutor, toolNamePrefix)
+        registerElementActionTools(server, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, toolNamePrefix)
+        registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider, toolNamePrefix)
+        registerUtilityTools(server, treeParser, elementFinder, accessibilityServiceProvider, toolNamePrefix)
+        registerFileTools(server, storageLocationProvider, fileOperationProvider, toolNamePrefix)
+        registerAppManagementTools(server, appManager, toolNamePrefix)
     }
```

#### Action 2.3.2: Compute prefix and update server name in `startServer()`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

**Context**: In `startServer()`, after reading config (line 140), compute the tool name prefix. Also update server name and the `registerAllTools()` call.

```diff
             val config = settingsRepository.serverConfig.first()
+            val toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug)
             Log.i(
                 TAG,
-                "Starting MCP server with config: port=${config.port}, binding=${config.bindingAddress.address}",
+                "Starting MCP server with config: port=${config.port}, binding=${config.bindingAddress.address}, toolNamePrefix=$toolNamePrefix",
             )
```

```diff
             val sdkServer =
                 Server(
                     serverInfo =
                         Implementation(
-                            name = "android-remote-control-mcp",
+                            name = McpToolUtils.buildServerName(config.deviceSlug),
                             version = com.danielealbano.androidremotecontrolmcp.BuildConfig.VERSION_NAME,
                         ),
```

```diff
-            registerAllTools(sdkServer)
+            registerAllTools(sdkServer, toolNamePrefix)
```

**Note**: Add import for `McpToolUtils` if not already imported:
```kotlin
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
```

**Explanation**: The prefix is computed once from the config using `buildToolNamePrefix()` and passed down to all tool registrations. The server name uses the slug via `buildServerName()`.

---

## User Story 3: UI — Device Slug Configuration

**As a** user,
**I want** to configure an optional device slug in the app settings,
**So that** my MCP tools have a unique prefix identifying my device.

### Acceptance Criteria / Definition of Done
- [x] Device slug text field appears in `ConfigurationSection`, between port and bearer token
- [x] Text field is disabled when server is running
- [x] Validation errors display below the field (invalid chars, too long)
- [x] Valid input is persisted to DataStore via SettingsRepository
- [x] ViewModel handles input, validation, and persistence
- [x] All unit tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModelTest"`
- [x] Lint passes on changed files

---

### Task 3.1: Add string resources

**Acceptance Criteria**: String resources exist for the device slug label and hint.

#### Action 3.1.1: Add string resources

**File**: `app/src/main/res/values/strings.xml`

**Context**: Add after `config_port_label` (line 31).

```diff
     <string name="config_port_label">Port</string>
+    <string name="config_device_slug_label">Device Slug (optional)</string>
+    <string name="config_device_slug_hint">e.g., pixel7, work_phone</string>
     <string name="config_bearer_token_label">Bearer Token</string>
```

---

### Task 3.2: Add slug state and update method to `MainViewModel`

**Acceptance Criteria**: ViewModel has `_deviceSlugInput`, `_deviceSlugError` state flows, `updateDeviceSlug()` method with validation.

#### Action 3.2.1: Add state flows for device slug

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

**Context**: Add after `_downloadTimeoutError` (line 94-95), before `_pendingAuthorizationLocationId`.

```diff
         private val _downloadTimeoutError = MutableStateFlow<String?>(null)
         val downloadTimeoutError: StateFlow<String?> = _downloadTimeoutError.asStateFlow()
+
+        private val _deviceSlugInput = MutableStateFlow("")
+        val deviceSlugInput: StateFlow<String> = _deviceSlugInput.asStateFlow()
+
+        private val _deviceSlugError = MutableStateFlow<String?>(null)
+        val deviceSlugError: StateFlow<String?> = _deviceSlugError.asStateFlow()
 
         private val _pendingAuthorizationLocationId = MutableStateFlow<String?>(null)
```

#### Action 3.2.2: Initialize slug from config flow

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

**Context**: In the `init` block's `settingsRepository.serverConfig.collect` lambda (around line 108-109), add slug initialization.

```diff
                     _fileSizeLimitInput.value = config.fileSizeLimitMb.toString()
                     _downloadTimeoutInput.value = config.downloadTimeoutSeconds.toString()
+                    _deviceSlugInput.value = config.deviceSlug
                 }
```

#### Action 3.2.3: Add `updateDeviceSlug()` method

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

**Context**: Add after `updateAllowUnverifiedHttpsCerts()` (line 390), before `getInitialPickerUri()`.

```diff
         fun updateAllowUnverifiedHttpsCerts(enabled: Boolean) {
             viewModelScope.launch(ioDispatcher) {
                 settingsRepository.updateAllowUnverifiedHttpsCerts(enabled)
             }
         }
+
+        fun updateDeviceSlug(slug: String) {
+            _deviceSlugInput.value = slug
+
+            val result = settingsRepository.validateDeviceSlug(slug)
+            if (result.isFailure) {
+                _deviceSlugError.value = result.exceptionOrNull()?.message
+                return
+            }
+
+            _deviceSlugError.value = null
+            viewModelScope.launch(ioDispatcher) {
+                settingsRepository.updateDeviceSlug(slug)
+            }
+        }
 
         @Suppress("TooGenericExceptionCaught")
         fun getInitialPickerUri(locationId: String): Uri? {
```

**Note**: The `@Suppress("TooGenericExceptionCaught")` annotation already exists on `getInitialPickerUri()` — it is shown here only as a context anchor. Do NOT duplicate it. The only new code is the `updateDeviceSlug()` method inserted before it.

---

### Task 3.3: Add slug text field to `ConfigurationSection`

**Acceptance Criteria**: `OutlinedTextField` for device slug appears between port input and bearer token display, disabled when server is running.

#### Action 3.3.1: Add parameters to `ConfigurationSection` composable

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConfigurationSection.kt`

**Context**: Add parameters to the function signature (between `portError` and `bearerToken`).

```diff
 fun ConfigurationSection(
     bindingAddress: BindingAddress,
     portInput: String,
     portError: String?,
+    deviceSlugInput: String,
+    deviceSlugError: String?,
     bearerToken: String,
     autoStartEnabled: Boolean,
     httpsEnabled: Boolean,
     certificateSource: CertificateSource,
     hostnameInput: String,
     hostnameError: String?,
     isServerRunning: Boolean,
     onBindingAddressChange: (BindingAddress) -> Unit,
     onPortChange: (String) -> Unit,
+    onDeviceSlugChange: (String) -> Unit,
     onRegenerateToken: () -> Unit,
     onCopyToken: () -> Unit,
```

#### Action 3.3.2: Add `OutlinedTextField` for device slug in the composable body

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConfigurationSection.kt`

**Context**: Add after the Port Input `OutlinedTextField` and its spacer (after line 139), before the Bearer Token Display section.

```diff
             Spacer(modifier = Modifier.height(16.dp))
 
+            // Device Slug Input
+            OutlinedTextField(
+                value = deviceSlugInput,
+                onValueChange = onDeviceSlugChange,
+                label = { Text(stringResource(R.string.config_device_slug_label)) },
+                placeholder = { Text(stringResource(R.string.config_device_slug_hint)) },
+                isError = deviceSlugError != null,
+                supportingText = deviceSlugError?.let { { Text(it) } },
+                singleLine = true,
+                enabled = !isServerRunning,
+                modifier = Modifier.fillMaxWidth(),
+            )
+
+            Spacer(modifier = Modifier.height(16.dp))
+
             // Bearer Token Display
```

#### Action 3.3.3: Update `ConfigurationSectionPreview`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/ConfigurationSection.kt`

**Context**: Update the preview composable to include the new parameters.

```diff
         ConfigurationSection(
             bindingAddress = BindingAddress.LOCALHOST,
             portInput = "8080",
             portError = null,
+            deviceSlugInput = "",
+            deviceSlugError = null,
             bearerToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
             ...
             onPortChange = {},
+            onDeviceSlugChange = {},
             onRegenerateToken = {},
```

---

### Task 3.4: Wire slug in `HomeScreen`

**Acceptance Criteria**: HomeScreen collects slug state from ViewModel and passes it to ConfigurationSection.

#### Action 3.4.1: Collect slug state flows

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`

**Context**: Add after `hostnameError` collection (line 60).

```diff
     val hostnameError by viewModel.hostnameError.collectAsStateWithLifecycle()
+    val deviceSlugInput by viewModel.deviceSlugInput.collectAsStateWithLifecycle()
+    val deviceSlugError by viewModel.deviceSlugError.collectAsStateWithLifecycle()
     val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
```

#### Action 3.4.2: Pass slug state to `ConfigurationSection`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt`

**Context**: In the `ConfigurationSection(...)` call, add after `portError`.

```diff
             ConfigurationSection(
                 bindingAddress = serverConfig.bindingAddress,
                 portInput = portInput,
                 portError = portError,
+                deviceSlugInput = deviceSlugInput,
+                deviceSlugError = deviceSlugError,
                 bearerToken = serverConfig.bearerToken,
                 ...
                 onPortChange = viewModel::updatePort,
+                onDeviceSlugChange = viewModel::updateDeviceSlug,
                 onRegenerateToken = viewModel::generateNewBearerToken,
```

---

### Task 3.5: Add ViewModel tests for device slug

**Acceptance Criteria**: Tests cover valid slug update, invalid slug rejection, empty slug.

#### Action 3.5.1: Add slug tests to `MainViewModelTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt`

**Context**: Add new test methods. The `settingsRepository` mock needs `validateDeviceSlug` behavior set up.

```kotlin
    @Test
    fun `updateDeviceSlug with valid slug clears error and saves`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("pixel7") } returns Result.success("pixel7")

            viewModel.updateDeviceSlug("pixel7")
            advanceUntilIdle()

            assertEquals("pixel7", viewModel.deviceSlugInput.value)
            assertNull(viewModel.deviceSlugError.value)
            coVerify { settingsRepository.updateDeviceSlug("pixel7") }
        }

    @Test
    fun `updateDeviceSlug with empty slug clears error and saves`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("") } returns Result.success("")

            viewModel.updateDeviceSlug("")
            advanceUntilIdle()

            assertEquals("", viewModel.deviceSlugInput.value)
            assertNull(viewModel.deviceSlugError.value)
            coVerify { settingsRepository.updateDeviceSlug("") }
        }

    @Test
    fun `updateDeviceSlug with invalid slug sets error and does not save`() =
        runTest {
            every { settingsRepository.validateDeviceSlug("work-phone") } returns
                Result.failure(IllegalArgumentException("Device slug can only contain letters, digits, and underscores"))

            viewModel.updateDeviceSlug("work-phone")
            advanceUntilIdle()

            assertEquals("work-phone", viewModel.deviceSlugInput.value)
            assertEquals(
                "Device slug can only contain letters, digits, and underscores",
                viewModel.deviceSlugError.value,
            )
            coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
        }

    @Test
    fun `updateDeviceSlug with too long slug sets error and does not save`() =
        runTest {
            val longSlug = "a".repeat(21)
            every { settingsRepository.validateDeviceSlug(longSlug) } returns
                Result.failure(IllegalArgumentException("Device slug must be at most 20 characters"))

            viewModel.updateDeviceSlug(longSlug)
            advanceUntilIdle()

            assertEquals(longSlug, viewModel.deviceSlugInput.value)
            assertEquals("Device slug must be at most 20 characters", viewModel.deviceSlugError.value)
            coVerify(exactly = 0) { settingsRepository.updateDeviceSlug(any()) }
        }

    @Test
    fun `initial state loads deviceSlug from repository`() =
        runTest {
            configFlow.value = configFlow.value.copy(deviceSlug = "test_device")
            advanceUntilIdle()

            assertEquals("test_device", viewModel.deviceSlugInput.value)
        }
```

---

## User Story 4: Test Infrastructure — Prefix Support

**As a** developer,
**I want** all integration tests to use the prefixed tool names,
**So that** tests verify the correct tool name format.

### Acceptance Criteria / Definition of Done
- [x] `McpIntegrationTestHelper` accepts `deviceSlug` and derives the prefix internally via `McpToolUtils.buildToolNamePrefix()`
- [x] `McpProtocolIntegrationTest` uses prefixed expected tool names
- [x] All integration tests use prefixed tool names in `client.callTool(name = ...)` calls
- [x] At least one dedicated test verifies slug-based tool names work
- [x] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.*"`
- [x] Lint passes on changed files

---

### Task 4.1: Update `McpIntegrationTestHelper`

**Acceptance Criteria**: Helper accepts `deviceSlug` and derives the prefix internally via `McpToolUtils.buildToolNamePrefix()`. No separate `toolNamePrefix` parameter — the slug is the single source of truth.

#### Action 4.1.1: Update `registerAllTools()` to accept `deviceSlug` and compute prefix internally

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

```diff
     fun registerAllTools(
         server: Server,
         deps: MockDependencies,
+        deviceSlug: String = "",
     ) {
+        val toolNamePrefix = McpToolUtils.buildToolNamePrefix(deviceSlug)
         registerScreenIntrospectionTools(
             server,
             deps.treeParser,
             deps.accessibilityServiceProvider,
             deps.screenCaptureProvider,
             CompactTreeFormatter(),
+            toolNamePrefix,
         )
         registerSystemActionTools(
             server,
             deps.actionExecutor,
             deps.accessibilityServiceProvider,
+            toolNamePrefix,
         )
-        registerTouchActionTools(server, deps.actionExecutor)
-        registerGestureTools(server, deps.actionExecutor)
+        registerTouchActionTools(server, deps.actionExecutor, toolNamePrefix)
+        registerGestureTools(server, deps.actionExecutor, toolNamePrefix)
         registerElementActionTools(
             server,
             deps.treeParser,
             deps.elementFinder,
             deps.actionExecutor,
             deps.accessibilityServiceProvider,
+            toolNamePrefix,
         )
         registerTextInputTools(
             server,
             deps.treeParser,
             deps.actionExecutor,
             deps.accessibilityServiceProvider,
+            toolNamePrefix,
         )
         registerUtilityTools(
             server,
             deps.treeParser,
             deps.elementFinder,
             deps.accessibilityServiceProvider,
+            toolNamePrefix,
         )
-        registerFileTools(server, deps.storageLocationProvider, deps.fileOperationProvider)
-        registerAppManagementTools(server, deps.appManager)
+        registerFileTools(server, deps.storageLocationProvider, deps.fileOperationProvider, toolNamePrefix)
+        registerAppManagementTools(server, deps.appManager, toolNamePrefix)
     }
```

#### Action 4.1.2: Update `createSdkServer()` to accept `deviceSlug`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

```diff
-    fun createSdkServer(deps: MockDependencies): Server {
+    fun createSdkServer(
+        deps: MockDependencies,
+        deviceSlug: String = "",
+    ): Server {
         val server =
             Server(
                 serverInfo =
                     Implementation(
-                        name = "android-remote-control-mcp",
+                        name = McpToolUtils.buildServerName(deviceSlug),
                         version = "test",
                     ),
```

```diff
-        registerAllTools(server, deps)
+        registerAllTools(server, deps, deviceSlug)
         return server
     }
```

#### Action 4.1.3: Update `withTestApplication()` and `withRawTestApplication()` to accept `deviceSlug`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt`

```diff
     suspend fun withTestApplication(
         deps: MockDependencies = createMockDependencies(),
+        deviceSlug: String = "",
         testBlock: suspend (client: Client, deps: MockDependencies) -> Unit,
     ) {
-        val sdkServer = createSdkServer(deps)
+        val sdkServer = createSdkServer(deps, deviceSlug)
```

```diff
     suspend fun withRawTestApplication(
         deps: MockDependencies = createMockDependencies(),
+        deviceSlug: String = "",
         testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(MockDependencies) -> Unit,
     ) {
-        val sdkServer = createSdkServer(deps)
+        val sdkServer = createSdkServer(deps, deviceSlug)
```

Add import for `McpToolUtils`:
```kotlin
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
```

**Explanation**: The helper accepts only `deviceSlug` and derives the prefix internally. This eliminates the risk of passing inconsistent `toolNamePrefix`/`deviceSlug` combinations. The default slug is empty, resulting in the `"android_"` prefix.

---

### Task 4.2: Update `McpProtocolIntegrationTest`

**Acceptance Criteria**: Expected tool names are prefixed with `"android_"`, count matches actual registered tools.

#### Action 4.2.1: Update expected tool names and count

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpProtocolIntegrationTest.kt`

**Context**: Update the `EXPECTED_TOOL_NAMES` set and `EXPECTED_TOOL_COUNT` to use prefixed names. All 38 tools are present (per pre-condition).

Update ALL tool name strings by prepending `"android_"`. Also update the test method name to reflect 38 tools:

```diff
-    fun `listTools returns all 27 registered tools`() =
+    fun `listTools returns all 38 registered tools`() =
```

```diff
     companion object {
-        private const val EXPECTED_TOOL_COUNT = 27
+        private const val EXPECTED_TOOL_COUNT = 38

         private val EXPECTED_TOOL_NAMES =
             setOf(
                 // Touch actions
-                "tap",
-                "long_press",
-                "double_tap",
-                "swipe",
-                "scroll",
+                "android_tap",
+                "android_long_press",
+                "android_double_tap",
+                "android_swipe",
+                "android_scroll",
                 // Gestures
-                "pinch",
-                "custom_gesture",
+                "android_pinch",
+                "android_custom_gesture",
                 // Element actions
-                "find_elements",
-                "click_element",
-                "long_click_element",
-                "set_text",
-                "scroll_to_element",
+                "android_find_elements",
+                "android_click_element",
+                "android_long_click_element",
+                "android_set_text",
+                "android_scroll_to_element",
                 // Screen introspection
-                "get_screen_state",
+                "android_get_screen_state",
                 // System actions
-                "press_back",
-                "press_home",
-                "press_recents",
-                "open_notifications",
-                "open_quick_settings",
-                "get_device_logs",
+                "android_press_back",
+                "android_press_home",
+                "android_press_recents",
+                "android_open_notifications",
+                "android_open_quick_settings",
+                "android_get_device_logs",
                 // Text input
-                "input_text",
-                "clear_text",
-                "press_key",
+                "android_input_text",
+                "android_clear_text",
+                "android_press_key",
                 // Utility
-                "get_clipboard",
-                "set_clipboard",
-                "wait_for_element",
-                "wait_for_idle",
-                "get_element_details",
+                "android_get_clipboard",
+                "android_set_clipboard",
+                "android_wait_for_element",
+                "android_wait_for_idle",
+                "android_get_element_details",
+                // File tools
+                "android_list_storage_locations",
+                "android_list_files",
+                "android_read_file",
+                "android_write_file",
+                "android_append_file",
+                "android_file_replace",
+                "android_download_from_url",
+                "android_delete_file",
+                // App management
+                "android_open_app",
+                "android_list_apps",
+                "android_close_app",
             )
     }
```

**Note**: Per the pre-condition, the file-operations branch must be merged before this plan is implemented, so all 38 tools (including 8 file tools + 3 app management tools) will be present.

Also update the server name check:
```diff
-                assertEquals("android-remote-control-mcp", client.serverVersion?.name)
+                assertEquals("android-remote-control-mcp", client.serverVersion?.name) // No slug in default tests
```

(This stays the same since default tests use empty slug.)

---

### Task 4.3: Update all integration test tool name references

**Acceptance Criteria**: Every `client.callTool(name = "...")` call uses the prefixed tool name.

The pattern for all integration tests is the same: prepend `"android_"` to every tool name string.

For maintainability, consider defining a helper constant or using `McpIntegrationTestHelper.DEFAULT_TOOL_NAME_PREFIX` concatenation. But since the tool names are used in assertions and must be exact, direct string literals are clearest.

#### Action 4.3.1: Update `TouchActionIntegrationTest.kt`

Replace all tool name strings:
- `"tap"` → `"android_tap"`
- `"swipe"` → `"android_swipe"`

#### Action 4.3.2: Update `ElementActionIntegrationTest.kt`

- `"find_elements"` → `"android_find_elements"`
- `"click_element"` → `"android_click_element"`

#### Action 4.3.3: Update `GestureIntegrationTest.kt`

- `"pinch"` → `"android_pinch"`

#### Action 4.3.4: Update `ScreenIntrospectionIntegrationTest.kt`

- `"get_screen_state"` → `"android_get_screen_state"`

#### Action 4.3.5: Update `SystemActionIntegrationTest.kt`

- `"press_home"` → `"android_press_home"`
- `"press_back"` → `"android_press_back"`

#### Action 4.3.6: Update `TextInputIntegrationTest.kt`

- `"input_text"` → `"android_input_text"`

#### Action 4.3.7: Update `UtilityIntegrationTest.kt`

- `"get_clipboard"` → `"android_get_clipboard"`
- `"set_clipboard"` → `"android_set_clipboard"`

#### Action 4.3.8: Update `FileToolsIntegrationTest.kt`

- `"list_storage_locations"` → `"android_list_storage_locations"`
- `"list_files"` → `"android_list_files"`
- `"read_file"` → `"android_read_file"`
- `"write_file"` → `"android_write_file"`
- `"append_file"` → `"android_append_file"`
- `"file_replace"` → `"android_file_replace"`
- `"download_from_url"` → `"android_download_from_url"`
- `"delete_file"` → `"android_delete_file"`

#### Action 4.3.9: Update `AppManagementToolsIntegrationTest.kt`

- `"open_app"` → `"android_open_app"`
- `"list_apps"` → `"android_list_apps"`
- `"close_app"` → `"android_close_app"`

#### Action 4.3.10: Update `ErrorHandlingIntegrationTest.kt`

- `"press_back"` → `"android_press_back"`
- `"click_element"` → `"android_click_element"`
- `"tap"` → `"android_tap"`
- `"wait_for_element"` → `"android_wait_for_element"`
- `"wait_for_idle"` → `"android_wait_for_idle"`

---

### Task 4.4: Add dedicated slug integration test(s)

**Acceptance Criteria**: At least one test verifies that when a slug is set, tool names include the slug. Another test verifies the server name includes the slug.

#### Action 4.4.1: Add slug integration tests to `McpProtocolIntegrationTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpProtocolIntegrationTest.kt`

**Context**: Add new test methods in the test class.

```kotlin
    @Test
    fun `listTools with device slug includes slug in tool names`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result = client.listTools()
                result.tools.forEach { tool ->
                    assertTrue(
                        tool.name.startsWith("android_pixel7_"),
                        "Tool '${tool.name}' should start with 'android_pixel7_'",
                    )
                }
            }
        }

    @Test
    fun `server name includes device slug when set`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication(
                deviceSlug = "pixel7",
            ) { client, _ ->
                assertEquals("android-remote-control-mcp-pixel7", client.serverVersion?.name)
            }
        }

    @Test
    fun `server name without slug uses default`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, _ ->
                assertEquals("android-remote-control-mcp", client.serverVersion?.name)
            }
        }

    @Test
    fun `tool with slug can be called successfully`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(any(), any()) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(
                deps = deps,
                deviceSlug = "pixel7",
            ) { client, _ ->
                val result = client.callTool(
                    name = "android_pixel7_tap",
                    arguments = mapOf("x" to 100, "y" to 200),
                )
                assertNotEquals(true, result.isError)
            }
        }
```

Add necessary imports at the top of the file:
```kotlin
import io.mockk.coEvery
import org.junit.jupiter.api.Assertions.assertNotEquals
```

---

## User Story 5: Documentation Updates

**As a** developer,
**I want** documentation to reflect the new tool name prefix,
**So that** users and contributors understand the naming convention.

### Acceptance Criteria / Definition of Done
- [x] `MCP_TOOLS.md` documents the prefix convention and includes prefixed tool names
- [x] `PROJECT.md` documents the `deviceSlug` setting with defaults and validation rules

---

### Task 5.1: Update `MCP_TOOLS.md`

**Acceptance Criteria**: Document explains the `android_` prefix convention, slug configuration, and all tool names are shown with prefix.

#### Action 5.1.1: Add prefix documentation section

**File**: `docs/MCP_TOOLS.md`

**Context**: Add a new section near the top of the document (after the introduction/overview, before the first tool category).

Add a section titled "## Tool Naming Convention" that explains:
- All tools are prefixed with `android_` by default
- When a device slug is configured (e.g., `pixel7`), the prefix becomes `android_pixel7_`
- The slug is configured in the app settings
- Examples: `android_tap`, `android_pixel7_tap`, `android_find_elements`, `android_work_phone_get_screen_state`

#### Action 5.1.2: Update all tool name references throughout the document

**Context**: Search-and-replace all tool name references in MCP_TOOLS.md to use the `android_` prefix. This includes headers, code blocks, JSON examples, etc. All 38 tool names throughout the document need the `android_` prefix.

**Note**: Tool names in JSON-RPC examples should also be updated (e.g., `"method": "tools/call", "params": {"name": "android_tap", ...}`).

---

### Task 5.2: Update `PROJECT.md`

**Acceptance Criteria**: PROJECT.md documents the `deviceSlug` setting with defaults, validation, and purpose.

#### Action 5.2.1: Add `deviceSlug` to the "Default Configuration" section

**File**: `docs/PROJECT.md`

**Context**: In the **"Default Configuration"** section (around lines 557-598), add a new entry for `deviceSlug` alongside the other `ServerConfig` properties:

```markdown
- **deviceSlug** (`String`, default: `""`): Optional device identifier used as part of the MCP tool name prefix. When empty, tools use the `android_` prefix. When set (e.g., `pixel7`), tools use `android_pixel7_` prefix. Valid characters: letters (a-z, A-Z), digits (0-9), underscores (_). Maximum length: 20 characters.
```

#### Action 5.2.2: Update tool name references in PROJECT.md

**File**: `docs/PROJECT.md`

**Context**: In the **"MCP Tools Specification"** section (around lines 189+), tool names are listed in tables and descriptions. Add a note at the top of that section explaining that all tool names are prefixed with `android_` (or `android_<slug>_` when a device slug is configured), and update the tool names in the tables to use the `android_` prefix — same search-and-replace pattern as MCP_TOOLS.md.

---

## User Story 6: Final Verification

**As a** developer,
**I want** to verify every change from the ground up,
**So that** nothing was missed or incorrectly implemented.

### Acceptance Criteria / Definition of Done
- [x] All linting passes: `make lint`
- [x] All unit tests pass: `make test-unit`
- [x] All integration tests pass (included in `make test-unit` which runs `./gradlew :app:test`) — Note: NgrokTunnelIntegrationTest has a pre-existing native library failure, unrelated to this plan
- [x] Full build succeeds without warnings: `./gradlew build`
- [x] Manual review: every file changed matches the plan
- [x] Every tool name in production code is prefixed
- [x] Every tool name in test code is prefixed
- [x] Every tool name in documentation is prefixed
- [x] `ServerConfig.deviceSlug` defaults to empty string
- [x] `validateDeviceSlug()` rejects invalid characters, too-long slugs
- [x] UI field is disabled when server is running
- [x] MCP server name includes slug when set
- [x] No TODOs, no dead code, no temporary hacks
- [x] Git history is clean and commits are logical

---

### Task 6.1: Run linting

```bash
make lint
```

- [x] No lint errors
- [x] No lint warnings

### Task 6.2: Run all tests

```bash
make test-unit
```

Integration tests are included in this target (they run as part of `./gradlew :app:test`).

- [x] All tests pass (except pre-existing NgrokTunnelIntegrationTest native library failure)
- [x] No test failures from plan changes
- [x] No test errors from plan changes

### Task 6.3: Full build

```bash
./gradlew build
```

- [x] Build succeeds
- [x] No build warnings

### Task 6.4: Ground-up code review

Review every changed file in order:

1. [x] `ServerConfig.kt` — `deviceSlug` property, constants, KDoc
2. [x] `SettingsRepository.kt` — `updateDeviceSlug()`, `validateDeviceSlug()`
3. [x] `SettingsRepositoryImpl.kt` — DataStore key, update, validate, mapping
4. [x] `McpToolUtils.kt` — `buildToolNamePrefix()`, `buildServerName()`
5. [x] `TouchActionTools.kt` — `toolNamePrefix` in all tool `register()` methods and `registerTouchActionTools()`
6. [x] `ElementActionTools.kt` — same pattern
7. [x] `GestureTools.kt` — same pattern
8. [x] `ScreenIntrospectionTools.kt` — same pattern
9. [x] `SystemActionTools.kt` — same pattern
10. [x] `TextInputTools.kt` — same pattern
11. [x] `UtilityTools.kt` — same pattern
12. [x] `FileTools.kt` — same pattern
13. [x] `AppManagementTools.kt` — same pattern
14. [x] `McpServerService.kt` — prefix computation, server name, `registerAllTools()` call
15. [x] `MainViewModel.kt` — slug state flows, `updateDeviceSlug()`, init block
16. [x] `HomeScreen.kt` — slug state collection, ConfigurationSection wiring
17. [x] `ConfigurationSection.kt` — slug text field, disabled when running, preview
18. [x] `strings.xml` — new string resources
19. [x] `ServerConfigTest.kt` — `deviceSlug` default value, companion constant tests
20. [x] `SettingsRepositoryImplTest.kt` — persistence + validation tests
21. [x] `MainViewModelTest.kt` — slug update tests
22. [x] `McpToolUtilsTest.kt` — `buildToolNamePrefix()`, `buildServerName()` tests (in `@Nested @DisplayName` classes)
23. [x] `McpIntegrationTestHelper.kt` — `deviceSlug` parameter, prefix derived internally, `registerAllTools()`, `createSdkServer()`, `withTestApplication()`
24. [x] `McpProtocolIntegrationTest.kt` — prefixed expected names, slug tests
25. [x] All 10 other integration test files — prefixed tool names
26. [x] `MCP_TOOLS.md` — prefix convention section, all tool names prefixed
27. [x] `PROJECT.md` — `deviceSlug` setting documented

### Task 6.5: Verify no unprefixed tool names remain

```bash
# Search for unprefixed tool name usage in production code (should find only TOOL_NAME constants)
rg 'name = TOOL_NAME' app/src/main/kotlin/ # Should find 0 results (now uses "$toolNamePrefix$TOOL_NAME")
rg 'name = "tap"' app/src/main/kotlin/ # Should find 0 results
rg 'name = "tap"' app/src/test/kotlin/ # Should find 0 results
```

- [x] No unprefixed tool names in production code
- [x] No unprefixed tool names in test code
