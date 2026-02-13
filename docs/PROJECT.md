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

The application is a **service-based Android app** that exposes an MCP server over HTTP (with optional HTTPS). It consists of four main components:

1. **AccessibilityService** — Provides UI introspection and action execution
2. **ScreenCaptureService** — Manages MediaProjection for screenshot capture
3. **McpServerService** — Runs the HTTP server implementing MCP protocol
4. **MainActivity** — UI for configuration and control

### Component Details

#### 1. AccessibilityService

- **Type**: Android `AccessibilityService`
- **Purpose**: Introspect UI hierarchy of all apps and perform actions
- **Lifecycle**: Runs as long as enabled in Android Settings (Accessibility)
- **Capabilities**: Traverse accessibility tree (full depth), find elements by text/content description/resource ID/class name, perform actions (click, long-click, scroll, swipe, set text), execute global actions (back, home, recents, notifications, quick settings)
- **Implementation**: Extends `android.accessibilityservice.AccessibilityService`, registers for all event types and all packages, stores singleton instance for inter-service communication, uses coroutines for non-blocking operations

#### 2. ScreenCaptureService

- **Type**: Android Foreground Service
- **Purpose**: Maintain continuous MediaProjection for screenshot capture
- **Lifecycle**: Started/stopped by McpServerService
- **Capabilities**: Request MediaProjection permission (one-time user grant), maintain active session, capture screenshots on demand, encode to JPEG
- **Implementation**: Uses `MediaProjectionManager` and `ImageReader`, runs as foreground service (persistent notification), singleton pattern, handles permission expiration and re-request

#### 3. McpServerService

- **Type**: Android Foreground Service
- **Purpose**: Run HTTP server implementing MCP protocol
- **Lifecycle**: User-controlled via MainActivity (start/stop)
- **Capabilities**: HTTP/HTTPS server using Ktor, MCP JSON-RPC 2.0 protocol, bearer token authentication, configurable binding address (127.0.0.1 or 0.0.0.0), orchestrates calls to AccessibilityService and ScreenCaptureService
- **Implementation**: Foreground service with persistent notification, Kotlin coroutines for async request handling, reads configuration from DataStore, exposes MCP endpoints (`/mcp/v1/tools`, `/mcp/v1/call`), graceful shutdown on service stop

#### 4. MainActivity

- **Type**: Android Activity (Jetpack Compose UI)
- **Purpose**: Configuration and control interface
- **Features**: Server status display (running/stopped), start/stop MCP server toggle, configuration settings (binding address, port, bearer token, auto-start on boot, HTTPS toggle and certificate management), quick links (enable accessibility service, grant MediaProjection), connection info display, server logs viewer (recent MCP requests)
- **Implementation**: Material Design 3 with dark mode support, Jetpack Compose, ViewModel for state management, observes service status via Flow/StateFlow

### Inter-Service Communication

**Pattern**: Singleton + Bound Service hybrid
- **AccessibilityService**: Stores singleton instance in companion object for direct access (system-managed, long-lived)
- **ScreenCaptureService**: Bound service pattern — McpServerService binds to it (lifecycle tied to McpServerService)
- **McpServerService**: Exposes status via companion-level StateFlow for UI observation

### Service Lifecycle

The typical startup flow: User opens app → enables Accessibility Service in Android Settings → grants MediaProjection permission → starts MCP server via button → McpServerService starts as foreground service → binds to ScreenCaptureService → starts Ktor HTTP/HTTPS server → MCP server ready on configured address:port → user minimizes app (services continue in background) → MCP clients can connect and control device.

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
- **Android SDK**: Target API 34 (Android 14), Minimum API 26 (Android 8.0)
- **JDK**: Java 17 (standard for Android development)

### Frameworks & Libraries

- **Jetpack Compose**: UI framework (Material Design 3)
- **Lifecycle**: ViewModel, LiveData, Flow
- **DataStore**: Settings persistence (modern replacement for SharedPreferences)
- **Hilt**: Dependency injection (Dagger-based, official Android DI)
- **Ktor Server**: HTTP/HTTPS server (Kotlin-native, async, coroutine-based)
- **MCP Kotlin SDK**: Official Model Context Protocol implementation (from Anthropic/ModelContextProtocol)
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
- **OkHttp**: HTTP client for E2E tests

### Build Tools

- **Gradle 8.x**: Build system (Kotlin DSL)
- **Makefile**: Development workflow automation
- **ktlint** and **detekt**: Kotlin linting

---

## Folder Structure

- `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
  - `McpApplication.kt` — Application class (Hilt setup)
  - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `ScreenInfo.kt`
  - `services/screencapture/` — `ScreenCaptureService.kt`, `ScreenCaptureProvider.kt`, `ScreenCaptureProviderImpl.kt`, `MediaProjectionHelper.kt`, `ScreenshotEncoder.kt`
  - `services/mcp/` — `McpServerService.kt`, `BootCompletedReceiver.kt`
  - `mcp/` — `McpServer.kt`, `McpProtocolHandler.kt`, `McpToolException.kt`, `CertificateManager.kt`
  - `mcp/tools/` — `ToolRegistry.kt`, `McpContentBuilder.kt`, `McpToolUtils.kt`, `ScreenIntrospectionTools.kt`, `TouchActionTools.kt`, `ElementActionTools.kt`, `TextInputTools.kt`, `SystemActionTools.kt`, `GestureTools.kt`, `UtilityTools.kt`
  - `mcp/auth/` — `BearerTokenAuth.kt`
  - `ui/` — `MainActivity.kt`
  - `ui/theme/` — `Theme.kt`, `Color.kt`, `Type.kt`
  - `ui/screens/` — `HomeScreen.kt`
  - `ui/components/` — `ServerStatusCard.kt`, `ConfigurationSection.kt`, `ConnectionInfoCard.kt`, `PermissionsSection.kt`, `ServerLogsSection.kt`
  - `ui/viewmodels/` — `MainViewModel.kt`
  - `data/repository/` — `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`
  - `data/model/` — `ServerConfig.kt`, `ServerStatus.kt`, `ServerLogEntry.kt`, `BindingAddress.kt`, `CertificateSource.kt`, `ScreenshotData.kt`
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

- **Protocol**: HTTP/HTTPS with JSON-RPC 2.0
- **Framework**: Ktor Server (async, coroutine-based)
- **Endpoints**:
  - `GET /health` — Health check (unauthenticated)
  - `POST /mcp/v1/initialize` — Initialize MCP session
  - `GET /mcp/v1/tools/list` — List available MCP tools
  - `POST /mcp/v1/tools/call` — Execute an MCP tool
- **Authentication**: Bearer token (`Authorization: Bearer <token>`)
- **Content-Type**: `application/json`

### Error Codes

Standard MCP error codes:
- `-32700`: Parse error (invalid JSON)
- `-32600`: Invalid request (malformed JSON-RPC)
- `-32601`: Method not found (unknown tool name)
- `-32602`: Invalid params (missing or invalid tool arguments)
- `-32603`: Internal error (server-side error)

Custom error codes:
- `-32001`: Permission not granted (accessibility or screenshot permission missing)
- `-32002`: Element not found (UI element search failed)
- `-32003`: Action failed (accessibility action execution failed)
- `-32004`: Timeout (operation timed out)

### Authentication

- Every MCP request must include `Authorization: Bearer <token>` header
- Token is validated by `BearerTokenAuth` middleware (constant-time comparison to prevent timing attacks)
- Invalid/missing token returns `401 Unauthorized`
- Token is stored in DataStore, configurable via UI
- `GET /health` endpoint is unauthenticated

---

## MCP Tools Specification

The MCP server exposes 29 tools across 7 categories. For full JSON-RPC schemas, detailed usage examples, and implementation notes, see [MCP_TOOLS.md](MCP_TOOLS.md).

### 1. Screen Introspection Tools (4 tools)

| Tool | Description | Parameters | Output |
|------|-------------|------------|--------|
| `get_accessibility_tree` | Returns full UI hierarchy of current screen | None | JSON accessibility tree with node IDs, text, bounds, class names, etc. |
| `capture_screenshot` | Captures screenshot as base64-encoded JPEG | `quality` (int, 1-100, default 80, optional) | Base64 JPEG data with width, height, mimeType |
| `get_current_app` | Returns package and activity of focused app | None | packageName, activityName |
| `get_screen_info` | Returns screen dimensions, DPI, orientation | None | width, height, densityDpi, orientation |

**Error**: `-32001` if accessibility/MediaProjection permission not granted.

**Note**: `get_accessibility_tree` returns full tree depth. Future optimization planned for configurable depth limiting.

### 2. Touch Action Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `tap` | Single tap at coordinates | `x` (number), `y` (number) | — |
| `long_press` | Long press at coordinates | `x` (number), `y` (number) | `duration` (number, ms, default 1000) |
| `double_tap` | Double tap at coordinates | `x` (number), `y` (number) | — |
| `swipe` | Swipe from point A to B | `x1`, `y1`, `x2`, `y2` (all number) | `duration` (number, ms, default 300) |
| `scroll` | Scroll in direction | `direction` (string: up/down/left/right) | `amount` (string: small/medium/large, default medium) |

**Errors**: `-32001` if accessibility not enabled, `-32003` if action execution failed.

### 3. Element Action Tools (5 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `find_elements` | Find UI elements by criteria | `by` (string: text/content_desc/resource_id/class_name), `value` (string) | `exact_match` (boolean, default false) |
| `click_element` | Click an accessibility node | `element_id` (string) | — |
| `long_click_element` | Long-click an accessibility node | `element_id` (string) | — |
| `set_text` | Set text on editable node | `element_id` (string), `text` (string) | — |
| `scroll_to_element` | Scroll to make element visible | `element_id` (string) | — |

**Errors**: `-32002` if element not found (ID invalid or stale), `-32003` if element not clickable/editable. `find_elements` returns empty array (not error) when no matches found.

### 4. Text Input Tools (3 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `input_text` | Type text into focused/target input | `text` (string) | `element_id` (string) |
| `clear_text` | Clear text from focused/target input | — | `element_id` (string) |
| `press_key` | Press a specific key | `key` (string: ENTER/BACK/DEL/HOME/TAB/SPACE) | — |

### 5. System Action Tools (6 tools)

| Tool | Description | Parameters |
|------|-------------|------------|
| `press_back` | Press back button | None |
| `press_home` | Navigate to home screen | None |
| `press_recents` | Open recent apps | None |
| `open_notifications` | Pull down notification shade | None |
| `open_quick_settings` | Open quick settings panel | None |
| `get_device_logs` | Retrieve filtered logcat logs | `last_lines` (int, 1-1000, default 100), `since`/`until` (ISO 8601 timestamp), `tag` (string), `level` (string: V/D/I/W/E/F, default D), `package_name` (string) — all optional |

### 6. Gesture Tools (2 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `pinch` | Pinch-to-zoom gesture | `center_x` (number), `center_y` (number), `scale` (number: >1 zoom in, <1 zoom out) | `duration` (number, ms, default 300) |
| `custom_gesture` | Multi-touch gesture from path points | `paths` (array of arrays of {x, y, time} objects) | — |

### 7. Utility Tools (4 tools)

| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `get_clipboard` | Get current clipboard content | — | — |
| `set_clipboard` | Set clipboard content | `text` (string) | — |
| `wait_for_element` | Wait until element appears | `by` (string), `value` (string) | `timeout` (number, ms, default 5000) |
| `wait_for_idle` | Wait for UI to become idle | — | `timeout` (number, ms, default 3000) |

**Errors**: `wait_for_element` returns `-32004` on timeout.

---

## Android-Specific Conventions

### Service Lifecycle Management

- All long-running services (McpServerService, ScreenCaptureService) MUST run as foreground services with persistent notifications
- Call `startForeground()` within 5 seconds of service start, `stopForeground()` before destruction
- Use bound service pattern for services with lifecycle tied to client (ScreenCaptureService)
- Always unbind in `onDestroy()` to prevent memory leaks
- Service-to-UI communication via Flow/StateFlow (LocalBroadcastManager is deprecated and NOT used)

### AccessibilityService Best Practices

- Register only for needed event types (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- Keep `onAccessibilityEvent()` fast, offload heavy work to coroutines
- Cache accessibility tree when possible; call `node.recycle()` after use
- Check `node.refresh()` before using cached nodes (stale detection)
- All node operations MUST happen on main thread
- Use `performAction()` for element actions, `performGlobalAction()` for system actions, `dispatchGesture()` for complex touch sequences (API 24+)

### Permission Handling

- **MediaProjection**: Request via `MediaProjectionManager.createScreenCaptureIntent()`
- **Accessibility**: User must enable manually in Settings (provide deep link)
- **Internet**: Declared in manifest, granted automatically
- Always check permission state before operations; return MCP error `-32001` if permission missing

### Background Restrictions & Memory Management

- Foreground services are exempt from Doze restrictions
- Never store Activity context in long-lived objects — use ApplicationContext
- Cancel coroutine scopes in `onDestroy()`; call `MediaProjection.stop()` when done; recycle large bitmaps after encoding; use `use {}` for automatic stream closure

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

HomeScreen contains a TopAppBar, then a scrollable layout with: ServerStatusCard (status, start/stop), ConfigurationSection (binding address, port, token, auto-start, HTTPS), PermissionsSection (accessibility/screenshot links), ServerLogsSection (scrollable recent MCP requests), and ConnectionInfoCard (IP, port, token for client setup).

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
- **Mocking**: Mock Android framework classes (AccessibilityNodeInfo, MediaProjection) with MockK; use `@MockK`/`@RelaxedMockK` annotations; verify interactions with `verify {}`
- **Pattern**: Arrange-Act-Assert consistently
- **Run**: `make test-unit` or `./gradlew test`

### Integration Tests

- **Framework**: Ktor `testApplication`, JUnit 5, MockK
- **Scope**: Full HTTP stack (authentication, JSON-RPC protocol, tool dispatch) via in-process Ktor test server; all 7 tool categories, error code propagation
- **Mocking**: Mock Android services (`ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`) via interfaces; real `McpProtocolHandler` and `ToolRegistry`
- **Infrastructure**: `McpIntegrationTestHelper` configures `testApplication` with same routing as production `McpServer`; `sendToolCall()` helper sends JSON-RPC requests
- **Run**: `make test-integration` or `./gradlew :app:testDebugUnitTest --tests "com.danielealbano.androidremotecontrolmcp.integration.*"`
- **Note**: JVM-based, no emulator or device required. Runs as part of `make test-unit` since both are under `app/src/test/`

### E2E Tests

- **Framework**: Testcontainers Kotlin (`budtmo/docker-android-x86:emulator_14.0`), JUnit 5, OkHttp
- **Scope**: Full MCP client → server → Android → action flow, Calculator app test (7 + 3 = 10), screenshot capture validation, error handling (auth, unknown tool, invalid params, element not found)
- **Infrastructure**: `SharedAndroidContainer` singleton shares one Docker container across all test classes (avoids ~2-4 min boot per class); `McpClient` test utility handles HTTP and HTTPS/self-signed certs; `E2EConfigReceiver` debug-only BroadcastReceiver injects test settings via `adb shell am broadcast`
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
- **Future direction**: Proper HTTPS exposure may be achieved via integration with tunneling services like ngrok or Tailscale, which provide valid certificates for dynamically-assigned endpoints. This is planned for a future release, not the current scope.
- **When HTTPS is enabled** (user opt-in via UI toggle):
  - **Option 1 — Auto-Generated Self-Signed Certificate**: Generated on first enable using Bouncy Castle, configurable hostname (default "android-mcp.local"), valid for 1 year, stored in app-private storage, regeneratable. Clients must allow insecure/self-signed certificates.
  - **Option 2 — Custom Certificate Upload**: User uploads `.p12`/`.pfx` file with password, supports CA-signed certificates, stored in app-private storage.

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

Only necessary permissions: `INTERNET`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, Accessibility Service (user-granted via Settings), MediaProjection (user-granted via dialog). Display clear explanations before requesting.

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

### MCP Defaults

- **Screenshot Quality**: 80 (JPEG, 1-100)
- **Timeout**: 5000ms (wait_for_element), 3000ms (wait_for_idle)
- **Long Press Duration**: 1000ms
- **Swipe Duration**: 300ms
- **Gesture Duration**: 300ms
- **Scroll Amount**: "medium" (50% of screen dimension)

### Accessibility Defaults

- **Event Types**: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED
- **Feedback Type**: FEEDBACK_GENERIC
- **Flags**: FLAG_REPORT_VIEW_IDS, FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
| `grant-permissions` | Display instructions for granting accessibility/MediaProjection permissions |
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
- **[MCP_TOOLS.md](MCP_TOOLS.md)** — Full MCP tools documentation with JSON-RPC schemas, usage examples, error codes, and implementation notes for all 29 tools

---

**End of PROJECT.md**
