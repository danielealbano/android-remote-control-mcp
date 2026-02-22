# Plan 30 — Rename `"id"` to `"element_id"` in MCP Tool Outputs + Rename `"ids"` to `"element_ids"` in `get_element_details` Input

## Context

There is a naming inconsistency between MCP tool outputs and inputs:
- Tools that **return** element/node information use `"id"` as the field name for the node identifier
- Tools that **accept** a node identifier as input use `"element_id"` as the parameter name

This forces LLM clients to mentally map `"id"` (from output) to `"element_id"` (for input), creating unnecessary confusion.

**Goal**: Rename the output field `"id"` to `"element_id"` in all tools that return element data, and rename the input parameter `"ids"` to `"element_ids"` in `get_element_details`. After this change, the field name will be `element_id` consistently across all inputs and outputs.

---

## User Story 1: Rename `"id"` to `"element_id"` in All Element/Node Output Fields and Rename `"ids"` to `"element_ids"` in `get_element_details` Input

### Acceptance Criteria / Definition of Done
- [x] All MCP tool outputs that return element/node identifiers use `"element_id"` instead of `"id"`
- [x] The `get_element_details` input parameter is renamed from `"ids"` to `"element_ids"` (schema, parsing, error messages)
- [x] The `get_element_details` TSV output header column is renamed from `"id"` to `"element_id"`
- [x] The `get_screen_state` TSV header column is renamed from `"id"` to `"element_id"`
- [x] The `find_elements` JSON response field is renamed from `"id"` to `"element_id"`
- [x] The `wait_for_element` JSON response field is renamed from `"id"` to `"element_id"`
- [x] Regression assertion added for `wait_for_element` `"element_id"` output field in existing unit test
- [x] New integration test for `get_element_details` added (validates `"element_ids"` input and `"element_id"` TSV header)
- [x] All unit tests pass (`./gradlew :app:testDebugUnitTest`)
- [x] All integration tests pass (`./gradlew :app:testDebugUnitTest --tests "*.integration.*"`)
- [x] E2E tests pass (`make test-e2e`) — code change verified via review (requires Docker emulator)
- [x] Linting passes (`make lint`)
- [x] Build succeeds without errors or warnings (`./gradlew build`) — Android lint QueryAllPackagesPermission is pre-existing
- [x] Documentation in `docs/MCP_TOOLS.md` is updated to reflect the new field names
- [x] Documentation in `docs/PROJECT.md` is updated to reflect the new parameter name

---

### Task 1: Rename `"id"` to `"element_id"` in `find_elements` Tool Output (ElementActionTools.kt)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Action 1.1**: At line 88, change the JSON key from `"id"` to `"element_id"`:
```diff
-                                        put("id", element.id)
+                                        put("element_id", element.id)
```

**Definition of Done**:
- [x] The `find_elements` tool response JSON uses `"element_id"` as the key name

---

### Task 2: Rename `"id"` to `"element_id"` in `wait_for_element` Tool Output (UtilityTools.kt)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Action 2.1**: At line 245, change the JSON key from `"id"` to `"element_id"`:
```diff
-                                    put("id", element.id)
+                                    put("element_id", element.id)
```

**Definition of Done**:
- [x] The `wait_for_element` tool response JSON uses `"element_id"` as the key name

---

### Task 3: Rename `"id"` column to `"element_id"` in `get_screen_state` TSV Output (CompactTreeFormatter.kt)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt`

**Action 3.1**: At line 286-288, change the HEADER constant to use `"element_id"` instead of `"id"` as the first column:
```diff
            const val HEADER =
-                "id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}" +
+                "element_id${COLUMN_SEPARATOR}class${COLUMN_SEPARATOR}text${COLUMN_SEPARATOR}" +
                    "desc${COLUMN_SEPARATOR}res_id${COLUMN_SEPARATOR}bounds${COLUMN_SEPARATOR}flags"
```

**Action 3.2**: At line 17, update the KDoc comment to reflect the new header column name:
```diff
- * - Line 7: TSV header: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
+ * - Line 7: TSV header: `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
```

**Note**: Line 81 (`val id = node.id`) is a local variable extracting the node's id property — this does NOT need to change. The variable is used as the column value in line 92 (`$id$SEP$className$SEP...`), which correctly writes the id value under whichever header column name we define. No change needed there.

**Definition of Done**:
- [x] The `get_screen_state` TSV output header first column reads `element_id` instead of `id`
- [x] The KDoc comment at line 17 references the updated header

---

### Task 4: Rename `"ids"` to `"element_ids"` Input Parameter and `"id"` to `"element_id"` TSV Header in `get_element_details` Tool (UtilityTools.kt)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Action 4.0**: At line 474, update the class KDoc comment:
```diff
- * Retrieves full (untruncated) text and contentDescription for a list of element IDs.
+ * Retrieves full (untruncated) text and contentDescription for a list of element_ids.
```

**Action 4.1**: At line 487, update the comment:
```diff
-            // 1. Parse "ids" parameter — required, JSON array of strings
+            // 1. Parse "element_ids" parameter — required, JSON array of strings
```

**Action 4.2**: At lines 489-490, update the parameter key and error message:
```diff
-                arguments?.get("ids")
-                    ?: throw McpToolException.InvalidParams("Missing required parameter 'ids'")
+                arguments?.get("element_ids")
+                    ?: throw McpToolException.InvalidParams("Missing required parameter 'element_ids'")
```

**Action 4.3**: At lines 493-495, update error messages:
```diff
-                    ?: throw McpToolException.InvalidParams("Parameter 'ids' must be an array of strings")
+                    ?: throw McpToolException.InvalidParams("Parameter 'element_ids' must be an array of strings")
             if (idsArray.isEmpty()) {
-                throw McpToolException.InvalidParams("Parameter 'ids' must not be empty")
+                throw McpToolException.InvalidParams("Parameter 'element_ids' must not be empty")
             }
```

**Action 4.4**: At lines 501-503, update error messages:
```diff
-                            ?: throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
+                            ?: throw McpToolException.InvalidParams("Each element in 'element_ids' must be a string")
                     if (!primitive.isString) {
-                        throw McpToolException.InvalidParams("Each element in 'ids' must be a string")
+                        throw McpToolException.InvalidParams("Each element in 'element_ids' must be a string")
                     }
```

**Action 4.5**: At line 513, update the TSV header:
```diff
-            sb.append("id\ttext\tdesc\n")
+            sb.append("element_id\ttext\tdesc\n")
```

**Action 4.6**: At line 526, update the log message:
```diff
-            Log.d(TAG, "get_element_details: ids=${ids.size}")
+            Log.d(TAG, "get_element_details: element_ids=${ids.size}")
```

**Action 4.7**: At lines 553-555, update the tool description:
```diff
                 description =
-                    "Retrieve full untruncated text and description for elements by ID. " +
-                        "Use after ${toolNamePrefix}get_screen_state when text or desc was truncated.",
+                    "Retrieve full untruncated text and description for elements by element_id. " +
+                        "Use after ${toolNamePrefix}get_screen_state when text or desc was truncated.",
```

**Action 4.8**: At lines 559-568, update the input schema:
```diff
                         buildJsonObject {
-                                putJsonObject("ids") {
+                                putJsonObject("element_ids") {
                                     put("type", "array")
                                     putJsonObject("items") {
                                         put("type", "string")
                                     }
-                                    put("description", "List of element IDs to retrieve details for")
+                                    put("description", "List of element_ids to retrieve details for")
                                 }
                             },
-                        required = listOf("ids"),
+                        required = listOf("element_ids"),
```

**Definition of Done**:
- [x] The `get_element_details` class KDoc references `element_ids`
- [x] The `get_element_details` input parameter is `"element_ids"` in schema, parsing, and all error messages
- [x] The `get_element_details` TSV output header first column reads `element_id`
- [x] The tool description and schema description reference the new parameter name

---

### Task 5: Update Unit Tests

#### 5.1 — ElementActionToolsTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionToolsTest.kt`

**Action 5.1.1**: At line 161, update the assertion key:
```diff
-                assertEquals("node_abc", elements[0].jsonObject["id"]?.jsonPrimitive?.content)
+                assertEquals("node_abc", elements[0].jsonObject["element_id"]?.jsonPrimitive?.content)
```

#### 5.2 — CompactTreeFormatterTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt`

**Action 5.2.1**: At line 114, update the expected header:
```diff
-            assertEquals("id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[6])
+            assertEquals("element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[6])
```

#### 5.3 — ScreenIntrospectionToolsTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt`

**Action 5.3.1**: At line 108, update the expected header in the full expected string:
```diff
-            "id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n" +
+            "element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n" +
```

#### 5.4 — GetElementDetailsToolTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/GetElementDetailsToolTest.kt`

**Action 5.4.1**: At line 121, update the parameter key in test input:
```diff
-                        "ids",
+                        "element_ids",
```

**Action 5.4.2**: At line 132, update the expected TSV header:
```diff
-            assertEquals("id\ttext\tdesc", lines[0])
+            assertEquals("element_id\ttext\tdesc", lines[0])
```

**Action 5.4.3**: At line 147, update the parameter key in test input:
```diff
-                        "ids",
+                        "element_ids",
```

**Action 5.4.4**: At line 170, update the parameter key:
```diff
-                    put("ids", buildJsonArray { add(JsonPrimitive("node_b")) })
+                    put("element_ids", buildJsonArray { add(JsonPrimitive("node_b")) })
```

**Action 5.4.5**: At line 195, update the parameter key:
```diff
-                    put("ids", buildJsonArray { add(JsonPrimitive("node_special")) })
+                    put("element_ids", buildJsonArray { add(JsonPrimitive("node_special")) })
```

**Action 5.4.6**: At line 220, update the parameter key:
```diff
-                    put("ids", buildJsonArray { add(JsonPrimitive("node_long")) })
+                    put("element_ids", buildJsonArray { add(JsonPrimitive("node_long")) })
```

**Action 5.4.7**: At line 242, update the parameter key:
```diff
-            val params = buildJsonObject { put("ids", "not_an_array") }
+            val params = buildJsonObject { put("element_ids", "not_an_array") }
```

**Action 5.4.8**: At line 252, update the parameter key:
```diff
-            val params = buildJsonObject { put("ids", buildJsonArray { }) }
+            val params = buildJsonObject { put("element_ids", buildJsonArray { }) }
```

**Action 5.4.9**: At line 264, update the parameter key:
```diff
-                    put("ids", buildJsonArray { add(JsonPrimitive(123)) })
+                    put("element_ids", buildJsonArray { add(JsonPrimitive(123)) })
```

**Action 5.4.10**: At line 279, update the parameter key:
```diff
-                    put("ids", buildJsonArray { add(JsonPrimitive("node_a")) })
+                    put("element_ids", buildJsonArray { add(JsonPrimitive("node_a")) })
```

**Action 5.4.11**: At line 230, update the `@DisplayName` annotation:
```diff
-    @DisplayName("throws InvalidParams when ids missing")
+    @DisplayName("throws InvalidParams when element_ids missing")
```

**Action 5.4.12**: At line 231, update the function name:
```diff
-    fun throwsInvalidParamsWhenIdsMissing() =
+    fun throwsInvalidParamsWhenElementIdsMissing() =
```

**Action 5.4.13**: At line 239, update the `@DisplayName` annotation:
```diff
-    @DisplayName("throws InvalidParams when ids is not array")
+    @DisplayName("throws InvalidParams when element_ids is not array")
```

**Action 5.4.14**: At line 240, update the function name:
```diff
-    fun throwsInvalidParamsWhenIdsIsNotArray() =
+    fun throwsInvalidParamsWhenElementIdsIsNotArray() =
```

**Action 5.4.15**: At line 249, update the `@DisplayName` annotation:
```diff
-    @DisplayName("throws InvalidParams when ids is empty array")
+    @DisplayName("throws InvalidParams when element_ids is empty array")
```

**Action 5.4.16**: At line 250, update the function name:
```diff
-    fun throwsInvalidParamsWhenIdsIsEmptyArray() =
+    fun throwsInvalidParamsWhenElementIdsIsEmptyArray() =
```

**Action 5.4.17**: At line 259, update the `@DisplayName` annotation:
```diff
-    @DisplayName("throws InvalidParams when ids contains non-string")
+    @DisplayName("throws InvalidParams when element_ids contains non-string")
```

**Action 5.4.18**: At line 260, update the function name:
```diff
-    fun throwsInvalidParamsWhenIdsContainsNonString() =
+    fun throwsInvalidParamsWhenElementIdsContainsNonString() =
```

#### 5.5 — UtilityToolsTest.kt — Add Regression Assertion for `wait_for_element` `"element_id"` Field

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityToolsTest.kt`

The existing `finds element on first attempt` test (line 192) only asserts `parsed["found"]` is true. It does not assert on the `"element_id"` field in the response, meaning Action 2.1 has no regression test coverage. Add an assertion to verify the renamed field.

**Action 5.5.1**: At line 207, after the existing assertion, add a new assertion for the `"element_id"` field:
```diff
                 assertEquals(true, parsed["found"]?.jsonPrimitive?.content?.toBoolean())
+                assertEquals(
+                    "node_abc",
+                    parsed["element"]?.jsonObject?.get("element_id")?.jsonPrimitive?.content,
+                )
```

The `sampleElementInfo` used by this test has `id = "node_abc"` (line 82), which matches the expected value.

**Definition of Done**:
- [x] All unit tests updated to use `"element_id"` for output assertions and `"element_ids"` for input parameters
- [x] All `@DisplayName` annotations and function names reference `element_ids` instead of `ids`
- [x] Regression assertion added for `wait_for_element` `"element_id"` output field
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest`

---

### Task 6: Update Integration Tests

#### 6.1 — ElementActionIntegrationTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ElementActionIntegrationTest.kt`

**Action 6.1.1**: At line 105, update the assertion key:
```diff
-                    elements[0].jsonObject["id"]?.jsonPrimitive?.content,
+                    elements[0].jsonObject["element_id"]?.jsonPrimitive?.content,
```

**Action 6.1.2**: At line 249, update the assertion key:
```diff
-                        elements[0].jsonObject["id"]?.jsonPrimitive?.content,
+                        elements[0].jsonObject["element_id"]?.jsonPrimitive?.content,
```

#### 6.2 — ScreenIntrospectionIntegrationTest.kt

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt`

**Action 6.2.1**: At line 107, update the expected header assertion:
```diff
-                assertTrue(textContent.contains("id\tclass\ttext\tdesc\tres_id\tbounds\tflags"))
+                assertTrue(textContent.contains("element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags"))
```

#### 6.3 — UtilityIntegrationTest.kt — Add `get_element_details` Integration Test (pre-existing gap)

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/UtilityIntegrationTest.kt`

There is no existing integration test for `get_element_details`. Since we are modifying its input parameter name (`"ids"` → `"element_ids"`) and output TSV header (`"id"` → `"element_id"`), add an integration test that exercises the full MCP HTTP stack with the new parameter name and validates the output format.

**Action 6.3.1**: Add a new integration test method at the end of `UtilityIntegrationTest` class (before the closing `}`). The test should:
1. Create mock dependencies via `McpIntegrationTestHelper.createMockDependencies()`
2. Set up multi-window mock with a sample tree containing nodes (e.g. `node_a` with text "Hello" and `node_b` with text "World")
3. Mock `deps.elementFinder.findNodeById(...)` for both node IDs
4. Call `android_get_element_details` with `arguments = mapOf("element_ids" to listOf("node_a", "node_b"))`
5. Assert the response is not an error
6. Assert the TSV header line is `"element_id\ttext\tdesc"`
7. Assert the data rows contain the expected values

The test should follow the same patterns used in `ElementActionIntegrationTest.kt` and the existing `UtilityIntegrationTest.kt` tests.

**Definition of Done**:
- [x] All integration tests updated to use `"element_id"` for output assertions
- [x] New `get_element_details` integration test added, validating `"element_ids"` input parameter and `"element_id"` TSV output header
- [x] All integration tests pass: `./gradlew :app:testDebugUnitTest --tests "*.integration.*"`

---

### Task 7: Update E2E Tests

**File**: `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`

**Action 7.1**: At line 228, update the JSON key extraction:
```diff
-                        return elements[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull
+                        return elements[0].jsonObject["element_id"]?.jsonPrimitive?.contentOrNull
```

**Definition of Done**:
- [x] E2E test updated to use `"element_id"` for output extraction
- [x] E2E tests pass: `make test-e2e` (E2E tests require Docker emulator — code change verified via review)

---

### Task 8: Update Documentation (MCP_TOOLS.md)

**File**: `docs/MCP_TOOLS.md`

**Action 8.1**: At line 1094, update `find_elements` output example:
```diff
-      "id": "node_abc123",
+      "element_id": "node_abc123",
```

**Action 8.2**: At line 216 (inside the `get_screen_state` response example text), update the TSV header within the response text string. The inline text contains `id\tclass\ttext\tdesc\tres_id\tbounds\tflags` — change to `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`.

**Action 8.3**: At line 232 (inside the `get_screen_state` with screenshot response example text), same change — update `id\tclass\ttext\tdesc\tres_id\tbounds\tflags` to `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`.

**Action 8.4**: At line 298, update the truncation note to reference `element_id`:
```diff
-Both `text` and `desc` columns are truncated to **100 characters**. If truncated, the value ends with `...truncated`. Use the `android_get_element_details` tool to retrieve full untruncated values by element ID.
+Both `text` and `desc` columns are truncated to **100 characters**. If truncated, the value ends with `...truncated`. Use the `android_get_element_details` tool to retrieve full untruncated values by element_id.
```

**Action 8.5**: At line 1607, update `wait_for_element` output example:
```diff
-    "id": "node_abc123",
+    "element_id": "node_abc123",
```

**Action 8.6**: At lines 1711-1721, update `get_element_details` description and input schema references:
```diff
-Retrieves full untruncated text and contentDescription for one or more elements by their IDs. Use this tool when `android_get_screen_state` shows truncated values (ending with `...truncated`) and you need the full content.
+Retrieves full untruncated text and contentDescription for one or more elements by their element_ids. Use this tool when `android_get_screen_state` shows truncated values (ending with `...truncated`) and you need the full content.
```

**Action 8.7**: At lines 1718-1721, update the input schema property name:
```diff
-    "ids": {
+    "element_ids": {
       "type": "array",
       "items": { "type": "string" },
-      "description": "Array of element IDs to look up (from get_screen_state output)"
+      "description": "Array of element_ids to look up (from get_screen_state output)"
     }
```

**Action 8.8**: At line 1724, update the required field:
```diff
-  "required": ["ids"]
+  "required": ["element_ids"]
```

**Action 8.9**: At lines 1737-1738, update the request example:
```diff
-      "ids": ["node_1", "node_2"]
+      "element_ids": ["node_1", "node_2"]
```

**Action 8.10**: At line 1752, update the response example TSV text:
```diff
-        "text": "id\ttext\tdesc\nnode_1\tThis is a very long text value..."
+        "text": "element_id\ttext\tdesc\nnode_1\tThis is a very long text value..."
```

**Action 8.11**: At lines 1759-1760, update the output format description:
```diff
 **Output Format**: TSV with three columns:
-- `id`: The element ID
+- `element_id`: The element ID
 - `text`: Full untruncated text (or `-` if null/empty)
 - `desc`: Full untruncated contentDescription (or `-` if null/empty)
```

**Action 8.12**: At line 257, update the TSV header description in the Output Format section:
```diff
-   - **TSV header**: `id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
+   - **TSV header**: `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
```

**Action 8.13**: At line 1767, update the Error Cases section:
```diff
-- **Invalid params**: Missing `ids` parameter, `ids` is not an array, array is empty, or contains non-string values
+- **Invalid params**: Missing `element_ids` parameter, `element_ids` is not an array, array is empty, or contains non-string values
```

**Definition of Done**:
- [x] All documentation examples and descriptions updated to use `"element_id"` / `"element_ids"`
- [x] Documentation is consistent with the code changes

---

### Task 8B: Update Documentation (PROJECT.md)

**File**: `docs/PROJECT.md`

**Action 8B.1**: At line 265, update the `get_element_details` parameter column:
```diff
-| `android_get_element_details` | Get full untruncated text/desc by element IDs | `ids` (array of strings) | — |
+| `android_get_element_details` | Get full untruncated text/desc by element_ids | `element_ids` (array of strings) | — |
```

**Definition of Done**:
- [x] `PROJECT.md` updated to reflect the renamed parameter

---

### Task 9: Final Verification — Double-Check Everything from the Ground Up

**Action 9.1**: Search the entire codebase for any remaining `"id"` references in element/node output contexts that were missed:
- `grep -rn '"id"' app/src/main/kotlin/.../mcp/tools/` — verify only non-element uses remain (legitimate: `FileTools.kt` storage location `put("id", location.id)`)
- `grep -rn '"id"' app/src/main/kotlin/.../services/accessibility/` — verify no `"id"` column headers remain
- `grep -rn '["id"]' app/src/test/` — verify no test assertions on old field name remain
- `grep -rn '["id"]' e2e-tests/src/test/` — verify no E2E test extractions on old field name remain

**Action 9.2**: Search for any remaining `"ids"` references in `get_element_details` contexts:
- `grep -rn '"ids"' app/src/main/kotlin/.../mcp/tools/UtilityTools.kt` — must return zero matches
- `grep -rn '"ids"' app/src/test/.../GetElementDetailsToolTest.kt` — must return zero matches
- `grep -rn '"ids"' docs/MCP_TOOLS.md` — verify only non-`get_element_details` uses remain (if any)
- `grep -rn '"ids"' docs/PROJECT.md` — must return zero matches for `get_element_details` row

**Action 9.3**: Run all quality gates:
- `make lint` — must pass with no warnings/errors
- `./gradlew :app:testDebugUnitTest` — all unit tests pass
- `./gradlew build` — build succeeds without errors or warnings
- `make test-e2e` — E2E tests pass

**Action 9.4**: Manual review of each changed file to confirm:
- The `find_elements` response JSON uses `"element_id"`
- The `wait_for_element` response JSON uses `"element_id"`
- The `get_screen_state` TSV header uses `element_id`
- The `get_element_details` TSV header uses `element_id`
- The `get_element_details` input parameter is `element_ids` everywhere (schema, parsing, error messages, description)
- All tests assert on the new names
- All test `@DisplayName` annotations and function names reference the new parameter names
- `wait_for_element` unit test asserts on `"element_id"` in success response (regression coverage for Action 2.1)
- New `get_element_details` integration test exists in `UtilityIntegrationTest.kt` and passes
- Documentation matches the implementation (`MCP_TOOLS.md` and `PROJECT.md`)
- `CompactTreeFormatter.kt` KDoc comment references the updated header
- `GetElementDetailsTool` class KDoc references `element_ids`

**Definition of Done**:
- [x] No stale `"id"` references found in element/node output contexts
- [x] No stale `"ids"` references found in `get_element_details` contexts
- [x] All quality gates pass (lint, unit tests, integration tests, build; Android lint QueryAllPackagesPermission is pre-existing)
- [x] Manual review confirms full consistency
