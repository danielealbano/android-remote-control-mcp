# Plan 11 — JVM-Based MCP Server Integration Tests & Interface Extraction

## Overview

Replace the Docker-emulator-based integration tests (which only tested UI element visibility) with proper JVM-based integration tests that test the full MCP server stack: HTTP → authentication → JSON-RPC → tool dispatch → mocked Android layer → response formatting.

This requires extracting 3 interfaces from the Android service layer so tools can receive mock implementations in tests, then writing comprehensive integration tests using Ktor's `testApplication` (in-process, no real socket, no emulator, no Docker).

The current `androidTest/` directory (MainActivityTest, HiltTestRunner), `scripts/run-integration-tests.sh`, and the CI `test-integration` job will be deleted entirely. Integration tests will run as JVM tests in `app/src/test/` via `./gradlew :app:test`, merged into the existing unit test CI job.

---

## Pre-requisite: Create feature branch

Before starting implementation, create a feature branch from the latest `main`:

```bash
git checkout main && git pull origin main
git checkout -b feat/jvm-integration-tests-refactor
```

Commits MUST be pushed regularly throughout the implementation. When all work is complete and quality gates pass, create a PR following TOOLS.md conventions and request Copilot as a reviewer.

---

## User Story 1: Extract Interfaces from Android Service Layer

**As a** developer,
**I want** the Android service layer to be abstracted behind interfaces,
**So that** MCP tools can be tested with mock implementations on JVM without an Android emulator.

### Acceptance Criteria
- [x] `ActionExecutor` is an interface; the current implementation is renamed `ActionExecutorImpl`
- [x] `AccessibilityServiceProvider` interface exists and is used by all tools that previously accessed `McpAccessibilityService.instance` directly
- [x] `ScreenCaptureProvider` interface exists and is used by `CaptureScreenshotHandler`
- [x] All 3 interfaces have Hilt bindings in `AppModule.kt`
- [x] No tool file references `McpAccessibilityService.instance` or `McpServerService.instance` directly (only via injected interfaces)
- [x] All existing unit tests pass (with updated constructor signatures)
- [x] `./gradlew ktlintCheck detekt` passes with no warnings or errors
- [x] `./gradlew :app:test` passes with no failures

---

### Task 1.1: Create `ActionExecutor` interface and rename implementation to `ActionExecutorImpl`

**Goal**: Extract an interface from the existing `ActionExecutor` class so tools depend on the interface (mockable) rather than the concrete class.

**Acceptance Criteria**:
- [x] `ActionExecutor` interface exists in `services/accessibility/ActionExecutor.kt` with all 17 public method signatures
- [x] `ActionExecutorImpl` class exists in `services/accessibility/ActionExecutorImpl.kt` implementing the interface
- [x] `ActionExecutorImpl` retains all existing logic including `McpAccessibilityService.instance` access
- [x] No compilation errors

#### Action 1.1.1: Create `ActionExecutor` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutor.kt`

Replace the current class with an interface containing all 17 public method signatures currently defined in the class (lines 80-655):

```kotlin
interface ActionExecutor {
    suspend fun clickNode(nodeId: String, tree: AccessibilityNodeData): Result<Unit>
    suspend fun longClickNode(nodeId: String, tree: AccessibilityNodeData): Result<Unit>
    suspend fun setTextOnNode(nodeId: String, text: String, tree: AccessibilityNodeData): Result<Unit>
    suspend fun scrollNode(nodeId: String, direction: ScrollDirection, tree: AccessibilityNodeData): Result<Unit>
    suspend fun tap(x: Float, y: Float): Result<Unit>
    suspend fun longPress(x: Float, y: Float, duration: Long = DEFAULT_LONG_PRESS_DURATION_MS): Result<Unit>
    suspend fun doubleTap(x: Float, y: Float): Result<Unit>
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = DEFAULT_SWIPE_DURATION_MS): Result<Unit>
    suspend fun scroll(direction: ScrollDirection, amount: ScrollAmount = ScrollAmount.MEDIUM): Result<Unit>
    suspend fun pressBack(): Result<Unit>
    suspend fun pressHome(): Result<Unit>
    suspend fun pressRecents(): Result<Unit>
    suspend fun openNotifications(): Result<Unit>
    suspend fun openQuickSettings(): Result<Unit>
    suspend fun pinch(centerX: Float, centerY: Float, scale: Float, duration: Long = DEFAULT_GESTURE_DURATION_MS): Result<Unit>
    suspend fun customGesture(paths: List<List<GesturePoint>>): Result<Unit>
    fun findAccessibilityNodeByNodeId(rootNode: AccessibilityNodeInfo, nodeId: String, tree: AccessibilityNodeData): AccessibilityNodeInfo?
}
```

Keep the companion object constants (`DEFAULT_LONG_PRESS_DURATION_MS`, `DEFAULT_SWIPE_DURATION_MS`, `DEFAULT_GESTURE_DURATION_MS`, etc.) in a companion object on the interface or in a separate constants object — verify which constants are currently in the companion and preserve them.

**Important**: The current companion object has 8 constants (line 760-768), all declared as `private const val`. The 3 constants used as default parameter values in the interface signatures (`DEFAULT_LONG_PRESS_DURATION_MS`, `DEFAULT_SWIPE_DURATION_MS`, `DEFAULT_GESTURE_DURATION_MS`) MUST change visibility from `private` to at least `internal` (or public) so the interface can reference them. The remaining 5 constants (`TAG`, `TAP_DURATION_MS`, `DOUBLE_TAP_GAP_MS`, `PINCH_BASE_DISTANCE`) are implementation details — keep them in `ActionExecutorImpl`'s own companion object, where they can remain `private`.

#### Action 1.1.2: Create `ActionExecutorImpl` class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ActionExecutorImpl.kt` (new file)

Move the entire current `ActionExecutor` class body into `ActionExecutorImpl`:
- Class declaration: `class ActionExecutorImpl @Inject constructor() : ActionExecutor`
- All method implementations from the current `ActionExecutor.kt` (lines 80-769)
- All private helper methods
- All `McpAccessibilityService.instance` accesses remain inside this implementation
- Remove the `@Inject` from the old file (it's now an interface)

---

### Task 1.2: Create `AccessibilityServiceProvider` interface and implementation

**Goal**: Abstract the `McpAccessibilityService.instance` singleton access behind an injectable interface.

**Acceptance Criteria**:
- [x] `AccessibilityServiceProvider` interface exists with methods matching what tools currently call on the singleton
- [x] `AccessibilityServiceProviderImpl` delegates to `McpAccessibilityService.instance`
- [x] No compilation errors

#### Action 1.2.1: Create `AccessibilityServiceProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityServiceProvider.kt` (new file)

Define the interface based on what tools currently access via `McpAccessibilityService.instance`:

```kotlin
interface AccessibilityServiceProvider {
    fun getRootNode(): AccessibilityNodeInfo?
    fun getCurrentPackageName(): String?
    fun getCurrentActivityName(): String?
    fun getScreenInfo(): ScreenInfo
    fun isReady(): Boolean
    fun getContext(): Context?
}
```

The `getContext()` method is needed by `GetClipboardTool` and `SetClipboardTool` which call `service.getSystemService(ClipboardManager::class.java)` on the `McpAccessibilityService` instance (which is a `Context` subclass since `AccessibilityService extends Service extends Context`).

#### Action 1.2.2: Create `AccessibilityServiceProviderImpl` class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityServiceProviderImpl.kt` (new file)

```kotlin
class AccessibilityServiceProviderImpl @Inject constructor() : AccessibilityServiceProvider {
    override fun getRootNode(): AccessibilityNodeInfo? =
        McpAccessibilityService.instance?.getRootNode()

    override fun getCurrentPackageName(): String? =
        McpAccessibilityService.instance?.getCurrentPackageName()

    override fun getCurrentActivityName(): String? =
        McpAccessibilityService.instance?.getCurrentActivityName()

    override fun getScreenInfo(): ScreenInfo =
        McpAccessibilityService.instance?.getScreenInfo()
            ?: throw McpToolException.PermissionDenied("Accessibility service not available")

    override fun isReady(): Boolean =
        McpAccessibilityService.instance?.isReady() == true

    override fun getContext(): Context? =
        McpAccessibilityService.instance
}
```

**Note**: Verify the exact null-handling and exception-throwing patterns currently used in each tool handler when `McpAccessibilityService.instance` is null, and replicate the same behavior. Some tools throw `McpToolException.PermissionDenied`, others check `isReady()` first. The implementation should preserve the same null-safety patterns.

---

### Task 1.3: Create `ScreenCaptureProvider` interface and implementation

**Goal**: Abstract the `McpServerService.instance?.getScreenCaptureService()` access behind an injectable interface.

**Acceptance Criteria**:
- [x] `ScreenCaptureProvider` interface exists with `captureScreenshot()` method
- [x] `ScreenCaptureProviderImpl` delegates to `McpServerService.instance?.getScreenCaptureService()`
- [x] No compilation errors

#### Action 1.3.1: Create `ScreenCaptureProvider` interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProvider.kt` (new file)

```kotlin
interface ScreenCaptureProvider {
    suspend fun captureScreenshot(quality: Int = ScreenCaptureService.DEFAULT_QUALITY): Result<ScreenshotData>
    fun isMediaProjectionActive(): Boolean
}
```

Verify `ScreenshotData` type and import — it's used by `ScreenCaptureService.captureScreenshot()` return type.

#### Action 1.3.2: Create `ScreenCaptureProviderImpl` class

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureProviderImpl.kt` (new file)

```kotlin
class ScreenCaptureProviderImpl @Inject constructor() : ScreenCaptureProvider {
    override suspend fun captureScreenshot(quality: Int): Result<ScreenshotData> {
        val service = McpServerService.instance?.getScreenCaptureService()
            ?: return Result.failure(McpToolException.PermissionDenied("Screen capture service not available"))
        return service.captureScreenshot(quality)
    }

    override fun isMediaProjectionActive(): Boolean =
        McpServerService.instance?.getScreenCaptureService()?.isMediaProjectionActive() == true
}
```

Verify the exact error-handling pattern currently used in `CaptureScreenshotHandler` (ScreenIntrospectionTools.kt line 124) and replicate it.

---

### Task 1.4: Update Hilt DI module with new interface bindings

**Goal**: Register the 3 new interface → implementation bindings in Hilt.

**Acceptance Criteria**:
- [x] `AppModule.kt` binds `ActionExecutor` → `ActionExecutorImpl`
- [x] `AppModule.kt` binds `AccessibilityServiceProvider` → `AccessibilityServiceProviderImpl`
- [x] `AppModule.kt` binds `ScreenCaptureProvider` → `ScreenCaptureProviderImpl`
- [x] No compilation errors

#### Action 1.4.1: Add bindings to `AppModule.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

Add to the existing `RepositoryModule` (or create a new `ServiceModule`) abstract class with `@Binds` methods:

```kotlin
@Binds
@Singleton
abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor

@Binds
@Singleton
abstract fun bindAccessibilityServiceProvider(impl: AccessibilityServiceProviderImpl): AccessibilityServiceProvider

@Binds
@Singleton
abstract fun bindScreenCaptureProvider(impl: ScreenCaptureProviderImpl): ScreenCaptureProvider
```

Add the necessary imports.

**Note on `@Singleton` scope**: Currently, each registration function creates its own local `ActionExecutor()` instance (7 separate instances across the codebase). With `@Singleton`, Hilt will provide a single shared instance. This is safe because `ActionExecutor` is documented as stateless (line 63 of `ActionExecutor.kt`). The same applies to `AccessibilityServiceProviderImpl` and `ScreenCaptureProviderImpl` — both are stateless wrappers.

---

### Task 1.5: Update tool handlers to receive `AccessibilityServiceProvider` and `ScreenCaptureProvider`

**Goal**: Replace all direct `McpAccessibilityService.instance` and `McpServerService.instance` accesses in tool files with injected interface dependencies.

**Acceptance Criteria**:
- [x] No tool file in `mcp/tools/` references `McpAccessibilityService.instance` directly
- [x] No tool file in `mcp/tools/` references `McpServerService.instance` directly
- [x] All tool handlers receive dependencies via constructor injection
- [x] No compilation errors

#### Action 1.5.1: Update `ScreenIntrospectionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt`

**Changes to `GetAccessibilityTreeHandler` (lines 37-98)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Replace `McpAccessibilityService.instance` (line 44) with `accessibilityServiceProvider.getRootNode()`
- Adjust null-check and error handling to match current behavior

**Changes to `CaptureScreenshotHandler` (lines 117-196)**:
- Add `private val screenCaptureProvider: ScreenCaptureProvider` to constructor
- Replace `McpServerService.instance?.getScreenCaptureService()` (line 124) with `screenCaptureProvider`
- Replace `screenCaptureService.isMediaProjectionActive()` check (line 129) with `screenCaptureProvider.isMediaProjectionActive()`
- Call `screenCaptureProvider.captureScreenshot(quality)` instead of `service.captureScreenshot(quality)` (line 135)
- Note: The current code has a separate null-check for the service (line 124) and a separate `isMediaProjectionActive()` check (line 129) with distinct error messages. With the provider, the null-check is absorbed into the provider implementation. If the service is null, `isMediaProjectionActive()` will return `false`, triggering the "MediaProjection permission not granted" error rather than "Screen capture service is not available". This is acceptable since both indicate a permission/setup issue.

**Changes to `GetCurrentAppHandler` (lines 211-250)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Replace `McpAccessibilityService.instance` (line 216) with `accessibilityServiceProvider`

**Changes to `GetScreenInfoHandler` (lines 265-302)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Replace `McpAccessibilityService.instance` (line 270) with `accessibilityServiceProvider`

**Update registration function** `registerScreenIntrospectionTools()` (lines 313-319):
- Add `accessibilityServiceProvider: AccessibilityServiceProvider` and `screenCaptureProvider: ScreenCaptureProvider` parameters
- Pass them to the handler constructors

#### Action 1.5.2: Update `UtilityTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt`

**Changes to `GetClipboardTool` (lines 34-97)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Replace `McpAccessibilityService.instance` (line 39) with `accessibilityServiceProvider`
- Use `accessibilityServiceProvider.getContext()?.getSystemService(ClipboardManager::class.java)` for clipboard access

**Changes to `SetClipboardTool` (lines 104-167)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Replace `McpAccessibilityService.instance` (line 113) with `accessibilityServiceProvider`
- Same clipboard access pattern change

**Changes to `WaitForElementTool` (lines 175-313)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Pass it to `getFreshTree()` calls (see Task 1.6)

**Changes to `WaitForIdleTool` (lines 322-427)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Pass it to `getFreshTree()` calls (see Task 1.6)

**Update registration function** `registerUtilityTools()` (lines 432-439):
- Add `accessibilityServiceProvider: AccessibilityServiceProvider` parameter
- Pass to handler constructors

#### Action 1.5.3: Update `ElementActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt`

**Changes to all 5 tool handlers** (`FindElementsTool`, `ClickElementTool`, `LongClickElementTool`, `SetTextTool`, `ScrollToElementTool`):
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to each constructor
- Pass it to `getFreshTree()` calls (see Task 1.6)

**Update registration function** `registerElementActionTools()` (lines 523-532):
- Add `accessibilityServiceProvider: AccessibilityServiceProvider` parameter
- Pass to handler constructors

#### Action 1.5.4: Update `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt`

**Changes to `InputTextTool` (lines 32-116)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Pass to `getFreshTree()` and `findFocusedEditableNode()` calls (see Task 1.6)

**Changes to `ClearTextTool` (lines 123-194)**:
- Same as InputTextTool

**Changes to `PressKeyTool` (lines 207-370)**:
- Add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to constructor
- Pass to `findFocusedEditableNode()` calls (see Task 1.6)

**Update registration function** `registerTextInputTools()` (lines 375-381):
- Add `accessibilityServiceProvider: AccessibilityServiceProvider` parameter
- Pass to handler constructors

#### Action 1.5.5: Update `SystemActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/SystemActionTools.kt`

**Changes to the `executeSystemAction()` private helper function (lines 34-45)**:

This is a package-level private function that accesses `McpAccessibilityService.instance` at line 38 as a null-guard (throws `McpToolException.PermissionDenied` if null). It does NOT call any methods on the instance — it only checks for null to verify the accessibility service is available.

Change signature from:
```kotlin
private suspend fun executeSystemAction(
    actionName: String,
    action: suspend () -> Result<Unit>,
): JsonElement
```
to:
```kotlin
private suspend fun executeSystemAction(
    accessibilityServiceProvider: AccessibilityServiceProvider,
    actionName: String,
    action: suspend () -> Result<Unit>,
): JsonElement
```

Replace `McpAccessibilityService.instance` (line 38) with `accessibilityServiceProvider.isReady()` (or equivalent null-check pattern).

**Update the 5 callers** of `executeSystemAction()`:
- `PressBackHandler.execute()` (line 74) — pass `accessibilityServiceProvider`
- `PressHomeHandler.execute()` (line 119) — pass `accessibilityServiceProvider`
- `PressRecentsHandler.execute()` (line 164) — pass `accessibilityServiceProvider`
- `OpenNotificationsHandler.execute()` (line 209) — pass `accessibilityServiceProvider`
- `OpenQuickSettingsHandler.execute()` (line 254) — pass `accessibilityServiceProvider`

**Changes to handler constructors**: Each of the 5 handlers above must add `private val accessibilityServiceProvider: AccessibilityServiceProvider` to their constructor so they can pass it to `executeSystemAction()`.

**No changes to `GetDeviceLogsHandler`** (line 296): This handler does NOT access `McpAccessibilityService.instance` and does NOT call `executeSystemAction()`. Its constructor remains unchanged (no args).

**Update registration function** `registerSystemActionTools()` (lines 532-540):
- Add `accessibilityServiceProvider: AccessibilityServiceProvider` parameter
- Pass to the 5 handler constructors (PressBackHandler, PressHomeHandler, PressRecentsHandler, OpenNotificationsHandler, OpenQuickSettingsHandler)
- Do NOT pass to `GetDeviceLogsHandler` (unchanged)

---

### Task 1.6: Update shared helper functions

**Goal**: The shared helper functions `getFreshTree()` and `findFocusedEditableNode()` currently access `McpAccessibilityService.instance` directly. They need to receive `AccessibilityServiceProvider` as a parameter instead.

**Acceptance Criteria**:
- [x] `getFreshTree()` takes `AccessibilityServiceProvider` parameter instead of accessing singleton
- [x] `findFocusedEditableNode()` takes `AccessibilityServiceProvider` parameter instead of accessing singleton
- [x] All callers updated to pass the parameter
- [x] No compilation errors

#### Action 1.6.1: Update `getFreshTree()` in `ElementActionTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ElementActionTools.kt` (line 499)

Change signature from:
```kotlin
internal fun getFreshTree(treeParser: AccessibilityTreeParser): AccessibilityNodeData
```
to:
```kotlin
internal fun getFreshTree(
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
): AccessibilityNodeData
```

Replace `McpAccessibilityService.instance` (line 501) with `accessibilityServiceProvider`.

Update all callers in:
- `ElementActionTools.kt` (lines 63, 178, 232, 295, 362, 393) — 6 call sites
- `UtilityTools.kt` (lines 217, 342) — 2 call sites
- `TextInputTools.kt` (lines 47, 52, 134) — 3 call sites

Each caller must pass `accessibilityServiceProvider` (available from the handler's constructor).

#### Action 1.6.2: Update `findFocusedEditableNode()` in `TextInputTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/TextInputTools.kt` (line 397)

Change signature from:
```kotlin
internal fun findFocusedEditableNode(): AccessibilityNodeInfo?
```
to:
```kotlin
internal fun findFocusedEditableNode(
    accessibilityServiceProvider: AccessibilityServiceProvider,
): AccessibilityNodeInfo?
```

Replace `McpAccessibilityService.instance` (line 399) with `accessibilityServiceProvider`.

Update all callers in `TextInputTools.kt` (lines 60, 142, 249, 281, 311) — 5 call sites.

---

### Task 1.7: Update tool registration functions and `McpServerService`

**Goal**: The registration functions and `McpServerService.startServer()` need to pass the new interface dependencies to tool constructors.

**Acceptance Criteria**:
- [x] All 7 `registerXxxTools()` functions accept the required interfaces as parameters
- [x] `McpServerService.startServer()` passes the injected interfaces to registration functions
- [x] No compilation errors

#### Action 1.7.1: Update `McpServerService.kt` tool registration

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt`

Add `@Inject` fields for the new interfaces:
```kotlin
@Inject lateinit var actionExecutor: ActionExecutor
@Inject lateinit var accessibilityServiceProvider: AccessibilityServiceProvider
@Inject lateinit var screenCaptureProvider: ScreenCaptureProvider
@Inject lateinit var treeParser: AccessibilityTreeParser
@Inject lateinit var elementFinder: ElementFinder
```

Update the registration calls (lines 149-155) to pass these dependencies:
```kotlin
registerScreenIntrospectionTools(toolRegistry, treeParser, accessibilityServiceProvider, screenCaptureProvider)
registerSystemActionTools(toolRegistry, actionExecutor, accessibilityServiceProvider)
registerTouchActionTools(toolRegistry, actionExecutor)
registerGestureTools(toolRegistry, actionExecutor)
registerElementActionTools(toolRegistry, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)
registerTextInputTools(toolRegistry, treeParser, actionExecutor, accessibilityServiceProvider)
registerUtilityTools(toolRegistry, treeParser, elementFinder, accessibilityServiceProvider)
```

**Note**: Verify the exact parameter list for each registration function. The registration functions currently create their own instances of `ActionExecutor()`, `AccessibilityTreeParser()`, `ElementFinder()` — these local instantiations should be removed since the dependencies are now passed in.

**Verified**: Both `AccessibilityTreeParser` (line 67-69 of `AccessibilityTreeParser.kt`) and `ElementFinder` (line 62-64 of `ElementFinder.kt`) already have `@Inject constructor()`, so Hilt can inject them into `McpServerService` without any changes to those classes.

#### Action 1.7.2: Update each registration function signature

Update all 7 `registerXxxTools()` functions to:
1. Accept the required interfaces/dependencies as parameters
2. Remove the local `val actionExecutor = ActionExecutor()`, `val treeParser = AccessibilityTreeParser()`, `val elementFinder = ElementFinder()` instantiations
3. Pass the received dependencies to handler constructors

Files to update:
- `TouchActionTools.kt` — `registerTouchActionTools(toolRegistry, actionExecutor)`
- `GestureTools.kt` — `registerGestureTools(toolRegistry, actionExecutor)`
- `SystemActionTools.kt` — `registerSystemActionTools(toolRegistry, actionExecutor, accessibilityServiceProvider)`
- `ScreenIntrospectionTools.kt` — `registerScreenIntrospectionTools(toolRegistry, treeParser, accessibilityServiceProvider, screenCaptureProvider)`
- `ElementActionTools.kt` — `registerElementActionTools(toolRegistry, treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)`
- `TextInputTools.kt` — `registerTextInputTools(toolRegistry, treeParser, actionExecutor, accessibilityServiceProvider)`
- `UtilityTools.kt` — `registerUtilityTools(toolRegistry, treeParser, elementFinder, accessibilityServiceProvider)`

---

### Task 1.8: Update existing unit tests for new constructor signatures

**Goal**: Existing unit tests mock `ActionExecutor` as a class — they now need to mock the interface instead. Tests that mock `McpAccessibilityService.Companion` need to also/instead mock `AccessibilityServiceProvider`.

**Acceptance Criteria**:
- [x] All 23 existing unit test files compile and pass
- [x] Tests that previously mocked `ActionExecutor` (class) now mock `ActionExecutor` (interface) — this should be transparent since MockK mocks interfaces the same way
- [x] Tests that previously mocked `McpAccessibilityService.Companion` may need updates if tool constructors changed (additional `AccessibilityServiceProvider` parameter)

#### Action 1.8.1: Update tool test files

Review and update each test file that creates tool handler instances or directly instantiates refactored classes:

- `ActionExecutorTest.kt` — **Must be updated**: This test directly instantiates `ActionExecutor()` (line 26) and declares `private lateinit var executor: ActionExecutor` (line 21). After refactoring, `ActionExecutor` becomes an interface and cannot be instantiated. Changes:
  - Rename file to `ActionExecutorImplTest.kt`
  - Rename class to `ActionExecutorImplTest`
  - Change `executor = ActionExecutor()` to `executor = ActionExecutorImpl()`
  - Update `@DisplayName("ActionExecutor")` to `@DisplayName("ActionExecutorImpl")`
  - The type `private lateinit var executor: ActionExecutor` can remain as the interface type (since `ActionExecutorImpl` implements it), OR change to `ActionExecutorImpl` for clarity
  - The reflection-based `setServiceInstance()` helper (line 40) remains unchanged — `ActionExecutorImpl` still accesses `McpAccessibilityService.instance` directly
- `TouchActionToolsTest.kt` — `TapTool(actionExecutor)` → no change needed (same constructor, `actionExecutor` is now interface type)
- `GestureToolsTest.kt` — same as above
- `SystemActionToolsTest.kt` — **Different mocking pattern**: This test currently uses **reflection** (`field.set()`) to inject `McpAccessibilityService.instance` via a private `setAccessibilityServiceInstance()` helper method, unlike other test files which use `mockkObject()`. After the refactoring:
  - Remove the reflection-based `setAccessibilityServiceInstance()` helper entirely
  - Remove `mockService = mockk<McpAccessibilityService>(relaxed = true)` and `setAccessibilityServiceInstance(mockService)` from `@BeforeEach`
  - Remove `setAccessibilityServiceInstance(null)` from `@AfterEach`
  - Add `mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)` to `@BeforeEach`
  - Update the 5 handler constructors to pass both `mockActionExecutor` and `mockAccessibilityServiceProvider` (e.g., `PressBackHandler(mockActionExecutor, mockAccessibilityServiceProvider)`)
  - `GetDeviceLogsHandler()` constructor remains unchanged (no args)
- `ScreenIntrospectionToolsTest.kt` — **Different mocking pattern (similar to SystemActionToolsTest)**: This test uses **reflection** for BOTH `McpAccessibilityService.instance` (`setAccessibilityServiceInstance()`, line 65) AND `McpServerService.instance` (`setServerServiceInstance()`, line 74). After the refactoring:
  - Remove both reflection-based helpers (`setAccessibilityServiceInstance()` and `setServerServiceInstance()`) entirely
  - Remove reflection setup/teardown calls from `@BeforeEach`/`@AfterEach`
  - Add `mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)` and `mockScreenCaptureProvider = mockk<ScreenCaptureProvider>(relaxed = true)` to `@BeforeEach`
  - Update handler constructors: `GetAccessibilityTreeHandler(mockTreeParser, mockAccessibilityServiceProvider)`, `CaptureScreenshotHandler(mockScreenCaptureProvider)`, `GetCurrentAppHandler(mockAccessibilityServiceProvider)`, `GetScreenInfoHandler(mockAccessibilityServiceProvider)`
- `ElementActionToolsTest.kt` — uses `mockkObject(McpAccessibilityService.Companion)` (line 92) with `unmockkObject` in teardown (line 101). After refactoring:
  - Remove `mockkObject(McpAccessibilityService.Companion)` from `@BeforeEach` and `unmockkObject(McpAccessibilityService.Companion)` from `@AfterEach`
  - Remove `every { McpAccessibilityService.instance } returns mockService` (line 93)
  - Add `mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()` to `@BeforeEach`
  - Configure `every { mockAccessibilityServiceProvider.getRootNode() } returns mockRootNode` (replaces the old mock chain)
  - Update tool constructors: `FindElementsTool(mockTreeParser, mockElementFinder, mockAccessibilityServiceProvider)`, etc.
- `TextInputToolsTest.kt` — uses `mockkObject(McpAccessibilityService.Companion)` (line 65) with `unmockkObject` in teardown (line 74). Same pattern as ElementActionToolsTest:
  - Remove `mockkObject`/`unmockkObject` calls
  - Add `mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()` to `@BeforeEach`
  - Update tool constructors to pass `mockAccessibilityServiceProvider`
- `UtilityToolsTest.kt` — uses `mockkObject(McpAccessibilityService.Companion)` (line 85). Same pattern as ElementActionToolsTest:
  - Remove `mockkObject`/`unmockkObject` calls
  - Add `mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>()` to `@BeforeEach`
  - Update tool constructors to pass `mockAccessibilityServiceProvider`
  - Also update clipboard tool tests: replace `every { mockService.getSystemService(...) } returns mockClipboardManager` with `every { mockAccessibilityServiceProvider.getContext() } returns mockContext` and mock the context's `getSystemService()`

#### Action 1.8.2: Run unit tests

```bash
./gradlew :app:test
```

All tests must pass.

---

### Task 1.9: Run lint and verify

**Acceptance Criteria**:
- [x] `./gradlew ktlintCheck` passes
- [x] `./gradlew detekt` passes
- [x] `./gradlew :app:test` passes (all unit tests)

#### Action 1.9.1: Run ktlint, fix any formatting issues

```bash
./gradlew ktlintCheck
# If issues: ./gradlew ktlintFormat
```

#### Action 1.9.2: Run detekt

```bash
./gradlew detekt
```

#### Action 1.9.3: Run full unit test suite

```bash
./gradlew :app:test
```

---

## User Story 2: Replace Docker-Based Integration Tests with JVM-Based MCP Server Integration Tests

**As a** developer,
**I want** integration tests that test the full MCP server stack on JVM with mocked Android services,
**So that** I can verify HTTP→auth→JSON-RPC→tool dispatch→response without needing a Docker emulator, while running fast in CI.

### Acceptance Criteria
- [x] Docker-based integration test infrastructure is fully removed (script, androidTest, CI job)
- [x] JVM-based integration tests exist in `app/src/test/kotlin/.../integration/`
- [x] Integration tests use Ktor `testApplication` to test the full MCP server pipeline
- [x] Integration tests cover: authentication, health, initialize, tools/list, tool execution (all categories), error handling
- [x] CI workflow runs integration tests as part of the unit test job
- [x] Makefile `test-integration` target updated to run JVM tests
- [x] `./gradlew ktlintCheck detekt` passes
- [x] `./gradlew :app:test` passes (unit + integration tests)

---

### Task 2.1: Delete Docker-based integration test infrastructure

**Goal**: Remove all files and configuration related to the Docker-emulator-based integration tests.

**Acceptance Criteria**:
- [x] `scripts/run-integration-tests.sh` deleted
- [x] `app/src/androidTest/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivityTest.kt` deleted
- [x] `app/src/androidTest/kotlin/com/danielealbano/androidremotecontrolmcp/HiltTestRunner.kt` deleted
- [x] `app/src/androidTest/` directory is empty (or deleted entirely)
- [x] Android instrumented test dependencies removed from `app/build.gradle.kts` (lines 165-172)
- [x] `testInstrumentationRunner` config removed from `app/build.gradle.kts` if it references `HiltTestRunner`

#### Action 2.1.1: Delete files

Delete:
- `scripts/run-integration-tests.sh`
- `app/src/androidTest/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivityTest.kt`
- `app/src/androidTest/kotlin/com/danielealbano/androidremotecontrolmcp/HiltTestRunner.kt`
- Remove the `app/src/androidTest/` directory if empty

#### Action 2.1.2: Remove androidTest dependencies from `build.gradle.kts`

**File**: `app/build.gradle.kts`

Remove lines 165-172 (androidTest dependencies and related):
```kotlin
androidTestImplementation(libs.test.core)          // line 165
androidTestImplementation(libs.test.runner)         // line 166
androidTestImplementation(libs.test.rules)          // line 167
androidTestImplementation(libs.compose.ui.test.junit4)  // line 168
androidTestImplementation(libs.mockk.android)       // line 169
androidTestImplementation(libs.hilt.android.testing) // line 170
kspAndroidTest(libs.hilt.compiler)                  // line 171
debugImplementation(libs.compose.ui.test.manifest)  // line 172 — technically a debugImplementation, not androidTest, but it provides the Compose test activity manifest used only by Compose UI tests. Remove since we're removing all Compose UI tests.
```

Also remove `testInstrumentationRunner` from `defaultConfig` (line 30):
```kotlin
testInstrumentationRunner = "com.danielealbano.androidremotecontrolmcp.HiltTestRunner"
```
This references the `HiltTestRunner` class being deleted. Removing this line entirely is safe — Gradle falls back to the default `AndroidJUnitRunner`, and since we have no androidTest sources, it's never used.

---

### Task 2.2: Update CI workflow

**Goal**: Remove the `test-integration` job and merge integration test execution into the `test-unit` job.

**Acceptance Criteria**:
- [x] `test-integration` job removed from `.github/workflows/ci.yml`
- [x] `test-unit` job name updated to reflect it runs both unit and integration tests
- [x] `test-e2e` job's `needs` updated (was `needs: test-integration`, should now be `needs: test-unit`)
- [x] KVM enablement step removed (no longer needed for integration tests)
- [x] Upload artifact step for integration test results removed

#### Action 2.2.1: Update `.github/workflows/ci.yml`

**File**: `.github/workflows/ci.yml`

1. Remove the entire `test-integration` job (lines 76-109)
2. Rename `test-unit` job name from `"Unit Tests"` to `"Unit & Integration Tests"` (line 38)
3. Update `test-e2e` job's `needs: test-integration` → `needs: test-unit` (line 114)

---

### Task 2.3: Update Makefile

**Goal**: Update `test-integration` target to run JVM-based integration tests.

**Acceptance Criteria**:
- [x] `test-integration` Makefile target runs JVM tests (not Docker script)
- [x] Help text updated

#### Action 2.3.1: Update Makefile `test-integration` target

**File**: `Makefile`

Change lines 107-108 from:
```makefile
test-integration: ## Run integration tests (requires Docker)
	bash scripts/run-integration-tests.sh
```
to:
```makefile
test-integration: ## Run integration tests (JVM-based, no emulator required)
	$(GRADLE) :app:test --tests "com.danielealbano.androidremotecontrolmcp.integration.*"
```

Also update line 104-105 help text to reflect that `test-unit` now implicitly includes integration tests:
```makefile
test-unit: ## Run unit tests (includes integration tests since both are JVM-based)
	$(GRADLE) :app:test
```

**Note on redundancy**: Since `./gradlew :app:test` runs ALL tests under `app/src/test/` (including both unit and integration), running `make test-unit` already executes integration tests. The separate `test-integration` target exists for convenience (running only integration tests in isolation). When both run as part of `make test`, Gradle caching ensures integration tests are not re-executed.

---

### Task 2.4: Create integration test infrastructure

**Goal**: Set up the base infrastructure for JVM-based integration tests using Ktor `testApplication`.

**Acceptance Criteria**:
- [x] Integration test base helper exists that configures a Ktor `testApplication` with: McpServer routing, BearerTokenAuth, mocked tool registry
- [x] Helper provides methods to send JSON-RPC requests and parse responses
- [x] Tests are in package `com.danielealbano.androidremotecontrolmcp.integration`

#### Action 2.4.1: Create integration test helper

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt` (new file)

Create a helper object/class that:
1. Configures a Ktor `testApplication` with the same plugins as `McpServer.kt`:
   - `ContentNegotiation` with `json()`
   - `StatusPages` error handling
   - `BearerTokenAuthPlugin` on `/mcp` routes
2. Wires in a real `McpProtocolHandler` backed by a real `ToolRegistry`
3. Registers all 29 tools with **mocked** `ActionExecutor`, `AccessibilityServiceProvider`, `ScreenCaptureProvider`, `AccessibilityTreeParser`, `ElementFinder`
4. Provides a `sendJsonRpc(method, params)` helper that POSTs to the correct endpoint and returns parsed `JsonRpcResponse`
5. Provides constants: `TEST_BEARER_TOKEN`, test endpoint paths

Reference `BearerTokenAuthTest.kt` for the `testApplication` pattern.

The exact routing structure from `McpServer.kt` (lines 163-215) must be replicated:
- `GET /health` — unauthenticated (lines 165-178)
- `POST /mcp/v1/initialize` — authenticated (lines 187-191)
- `GET /mcp/v1/tools/list` — authenticated (lines 193-206)
- `POST /mcp/v1/tools/call` — authenticated (lines 208-212)

**Note on routing drift**: The test helper replicates the routing setup from `McpServer.kt`, meaning two copies of the routing configuration exist. If the server routing changes in the future, the test helper must be updated in sync. During implementation, consider whether extracting the routing configuration into a shared function (called by both `McpServer` and the test helper) is worthwhile to prevent drift. If not extracted, add a code comment in the test helper referencing the source in `McpServer.kt`.

---

### Task 2.5: Write authentication integration tests

**Goal**: Test bearer token authentication through the full HTTP stack.

**Acceptance Criteria**:
- [x] Tests verify: valid token → 200, invalid token → 401, missing token → 401, malformed header → 401
- [x] Tests verify health endpoint is accessible without authentication
- [x] All tests pass

#### Action 2.5.1: Create authentication integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/AuthIntegrationTest.kt` (new file)

Tests:
- `valid bearer token on tools/call returns 200`
- `invalid bearer token on tools/call returns 401`
- `missing Authorization header on tools/call returns 401`
- `malformed Authorization header on tools/call returns 401`
- `health endpoint accessible without bearer token`
- `health endpoint returns JSON with status healthy`

---

### Task 2.6: Write MCP protocol integration tests

**Goal**: Test JSON-RPC protocol handling through the full HTTP stack.

**Acceptance Criteria**:
- [x] Tests verify: initialize returns correct server info, tools/list returns all 29 tools with schemas
- [x] Tests verify JSON-RPC error handling: parse error, invalid request, method not found
- [x] All tests pass

#### Action 2.6.1: Create protocol integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpProtocolIntegrationTest.kt` (new file)

Tests:
- `initialize returns server info with correct protocol version`
- `tools/list returns all 29 registered tools`
- `tools/list includes correct input schemas for each tool`
- `unknown method returns JSON-RPC error -32601 method not found`
- `malformed JSON body returns JSON-RPC error -32700 parse error`
- `missing jsonrpc field returns JSON-RPC error -32600 invalid request`

---

### Task 2.7: Write tool execution integration tests

**Goal**: Test tool dispatch and execution through the full HTTP stack with mocked Android services. Cover all 7 tool categories with representative tests.

**Acceptance Criteria**:
- [x] At least 1-2 integration tests per tool category (7 categories, ~15 tests total)
- [x] Each test verifies: HTTP 200, correct JSON-RPC response structure, correct mock interaction
- [x] Parameter validation errors tested for at least 2 tools
- [x] All tests pass

#### Action 2.7.1: Create touch action integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/TouchActionIntegrationTest.kt` (new file)

Tests:
- `tap with valid coordinates calls actionExecutor.tap() and returns success`
- `tap with missing x coordinate returns JSON-RPC error -32602 invalid params`
- `swipe with valid coordinates calls actionExecutor.swipe() and returns success`

#### Action 2.7.2: Create element action integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ElementActionIntegrationTest.kt` (new file)

Tests:
- `find_elements returns matching elements from mocked tree`
- `click_element with valid node_id calls actionExecutor.clickNode() and returns success`
- `click_element with non-existent node_id returns element not found error -32002`

#### Action 2.7.3: Create gesture integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/GestureIntegrationTest.kt` (new file)

Tests:
- `pinch with valid parameters calls actionExecutor.pinch() and returns success`
- `pinch with invalid scale returns error -32602 invalid params`

#### Action 2.7.4: Create screen introspection integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ScreenIntrospectionIntegrationTest.kt` (new file)

Tests:
- `get_accessibility_tree returns parsed tree from mocked service`
- `capture_screenshot returns base64 image from mocked service`
- `get_current_app returns package and activity from mocked service`
- `capture_screenshot when permission denied returns error -32001`

#### Action 2.7.5: Create system action integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/SystemActionIntegrationTest.kt` (new file)

Tests:
- `press_home calls actionExecutor.pressHome() and returns success`
- `press_back calls actionExecutor.pressBack() and returns success`

#### Action 2.7.6: Create text input integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/TextInputIntegrationTest.kt` (new file)

Tests:
- `input_text with valid text calls actionExecutor and returns success`
- `input_text with missing text parameter returns error -32602`

#### Action 2.7.7: Create utility tools integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/UtilityIntegrationTest.kt` (new file)

Tests:
- `get_clipboard returns clipboard content from mocked service`
- `set_clipboard sets content and returns success`

---

### Task 2.8: Write error handling integration tests

**Goal**: Test that MCP error codes propagate correctly through the full HTTP stack.

**Acceptance Criteria**:
- [x] Each `McpToolException` subclass maps to the correct JSON-RPC error code in the HTTP response
- [x] Tests verify the complete error response structure (code, message)
- [x] All tests pass

#### Action 2.8.1: Create error handling integration test

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ErrorHandlingIntegrationTest.kt` (new file)

Tests:
- `permission denied exception returns JSON-RPC error code -32001`
- `element not found exception returns JSON-RPC error code -32002`
- `action failed exception returns JSON-RPC error code -32003`
- `timeout exception returns JSON-RPC error code -32004`
- `invalid params exception returns JSON-RPC error code -32602`
- `internal error exception returns JSON-RPC error code -32603`

These tests should configure mock `ActionExecutor` to throw specific `McpToolException` subclasses and verify the HTTP response contains the correct error code.

---

### Task 2.9: Run lint and all tests

**Acceptance Criteria**:
- [x] `./gradlew ktlintCheck` passes
- [x] `./gradlew detekt` passes
- [x] `./gradlew :app:test` passes (all unit + integration tests)

#### Action 2.9.1: Run ktlint, fix any formatting issues

```bash
./gradlew ktlintCheck
# If issues: ./gradlew ktlintFormat
```

#### Action 2.9.2: Run detekt

```bash
./gradlew detekt
```

#### Action 2.9.3: Run full test suite

```bash
./gradlew :app:test
```

---

## User Story 3: Update Documentation

**As a** developer,
**I want** the project documentation to reflect the new test architecture,
**So that** the documentation is accurate and up to date.

### Acceptance Criteria
- [x] `docs/PROJECT.md` updated: integration test section reflects JVM-based approach (no Docker/emulator)
- [x] `docs/PROJECT.md` updated: testing pyramid described correctly (unit, integration JVM, E2E emulator)
- [x] No stale references to Docker-based integration tests in any documentation

---

### Task 3.1: Update PROJECT.md

**Goal**: Update the project documentation to reflect the new integration test architecture.

**Acceptance Criteria**:
- [x] Integration test section describes JVM-based approach with Ktor `testApplication`
- [x] References to Docker-emulator-based integration tests removed
- [x] Testing pyramid documented: unit tests, JVM integration tests, E2E tests (Docker emulator)

#### Action 3.1.1: Update integration test documentation in PROJECT.md

Review and update all sections in `docs/PROJECT.md` that reference integration tests, `connectedAndroidTest`, Docker emulator for integration, `scripts/run-integration-tests.sh`, or `androidTest/`.

---

## User Story 4: Final Verification

**As a** developer,
**I want** to verify the entire implementation from the ground up,
**So that** nothing is missed, broken, or inconsistent.

### Acceptance Criteria
- [x] Every item in this plan has been implemented and checked off
- [x] Full build succeeds: `./gradlew build`
- [x] All linters pass: `./gradlew ktlintCheck detekt`
- [x] All unit + integration tests pass: `./gradlew :app:test`
- [x] No tool file references `McpAccessibilityService.instance` or `McpServerService.instance` directly
- [x] All 3 interfaces exist with correct Hilt bindings
- [x] Docker-based integration test infrastructure fully removed
- [x] CI workflow correct (no `test-integration` job, E2E `needs: test-unit`)
- [x] Makefile targets correct
- [x] Documentation up to date

---

### Task 4.1: Full verification checklist

#### Action 4.1.1: Verify no direct singleton access in tool files

Run:
```bash
grep -r "McpAccessibilityService\.instance" app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/
grep -r "McpServerService\.instance" app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/
```

Both must return **zero results**.

#### Action 4.1.2: Verify interfaces exist and have Hilt bindings

Confirm these files exist:
- `app/src/main/kotlin/.../services/accessibility/ActionExecutor.kt` (interface)
- `app/src/main/kotlin/.../services/accessibility/ActionExecutorImpl.kt` (implementation)
- `app/src/main/kotlin/.../services/accessibility/AccessibilityServiceProvider.kt` (interface)
- `app/src/main/kotlin/.../services/accessibility/AccessibilityServiceProviderImpl.kt` (implementation)
- `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProvider.kt` (interface)
- `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProviderImpl.kt` (implementation)

Confirm `AppModule.kt` contains `@Binds` for all 3.

Confirm `ActionExecutorTest.kt` has been renamed to `ActionExecutorImplTest.kt` and tests `ActionExecutorImpl`.

#### Action 4.1.3: Verify Docker infrastructure removed

Confirm these files do NOT exist:
- `scripts/run-integration-tests.sh`
- `app/src/androidTest/kotlin/.../MainActivityTest.kt`
- `app/src/androidTest/kotlin/.../HiltTestRunner.kt`

Confirm `build.gradle.kts` has no `androidTestImplementation` dependencies.

#### Action 4.1.4: Verify CI workflow

Confirm `.github/workflows/ci.yml`:
- No `test-integration` job
- `test-unit` job name includes "Integration"
- `test-e2e` needs `test-unit`

#### Action 4.1.5: Verify integration tests exist and cover all categories

Confirm these test files exist in `app/src/test/kotlin/.../integration/`:
- `McpIntegrationTestHelper.kt`
- `AuthIntegrationTest.kt`
- `McpProtocolIntegrationTest.kt`
- `TouchActionIntegrationTest.kt`
- `ElementActionIntegrationTest.kt`
- `GestureIntegrationTest.kt`
- `ScreenIntrospectionIntegrationTest.kt`
- `SystemActionIntegrationTest.kt`
- `TextInputIntegrationTest.kt`
- `UtilityIntegrationTest.kt`
- `ErrorHandlingIntegrationTest.kt`

#### Action 4.1.6: Run full build and all checks

```bash
./gradlew clean build
./gradlew ktlintCheck detekt
./gradlew :app:test
```

All must pass with zero warnings and zero failures.

#### Action 4.1.7: Verify Makefile targets

```bash
make test-integration  # Must run JVM integration tests
make test-unit         # Must run unit + integration tests
make lint              # Must pass
```

#### Action 4.1.8: Review all changes from ground up

Re-read every modified and new file to verify:
- No TODOs left
- No commented-out code
- No stale imports
- No references to deleted files
- Consistent coding style with existing codebase
- All new interfaces properly documented
