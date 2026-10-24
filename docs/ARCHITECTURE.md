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
            IntentDisp["IntentDispatcher"]
            LocationProv["LocationProvider"]
        end

        subgraph NotifSvc["McpNotificationListenerService (System-managed)"]
            NotifProv["NotificationProvider"]
        end

        subgraph CameraSvc["Camera Services"]
            CamProv["CameraProvider\n(CameraX)"]
            SvcLifecycle["ServiceLifecycleOwner"]
            CamProv --> SvcLifecycle
        end

        subgraph McpServerSvc["McpServerService (Foreground Service)"]
            subgraph Ktor["McpServer (Ktor)"]
                HTTP["HTTP :8080 (HTTPS optional)"]
                StreamHTTP["Streamable HTTP /mcp\n(POST, DELETE; JSON-only, no SSE)"]
                Auth["BearerTokenAuth (global)"]
                SDK["SDK Server → Server.addTool()"]
                HTTP --> StreamHTTP --> Auth --> SDK
                TLSL["Loopback TLS listener\n(TunnelListenerHost, free E2E tunnel)"]
                TLSL --> StreamHTTP
            end
            subgraph Tunnel["TunnelManager (optional)"]
                Arc["ArcTunnelProvider\n(free E2E tunnel, in-process)"]
                CF["CloudflareTunnelProvider\n(process-based)"]
                Ngrok["NgrokTunnelProvider\n(in-process JNI)"]
                PubURL["Public HTTPS URL\n(*.d.arc-tunnel.io / *.trycloudflare.com / ngrok)"]
                Arc --> PubURL
                CF --> PubURL
                Ngrok --> PubURL
            end
            Arc -. "dial-back splice" .-> TLSL
        end

        MainAct -->|"StateFlow (status)"| McpServerSvc
        SDK -->|"Singleton\n(companion object)"| AccSvc
        SDK --> StorageSvc
        SDK --> LocationProv
        SDK --> CameraSvc
        SDK --> NotifSvc
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
     g. If tunnel enabled: starts `TunnelManager` with the `McpServer` as `TunnelListenerHost` (free E2E tunnel: resumes the saved identity, renews a missing or expired certificate (shown in the UI through `ArcTunnelRenewals`), opens the loopback TLS listener and the control stream; Cloudflare/ngrok: as before)
     h. Tunnel status and URL logged to UI via `serverLogEvents` SharedFlow

### Shutdown Sequence

1. **User taps "Stop Server"** -> `MainViewModel.stopServer()` called
   - Sends `ACTION_STOP` intent to `McpServerService`
   - `McpServerService.onDestroy()` (a server start still in progress is cancelled and waited for first, at most 5 s, through `ServerStartTracker`):
     a. Updates `ServerStatus.Stopping` via companion-level StateFlow
     b. Stops tunnel (with 3s ANR-safe timeout) — tunnel stops BEFORE server
     c. Stops Ktor server gracefully (1s grace + 5s timeout)
     d. Cancels coroutine scope
     e. Clears singleton instance
     f. Updates `ServerStatus.Stopped` via companion-level StateFlow

### McpNotificationListenerService Lifecycle

- **Type**: Android `NotificationListenerService` (system-managed)
- **Lifecycle**: Runs as long as enabled in Settings > Notification access
- **Singleton**: Stores `instance` in `@Volatile` companion property
- **Connected**: `onListenerConnected()` sets singleton instance
- **Disconnected**: `onListenerDisconnected()` clears singleton instance
- **Destroyed**: `onDestroy()` clears singleton instance
- **Memory**: `onLowMemory()` and `onTrimMemory()` logged for diagnostics

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
| ArcTunnelEnrollService | Custom `CoroutineScope` (IO) | Service start to the end of the last request |
| ArcTunnelProvider session | Custom `CoroutineScope` (IO + handler) | Tunnel start to stop (per session) |

### Thread Safety

- SDK `Server` tool registry: thread-safe (managed by MCP SDK)
- `McpAccessibilityService.instance`: `@Volatile` singleton
- `McpNotificationListenerService.instance`: `@Volatile` singleton
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

### Combined Authentication (dual-accept)

- The global Application-level `McpAuthPlugin` authorizes a `/mcp` request when it presents the static bearer token OR a valid issued OAuth access token. Two independent toggles control it: `bearer_token_enabled` (default true) and `oauth_enabled` (default true).
- Auth is required iff at least one toggle is on; with BOTH off the server is OPEN (explicit — the UI shows a warning and a confirm dialog before the last method is disabled). An enabled-but-empty bearer fails CLOSED (401).
- Bearer token validated with constant-time comparison; auto-generated (UUID) once on first launch (existing tokens preserved on upgrade), persisted in DataStore. The enabled flags are decoupled from the value (disabling keeps the value; enabling with an empty value auto-generates one). A one-time migration initializes `bearer_token_enabled` from whether a non-empty token already existed.
- A 401 carries `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/mcp"` ONLY when OAuth is enabled (triggers Claude's discovery).

### OAuth 2.1 Authorization Server (self-contained)

- New components live under `mcp/oauth/`: `OAuthPolicy` (redirect allowlist, resource matching, TTL/cap constants), `Pkce` (S256), `JwtTokenService(Impl)` (HS256 issue/verify, memoized algorithm), `OAuthClientRepository(Impl)` (persisted registry in a dedicated `oauth_clients` DataStore with an in-memory snapshot), `AuthorizationCodeStore(Impl)` (single-use 60s codes), `OAuthApprovalCoordinator(Impl)` (number-match pending approvals), `OAuthMetadata` (RFC 9728/8414 docs), `OAuthRoutes` (the HTTP endpoints), `OAuthAccessValidator` (the `/mcp` token check, shared by server and tests), `LogoUrlPolicy` (SSRF guard for client-logo rendering). `RequestBaseUrl` derives the per-request public base URL (used by OAuth metadata/`aud` and the share-content links).
- The app is both Authorization Server and Resource Server, so tokens are HS256-signed with a device-held secret (no JWKS). Access TTL 24h, refresh TTL 90d, code TTL 60s; both tokens carry `aud` = `<base>/mcp` and `client_id`.

```mermaid
sequenceDiagram
    participant C as Claude.ai
    participant S as MCP Server
    participant U as User (device)
    C->>S: POST /mcp (no token)
    S-->>C: 401 + WWW-Authenticate (resource_metadata)
    C->>S: GET /.well-known/oauth-protected-resource/mcp
    C->>S: GET /.well-known/oauth-authorization-server
    C->>S: POST /register (DCR)
    S-->>C: 201 client_id
    C->>S: GET /authorize (PKCE S256, resource)
    S->>S: createPending (2-digit code)
    S-->>C: consent page (polls /authorize/status)
    U->>S: approve in-app (match code)
    C->>S: GET /authorize/status
    S-->>C: redirect with code
    C->>S: POST /token (code_verifier, resource)
    S-->>C: access + refresh JWT
    C->>S: POST /mcp (Bearer access)
    S->>S: verify sig + aud + client in registry
    S-->>C: 200 (tools)
```

### Network Binding (Exposure Control)

- Default: `127.0.0.1` (localhost only, requires `adb forward`)
- Optional: `0.0.0.0` (all interfaces, with security warning)
- No external firewall; relies on Android's app sandbox, the network-binding choice (loopback by default), and the combined auth. When both auth methods are disabled, the network binding is the only remaining layer — stay on loopback unless you trust the network.

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
| FOREGROUND_SERVICE_DATA_SYNC | Normal    | Auto-granted (manifest)            | Free E2E tunnel enrollment service |
| RECEIVE_BOOT_COMPLETED   | Normal        | Auto-granted (manifest)            | Auto-start on boot        |
| QUERY_ALL_PACKAGES       | Normal        | Auto-granted (manifest)            | Listing installed apps    |
| KILL_BACKGROUND_PROCESSES| Normal        | Auto-granted (manifest)            | Closing background apps   |
| POST_NOTIFICATIONS       | Runtime (13+) | System dialog                      | Foreground notifications  |
| Accessibility Service    | Special       | User enables in Settings           | UI introspection/actions  |
| AccessibilityService takeScreenshot | Special | User enables in Settings | Screenshots (Android 11+) |
| CAMERA                   | Runtime       | System dialog                      | Camera photo/video tools  |
| RECORD_AUDIO             | Runtime       | System dialog                      | Video recording with audio|
| ACCESS_FINE_LOCATION     | Runtime       | System dialog                      | Device location tool      |
| ACCESS_COARSE_LOCATION   | Runtime       | Declared (implied by FINE)         | Device location fallback  |
| SAF tree URI permissions | Special       | User grants via system file picker | File operations per storage location |
| Notification Listener    | Special       | User enables in Settings > Notification access | Reading/interacting with notifications |

---

## Multi-Window Accessibility Architecture

The application uses Android's multi-window accessibility API (`AccessibilityService.getWindows()`) to enumerate and introspect **all** interactive windows on screen, not just the foreground app. This enables the MCP client to see and interact with system dialogs, permission popups, IME keyboards, and accessibility overlays.

### Window Discovery Flow

```mermaid
flowchart TB
    Service["McpAccessibilityService"]
    Service -->|"getWindows()"| Windows["List<AccessibilityWindowInfo>"]
    Windows --> ForEach["For each window"]
    ForEach -->|"window.root"| Root["AccessibilityNodeInfo (root)"]
    Root -->|"parseTree(root, 'root_w{windowId}')"| Tree["AccessibilityNodeData tree"]
    Tree --> WD["WindowData(windowId, type, pkg, title, activity, layer, focused, tree)"]
    WD --> Result["MultiWindowResult(windows, degraded=false)"]

    Service -->|"getWindows() fails/empty"| Fallback["rootInActiveWindow"]
    Fallback -->|"parseTree(root, 'root_w{rootNode.windowId}')"| FallbackTree["AccessibilityNodeData tree"]
    FallbackTree --> FallbackWD["WindowData(windowId=rootNode.windowId, ...)"]
    FallbackWD --> DegradedResult["MultiWindowResult(windows, degraded=true)"]
```

### Key Data Types

| Type | Description |
|------|-------------|
| `WindowData` | Window metadata (ID from `AccessibilityWindowInfo.getId()`, type, package, title, activity, layer, focused) plus the parsed `AccessibilityNodeData` tree |
| `MultiWindowResult` | List of `WindowData` plus a `degraded` flag indicating fallback to single-window mode |

### Node ID Uniqueness

Node IDs are deterministic hashes generated from the node's properties and parent chain. The `rootParentId` passed to `parseTree()` (e.g., `"root_w42"`) is the root of the hash chain, so identical nodes in different windows produce different IDs. The window ID is not appended as a visible suffix — it influences the hash internally. Example: `node_a1b2` (not `node_a1b2_w42`).

### Degraded Mode

When `getWindows()` returns empty or fails, the system falls back to `rootInActiveWindow` (single-window mode). In this mode:
- A single `WindowData` is created with `windowId` set to `rootNode.windowId` (the system-assigned window ID of the active window)
- The window type is detected via `rootNode.window?.type` when available, defaulting to `APPLICATION` otherwise
- The `MultiWindowResult.degraded` flag is set to `true`
- The TSV output includes a `note:DEGRADED` line to inform the MCP client
- Action execution falls back to `getRootNode()` for node resolution

### Cross-Window Action Execution

When executing a node-based action (click, long-click, scroll-to-node):
1. The caller provides the `List<WindowData>` from the multi-window snapshot, each containing a `windowId` (from `AccessibilityWindowInfo.getId()`)
2. `performNodeAction()` calls `getAccessibilityWindows()` to get the live window list
3. For each `WindowData`, find the matching live `AccessibilityWindowInfo` by `getId()`
4. Get the window's root `AccessibilityNodeInfo`
5. Walk the live tree in parallel with the parsed tree to find the target node by matching the deterministic node ID
6. Perform the accessibility action on the live node

### Required Configuration

The accessibility service XML config (`accessibility_service_config.xml`) must include `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` in the `accessibilityFlags` attribute for `getWindows()` to return results.

---

**End of ARCHITECTURE.md**
