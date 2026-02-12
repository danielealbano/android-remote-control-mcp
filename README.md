# Android Remote Control MCP

[![CI](https://github.com/danielealbano/android-remote-control-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/danielealbano/android-remote-control-mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

An Android application that runs as an **MCP (Model Context Protocol) server**, enabling AI models to **fully control an Android device** remotely using accessibility services and screenshot capture.

The app runs directly on your Android device (or emulator) and exposes an HTTP server (with optional HTTPS) implementing the MCP protocol. AI models like Claude can connect to it and interact with any app on the device — reading UI elements, tapping buttons, typing text, swiping, capturing screenshots, and more.

---

## Features

### MCP Server
- HTTP server running directly on Android (Ktor + Netty), with optional HTTPS
- JSON-RPC 2.0 protocol (MCP specification compliant)
- Bearer token authentication
- Auto-generated self-signed TLS certificates (or custom certificate upload)
- Configurable binding: localhost (127.0.0.1) or network (0.0.0.0)
- Auto-start on boot
- Health check endpoint (`GET /health`)

### 29 MCP Tools across 7 Categories

| Category | Tools | Description |
|----------|-------|-------------|
| **Screen Introspection** (4) | `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` | Read UI state and capture screenshots |
| **System Actions** (6) | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | Global device actions and log retrieval |
| **Touch Actions** (5) | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | Coordinate-based touch interactions |
| **Gestures** (2) | `pinch`, `custom_gesture` | Multi-touch and complex gestures |
| **Element Actions** (5) | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | Accessibility node-based interactions |
| **Text Input** (3) | `input_text`, `clear_text`, `press_key` | Keyboard input and text manipulation |
| **Utilities** (4) | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle` | Helper tools for automation |

See [docs/MCP_TOOLS.md](docs/MCP_TOOLS.md) for full tool documentation with input/output schemas and examples.

### Android App
- Material Design 3 configuration UI with dark mode
- Server status monitoring (running/stopped)
- Connection info display (IP, port, token)
- Permission management (Accessibility, MediaProjection)
- Server log viewer

---

## Requirements

### For Building
- **JDK 17** (e.g., [Eclipse Temurin](https://adoptium.net/))
- **Android SDK** with API 34 (Android 14)
- **Gradle** 8.x (wrapper included, no global install needed)

### For Running
- Android device or emulator running **Android 8.0+** (API 26+), targeting **Android 14** (API 34)
- **adb** (Android Debug Bridge) for device/emulator management

### For E2E Tests
- **Docker** (for `budtmo/docker-android-x86` emulator image)

Check all dependencies:
```bash
make check-deps
```

---

## Quick Start

### 1. Build the App

```bash
git clone https://github.com/danielealbano/android-remote-control-mcp.git
cd android-remote-control-mcp
make build
```

### 2. Install on Device/Emulator

```bash
# Start an emulator (if no device connected)
make setup-emulator
make start-emulator

# Install the debug APK
make install
```

### 3. Configure Permissions

1. **Enable Accessibility Service**: Open the app, tap "Enable Accessibility Service", and toggle it on in Android Settings.
2. **Grant Screen Capture**: When prompted by the app, grant the MediaProjection permission.

### 4. Start the MCP Server

Tap the "Start Server" button in the app. The server starts on `http://127.0.0.1:8080` by default (HTTPS is disabled by default).

### 5. Connect from Host Machine

Set up port forwarding (if server is bound to localhost):
```bash
make forward-port
```

Test the connection:
```bash
curl http://localhost:8080/health
```

Expected response:
```json
{"status": "healthy", "version": "1.0.0", "server": "running"}
```

### 6. Make MCP Tool Calls

```bash
# List available tools
curl -X POST http://localhost:8080/mcp/v1/tools/list \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# Get the accessibility tree
curl -X POST http://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_accessibility_tree","arguments":{}}}'

# Tap at coordinates
curl -X POST http://localhost:8080/mcp/v1/tools/call \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tap","arguments":{"x":540,"y":1200}}}'
```

The bearer token is displayed in the app's connection info section. You can copy it directly from the app.

---

## Building

### Debug Build

```bash
make build
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build

```bash
make build-release
# APK: app/build/outputs/apk/release/app-release.apk
```

For signed release builds, create `keystore.properties` in the project root:
```properties
storeFile=path/to/your.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### Clean Build

```bash
make clean
```

---

## Testing

### Unit Tests

```bash
make test-unit
```

Runs JUnit 5 unit tests with MockK for mocking. Tests cover MCP protocol handling, accessibility tree parsing, element finding, screenshot encoding, settings repository, network utilities, and all 29 MCP tool handlers.

### Integration Tests

```bash
make test-integration
```

Requires a connected device or running emulator. Tests cover MainActivity UI interactions, Compose rendering, ViewModel-repository integration, and service binding.

### E2E Tests

```bash
make test-e2e
```

Requires Docker. Starts a full Android emulator inside Docker, installs the app, and performs real MCP tool calls. Includes:
- **Calculator test**: 7 + 3 = 10 via MCP tools (verifies full stack)
- **Screenshot test**: Capture with different quality settings
- **Error handling test**: Authentication, unknown tools, invalid params

### All Tests

```bash
make test
```

### Code Coverage

```bash
make coverage
```

Generates a Jacoco HTML report at `app/build/reports/jacoco/jacocoTestReport/html/index.html`. Minimum coverage target: 80%.

---

## Architecture

The application is a **service-based Android app** with four main components:

1. **AccessibilityService** - UI introspection and action execution via Android Accessibility APIs
2. **ScreenCaptureService** - Screenshot capture via MediaProjection
3. **McpServerService** - Foreground service running the Ktor HTTP/HTTPS server
4. **MainActivity** - Jetpack Compose UI for configuration and control

```
MCP Client (AI Model)
    |
    | HTTP/HTTPS + Bearer Token
    v
McpServerService (Ktor)
    |
    |--- McpProtocolHandler (JSON-RPC 2.0)
    |       |
    |       |--- 29 MCP Tool Handlers
    |
    |--- AccessibilityService
    |       |--- AccessibilityTreeParser
    |       |--- ElementFinder
    |       |--- ActionExecutor
    |
    |--- ScreenCaptureService
            |--- MediaProjection
            |--- ScreenshotEncoder
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

---

## Configuration

### Server Settings (via App UI)

| Setting | Default | Description |
|---------|---------|-------------|
| Port | `8080` | HTTP/HTTPS server port |
| Binding Address | `127.0.0.1` | `127.0.0.1` (localhost, use with adb port forwarding) or `0.0.0.0` (network, all interfaces) |
| Bearer Token | Auto-generated UUID | Authentication token for MCP requests |
| HTTPS | Disabled | Enable HTTPS with auto-generated self-signed certificate (configurable hostname) or upload custom .p12/.pfx |
| Auto-start on Boot | Disabled | Start MCP server automatically when device boots |

### Using with adb Port Forwarding (Recommended)

When the server is bound to `127.0.0.1` (default, most secure):

```bash
# Forward device port to host
adb forward tcp:8080 tcp:8080

# Connect from host
curl http://localhost:8080/health
```

### Using over Network

When the server is bound to `0.0.0.0`:

1. Find the device's IP address (shown in the app's connection info)
2. Connect directly: `curl http://DEVICE_IP:8080/health`

**Warning**: Binding to `0.0.0.0` exposes the server to all devices on the same network. Only use on trusted private networks.

---

## Security

### HTTPS (Optional, Disabled by Default)
- HTTPS can be enabled in the app settings for encrypted TLS communication
- When enabled, uses auto-generated self-signed certificates (or upload your own CA-signed certificate)
- Certificate is stored in app-private storage
- Server defaults to HTTP; enable HTTPS when operating on untrusted networks

### Bearer Token Authentication
- Every MCP request requires `Authorization: Bearer <token>` header
- Token is auto-generated on first launch (UUID)
- Token can be viewed, copied, and regenerated in the app
- Constant-time comparison prevents timing attacks

### Binding Address
- **Default `127.0.0.1`**: Only accessible via adb port forwarding (most secure)
- **Optional `0.0.0.0`**: Accessible over network (use only on trusted networks)
- Security warning dialog displayed when switching to network mode

### Permissions
- **Accessibility Service**: Required for UI introspection and actions (user must enable manually)
- **MediaProjection**: Required for screenshots (one-time user grant)
- **Internet**: For running the HTTP/HTTPS server
- **Foreground Service**: For keeping services alive in background
- No root access required

---

## Linting

```bash
# Check for issues
make lint

# Auto-fix issues
make lint-fix
```

Uses ktlint for code style and detekt for static analysis.

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Make your changes following the project conventions (see [docs/PROJECT.md](docs/PROJECT.md))
4. Ensure all checks pass: `make lint && make test-unit && make build`
5. Commit with descriptive messages (e.g., `feat: add new MCP tool for ...`)
6. Open a pull request

### Development Conventions

- **Language**: Kotlin with Android (Jetpack Compose, Ktor)
- **Architecture**: Service-based with SOLID principles
- **Testing**: JUnit 5 + MockK (unit), AndroidX Test (integration), Testcontainers (E2E)
- **Linting**: ktlint + detekt
- **DI**: Hilt (Dagger-based)

See [docs/PROJECT.md](docs/PROJECT.md) for the complete project bible.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
