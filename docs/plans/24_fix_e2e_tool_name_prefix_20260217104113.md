# Plan 24 — Fix E2E Tests: Add Missing `android_` Tool Name Prefix

**Created**: 2026-02-17
**Status**: Done
**Summary**: Fix 5 pre-existing E2E test failures caused by E2E tests calling MCP tools without the `android_` prefix. All MCP tools are registered with the prefix `android_` (e.g., `android_get_screen_state`, `android_press_home`), but the E2E tests call them without it (e.g., `get_screen_state`, `press_home`), resulting in "Tool not found" errors.

---

## Context & Root Cause

The MCP server registers all tools with a prefix built by `McpToolUtils.buildToolNamePrefix()`. With an empty device slug (default), the prefix is `"android_"`. For example, the tool `get_screen_state` is registered as `android_get_screen_state`.

The E2E tests use `McpClient.callTool(name)` which passes the tool name as-is to the MCP SDK. The tests were written with tool names missing the `android_` prefix.

**Evidence from CI test report (E2ECalculatorTest stdout):**
```
[E2E Calculator] Screen state excerpt: Tool get_screen_state not found
```

**5 failing tests (identical on `main` and PR #29):**

| # | Test | Error | Root Cause |
|---|------|-------|------------|
| 1 | `E2ECalculatorTest > calculate 7 plus 3 equals 10` | `Tool get_screen_state not found` | `get_screen_state` → should be `android_get_screen_state` |
| 2 | `E2ECalculatorTest > get_screen_state with screenshot` | `Result should have at least 2 content items (text + image), got: 1` | `get_screen_state` → should be `android_get_screen_state` |
| 3 | `E2EErrorHandlingTest > correct bearer token returns successful response` | `expected: not equal but was: <true>` (`isError=true`) | `press_home` → should be `android_press_home` |
| 4 | `E2EErrorHandlingTest > click on non-existent element returns error result` | `expected: <true> but was: <false>` (error text doesn't contain element ID) | `click_element` → should be `android_click_element` |
| 5 | `E2EScreenshotTest > get_screen_state with screenshot returns valid JPEG data` | `Result should have at least 2 content items (text + image), got: 1` | `get_screen_state` → should be `android_get_screen_state` |

**5 passing tests (unaffected — they either don't call tools or use error paths):**
- `MCP server lists all available tools` — uses `listTools()`, no `callTool()`
- `missing bearer token returns 401 Unauthorized` — raw HTTP, no tool call
- `invalid bearer token returns 401 Unauthorized` — raw HTTP, no tool call
- `invalid params returns error result` — calls `tap` which also needs prefix, but the test asserts `isError == true` which is satisfied by both "tool not found" and "invalid params" errors (accidental pass)
- `unknown tool name returns error result` — calls `nonexistent_tool_name` which correctly returns "not found"

**Note on `invalid params returns error result`**: This test calls `mcpClient.callTool("tap", emptyMap())` and asserts `isError == true`. It passes because `tap` (without prefix) returns "tool not found", which is still `isError=true`. However, the test is testing the WRONG error — it should test invalid params on a real tool. The tool name must be fixed to `android_tap` so the test actually validates parameter validation, not tool-not-found.

---

## Files Impacted

| File | Action |
|------|--------|
| `e2e-tests/src/test/kotlin/.../E2ECalculatorTest.kt` | Modify: add `android_` prefix to all tool calls |
| `e2e-tests/src/test/kotlin/.../E2EErrorHandlingTest.kt` | Modify: add `android_` prefix to all real tool calls |
| `e2e-tests/src/test/kotlin/.../E2EScreenshotTest.kt` | Modify: add `android_` prefix to all tool calls |

---

## User Story 1: Fix E2E Tool Name Prefix

**As a developer**, I need the E2E tests to call MCP tools with the correct `android_` prefix so all E2E tests pass in CI.

### Acceptance Criteria
- [x] All tool calls in E2E tests use the `android_` prefix
- [x] `E2ECalculatorTest > calculate 7 plus 3 equals 10` passes
- [x] `E2ECalculatorTest > get_screen_state with screenshot returns valid image data` passes
- [x] `E2EErrorHandlingTest > correct bearer token returns successful response` passes
- [x] `E2EErrorHandlingTest > click on non-existent element returns error result` passes
- [x] `E2EErrorHandlingTest > invalid params returns error result` passes with the correct error type (parameter validation, not tool-not-found)
- [x] `E2EScreenshotTest > get_screen_state with screenshot returns valid JPEG data` passes
- [x] All 10 E2E tests pass: `./gradlew :e2e-tests:test`
- [x] All existing unit/integration tests still pass: `./gradlew :app:testDebugUnitTest`
- [x] Linting passes on `app` module (no regressions): `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 1.1: Fix tool names in E2ECalculatorTest.kt

**Definition of Done**: All tool calls in `E2ECalculatorTest.kt` use `android_` prefix.

- [x] **Action 1.1.1**: Add `android_` prefix to all `callTool()` calls

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`

**What changes**: Replace all tool name strings with `android_`-prefixed versions:

```diff
     // Step 1: Press home to ensure clean state
-    mcpClient.callTool("press_home")
+    mcpClient.callTool("android_press_home")
```

```diff
     // Step 3: Verify Calculator is visible in screen state
-    val tree = mcpClient.callTool("get_screen_state")
+    val tree = mcpClient.callTool("android_get_screen_state")
```

```diff
     val button7 = findElementWithRetry("text", "7")
     assertNotNull(button7, "Could not find '7' button in Calculator")
-    mcpClient.callTool("click_element", mapOf("element_id" to button7!!))
+    mcpClient.callTool("android_click_element", mapOf("element_id" to button7!!))
```

> Note: `findElementWithRetry("text", "7")` itself does not change here — the `find_elements` tool name inside `findElementWithRetry` is fixed in the last diff of this action.

```diff
-    mcpClient.callTool("click_element", mapOf("element_id" to buttonPlus!!))
+    mcpClient.callTool("android_click_element", mapOf("element_id" to buttonPlus!!))
```

```diff
-    mcpClient.callTool("click_element", mapOf("element_id" to button3!!))
+    mcpClient.callTool("android_click_element", mapOf("element_id" to button3!!))
```

```diff
-    mcpClient.callTool("click_element", mapOf("element_id" to buttonEquals!!))
+    mcpClient.callTool("android_click_element", mapOf("element_id" to buttonEquals!!))
```

```diff
-    mcpClient.callTool("wait_for_idle", mapOf("timeout" to 3000))
+    mcpClient.callTool("android_wait_for_idle", mapOf("timeout" to 3000))
```

```diff
     // Step 9: Verify result "10" in screen state
-    val resultTree = mcpClient.callTool("get_screen_state")
+    val resultTree = mcpClient.callTool("android_get_screen_state")
```

```diff
     // get_screen_state with screenshot test:
-    val result = mcpClient.callTool(
-        "get_screen_state",
+    val result = mcpClient.callTool(
+        "android_get_screen_state",
         mapOf("include_screenshot" to true),
     )
```

```diff
     // findElementWithRetry:
     val result = mcpClient.callTool(
-        "find_elements",
+        "android_find_elements",
         mapOf("by" to by, "value" to value, "exact_match" to true),
     )
```

**Total changes**: 10 tool name strings (9 direct `callTool` calls + 1 inside `findElementWithRetry`).

---

### Task 1.2: Fix tool names in E2EErrorHandlingTest.kt

**Definition of Done**: All real tool calls in `E2EErrorHandlingTest.kt` use `android_` prefix. The intentionally-wrong tool name (`nonexistent_tool_name`) is left unchanged.

- [x] **Action 1.2.1**: Add `android_` prefix to all real tool calls

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EErrorHandlingTest.kt`

**What changes**:

```diff
     // correct bearer token test:
-    val result = mcpClient.callTool("press_home")
+    val result = mcpClient.callTool("android_press_home")
```

```diff
     // invalid params test:
-    val result = mcpClient.callTool("tap", emptyMap())
+    val result = mcpClient.callTool("android_tap", emptyMap())
```

```diff
     // click on non-existent element test:
     val result = mcpClient.callTool(
-        "click_element",
+        "android_click_element",
         mapOf("element_id" to "nonexistent_element_id_12345"),
     )
```

**Unchanged** (intentionally wrong tool name — this test verifies the "tool not found" error path):
```kotlin
val result = mcpClient.callTool("nonexistent_tool_name")  // keep as-is
```

**Total changes**: 3 tool name strings.

---

### Task 1.3: Fix tool names in E2EScreenshotTest.kt

**Definition of Done**: All tool calls in `E2EScreenshotTest.kt` use `android_` prefix.

- [x] **Action 1.3.1**: Add `android_` prefix to all `callTool()` calls

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2EScreenshotTest.kt`

**What changes**:

```diff
-    mcpClient.callTool("press_home")
+    mcpClient.callTool("android_press_home")
```

```diff
     val result = mcpClient.callTool(
-        "get_screen_state",
+        "android_get_screen_state",
         mapOf("include_screenshot" to true),
     )
```

**Total changes**: 2 tool name strings.

---

### Task 1.4: Verify linting on the app module

**Definition of Done**: ktlint and detekt pass on the `app` module (no regressions).

**Important**: The `e2e-tests` module does **not** have ktlint or detekt plugins configured — only the `app` module does. Since this plan only modifies E2E test files (not `app` code), linting here verifies that the `app` module's lint status has no regressions.

- [x] **Action 1.4.1**: Run ktlint and detekt on the app module

```bash
./gradlew ktlintCheck
./gradlew detekt
```

---

### Task 1.5: Verify all unit and integration tests still pass

**Definition of Done**: No regressions in existing unit/integration tests.

- [x] **Action 1.5.1**: Run unit and integration tests

```bash
./gradlew :app:testDebugUnitTest
```

All unit and integration tests must pass.

---

## User Story 2: Final Verification

**As a developer**, I need to verify the entire fix from the ground up.

### Acceptance Criteria
- [x] All 3 E2E test files have been reviewed and all tool names use the `android_` prefix
- [x] The intentionally-wrong tool name `nonexistent_tool_name` is unchanged
- [x] `E2EErrorHandlingTest > invalid params returns error result` now tests actual parameter validation (calls `android_tap` with empty params), not tool-not-found
- [x] No other tool name strings in E2E tests are missing the prefix
- [x] All unit/integration tests pass: `./gradlew :app:testDebugUnitTest`
- [x] Linting passes on `app` module (no regressions): `./gradlew ktlintCheck && ./gradlew detekt`
- [x] Full build succeeds: `./gradlew assembleDebug`

---

### Task 2.1: Full code review of E2E test changes

- [x] **Action 2.1.1**: Re-read all 3 modified E2E test files and verify every `callTool()` call uses the correct `android_`-prefixed tool name

Search for any remaining unprefixed tool names using a pattern that catches both single-line and multi-line `callTool()` invocations:
```bash
grep -n '\.callTool(' e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/*.kt
```

This matches all `callTool(` call sites. For multi-line calls (where the tool name is on the following line), also inspect the next line after each match.

Every tool name string should either:
- Start with `android_` (real tool calls), OR
- Be `nonexistent_tool_name` (intentional error test)

---

### Task 2.2: Run full build and test pipeline

- [x] **Action 2.2.1**: Run full build and unit/integration tests

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
```

All must pass with zero errors.

Note: E2E tests (`./gradlew :e2e-tests:test`) require Docker with the Android emulator image and cannot be run locally in this verification step unless Docker is available. The CI pipeline will validate E2E tests on push.
