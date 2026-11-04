<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 69: node accessibility actions

Expose each node's labelled accessibility actions and run them by label (Reddit vote bar: one node, buttons only as actions).

Rules:
- **Listed actions:** an action is listed when its label is not blank after sanitizing (tabs, `\n`, `\r` and `¦` become spaces, then trimmed). Custom and standard actions alike, in their original order.
- **Excluded:** standard actions that take arguments, by compile-time int ids equal to `AccessibilityNodeInfo.ACTION_*` / `android.R.id.accessibilityAction*` (never the `AccessibilityAction.X` objects, which are null in JVM tests): SET_TEXT, SET_SELECTION, SET_EXTENDED_SELECTION, NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY, NEXT/PREVIOUS_HTML_ELEMENT, SCROLL_TO_POSITION, SET_PROGRESS, MOVE_WINDOW, SCROLL_IN_DIRECTION, PRESS_AND_HOLD.
- **Format:** separator `¦`; action ids are never exposed.
- **No label in errors or logs:** no error message and no log line carries a label. Labels are returned only in output listings and in the no-match result of the new tool; its success text echoes the client's `action` as given.

## Model and parsing
- [ ] `AccessibilityNodeData` (`:privacy`): add `actions: List<String> = emptyList()`.
- [ ] Shared helper in `services/accessibility/` that applies the rules.
  - Parser path: labels only, the shared `emptyList()` when none, no intermediate collections.
  - Executor path: the first listed action whose sanitized label equals a given label.
- [ ] `AccessibilityTreeParser`: fill `actions` at both construction sites.

## Output
- [ ] `CompactTreeFormatter.shouldKeepNode`: also keep a node with `actions` not empty.
  - The screenshot annotator follows the same rule, so action-only nodes get boxes.
- [ ] `WebViewNodeMerger`: a node with `actions` not empty is kept like an interactive anchor; `actions` survive the anchor and block copies.
- [ ] `formatMultiWindow`, `formatMultiWindowPage` and `format`: the actions section, per window, after the rows and before `hierarchy:`.
  - It is the constant `Nodes with actions:` (next to `HIERARCHY_HEADER`), one blank line, then `* <node_id>: <a>¦<b>` for each listed (page) node with actions.
  - No blank line after the list; omitted when none.
- [ ] `ElementInfo` + `toElementInfo`: add `actions`.
- [ ] `McpToolUtils.buildNodeJson`: add `"actions": [...]`, which reaches `find_nodes`, `wait_for_node` and the ambiguous-selector candidates.
- [ ] `get_node_details`: new last column `actions`, `¦`-joined.
  - `-` when empty, `not_found` for an unknown node id.

## Privacy Mode (no benchmark, owner decision)
- [ ] `RedactionEngine.redactTree`: redact each label with the node's context, like text and content description.
  - Rebuild `actions` from the redacted labels.
  - A changed label adds the node's bounds to the flagged bounds.
- [ ] `find_nodes`, `wait_for_node`, ambiguous-selector candidates, `get_node_details`: redact labels through `privacyToolGate.nodeTexts`, together with text and content description.

## Tool `android_perform_node_action`
- [ ] `ActionExecutor` + `ActionExecutorImpl`: `performLabelledAction(nodeId, windows, label)` via `performNodeAction`.
  - Runs the helper's action on the live node.
  - No live match: `IllegalStateException("Node '<id>' no longer offers the requested action; read the screen again")`.
  - `performAction` false: `IllegalStateException("Action failed on node '<id>'")`.
  - Both are `IllegalStateException`, so `mapNodeActionException` passes the text to the client verbatim.
- [ ] `PerformNodeActionTool` in `NodeActionTools.kt` (`TOOL_NAME = "perform_node_action"`).
  - Parameters: a node target like `click_node` (`node_id` or selector) and a required string `action`.
  - `action` is empty: `InvalidParams`.
  - `action` is matched as given, never passed through `PlaceholderSubstitutor`.
  - A `node_id` not in the fresh read: `NodeNotFound` (`Node '<id>' not found`), before any matching.
  - Matching: fresh read, resolve the target, then match the node's listed labels as shown (redacted when Privacy Mode is on), exact and case-sensitive; the first match wins.
  - No match: an untrusted error result listing the node's labels as shown.
  - The executor gets the unredacted label at the matched position.
  - Success: `textResult("Action '<action>' performed on node '<node_id>'")`.
  - Failures go through `mapNodeActionException`; logs carry the node id only.
- [ ] Register it in `registerNodeActionTools` behind `perms.isToolEnabled`.
- [ ] `McpToolsSettingsScreen`: Node Actions entry, plus the `mcp_tool_perform_node_action` string.
- [ ] Tool descriptions:
  - `get_screen_state` and `find_nodes` mention the section/field and the new tool;
  - `get_node_details` mentions the `actions` column.

## Tests
- [ ] Unit:
  - parser: labels, unlabelled actions skipped, excluded actions, sanitizing (including `\r`), order;
  - `AccessibilityAction` instances are mocks with `id` and `label` stubbed, since real ones answer 0/null on the JVM;
  - helper: both paths;
  - formatter: keep rule, section in the three outputs and in pages, page-only nodes, omitted when none, offscreen off, hierarchy off, exact bytes;
  - `ScreenIntrospectionToolsTest`: an action-only visible node is annotated;
  - `WebViewNodeMerger`: actions survive the anchor and block copies, a plain web node with actions is kept;
  - `ElementFinder` / `buildNodeJson`;
  - `get_node_details`: column, `-`, `not_found`;
  - `RedactionEngine`: labels redacted, flagged bounds;
  - `PrivacyToolGate` users;
  - `ActionExecutorImpl`: cache hit, fallback, no live match, `performAction` false, the exact label-free messages;
  - `PerformNodeActionTool`: success, selector, unknown `node_id`, no match lists labels, first of duplicates, redacted match, no placeholder substitution, missing/empty/non-string `action`, label gone on the live node and executor failure (asserting the mapped text), accessibility not ready.
- [ ] Integration:
  - `perform_node_action` through the full stack;
  - the section in `get_screen_state`;
  - the `find_nodes` field;
  - the `get_node_details` column;
  - Privacy Mode redacts labels and matches the redacted label;
  - tool count 58 → 59 in `McpProtocolIntegrationTest` (count, `EXPECTED_TOOL_NAMES` and the test name), `AuthIntegrationTest`, `ToolPermissionsIntegrationTest.ALL_TOOL_NAMES`.
- [ ] E2E:
  - `compose-test-app`: a new activity with a counter `Text` and a node with ONLY custom actions "Upvote"/"Downvote" (no text, desc, testTag or click), so the actions alone get it listed;
  - a driver in `E2ETestApps`;
  - test: the section lists the node, `find_nodes` and `get_node_details` show the actions, `perform_node_action` "Upvote" changes the counter, an unknown label returns an error.
- [ ] `E2ETestApps.uiAutomatorKeptNodeCount`: the comment notes that a uiautomator dump has no action labels, so the reduction assertion holds only while web nodes carry no labelled actions.

## Docs
- [ ] `docs/MCP_TOOLS.md`:
  - tool count and category table;
  - `get_screen_state` output format and node filtering;
  - "Targeting a node" list;
  - `find_nodes` JSON and `wait_for_node` response example;
  - `get_node_details` TSV and its `not_found` sentence;
  - a new tool section.
- [ ] `docs/PROJECT.md`: tool counts, Node Action Tools table (6 → 7), `get_screen_state` note, `get_node_details` row.
- [ ] `README.md`: tool counts 58 → 59.
- [ ] `CONTRIBUTING.md`: tool counts at :101 and in the Mermaid chart (:171), validated with `mmdc`.

## Gates and delivery
- [ ] Gates:
  - `make lint`;
  - unit tests (gms + foss);
  - `./gradlew build` with no `^w: ` line;
  - `make test-e2e` with `--no-build-cache`, under the phone marker.
- [ ] Manual QA on the T70 with Reddit: the vote bar lists its actions; `perform_node_action` upvotes and undoes.
- [ ] Code review loop; the PR says it improves the use of many apps, Reddit included.
