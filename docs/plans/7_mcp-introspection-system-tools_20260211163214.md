# Plan 7: Screen Introspection & System Action MCP Tools

**Branch**: `feat/mcp-introspection-system-tools`
**PR Title**: `Plan 7: Screen introspection and system action MCP tools`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan implements the first batch of 10 MCP tools (4 screen introspection + 6 system actions), a central tool registration mechanism (`ToolRegistry`), and the `docs/MCP_TOOLS.md` documentation. These tools connect the MCP protocol handler (Plan 6) to the accessibility service (Plan 4) and screen capture service (Plan 5), enabling AI clients to observe the device screen and perform system-level navigation actions.

### Dependencies on Previous Plans

- **Plan 1**: Project scaffolding, Gradle build system, Hilt setup
- **Plan 2**: `ServerConfig`, `SettingsRepository`, `Logger`, Hilt bindings in `AppModule`
- **Plan 4**: `McpAccessibilityService` (singleton, `getRootNode()`, `getCurrentPackageName()`, `getCurrentActivityName()`, `isReady()`, `getScreenInfo()`), `AccessibilityTreeParser` (`.parseTree(rootNode): AccessibilityNodeData`), `ActionExecutor` (`pressBack()`, `pressHome()`, `pressRecents()`, `openNotifications()`, `openQuickSettings()` -- all return `Result<Unit>`)
- **Plan 5**: `ScreenCaptureService` (`.captureScreenshot(quality): Result<ScreenshotData>`, `.isMediaProjectionActive(): Boolean`), `ScreenshotData` (`format`, `data`, `width`, `height`), `ScreenInfo` (`width`, `height`, `densityDpi`, `orientation`)
- **Plan 6**: `McpProtocolHandler` (`registerTool(name, description, inputSchema, handler)`, `ToolHandler` interface with `suspend fun execute(params: JsonObject?): JsonElement`), `McpServer`, `McpServerService` (with `@Inject protocolHandler`, `screenCaptureService` bound reference), error constants (`ERROR_PERMISSION_DENIED = -32001`, `ERROR_ACTION_FAILED = -32003`, `ERROR_INTERNAL = -32603`, `ERROR_INVALID_PARAMS = -32602`)

### Package Base

`com.danielealbano.androidremotecontrolmcp`

### Path References

- **Source root**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Test root**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`

### Key Design Decisions

1. **Each tool is a separate class implementing `ToolHandler`**: This keeps each tool focused (single responsibility) and independently unit-testable.
2. **`ToolRegistry` is a central registration point**: A single Hilt-injected class that receives all tool instances and registers them with `McpProtocolHandler`. Plans 8 and 9 will update this class to register additional tools.
3. **Tool classes are grouped by file**: `ScreenIntrospectionTools.kt` contains the 4 screen introspection tool classes, `SystemActionTools.kt` contains the 6 system action tool classes. Each file also contains a registrar function.

> **Implementation Note — Registration mechanism**: The description mentions "a registrar function" but the actual plan code uses `ToolHandler` classes with a `register()` instance method pattern. The acceptance criteria (line 61) and Plan 8-9 use `ToolRegistry.registerAll()`. At implementation time, follow the `ToolRegistry.registerAll()` pattern from Plans 8-9.
4. **MCP content format**: Tool results are wrapped in the MCP content format `{ "content": [{ "type": "text", "text": "..." }] }` for text results, or `{ "content": [{ "type": "image", "data": "...", "mimeType": "image/jpeg" }] }` for screenshot images.
5. **AccessibilityService access via singleton**: Tool classes access `McpAccessibilityService.instance` directly (same pattern as `ActionExecutor` in Plan 4) since the accessibility service is system-managed and cannot be Hilt-injected.
6. **ScreenCaptureService access via `McpServerService`**: The `capture_screenshot` tool needs access to `ScreenCaptureService`. Since `ScreenCaptureService` is a bound service managed by `McpServerService`, the tool accesses it via `McpServerService.instance?.screenCaptureService`. This requires making `screenCaptureService` accessible (internal or via getter) on `McpServerService`.

---

## User Story 1: Screen Introspection & System Action MCP Tools

**As a** remote AI client connected to the Android device via MCP
**I want** to introspect the current screen state (UI hierarchy, screenshot, app info, screen dimensions) and perform system navigation actions (back, home, recents, notifications, quick settings)
**So that** I can observe the device's current UI state and navigate between apps and system panels as part of an automated control workflow.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] `GetAccessibilityTreeHandler` implements `ToolHandler` and returns the full UI hierarchy as JSON
- [ ] `CaptureScreenshotHandler` implements `ToolHandler` and returns a base64-encoded JPEG screenshot
- [ ] `CaptureScreenshotHandler` validates `quality` parameter (range 1-100, default 80)
- [ ] `CaptureScreenshotHandler` returns image content type per MCP spec (including `width` and `height` fields)
- [ ] `GetCurrentAppHandler` implements `ToolHandler` and returns the foreground app's package/activity name
- [ ] `GetScreenInfoHandler` implements `ToolHandler` and returns screen dimensions, DPI, and orientation
- [ ] `PressBackHandler`, `PressHomeHandler`, `PressRecentsHandler`, `OpenNotificationsHandler`, `OpenQuickSettingsHandler` each implement `ToolHandler`
- [ ] `GetDeviceLogsHandler` implements `ToolHandler` and returns filtered logcat output
- [ ] All tools return proper MCP error code `-32001` when accessibility service is not enabled
- [ ] `CaptureScreenshotHandler` returns error `-32001` when MediaProjection is not granted
- [ ] System action tools return error `-32003` when action execution fails
- [ ] `ToolRegistry` registers all 10 tools on server startup (4 introspection + 6 system action)
- [ ] `McpServerService.startServer()` calls `ToolRegistry.registerAllTools()` before `McpServer.start()`
- [ ] `McpServerService` exposes `screenCaptureService` reference for tool access
- [ ] All tool results use the MCP content format (`content` array with typed entries)

> **Implementation Note — Naming evolution**: The acceptance criteria references `registerAllTools()`, but the actual Plan 7 code uses top-level registration functions called from `McpServerService`. Plans 8-9 introduce `registerAll()` inside `ToolRegistry`. The final method name is `registerAll()`.
- [ ] Unit tests exist and pass for all 10 tools (success paths, error paths, parameter validation)
- [ ] `docs/MCP_TOOLS.md` documents all 10 tools with schemas, examples, and error codes
- [ ] `make lint` passes with no warnings or errors
- [ ] `make test-unit` passes with all tests green
- [ ] `make build` succeeds without errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add tool registrar for MCP tool management` | `ToolRegistry.kt`, updated `McpServerService.kt` |
| 2 | `feat: add screen introspection MCP tools (get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info)` | `ScreenIntrospectionTools.kt` |
| 3 | `feat: add system action MCP tools (press_back, press_home, press_recents, open_notifications, open_quick_settings, get_device_logs)` | `SystemActionTools.kt` |
| 4 | `test: add unit tests for screen introspection and system action tools` | `ScreenIntrospectionToolsTest.kt`, `SystemActionToolsTest.kt` |
| 5 | `docs: add MCP tools documentation with introspection and system action tools` | `docs/MCP_TOOLS.md` |

---

### Task 7.1: Create ToolRegistry and Wire to McpServerService

**Description**: Create the `ToolRegistry` class that centralizes registration of all MCP tools with the `McpProtocolHandler`. Update `McpServerService` to call `ToolRegistry.registerAllTools()` during server startup and to expose `screenCaptureService` for tool access.

**Acceptance Criteria**:
- [ ] `ToolRegistry` has `@Inject constructor(protocolHandler: McpProtocolHandler, ...tool classes)` for Hilt injection
- [ ] `registerAllTools()` registers all 10 tools (4 introspection + 6 system action)
- [ ] `McpServerService.startServer()` calls `toolRegistry.registerAllTools()` before `mcpServer?.start()`
- [ ] `McpServerService` exposes `screenCaptureService` via a getter method or internal property
- [ ] `ToolRegistry` is annotated `@Singleton` to match `McpProtocolHandler` scope
- [ ] `AppModule` provides Hilt bindings for the tool classes (or they use `@Inject constructor()`)
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: `ToolRegistry` is tested indirectly through the individual tool tests. Direct unit testing would require verifying that `McpProtocolHandler.registerTool()` was called for each tool, which is covered by the protocol handler's `handleToolsList()` returning the registered tools.

#### Action 7.1.1: Create `ToolRegistry.kt`

**What**: Create the central tool registration class.

**Context**: This class acts as the single entry point for registering all MCP tools. It is Hilt-injected with the `McpProtocolHandler` and all tool handler instances. The `registerAllTools()` method iterates through all tools and registers each with the protocol handler. Plans 8 and 9 will add additional tool handler parameters to the constructor and additional registration calls in `registerAllTools()`.

> **IMPORTANT — Supersedes Plan 6 definitions**: Plan 6 defined `ToolDefinition` and `ToolHandler` inside `McpProtocolHandler.kt`. This plan **moves** both to `ToolRegistry.kt` as standalone types. At implementation time, **remove** the `ToolDefinition` and `ToolHandler` definitions from `McpProtocolHandler.kt` and replace references with imports from this file. `McpProtocolHandler` should delegate tool execution to `ToolRegistry.execute()` instead of managing its own tool map.

> **Implementation Note — ToolHandler location**: The code imports `ToolHandler` from `com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler` (the `mcp` package), but this plan moves `ToolDefinition` to `ToolRegistry.kt` in `mcp/tools/`. At implementation time, either keep `ToolHandler` in `McpProtocolHandler.kt` (Plan 6) or create a standalone `mcp/ToolHandler.kt` file. The imports already reference the correct package path.

> **Registration pattern evolution**: This plan registers tools via `McpServerService.registerAllTools()` calling into `ToolRegistry`. Plans 8-9 evolve this by adding private helper methods (`registerTouchActionTools()`, `registerGestureTools()`, etc.) inside `ToolRegistry.registerAll()` and expanding the constructor with tool dependencies. The final pattern is: `ToolRegistry.registerAll()` calls private categorized registration methods internally.

The tool handler classes themselves are simple `@Inject constructor()` classes (no special Hilt configuration needed beyond `@Inject`), so Hilt can provide them automatically. The `ToolRegistry` is `@Singleton` to match the protocol handler's scope.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt
@@ -0,0 +1,110 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import javax.inject.Inject
+import javax.inject.Singleton
+
+/**
+ * Data class holding a registered tool's metadata and handler.
+ */
+data class ToolDefinition(
+    val name: String,
+    val description: String,
+    val inputSchema: JsonObject,
+    val handler: ToolHandler,
+)
+
+/**
+ * Central registry for all MCP tools.
+ *
+ * Holds a map of tool name -> [ToolDefinition] and provides:
+ * - [register]: adds a tool to the registry
+ * - [execute]: dispatches a tool call by name
+ * - [listTools]: returns all registered tool definitions
+ *
+ * [McpProtocolHandler] delegates tool dispatch to this registry.
+ * Plans 7, 8, and 9 each call [registerAllTools] to register their
+ * respective tool categories.
+ *
+ * The registry is called once during [McpServerService] startup,
+ * before the Ktor server begins accepting requests.
+ */
+@Singleton
+class ToolRegistry @Inject constructor() {
+
+    private val tools = mutableMapOf<String, ToolDefinition>()
+
+    /**
+     * Registers a tool with the registry.
+     *
+     * @param name The MCP tool name (e.g., "get_accessibility_tree").
+     * @param description Human-readable tool description.
+     * @param inputSchema JSON Schema for the tool's input parameters.
+     * @param handler The [ToolHandler] implementation that executes the tool.
+     */
+    fun register(
+        name: String,
+        description: String,
+        inputSchema: JsonObject,
+        handler: ToolHandler,
+    ) {
+        tools[name] = ToolDefinition(name, description, inputSchema, handler)
+        Log.d(TAG, "Registered tool: $name")
+    }
+
+    /**
+     * Executes a tool by name with the given parameters.
+     *
+     * @param name The tool name to execute.
+     * @param params Optional JSON parameters for the tool.
+     * @return The tool's result as a [JsonElement].
+     * @throws McpToolException if the tool throws a tool-level error.
+     * @throws NoSuchElementException if the tool name is not registered.
+     */
+    suspend fun execute(name: String, params: JsonObject?): JsonElement {
+        val toolDef = tools[name]
+            ?: throw NoSuchElementException("Unknown tool: $name")
+        return toolDef.handler.execute(params)
+    }
+
+    /**
+     * Returns all registered tool definitions.
+     */
+    fun listTools(): List<ToolDefinition> = tools.values.toList()
+
+    /**
+     * Returns the number of registered tools.
+     */
+    fun size(): Int = tools.size
+
+    companion object {
+        private const val TAG = "MCP:ToolRegistry"
+    }
+}
```

> **Design Note**: The `ToolRegistry` holds the tool map internally and provides `register()`, `execute()`, and `listTools()` methods. `McpProtocolHandler.handleToolCall()` delegates to `toolRegistry.execute(toolName, params)`. Each plan registers its tools by calling `toolRegistry.register(name, description, inputSchema, handler)` during startup. This separates tool management from protocol handling.

---

#### Action 7.1.2: Update `McpServerService.kt` to inject ToolRegistry and expose ScreenCaptureService

**What**: Add `ToolRegistry` injection, call `registerAllTools()` in `startServer()`, and expose `screenCaptureService` for tool access.

**Context**: The `McpServerService` currently creates the `McpServer` and starts it. We need to register tools before the server starts accepting requests. The `screenCaptureService` reference (obtained via bound service pattern) needs to be accessible to the `CaptureScreenshotHandler`. Since `McpServerService` already has a singleton `instance` in its companion object, the tool can access `McpServerService.instance?.getScreenCaptureService()`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt
@@ -14,6 +14,7 @@
 import com.danielealbano.androidremotecontrolmcp.mcp.CertificateManager
 import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
 import com.danielealbano.androidremotecontrolmcp.mcp.McpServer
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolRegistry
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerScreenIntrospectionTools
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerSystemActionTools
 import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureService
 import com.danielealbano.androidremotecontrolmcp.ui.MainActivity
 import dagger.hilt.android.AndroidEntryPoint
@@ -31,6 +32,7 @@
     @Inject lateinit var settingsRepository: SettingsRepository
     @Inject lateinit var protocolHandler: McpProtocolHandler
     @Inject lateinit var certificateManager: CertificateManager
+    @Inject lateinit var toolRegistry: ToolRegistry

     private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
     private var mcpServer: McpServer? = null
@@ -76,6 +78,9 @@
             // Get or create SSL keystore
             val keyStore = certificateManager.getOrCreateKeyStore()
             val keyStorePassword = certificateManager.getKeyStorePassword()
+
+            // Register all MCP tools before starting the server (Plan 7 tools)
+
+> **CRITICAL — Tool registration placement**: The `registerScreenIntrospectionTools()` and `registerSystemActionTools()` calls appear after the certificate/HTTPS setup code. At implementation time, ensure these registration calls are placed UNCONDITIONALLY before any HTTPS-conditional block. Tools must be registered regardless of whether HTTPS is enabled or disabled.
+
+            registerScreenIntrospectionTools(toolRegistry)
+            registerSystemActionTools(toolRegistry)
+            // Plans 8 and 9 add: registerTouchActionTools(toolRegistry),
+            // registerGestureTools(toolRegistry), registerElementActionTools(toolRegistry),
+            // registerTextInputTools(toolRegistry), registerUtilityTools(toolRegistry)

             // Create and start the Ktor server
             mcpServer = McpServer(
@@ -100,6 +105,15 @@
         }
     }

+    /**
+     * Returns the bound [ScreenCaptureService] instance, or null if not yet bound.
+     *
+     * Used by MCP tools (e.g., [CaptureScreenshotHandler]) to access screenshot
+     * capture functionality.
+     */
+    fun getScreenCaptureService(): ScreenCaptureService? = screenCaptureService
+
     // ... (rest of existing McpServerService code unchanged)

     companion object {
```

---

### Task 7.2: Implement Screen Introspection Tools

**Description**: Create the 4 screen introspection MCP tool handlers: `GetAccessibilityTreeHandler`, `CaptureScreenshotHandler`, `GetCurrentAppHandler`, and `GetScreenInfoHandler`. Each class implements `ToolHandler` and includes a `register()` method for self-registration with the protocol handler.

**Acceptance Criteria**:
- [ ] `GetAccessibilityTreeHandler` checks `McpAccessibilityService.isReady()` before accessing root node
- [ ] `GetAccessibilityTreeHandler` calls `AccessibilityTreeParser.parseTree()` and serializes the result to JSON
- [ ] `GetAccessibilityTreeHandler` wraps output in MCP content format with type "text"
- [ ] `CaptureScreenshotHandler` validates `quality` parameter: defaults to 80, rejects values outside 1-100
- [ ] `CaptureScreenshotHandler` accesses `ScreenCaptureService` via `McpServerService.instance?.getScreenCaptureService()`
- [ ] `CaptureScreenshotHandler` checks `isMediaProjectionActive()` before capture
- [ ] `CaptureScreenshotHandler` returns MCP content with type "image", including base64 data, mimeType, width, and height
- [ ] `GetCurrentAppHandler` returns package name and activity name from `McpAccessibilityService`
- [ ] `GetCurrentAppHandler` handles null package/activity gracefully (returns "unknown")
- [ ] `GetScreenInfoHandler` calls `McpAccessibilityService.instance?.getScreenInfo()`
- [ ] All 4 tools throw `McpToolException` with appropriate error codes for error conditions
- [ ] All 4 tools define correct `name`, `description`, and `inputSchema` per PROJECT.md spec
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests in Task 7.4 (`ScreenIntrospectionToolsTest.kt`).

#### Action 7.2.1: Create `ScreenIntrospectionTools.kt`

> **CRITICAL — Action ordering**: Actions 7.2.2 (McpToolException.kt) and 7.2.3 (McpContentBuilder.kt) MUST be implemented BEFORE Action 7.2.1 (ScreenIntrospectionTools.kt), because ScreenIntrospectionTools imports both. At implementation time, execute in order: 7.2.2 → 7.2.3 → 7.2.1.

**What**: Create the 4 screen introspection tool handler classes and a shared exception class.

**Context**: Each handler follows the same pattern: (1) validate params, (2) check service availability, (3) execute operation, (4) wrap result in MCP content format, (5) return `JsonElement`. The `McpToolException` is a shared exception class that tool handlers throw to signal MCP-specific errors (with error code and message). The `handleToolCall()` method in `McpProtocolHandler` catches general exceptions, but for more precise error handling, tools should throw `McpToolException` which can be caught and mapped to the correct JSON-RPC error response.

However, since the current `McpProtocolHandler.handleToolCall()` catches `Exception` and returns `internalError()`, we need a way to differentiate between permission errors (-32001), action failures (-32003), and internal errors (-32603). The approach is:
- Tool handlers return the result `JsonElement` on success.
- Tool handlers throw `McpToolException(code, message)` on expected errors (permission denied, action failed, invalid params).
- `McpProtocolHandler.handleToolCall()` will be updated to catch `McpToolException` and return the appropriate error response.

This means we also need to update `McpProtocolHandler.handleToolCall()` to handle `McpToolException` specifically (Action 7.2.2).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt
@@ -0,0 +1,289 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
+import kotlinx.serialization.encodeToString
+import kotlinx.serialization.json.Json
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.buildJsonArray
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.int
+import kotlinx.serialization.json.jsonPrimitive
+import kotlinx.serialization.json.put
+import kotlinx.serialization.json.putJsonArray
+import kotlinx.serialization.json.putJsonObject
+import javax.inject.Inject
+
+// ─────────────────────────────────────────────────────────────────────────────
+// get_accessibility_tree
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `get_accessibility_tree`.
+ *
+ * Returns the full UI hierarchy of the current screen as JSON.
+ * The tree is obtained from the accessibility service and parsed
+ * via [AccessibilityTreeParser].
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "<tree JSON>" }] }`
+ * **Error**: -32001 if accessibility service is not enabled
+ */
+class GetAccessibilityTreeHandler @Inject constructor(
+    private val treeParser: AccessibilityTreeParser,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val service = McpAccessibilityService.instance
+            ?: throw McpToolException.PermissionDenied(
+                "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
+            )
+
+        if (!service.isReady()) {
+            throw McpToolException.PermissionDenied(
+                "Accessibility service is not ready. No active window available.",
+            )
+        }
+
+        val rootNode = service.getRootNode()
+            ?: throw McpToolException.ActionFailed(
+                "Failed to obtain root accessibility node.",
+            )
+
+        return try {
+            val treeData = treeParser.parseTree(rootNode)
+            val treeJson = buildJsonObject {
+                putJsonArray("nodes") {
+                    add(Json.encodeToJsonElement(
+                        com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData.serializer(),
+                        treeData,
+                    ))
+                }
+            }
+            McpContentBuilder.textContent(Json.encodeToString(treeJson))
+        } finally {
+            @Suppress("DEPRECATION")
+            rootNode.recycle()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Returns the full UI hierarchy of the current screen using accessibility services.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "get_accessibility_tree"
+        private const val TAG = "MCP:Tool:GetTree"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// capture_screenshot
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `capture_screenshot`.
+ *
+ * Captures a screenshot of the current screen and returns it as base64-encoded
+ * JPEG data with image metadata.
+ *
+ * **Input**: `{ "quality": 80 }` (optional, default 80, range 1-100)
+ * **Output**: `{ "content": [{ "type": "image", "data": "<base64>", "mimeType": "image/jpeg" }] }`
+ * **Errors**:
+ *   - -32001 if MediaProjection permission not granted
+ *   - -32602 if quality parameter is out of range
+ *   - -32603 if screenshot capture fails
+ */
+class CaptureScreenshotHandler @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val quality = parseQuality(params)
+
+        val screenCaptureService = McpServerService.instance?.getScreenCaptureService()
+            ?: throw McpToolException.PermissionDenied(
+                "Screen capture service is not available. Ensure the MCP server is running.",
+            )
+
+        if (!screenCaptureService.isMediaProjectionActive()) {
+            throw McpToolException.PermissionDenied(
+                "MediaProjection permission not granted. Please grant screen capture permission in the app.",
+            )
+        }
+
+        val result = screenCaptureService.captureScreenshot(quality)
+        val screenshotData = result.getOrElse { exception ->
+            throw McpToolException.ActionFailed(
+                "Screenshot capture failed: ${exception.message ?: "Unknown error"}",
+            )
+        }
+
+        return McpContentBuilder.imageContent(
+            data = screenshotData.data,
+            mimeType = "image/jpeg",
+            width = screenshotData.width,
+            height = screenshotData.height,
+        )
+    }
+
+    private fun parseQuality(params: JsonObject?): Int {
+        val quality = params?.get("quality")?.jsonPrimitive?.int ?: DEFAULT_QUALITY
+        if (quality < MIN_QUALITY || quality > MAX_QUALITY) {
+            throw McpToolException.InvalidParams(
+                "Quality must be between $MIN_QUALITY and $MAX_QUALITY, got $quality",
+            )
+        }
+        return quality
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Captures a screenshot of the current screen and returns it as base64-encoded JPEG.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("quality") {
+                        put("type", "integer")
+                        put("description", "JPEG quality (1-100)")
+                        put("default", DEFAULT_QUALITY)
+                    }
+                }
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "capture_screenshot"
+        const val DEFAULT_QUALITY = 80
+        const val MIN_QUALITY = 1
+        const val MAX_QUALITY = 100
+        private const val TAG = "MCP:Tool:Screenshot"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// get_current_app
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `get_current_app`.
+ *
+ * Returns the package name and activity name of the currently focused application.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "{\"packageName\":\"...\",\"activityName\":\"...\"}" }] }`
+ * **Error**: -32001 if accessibility service is not enabled
+ */
+class GetCurrentAppHandler @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val service = McpAccessibilityService.instance
+            ?: throw McpToolException.PermissionDenied(
+                "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
+            )
+
+        val packageName = service.getCurrentPackageName() ?: "unknown"
+        val activityName = service.getCurrentActivityName() ?: "unknown"
+
+        val resultJson = buildJsonObject {
+            put("packageName", packageName)
+            put("activityName", activityName)
+        }
+
+        return McpContentBuilder.textContent(Json.encodeToString(resultJson))
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Returns the package name and activity name of the currently focused app.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "get_current_app"
+        private const val TAG = "MCP:Tool:CurrentApp"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// get_screen_info
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `get_screen_info`.
+ *
+ * Returns the screen dimensions, density DPI, and orientation.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "{\"width\":1080,\"height\":2400,\"densityDpi\":420,\"orientation\":\"portrait\"}" }] }`
+ * **Error**: -32001 if accessibility service is not enabled
+ */
+class GetScreenInfoHandler @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val service = McpAccessibilityService.instance
+            ?: throw McpToolException.PermissionDenied(
+                "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
+            )
+
+        val screenInfo = service.getScreenInfo()
+        val resultJson = Json.encodeToString(
+            com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo.serializer(),
+            screenInfo,
+        )
+
+        return McpContentBuilder.textContent(resultJson)
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Returns screen dimensions, orientation, and DPI.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "get_screen_info"
+        private const val TAG = "MCP:Tool:ScreenInfo"
+    }
+}
```

---

#### Action 7.2.2: Create `McpToolException.kt`

**What**: Create the shared exception class for MCP tool errors.

**Context**: This exception carries an MCP error code and message. When a tool handler throws `McpToolException`, the `McpProtocolHandler.handleToolCall()` method catches it and maps it to the correct JSON-RPC error response with the appropriate error code (e.g., -32001 for permission denied, -32003 for action failed). This is cleaner than having each tool handler return error responses directly, because tools implement `ToolHandler.execute()` which returns `JsonElement` (success case only).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpToolException.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpToolException.kt
@@ -0,0 +1,30 @@
+package com.danielealbano.androidremotecontrolmcp.mcp
+
+/**
+ * Sealed exception hierarchy for MCP tool errors.
+ *
+ * When thrown from [ToolHandler.execute], the [McpProtocolHandler] catches
+ * this exception and produces the appropriate JSON-RPC error response
+ * with the given [code] and [message].
+ *
+ * Each subclass maps to a specific MCP/JSON-RPC error code.
+ *
+ * @property code The MCP/JSON-RPC error code.
+ */
+sealed class McpToolException(message: String, val code: Int) : Exception(message) {
+    /** Invalid parameters (error code -32602). */
+    class InvalidParams(message: String) : McpToolException(message, -32602)
+
+    /** Internal server error (error code -32603). */
+    class InternalError(message: String) : McpToolException(message, -32603)
+
+    /** Permission not granted (error code -32001). */
+    class PermissionDenied(message: String) : McpToolException(message, -32001)
+
+    /** Element not found by ID or criteria (error code -32002). */
+    class ElementNotFound(message: String) : McpToolException(message, -32002)
+
+    /** Accessibility action execution failed (error code -32003). */
+    class ActionFailed(message: String) : McpToolException(message, -32003)
+
+    /** Operation timed out (error code -32004). */
+    class Timeout(message: String) : McpToolException(message, -32004)
+}
```

---

#### Action 7.2.3: Create `McpContentBuilder.kt`

**What**: Create a utility object for building MCP content responses in the standard format.

**Context**: All MCP tool responses must wrap their results in a `content` array with typed entries. Text results use `{ "type": "text", "text": "..." }` and image results use `{ "type": "image", "data": "...", "mimeType": "..." }`. This utility avoids duplicating the content-building logic across every tool handler.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpContentBuilder.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpContentBuilder.kt
@@ -0,0 +1,52 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.buildJsonArray
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.put
+
+/**
+ * Utility for building MCP tool response content in the standard format.
+ *
+ * MCP tool responses wrap results in a `content` array with typed entries:
+ * - Text: `{ "content": [{ "type": "text", "text": "..." }] }`
+ * - Image: `{ "content": [{ "type": "image", "data": "...", "mimeType": "..." }] }`
+ */
+object McpContentBuilder {
+
+    /**
+     * Builds a text content response.
+     *
+     * @param text The text content (can be plain text or JSON string).
+     * @return JsonElement wrapping the text in MCP content format.
+     */
+    fun textContent(text: String): JsonElement {
+        return buildJsonObject {
+            put("content", buildJsonArray {
+                add(buildJsonObject {
+                    put("type", "text")
+                    put("text", text)
+                })
+            })
+        }
+    }
+
+    /**
+     * Builds an image content response.
+     *
+     * @param data The base64-encoded image data.
+     * @param mimeType The MIME type (e.g., "image/jpeg").
+     * @param width The image width in pixels (optional, included when available).
+     * @param height The image height in pixels (optional, included when available).
+     * @return JsonElement wrapping the image in MCP content format.
+     */
+    fun imageContent(data: String, mimeType: String, width: Int? = null, height: Int? = null): JsonElement {
+        return buildJsonObject {
+            put("content", buildJsonArray {
+                add(buildJsonObject {
+                    put("type", "image")
+                    put("data", data)
+                    put("mimeType", mimeType)
+                    if (width != null) put("width", width)
+                    if (height != null) put("height", height)
+                })
+            })
+        }
+    }
+}
```

---

#### Action 7.2.4: Update `McpProtocolHandler.handleToolCall()` to handle McpToolException

**What**: Update the exception handling in `handleToolCall()` to catch `McpToolException` and return the appropriate error code.

**Context**: Currently, `handleToolCall()` catches all `Exception` types and returns `internalError()` (code -32603). With `McpToolException`, tools can signal specific error codes (e.g., -32001 for permission denied). We need to catch `McpToolException` first and create the error response with the tool's specified code.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt
@@ -1,5 +1,6 @@
 package com.danielealbano.androidremotecontrolmcp.mcp

+// McpToolException is in the same package (mcp/McpToolException.kt), no extra import needed
 // ... (existing imports)

     private suspend fun handleToolCall(request: JsonRpcRequest): JsonRpcResponse {
@@ -9,6 +10,14 @@

         return try {
             val result = toolDef.handler.execute(toolArgs)
             JsonRpcResponse(id = request.id, result = result)
+        } catch (e: McpToolException) {
+            Log.w(TAG, "Tool returned error: $toolName, code=${e.code}, message=${e.message}")
+            JsonRpcResponse(
+                id = request.id,
+                error = JsonRpcError(
+                    code = e.code,
+                    message = e.message ?: "Unknown tool error",
+                ),
+            )
         } catch (e: Exception) {
             Log.e(TAG, "Tool execution failed: $toolName", e)
             internalError(request.id, "Tool execution failed: ${e.message ?: "Unknown error"}")
```

> **Important**: The `McpToolException` catch block MUST appear before the general `Exception` catch block since `McpToolException` extends `Exception`. Kotlin evaluates catch blocks in order and uses the first matching one. The sealed class hierarchy means all subtypes (`InvalidParams`, `InternalError`, `PermissionDenied`, `ElementNotFound`, `ActionFailed`, `Timeout`) are caught by this single block, and each carries its own `code` property.

---

### Task 7.3: Implement System Action Tools

**Description**: Create the 6 system action MCP tool handlers: `PressBackHandler`, `PressHomeHandler`, `PressRecentsHandler`, `OpenNotificationsHandler`, `OpenQuickSettingsHandler`, and `GetDeviceLogsHandler`. The first 5 delegate to `ActionExecutor` from Plan 4. `GetDeviceLogsHandler` retrieves filtered logcat output.

**Acceptance Criteria**:
- [ ] Each handler checks `McpAccessibilityService.instance` availability and throws `-32001` if not ready
- [ ] Each handler calls the corresponding `ActionExecutor` method (`pressBack()`, `pressHome()`, etc.)
- [ ] Each handler checks the `Result<Unit>` return and throws `-32003` on failure
- [ ] Each handler returns a text content confirmation message on success
- [ ] All 6 tools define correct `name`, `description`, and `inputSchema` per PROJECT.md spec
- [ ] `GetDeviceLogsHandler` validates `last_lines` (1-1000), `level` (V/D/I/W/E/F), and other optional params
- [ ] `GetDeviceLogsHandler` returns `-32602` for invalid params and `-32603` for execution failure
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests in Task 7.4 (`SystemActionToolsTest.kt`).

#### Action 7.3.1: Create `SystemActionTools.kt`

**What**: Create the 6 system action tool handler classes.

**Context**: The first 5 are the simplest tool handlers. They take no parameters, delegate to `ActionExecutor`, and return a text confirmation. All follow the same pattern: (1) check accessibility service availability, (2) call ActionExecutor, (3) check result, (4) return MCP content. To reduce boilerplate, a private helper function `executeSystemAction()` encapsulates the common logic. The 6th handler, `GetDeviceLogsHandler`, retrieves filtered logcat output by executing `logcat` via `Runtime.getRuntime().exec()`.

Note: `ActionExecutor` methods return `Result<Unit>`. On failure, the `Result` contains an exception with a descriptive message. Tools map `Result.failure` to `McpToolException` with error code `-32003` (action failed).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt
@@ -0,0 +1,262 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.put
+import kotlinx.serialization.json.putJsonArray
+import kotlinx.serialization.json.putJsonObject
+import javax.inject.Inject

> **CRITICAL — Missing imports**: The following imports are used in the code but not listed: `import kotlinx.serialization.json.buildJsonArray`, `import kotlinx.serialization.json.JsonPrimitive`, `import kotlinx.serialization.json.contentOrNull`, `import kotlinx.serialization.json.int`, `import kotlinx.serialization.json.Json`. Add these at implementation time.

+/**
+ * Executes a system action via [ActionExecutor], with standard error handling.
+ *
+ * Checks accessibility service availability, executes the action, and returns
+ * a text content response on success. Throws [McpToolException] on failure.
+ *
+ * @param actionName Human-readable name of the action (for error/success messages).
+ * @param action Suspend function that performs the system action and returns [Result].
+ * @return MCP content [JsonElement] with confirmation message.
+ */
+private suspend fun executeSystemAction(
+    actionName: String,
+    action: suspend () -> Result<Unit>,
+): JsonElement {
+    McpAccessibilityService.instance
+        ?: throw McpToolException.PermissionDenied(
+            "Accessibility service not enabled. Please enable it in Android Settings > Accessibility.",
+        )
+
+    val result = action()
+    result.onFailure { exception ->
+        throw McpToolException.ActionFailed(
+            "$actionName failed: ${exception.message ?: "Unknown error"}",
+        )
+    }
+
+    return McpContentBuilder.textContent("$actionName executed successfully")
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// press_back
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `press_back`.
+ *
+ * Presses the system back button via accessibility global action.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "Back button pressed" }] }`
+ * **Errors**:
+ *   - -32001 if accessibility service is not enabled
+ *   - -32003 if action execution failed
+ */
+class PressBackHandler @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        return executeSystemAction("Back button press") {
+            actionExecutor.pressBack()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Presses the back button (global accessibility action).",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "press_back"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// press_home
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `press_home`.
+ *
+ * Navigates to the home screen via accessibility global action.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "Home button press executed successfully" }] }`
+ * **Errors**:
+ *   - -32001 if accessibility service is not enabled
+ *   - -32003 if action execution failed
+ */
+class PressHomeHandler @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        return executeSystemAction("Home button press") {
+            actionExecutor.pressHome()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Navigates to the home screen.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "press_home"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// press_recents
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `press_recents`.
+ *
+ * Opens the recent apps screen via accessibility global action.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "Recents button press executed successfully" }] }`
+ * **Errors**:
+ *   - -32001 if accessibility service is not enabled
+ *   - -32003 if action execution failed
+ */
+class PressRecentsHandler @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        return executeSystemAction("Recents button press") {
+            actionExecutor.pressRecents()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Opens the recent apps screen.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "press_recents"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// open_notifications
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `open_notifications`.
+ *
+ * Pulls down the notification shade via accessibility global action.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "Open notifications executed successfully" }] }`
+ * **Errors**:
+ *   - -32001 if accessibility service is not enabled
+ *   - -32003 if action execution failed
+ */
+class OpenNotificationsHandler @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        return executeSystemAction("Open notifications") {
+            actionExecutor.openNotifications()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Pulls down the notification shade.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "open_notifications"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// open_quick_settings
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `open_quick_settings`.
+ *
+ * Opens the quick settings panel via accessibility global action.
+ *
+ * **Input**: `{}` (no parameters)
+ * **Output**: `{ "content": [{ "type": "text", "text": "Open quick settings executed successfully" }] }`
+ * **Errors**:
+ *   - -32001 if accessibility service is not enabled
+ *   - -32003 if action execution failed
+ */
+class OpenQuickSettingsHandler @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        return executeSystemAction("Open quick settings") {
+            actionExecutor.openQuickSettings()
+        }
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Opens the quick settings panel.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {}
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "open_quick_settings"
+    }
+}
+
+// ─────────────────────────────────────────────────────────────────────────────
+// get_device_logs
+// ─────────────────────────────────────────────────────────────────────────────
+
+/**
+ * MCP tool handler for `get_device_logs`.
+ *
+ * Retrieves device logcat logs filtered by time range, last N lines, tag, level,
+ * or package name. Useful for debugging app behavior and system events.
+ *
+ * **Input**: `{ "last_lines": 100, "since": "...", "until": "...", "tag": "...", "level": "D", "package_name": "..." }`
+ * **Output**: `{ "content": [{ "type": "text", "text": "{\"logs\":\"...\",\"line_count\":100,\"truncated\":false}" }] }`
+ * **Errors**:
+ *   - -32602 if parameters are invalid
+ *   - -32603 if logcat execution fails

> **Implementation Note — Wrong error code in KDoc**: The KDoc and acceptance criteria say `-32603` for logcat execution failure, but the catch block throws `McpToolException.ActionFailed` which is `-32003`. Update KDoc to say `-32003` at implementation time.

+ */
+class GetDeviceLogsHandler @Inject constructor() : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val lastLines = params?.get("last_lines")?.jsonPrimitive?.int ?: DEFAULT_LAST_LINES
+        if (lastLines < 1 || lastLines > MAX_LAST_LINES) {
+            throw McpToolException.InvalidParams(
+                "last_lines must be between 1 and $MAX_LAST_LINES, got $lastLines",
+            )
+        }
+
+        val since = params?.get("since")?.jsonPrimitive?.contentOrNull
+        val until = params?.get("until")?.jsonPrimitive?.contentOrNull
+        val tag = params?.get("tag")?.jsonPrimitive?.contentOrNull
+        val levelStr = params?.get("level")?.jsonPrimitive?.contentOrNull ?: DEFAULT_LEVEL
+        val packageName = params?.get("package_name")?.jsonPrimitive?.contentOrNull

> **IMPORTANT — Unimplemented parameters**: The `until` and `package_name` parameters are accepted in the schema and extracted from the request, but are NOT used in `buildLogcatCommand()`. At implementation time, either implement these parameters or remove them from the schema.
+
+        if (levelStr !in VALID_LEVELS) {
+            throw McpToolException.InvalidParams(
+                "level must be one of: ${VALID_LEVELS.joinToString(", ")}. Got: '$levelStr'",
+            )
+        }
+
+        return try {
+            val command = buildLogcatCommand(lastLines, since, until, tag, levelStr, packageName)
+            val process = Runtime.getRuntime().exec(command.toTypedArray())
+            val output = process.inputStream.bufferedReader().readText()
+            val exitCode = process.waitFor()
+
+            if (exitCode != 0 && output.isEmpty()) {
+                val errorOutput = process.errorStream.bufferedReader().readText()
+                throw McpToolException.ActionFailed(
+                    "logcat command failed (exit $exitCode): $errorOutput",
+                )
+            }
+
+            val lines = output.lines().filter { it.isNotBlank() }
+            val truncated = lines.size >= lastLines
+
+            val resultJson = buildJsonObject {
+                put("logs", lines.joinToString("\n"))
+                put("line_count", lines.size)
+                put("truncated", truncated)
+            }
+
+            McpContentBuilder.textContent(Json.encodeToString(resultJson))
+        } catch (e: McpToolException) {
+            throw e
+        } catch (e: Exception) {
+            throw McpToolException.ActionFailed(
+                "Failed to retrieve device logs: ${e.message ?: "Unknown error"}",
+            )
+        }
+    }
+
+    private fun buildLogcatCommand(
+        lastLines: Int,
+        since: String?,
+        until: String?,
+        tag: String?,
+        level: String,
+        packageName: String?,
+    ): List<String> {
+        val cmd = mutableListOf("logcat", "-d")
+
+        if (since != null) {
+            cmd.addAll(listOf("-T", since))
+        } else {
+            cmd.addAll(listOf("-t", lastLines.toString()))
+        }
+
+        if (tag != null) {
+            cmd.addAll(listOf("-s", "$tag:$level"))
+        } else {
+            cmd.add("*:$level")
+        }
+
+        return cmd
+    }
+
+    fun register(toolRegistry: ToolRegistry) {
+        toolRegistry.register(
+            name = TOOL_NAME,
+            description = "Retrieves device logcat logs filtered by time range, tag, level, or package name.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("last_lines") {
+                        put("type", "integer")
+                        put("description", "Number of most recent log lines to return (1-1000)")
+                        put("default", DEFAULT_LAST_LINES)
+                    }
+                    putJsonObject("since") {
+                        put("type", "string")
+                        put("description", "ISO 8601 timestamp to filter logs from (e.g., 2024-01-15T10:30:00)")
+                    }
+                    putJsonObject("until") {
+                        put("type", "string")
+                        put("description", "ISO 8601 timestamp to filter logs until (used with since)")
+                    }
+                    putJsonObject("tag") {
+                        put("type", "string")
+                        put("description", "Filter by log tag (exact match, e.g., MCP:ServerService)")
+                    }
+                    putJsonObject("level") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            VALID_LEVELS.forEach { add(JsonPrimitive(it)) }
+                        })
+                        put("description", "Minimum log level to include")
+                        put("default", DEFAULT_LEVEL)
+                    }
+                    putJsonObject("package_name") {
+                        put("type", "string")
+                        put("description", "Filter logs by package name")
+                    }
+                }
+                putJsonArray("required") {}
+            },
+            handler = this,
+        )
+    }
+
+    companion object {
+        const val TOOL_NAME = "get_device_logs"
+        private const val DEFAULT_LAST_LINES = 100
+        private const val MAX_LAST_LINES = 1000
+        private const val DEFAULT_LEVEL = "D"
+        private val VALID_LEVELS = setOf("V", "D", "I", "W", "E", "F")
+        private const val TAG = "MCP:Tool:DeviceLogs"
+    }
+}
```

---

### Task 7.4: Unit Tests for All 10 Tools

**Description**: Create comprehensive unit tests for all screen introspection and system action tool handlers.

**Acceptance Criteria**:
- [ ] `ScreenIntrospectionToolsTest.kt` tests all 4 introspection tools
- [ ] `SystemActionToolsTest.kt` tests all 6 system action tools (including `GetDeviceLogsHandler`)
- [ ] Tests verify success paths (correct output format, correct content type)
- [ ] Tests verify error paths (service not available, permission denied, action failure)
- [ ] Tests verify parameter validation (capture_screenshot quality range)
- [ ] Tests use MockK to mock `McpAccessibilityService`, `ScreenCaptureService`, `AccessibilityTreeParser`, `ActionExecutor`
- [ ] Tests follow Arrange-Act-Assert pattern
- [ ] All tests pass via `make test-unit`

**Tests**: These ARE the tests.

#### Action 7.4.1: Create `ScreenIntrospectionToolsTest.kt`

**What**: Create unit tests for `GetAccessibilityTreeHandler`, `CaptureScreenshotHandler`, `GetCurrentAppHandler`, and `GetScreenInfoHandler`.

**Context**: Tests mock the Android accessibility and screen capture services to test tool handler logic in isolation. The singleton `McpAccessibilityService.instance` is set via reflection in tests (same pattern used in `ActionExecutorTest` from Plan 4). `ScreenCaptureService` is accessed via `McpServerService.instance`, which is also set via reflection. The `AccessibilityTreeParser` is mocked directly since it is Hilt-injected.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt
@@ -0,0 +1,380 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.view.accessibility.AccessibilityNodeInfo
+import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
+import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureService
+import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotData
+import io.mockk.coEvery
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkObject
+import io.mockk.unmockkAll
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonArray
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertThrows
+
+@DisplayName("Screen Introspection Tools")
+class ScreenIntrospectionToolsTest {
+
+    private lateinit var mockService: McpAccessibilityService
+    private lateinit var mockTreeParser: AccessibilityTreeParser
+    private lateinit var mockRootNode: AccessibilityNodeInfo
+    private lateinit var mockScreenCaptureService: ScreenCaptureService
+    private lateinit var mockServerService: McpServerService
+
+    @BeforeEach
+    fun setUp() {
+        mockService = mockk<McpAccessibilityService>(relaxed = true)
+        mockTreeParser = mockk<AccessibilityTreeParser>()
+        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
+        mockScreenCaptureService = mockk<ScreenCaptureService>()
+        mockServerService = mockk<McpServerService>()
+
+        setAccessibilityServiceInstance(mockService)
+        setServerServiceInstance(mockServerService)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        setAccessibilityServiceInstance(null)
+        setServerServiceInstance(null)
+        unmockkAll()
+    }
+
+    /**
+     * Sets [McpAccessibilityService.instance] via reflection for testing.
+     */
+    private fun setAccessibilityServiceInstance(instance: McpAccessibilityService?) {
+        val field = McpAccessibilityService::class.java.getDeclaredField("instance")
+        field.isAccessible = true
+        field.set(null, instance)
+    }
+
+    /**
+     * Sets [McpServerService.instance] via reflection for testing.
+     */
+    private fun setServerServiceInstance(instance: McpServerService?) {
+        val field = McpServerService::class.java.getDeclaredField("instance")
+        field.isAccessible = true
+        field.set(null, instance)
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // get_accessibility_tree
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("GetAccessibilityTreeHandler")
+    inner class GetAccessibilityTreeTests {
+
+        private lateinit var handler: GetAccessibilityTreeHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = GetAccessibilityTreeHandler(mockTreeParser)
+        }
+
+        @Test
+        @DisplayName("returns tree JSON when service is ready")
+        fun returnsTreeJsonWhenServiceIsReady() = runTest {
+            // Arrange
+            every { mockService.isReady() } returns true
+            every { mockService.getRootNode() } returns mockRootNode
+            val mockTreeData = AccessibilityNodeData(
+                id = "root_0",
+                className = "android.widget.FrameLayout",
+                text = null,
+                bounds = BoundsData(0, 0, 1080, 2400),
+                visible = true,
+                enabled = true,
+                children = emptyList(),
+            )
+            every { mockTreeParser.parseTree(mockRootNode) } returns mockTreeData
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            assertEquals(1, content!!.size)
+            assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
+            val textContent = content[0].jsonObject["text"]?.jsonPrimitive?.content
+            assertNotNull(textContent)
+            assertTrue(textContent!!.contains("root_0"))
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service is not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setAccessibilityServiceInstance(null)
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+            assertTrue(exception.message.contains("Accessibility service not enabled"))
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service is not ready")
+        fun throwsErrorWhenServiceNotReady() = runTest {
+            // Arrange
+            every { mockService.isReady() } returns false
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+            assertTrue(exception.message.contains("not ready"))
+        }
+
+        @Test
+        @DisplayName("throws error -32603 when root node is null")
+        fun throwsErrorWhenRootNodeNull() = runTest {
+            // Arrange
+            every { mockService.isReady() } returns true
+            every { mockService.getRootNode() } returns null
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INTERNAL, exception.code)
+        }

> **CRITICAL — Wrong error code in test**: These tests assert error code `-32603` (InternalError), but the actual code throws `McpToolException.ActionFailed` which is `-32003`. Fix at implementation time: change assertions from `-32603` to `-32003`.

+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // capture_screenshot
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("CaptureScreenshotHandler")
+    inner class CaptureScreenshotTests {
+
+        private lateinit var handler: CaptureScreenshotHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = CaptureScreenshotHandler()
+        }
+
+        @Test
+        @DisplayName("captures screenshot with default quality")
+        fun capturesScreenshotWithDefaultQuality() = runTest {
+            // Arrange
+            every { mockServerService.getScreenCaptureService() } returns mockScreenCaptureService
+            every { mockScreenCaptureService.isMediaProjectionActive() } returns true
+            coEvery { mockScreenCaptureService.captureScreenshot(80) } returns Result.success(
+                ScreenshotData(data = "base64data", width = 1080, height = 2400),
+            )
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            assertEquals(1, content!!.size)
+            assertEquals("image", content[0].jsonObject["type"]?.jsonPrimitive?.content)
+            assertEquals("base64data", content[0].jsonObject["data"]?.jsonPrimitive?.content)
+            assertEquals("image/jpeg", content[0].jsonObject["mimeType"]?.jsonPrimitive?.content)
+            assertEquals(1080, content[0].jsonObject["width"]?.jsonPrimitive?.int)
+            assertEquals(2400, content[0].jsonObject["height"]?.jsonPrimitive?.int)
+        }
+
+        @Test
+        @DisplayName("captures screenshot with custom quality")
+        fun capturesScreenshotWithCustomQuality() = runTest {
+            // Arrange
+            every { mockServerService.getScreenCaptureService() } returns mockScreenCaptureService
+            every { mockScreenCaptureService.isMediaProjectionActive() } returns true
+            coEvery { mockScreenCaptureService.captureScreenshot(50) } returns Result.success(
+                ScreenshotData(data = "base64data50", width = 1080, height = 2400),
+            )
+            val params = buildJsonObject { put("quality", JsonPrimitive(50)) }
+
+            // Act
+            val result = handler.execute(params)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            assertEquals("base64data50", content!![0].jsonObject["data"]?.jsonPrimitive?.content)
+        }
+
+        @Test
+        @DisplayName("rejects quality below minimum (0)")
+        fun rejectsQualityBelowMinimum() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("quality", JsonPrimitive(0)) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("between 1 and 100"))
+        }
+
+        @Test
+        @DisplayName("rejects quality above maximum (101)")
+        fun rejectsQualityAboveMaximum() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("quality", JsonPrimitive(101)) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+
+        @Test
+        @DisplayName("rejects negative quality (-1)")
+        fun rejectsNegativeQuality() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("quality", JsonPrimitive(-1)) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when MediaProjection not granted")
+        fun throwsErrorWhenMediaProjectionNotGranted() = runTest {
+            // Arrange
+            every { mockServerService.getScreenCaptureService() } returns mockScreenCaptureService
+            every { mockScreenCaptureService.isMediaProjectionActive() } returns false
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+            assertTrue(exception.message.contains("MediaProjection"))
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when screen capture service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            // Arrange
+            every { mockServerService.getScreenCaptureService() } returns null
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32603 when capture fails")
+        fun throwsErrorWhenCaptureFails() = runTest {
+            // Arrange
+            every { mockServerService.getScreenCaptureService() } returns mockScreenCaptureService
+            every { mockScreenCaptureService.isMediaProjectionActive() } returns true
+            coEvery { mockScreenCaptureService.captureScreenshot(80) } returns Result.failure(
+                RuntimeException("Capture timeout"),
+            )
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INTERNAL, exception.code)
+            assertTrue(exception.message.contains("Capture timeout"))
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // get_current_app
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("GetCurrentAppHandler")
+    inner class GetCurrentAppTests {
+
+        private lateinit var handler: GetCurrentAppHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = GetCurrentAppHandler()
+        }
+
+        @Test
+        @DisplayName("returns package and activity name")
+        fun returnsPackageAndActivityName() = runTest {
+            // Arrange
+            every { mockService.getCurrentPackageName() } returns "com.android.calculator2"
+            every { mockService.getCurrentActivityName() } returns ".Calculator"
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
+            assertNotNull(textContent)
+            assertTrue(textContent!!.contains("com.android.calculator2"))
+            assertTrue(textContent.contains(".Calculator"))
+        }
+
+        @Test
+        @DisplayName("returns unknown when no app focused")
+        fun returnsUnknownWhenNoAppFocused() = runTest {
+            // Arrange
+            every { mockService.getCurrentPackageName() } returns null
+            every { mockService.getCurrentActivityName() } returns null
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
+            assertTrue(textContent!!.contains("unknown"))
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setAccessibilityServiceInstance(null)
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // get_screen_info
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("GetScreenInfoHandler")
+    inner class GetScreenInfoTests {
+
+        private lateinit var handler: GetScreenInfoHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = GetScreenInfoHandler()
+        }
+
+        @Test
+        @DisplayName("returns screen dimensions and orientation")
+        fun returnsScreenDimensionsAndOrientation() = runTest {
+            // Arrange
+            every { mockService.getScreenInfo() } returns ScreenInfo(
+                width = 1080,
+                height = 2400,
+                densityDpi = 420,
+                orientation = ScreenInfo.ORIENTATION_PORTRAIT,
+            )
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            val textContent = content!![0].jsonObject["text"]?.jsonPrimitive?.content
+            assertNotNull(textContent)
+            assertTrue(textContent!!.contains("1080"))
+            assertTrue(textContent.contains("2400"))
+            assertTrue(textContent.contains("420"))
+            assertTrue(textContent.contains("portrait"))
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setAccessibilityServiceInstance(null)
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+    }
+}
```

---

#### Action 7.4.2: Create `SystemActionToolsTest.kt`

**What**: Create unit tests for all 6 system action tool handlers.

**Context**: The first 5 system action handlers follow the same pattern, so their tests are structurally similar: verify the correct `ActionExecutor` method is called, verify success response format, verify error when service is unavailable, verify error when action fails. `GetDeviceLogsHandler` has additional tests for parameter validation (`last_lines` range, `level` values) and logcat output parsing.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionToolsTest.kt
@@ -0,0 +1,310 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
+import io.mockk.coEvery
+import io.mockk.coVerify
+import io.mockk.mockk
+import io.mockk.unmockkAll
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.jsonArray
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertThrows
+
+@DisplayName("System Action Tools")
+class SystemActionToolsTest {
+
+    private lateinit var mockService: McpAccessibilityService
+    private lateinit var mockActionExecutor: ActionExecutor
+
+    @BeforeEach
+    fun setUp() {
+        mockService = mockk<McpAccessibilityService>(relaxed = true)
+        mockActionExecutor = mockk<ActionExecutor>()
+        setAccessibilityServiceInstance(mockService)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        setAccessibilityServiceInstance(null)
+        unmockkAll()
+    }
+
+    private fun setAccessibilityServiceInstance(instance: McpAccessibilityService?) {
+        val field = McpAccessibilityService::class.java.getDeclaredField("instance")
+        field.isAccessible = true
+        field.set(null, instance)
+    }
+
+    /**
+     * Verifies the standard text content response format.
+     */
+    private fun assertTextContentResponse(result: kotlinx.serialization.json.JsonElement, containsText: String) {
+        val content = result.jsonObject["content"]?.jsonArray
+        assertNotNull(content)
+        assertEquals(1, content!!.size)
+        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
+        val text = content[0].jsonObject["text"]?.jsonPrimitive?.content
+        assertNotNull(text)
+        assertTrue(text!!.contains(containsText), "Expected text to contain '$containsText' but was '$text'")
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // press_back
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("PressBackHandler")
+    inner class PressBackTests {
+
+        private lateinit var handler: PressBackHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = PressBackHandler(mockActionExecutor)
+        }
+
+        @Test
+        @DisplayName("calls ActionExecutor.pressBack and returns confirmation")
+        fun callsPressBackAndReturnsConfirmation() = runTest {
+            // Arrange
+            coEvery { mockActionExecutor.pressBack() } returns Result.success(Unit)
+
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            coVerify(exactly = 1) { mockActionExecutor.pressBack() }
+            assertTextContentResponse(result, "executed successfully")
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setAccessibilityServiceInstance(null)
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32003 when action fails")
+        fun throwsErrorWhenActionFails() = runTest {
+            // Arrange
+            coEvery { mockActionExecutor.pressBack() } returns Result.failure(
+                RuntimeException("Global action failed"),
+            )
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(null)
+            }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+            assertTrue(exception.message.contains("Global action failed"))
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // press_home
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("PressHomeHandler")
+    inner class PressHomeTests {
+
+        private lateinit var handler: PressHomeHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = PressHomeHandler(mockActionExecutor)
+        }
+
+        @Test
+        @DisplayName("calls ActionExecutor.pressHome and returns confirmation")
+        fun callsPressHomeAndReturnsConfirmation() = runTest {
+            coEvery { mockActionExecutor.pressHome() } returns Result.success(Unit)
+            val result = handler.execute(null)
+            coVerify(exactly = 1) { mockActionExecutor.pressHome() }
+            assertTextContentResponse(result, "executed successfully")
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            setAccessibilityServiceInstance(null)
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32003 when action fails")
+        fun throwsErrorWhenActionFails() = runTest {
+            coEvery { mockActionExecutor.pressHome() } returns Result.failure(
+                RuntimeException("Action failed"),
+            )
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // press_recents
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("PressRecentsHandler")
+    inner class PressRecentsTests {
+
+        private lateinit var handler: PressRecentsHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = PressRecentsHandler(mockActionExecutor)
+        }
+
+        @Test
+        @DisplayName("calls ActionExecutor.pressRecents and returns confirmation")
+        fun callsPressRecentsAndReturnsConfirmation() = runTest {
+            coEvery { mockActionExecutor.pressRecents() } returns Result.success(Unit)
+            val result = handler.execute(null)
+            coVerify(exactly = 1) { mockActionExecutor.pressRecents() }
+            assertTextContentResponse(result, "executed successfully")
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            setAccessibilityServiceInstance(null)
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32003 when action fails")
+        fun throwsErrorWhenActionFails() = runTest {
+            coEvery { mockActionExecutor.pressRecents() } returns Result.failure(
+                RuntimeException("Action failed"),
+            )
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // open_notifications
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("OpenNotificationsHandler")
+    inner class OpenNotificationsTests {
+
+        private lateinit var handler: OpenNotificationsHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = OpenNotificationsHandler(mockActionExecutor)
+        }
+
+        @Test
+        @DisplayName("calls ActionExecutor.openNotifications and returns confirmation")
+        fun callsOpenNotificationsAndReturnsConfirmation() = runTest {
+            coEvery { mockActionExecutor.openNotifications() } returns Result.success(Unit)
+            val result = handler.execute(null)
+            coVerify(exactly = 1) { mockActionExecutor.openNotifications() }
+            assertTextContentResponse(result, "executed successfully")
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            setAccessibilityServiceInstance(null)
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32003 when action fails")
+        fun throwsErrorWhenActionFails() = runTest {
+            coEvery { mockActionExecutor.openNotifications() } returns Result.failure(
+                RuntimeException("Action failed"),
+            )
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // open_quick_settings
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("OpenQuickSettingsHandler")
+    inner class OpenQuickSettingsTests {
+
+        private lateinit var handler: OpenQuickSettingsHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = OpenQuickSettingsHandler(mockActionExecutor)
+        }
+
+        @Test
+        @DisplayName("calls ActionExecutor.openQuickSettings and returns confirmation")
+        fun callsOpenQuickSettingsAndReturnsConfirmation() = runTest {
+            coEvery { mockActionExecutor.openQuickSettings() } returns Result.success(Unit)
+            val result = handler.execute(null)
+            coVerify(exactly = 1) { mockActionExecutor.openQuickSettings() }
+            assertTextContentResponse(result, "executed successfully")
+        }
+
+        @Test
+        @DisplayName("throws error -32001 when service not available")
+        fun throwsErrorWhenServiceNotAvailable() = runTest {
+            setAccessibilityServiceInstance(null)
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_PERMISSION_DENIED, exception.code)
+        }
+
+        @Test
+        @DisplayName("throws error -32003 when action fails")
+        fun throwsErrorWhenActionFails() = runTest {
+            coEvery { mockActionExecutor.openQuickSettings() } returns Result.failure(
+                RuntimeException("Action failed"),
+            )
+            val exception = assertThrows<McpToolException> { handler.execute(null) }
+            assertEquals(McpProtocolHandler.ERROR_ACTION_FAILED, exception.code)
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────
+    // get_device_logs
+    // ─────────────────────────────────────────────────────────────────────
+
+    @Nested
+    @DisplayName("GetDeviceLogsHandler")
+    inner class GetDeviceLogsTests {
+
+        private lateinit var handler: GetDeviceLogsHandler
+
+        @BeforeEach
+        fun setUp() {
+            handler = GetDeviceLogsHandler()
+        }
+
+        @Test
+        @DisplayName("returns logs with default parameters")
+        fun returnsLogsWithDefaultParameters() = runTest {
+            // Act
+            val result = handler.execute(null)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            assertEquals(1, content!!.size)
+            assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
+            val textContent = content[0].jsonObject["text"]?.jsonPrimitive?.content
+            assertNotNull(textContent)
+            assertTrue(textContent!!.contains("logs"))
+            assertTrue(textContent.contains("line_count"))
+            assertTrue(textContent.contains("truncated"))
+        }
+
+        @Test
+        @DisplayName("returns logs with custom last_lines")
+        fun returnsLogsWithCustomLastLines() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("last_lines", JsonPrimitive(50)) }
+
+            // Act
+            val result = handler.execute(params)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+            assertEquals("text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
+        }
+
+        @Test
+        @DisplayName("throws error -32602 when last_lines is below minimum (0)")
+        fun throwsErrorWhenLastLinesBelowMinimum() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("last_lines", JsonPrimitive(0)) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("last_lines"))
+        }
+
+        @Test
+        @DisplayName("throws error -32602 when last_lines exceeds maximum (1001)")
+        fun throwsErrorWhenLastLinesExceedsMaximum() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("last_lines", JsonPrimitive(1001)) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("last_lines"))
+        }
+
+        @Test
+        @DisplayName("throws error -32602 for invalid log level")
+        fun throwsErrorForInvalidLogLevel() = runTest {
+            // Arrange
+            val params = buildJsonObject { put("level", JsonPrimitive("X")) }
+
+            // Act & Assert
+            val exception = assertThrows<McpToolException> {
+                handler.execute(params)
+            }
+            assertEquals(McpProtocolHandler.ERROR_INVALID_PARAMS, exception.code)
+            assertTrue(exception.message.contains("level"))
+        }
+
+        @Test
+        @DisplayName("accepts valid log levels")
+        fun acceptsValidLogLevels() = runTest {
+            // Verify each valid level does not throw InvalidParams
+            for (level in listOf("V", "D", "I", "W", "E", "F")) {
+                val params = buildJsonObject { put("level", JsonPrimitive(level)) }
+                val result = handler.execute(params)
+                val content = result.jsonObject["content"]?.jsonArray
+                assertNotNull(content, "Expected content for level $level")
+            }
+        }
+
+        @Test
+        @DisplayName("accepts optional tag parameter")
+        fun acceptsOptionalTagParameter() = runTest {
+            // Arrange
+            val params = buildJsonObject {
+                put("tag", JsonPrimitive("MCP:ServerService"))
+                put("level", JsonPrimitive("W"))
+            }
+
+            // Act
+            val result = handler.execute(params)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+        }
+
+        @Test
+        @DisplayName("accepts optional since parameter")
+        fun acceptsOptionalSinceParameter() = runTest {
+            // Arrange
+            val params = buildJsonObject {
+                put("since", JsonPrimitive("2024-01-15T10:30:00"))
+            }
+
+            // Act
+            val result = handler.execute(params)
+
+            // Assert
+            val content = result.jsonObject["content"]?.jsonArray
+            assertNotNull(content)
+        }
+    }
+}
```

---

### Task 7.5: Create MCP Tools Documentation

**Description**: Create the `docs/MCP_TOOLS.md` documentation file covering the 10 tools implemented in this plan, with full schemas, request/response examples, and error codes.

**Acceptance Criteria**:
- [ ] Document includes overview of MCP tools system
- [ ] Document includes Screen Introspection Tools section with 4 tools fully documented
- [ ] Document includes System Action Tools section with 6 tools fully documented
- [ ] Each tool has: name, description, input schema, output example, error codes, full request/response JSON examples
- [ ] Document includes placeholder sections for remaining tool categories (to be filled in Plans 8 and 9)
- [ ] Document follows Markdown conventions and is readable

**Tests**: Documentation only, no automated tests.

#### Action 7.5.1: Create `docs/MCP_TOOLS.md`

**What**: Create the MCP tools documentation file.

**Context**: This file will be the comprehensive reference for all MCP tools. It starts with the 10 tools from this plan and will be expanded in Plans 8 and 9. The document follows the structure laid out in PROJECT.md's Related Documentation section.

**File**: `docs/MCP_TOOLS.md`

```diff
--- /dev/null
+++ b/docs/MCP_TOOLS.md
@@ -0,0 +1,612 @@
+# MCP Tools Reference
+
+This document provides a comprehensive reference for all MCP tools available in the Android Remote Control MCP application. Each tool includes its schema, usage examples, and error handling information.
+
+**Protocol**: JSON-RPC 2.0 over HTTPS
+**Authentication**: Bearer token required for all tool calls
+**Content-Type**: `application/json`
+
+---
+
+## Table of Contents
+
+1. [Overview](#overview)
+2. [Common Patterns](#common-patterns)
+3. [Error Codes](#error-codes)
+4. [Screen Introspection Tools](#1-screen-introspection-tools)
+5. [System Action Tools](#2-system-action-tools)
+6. [Touch Action Tools](#3-touch-action-tools) *(Plan 8)*
+7. [Gesture Tools](#4-gesture-tools) *(Plan 8)*
+8. [Element Action Tools](#5-element-action-tools) *(Plan 9)*
+9. [Text Input Tools](#6-text-input-tools) *(Plan 9)*
+10. [Utility Tools](#7-utility-tools) *(Plan 9)*
+
+---
+
+## Overview
+
+The MCP server exposes tools via the JSON-RPC 2.0 protocol. Tools are organized into 7 categories:
+
+| Category | Tools | Plan |
+|----------|-------|------|
+| Screen Introspection | `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, `get_screen_info` | 7 |
+| System Actions | `press_back`, `press_home`, `press_recents`, `open_notifications`, `open_quick_settings`, `get_device_logs` | 7 |
+| Touch Actions | `tap`, `long_press`, `double_tap`, `swipe`, `scroll` | 8 |
+| Gestures | `pinch`, `custom_gesture` | 8 |
+| Element Actions | `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element` | 9 |
+| Text Input | `input_text`, `clear_text`, `press_key` | 9 |
+| Utilities | `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle` | 9 |
+
+### Endpoints
+
+- **List tools**: `GET /mcp/v1/tools/list` (returns all registered tools)
+- **Call tool**: `POST /mcp/v1/tools/call` (executes a tool)
+
+---
+
+## Common Patterns
+
+### Request Format
+
+All tool calls use the same JSON-RPC 2.0 request format:
+
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "<tool_name>",
+    "arguments": { ... }
+  }
+}
+```
+
+### Response Format (Success)
+
+Successful tool calls return a `content` array with typed entries:
+
+**Text content**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "..."
+      }
+    ]
+  }
+}
+```
+
+**Image content**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "image",
+        "data": "<base64-encoded data>",
+        "mimeType": "image/jpeg"
+      }
+    ]
+  }
+}
+```
+
+### Response Format (Error)
+
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "error": {
+    "code": -32001,
+    "message": "Accessibility service not enabled"
+  }
+}
+```
+
+---
+
+## Error Codes
+
+### Standard JSON-RPC Error Codes
+
+| Code | Name | Description |
+|------|------|-------------|
+| -32700 | Parse Error | Invalid JSON received |
+| -32600 | Invalid Request | Malformed JSON-RPC request |
+| -32601 | Method Not Found | Unknown tool name |
+| -32602 | Invalid Params | Missing or invalid tool arguments |
+| -32603 | Internal Error | Server-side error during tool execution |
+
+### Custom MCP Error Codes
+
+| Code | Name | Description |
+|------|------|-------------|
+| -32001 | Permission Denied | Accessibility service or MediaProjection not enabled |
+| -32002 | Element Not Found | UI element not found by ID or criteria |
+| -32003 | Action Failed | Accessibility action execution failed |
+| -32004 | Timeout | Operation timed out |
+
+---
+
+## 1. Screen Introspection Tools
+
+### `get_accessibility_tree`
+
+Returns the full UI hierarchy of the current screen using accessibility services.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "get_accessibility_tree",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "{\"nodes\":[{\"id\":\"root_0\",\"className\":\"android.widget.FrameLayout\",\"text\":null,\"contentDescription\":null,\"resourceId\":null,\"bounds\":{\"left\":0,\"top\":0,\"right\":1080,\"bottom\":2400},\"clickable\":false,\"longClickable\":false,\"focusable\":false,\"scrollable\":false,\"editable\":false,\"enabled\":true,\"visible\":true,\"children\":[{\"id\":\"node_1\",\"className\":\"android.widget.TextView\",\"text\":\"Calculator\",\"bounds\":{\"left\":100,\"top\":50,\"right\":500,\"bottom\":120},\"visible\":true,\"enabled\":true,\"children\":[]}]}]}"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32603`: Failed to obtain root node
+
+---
+
+### `capture_screenshot`
+
+Captures a screenshot of the current screen and returns it as base64-encoded JPEG.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {
+    "quality": {
+      "type": "integer",
+      "description": "JPEG quality (1-100)",
+      "default": 80
+    }
+  },
+  "required": []
+}
+```
+
+**Request Example** (default quality):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 2,
+  "method": "tools/call",
+  "params": {
+    "name": "capture_screenshot",
+    "arguments": {}
+  }
+}
+```
+
+**Request Example** (custom quality):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 2,
+  "method": "tools/call",
+  "params": {
+    "name": "capture_screenshot",
+    "arguments": {
+      "quality": 50
+    }
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 2,
+  "result": {
+    "content": [
+      {
+        "type": "image",
+        "data": "/9j/4AAQSkZJRgABAQ...<base64 JPEG data>",
+        "mimeType": "image/jpeg",
+        "width": 1080,
+        "height": 2400
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: MediaProjection permission not granted
+- `-32602`: Quality parameter out of range (must be 1-100)
+- `-32603`: Screenshot capture failed
+
+---
+
+### `get_current_app`
+
+Returns the package name and activity name of the currently focused app.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 3,
+  "method": "tools/call",
+  "params": {
+    "name": "get_current_app",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 3,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "{\"packageName\":\"com.android.calculator2\",\"activityName\":\".Calculator\"}"
+      }
+    ]
+  }
+}
+```
+
+**Response Example (No app focused)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 3,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "{\"packageName\":\"unknown\",\"activityName\":\"unknown\"}"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+
+---
+
+### `get_screen_info`
+
+Returns screen dimensions, orientation, and DPI.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 4,
+  "method": "tools/call",
+  "params": {
+    "name": "get_screen_info",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 4,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "{\"width\":1080,\"height\":2400,\"densityDpi\":420,\"orientation\":\"portrait\"}"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+
+---
+
+## 2. System Action Tools
+
+### `press_back`
+
+Presses the back button (global accessibility action).
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 5,
+  "method": "tools/call",
+  "params": {
+    "name": "press_back",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 5,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Back button press executed successfully"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32003`: Action execution failed
+
+---
+
+### `press_home`
+
+Navigates to the home screen.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 6,
+  "method": "tools/call",
+  "params": {
+    "name": "press_home",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 6,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Home button press executed successfully"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32003`: Action execution failed
+
+---
+
+### `press_recents`
+
+Opens the recent apps screen.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 7,
+  "method": "tools/call",
+  "params": {
+    "name": "press_recents",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 7,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Recents button press executed successfully"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32003`: Action execution failed
+
+---
+
+### `open_notifications`
+
+Pulls down the notification shade.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 8,
+  "method": "tools/call",
+  "params": {
+    "name": "open_notifications",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 8,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Open notifications executed successfully"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32003`: Action execution failed
+
+---
+
+### `open_quick_settings`
+
+Opens the quick settings panel.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {},
+  "required": []
+}
+```
+
+**Request Example**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 9,
+  "method": "tools/call",
+  "params": {
+    "name": "open_quick_settings",
+    "arguments": {}
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 9,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Open quick settings executed successfully"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32001`: Accessibility service not enabled
+- `-32003`: Action execution failed
+
+---
+
+### `get_device_logs`
+
+Retrieves device logcat logs filtered by time range, tag, level, or package name.
+
+**Input Schema**:
+```json
+{
+  "type": "object",
+  "properties": {
+    "last_lines": {
+      "type": "integer",
+      "description": "Number of most recent log lines to return (1-1000)",
+      "default": 100
+    },
+    "since": {
+      "type": "string",
+      "description": "ISO 8601 timestamp to filter logs from (e.g., 2024-01-15T10:30:00)"
+    },
+    "until": {
+      "type": "string",
+      "description": "ISO 8601 timestamp to filter logs until (used with since)"
+    },
+    "tag": {
+      "type": "string",
+      "description": "Filter by log tag (exact match, e.g., MCP:ServerService)"
+    },
+    "level": {
+      "type": "string",
+      "enum": ["V", "D", "I", "W", "E", "F"],
+      "description": "Minimum log level to include",
+      "default": "D"
+    },
+    "package_name": {
+      "type": "string",
+      "description": "Filter logs by package name"
+    }
+  },
+  "required": []
+}
+```
+
+**Request Example** (default, last 100 lines):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 10,
+  "method": "tools/call",
+  "params": {
+    "name": "get_device_logs",
+    "arguments": {}
+  }
+}
+```
+
+**Request Example** (filtered by tag and level):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 10,
+  "method": "tools/call",
+  "params": {
+    "name": "get_device_logs",
+    "arguments": {
+      "last_lines": 50,
+      "tag": "MCP:ServerService",
+      "level": "W"
+    }
+  }
+}
+```
+
+**Response Example (Success)**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 10,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "{\"logs\":\"02-11 16:30:00.123 D/MCP:ServerService: Server started on port 8080\\n02-11 16:30:01.456 I/MCP:ServerService: Client connected\",\"line_count\":2,\"truncated\":false}"
+      }
+    ]
+  }
+}
+```
+
+**Error Cases**:
+- `-32602`: Invalid parameter (e.g., `last_lines` out of range 1-1000, invalid `level`)
+- `-32603`: Logcat command execution failed
+
+---
+
+## 3. Touch Action Tools
+
+*To be documented in Plan 8.*
+
+Tools: `tap`, `long_press`, `double_tap`, `swipe`, `scroll`
+
+---
+
+## 4. Gesture Tools
+
+*To be documented in Plan 8.*
+
+Tools: `pinch`, `custom_gesture`
+
+---
+
+## 5. Element Action Tools
+
+*To be documented in Plan 9.*
+
+Tools: `find_elements`, `click_element`, `long_click_element`, `set_text`, `scroll_to_element`
+
+---
+
+## 6. Text Input Tools
+
+*To be documented in Plan 9.*
+
+Tools: `input_text`, `clear_text`, `press_key`
+
+---
+
+## 7. Utility Tools
+
+*To be documented in Plan 9.*
+
+Tools: `get_clipboard`, `set_clipboard`, `wait_for_element`, `wait_for_idle`
+
+---
+
+**End of MCP_TOOLS.md**
```

---

## Verification

After implementing all tasks in order, run the following commands to verify the plan:

```bash
# Run unit tests (targeted for this plan's files)
./gradlew test --tests "com.danielealbano.androidremotecontrolmcp.mcp.tools.*"

# Run all unit tests
make test-unit

# Run linters
make lint

# Build the project
make build
```

All three commands must pass without errors or warnings.

---

## Git Operations

```bash
# Create branch
git checkout -b feat/mcp-introspection-system-tools

# Commit 1: Tool registrar
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpToolException.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpContentBuilder.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt
git commit -m "feat: add tool registrar for MCP tool management

Add ToolRegistry, McpToolException, and McpContentBuilder as the
infrastructure for MCP tool registration and error handling. Update
McpServerService to register tools on startup and expose ScreenCaptureService.
Update McpProtocolHandler to handle McpToolException with correct error codes."

# Commit 2: Screen introspection tools
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt
git commit -m "feat: add screen introspection MCP tools (get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info)

Implement 4 screen introspection tool handlers that bridge the MCP protocol
to AccessibilityService and ScreenCaptureService. Each tool validates inputs,
checks service availability, and returns results in MCP content format."

# Commit 3: System action tools
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt
git commit -m "feat: add system action MCP tools (press_back, press_home, press_recents, open_notifications, open_quick_settings, get_device_logs)

Implement 6 system action tool handlers. First 5 delegate to ActionExecutor for
global accessibility actions using shared executeSystemAction() helper.
GetDeviceLogsHandler retrieves filtered logcat output via Runtime.exec()."

# Commit 4: Unit tests
git add app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt \
       app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionToolsTest.kt
git commit -m "test: add unit tests for screen introspection and system action tools

Comprehensive tests covering success paths, error paths (service unavailable,
permission denied, action failure), and parameter validation (screenshot quality
range). Uses MockK for mocking AccessibilityService, ScreenCaptureService,
AccessibilityTreeParser, and ActionExecutor."

# Commit 5: MCP Tools documentation
git add docs/MCP_TOOLS.md
git commit -m "docs: add MCP tools documentation with introspection and system action tools

Create comprehensive MCP_TOOLS.md with overview, common patterns, error codes,
and full documentation for 10 tools (4 screen introspection, 6 system actions).
Includes request/response examples, input schemas, and error cases. Placeholder
sections for remaining tool categories (Plans 8 and 9)."

# Push and create PR
git push -u origin feat/mcp-introspection-system-tools
```

---

## File Summary

| # | File | Operation | Task |
|---|------|-----------|------|
| 1 | `app/src/main/kotlin/.../mcp/tools/ToolRegistry.kt` | CREATE | 7.1 |
| 2 | `app/src/main/kotlin/.../mcp/McpToolException.kt` | CREATE | 7.2 |
| 3 | `app/src/main/kotlin/.../mcp/tools/McpContentBuilder.kt` | CREATE | 7.2 |
| 4 | `app/src/main/kotlin/.../mcp/tools/ScreenIntrospectionTools.kt` | CREATE | 7.2 |
| 5 | `app/src/main/kotlin/.../mcp/tools/SystemActionTools.kt` | CREATE | 7.3 |
| 6 | `app/src/main/kotlin/.../mcp/McpProtocolHandler.kt` | MODIFY (add McpToolException handling) | 7.2 |
| 7 | `app/src/main/kotlin/.../services/mcp/McpServerService.kt` | MODIFY (add ToolRegistry, expose ScreenCaptureService) | 7.1 |
| 8 | `app/src/test/kotlin/.../mcp/tools/ScreenIntrospectionToolsTest.kt` | CREATE | 7.4 |
| 9 | `app/src/test/kotlin/.../mcp/tools/SystemActionToolsTest.kt` | CREATE | 7.4 |
| 10 | `docs/MCP_TOOLS.md` | CREATE | 7.5 |

---

## Performance, Security, and QA Review

### Performance Considerations

1. **Accessibility tree serialization**: The `get_accessibility_tree` tool serializes the entire accessibility tree to JSON. For complex UIs with deep hierarchies, this could produce large payloads and consume significant memory. The current implementation returns the full tree (as noted in PROJECT.md: "Future optimization planned to add configurable depth limiting"). This is acceptable for Plan 7; optimization will follow once usage patterns are understood.

2. **Screenshot capture**: The `capture_screenshot` tool acquires a Mutex in `ScreenCaptureService.captureScreenshot()`, which serializes concurrent screenshot requests. This is correct behavior (only one capture at a time) but could become a bottleneck under concurrent MCP requests. For the expected use case (single AI client), this is acceptable.

3. **Root node recycling**: The `GetAccessibilityTreeHandler` calls `rootNode.recycle()` in a `finally` block after parsing. This ensures native memory is freed even if parsing fails. The parser itself also recycles child nodes internally.

### Security Considerations

1. **Singleton access pattern**: Tools access `McpAccessibilityService.instance` and `McpServerService.instance` directly. These singletons are only available when the respective services are running, and all MCP requests are authenticated via bearer token before reaching tool handlers. No security concern.

2. **No sensitive data leakage**: Tool error messages include descriptive text about what failed but do not expose internal paths, class names, or stack traces. The `McpToolException` message is user-facing.

3. **Input validation**: `CaptureScreenshotHandler` validates the `quality` parameter before use. `GetDeviceLogsHandler` validates `last_lines` (1-1000) and `level` (V/D/I/W/E/F). Other tools take no parameters. This is correct and complete for this plan's tools.

### QA Considerations

1. **Test coverage**: All 10 tools have unit tests covering success paths, error paths (service unavailable), and where applicable, parameter validation. The `CaptureScreenshotHandler` has 7 test cases covering default quality, custom quality, quality out of range (0, -1, 101), permission denied, service unavailable, and capture failure. The `GetDeviceLogsHandler` has tests for default parameters, custom `last_lines`, invalid `last_lines` range, invalid `level`, valid levels, and optional parameters.

2. **Idempotency**: All 10 tools are idempotent. `get_accessibility_tree`, `capture_screenshot`, `get_current_app`, and `get_screen_info` are read-only. System action tools (press_back, press_home, etc.) are global actions that can be safely retried -- pressing back twice from the home screen has no adverse effect. `get_device_logs` is read-only (retrieves logcat output).

3. **Thread safety**: Tool handlers are stateless classes. They access thread-safe singletons (`McpAccessibilityService.instance` is `@Volatile`) and thread-safe services (`ScreenCaptureService` uses `Mutex` for screenshot capture). No thread safety issues.

---

**End of Plan 7**
