<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 31 — Intent Tools: `android_send_intent` and `android_open_uri`

Two new MCP tools for sending Android intents. `android_send_intent` provides full intent control (action, data, component, extras with type inference/overrides, flags via reflection). `android_open_uri` is a convenience `ACTION_VIEW` wrapper for URIs. Follows `AppManager`/`AppManagementTools` pattern.

---

## User Story 1: Implement Intent Service Layer

`IntentDispatcher` interface + Hilt-bound impl providing intent dispatch with extras type inference, dynamic flag resolution, and error handling.

### Acceptance Criteria
- [x] `IntentDispatcher` interface with `sendIntent()` and `openUri()`
- [x] `IntentDispatcherImpl` with `@ApplicationContext` injection
- [x] Extras type inference: string→String, int-fitting→Int, long-range→Long, decimal→Double, boolean→Boolean, string-list→StringArrayList
- [x] `extras_types` override: `"string"`, `"int"`, `"long"`, `"float"`, `"double"`, `"boolean"`
- [x] Flags resolved dynamically via `Intent::class.java.fields` reflection
- [x] `FLAG_ACTIVITY_NEW_TASK` auto-added for `type = "activity"`
- [x] Dispatch: `"activity"` → `startActivity()`, `"broadcast"` → `sendBroadcast()`, `"service"` → `startService()`
- [x] Component parsed from `"package/class"` format
- [x] Errors wrapped in `Result.failure()`: `ActivityNotFoundException`, `SecurityException`, `IllegalArgumentException`, `IllegalStateException` (background start restriction)
- [x] Exception messages sanitized before returning (no raw Android internals leaked)
- [x] Hilt binding in `ServiceModule`
- [x] Unit tests for all paths

---

### Task 1.1: Create `IntentDispatcher` Interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/intents/IntentDispatcher.kt` (create)

**Action 1.1.1**:
```kotlin
package com.danielealbano.androidremotecontrolmcp.services.intents

interface IntentDispatcher {
    suspend fun sendIntent(
        type: String,
        action: String? = null,
        data: String? = null,
        component: String? = null,
        extras: Map<String, Any?>? = null,
        extrasTypes: Map<String, String>? = null,
        flags: List<String>? = null,
    ): Result<Unit>

    suspend fun openUri(
        uri: String,
        packageName: String? = null,
        mimeType: String? = null,
    ): Result<Unit>
}
```

**Definition of Done**:
- [x] Interface created with both methods returning `Result<Unit>`

---

### Task 1.2: Create `IntentDispatcherImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/intents/IntentDispatcherImpl.kt` (create)

**Action 1.2.1**: Create class with this structure:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.intents

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class IntentDispatcherImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : IntentDispatcher {

    override suspend fun sendIntent(
        type: String,
        action: String?,
        data: String?,
        component: String?,
        extras: Map<String, Any?>?,
        extrasTypes: Map<String, String>?,
        flags: List<String>?,
    ): Result<Unit> {
        // 1. Validate type ∈ {"activity", "broadcast", "service"}
        // 2. Build Intent, set action
        // 3. If data and mimeType both present: intent.setDataAndType(Uri.parse(data), mimeType)
        //    If only data: intent.data = Uri.parse(data)
        //    If only mimeType: intent.type = mimeType
        //    CRITICAL: setData() clears type and setType() clears data — must use setDataAndType() when both present
        // 4. Set component via parseComponent (if provided)
        // 5. Resolve and apply flags via resolveFlags(); auto-add FLAG_ACTIVITY_NEW_TASK for "activity"
        // 6. Apply extras via putExtraWithInference() for each entry
        // 7. Dispatch: when(type) { "activity" -> startActivity, "broadcast" -> sendBroadcast, "service" -> startService }
        // 8. Catch:
        //    - ActivityNotFoundException → Result.failure(IllegalArgumentException("No activity found to handle intent"))
        //    - SecurityException → Result.failure(IllegalArgumentException("Permission denied: not allowed to start component"))
        //    - IllegalStateException → Result.failure() (background start restriction on API 26+ for startService)
        //    - IllegalArgumentException → Result.failure()
        //    NOTE: Sanitize exception messages — do not leak raw Android internal details to MCP client
    }

    override suspend fun openUri(
        uri: String,
        packageName: String?,
        mimeType: String?,
    ): Result<Unit> {
        // Intent(ACTION_VIEW, Uri.parse(uri)) + FLAG_ACTIVITY_NEW_TASK
        // Optionally set intent.setPackage(packageName)
        // CRITICAL: If mimeType is non-null, MUST use intent.setDataAndType(Uri.parse(uri), mimeType)
        //   instead of setting data and type separately — calling setType() clears data and vice versa.
        //   If mimeType is null, use intent.data = Uri.parse(uri) as usual.
        // startActivity, catch:
        //   - ActivityNotFoundException → Result.failure(IllegalArgumentException("No app found to handle URI"))
        //   - SecurityException → Result.failure(IllegalArgumentException("Permission denied: not allowed to open URI"))
        //   NOTE: Sanitize exception messages — do not leak raw Android internal details to MCP client
    }

    private fun putExtraWithInference(intent: Intent, key: String, value: Any?, typeOverride: String?) {
        // IMPORTANT: All cast/conversion failures must be caught — wrap the entire body in
        // try { ... } catch (e: Exception) { throw IllegalArgumentException("Failed to convert extra '$key': ${e.message}") }
        // This handles ClassCastException, NumberFormatException, and any other conversion issues.
        //
        // With typeOverride:
        //   "string" → putExtra(key, value.toString())
        //   "int"    → putExtra(key, (value as? Number)?.toInt() ?: value.toString().toInt())
        //   "long"   → putExtra(key, (value as? Number)?.toLong() ?: value.toString().toLong())
        //   "float"  → putExtra(key, (value as? Number)?.toFloat() ?: value.toString().toFloat())
        //   "double" → putExtra(key, (value as? Number)?.toDouble() ?: value.toString().toDouble())
        //   "boolean"→ putExtra(key, when (value) { is Boolean -> value; else -> value.toString().toBooleanStrict() })
        //   else     → throw IllegalArgumentException("Unsupported extras_types value: '$typeOverride'. Supported: string, int, long, float, double, boolean")
        //
        // Without typeOverride (JSON-native inference):
        //   value is String  → putExtra(key, value)
        //   value is Boolean → putExtra(key, value)
        //   value is Number  →
        //     no decimal AND fits Int range → putExtra(key, value.toInt())
        //     no decimal AND exceeds Int    → putExtra(key, value.toLong())
        //     has decimal                   → putExtra(key, value.toDouble())
        //   value is List<*> (all String)   → putExtra(key, ArrayList(value.filterIsInstance<String>()))
        //   value == null                   → skip (do not put extra)
        //   else → throw IllegalArgumentException("Cannot infer extra type for key '$key': unsupported value type")
    }

    private fun resolveFlags(flagNames: List<String>): Result<Int> {
        // Combine all flag values with bitwise OR
        // For each name: flagMap[name] ?: return Result.failure(IllegalArgumentException("Unknown flag: $name"))
    }

    private fun parseComponent(component: String): Result<ComponentName> {
        // Split on "/" → first = package, second = class
        // No "/" → Result.failure(IllegalArgumentException("Invalid component format..."))
        // Return Result.success(ComponentName(pkg, cls))
    }

    companion object {
        private const val TAG = "MCP:IntentDispatcher"

        // Built lazily via reflection — picks up all public FLAG_* constants on the current API level
        val flagMap: Map<String, Int> by lazy {
            Intent::class.java.fields
                .filter { it.name.startsWith("FLAG_") && it.type == Int::class.javaPrimitiveType }
                .associate { it.name to it.getInt(null) }
        }
    }
}
```

**Constraint (non-obvious)**: `Number` values from kotlinx.serialization's `JsonPrimitive.content` deserialization may arrive as `Double` or `Long` depending on how the JSON was parsed in the tool handler. The `putExtraWithInference` method must handle this: check `value.toDouble() % 1.0 == 0.0` for "no decimal part", and `value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE` for Int range fit.

**Definition of Done**:
- [x] All extras type inference paths implemented (String, Boolean, Int, Long, Double, String list)
- [x] `extras_types` override implemented for all 6 types; unsupported type → `IllegalArgumentException`
- [x] `putExtraWithInference` wrapped in try/catch for all cast/conversion failures
- [x] `flagMap` built via reflection, cached in companion `lazy`
- [x] `FLAG_ACTIVITY_NEW_TASK` auto-added for `type = "activity"`
- [x] Three dispatch modes implemented
- [x] Component parsed from `"package/class"` format
- [x] `setDataAndType()` used when both data and type/mimeType present (not separate set calls)
- [x] All exceptions caught → `Result.failure()` including `IllegalStateException` for background start restriction
- [x] Exception messages sanitized before returning (no raw Android internals)

---

### Task 1.3: Add Hilt Binding

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

**Action 1.3.1**: Add to `ServiceModule` (after `bindAppManager`):
```diff
+    @Binds
+    @Singleton
+    abstract fun bindIntentDispatcher(impl: IntentDispatcherImpl): IntentDispatcher
```

**Definition of Done**:
- [x] Binding added in `ServiceModule`

---

### Task 1.4: Unit Tests for `IntentDispatcherImpl`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/intents/IntentDispatcherImplTest.kt` (create)

**Action 1.4.1**: Create test class using MockK to mock `Context`. Follow `AppManagerTest.kt` pattern.

Test cases (each a `@Test` method):
- `sendIntent with string extra puts String`
- `sendIntent with boolean extra puts Boolean`
- `sendIntent with small integer extra puts Int` (42 → Int)
- `sendIntent with large integer extra puts Long` (9999999999 → Long)
- `sendIntent with decimal extra puts Double` (3.14 → Double)
- `sendIntent with string list extra puts StringArrayList`
- `sendIntent with extras_types long override puts Long` (42 with override "long" → Long)
- `sendIntent with extras_types float override puts Float`
- `sendIntent with extras_types string override converts number to String`
- `sendIntent with valid flag resolves correctly`
- `sendIntent with invalid flag name returns failure`
- `sendIntent with multiple flags combines with bitwise OR`
- `sendIntent activity auto-adds FLAG_ACTIVITY_NEW_TASK`
- `sendIntent activity calls startActivity`
- `sendIntent broadcast calls sendBroadcast`
- `sendIntent service calls startService`
- `sendIntent with invalid type returns failure`
- `sendIntent with valid component sets ComponentName`
- `sendIntent with invalid component format returns failure`
- `sendIntent wraps ActivityNotFoundException in Result failure`
- `sendIntent wraps SecurityException in Result failure with sanitized message`
- `sendIntent wraps IllegalStateException in Result failure` (background start restriction)
- `sendIntent with extras_types boolean converts correctly`
- `sendIntent with unsupported extras_types value returns failure`
- `sendIntent with data and type uses setDataAndType`
- `sendIntent with data only sets data without clearing type`
- `sendIntent with type only sets type without clearing data`
- `sendIntent with extras conversion failure returns failure` (e.g., "abc" with type override "int")
- `sendIntent with null extra value skips extra`
- `openUri calls startActivity with ACTION_VIEW`
- `openUri with package_name sets package on intent`
- `openUri with mime_type uses setDataAndType`
- `openUri with uri only sets data`
- `openUri wraps ActivityNotFoundException in Result failure`
- `openUri wraps SecurityException in Result failure with sanitized message`

**Definition of Done**:
- [x] All 35 test cases implemented and passing

---

## User Story 2: Implement MCP Tool Handlers

`IntentTools.kt` with `SendIntentHandler` and `OpenUriHandler`.

### Acceptance Criteria
- [x] Both handlers registered with schemas matching agreed spec
- [x] Parameter validation and delegation to `IntentDispatcher`
- [x] Unit tests for handlers

---

### Task 2.1: Create `IntentTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/IntentTools.kt` (create)

**Action 2.1.1**: `SendIntentHandler`:

```kotlin
class SendIntentHandler @Inject constructor(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val type = McpToolUtils.requireString(arguments, "type")
        if (type !in listOf("activity", "broadcast", "service")) {
            throw McpToolException.InvalidParams("type must be 'activity', 'broadcast', or 'service'")
        }
        val action = McpToolUtils.optionalString(arguments, "action", "")
            .ifEmpty { null }
        val data = McpToolUtils.optionalString(arguments, "data", "")
            .ifEmpty { null }
        val component = McpToolUtils.optionalString(arguments, "component", "")
            .ifEmpty { null }
        // Extract extras: arguments?.get("extras")?.jsonObject → Map<String, Any?> via:
        //   for each key/value in the JsonObject:
        //     JsonPrimitive.isString → String
        //     JsonPrimitive.booleanOrNull != null → Boolean
        //     JsonPrimitive.longOrNull != null → Number (Long, which putExtraWithInference will narrow to Int/Long/Double)
        //     JsonPrimitive.doubleOrNull != null → Number (Double)
        //     JsonArray (all primitives are strings) → List<String>
        //     else → skip with warning log
        // Extract extras_types: arguments?.get("extras_types")?.jsonObject → Map<String, String>
        //   for each key/value: value.jsonPrimitive.content → String
        // Extract flags: arguments?.get("flags")?.jsonArray → List<String>
        //   for each element: element.jsonPrimitive.content → String
        // Call intentDispatcher.sendIntent(...) → handle Result via handleActionResult
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}send_intent",
            description = "Send an Android intent. Supports starting activities, " +
                "sending broadcasts, and starting services. Use for opening specific " +
                "settings pages, triggering app-specific actions, or sending broadcasts.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "The intent delivery type: 'activity', 'broadcast', or 'service'")
                    }
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "The intent action (e.g., 'android.intent.action.VIEW')")
                    }
                    putJsonObject("data") {
                        put("type", "string")
                        put("description", "Data URI for the intent")
                    }
                    putJsonObject("component") {
                        put("type", "string")
                        put("description", "Target component as 'package/class' " +
                            "(e.g., 'com.example.app/com.example.app.MyActivity')")
                    }
                    putJsonObject("extras") {
                        put("type", "object")
                        put("description", "Key-value extras. Values auto-typed: " +
                            "string→String, integer→Int/Long, decimal→Double, " +
                            "boolean→Boolean, string array→StringArrayList")
                    }
                    putJsonObject("extras_types") {
                        put("type", "object")
                        put("description", "Type overrides for extras keys. " +
                            "Supported: 'string', 'int', 'long', 'float', 'double', 'boolean'")
                    }
                    putJsonObject("flags") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Intent flag names (e.g., 'FLAG_ACTIVITY_CLEAR_TOP'). " +
                            "FLAG_ACTIVITY_NEW_TASK auto-added for activity type.")
                    }
                },
                required = listOf("type"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "send_intent"
        private const val TAG = "MCP:SendIntentTool"
    }
}
```

**Action 2.1.2**: `OpenUriHandler`:

```kotlin
class OpenUriHandler @Inject constructor(
    private val intentDispatcher: IntentDispatcher,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val uri = McpToolUtils.requireString(arguments, "uri")
        val packageName = McpToolUtils.optionalString(arguments, "package_name", "")
            .ifEmpty { null }
        val mimeType = McpToolUtils.optionalString(arguments, "mime_type", "")
            .ifEmpty { null }
        val result = intentDispatcher.openUri(uri, packageName, mimeType)
        return McpToolUtils.handleActionResult(result, "URI opened successfully: $uri")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}open_uri",
            description = "Open a URI using Android's ACTION_VIEW. Handles https://, http://, " +
                "tel:, mailto:, geo:, content:// URLs, deep links, and custom app schemes " +
                "(e.g., whatsapp://send?phone=...).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "The URI to open")
                    }
                    putJsonObject("package_name") {
                        put("type", "string")
                        put("description", "Force a specific app to handle the URI")
                    }
                    putJsonObject("mime_type") {
                        put("type", "string")
                        put("description", "MIME type hint (useful for content:// URIs)")
                    }
                },
                required = listOf("uri"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "open_uri"
        private const val TAG = "MCP:OpenUriTool"
    }
}
```

**Action 2.1.3**: Registration function:

```kotlin
fun registerIntentTools(
    server: Server,
    intentDispatcher: IntentDispatcher,
    toolNamePrefix: String,
) {
    SendIntentHandler(intentDispatcher).register(server, toolNamePrefix)
    OpenUriHandler(intentDispatcher).register(server, toolNamePrefix)
}
```

**Definition of Done**:
- [x] Both handlers extract/validate parameters and delegate to `IntentDispatcher`
- [x] Schemas match agreed spec
- [x] Registration function created

---

### Task 2.2: Unit Tests for `IntentTools`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/IntentToolsTest.kt` (create)

**Action 2.2.1**: Create test class mocking `IntentDispatcher`. Follow `SystemActionToolsTest.kt` pattern.

Test cases:
- `send_intent activity with action returns success`
- `send_intent broadcast with action returns success`
- `send_intent service with action returns success`
- `send_intent missing type throws InvalidParams`
- `send_intent invalid type throws InvalidParams`
- `send_intent with extras passes map to dispatcher`
- `send_intent with extras_types passes overrides to dispatcher`
- `send_intent with flags passes list to dispatcher`
- `send_intent with component passes string to dispatcher`
- `send_intent dispatcher failure returns error result`
- `open_uri valid uri returns success`
- `open_uri missing uri throws InvalidParams`
- `open_uri with package_name passes to dispatcher`
- `open_uri with mime_type passes to dispatcher`
- `open_uri dispatcher failure returns error result`

**Definition of Done**:
- [x] All 15 test cases implemented and passing

---

## User Story 3: Wire into MCP Server and Integration Tests

### Acceptance Criteria
- [x] `McpServerService` injects `IntentDispatcher` and registers tools
- [x] `McpIntegrationTestHelper` updated
- [x] Integration tests cover both tools through full HTTP stack

---

### Task 3.1: Wire into `McpServerService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (modify)

**Action 3.1.1**: Add import:
```diff
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerIntentTools
+import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
```

**Action 3.1.2**: Add field (after `cameraProvider`):
```diff
+    @Inject lateinit var intentDispatcher: IntentDispatcher
```

**Action 3.1.3**: Add in `registerAllTools()` (after `registerCameraTools`):
```diff
+        registerIntentTools(server, intentDispatcher, toolNamePrefix)
```

**Definition of Done**:
- [x] Import, field injection, and registration call added

---

### Task 3.2: Update `McpIntegrationTestHelper`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt` (modify)

**Action 3.2.1**: Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.services.intents.IntentDispatcher
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerIntentTools
```

**Action 3.2.2**: Add to `MockDependencies`:
```diff
+    val intentDispatcher: IntentDispatcher,
```

**Action 3.2.3**: Add to `createMockDependencies()`:
```diff
+            intentDispatcher = mockk(relaxed = true),
```

**Action 3.2.4**: Add to `registerAllTools()`:
```diff
+        registerIntentTools(server, deps.intentDispatcher, toolNamePrefix)
```

**Definition of Done**:
- [x] `MockDependencies`, `createMockDependencies()`, and `registerAllTools()` all updated

---

### Task 3.3: Integration Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/IntentToolsIntegrationTest.kt` (create)

**Action 3.3.1**: Create test class following `AppManagementToolsIntegrationTest.kt` pattern.

Test cases:
- `send_intent activity with action calls dispatcher and returns success`
- `send_intent broadcast with extras passes extras through`
- `send_intent with component passes component through`
- `send_intent with flags passes flags through`
- `send_intent with extras_types passes overrides through`
- `send_intent missing type returns error`
- `send_intent invalid type returns error`
- `send_intent dispatcher failure returns error with message`
- `open_uri valid uri returns success`
- `open_uri with package_name passes through`
- `open_uri with mime_type passes through`
- `open_uri missing uri returns error`
- `open_uri dispatcher failure returns error with message`
- `send_intent SecurityException returns sanitized error message`
- `send_intent IllegalStateException returns error` (background start)

**Definition of Done**:
- [x] All 15 integration test cases implemented and passing

---

## User Story 4: Update Documentation

### Acceptance Criteria
- [x] `MCP_TOOLS.md` has full section for Intent Tools
- [x] `PROJECT.md` tool table updated
- [x] `ARCHITECTURE.md` updated (component diagram, tool categories table)
- [ ] Tool count updated from 45 to 47

---

### Task 4.1: Update `MCP_TOOLS.md`

**File**: `docs/MCP_TOOLS.md` (modify)

**Action 4.1.1**: Add to overview table and update count from 45 to 47. Add ToC entry.

**Action 4.1.2**: Add `## 11. Intent Tools` section after Camera Tools with full schema, request/response examples, and error cases for both `android_send_intent` and `android_open_uri`.

**Definition of Done**:
- [x] Overview table, ToC, and tool count updated
- [x] Full documentation section with schemas, examples, error cases

---

### Task 4.2: Update `PROJECT.md`

**File**: `docs/PROJECT.md` (modify)

**Action 4.2.1**: Add `### 11. Intent Tools (2 tools)` section with tool table. Update tool count.

**Definition of Done**:
- [x] Tool table added, count updated

---

### Task 4.3: Update `ARCHITECTURE.md`

**File**: `docs/ARCHITECTURE.md` (modify)

**Action 4.3.1**: Update the component diagram (Mermaid) to include intent dispatch. Update the MCP tool categories table to include Intent Tools category. Validate updated Mermaid with `mmdc`.

**Definition of Done**:
- [x] Component diagram updated
- [x] Tool categories table updated

---

## User Story 5: Final Verification

### Acceptance Criteria
- [ ] All quality gates pass
- [ ] Implementation matches this plan exactly

---

### Task 5.1: Codebase-Wide Verification

**Action 5.1.1**: Grep verification:
- `grep -rn "send_intent\|open_uri" app/src/main/` → tools registered
- `grep -rn "IntentDispatcher" app/src/main/` → interface, impl, binding, injection
- `grep -rn "registerIntentTools" app/src/` → McpServerService and McpIntegrationTestHelper

**Action 5.1.2**: Quality gates:
- `make lint`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew build`

**Action 5.1.3**: Manual review: verify every file created/modified matches this plan — interface signatures, impl logic, handler schemas, test coverage, documentation.

**Definition of Done**:
- [ ] No stale or missing references
- [ ] All quality gates pass
- [ ] Manual review confirms consistency
