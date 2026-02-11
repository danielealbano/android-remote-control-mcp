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

---

## Architecture

### Overview

The application is a **service-based Android app** that exposes an MCP server over HTTP/HTTPS. It consists of four main components:

1. **AccessibilityService** - Provides UI introspection and action execution
2. **ScreenCaptureService** - Manages MediaProjection for screenshot capture
3. **McpServerService** - Runs the HTTP server implementing MCP protocol
4. **MainActivity** - UI for configuration and control

### Component Details

#### 1. AccessibilityService

- **Type**: Android `AccessibilityService`
- **Purpose**: Introspect UI hierarchy of all apps and perform actions
- **Lifecycle**: Runs as long as enabled in Android Settings (Accessibility)
- **Capabilities**:
  - Traverse accessibility tree (full depth)
  - Find elements by text, content description, resource ID, class name
  - Perform actions: click, long-click, scroll, swipe, set text
  - Execute global actions: back, home, recents, notifications, quick settings
- **Implementation**:
  - Extends `android.accessibilityservice.AccessibilityService`
  - Registers for all event types and all packages
  - Stores singleton instance for inter-service communication
  - Uses coroutines for non-blocking operations

#### 2. ScreenCaptureService

- **Type**: Android Foreground Service
- **Purpose**: Maintain continuous MediaProjection for screenshot capture
- **Lifecycle**: Started/stopped by McpServerService
- **Capabilities**:
  - Request MediaProjection permission (one-time user grant)
  - Maintain active MediaProjection session
  - Capture screenshots on demand
  - Encode screenshots to JPEG format
- **Implementation**:
  - Uses `MediaProjectionManager` and `ImageReader`
  - Runs as foreground service (persistent notification required)
  - Singleton pattern for access from MCP server
  - Handles permission expiration and re-request

#### 3. McpServerService

- **Type**: Android Foreground Service
- **Purpose**: Run HTTP server implementing MCP protocol
- **Lifecycle**: User-controlled via MainActivity (start/stop)
- **Capabilities**:
  - HTTP/HTTPS server using Ktor
  - Implements MCP JSON-RPC 2.0 protocol
  - Bearer token authentication
  - Configurable binding address (127.0.0.1 or 0.0.0.0)
  - Orchestrates calls to AccessibilityService and ScreenCaptureService
- **Implementation**:
  - Foreground service with persistent notification
  - Uses Kotlin coroutines for async request handling
  - Reads configuration from DataStore
  - Exposes MCP endpoints: `/mcp/v1/tools`, `/mcp/v1/call`
  - Graceful shutdown on service stop

#### 4. MainActivity

- **Type**: Android Activity (Jetpack Compose UI)
- **Purpose**: Configuration and control interface
- **Features**:
  - Server status display (running/stopped)
  - Start/Stop MCP server toggle
  - Configuration settings:
    - Binding address (127.0.0.1 vs 0.0.0.0) with security warning for 0.0.0.0
    - Port number
    - Bearer token (view/copy/regenerate)
    - Auto-start on boot toggle
    - HTTPS certificate management:
      - Auto-generate self-signed certificate (with hostname configuration)
      - Upload custom certificate (.p12/.pfx file)
  - Quick links:
    - Enable Accessibility Service (opens Settings)
    - Grant MediaProjection permission
  - Connection info display (IP, port, token for client setup)
  - Server logs viewer (recent MCP requests)
- **Implementation**:
  - Material Design 3 with modern, cool UI
  - Dark mode support
  - Jetpack Compose
  - ViewModel for state management
  - Observes service status via Flow/LiveData

### Inter-Service Communication

**Pattern**: Singleton + Bound Service hybrid

- **AccessibilityService**: Stores singleton instance in companion object for direct access
- **ScreenCaptureService**: Bound service pattern - McpServerService binds to it
- **McpServerService**: Broadcasts status updates via LocalBroadcastManager for UI

**Rationale**:
- AccessibilityService is system-managed and long-lived, singleton is safe
- ScreenCaptureService needs lifecycle tied to McpServerService, bound service appropriate
- MainActivity needs status updates, broadcast pattern is standard Android approach

### Service Lifecycle

```
User Opens App (MainActivity)
    ↓
User Enables Accessibility Service (Android Settings)
    ↓ (AccessibilityService starts automatically)
User Grants MediaProjection Permission
    ↓
User Starts MCP Server (Button in MainActivity)
    ↓
McpServerService starts (foreground)
    ↓
McpServerService binds to ScreenCaptureService
    ↓
ScreenCaptureService starts MediaProjection
    ↓
McpServerService starts Ktor HTTP server
    ↓
MCP server ready (listening on configured address:port)
    ↓
User Minimizes App
    ↓ (Services continue running in background)
MCP clients can connect and control device
```

**Auto-start on Boot** (if enabled):
```
Device Boots
    ↓
BootCompletedReceiver triggers
    ↓
If auto-start enabled in settings:
    ↓
McpServerService starts automatically
```

### Threading Model

- **Main Thread**: UI operations only (Compose, Activity lifecycle)
- **IO Dispatcher**: Network operations (Ktor server, HTTP requests)
- **Default Dispatcher**: CPU-intensive operations (screenshot encoding, accessibility tree parsing)
- **Service Operations**: All AccessibilityService operations must be posted to main thread (Android requirement)

---

## Tech Stack

### Core Technologies

- **Language**: Kotlin 1.9.x (latest stable compatible with Android 14)
- **Android SDK**: Target API 34 (Android 14), Minimum API 26 (Android 8.0)
- **JDK**: Java 17 (standard for Android development)

### Frameworks & Libraries

#### Android Core
- **Jetpack Compose**: UI framework (Material Design 3)
- **Lifecycle**: ViewModel, LiveData, Flow
- **DataStore**: Settings persistence (modern replacement for SharedPreferences)
- **Hilt**: Dependency injection (Dagger-based, official Android DI)

#### Networking & MCP
- **Ktor Server**: HTTP/HTTPS server (Kotlin-native, async, coroutine-based)
- **MCP Kotlin SDK**: Official Model Context Protocol implementation (from Anthropic/ModelContextProtocol)
- **Kotlinx Serialization**: JSON serialization for MCP protocol

#### Testing
- **JUnit 5**: Unit test framework
- **Kotlin Test**: Kotlin-specific test utilities
- **MockK**: Mocking framework for Kotlin
- **Turbine**: Flow testing library
- **Compose UI Test**: Jetpack Compose testing
- **Testcontainers Kotlin**: Container-based integration/E2E tests
- **Docker Android**: budtmo/docker-android-x86 image for E2E tests

#### Utilities
- **Kotlinx Coroutines**: Async/concurrency
- **Android Log**: Logging (standard Android Log class)
- **Accompanist**: Compose utilities (permissions handling)

### Build Tools

- **Gradle 8.x**: Build system (Kotlin DSL)
- **Makefile**: Development workflow automation
- **ktlint** or **detekt**: Kotlin linting

---

## Folder Structure

```
android-remote-control-mcp/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/danielealbano/androidremotecontrolmcp/
│   │   │   │   ├── McpApplication.kt                # Application class (Hilt setup)
│   │   │   │   ├── services/
│   │   │   │   │   ├── accessibility/
│   │   │   │   │   │   ├── McpAccessibilityService.kt    # AccessibilityService implementation
│   │   │   │   │   │   ├── AccessibilityTreeParser.kt    # Parse accessibility tree
│   │   │   │   │   │   ├── ElementFinder.kt              # Find elements by criteria
│   │   │   │   │   │   └── ActionExecutor.kt             # Execute actions on elements
│   │   │   │   │   ├── screencapture/
│   │   │   │   │   │   ├── ScreenCaptureService.kt       # Foreground service for screenshots
│   │   │   │   │   │   └── MediaProjectionManager.kt     # Manage MediaProjection lifecycle
│   │   │   │   │   └── mcp/
│   │   │   │   │       ├── McpServerService.kt           # Foreground service for MCP server
│   │   │   │   │       └── BootCompletedReceiver.kt      # Auto-start on boot
│   │   │   │   ├── mcp/
│   │   │   │   │   ├── McpServer.kt                      # Ktor server setup
│   │   │   │   │   ├── McpProtocolHandler.kt             # MCP JSON-RPC handling
│   │   │   │   │   ├── tools/
│   │   │   │   │   │   ├── ScreenIntrospectionTools.kt   # get_accessibility_tree, capture_screenshot, etc.
│   │   │   │   │   │   ├── TouchActionTools.kt           # tap, long_press, swipe, etc.
│   │   │   │   │   │   ├── ElementActionTools.kt         # find_elements, click_element, etc.
│   │   │   │   │   │   ├── TextInputTools.kt             # input_text, clear_text, press_key
│   │   │   │   │   │   ├── SystemActionTools.kt          # press_back, press_home, etc.
│   │   │   │   │   │   ├── GestureTools.kt               # pinch, custom_gesture
│   │   │   │   │   │   └── UtilityTools.kt               # get_clipboard, wait_for_element, etc.
│   │   │   │   │   └── auth/
│   │   │   │   │       └── BearerTokenAuth.kt            # Bearer token authentication middleware
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt                   # Main Activity
│   │   │   │   │   ├── theme/
│   │   │   │   │   │   ├── Theme.kt                      # Material 3 theme
│   │   │   │   │   │   ├── Color.kt                      # Color palette
│   │   │   │   │   │   └── Type.kt                       # Typography
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   └── HomeScreen.kt                 # Main configuration screen
│   │   │   │   │   ├── components/
│   │   │   │   │   │   ├── ServerStatusCard.kt           # Server status display
│   │   │   │   │   │   ├── ConfigurationSection.kt       # Settings section
│   │   │   │   │   │   └── ConnectionInfoCard.kt         # Connection details
│   │   │   │   │   └── viewmodels/
│   │   │   │   │       └── MainViewModel.kt              # ViewModel for MainActivity
│   │   │   │   ├── data/
│   │   │   │   │   ├── repository/
│   │   │   │   │   │   └── SettingsRepository.kt         # DataStore wrapper
│   │   │   │   │   └── model/
│   │   │   │   │       ├── ServerConfig.kt               # Server configuration data class
│   │   │   │   │       └── ServerStatus.kt               # Server status sealed class
│   │   │   │   ├── di/
│   │   │   │   │   └── AppModule.kt                      # Hilt dependency injection module
│   │   │   │   └── utils/
│   │   │   │       ├── NetworkUtils.kt                   # Network helper functions
│   │   │   │       ├── PermissionUtils.kt                # Permission checking helpers
│   │   │   │       └── Logger.kt                         # Logging wrapper
│   │   │   ├── res/
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml                       # String resources
│   │   │   │   │   └── themes.xml                        # Theme definitions
│   │   │   │   ├── drawable/                             # Icons and drawables
│   │   │   │   ├── mipmap/                               # App icons
│   │   │   │   └── xml/
│   │   │   │       └── accessibility_service_config.xml   # Accessibility service config
│   │   │   └── AndroidManifest.xml                        # App manifest
│   │   ├── test/                                          # Unit tests
│   │   │   └── kotlin/com/danielealbano/androidremotecontrolmcp/
│   │   │       ├── mcp/
│   │   │       │   └── McpProtocolHandlerTest.kt
│   │   │       ├── services/
│   │   │       │   └── accessibility/
│   │   │       │       └── ElementFinderTest.kt
│   │   │       └── utils/
│   │   │           └── NetworkUtilsTest.kt
│   │   └── androidTest/                                   # Integration tests (instrumented)
│   │       └── kotlin/com/danielealbano/androidremotecontrolmcp/
│   │           └── ui/
│   │               └── MainActivityTest.kt
│   ├── build.gradle.kts                                   # App module build script
│   └── proguard-rules.pro                                 # ProGuard rules (not used, open source)
├── e2e-tests/                                              # E2E tests (Docker Android)
│   ├── src/
│   │   └── test/
│   │       └── kotlin/
│   │           └── E2ECalculatorTest.kt                   # Calculator app E2E test
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml                                 # Gradle version catalog
├── build.gradle.kts                                        # Root build script
├── settings.gradle.kts                                     # Gradle settings
├── gradle.properties                                       # Gradle properties (version, etc.)
├── Makefile                                                # Development workflow automation
├── PROJECT.md                                              # This file (project bible)
├── CLAUDE.md                                               # LLM agent rules
├── README.md                                               # User-facing documentation
├── LICENSE                                                 # MIT License
└── .github/
    └── workflows/
        └── ci.yml                                          # GitHub Actions CI workflow
```

---

## MCP Protocol Implementation

### Protocol Overview

The application implements the **Model Context Protocol (MCP)** specification from Anthropic/ModelContextProtocol. MCP is a JSON-RPC 2.0 based protocol that enables AI models to interact with external tools and resources.

### Transport Layer

- **Protocol**: HTTP/HTTPS with JSON-RPC 2.0
- **Framework**: Ktor Server (async, coroutine-based)
- **Endpoints**:
  - `GET /health` - Health check endpoint
  - `POST /mcp/v1/initialize` - Initialize MCP session
  - `GET /mcp/v1/tools/list` - List available MCP tools
  - `POST /mcp/v1/tools/call` - Execute an MCP tool
- **Authentication**: Bearer token (Authorization: Bearer <token>)
- **Content-Type**: `application/json`

### MCP SDK Integration

Use the **official MCP Kotlin/JVM library** from Anthropic/ModelContextProtocol:

```kotlin
dependencies {
    implementation("com.anthropic:mcp-kotlin:<version>")
}
```

The SDK provides:
- JSON-RPC 2.0 message parsing/serialization
- Tool registration and invocation framework
- Error handling and response formatting
- Type-safe tool definitions

### Request/Response Format

**Tool List Request** (GET /mcp/v1/tools/list):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list"
}
```

**Tool List Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "get_accessibility_tree",
        "description": "Get full UI hierarchy of current screen",
        "inputSchema": {
          "type": "object",
          "properties": {},
          "required": []
        }
      },
      ...
    ]
  }
}
```

**Tool Call Request** (POST /mcp/v1/tools/call):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "tap",
    "arguments": {
      "x": 500,
      "y": 1000
    }
  }
}
```

**Tool Call Response** (Success):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Tap executed at (500, 1000)"
      }
    ]
  }
}
```

**Tool Call Response** (Error):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": {
    "code": -32001,
    "message": "Accessibility service not enabled",
    "data": {
      "details": "Please enable accessibility service in Android Settings"
    }
  }
}
```

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

**Bearer Token Authentication**:
- Every request must include `Authorization: Bearer <token>` header
- Token is validated by `BearerTokenAuth` middleware before tool execution
- Invalid/missing token returns `401 Unauthorized`
- Token is stored in DataStore, configurable via UI

---

## MCP Tools Specification

### Tool Categories

1. **Screen Introspection** - Get information about current UI state
2. **Touch Actions** - Coordinate-based touch interactions
3. **Element Actions** - Accessibility node-based interactions
4. **Text Input** - Keyboard input and text manipulation
5. **System Actions** - Global device actions
6. **Gestures** - Advanced multi-touch gestures
7. **Utilities** - Helper tools (clipboard, wait, etc.)

---

### 1. Screen Introspection Tools

#### `get_accessibility_tree`

**Description**: Returns the full UI hierarchy of the current screen using accessibility services.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: JSON representation of accessibility tree (full depth).

**Output Structure**:
```json
{
  "nodes": [
    {
      "id": "node_123",
      "className": "android.widget.TextView",
      "text": "Calculator",
      "contentDescription": "Calculator app",
      "resourceId": "com.android.calculator2:id/title",
      "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 200},
      "clickable": false,
      "focusable": false,
      "visible": true,
      "children": [...]
    }
  ]
}
```

**Note on Future Optimization**: Current implementation returns full tree depth for maximum flexibility. **Future optimization planned** to add configurable depth limiting or tree pruning strategies to reduce token usage when calling MCP tools. This will be reviewed and implemented in a later stage once usage patterns are understood.

**Error Cases**:
- Accessibility service not enabled → Error -32001

---

#### `capture_screenshot`

**Description**: Captures a screenshot of the current screen and returns it as base64-encoded JPEG.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "quality": {
      "type": "integer",
      "description": "JPEG quality (1-100)",
      "default": 80
    }
  },
  "required": []
}
```

**Output**:
```json
{
  "format": "jpeg",
  "data": "<base64-encoded JPEG data>",
  "width": 1080,
  "height": 2400
}
```

**Error Cases**:
- MediaProjection permission not granted → Error -32001
- Screenshot capture failed → Error -32603

---

#### `get_current_app`

**Description**: Returns the package name and activity name of the currently focused app.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**:
```json
{
  "packageName": "com.android.calculator2",
  "activityName": ".Calculator"
}
```

---

#### `get_screen_info`

**Description**: Returns screen dimensions, orientation, and DPI.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**:
```json
{
  "width": 1080,
  "height": 2400,
  "densityDpi": 420,
  "orientation": "portrait"
}
```

---

### 2. Touch Action Tools

#### `tap`

**Description**: Performs a single tap at the specified coordinates.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "x": {"type": "number", "description": "X coordinate"},
    "y": {"type": "number", "description": "Y coordinate"}
  },
  "required": ["x", "y"]
}
```

**Output**: Confirmation message.

**Error Cases**:
- Accessibility service not enabled → Error -32001
- Action execution failed → Error -32003

---

#### `long_press`

**Description**: Performs a long press at the specified coordinates.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "x": {"type": "number", "description": "X coordinate"},
    "y": {"type": "number", "description": "Y coordinate"},
    "duration": {"type": "number", "description": "Press duration in ms", "default": 1000}
  },
  "required": ["x", "y"]
}
```

**Output**: Confirmation message.

---

#### `double_tap`

**Description**: Performs a double tap at the specified coordinates.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "x": {"type": "number", "description": "X coordinate"},
    "y": {"type": "number", "description": "Y coordinate"}
  },
  "required": ["x", "y"]
}
```

**Output**: Confirmation message.

---

#### `swipe`

**Description**: Performs a swipe gesture from one point to another.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "x1": {"type": "number", "description": "Start X coordinate"},
    "y1": {"type": "number", "description": "Start Y coordinate"},
    "x2": {"type": "number", "description": "End X coordinate"},
    "y2": {"type": "number", "description": "End Y coordinate"},
    "duration": {"type": "number", "description": "Swipe duration in ms", "default": 300}
  },
  "required": ["x1", "y1", "x2", "y2"]
}
```

**Output**: Confirmation message.

---

#### `scroll`

**Description**: Scrolls in the specified direction.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
    "amount": {"type": "string", "enum": ["small", "medium", "large"], "default": "medium"}
  },
  "required": ["direction"]
}
```

**Output**: Confirmation message.

---

### 3. Element Action Tools

#### `find_elements`

**Description**: Finds UI elements matching the specified criteria.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "by": {"type": "string", "enum": ["text", "content_desc", "resource_id", "class_name"]},
    "value": {"type": "string", "description": "Search value"},
    "exact_match": {"type": "boolean", "default": false}
  },
  "required": ["by", "value"]
}
```

**Output**:
```json
{
  "elements": [
    {
      "id": "node_456",
      "text": "7",
      "bounds": {"left": 50, "top": 800, "right": 250, "bottom": 1000},
      "clickable": true
    }
  ]
}
```

**Error Cases**:
- No elements found → Return empty array (not an error)

---

#### `click_element`

**Description**: Clicks the specified accessibility node.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": {"type": "string", "description": "Node ID from find_elements"}
  },
  "required": ["element_id"]
}
```

**Output**: Confirmation message.

**Error Cases**:
- Element not found (ID invalid or stale) → Error -32002
- Element not clickable → Error -32003

---

#### `long_click_element`

**Description**: Long-clicks the specified accessibility node.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": {"type": "string"}
  },
  "required": ["element_id"]
}
```

**Output**: Confirmation message.

---

#### `set_text`

**Description**: Sets text on an editable accessibility node.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": {"type": "string"},
    "text": {"type": "string"}
  },
  "required": ["element_id", "text"]
}
```

**Output**: Confirmation message.

**Error Cases**:
- Element not editable → Error -32003

---

#### `scroll_to_element`

**Description**: Scrolls to make the specified element visible.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": {"type": "string"}
  },
  "required": ["element_id"]
}
```

**Output**: Confirmation message.

---

### 4. Text Input Tools

#### `input_text`

**Description**: Types text into the currently focused input field or specified element.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string"},
    "element_id": {"type": "string", "description": "Optional: target element ID"}
  },
  "required": ["text"]
}
```

**Output**: Confirmation message.

---

#### `clear_text`

**Description**: Clears text from the focused input field or specified element.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "element_id": {"type": "string", "description": "Optional: target element ID"}
  },
  "required": []
}
```

**Output**: Confirmation message.

---

#### `press_key`

**Description**: Presses a specific key (ENTER, BACK, DEL, etc.).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "key": {"type": "string", "enum": ["ENTER", "BACK", "DEL", "HOME", "TAB", "SPACE"]}
  },
  "required": ["key"]
}
```

**Output**: Confirmation message.

---

### 5. System Action Tools

#### `press_back`

**Description**: Presses the back button (global accessibility action).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: Confirmation message.

---

#### `press_home`

**Description**: Navigates to the home screen.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: Confirmation message.

---

#### `press_recents`

**Description**: Opens the recent apps screen.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: Confirmation message.

---

#### `open_notifications`

**Description**: Pulls down the notification shade.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: Confirmation message.

---

#### `open_quick_settings`

**Description**: Opens the quick settings panel.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**: Confirmation message.

---

### 6. Gesture Tools

#### `pinch`

**Description**: Performs a pinch-to-zoom gesture.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "center_x": {"type": "number", "description": "Center X coordinate"},
    "center_y": {"type": "number", "description": "Center Y coordinate"},
    "scale": {"type": "number", "description": "Scale factor (>1 = zoom in, <1 = zoom out)"},
    "duration": {"type": "number", "description": "Gesture duration in ms", "default": 300}
  },
  "required": ["center_x", "center_y", "scale"]
}
```

**Output**: Confirmation message.

---

#### `custom_gesture`

**Description**: Executes a custom multi-touch gesture defined by path points.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "paths": {
      "type": "array",
      "items": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "x": {"type": "number"},
            "y": {"type": "number"},
            "time": {"type": "number", "description": "Time offset in ms"}
          }
        }
      }
    }
  },
  "required": ["paths"]
}
```

**Output**: Confirmation message.

---

### 7. Utility Tools

#### `get_clipboard`

**Description**: Gets the current clipboard content.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

**Output**:
```json
{
  "text": "clipboard content here"
}
```

---

#### `set_clipboard`

**Description**: Sets clipboard content (useful for paste operations).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string"}
  },
  "required": ["text"]
}
```

**Output**: Confirmation message.

---

#### `wait_for_element`

**Description**: Waits until an element matching criteria appears (with timeout).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "by": {"type": "string", "enum": ["text", "content_desc", "resource_id", "class_name"]},
    "value": {"type": "string"},
    "timeout": {"type": "number", "description": "Timeout in ms", "default": 5000}
  },
  "required": ["by", "value"]
}
```

**Output**: Element details when found.

**Error Cases**:
- Element not found within timeout → Error -32004

---

#### `wait_for_idle`

**Description**: Waits for the UI to become idle (no animations/loading).

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "timeout": {"type": "number", "description": "Timeout in ms", "default": 3000}
  },
  "required": []
}
```

**Output**: Confirmation message when idle.

---

## Android-Specific Conventions

### Service Lifecycle Management

#### Foreground Services

All long-running services (McpServerService, ScreenCaptureService) MUST run as foreground services:

- **Requirement**: Show persistent notification (Android requirement)
- **Notification**: Must include service name, status, and stop action
- **Start**: Use `startForeground()` within 5 seconds of service start
- **Stop**: Call `stopForeground()` before service destruction

#### Service Binding

- **Bound Services**: Use for services with lifecycle tied to client (ScreenCaptureService)
- **Started Services**: Use for independent services (McpServerService)
- **Unbinding**: Always unbind in `onDestroy()` to prevent memory leaks

#### Service Communication

- **From Service to UI**: Use LocalBroadcastManager or Flow/StateFlow
- **From UI to Service**: Use Intent extras or bound service methods
- **Between Services**: Use singleton pattern or IPC (Binder)

### AccessibilityService Best Practices

#### Event Handling

- **Event Types**: Register only for needed event types (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- **Event Processing**: Keep `onAccessibilityEvent()` fast, offload heavy work to coroutines
- **Event Filtering**: Filter events by package name when possible

#### Node Traversal

- **Efficiency**: Cache accessibility tree when possible, don't traverse on every request
- **Memory**: Call `node.recycle()` after use to free native memory
- **Stale Nodes**: Check `node.refresh()` before using cached nodes
- **Thread Safety**: All node operations must happen on main thread

#### Actions

- **Action Execution**: Use `performAction()` and check return value
- **Global Actions**: Use `performGlobalAction()` for system actions (back, home, recents)
- **Gestures**: Use `dispatchGesture()` for complex touch sequences (API 24+)

### Permission Handling

#### Runtime Permissions

- **MediaProjection**: Request via `MediaProjectionManager.createScreenCaptureIntent()`
- **Accessibility**: User must enable manually in Settings (provide deep link)
- **Internet**: Declared in manifest, granted automatically

#### Permission State Checking

- Always check permission state before operations:
  - Accessibility: Check `isAccessibilityServiceEnabled()`
  - MediaProjection: Check `mediaProjection != null`
- Return appropriate MCP error if permission missing

### Background Restrictions

#### Doze Mode

- Foreground services are exempt from Doze restrictions
- Use `PowerManager.isIgnoringBatteryOptimizations()` to check if needed

#### Background Limits (Android 8+)

- Services started with `startForeground()` within 5 seconds are allowed
- Use `JobScheduler` for deferred background work (not needed for this app)

### Memory Management

#### Leak Prevention

- **Context**: Never store Activity context in long-lived objects, use ApplicationContext
- **Service Binding**: Always unbind services in `onDestroy()`
- **Callbacks**: Remove callbacks/listeners in lifecycle cleanup methods
- **Coroutines**: Cancel coroutine scopes in `onDestroy()`

#### Resource Cleanup

- **Accessibility Nodes**: Call `recycle()` after use
- **MediaProjection**: Call `stop()` when done
- **Bitmap**: Recycle large bitmaps after encoding to JPEG
- **Streams**: Use `use {}` for automatic closure

### Threading Rules

#### Main Thread Requirements

- All AccessibilityService operations (node access, actions) MUST run on main thread
- UI operations (Compose, Activity lifecycle) MUST run on main thread
- Use `withContext(Dispatchers.Main) {}` when needed

#### Background Thread Usage

- Network operations (Ktor) automatically run on IO dispatcher
- Screenshot encoding should run on Default dispatcher
- Heavy computation (accessibility tree parsing) should run on Default dispatcher

### Configuration Changes

#### Handling Rotation

- Services are not affected by configuration changes
- MainActivity should use `rememberSaveable` for Compose state
- Use ViewModel to survive configuration changes

---

## Kotlin Coding Standards

### Naming Conventions

#### Classes and Interfaces

- **Classes**: PascalCase (e.g., `McpServerService`, `AccessibilityTreeParser`)
- **Interfaces**: PascalCase, no "I" prefix (e.g., `SettingsRepository`, not `ISettingsRepository`)
- **Data Classes**: PascalCase (e.g., `ServerConfig`, `ElementInfo`)
- **Sealed Classes**: PascalCase (e.g., `ServerStatus`)

#### Functions and Variables

- **Functions**: camelCase (e.g., `captureScreenshot()`, `findElements()`)
- **Variables**: camelCase (e.g., `bearerToken`, `bindingAddress`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `DEFAULT_PORT`, `MAX_RETRY_COUNT`)
- **Private properties**: camelCase with underscore prefix for backing fields (e.g., `_serverStatus`)

#### Packages

- All lowercase, no underscores (e.g., `com.danielealbano.androidremotecontrolmcp.services.accessibility`)

### Null Safety

#### Nullable Types

- Prefer non-null types by default
- Use nullable types (`Type?`) only when null is a valid state
- Avoid `!!` operator (use safe calls `?.` or `let {}` instead)
- Use `require()` or `check()` for preconditions

#### Safe Calls

```kotlin
// Good
val text = node?.text?.toString() ?: "No text"

// Bad
val text = node!!.text!!.toString()
```

#### Elvis Operator

Use for default values:
```kotlin
val port = config.port ?: DEFAULT_PORT
```

### Coroutines Best Practices

#### Structured Concurrency

- Always use `CoroutineScope` (never `GlobalScope`)
- Cancel scope in lifecycle cleanup methods
- Use `viewModelScope` for ViewModels
- Use `lifecycleScope` for Activities/Fragments

#### Dispatchers

- **Dispatchers.Main**: UI operations, AccessibilityService operations
- **Dispatchers.IO**: Network, file I/O, DataStore
- **Dispatchers.Default**: CPU-intensive work (parsing, encoding)

#### Error Handling

```kotlin
try {
    withContext(Dispatchers.IO) {
        // network operation
    }
} catch (e: Exception) {
    Log.e(TAG, "Network error", e)
    return McpError(code = -32603, message = e.message ?: "Unknown error")
}
```

### Code Organization

#### File Structure

1. Package declaration
2. Imports (Android, third-party, project)
3. Class/interface declaration
4. Companion object (constants)
5. Properties (public, then private)
6. Init blocks
7. Public methods
8. Private methods
9. Inner classes

#### Class Size

- Keep classes focused (single responsibility)
- Prefer files under 300 lines
- Extract helper classes when needed

#### Function Length

- Prefer functions under 20 lines
- Extract complex logic into separate functions
- Use meaningful function names (prefer descriptive over short)

### Immutability

#### Prefer val over var

```kotlin
// Good
val config = ServerConfig(port = 8080, binding = "127.0.0.1")

// Bad
var config = ServerConfig(port = 8080, binding = "127.0.0.1")
```

#### Data Classes

- Use `data class` for immutable data
- Use `copy()` for modifications
- Avoid mutable collections in data classes

### Extension Functions

Use for utility functions:
```kotlin
fun Context.isAccessibilityServiceEnabled(): Boolean {
    // implementation
}
```

Don't overuse - prefer member functions for core logic.

### Companion Objects

For constants and factory methods:
```kotlin
companion object {
    private const val TAG = "McpServerService"
    const val DEFAULT_PORT = 8080

    fun create(context: Context): McpServerService {
        // factory method
    }
}
```

---

## UI Design Principles

### Design System

#### Material Design 3

- Use Material Design 3 components (Compose Material3 library)
- Follow Material Design guidelines for spacing, typography, elevation
- Use theme tokens for consistent styling

#### Color Palette

- Define primary, secondary, tertiary colors in `Color.kt`
- Support both light and dark themes
- Ensure sufficient contrast (WCAG AA minimum)
- Use semantic color names (e.g., `surfaceVariant`, not `grey200`)

#### Typography

- Define type scale in `Type.kt` (displayLarge, headlineMedium, bodySmall, etc.)
- Use system fonts or include custom font if needed
- Ensure readability (minimum 12sp, prefer 14sp+ for body text)

### Modern, Cool UI

#### Visual Style

- **Clean**: Minimal clutter, ample whitespace
- **Modern**: Rounded corners, subtle shadows, smooth animations
- **Cool**: Accent colors, glass-morphism effects (if appropriate), micro-interactions

#### Component Style

- **Cards**: Elevated cards for grouped content (server status, configuration)
- **Buttons**: Filled buttons for primary actions, outlined for secondary
- **Icons**: Material Icons or custom icons for visual cues
- **Switches**: Use for toggles (server on/off, auto-start)
- **Text Fields**: Outlined text fields for input (port, token)

### Dark Mode

- Mandatory support for dark theme
- Use dynamic colors (Material You) if appropriate
- Test contrast in both light and dark modes
- Avoid pure white/black (use surface colors)

### Layout

#### Spacing

- Use consistent spacing scale (4dp, 8dp, 16dp, 24dp, 32dp)
- Padding inside components: 16dp standard
- Margin between components: 8dp-16dp

#### Screen Structure

```
HomeScreen
├── TopAppBar (title, settings icon)
├── LazyColumn
│   ├── ServerStatusCard (status, start/stop button)
│   ├── ConfigurationSection
│   │   ├── BindingAddressSelector
│   │   ├── PortInput
│   │   ├── TokenDisplay (with copy button)
│   │   ├── AutoStartToggle
│   │   └── HttpsToggle
│   ├── PermissionsSection (links to enable accessibility, grant screenshot)
│   └── ConnectionInfoCard (IP, port, token for client)
└── FloatingActionButton (optional: quick start/stop)
```

### Accessibility (UI)

- All interactive elements have minimum touch target (48dp)
- Use `contentDescription` for icons/images
- Ensure focus order is logical
- Support TalkBack (screen reader)
- Test with large text sizes

### Animations

- Use subtle animations for state changes (server status transitions)
- Animate button presses (ripple effect)
- Animate card expansions if using expandable cards
- Keep animations short (200-300ms)

### Compose Best Practices

#### Composable Naming

- Use PascalCase for composables (e.g., `ServerStatusCard()`)
- Suffix with noun (not verb): `ServerStatusCard`, not `ShowServerStatus`

#### State Management

- Hoist state to parent composables
- Use `remember` for UI state
- Use `rememberSaveable` for state surviving configuration changes
- Observe ViewModel state with `collectAsState()`

#### Reusability

- Extract reusable components (buttons, cards, inputs)
- Use modifiers for customization, not parameters
- Keep composables small and focused

---

## Testing Strategy

### Unit Tests

#### Framework

- **JUnit 5**: Test framework (`junit-jupiter`)
- **Kotlin Test**: Kotlin assertions and utilities
- **MockK**: Mocking framework for Kotlin
- **Turbine**: Flow testing library

#### Scope

Unit tests MUST cover:
- MCP protocol parsing and response formatting (`McpProtocolHandlerTest`)
- Accessibility tree parsing logic (`AccessibilityTreeParserTest`)
- Element finding algorithms (`ElementFinderTest`)
- Screenshot encoding (`ScreenshotEncoderTest`)
- Network utilities (`NetworkUtilsTest`)
- Settings repository (`SettingsRepositoryTest`)

#### Mocking Strategy

- Mock Android framework classes (AccessibilityNodeInfo, MediaProjection) using MockK
- Mock repositories and services when testing higher-level components
- Use `@MockK`, `@RelaxedMockK` annotations
- Verify interactions with `verify {}`

#### Test Structure

Follow **Arrange-Act-Assert** pattern:
```kotlin
@Test
fun `findElements by text returns matching nodes`() {
    // Arrange
    val finder = ElementFinder()
    val mockNode = mockk<AccessibilityNodeInfo> {
        every { text } returns "Button"
        every { className } returns "android.widget.Button"
    }

    // Act
    val results = finder.findByText("Button", listOf(mockNode))

    // Assert
    assertEquals(1, results.size)
    assertEquals("Button", results[0].text)
}
```

#### Running Unit Tests

```bash
make test-unit
# or
./gradlew test
```

### Integration Tests

#### Framework

- **AndroidX Test**: Instrumented test framework
- **Compose UI Test**: Test Compose UI components
- **MockK**: Mocking framework
- **Hilt Test**: DI for tests

#### Scope

Integration tests MUST cover:
- MainActivity UI interactions (`MainActivityTest`)
- Compose screen rendering and state updates
- ViewModel and repository integration
- Service binding and unbinding

#### Mocking Strategy

- **Mock all Android services**: AccessibilityService, MediaProjection, Ktor server
- **Use real DataStore**: Test with in-memory DataStore
- **Mock network**: No real HTTP requests

#### Test Structure

```kotlin
@HiltAndroidTest
class MainActivityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun whenServerStarted_statusUpdates() {
        // Arrange
        composeTestRule.onNodeWithText("Start Server").performClick()

        // Act & Assert
        composeTestRule.onNodeWithText("Server Running").assertIsDisplayed()
    }
}
```

#### Running Integration Tests

```bash
make test-integration
# or
./gradlew connectedAndroidTest
```

### E2E Tests

#### Framework

- **Testcontainers Kotlin**: Container orchestration
- **Docker Android**: budtmo/docker-android-x86 image
- **JUnit 5**: Test framework
- **OkHttp**: HTTP client for MCP requests

#### Scope

E2E tests MUST cover:
- Full MCP client → server → Android → action flow
- Calculator app interaction (simple, static, verifiable)
- Screenshot capture and validation
- Error handling (permission denied, element not found)

#### Test Scenario: Calculator Test

**Objective**: Verify full MCP control by performing a calculation (7 + 3 = 10) on Android Calculator app.

**Steps**:
1. Start Docker Android container (API 34, x86_64)
2. Install MCP app APK on container
3. Grant accessibility permission via adb
4. Grant MediaProjection permission (simulated via adb)
5. Start MCP server on Android
6. Connect MCP client (test) to server
7. Launch Calculator app
8. Get accessibility tree, verify "Calculator" title
9. Find element "7" button by text
10. Click "7" button
11. Find element "+" button
12. Click "+" button
13. Find element "3" button
14. Click "3" button
15. Find element "=" button
16. Click "=" button
17. Capture screenshot
18. Verify result "10" in accessibility tree
19. Stop MCP server
20. Stop container

**Implementation**:
```kotlin
@Testcontainers
class E2ECalculatorTest {
    @Container
    val androidContainer = GenericContainer("budtmo/docker-android-x86:emulator_14.0")
        .withExposedPorts(5555, 8080)
        .waitingFor(Wait.forLogMessage(".*Boot completed.*", 1))

    @Test
    fun `calculate 7 plus 3 equals 10`() {
        // Setup: Install APK, grant permissions, start MCP server
        // ...

        // MCP client calls
        mcpClient.call("press_home")
        mcpClient.call("tap", mapOf("x" to 540, "y" to 1200)) // Calculator icon

        val tree = mcpClient.call("get_accessibility_tree")
        assertTrue(tree.contains("Calculator"))

        val button7 = mcpClient.call("find_elements", mapOf("by" to "text", "value" to "7"))
        mcpClient.call("click_element", mapOf("element_id" to button7[0].id))

        // ... continue calculation ...

        val resultTree = mcpClient.call("get_accessibility_tree")
        assertTrue(resultTree.contains("10"))
    }
}
```

#### Running E2E Tests

```bash
make test-e2e
# or
./gradlew :e2e-tests:test
```

**Note**: E2E tests are slow (container startup, emulator boot). Run selectively during development, always in CI.

### Test Coverage

- **Target**: Minimum 80% code coverage for unit tests
- **Measurement**: Use Jacoco Gradle plugin
- **Report**: `./gradlew jacocoTestReport`

### Continuous Testing

- Run unit tests on every commit (fast feedback)
- Run integration tests on PR (medium speed)
- Run E2E tests on PR and pre-merge (slow, comprehensive)

---

## Build & Deployment

### Build System

#### Gradle

- **Version**: Gradle 8.x
- **DSL**: Kotlin DSL (`build.gradle.kts`)
- **Version Catalog**: Use `libs.versions.toml` for dependency versions

#### Build Variants

**Debug**:
- Application ID: `com.danielealbano.androidremotecontrolmcp.debug`
- Debuggable: true
- Minify: false
- Default port: 8080
- Default binding: 127.0.0.1
- Logging: Verbose

**Release**:
- Application ID: `com.danielealbano.androidremotecontrolmcp`
- Debuggable: false
- Minify: false (open source, no ProGuard/R8)
- Default port: 8080
- Default binding: 127.0.0.1
- Logging: Info and above

### Makefile

Makefile provides high-level development workflow automation. All common tasks should be accessible via `make <target>`.

(See [Makefile Targets](#makefile-targets) section for full list)

### Versioning

#### Semantic Versioning

Follow semver (MAJOR.MINOR.PATCH):
- **MAJOR**: Breaking changes (incompatible MCP protocol changes)
- **MINOR**: New features (new MCP tools, UI improvements)
- **PATCH**: Bug fixes, performance improvements

#### Version Management

**Version defined in** `gradle.properties`:
```properties
VERSION_NAME=1.0.0
VERSION_CODE=1
```

**Auto-increment** (Makefile target):
```bash
make version-bump-patch  # 1.0.0 → 1.0.1
make version-bump-minor  # 1.0.0 → 1.1.0
make version-bump-major  # 1.0.0 → 2.0.0
```

### APK Signing

#### Debug Signing

- Use default debug keystore (automatic)

#### Release Signing

- Use custom keystore (not checked into git)
- Store keystore path, alias, passwords in `keystore.properties` (gitignored)
- Load in `app/build.gradle.kts`:
```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    val keystoreProperties = Properties()
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
}
```

### CI/CD

#### GitHub Actions

**Workflow** (`.github/workflows/ci.yml`):
- **Trigger**: Push to main, pull requests
- **Jobs**:
  1. **Lint**: Run ktlint/detekt
  2. **Unit Tests**: Run `./gradlew test`, upload coverage report
  3. **Integration Tests**: Run `./gradlew connectedAndroidTest` (requires emulator)
  4. **E2E Tests**: Run `./gradlew :e2e-tests:test` (Docker Android)
  5. **Build**: Build debug and release APKs
  6. **Artifact Upload**: Upload APKs as artifacts

**Emulator in CI**:
Use `reactivecircus/android-emulator-runner` action:
```yaml
- name: Run Integration Tests
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 34
    arch: x86_64
    script: ./gradlew connectedAndroidTest
```

### Deployment

#### Manual Deployment

1. Build release APK: `make build-release`
2. APK location: `app/build/outputs/apk/release/app-release.apk`
3. Install on device: `adb install app-release.apk`

#### GitHub Releases

- Tag commits with version: `git tag v1.0.0`
- Create GitHub release with APK attached
- Include changelog in release notes

---

## Security Practices

### Bearer Token Security

#### Storage

- **SharedPreferences** (standard, not encrypted for now)
- Key: `bearer_token`
- Auto-generated on first launch (UUID.randomUUID())
- User can view/copy/regenerate via UI

#### Token Generation

```kotlin
fun generateBearerToken(): String {
    return UUID.randomUUID().toString()
}
```

#### Token Validation

- Every MCP request must include `Authorization: Bearer <token>` header
- Compare with stored token (constant-time comparison to prevent timing attacks)
- Return `401 Unauthorized` if invalid/missing

### HTTPS

#### Requirement

- **Mandatory**: MCP server MUST use HTTPS (not HTTP)
- HTTPS is always enabled, not optional
- User can choose certificate source: auto-generated or custom

#### Certificate Management Options

**Option 1: Auto-Generated Self-Signed Certificate**
- Generated on first launch using Bouncy Castle or Java KeyStore API
- User can configure hostname (e.g., "android-mcp.local")
- Certificate valid for 1 year
- Stored in app-private storage
- User can regenerate certificate with new hostname if needed

**Option 2: Custom Certificate Upload**
- User uploads existing certificate (.p12 or .pfx file)
- User provides certificate password
- Supports proper CA-signed certificates for production use
- Certificate stored in app-private storage

#### Certificate Generation (Auto-Generated)

```kotlin
fun generateSelfSignedCertificate(hostname: String): KeyStore {
    // Generate RSA 2048-bit key pair
    // Create X.509 certificate with hostname as CN and SAN
    // Certificate valid for 1 year from generation
    // Store in KeyStore with alias "mcp-server"
}
```

#### Certificate Import (Custom Upload)

```kotlin
fun importCustomCertificate(certificateBytes: ByteArray, password: String): KeyStore {
    // Load .p12/.pfx certificate
    // Validate certificate is not expired
    // Store in app KeyStore
}
```

#### Ktor HTTPS Configuration

```kotlin
embeddedServer(Netty, environment) {
    sslConnector(
        keyStore = loadKeyStore(), // Auto-generated or custom
        keyAlias = "mcp-server",
        keyStorePassword = { keystorePassword.toCharArray() },
        privateKeyPassword = { privateKeyPassword.toCharArray() }
    ) {
        port = config.port
        host = config.bindingAddress
    }
}
```

### Network Security

#### Binding Address

- **Default**: `127.0.0.1` (localhost only, requires ADB port forwarding)
  - Most secure option
  - Only accessible via `adb forward tcp:8080 tcp:8080`
  - Recommended for most use cases

- **Network mode**: `0.0.0.0` (binds to all network interfaces)
  - **Security Warning**: Display warning dialog when user selects this option
  - **WiFi exposure**: Server is accessible to anyone on the same WiFi network
  - **Mobile data exposure**: Generally NOT accessible from public internet due to CGNAT (Carrier-Grade NAT) used by mobile carriers
  - **Hotspot/Tethering exposure**: Accessible to devices connected to this device's hotspot
  - **Use case**: For remote access within trusted private networks only

#### Network Exposure Matrix

| Connection Type | Binding: 127.0.0.1 | Binding: 0.0.0.0 |
|-----------------|-------------------|------------------|
| Mobile Data (4G/5G) | Not accessible (localhost only) | Not accessible from internet (CGNAT), but accessible within carrier network |
| WiFi (Private) | Not accessible (localhost only) | Accessible to devices on same WiFi network |
| WiFi (Public) | Not accessible (localhost only) | **DANGER**: Accessible to anyone on public WiFi |
| USB Tethered | Not accessible (localhost only) | Accessible to tethered device |
| Device as Hotspot | Not accessible (localhost only) | Accessible to connected hotspot clients |
| ADB Port Forward | Accessible via host machine | Accessible via host machine |

#### Security Warning UI

When user selects `0.0.0.0` binding, display warning:
```
⚠️ Network Exposure Warning

Binding to 0.0.0.0 makes the MCP server accessible to:
• All devices on your current WiFi network
• Devices connected to your hotspot
• Potentially other devices depending on network configuration

This is a SECURITY RISK on public/untrusted networks.

Only use this option on trusted private networks.

Continue?  [Cancel] [I Understand, Continue]
```

#### Firewall

- No built-in firewall (rely on Android system firewall)
- Document recommendation: Use on trusted networks only
- Bearer token provides authentication layer, but network exposure is still a concern

### Permission Security

#### Minimal Permissions

Only request necessary permissions:
- `INTERNET` (for MCP server)
- `FOREGROUND_SERVICE` (for long-running services)
- `RECEIVE_BOOT_COMPLETED` (for auto-start)
- Accessibility Service (user-granted via Settings)
- MediaProjection (user-granted via dialog)

#### Permission Justification

- Display clear explanations in UI before requesting permissions
- Link to Android Settings for Accessibility Service

### Code Security

#### No Secrets in Code

- No hardcoded tokens, keys, passwords
- All secrets stored in SharedPreferences or injected at runtime

#### Input Validation

- Validate all MCP request parameters (type, range, format)
- Sanitize inputs before using in AccessibilityService operations
- Prevent injection attacks (SQL injection not applicable, but validate element IDs)

#### Error Messages

- Don't leak sensitive information in error messages
- Don't expose internal paths, class names, stack traces to clients (debug mode only)

---

## Default Configuration

### Server Defaults

- **Port**: `8080`
- **Binding Address**: `127.0.0.1` (localhost)
- **Bearer Token**: Auto-generated UUID on first launch
- **HTTPS**: Always enabled (mandatory)
  - **Certificate**: Auto-generated self-signed on first launch
  - **Hostname**: "android-mcp.local" (default, user can change)
  - **Certificate Validity**: 1 year
  - **User Options**: Auto-generate (with custom hostname) OR upload custom certificate
- **Auto-start on Boot**: Disabled (user must enable)

### MCP Defaults

- **Screenshot Quality**: 80 (JPEG quality 1-100)
- **Timeout**: 5000ms (for wait_for_element, wait_for_idle)
- **Long Press Duration**: 1000ms
- **Swipe Duration**: 300ms
- **Gesture Duration**: 300ms
- **Scroll Amount**: "medium" (translates to 50% of screen height/width)

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

### Environment & Dependencies

#### `check-deps`
**Description**: Check for required tools and report missing dependencies.

**Checks**:
- Android SDK (via `$ANDROID_HOME`)
- Java (version 17+)
- Gradle (version 8+)
- adb (Android Debug Bridge)
- Docker (for E2E tests)

**Output**: List of missing dependencies with installation instructions.

```bash
make check-deps
```

---

### Build Targets

#### `build`
**Description**: Build debug APK.

**Command**: `./gradlew assembleDebug`

**Output**: `app/build/outputs/apk/debug/app-debug.apk`

```bash
make build
```

---

#### `build-release`
**Description**: Build release APK (signed if keystore configured).

**Command**: `./gradlew assembleRelease`

**Output**: `app/build/outputs/apk/release/app-release.apk`

```bash
make build-release
```

---

#### `clean`
**Description**: Clean build artifacts.

**Command**: `./gradlew clean`

```bash
make clean
```

---

### Testing Targets

#### `test-unit`
**Description**: Run unit tests.

**Command**: `./gradlew test`

**Output**: Test results in `app/build/reports/tests/testDebugUnitTest/index.html`

```bash
make test-unit
```

---

#### `test-integration`
**Description**: Run integration tests (requires connected device/emulator).

**Command**: `./gradlew connectedAndroidTest`

**Output**: Test results in `app/build/reports/androidTests/connected/index.html`

```bash
make test-integration
```

---

#### `test-e2e`
**Description**: Run E2E tests (Docker Android).

**Command**: `./gradlew :e2e-tests:test`

**Output**: Test results in `e2e-tests/build/reports/tests/test/index.html`

```bash
make test-e2e
```

---

#### `test`
**Description**: Run all tests (unit + integration + E2E).

**Command**: Runs `test-unit`, `test-integration`, `test-e2e` sequentially.

```bash
make test
```

---

#### `coverage`
**Description**: Generate code coverage report (Jacoco).

**Command**: `./gradlew jacocoTestReport`

**Output**: Coverage report in `app/build/reports/jacoco/index.html`

```bash
make coverage
```

---

### Linting Targets

#### `lint`
**Description**: Run all linters (ktlint/detekt).

**Command**: `./gradlew ktlintCheck detekt`

```bash
make lint
```

---

#### `lint-fix`
**Description**: Auto-fix linting issues.

**Command**: `./gradlew ktlintFormat`

```bash
make lint-fix
```

---

### Device Management Targets

#### `install`
**Description**: Install debug APK on connected device/emulator.

**Command**: `./gradlew installDebug`

**Prerequisite**: Device connected via adb.

```bash
make install
```

---

#### `install-release`
**Description**: Install release APK on connected device/emulator.

**Command**: `./gradlew installRelease`

```bash
make install-release
```

---

#### `uninstall`
**Description**: Uninstall app from connected device/emulator.

**Command**: `adb uninstall com.danielealbano.androidremotecontrolmcp`

```bash
make uninstall
```

---

#### `grant-permissions`
**Description**: Grant necessary permissions via adb (accessibility, MediaProjection simulation).

**Commands**:
```bash
# Note: Accessibility service cannot be enabled via adb without root
# This target provides instructions to user
echo "Please enable Accessibility Service manually:"
echo "Settings > Accessibility > MCP Remote Control > Enable"

# MediaProjection also requires user interaction (cannot be granted via adb)
echo "Please grant MediaProjection permission when prompted in app"
```

**Note**: Actual permission granting requires user interaction. This target provides instructions.

```bash
make grant-permissions
```

---

#### `start-server`
**Description**: Start MCP server on device via adb (launches MainActivity).

**Command**: `adb shell am start -n com.danielealbano.androidremotecontrolmcp/.ui.MainActivity`

```bash
make start-server
```

---

#### `forward-port`
**Description**: Set up adb port forwarding (device 8080 → host 8080).

**Command**: `adb forward tcp:8080 tcp:8080`

**Usage**: Allows connecting to MCP server (bound to 127.0.0.1 on device) from host machine.

```bash
make forward-port
```

---

### Emulator Targets

#### `setup-emulator`
**Description**: Create AVD (Android Virtual Device) if not exists.

**Command**:
```bash
avdmanager create avd -n mcp_test_emulator \
  -k "system-images;android-34;google_apis;x86_64" \
  --device "pixel_6"
```

**Note**: Requires Android SDK system image installed.

```bash
make setup-emulator
```

---

#### `start-emulator`
**Description**: Start emulator in background.

**Command**: `emulator -avd mcp_test_emulator -no-snapshot -no-window &`

**Note**: Emulator runs headless. Use `-no-window` to avoid GUI.

```bash
make start-emulator
```

---

#### `stop-emulator`
**Description**: Stop running emulator.

**Command**: `adb -s emulator-5554 emu kill`

```bash
make stop-emulator
```

---

### Logging & Debugging Targets

#### `logs`
**Description**: Show app logs via adb logcat (filtered by tag).

**Command**: `adb logcat -s MCP:* AndroidRemoteControl:*`

```bash
make logs
```

---

#### `logs-clear`
**Description**: Clear logcat buffer.

**Command**: `adb logcat -c`

```bash
make logs-clear
```

---

### Versioning Targets

#### `version-bump-patch`
**Description**: Increment patch version (1.0.0 → 1.0.1).

**Implementation**: Sed script to update `gradle.properties`.

```bash
make version-bump-patch
```

---

#### `version-bump-minor`
**Description**: Increment minor version (1.0.0 → 1.1.0).

```bash
make version-bump-minor
```

---

#### `version-bump-major`
**Description**: Increment major version (1.0.0 → 2.0.0).

```bash
make version-bump-major
```

---

### All-in-One Targets

#### `all`
**Description**: Run full workflow (clean, build, lint, test).

**Command**: Runs `clean`, `build`, `lint`, `test-unit` sequentially.

```bash
make all
```

---

#### `ci`
**Description**: Run CI workflow (what GitHub Actions runs).

**Command**: Runs `check-deps`, `lint`, `test-unit`, `test-integration`, `test-e2e`, `build-release`.

```bash
make ci
```

---

### Help Target

#### `help`
**Description**: Display list of available targets with descriptions.

```bash
make help
```

---

**End of PROJECT.md**
