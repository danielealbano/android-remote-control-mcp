# Application Architecture

This document describes the runtime architecture of the Android Remote Control MCP application.
It focuses on **how** components interact at runtime rather than **what** they are
(for design decisions and specifications, see [PROJECT.md](PROJECT.md)).

---

## Component Diagram

```mermaid
graph TB
    Client["MCP Client (AI)"]
    Client -->|"HTTP or HTTPS/TLS 1.2+"| McpServerSvc

    subgraph Device["Android Device"]
        subgraph MainAct["MainActivity (Compose UI)"]
            VM["MainViewModel"]
            VM --- Settings["Settings"]
            VM --- Status["Status"]
        end

        subgraph AccSvc["McpAccessibilityService (System-managed)"]
            TreeParser["AccessibilityTreeParser"]
            ElemFinder["ElementFinder"]
            ActionExec["ActionExecutor"]
            ScreenEnc["ScreenshotEncoder"]
            TypeInputCtrl["TypeInputController"]
        end

        subgraph StorageSvc["Storage & App Services"]
            StorageProv["StorageLocationProvider"]
            FileOps["FileOperationProvider"]
            AppMgr["AppManager"]
        end

        subgraph McpServerSvc["McpServerService (Foreground Service)"]
            subgraph Ktor["McpServer (Ktor)"]
                HTTP["HTTP :8080 (HTTPS optional)"]
                StreamHTTP["Streamable HTTP /mcp\n(POST, DELETE; JSON-only, no SSE)"]
                Auth["BearerTokenAuth (global)"]
                SDK["SDK Server → Server.addTool()"]
                HTTP --> StreamHTTP --> Auth --> SDK
            end
            subgraph Tunnel["TunnelManager (optional)"]
                CF["CloudflareTunnelProvider\n(process-based)"]
                Ngrok["NgrokTunnelProvider\n(in-process JNI)"]
                PubURL["Public HTTPS URL\n(*.trycloudflare.com / ngrok)"]
                CF --> PubURL
                Ngrok --> PubURL
            end
        end

        MainAct -->|"StateFlow (status)"| McpServerSvc
        SDK -->|"Singleton\n(companion object)"| AccSvc
        SDK --> StorageSvc
    end
```

---

## Service Lifecycle

### Startup Sequence

1. **User opens app** -> `MainActivity.onCreate()` renders Compose UI
2. **User enables accessibility** -> System starts `McpAccessibilityService`
   - `onServiceConnected()` stores `instance` in companion object
   - Service remains running until disabled in Settings
3. **User taps "Start Server"** -> `MainViewModel.startServer()` called
   - Sends `ACTION_START` intent to `McpServerService`
   - `McpServerService.onStartCommand()`:
     a. Calls `startForeground()` with notification (within 5 seconds)
     b. Reads `ServerConfig` from `SettingsRepository`
     c. Gets/creates SSL keystore from `CertificateManager` (only if HTTPS enabled)
     d. Creates `McpServer` with config, keystore, and SDK `Server` (MCP Kotlin SDK)
     e. Starts Ktor server (HTTP by default, HTTPS if enabled)
     f. Updates `ServerStatus.Running` via companion-level StateFlow
     g. If tunnel enabled: starts `TunnelManager` (connects to Cloudflare or ngrok)
     h. Tunnel status and URL logged to UI via `serverLogEvents` SharedFlow

### Shutdown Sequence

1. **User taps "Stop Server"** -> `MainViewModel.stopServer()` called
   - Sends `ACTION_STOP` intent to `McpServerService`
   - `McpServerService.onDestroy()`:
     a. Updates `ServerStatus.Stopping` via companion-level StateFlow
     b. Stops tunnel (with 3s ANR-safe timeout) — tunnel stops BEFORE server
     c. Stops Ktor server gracefully (1s grace + 5s timeout)
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
| Dispatchers.IO        | DataStore reads/writes, Ktor server startup, network I/O, file operations (SAF) |
| Dispatchers.Default   | Screenshot JPEG encoding, accessibility tree parsing    |
| Ktor Netty threads    | HTTP request handling (NIO event loop)                  |

### Coroutine Scopes

| Component             | Scope                      | Lifecycle                    |
|-----------------------|---------------------------|------------------------------|
| MainViewModel         | `viewModelScope`          | ViewModel lifecycle          |
| McpServerService      | Custom `CoroutineScope`   | Service onCreate to onDestroy|
| McpAccessibilityService| Custom `CoroutineScope`  | Service lifecycle            |

### Thread Safety

- SDK `Server` tool registry: thread-safe (managed by MCP SDK)
- `McpAccessibilityService.instance`: `@Volatile` singleton
- `McpServer.running`: `AtomicBoolean`
- Accessibility node access: Must be on main thread (Android requirement)

---

## Data Flow: MCP Request

```mermaid
sequenceDiagram
    participant Client as MCP Client
    participant Ktor as Ktor Netty (IO threads)
    participant Auth as BearerTokenAuth Plugin
    participant Route as McpStreamableHttp (/mcp)
    participant SDK as SDK Server (MCP Kotlin SDK)
    participant Tool as Tool Handler
    participant Acc as AccessibilityService (Main Thread)

    Client->>Ktor: HTTP(S) POST /mcp<br/>Authorization: Bearer <token><br/>{"method":"tools/call","params":{"name":"android_tap",...}}
    Ktor->>Auth: Forward request
    Auth->>Auth: Extract Bearer token<br/>Constant-time compare
    alt Invalid token
        Auth-->>Client: 401 Unauthorized
    end
    Auth->>Route: Authenticated request
    Route->>SDK: StreamableHttpServerTransport<br/>(JSON-only, no SSE)
    SDK->>SDK: Route by method ("tools/call")<br/>Extract tool name + arguments<br/>Look up registered tool
    SDK->>Tool: Execute tool lambda
    Tool->>Acc: withContext(Dispatchers.Main)<br/>dispatchGesture / performAction / ...
    Acc->>Acc: Perform action on Android UI
    Acc-->>Tool: Success / Failure
    Tool-->>SDK: CallToolResult<br/>(TextContent or ImageContent)
    SDK-->>Ktor: JSON-RPC response via transport
    Ktor-->>Client: HTTP 200 + JSON body
```

---

## Security Model

### HTTPS (Optional Transport Security)

- When HTTPS is enabled, all traffic encrypted with TLS 1.2+
- Self-signed or custom certificate (when HTTPS is enabled)
- HTTP by default; HTTPS available as optional toggle in settings
- Certificate stored in app-private directory

### Bearer Token (Authentication)

- Every request requires `Authorization: Bearer <token>` header (global Application-level plugin)
- Token validated with constant-time comparison (prevents timing attacks)
- Token auto-generated (UUID) on first launch, user can regenerate
- Token stored in DataStore (app-private)

### Network Binding (Exposure Control)

- Default: `127.0.0.1` (localhost only, requires `adb forward`)
- Optional: `0.0.0.0` (all interfaces, with security warning)
- No external firewall; relies on Android's app sandbox and bearer token

---

## Configuration Flow

```mermaid
flowchart TB
    User["User (UI)"]
    User --> VM["MainViewModel"]
    VM -->|"updatePort(), updateBindingAddress(), etc."| Repo["SettingsRepository (interface)"]
    Repo --> Impl["SettingsRepositoryImpl\n(DataStore&lt;Preferences&gt;)"]
    Impl -->|"Persists to DataStore file\nEmits via serverConfig: Flow&lt;ServerConfig&gt;"| DS[(DataStore)]
    DS -->|"config = serverConfig.first()"| SvcRead["McpServerService\n(reads on start)"]
    SvcRead --> McpServer["McpServer\n(uses config for Ktor setup)"]
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
| QUERY_ALL_PACKAGES       | Normal        | Auto-granted (manifest)            | Listing installed apps    |
| KILL_BACKGROUND_PROCESSES| Normal        | Auto-granted (manifest)            | Closing background apps   |
| POST_NOTIFICATIONS       | Runtime (13+) | System dialog                      | Foreground notifications  |
| Accessibility Service    | Special       | User enables in Settings           | UI introspection/actions  |
| AccessibilityService takeScreenshot | Special | User enables in Settings | Screenshots (Android 11+) |
| SAF tree URI permissions | Special       | User grants via system file picker | File operations per storage location |

---

**End of ARCHITECTURE.md**
