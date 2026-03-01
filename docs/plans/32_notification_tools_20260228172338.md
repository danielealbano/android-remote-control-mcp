<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 32 — Notification Tools

Six new MCP tools for reading and interacting with Android notifications via `NotificationListenerService`. Requires a new Android service (`McpNotificationListenerService`), a new permission ("Notification access"), and UI updates for permission management.

Tools: `android_notification_list`, `android_notification_open`, `android_notification_dismiss`, `android_notification_snooze`, `android_notification_action`, `android_notification_reply`.

**ID scheme**: Notifications get a `notification_id` (SHA-256 hash of notification key, 6 hex chars). Actions get an `action_id` (SHA-256 hash of notification key + action index, 6 hex chars). `notification_list` returns both. Notification-level tools take `notification_id`, action-level tools take `action_id`.

---

## User Story 1: Implement NotificationListenerService and Provider

New Android service extending `NotificationListenerService` + `NotificationProvider` interface for testability. The service provides access to active notifications with structured data extraction and interaction capabilities (open, dismiss, snooze, execute actions, reply).

### Acceptance Criteria
- [x] `McpNotificationListenerService` extends `NotificationListenerService` with singleton pattern, lifecycle callbacks (`onDestroy`, `onLowMemory`, `onTrimMemory`)
- [x] `NotificationProvider` interface abstracts all notification operations
- [x] `NotificationProviderImpl` delegates to service singleton, uses non-deprecated `PackageManager` API
- [x] Notification data extracted: key, package, app name, title, text, big_text, sub_text, timestamp, is_ongoing, is_clearable, category, group_key, actions (title, accepts_text)
- [x] Hash IDs generated: `notification_id` = SHA-256(key)[0:3] hex, `action_id` = SHA-256(key + "::" + index)[0:3] hex
- [x] Interactions: open (contentIntent), dismiss (cancelNotification), snooze (snoozeNotification), action (action PendingIntent), reply (RemoteInput + action PendingIntent)
- [x] AndroidManifest updated with service declaration and `BIND_NOTIFICATION_LISTENER_SERVICE` permission
- [x] Hilt binding in `ServiceModule`
- [x] Unit tests for provider logic

---

### Task 1.1: Create `McpNotificationListenerService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/notifications/McpNotificationListenerService.kt` (create)

**Action 1.1.1**: Create service following `McpAccessibilityService` singleton pattern:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger

class McpNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Logger.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Logger.i(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        Logger.i(TAG, "Notification listener service destroying")
        instance = null
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.d(TAG, "onTrimMemory level=$level")
    }

    fun getNotifications(): Array<StatusBarNotification> = activeNotifications ?: emptyArray()

    fun dismissNotification(key: String) {
        cancelNotification(key)
    }

    fun dismissAllNotifications() {
        cancelAllNotifications()
    }

    fun snoozeNotificationByKey(key: String, durationMs: Long) {
        snoozeNotification(key, durationMs)
    }

    companion object {
        private const val TAG = "MCP:NotificationListener"

        @Volatile
        var instance: McpNotificationListenerService? = null
            private set
    }
}
```

**Definition of Done**:
- [x] Service extends `NotificationListenerService`
- [x] Singleton pattern with `@Volatile instance`
- [x] Lifecycle logging on connect/disconnect
- [x] `onDestroy()` clears singleton and calls `super.onDestroy()`
- [x] `onLowMemory()` and `onTrimMemory()` implemented with logging (following `McpAccessibilityService` pattern)

---

### Task 1.2: Create `NotificationProvider` Interface

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/notifications/NotificationProvider.kt` (create)

**Action 1.2.1**: Create interface and data models:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.notifications

data class NotificationData(
    val notificationId: String,      // 6 hex char hash of key
    val key: String,                 // raw StatusBarNotification key
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val timestamp: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String?,
    val groupKey: String?,
    val actions: List<NotificationActionData>,
)

data class NotificationActionData(
    val actionId: String,            // 6 hex char hash of key + "::" + index
    val index: Int,
    val title: String,
    val acceptsText: Boolean,        // true if action has RemoteInput
)

interface NotificationProvider {
    fun isReady(): Boolean
    suspend fun getNotifications(packageName: String? = null, limit: Int? = null): List<NotificationData>
    suspend fun openNotification(notificationId: String): Result<Unit>
    suspend fun dismissNotification(notificationId: String): Result<Unit>
    suspend fun snoozeNotification(notificationId: String, durationMs: Long): Result<Unit>
    suspend fun executeAction(actionId: String): Result<Unit>
    suspend fun replyToAction(actionId: String, text: String): Result<Unit>
}
```

**Definition of Done**:
- [x] Data classes capture all agreed notification fields
- [x] Interface methods cover all 6 tools' needs
- [x] Hash IDs are strings (6 hex chars)

---

### Task 1.3: Create `NotificationProviderImpl`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/notifications/NotificationProviderImpl.kt` (create)

**Action 1.3.1**: Create implementation:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

class NotificationProviderImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NotificationProvider {

    override fun isReady(): Boolean =
        McpNotificationListenerService.instance != null

    override suspend fun getNotifications(
        packageName: String?,
        limit: Int?,
    ): List<NotificationData> {
        val service = requireService()
        val notifications = service.getNotifications()
            .let { if (packageName != null) it.filter { sbn -> sbn.packageName == packageName } else it.toList() }
            .sortedByDescending { it.postTime }
            .let { if (limit != null) it.take(limit) else it }
        return notifications.map { toNotificationData(it) }
    }

    override suspend fun openNotification(notificationId: String): Result<Unit> {
        val sbn = findNotificationByHash(notificationId)
            ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
        val pendingIntent = sbn.notification.contentIntent
            ?: return Result.failure(IllegalStateException("Notification has no content intent"))
        return try {
            pendingIntent.send()
            Result.success(Unit)
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(e)
        }
    }

    override suspend fun dismissNotification(notificationId: String): Result<Unit> {
        val service = requireService()
        val sbn = service.getNotifications().firstOrNull {
            computeNotificationHash(it.key) == notificationId
        } ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
        return try {
            service.dismissNotification(sbn.key)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    override suspend fun snoozeNotification(notificationId: String, durationMs: Long): Result<Unit> {
        val service = requireService()
        val sbn = service.getNotifications().firstOrNull {
            computeNotificationHash(it.key) == notificationId
        } ?: return Result.failure(IllegalArgumentException("Notification not found: $notificationId"))
        return try {
            service.snoozeNotificationByKey(sbn.key, durationMs)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    override suspend fun executeAction(actionId: String): Result<Unit> {
        val (sbn, action) = findActionByHash(actionId)
            ?: return Result.failure(IllegalArgumentException("Action not found: $actionId"))
        val pendingIntent = action.actionIntent
            ?: return Result.failure(IllegalStateException("Action has no pending intent"))
        return try {
            pendingIntent.send()
            Result.success(Unit)
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(e)
        }
    }

    override suspend fun replyToAction(actionId: String, text: String): Result<Unit> {
        val (sbn, action) = findActionByHash(actionId)
            ?: return Result.failure(IllegalArgumentException("Action not found: $actionId"))
        val remoteInputs = action.remoteInputs
            ?: return Result.failure(IllegalStateException("Action does not accept text input"))
        val pendingIntent = action.actionIntent
            ?: return Result.failure(IllegalStateException("Action has no pending intent"))
        // Build reply intent with RemoteInput results
        val replyIntent = Intent()
        val resultsBundle = Bundle()
        for (remoteInput in remoteInputs) {
            resultsBundle.putCharSequence(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(remoteInputs, replyIntent, resultsBundle)
        return try {
            pendingIntent.send(context, 0, replyIntent)
            Result.success(Unit)
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(e)
        }
    }

    // --- Private helpers ---

    private fun requireService(): McpNotificationListenerService =
        McpNotificationListenerService.instance
            ?: throw IllegalStateException("Notification listener service not available")

    private fun toNotificationData(sbn: StatusBarNotification): NotificationData {
        val notification = sbn.notification
        val extras = notification.extras
        val pm = context.packageManager
        val appName = try {
            pm.getApplicationLabel(
                pm.getApplicationInfo(sbn.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            ).toString()
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val actions = notification.actions?.mapIndexed { index, action ->
            NotificationActionData(
                actionId = computeActionHash(sbn.key, index),
                index = index,
                title = action.title?.toString() ?: "",
                acceptsText = action.remoteInputs?.any { !it.isDataOnly } ?: false,
            )
        } ?: emptyList()
        return NotificationData(
            notificationId = computeNotificationHash(sbn.key),
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            timestamp = sbn.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = sbn.isClearable,
            category = notification.category,
            groupKey = sbn.groupKey,
            actions = actions,
        )
    }

    private fun findNotificationByHash(notificationId: String): StatusBarNotification? {
        val service = requireService()
        return service.getNotifications().firstOrNull {
            computeNotificationHash(it.key) == notificationId
        }
    }

    private fun findActionByHash(actionId: String): Pair<StatusBarNotification, Notification.Action>? {
        val service = requireService()
        for (sbn in service.getNotifications()) {
            val actions = sbn.notification.actions ?: continue
            for ((index, action) in actions.withIndex()) {
                if (computeActionHash(sbn.key, index) == actionId) {
                    return Pair(sbn, action)
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MCP:NotificationProvider"
        private const val HASH_BYTE_LENGTH = 3  // 3 bytes = 6 hex chars

        fun computeNotificationHash(key: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray())
                .take(HASH_BYTE_LENGTH)
                .joinToString("") { "%02x".format(it) }

        fun computeActionHash(key: String, actionIndex: Int): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$key::$actionIndex".toByteArray())
                .take(HASH_BYTE_LENGTH)
                .joinToString("") { "%02x".format(it) }
    }
}
```

**Constraint (non-obvious)**: `RemoteInput.addResultsToIntent()` requires the full `RemoteInput[]` array and an `Intent` for the extras bundle. The `PendingIntent.send(context, code, intent)` overload merges the intent extras with the pending intent's original intent.

**Definition of Done**:
- [x] `isReady()` checks singleton
- [x] `getNotifications()` extracts all fields, supports package filter and limit
- [x] Hash functions produce 6 hex chars (3 bytes SHA-256)
- [x] `openNotification()` fires `contentIntent` PendingIntent
- [x] `dismissNotification()` calls `cancelNotification(key)` with single `requireService()` call (no redundancy)
- [x] `snoozeNotification()` calls `snoozeNotification(key, durationMs)` with single `requireService()` call
- [x] `executeAction()` fires action's PendingIntent
- [x] `replyToAction()` uses `RemoteInput.addResultsToIntent()` + `PendingIntent.send()`
- [x] Lookup by hash iterates all notifications/actions and recomputes hash
- [x] Uses non-deprecated `PackageManager.getApplicationInfo(String, ApplicationInfoFlags)` API
- [x] Exception types narrowed: `SecurityException` + `IllegalArgumentException` (not generic `Exception`)

---

### Task 1.4: Update AndroidManifest

**File**: `app/src/main/AndroidManifest.xml` (modify)

**Action 1.4.1**: Add service declaration (after `McpAccessibilityService`):

```diff
+        <service
+            android:name=".services.notifications.McpNotificationListenerService"
+            android:exported="false"
+            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
+            <intent-filter>
+                <action android:name="android.service.notification.NotificationListenerService" />
+            </intent-filter>
+        </service>
```

**Constraint**: No new `<uses-permission>` needed — `BIND_NOTIFICATION_LISTENER_SERVICE` is a service-level permission, not an app-level one. The user grants access via Settings > Apps > Special app access > Notification access.

**Definition of Done**:
- [x] Service declared with correct permission and intent filter

---

### Task 1.5: Add Hilt Binding

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/di/AppModule.kt` (modify)

**Action 1.5.1**: Add to `ServiceModule`:
```diff
+    @Binds
+    @Singleton
+    abstract fun bindNotificationProvider(impl: NotificationProviderImpl): NotificationProvider
```

**Definition of Done**:
- [x] Binding added in `ServiceModule`

---

### Task 1.6: Unit Tests for `NotificationProviderImpl`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/notifications/NotificationProviderImplTest.kt` (create)

**Action 1.6.1**: Create test class using MockK. Mock `McpNotificationListenerService` singleton via `mockkObject` or by setting the companion `instance` field. Mock `StatusBarNotification`, `Notification`, `Notification.Action`, `PendingIntent`, `RemoteInput`.

Test cases:
- `isReady returns true when service instance is set`
- `isReady returns false when service instance is null`
- `getNotifications returns all notifications with correct data extraction`
- `getNotifications with packageName filter returns only matching`
- `getNotifications with limit returns at most N`
- `getNotifications sorted by postTime descending`
- `computeNotificationHash produces 6 hex chars`
- `computeActionHash produces 6 hex chars`
- `computeNotificationHash is deterministic for same key`
- `computeActionHash is deterministic for same key and index`
- `computeNotificationHash differs for different keys`
- `computeActionHash differs for different key or index`
- `openNotification finds notification by hash and sends contentIntent`
- `openNotification with unknown hash returns failure`
- `openNotification with no contentIntent returns failure`
- `dismissNotification calls cancelNotification with correct key`
- `dismissNotification with unknown hash returns failure`
- `snoozeNotification calls snoozeNotification with correct key and duration`
- `snoozeNotification with unknown hash returns failure`
- `executeAction finds action by hash and sends PendingIntent`
- `executeAction with unknown hash returns failure`
- `executeAction with null actionIntent returns failure`
- `replyToAction sends PendingIntent with RemoteInput results`
- `replyToAction with unknown hash returns failure`
- `replyToAction with no RemoteInput returns failure`
- `toNotificationData extracts title, text, bigText, subText from extras`
- `toNotificationData detects isOngoing from FLAG_ONGOING_EVENT`
- `toNotificationData maps actions with acceptsText from RemoteInput`
- `toNotificationData uses packageName as appName fallback when getApplicationInfo fails`
- `openNotification with CanceledException returns failure`
- `executeAction with CanceledException returns failure`
- `replyToAction with CanceledException returns failure`
- `dismissNotification with SecurityException returns failure`
- `snoozeNotification with SecurityException returns failure`
- `getNotifications returns empty list when no notifications`
- `getNotifications when service is null throws IllegalStateException`

**Definition of Done**:
- [x] All 36 test cases implemented and passing

---

## User Story 2: Add Permission Check UI

The notification listener requires manual permission grant via Settings. Add permission check to `PermissionUtils`, `MainViewModel`, and `PermissionsSection` UI following the existing pattern for accessibility/camera/microphone permissions.

### Acceptance Criteria
- [x] `PermissionUtils.isNotificationListenerEnabled()` checks if `McpNotificationListenerService` is enabled
- [x] `MainViewModel` exposes `isNotificationListenerEnabled` state
- [x] `PermissionsSection` shows notification listener permission row
- [x] Button opens notification listener settings
- [x] Unit tests for permission check

---

### Task 2.1: Add `isNotificationListenerEnabled` to `PermissionUtils`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtils.kt` (modify)

**Action 2.1.1**: Add method following `isAccessibilityServiceEnabled` pattern. Notification listener services are tracked in `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`:

```diff
+    fun isNotificationListenerEnabled(
+        context: Context,
+        serviceClass: Class<*>,
+    ): Boolean {
+        val expectedComponentName =
+            "${context.packageName}/${serviceClass.canonicalName}"
+
+        val enabledListeners =
+            Settings.Secure.getString(
+                context.contentResolver,
+                "enabled_notification_listeners",
+            ) ?: return false
+
+        return enabledListeners
+            .split(ENABLED_SERVICES_SEPARATOR)
+            .any { it.equals(expectedComponentName, ignoreCase = true) }
+    }
+
+    fun openNotificationListenerSettings(context: Context) {
+        val intent =
+            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
+                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+            }
+        context.startActivity(intent)
+    }
```

**Definition of Done**:
- [x] Check reads `enabled_notification_listeners` setting
- [x] Open settings method launches correct settings page

---

### Task 2.2: Update `MainViewModel`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt` (modify)

**Action 2.2.1**: Add state field:
```diff
+        private val _isNotificationListenerEnabled = MutableStateFlow(false)
+        val isNotificationListenerEnabled: StateFlow<Boolean> = _isNotificationListenerEnabled.asStateFlow()
```

**Action 2.2.2**: Add to `refreshPermissionStatus()`:
```diff
+            _isNotificationListenerEnabled.value =
+                PermissionUtils.isNotificationListenerEnabled(
+                    context,
+                    McpNotificationListenerService::class.java,
+                )
```

**Action 2.2.3**: Add import for `McpNotificationListenerService`.

**Definition of Done**:
- [x] State exposed and refreshed with other permissions

---

### Task 2.3: Update `PermissionsSection` UI

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/components/PermissionsSection.kt` (modify)

**Action 2.3.1**: Add parameter to `PermissionsSection` composable:
```diff
 fun PermissionsSection(
     isAccessibilityEnabled: Boolean,
     isNotificationPermissionGranted: Boolean,
+    isNotificationListenerEnabled: Boolean,
     isCameraPermissionGranted: Boolean,
     isMicrophonePermissionGranted: Boolean,
     onOpenAccessibilitySettings: () -> Unit,
     onRequestNotificationPermission: () -> Unit,
+    onOpenNotificationListenerSettings: () -> Unit,
     onRequestCameraPermission: () -> Unit,
     onRequestMicrophonePermission: () -> Unit,
```

**Action 2.3.2**: Add `PermissionRow` for notification listener (after the existing notification permission row):
```kotlin
PermissionRow(
    label = stringResource(R.string.permission_notification_listener),
    isEnabled = isNotificationListenerEnabled,
    buttonText = if (isNotificationListenerEnabled) {
        stringResource(R.string.permission_enabled)
    } else {
        stringResource(R.string.permission_enable)
    },
    onAction = onOpenNotificationListenerSettings,
    actionEnabled = !isNotificationListenerEnabled,
)
```

**Action 2.3.3**: Add string resource:
**File**: `app/src/main/res/values/strings.xml` (modify)
```diff
+    <string name="permission_notification_listener">Notification Listener</string>
```

**Action 2.3.4**: Update `PermissionsSectionPreview` composable to include:
```diff
+            isNotificationListenerEnabled = false,
+            onOpenNotificationListenerSettings = {},
```

**Definition of Done**:
- [x] Permission row added to UI
- [x] String resource added
- [x] Preview updated

---

### Task 2.4: Wire Permission into `HomeScreen` and `MainActivity`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/HomeScreen.kt` (modify)
**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivity.kt` (modify)

**Action 2.4.1**: Pass `isNotificationListenerEnabled` and `onOpenNotificationListenerSettings` through the composable hierarchy from `MainViewModel` to `PermissionsSection`, following the existing pattern for `isAccessibilityEnabled` / `onOpenAccessibilitySettings`.

The callback should call `PermissionUtils.openNotificationListenerSettings(context)`.

**Definition of Done**:
- [x] New permission state and callback wired from ViewModel through HomeScreen to PermissionsSection

---

### Task 2.5: Unit Tests for Permission Check

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/utils/PermissionUtilsTest.kt` (modify)

**Action 2.5.1**: Add tests following the existing `isAccessibilityServiceEnabled` test pattern:
- `isNotificationListenerEnabled returns true when service is in enabled_notification_listeners`
- `isNotificationListenerEnabled returns false when service is not in list`
- `isNotificationListenerEnabled returns false when setting is null`

**Definition of Done**:
- [x] 3 test cases added and passing

---

## User Story 3: Implement MCP Tool Handlers

`NotificationTools.kt` with 6 tool handlers.

### Acceptance Criteria
- [x] All 6 tools registered with schemas matching agreed spec
- [x] Permission check: all tools verify `notificationProvider.isReady()` before proceeding
- [x] Empty string validation on all ID/text parameters (consistent with `OpenAppHandler` pattern)
- [x] `Log.d` tracing in all handlers
- [x] `duration_ms` upper bound validation (7 days max)
- [x] `requireLong` follows `requireInt`/`optionalLong` pattern exactly (isString check, consistent error messages)
- [x] Unit tests for all handlers

---

### Task 3.1: Create `NotificationTools.kt`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NotificationTools.kt` (create)

**Constraint**: All handlers use `Log.d(TAG, ...)` for tracing — import `android.util.Log`. Follow `AppManagementTools.kt` pattern for imports and structure.

**Action 3.1.1**: `NotificationListHandler`:

```kotlin
class NotificationListHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val packageName = McpToolUtils.optionalString(arguments, "package_name", "")
            .ifEmpty { null }
        val limit = McpToolUtils.optionalInt(arguments, "limit", 0)
            .let { if (it <= 0) null else it }
        Log.d(TAG, "Executing notification_list, package=$packageName, limit=$limit")
        val notifications = notificationProvider.getNotifications(packageName, limit)
        val json = buildJsonObject {
            putJsonArray("notifications") {
                for (n in notifications) {
                    addJsonObject {
                        put("notification_id", n.notificationId)
                        put("package_name", n.packageName)
                        put("app_name", n.appName)
                        put("title", n.title)
                        put("text", n.text)
                        put("big_text", n.bigText)
                        put("sub_text", n.subText)
                        put("timestamp", n.timestamp)
                        put("is_ongoing", n.isOngoing)
                        put("is_clearable", n.isClearable)
                        put("category", n.category)
                        put("group_key", n.groupKey)
                        putJsonArray("actions") {
                            for (a in n.actions) {
                                addJsonObject {
                                    put("action_id", a.actionId)
                                    put("title", a.title)
                                    put("accepts_text", a.acceptsText)
                                }
                            }
                        }
                    }
                }
            }
            put("count", notifications.size)
        }
        return McpToolUtils.textResult(json.toString())
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_list",
            description = "List active notifications with structured data " +
                "(app, title, text, actions, timestamp). Returns notification_id for " +
                "each notification and action_id for each action button.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("package_name") {
                        put("type", "string")
                        put("description", "Filter by source app package name")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Maximum number of notifications to return")
                    }
                },
                required = listOf(),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_list"
        private const val TAG = "MCP:NotificationListHandler"
    }
}
```

**Action 3.1.2**: `NotificationOpenHandler`:

```kotlin
class NotificationOpenHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val notificationId = McpToolUtils.requireString(arguments, "notification_id")
        if (notificationId.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'notification_id' must not be empty")
        }
        Log.d(TAG, "Executing notification_open for id: $notificationId")
        val result = notificationProvider.openNotification(notificationId)
        return McpToolUtils.handleActionResult(result, "Notification opened")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_open",
            description = "Open/tap a notification (fires its content intent). " +
                "Use notification_id from notification_list.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notification_id") {
                        put("type", "string")
                        put("description", "The notification_id from notification_list")
                    }
                },
                required = listOf("notification_id"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_open"
        private const val TAG = "MCP:NotificationOpenHandler"
    }
}
```

**Action 3.1.3**: `NotificationDismissHandler`:

```kotlin
class NotificationDismissHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val notificationId = McpToolUtils.requireString(arguments, "notification_id")
        if (notificationId.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'notification_id' must not be empty")
        }
        Log.d(TAG, "Executing notification_dismiss for id: $notificationId")
        val result = notificationProvider.dismissNotification(notificationId)
        return McpToolUtils.handleActionResult(result, "Notification dismissed")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_dismiss",
            description = "Dismiss/remove a notification. Use notification_id from notification_list.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notification_id") {
                        put("type", "string")
                        put("description", "The notification_id from notification_list")
                    }
                },
                required = listOf("notification_id"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_dismiss"
        private const val TAG = "MCP:NotificationDismissHandler"
    }
}
```

**Action 3.1.4**: `NotificationSnoozeHandler`:

```kotlin
class NotificationSnoozeHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val notificationId = McpToolUtils.requireString(arguments, "notification_id")
        if (notificationId.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'notification_id' must not be empty")
        }
        val durationMs = McpToolUtils.requireLong(arguments, "duration_ms")
        if (durationMs <= 0) {
            throw McpToolException.InvalidParams("duration_ms must be positive")
        }
        if (durationMs > MAX_SNOOZE_DURATION_MS) {
            throw McpToolException.InvalidParams(
                "duration_ms must not exceed $MAX_SNOOZE_DURATION_MS (7 days)"
            )
        }
        Log.d(TAG, "Executing notification_snooze for id: $notificationId, duration: ${durationMs}ms")
        val result = notificationProvider.snoozeNotification(notificationId, durationMs)
        return McpToolUtils.handleActionResult(result, "Notification snoozed for ${durationMs}ms")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_snooze",
            description = "Snooze a notification for a duration. " +
                "The notification reappears after the specified time. " +
                "Use notification_id from notification_list.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("notification_id") {
                        put("type", "string")
                        put("description", "The notification_id from notification_list")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "integer")
                        put("description", "Snooze duration in milliseconds (must be positive, max 604800000 = 7 days)")
                    }
                },
                required = listOf("notification_id", "duration_ms"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_snooze"
        private const val TAG = "MCP:NotificationSnoozeHandler"
        private const val MAX_SNOOZE_DURATION_MS = 604_800_000L  // 7 days in milliseconds
    }
}
```

**Constraint (non-obvious)**: `McpToolUtils` does not have a `requireLong` method. Either add one to `McpToolUtils.kt` (following the `requireInt` pattern), or extract as `McpToolUtils.requireInt` and cast to Long — but `duration_ms` can exceed `Int.MAX_VALUE` (e.g., 24h = 86400000 fits Int, but larger values may not). Add `requireLong` to `McpToolUtils`.

**Action 3.1.5**: `NotificationActionHandler`:

```kotlin
class NotificationActionHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val actionId = McpToolUtils.requireString(arguments, "action_id")
        if (actionId.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'action_id' must not be empty")
        }
        Log.d(TAG, "Executing notification_action for id: $actionId")
        val result = notificationProvider.executeAction(actionId)
        return McpToolUtils.handleActionResult(result, "Notification action executed")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_action",
            description = "Execute a notification action button. " +
                "Use action_id from notification_list.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action_id") {
                        put("type", "string")
                        put("description", "The action_id from notification_list")
                    }
                },
                required = listOf("action_id"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_action"
        private const val TAG = "MCP:NotificationActionHandler"
    }
}
```

**Action 3.1.6**: `NotificationReplyHandler`:

```kotlin
class NotificationReplyHandler @Inject constructor(
    private val notificationProvider: NotificationProvider,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        if (!notificationProvider.isReady()) {
            throw McpToolException.PermissionDenied(
                "Notification listener not enabled. Enable in Settings > Notification access."
            )
        }
        val actionId = McpToolUtils.requireString(arguments, "action_id")
        if (actionId.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'action_id' must not be empty")
        }
        val text = McpToolUtils.requireString(arguments, "text")
        if (text.isEmpty()) {
            throw McpToolException.InvalidParams("Parameter 'text' must not be empty")
        }
        Log.d(TAG, "Executing notification_reply for id: $actionId")
        val result = notificationProvider.replyToAction(actionId, text)
        return McpToolUtils.handleActionResult(result, "Reply sent")
    }

    fun register(server: Server, toolNamePrefix: String) {
        server.addTool(
            name = "${toolNamePrefix}notification_reply",
            description = "Reply to a notification action that accepts text input " +
                "(e.g., messaging apps). Use action_id from notification_list " +
                "where accepts_text is true.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("action_id") {
                        put("type", "string")
                        put("description", "The action_id from notification_list (must have accepts_text=true)")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The reply text to send")
                    }
                },
                required = listOf("action_id", "text"),
            ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = "notification_reply"
        private const val TAG = "MCP:NotificationReplyHandler"
    }
}
```

**Action 3.1.7**: Registration function:

```kotlin
fun registerNotificationTools(
    server: Server,
    notificationProvider: NotificationProvider,
    toolNamePrefix: String,
) {
    NotificationListHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationOpenHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationDismissHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationSnoozeHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationActionHandler(notificationProvider).register(server, toolNamePrefix)
    NotificationReplyHandler(notificationProvider).register(server, toolNamePrefix)
}
```

**Definition of Done**:
- [x] All 6 handlers implemented with permission check
- [x] All handlers validate non-empty `notification_id`/`action_id`/`text` (consistent with `OpenAppHandler` pattern)
- [x] All handlers include `Log.d` tracing with `TAG` constant
- [x] `notification_snooze` validates `duration_ms` upper bound (7 days max)
- [x] Schemas match agreed spec
- [x] Registration function created

---

### Task 3.2: Add `requireLong` to `McpToolUtils`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtils.kt` (modify)

**Action 3.2.1**: Add `requireLong` method following the `requireInt`/`optionalLong` pattern exactly:

```kotlin
/**
 * Extracts a required long integer value from [params].
 *
 * Accepts JSON numbers only (not string-encoded). Rejects floats (e.g. 3.5).
 *
 * @throws McpToolException.InvalidParams if the parameter is missing, not a number,
 *   or not an integer.
 */
@Suppress("ThrowsCount")
fun requireLong(
    params: JsonObject?,
    name: String,
): Long {
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
    val longVal = doubleVal.toLong()
    if (doubleVal != longVal.toDouble()) {
        throw McpToolException.InvalidParams(
            "Parameter '$name' must be an integer, got: '${primitive.content}'",
        )
    }
    return longVal
}
```

**Definition of Done**:
- [x] `requireLong` added following `requireInt`/`optionalLong` pattern exactly
- [x] Rejects string-encoded numbers via `primitive.isString` check
- [x] Rejects fractional values via `doubleVal != longVal.toDouble()` check
- [x] Error messages consistent with existing `requireInt`/`requireFloat` (colon after "parameter", includes invalid value)

---

### Task 3.3: Unit Tests for `NotificationTools`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NotificationToolsTest.kt` (create)

**Action 3.3.1**: Create test class mocking `NotificationProvider`.

Test cases:
- `notification_list when not ready throws PermissionDenied`
- `notification_list returns JSON with notifications`
- `notification_list with package_name passes filter`
- `notification_list with limit passes limit`
- `notification_open when not ready throws PermissionDenied`
- `notification_open valid notification_id returns success`
- `notification_open missing notification_id throws InvalidParams`
- `notification_open unknown notification_id returns error`
- `notification_dismiss valid notification_id returns success`
- `notification_dismiss unknown notification_id returns error`
- `notification_snooze valid params returns success`
- `notification_snooze missing notification_id throws InvalidParams`
- `notification_snooze missing duration_ms throws InvalidParams`
- `notification_snooze non-positive duration_ms throws InvalidParams`
- `notification_action valid action_id returns success`
- `notification_action missing action_id throws InvalidParams`
- `notification_action unknown action_id returns error`
- `notification_reply valid params returns success`
- `notification_reply missing action_id throws InvalidParams`
- `notification_reply missing text throws InvalidParams`
- `notification_reply empty text throws InvalidParams`
- `notification_reply action without text input returns error`
- `notification_open empty notification_id throws InvalidParams`
- `notification_dismiss empty notification_id throws InvalidParams`
- `notification_snooze empty notification_id throws InvalidParams`
- `notification_snooze duration_ms exceeds max throws InvalidParams`
- `notification_action empty action_id throws InvalidParams`
- `notification_reply empty action_id throws InvalidParams`
- `notification_list returns empty JSON when no notifications`
- `notification_list with limit=0 returns all notifications`
- `notification_list with negative limit returns all notifications`

**Definition of Done**:
- [x] All 31 test cases implemented and passing

---

### Task 3.4: Unit Test for `requireLong` in `McpToolUtils`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/McpToolUtilsTest.kt` (modify)

**Action 3.4.1**: Add test cases for `requireLong` (follow `requireInt` test pattern):
- `requireLong valid integer returns Long`
- `requireLong missing param throws InvalidParams`
- `requireLong null params throws InvalidParams`
- `requireLong non-number throws InvalidParams`
- `requireLong decimal throws InvalidParams`
- `requireLong rejects string-encoded number`
- `requireLong accepts integer-equivalent float` (e.g., `5.0` → `5L`)

**Definition of Done**:
- [x] 7 test cases added and passing

---

## User Story 4: Wire into MCP Server and Integration Tests

### Acceptance Criteria
- [x] `NotificationProvider` injected in `McpServerService`
- [x] `McpIntegrationTestHelper` updated
- [x] Integration tests cover all 6 tools

---

### Task 4.1: Wire into `McpServerService`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` (modify)

**Action 4.1.1**: Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNotificationTools
+import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
```

**Action 4.1.2**: Add field:
```diff
+    @Inject lateinit var notificationProvider: NotificationProvider
```

**Action 4.1.3**: Add in `registerAllTools()`:
```diff
+        registerNotificationTools(server, notificationProvider, toolNamePrefix)
```

**Definition of Done**:
- [x] Import, field, and registration call added

---

### Task 4.2: Update `McpIntegrationTestHelper`

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/McpIntegrationTestHelper.kt` (modify)

**Action 4.2.1**: Add imports:
```diff
+import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationProvider
+import com.danielealbano.androidremotecontrolmcp.mcp.tools.registerNotificationTools
```

**Action 4.2.2**: Add to `MockDependencies`:
```diff
+    val notificationProvider: NotificationProvider,
```

**Action 4.2.3**: Add to `createMockDependencies()`:
```diff
+            notificationProvider = mockk(relaxed = true),
```

**Action 4.2.4**: Add to `registerAllTools()`:
```diff
+        registerNotificationTools(server, deps.notificationProvider, toolNamePrefix)
```

**Definition of Done**:
- [x] All four locations updated

---

### Task 4.3: Integration Tests

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/NotificationToolsIntegrationTest.kt` (create)

**Action 4.3.1**: Create test class following existing pattern. Mock `notificationProvider.isReady()` to return `true`.

Test cases:
- `notification_list returns JSON with notifications array`
- `notification_list with package_name filter passes through`
- `notification_list when not ready returns permission error`
- `notification_open valid notification_id returns success`
- `notification_open unknown id returns error`
- `notification_dismiss valid notification_id returns success`
- `notification_dismiss unknown id returns error`
- `notification_snooze valid params returns success`
- `notification_snooze missing duration_ms returns error`
- `notification_action valid action_id returns success`
- `notification_action unknown id returns error`
- `notification_reply valid params returns success`
- `notification_reply action without text input returns error`
- `notification_list with limit parameter passes through`
- `notification_open when not ready returns permission error`
- `notification_snooze non-positive duration_ms returns error`
- `notification_open with no contentIntent returns error`

**Definition of Done**:
- [x] All 17 integration test cases implemented and passing

---

## User Story 5: Update Documentation

### Acceptance Criteria
- [x] `MCP_TOOLS.md` has full section for Notification Tools, including security consideration about notification content exposure
- [x] `PROJECT.md` tool table updated
- [x] `ARCHITECTURE.md` updated (component diagram, permission model table, service lifecycle docs)
- [x] Tool count updated (47 → 53, accounting for Plan 31's 2 tools)

---

### Task 5.1: Update `MCP_TOOLS.md`

**File**: `docs/MCP_TOOLS.md` (modify)

**Action 5.1.1**: Add to overview table, ToC, and update tool count.

**Action 5.1.2**: Add `## 12. Notification Tools` section with full schema, request/response examples, and error cases for all 6 tools. Include:
- Note about notification listener permission requirement
- Explanation of `notification_id` and `action_id` hash scheme
- Note about `accepts_text` field indicating reply capability
- Note about max 3 action buttons per notification
- **Security consideration**: Notification tools expose notification content (titles, text, app names, action labels) to MCP clients. This is inherent to the feature's purpose. Ensure bearer token authentication is enabled in production to restrict access.

**Definition of Done**:
- [x] Full documentation for all 6 tools
- [x] Security consideration documented
- [x] Overview and count updated

---

### Task 5.2: Update `PROJECT.md`

**File**: `docs/PROJECT.md` (modify)

**Action 5.2.1**: Add `### 12. Notification Tools (6 tools)` section with tool table:

```
| Tool | Description | Required Params | Optional Params |
|------|-------------|-----------------|-----------------|
| `android_notification_list` | List active notifications with structured data | — | `package_name`, `limit` |
| `android_notification_open` | Open/tap a notification | `notification_id` | — |
| `android_notification_dismiss` | Dismiss a notification | `notification_id` | — |
| `android_notification_snooze` | Snooze a notification for a duration | `notification_id`, `duration_ms` | — |
| `android_notification_action` | Execute a notification action button | `action_id` | — |
| `android_notification_reply` | Reply to a notification with text | `action_id`, `text` | — |
```

**Action 5.2.2**: Update tool count. Add note about notification listener permission requirement.

**Definition of Done**:
- [x] Tool table added, count updated

---

### Task 5.3: Update `ARCHITECTURE.md`

**File**: `docs/ARCHITECTURE.md` (modify)

**Action 5.3.1**: Update component diagram (Mermaid) to include `McpNotificationListenerService` as a new service component with connection to MCP Server.

**Action 5.3.2**: Update permission model table to add a row for "Notification Listener" special permission (Settings > Notification access, not a runtime permission).

**Action 5.3.3**: Add `McpNotificationListenerService` to service lifecycle documentation, noting it follows the singleton pattern with `onListenerConnected`/`onListenerDisconnected`/`onDestroy` lifecycle.

**Action 5.3.4**: Update MCP tool categories table to include Notification Tools category (6 tools).

**Action 5.3.5**: Validate Mermaid diagrams with `mmdc`.

**Definition of Done**:
- [x] Component diagram updated and validated
- [x] Permission model table updated
- [x] Service lifecycle documented
- [x] Tool categories updated

---

### Task 5.4: Unit Test for `MainViewModel` Notification Listener State

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` (modify)

**Action 5.4.1**: Add test case following existing ViewModel test patterns:
- `refreshPermissionStatus updates isNotificationListenerEnabled when service is enabled`
- `refreshPermissionStatus updates isNotificationListenerEnabled when service is not enabled`

**Definition of Done**:
- [x] 2 test cases added and passing

---

## User Story 6: Final Verification

### Acceptance Criteria
- [ ] All quality gates pass
- [ ] Implementation matches this plan exactly

---

### Task 6.1: Codebase-Wide Verification

**Action 6.1.1**: Grep verification:
- `grep -rn "notification_list\|notification_open\|notification_dismiss\|notification_snooze\|notification_action\|notification_reply" app/src/main/` → all 6 tools registered
- `grep -rn "NotificationProvider" app/src/main/` → interface, impl, binding, injection
- `grep -rn "McpNotificationListenerService" app/src/main/` → service, manifest, singleton access
- `grep -rn "registerNotificationTools" app/src/` → McpServerService and McpIntegrationTestHelper
- `grep -rn "isNotificationListenerEnabled" app/src/main/` → PermissionUtils, MainViewModel, UI

**Action 6.1.2**: Quality gates:
- `make lint`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew build`

**Action 6.1.3**: Manual review: verify every file matches this plan — service lifecycle, provider implementation, hash functions, tool schemas, permission UI, test coverage, documentation.

**Definition of Done**:
- [ ] No stale or missing references
- [ ] All quality gates pass
- [ ] Manual review confirms consistency
