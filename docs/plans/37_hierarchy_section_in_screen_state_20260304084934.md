<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 37: Add hierarchy section to `android_get_screen_state` output

## Purpose

Add an indented tree representation of node nesting to the `android_get_screen_state` output, placed **after** the elements table in each window section. The hierarchy includes **only** nodes that pass the existing `shouldKeepNode()` filter (the same nodes in the elements table). Structural-only nodes that are filtered out are skipped, and their kept children are promoted to the filtered parent's depth level.

Format — indentation-based (2 spaces per depth level), element IDs only:

```
hierarchy:
node_a
  node_b
    node_c
  node_d
```

---

## User Story 1: Add hierarchy output to CompactTreeFormatter

### Acceptance Criteria

- [x] A `hierarchy:` section appears after the elements table for each window in `formatMultiWindow()` output
- [x] A `hierarchy:` section appears after the elements table in `format()` output
- [x] The hierarchy contains only nodes that pass `shouldKeepNode()` — same set as the elements table
- [x] Tree traversal is visitor-based — single `walkTree` pass with visitor lambdas for TSV rows and hierarchy lines
- [x] Nesting uses 2-space indentation per depth level
- [x] When a structural-only node is filtered out, its kept children are promoted to the filtered parent's depth (no phantom indentation gaps)
- [x] Each line in the hierarchy section contains only the node's `id`
- [x] Empty hierarchies (all nodes filtered) produce just the `hierarchy:` label with no node lines
- [x] The tool description in `ScreenIntrospectionTools.kt` is updated to mention the hierarchy section
- [x] `CompactTreeFormatter` class-level KDoc is updated to document the hierarchy section
- [x] `MCP_TOOLS.md` output format spec and response examples are updated to include the hierarchy section
- [x] Integration test `get_screen_state returns compact flat TSV with metadata` asserts hierarchy presence

### Task 1.1: Refactor `walkNode` to visitor-based `walkTree` and add hierarchy output

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt` — modify

**Action 1.1.1**: Add `HIERARCHY_HEADER` constant in companion object

```kotlin
const val HIERARCHY_HEADER = "hierarchy:"
```

**Action 1.1.2**: Add `HIERARCHY_INDENT` constant in companion object

```kotlin
private const val HIERARCHY_INDENT = "  "
```

**Action 1.1.3**: Refactor `walkNode` (lines 76-99) into three methods: `walkTree` (generic visitor-based traversal), `appendElementRow` (extracted TSV row logic), and `appendHierarchyNode` (new hierarchy line logic).

Replace the entire `walkNode` method with:

```kotlin
private fun walkTree(
    node: AccessibilityNodeData,
    depth: Int = 0,
    visitors: List<(node: AccessibilityNodeData, depth: Int) -> Unit>,
) {
    val isKept = shouldKeepNode(node)
    if (isKept) {
        for (visitor in visitors) {
            visitor(node, depth)
        }
    }
    val childDepth = if (isKept) depth + 1 else depth
    for (child in node.children) {
        walkTree(child, childDepth, visitors)
    }
}

private fun appendElementRow(
    sb: StringBuilder,
    node: AccessibilityNodeData,
) {
    val id = node.id
    val className = simplifyClassName(node.className)
    val text = sanitizeText(node.text)
    val desc = sanitizeText(node.contentDescription)
    val resId = sanitizeResourceId(node.resourceId)
    val bounds =
        "${node.bounds.left},${node.bounds.top}," +
            "${node.bounds.right},${node.bounds.bottom}"
    val flags = buildFlags(node)
    sb.appendLine(
        "$id$SEP$className$SEP$text$SEP$desc$SEP$resId$SEP$bounds$SEP$flags",
    )
}

private fun appendHierarchyNode(
    sb: StringBuilder,
    node: AccessibilityNodeData,
    depth: Int,
) {
    repeat(depth) { sb.append(HIERARCHY_INDENT) }
    sb.appendLine(node.id)
}
```

`walkTree` is a generic traversal that handles `shouldKeepNode()` filtering and depth tracking. It calls each visitor for every kept node, passing the node and its depth. Visitors are lambdas that capture their own `StringBuilder` — `walkTree` has no knowledge of TSV or hierarchy formatting.

**Action 1.1.4**: In `formatMultiWindow()`, replace the `walkNode` call with `walkTree` using visitors.

Change the per-window loop (lines 131-135) from:

```kotlin
for (windowData in result.windows) {
    sb.appendLine(buildWindowHeader(windowData))
    sb.appendLine(HEADER)
    walkNode(sb, windowData.tree)
}
```

to:

```kotlin
for (windowData in result.windows) {
    sb.appendLine(buildWindowHeader(windowData))
    sb.appendLine(HEADER)
    val hierarchySb = StringBuilder()
    walkTree(
        windowData.tree,
        visitors = listOf(
            { node, _ -> appendElementRow(sb, node) },
            { node, depth -> appendHierarchyNode(hierarchySb, node, depth) },
        ),
    )
    sb.appendLine(HIERARCHY_HEADER)
    sb.append(hierarchySb)
}
```

The TSV visitor captures `sb` (main output), the hierarchy visitor captures `hierarchySb` (buffer). After the single walk, the hierarchy buffer is appended after the `hierarchy:` header.

**Action 1.1.5**: In `format()`, replace the `walkNode` call with `walkTree` using visitors.

Replace `walkNode(sb, tree)` (line 71) with:

```kotlin
val hierarchySb = StringBuilder()
walkTree(
    tree,
    visitors = listOf(
        { node, _ -> appendElementRow(sb, node) },
        { node, depth -> appendHierarchyNode(hierarchySb, node, depth) },
    ),
)
sb.appendLine(HIERARCHY_HEADER)
sb.append(hierarchySb)
```

**Action 1.1.6**: Update the class-level KDoc (lines 5-31) to document the hierarchy section.

Add after `* - Lines 8+: one TSV row per kept node (flat, no depth)` (line 18):

```
 * - Line N: `hierarchy:`
 * - Lines N+1+: one line per kept node, indented by 2 spaces per nesting depth
```

#### Definition of Done

- [x] `walkNode` removed; replaced by `walkTree` — generic visitor-based traversal handling `shouldKeepNode()` filtering and depth tracking
- [x] `appendElementRow` extracted — builds TSV row from node (same logic as old `walkNode`)
- [x] `appendHierarchyNode` added — builds indented hierarchy line (2-space per depth, element ID only)
- [x] `formatMultiWindow()` uses `walkTree` with TSV and hierarchy visitors, appends `hierarchy:` section after element rows for each window
- [x] `format()` uses `walkTree` with TSV and hierarchy visitors, appends `hierarchy:` section after element rows
- [x] Class-level KDoc documents the hierarchy section

### Task 1.2: Update tool description

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt` — modify

**Action 1.2.1**: Update the tool description string (line 179-183) to mention the hierarchy.

Change from:

```kotlin
"Returns the current screen state: app info, screen dimensions, " +
    "and a compact UI element list (text/desc truncated to 100 chars, use " +
    "${toolNamePrefix}get_element_details to retrieve full values). Optionally includes a " +
    "low-resolution screenshot (only request the screenshot when the element " +
    "list alone is not sufficient to understand the screen layout).",
```

to:

```kotlin
"Returns the current screen state: app info, screen dimensions, " +
    "and a compact UI element list (text/desc truncated to 100 chars, use " +
    "${toolNamePrefix}get_element_details to retrieve full values). Optionally includes a " +
    "low-resolution screenshot (only request the screenshot when the element " +
    "list alone is not sufficient to understand the screen layout). " +
    "Includes a hierarchy section showing node nesting via indentation.",
```

#### Definition of Done

- [x] Tool description mentions the hierarchy section

### Task 1.3: Update `MCP_TOOLS.md` output format spec

**File**: `docs/MCP_TOOLS.md` — modify

**Action 1.3.1**: Update the per-window section structure (line 259-262).

Change from:

```markdown
4. **Per-window sections** (repeated for each window):
   - **Window header**: `--- window:<id> type:<TYPE> pkg:<package> title:<title> [activity:<activity>] layer:<N> focused:<bool> ---`
   - **TSV header**: `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
   - **Data rows**: One row per filtered node with tab-separated values
```

to:

```markdown
4. **Per-window sections** (repeated for each window):
   - **Window header**: `--- window:<id> type:<TYPE> pkg:<package> title:<title> [activity:<activity>] layer:<N> focused:<bool> ---`
   - **TSV header**: `element_id\tclass\ttext\tdesc\tres_id\tbounds\tflags`
   - **Data rows**: One row per filtered node with tab-separated values
   - **Hierarchy header**: `hierarchy:`
   - **Hierarchy rows**: One line per kept node (element ID only), indented by 2 spaces per nesting depth. Structural-only nodes are omitted; their kept children are promoted to the parent's depth.
```

**Action 1.3.2**: Update the text-only response example (line 220) to append the hierarchy section to the `text` field value.

Append before the closing `"` of the `text` field:

```
\nhierarchy:\nnode_a1b2\nnode_c3d4
```

Note: both `node_a1b2` and `node_c3d4` are kept nodes at the same depth (siblings under filtered root), so both appear at depth 0 with no indentation.

**Action 1.3.3**: Update the with-screenshot response example (line 236) to include `hierarchy:` in the abbreviated `text` field.

Change the abbreviated text value to include `...` that implies hierarchy is present after the TSV rows. The existing `\n...` already serves this purpose — no change needed to the abbreviated example.

#### Definition of Done

- [x] Per-window section structure includes hierarchy header and hierarchy rows
- [x] Text-only response example includes hierarchy section

### Task 1.4: Update `CompactTreeFormatterTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatterTest.kt` — modify

**Action 1.4.1**: Update existing tests in `FormatTests` that assert on line counts — they will shift because of the added `hierarchy:` line + hierarchy node lines.

Affected tests and their new expected line counts:

| Test method | Current line count assertion | New line count assertion | Reason |
|---|---|---|---|
| `filtersOutNoiseNodes` | `assertEquals(7, lines.size)` | `assertEquals(8, lines.size)` | +1 for `hierarchy:` line (no kept nodes, so no hierarchy node lines) |
| `includesChildrenOfFilteredNodes` | `assertEquals(8, lines.size)` | `assertEquals(10, lines.size)` | +1 for `hierarchy:` line, +1 for hierarchy node `node_child` |
| `includesNonVisibleNodesThatPassFilter` | `assertEquals(8, lines.size)` | `assertEquals(10, lines.size)` | +1 for `hierarchy:` line, +1 for hierarchy node `node_hidden` |
| `handlesTreeWithAllNodesFiltered` | `assertEquals(7, lines.size)` | `assertEquals(8, lines.size)` | +1 for `hierarchy:` line (no kept nodes) |

**Action 1.4.2**: Add new `Nested` test class `WalkHierarchyTests` inside `CompactTreeFormatterTest`.

Tests use `format()` output (single-window) and extract the hierarchy section (everything after the `hierarchy:` line).

| Test | Verifies |
|---|---|
| `hierarchy section present after elements` | Output contains `hierarchy:` line after the last element row |
| `single kept node produces flat hierarchy` | Single kept root node → `hierarchy:` + one line with `node_id` at depth 0 |
| `parent-child nesting uses 2-space indentation` | Kept parent with kept child → child indented 2 spaces under parent |
| `filtered parent promotes children to parent depth` | Structural parent (filtered) with kept child → child at depth 0 (promoted) |
| `deep nesting produces correct indentation levels` | 3-level kept hierarchy → depths 0, 2, 4 spaces |
| `multiple children at same level` | Kept parent with 2 kept children → both at depth 1 (2 spaces) |
| `all nodes filtered produces only hierarchy label` | Tree with only structural nodes → `hierarchy:` line with no node lines after it |
| `mixed kept and filtered in deep tree` | Kept → filtered → kept chain → inner kept promoted. **Setup**: root(kept) → mid(filtered) → leaf(kept); expect leaf at depth 1 (2 spaces), not depth 2 |

**Action 1.4.3**: Add new tests to `FormatMultiWindow` nested class.

| Test | Verifies |
|---|---|
| `each window has hierarchy section` | Two-window result → each window section contains its own `hierarchy:` label and nodes |

#### Definition of Done

- [x] All existing tests updated for new line counts
- [x] New `WalkHierarchyTests` nested class with all tests listed above
- [x] New multi-window hierarchy test added

### Task 1.5: Update `ScreenIntrospectionToolsTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionToolsTest.kt` — modify

**Action 1.5.1**: Update the `sampleCompactOutput` string constant (lines 97-109) to include the hierarchy section.

The current `sampleCompactOutput` ends with:
```
"node_btn\tButton\tOK\t-\t-\t100,200,300,260\ton,clk,ena"
```

Append the hierarchy section:
```kotlin
"\nhierarchy:\nnode_btn"
```

Note: `node_root` is a structural-only node (no text, no desc, no resourceId, not clickable/longClickable/scrollable/editable), so it does NOT appear in the hierarchy. Only `node_btn` appears at depth 0 (promoted from depth 1 because root is filtered).

#### Definition of Done

- [x] `sampleCompactOutput` includes the `hierarchy:` section matching what the real formatter produces for `sampleTree`

### Task 1.6: Add hierarchy assertion to `ScreenIntrospectionIntegrationTest`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt` — modify

**Action 1.6.1**: In the test `get_screen_state returns compact flat TSV with metadata`, add a `assertTrue` assertion after the existing `contains` checks (after line 109).

Add:

```kotlin
assertTrue(textContent.contains("hierarchy:"))
```

#### Definition of Done

- [x] Integration test asserts that `hierarchy:` is present in the screen state output

### Task 1.7: Quality gates

- [x] `make lint` passes with zero warnings/errors
- [x] `make lint-fix` applied if needed
- [x] `./gradlew test` passes with zero failures (NgrokTunnelIntegrationTest excluded — pre-existing env-dependent failure)
- [x] `./gradlew build` passes with zero warnings/errors (same NgrokTunnelIntegrationTest caveat)

### Task 1.8: Verify implementation from the ground up

Perform a complete end-to-end review of all changes:

- [x] Re-read `CompactTreeFormatter.kt` and verify: `walkNode` is removed; `walkTree` handles filtering + depth tracking + visitor dispatch; `appendElementRow` produces the same TSV rows as old `walkNode`; `appendHierarchyNode` uses 2-space indent with depth promotion for filtered nodes, element IDs only
- [x] Re-read `CompactTreeFormatter.kt` class-level KDoc and verify it documents the hierarchy section
- [x] Re-read `formatMultiWindow()` and verify the hierarchy section is placed after the elements table for each window
- [x] Re-read `format()` and verify the hierarchy section is placed after the elements table
- [x] Re-read the tool description in `ScreenIntrospectionTools.kt` and verify it mentions the hierarchy
- [x] Re-read `MCP_TOOLS.md` output format spec and verify the per-window section structure includes hierarchy header and rows, and the text-only response example includes the hierarchy section
- [x] Re-read all new and updated tests in `CompactTreeFormatterTest.kt` and verify they cover: single node, parent-child, filtered parent promotion, deep nesting, siblings, all-filtered, mixed tree, multi-window
- [x] Re-read `ScreenIntrospectionToolsTest.kt` and verify `sampleCompactOutput` matches the real formatter output for `sampleTree`
- [x] Re-read `ScreenIntrospectionIntegrationTest.kt` and verify the hierarchy assertion is present
- [x] Confirm no files outside the plan scope were modified
- [x] Run `code-reviewer` subagent in plan compliance mode
