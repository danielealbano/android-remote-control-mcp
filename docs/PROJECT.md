# Android Remote Control MCP - Project Bible

This document is the source of truth for the Android Remote Control MCP project. It defines the architecture, technical decisions, conventions, and implementation guidelines.

**Project Goal**: Build an Android application that runs as an MCP (Model Context Protocol) server, enabling AI models to fully control an Android device remotely using accessibility services and screenshot capture.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Tech Stack](#tech-stack)
3. [Folder Structure](#folder-structure)
4. [MCP Protocol Implementation](#mcp-protocol-implementation)
5. [MCP Tools Specification](#mcp-tools-specification)
6. [Android-Specific Conventions](#android-specific-conventions)
7. [Kotlin Coding Standards](#kotlin-coding-standards)
8. [UI Design Principles](#ui-design-principles)
9. [Testing Strategy](#testing-strategy)
10. [Build & Deployment](#build--deployment)
11. [Security Practices](#security-practices)
12. [Default Configuration](#default-configuration)
13. [Makefile Targets](#makefile-targets)
14. [Related Documentation](#related-documentation)

---

## Architecture

### Overview

The application is a **service-based Android app** that exposes an MCP server over HTTP (with optional HTTPS). It consists of three main components:

1. **AccessibilityService** — Provides UI introspection and action execution (including screenshot capture via `takeScreenshot()` API on Android 11+)
2. **McpServerService** — Runs the HTTP server implementing MCP protocol
3. **MainActivity** — UI for configuration and control

### Component Details

#### 1. AccessibilityService

- **Type**: Android `AccessibilityService`
- **Purpose**: Introspect UI hierarchy of all apps and perform actions
- **Lifecycle**: Runs as long as enabled in Android Settings (Accessibility)
- **Capabilities**: Traverse accessibility tree (full depth), find elements by text/content description/resource ID/class name, perform actions (click, long-click, scroll, swipe, type text via InputConnection), execute global actions (back, home, recents, notifications, quick settings)
- **Implementation**: Extends `android.accessibilityservice.AccessibilityService`, registers for all event types and all packages, stores singleton instance for inter-service communication, uses coroutines for non-blocking operations

#### 2. McpServerService

- **Type**: Android Foreground Service
- **Purpose**: Run HTTP server implementing MCP protocol
- **Lifecycle**: User-controlled via MainActivity (start/stop)
- **Capabilities**: HTTP/HTTPS server using Ktor, MCP JSON-RPC 2.0 protocol, bearer token authentication, configurable binding address (127.0.0.1 or 0.0.0.0), orchestrates calls to AccessibilityService
- **Implementation**: Foreground service with persistent notification, Kotlin coroutines for async request handling, reads configuration from DataStore, exposes Streamable HTTP endpoint at `/mcp` via MCP Kotlin SDK, graceful shutdown on service stop

#### 3. MainActivity

- **Type**: Android Activity (Jetpack Compose UI)
- **Purpose**: Configuration and control interface
- **Features**: Server status display (running/stopped), start/stop MCP server toggle, configuration settings (binding address, port, bearer token, auto-start on boot, HTTPS toggle and certificate management, remote access tunnel toggle and provider selection), quick links (enable accessibility service), connection info display (with tunnel public URL when connected), server logs viewer (recent server events including MCP tool calls and tunnel events)
- **Implementation**: Material Design 3 with dark mode support, Jetpack Compose, ViewModel for state management, observes service status via Flow/StateFlow

### Inter-Service Communication

**Pattern**: Singleton + StateFlow
- **AccessibilityService**: Stores singleton instance in companion object for direct access (system-managed, long-lived). Also provides screenshot capture via `takeScreenshot()` API (Android 11+)
- **McpServerService**: Exposes status via companion-level StateFlow for UI observation

### Service Lifecycle

The typical startup flow: User opens app → enables Accessibility Service in Android Settings → starts MCP server via button → McpServerService starts as foreground service → starts Ktor HTTP/HTTPS server → MCP server ready on configured address:port → user minimizes app (services continue in background) → MCP clients can connect and control device.

**Auto-start on Boot** (if enabled): Device boots → `BootCompletedReceiver` triggers → if auto-start enabled in settings → McpServerService starts automatically.

### Threading Model

- **Main Thread**: UI operations only (Compose, Activity lifecycle)
- **IO Dispatcher**: Network operations (Ktor server, HTTP requests)
- **Default Dispatcher**: CPU-intensive operations (screenshot encoding, accessibility tree parsing)
- **Service Operations**: All AccessibilityService operations must be posted to main thread (Android requirement)

---

## Tech Stack

### Core Technologies

- **Language**: Kotlin 2.3.10 (latest stable, Feb 2026)
- **Android Gradle Plugin (AGP)**: 8.13 (latest stable 8.x)
- **Gradle**: 8.14.4 (latest stable 8.x)
- **KSP**: 2.3.5 (Kotlin Symbol Processing, decoupled from Kotlin since 2.3.0)
- **Android SDK**: Target API 34 (Android 14), Minimum API 33 (Android 13 Tiramisu)
- **JDK**: Java 17 (standard for Android development)

### Frameworks & Libraries

- **Jetpack Compose**: UI framework (Material Design 3)
- **Lifecycle**: ViewModel, LiveData, Flow
- **DataStore**: Settings persistence (modern replacement for SharedPreferences)
- **Hilt**: Dependency injection (Dagger-based, official Android DI)
- **Ktor Server**: HTTP/HTTPS server (Kotlin-native, async, coroutine-based)
- **MCP Kotlin SDK**: Official Model Context Protocol implementation v0.8.3 (from Anthropic/ModelContextProtocol), including `Server`, `StreamableHttpServerTransport`, and type-safe tool registration via `Server.addTool()`
- **SLF4J-Android**: Routes MCP SDK internal SLF4J logs to `android.util.Log`
- **Kotlinx Serialization**: JSON serialization for MCP protocol
- **Kotlinx Coroutines**: Async/concurrency
- **Android Log**: Logging (standard Android Log class)
- **Accompanist**: Compose utilities (permissions handling)

### Testing

- **JUnit 5**: Unit test framework
- **MockK**: Mocking framework for Kotlin
- **Turbine**: Flow testing library
- **Compose UI Test**: Jetpack Compose testing
- **Testcontainers Kotlin**: Container-based E2E tests
- **MCP Kotlin SDK Client**: SDK `Client` + `StreamableHttpClientTransport` for E2E tests

### Build Tools

- **Gradle 8.x**: Build system (Kotlin DSL)
- **Makefile**: Development workflow automation
- **ktlint** and **detekt**: Kotlin linting

---

## Folder Structure

- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
  - `McpApplication.kt` — Application class (Hilt setup)
  - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `TypeInputController.kt`, `TypeInputControllerImpl.kt`, `ScreenInfo.kt`, `CompactTreeFormatter.kt`, `AccessibilityNodeCache.kt`, `AccessibilityNodeCacheImpl.kt`
  - `services/screencapture/` — `ScreenCaptureProvider.kt`, `ScreenCaptureProviderImpl.kt`, `ScreenshotEncoder.kt`, `ScreenshotAnnotator.kt`, `ApiLevelProvider.kt`
  - `services/storage/` — `StorageLocationProvider.kt`, `StorageLocationProviderImpl.kt`, `FileOperationProvider.kt`, `FileOperationProviderImpl.kt`
  - `services/apps/` — `AppManager.kt`, `AppManagerImpl.kt`
  - `services/camera/` — `CameraProvider.kt`, `CameraProviderImpl.kt`, `ServiceLifecycleOwner.kt`
  - `services/mcp/` — `McpServerService.kt`, `BootCompletedReceiver.kt`, `AdbConfigHandler.kt`, `AdbConfigReceiver.kt`, `AdbServiceTrampolineActivity.kt`
  - `services/tunnel/` — `TunnelProvider.kt`, `TunnelManager.kt`, `CloudflareTunnelProvider.kt`, `CloudflaredBinaryResolver.kt`, `AndroidCloudflareBinaryResolver.kt`, `NgrokTunnelProvider.kt`
  - `mcp/` — `McpServer.kt`, `McpStreamableHttpExtension.kt`, `McpToolException.kt`, `CertificateManager.kt`
  - `mcp/tools/` — `McpToolUtils.kt`, `TreeFingerprint.kt`, `ScreenIntrospectionTools.kt`, `TouchActionTools.kt`, `ElementActionTools.kt`, `TextInputTools.kt`, `SystemActionTools.kt`, `GestureTools.kt`, `UtilityTools.kt`, `FileTools.kt`, `AppManagementTools.kt`, `CameraTools.kt`
  - `mcp/auth/` — `BearerTokenAuth.kt`
  - `ui/` — `MainActivity.kt`
  - `ui/theme/` — `Theme.kt`, `Color.kt`, `Type.kt`
  - `ui/screens/` — `HomeScreen.kt`
  - `ui/components/` — `ServerStatusCard.kt`, `ConfigurationSection.kt`, `RemoteAccessSection.kt`, `ConnectionInfoCard.kt`, `PermissionsSection.kt`, `ServerLogsSection.kt`, `StorageLocationsSection.kt`
  - `ui/viewmodels/` — `MainViewModel.kt`
  - `data/repository/` — `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`
  - `data/model/` — `ServerConfig.kt`, `ServerStatus.kt`, `ServerLogEntry.kt`, `BindingAddress.kt`, `CertificateSource.kt`, `ScreenshotData.kt`, `TunnelProviderType.kt`, `TunnelStatus.kt`, `StorageLocation.kt`, `FileInfo.kt`, `AppInfo.kt`, `AppFilter.kt`, `CameraInfo.kt`, `CameraResolution.kt`
  - `di/` — `AppModule.kt`
  - `utils/` — `NetworkUtils.kt`, `PermissionUtils.kt`, `Logger.kt`
- `app/src/main/res/` — `values/strings.xml`, `values/themes.xml`, `drawable/`, `mipmap/`, `xml/accessibility_service_config.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/debug/` — `AndroidManifest.xml` (debug overlay), `kotlin/.../debug/E2EConfigReceiver.kt`
- `app/src/test/kotlin/` — Unit tests and JVM-based integration tests
- `app/src/test/kotlin/.../integration/` — Integration tests (Ktor `testApplication`, no emulator)
- `e2e-tests/` — E2E tests (Docker Android, JVM-only module)
- `gradle/libs.versions.toml` — Gradle version catalog
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- `Makefile`, `CLAUDE.md`, `README.md`, `LICENSE`
- `.github/workflows/ci.yml` — GitHub Actions CI workflow

---

## MCP Protocol Implementation

### Protocol Overview

The application implements the **Model Context Protocol (MCP)** specification from Anthropic/ModelContextProtocol. MCP is a JSON-RPC 2.0 based protocol that enables AI models to interact with external tools and resources. The official **MCP Kotlin/JVM SDK** is used for message parsing/serialization, tool registration, error handling, and type-safe tool definitions.

### Transport Layer

- **Transport**: Streamable HTTP (JSON-only, no SSE) via the MCP Kotlin SDK's `StreamableHttpServerTransport(enableJsonResponse = true)`
- **Endpoint**: `POST /mcp` — Single MCP endpoint handling all protocol messages (initialize, tools/list, tools/call, etc.)
- **Framework**: Ktor Server (async, coroutine-based)
- **Authentication**: Global Application-level bearer token (`Authorization: Bearer <token>`) — all routes require authentication (no unauthenticated endpoints)
- **Content-Type**: `application/json`
- **Compatibility**: Standard MCP clients (`mcp-remote`, Claude Desktop, etc.) can connect via the standard `/mcp` endpoint

### Error Handling

Tool errors are returned as `CallToolResult(isError = true)` with an error message in `TextContent`, following the standard MCP SDK pattern. The SDK handles protocol-level errors (parse errors, invalid requests) automatically. Custom JSON-RPC error codes (`-32001` to `-32004`) are no longer exposed to clients — the server catches `McpToolException` subtypes and wraps them as tool-level error results.

### Authentication

- Every request must include `Authorization: Bearer <token>` header
- Token is validated by `BearerTokenAuth` Application-level plugin (constant-time comparison to prevent timing attacks)
- Invalid/missing token returns `401 Unauthorized`
- Token is stored in DataStore, configurable via UI

---

## MCP Tools Specification

The MCP server exposes 53 tools across 12 categories. For full JSON-RPC schemas, detailed usage examples, and implementation notes, see [MCP_TOOLS.md](MCP_TOOLS.md).

> **Tool Naming Convention**: All tool names are prefixed with `android_` by default (e.g., `android_tap`, `android_find_elements`). When a device slug is configured (e.g., `pixel7`), the prefix becomes `android_pixel7_` (e.g., `android_pixel7_tap`). See [MCP_TOOLS.md](MCP_TOOLS.md) for details.

### 1. Screen Introspection Tools (1 tool)

| Tool | Description | Parameters | Output |
|------|-------------|------------|--------|
| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI elements | `include_screenshot` (boolean, optional, default false) | `TextContent` with compact TSV (text/desc truncated to 100 chars, comma-separated abbreviated flags with on/off visibility). Optionally includes `ImageContent` with annotated low-resolution JPEG screenshot (700px max, red bounding boxes with element ID labels). |

**Error**: Returns `CallToolResult(isError = true)` if accessibility permission not granted or screen capture not available.

**Note**: `android_get_screen_state` returns a filtered flat TSV list — structural-only nodes (no text, no contentDescription, no resourceId, not interactive) are omitted. Text and contentDescription are truncated to 100 characters; use `android_get_element_details` to retrieve full values. Flags use comma-separated abbreviations with a legend in the TSV notes. When screenshot is included, it is annotated with red bounding boxes and element ID labels for on-screen elements.

### 2. Touch Action Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_tap` | Single tap at coordinates | `x` (number), `y` (number) | — |
| `android_long_press` | Long press at coordinates | `x` (number), `y` (number) | `duration` (number, ms, default 1000) |
| `android_double_tap` | Double tap at coordinates | `x` (number), `y` (number) | — |
| `android_swipe` | Swipe from point A to B | `x1`, `y1`, `x2`, `y2` (all number) | `duration` (number, ms, default 300) |
| `android_scroll` | Scroll in direction | `direction` (string: up/down/left/right) | `amount` (string: small/medium/large, default medium), `variance` (number: 0-20, default 5) |

**Errors**: Returns `CallToolResult(isError = true)` if accessibility not enabled or action execution failed.

### 3. Element Action Tools (4 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_find_elements` | Find UI elements by criteria | `by` (string: text/content_desc/resource_id/class_name), `value` (string) | `exact_match` (boolean, default false) |
| `android_click_element` | Click an accessibility node | `element_id` (string) | — |
| `android_long_click_element` | Long-click an accessibility node | `element_id` (string) | — |
| `android_scroll_to_element` | Scroll to make element visible | `element_id` (string) | — |

**Errors**: Returns `CallToolResult(isError = true)` if element not found (ID invalid or stale) or element not clickable. `android_find_elements` returns empty array (not error) when no matches found.

### 4. Text Input Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_type_append_text` | Type text at end of field via InputConnection | `element_id` (string), `text` (string) | `typing_speed` (int, ms, default 70), `typing_speed_variance` (int, ms, default 15) |
| `android_type_insert_text` | Type text at specific position via InputConnection | `element_id` (string), `text` (string), `offset` (int) | `typing_speed` (int, ms, default 70), `typing_speed_variance` (int, ms, default 15) |
| `android_type_replace_text` | Find and replace text in field via InputConnection | `element_id` (string), `search` (string), `new_text` (string) | `typing_speed` (int, ms, default 70), `typing_speed_variance` (int, ms, default 15) |
| `android_type_clear_text` | Clear all text from field via select-all + delete | `element_id` (string) | — |
| `android_press_key` | Press a specific key | `key` (string: ENTER/BACK/DEL/HOME/TAB/SPACE) | — |

### 5. System Action Tools (6 tools)

| Tool | Description | Parameters |
|------|-------------|------------|
| `android_press_back` | Press back button | None |
| `android_press_home` | Navigate to home screen | None |
| `android_press_recents` | Open recent apps | None |
| `android_open_notifications` | Pull down notification shade | None |
| `android_open_quick_settings` | Open quick settings panel | None |
| `android_get_device_logs` | Retrieve filtered logcat logs | `last_lines` (int, 1-1000, default 100), `since`/`until` (ISO 8601 timestamp), `tag` (string), `level` (string: V/D/I/W/E/F, default D), `package_name` (string) — all optional |

### 6. Gesture Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_pinch` | Pinch-to-zoom gesture | `center_x` (number), `center_y` (number), `scale` (number: >1 zoom in, <1 zoom out) | `duration` (number, ms, default 300) |
| `android_custom_gesture` | Multi-touch gesture from path points | `paths` (array of arrays of {x, y, time} objects) | — |

### 7. Utility Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_get_clipboard` | Get current clipboard content | — | — |
| `android_set_clipboard` | Set clipboard content | `text` (string) | — |
| `android_wait_for_element` | Wait until element appears | `by` (string), `value` (string), `timeout` (number, ms, 1-30000) | — |
| `android_wait_for_idle` | Wait for UI to become idle | `timeout` (number, ms, 1-30000) | — |
| `android_get_element_details` | Get full untruncated text/desc by element_ids | `element_ids` (array of strings) | — |

**Timeout behavior**: Both `android_wait_for_element` and `android_wait_for_idle` require a mandatory `timeout` parameter (max 30000ms). On timeout, they return a non-error `CallToolResult` with an informational message (not an error).

### 8. File Tools (8 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_list_storage_locations` | List user-added storage locations with metadata | — | — |
| `android_list_files` | List files/directories in a storage location | `location_id` (string) | `path` (string, default ""), `offset` (int, default 0), `limit` (int, default 200, max 200) |
| `android_read_file` | Read text file with line-based pagination | `location_id` (string), `path` (string) | `offset` (int, 1-based line number, default 1), `limit` (int, default 200, max 200) |
| `android_write_file` | Write/create text file (creates parents, overwrites) | `location_id` (string), `path` (string), `content` (string) | — |
| `android_append_file` | Append to text file (tries native "wa" mode) | `location_id` (string), `path` (string), `content` (string) | — |
| `android_file_replace` | Literal string replacement in text file | `location_id` (string), `path` (string), `old_string` (string), `new_string` (string) | `replace_all` (boolean, default false) |
| `android_download_from_url` | Download file from URL to storage location | `location_id` (string), `path` (string), `url` (string) | — |
| `android_delete_file` | Delete a single file (not directories) | `location_id` (string), `path` (string) | — |

**Virtual path system**: All file tools use virtual paths: `{location_id}/{relative_path}` where `location_id` is `{authority}/{treeDocumentId}`. The location must be added by the user via the UI before file operations can be performed.

**File size limit**: All file operations are subject to the configurable file size limit (default 50 MB, range 1-500 MB). Stored in `ServerConfig.fileSizeLimitMb`.

**Encoding**: All text operations use UTF-8.

**Errors**: Returns `CallToolResult(isError = true)` if location not found, file not found, file exceeds size limit, or operation fails. `android_append_file` returns a hint to use `android_write_file` if the storage provider does not support append mode. `android_download_from_url` returns an error if HTTP downloads are disabled and the URL is HTTP, or if the download times out.

### 9. App Management Tools (3 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_open_app` | Launch an application by package ID | `package_id` (string) | — |
| `android_list_apps` | List installed applications with filtering | — | `filter` (string: all/user/system, default "all"), `name_query` (string) |
| `android_close_app` | Kill a background application process | `package_id` (string) | — |

**Errors**: `android_open_app` returns `CallToolResult(isError = true)` if the app is not installed or has no launchable activity. `android_close_app` only affects background processes — for foreground apps, use `android_press_home` first to send the app to the background. `android_list_apps` requires the `QUERY_ALL_PACKAGES` permission. `android_close_app` requires the `KILL_BACKGROUND_PROCESSES` permission.

### 10. Camera Tools (6 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_list_cameras` | List all available cameras with capabilities | — | — |
| `android_list_camera_photo_resolutions` | List supported photo resolutions for a camera | `camera_id` (string) | — |
| `android_list_camera_video_resolutions` | List supported video resolutions for a camera | `camera_id` (string) | — |
| `android_take_camera_photo` | Capture a photo and return as base64 JPEG (max 1920x1080) | `camera_id` (string) | `resolution` (string, WIDTHxHEIGHT), `quality` (int, 1-100, default 80), `flash_mode` (string: off/on/auto, default auto) |
| `android_save_camera_photo` | Capture a photo and save to storage location | `camera_id` (string), `location_id` (string), `path` (string) | `resolution` (string, WIDTHxHEIGHT), `quality` (int, 1-100, default 80), `flash_mode` (string: off/on/auto, default auto) |
| `android_save_camera_video` | Record video and save to storage (max 30s), returns thumbnail | `camera_id` (string), `location_id` (string), `path` (string), `duration` (int, 1-30) | `resolution` (string, WIDTHxHEIGHT), `audio` (boolean, default true), `flash_mode` (string: off/on/auto, default auto) |

**Errors**: All camera tools return `McpToolException.PermissionDenied` if CAMERA permission is not granted. `android_save_camera_video` also requires RECORD_AUDIO permission when audio is enabled. Save tools delegate write permission checks to `FileOperationProvider.createFileUri()`. Invalid parameters (resolution format, quality range, duration range, flash mode) return `McpToolException.InvalidParams`.

### 11. Intent Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_send_intent` | Send an Android intent (activity, broadcast, or service) | `type` (string: activity/broadcast/service) | `action` (string), `data` (string), `component` (string, package/class), `extras` (object), `extras_types` (object), `flags` (string[]) |
| `android_open_uri` | Open a URI using ACTION_VIEW | `uri` (string) | `package_name` (string), `mime_type` (string) |

**Extras type inference**: String values → `String`, integers fitting Int range → `Int`, integers exceeding Int range → `Long`, decimals → `Double`, booleans → `Boolean`, string arrays → `StringArrayList`. Use `extras_types` to override (supported: `"string"`, `"int"`, `"long"`, `"float"`, `"double"`, `"boolean"`).

**Flags**: Resolved dynamically via `Intent::class.java.fields` reflection. `FLAG_ACTIVITY_NEW_TASK` auto-added for `type = "activity"`.

**Errors**: Invalid type/component/flag → `McpToolException.InvalidParams`. No handler found / permission denied / background start restriction → `McpToolException.ActionFailed` with sanitized message.

### 12. Notification Tools (6 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_notification_list` | List active notifications with structured data | — | `package_name`, `limit` |
| `android_notification_open` | Open/tap a notification | `notification_id` | — |
| `android_notification_dismiss` | Dismiss a notification | `notification_id` | — |
| `android_notification_snooze` | Snooze a notification for a duration | `notification_id`, `duration_ms` | — |
| `android_notification_action` | Execute a notification action button | `action_id` | — |
| `android_notification_reply` | Reply to a notification with text | `action_id`, `text` | — |

**Permission**: Requires "Notification Listener" special permission (Settings > Apps > Special app access > Notification access). This is NOT a runtime permission — the user must manually enable it in Settings.

**ID scheme**: `notification_id` = 6 hex chars (SHA-256 hash of notification key), `action_id` = 6 hex chars (SHA-256 hash of notification key + "::" + action index). Both returned by `android_notification_list`.

**Errors**: All tools return `McpToolException.PermissionDenied` if notification listener is not enabled. ID-based tools return `McpToolException.ActionFailed` if the notification/action is not found. `android_notification_snooze` validates `duration_ms` (positive, max 604800000 = 7 days).

---

## Android-Specific Conventions

### Service Lifecycle Management

- All long-running services (McpServerService) MUST run as foreground services with persistent notifications
- Call `startForeground()` within 5 seconds of service start, `stopForeground()` before destruction
- Service-to-UI communication via Flow/StateFlow (LocalBroadcastManager is deprecated and NOT used)

### AccessibilityService Best Practices

- Register only for needed event types (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- Keep `onAccessibilityEvent()` fast, offload heavy work to coroutines
- Cache accessibility tree when possible; call `node.recycle()` after use
- Check `node.refresh()` before using cached nodes (stale detection)
- All node operations MUST happen on main thread
- Use `performAction()` for element actions, `performGlobalAction()` for system actions, `dispatchGesture()` for complex touch sequences (API 24+)

### Permission Handling

- **Accessibility**: User must enable manually in Settings (provide deep link). Also provides screenshot capture via `takeScreenshot()` API (Android 11+)
- **Internet**: Declared in manifest, granted automatically
- **QUERY_ALL_PACKAGES**: Required for listing all installed applications via `PackageManager`. Declared in manifest, granted automatically
- **KILL_BACKGROUND_PROCESSES**: Required for killing background app processes via `ActivityManager.killBackgroundProcesses()`. Declared in manifest, granted automatically
- **CAMERA**: Runtime permission required for camera photo/video tools. Requested via UI permission launcher
- **RECORD_AUDIO**: Runtime permission required for video recording with audio. Requested via UI permission launcher
- Always check permission state before operations; return `CallToolResult(isError = true)` if permission missing

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

### Background Restrictions & Memory Management

- Foreground services are exempt from Doze restrictions
- Never store Activity context in long-lived objects — use ApplicationContext
- Cancel coroutine scopes in `onDestroy()`; recycle large bitmaps after encoding; use `use {}` for automatic stream closure

### Threading Rules

- All AccessibilityService operations and UI operations MUST run on main thread
- Network operations (Ktor) on IO dispatcher, screenshot encoding and tree parsing on Default dispatcher

---

## Kotlin Coding Standards

### Naming Conventions

- **Classes/Interfaces**: PascalCase, no "I" prefix (e.g., `SettingsRepository`, not `ISettingsRepository`)
- **Functions/Variables**: camelCase (e.g., `captureScreenshot()`, `bearerToken`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `DEFAULT_PORT`)
- **Backing fields**: underscore prefix (e.g., `_serverStatus`)
- **Packages**: All lowercase, no underscores

### Null Safety

- Prefer non-null types by default; use nullable types only when null is a valid state
- Avoid `!!` operator — use safe calls `?.`, `let {}`, or elvis operator `?:` instead
- Use `require()` or `check()` for preconditions

### Coroutines Best Practices

- Always use `CoroutineScope` (never `GlobalScope`); cancel scope in lifecycle cleanup
- Use `viewModelScope` for ViewModels, `lifecycleScope` for Activities
- Dispatchers: `Main` for UI/AccessibilityService, `IO` for network/file I/O/DataStore, `Default` for CPU-intensive work

### Code Organization

- File structure: package declaration → imports → class declaration → companion object → properties → init blocks → public methods → private methods → inner classes
- Keep classes focused (single responsibility), prefer files under 300 lines
- Prefer functions under 20 lines with meaningful names
- Prefer `val` over `var`; use `data class` for immutable data with `copy()` for modifications
- Use extension functions for utility operations; don't overuse — prefer member functions for core logic

---

## UI Design Principles

### Design System

- **Material Design 3** components (Compose Material3 library) with theme tokens for consistent styling
- Define primary/secondary/tertiary colors in `Color.kt`, type scale in `Type.kt`
- Support both light and dark themes; ensure sufficient contrast (WCAG AA minimum)
- Use semantic color names (e.g., `surfaceVariant`, not `grey200`)

### Visual Style

- Clean (minimal clutter, ample whitespace), modern (rounded corners, subtle shadows, smooth animations)
- Elevated cards for grouped content, filled buttons for primary actions, outlined for secondary
- Material Icons, switches for toggles, outlined text fields for input
- Consistent spacing scale (4dp, 8dp, 16dp, 24dp, 32dp), 16dp padding inside components

### Dark Mode

- Mandatory dark theme support; use dynamic colors (Material You) if appropriate
- Avoid pure white/black — use surface colors; test contrast in both modes

### Screen Structure

HomeScreen contains a TopAppBar, then a scrollable layout with: ServerStatusCard (status, start/stop), ConfigurationSection (binding address, port, token, auto-start, HTTPS, file size limit, download settings), RemoteAccessSection (tunnel toggle, provider selection, ngrok config, tunnel status), StorageLocationsSection (user-managed SAF storage locations with add/edit/delete), PermissionsSection (accessibility/screenshot links), ServerLogsSection (scrollable recent server events including MCP tool calls and tunnel events), and ConnectionInfoCard (IP, port, token, tunnel URL, share button).

### Accessibility (UI)

- All interactive elements have minimum 48dp touch target
- Use `contentDescription` for icons/images; ensure logical focus order; support TalkBack; test with large text sizes

### Compose Best Practices

- PascalCase for composables, suffix with noun (e.g., `ServerStatusCard`, not `ShowServerStatus`)
- Hoist state to parent composables; use `remember` for UI state, `rememberSaveable` for surviving config changes
- Extract reusable components; use modifiers for customization; keep composables small and focused

---

## Testing Strategy

### Unit Tests

- **Framework**: JUnit 5, MockK, Turbine (for Flows)
- **Scope**: MCP protocol parsing/formatting, accessibility tree parsing, element finding, screenshot encoding, network utils, settings repository, ViewModel logic
- **Mocking**: Mock Android framework classes (AccessibilityNodeInfo, Context) with MockK; use `@MockK`/`@RelaxedMockK` annotations; verify interactions with `verify {}`
- **Pattern**: Arrange-Act-Assert consistently
- **Run**: `make test-unit` or `./gradlew test`

### Integration Tests

- **Framework**: Ktor `testApplication`, JUnit 5, MockK
- **Scope**: Full HTTP stack (authentication, JSON-RPC protocol, tool dispatch) via in-process Ktor test server; all 10 tool categories, error code propagation
- **Mocking**: Mock Android services (`ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`, `TypeInputController`, `StorageLocationProvider`, `FileOperationProvider`, `AppManager`, `CameraProvider`) via interfaces; real SDK `Server` with `McpStreamableHttp` routing and `BearerTokenAuth`
- **Infrastructure**: `McpIntegrationTestHelper` configures `testApplication` with same routing as production `McpServer`; uses SDK `Client` + `StreamableHttpClientTransport` for type-safe MCP communication
- **Run**: `make test-integration` or `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.*"`
- **Note**: JVM-based, no emulator or device required. Runs as part of `make test-unit` since both are under `app/src/test/`

### E2E Tests

- **Framework**: Testcontainers Kotlin (`budtmo/docker-android-x86:emulator_14.0`), JUnit 5, MCP Kotlin SDK Client
- **Scope**: Full MCP client → server → Android → action flow, Calculator app test (7 + 3 = 10), screenshot capture validation, error handling (auth, unknown tool, invalid params, element not found)
- **Infrastructure**: `SharedAndroidContainer` singleton shares one Docker container across all test classes (avoids ~2-4 min boot per class); `McpClient` test utility wraps SDK `Client` + `StreamableHttpClientTransport` with trust-all TLS for self-signed certs; `E2EConfigReceiver` debug-only BroadcastReceiver injects test settings via `adb shell am broadcast`
- **Run**: `make test-e2e` or `./gradlew :e2e-tests:test`
- **Note**: E2E tests are slow (container startup, emulator boot). Run selectively during development, always in CI.

### Coverage

- **Target**: Minimum 80% code coverage for unit tests (Jacoco, enforced via `jacocoTestCoverageVerification`)
- **Report**: `make coverage` or `./gradlew jacocoTestReport`

### Continuous Testing

- Unit tests and JVM integration tests on every commit (fast); E2E tests on PR and pre-merge (slow, comprehensive)

---

## Build & Deployment

### Build System

- **Gradle 8.x** with Kotlin DSL (`build.gradle.kts`) and version catalog (`libs.versions.toml`)

### Build Variants

| Variant | Application ID | Debuggable | Minify | Logging |
|---------|---------------|-----------|--------|---------|
| Debug | `com.danielealbano.androidremotecontrolmcp.debug` | true | false | Verbose |
| Release | `com.danielealbano.androidremotecontrolmcp` | false | false (open source) | Info+ |

### Versioning

- **Semantic versioning** (MAJOR.MINOR.PATCH): MAJOR for breaking MCP protocol changes, MINOR for new features, PATCH for bug fixes
- Version defined in `gradle.properties` (`VERSION_NAME`, `VERSION_CODE`)
- Bump via Makefile: `make version-bump-patch`, `make version-bump-minor`, `make version-bump-major`

### APK Signing

- **Debug**: Default debug keystore (automatic)
- **Release**: Custom keystore via `keystore.properties` (gitignored), loaded in `app/build.gradle.kts`

### CI/CD (GitHub Actions)

- **Trigger**: Push to main, pull requests
- **Pipeline**: lint → test-unit (includes JVM integration tests) → test-e2e → build-release (sequential)
- **E2E tests**: Docker pre-installed on GitHub Actions runners, Testcontainers works out of the box
- **Artifacts**: Debug and release APKs uploaded on successful build

### Deployment

- Build release APK: `make build-release` → `app/build/outputs/apk/release/app-release.apk`
- Distribute via GitHub Releases (tag with version, attach APK, include changelog)

---

## Security Practices

### Bearer Token Security

- Stored in DataStore (Preferences DataStore, accessed via `SettingsRepository`)
- Auto-generated UUID on first launch; user can view/copy/regenerate via UI
- Every MCP request must include `Authorization: Bearer <token>` header
- Constant-time comparison to prevent timing attacks; return `401 Unauthorized` if invalid/missing

### HTTPS (Optional — Disabled by Default)

- **HTTP is the default and primary transport.** The server starts on plain HTTP. This is intentional and the recommended mode for most users.
- **Why HTTP is the priority**: The MCP server runs on an Android device whose IP address changes frequently (WiFi reconnects, mobile data, different networks). Standard/public Certificate Authorities (CAs) cannot issue valid TLS certificates for bare IP addresses or dynamic IPs. Any HTTPS certificate the device can generate will be self-signed, meaning every MCP client would need to explicitly trust it or disable certificate verification. This makes HTTPS impractical as a default — it adds configuration friction with no real security benefit for the primary use case (localhost via ADB port forwarding, where traffic never leaves the USB cable).
- **HTTPS is a nice-to-have, not a priority.** It exists for users who need encrypted transport over a local network (binding to `0.0.0.0`), but even then the certificate will be self-signed and clients must allow insecure/untrusted certificates. Users who enable HTTPS must understand this trade-off.
- **Remote access tunnels**: The app integrates with **Cloudflare Quick Tunnels** (no account required, random `*.trycloudflare.com` URL) and **ngrok** (account required, optional custom domain) to expose the local MCP server via a public HTTPS URL with valid certificates. See the Remote Access / Tunnel section below.
- **When HTTPS is enabled** (user opt-in via UI toggle):
  - **Option 1 — Auto-Generated Self-Signed Certificate**: Generated on first enable using Bouncy Castle, configurable hostname (default "android-mcp.local"), valid for 1 year, stored in app-private storage, regeneratable. Clients must allow insecure/self-signed certificates.
  - **Option 2 — Custom Certificate Upload**: User uploads `.p12`/`.pfx` file with password, supports CA-signed certificates, stored in app-private storage.

### Remote Access / Tunnel

The app supports exposing the local MCP server to the internet via tunnel providers. This allows MCP clients to connect from anywhere without port forwarding or VPN configuration.

- **Cloudflare Quick Tunnels** (default): Runs the `cloudflared` binary as a child process. Creates a temporary tunnel with a random `*.trycloudflare.com` HTTPS URL. No account or configuration needed. The cloudflared binary is bundled as a native library (`libcloudflared.so`) via a git submodule in `vendor/cloudflared/`.
- **ngrok**: Uses the `ngrok-java` library (JNI-based, in-process). Requires an ngrok authtoken (free tier available). Supports optional custom domains. Only available on ARM64 devices.

Tunnel architecture:
- `TunnelProvider` interface defines `start(localPort, config)` / `stop()` with `status: StateFlow<TunnelStatus>`
- `TunnelManager` orchestrates provider lifecycle, reads `ServerConfig` to select the active provider
- `McpServerService` starts tunnel AFTER the Ktor server is running and stops tunnel BEFORE the server on shutdown
- Tunnel failure does NOT prevent the MCP server from running locally
- Tunnel URL is logged to both logcat and the UI server logs via `McpServerService.serverLogEvents` SharedFlow

### Network Security

- **Default binding**: `127.0.0.1` (localhost only, requires `adb forward tcp:8080 tcp:8080`)
- **Network mode**: `0.0.0.0` (all interfaces) — display security warning dialog when selected

| Connection Type | Binding: 127.0.0.1 | Binding: 0.0.0.0 |
|-----------------|-------------------|------------------|
| Mobile Data (4G/5G) | Not accessible | Not accessible from internet (CGNAT) |
| WiFi (Private) | Not accessible | Accessible to same-network devices |
| WiFi (Public) | Not accessible | **DANGER**: Accessible to anyone on public WiFi |
| USB Tethered | Not accessible | Accessible to tethered device |
| Device as Hotspot | Not accessible | Accessible to hotspot clients |
| ADB Port Forward | Accessible via host | Accessible via host |

### Permission Security

Only necessary permissions: `INTERNET`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES` (app listing), `KILL_BACKGROUND_PROCESSES` (app closing), `CAMERA` (camera tools, runtime), `RECORD_AUDIO` (video recording with audio, runtime), Accessibility Service (user-granted via Settings), SAF tree URI permissions (user-granted per storage location via system file picker). Display clear explanations before requesting.
Per-location read/write/delete permissions are enforced by `FileOperationProvider` — see the Storage Location Permissions section above.

### Code Security

- No hardcoded tokens/keys/passwords; all secrets in DataStore or injected at runtime
- Validate all MCP request parameters (type, range, format); sanitize inputs before AccessibilityService operations
- Don't leak sensitive information in error messages; don't expose internal paths/stack traces to clients

---

## Default Configuration

### Server Defaults

- **Port**: `8080`
- **Binding Address**: `127.0.0.1` (localhost)
- **Bearer Token**: Auto-generated UUID on first launch
- **HTTPS**: Disabled by default (HTTP is the primary transport). When enabled by the user, uses auto-generated self-signed certificate with hostname "android-mcp.local", 1-year validity. Clients must allow insecure/self-signed certificates.
- **Auto-start on Boot**: Disabled
- **Remote Access Tunnel**: Disabled by default
- **Tunnel Provider**: Cloudflare (no account required)
- **ngrok Authtoken**: Empty (required when using ngrok)
- **ngrok Domain**: Empty (auto-assigned when empty)
- **File Size Limit**: 50 MB (range 1-500 MB, configurable via UI, applies to all file operations)
- **Allow HTTP Downloads**: Disabled (must be explicitly enabled to allow non-HTTPS downloads)
- **Allow Unverified HTTPS Certificates**: Disabled (must be explicitly enabled to accept self-signed/invalid certs for downloads)
- **Download Timeout**: 60 seconds (range 10-300 seconds, configurable via UI)
- **Device Slug** (`String`, default: `""`): Optional device identifier used as part of the MCP tool name prefix. When empty, tools use the `android_` prefix. When set (e.g., `pixel7`), tools use `android_pixel7_` prefix. Valid characters: letters (a-z, A-Z), digits (0-9), underscores (_). Maximum length: 20 characters.

### MCP Defaults

- **Screenshot Quality**: 80 (JPEG, 1-100)
- **Timeout**: 5000ms (wait_for_element), 3000ms (wait_for_idle)
- **Long Press Duration**: 1000ms
- **Swipe Duration**: 300ms
- **Gesture Duration**: 300ms
- **Scroll Amount**: "medium" (50% of screen dimension)

### Camera Defaults

- **Default Resolution**: 720p (closest available match)
- **Default Quality**: 80 (JPEG, 1-100)
- **Default Flash Mode**: auto (off/on/auto)
- **Max Video Duration**: 30 seconds
- **Default Audio**: Enabled (true)
- **Max Photo Resolution (take_camera_photo)**: 1920x1080 (to prevent excessively large base64 responses)

### Accessibility Defaults

- **Event Types**: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED
- **Feedback Type**: FEEDBACK_GENERIC
- **Flags**: FLAG_REPORT_VIEW_IDS, FLAG_RETRIEVE_INTERACTIVE_WINDOWS, FLAG_INPUT_METHOD_EDITOR
- **Capabilities**: CAPABILITY_CAN_PERFORM_GESTURES, CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT

### UI Defaults

- **Theme**: System default (light/dark follows system)
- **Language**: System default

---

## Makefile Targets

All common development tasks are accessible via `make <target>`. Run `make help` for a full list.

### Build & Clean

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `build` | Build debug APK | `./gradlew assembleDebug` |
| `build-release` | Build release APK | `./gradlew assembleRelease` |
| `clean` | Clean build artifacts | `./gradlew clean` |

### Testing

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `test-unit` | Run unit and JVM integration tests | `./gradlew test` |
| `test-integration` | Run JVM integration tests only | `./gradlew :app:testDebugUnitTest --tests "...integration.*"` |
| `test-e2e` | Run E2E tests (requires Docker) | `./gradlew :e2e-tests:test` |
| `test` | Run all tests sequentially | test-unit + test-e2e |
| `coverage` | Generate Jacoco coverage report | `./gradlew jacocoTestReport` |

### Linting

| Target | Description | Underlying Command |
|--------|-------------|-------------------|
| `lint` | Run all linters | `./gradlew ktlintCheck detekt` |
| `lint-fix` | Auto-fix linting issues | `./gradlew ktlintFormat` |

### Device Management

| Target | Description |
|--------|-------------|
| `install` | Install debug APK on connected device/emulator |
| `install-release` | Install release APK |
| `uninstall` | Uninstall app from device |
| `grant-permissions` | Grant permissions via adb (accessibility, notifications, camera, microphone) |
| `start-server` | Launch MainActivity on device via adb |
| `forward-port` | Set up adb port forwarding (device 8080 → host 8080) |

### Emulator

| Target | Description |
|--------|-------------|
| `setup-emulator` | Create AVD (API 34, x86_64, Pixel 6) |
| `start-emulator` | Start emulator in headless mode |
| `stop-emulator` | Stop running emulator |

### Logging, Versioning, All-in-One

| Target | Description |
|--------|-------------|
| `logs` | Show app logs via adb logcat (filtered by MCP tags) |
| `logs-clear` | Clear logcat buffer |
| `version-bump-patch` | Increment patch version (1.0.0 → 1.0.1) |
| `version-bump-minor` | Increment minor version (1.0.0 → 1.1.0) |
| `version-bump-major` | Increment major version (1.0.0 → 2.0.0) |
| `check-deps` | Check for required tools (Android SDK, Java 17+, Gradle, adb, Docker) |
| `all` | Run full workflow: clean → build → lint → test-unit |
| `ci` | Run CI workflow: check-deps → lint → test-unit (includes JVM integration) → test-e2e → build-release |

---

## Related Documentation

- **[TOOLS.md](TOOLS.md)** — Git branching conventions, commit format, PR creation, GitHub CLI commands, and local CI testing with `act`
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — Detailed application architecture: component interactions, service lifecycle diagrams, threading model, inter-service communication patterns
- **[MCP_TOOLS.md](MCP_TOOLS.md)** — Full MCP tools documentation with JSON-RPC schemas, usage examples, error codes, and implementation notes for all 53 tools

---

**End of PROJECT.md**
