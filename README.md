# Android Remote Control MCP

An Android application that runs as an MCP (Model Context Protocol) server, enabling AI models to fully control an Android device remotely using accessibility services and screenshot capture.

## Features

- MCP server running directly on Android device over HTTP (with optional HTTPS)
- Full UI introspection via Android Accessibility Services
- Screenshot capture via MediaProjection
- Coordinate-based and element-based touch interactions
- Text input and keyboard actions
- System actions (back, home, recents, notifications)
- Advanced gesture support (swipe, pinch, custom gestures)
- Bearer token authentication
- Configurable binding address (localhost or network)
- Auto-start on boot
- Material Design 3 configuration UI with dark mode

## Requirements

- Android device or emulator running Android 8.0+ (API 26+), targeting Android 14 (API 34)
- JDK 17
- Android SDK with API 34
- Docker (for E2E tests only)

## Quick Start

1. Clone the repository:
   ```bash
   git clone https://github.com/danielealbano/android-remote-control-mcp.git
   cd android-remote-control-mcp
   ```

2. Check dependencies:
   ```bash
   make check-deps
   ```

3. Build the debug APK:
   ```bash
   make build
   ```

4. Install on a connected device/emulator:
   ```bash
   make install
   ```

5. Open the app and follow the setup instructions:
   - Enable the Accessibility Service in Android Settings
   - Grant screen capture permission
   - Start the MCP server

6. Connect your MCP client:
   ```bash
   # If using localhost binding (default), set up port forwarding:
   make forward-port
   # Then connect to http://localhost:8080
   ```

## Building

```bash
make build           # Build debug APK
make build-release   # Build release APK
make clean           # Clean build artifacts
```

## Testing

```bash
make test-unit         # Run unit tests
make test-integration  # Run integration tests (requires device/emulator)
make test-e2e          # Run E2E tests (requires Docker)
make test              # Run all tests
make coverage          # Generate coverage report
```

## Linting

```bash
make lint       # Run ktlint and detekt
make lint-fix   # Auto-fix linting issues
```

## MCP Tools

The server exposes the following MCP tool categories:

| Category | Tools | Description |
|----------|-------|-------------|
| Screen Introspection | `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` | Query device screen state |
| Touch Actions | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | Coordinate-based interactions |
| Element Actions | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | Accessibility node interactions |
| Text Input | `input_text`, `clear_text`, `press_key` | Keyboard input |
| System Actions | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | Global device actions |
| Gestures | `pinch`, `custom_gesture` | Advanced multi-touch gestures |
| Utilities | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle` | Helper tools |

See [MCP_TOOLS.md](docs/MCP_TOOLS.md) for detailed tool documentation.

## Architecture

The application is service-based with four main components:

- **McpServerService** - Foreground service running Ktor HTTP/HTTPS server
- **McpAccessibilityService** - Android AccessibilityService for UI introspection and actions
- **ScreenCaptureService** - Foreground service managing MediaProjection for screenshots
- **MainActivity** - Jetpack Compose UI for configuration and control

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Port | 8080 | HTTP/HTTPS server port |
| Binding Address | 127.0.0.1 | Localhost only (use 0.0.0.0 for network access) |
| Bearer Token | Auto-generated | Authentication token for MCP clients |
| HTTPS | Disabled by default | Optional; self-signed certificate (auto-generated) or custom when enabled |
| Auto-start | Disabled | Start MCP server on device boot |

## Security

- **HTTPS (optional)**: When enabled, all connections use TLS encryption (disabled by default)
- **Bearer token authentication**: Every MCP request requires a valid token
- **Localhost by default**: Server binds to 127.0.0.1 (requires ADB port forwarding)
- **No root required**: Application uses standard Android APIs only
- **Minimal permissions**: Only requests permissions that are strictly necessary

## Development

```bash
make help              # Show all available targets
make check-deps        # Verify development environment
make setup-emulator    # Create test emulator
make start-emulator    # Start emulator (headless)
make logs              # View app logs
```

## Contributing

Contributions are welcome. Please follow the coding conventions defined in [PROJECT.md](docs/PROJECT.md) and the development workflow defined in [TOOLS.md](docs/TOOLS.md).

## License

MIT License. See [LICENSE](LICENSE) for details.
