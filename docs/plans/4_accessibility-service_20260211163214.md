# Plan 4: AccessibilityService & Core Accessibility Logic

**Branch**: `feat/accessibility-service`
**PR Title**: `Plan 4: AccessibilityService and core accessibility logic`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan implements the Android AccessibilityService, accessibility tree parser, element finder, and action executor -- the core engine for UI introspection and action execution. These components are the foundation for all MCP tools that interact with the device UI.

### Dependencies on Previous Plans

- **Plan 1**: Project scaffolding, Gradle build system, `AndroidManifest.xml` with accessibility service declaration, `accessibility_service_config.xml`, stub `McpAccessibilityService.kt`
- **Plan 2**: `Logger` utility for structured logging, `PermissionUtils` for accessibility service detection
- **Plan 3**: UI layer (no direct code dependency, but MainViewModel may observe accessibility service state)

### Package Base

`com.danielealbano.androidremotecontrolmcp`

### Path References

- **Source root**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Test root**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`

---

## User Story 1: AccessibilityService & Core Accessibility Logic

**As a** developer building MCP tools for remote device control
**I want** a fully functional AccessibilityService with tree parsing, element finding, and action execution capabilities
**So that** MCP tools (Plans 7-9) can introspect the UI hierarchy, locate specific elements, and perform touch/click/gesture actions on the device.

### Acceptance Criteria / Definition of Done (High Level)

- [x] `McpAccessibilityService` extends `AccessibilityService` with singleton lifecycle (companion object `instance`)
- [x] `McpAccessibilityService` tracks current package name and activity name from accessibility events
- [x] `McpAccessibilityService` provides `getRootNode()`, `getCurrentPackageName()`, `getCurrentActivityName()`, `isReady()`
- [x] `McpAccessibilityService` handles `onServiceConnected()`, `onAccessibilityEvent()`, `onInterrupt()`, `onDestroy()`, `onLowMemory()`, `onTrimMemory()`
- [x] `McpAccessibilityService` uses `CoroutineScope(SupervisorJob() + Dispatchers.Main)` for the service scope
- [x] `AccessibilityNodeData` and `BoundsData` are `@Serializable` data classes representing the parsed accessibility tree
- [x] `AccessibilityTreeParser` recursively parses `AccessibilityNodeInfo` into `AccessibilityNodeData` with stable node IDs
- [x] `AccessibilityTreeParser` properly recycles `AccessibilityNodeInfo` nodes after parsing
- [x] `ElementFinder` finds elements by `TEXT`, `CONTENT_DESC`, `RESOURCE_ID`, `CLASS_NAME` with exact and contains matching
- [x] `ElementFinder` operates on parsed `AccessibilityNodeData` (not raw `AccessibilityNodeInfo`), making it testable without Android framework
- [x] `ElementInfo` data class represents found elements with key properties and bounds
- [x] `ActionExecutor` performs node actions: click, long click, set text, scroll
- [x] `ActionExecutor` performs coordinate-based actions: tap, long press, double tap, swipe, scroll
- [x] `ActionExecutor` performs global actions: back, home, recents, notifications, quick settings
- [x] `ActionExecutor` performs advanced gestures: pinch, custom multi-path gesture
- [x] `ActionExecutor` uses `suspendCancellableCoroutine` to wrap `GestureResultCallback`
- [x] `ActionExecutor` validates coordinates are within screen bounds
- [x] `ActionExecutor` checks `McpAccessibilityService.instance` before all operations
- [x] Unit tests exist and pass for `AccessibilityTreeParser`, `ElementFinder`, and `ActionExecutor`
- [x] `make lint` passes with no warnings or errors
- [x] `make test-unit` passes with all tests green
- [x] `make build` succeeds without errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add McpAccessibilityService with singleton lifecycle` | `McpAccessibilityService.kt` |
| 2 | `feat: add accessibility tree parser with JSON-serializable output` | `AccessibilityTreeParser.kt` |
| 3 | `feat: add element finder with multi-criteria search` | `ElementFinder.kt` |
| 4 | `feat: add action executor for gestures, clicks, and global actions` | `ActionExecutor.kt` |
| 5 | `test: add unit tests for accessibility tree parser` | `AccessibilityTreeParserTest.kt` |
| 6 | `test: add unit tests for element finder and action executor` | `ElementFinderTest.kt`, `ActionExecutorTest.kt` |

---

### Task 4.1: Implement McpAccessibilityService

**Description**: Replace the stub `McpAccessibilityService.kt` with the full implementation. This service is the Android-managed accessibility service that provides UI introspection capabilities. It stores a singleton instance in a companion object for inter-service access, tracks the currently focused app/activity, and manages a coroutine scope for async operations.

**Acceptance Criteria**:
- [x] `McpAccessibilityService` extends `android.accessibilityservice.AccessibilityService`
- [x] Companion object stores `var instance: McpAccessibilityService? = null`
- [x] `onServiceConnected()` stores singleton, configures service info, initializes coroutine scope, logs start
- [x] `onAccessibilityEvent()` filters by `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED`, tracks package/activity name
- [x] `onInterrupt()` logs interruption
- [x] `onDestroy()` clears singleton, cancels coroutine scope, logs shutdown
- [x] `onLowMemory()` and `onTrimMemory()` log events
- [x] `getRootNode()` returns root node of active window (or null)
- [x] `getCurrentPackageName()` returns tracked package name
- [x] `getCurrentActivityName()` returns tracked activity name
- [x] `isReady()` returns true when service is connected and root node is available
- [x] File compiles without errors and passes lint

**Tests**: `McpAccessibilityService` is an Android system-managed component and cannot be unit tested directly. Its behavior is verified indirectly through `AccessibilityTreeParser` tests (which mock `AccessibilityNodeInfo`) and through integration/E2E tests in later plans.

#### Action 4.1.1: Replace `McpAccessibilityService.kt` stub with full implementation

**What**: Replace the minimal stub created in Plan 1 with the complete accessibility service implementation.

**Context**: This class is the entry point for all accessibility operations. Android manages its lifecycle (started when user enables it in Settings, stopped when disabled). The singleton pattern is the standard Android approach for accessibility services since there is only ever one instance. The coroutine scope uses `SupervisorJob()` so that failure of one child coroutine does not cancel the others. `Dispatchers.Main` is required because all `AccessibilityNodeInfo` operations must happen on the main thread.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt
@@ -1,18 +1,139 @@
 package com.danielealbano.androidremotecontrolmcp.services.accessibility

+import android.accessibilityservice.AccessibilityService
 import android.accessibilityservice.AccessibilityServiceInfo
+import android.content.ComponentCallbacks2
+import android.util.Log
 import android.view.accessibility.AccessibilityEvent
 import android.view.accessibility.AccessibilityNodeInfo
-import android.accessibilityservice.AccessibilityService
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.cancel

 class McpAccessibilityService : AccessibilityService() {

-    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
-        // Will be implemented in Plan 4
+    private var serviceScope: CoroutineScope? = null
+    private var currentPackageName: String? = null
+    private var currentActivityName: String? = null
+
+    override fun onServiceConnected() {
+        super.onServiceConnected()
+
+        instance = this
+        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
+
+        configureServiceInfo()
+
+        Log.i(TAG, "Accessibility service connected")
+    }
+
+    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
+        if (event == null) return
+
+        when (event.eventType) {
+            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
+                event.packageName?.toString()?.let { packageName ->
+                    currentPackageName = packageName
+                }
+                event.className?.toString()?.let { className ->
+                    currentActivityName = className
+                }
+                Log.d(
+                    TAG,
+                    "Window state changed: package=$currentPackageName, " +
+                        "activity=$currentActivityName",
+                )
+            }
+            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
+                Log.d(TAG, "Window content changed: package=${event.packageName}")
+            }
+            else -> {
+                // Ignored event types
+            }
+        }
     }

     override fun onInterrupt() {
+        Log.w(TAG, "Accessibility service interrupted")
+    }
+
+    override fun onDestroy() {
+        Log.i(TAG, "Accessibility service destroying")
+
+        serviceScope?.cancel()
+        serviceScope = null
+        currentPackageName = null
+        currentActivityName = null
+        instance = null
+
+        super.onDestroy()
+    }
+
+    override fun onLowMemory() {
+        super.onLowMemory()
+        Log.w(TAG, "Low memory condition reported")
+    }
+
+    override fun onTrimMemory(level: Int) {
+        super.onTrimMemory(level)
+        val levelName = when (level) {
+            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
+            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
+            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
+            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
+            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
+            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
+            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
+            else -> "UNKNOWN($level)"
+        }
+        Log.w(TAG, "Trim memory: level=$levelName")
+    }
+
+    /**
+     * Returns the root [AccessibilityNodeInfo] of the currently active window,
+     * or null if no window is available.
+     */
+    fun getRootNode(): AccessibilityNodeInfo? {
+        return rootInActiveWindow
+    }
+
+    /**
+     * Returns the package name of the currently focused application,
+     * or null if unknown.
+     */
+    fun getCurrentPackageName(): String? {
+        return currentPackageName
+    }
+
+    /**
+     * Returns the class name (activity name) of the currently focused window,
+     * or null if unknown.
+     */
+    fun getCurrentActivityName(): String? {
+        return currentActivityName
+    }
+
+    /**
+     * Returns true if the service is connected and has an active root node available.
+     */
+    fun isReady(): Boolean {
+        return instance != null && rootInActiveWindow != null
+    }
+
+    /**
+     * Returns the [CoroutineScope] for this service, or null if not connected.
+     */
+    fun getServiceScope(): CoroutineScope? {
+        return serviceScope
+    }
+
+    private fun configureServiceInfo() {
+        serviceInfo = serviceInfo?.apply {
+            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
+                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
+            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
+            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
+                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
+            notificationTimeout = NOTIFICATION_TIMEOUT_MS
+        }
     }

     companion object {
         private const val TAG = "MCP:AccessibilityService"
+        private const val NOTIFICATION_TIMEOUT_MS = 100L
+
+        /**
+         * Singleton instance of the accessibility service.
+         * Set when the service connects, cleared when it is destroyed.
+         * Access from other components to interact with the accessibility tree.
+         */
+        @Volatile
+        var instance: McpAccessibilityService? = null
+            private set
     }
 }
```

> **Implementation Notes**:
> - The `@Volatile` annotation on `instance` ensures visibility across threads since the service runs on the main thread but may be queried from IO threads (MCP server).
> - `configureServiceInfo()` programmatically sets the service info, which supplements the XML config. The XML config (`accessibility_service_config.xml`) is the declarative configuration read by Android; the programmatic configuration in `onServiceConnected()` can override or extend it at runtime.
> - `rootInActiveWindow` is a property of `AccessibilityService` that returns the root node. It may return null if no window is active.
> - The `serviceScope` is exposed via `getServiceScope()` for potential use by other components that need to launch coroutines tied to the service lifecycle.

---

### Task 4.2: Implement AccessibilityTreeParser

**Description**: Create the `AccessibilityTreeParser` that converts the Android `AccessibilityNodeInfo` tree into a serializable `AccessibilityNodeData` hierarchy. This parser is the bridge between the Android framework's accessibility API and our JSON-based MCP protocol.

**Acceptance Criteria**:
- [x] `BoundsData` is a `@Serializable` data class with `left`, `top`, `right`, `bottom` Int fields
- [x] `AccessibilityNodeData` is a `@Serializable` data class with all specified fields including `children: List<AccessibilityNodeData>`
- [x] `AccessibilityTreeParser` has `@Inject constructor()` for Hilt injection
- [x] `parseTree(rootNode)` recursively parses the entire accessibility tree
- [x] `parseNode(node, depth, index, parentId)` parses a single node and its children
- [x] `isNodeVisible(node)` checks node visibility using `isVisibleToUser`
- [x] Node ID generation is stable: uses combination of resource ID, class name, bounds, depth, and index
- [x] Nodes are properly recycled after data extraction (children obtained via `getChild(i)` are recycled)
- [x] File compiles without errors and passes lint

**Tests**: Unit tests in Task 4.5 (`AccessibilityTreeParserTest.kt`).

#### Action 4.2.1: Create `AccessibilityTreeParser.kt`

**What**: Create the accessibility tree parser with `@Serializable` data classes and recursive parsing logic.

**Context**: The parser converts the Android `AccessibilityNodeInfo` tree (which uses native resources and requires recycling) into plain Kotlin data classes that can be serialized to JSON for the MCP protocol. The `AccessibilityNodeInfo` objects are framework-managed and must be recycled after use to free native memory. The parser must be thread-safe: all `AccessibilityNodeInfo` operations happen on the main thread.

Node ID generation must be deterministic so that IDs returned by `find_elements` MCP tool can be used with `click_element` -- as long as the UI has not changed, the same element should get the same ID. We use a combination of resource ID (if available), class name, bounds, and positional index within the tree to create a stable hash.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt
@@ -0,0 +1,174 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import android.graphics.Rect
+import android.util.Log
+import android.view.accessibility.AccessibilityNodeInfo
+import kotlinx.serialization.Serializable
+import javax.inject.Inject
+
+/**
+ * Represents the screen bounds of an accessibility node.
+ */
+@Serializable
+data class BoundsData(
+    val left: Int,
+    val top: Int,
+    val right: Int,
+    val bottom: Int,
+)
+
+/**
+ * Represents a parsed accessibility node with all relevant properties.
+ *
+ * This is a JSON-serializable representation of [AccessibilityNodeInfo],
+ * used as the MCP protocol output for `get_accessibility_tree` and related tools.
+ *
+ * @property id Stable generated ID for this node, used by element action tools.
+ * @property className The class name of the view (e.g., "android.widget.Button").
+ * @property text The text content of the node.
+ * @property contentDescription The content description for accessibility.
+ * @property resourceId The view ID resource name (e.g., "com.example:id/button1").
+ * @property bounds The screen bounds of the node.
+ * @property clickable Whether the node responds to click actions.
+ * @property longClickable Whether the node responds to long-click actions.
+ * @property focusable Whether the node can receive focus.
+ * @property scrollable Whether the node is scrollable.
+ * @property editable Whether the node is an editable text field.
+ * @property enabled Whether the node is enabled.
+ * @property visible Whether the node is visible to the user.
+ * @property children The child nodes of this node.
+ */
+@Serializable
+data class AccessibilityNodeData(
+    val id: String,
+    val className: String? = null,
+    val text: String? = null,
+    val contentDescription: String? = null,
+    val resourceId: String? = null,
+    val bounds: BoundsData,
+    val clickable: Boolean = false,
+    val longClickable: Boolean = false,
+    val focusable: Boolean = false,
+    val scrollable: Boolean = false,
+    val editable: Boolean = false,
+    val enabled: Boolean = false,
+    val visible: Boolean = false,
+    val children: List<AccessibilityNodeData> = emptyList(),
+)
+
+/**
+ * Parses an [AccessibilityNodeInfo] tree into a serializable [AccessibilityNodeData] hierarchy.
+ *
+ * All [AccessibilityNodeInfo] child nodes obtained via [AccessibilityNodeInfo.getChild] are
+ * recycled after their data has been extracted. The root node passed to [parseTree] is NOT
+ * recycled by the parser -- the caller is responsible for recycling it.
+ *
+ * This class is Hilt-injectable and stateless.
+ */
+class AccessibilityTreeParser @Inject constructor() {
+
+    /**
+     * Parses the full accessibility tree starting from [rootNode].
+     *
+     * The [rootNode] is NOT recycled by this method. The caller retains ownership.
+     *
+     * @param rootNode The root [AccessibilityNodeInfo] to parse.
+     * @return The parsed tree as [AccessibilityNodeData].
+     */
+    fun parseTree(rootNode: AccessibilityNodeInfo): AccessibilityNodeData {
+        return parseNode(
+            node = rootNode,
+            depth = 0,
+            index = 0,
+            parentId = ROOT_PARENT_ID,
+            recycleNode = false,
+        )
+    }
+
+    /**
+     * Parses a single [AccessibilityNodeInfo] and recursively parses its children.
+     *
+     * @param node The node to parse.
+     * @param depth The depth of this node in the tree (root = 0).
+     * @param index The index of this node among its siblings.
+     * @param parentId The generated ID of the parent node.
+     * @param recycleNode Whether to recycle [node] after parsing. Child nodes are always recycled.
+     * @return The parsed node as [AccessibilityNodeData].
+     */
+    internal fun parseNode(
+        node: AccessibilityNodeInfo,
+        depth: Int,
+        index: Int,
+        parentId: String,
+        recycleNode: Boolean = true,
+    ): AccessibilityNodeData {
+        val rect = Rect()
+        node.getBoundsInScreen(rect)
+        val bounds = BoundsData(
+            left = rect.left,
+            top = rect.top,
+            right = rect.right,
+            bottom = rect.bottom,
+        )
+
+        val nodeId = generateNodeId(node, bounds, depth, index, parentId)
+
+        val className = node.className?.toString()
+        val text = node.text?.toString()
+        val contentDescription = node.contentDescription?.toString()
+        val resourceId = node.viewIdResourceName
+        val clickable = node.isClickable
+        val longClickable = node.isLongClickable
+        val focusable = node.isFocusable
+        val scrollable = node.isScrollable
+        val editable = node.isEditable
+        val enabled = node.isEnabled
+        val visible = isNodeVisible(node)
+
+        val children = mutableListOf<AccessibilityNodeData>()
+        val childCount = node.childCount
+        for (i in 0 until childCount) {
+            val childNode = node.getChild(i)
+            if (childNode != null) {
+                children.add(
+                    parseNode(
+                        node = childNode,
+                        depth = depth + 1,
+                        index = i,
+                        parentId = nodeId,
+                        recycleNode = true,
+                    ),
+                )
+            }
+        }
+
+        if (recycleNode) {
+            @Suppress("DEPRECATION")
+            node.recycle()
+        }
+
+        return AccessibilityNodeData(
+            id = nodeId,
+            className = className,
+            text = text,
+            contentDescription = contentDescription,
+            resourceId = resourceId,
+            bounds = bounds,
+            clickable = clickable,
+            longClickable = longClickable,
+            focusable = focusable,
+            scrollable = scrollable,
+            editable = editable,
+            enabled = enabled,
+            visible = visible,
+            children = children,
+        )
+    }
+
+    /**
+     * Checks whether [node] is visible to the user.
+     */
+    fun isNodeVisible(node: AccessibilityNodeInfo): Boolean {
+        return node.isVisibleToUser
+    }
+
+    /**
+     * Generates a stable, deterministic node ID based on the node's properties.
+     *
+     * The ID is stable across tree parses as long as the UI state has not changed.
+     * Uses resource ID, class name, bounds, depth, and sibling index for uniqueness.
+     */
+    internal fun generateNodeId(
+        node: AccessibilityNodeInfo,
+        bounds: BoundsData,
+        depth: Int,
+        index: Int,
+        parentId: String,
+    ): String {
+        val resourceId = node.viewIdResourceName ?: ""
+        val className = node.className?.toString() ?: ""
+        val hashInput = "$resourceId|$className|${bounds.left},${bounds.top}," +
+            "${bounds.right},${bounds.bottom}|$depth|$index|$parentId"
+        val hash = hashInput.hashCode().toUInt().toString(HASH_RADIX)
+        return "node_${hash}"
+    }
+
+    companion object {
+        private const val TAG = "MCP:TreeParser"
+        private const val ROOT_PARENT_ID = "root"
+        private const val HASH_RADIX = 16
+    }
+}
```

> **Implementation Notes**:
> - `@Suppress("DEPRECATION")` on `node.recycle()`: In API 34+ `recycle()` is deprecated because the framework handles it automatically. However, since `minSdk` is 26, we must still call it for older API levels. The suppression prevents the deprecation warning.
> - `generateNodeId` produces a hex hash string prefixed with `node_`. The hash is deterministic for the same UI state because it incorporates the resource ID, class name, screen bounds, tree depth, sibling index, and parent ID.
> - The root node is NOT recycled by the parser. This is important because the caller (typically `McpAccessibilityService.getRootNode()`) owns the root node and may reuse it.
> - Child nodes obtained via `getChild(i)` ARE recycled after extraction because the parser created those references.
> - The parser is stateless and thread-safe. It can be called from any thread, but the `AccessibilityNodeInfo` operations themselves must happen on the main thread (Android requirement). Callers are responsible for ensuring main-thread execution.

---

### Task 4.3: Implement ElementFinder

**Description**: Create the `ElementFinder` that searches through parsed `AccessibilityNodeData` trees to find elements matching specific criteria. This operates entirely on parsed data (not raw `AccessibilityNodeInfo`), making it fully testable without Android framework mocks.

**Acceptance Criteria**:
- [x] `FindBy` enum has `TEXT`, `CONTENT_DESC`, `RESOURCE_ID`, `CLASS_NAME` entries
- [x] `ElementInfo` data class holds key properties of found elements (id, text, contentDescription, resourceId, className, bounds, clickable, longClickable, scrollable, editable, enabled)
- [x] `findElements(tree, by, value, exactMatch)` recursively searches the tree and returns matching `ElementInfo` list
- [x] `findNodeById(tree, nodeId)` finds a specific `AccessibilityNodeData` by its ID
- [x] Exact match compares strings case-sensitively using equality
- [x] Contains match is case-insensitive
- [x] Returns empty list when no matches found (not an error)
- [x] File compiles without errors and passes lint

**Tests**: Unit tests in Task 4.6 (`ElementFinderTest.kt`).

#### Action 4.3.1: Create `ElementFinder.kt`

**What**: Create the element finder with multi-criteria search on parsed accessibility tree data.

**Context**: The `ElementFinder` is the implementation behind the `find_elements` MCP tool. It searches through the already-parsed `AccessibilityNodeData` tree, which means it does not touch `AccessibilityNodeInfo` at all. This separation makes it fully unit-testable without Android framework dependencies. The finder supports four search criteria (text, content description, resource ID, class name) with both exact and contains matching modes.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinder.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinder.kt
@@ -0,0 +1,146 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import kotlinx.serialization.Serializable
+import javax.inject.Inject
+
+/**
+ * Criteria for searching accessibility nodes.
+ */
+enum class FindBy {
+    /** Match by the node's text content. */
+    TEXT,
+
+    /** Match by the node's content description. */
+    CONTENT_DESC,
+
+    /** Match by the node's resource ID (e.g., "com.example:id/button1"). */
+    RESOURCE_ID,
+
+    /** Match by the node's class name (e.g., "android.widget.Button"). */
+    CLASS_NAME,
+}
+
+/**
+ * Represents a found UI element with key properties for MCP tool responses.
+ *
+ * @property id The node ID (matches [AccessibilityNodeData.id]).
+ * @property text The text content of the element.
+ * @property contentDescription The content description of the element.
+ * @property resourceId The view ID resource name.
+ * @property className The class name of the view.
+ * @property bounds The screen bounds of the element.
+ * @property clickable Whether the element is clickable.
+ * @property longClickable Whether the element is long-clickable.
+ * @property scrollable Whether the element is scrollable.
+ * @property editable Whether the element is an editable text field.
+ * @property enabled Whether the element is enabled.
+ */
+@Serializable
+data class ElementInfo(
+    val id: String,
+    val text: String? = null,
+    val contentDescription: String? = null,
+    val resourceId: String? = null,
+    val className: String? = null,
+    val bounds: BoundsData,
+    val clickable: Boolean = false,
+    val longClickable: Boolean = false,
+    val scrollable: Boolean = false,
+    val editable: Boolean = false,
+    val enabled: Boolean = false,
+)
+
+/**
+ * Finds elements in a parsed [AccessibilityNodeData] tree by various criteria.
+ *
+ * This class operates entirely on parsed tree data and does NOT access
+ * [android.view.accessibility.AccessibilityNodeInfo] directly, making it
+ * fully unit-testable without Android framework mocks.
+ *
+ * This class is Hilt-injectable and stateless.
+ */
+class ElementFinder @Inject constructor() {
+
+    /**
+     * Searches the [tree] recursively for elements matching the given [by] criteria and [value].
+     *
+     * @param tree The root of the parsed accessibility tree to search.
+     * @param by The criteria to search by (text, content description, resource ID, class name).
+     * @param value The value to search for.
+     * @param exactMatch If true, matches require exact (case-sensitive) equality.
+     *                   If false, matches use case-insensitive contains.
+     * @return A list of [ElementInfo] for all matching nodes. Empty list if no matches.
+     */
+    fun findElements(
+        tree: AccessibilityNodeData,
+        by: FindBy,
+        value: String,
+        exactMatch: Boolean = false,
+    ): List<ElementInfo> {
+        val results = mutableListOf<ElementInfo>()
+        searchRecursive(tree, by, value, exactMatch, results)
+        return results
+    }
+
+    /**
+     * Finds a specific [AccessibilityNodeData] by its node [nodeId].
+     *
+     * @param tree The root of the parsed accessibility tree to search.
+     * @param nodeId The node ID to find.
+     * @return The matching [AccessibilityNodeData], or null if not found.
+     */
+    fun findNodeById(
+        tree: AccessibilityNodeData,
+        nodeId: String,
+    ): AccessibilityNodeData? {
+        if (tree.id == nodeId) return tree
+        for (child in tree.children) {
+            val found = findNodeById(child, nodeId)
+            if (found != null) return found
+        }
+        return null
+    }
+
+    private fun searchRecursive(
+        node: AccessibilityNodeData,
+        by: FindBy,
+        value: String,
+        exactMatch: Boolean,
+        results: MutableList<ElementInfo>,
+    ) {
+        val nodeValue = when (by) {
+            FindBy.TEXT -> node.text
+            FindBy.CONTENT_DESC -> node.contentDescription
+            FindBy.RESOURCE_ID -> node.resourceId
+            FindBy.CLASS_NAME -> node.className
+        }
+
+        if (matchesValue(nodeValue, value, exactMatch)) {
+            results.add(toElementInfo(node))
+        }
+
+        for (child in node.children) {
+            searchRecursive(child, by, value, exactMatch, results)
+        }
+    }
+
+    /**
+     * Compares a node's property value against the search value.
+     *
+     * @param nodeValue The value from the node (may be null).
+     * @param searchValue The value to search for.
+     * @param exactMatch If true, uses case-sensitive equality. If false, uses case-insensitive contains.
+     * @return True if the values match according to the matching mode.
+     */
+    internal fun matchesValue(
+        nodeValue: String?,
+        searchValue: String,
+        exactMatch: Boolean,
+    ): Boolean {
+        if (nodeValue == null) return false
+        return if (exactMatch) {
+            nodeValue == searchValue
+        } else {
+            nodeValue.contains(searchValue, ignoreCase = true)
+        }
+    }
+
+    private fun toElementInfo(node: AccessibilityNodeData): ElementInfo {
+        return ElementInfo(
+            id = node.id,
+            text = node.text,
+            contentDescription = node.contentDescription,
+            resourceId = node.resourceId,
+            className = node.className,
+            bounds = node.bounds,
+            clickable = node.clickable,
+            longClickable = node.longClickable,
+            scrollable = node.scrollable,
+            editable = node.editable,
+            enabled = node.enabled,
+        )
+    }
+}
```

> **Implementation Notes**:
> - `ElementFinder` has zero Android framework dependencies in its logic. It only depends on `AccessibilityNodeData`, `BoundsData`, and `ElementInfo` -- all plain Kotlin data classes.
> - The `findElements` method returns an empty list (not null, not an error) when no matches are found, consistent with the MCP tools specification in PROJECT.md.
> - `matchesValue` is `internal` visibility so it can be tested directly in unit tests.
> - `ElementInfo` is `@Serializable` so it can be directly serialized to JSON in MCP tool responses.

---

### Task 4.4: Implement ActionExecutor

**Description**: Create the `ActionExecutor` that performs actions on accessibility nodes and the screen. This includes node-based actions (click, long click, set text, scroll), coordinate-based gestures (tap, long press, double tap, swipe), global actions (back, home, recents), and advanced gestures (pinch, custom multi-path).

**Acceptance Criteria**:
- [x] `GesturePoint` data class has `x: Float`, `y: Float`, `time: Long` fields
- [x] `ScrollDirection` enum has `UP`, `DOWN`, `LEFT`, `RIGHT` entries
- [x] `ScrollAmount` enum has `SMALL`, `MEDIUM`, `LARGE` entries mapping to 25%, 50%, 75% screen percentage
- [x] All node actions check service availability and return `Result.failure` if not available
- [x] All node actions find the real `AccessibilityNodeInfo` corresponding to a parsed node ID
- [x] `clickNode`, `longClickNode`, `setTextOnNode`, `scrollNode` use `performAction()` on the real node
- [x] `tap`, `longPress`, `doubleTap`, `swipe`, `scroll` use `dispatchGesture()` with `GestureDescription`
- [x] `pressBack`, `pressHome`, `pressRecents`, `openNotifications`, `openQuickSettings` use `performGlobalAction()`
- [x] `pinch` and `customGesture` use `dispatchGesture()` with multi-stroke `GestureDescription`
- [x] All gesture methods use `suspendCancellableCoroutine` to wrap `GestureResultCallback`
- [x] All coordinate-based methods validate coordinates are non-negative
- [x] `findAccessibilityNodeByNodeId` walks the real node tree matching the parsed tree structure
- [x] File compiles without errors and passes lint

**Tests**: Unit tests in Task 4.6 (`ActionExecutorTest.kt`).

#### Action 4.4.1: Create `ActionExecutor.kt`

**What**: Create the action executor for all gesture, click, and global action operations.

**Context**: The `ActionExecutor` is the implementation behind multiple MCP tools: `tap`, `long_press`, `swipe`, `click_element`, `press_back`, `pinch`, etc. It accesses `McpAccessibilityService.instance` directly (singleton pattern) because the accessibility service is system-managed and cannot be injected via Hilt.

The key challenge is mapping parsed `AccessibilityNodeData` node IDs back to real `AccessibilityNodeInfo` objects. The `findAccessibilityNodeByNodeId` method walks the real node tree in parallel with the parsed tree to find the actual `AccessibilityNodeInfo` for a given node ID. This is necessary because `performAction()` must be called on the real `AccessibilityNodeInfo`, not on our parsed data class.

All gesture methods (`tap`, `swipe`, `pinch`, etc.) use Android's `dispatchGesture()` API which takes a `GestureDescription` and calls back asynchronously via `GestureResultCallback`. We wrap this callback pattern using `suspendCancellableCoroutine` to integrate cleanly with Kotlin coroutines.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutor.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutor.kt
@@ -0,0 +1,505 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import android.accessibilityservice.GestureDescription
+import android.graphics.Path
+import android.graphics.Rect
+import android.os.Bundle
+import android.util.Log
+import android.view.accessibility.AccessibilityNodeInfo
+import kotlinx.coroutines.suspendCancellableCoroutine
+import javax.inject.Inject
+import kotlin.coroutines.resume
+
+/**
+ * Represents a single point in a gesture path.
+ *
+ * @property x The X coordinate on screen.
+ * @property y The Y coordinate on screen.
+ * @property time The time offset in milliseconds from the start of the gesture.
+ */
+data class GesturePoint(
+    val x: Float,
+    val y: Float,
+    val time: Long,
+)
+
+/**
+ * Direction for scroll gestures.
+ */
+enum class ScrollDirection {
+    UP,
+    DOWN,
+    LEFT,
+    RIGHT,
+}
+
+/**
+ * Amount for scroll gestures, mapping to screen percentages.
+ */
+enum class ScrollAmount(val screenPercentage: Float) {
+    /** 25% of screen dimension. */
+    SMALL(0.25f),
+
+    /** 50% of screen dimension. */
+    MEDIUM(0.50f),
+
+    /** 75% of screen dimension. */
+    LARGE(0.75f),
+}
+
+/**
+ * Executes accessibility actions on nodes and the screen.
+ *
+ * Provides three categories of actions:
+ * 1. **Node actions**: Click, long-click, set text, scroll on specific accessibility nodes.
+ * 2. **Coordinate-based actions**: Tap, long press, double tap, swipe, scroll at screen coordinates.
+ * 3. **Global actions**: Back, home, recents, notifications, quick settings.
+ * 4. **Advanced gestures**: Pinch, custom multi-path gestures.
+ *
+ * Accesses [McpAccessibilityService.instance] directly (singleton pattern) because the
+ * accessibility service is system-managed and cannot be injected via Hilt.
+ *
+ * This class is Hilt-injectable and stateless.
+ */
+class ActionExecutor @Inject constructor() {
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Node Actions
+    // ─────────────────────────────────────────────────────────────────────────
+
+    /**
+     * Clicks the node identified by [nodeId] in the parsed [tree].
+     *
+     * Finds the corresponding real [AccessibilityNodeInfo] and performs ACTION_CLICK.
+     *
+     * @return [Result.success] if the action was performed, [Result.failure] otherwise.
+     */
+    suspend fun clickNode(
+        nodeId: String,
+        tree: AccessibilityNodeData,
+    ): Result<Unit> {
+        return performNodeAction(nodeId, tree, "click") { realNode ->
+            if (!realNode.isClickable) {
+                return@performNodeAction Result.failure(
+                    IllegalStateException("Node '$nodeId' is not clickable"),
+                )
+            }
+            val success = realNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
+            if (success) Result.success(Unit) else Result.failure(
+                RuntimeException("ACTION_CLICK failed on node '$nodeId'"),
+            )
+        }
+    }
+
+    /**
+     * Long-clicks the node identified by [nodeId] in the parsed [tree].
+     */
+    suspend fun longClickNode(
+        nodeId: String,
+        tree: AccessibilityNodeData,
+    ): Result<Unit> {
+        return performNodeAction(nodeId, tree, "long click") { realNode ->
+            if (!realNode.isLongClickable) {
+                return@performNodeAction Result.failure(
+                    IllegalStateException("Node '$nodeId' is not long-clickable"),
+                )
+            }
+            val success = realNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
+            if (success) Result.success(Unit) else Result.failure(
+                RuntimeException("ACTION_LONG_CLICK failed on node '$nodeId'"),
+            )
+        }
+    }
+
+    /**
+     * Sets [text] on the editable node identified by [nodeId] in the parsed [tree].
+     */
+    suspend fun setTextOnNode(
+        nodeId: String,
+        text: String,
+        tree: AccessibilityNodeData,
+    ): Result<Unit> {
+        return performNodeAction(nodeId, tree, "set text") { realNode ->
+            if (!realNode.isEditable) {
+                return@performNodeAction Result.failure(
+                    IllegalStateException("Node '$nodeId' is not editable"),
+                )
+            }
+            val arguments = Bundle().apply {
+                putCharSequence(
+                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
+                    text,
+                )
+            }
+            val success = realNode.performAction(
+                AccessibilityNodeInfo.ACTION_SET_TEXT,
+                arguments,
+            )
+            if (success) Result.success(Unit) else Result.failure(
+                RuntimeException("ACTION_SET_TEXT failed on node '$nodeId'"),
+            )
+        }
+    }
+
+    /**
+     * Scrolls the node identified by [nodeId] in the given [direction].
+     */
+    suspend fun scrollNode(
+        nodeId: String,
+        direction: ScrollDirection,
+        tree: AccessibilityNodeData,
+    ): Result<Unit> {
+        return performNodeAction(nodeId, tree, "scroll") { realNode ->
+            if (!realNode.isScrollable) {
+                return@performNodeAction Result.failure(
+                    IllegalStateException("Node '$nodeId' is not scrollable"),
+                )
+            }
+            val action = when (direction) {
+                ScrollDirection.UP, ScrollDirection.LEFT ->
+                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
+                ScrollDirection.DOWN, ScrollDirection.RIGHT ->
+                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
+            }
+            val success = realNode.performAction(action)
+            if (success) Result.success(Unit) else Result.failure(
+                RuntimeException("Scroll ${direction.name} failed on node '$nodeId'"),
+            )
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Coordinate-Based Actions
+    // ─────────────────────────────────────────────────────────────────────────
+
+    /**
+     * Performs a single tap at the specified coordinates.
+     */
+    suspend fun tap(x: Float, y: Float): Result<Unit> {
+        validateCoordinates(x, y)?.let { return it }
+        val path = Path().apply { moveTo(x, y) }
+        val stroke = GestureDescription.StrokeDescription(
+            path,
+            0L,
+            TAP_DURATION_MS,
+        )
+        return dispatchSingleStrokeGesture(stroke, "tap($x, $y)")
+    }
+
+    /**
+     * Performs a long press at the specified coordinates.
+     *
+     * @param duration Press duration in milliseconds. Defaults to [DEFAULT_LONG_PRESS_DURATION_MS].
+     */
+    suspend fun longPress(
+        x: Float,
+        y: Float,
+        duration: Long = DEFAULT_LONG_PRESS_DURATION_MS,
+    ): Result<Unit> {
+        validateCoordinates(x, y)?.let { return it }
+        val path = Path().apply { moveTo(x, y) }
+        val stroke = GestureDescription.StrokeDescription(
+            path,
+            0L,
+            duration,
+        )
+        return dispatchSingleStrokeGesture(stroke, "longPress($x, $y, ${duration}ms)")
+    }
+
+    /**
+     * Performs a double tap at the specified coordinates.
+     *
+     * Uses a [GestureDescription] with two sequential tap strokes at the same
+     * coordinates, separated by [DOUBLE_TAP_GAP_MS]. This is more reliable than
+     * dispatching two separate gestures because Android recognizes the two strokes
+     * within a single gesture dispatch as a double-tap pattern.
+     */
+    suspend fun doubleTap(x: Float, y: Float): Result<Unit> {
+        validateCoordinates(x, y)?.let { return it }
+
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val firstTapPath = Path().apply { moveTo(x, y) }
+        val secondTapPath = Path().apply { moveTo(x, y) }
+
+        val firstTapStroke = GestureDescription.StrokeDescription(
+            firstTapPath,
+            0L,
+            TAP_DURATION_MS,
+        )
+        val secondTapStartTime = TAP_DURATION_MS + DOUBLE_TAP_GAP_MS
+        val secondTapStroke = GestureDescription.StrokeDescription(
+            secondTapPath,
+            secondTapStartTime,
+            TAP_DURATION_MS,
+        )
+
+        val gesture = GestureDescription.Builder()
+            .addStroke(firstTapStroke)
+            .addStroke(secondTapStroke)
+            .build()
+
+        return dispatchGesture(service, gesture, "doubleTap($x, $y)")
+    }
+
+    /**
+     * Performs a swipe gesture from (x1, y1) to (x2, y2).
+     *
+     * @param duration Swipe duration in milliseconds. Defaults to [DEFAULT_SWIPE_DURATION_MS].
+     */
+    suspend fun swipe(
+        x1: Float,
+        y1: Float,
+        x2: Float,
+        y2: Float,
+        duration: Long = DEFAULT_SWIPE_DURATION_MS,
+    ): Result<Unit> {
+        validateCoordinates(x1, y1)?.let { return it }
+        validateCoordinates(x2, y2)?.let { return it }
+        val path = Path().apply {
+            moveTo(x1, y1)
+            lineTo(x2, y2)
+        }
+        val stroke = GestureDescription.StrokeDescription(
+            path,
+            0L,
+            duration,
+        )
+        return dispatchSingleStrokeGesture(
+            stroke,
+            "swipe($x1,$y1 -> $x2,$y2, ${duration}ms)",
+        )
+    }
+
+    /**
+     * Scrolls the screen in the given [direction] by the given [amount].
+     *
+     * Calculates start and end coordinates as percentages of the screen size
+     * and dispatches a swipe gesture.
+     */
+    suspend fun scroll(
+        direction: ScrollDirection,
+        amount: ScrollAmount = ScrollAmount.MEDIUM,
+    ): Result<Unit> {
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val rootNode = service.getRootNode()
+            ?: return Result.failure(
+                IllegalStateException("No root node available for screen dimensions"),
+            )
+
+        val rect = Rect()
+        rootNode.getBoundsInScreen(rect)
+        @Suppress("DEPRECATION")
+        rootNode.recycle()
+
+        val screenWidth = rect.right.toFloat()
+        val screenHeight = rect.bottom.toFloat()
+        val centerX = screenWidth / 2f
+        val centerY = screenHeight / 2f
+        val scrollDistance = when (direction) {
+            ScrollDirection.UP, ScrollDirection.DOWN ->
+                screenHeight * amount.screenPercentage
+            ScrollDirection.LEFT, ScrollDirection.RIGHT ->
+                screenWidth * amount.screenPercentage
+        }
+        val halfDistance = scrollDistance / 2f
+
+        return when (direction) {
+            ScrollDirection.UP -> swipe(
+                centerX, centerY + halfDistance,
+                centerX, centerY - halfDistance,
+            )
+            ScrollDirection.DOWN -> swipe(
+                centerX, centerY - halfDistance,
+                centerX, centerY + halfDistance,
+            )
+            ScrollDirection.LEFT -> swipe(
+                centerX + halfDistance, centerY,
+                centerX - halfDistance, centerY,
+            )
+            ScrollDirection.RIGHT -> swipe(
+                centerX - halfDistance, centerY,
+                centerX + halfDistance, centerY,
+            )
+        }
+    }
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Global Actions
+    // ─────────────────────────────────────────────────────────────────────────
+
+    /**
+     * Presses the Back button.
+     */
+    suspend fun pressBack(): Result<Unit> {
+        return performGlobalAction(
+            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
+            "pressBack",
+        )
+    }
+
+    /**
+     * Presses the Home button.
+     */
+    suspend fun pressHome(): Result<Unit> {
+        return performGlobalAction(
+            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
+            "pressHome",
+        )
+    }
+
+    /**
+     * Opens the Recents screen.
+     */
+    suspend fun pressRecents(): Result<Unit> {
+        return performGlobalAction(
+            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
+            "pressRecents",
+        )
+    }
+
+    /**
+     * Opens the notification shade.
+     */
+    suspend fun openNotifications(): Result<Unit> {
+        return performGlobalAction(
+            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
+            "openNotifications",
+        )
+    }
+
+    /**
+     * Opens the quick settings panel.
+     */
+    suspend fun openQuickSettings(): Result<Unit> {
+        return performGlobalAction(
+            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
+            "openQuickSettings",
+        )
+    }
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Advanced Gestures
+    // ─────────────────────────────────────────────────────────────────────────
+
+    /**
+     * Performs a pinch gesture centered at ([centerX], [centerY]).
+     *
+     * @param scale Scale factor. > 1 = zoom in (fingers move apart), < 1 = zoom out (fingers come together).
+     * @param duration Gesture duration in milliseconds.
+     */
+    suspend fun pinch(
+        centerX: Float,
+        centerY: Float,
+        scale: Float,
+        duration: Long = DEFAULT_GESTURE_DURATION_MS,
+    ): Result<Unit> {
+        validateCoordinates(centerX, centerY)?.let { return it }
+
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val pinchDistance = PINCH_BASE_DISTANCE
+        val isZoomIn = scale > 1.0f
+
+        val startDistance = if (isZoomIn) pinchDistance else pinchDistance * scale
+        val endDistance = if (isZoomIn) pinchDistance * scale else pinchDistance
+
+> **CRITICAL — Pinch zoom-out direction inverted**: For `scale < 1` (zoom-out), `startDistance = pinchDistance * scale` (small) and `endDistance = pinchDistance` (large) means fingers START close and MOVE APART — that's zoom-IN, not zoom-OUT. At implementation time, swap the zoom-out logic: `startDistance = pinchDistance` (fingers start far apart) and `endDistance = pinchDistance * scale` (fingers end close together). The corrected code should be: `val startDistance = if (isZoomIn) pinchDistance else pinchDistance` and `val endDistance = if (isZoomIn) pinchDistance * scale else pinchDistance * scale`, which simplifies to `val startDistance = pinchDistance` and `val endDistance = pinchDistance * scale` for both cases.
+
+        val finger1Path = Path().apply {
+            moveTo(centerX - startDistance, centerY)
+            lineTo(centerX - endDistance, centerY)
+        }
+        val finger2Path = Path().apply {
+            moveTo(centerX + startDistance, centerY)
+            lineTo(centerX + endDistance, centerY)
+        }
+
+        val stroke1 = GestureDescription.StrokeDescription(finger1Path, 0L, duration)
+        val stroke2 = GestureDescription.StrokeDescription(finger2Path, 0L, duration)
+
+        val gesture = GestureDescription.Builder()
+            .addStroke(stroke1)
+            .addStroke(stroke2)
+            .build()
+
+        return dispatchGesture(
+            service,
+            gesture,
+            "pinch($centerX, $centerY, scale=$scale, ${duration}ms)",
+        )
+    }
+
+    /**
+     * Executes a custom multi-path gesture.
+     *
+     * @param paths A list of paths, where each path is a list of [GesturePoint]s.
+     *              Each path represents one finger's movement.
+     */
+    suspend fun customGesture(
+        paths: List<List<GesturePoint>>,
+    ): Result<Unit> {
+        if (paths.isEmpty()) {
+            return Result.failure(IllegalArgumentException("Gesture paths must not be empty"))
+        }
+
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val builder = GestureDescription.Builder()
+
+        for ((pathIndex, points) in paths.withIndex()) {
+            if (points.size < 2) {
+                return Result.failure(
+                    IllegalArgumentException(
+                        "Path $pathIndex must have at least 2 points, has ${points.size}",
+                    ),
+                )
+            }
+
+            for (point in points) {
+                validateCoordinates(point.x, point.y)?.let { return it }
+            }
+
+            val path = Path().apply {
+                moveTo(points[0].x, points[0].y)
+                for (i in 1 until points.size) {
+                    lineTo(points[i].x, points[i].y)
+                }
+            }
+
+            val startTime = points[0].time
+            val endTime = points.last().time
+            val duration = (endTime - startTime).coerceAtLeast(1L)
+
+            val stroke = GestureDescription.StrokeDescription(path, startTime, duration)
+            builder.addStroke(stroke)
+        }
+
+        return dispatchGesture(
+            service,
+            builder.build(),
+            "customGesture(${paths.size} paths)",
+        )
+    }
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Node Resolution (Parsed ID -> Real AccessibilityNodeInfo)
+    // ─────────────────────────────────────────────────────────────────────────
+
+    /**
+     * Walks the real [AccessibilityNodeInfo] tree in parallel with the parsed
+     * [AccessibilityNodeData] tree to find the real node matching [nodeId].
+     *
+     * This is necessary because [AccessibilityNodeInfo.performAction] must be
+     * called on the actual framework node, not on our parsed data class.
+     *
+     * @param rootNode The real root [AccessibilityNodeInfo].
+     * @param nodeId The node ID to find.
+     * @param tree The parsed tree (used to regenerate IDs for comparison).
+     * @return The matching real [AccessibilityNodeInfo], or null if not found.
+     *         The caller is responsible for recycling the returned node.
+     */
+    fun findAccessibilityNodeByNodeId(
+        rootNode: AccessibilityNodeInfo,
+        nodeId: String,
+        tree: AccessibilityNodeData,
+    ): AccessibilityNodeInfo? {
+        return walkAndMatch(rootNode, tree, nodeId, recycleOnMismatch = false)
+    }
+
+    // ─────────────────────────────────────────────────────────────────────────
+    // Internal Helpers
+    // ─────────────────────────────────────────────────────────────────────────
+
+    private suspend fun performNodeAction(
+        nodeId: String,
+        tree: AccessibilityNodeData,
+        actionName: String,
+        action: (AccessibilityNodeInfo) -> Result<Unit>,
+    ): Result<Unit> {
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val rootNode = service.getRootNode()
+            ?: return Result.failure(
+                IllegalStateException("No root node available"),
+            )
+
+        val realNode = findAccessibilityNodeByNodeId(rootNode, nodeId, tree)
+        if (realNode == null) {
+            @Suppress("DEPRECATION")
+            rootNode.recycle()
+            return Result.failure(
+                NoSuchElementException("Node '$nodeId' not found in accessibility tree"),
+            )
+        }
+
+        return try {
+            val result = action(realNode)
+            if (result.isSuccess) {
+                Log.d(TAG, "Node action '$actionName' succeeded on node '$nodeId'")
+            } else {
+                Log.w(
+                    TAG,
+                    "Node action '$actionName' failed on node '$nodeId': " +
+                        "${result.exceptionOrNull()?.message}",
+                )
+            }
+            result
+        } finally {
+            @Suppress("DEPRECATION")
+            realNode.recycle()
+            @Suppress("DEPRECATION")
+            rootNode.recycle()
+        }
+    }
+
+    private suspend fun performGlobalAction(
+        action: Int,
+        actionName: String,
+    ): Result<Unit> {
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val success = service.performGlobalAction(action)
+        return if (success) {
+            Log.d(TAG, "Global action '$actionName' succeeded")
+            Result.success(Unit)
+        } else {
+            Log.w(TAG, "Global action '$actionName' failed")
+            Result.failure(RuntimeException("Global action '$actionName' failed"))
+        }
+    }
+
+    private suspend fun dispatchSingleStrokeGesture(
+        stroke: GestureDescription.StrokeDescription,
+        description: String,
+    ): Result<Unit> {
+        val service = McpAccessibilityService.instance
+            ?: return Result.failure(
+                IllegalStateException("Accessibility service is not available"),
+            )
+
+        val gesture = GestureDescription.Builder()
+            .addStroke(stroke)
+            .build()
+
+        return dispatchGesture(service, gesture, description)
+    }
+
+    private suspend fun dispatchGesture(
+        service: McpAccessibilityService,
+        gesture: GestureDescription,
+        description: String,
+    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
+        val callback = object :
+            android.accessibilityservice.AccessibilityService.GestureResultCallback() {
+            override fun onCompleted(gestureDescription: GestureDescription?) {
+                Log.d(TAG, "Gesture completed: $description")
+                if (continuation.isActive) {
+                    continuation.resume(Result.success(Unit))
+                }
+            }
+
+            override fun onCancelled(gestureDescription: GestureDescription?) {
+                Log.w(TAG, "Gesture cancelled: $description")
+                if (continuation.isActive) {
+                    continuation.resume(
+                        Result.failure(
+                            RuntimeException("Gesture cancelled: $description"),
+                        ),
+                    )
+                }
+            }
+        }
+
+        val dispatched = service.dispatchGesture(gesture, callback, null)
+        if (!dispatched) {
+            Log.e(TAG, "Failed to dispatch gesture: $description")
+            if (continuation.isActive) {
+                continuation.resume(
+                    Result.failure(
+                        RuntimeException("Failed to dispatch gesture: $description"),
+                    ),
+                )
+            }
+        }
+    }
+
+    /**
+     * Recursively walks the real node tree and the parsed tree in parallel.
+     * When a parsed node's ID matches [targetNodeId], returns the corresponding real node.
+     */
+    private fun walkAndMatch(
+        realNode: AccessibilityNodeInfo,
+        parsedNode: AccessibilityNodeData,
+        targetNodeId: String,
+        recycleOnMismatch: Boolean,
+    ): AccessibilityNodeInfo? {
+        if (parsedNode.id == targetNodeId) {
+            return realNode
+        }
+
+        val childCount = realNode.childCount
+        val parsedChildCount = parsedNode.children.size
+        val minCount = minOf(childCount, parsedChildCount)
+
+        for (i in 0 until minCount) {
+            val realChild = realNode.getChild(i) ?: continue
+            val parsedChild = parsedNode.children[i]
+
+            val found = walkAndMatch(realChild, parsedChild, targetNodeId, recycleOnMismatch = true)
+            if (found != null) {
+                return found
+            }
+        }
+
+        if (recycleOnMismatch) {
+            @Suppress("DEPRECATION")
+            realNode.recycle()
+        }
+
+        return null
+    }
+
+    private fun validateCoordinates(x: Float, y: Float): Result<Unit>? {
+        if (x < 0f || y < 0f) {
+            return Result.failure(
+                IllegalArgumentException(
+                    "Coordinates must be non-negative: ($x, $y)",
+                ),
+            )
+        }
+        return null
+    }
+
+    companion object {
+        private const val TAG = "MCP:ActionExecutor"
+        private const val TAP_DURATION_MS = 50L
+        private const val DEFAULT_LONG_PRESS_DURATION_MS = 1000L
+        private const val DEFAULT_SWIPE_DURATION_MS = 300L
+        private const val DEFAULT_GESTURE_DURATION_MS = 300L
+        private const val DOUBLE_TAP_GAP_MS = 100L
+        private const val PINCH_BASE_DISTANCE = 100f
+    }
+}
```

> **Implementation Notes**:
> - `dispatchGesture` returns a `Boolean` indicating whether the gesture was dispatched. If `false`, we immediately resume the continuation with a failure. The `GestureResultCallback` is only called if dispatch succeeds.
> - `walkAndMatch` recycles non-matching real nodes to prevent memory leaks. When a match is found, the matching real node is NOT recycled -- the caller takes ownership.
> - `validateCoordinates` returns `null` if coordinates are valid (allowing the caller to proceed) or a `Result.failure` if invalid (allowing the caller to return early). This avoids nested if/else.
> - `doubleTap` uses a single `GestureDescription` with two `StrokeDescription` entries (one per tap) separated by `DOUBLE_TAP_GAP_MS`. This is more reliable than dispatching two separate gestures sequentially because Android's gesture system recognizes the two strokes within a single dispatch as a proper double-tap pattern. The second tap starts at `TAP_DURATION_MS + DOUBLE_TAP_GAP_MS` to ensure proper timing.
> - The `scroll` method obtains the root node to determine screen dimensions, then delegates to `swipe`. The root node is recycled after extracting bounds.
> - `pinch` creates two stroke descriptions (one per "finger") moving in opposite directions. For zoom-in, fingers start close and move apart. For zoom-out, the reverse.
> - `customGesture` validates that each path has at least 2 points and that all coordinates are non-negative.

---

### Task 4.5: Unit Tests for AccessibilityTreeParser

**Description**: Create comprehensive unit tests for the `AccessibilityTreeParser` using JUnit 5 and MockK to mock `AccessibilityNodeInfo`.

**Acceptance Criteria**:
- [x] Tests mock `AccessibilityNodeInfo` using MockK
- [x] Test single node parsing extracts all properties correctly
- [x] Test nested tree parsing (parent with children) produces correct hierarchy
- [x] Test node visibility detection
- [x] Test node ID generation stability (same input produces same ID)
- [x] Test bounds extraction via `getBoundsInScreen`
- [x] Test null/empty text handling (text is null, contentDescription is null)
- [x] Test deep tree (3+ levels)
- [x] Test node with no children
- [x] All tests pass via `./gradlew test --tests '*AccessibilityTreeParserTest*'`

**Tests**: This IS the test task.

#### Action 4.5.1: Create `AccessibilityTreeParserTest.kt`

**What**: Create unit tests for the accessibility tree parser.

**Context**: `AccessibilityNodeInfo` is an Android framework class that requires mocking. MockK is used to create mock nodes with configurable properties. The `getBoundsInScreen(Rect)` method is a void method that populates a `Rect` output parameter -- MockK's `answers` block is used to handle this. Each test follows the Arrange-Act-Assert pattern per CLAUDE.md testing rules.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParserTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParserTest.kt
@@ -0,0 +1,282 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import android.graphics.Rect
+import android.view.accessibility.AccessibilityNodeInfo
+import io.mockk.every
+import io.mockk.just
+import io.mockk.mockk
+import io.mockk.runs
+import io.mockk.slot
+import io.mockk.verify
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("AccessibilityTreeParser")
+class AccessibilityTreeParserTest {
+
+    private lateinit var parser: AccessibilityTreeParser
+
+    @BeforeEach
+    fun setUp() {
+        parser = AccessibilityTreeParser()
+    }
+
+    private fun createMockNode(
+        className: String? = "android.widget.TextView",
+        text: CharSequence? = null,
+        contentDescription: CharSequence? = null,
+        resourceId: String? = null,
+        boundsLeft: Int = 0,
+        boundsTop: Int = 0,
+        boundsRight: Int = 100,
+        boundsBottom: Int = 50,
+        clickable: Boolean = false,
+        longClickable: Boolean = false,
+        focusable: Boolean = false,
+        scrollable: Boolean = false,
+        editable: Boolean = false,
+        enabled: Boolean = true,
+        visibleToUser: Boolean = true,
+        childCount: Int = 0,
+        children: List<AccessibilityNodeInfo> = emptyList(),
+    ): AccessibilityNodeInfo {
+        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
+        every { node.className } returns className
+        every { node.text } returns text
+        every { node.contentDescription } returns contentDescription
+        every { node.viewIdResourceName } returns resourceId
+        every { node.isClickable } returns clickable
+        every { node.isLongClickable } returns longClickable
+        every { node.isFocusable } returns focusable
+        every { node.isScrollable } returns scrollable
+        every { node.isEditable } returns editable
+        every { node.isEnabled } returns enabled
+        every { node.isVisibleToUser } returns visibleToUser
+        every { node.childCount } returns childCount
+
+        val rectSlot = slot<Rect>()
+        every { node.getBoundsInScreen(capture(rectSlot)) } answers {
+            rectSlot.captured.set(boundsLeft, boundsTop, boundsRight, boundsBottom)
+        }
+
+        for (i in children.indices) {
+            every { node.getChild(i) } returns children[i]
+        }
+
+        every { node.recycle() } just runs
+
+        return node
+    }
+
+    @Nested
+    @DisplayName("parseTree")
+    inner class ParseTree {
+
+        @Test
+        @DisplayName("parses single node with all properties")
+        fun parsesSingleNodeWithAllProperties() {
+            // Arrange
+            val node = createMockNode(
+                className = "android.widget.Button",
+                text = "Click me",
+                contentDescription = "Action button",
+                resourceId = "com.example:id/btn_action",
+                boundsLeft = 10,
+                boundsTop = 20,
+                boundsRight = 200,
+                boundsBottom = 80,
+                clickable = true,
+                longClickable = true,
+                focusable = true,
+                scrollable = false,
+                editable = false,
+                enabled = true,
+                visibleToUser = true,
+            )
+
+            // Act
+            val result = parser.parseTree(node)
+
+            // Assert
+            assertNotNull(result.id)
+            assertTrue(result.id.startsWith("node_"))
+            assertEquals("android.widget.Button", result.className)
+            assertEquals("Click me", result.text)
+            assertEquals("Action button", result.contentDescription)
+            assertEquals("com.example:id/btn_action", result.resourceId)
+            assertEquals(BoundsData(10, 20, 200, 80), result.bounds)
+            assertTrue(result.clickable)
+            assertTrue(result.longClickable)
+            assertTrue(result.focusable)
+            assertFalse(result.scrollable)
+            assertFalse(result.editable)
+            assertTrue(result.enabled)
+            assertTrue(result.visible)
+            assertTrue(result.children.isEmpty())
+        }
+
+        @Test
+        @DisplayName("parses node with null text and contentDescription")
+        fun parsesNodeWithNullTextAndContentDescription() {
+            // Arrange
+            val node = createMockNode(
+                text = null,
+                contentDescription = null,
+                resourceId = null,
+            )
+
+            // Act
+            val result = parser.parseTree(node)
+
+            // Assert
+            assertNull(result.text)
+            assertNull(result.contentDescription)
+            assertNull(result.resourceId)
+        }
+
+        @Test
+        @DisplayName("parses nested tree with parent and children")
+        fun parsesNestedTreeWithParentAndChildren() {
+            // Arrange
+            val child1 = createMockNode(
+                className = "android.widget.Button",
+                text = "Button 1",
+                boundsLeft = 0,
+                boundsTop = 0,
+                boundsRight = 100,
+                boundsBottom = 50,
+                clickable = true,
+            )
+            val child2 = createMockNode(
+                className = "android.widget.Button",
+                text = "Button 2",
+                boundsLeft = 100,
+                boundsTop = 0,
+                boundsRight = 200,
+                boundsBottom = 50,
+                clickable = true,
+            )
+            val parent = createMockNode(
+                className = "android.widget.LinearLayout",
+                boundsLeft = 0,
+                boundsTop = 0,
+                boundsRight = 200,
+                boundsBottom = 50,
+                childCount = 2,
+                children = listOf(child1, child2),
+            )
+
+            // Act
+            val result = parser.parseTree(parent)
+
+            // Assert
+            assertEquals("android.widget.LinearLayout", result.className)
+            assertEquals(2, result.children.size)
+            assertEquals("Button 1", result.children[0].text)
+            assertEquals("Button 2", result.children[1].text)
+            assertTrue(result.children[0].clickable)
+            assertTrue(result.children[1].clickable)
+        }
+
+        @Test
+        @DisplayName("parses deep tree with 3 levels")
+        fun parsesDeepTreeWith3Levels() {
+            // Arrange
+            val grandchild = createMockNode(
+                className = "android.widget.TextView",
+                text = "Deep text",
+            )
+            val child = createMockNode(
+                className = "android.widget.FrameLayout",
+                childCount = 1,
+                children = listOf(grandchild),
+            )
+            val root = createMockNode(
+                className = "android.widget.LinearLayout",
+                childCount = 1,
+                children = listOf(child),
+            )
+
+            // Act
+            val result = parser.parseTree(root)
+
+            // Assert
+            assertEquals(1, result.children.size)
+            assertEquals(1, result.children[0].children.size)
+            assertEquals("Deep text", result.children[0].children[0].text)
+        }
+
+        @Test
+        @DisplayName("does not recycle root node")
+        fun doesNotRecycleRootNode() {
+            // Arrange
+            val node = createMockNode()
+
+            // Act
+            parser.parseTree(node)
+
+            // Assert
+            verify(exactly = 0) { node.recycle() }
+        }
+
+        @Test
+        @DisplayName("recycles child nodes after parsing")
+        fun recyclesChildNodesAfterParsing() {
+            // Arrange
+            val child = createMockNode(text = "Child")
+            val parent = createMockNode(
+                childCount = 1,
+                children = listOf(child),
+            )
+
+            // Act
+            parser.parseTree(parent)
+
+            // Assert
+            verify(exactly = 1) { child.recycle() }
+        }
+    }
+
+    @Nested
+    @DisplayName("isNodeVisible")
+    inner class IsNodeVisible {
+
+        @Test
+        @DisplayName("returns true for visible node")
+        fun returnsTrueForVisibleNode() {
+            // Arrange
+            val node = createMockNode(visibleToUser = true)
+
+            // Act & Assert
+            assertTrue(parser.isNodeVisible(node))
+        }
+
+        @Test
+        @DisplayName("returns false for invisible node")
+        fun returnsFalseForInvisibleNode() {
+            // Arrange
+            val node = createMockNode(visibleToUser = false)
+
+            // Act & Assert
+            assertFalse(parser.isNodeVisible(node))
+        }
+    }
+
+    @Nested
+    @DisplayName("generateNodeId")
+    inner class GenerateNodeId {
+
+        @Test
+        @DisplayName("generates stable IDs for same input")
+        fun generatesStableIdsForSameInput() {
+            // Arrange
+            val node = createMockNode(
+                className = "android.widget.Button",
+                resourceId = "com.example:id/button",
+            )
+            val bounds = BoundsData(10, 20, 100, 80)
+
+            // Act
+            val id1 = parser.generateNodeId(node, bounds, 0, 0, "root")
+            val id2 = parser.generateNodeId(node, bounds, 0, 0, "root")
+
+            // Assert
+            assertEquals(id1, id2)
+        }
+
+        @Test
+        @DisplayName("generates different IDs for different positions")
+        fun generatesDifferentIdsForDifferentPositions() {
+            // Arrange
+            val node = createMockNode(
+                className = "android.widget.Button",
+                resourceId = "com.example:id/button",
+            )
+            val bounds = BoundsData(10, 20, 100, 80)
+
+            // Act
+            val id1 = parser.generateNodeId(node, bounds, 0, 0, "root")
+            val id2 = parser.generateNodeId(node, bounds, 0, 1, "root")
+
+            // Assert
+            assertTrue(id1 != id2)
+        }
+
+        @Test
+        @DisplayName("generates IDs with node_ prefix")
+        fun generatesIdsWithNodePrefix() {
+            // Arrange
+            val node = createMockNode()
+            val bounds = BoundsData(0, 0, 100, 50)
+
+            // Act
+            val id = parser.generateNodeId(node, bounds, 0, 0, "root")
+
+            // Assert
+            assertTrue(id.startsWith("node_"))
+        }
+    }
+}
```

> **Implementation Notes**:
> - `createMockNode` is a helper that creates a fully configured mock `AccessibilityNodeInfo`. It uses `relaxed = true` as the base to avoid needing to stub every possible method call, then overrides specific properties.
> - `getBoundsInScreen(Rect)` is a void method with an output parameter. MockK's `slot<Rect>()` and `capture()` pattern is used to intercept the `Rect` parameter and populate it with the test data.
> - The `recycle()` verification tests ensure the parser correctly manages node lifecycle: root is not recycled (caller owns it), children are recycled after parsing.
> - Node ID stability test verifies determinism by calling `generateNodeId` twice with identical inputs.

---

### Task 4.6: Unit Tests for ElementFinder and ActionExecutor

**Description**: Create comprehensive unit tests for `ElementFinder` (which operates on plain data classes) and `ActionExecutor` (which requires mocking the accessibility service).

**Acceptance Criteria**:
- [x] `ElementFinderTest` tests all four `FindBy` criteria with exact and contains matching
- [x] `ElementFinderTest` tests `findNodeById` (found and not found)
- [x] `ElementFinderTest` tests empty result for no matches
- [x] `ElementFinderTest` tests multiple matches
- [x] `ActionExecutorTest` mocks `McpAccessibilityService` singleton
- [x] `ActionExecutorTest` tests gesture description creation (tap, long press, swipe)
- [x] `ActionExecutorTest` tests global action delegation
- [x] `ActionExecutorTest` tests service not available returns failure
- [x] `ActionExecutorTest` tests node not found returns failure
- [x] `ActionExecutorTest` tests coordinate validation (negative values rejected)
- [x] All tests pass via `./gradlew test --tests '*ElementFinderTest*'` and `./gradlew test --tests '*ActionExecutorTest*'`

**Tests**: This IS the test task.

#### Action 4.6.1: Create `ElementFinderTest.kt`

**What**: Create unit tests for the element finder.

**Context**: `ElementFinder` operates entirely on `AccessibilityNodeData` which is a plain Kotlin data class. No Android framework mocking is needed -- we simply construct test trees manually. This makes these tests fast, pure, and reliable.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinderTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinderTest.kt
@@ -0,0 +1,288 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+@DisplayName("ElementFinder")
+class ElementFinderTest {
+
+    private lateinit var finder: ElementFinder
+
+    private val defaultBounds = BoundsData(0, 0, 100, 50)
+
+    @BeforeEach
+    fun setUp() {
+        finder = ElementFinder()
+    }
+
+    private fun createNode(
+        id: String = "node_test",
+        className: String? = "android.widget.TextView",
+        text: String? = null,
+        contentDescription: String? = null,
+        resourceId: String? = null,
+        bounds: BoundsData = defaultBounds,
+        clickable: Boolean = false,
+        longClickable: Boolean = false,
+        scrollable: Boolean = false,
+        editable: Boolean = false,
+        enabled: Boolean = true,
+        visible: Boolean = true,
+        children: List<AccessibilityNodeData> = emptyList(),
+    ): AccessibilityNodeData {
+        return AccessibilityNodeData(
+            id = id,
+            className = className,
+            text = text,
+            contentDescription = contentDescription,
+            resourceId = resourceId,
+            bounds = bounds,
+            clickable = clickable,
+            longClickable = longClickable,
+            scrollable = scrollable,
+            editable = editable,
+            enabled = enabled,
+            visible = visible,
+            children = children,
+        )
+    }
+
+    private fun buildSampleTree(): AccessibilityNodeData {
+        return createNode(
+            id = "node_root",
+            className = "android.widget.FrameLayout",
+            children = listOf(
+                createNode(
+                    id = "node_button_7",
+                    className = "android.widget.Button",
+                    text = "7",
+                    contentDescription = "Seven",
+                    resourceId = "com.calculator:id/btn_7",
+                    clickable = true,
+                ),
+                createNode(
+                    id = "node_button_plus",
+                    className = "android.widget.Button",
+                    text = "+",
+                    contentDescription = "Plus",
+                    resourceId = "com.calculator:id/btn_plus",
+                    clickable = true,
+                ),
+                createNode(
+                    id = "node_button_3",
+                    className = "android.widget.Button",
+                    text = "3",
+                    contentDescription = "Three",
+                    resourceId = "com.calculator:id/btn_3",
+                    clickable = true,
+                ),
+                createNode(
+                    id = "node_display",
+                    className = "android.widget.EditText",
+                    text = "0",
+                    resourceId = "com.calculator:id/display",
+                    editable = true,
+                ),
+            ),
+        )
+    }
+
+    @Nested
+    @DisplayName("findElements by TEXT")
+    inner class FindByText {
+
+        @Test
+        @DisplayName("finds element by exact text match")
+        fun findsElementByExactTextMatch() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(tree, FindBy.TEXT, "7", exactMatch = true)
+
+            // Assert
+            assertEquals(1, results.size)
+            assertEquals("node_button_7", results[0].id)
+            assertEquals("7", results[0].text)
+        }
+
+        @Test
+        @DisplayName("finds element by contains text match (case-insensitive)")
+        fun findsElementByContainsTextMatch() {
+            // Arrange
+            val tree = createNode(
+                id = "node_root",
+                children = listOf(
+                    createNode(id = "node_1", text = "Hello World"),
+                    createNode(id = "node_2", text = "hello there"),
+                    createNode(id = "node_3", text = "Goodbye"),
+                ),
+            )
+
+            // Act
+            val results = finder.findElements(tree, FindBy.TEXT, "hello", exactMatch = false)
+
+            // Assert
+            assertEquals(2, results.size)
+            assertEquals("node_1", results[0].id)
+            assertEquals("node_2", results[1].id)
+        }
+
+        @Test
+        @DisplayName("returns empty list when no text matches")
+        fun returnsEmptyListWhenNoTextMatches() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(tree, FindBy.TEXT, "NonExistent", exactMatch = true)
+
+            // Assert
+            assertTrue(results.isEmpty())
+        }
+
+        @Test
+        @DisplayName("exact match is case-sensitive")
+        fun exactMatchIsCaseSensitive() {
+            // Arrange
+            val tree = createNode(
+                id = "node_root",
+                children = listOf(
+                    createNode(id = "node_1", text = "Hello"),
+                    createNode(id = "node_2", text = "hello"),
+                ),
+            )
+
+            // Act
+            val results = finder.findElements(tree, FindBy.TEXT, "Hello", exactMatch = true)
+
+            // Assert
+            assertEquals(1, results.size)
+            assertEquals("node_1", results[0].id)
+        }
+    }
+
+    @Nested
+    @DisplayName("findElements by CONTENT_DESC")
+    inner class FindByContentDesc {
+
+        @Test
+        @DisplayName("finds element by content description")
+        fun findsElementByContentDescription() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.CONTENT_DESC, "Seven", exactMatch = true,
+            )
+
+            // Assert
+            assertEquals(1, results.size)
+            assertEquals("node_button_7", results[0].id)
+        }
+
+        @Test
+        @DisplayName("contains match on content description is case-insensitive")
+        fun containsMatchIsCaseInsensitive() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.CONTENT_DESC, "seven", exactMatch = false,
+            )
+
+            // Assert
+            assertEquals(1, results.size)
+            assertEquals("node_button_7", results[0].id)
+        }
+    }
+
+    @Nested
+    @DisplayName("findElements by RESOURCE_ID")
+    inner class FindByResourceId {
+
+        @Test
+        @DisplayName("finds element by resource ID")
+        fun findsElementByResourceId() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.RESOURCE_ID, "com.calculator:id/btn_plus", exactMatch = true,
+            )
+
+            // Assert
+            assertEquals(1, results.size)
+            assertEquals("node_button_plus", results[0].id)
+        }
+
+        @Test
+        @DisplayName("contains match on resource ID")
+        fun containsMatchOnResourceId() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.RESOURCE_ID, "btn_", exactMatch = false,
+            )
+
+            // Assert
+            assertEquals(3, results.size)
+        }
+    }
+
+    @Nested
+    @DisplayName("findElements by CLASS_NAME")
+    inner class FindByClassName {
+
+        @Test
+        @DisplayName("finds elements by class name")
+        fun findsElementsByClassName() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.CLASS_NAME, "android.widget.Button", exactMatch = true,
+            )
+
+            // Assert
+            assertEquals(3, results.size)
+        }
+
+        @Test
+        @DisplayName("finds elements by partial class name")
+        fun findsElementsByPartialClassName() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val results = finder.findElements(
+                tree, FindBy.CLASS_NAME, "Button", exactMatch = false,
+            )
+
+            // Assert
+            assertEquals(3, results.size)
+        }
+    }
+
+    @Nested
+    @DisplayName("findNodeById")
+    inner class FindNodeById {
+
+        @Test
+        @DisplayName("finds node by ID in tree")
+        fun findsNodeByIdInTree() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val result = finder.findNodeById(tree, "node_button_3")
+
+            // Assert
+            assertNotNull(result)
+            assertEquals("3", result?.text)
+            assertEquals("node_button_3", result?.id)
+        }
+
+        @Test
+        @DisplayName("returns null for non-existent ID")
+        fun returnsNullForNonExistentId() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val result = finder.findNodeById(tree, "node_does_not_exist")
+
+            // Assert
+            assertNull(result)
+        }
+
+        @Test
+        @DisplayName("finds root node by ID")
+        fun findsRootNodeById() {
+            // Arrange
+            val tree = buildSampleTree()
+
+            // Act
+            val result = finder.findNodeById(tree, "node_root")
+
+            // Assert
+            assertNotNull(result)
+            assertEquals("node_root", result?.id)
+        }
+    }
+
+    @Nested
+    @DisplayName("matchesValue")
+    inner class MatchesValue {
+
+        @Test
+        @DisplayName("returns false for null node value")
+        fun returnsFalseForNullNodeValue() {
+            // Act & Assert
+            assertEquals(false, finder.matchesValue(null, "test", exactMatch = true))
+            assertEquals(false, finder.matchesValue(null, "test", exactMatch = false))
+        }
+
+        @Test
+        @DisplayName("exact match returns true for equal strings")
+        fun exactMatchReturnsTrueForEqualStrings() {
+            // Act & Assert
+            assertEquals(true, finder.matchesValue("Hello", "Hello", exactMatch = true))
+        }
+
+        @Test
+        @DisplayName("exact match returns false for different case")
+        fun exactMatchReturnsFalseForDifferentCase() {
+            // Act & Assert
+            assertEquals(false, finder.matchesValue("Hello", "hello", exactMatch = true))
+        }
+
+        @Test
+        @DisplayName("contains match is case-insensitive")
+        fun containsMatchIsCaseInsensitive() {
+            // Act & Assert
+            assertEquals(true, finder.matchesValue("Hello World", "hello", exactMatch = false))
+        }
+    }
+}
```

---

#### Action 4.6.2: Create `ActionExecutorTest.kt`

**What**: Create unit tests for the action executor.

**Context**: Testing the `ActionExecutor` is more challenging because it depends on `McpAccessibilityService.instance` (a static singleton). We use MockK's `mockkObject` or directly set the companion object field to mock the service. Global actions and gesture dispatch can be verified by checking that the correct methods are called with the correct parameters.

For coordinate validation and service unavailability, we can test directly without heavy mocking.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorTest.kt
@@ -0,0 +1,253 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import android.accessibilityservice.AccessibilityService
+import android.accessibilityservice.GestureDescription
+import android.graphics.Rect
+import android.view.accessibility.AccessibilityNodeInfo
+import io.mockk.every
+import io.mockk.just
+import io.mockk.mockk
+import io.mockk.runs
+import io.mockk.slot
+import io.mockk.verify
+import kotlinx.coroutines.test.runTest
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import java.lang.reflect.Field
+
+@DisplayName("ActionExecutor")
+class ActionExecutorTest {
+
+    private lateinit var executor: ActionExecutor
+    private lateinit var mockService: McpAccessibilityService
+
+    @BeforeEach
+    fun setUp() {
+        executor = ActionExecutor()
+        mockService = mockk<McpAccessibilityService>(relaxed = true)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        setServiceInstance(null)
+    }
+
+    /**
+     * Uses reflection to set the McpAccessibilityService companion object's
+     * instance field for testing purposes.
+     */
+    private fun setServiceInstance(service: McpAccessibilityService?) {
+        val companionClass = McpAccessibilityService.Companion::class.java
+        val instanceField: Field = companionClass.getDeclaredField("instance")
+        instanceField.isAccessible = true
+        instanceField.set(McpAccessibilityService.Companion, service)
+    }
+
+    @Nested
+    @DisplayName("Service availability")
+    inner class ServiceAvailability {
+
+        @Test
+        @DisplayName("tap returns failure when service is not available")
+        fun tapReturnsFailureWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setServiceInstance(null)
+
+            // Act
+            val result = executor.tap(100f, 200f)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(
+                result.exceptionOrNull() is IllegalStateException,
+            )
+            assertTrue(
+                result.exceptionOrNull()?.message?.contains("not available") == true,
+            )
+        }
+
+        @Test
+        @DisplayName("pressBack returns failure when service is not available")
+        fun pressBackReturnsFailureWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setServiceInstance(null)
+
+            // Act
+            val result = executor.pressBack()
+
+            // Assert
+            assertTrue(result.isFailure)
+        }
+
+        @Test
+        @DisplayName("clickNode returns failure when service is not available")
+        fun clickNodeReturnsFailureWhenServiceNotAvailable() = runTest {
+            // Arrange
+            setServiceInstance(null)
+            val tree = AccessibilityNodeData(
+                id = "node_test",
+                bounds = BoundsData(0, 0, 100, 50),
+            )
+
+            // Act
+            val result = executor.clickNode("node_test", tree)
+
+            // Assert
+            assertTrue(result.isFailure)
+        }
+    }
+
+    @Nested
+    @DisplayName("Coordinate validation")
+    inner class CoordinateValidation {
+
+        @Test
+        @DisplayName("tap rejects negative X coordinate")
+        fun tapRejectsNegativeXCoordinate() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+
+            // Act
+            val result = executor.tap(-1f, 100f)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
+        }
+
+        @Test
+        @DisplayName("tap rejects negative Y coordinate")
+        fun tapRejectsNegativeYCoordinate() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+
+            // Act
+            val result = executor.tap(100f, -1f)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
+        }
+
+        @Test
+        @DisplayName("swipe rejects negative start coordinates")
+        fun swipeRejectsNegativeStartCoordinates() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+
+            // Act
+            val result = executor.swipe(-1f, 0f, 100f, 100f)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
+        }
+    }
+
+    @Nested
+    @DisplayName("Global actions")
+    inner class GlobalActions {
+
+        @Test
+        @DisplayName("pressBack calls performGlobalAction with GLOBAL_ACTION_BACK")
+        fun pressBackCallsCorrectGlobalAction() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            every {
+                mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
+            } returns true
+
+            // Act
+            val result = executor.pressBack()
+
+            // Assert
+            assertTrue(result.isSuccess)
+            verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
+        }
+
+        @Test
+        @DisplayName("pressHome calls performGlobalAction with GLOBAL_ACTION_HOME")
+        fun pressHomeCallsCorrectGlobalAction() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            every {
+                mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
+            } returns true
+
+            // Act
+            val result = executor.pressHome()
+
+            // Assert
+            assertTrue(result.isSuccess)
+            verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
+        }
+
+        @Test
+        @DisplayName("pressRecents calls performGlobalAction with GLOBAL_ACTION_RECENTS")
+        fun pressRecentsCallsCorrectGlobalAction() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            every {
+                mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
+            } returns true
+
+            // Act
+            val result = executor.pressRecents()
+
+            // Assert
+            assertTrue(result.isSuccess)
+            verify { mockService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
+        }
+
+        @Test
+        @DisplayName("global action returns failure when performGlobalAction returns false")
+        fun globalActionReturnsFailureWhenActionFails() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            every {
+                mockService.performGlobalAction(any())
+            } returns false
+
+            // Act
+            val result = executor.pressBack()
+
+            // Assert
+            assertTrue(result.isFailure)
+        }
+    }
+
+    @Nested
+    @DisplayName("Node actions")
+    inner class NodeActions {
+
+        @Test
+        @DisplayName("clickNode returns failure when node is not found in tree")
+        fun clickNodeReturnsFailureWhenNodeNotFound() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            val mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
+            every { mockService.getRootNode() } returns mockRootNode
+            every { mockRootNode.childCount } returns 0
+
+            val rectSlot = slot<Rect>()
+            every { mockRootNode.getBoundsInScreen(capture(rectSlot)) } answers {
+                rectSlot.captured.set(0, 0, 1080, 2400)
+            }
+
+            val tree = AccessibilityNodeData(
+                id = "node_root",
+                bounds = BoundsData(0, 0, 1080, 2400),
+            )
+
+            // Act
+            val result = executor.clickNode("node_nonexistent", tree)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is NoSuchElementException)
+        }
+    }
+
+    @Nested
+    @DisplayName("Custom gesture validation")
+    inner class CustomGestureValidation {
+
+        @Test
+        @DisplayName("customGesture returns failure for empty paths")
+        fun customGestureReturnsFailureForEmptyPaths() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+
+            // Act
+            val result = executor.customGesture(emptyList())
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
+        }
+
+        @Test
+        @DisplayName("customGesture returns failure for path with less than 2 points")
+        fun customGestureReturnsFailureForShortPath() = runTest {
+            // Arrange
+            setServiceInstance(mockService)
+            val singlePointPath = listOf(
+                listOf(GesturePoint(100f, 100f, 0L)),
+            )
+
+            // Act
+            val result = executor.customGesture(singlePointPath)
+
+            // Assert
+            assertTrue(result.isFailure)
+            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
+        }
+    }
+}
```

> **Implementation Notes**:
> - `setServiceInstance` uses reflection to set the `McpAccessibilityService.instance` companion object field. This is necessary because the field has `private set` visibility. While reflection in tests is not ideal, it is the standard approach for testing singleton patterns in Android services. The `@AfterEach` teardown ensures the singleton is always cleaned up.
> - Tests use `runTest` from `kotlinx.coroutines.test` for testing suspend functions.
> - The `relaxed = true` option on MockK creates a mock that returns default values for unstubbed methods, reducing boilerplate.
> - Service availability tests verify that ALL action categories (tap, global, node) correctly check for the service before attempting operations.
> - Coordinate validation tests verify that negative coordinates are rejected with `IllegalArgumentException`.
> - Global action tests verify that the correct `GLOBAL_ACTION_*` constant is passed to `performGlobalAction`.
> - Node action tests verify the failure case when a node ID is not found in the tree.

---

### Task 4.7: Verification and Commit

**Description**: Run all verification commands and create commits.

**Acceptance Criteria**:
- [x] `./gradlew test --tests '*AccessibilityTreeParserTest*'` passes
- [x] `./gradlew test --tests '*ElementFinderTest*'` passes
- [x] `./gradlew test --tests '*ActionExecutorTest*'` passes
- [x] `make lint` passes with no errors or warnings
- [x] `make test-unit` passes with all tests green
- [x] `make build` succeeds without errors or warnings
- [x] All 6 commits are created on the `feat/accessibility-service` branch

**Tests**: These are the verification steps.

#### Action 4.7.1: Run targeted tests

**What**: Run the unit tests specific to this plan's files.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Run tree parser tests
timeout 300 ./gradlew test --tests '*AccessibilityTreeParserTest*'

# Run element finder tests
timeout 300 ./gradlew test --tests '*ElementFinderTest*'

# Run action executor tests
timeout 300 ./gradlew test --tests '*ActionExecutorTest*'
```

**Expected outcome**: All tests pass.

**If tests fail**: Inspect the test report at `app/build/reports/tests/testDebugUnitTest/index.html`, identify the root cause, and fix the code. Re-run the failing test class.

---

#### Action 4.7.2: Run full test suite

**What**: Run all unit tests to ensure no regressions from Plan 2/3 tests.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
timeout 300 make test-unit
```

**Expected outcome**: All tests pass (Plan 2 tests + Plan 3 tests + Plan 4 tests).

---

#### Action 4.7.3: Run lint

**What**: Verify no linting errors or warnings.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
timeout 300 make lint
```

**Expected outcome**: No ktlint or detekt violations.

**If lint fails**: Run `make lint-fix` for auto-fixable issues, then re-run `make lint`.

---

#### Action 4.7.4: Run build

**What**: Verify the project compiles.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
timeout 300 make build
```

**Expected outcome**: BUILD SUCCESSFUL.

---

#### Action 4.7.5: Create the feature branch and commits

**What**: Create the `feat/accessibility-service` branch and make all 6 commits.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Create and switch to feature branch
git checkout -b feat/accessibility-service

# Commit 1: McpAccessibilityService
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt
git commit -m "$(cat <<'EOF'
feat: add McpAccessibilityService with singleton lifecycle

Replace stub with full implementation including singleton instance
management, accessibility event tracking (package/activity name),
coroutine scope lifecycle, and memory management callbacks.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 2: AccessibilityTreeParser
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt
git commit -m "$(cat <<'EOF'
feat: add accessibility tree parser with JSON-serializable output

Add AccessibilityTreeParser with @Serializable data classes
(AccessibilityNodeData, BoundsData) for recursive parsing of
AccessibilityNodeInfo trees. Includes stable node ID generation
and proper node recycling.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 3: ElementFinder
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinder.kt
git commit -m "$(cat <<'EOF'
feat: add element finder with multi-criteria search

Add ElementFinder supporting search by text, content description,
resource ID, and class name with exact and case-insensitive contains
matching. Operates on parsed AccessibilityNodeData for testability.
Includes ElementInfo and FindBy types.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 4: ActionExecutor
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutor.kt
git commit -m "$(cat <<'EOF'
feat: add action executor for gestures, clicks, and global actions

Add ActionExecutor with node actions (click, long click, set text,
scroll), coordinate-based gestures (tap, long press, double tap,
swipe, scroll), global actions (back, home, recents, notifications,
quick settings), and advanced gestures (pinch, custom multi-path).
Uses suspendCancellableCoroutine for gesture callbacks.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 5: AccessibilityTreeParser tests
git add app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParserTest.kt
git commit -m "$(cat <<'EOF'
test: add unit tests for accessibility tree parser

Test single node parsing, nested trees, deep trees, null handling,
node visibility, ID generation stability, bounds extraction,
and node recycling behavior using MockK mocks.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 6: ElementFinder and ActionExecutor tests
git add app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ElementFinderTest.kt \
     app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorTest.kt
git commit -m "$(cat <<'EOF'
test: add unit tests for element finder and action executor

ElementFinderTest: test all FindBy criteria with exact and contains
matching, findNodeById, multiple matches, empty results.
ActionExecutorTest: test service availability checks, coordinate
validation, global action dispatch, node-not-found handling,
and custom gesture validation.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

#### Action 4.7.6: Push and create PR

**What**: Push the branch and create the pull request.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Push feature branch
git push -u origin feat/accessibility-service

# Create PR
gh pr create --base main --title "Plan 4: AccessibilityService and core accessibility logic" --body "$(cat <<'EOF'
## Summary

- Implement McpAccessibilityService with singleton lifecycle, event tracking, and coroutine scope management
- Add AccessibilityTreeParser with @Serializable data classes for recursive AccessibilityNodeInfo parsing
- Add ElementFinder with multi-criteria search (text, content desc, resource ID, class name) on parsed tree data
- Add ActionExecutor with node actions, coordinate gestures, global actions, and advanced gestures (pinch, custom)
- Comprehensive unit tests for all three new components

## Plan Reference

Implementation of Plan 4: AccessibilityService & Core Accessibility Logic from `docs/plans/4_accessibility-service_20260211163214.md`.

## Test plan

- [x] `./gradlew test --tests '*AccessibilityTreeParserTest*'` passes
- [x] `./gradlew test --tests '*ElementFinderTest*'` passes
- [x] `./gradlew test --tests '*ActionExecutorTest*'` passes
- [x] `make test-unit` passes (all tests including Plans 2-3)
- [x] `make lint` passes
- [x] `make build` succeeds

Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Performance, Security, and QA Review

### Performance

- **Node recycling**: All `AccessibilityNodeInfo` child nodes are properly recycled after data extraction via `node.recycle()`, preventing native memory leaks. Root nodes are explicitly NOT recycled by the parser (caller responsibility).
- **Tree parsing efficiency**: The parser does a single recursive pass through the tree. No redundant traversals.
- **ElementFinder on parsed data**: The `ElementFinder` operates on plain Kotlin data classes, not on `AccessibilityNodeInfo`. This avoids repeated IPC calls to the accessibility framework and allows the finder to work without holding onto native node references.
- **Gesture dispatch**: `suspendCancellableCoroutine` properly handles cancellation. If the coroutine is cancelled, the gesture callback will not resume on a dead continuation (checked via `continuation.isActive`).
- **Coroutine scope**: The service uses `SupervisorJob()` so failure of one child coroutine does not cascade. The scope is properly cancelled in `onDestroy()`.
- **Singleton volatile**: `@Volatile` on the instance field ensures cross-thread visibility without requiring synchronization for simple reads.

### Security

- **No sensitive data in logs**: Log statements include package names, activity names, node IDs, and action descriptions, but never bearer tokens, full accessibility tree content, or other sensitive data.
- **No reflection to access hidden APIs**: All accessibility operations use public Android SDK APIs (`performAction`, `performGlobalAction`, `dispatchGesture`, `rootInActiveWindow`).
- **No root access**: The service uses standard `AccessibilityService` APIs that do not require root.
- **Input validation**: All coordinate-based actions validate that coordinates are non-negative. Custom gesture paths are validated for minimum point count. Node actions check for service availability and node existence before attempting operations.
- **Node ID generation**: Node IDs are hash-based and do not expose internal implementation details. They cannot be used to extract sensitive information.

### QA

- **Test coverage**: Three test files cover the parser, finder, and executor. Tests include standard cases, edge cases (null values, empty trees, non-existent nodes), and failure modes (service unavailable, invalid coordinates, non-clickable nodes).
- **Mock strategy**: `AccessibilityNodeInfo` is thoroughly mocked with MockK including the `getBoundsInScreen` output parameter pattern. `ElementFinder` tests use plain data classes (no mocking needed), demonstrating the benefit of the parsed-data architecture.
- **Singleton testing**: `ActionExecutorTest` uses reflection to set/clear the service singleton, with `@AfterEach` cleanup to prevent test pollution.
- **No Android framework dependency in ElementFinder tests**: The `ElementFinder` tests build trees from plain `AccessibilityNodeData` instances, making them fast and reliable without any Android framework interaction.
- **Recycling verification**: Tests explicitly verify that child nodes are recycled and root nodes are NOT recycled, catching potential memory leak regressions.
- **Backwards compatibility**: `node.recycle()` deprecation is handled with `@Suppress("DEPRECATION")` since `minSdk=26` requires supporting older API levels where recycling is mandatory.

---

## File Inventory

All files created or modified in this plan:

| # | File Path | Task | Action |
|---|-----------|------|--------|
| 1 | `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` | 4.1 | Modified (replace stub) |
| 2 | `app/src/main/kotlin/.../services/accessibility/AccessibilityTreeParser.kt` | 4.2 | Created |
| 3 | `app/src/main/kotlin/.../services/accessibility/ElementFinder.kt` | 4.3 | Created |
| 4 | `app/src/main/kotlin/.../services/accessibility/ActionExecutor.kt` | 4.4 | Created |
| 5 | `app/src/test/kotlin/.../services/accessibility/AccessibilityTreeParserTest.kt` | 4.5 | Created |
| 6 | `app/src/test/kotlin/.../services/accessibility/ElementFinderTest.kt` | 4.6 | Created |
| 7 | `app/src/test/kotlin/.../services/accessibility/ActionExecutorTest.kt` | 4.6 | Created |

**Total**: 7 files (1 modified, 6 created)

---

**End of Plan 4**
