# Plan 22 — Natural Typing Tools via AccessibilityService IME

**Created**: 2026-02-16
**Status**: Pending
**Summary**: Implement four new natural text input MCP tools (`android_type_append_text`, `android_type_insert_text`, `android_type_replace_text`, `android_type_clear_text`) that use the Android AccessibilityService's `FLAG_INPUT_METHOD_EDITOR` + `AccessibilityInputConnection.commitText()` API (API 33+) for character-by-character typing that is indistinguishable from real IME input. Remove three existing programmatic text tools (`android_input_text`, `android_clear_text`, `android_set_text`) that use detectable `ACTION_SET_TEXT`. Raise `minSdk` from 26 to 33.

---

## Context & Decisions

The current text input tools (`android_input_text`, `android_clear_text`, `android_set_text`) use `AccessibilityNodeInfo.ACTION_SET_TEXT` which replaces the entire field content atomically. This is detectable by apps as non-human input because:
- It bypasses the `InputConnection` pipeline
- `TextWatcher` fires with the full text at once (not character by character)
- No IME events are triggered
- Apps can distinguish programmatic `ACTION_SET_TEXT` from real keyboard input

**Agreed approach: Option C — `FLAG_INPUT_METHOD_EDITOR` on AccessibilityService (API 33+)**

The AccessibilityService sets `FLAG_INPUT_METHOD_EDITOR` and overrides `onCreateInputMethod()` to get a parallel `AccessibilityInputConnection` to the focused text field. This allows calling `commitText()` character by character, which goes through the real `InputConnection` pipeline — triggering `TextWatcher`, auto-complete, and all normal text change events. The user's active IME (e.g., Gboard) is completely unaffected: no IME switching, no keyboard visibility changes, no IME change events.

**Key API facts verified:**
- `FLAG_INPUT_METHOD_EDITOR` added in API 33
- `InputMethod` class added in API 33
- `AccessibilityInputConnection.commitText()` delegates to the real `InputConnection`
- No keyboard switch events are fired — the user's IME stays active
- `getCurrentInputConnection()` returns `null` when no text field is focused, non-null when focused

**Tool decisions:**
- Four new tools: `android_type_append_text`, `android_type_insert_text`, `android_type_replace_text`, `android_type_clear_text`
- Remove three old tools: `android_input_text`, `android_clear_text`, `android_set_text`
- Keep `android_press_key` (key events, different purpose)
  - **Known limitation**: `PressKeyTool.pressDelete()` and `appendCharToFocused()` still use `ACTION_SET_TEXT` internally for DEL, TAB, and SPACE keys. These operations remain detectable as non-human input. Migrating `PressKeyTool` to the `InputConnection` pipeline is out of scope for this plan and can be addressed in a future iteration.
- `element_id` is mandatory on all four tools
- Typing speed: default 70ms, min 10ms, max 5000ms
- Typing speed variance: default 15ms, clamped to `[0, typing_speed]`
- Max text length: 2000 characters
- Max search text length: 10000 characters (bounded by `getSurroundingText` buffer)
- Focus flow: always click element → poll-retry for InputConnection readiness (max 500ms, 50ms interval) → use `setSelection()` to position cursor
- All typing operations serialized via `Mutex` — concurrent MCP requests are queued, not interleaved
- `AccessibilityInputConnection` methods dispatched on `Dispatchers.Main` (Android framework threading requirement)
- Unicode: text iteration uses code points (not `Char`), so emoji and supplementary characters are handled correctly
- Raise `minSdk` from 26 to 33 (Android 13 Tiramisu)

---

## New Tool Specifications

### `android_type_append_text`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | yes | — | Target element ID |
| `text` | string | yes | — | Text to type (max 2000 chars) |
| `typing_speed` | integer | no | 70 | Base delay between characters in ms (min: 10) |
| `typing_speed_variance` | integer | no | 15 | Random variance in ms, clamped to `[0, typing_speed]` |

**Flow:**
1. Click `element_id` to focus
2. Get current text length via `getSurroundingText()`
3. `setSelection(textLength, textLength)` — cursor at end
4. Type `text` char by char via `commitText(char, 1, null)` with timing delays
5. Return success

### `android_type_insert_text`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | yes | — | Target element ID |
| `text` | string | yes | — | Text to type (max 2000 chars) |
| `offset` | integer | yes | — | 0-based character offset for cursor position |
| `typing_speed` | integer | no | 70 | Base delay between characters in ms (min: 10) |
| `typing_speed_variance` | integer | no | 15 | Random variance in ms, clamped to `[0, typing_speed]` |

**Flow:**
1. Click `element_id` to focus
2. Get current text length via `getSurroundingText()`
3. Validate `offset` is in range `[0, textLength]` — error if out of range
4. `setSelection(offset, offset)` — cursor at specified position
5. Type `text` char by char via `commitText(char, 1, null)` with timing delays
6. Return success

### `android_type_replace_text`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | yes | — | Target element ID |
| `search` | string | yes | — | Text to find in the field (first occurrence) |
| `new_text` | string | yes | — | Replacement text to type (max 2000 chars) |
| `typing_speed` | integer | no | 70 | Base delay between characters in ms (min: 10) |
| `typing_speed_variance` | integer | no | 15 | Random variance in ms, clamped to `[0, typing_speed]` |

**Flow:**
1. Click `element_id` to focus
2. Get current text via `getSurroundingText()`
3. Find first occurrence of `search` in the text — error if not found
4. `setSelection(startIndex, endIndex)` to select the found text
5. Send DELETE key event (`KeyEvent(ACTION_DOWN, KEYCODE_DEL)` + `KeyEvent(ACTION_UP, KEYCODE_DEL)`) to delete selection
6. Type `new_text` char by char via `commitText(char, 1, null)` with timing delays (if `new_text` is non-empty)
7. Return success

### `android_type_clear_text`

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `element_id` | string | yes | — | Target element ID |

**Flow:**
1. Click `element_id` to focus
2. Check if field has text via `getSurroundingText()` — if empty, return success immediately
3. `performContextMenuAction(android.R.id.selectAll)` to select all text — check return value
4. Send DELETE key event (`KeyEvent(ACTION_DOWN, KEYCODE_DEL)` + `KeyEvent(ACTION_UP, KEYCODE_DEL)`) to delete selection — check return values
5. Return success

---

## Files Impacted

| File | Action |
|------|--------|
| `app/build.gradle.kts` | Modify: raise `minSdk` from 26 to 33 |
| `app/src/main/res/xml/accessibility_service_config.xml` | Modify: add `android:isInputMethodEditor="true"` and `flagInputMethodEditor` to flags |
| `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` | Modify: add `FLAG_INPUT_METHOD_EDITOR` to flags, override `onCreateInputMethod()`, add `InputMethod` subclass, expose input connection access |
| `app/src/main/kotlin/.../services/accessibility/TypeInputController.kt` | **Create**: interface wrapping `AccessibilityInputConnection` operations |
| `app/src/main/kotlin/.../services/accessibility/TypeInputControllerImpl.kt` | **Create**: implementation of `TypeInputController` |
| `app/src/main/kotlin/.../di/AppModule.kt` | Modify: add `TypeInputController` binding |
| `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt` | Modify: add `requireInt()` helper method |
| `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt` | **Rewrite**: remove `InputTextTool`, `ClearTextTool`; keep `PressKeyTool`; add four new type tools + shared utilities (Mutex, poll-retry, code point iteration) |
| `app/src/main/kotlin/.../mcp/tools/ElementActionTools.kt` | Modify: remove `SetTextTool` and its registration |
| `app/src/main/kotlin/.../services/mcp/McpServerService.kt` | Modify: inject `TypeInputController`, add it as parameter to `registerTextInputTools` call (keep existing params) |
| `app/src/test/kotlin/.../mcp/tools/TextInputToolsTest.kt` | **Rewrite**: remove `InputTextTool`/`ClearTextTool` tests; add tests for four new tools |
| `app/src/test/kotlin/.../mcp/tools/ElementActionToolsTest.kt` | Modify: remove `SetTextToolTests` nested class |
| `app/src/test/kotlin/.../integration/TextInputIntegrationTest.kt` | **Rewrite**: remove old integration tests; add integration tests for four new tools |
| `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt` | Modify: add `TypeInputController` to `MockDependencies`; update `registerAllTools` and `registerTextInputTools` |
| `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProviderImpl.kt` | Modify: remove API < 33 check (dead code after minSdk raise); remove stale `@SuppressLint("NewApi")` annotation |
| `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` | Modify: remove API < 33 compat code in `getScreenInfo()` and `canTakeScreenshot()` |
| `app/src/main/kotlin/.../utils/PermissionUtils.kt` | Modify: remove API < 33 check (notification permission) |
| `app/src/main/kotlin/.../ui/MainActivity.kt` | Modify: remove API < 33 guard on `requestNotificationPermission()` |
| `app/src/main/kotlin/.../ui/theme/Theme.kt` | Modify: remove `Build.VERSION_CODES.S` dead compat check (API 31 < minSdk 33) |
| `docs/MCP_TOOLS.md` | Modify: update Text Input Tools section (remove old tools, add new tools) |
| `docs/PROJECT.md` | Modify: update minSdk reference, text tools section |

---

## User Story 1: Raise minSdk to 33 and Clean Up Compat Code

**As a developer**, I need the minimum SDK raised to 33 so the new `FLAG_INPUT_METHOD_EDITOR` APIs are guaranteed available, and I need dead compat code removed to keep the codebase clean.

### Acceptance Criteria
- [ ] `minSdk` is 33 in `app/build.gradle.kts`
- [ ] All `Build.VERSION.SDK_INT` / `Build.VERSION_CODES` checks for API < 33 are removed (dead code)
- [ ] `@RequiresApi` annotations for API < 33 are removed where now redundant
- [ ] Project builds without errors or warnings: `./gradlew assembleDebug`
- [ ] All existing tests pass: `./gradlew :app:testDebugUnitTest`
- [ ] Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 1.1: Raise minSdk in build.gradle.kts

**Definition of Done**: `minSdk = 33` compiles, debug APK builds.

- [ ] **Action 1.1.1**: Update minSdk in `app/build.gradle.kts`

**File**: `app/build.gradle.kts`

**What changes**: Change `minSdk = 26` to `minSdk = 33` on line 25.

```diff
 defaultConfig {
     applicationId = "com.danielealbano.androidremotecontrolmcp"
-    minSdk = 26
+    minSdk = 33
     targetSdk = 34
     versionCode = versionCodeProp
     versionName = versionNameProp
 }
```

---

### Task 1.2: Remove API compat code in McpAccessibilityService

**Definition of Done**: No `Build.VERSION` checks for API < 33 remain in `McpAccessibilityService.kt`. Code compiles.

- [ ] **Action 1.2.1**: Simplify `getScreenInfo()` — remove the `else` branch for API < 30

**File**: `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt`

**What changes**: In `getScreenInfo()` (around line 140-156), the `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)` check is always true (API 30 is below minSdk 33). Remove the `else` branch and the `if` guard, keeping only the `WindowMetrics` path. Remove the `@Suppress("DEPRECATION")` annotation above `getScreenInfo()` since the deprecated `defaultDisplay.getRealMetrics` path is removed.

```diff
-    @Suppress("DEPRECATION")
     fun getScreenInfo(): ScreenInfo {
         val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
-        val width: Int
-        val height: Int
-
-        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
-            val metrics = windowManager.currentWindowMetrics
-            val bounds = metrics.bounds
-            width = bounds.width()
-            height = bounds.height()
-        } else {
-            val display = windowManager.defaultDisplay
-            val displayMetrics = android.util.DisplayMetrics()
-            display.getRealMetrics(displayMetrics)
-            width = displayMetrics.widthPixels
-            height = displayMetrics.heightPixels
-        }
+        val metrics = windowManager.currentWindowMetrics
+        val bounds = metrics.bounds
+        val width = bounds.width()
+        val height = bounds.height()

         val displayMetrics = resources.displayMetrics
```

- [ ] **Action 1.2.2**: Simplify `canTakeScreenshot()` — always returns true on API 33+

**File**: `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt`

**What changes**: `canTakeScreenshot()` currently checks `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`. With minSdk 33, this is always true. Simplify to always return `true`.

```diff
-    fun canTakeScreenshot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
+    fun canTakeScreenshot(): Boolean = true
```

- [ ] **Action 1.2.3**: Remove `@RequiresApi` from `takeScreenshotBitmap()`

**File**: `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt`

**What changes**: The `@RequiresApi(Build.VERSION_CODES.R)` annotation on `takeScreenshotBitmap()` is no longer needed since minSdk 33 > API 30.

```diff
-    @RequiresApi(Build.VERSION_CODES.R)
     suspend fun takeScreenshotBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? =
```

Also remove the `import androidx.annotation.RequiresApi` import if no other usages remain in the file.

---

### Task 1.3: Remove API compat code in ScreenCaptureProviderImpl

**Definition of Done**: No API < 33 checks remain in `ScreenCaptureProviderImpl.kt`. Code compiles.

- [ ] **Action 1.3.1**: Simplify `validateService()` — remove API < 30 check

**File**: `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProviderImpl.kt`

**What changes**: In `validateService()` (around line 71-74), the `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)` check is always false on API 33+. Remove this dead branch entirely.

```diff
 private fun validateService(): ServiceValidation {
-    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
-        return ServiceValidation.Invalid(
-            McpToolException.PermissionDenied(
-                "Screenshot capture requires Android 11 (API 30) or higher"
-            )
-        )
-    }
     val service = McpAccessibilityService.instance
```

- [ ] **Action 1.3.2**: Simplify `isAvailable()` — remove API check

**File**: `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProviderImpl.kt`

**What changes**: In `isAvailable()` (around line 97-98), remove the `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` check since it's always true.

```diff
-    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service.canTakeScreenshot()
+    return service.canTakeScreenshot()
```

- [ ] **Action 1.3.3**: Remove stale `@SuppressLint("NewApi")` annotation

**File**: `app/src/main/kotlin/.../services/screencapture/ScreenCaptureProviderImpl.kt`

**What changes**: The class-level `@SuppressLint("NewApi")` annotation (around line 35) was originally needed because `validateService()` had an API 30 guard. After removing that guard in Action 1.3.1 and raising `minSdk` to 33, this annotation is stale and misleading. Remove it along with its comment.

```diff
-@SuppressLint("NewApi") // API 30 guard in validateService()
 class ScreenCaptureProviderImpl
```

Also remove the `import android.annotation.SuppressLint` if no other usages remain in the file.

---

### Task 1.4: Remove API compat code in PermissionUtils and MainActivity

**Definition of Done**: No API < 33 compat code remains in `PermissionUtils.kt` or `MainActivity.kt`. Code compiles.

- [ ] **Action 1.4.1**: Simplify `isNotificationPermissionGranted()` in PermissionUtils

**File**: `app/src/main/kotlin/.../utils/PermissionUtils.kt`

**What changes**: The `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)` check (API 33) is always true. Remove the guard, keep only the `checkSelfPermission` path.

```diff
 fun isNotificationPermissionGranted(context: Context): Boolean =
-    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
-        ContextCompat.checkSelfPermission(
-            context,
-            Manifest.permission.POST_NOTIFICATIONS,
-        ) == PackageManager.PERMISSION_GRANTED
-    } else {
-        true
-    }
+    ContextCompat.checkSelfPermission(
+        context,
+        Manifest.permission.POST_NOTIFICATIONS,
+    ) == PackageManager.PERMISSION_GRANTED
```

- [ ] **Action 1.4.2**: Remove API guard in `requestNotificationPermission()` in MainActivity

**File**: `app/src/main/kotlin/.../ui/MainActivity.kt`

**What changes**: The `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)` check is always true. Remove the guard.

```diff
 private fun requestNotificationPermission() {
-    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
-        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
-    }
+    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
 }
```

---

### Task 1.5: Remove API compat code in Theme.kt

**Definition of Done**: No API < 33 compat code remains in `Theme.kt`. Code compiles.

- [ ] **Action 1.5.1**: Simplify dynamic color check — remove API < 31 check

**File**: `app/src/main/kotlin/.../ui/theme/Theme.kt`

**What changes**: The `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` check (API 31) is always true on minSdk 33. Simplify the `when` to use `dynamicColor` directly.

```diff
     val colorScheme =
         when {
-            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
+            dynamicColor -> {
                 val context = LocalContext.current
```

Also remove the `import android.os.Build` if no other usages remain in the file.

---

### Task 1.6: Remove API compat code in PressKeyTool

**Definition of Done**: No API < 33 compat code remains in `PressKeyTool`. Code compiles.

- [ ] **Action 1.6.1**: Simplify `pressEnter()` — remove API < 30 fallback

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: In `pressEnter()` (around line 257-287), the `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)` check is always true on API 33+. Remove the `else` branch (the `ACTION_SET_TEXT` fallback with newline append). Keep only the `ACTION_IME_ENTER` path.

```diff
 private fun pressEnter() {
     val focusedNode =
         findFocusedEditableNode(accessibilityServiceProvider)
             ?: throw McpToolException.ElementNotFound(
                 "No focused element found for ENTER key",
             )

     try {
-        val success =
-            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
-                focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
-            } else {
-                // Fallback for API < 30: append newline character
-                val currentText = focusedNode.text?.toString() ?: ""
-                val arguments =
-                    Bundle().apply {
-                        putCharSequence(
-                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
-                            currentText + "\n",
-                        )
-                    }
-                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
-            }
+        val success = focusedNode.performAction(
+            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
+        )
         if (!success) {
             throw McpToolException.ActionFailed("ENTER key action failed")
         }
     } finally {
         @Suppress("DEPRECATION")
         focusedNode.recycle()
     }
 }
```

---

### Task 1.7: Clean up unused imports

**Definition of Done**: No unused imports related to removed compat code. Linting passes.

- [ ] **Action 1.7.1**: Remove unused imports from all modified files

**Files**: All files modified in Tasks 1.2-1.6.

**What changes**: After removing compat code, remove any now-unused imports such as:
- `import android.os.Build` (if no remaining usages in that file)
- `import androidx.annotation.RequiresApi` (if no remaining usages)
- `import android.os.Bundle` (if removed from TextInputTools.kt due to pressEnter simplification — check if still needed by other methods in the file)

Run `./gradlew ktlintCheck` to identify unused imports, then `./gradlew ktlintFormat` to auto-fix.

---

### Task 1.8: Verify build and tests

**Definition of Done**: Debug APK builds. All existing tests pass. Linting clean.

- [ ] **Action 1.8.1**: Run full build and test suite

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
```

All must pass with zero errors and zero warnings.

---

## User Story 2: AccessibilityService IME Infrastructure

**As a developer**, I need the AccessibilityService configured with `FLAG_INPUT_METHOD_EDITOR` and a `TypeInputController` interface so the new typing tools have a testable, injectable way to call `commitText()`, `setSelection()`, `getSurroundingText()`, `performContextMenuAction()`, `sendKeyEvent()`, and `deleteSurroundingText()` on the `AccessibilityInputConnection`.

### Acceptance Criteria
- [ ] `accessibility_service_config.xml` has `android:isInputMethodEditor="true"` attribute
- [ ] `McpAccessibilityService.configureServiceInfo()` includes `FLAG_INPUT_METHOD_EDITOR` in flags
- [ ] `McpAccessibilityService` overrides `onCreateInputMethod()` returning a custom `InputMethod` subclass
- [ ] `TypeInputController` interface exists with all needed `AccessibilityInputConnection` operations
- [ ] `TypeInputControllerImpl` implementation wraps the real `AccessibilityInputConnection` via the `McpAccessibilityService` singleton
- [ ] `TypeInputController` is bound in Hilt `ServiceModule`
- [ ] `McpServerService` injects `TypeInputController` and passes it to tool registration
- [ ] `MockDependencies` in `McpIntegrationTestHelper` includes `TypeInputController`
- [ ] Project builds without errors: `./gradlew assembleDebug`
- [ ] Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 2.1: Update accessibility_service_config.xml

**Definition of Done**: Config XML declares IME capability.

- [ ] **Action 2.1.1**: Add `isInputMethodEditor` attribute

**File**: `app/src/main/res/xml/accessibility_service_config.xml`

**What changes**: Add `android:isInputMethodEditor="true"` attribute to the `<accessibility-service>` element.

```diff
 <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
     android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
     android:accessibilityFeedbackType="feedbackGeneric"
-    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
+    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagInputMethodEditor"
     android:canPerformGestures="true"
     android:canRetrieveWindowContent="true"
     android:canTakeScreenshot="true"
+    android:isInputMethodEditor="true"
     android:description="@string/accessibility_service_description"
     android:notificationTimeout="100"
     android:settingsActivity="com.danielealbano.androidremotecontrolmcp.ui.MainActivity" />
```

---

### Task 2.2: Update McpAccessibilityService for InputMethod

**Definition of Done**: Service creates a custom `InputMethod`, exposes access to it. Compiles.

- [ ] **Action 2.2.1**: Add `FLAG_INPUT_METHOD_EDITOR` to `configureServiceInfo()` and override `onCreateInputMethod()`

**File**: `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt`

**What changes**:

1. Add imports: `import android.accessibilityservice.AccessibilityServiceInfo`, `import android.accessibilityservice.InputMethod`
2. Add a class-level property `private var inputMethod: McpInputMethod? = null`
3. In `configureServiceInfo()`, add `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR` to the flags bitmask
4. Override `onCreateInputMethod()` to create and store a `McpInputMethod` instance
5. Add a `getInputMethod(): McpInputMethod?` accessor
6. Store the `McpInputMethod` reference in companion object (alongside `instance`) so `TypeInputControllerImpl` can access it
7. Clear `inputMethod` in `onDestroy()`

**Add inner class `McpInputMethod`** extending `InputMethod`:
```kotlin
class McpInputMethod(service: AccessibilityService) : InputMethod(service)
```

This subclass does not need to override any methods — the default `InputMethod` implementation provides `getCurrentInputConnection()`, `getCurrentInputEditorInfo()`, `getCurrentInputStarted()`, `onStartInput()`, `onFinishInput()`, `onUpdateSelection()` out of the box.

In `configureServiceInfo()`:
```diff
 flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
-    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
+    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
+    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
```

Override:
```kotlin
override fun onCreateInputMethod(): InputMethod {
    val method = McpInputMethod(this)
    inputMethod = method
    return method
}
```

In companion object, add:
```kotlin
@Volatile
var inputMethodInstance: McpInputMethod? = null
    private set
```

In `onCreateInputMethod()`, also set `inputMethodInstance = method`.
In `onDestroy()`, add `inputMethodInstance = null`.

---

### Task 2.3: Create TypeInputController interface

**Definition of Done**: Interface compiles with all needed methods.

- [ ] **Action 2.3.1**: Create `TypeInputController.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/TypeInputController.kt`

**What changes**: Create new file with the interface:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.KeyEvent
import android.view.inputmethod.SurroundingText

/**
 * Abstraction over AccessibilityInputConnection operations for natural text input.
 * Wraps the InputMethod API (API 33+) provided by the AccessibilityService
 * with FLAG_INPUT_METHOD_EDITOR.
 *
 * Implementations access the real AccessibilityInputConnection via
 * McpAccessibilityService's InputMethod instance.
 *
 * **Threading**: The AccessibilityInputConnection obtained from InputMethod is an
 * IPC proxy managed by the accessibility framework — it is NOT a View-bound
 * InputConnection. Methods can be called from any thread safely.
 * If runtime testing reveals issues, the implementation should wrap calls
 * in `withContext(Dispatchers.Main)`.
 *
 * **Concurrency**: All mutating operations are serialized via a file-level Mutex
 * (`typeOperationMutex` in TextInputTools.kt) at the tool layer, preventing
 * concurrent MCP requests from interleaving character commits.
 */
interface TypeInputController {
    /**
     * Returns true if the input connection is available
     * (accessibility service connected, text field focused, input started).
     */
    fun isReady(): Boolean

    /**
     * Commits a single character or text to the focused text field.
     * Delegates to AccessibilityInputConnection.commitText().
     *
     * @param text The text to commit.
     * @param newCursorPosition Cursor position relative to the committed text.
     *   1 = after the text (most common for typing).
     * @return true if the operation was dispatched successfully, false if input connection unavailable.
     */
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean

    /**
     * Sets the selection/cursor position in the focused text field.
     * If start == end, positions the cursor without selecting.
     *
     * @param start Selection start (0-based character index).
     * @param end Selection end (0-based character index).
     * @return true if the operation was dispatched successfully, false if input connection unavailable.
     */
    fun setSelection(start: Int, end: Int): Boolean

    /**
     * Gets the text surrounding the cursor in the focused text field.
     *
     * @param beforeLength Characters to retrieve before cursor.
     * @param afterLength Characters to retrieve after cursor.
     * @param flags 0 or InputConnection.GET_TEXT_WITH_STYLES.
     * @return SurroundingText, or null if unavailable.
     */
    fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText?

    /**
     * Performs a context menu action on the focused text field.
     * Used for select-all (android.R.id.selectAll).
     *
     * @param id The context menu action ID (e.g., android.R.id.selectAll).
     * @return true if the operation was dispatched successfully, false if input connection unavailable.
     */
    fun performContextMenuAction(id: Int): Boolean

    /**
     * Sends a key event to the focused text field.
     * Used for DELETE key after selection.
     *
     * @param event The KeyEvent to send.
     * @return true if the operation was dispatched successfully, false if input connection unavailable.
     */
    fun sendKeyEvent(event: KeyEvent): Boolean

    /**
     * Deletes text surrounding the cursor.
     *
     * @param beforeLength Characters to delete before cursor.
     * @param afterLength Characters to delete after cursor.
     * @return true if the operation was dispatched successfully, false if input connection unavailable.
     */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
}
```

---

### Task 2.4: Create TypeInputControllerImpl

**Definition of Done**: Implementation compiles, accesses `McpAccessibilityService.inputMethodInstance`.

- [ ] **Action 2.4.1**: Create `TypeInputControllerImpl.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/TypeInputControllerImpl.kt`

**What changes**: Create new file with the implementation:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.KeyEvent
import android.view.inputmethod.SurroundingText
import javax.inject.Inject

/**
 * Implementation of [TypeInputController] that delegates to the
 * [AccessibilityInputConnection] obtained from the [McpAccessibilityService]'s
 * [InputMethod] instance.
 *
 * All methods access the singleton [McpAccessibilityService.inputMethodInstance]
 * to get the current [AccessibilityInputConnection].
 *
 * **Threading**: The AccessibilityInputConnection is an IPC proxy managed by
 * the accessibility framework — NOT a View-bound InputConnection. Methods can
 * be called safely from any thread. If runtime testing reveals thread-safety
 * issues, wrap calls in `withContext(Dispatchers.Main)`.
 *
 * **Concurrency**: This class is stateless and safe to call from any thread.
 * Callers must use the file-level `typeOperationMutex` in the typing tools
 * to serialize operations and prevent interleaved character commits.
 */
class TypeInputControllerImpl
    @Inject
    constructor() : TypeInputController {
        private fun getInputConnection() =
            McpAccessibilityService.inputMethodInstance?.getCurrentInputConnection()

        override fun isReady(): Boolean =
            McpAccessibilityService.inputMethodInstance?.getCurrentInputStarted() == true &&
                getInputConnection() != null

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val ic = getInputConnection() ?: return false
            ic.commitText(text, newCursorPosition, null)
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            val ic = getInputConnection() ?: return false
            ic.setSelection(start, end)
            return true
        }

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? = getInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)

        override fun performContextMenuAction(id: Int): Boolean {
            val ic = getInputConnection() ?: return false
            ic.performContextMenuAction(id)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val ic = getInputConnection() ?: return false
            ic.sendKeyEvent(event)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val ic = getInputConnection() ?: return false
            ic.deleteSurroundingText(beforeLength, afterLength)
            return true
        }
    }
```

---

### Task 2.5: Register TypeInputController in Hilt

**Definition of Done**: `TypeInputController` is injectable via Hilt.

- [ ] **Action 2.5.1**: Add binding in `ServiceModule`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt`

**What changes**: Add a `@Binds` method in `ServiceModule`:

```diff
 abstract class ServiceModule {
+    @Binds
+    @Singleton
+    abstract fun bindTypeInputController(impl: TypeInputControllerImpl): TypeInputController
+
     @Binds
     @Singleton
     abstract fun bindActionExecutor(impl: ActionExecutorImpl): ActionExecutor
```

Add necessary imports for `TypeInputController` and `TypeInputControllerImpl`.

---

### Task 2.6: Inject TypeInputController in McpServerService

**Definition of Done**: `McpServerService` has `TypeInputController` injected and passes it to tool registration.

- [ ] **Action 2.6.1**: Add `TypeInputController` injection

**File**: `app/src/main/kotlin/.../services/mcp/McpServerService.kt`

**What changes**: Add injected field:

```diff
 @Inject lateinit var appManager: AppManager
+
+@Inject lateinit var typeInputController: TypeInputController
```

Update `registerAllTools()` to pass it — **keep `treeParser`** (it's still needed by the new tools for `getFreshTree()` in click-to-focus):

```diff
-    registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider, toolNamePrefix)
+    registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, toolNamePrefix)
```

Add import for `TypeInputController`.

Note: `treeParser` is still needed by the new type tools for `getFreshTree()` in click-to-focus. `actionExecutor` is still needed by `PressKeyTool` and the new type tools for `clickNode`. `accessibilityServiceProvider` is still needed by `PressKeyTool` and the new type tools.

---

### Task 2.7: Update McpIntegrationTestHelper

**Definition of Done**: `MockDependencies` includes `TypeInputController`. All tools register with the mock.

- [ ] **Action 2.7.1**: Add `TypeInputController` to MockDependencies and registration

**File**: `app/src/test/kotlin/.../integration/McpIntegrationTestHelper.kt`

**What changes**:

1. Add `typeInputController` to `MockDependencies`:
```diff
 data class MockDependencies(
     val actionExecutor: ActionExecutor,
     val accessibilityServiceProvider: AccessibilityServiceProvider,
     val screenCaptureProvider: ScreenCaptureProvider,
     val treeParser: AccessibilityTreeParser,
     val elementFinder: ElementFinder,
     val storageLocationProvider: StorageLocationProvider,
     val fileOperationProvider: FileOperationProvider,
     val appManager: AppManager,
+    val typeInputController: TypeInputController,
 )
```

2. Add to `createMockDependencies()`:
```diff
 fun createMockDependencies(): MockDependencies =
     MockDependencies(
         ...
         appManager = mockk(relaxed = true),
+        typeInputController = mockk(relaxed = true),
     )
```

3. Update `registerAllTools()` call to `registerTextInputTools` — **keep `treeParser`** (still needed by new tools):
```diff
     registerTextInputTools(
         server,
         deps.treeParser,
         deps.actionExecutor,
         deps.accessibilityServiceProvider,
+        deps.typeInputController,
         toolNamePrefix,
     )
```

Add import for `TypeInputController`.

---

### Task 2.8: Verify build

**Definition of Done**: Project builds and tests pass with the new infrastructure.

- [ ] **Action 2.8.1**: Run build and tests

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
```

Note: The `registerTextInputTools` signature has not changed in this user story — `typeInputController` is added as an additional parameter while keeping all existing params. All existing tools still receive their dependencies and the new `typeInputController` is simply ignored by `PressKeyTool` (it doesn't use it). The key validation here is that the new infrastructure code (TypeInputController, McpAccessibilityService changes, config XML, Hilt bindings) compiles and doesn't break existing tests.

---

## User Story 3: Implement New Type Tools & Remove Old Tools

**As a developer**, I need the four new natural typing tools implemented and the three old programmatic tools removed, so that all text input goes through the `InputConnection` pipeline.

### Acceptance Criteria
- [ ] `android_type_append_text` tool is registered and functional
- [ ] `android_type_insert_text` tool is registered and functional
- [ ] `android_type_replace_text` tool is registered and functional
- [ ] `android_type_clear_text` tool is registered and functional
- [ ] `android_input_text` tool is removed (no longer registered)
- [ ] `android_clear_text` tool is removed (no longer registered)
- [ ] `android_set_text` tool is removed (no longer registered)
- [ ] `android_press_key` tool is preserved and functional
- [ ] All four tools use `element_id` (mandatory), click to focus, `setSelection()` for cursor positioning
- [ ] `type_append_text` and `type_insert_text` type char by char with configurable speed/variance
- [ ] `type_replace_text` finds first occurrence, selects it, sends DELETE, types replacement
- [ ] `type_clear_text` performs select all + DELETE
- [ ] Max text length enforced at 2000 characters
- [ ] Typing speed minimum enforced at 10ms
- [ ] Variance clamped to `[0, typing_speed]`
- [ ] Cancellation supported via structured concurrency (coroutine cancellation stops typing loop)
- [ ] Mid-typing failure stops immediately and returns error
- [ ] Unit tests for all four tools pass
- [ ] Integration tests for all four tools pass
- [ ] PressKeyTool unit tests still pass
- [ ] Linting passes: `./gradlew ktlintCheck && ./gradlew detekt`
- [ ] All tests pass: `./gradlew :app:testDebugUnitTest`

---

### Task 3.1: Rewrite TextInputTools.kt — Remove old tools, add shared utilities

**Definition of Done**: Old `InputTextTool` and `ClearTextTool` classes removed. Shared typing utilities added. `PressKeyTool` preserved. File compiles.

- [ ] **Action 3.1.0**: Add `McpToolUtils.requireInt()` helper method

**File**: `app/src/main/kotlin/.../mcp/tools/McpToolUtils.kt`

**What changes**: Add a `requireInt()` method following the same pattern as `requireFloat()`. Place it after `requireFloat()`. This is needed by `TypeInsertTextTool` for the mandatory `offset` parameter.

```kotlin
/**
 * Extracts a required integer value from [params].
 *
 * Accepts JSON numbers only (not string-encoded). Rejects floats (e.g. 3.5).
 *
 * @throws McpToolException.InvalidParams if the parameter is missing, not a number,
 *   or not an integer.
 */
@Suppress("ThrowsCount")
fun requireInt(
    params: JsonObject?,
    name: String,
): Int {
    val element =
        params?.get(name)
            ?: throw McpToolException.InvalidParams("Missing required parameter: '$name'")
    val primitive =
        element as? JsonPrimitive
            ?: throw McpToolException.InvalidParams("Parameter '$name' must be a number")
    if (primitive.isString) {
        throw McpToolException.InvalidParams(
            "Parameter '$name' must be a number, got string: '${primitive.content}'",
        )
    }
    val doubleVal =
        primitive.content.toDoubleOrNull()
            ?: throw McpToolException.InvalidParams(
                "Parameter '$name' must be a number, got: '${primitive.content}'",
            )
    val intVal = doubleVal.toInt()
    if (doubleVal != intVal.toDouble()) {
        throw McpToolException.InvalidParams(
            "Parameter '$name' must be an integer, got: '${primitive.content}'",
        )
    }
    return intVal
}
```

Also add unit tests for `requireInt()` in `McpToolUtilsTest.kt`, following the same pattern as the existing `requireFloat()` tests:
- `requireInt returns integer value`
- `requireInt throws for missing param`
- `requireInt throws for string-encoded number`
- `requireInt throws for float value`

- [ ] **Action 3.1.1**: Remove `InputTextTool` class and `ClearTextTool` class

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Delete the `InputTextTool` class entirely and `ClearTextTool` class entirely. Keep `PressKeyTool` and `findFocusedEditableNode()` (`PressKeyTool` uses `findFocusedEditableNode()` in `pressEnter()`, `pressDelete()`, and `appendCharToFocused()`, so it must be kept).

- [ ] **Action 3.1.2**: Add shared typing constants, Mutex, and utility functions

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Add constants, a Mutex for concurrency control, and shared utility functions that will be used by the four new type tools. **No `clickToFocusAndVerify` standalone function** — the click-to-focus + poll-retry logic is inlined in each tool's `execute()` method since each tool injects its own dependencies.

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Shared constants for type tools
private const val MAX_TEXT_LENGTH = 2000
private const val DEFAULT_TYPING_SPEED_MS = 70
private const val DEFAULT_TYPING_VARIANCE_MS = 15
private const val MIN_TYPING_SPEED_MS = 10
private const val MAX_TYPING_SPEED_MS = 5000
private const val MAX_SURROUNDING_TEXT_LENGTH = 10000
private const val FOCUS_POLL_INTERVAL_MS = 50L
private const val FOCUS_POLL_MAX_MS = 500L

/**
 * Mutex serializing all type tool operations.
 * Prevents concurrent MCP requests from interleaving character commits.
 */
internal val typeOperationMutex = Mutex()

/**
 * Types text character by character using the given [TypeInputController],
 * with configurable speed and variance to simulate natural human typing.
 *
 * Iterates by **Unicode code points** (not Char), so supplementary characters
 * (emoji, CJK extensions) are committed as a single unit instead of being
 * split into surrogate pairs.
 *
 * Each code point is committed via [TypeInputController.commitText] with a delay of:
 *   typingSpeed + random(-effectiveVariance, +effectiveVariance)
 * where effectiveVariance = clamp(variance, 0, typingSpeed).
 *
 * Supports cancellation via structured concurrency — if the parent coroutine
 * is cancelled, the typing loop stops immediately at the next `delay()`.
 *
 * **Input connection loss detection**: Instead of checking `isReady()` before
 * every character (which can cause false negatives during brief framework state
 * transitions), this function relies solely on the `commitText()` return value.
 * If `commitText` returns `false`, the input connection has been lost and typing
 * stops immediately with an error.
 *
 * @param text The text to type.
 * @param typingSpeed Base delay between characters in ms.
 * @param typingSpeedVariance Variance in ms.
 * @param typeInputController The input controller to commit characters through.
 * @throws McpToolException.ActionFailed if commitText fails (input connection lost mid-typing).
 */
internal suspend fun typeCharByChar(
    text: String,
    typingSpeed: Int,
    typingSpeedVariance: Int,
    typeInputController: TypeInputController,
) {
    val effectiveVariance = typingSpeedVariance.coerceIn(0, typingSpeed)
    val codePointCount = text.codePointCount(0, text.length)
    var codePointIndex = 0
    var offset = 0

    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val charCount = Character.charCount(codePoint)
        val codePointStr = text.substring(offset, offset + charCount)

        val committed = typeInputController.commitText(codePointStr, 1)
        if (!committed) {
            throw McpToolException.ActionFailed(
                "Input connection lost during typing at position $codePointIndex of $codePointCount. " +
                    "commitText returned false.",
            )
        }

        offset += charCount
        codePointIndex++

        val delay = if (effectiveVariance > 0) {
            val variance = kotlin.random.Random.nextInt(-effectiveVariance, effectiveVariance + 1)
            (typingSpeed + variance).coerceAtLeast(1).toLong()
        } else {
            typingSpeed.toLong()
        }
        kotlinx.coroutines.delay(delay)
    }
}

/**
 * Validates and extracts typing speed parameters from tool arguments.
 * Uses [McpToolUtils.optionalInt] for consistent parameter parsing with
 * proper type checking (rejects string-encoded numbers, floats, etc.).
 *
 * @return Pair of (typingSpeed, typingSpeedVariance) in ms.
 * @throws McpToolException.InvalidParams if values are out of range.
 */
internal fun extractTypingParams(arguments: JsonObject?): Pair<Int, Int> {
    val typingSpeed = McpToolUtils.optionalInt(arguments, "typing_speed", DEFAULT_TYPING_SPEED_MS)

    if (typingSpeed < MIN_TYPING_SPEED_MS) {
        throw McpToolException.InvalidParams(
            "typing_speed must be >= $MIN_TYPING_SPEED_MS ms, got $typingSpeed",
        )
    }
    if (typingSpeed > MAX_TYPING_SPEED_MS) {
        throw McpToolException.InvalidParams(
            "typing_speed must be <= $MAX_TYPING_SPEED_MS ms, got $typingSpeed",
        )
    }

    val typingSpeedVariance = McpToolUtils.optionalInt(arguments, "typing_speed_variance", DEFAULT_TYPING_VARIANCE_MS)

    if (typingSpeedVariance < 0) {
        throw McpToolException.InvalidParams(
            "typing_speed_variance must be >= 0, got $typingSpeedVariance",
        )
    }

    return typingSpeed to typingSpeedVariance
}

/**
 * Validates that text length does not exceed the maximum.
 *
 * @throws McpToolException.InvalidParams if text exceeds MAX_TEXT_LENGTH.
 */
internal fun validateTextLength(text: String, paramName: String = "text") {
    if (text.length > MAX_TEXT_LENGTH) {
        throw McpToolException.InvalidParams(
            "$paramName exceeds maximum length of $MAX_TEXT_LENGTH characters (got ${text.length})",
        )
    }
}

/**
 * Polls for TypeInputController readiness after clicking an element to focus it.
 * Uses a poll-retry loop instead of a fixed delay to minimize unnecessary waiting
 * while still giving the framework time to establish the InputConnection.
 *
 * Uses wall-clock time (`System.currentTimeMillis()`) for accurate timeout
 * tracking, since `delay()` is a suspension point and may resume later than
 * the requested interval.
 *
 * @param typeInputController The controller to check.
 * @param elementId The element ID (for error message).
 * @throws McpToolException.ActionFailed if not ready after FOCUS_POLL_MAX_MS.
 */
internal suspend fun awaitInputConnectionReady(
    typeInputController: TypeInputController,
    elementId: String,
) {
    val deadline = System.currentTimeMillis() + FOCUS_POLL_MAX_MS
    while (System.currentTimeMillis() < deadline) {
        if (typeInputController.isReady()) return
        kotlinx.coroutines.delay(FOCUS_POLL_INTERVAL_MS)
    }
    throw McpToolException.ActionFailed(
        "Input connection not available after focusing element '$elementId'. " +
            "The element may not be an editable text field.",
    )
}
```

The click-to-focus pattern used by each tool's `execute()`:
```kotlin
// In each tool's execute(), wrapped in typeOperationMutex.withLock { ... }:
val tree = getFreshTree(treeParser, accessibilityServiceProvider)
val clickResult = actionExecutor.clickNode(elementId, tree)
clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

// Poll-retry loop for InputConnection readiness (max 500ms, 50ms interval)
awaitInputConnectionReady(typeInputController, elementId)
```

- [ ] **Action 3.1.3**: Update `registerTextInputTools()` function — remove old tool registrations, add new ones

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: The function signature already has `typeInputController` added (from Task 2.6). Now update the body to remove old tool registrations and add the four new tools:

```diff
 fun registerTextInputTools(
     server: Server,
     treeParser: AccessibilityTreeParser,
     actionExecutor: ActionExecutor,
     accessibilityServiceProvider: AccessibilityServiceProvider,
+    typeInputController: TypeInputController,
     toolNamePrefix: String,
 ) {
-    InputTextTool(treeParser, actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
-    ClearTextTool(treeParser, actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
+    TypeAppendTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+        .register(server, toolNamePrefix)
+    TypeInsertTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+        .register(server, toolNamePrefix)
+    TypeReplaceTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+        .register(server, toolNamePrefix)
+    TypeClearTextTool(treeParser, actionExecutor, accessibilityServiceProvider, typeInputController)
+        .register(server, toolNamePrefix)
     PressKeyTool(actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
 }
```

Note: `treeParser` is kept (already in the signature from the original code), `typeInputController` was added in Task 2.6. The `McpServerService` call was already updated in Task 2.6/3.9.

---

### Task 3.2: Implement TypeAppendTextTool

**Definition of Done**: Tool registered, functional, handles all parameters and edge cases.

- [ ] **Action 3.2.1**: Add `TypeAppendTextTool` class

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Add the following class (place after the shared utilities, before `PressKeyTool`). Key differences from original:
- Uses `McpToolUtils.requireString()` for parameter parsing consistency
- Wraps body in `typeOperationMutex.withLock { }` for concurrency safety
- Uses `awaitInputConnectionReady()` poll-retry instead of fixed delay
- Log does not leak text content, only length

```kotlin
class TypeAppendTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val text = McpToolUtils.requireString(arguments, "text")
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must be non-empty")
            }
            validateTextLength(text)

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            typeOperationMutex.withLock {
                // Click to focus
                val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                val clickResult = actionExecutor.clickNode(elementId, tree)
                clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                // Poll-retry for InputConnection readiness (max 500ms, 50ms interval)
                awaitInputConnectionReady(typeInputController, elementId)

                // Position cursor at end
                // Note: offset + text.length gives the total text length only if
                // getSurroundingText returns the complete field content. For fields
                // with >2*MAX_SURROUNDING_TEXT_LENGTH chars, this may undercount.
                // Given the 2000-char tool limit, this is not a practical concern.
                val surroundingText = typeInputController.getSurroundingText(
                    MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
                )
                val textLength = surroundingText?.let {
                    it.offset + it.text.length
                } ?: 0
                typeInputController.setSelection(textLength, textLength)

                // Type code point by code point
                typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)
            }

            Log.d(TAG, "type_append_text: typed ${text.length} chars on element '$elementId'")
            return McpToolUtils.textResult(
                "Typed ${text.length} characters at end of element '$elementId'",
            )
        }

        fun register(server: Server, toolNamePrefix: String) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Type text character by character at the end of a text field. " +
                    "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                    "Maximum text length: $MAX_TEXT_LENGTH characters. " +
                    "For text longer than $MAX_TEXT_LENGTH chars, call this tool multiple times — " +
                    "subsequent calls continue typing at the current cursor position.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("element_id") {
                            put("type", "string")
                            put("description", "Target element ID to type into")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Text to type (max $MAX_TEXT_LENGTH characters)")
                        }
                        putJsonObject("typing_speed") {
                            put("type", "integer")
                            put("description", "Base delay between characters in ms (default: $DEFAULT_TYPING_SPEED_MS, min: $MIN_TYPING_SPEED_MS, max: $MAX_TYPING_SPEED_MS)")
                        }
                        putJsonObject("typing_speed_variance") {
                            put("type", "integer")
                            put("description", "Random variance in ms, clamped to [0, typing_speed] (default: $DEFAULT_TYPING_VARIANCE_MS)")
                        }
                    },
                    required = listOf("element_id", "text"),
                ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeAppendTextTool"
            private const val TOOL_NAME = "type_append_text"
        }
    }
```

---

### Task 3.3: Implement TypeInsertTextTool

**Definition of Done**: Tool registered, functional, validates offset range.

- [ ] **Action 3.3.1**: Add `TypeInsertTextTool` class

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Add the following class. Uses `McpToolUtils.requireString()` for strings, `McpToolUtils.requireInt()` (added in Action 3.1.0) for the mandatory `offset` parameter, Mutex, poll-retry, sanitized log.

```kotlin
class TypeInsertTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val text = McpToolUtils.requireString(arguments, "text")
            if (text.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'text' must be non-empty")
            }
            validateTextLength(text)

            // Parse offset with proper type checking (see note above)
            val offset = McpToolUtils.requireInt(arguments, "offset")
            if (offset < 0) {
                throw McpToolException.InvalidParams("Parameter 'offset' must be >= 0, got $offset")
            }

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            typeOperationMutex.withLock {
                // Click to focus
                val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                val clickResult = actionExecutor.clickNode(elementId, tree)
                clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                // Poll-retry for InputConnection readiness
                awaitInputConnectionReady(typeInputController, elementId)

                // Validate offset against current text length
                val surroundingText = typeInputController.getSurroundingText(
                    MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
                )
                val textLength = surroundingText?.let {
                    it.offset + it.text.length
                } ?: 0

                if (offset > textLength) {
                    throw McpToolException.InvalidParams(
                        "offset ($offset) exceeds text length ($textLength) in element '$elementId'",
                    )
                }

                // Position cursor at offset
                typeInputController.setSelection(offset, offset)

                // Type character by character (code point aware)
                typeCharByChar(text, typingSpeed, typingSpeedVariance, typeInputController)
            }

            Log.d(TAG, "type_insert_text: typed ${text.length} chars at offset $offset on '$elementId'")
            return McpToolUtils.textResult(
                "Typed ${text.length} characters at offset $offset in element '$elementId'",
            )
        }

        fun register(server: Server, toolNamePrefix: String) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Type text character by character at a specific position in a text field. " +
                    "Uses natural InputConnection typing (indistinguishable from keyboard input). " +
                    "Maximum text length: $MAX_TEXT_LENGTH characters.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("element_id") {
                            put("type", "string")
                            put("description", "Target element ID to type into")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Text to type (max $MAX_TEXT_LENGTH characters)")
                        }
                        putJsonObject("offset") {
                            put("type", "integer")
                            put("description", "0-based character offset for cursor position. Must be within [0, current text length].")
                        }
                        putJsonObject("typing_speed") {
                            put("type", "integer")
                            put("description", "Base delay between characters in ms (default: $DEFAULT_TYPING_SPEED_MS, min: $MIN_TYPING_SPEED_MS, max: $MAX_TYPING_SPEED_MS)")
                        }
                        putJsonObject("typing_speed_variance") {
                            put("type", "integer")
                            put("description", "Random variance in ms, clamped to [0, typing_speed] (default: $DEFAULT_TYPING_VARIANCE_MS)")
                        }
                    },
                    required = listOf("element_id", "text", "offset"),
                ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeInsertTextTool"
            private const val TOOL_NAME = "type_insert_text"
        }
    }
```

---

### Task 3.4: Implement TypeReplaceTextTool

**Definition of Done**: Tool registered, functional, finds first occurrence, deletes and types replacement.

- [ ] **Action 3.4.1**: Add `TypeReplaceTextTool` class

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Add the following class:

```kotlin
/**
 * MCP tool: type_replace_text
 *
 * Finds the first occurrence of search text in a field, selects it,
 * deletes it via DELETE key event, then types the replacement text
 * character by character using the InputConnection pipeline.
 *
 * **Known limitation (TOCTOU race)**: There is an inherent time-of-check to
 * time-of-use race between `getSurroundingText()` (read) and `setSelection()`
 * (write). If the target app modifies the text field between these two calls
 * (e.g., via autocomplete, autofill, or background updates), the selection
 * indices may be wrong. The `typeOperationMutex` prevents concurrent MCP
 * requests from racing, but cannot prevent the target app itself from
 * modifying its own text. This is inherent to the approach and unavoidable
 * without framework-level atomic read-select operations.
 *
 * Flow:
 * 1. Click element_id to focus
 * 2. Get current text via getSurroundingText()
 * 3. Find first occurrence of search text — error if not found
 * 4. setSelection(startIndex, endIndex) to select found text
 * 5. Send DELETE key event to remove selection
 * 6. Type new_text char by char via commitText() (if non-empty)
 */
class TypeReplaceTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            val search = McpToolUtils.requireString(arguments, "search")
            if (search.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'search' must be non-empty")
            }
            // Validate search length — bounded by getSurroundingText buffer
            if (search.length > MAX_SURROUNDING_TEXT_LENGTH) {
                throw McpToolException.InvalidParams(
                    "search exceeds maximum length of $MAX_SURROUNDING_TEXT_LENGTH characters (got ${search.length})",
                )
            }

            val newText = McpToolUtils.requireString(arguments, "new_text")
            if (newText.isNotEmpty()) {
                validateTextLength(newText, "new_text")
            }

            val (typingSpeed, typingSpeedVariance) = extractTypingParams(arguments)

            typeOperationMutex.withLock {
                // Click to focus
                val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                val clickResult = actionExecutor.clickNode(elementId, tree)
                clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                // Poll-retry for InputConnection readiness
                awaitInputConnectionReady(typeInputController, elementId)

                // Get current text and find the search string
                val surroundingText = typeInputController.getSurroundingText(
                    MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
                ) ?: throw McpToolException.ActionFailed(
                    "Unable to read text from element '$elementId'",
                )

                val fullText = surroundingText.text.toString()
                val searchIndex = fullText.indexOf(search)
                if (searchIndex == -1) {
                    throw McpToolException.ElementNotFound(
                        "Search text (${search.length} chars) not found in element '$elementId'",
                    )
                }

                // Adjust index relative to the actual field (account for surroundingText offset)
                val absoluteStart = surroundingText.offset + searchIndex
                val absoluteEnd = absoluteStart + search.length

                // Select the found text
                typeInputController.setSelection(absoluteStart, absoluteEnd)

                // Delete the selection via DELETE key event
                typeInputController.sendKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL),
                )
                typeInputController.sendKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL),
                )

                // Type replacement text character by character (if non-empty)
                if (newText.isNotEmpty()) {
                    typeCharByChar(newText, typingSpeed, typingSpeedVariance, typeInputController)
                }
            }

            // Log only lengths, never actual text content (security: prevent sensitive data leaking)
            Log.d(TAG, "type_replace_text: replaced ${search.length} chars with ${newText.length} chars on '$elementId'")
            return McpToolUtils.textResult(
                "Replaced ${search.length} characters with ${newText.length} characters in element '$elementId'",
            )
        }

        fun register(server: Server, toolNamePrefix: String) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Find and replace text in a field by typing the replacement naturally. " +
                    "Finds the first occurrence of search text, deletes it, then types new_text " +
                    "character by character via InputConnection. " +
                    "Maximum new_text length: $MAX_TEXT_LENGTH characters. " +
                    "Returns error if search text is not found.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("element_id") {
                            put("type", "string")
                            put("description", "Target element ID")
                        }
                        putJsonObject("search") {
                            put("type", "string")
                            put("description", "Text to find in the field (first occurrence, max $MAX_SURROUNDING_TEXT_LENGTH chars)")
                        }
                        putJsonObject("new_text") {
                            put("type", "string")
                            put("description", "Replacement text to type (max $MAX_TEXT_LENGTH characters). Can be empty to just delete the found text.")
                        }
                        putJsonObject("typing_speed") {
                            put("type", "integer")
                            put("description", "Base delay between characters in ms (default: $DEFAULT_TYPING_SPEED_MS, min: $MIN_TYPING_SPEED_MS, max: $MAX_TYPING_SPEED_MS)")
                        }
                        putJsonObject("typing_speed_variance") {
                            put("type", "integer")
                            put("description", "Random variance in ms, clamped to [0, typing_speed] (default: $DEFAULT_TYPING_VARIANCE_MS)")
                        }
                    },
                    required = listOf("element_id", "search", "new_text"),
                ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeReplaceTextTool"
            private const val TOOL_NAME = "type_replace_text"
        }
    }
```

---

### Task 3.5: Implement TypeClearTextTool

**Definition of Done**: Tool registered, functional, performs select all + DELETE.

- [ ] **Action 3.5.1**: Add `TypeClearTextTool` class

**File**: `app/src/main/kotlin/.../mcp/tools/TextInputTools.kt`

**What changes**: Add the following class:

```kotlin
/**
 * MCP tool: type_clear_text
 *
 * Clears all text from a field naturally using InputConnection operations:
 * select all via context menu + DELETE key event.
 *
 * This is indistinguishable from a user performing select-all + backspace.
 *
 * If the field is already empty, returns success without performing any action.
 * All InputConnection return values are checked for robustness.
 */
class TypeClearTextTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val typeInputController: TypeInputController,
    ) {
        @Suppress("ThrowsCount")
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val elementId = McpToolUtils.requireString(arguments, "element_id")
            if (elementId.isEmpty()) {
                throw McpToolException.InvalidParams("Parameter 'element_id' must be non-empty")
            }

            typeOperationMutex.withLock {
                // Click to focus
                val tree = getFreshTree(treeParser, accessibilityServiceProvider)
                val clickResult = actionExecutor.clickNode(elementId, tree)
                clickResult.onFailure { e -> mapNodeActionException(e, elementId) }

                // Poll-retry for InputConnection readiness
                awaitInputConnectionReady(typeInputController, elementId)

                // Check if field has text — skip clear if already empty
                val surroundingText = typeInputController.getSurroundingText(
                    MAX_SURROUNDING_TEXT_LENGTH, MAX_SURROUNDING_TEXT_LENGTH, 0,
                )
                val textLength = surroundingText?.let { it.offset + it.text.length } ?: 0
                if (textLength == 0) {
                    Log.d(TAG, "type_clear_text: field already empty on '$elementId'")
                    return McpToolUtils.textResult("Text cleared from element '$elementId'")
                }

                // Select all text — check return value
                if (!typeInputController.performContextMenuAction(android.R.id.selectAll)) {
                    throw McpToolException.ActionFailed(
                        "Failed to select all text in element '$elementId' — input connection lost",
                    )
                }

                // Delete selection via DELETE key event — check return values
                if (!typeInputController.sendKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL),
                    )
                ) {
                    throw McpToolException.ActionFailed(
                        "Failed to send DELETE key (ACTION_DOWN) on element '$elementId' — input connection lost",
                    )
                }
                if (!typeInputController.sendKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL),
                    )
                ) {
                    throw McpToolException.ActionFailed(
                        "Failed to send DELETE key (ACTION_UP) on element '$elementId' — input connection lost",
                    )
                }
            }

            Log.d(TAG, "type_clear_text: cleared text on element '$elementId'")
            return McpToolUtils.textResult("Text cleared from element '$elementId'")
        }

        fun register(server: Server, toolNamePrefix: String) {
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description = "Clear all text from a field naturally using select-all + delete. " +
                    "Uses InputConnection operations (indistinguishable from user action).",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("element_id") {
                            put("type", "string")
                            put("description", "Target element ID to clear")
                        }
                    },
                    required = listOf("element_id"),
                ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TypeClearTextTool"
            private const val TOOL_NAME = "type_clear_text"
        }
    }
```

---

### Task 3.6: Remove SetTextTool from ElementActionTools

**Definition of Done**: `SetTextTool` class removed, no longer registered. Other element action tools unaffected.

- [ ] **Action 3.6.1**: Remove `SetTextTool` class from `ElementActionTools.kt`

**File**: `app/src/main/kotlin/.../mcp/tools/ElementActionTools.kt`

**What changes**: Delete the `SetTextTool` class (lines 276-340). Remove its registration from `registerElementActionTools()`:

```diff
 fun registerElementActionTools(...) {
     FindElementsTool(...).register(server, toolNamePrefix)
     ClickElementTool(...).register(server, toolNamePrefix)
     LongClickElementTool(...).register(server, toolNamePrefix)
-    SetTextTool(treeParser, actionExecutor, accessibilityServiceProvider).register(server, toolNamePrefix)
     ScrollToElementTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider)
         .register(server, toolNamePrefix)
 }
```

- [ ] **Action 3.6.2**: Remove `SetTextToolTests` from `ElementActionToolsTest.kt`

**File**: `app/src/test/kotlin/.../mcp/tools/ElementActionToolsTest.kt`

**What changes**: Remove the entire `SetTextToolTests` nested class (includes tests: `sets text on element`, `allows empty text to clear field`, `throws error for non-editable element`).

---

### Task 3.7: Rewrite TextInputToolsTest

**Definition of Done**: All old tool tests removed. New tests for all four type tools. PressKeyTool tests preserved.

- [ ] **Action 3.7.1**: Rewrite `TextInputToolsTest.kt`

**File**: `app/src/test/kotlin/.../mcp/tools/TextInputToolsTest.kt`

**What changes**: Remove `InputTextToolTests` and `ClearTextToolTests` nested classes. Keep `PressKeyToolTests`. Add four new nested test classes:

**`SharedUtilitiesTests`** (new nested class for shared functions):
- `typeCharByChar iterates by code points not chars` — provide emoji text (e.g., "A😀B"), verify `commitText` called 3 times (not 4)
- `typeCharByChar stops when commitText returns false` — `commitText()` returns false after N chars, verify ActionFailed thrown with correct count
- `typeCharByChar respects coroutine cancellation` — cancel parent scope mid-typing, verify loop stops
- `extractTypingParams uses defaults when not provided` — verify 70/15
- `extractTypingParams rejects typing_speed below minimum` — verify InvalidParams for 5ms
- `extractTypingParams rejects typing_speed above maximum` — verify InvalidParams for 6000ms
- `extractTypingParams rejects negative variance` — verify InvalidParams
- `validateTextLength accepts exactly 2000 chars` — verify no exception at boundary
- `validateTextLength rejects 2001 chars` — verify InvalidParams
- `awaitInputConnectionReady succeeds when ready immediately` — `isReady()` returns true first call
- `awaitInputConnectionReady succeeds after retry` — `isReady()` returns false, false, true
- `awaitInputConnectionReady fails after timeout` — `isReady()` always false, verify ActionFailed

**`TypeAppendTextToolTests`**:
- `appends text to element` — clicks element, verifies `setSelection` at end, verifies `commitText` called for each code point
- `throws error when text is missing` — validates required parameter
- `throws error when element_id is missing` — validates required parameter
- `throws error when text exceeds max length` — validates 2000 char limit
- `throws error when typing_speed below minimum` — validates min 10ms
- `throws error when typing_speed above maximum` — validates max 5000ms
- `throws error when input connection not ready after poll timeout` — verifies error after poll-retry exhausted
- `uses default typing speed and variance` — verifies defaults 70/15 when not provided
- `handles emoji text correctly` — types "Hi😀" → 3 commitText calls, not 4

**`TypeInsertTextToolTests`**:
- `inserts text at offset` — clicks element, verifies `setSelection` at specified offset
- `throws error when offset is missing` — validates required parameter
- `throws error when offset exceeds text length` — validates range
- `throws error when offset is negative` — validates >= 0

**`TypeReplaceTextToolTests`**:
- `replaces first occurrence of search text` — verifies find, select, delete, type flow
- `throws error when search text not found` — verifies ElementNotFound error
- `throws error when search is empty` — validates non-empty
- `throws error when search exceeds max length` — validates search length limit
- `handles empty new_text (delete only)` — verifies delete without typing
- `replaces only first occurrence when multiple exist` — verifies first match behavior
- `log does not contain search or new_text content` — verify sanitized logging

**`TypeClearTextToolTests`**:
- `clears text from element` — verifies select all + DELETE key events, checks return values
- `returns success for already empty field without sending keys` — `getSurroundingText` returns empty text, verify `performContextMenuAction` and `sendKeyEvent` NOT called
- `throws error when element_id is missing` — validates required parameter
- `throws error when input connection not ready after poll timeout` — verifies error after poll-retry
- `throws error when performContextMenuAction fails` — `performContextMenuAction` returns false, verify ActionFailed
- `throws error when sendKeyEvent fails` — `sendKeyEvent` returns false, verify ActionFailed

All tests mock `TypeInputController`, `ActionExecutor`, `AccessibilityTreeParser`, `AccessibilityServiceProvider`. Use `coEvery`/`every` from MockK. Verify calls with `verify {}`.

For mocking `TypeInputController`:
- `isReady()` returns `true` (or `false` for error/timeout tests)
- `commitText()` — returns `true` (or `false` for failure tests)
- `setSelection()` — returns `true`
- `getSurroundingText()` — return a mocked `SurroundingText` with test data
- `performContextMenuAction()` — returns `true`
- `sendKeyEvent()` — returns `true`

**`SurroundingText` mocking strategy**: `SurroundingText` is an Android framework class that cannot be constructed on JVM. Use `mockk<SurroundingText>()` with explicit `every` stubs:
```kotlin
val mockSurroundingText = mockk<SurroundingText> {
    every { text } returns "Hello World"
    every { offset } returns 0
    every { selectionStart } returns 5
    every { selectionEnd } returns 5
}
```
This works because the project has `unitTests.isReturnDefaultValues = true` in `build.gradle.kts`, and MockK can mock final Android classes on JVM with its inline mock maker.

---

### Task 3.8: Rewrite TextInputIntegrationTest

**Definition of Done**: Old integration tests removed. New integration tests for all four type tools via MCP protocol.

- [ ] **Action 3.8.1**: Rewrite `TextInputIntegrationTest.kt`

**File**: `app/src/test/kotlin/.../integration/TextInputIntegrationTest.kt`

**What changes**: Remove old `input_text` tests. Add integration tests that go through the full MCP HTTP → SDK → tool dispatch → mock execution path:

**`type_append_text with element_id returns success`**:
- Set up mock: `clickNode` returns success, `typeInputController.isReady()` returns true, `getSurroundingText()` returns text
- Call `client.callTool(name = "android_type_append_text", arguments = mapOf("element_id" to "node_edit", "text" to "Hello"))`
- Assert `isError != true`, response contains "Typed 5 characters"

**`type_append_text with missing text returns error`**:
- Call with empty arguments
- Assert `isError == true`

**`type_insert_text with valid offset returns success`**:
- Set up mock with text "Hello"
- Call with offset = 3, text = " World"
- Assert success

**`type_replace_text with found search text returns success`**:
- Set up mock with text containing "Hello"
- Call with search = "Hello", new_text = "World"
- Assert success

**`type_replace_text with missing search text returns error`**:
- Set up mock with text "Something"
- Call with search = "NotFound", new_text = "X"
- Assert `isError == true`

**`type_clear_text returns success`**:
- Set up mock
- Call with element_id
- Assert success

**`press_key still works after tool changes`**:
- Keep or add a test for `android_press_key` to ensure it wasn't broken

**`listTools verifies correct tool set`**:
- Call `client.listTools()` and assert:
  - `android_type_append_text` is present
  - `android_type_insert_text` is present
  - `android_type_replace_text` is present
  - `android_type_clear_text` is present
  - `android_press_key` is present
  - `android_input_text` is NOT present
  - `android_clear_text` is NOT present
  - `android_set_text` is NOT present

---

### Task 3.9: Verify McpServerService registration (no-op if Task 2.6 was correct)

**Definition of Done**: McpServerService passes all required dependencies including TypeInputController. This was already done in Task 2.6.

- [ ] **Action 3.9.1**: Verify registerAllTools() in McpServerService

**File**: `app/src/main/kotlin/.../services/mcp/McpServerService.kt`

**What changes**: Verify the call matches the expected signature (already updated in Task 2.6):

```kotlin
registerTextInputTools(server, treeParser, actionExecutor, accessibilityServiceProvider, typeInputController, toolNamePrefix)
```

The injection and import were already added in Task 2.6. No additional changes needed unless Task 2.6 was not fully applied.

---

### Task 3.10: Verify build and all tests

**Definition of Done**: Project compiles. All unit and integration tests pass. Lint clean.

- [ ] **Action 3.10.1**: Run full build, tests, and linting

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
```

All must pass with zero errors.

---

## User Story 4: Update Documentation

**As a developer**, I need the MCP tools documentation and project documentation updated to reflect the new tools, removed tools, and minSdk change.

### Acceptance Criteria
- [ ] `docs/MCP_TOOLS.md` — Text Input Tools section updated: old tools removed, new tools documented with parameters, flows, and examples
- [ ] `docs/MCP_TOOLS.md` — Overview table updated (tool counts, tool names)
- [ ] `docs/MCP_TOOLS.md` — Element Action Tools section updated: `android_set_text` removed
- [ ] `docs/PROJECT.md` — minSdk updated from 26 to 33
- [ ] `docs/PROJECT.md` — Text tools section updated
- [ ] Documentation is accurate and consistent with implementation
- [ ] Linting passes (no code changes in this story, but verify)

---

### Task 4.1: Update MCP_TOOLS.md — Overview and Table of Contents

**Definition of Done**: Overview table reflects new tool set.

- [ ] **Action 4.1.1**: Update overview table

**File**: `docs/MCP_TOOLS.md`

**What changes**: In the Overview section:
- Update Element Actions: remove `android_set_text` from the list
- Update Text Input: replace `android_input_text, android_clear_text, android_press_key` with `android_type_append_text, android_type_insert_text, android_type_replace_text, android_type_clear_text, android_press_key`
- Update tool counts accordingly

---

### Task 4.2: Update MCP_TOOLS.md — Element Action Tools section

**Definition of Done**: `android_set_text` removed from Element Action Tools section.

- [ ] **Action 4.2.1**: Remove `android_set_text` documentation

**File**: `docs/MCP_TOOLS.md`

**What changes**: Remove the entire `android_set_text` subsection from the Element Action Tools section.

---

### Task 4.3: Update MCP_TOOLS.md — Text Input Tools section

**Definition of Done**: All four new tools fully documented. Old tools removed.

- [ ] **Action 4.3.1**: Rewrite the Text Input Tools section

**File**: `docs/MCP_TOOLS.md`

**What changes**: Remove documentation for `android_input_text` and `android_clear_text`. Add full documentation for the four new tools:

For each tool, include:
- Tool name and description
- Parameters table (name, type, required, default, description)
- Request/response JSON examples
- Error cases
- Notes about natural InputConnection typing, max text length, typing speed

Document:
- `android_type_append_text`: types text at end, max 2000 chars, continuation across multiple calls
- `android_type_insert_text`: types text at offset, offset validation
- `android_type_replace_text`: find and replace first occurrence, DELETE + type flow, empty new_text for delete-only
- `android_type_clear_text`: select all + DELETE, no typing parameters

---

### Task 4.4: Update PROJECT.md

**Definition of Done**: minSdk and text tools references updated.

- [ ] **Action 4.4.1**: Update minSdk references

**File**: `docs/PROJECT.md`

**What changes**: Search for `minSdk`, `API 26`, `Android 8`, or similar references and update to `minSdk 33` / `Android 13 (Tiramisu)`.

- [ ] **Action 4.4.2**: Update text tools references

**File**: `docs/PROJECT.md`

**What changes**: If there are references to `input_text`, `clear_text`, `set_text` tools, update them to reference the new `type_*` tools. If there's a section about text input architecture, update it to describe the `FLAG_INPUT_METHOD_EDITOR` + `AccessibilityInputConnection` approach.

---

### Task 4.5: Verify documentation consistency

**Definition of Done**: No stale references to removed tools in any documentation.

- [ ] **Action 4.5.1**: Search for stale references

Search the entire `docs/` directory (including `ARCHITECTURE.md`, `MCP_TOOLS.md`, `PROJECT.md`, and any other docs) for:
- `input_text` (should only appear in commit history context, not as a current tool)
- `clear_text` (same)
- `set_text` (same)
- `ACTION_SET_TEXT` (should only appear in historical/context, not as current approach)
- `minSdk.*26` or `API 26` (should be updated to 33)

Fix any stale references found.

---

## User Story 5: Final Verification

**As a developer**, I need to verify the entire implementation from the ground up to ensure everything is correct, consistent, and complete.

### Acceptance Criteria
- [ ] All files modified/created in this plan are reviewed for correctness
- [ ] minSdk is 33 in `build.gradle.kts`
- [ ] No API < 33 compat code remains anywhere in the codebase (including Theme.kt)
- [ ] `accessibility_service_config.xml` has `isInputMethodEditor="true"` and `flagInputMethodEditor`
- [ ] `McpAccessibilityService` has `FLAG_INPUT_METHOD_EDITOR`, `onCreateInputMethod()`, `McpInputMethod` inner class
- [ ] `TypeInputController` interface has all required methods **returning Boolean** (except `isReady` and `getSurroundingText`)
- [ ] `TypeInputControllerImpl` correctly delegates to `AccessibilityInputConnection`, returns Boolean success/failure
- [ ] `TypeInputController` is bound in Hilt `ServiceModule`
- [ ] `McpServerService` injects and passes `TypeInputController`
- [ ] Four new tools are registered: `type_append_text`, `type_insert_text`, `type_replace_text`, `type_clear_text`
- [ ] Three old tools are completely removed: `input_text`, `clear_text`, `set_text`
- [ ] `PressKeyTool` is preserved and functional
- [ ] All tool parameter validation works (missing params, out of range, etc.)
- [ ] Typing speed defaults are 70ms/15ms
- [ ] Typing speed minimum is 10ms, maximum is 5000ms
- [ ] Max text length is 2000; search text bounded by `MAX_SURROUNDING_TEXT_LENGTH` (10000)
- [ ] Variance is clamped to `[0, typing_speed]`
- [ ] `typeCharByChar` iterates by Unicode code points, not `Char` (handles emoji/supplementary)
- [ ] All type tool `execute()` methods wrap body in `typeOperationMutex.withLock { }`
- [ ] Focus readiness uses poll-retry loop (max 500ms, 50ms interval), NOT fixed delay
- [ ] `commitText` return value checked — `false` throws `ActionFailed`
- [ ] Logs never contain actual text content — only lengths and element IDs
- [ ] Parameter parsing uses `McpToolUtils.requireString()` and `McpToolUtils.optionalInt()`
- [ ] `listTools` integration test verifies new tools present and old tools absent
- [ ] Dedicated unit tests exist for shared utilities (`typeCharByChar`, `extractTypingParams`, `validateTextLength`, `awaitInputConnectionReady`)
- [ ] Cancellation test exists for `typeCharByChar`
- [ ] Boundary test exists for exactly 2000 characters
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] No stale references in documentation
- [ ] Full build succeeds: `./gradlew assembleDebug`
- [ ] Full test suite passes: `./gradlew :app:testDebugUnitTest`
- [ ] Full lint passes: `./gradlew ktlintCheck && ./gradlew detekt`

---

### Task 5.1: Full code review

- [ ] **Action 5.1.1**: Review every file changed in this plan

Read each file listed in the "Files Impacted" table. Verify:
1. No TODO or placeholder code
2. No references to removed tools
3. Consistent code style with rest of codebase
4. Proper error handling in all tool methods
5. Log statements use correct tags
6. All imports are used (no unused imports)

---

### Task 5.2: Full test run

- [ ] **Action 5.2.1**: Run complete build and test pipeline

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
./gradlew detekt
```

All four commands must complete with zero errors and zero warnings.

---

### Task 5.3: Verify tool registration end-to-end

- [ ] **Action 5.3.1**: Verify via integration tests that all expected tools are available

Run the integration test suite. Verify that the MCP client can discover and call:
- `android_type_append_text`
- `android_type_insert_text`
- `android_type_replace_text`
- `android_type_clear_text`
- `android_press_key`

And that the following are NOT available:
- `android_input_text`
- `android_clear_text`
- `android_set_text`

This can be verified by calling `client.listTools()` in an integration test and asserting on the tool names.

---

### Task 5.4: Verify documentation completeness

- [ ] **Action 5.4.1**: Cross-check MCP_TOOLS.md against actual registered tools

Read `docs/MCP_TOOLS.md` and verify every documented tool exists in the code, and every registered tool is documented. No mismatches.

- [ ] **Action 5.4.2**: Cross-check PROJECT.md for consistency

Verify minSdk, tool references, and architecture descriptions match the implementation.
