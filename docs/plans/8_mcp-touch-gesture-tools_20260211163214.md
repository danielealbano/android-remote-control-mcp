# Plan 8: Touch & Gesture MCP Tools

**Branch**: `feat/mcp-touch-gesture-tools`
**PR Title**: `Plan 8: Touch action and gesture MCP tools`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan implements 7 MCP tools for coordinate-based touch interactions and advanced gestures. These tools bridge MCP protocol requests to the `ActionExecutor` methods established in Plan 4, enabling AI models to perform tap, long press, double tap, swipe, scroll, pinch, and custom multi-touch gestures on the Android device.

### Dependencies on Previous Plans

- **Plan 1**: Project scaffolding, Gradle build system, all dependencies
- **Plan 4**: `ActionExecutor` with methods: `tap()`, `longPress()`, `doubleTap()`, `swipe()`, `scroll()`, `pinch()`, `customGesture()`; `ScrollDirection` enum, `ScrollAmount` enum, `GesturePoint` data class
- **Plan 6**: `McpProtocolHandler` with `ToolHandler` interface, `ToolDefinition`, `JsonRpcRequest`/`JsonRpcResponse` data classes, error factory methods, error code constants
- **Plan 7**: `ToolRegistry` class that registers all tools with `McpProtocolHandler`; `ScreenIntrospectionTools.kt` and `SystemActionTools.kt` as pattern references for tool implementation

### Package Base

`com.danielealbano.androidremotecontrolmcp`

### Path References

- **Source root**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Test root**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`

### Tools Summary (7 total)

| # | Tool Name | File | Category |
|---|-----------|------|----------|
| 1 | `tap` | `TouchActionTools.kt` | Touch Action |
| 2 | `long_press` | `TouchActionTools.kt` | Touch Action |
| 3 | `double_tap` | `TouchActionTools.kt` | Touch Action |
| 4 | `swipe` | `TouchActionTools.kt` | Touch Action |
| 5 | `scroll` | `TouchActionTools.kt` | Touch Action |
| 6 | `pinch` | `GestureTools.kt` | Gesture |
| 7 | `custom_gesture` | `GestureTools.kt` | Gesture |

---

## User Story 1: Touch & Gesture MCP Tools

**As a** remote AI model connected to the Android device via MCP
**I want** to perform coordinate-based touch interactions (tap, long press, double tap, swipe, scroll) and advanced gestures (pinch, custom multi-touch gesture) via MCP tool calls
**So that** I can interact with any visible UI element on the screen using precise coordinates, scroll through content, zoom in/out with pinch gestures, and execute arbitrary multi-touch gesture sequences.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] `TapTool` validates `x` and `y` params (required, >= 0), calls `ActionExecutor.tap()`, returns MCP text content response
- [ ] `LongPressTool` validates `x`, `y` (required, >= 0) and `duration` (optional, default 1000, > 0, <= 60000), calls `ActionExecutor.longPress()`, returns MCP text content response
- [ ] `DoubleTapTool` validates `x` and `y` params (required, >= 0), calls `ActionExecutor.doubleTap()`, returns MCP text content response
- [ ] `SwipeTool` validates `x1`, `y1`, `x2`, `y2` (required, >= 0) and `duration` (optional, default 300, > 0, <= 60000), calls `ActionExecutor.swipe()`, returns MCP text content response
- [ ] `ScrollTool` validates `direction` (required, one of up/down/left/right) and `amount` (optional, default medium, one of small/medium/large), maps strings to enums, calls `ActionExecutor.scroll()`, returns MCP text content response
- [ ] `PinchTool` validates `center_x`, `center_y` (required, >= 0), `scale` (required, > 0), `duration` (optional, default 300, > 0, <= 60000), calls `ActionExecutor.pinch()`, returns MCP text content response
- [ ] `CustomGestureTool` validates `paths` (required, non-empty array, each path >= 2 points, all coords >= 0, all times >= 0, times monotonically increasing within each path), maps to `GesturePoint` lists, calls `ActionExecutor.customGesture()`, returns MCP text content response
- [ ] All 7 tools return error code `-32602` (invalid params) for missing or invalid parameters
- [ ] All 7 tools return error code `-32001` (permission denied) when accessibility service is not enabled
- [ ] All 7 tools return error code `-32003` (action failed) when ActionExecutor returns `Result.failure`
- [ ] All 7 tools handle both `Int` and `Double` JSON number types for numeric parameter extraction
- [ ] All tool responses follow MCP content format: `{ "content": [{ "type": "text", "text": "..." }] }`
- [ ] `ToolRegistry` updated to register all 7 new tools with correct names, descriptions, and input schemas
- [ ] `docs/MCP_TOOLS.md` updated with Touch Action Tools and Gesture Tools sections
- [ ] Unit tests exist and pass for all 7 tools covering valid params, missing params, invalid params, service not enabled, and action failure scenarios
- [ ] `make lint` passes with no errors or warnings
- [ ] `make test-unit` passes
- [ ] `make build` succeeds with no errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add touch action MCP tools (tap, long_press, double_tap, swipe, scroll)` | `TouchActionTools.kt`, `ToolRegistry.kt (from Plan 7)` |
| 2 | `feat: add gesture MCP tools (pinch, custom_gesture)` | `GestureTools.kt`, `ToolRegistry.kt (from Plan 7)` |
| 3 | `test: add unit tests for touch action and gesture tools` | `TouchActionToolsTest.kt`, `GestureToolsTest.kt` |
| 4 | `docs: add touch action and gesture tools to MCP_TOOLS.md` | `docs/MCP_TOOLS.md` |

---

### Task 8.1: Create Touch Action Tools (tap, long_press, double_tap, swipe, scroll)

**Description**: Create `TouchActionTools.kt` containing 5 tool handler classes that implement the `ToolHandler` interface. Each tool validates its MCP parameters, delegates to the corresponding `ActionExecutor` method, and returns an MCP-formatted response. Also update `ToolRegistry` to register these 5 tools.

**Acceptance Criteria**:
- [ ] `TapTool` implements `ToolHandler`, extracts `x` and `y` from `JsonObject`, validates >= 0, calls `ActionExecutor.tap(x, y)`, returns success text content
- [ ] `LongPressTool` implements `ToolHandler`, extracts `x`, `y`, optional `duration` (default 1000), validates coords >= 0 and duration in range (1..60000), calls `ActionExecutor.longPress(x, y, duration)`, returns success text content
- [ ] `DoubleTapTool` implements `ToolHandler`, extracts `x` and `y`, validates >= 0, calls `ActionExecutor.doubleTap(x, y)`, returns success text content
- [ ] `SwipeTool` implements `ToolHandler`, extracts `x1`, `y1`, `x2`, `y2`, optional `duration` (default 300), validates all coords >= 0 and duration in range (1..60000), calls `ActionExecutor.swipe(x1, y1, x2, y2, duration)`, returns success text content
- [ ] `ScrollTool` implements `ToolHandler`, extracts `direction` (string), optional `amount` (string, default "medium"), validates direction is one of up/down/left/right, validates amount is one of small/medium/large, maps to enums, calls `ActionExecutor.scroll(direction, amount)`, returns success text content
- [ ] All tools return `-32602` error via `McpToolErrors.invalidParams()` for missing or invalid params
- [ ] All tools return `-32001` error via `McpToolErrors.permissionDenied()` when `ActionExecutor` returns failure with `IllegalStateException` containing "not available"
- [ ] All tools return `-32003` error via `McpToolErrors.actionFailed()` when `ActionExecutor` returns failure with other exceptions
- [ ] Numeric parameter extraction handles both `Int` and `Double` JSON number types using helper function
- [ ] `ToolRegistry` updated to register `TapTool`, `LongPressTool`, `DoubleTapTool`, `SwipeTool`, `ScrollTool`
- [ ] Files compile without errors and pass ktlint/detekt

**Tests**: Unit tests in Task 8.3 (`TouchActionToolsTest.kt`).

#### Action 8.1.1: Create `TouchActionTools.kt`

**What**: Create the file containing 5 tool handler classes for touch actions plus a shared helper object for parameter extraction, validation, and response building.

**Context**: Each tool handler class implements the `ToolHandler` interface from `McpProtocolHandler.kt`. The tools extract parameters from the `JsonObject`, validate them, call the corresponding `ActionExecutor` method, and translate the `Result<Unit>` return value into either a success MCP content response or an appropriate error response thrown as an exception that the `McpProtocolHandler.handleToolCall()` catches. However, since `handleToolCall()` wraps exceptions into `-32603` (internal error), the tools should instead return the result directly as a `JsonElement` for success, and throw specific exceptions that can be caught and translated. Looking at the Plan 6 `McpProtocolHandler.handleToolCall()` implementation, it catches `Exception` and returns `internalError`. Therefore, the tool handlers must return success results as `JsonElement` and for error cases, they should return error-formatted `JsonElement` responses or we need a convention.

**Error handling convention**: Uses `McpToolException` sealed class defined in Plan 7 (`mcp/McpToolException.kt`). Tool handlers throw the appropriate subtype:
- `McpToolException.InvalidParams(message)` - maps to error code `-32602`
- `McpToolException.PermissionDenied(message)` - maps to error code `-32001`
- `McpToolException.ActionFailed(message)` - maps to error code `-32003`

The `McpProtocolHandler.handleToolCall()` catch block was updated in Plan 7 (Action 7.2.4) to catch `McpToolException` and use `e.code` to produce the correct JSON-RPC error response.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TouchActionTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TouchActionTools.kt
@@ -0,0 +1,362 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.buildJsonArray
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.put
+import javax.inject.Inject
+
+// NOTE: Uses McpToolException sealed class defined in Plan 7 (mcp/McpToolException.kt).
+// Import: com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+// Subtypes used: McpToolException.InvalidParams, McpToolException.PermissionDenied,
+//                McpToolException.ActionFailed, McpToolException.InternalError
+
+
+> **CRITICAL — Wrong file location**: `McpToolUtils` is defined as `internal object` inside `TouchActionTools.kt`, but PROJECT.md line 143 lists `McpToolUtils.kt` as a separate file in `mcp/tools/`. At implementation time, create `McpToolUtils.kt` as a standalone file in `mcp/tools/` instead of embedding it inside `TouchActionTools.kt`.
+
+/**
+ * Shared utilities for MCP tool parameter extraction and response building.
+ *
+ * Provides helper functions to extract numeric values from [JsonObject] params
+ * (handling both Int and Double JSON number types), build MCP text content
+ * responses, and translate [ActionExecutor] [Result] failures into appropriate
+ * [McpToolException] subtypes.
+ */
+internal object McpToolUtils {
+
+    /**
+     * Extracts a required numeric value from [params] as a [Float].
+     *
+     * Handles both integer and floating-point JSON numbers.
+     *
+     * @throws McpToolException.InvalidParams if the parameter is missing or not a number.
+     */
+    fun requireFloat(params: JsonObject?, name: String): Float {
+        val element = params?.get(name)
+            ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
+        return primitive.content.toFloatOrNull()
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number, got: '${primitive.content}'")
+    }
+
+    /**
+     * Extracts an optional numeric value from [params] as a [Float],
+     * returning [default] if not present.
+     *
+     * @throws McpToolException.InvalidParams if the parameter is present but not a valid number.
+     */
+    fun optionalFloat(params: JsonObject?, name: String, default: Float): Float {
+        val element = params?.get(name) ?: return default
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
+        return primitive.content.toFloatOrNull()
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number, got: '${primitive.content}'")
+    }
+
+    /**
+     * Extracts an optional numeric value from [params] as a [Long],
+     * returning [default] if not present.
+     *
+     * @throws McpToolException.InvalidParams if the parameter is present but not a valid number.
+     */
+    fun optionalLong(params: JsonObject?, name: String, default: Long): Long {
+        val element = params?.get(name) ?: return default
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
+        return primitive.content.toDoubleOrNull()?.toLong()
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number, got: '${primitive.content}'")
+    }
+
+    /**
+     * Extracts a required string value from [params].
+     *
+     * @throws McpToolException.InvalidParams if the parameter is missing or not a string.
+     */
+    fun requireString(params: JsonObject?, name: String): String {
+        val element = params?.get(name)
+            ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a string")
+        return primitive.content
+    }
+
+    /**
+     * Extracts an optional string value from [params],
+     * returning [default] if not present.
+     *
+     * @throws McpToolException.InvalidParams if the parameter is present but not a string.
+     */
+    fun optionalString(params: JsonObject?, name: String, default: String): String {
+        val element = params?.get(name) ?: return default
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a string")
+        return primitive.content
+    }
+
+    /**
+     * Validates that [value] is >= 0.
+     *
+     * @throws McpToolException.InvalidParams if validation fails.
+     */
+    fun validateNonNegative(value: Float, name: String) {
+        if (value < 0f) {
+            throw McpToolException.InvalidParams("Parameter '$name' must be >= 0, got: $value")
+        }
+    }
+
+    /**
+     * Validates that [value] is > 0 and <= [max].
+     *
+     * @throws McpToolException.InvalidParams if validation fails.
+     */
+    fun validatePositiveRange(value: Long, name: String, max: Long) {
+        if (value <= 0L || value > max) {
+            throw McpToolException.InvalidParams(
+                "Parameter '$name' must be between 1 and $max, got: $value",
+            )
+        }
+    }
+
+    /**
+     * Builds a standard MCP text content response.
+     *
+     * Returns: `{ "content": [{ "type": "text", "text": "<message>" }] }`
+     *
+     * **Implementation Note**: This method overlaps with [McpContentBuilder.textContent()]
+     * from Plan 7. At implementation time, consolidate these into a single utility to avoid
+     * duplication. Prefer delegating to [McpContentBuilder.textContent()] and remove this method,
+     * or move all response-building into [McpToolUtils] and remove [McpContentBuilder].
+     */

> **CRITICAL — Duplicate utility**: `McpToolUtils.textContentResponse()` duplicates `McpContentBuilder.textContent()` from Plan 7. At implementation time, remove `McpToolUtils.textContentResponse()` and use `McpContentBuilder.textContent()` instead. Update all tool handlers that call `McpToolUtils.textContentResponse()` or `McpToolUtils.handleActionResult()` to use `McpContentBuilder` directly.

+    fun textContentResponse(message: String): JsonElement {
+        return buildJsonObject {
+            put("content", buildJsonArray {
+                add(buildJsonObject {
+                    put("type", "text")
+                    put("text", message)
+                })
+            })
+        }
+    }
+
+    /**
+     * Translates an [ActionExecutor] [Result.failure] into the appropriate
+     * [McpToolException].
+     *
+     * - [IllegalStateException] with "not available" -> [McpToolException.PermissionDenied]
+     * - All other exceptions -> [McpToolException.ActionFailed]
+     */
+    fun handleActionResult(result: Result<Unit>, successMessage: String): JsonElement {
+        if (result.isSuccess) {
+            return textContentResponse(successMessage)
+        }
+
+        val exception = result.exceptionOrNull()!!
+        val message = exception.message ?: "Unknown error"
+
+        if (exception is IllegalStateException && message.contains("not available")) {
+            throw McpToolException.PermissionDenied(
+                "Accessibility service not enabled. Please enable it in Android Settings.",
+            )
+        }
+
+        throw McpToolException.ActionFailed(message)
+    }
+
+    /** Maximum duration in milliseconds for any gesture/action. */
+    const val MAX_DURATION_MS = 60000L
+}
+
+/**
+ * MCP tool: `tap` - Performs a single tap at the specified coordinates.
+ *
+ * Required params: `x` (number, >= 0), `y` (number, >= 0)
+ */
+class TapTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val x = McpToolUtils.requireFloat(params, "x")
+        val y = McpToolUtils.requireFloat(params, "y")
+        McpToolUtils.validateNonNegative(x, "x")
+        McpToolUtils.validateNonNegative(y, "y")
+
+        Log.d(TAG, "Executing tap at ($x, $y)")
+        val result = actionExecutor.tap(x, y)
+        return McpToolUtils.handleActionResult(result, "Tap executed at (${x.toInt()}, ${y.toInt()})")
+    }
+
+    companion object {
+        private const val TAG = "MCP:TapTool"
+    }
+}
+
+/**
+ * MCP tool: `long_press` - Performs a long press at the specified coordinates.
+ *
+ * Required params: `x` (number, >= 0), `y` (number, >= 0)
+ * Optional params: `duration` (number, > 0, <= 60000, default 1000ms)
+ */
+class LongPressTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val x = McpToolUtils.requireFloat(params, "x")
+        val y = McpToolUtils.requireFloat(params, "y")
+        val duration = McpToolUtils.optionalLong(params, "duration", DEFAULT_DURATION_MS)
+        McpToolUtils.validateNonNegative(x, "x")
+        McpToolUtils.validateNonNegative(y, "y")
+        McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)
+
+        Log.d(TAG, "Executing long press at ($x, $y) for ${duration}ms")
+        val result = actionExecutor.longPress(x, y, duration)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Long press executed at (${x.toInt()}, ${y.toInt()}) for ${duration}ms",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:LongPressTool"
+        private const val DEFAULT_DURATION_MS = 1000L
+    }
+}
+
+/**
+ * MCP tool: `double_tap` - Performs a double tap at the specified coordinates.
+ *
+ * Required params: `x` (number, >= 0), `y` (number, >= 0)
+ */
+class DoubleTapTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val x = McpToolUtils.requireFloat(params, "x")
+        val y = McpToolUtils.requireFloat(params, "y")
+        McpToolUtils.validateNonNegative(x, "x")
+        McpToolUtils.validateNonNegative(y, "y")
+
+        Log.d(TAG, "Executing double tap at ($x, $y)")
+        val result = actionExecutor.doubleTap(x, y)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Double tap executed at (${x.toInt()}, ${y.toInt()})",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:DoubleTapTool"
+    }
+}
+
+/**
+ * MCP tool: `swipe` - Performs a swipe gesture from one point to another.
+ *
+ * Required params: `x1`, `y1`, `x2`, `y2` (numbers, >= 0)
+ * Optional params: `duration` (number, > 0, <= 60000, default 300ms)
+ */
+class SwipeTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val x1 = McpToolUtils.requireFloat(params, "x1")
+        val y1 = McpToolUtils.requireFloat(params, "y1")
+        val x2 = McpToolUtils.requireFloat(params, "x2")
+        val y2 = McpToolUtils.requireFloat(params, "y2")
+        val duration = McpToolUtils.optionalLong(params, "duration", DEFAULT_DURATION_MS)
+        McpToolUtils.validateNonNegative(x1, "x1")
+        McpToolUtils.validateNonNegative(y1, "y1")
+        McpToolUtils.validateNonNegative(x2, "x2")
+        McpToolUtils.validateNonNegative(y2, "y2")
+        McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)
+
+        Log.d(TAG, "Executing swipe from ($x1, $y1) to ($x2, $y2) over ${duration}ms")
+        val result = actionExecutor.swipe(x1, y1, x2, y2, duration)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Swipe executed from (${x1.toInt()}, ${y1.toInt()}) to (${x2.toInt()}, ${y2.toInt()}) over ${duration}ms",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:SwipeTool"
+        private const val DEFAULT_DURATION_MS = 300L
+    }
+}
+
+/**
+ * MCP tool: `scroll` - Scrolls the screen in a specified direction.
+ *
+ * Required params: `direction` (string: "up", "down", "left", "right")
+ * Optional params: `amount` (string: "small", "medium", "large", default "medium")
+ */
+class ScrollTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val directionStr = McpToolUtils.requireString(params, "direction")
+        val amountStr = McpToolUtils.optionalString(params, "amount", "medium")
+
+        val direction = when (directionStr.lowercase()) {
+            "up" -> ScrollDirection.UP
+            "down" -> ScrollDirection.DOWN
+            "left" -> ScrollDirection.LEFT
+            "right" -> ScrollDirection.RIGHT
+            else -> throw McpToolException.InvalidParams(
+                "Parameter 'direction' must be one of: up, down, left, right. Got: '$directionStr'",
+            )
+        }
+
+        val amount = when (amountStr.lowercase()) {
+            "small" -> ScrollAmount.SMALL
+            "medium" -> ScrollAmount.MEDIUM
+            "large" -> ScrollAmount.LARGE
+            else -> throw McpToolException.InvalidParams(
+                "Parameter 'amount' must be one of: small, medium, large. Got: '$amountStr'",
+            )
+        }
+
+        Log.d(TAG, "Executing scroll ${direction.name} with amount ${amount.name}")
+        val result = actionExecutor.scroll(direction, amount)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Scroll ${directionStr.lowercase()} (${amountStr.lowercase()}) executed",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:ScrollTool"
+    }
+}
```

> **Implementation Notes**:
> - `McpToolUtils.requireFloat()` uses `toFloatOrNull()` on the `JsonPrimitive.content` string, which handles both integer (`"500"`) and floating-point (`"500.5"`) JSON numbers transparently.
> - `McpToolUtils.optionalLong()` first parses as `Double` then converts to `Long` with `toLong()`. This handles JSON numbers like `300` (which kotlinx.serialization may represent as `300.0` internally) as well as explicit integers.
> - `McpToolUtils.handleActionResult()` inspects the `Result.failure` exception type: `IllegalStateException` with "not available" in the message indicates the accessibility service is not enabled (maps to `-32001`), while all other failures map to `-32003`.
> - `McpToolException` is the sealed class hierarchy defined in Plan 7 (`mcp/McpToolException.kt`). This file imports it via `import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException`. Subtypes used: `InvalidParams`, `PermissionDenied`, `ActionFailed`.
> - `ScrollTool` uses `lowercase()` for case-insensitive matching of direction and amount strings.
> - All coordinate values displayed in response messages use `toInt()` for clean integer display.

---

#### Action 8.1.2: Update `McpProtocolHandler.handleToolCall()` for McpToolException

**What**: Verify that `handleToolCall()` in `McpProtocolHandler` handles `McpToolException` correctly.

**Context**: Plan 7 already updated `handleToolCall()` to catch `McpToolException` (the sealed base class) and use `e.code` to produce the correct JSON-RPC error response. Since the sealed class hierarchy includes `InvalidParams`, `InternalError`, `PermissionDenied`, `ElementNotFound`, `ActionFailed`, and `Timeout` -- all carrying their own `code` -- a single catch block handles all subtypes. This action is a **no-op** if Plan 7 was implemented first. The implementer MUST verify the catch block exists before proceeding.

> **Implementation Note**: Plan 7 already handles `McpToolException` with a single catch block using `e.code`. No changes needed here. If Plan 8 is implemented before Plan 7, the implementer must add the catch block from Plan 7 Action 7.2.4.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/McpProtocolHandler.kt`

No diff required -- already handled by Plan 7 Action 7.2.4.

---

#### Action 8.1.3: Update `ToolRegistry` to register touch action tools

**What**: Add registration of `TapTool`, `LongPressTool`, `DoubleTapTool`, `SwipeTool`, and `ScrollTool` in `ToolRegistry`.

**Context**: The `ToolRegistry` (from Plan 7) is responsible for registering all MCP tools with `McpProtocolHandler` at startup. Each tool registration requires a name, description, JSON input schema, and handler instance. The tool handler instances are created with the injected `ActionExecutor`. Input schemas match the specifications in PROJECT.md exactly.

> **IMPORTANT — Constructor evolution**: Plan 7 defined `ToolRegistry @Inject constructor()` with an empty constructor. This plan **replaces** that constructor to add `protocolHandler: McpProtocolHandler` and `actionExecutor: ActionExecutor` as constructor parameters for Hilt injection. Plan 9 will further expand this constructor with additional tool dependencies. Each plan's constructor definition supersedes the previous one.

> **CRITICAL — ToolRegistry constructor evolution**: Plan 7 defines `ToolRegistry @Inject constructor()` (empty). This plan adds `protocolHandler: McpProtocolHandler, actionExecutor: ActionExecutor`. Plan 9 further adds 12 tool instance parameters. These are incompatible changes. Additionally, tool classes have `@Inject constructor(...)` annotations but `registerTouchActionTools()` manually instantiates them via `TapTool(actionExecutor)`, making the `@Inject` annotations dead code. At implementation time, choose ONE consistent pattern: either (a) inject all tool instances into `ToolRegistry` constructor (Plan 9 pattern) and remove manual instantiation, or (b) use manual instantiation and remove `@Inject` from tool classes. The Plan 9 pattern (constructor injection) is recommended since it leverages Hilt for all dependency resolution.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)
@@ -XX,6 +XX,8 @@

 import com.danielealbano.androidremotecontrolmcp.mcp.McpProtocolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
 import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.putJsonArray
 import kotlinx.serialization.json.putJsonObject
 import kotlinx.serialization.json.put
 import javax.inject.Inject
@@ -XX,6 +XX,8 @@
 class ToolRegistry @Inject constructor(
     private val protocolHandler: McpProtocolHandler,
+    private val actionExecutor: ActionExecutor,
     // ... existing dependencies ...
 ) {

     fun registerAll() {
         // ... existing tool registrations from Plan 7 ...
+        registerTouchActionTools()
     }

+    private fun registerTouchActionTools() {
+        register(
+            name = "tap",
+            description = "Performs a single tap at the specified coordinates.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("x") {
+                        put("type", "number")
+                        put("description", "X coordinate")
+                    }
+                    putJsonObject("y") {
+                        put("type", "number")
+                        put("description", "Y coordinate")
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("x"))
+                    add(kotlinx.serialization.json.JsonPrimitive("y"))
+                })
+            },
+            handler = TapTool(actionExecutor),
+        )
+
+        register(
+            name = "long_press",
+            description = "Performs a long press at the specified coordinates.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("x") {
+                        put("type", "number")
+                        put("description", "X coordinate")
+                    }
+                    putJsonObject("y") {
+                        put("type", "number")
+                        put("description", "Y coordinate")
+                    }
+                    putJsonObject("duration") {
+                        put("type", "number")
+                        put("description", "Press duration in ms")
+                        put("default", 1000)
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("x"))
+                    add(kotlinx.serialization.json.JsonPrimitive("y"))
+                })
+            },
+            handler = LongPressTool(actionExecutor),
+        )
+
+        register(
+            name = "double_tap",
+            description = "Performs a double tap at the specified coordinates.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("x") {
+                        put("type", "number")
+                        put("description", "X coordinate")
+                    }
+                    putJsonObject("y") {
+                        put("type", "number")
+                        put("description", "Y coordinate")
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("x"))
+                    add(kotlinx.serialization.json.JsonPrimitive("y"))
+                })
+            },
+            handler = DoubleTapTool(actionExecutor),
+        )
+
+        register(
+            name = "swipe",
+            description = "Performs a swipe gesture from one point to another.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("x1") {
+                        put("type", "number")
+                        put("description", "Start X coordinate")
+                    }
+                    putJsonObject("y1") {
+                        put("type", "number")
+                        put("description", "Start Y coordinate")
+                    }
+                    putJsonObject("x2") {
+                        put("type", "number")
+                        put("description", "End X coordinate")
+                    }
+                    putJsonObject("y2") {
+                        put("type", "number")
+                        put("description", "End Y coordinate")
+                    }
+                    putJsonObject("duration") {
+                        put("type", "number")
+                        put("description", "Swipe duration in ms")
+                        put("default", 300)
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("x1"))
+                    add(kotlinx.serialization.json.JsonPrimitive("y1"))
+                    add(kotlinx.serialization.json.JsonPrimitive("x2"))
+                    add(kotlinx.serialization.json.JsonPrimitive("y2"))
+                })
+            },
+            handler = SwipeTool(actionExecutor),
+        )
+
+        register(
+            name = "scroll",
+            description = "Scrolls in the specified direction.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("direction") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            add(kotlinx.serialization.json.JsonPrimitive("up"))
+                            add(kotlinx.serialization.json.JsonPrimitive("down"))
+                            add(kotlinx.serialization.json.JsonPrimitive("left"))
+                            add(kotlinx.serialization.json.JsonPrimitive("right"))
+                        })
+                    }
+                    putJsonObject("amount") {
+                        put("type", "string")
+                        put("enum", buildJsonArray {
+                            add(kotlinx.serialization.json.JsonPrimitive("small"))
+                            add(kotlinx.serialization.json.JsonPrimitive("medium"))
+                            add(kotlinx.serialization.json.JsonPrimitive("large"))
+                        })
+                        put("default", "medium")
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("direction"))
+                })
+            },
+            handler = ScrollTool(actionExecutor),
+        )
+    }
```

---

### Task 8.2: Create Gesture Tools (pinch, custom_gesture)

**Description**: Create `GestureTools.kt` containing 2 tool handler classes for advanced gesture operations. Also update `ToolRegistry` to register these 2 tools.

**Acceptance Criteria**:
- [ ] `PinchTool` implements `ToolHandler`, extracts `center_x`, `center_y` (required, >= 0), `scale` (required, > 0), `duration` (optional, default 300, > 0, <= 60000), calls `ActionExecutor.pinch(centerX, centerY, scale, duration)`, returns success text content
- [ ] `CustomGestureTool` implements `ToolHandler`, extracts `paths` (required, non-empty array of arrays of point objects), validates each path has >= 2 points, all coords >= 0, all times >= 0, times monotonically increasing within each path, maps to `List<List<GesturePoint>>`, calls `ActionExecutor.customGesture(paths)`, returns success text content
- [ ] `PinchTool` returns `-32602` for missing params, invalid scale (<= 0), invalid duration
- [ ] `CustomGestureTool` returns `-32602` for empty paths, short paths (< 2 points), negative coords, negative times, non-monotonic times
- [ ] Both tools return `-32001` when accessibility service not enabled
- [ ] Both tools return `-32003` when action execution fails
- [ ] `ToolRegistry` updated to register `PinchTool` and `CustomGestureTool`
- [ ] Files compile without errors and pass ktlint/detekt

**Tests**: Unit tests in Task 8.3 (`GestureToolsTest.kt`).

#### Action 8.2.1: Create `GestureTools.kt`

**What**: Create the file containing `PinchTool` and `CustomGestureTool` handler classes.

**Context**: These tools handle more complex gesture operations. `PinchTool` is straightforward parameter extraction and delegation. `CustomGestureTool` requires parsing a nested JSON array structure (`paths` is an array of arrays of objects, each with `x`, `y`, `time` fields) and performing thorough validation before delegating to `ActionExecutor.customGesture()`. Both tools reuse the `McpToolUtils` and `McpToolException` from `TouchActionTools.kt`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GestureTools.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GestureTools.kt
@@ -0,0 +1,174 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import android.util.Log
+import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
+import com.danielealbano.androidremotecontrolmcp.mcp.ToolHandler
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.GesturePoint
+import kotlinx.serialization.json.JsonArray
+import kotlinx.serialization.json.JsonElement
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import javax.inject.Inject
+
+/**
+ * MCP tool: `pinch` - Performs a pinch-to-zoom gesture.
+ *
+ * Required params: `center_x` (number, >= 0), `center_y` (number, >= 0), `scale` (number, > 0)
+ * Optional params: `duration` (number, > 0, <= 60000, default 300ms)
+ *
+ * Scale > 1 = zoom in (fingers move apart), scale < 1 = zoom out (fingers come together).
+ */
+class PinchTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val centerX = McpToolUtils.requireFloat(params, "center_x")
+        val centerY = McpToolUtils.requireFloat(params, "center_y")
+        val scale = McpToolUtils.requireFloat(params, "scale")
+        val duration = McpToolUtils.optionalLong(params, "duration", DEFAULT_DURATION_MS)
+
+        McpToolUtils.validateNonNegative(centerX, "center_x")
+        McpToolUtils.validateNonNegative(centerY, "center_y")
+
+        if (scale <= 0f) {
+            throw McpToolException.InvalidParams(
+                "Parameter 'scale' must be > 0, got: $scale",
+            )
+        }
+
+        McpToolUtils.validatePositiveRange(duration, "duration", McpToolUtils.MAX_DURATION_MS)
+
+        val zoomType = if (scale > 1f) "in" else "out"
+        Log.d(TAG, "Executing pinch (zoom $zoomType) at ($centerX, $centerY) scale=$scale, ${duration}ms")
+        val result = actionExecutor.pinch(centerX, centerY, scale, duration)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Pinch (zoom $zoomType) executed at (${centerX.toInt()}, ${centerY.toInt()}) with scale $scale over ${duration}ms",
+        )
+    }
+
+    companion object {
+        private const val TAG = "MCP:PinchTool"
+        private const val DEFAULT_DURATION_MS = 300L
+    }
+}
+
+/**
+ * MCP tool: `custom_gesture` - Executes a custom multi-touch gesture defined by path points.
+ *
+ * Required params: `paths` (array of arrays of point objects)
+ *
+ * Each point object has: `x` (number, >= 0), `y` (number, >= 0), `time` (number, >= 0, ms offset).
+ * Each path must have at least 2 points.
+ * Times must be monotonically increasing within each path.
+ */
+class CustomGestureTool @Inject constructor(
+    private val actionExecutor: ActionExecutor,
+) : ToolHandler {
+
+    override suspend fun execute(params: JsonObject?): JsonElement {
+        val pathsElement = params?.get("paths")
+            ?: throw McpToolException.InvalidParams("Missing required parameter: 'paths'")
+
+        val pathsArray = pathsElement as? JsonArray
+            ?: throw McpToolException.InvalidParams("Parameter 'paths' must be an array")
+
+        if (pathsArray.isEmpty()) {
+            throw McpToolException.InvalidParams("Parameter 'paths' must not be empty")
+        }
+
+        val paths = mutableListOf<List<GesturePoint>>()
+
+        for ((pathIndex, pathElement) in pathsArray.withIndex()) {
+            val pointsArray = pathElement as? JsonArray
+                ?: throw McpToolException.InvalidParams(
+                    "Path at index $pathIndex must be an array of point objects",
+                )
+
+            if (pointsArray.size < 2) {
+                throw McpToolException.InvalidParams(
+                    "Path at index $pathIndex must have at least 2 points, has ${pointsArray.size}",
+                )
+            }
+
+            val points = mutableListOf<GesturePoint>()
+            var previousTime = -1L
+
+            for ((pointIndex, pointElement) in pointsArray.withIndex()) {
+                val pointObj = pointElement as? JsonObject
+                    ?: throw McpToolException.InvalidParams(
+                        "Point at path[$pathIndex][$pointIndex] must be an object with x, y, time",
+                    )
+
+                val x = extractPointFloat(pointObj, "x", pathIndex, pointIndex)
+                val y = extractPointFloat(pointObj, "y", pathIndex, pointIndex)
+                val time = extractPointLong(pointObj, "time", pathIndex, pointIndex)
+
+                if (x < 0f) {
+                    throw McpToolException.InvalidParams(
+                        "Point at path[$pathIndex][$pointIndex].x must be >= 0, got: $x",
+                    )
+                }
+                if (y < 0f) {
+                    throw McpToolException.InvalidParams(
+                        "Point at path[$pathIndex][$pointIndex].y must be >= 0, got: $y",
+                    )
+                }
+                if (time < 0L) {
+                    throw McpToolException.InvalidParams(
+                        "Point at path[$pathIndex][$pointIndex].time must be >= 0, got: $time",
+                    )
+                }
+                if (time <= previousTime) {
+                    throw McpToolException.InvalidParams(
+                        "Times must be monotonically increasing within each path. " +
+                            "path[$pathIndex][$pointIndex].time ($time) <= previous time ($previousTime)",
+                    )
+                }
+
+                previousTime = time
+                points.add(GesturePoint(x, y, time))
+            }
+
+            paths.add(points)
+        }
+
+        Log.d(TAG, "Executing custom gesture with ${paths.size} path(s)")
+        val result = actionExecutor.customGesture(paths)
+        return McpToolUtils.handleActionResult(
+            result,
+            "Custom gesture executed with ${paths.size} path(s), " +
+                "total ${paths.sumOf { it.size }} point(s)",
+        )
+    }
+
+    private fun extractPointFloat(
+        obj: JsonObject,
+        field: String,
+        pathIndex: Int,
+        pointIndex: Int,
+    ): Float {
+        val element = obj[field]
+            ?: throw McpToolException.InvalidParams(
+                "Missing '$field' in point at path[$pathIndex][$pointIndex]",
+            )
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams(
+                "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number",
+            )
+        return primitive.content.toFloatOrNull()
+            ?: throw McpToolException.InvalidParams(
+                "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, got: '${primitive.content}'",
+            )
+    }
+
+    private fun extractPointLong(
+        obj: JsonObject,
+        field: String,
+        pathIndex: Int,
+        pointIndex: Int,
+    ): Long {
+        val element = obj[field]
+            ?: throw McpToolException.InvalidParams(
+                "Missing '$field' in point at path[$pathIndex][$pointIndex]",
+            )
+        val primitive = element as? JsonPrimitive
+            ?: throw McpToolException.InvalidParams(
+                "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number",
+            )
+        return primitive.content.toDoubleOrNull()?.toLong()
+            ?: throw McpToolException.InvalidParams(
+                "Field '$field' in point at path[$pathIndex][$pointIndex] must be a number, got: '${primitive.content}'",
+            )
+    }
+
+    companion object {
+        private const val TAG = "MCP:CustomGestureTool"
+    }
+}
```

> **Implementation Notes**:
> - `CustomGestureTool` performs comprehensive validation of the nested JSON structure. Each level (paths array, individual path array, point objects, point fields) has explicit error messages with indices for debuggability.
> - Monotonic time validation uses `previousTime` initialized to `-1L` so the first point's time (which can be `0`) passes validation. Subsequent points must have strictly increasing times (`time <= previousTime` triggers the error for equal times too, enforcing strict monotonicity).
> - The `extractPointFloat` and `extractPointLong` helper methods are private to `CustomGestureTool` (not in `McpToolUtils`) because they include path/point index context in error messages that is specific to the nested structure.
> - `PinchTool` validates `scale > 0` explicitly rather than using `validatePositiveRange` because scale is a `Float` (not `Long`) with no upper bound.

---

#### Action 8.2.2: Update `ToolRegistry` to register gesture tools

**What**: Add registration of `PinchTool` and `CustomGestureTool` in `ToolRegistry`.

**Context**: Extends the `registerAll()` method to call a new `registerGestureTools()` private method. Input schemas match PROJECT.md specifications exactly.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ToolRegistry.kt (from Plan 7)
@@ -XX,6 +XX,7 @@
     fun registerAll() {
         // ... existing tool registrations from Plan 7 ...
         registerTouchActionTools()
+        registerGestureTools()
     }

+    private fun registerGestureTools() {
+        register(
+            name = "pinch",
+            description = "Performs a pinch-to-zoom gesture.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("center_x") {
+                        put("type", "number")
+                        put("description", "Center X coordinate")
+                    }
+                    putJsonObject("center_y") {
+                        put("type", "number")
+                        put("description", "Center Y coordinate")
+                    }
+                    putJsonObject("scale") {
+                        put("type", "number")
+                        put("description", "Scale factor (>1 = zoom in, <1 = zoom out)")
+                    }
+                    putJsonObject("duration") {
+                        put("type", "number")
+                        put("description", "Gesture duration in ms")
+                        put("default", 300)
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("center_x"))
+                    add(kotlinx.serialization.json.JsonPrimitive("center_y"))
+                    add(kotlinx.serialization.json.JsonPrimitive("scale"))
+                })
+            },
+            handler = PinchTool(actionExecutor),
+        )
+
+        register(
+            name = "custom_gesture",
+            description = "Executes a custom multi-touch gesture defined by path points.",
+            inputSchema = buildJsonObject {
+                put("type", "object")
+                putJsonObject("properties") {
+                    putJsonObject("paths") {
+                        put("type", "array")
+                        putJsonObject("items") {
+                            put("type", "array")
+                            putJsonObject("items") {
+                                put("type", "object")
+                                putJsonObject("properties") {
+                                    putJsonObject("x") {
+                                        put("type", "number")
+                                    }
+                                    putJsonObject("y") {
+                                        put("type", "number")
+                                    }
+                                    putJsonObject("time") {
+                                        put("type", "number")
+                                        put("description", "Time offset in ms")
+                                    }
+                                }
+                            }
+                        }
+                    }
+                }
+                put("required", buildJsonArray {
+                    add(kotlinx.serialization.json.JsonPrimitive("paths"))
+                })
+            },
+            handler = CustomGestureTool(actionExecutor),
+        )
+    }
```

---

### Task 8.3: Unit Tests for Touch Action and Gesture Tools

**Description**: Create comprehensive unit tests for all 7 tools using JUnit 5 and MockK. Tests cover valid parameters, missing parameters, invalid parameters, service-not-enabled errors, and action-failure errors. `ActionExecutor` is mocked in all tests.

**Acceptance Criteria**:
- [ ] `TouchActionToolsTest.kt` tests all 5 touch action tools
- [ ] `GestureToolsTest.kt` tests both gesture tools
- [ ] Tests cover: valid params, missing required params, negative coordinates, invalid duration, invalid direction/amount, service not enabled, action failure
- [ ] Tests verify correct MCP text content response format for success cases
- [ ] Tests verify correct `McpToolException` subtype and message for error cases
- [ ] All tests mock `ActionExecutor` using MockK
- [ ] All tests use JUnit 5 `@Test` and `@Nested` for organization
- [ ] All tests follow Arrange-Act-Assert pattern
- [ ] Tests compile and pass
- [ ] Tests pass ktlint/detekt

**Tests**: These ARE the tests. Run via `./gradlew test --tests "*TouchActionToolsTest"` and `./gradlew test --tests "*GestureToolsTest"`.

#### Action 8.3.1: Create `TouchActionToolsTest.kt`

**What**: Create unit tests for `TapTool`, `LongPressTool`, `DoubleTapTool`, `SwipeTool`, and `ScrollTool`.

**Context**: Each tool is tested independently with a mocked `ActionExecutor`. Tests are organized using `@Nested` inner classes for each tool. The `ActionExecutor` mock is configured per test to return either `Result.success(Unit)` or `Result.failure(exception)` depending on the scenario. `McpToolException` subtypes are verified using `assertThrows`. The MCP text content response is verified by parsing the returned `JsonElement`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TouchActionToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TouchActionToolsTest.kt
@@ -0,0 +1,470 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollAmount
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScrollDirection
+import io.mockk.coEvery
+import io.mockk.coVerify
+import io.mockk.mockk
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.JsonObject
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonArray
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertThrows
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("TouchActionTools")
+class TouchActionToolsTest {
+
+    private lateinit var actionExecutor: ActionExecutor
+
+    @BeforeEach
+    fun setUp() {
+        actionExecutor = mockk()
+    }
+
+    /**
+     * Extracts the text content from a standard MCP response.
+     * Expected format: { "content": [{ "type": "text", "text": "..." }] }
+     */
+    private fun extractTextContent(result: kotlinx.serialization.json.JsonElement): String {
+        val content = result.jsonObject["content"]!!.jsonArray
+        assertEquals(1, content.size)
+        val item = content[0].jsonObject
+        assertEquals("text", item["type"]!!.jsonPrimitive.content)
+        return item["text"]!!.jsonPrimitive.content
+    }
+
+    @Nested
+    @DisplayName("TapTool")
+    inner class TapToolTests {
+
+        private lateinit var tool: TapTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = TapTool(actionExecutor)
+        }
+
+        @Test
+        fun `tap with valid coordinates returns success`() = runTest {
+            coEvery { actionExecutor.tap(500f, 1000f) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Tap executed"))
+            assertTrue(text.contains("500"))
+            assertTrue(text.contains("1000"))
+            coVerify(exactly = 1) { actionExecutor.tap(500f, 1000f) }
+        }
+
+        @Test
+        fun `tap with float coordinates returns success`() = runTest {
+            coEvery { actionExecutor.tap(500.5f, 1000.3f) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x", 500.5)
+                put("y", 1000.3)
+            }
+            val result = tool.execute(params)
+
+            extractTextContent(result)
+            coVerify(exactly = 1) { actionExecutor.tap(500.5f, 1000.3f) }
+        }
+
+        @Test
+        fun `tap with missing x returns invalid params`() = runTest {
+            val params = buildJsonObject {
+                put("y", 1000)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("x"))
+        }
+
+        @Test
+        fun `tap with missing y returns invalid params`() = runTest {
+            val params = buildJsonObject {
+                put("x", 500)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("y"))
+        }
+
+        @Test
+        fun `tap with null params returns invalid params`() {
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(null) }
+            }
+            assertTrue(exception.message!!.contains("x"))
+        }
+
+        @Test
+        fun `tap with negative x returns invalid params`() {
+            val params = buildJsonObject {
+                put("x", -1)
+                put("y", 1000)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("x"))
+            assertTrue(exception.message!!.contains(">= 0"))
+        }
+
+        @Test
+        fun `tap with negative y returns invalid params`() {
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", -5)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("y"))
+        }
+
+        @Test
+        fun `tap when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.tap(any(), any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+
+            val exception = assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("Accessibility service"))
+        }
+
+        @Test
+        fun `tap when action fails returns action failed`() {
+            coEvery { actionExecutor.tap(any(), any()) } returns Result.failure(
+                RuntimeException("Gesture cancelled"),
+            )
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+
+            val exception = assertThrows(McpToolException.ActionFailed::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("Gesture cancelled"))
+        }
+    }
+
+    @Nested
+    @DisplayName("LongPressTool")
+    inner class LongPressToolTests {
+
+        private lateinit var tool: LongPressTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = LongPressTool(actionExecutor)
+        }
+
+        @Test
+        fun `long press with default duration returns success`() = runTest {
+            coEvery { actionExecutor.longPress(500f, 1000f, 1000L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Long press"))
+            assertTrue(text.contains("1000ms"))
+            coVerify(exactly = 1) { actionExecutor.longPress(500f, 1000f, 1000L) }
+        }
+
+        @Test
+        fun `long press with custom duration returns success`() = runTest {
+            coEvery { actionExecutor.longPress(500f, 1000f, 2000L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+                put("duration", 2000)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("2000ms"))
+            coVerify(exactly = 1) { actionExecutor.longPress(500f, 1000f, 2000L) }
+        }
+
+        @Test
+        fun `long press with zero duration returns invalid params`() {
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+                put("duration", 0)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("duration"))
+        }
+
+        @Test
+        fun `long press with duration exceeding max returns invalid params`() {
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+                put("duration", 60001)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("duration"))
+            assertTrue(exception.message!!.contains("60000"))
+        }
+
+        @Test
+        fun `long press when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.longPress(any(), any(), any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+
+            assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+
+    @Nested
+    @DisplayName("DoubleTapTool")
+    inner class DoubleTapToolTests {
+
+        private lateinit var tool: DoubleTapTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = DoubleTapTool(actionExecutor)
+        }
+
+        @Test
+        fun `double tap with valid coordinates returns success`() = runTest {
+            coEvery { actionExecutor.doubleTap(500f, 1000f) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Double tap"))
+            coVerify(exactly = 1) { actionExecutor.doubleTap(500f, 1000f) }
+        }
+
+        @Test
+        fun `double tap with missing params returns invalid params`() {
+            assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(null) }
+            }
+        }
+
+        @Test
+        fun `double tap when action fails returns action failed`() {
+            coEvery { actionExecutor.doubleTap(any(), any()) } returns Result.failure(
+                RuntimeException("Failed to dispatch gesture"),
+            )
+
+            val params = buildJsonObject {
+                put("x", 500)
+                put("y", 1000)
+            }
+
+            assertThrows(McpToolException.ActionFailed::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+
+    @Nested
+    @DisplayName("SwipeTool")
+    inner class SwipeToolTests {
+
+        private lateinit var tool: SwipeTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = SwipeTool(actionExecutor)
+        }
+
+        @Test
+        fun `swipe with valid coordinates returns success`() = runTest {
+            coEvery { actionExecutor.swipe(100f, 200f, 300f, 400f, 300L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x1", 100)
+                put("y1", 200)
+                put("x2", 300)
+                put("y2", 400)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Swipe executed"))
+            coVerify(exactly = 1) { actionExecutor.swipe(100f, 200f, 300f, 400f, 300L) }
+        }
+
+        @Test
+        fun `swipe with custom duration returns success`() = runTest {
+            coEvery { actionExecutor.swipe(100f, 200f, 300f, 400f, 500L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("x1", 100)
+                put("y1", 200)
+                put("x2", 300)
+                put("y2", 400)
+                put("duration", 500)
+            }
+            val result = tool.execute(params)
+
+            extractTextContent(result)
+            coVerify(exactly = 1) { actionExecutor.swipe(100f, 200f, 300f, 400f, 500L) }
+        }
+
+        @Test
+        fun `swipe with missing x2 returns invalid params`() {
+            val params = buildJsonObject {
+                put("x1", 100)
+                put("y1", 200)
+                put("y2", 400)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("x2"))
+        }
+
+        @Test
+        fun `swipe with negative coordinate returns invalid params`() {
+            val params = buildJsonObject {
+                put("x1", -5)
+                put("y1", 200)
+                put("x2", 300)
+                put("y2", 400)
+            }
+
+            assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `swipe when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.swipe(any(), any(), any(), any(), any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("x1", 100)
+                put("y1", 200)
+                put("x2", 300)
+                put("y2", 400)
+            }
+
+            assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+
+    @Nested
+    @DisplayName("ScrollTool")
+    inner class ScrollToolTests {
+
+        private lateinit var tool: ScrollTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = ScrollTool(actionExecutor)
+        }
+
+        @Test
+        fun `scroll down with default amount returns success`() = runTest {
+            coEvery { actionExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("direction", "down")
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Scroll down"))
+            coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.DOWN, ScrollAmount.MEDIUM) }
+        }
+
+        @Test
+        fun `scroll up with large amount returns success`() = runTest {
+            coEvery { actionExecutor.scroll(ScrollDirection.UP, ScrollAmount.LARGE) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("direction", "up")
+                put("amount", "large")
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Scroll up"))
+            assertTrue(text.contains("large"))
+            coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.UP, ScrollAmount.LARGE) }
+        }
+
+        @Test
+        fun `scroll with case-insensitive direction returns success`() = runTest {
+            coEvery { actionExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.MEDIUM) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("direction", "LEFT")
+            }
+            tool.execute(params)
+            coVerify(exactly = 1) { actionExecutor.scroll(ScrollDirection.LEFT, ScrollAmount.MEDIUM) }
+        }
+
+        @Test
+        fun `scroll with invalid direction returns invalid params`() {
+            val params = buildJsonObject {
+                put("direction", "diagonal")
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("direction"))
+            assertTrue(exception.message!!.contains("diagonal"))
+        }
+
+        @Test
+        fun `scroll with invalid amount returns invalid params`() {
+            val params = buildJsonObject {
+                put("direction", "down")
+                put("amount", "huge")
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("amount"))
+            assertTrue(exception.message!!.contains("huge"))
+        }
+
+        @Test
+        fun `scroll with missing direction returns invalid params`() {
+            val params = buildJsonObject {
+                put("amount", "small")
+            }
+
+            assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `scroll when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.scroll(any(), any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("direction", "down")
+            }
+
+            assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `scroll when action fails returns action failed`() {
+            coEvery { actionExecutor.scroll(any(), any()) } returns Result.failure(
+                RuntimeException("No root node available for screen dimensions"),
+            )
+
+            val params = buildJsonObject {
+                put("direction", "down")
+            }
+
+            assertThrows(McpToolException.ActionFailed::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+}
```

> **Implementation Notes**:
> - Tests use `kotlinx.coroutines.test.runTest` for suspend function testing.
> - `assertThrows` wraps the suspend call in a nested `runTest` block to catch exceptions from suspend functions.
> - `extractTextContent` is a shared helper that validates the MCP response format and extracts the text string for assertion.
> - MockK `coEvery` is used for mocking suspend functions on `ActionExecutor`.
> - Each tool's `@Nested` class has its own `@BeforeEach` to create a fresh tool instance.
> - The `actionExecutor` mock is shared across all nested classes via the outer class `@BeforeEach`.
> - Tests verify both the response content (text contains expected strings) and the mock interactions (`coVerify`).
> - At implementation time, the exact `assertThrows` pattern for coroutines may need adjustment based on the testing framework behavior. If `assertThrows` does not work with `runTest`, use `try/catch` with `fail()` instead.

---

#### Action 8.3.2: Create `GestureToolsTest.kt`

**What**: Create unit tests for `PinchTool` and `CustomGestureTool`.

**Context**: Follows the same testing patterns as `TouchActionToolsTest.kt`. `CustomGestureTool` tests require building nested JSON structures (arrays of arrays of objects) for the `paths` parameter. Tests cover all validation scenarios including empty paths, short paths, negative coordinates, negative times, and non-monotonic times.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GestureToolsTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GestureToolsTest.kt
@@ -0,0 +1,380 @@
+package com.danielealbano.androidremotecontrolmcp.mcp.tools
+
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.GesturePoint
+import io.mockk.coEvery
+import io.mockk.coVerify
+import io.mockk.mockk
+import kotlinx.coroutines.test.runTest
+import kotlinx.serialization.json.JsonPrimitive
+import kotlinx.serialization.json.buildJsonArray
+import kotlinx.serialization.json.buildJsonObject
+import kotlinx.serialization.json.jsonArray
+import kotlinx.serialization.json.jsonObject
+import kotlinx.serialization.json.jsonPrimitive
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertThrows
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("GestureTools")
+class GestureToolsTest {
+
+    private lateinit var actionExecutor: ActionExecutor
+
+    @BeforeEach
+    fun setUp() {
+        actionExecutor = mockk()
+    }
+
+    /**
+     * Extracts the text content from a standard MCP response.
+     */
+    private fun extractTextContent(result: kotlinx.serialization.json.JsonElement): String {
+        val content = result.jsonObject["content"]!!.jsonArray
+        assertEquals(1, content.size)
+        val item = content[0].jsonObject
+        assertEquals("text", item["type"]!!.jsonPrimitive.content)
+        return item["text"]!!.jsonPrimitive.content
+    }
+
+    @Nested
+    @DisplayName("PinchTool")
+    inner class PinchToolTests {
+
+        private lateinit var tool: PinchTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = PinchTool(actionExecutor)
+        }
+
+        @Test
+        fun `pinch zoom in with valid params returns success`() = runTest {
+            coEvery { actionExecutor.pinch(540f, 1200f, 1.5f, 300L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 1.5)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Pinch"))
+            assertTrue(text.contains("zoom in"))
+            coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 1.5f, 300L) }
+        }
+
+        @Test
+        fun `pinch zoom out with valid params returns success`() = runTest {
+            coEvery { actionExecutor.pinch(540f, 1200f, 0.5f, 300L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 0.5)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("zoom out"))
+            coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 0.5f, 300L) }
+        }
+
+        @Test
+        fun `pinch with custom duration returns success`() = runTest {
+            coEvery { actionExecutor.pinch(540f, 1200f, 2.0f, 500L) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 2.0)
+                put("duration", 500)
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("500ms"))
+            coVerify(exactly = 1) { actionExecutor.pinch(540f, 1200f, 2.0f, 500L) }
+        }
+
+        @Test
+        fun `pinch with missing center_x returns invalid params`() {
+            val params = buildJsonObject {
+                put("center_y", 1200)
+                put("scale", 1.5)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("center_x"))
+        }
+
+        @Test
+        fun `pinch with missing scale returns invalid params`() {
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("scale"))
+        }
+
+        @Test
+        fun `pinch with zero scale returns invalid params`() {
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 0)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("scale"))
+            assertTrue(exception.message!!.contains("> 0"))
+        }
+
+        @Test
+        fun `pinch with negative scale returns invalid params`() {
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", -1.0)
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("scale"))
+        }
+
+        @Test
+        fun `pinch with negative center_x returns invalid params`() {
+            val params = buildJsonObject {
+                put("center_x", -10)
+                put("center_y", 1200)
+                put("scale", 1.5)
+            }
+
+            assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `pinch when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.pinch(any(), any(), any(), any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 1.5)
+            }
+
+            assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `pinch when action fails returns action failed`() {
+            coEvery { actionExecutor.pinch(any(), any(), any(), any()) } returns Result.failure(
+                RuntimeException("Gesture cancelled"),
+            )
+
+            val params = buildJsonObject {
+                put("center_x", 540)
+                put("center_y", 1200)
+                put("scale", 1.5)
+            }
+
+            assertThrows(McpToolException.ActionFailed::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+
+    @Nested
+    @DisplayName("CustomGestureTool")
+    inner class CustomGestureToolTests {
+
+        private lateinit var tool: CustomGestureTool
+
+        @BeforeEach
+        fun setUp() {
+            tool = CustomGestureTool(actionExecutor)
+        }
+
+        @Test
+        fun `custom gesture with valid single path returns success`() = runTest {
+            coEvery { actionExecutor.customGesture(any()) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject {
+                            put("x", 100)
+                            put("y", 100)
+                            put("time", 0)
+                        })
+                        add(buildJsonObject {
+                            put("x", 200)
+                            put("y", 200)
+                            put("time", 300)
+                        })
+                    })
+                })
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("Custom gesture"))
+            assertTrue(text.contains("1 path"))
+            coVerify(exactly = 1) {
+                actionExecutor.customGesture(match { paths ->
+                    paths.size == 1 &&
+                        paths[0].size == 2 &&
+                        paths[0][0] == GesturePoint(100f, 100f, 0L) &&
+                        paths[0][1] == GesturePoint(200f, 200f, 300L)
+                })
+            }
+        }
+
+        @Test
+        fun `custom gesture with multiple paths returns success`() = runTest {
+            coEvery { actionExecutor.customGesture(any()) } returns Result.success(Unit)
+
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 400); put("y", 400); put("time", 0) })
+                        add(buildJsonObject { put("x", 300); put("y", 300); put("time", 300) })
+                    })
+                })
+            }
+            val result = tool.execute(params)
+            val text = extractTextContent(result)
+
+            assertTrue(text.contains("2 path"))
+            coVerify(exactly = 1) { actionExecutor.customGesture(match { it.size == 2 }) }
+        }
+
+        @Test
+        fun `custom gesture with missing paths returns invalid params`() {
+            val params = buildJsonObject {}
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("paths"))
+        }
+
+        @Test
+        fun `custom gesture with null params returns invalid params`() {
+            assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(null) }
+            }
+        }
+
+        @Test
+        fun `custom gesture with empty paths returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {})
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("must not be empty"))
+        }
+
+        @Test
+        fun `custom gesture with single point path returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject {
+                            put("x", 100)
+                            put("y", 100)
+                            put("time", 0)
+                        })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("at least 2 points"))
+        }
+
+        @Test
+        fun `custom gesture with negative coordinate returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", -1); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains(">= 0"))
+        }
+
+        @Test
+        fun `custom gesture with negative time returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", -5) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("time"))
+            assertTrue(exception.message!!.contains(">= 0"))
+        }
+
+        @Test
+        fun `custom gesture with non-monotonic times returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 150); put("y", 150); put("time", 200) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 100) })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("monotonically increasing"))
+        }
+
+        @Test
+        fun `custom gesture with equal times returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 0) })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("monotonically increasing"))
+        }
+
+        @Test
+        fun `custom gesture with missing point field returns invalid params`() {
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                })
+            }
+
+            val exception = assertThrows(McpToolException.InvalidParams::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+            assertTrue(exception.message!!.contains("time"))
+        }
+
+        @Test
+        fun `custom gesture when service not enabled returns permission denied`() {
+            coEvery { actionExecutor.customGesture(any()) } returns Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                })
+            }
+
+            assertThrows(McpToolException.PermissionDenied::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+
+        @Test
+        fun `custom gesture when action fails returns action failed`() {
+            coEvery { actionExecutor.customGesture(any()) } returns Result.failure(
+                RuntimeException("Gesture cancelled"),
+            )
+
+            val params = buildJsonObject {
+                put("paths", buildJsonArray {
+                    add(buildJsonArray {
+                        add(buildJsonObject { put("x", 100); put("y", 100); put("time", 0) })
+                        add(buildJsonObject { put("x", 200); put("y", 200); put("time", 300) })
+                    })
+                })
+            }
+
+            assertThrows(McpToolException.ActionFailed::class.java) {
+                kotlinx.coroutines.test.runTest { tool.execute(params) }
+            }
+        }
+    }
+}
```

---

### Task 8.4: Update MCP_TOOLS.md Documentation

**Description**: Add Touch Action Tools and Gesture Tools sections to `docs/MCP_TOOLS.md` with full schemas, examples, error codes, and implementation notes.

**Acceptance Criteria**:
- [ ] Touch Action Tools section documents all 5 tools (tap, long_press, double_tap, swipe, scroll)
- [ ] Gesture Tools section documents both gesture tools (pinch, custom_gesture)
- [ ] Each tool has: description, input schema, example request/response, error codes
- [ ] Error codes documented: -32602 (invalid params), -32001 (permission denied), -32003 (action failed)
- [ ] Duration limits documented (max 60000ms)
- [ ] Coordinate validation documented (>= 0)
- [ ] File passes any applicable markdown linting

**Tests**: Documentation-only task, no automated tests.

#### Action 8.4.1: Update `docs/MCP_TOOLS.md` with Touch Action and Gesture Tools

**What**: Add comprehensive documentation for the 7 new tools to the MCP tools reference document.

**Context**: `docs/MCP_TOOLS.md` was created during Plan 7 with Screen Introspection Tools and System Action Tools sections. This action appends the Touch Action Tools and Gesture Tools sections following the same documentation format established in Plan 7. The schemas must exactly match the input schemas registered in `ToolRegistry`.

**File**: `docs/MCP_TOOLS.md`

```diff
--- a/docs/MCP_TOOLS.md
+++ b/docs/MCP_TOOLS.md
@@ (end of existing content, after System Action Tools section)

+---
+
+## Touch Action Tools
+
+Coordinate-based touch interactions. All coordinates are in screen pixels (absolute).
+Coordinate values must be >= 0. Duration values must be between 1 and 60000 milliseconds.
+
+### `tap`
+
+Performs a single tap at the specified coordinates.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `x` | number | Yes | - | X coordinate (>= 0) |
+| `y` | number | Yes | - | Y coordinate (>= 0) |
+
+**Example Request**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "tap",
+    "arguments": {
+      "x": 500,
+      "y": 1000
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Tap executed at (500, 1000)"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Missing or invalid parameters (x, y not numbers or negative)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Tap gesture execution failed
+
+---
+
+### `long_press`
+
+Performs a long press at the specified coordinates.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `x` | number | Yes | - | X coordinate (>= 0) |
+| `y` | number | Yes | - | Y coordinate (>= 0) |
+| `duration` | number | No | 1000 | Press duration in ms (1-60000) |
+
+**Example Request**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "long_press",
+    "arguments": {
+      "x": 500,
+      "y": 1000,
+      "duration": 2000
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Long press executed at (500, 1000) for 2000ms"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Missing or invalid parameters (x, y not numbers or negative; duration <= 0 or > 60000)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Long press gesture execution failed
+
+---
+
+### `double_tap`
+
+Performs a double tap at the specified coordinates.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `x` | number | Yes | - | X coordinate (>= 0) |
+| `y` | number | Yes | - | Y coordinate (>= 0) |
+
+**Example Request**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "double_tap",
+    "arguments": {
+      "x": 500,
+      "y": 1000
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Double tap executed at (500, 1000)"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Missing or invalid parameters
+- `-32001`: Accessibility service not enabled
+- `-32003`: Double tap gesture execution failed
+
+---
+
+### `swipe`
+
+Performs a swipe gesture from one point to another.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `x1` | number | Yes | - | Start X coordinate (>= 0) |
+| `y1` | number | Yes | - | Start Y coordinate (>= 0) |
+| `x2` | number | Yes | - | End X coordinate (>= 0) |
+| `y2` | number | Yes | - | End Y coordinate (>= 0) |
+| `duration` | number | No | 300 | Swipe duration in ms (1-60000) |
+
+**Example Request**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "swipe",
+    "arguments": {
+      "x1": 500,
+      "y1": 1500,
+      "x2": 500,
+      "y2": 500,
+      "duration": 300
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Swipe executed from (500, 1500) to (500, 500) over 300ms"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Missing or invalid parameters (coords not numbers or negative; duration <= 0 or > 60000)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Swipe gesture execution failed
+
+---
+
+### `scroll`
+
+Scrolls the screen in the specified direction. Calculates scroll distance as a percentage of screen dimension based on the amount parameter.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `direction` | string | Yes | - | Direction: "up", "down", "left", "right" |
+| `amount` | string | No | "medium" | Amount: "small" (25%), "medium" (50%), "large" (75%) |
+
+**Example Request**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "scroll",
+    "arguments": {
+      "direction": "down",
+      "amount": "large"
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Scroll down (large) executed"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Invalid direction (not one of up/down/left/right) or invalid amount (not one of small/medium/large)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Scroll gesture execution failed (e.g., no root node available for screen dimensions)
+
+---
+
+## Gesture Tools
+
+Advanced multi-touch gesture tools for zoom and custom gesture sequences.
+
+### `pinch`
+
+Performs a pinch-to-zoom gesture centered at the specified coordinates.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `center_x` | number | Yes | - | Center X coordinate (>= 0) |
+| `center_y` | number | Yes | - | Center Y coordinate (>= 0) |
+| `scale` | number | Yes | - | Scale factor (> 0; > 1 = zoom in, < 1 = zoom out) |
+| `duration` | number | No | 300 | Gesture duration in ms (1-60000) |
+
+**Example Request** (zoom in):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "pinch",
+    "arguments": {
+      "center_x": 540,
+      "center_y": 1200,
+      "scale": 2.0
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Pinch (zoom in) executed at (540, 1200) with scale 2.0 over 300ms"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Missing or invalid parameters (coords negative; scale <= 0; duration <= 0 or > 60000)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Pinch gesture execution failed
+
+---
+
+### `custom_gesture`
+
+Executes a custom multi-touch gesture defined by path points. Each path represents one finger's movement. Multiple paths enable multi-finger gestures.
+
+**Input Schema**:
+| Parameter | Type | Required | Default | Description |
+|-----------|------|----------|---------|-------------|
+| `paths` | array | Yes | - | Array of paths (see below) |
+
+Each path is an array of point objects:
+| Field | Type | Description |
+|-------|------|-------------|
+| `x` | number | X coordinate (>= 0) |
+| `y` | number | Y coordinate (>= 0) |
+| `time` | number | Time offset in ms from gesture start (>= 0, monotonically increasing) |
+
+**Validation Rules**:
+- `paths` must be a non-empty array
+- Each path must contain at least 2 points
+- All coordinates must be >= 0
+- All time values must be >= 0
+- Time values must be strictly monotonically increasing within each path
+
+**Example Request** (single-finger drag):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "custom_gesture",
+    "arguments": {
+      "paths": [
+        [
+          {"x": 100, "y": 100, "time": 0},
+          {"x": 200, "y": 200, "time": 150},
+          {"x": 300, "y": 300, "time": 300}
+        ]
+      ]
+    }
+  }
+}
+```
+
+**Example Request** (two-finger pinch):
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "method": "tools/call",
+  "params": {
+    "name": "custom_gesture",
+    "arguments": {
+      "paths": [
+        [
+          {"x": 400, "y": 600, "time": 0},
+          {"x": 300, "y": 600, "time": 300}
+        ],
+        [
+          {"x": 600, "y": 600, "time": 0},
+          {"x": 700, "y": 600, "time": 300}
+        ]
+      ]
+    }
+  }
+}
+```
+
+**Example Response**:
+```json
+{
+  "jsonrpc": "2.0",
+  "id": 1,
+  "result": {
+    "content": [
+      {
+        "type": "text",
+        "text": "Custom gesture executed with 2 path(s), total 4 point(s)"
+      }
+    ]
+  }
+}
+```
+
+**Error Codes**:
+- `-32602`: Invalid parameters (empty paths, path with < 2 points, negative coords/times, non-monotonic times, missing fields)
+- `-32001`: Accessibility service not enabled
+- `-32003`: Custom gesture execution failed
```

---

## Verification

After all tasks are complete, run the following commands to verify the implementation:

```bash
# Run targeted unit tests for the new tool files
./gradlew test --tests "*TouchActionToolsTest" --tests "*GestureToolsTest"

# Run all unit tests
make test-unit

# Run linting
make lint

# Build the project
make build
```

All three commands must succeed with no errors or warnings.

---

## Performance, Security, and QA Review

### Performance

- **No performance concerns**: The tool handlers are thin wrappers around `ActionExecutor` calls. Parameter extraction from `JsonObject` is O(1) hash lookups. No heavy computation occurs in the tool handlers themselves.
- **Duration cap (60000ms)**: Prevents accidental extremely long gestures that could block the accessibility service gesture dispatcher.
- **CustomGestureTool validation**: The nested loop validation is O(P * N) where P is the number of paths and N is the number of points per path. This is acceptable since gestures typically have few paths (1-10) with few points (2-20).

### Security

- **Input validation**: All numeric parameters are validated for range (>= 0, within bounds). String parameters are validated against allowed values (direction, amount enums). This prevents injection of invalid values into the accessibility service.
- **No sensitive data exposure**: Tool response messages contain only coordinate values and action descriptions. No internal state, paths, or class names are exposed.
- **Duration limit**: The 60000ms cap prevents denial-of-service via extremely long gesture durations that would block the gesture dispatcher.
- **No credential handling**: These tools do not handle bearer tokens, certificates, or any credentials. Authentication is handled by the `BearerTokenAuth` middleware at the Ktor layer.

### QA

- **Test coverage**: Each tool has tests for valid params, missing params, invalid params, service-not-enabled, and action-failure scenarios. `CustomGestureTool` additionally tests edge cases for the nested paths structure (empty paths, short paths, negative coords/times, non-monotonic times, equal times, missing fields).
- **Error message quality**: All error messages include the parameter name and the invalid value, enabling the MCP client (AI model) to understand and correct its input.
- **Consistency**: All 7 tools follow the same pattern: extract params -> validate -> call ActionExecutor -> translate result. This consistency reduces the risk of implementation errors.
- **JSON number handling**: The `requireFloat` and `optionalLong` helpers handle both integer and floating-point JSON numbers, preventing type-related failures that could occur if the MCP client sends `500` vs `500.0`.

---

**End of Plan 8**
