# Application Architecture

This document describes the runtime architecture of the Android Remote Control MCP application.
It focuses on **how** components interact at runtime rather than **what** they are
(for design decisions and specifications, see [PROJECT.md](PROJECT.md)).

---

## Component Diagram

```
+------------------------------------------------------------+
|                    Android Device                           |
|                                                            |
|  +------------------+    +---------------------------+     |
|  |   MainActivity   |    |  McpAccessibilityService  |     |
|  |  (Compose UI)    |    |  (System-managed)         |     |
|  |                  |    |                           |     |
|  |  MainViewModel   |    |  - TreeParser             |     |
|  |  - Settings      |    |  - ElementFinder          |     |
|  |  - Status        |    |  - ActionExecutor         |     |
|  +-------+----------+    +----------+----------------+     |
|          |                           |                     |
|          | StateFlow                 | Singleton            |
|          | (status)                  | (companion object)   |
|          |                           |                     |
|  +-------v---------------------------v----------------+    |
|  |              McpServerService                      |    |
|  |              (Foreground Service)                   |    |
|  |                                                    |    |
|  |  +--------------------------------------------+   |    |
|  |  |            McpServer (Ktor)                 |   |    |
|  |  |  HTTP :8080 (HTTPS optional)                |   |    |
|  |  |                                            |   |    |
|  |  |  GET  /health           (unauthenticated)  |   |    |
|  |  |  POST /mcp/v1/initialize (bearer token)    |   |    |
|  |  |  GET  /mcp/v1/tools/list (bearer token)    |   |    |
|  |  |  POST /mcp/v1/tools/call (bearer token)    |   |    |
|  |  |                                            |   |    |
|  |  |  BearerTokenAuth -> McpProtocolHandler     |   |    |
|  |  |                    -> ToolHandlers          |   |    |
|  |  +--------------------------------------------+   |    |
|  |                                                    |    |
|  +----+----------------------------------------------+    |
|       |                                                    |
|       | Bound Service                                      |
|       |                                                    |
|  +----v-------------------------------------------+       |
|  |          ScreenCaptureService                   |       |
|  |          (Foreground Service)                    |       |
|  |                                                 |       |
|  |  MediaProjection -> ImageReader -> JPEG encode  |       |
|  +-------------------------------------------------+       |
|                                                            |
+------------------------------------------------------------+
         |
         | HTTP (or HTTPS/TLS 1.2+ if enabled)
         |
    +----v----+
    |  MCP    |
    |  Client |
    |  (AI)   |
    +---------+
```

---

## Service Lifecycle

### Startup Sequence

1. **User opens app** -> `MainActivity.onCreate()` renders Compose UI
2. **User enables accessibility** -> System starts `McpAccessibilityService`
   - `onServiceConnected()` stores `instance` in companion object
   - Service remains running until disabled in Settings
3. **User grants MediaProjection** -> Permission stored for `ScreenCaptureService`
4. **User taps "Start Server"** -> `MainViewModel.startServer()` called
   - Sends `ACTION_START` intent to `McpServerService`
   - `McpServerService.onStartCommand()`:
     a. Calls `startForeground()` with notification (within 5 seconds)
     b. Reads `ServerConfig` from `SettingsRepository`
     c. Gets/creates SSL keystore from `CertificateManager` (only if HTTPS enabled)
     d. Creates `McpServer` with config, keystore, and `McpProtocolHandler`
     e. Binds to `ScreenCaptureService` via `ServiceConnection`
     f. Starts Ktor server (HTTP by default, HTTPS if enabled)
     g. Updates `ServerStatus.Running` via companion-level StateFlow

### Shutdown Sequence

1. **User taps "Stop Server"** -> `MainViewModel.stopServer()` called
   - Sends `ACTION_STOP` intent to `McpServerService`
   - `McpServerService.onDestroy()`:
     a. Updates `ServerStatus.Stopping` via companion-level StateFlow
     b. Stops Ktor server gracefully (1s grace + 5s timeout)
     c. Unbinds from `ScreenCaptureService`
     d. Cancels coroutine scope
     e. Clears singleton instance
     f. Updates `ServerStatus.Stopped` via companion-level StateFlow

### Auto-Start on Boot

1. Device boots -> Android delivers `BOOT_COMPLETED` broadcast
2. `BootCompletedReceiver.onReceive()`:
   a. Reads auto-start setting from `SettingsRepository`
   b. If enabled: starts `McpServerService` via `startForegroundService()`
   c. If disabled: does nothing

---

## Threading Model

### Thread Assignments

| Thread/Dispatcher     | Responsibilities                                        |
|-----------------------|---------------------------------------------------------|
| Main Thread           | Compose UI, Activity lifecycle, AccessibilityService    |
|                       | node operations, `onAccessibilityEvent()`               |
| Dispatchers.IO        | DataStore reads/writes, Ktor server startup, network I/O|
| Dispatchers.Default   | Screenshot JPEG encoding, accessibility tree parsing    |
| Ktor Netty threads    | HTTP request handling (NIO event loop)                  |

### Coroutine Scopes

| Component             | Scope                      | Lifecycle                    |
|-----------------------|---------------------------|------------------------------|
| MainViewModel         | `viewModelScope`          | ViewModel lifecycle          |
| McpServerService      | Custom `CoroutineScope`   | Service onCreate to onDestroy|
| McpAccessibilityService| Custom `CoroutineScope`  | Service lifecycle            |
| ScreenCaptureService  | Custom `CoroutineScope`   | Service lifecycle            |

### Thread Safety

- `McpProtocolHandler.tools` registry: `ConcurrentHashMap` (lock-free reads)
- `McpAccessibilityService.instance`: `@Volatile` singleton
- `McpServer.running`: `AtomicBoolean`
- Accessibility node access: Must be on main thread (Android requirement)

---

## Data Flow: MCP Request

```
MCP Client
    |
    | HTTP(S) POST /mcp/v1/tools/call
    | Authorization: Bearer <token>
    | { "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    |   "params": { "name": "tap", "arguments": { "x": 500, "y": 1000 } } }
    v
Ktor Netty (IO threads)
    |
    | ContentNegotiation: deserialize JSON -> JsonRpcRequest
    v
BearerTokenAuth Plugin
    |
    | Extract "Authorization: Bearer <token>" header
    | Constant-time compare with stored token
    | If invalid -> respond 401, stop pipeline
    v
Route Handler (/mcp/v1/tools/call)
    |
    | call.receive<JsonRpcRequest>()
    v
McpProtocolHandler.handleRequest()
    |
    | Route by method ("tools/call")
    | Extract tool name ("tap") and arguments
    | Look up ToolHandler from registry
    v
ToolHandler.execute(params)  [e.g., TouchActionTools]
    |
    | withContext(Dispatchers.Main) {
    |     McpAccessibilityService.instance?.dispatchGesture(...)
    | }
    v
AccessibilityService (Main Thread)
    |
    | Performs gesture on Android UI
    | Returns success/failure
    v
ToolHandler returns JsonElement result
    |
    v
McpProtocolHandler wraps in JsonRpcResponse
    |
    v
Ktor Netty
    |
    | ContentNegotiation: serialize JsonRpcResponse -> JSON
    | Respond with HTTP 200
    v
MCP Client receives response
```

---

## Security Model

### HTTPS (Optional Transport Security)

- When HTTPS is enabled, all traffic encrypted with TLS 1.2+
- Self-signed or custom certificate (when HTTPS is enabled)
- HTTP by default; HTTPS available as optional toggle in settings
- Certificate stored in app-private directory

### Bearer Token (Authentication)

- Every `/mcp/*` request requires `Authorization: Bearer <token>` header
- Token validated with constant-time comparison (prevents timing attacks)
- Token auto-generated (UUID) on first launch, user can regenerate
- Token stored in DataStore (app-private)

### Network Binding (Exposure Control)

- Default: `127.0.0.1` (localhost only, requires `adb forward`)
- Optional: `0.0.0.0` (all interfaces, with security warning)
- No external firewall; relies on Android's app sandbox and bearer token

---

## Configuration Flow

```
User (UI)
    |
    v
MainViewModel
    |
    | updatePort(), updateBindingAddress(), etc.
    v
SettingsRepository (interface)
    |
    v
SettingsRepositoryImpl (DataStore<Preferences>)
    |
    | Persists to DataStore file
    | Emits via serverConfig: Flow<ServerConfig>
    v
McpServerService (reads on start)
    |
    | config = settingsRepository.serverConfig.first()
    v
McpServer (uses config for Ktor setup)
```

Settings are read at server start time. Changing settings while the server is
running requires a restart (UI disables config editing when server is running).

---

## Permission Model

| Permission               | Type          | How Granted                        | Required For              |
|--------------------------|---------------|------------------------------------|---------------------------|
| INTERNET                 | Normal        | Auto-granted (manifest)            | HTTP server               |
| FOREGROUND_SERVICE       | Normal        | Auto-granted (manifest)            | Foreground services       |
| RECEIVE_BOOT_COMPLETED   | Normal        | Auto-granted (manifest)            | Auto-start on boot        |
| POST_NOTIFICATIONS       | Runtime (13+) | System dialog                      | Foreground notifications  |
| Accessibility Service    | Special       | User enables in Settings           | UI introspection/actions  |
| MediaProjection          | Special       | User confirms system dialog        | Screenshots               |

---

**End of ARCHITECTURE.md**
