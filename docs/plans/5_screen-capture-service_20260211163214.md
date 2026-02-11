# Plan 5: ScreenCaptureService & Screenshot Capture

**Branch**: `feat/screen-capture-service`
**PR Title**: `Plan 5: ScreenCaptureService and screenshot capture`
**Created**: 2026-02-11 16:32:14

---

## Overview

This plan implements the ScreenCaptureService as a bound foreground service that manages MediaProjection for on-demand screenshot capture with JPEG encoding and base64 output. It also adds MediaProjection permission handling to the UI layer (MainActivity/MainViewModel) and a screen info method to the AccessibilityService.

### Dependencies on Previous Plans

- **Plan 1**: Project scaffolding, AndroidManifest.xml (ScreenCaptureService declared with `foregroundServiceType="mediaProjection"`), notification channel string resources, notification icon (`ic_notification.xml`), Hilt setup
- **Plan 2**: Data models (`ServerConfig`, `ServerStatus`), `SettingsRepository`, `Logger` utility, `PermissionUtils`, Hilt DI bindings in `AppModule`
- **Plan 3**: `MainActivity` with Compose UI, `MainViewModel`, `PermissionsSection` composable, `HomeScreen`, theme setup
- **Plan 4**: `McpAccessibilityService` with singleton pattern, `AccessibilityTreeParser`, `ElementFinder`, `ActionExecutor`

### Package Base

`com.danielealbano.androidremotecontrolmcp`

### Path References

- **Source root**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Test root**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`
- **Resources**: `app/src/main/res/`

---

## User Story 1: ScreenCaptureService with MediaProjection-Based Screenshot Capture

**As a** developer integrating an MCP client with this Android device
**I want** a reliable, thread-safe ScreenCaptureService that captures screenshots on demand via MediaProjection and returns them as base64-encoded JPEG data
**So that** MCP tool calls (e.g., `capture_screenshot`) can retrieve the current screen state for AI-driven device control.

### Acceptance Criteria / Definition of Done (High Level)

- [ ] `ScreenshotData` data class exists with `format`, `data` (base64), `width`, `height` fields and is `@Serializable`
- [ ] `ScreenshotEncoder` utility class converts `Image` to `Bitmap`, encodes `Bitmap` to JPEG, and base64-encodes the result
- [ ] `ScreenshotEncoder` handles row padding correctly when `rowStride != width * pixelStride`
- [ ] `ScreenshotEncoder` recycles bitmaps after encoding to prevent memory leaks
- [ ] `ScreenCaptureService` extends `Service()` and implements the bound service pattern with `LocalBinder`
- [ ] `ScreenCaptureService` calls `startForeground()` within 5 seconds of `onStartCommand()` with a valid notification
- [ ] `ScreenCaptureService` notification uses channel `screen_capture_channel` (already defined in `strings.xml` from Plan 1, channel created in `McpApplication.onCreate()` from Plan 6)
- [ ] `ScreenCaptureService` MUST be started via `startForegroundService()` BEFORE `bindService()` is called (from McpServerService in Plan 6), because `bindService()` alone does NOT trigger `onStartCommand()`, and `startForeground()` must be called within 5 seconds of `startForegroundService()`. The correct sequence in McpServerService is: (1) `startForegroundService(intent)` (2) `bindService(intent, connection, flags)`.
- [ ] `ScreenCaptureService.setupMediaProjection(resultCode, data)` delegates to `MediaProjectionManager.setupProjection()` using the activity result
- [ ] `ScreenCaptureService.isMediaProjectionActive()` returns correct boolean state
- [ ] `ScreenCaptureService.captureScreenshot(quality)` captures a screenshot with Mutex-based thread safety
- [ ] `ScreenCaptureService` handles `onDestroy()` correctly: stops MediaProjection, releases ImageReader/VirtualDisplay, cancels coroutines, logs shutdown
- [ ] `ScreenCaptureService` handles `onLowMemory()` and `onTrimMemory()` by releasing bitmap caches
- [ ] `ScreenCaptureService` registers `MediaProjection.Callback` to handle projection stop events gracefully
- [ ] `ScreenInfo` data class exists with `width`, `height`, `densityDpi`, `orientation` fields and is `@Serializable`
- [ ] `McpAccessibilityService.getScreenInfo()` returns correct screen metrics and orientation
- [ ] `MainActivity` registers `ActivityResultLauncher` for MediaProjection permission
- [ ] `MainViewModel` exposes `isMediaProjectionGranted: StateFlow<Boolean>` and stores MediaProjection result
- [ ] The notification channel for screen capture (`screen_capture_channel`) is created centrally in `McpApplication.onCreate()` (Plan 6, Task 6.6.3) before any service calls `startForeground()`. ScreenCaptureService does NOT create its own channel.
- [ ] Unit tests exist and pass for `ScreenshotEncoder` (image-to-bitmap, JPEG encoding, base64 encoding, quality parameter, bitmap recycling)
- [ ] Unit tests exist and pass for `ScreenCaptureService` (projection state, capture failure when not active, mutex concurrency)
- [ ] `make lint` passes with no warnings or errors
- [ ] `make test-unit` passes with all tests green
- [ ] `make build` succeeds without errors or warnings

### Commit Strategy

| # | Message | Files |
|---|---------|-------|
| 1 | `feat: add ScreenshotData model and ScreenshotEncoder utility` | `ScreenshotData.kt`, `ScreenshotEncoder.kt` |
| 2 | `feat: add MediaProjectionManager and ScreenCaptureService with screenshot capture` | `MediaProjectionManager.kt`, `ScreenCaptureService.kt` (replaces stub) |
| 3 | `feat: add screen info to AccessibilityService and MediaProjection handling to UI` | Updated `McpAccessibilityService.kt`, `ScreenInfo.kt`, updated `MainActivity.kt`, updated `MainViewModel.kt`, updated `strings.xml` |
| 4 | `test: add unit tests for screenshot encoder and capture service` | `ScreenshotEncoderTest.kt`, `ScreenCaptureServiceTest.kt` |

---

### Task 5.1: Create ScreenshotData Model

**Description**: Create the `ScreenshotData` data class that represents a captured screenshot ready for MCP response serialization.

**Acceptance Criteria**:
- [ ] `ScreenshotData` is a `@Serializable` data class in package `services.screencapture`
- [ ] Fields: `format: String` (defaults to `"jpeg"`), `data: String` (base64-encoded JPEG), `width: Int`, `height: Int`
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Serialization is implicitly tested by `ScreenshotEncoderTest` in Task 5.7. No separate test file for this data class.

#### Action 5.1.1: Create `ScreenshotData.kt`

**What**: Create the `ScreenshotData` data class.

**Context**: This data class represents the output of a screenshot capture operation. It is annotated with `@Serializable` so it can be serialized to JSON in MCP tool responses (via Kotlinx Serialization). The `format` field defaults to `"jpeg"` because the encoder always produces JPEG output. The `data` field holds the base64-encoded JPEG bytes. `width` and `height` provide the image dimensions. This class is used by `ScreenshotEncoder.bitmapToScreenshotData()` and will be returned by the `capture_screenshot` MCP tool in Plan 7.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotData.kt`

> **IMPORTANT — Path discrepancy**: PROJECT.md lists `ScreenshotData.kt` under `data/model/`. This plan places it in `services/screencapture/` for proximity to the screen capture code. At implementation time, use the `data/model/` location per PROJECT.md and update the import paths accordingly.

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotData.kt
@@ -0,0 +1,22 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import kotlinx.serialization.Serializable
+
+/**
+ * Represents a captured screenshot ready for MCP response serialization.
+ *
+ * @property format The image encoding format (always "jpeg").
+ * @property data The base64-encoded JPEG image data.
+ * @property width The screenshot width in pixels.
+ * @property height The screenshot height in pixels.
+ */
+@Serializable
+data class ScreenshotData(
+    val format: String = FORMAT_JPEG,
+    val data: String,
+    val width: Int,
+    val height: Int,
+) {
+    companion object {
+        const val FORMAT_JPEG = "jpeg"
+    }
+}
```

---

### Task 5.2: Create ScreenshotEncoder Utility

**Description**: Create the `ScreenshotEncoder` utility class that handles the full pipeline of converting an `Image` (from ImageReader) to a `Bitmap`, encoding the `Bitmap` to JPEG, and base64-encoding the result.

**Acceptance Criteria**:
- [ ] `ScreenshotEncoder` has `@Inject constructor()` for Hilt injection
- [ ] `imageToBitmap(image: Image): Bitmap` correctly handles row padding (when `rowStride != width * pixelStride`)
- [ ] `encodeBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray` compresses bitmap to JPEG
- [ ] `encodeToBase64(bytes: ByteArray): String` produces base64 string with `NO_WRAP` flag
- [ ] `bitmapToScreenshotData(bitmap: Bitmap, quality: Int): ScreenshotData` chains the full pipeline and returns a complete `ScreenshotData`
- [ ] Bitmaps created in `imageToBitmap` are the caller's responsibility to recycle (documented)
- [ ] Quality parameter is validated (clamped to 1-100 range)
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests in Task 5.7 (`ScreenshotEncoderTest.kt`).

#### Action 5.2.1: Create `ScreenshotEncoder.kt`

**What**: Create the `ScreenshotEncoder` utility class with the full image encoding pipeline.

**Context**: This class encapsulates the three-step screenshot encoding pipeline: (1) convert Android `media.Image` to `Bitmap`, (2) compress `Bitmap` to JPEG byte array, (3) base64-encode the bytes. The `imageToBitmap` method must handle row padding correctly -- `ImageReader` may return images where `rowStride` exceeds `width * pixelStride` due to memory alignment. In that case, the buffer cannot be directly used with `Bitmap.copyPixelsFromBuffer()` and must be copied row by row. The class is annotated for Hilt injection so it can be provided to `ScreenCaptureService` via constructor injection.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoder.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoder.kt
@@ -0,0 +1,100 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import android.graphics.Bitmap
+import android.media.Image
+import android.util.Base64
+import java.io.ByteArrayOutputStream
+import javax.inject.Inject
+
+/**
+ * Utility class for converting captured screen images to base64-encoded JPEG data.
+ *
+ * Handles the full encoding pipeline:
+ * 1. Convert [Image] (from ImageReader) to [Bitmap]
+ * 2. Compress [Bitmap] to JPEG byte array
+ * 3. Base64-encode the JPEG bytes
+ *
+ * The caller is responsible for recycling any [Bitmap] objects returned by [imageToBitmap].
+ */
+class ScreenshotEncoder @Inject constructor() {
+
+    /**
+     * Converts an [Image] from ImageReader to a [Bitmap].
+     *
+     * Handles row padding correctly when `rowStride != width * pixelStride`,
+     * which can occur due to memory alignment in the image buffer.
+     *
+     * The caller is responsible for calling [Bitmap.recycle] on the returned bitmap
+     * when it is no longer needed.
+     *
+     * @param image The captured image from ImageReader. Must have RGBA_8888 format.
+     * @return A [Bitmap] containing the image pixels.
+     */
+    fun imageToBitmap(image: Image): Bitmap {
+        val plane = image.planes[0]
+        val buffer = plane.buffer
+        val pixelStride = plane.pixelStride
+        val rowStride = plane.rowStride
+        val rowPadding = rowStride - pixelStride * image.width
+
+        val bitmapWidth = image.width + rowPadding / pixelStride
+        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
+        bitmap.copyPixelsFromBuffer(buffer)
+
+        return if (rowPadding > 0) {
+            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
+            bitmap.recycle()
+            croppedBitmap
+        } else {
+            bitmap
+        }
+    }
+
+    /**
+     * Compresses a [Bitmap] to JPEG format with the specified quality.
+     *
+     * @param bitmap The bitmap to compress.
+     * @param quality JPEG quality (1-100). Values outside this range are clamped.
+     * @return The JPEG-encoded bytes.
+     */
+    fun encodeBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
+        val clampedQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
+        val outputStream = ByteArrayOutputStream()
+        bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, outputStream)
+        return outputStream.toByteArray()
+    }
+
+    /**
+     * Base64-encodes a byte array.
+     *
+     * Uses [Base64.NO_WRAP] to produce a single-line output without line breaks.
+     *
+     * @param bytes The bytes to encode.
+     * @return The base64-encoded string.
+     */
+    fun encodeToBase64(bytes: ByteArray): String =
+        Base64.encodeToString(bytes, Base64.NO_WRAP)
+
+    /**
+     * Full pipeline: compresses a [Bitmap] to JPEG, base64-encodes the result,
+     * and returns a [ScreenshotData] with the image dimensions.
+     *
+     * Does NOT recycle the input bitmap -- the caller retains ownership.
+     *
+     * @param bitmap The bitmap to encode.
+     * @param quality JPEG quality (1-100). Values outside this range are clamped.
+     * @return A [ScreenshotData] containing the base64-encoded JPEG and image metadata.
+     */
+    fun bitmapToScreenshotData(bitmap: Bitmap, quality: Int): ScreenshotData {
+        val jpegBytes = encodeBitmapToJpeg(bitmap, quality)
+        val base64Data = encodeToBase64(jpegBytes)
+        return ScreenshotData(
+            data = base64Data,
+            width = bitmap.width,
+            height = bitmap.height,
+        )
+    }
+
+    companion object {
+        private const val MIN_QUALITY = 1
+        private const val MAX_QUALITY = 100
+    }
+}
```

---

### Task 5.2b: Create MediaProjectionManager

**Description**: Create a `MediaProjectionManager.kt` class that encapsulates all MediaProjection lifecycle management: permission result handling, projection setup/teardown, and callback registration. This follows SOLID's Single Responsibility Principle -- `ScreenCaptureService` should focus on service lifecycle and screenshot coordination, while `MediaProjectionManager` handles projection-specific logic. The class is injected into `ScreenCaptureService`.

**Acceptance Criteria**:
- [ ] `MediaProjectionManager` is a class (not a service) in `services/screencapture/` package
- [ ] `MediaProjectionManager` has `setupProjection(context: Context, resultCode: Int, data: Intent)` method
- [ ] `MediaProjectionManager` has `stopProjection()` method for cleanup
- [ ] `MediaProjectionManager` has `isProjectionActive(): Boolean` method
- [ ] `MediaProjectionManager` has `getProjection(): MediaProjection?` accessor
- [ ] `MediaProjectionManager` registers `MediaProjection.Callback` to handle system-initiated stops
- [ ] `MediaProjectionManager` provides an `onProjectionStopped: (() -> Unit)?` callback for the service to react to projection loss
- [ ] `MediaProjectionManager` is `@Inject`-able via Hilt (empty constructor or Hilt module binding)
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Tested indirectly via `ScreenCaptureServiceTest`. Since `MediaProjectionManager` wraps Android framework APIs (`MediaProjectionManager` system service), direct unit testing requires mocking. Tests added in Task 5.8.

#### Action 5.2b.1: Create `MediaProjectionManager.kt`

**What**: Create the MediaProjection lifecycle manager class.

**Context**: This class follows the Single Responsibility Principle by separating MediaProjection management from the service lifecycle. `ScreenCaptureService` delegates all projection setup/teardown to this class. The class obtains `android.media.projection.MediaProjectionManager` from the passed `Context`, calls `getMediaProjection()` with the activity result, registers a callback, and exposes projection state. The `onProjectionStopped` callback allows `ScreenCaptureService` to react when the system or user stops the projection (e.g., release ImageReader/VirtualDisplay resources).

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/MediaProjectionManager.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/MediaProjectionManager.kt
@@ -0,0 +1,99 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import android.content.Context
+import android.content.Intent
+import android.media.projection.MediaProjection
+import android.os.Handler
+import android.os.Looper
+import android.util.Log
+import javax.inject.Inject
+
+/**
+ * Manages the MediaProjection lifecycle for screen capture.
+ *
+ * Responsibilities:
+ * - Initializes MediaProjection from activity result (resultCode + data Intent).
+ * - Registers a [MediaProjection.Callback] to handle system-initiated projection stops.
+ * - Provides projection state and access for [ScreenCaptureService].
+ * - Handles cleanup on stop/destroy.
+ *
+ * This class follows the Single Responsibility Principle: it handles only
+ * MediaProjection lifecycle, while [ScreenCaptureService] handles service
+ * lifecycle and screenshot coordination.
+ */
+class MediaProjectionManager @Inject constructor() {
+
+    private val handler = Handler(Looper.getMainLooper())
+    private var mediaProjection: MediaProjection? = null
+
+    /**
+     * Optional callback invoked when the projection is stopped by the system or user.
+     * Set by [ScreenCaptureService] to trigger resource cleanup (ImageReader, VirtualDisplay).
+     */
+    var onProjectionStopped: (() -> Unit)? = null
+
+    private val projectionCallback = object : MediaProjection.Callback() {
+        override fun onStop() {
+            Log.i(TAG, "MediaProjection stopped by system or user")
+            mediaProjection = null
+            onProjectionStopped?.invoke()
+        }
+    }
+
+    /**
+     * Initializes MediaProjection from the activity result obtained via
+     * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
+     *
+     * Must be called after the user grants screen capture permission.
+     *
+     * @param context The application or service context to obtain the system MediaProjectionManager.
+     * @param resultCode The result code from the activity result (must be [android.app.Activity.RESULT_OK]).
+     * @param data The intent data from the activity result containing the MediaProjection token.
+     */
+    fun setupProjection(context: Context, resultCode: Int, data: Intent) {
+        Log.i(TAG, "Setting up MediaProjection")
+        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
+            as android.media.projection.MediaProjectionManager
+        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
+        mediaProjection?.registerCallback(projectionCallback, handler)
+        Log.i(TAG, "MediaProjection set up successfully")
+    }
+
+    /**
+     * Stops and cleans up the MediaProjection.
+     *
+     * Safe to call multiple times or when no projection is active.
+     */
+    fun stopProjection() {
+        Log.i(TAG, "Stopping MediaProjection")
+        mediaProjection?.unregisterCallback(projectionCallback)
+        mediaProjection?.stop()
+        mediaProjection = null
+    }
+
+    /**
+     * Checks whether MediaProjection is currently active and ready for use.
+     *
+     * @return `true` if [setupProjection] has been called successfully and the projection
+     *         has not been stopped.
+     */
+    fun isProjectionActive(): Boolean = mediaProjection != null
+
+    /**
+     * Returns the current MediaProjection instance, or null if not active.
+     *
+     * Used by [ScreenCaptureService] to create VirtualDisplay for screenshot capture.
+     */
+    fun getProjection(): MediaProjection? = mediaProjection
+
+    companion object {
+        private const val TAG = "MCP:MediaProjectionMgr"
+    }
+}
```

---

### Task 5.3: Create ScreenCaptureService

**Description**: Replace the stub `ScreenCaptureService` (from Plan 1) with a full implementation: a bound foreground service that manages MediaProjection, handles lifecycle correctly, and provides thread-safe screenshot capture.

**Acceptance Criteria**:
- [ ] `ScreenCaptureService` extends `Service()` and is annotated with `@AndroidEntryPoint` for Hilt injection
- [ ] Inner class `LocalBinder : Binder()` exposes `fun getService(): ScreenCaptureService`
- [ ] `onBind()` returns `LocalBinder` instance
- [ ] `onStartCommand()` calls `startForeground()` with a notification on channel `screen_capture_channel`
- [ ] Notification channel is NOT created by the service itself -- it relies on centralized channel creation in `McpApplication.onCreate()` (see Plan 6, Task 6.6.3). The channel MUST already exist before `startForeground()` is called.
- [ ] `onDestroy()` delegates to `MediaProjectionManager.stopProjection()`, releases ImageReader/VirtualDisplay, cancels coroutine scope, clears singleton, logs shutdown
- [ ] `onLowMemory()` and `onTrimMemory()` log the event (no bitmap caches to release at this layer since bitmaps are created and recycled within a single capture call)
- [ ] `setupMediaProjection(resultCode, data)` delegates to `MediaProjectionManager.setupProjection(context, resultCode, data)`
- [ ] `isMediaProjectionActive()` delegates to `MediaProjectionManager.isProjectionActive()`
- [ ] `captureScreenshot(quality)` acquires mutex, checks projection active, creates ImageReader/VirtualDisplay, waits for image via `suspendCancellableCoroutine`, converts image, returns `Result<ScreenshotData>`
- [ ] `captureScreenshot()` returns `Result.failure()` with descriptive message when projection is not active
- [ ] ImageReader uses `PixelFormat.RGBA_8888` with buffer size 2 (double-buffered)
- [ ] VirtualDisplay uses `DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`
- [ ] `MediaProjectionManager.onProjectionStopped` callback set to release resources and log
- [ ] Companion object stores singleton instance for inter-service access (following accessibility service pattern from Plan 4)
- [ ] Screenshot capture uses `Dispatchers.Default` for CPU-intensive encoding work
- [ ] `ScreenshotEncoder` and `MediaProjectionManager` are `@Inject`ed via Hilt
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Unit tests in Task 5.8 (`ScreenCaptureServiceTest.kt`).

#### Action 5.3.1: Replace `ScreenCaptureService.kt` stub with full implementation

**What**: Replace the minimal stub created in Plan 1 with the complete ScreenCaptureService implementation.

**Context**: The stub from Plan 1 (`services/screencapture/ScreenCaptureService.kt`) is a bare `Service()` with `onBind()` returning null and a companion object with TAG. This action replaces it entirely. The service uses the bound service pattern (LocalBinder) so that McpServerService (Plan 6) can bind to it and call `captureScreenshot()`. It runs as a foreground service (Android requirement for MediaProjection) with a persistent notification. MediaProjection lifecycle is delegated to the injected `MediaProjectionManager` (Task 5.2b), following the Single Responsibility Principle. The `Mutex` ensures that only one screenshot capture happens at a time -- multiple MCP requests could trigger concurrent captures, and ImageReader/VirtualDisplay resources are not thread-safe. The `suspendCancellableCoroutine` approach converts the callback-based `ImageReader.OnImageAvailableListener` into a suspend function for clean coroutine integration. Screen metrics (width, height, density) are obtained from `WindowManager` at capture time to handle rotation changes. The singleton pattern in companion object mirrors the pattern used by `McpAccessibilityService` (Plan 4) for cross-service access.

**IMPORTANT**: The service is annotated with `@AndroidEntryPoint` to enable Hilt injection of `ScreenshotEncoder` and `MediaProjectionManager`. The notification channel is NOT created by this service -- it must be created centrally in `McpApplication.onCreate()` (Plan 6, Task 6.6.3) before this service starts.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt`

```diff
--- a/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt
@@ -1,14 +1,259 @@
 package com.danielealbano.androidremotecontrolmcp.services.screencapture

-import android.app.Service
+import android.app.Notification
+import android.content.Context
 import android.content.Intent
+import android.graphics.PixelFormat
+import android.hardware.display.DisplayManager
+import android.hardware.display.VirtualDisplay
+import android.media.ImageReader
+import android.os.Binder
+import android.os.Build
 import android.os.IBinder
+import android.os.Handler
+import android.os.Looper
+import android.util.Log
+import android.view.WindowManager
+import android.app.Service
+import dagger.hilt.android.AndroidEntryPoint
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.cancel
+import kotlinx.coroutines.suspendCancellableCoroutine
+import kotlinx.coroutines.sync.Mutex
+import kotlinx.coroutines.sync.withLock
+import kotlinx.coroutines.withContext
+import kotlinx.coroutines.withTimeoutOrNull
+import javax.inject.Inject
+import kotlin.coroutines.resume

-class ScreenCaptureService : Service() {
+/**
+ * Bound foreground service that manages MediaProjection for on-demand screenshot capture.
+ *
+ * This service:
+ * - Runs as a foreground service (Android requirement for MediaProjection).
+ * - Uses the bound service pattern (LocalBinder) for inter-service communication.
+ * - Provides thread-safe screenshot capture via [captureScreenshot] using a [Mutex].
+ * - Delegates MediaProjection lifecycle to [MediaProjectionManager] (SRP).
+ * - Stores a singleton instance for cross-service access.
+ *
+ * Lifecycle:
+ * 1. McpServerService calls `startForegroundService()` THEN `bindService()` (order matters!).
+ * 2. `onStartCommand()` calls `startForeground()` within 5 seconds.
+ * 3. MainActivity calls [setupMediaProjection] with the activity result.
+ * 4. MCP tool calls invoke [captureScreenshot] to capture the current screen.
+ * 5. On service destruction, all resources are released.
+ *
+ * IMPORTANT: The notification channel (`screen_capture_channel`) MUST be created in
+ * `McpApplication.onCreate()` BEFORE this service starts. This service does NOT create
+ * its own notification channel.
+ */
+@AndroidEntryPoint
+class ScreenCaptureService : Service() {

-    override fun onBind(intent: Intent?): IBinder? = null
+    private val binder = LocalBinder()
+    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
+    private val captureMutex = Mutex()
+    private val handler = Handler(Looper.getMainLooper())
+
+    private var virtualDisplay: VirtualDisplay? = null
+    private var imageReader: ImageReader? = null
+    private var screenWidth: Int = 0
+    private var screenHeight: Int = 0
+    private var screenDensity: Int = 0
+
+    @Inject
+    lateinit var screenshotEncoder: ScreenshotEncoder
+
+    @Inject
+    lateinit var mediaProjectionManager: MediaProjectionManager
+
+    /**
+     * Binder class that provides access to this service instance.
+     */
+    inner class LocalBinder : Binder() {
+        fun getService(): ScreenCaptureService = this@ScreenCaptureService
+    }
+
+    override fun onCreate() {
+        super.onCreate()
+        // Wire the projection-stopped callback to release capture resources
+        mediaProjectionManager.onProjectionStopped = {
+            Log.i(TAG, "MediaProjection stopped, releasing capture resources")
+            releaseProjectionResources()
+        }
+    }
+
+    override fun onBind(intent: Intent?): IBinder = binder
+
+    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
+        Log.i(TAG, "ScreenCaptureService starting")
+        // Notification channel MUST already exist (created in McpApplication.onCreate())
+        startForeground(NOTIFICATION_ID, createNotification())
+        instance = this
+        return START_NOT_STICKY
+    }
+
+    override fun onDestroy() {
+        Log.i(TAG, "ScreenCaptureService destroying")
+        releaseProjectionResources()
+        mediaProjectionManager.stopProjection()
+        coroutineScope.cancel()
+        instance = null
+        super.onDestroy()
+        Log.i(TAG, "ScreenCaptureService destroyed")
+    }
+
+    override fun onLowMemory() {
+        super.onLowMemory()
+        Log.w(TAG, "Low memory condition reported")
+    }
+
+    override fun onTrimMemory(level: Int) {
+        super.onTrimMemory(level)
+        Log.w(TAG, "Trim memory requested at level $level")
+    }
+
+    /**
+     * Initializes MediaProjection from the activity result obtained via
+     * [android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
+     *
+     * Delegates to [MediaProjectionManager.setupProjection].
+     * Must be called after the user grants screen capture permission.
+     *
+     * @param resultCode The result code from the activity result (must be [android.app.Activity.RESULT_OK]).
+     * @param data The intent data from the activity result containing the MediaProjection token.
+     */
+    fun setupMediaProjection(resultCode: Int, data: Intent) {
+        mediaProjectionManager.setupProjection(this, resultCode, data)
+    }
+
+    /**
+     * Checks whether MediaProjection is currently active and ready for screenshot capture.
+     *
+     * @return `true` if [setupMediaProjection] has been called successfully and the projection
+     *         has not been stopped.
+     */
+    fun isMediaProjectionActive(): Boolean = mediaProjectionManager.isProjectionActive()
+
+    /**
+     * Captures a screenshot of the current screen.
+     *
+     * This method is thread-safe: concurrent calls are serialized via a [Mutex].
+     * Only one screenshot capture can happen at a time.
+     *
+     * @param quality JPEG compression quality (1-100). Default is [DEFAULT_QUALITY].
+     * @return [Result.success] with [ScreenshotData] on successful capture,
+     *         or [Result.failure] with a descriptive exception on error.
+     */
+    suspend fun captureScreenshot(quality: Int = DEFAULT_QUALITY): Result<ScreenshotData> =
+        captureMutex.withLock {
+            captureScreenshotInternal(quality)
+        }
+
+    private suspend fun captureScreenshotInternal(quality: Int): Result<ScreenshotData> {
+        if (!mediaProjectionManager.isProjectionActive()) {
+            return Result.failure(
+                IllegalStateException("MediaProjection is not active. Grant screen capture permission first."),
+            )
+        }
+
+        return try {
+            updateScreenMetrics()
+            setupImageReader()
+            setupVirtualDisplay()
+
+            val image = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
+                suspendCancellableCoroutine { continuation ->
+                    imageReader?.setOnImageAvailableListener(
+                        { reader ->
+                            val img = reader.acquireLatestImage()
+                            if (img != null && continuation.isActive) {
+                                continuation.resume(img)
+                            }
+                        },
+                        handler,
+                    )
+
+                    continuation.invokeOnCancellation {
+                        imageReader?.setOnImageAvailableListener(null, null)
+                    }
+                }
+            }
+
+            if (image == null) {
+                releaseProjectionResources()
+                return Result.failure(
+                    IllegalStateException("Screenshot capture timed out after ${CAPTURE_TIMEOUT_MS}ms"),
+                )
+            }
+
+            val screenshotData = withContext(Dispatchers.Default) {
+                val bitmap = screenshotEncoder.imageToBitmap(image)
+                image.close()
+                try {
+                    screenshotEncoder.bitmapToScreenshotData(bitmap, quality)
+                } finally {
+                    bitmap.recycle()
+                }
+            }
+
+            releaseProjectionResources()
+            Result.success(screenshotData)
+        } catch (e: Exception) {
+            Log.e(TAG, "Screenshot capture failed", e)
+            releaseProjectionResources()
+            Result.failure(e)
+        }
+    }
+
+    private fun updateScreenMetrics() {
+        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
+        val metrics = windowManager.currentWindowMetrics
+        val bounds = metrics.bounds
+        screenWidth = bounds.width()
+        screenHeight = bounds.height()
+
+        val displayMetrics = resources.displayMetrics
+        screenDensity = displayMetrics.densityDpi
+    }

> **CRITICAL — API level check missing**: `windowManager.currentWindowMetrics` requires API 30+. Since `minSdk = 26`, this will crash on API 26-29. Add a `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` check with a `DisplayMetrics` fallback, matching the pattern already used in `McpAccessibilityService.getScreenInfo()` (Action 5.5.1).
+
+    private fun setupImageReader() {
+        imageReader?.close()
+        imageReader = ImageReader.newInstance(
+            screenWidth,
+            screenHeight,
+            PixelFormat.RGBA_8888,
+            IMAGE_READER_MAX_IMAGES,
+        )
+    }
+
+    private fun setupVirtualDisplay() {
+        virtualDisplay?.release()
+        virtualDisplay = mediaProjectionManager.getProjection()?.createVirtualDisplay(
+            VIRTUAL_DISPLAY_NAME,
+            screenWidth,
+            screenHeight,
+            screenDensity,
+            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
+            imageReader?.surface,
+            null,
+            handler,
+        )
+    }
+
+    private fun releaseProjectionResources() {
+        imageReader?.setOnImageAvailableListener(null, null)
+        virtualDisplay?.release()
+        virtualDisplay = null
+        imageReader?.close()
+        imageReader = null
+    }
+
+    private fun createNotification(): Notification {
+        val channelId = getString(R.string.notification_channel_screen_capture_id)
+        return Notification.Builder(this, channelId)
+            .setContentTitle(getString(R.string.notification_screen_capture_title))
+            .setSmallIcon(R.drawable.ic_notification)
+            .setOngoing(true)
+            .build()
+    }

     companion object {
         private const val TAG = "MCP:ScreenCaptureService"
+        const val NOTIFICATION_ID = 2002
+        const val DEFAULT_QUALITY = 80
+
+        private const val IMAGE_READER_MAX_IMAGES = 2
+        private const val VIRTUAL_DISPLAY_NAME = "McpScreenCapture"
+        private const val CAPTURE_TIMEOUT_MS = 5000L
+
+        @Volatile
+        var instance: ScreenCaptureService? = null
+            private set
     }
 }
```

> **Implementation Note on R class import**: The `R.string.*` and `R.drawable.*` references resolve to `com.danielealbano.androidremotecontrolmcp.R` automatically because the file is in the same package namespace. If the build fails with unresolved `R` references, add an explicit import: `import com.danielealbano.androidremotecontrolmcp.R`.

> **Implementation Note on `@AndroidEntryPoint` and Hilt injection**: The `ScreenCaptureService` is annotated with `@AndroidEntryPoint`, which enables Hilt field injection for `ScreenshotEncoder` and `MediaProjectionManager`. Hilt's `@AndroidEntryPoint` supports `Service` subclasses since Hilt 2.28+. Both injected classes have `@Inject constructor()` making them directly injectable. If `@AndroidEntryPoint` does not work on this Service subclass (unlikely), fall back to `EntryPointAccessors.fromApplication()` to manually obtain dependencies.

> **Implementation Note on `currentWindowMetrics`**: `WindowManager.currentWindowMetrics` is available from API 30 (Android 11). Since `minSdk = 26`, a fallback using `DisplayMetrics` is needed for API 26-29. At implementation time, add a version check:
> ```kotlin
> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
>     val metrics = windowManager.currentWindowMetrics
>     screenWidth = metrics.bounds.width()
>     screenHeight = metrics.bounds.height()
> } else {
>     @Suppress("DEPRECATION")
>     val display = windowManager.defaultDisplay
>     val metrics = android.util.DisplayMetrics()
>     @Suppress("DEPRECATION")
>     display.getRealMetrics(metrics)
>     screenWidth = metrics.widthPixels
>     screenHeight = metrics.heightPixels
> }
> ```
> Adjust the `updateScreenMetrics()` method accordingly.

---

### Task 5.4: Create ScreenInfo Data Class

**Description**: Create the `ScreenInfo` data class that represents screen dimensions and orientation. This is returned by `McpAccessibilityService.getScreenInfo()` and will be used by the `get_screen_info` MCP tool.

**Acceptance Criteria**:
- [ ] `ScreenInfo` is a `@Serializable` data class
- [ ] Fields: `width: Int`, `height: Int`, `densityDpi: Int`, `orientation: String`
- [ ] Located in `services/accessibility/` package (since it is produced by the accessibility service)
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Implicitly tested by the accessibility service unit tests. No separate test file for this data class.

#### Action 5.4.1: Create `ScreenInfo.kt`

**What**: Create the `ScreenInfo` data class.

**Context**: This data class represents the device screen metrics and orientation. It is annotated with `@Serializable` for JSON serialization in MCP responses. The `orientation` field is a string (`"portrait"` or `"landscape"`) for readability in MCP tool output. This class is placed in the `services.accessibility` package because the `McpAccessibilityService` is the component that produces it (it has access to the display context). It will be returned by the `get_screen_info` MCP tool in Plan 7.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenInfo.kt`

```diff
--- /dev/null
+++ b/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenInfo.kt
@@ -0,0 +1,24 @@
+package com.danielealbano.androidremotecontrolmcp.services.accessibility
+
+import kotlinx.serialization.Serializable
+
+/**
+ * Represents the device screen dimensions, density, and orientation.
+ *
+ * Produced by [McpAccessibilityService.getScreenInfo] and returned
+ * by the `get_screen_info` MCP tool.
+ *
+ * @property width Screen width in pixels.
+ * @property height Screen height in pixels.
+ * @property densityDpi Screen density in dots per inch.
+ * @property orientation Screen orientation: "portrait" or "landscape".
+ */
+@Serializable
+data class ScreenInfo(
+    val width: Int,
+    val height: Int,
+    val densityDpi: Int,
+    val orientation: String,
+) {
+    companion object {
+        const val ORIENTATION_PORTRAIT = "portrait"
+        const val ORIENTATION_LANDSCAPE = "landscape"
+    }
+}
```

---

### Task 5.5: Update McpAccessibilityService with getScreenInfo Method

**Description**: Add a `getScreenInfo()` method to `McpAccessibilityService` that returns the current screen dimensions, density, and orientation.

**Acceptance Criteria**:
- [ ] `getScreenInfo(): ScreenInfo` method is added to `McpAccessibilityService`
- [ ] Method returns correct width, height, densityDpi from display metrics
- [ ] Method returns correct orientation string ("portrait" or "landscape") based on `Configuration.orientation`
- [ ] Method handles API level differences for getting display metrics (API 30+ vs older)
- [ ] File compiles without errors
- [ ] File passes ktlint and detekt

**Tests**: Tested as part of accessibility service tests (Plan 4 tests or extended in Task 5.8). The method is straightforward and primarily delegates to Android framework APIs.

> **Implementation Note — Missing test**: `McpAccessibilityService.getScreenInfo()` is added in Task 5.5 but has no dedicated test. Add a test at implementation time.

#### Action 5.5.1: Add `getScreenInfo()` method to `McpAccessibilityService.kt`

**What**: Add the `getScreenInfo()` method to the existing `McpAccessibilityService`.

**Context**: The `McpAccessibilityService` already exists from Plan 4 with singleton pattern, event handling, and tree parsing methods. This action adds a single method `getScreenInfo()` that returns a `ScreenInfo` data class. The accessibility service has access to the display context through its inherited `Context`. The method uses `WindowManager` to get screen dimensions and `Configuration` to determine orientation. As with `ScreenCaptureService.updateScreenMetrics()`, a version check is needed for `currentWindowMetrics` (API 30+) vs the deprecated `defaultDisplay.getRealMetrics()` approach.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt`

The following additions should be made to the existing file. The exact line numbers depend on the current state of the file after Plan 4 implementation.

```diff
 package com.danielealbano.androidremotecontrolmcp.services.accessibility

+import android.content.res.Configuration
+import android.os.Build
+import android.view.WindowManager
 // ... (existing imports)

 class McpAccessibilityService : AccessibilityService() {
     // ... (existing code from Plan 4)

+    /**
+     * Returns the current screen dimensions, density, and orientation.
+     *
+     * @return [ScreenInfo] with width, height, densityDpi, and orientation.
+     */
+    fun getScreenInfo(): ScreenInfo {
+        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
+        val width: Int
+        val height: Int
+
+        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
+            val metrics = windowManager.currentWindowMetrics
+            val bounds = metrics.bounds
+            width = bounds.width()
+            height = bounds.height()
+        } else {
+            @Suppress("DEPRECATION")
+            val display = windowManager.defaultDisplay
+            val displayMetrics = android.util.DisplayMetrics()
+            @Suppress("DEPRECATION")
+            display.getRealMetrics(displayMetrics)
+            width = displayMetrics.widthPixels
+            height = displayMetrics.heightPixels
+        }
+
+        val displayMetrics = resources.displayMetrics
+        val densityDpi = displayMetrics.densityDpi
+
+        val orientation = when (resources.configuration.orientation) {
+            Configuration.ORIENTATION_LANDSCAPE -> ScreenInfo.ORIENTATION_LANDSCAPE
+            else -> ScreenInfo.ORIENTATION_PORTRAIT
+        }
+
+        return ScreenInfo(
+            width = width,
+            height = height,
+            densityDpi = densityDpi,
+            orientation = orientation,
+        )
+    }

     // ... (existing companion object)
 }
```

> **Implementation Note**: The exact insertion point depends on the Plan 4 implementation. Place this method after the existing public methods and before any private methods, following the code organization convention from CLAUDE.md Section 6 (Kotlin Coding Standards).

---

### Task 5.6: Update MainActivity and MainViewModel for MediaProjection Permission

**Description**: Add MediaProjection permission handling to `MainActivity` (ActivityResultLauncher) and `MainViewModel` (state tracking). This connects the "Grant" button in the PermissionsSection to the MediaProjection permission dialog.

**Acceptance Criteria**:
- [ ] `MainActivity` registers an `ActivityResultLauncher<Intent>` for `MediaProjectionManager.createScreenCaptureIntent()`
- [ ] On successful result (`RESULT_OK`), the result code and data intent are stored in `MainViewModel`
- [ ] `MainViewModel` exposes `isMediaProjectionGranted: StateFlow<Boolean>` for UI observation
- [ ] `MainViewModel` has `fun setMediaProjectionResult(resultCode: Int, data: Intent)` to store the result
- [ ] `MainViewModel` exposes `mediaProjectionResultCode: Int` and `mediaProjectionData: Intent?` for later retrieval by McpServerService
- [ ] The PermissionsSection "Grant" button for MediaProjection calls the launcher
- [ ] `strings.xml` is updated with any new strings needed for this task (if any)
- [ ] Files compile without errors
- [ ] Files pass ktlint and detekt

**Tests**: MainViewModel updates are tested as part of `MainViewModelTest` (from Plan 3, extended here). UI interactions are tested in integration tests.

#### Action 5.6.1: Update `MainViewModel.kt` with MediaProjection state

**What**: Add MediaProjection permission state tracking to `MainViewModel`.

**Context**: The `MainViewModel` already exists from Plan 3 with server config state, validation, and settings operations. This action adds three items: (1) a `StateFlow<Boolean>` to track whether MediaProjection permission has been granted, (2) a method to store the activity result for later use, and (3) stored values for `resultCode` and `data` Intent so they can be passed to `ScreenCaptureService.setupMediaProjection()` when the server starts.

**IMPORTANT - MediaProjection result passing mechanism**: The `resultCode` and `data` Intent obtained from the MediaProjection permission dialog in `MainActivity` are stored in the ViewModel. When `McpServerService` starts the `ScreenCaptureService`, it needs these values to call `setupMediaProjection()`. The passing mechanism works as follows:
1. User grants MediaProjection permission in `MainActivity` -> result stored in `MainViewModel`.
2. When the MCP server starts (`MainViewModel.startServer()`), the `resultCode` and `data` are passed as Intent extras to `McpServerService`.
3. `McpServerService` extracts them and, after binding to `ScreenCaptureService`, calls `screenCaptureService.setupMediaProjection(resultCode, data)`.
4. Alternatively, since `ScreenCaptureService` has a singleton `instance`, `MainActivity` can call `ScreenCaptureService.instance?.setupMediaProjection(resultCode, data)` directly after the service is running.
The recommended approach is option 3 (via McpServerService), keeping the flow centralized through the server service. Plan 6 (Task 6.5) must implement the intent extras to carry the MediaProjection result.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt`

The following additions should be made to the existing file:

```diff
 // ... (existing imports)
+import android.app.Activity
+import android.content.Intent

 @HiltViewModel
 class MainViewModel @Inject constructor(
     // ... existing constructor params
 ) : ViewModel() {
     // ... (existing state flows and methods from Plan 3)

+    private val _isMediaProjectionGranted = MutableStateFlow(false)
+    val isMediaProjectionGranted: StateFlow<Boolean> = _isMediaProjectionGranted.asStateFlow()
+
+    private var _mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
+    val mediaProjectionResultCode: Int get() = _mediaProjectionResultCode
+
+    private var _mediaProjectionData: Intent? = null
+    val mediaProjectionData: Intent? get() = _mediaProjectionData
+
+    /**
+     * Stores the MediaProjection activity result for later use when starting the
+     * ScreenCaptureService.
+     *
+     * @param resultCode The result code from the activity result.
+     * @param data The intent data containing the MediaProjection token.
+     */
+    fun setMediaProjectionResult(resultCode: Int, data: Intent) {
+        _mediaProjectionResultCode = resultCode
+        _mediaProjectionData = data
+        _isMediaProjectionGranted.value = (resultCode == Activity.RESULT_OK)
+    }

     // ... (existing companion object)
 }
```

---

#### Action 5.6.2: Update `MainActivity.kt` with ActivityResultLauncher for MediaProjection

**What**: Register an `ActivityResultLauncher` in `MainActivity` to handle the MediaProjection permission request and result.

**Context**: `MainActivity` already exists from Plan 3 with Compose UI, `@AndroidEntryPoint`, and ViewModel observation. This action adds an `ActivityResultLauncher<Intent>` that launches `MediaProjectionManager.createScreenCaptureIntent()` when the user taps the "Grant" button in the PermissionsSection for screen capture. On result, it stores the result in the ViewModel. The launcher must be registered in `onCreate()` (before `setContent {}`) because `registerForActivityResult()` must be called before the Activity is in STARTED state.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivity.kt`

The following additions should be made to the existing file:

```diff
 // ... (existing imports)
+import android.app.Activity
+import android.media.projection.MediaProjectionManager
+import android.content.Context
+import androidx.activity.result.ActivityResultLauncher
+import androidx.activity.result.contract.ActivityResultContracts

 @AndroidEntryPoint
 class MainActivity : ComponentActivity() {
+
+    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)

+        val viewModel: MainViewModel by viewModels()
+
+        mediaProjectionLauncher = registerForActivityResult(
+            ActivityResultContracts.StartActivityForResult(),
+        ) { result ->
+            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
+                viewModel.setMediaProjectionResult(result.resultCode, result.data!!)
+            }
+        }
+
         setContent {
             // ... (existing theme and HomeScreen setup)
+            // Pass the launcher callback to the HomeScreen/PermissionsSection
+            // so the "Grant Screen Capture" button can invoke it.
         }
     }
+
+    /**
+     * Launches the system MediaProjection permission dialog.
+     *
+     * Called from the PermissionsSection "Grant" button for screen capture.
+     */
+    fun requestMediaProjectionPermission() {
+        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
+        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
+    }

     companion object {
         private const val TAG = "MCP:MainActivity"
     }
 }
```

> **Implementation Note**: The exact wiring of `requestMediaProjectionPermission()` to the PermissionsSection "Grant" button depends on the Plan 3 composable architecture. If the PermissionsSection receives callbacks as lambda parameters, pass `{ requestMediaProjectionPermission() }` from `MainActivity`. If the PermissionsSection is in `HomeScreen` and does not have direct access to the Activity, use a pattern like:
> - Pass the callback through `HomeScreen` composable parameters, or
> - Use `LocalContext.current as? MainActivity` within the composable (less clean but functional), or
> - Use a ViewModel-driven approach with a `SharedFlow<Unit>` event.
>
> At implementation time, adapt this wiring to match the existing Plan 3 composable parameter structure. The key requirement is that tapping the "Grant Screen Capture" button triggers `MediaProjectionManager.createScreenCaptureIntent()` via the registered launcher.

---

### Task 5.7: Create ScreenshotEncoder Unit Tests

**Description**: Create unit tests for the `ScreenshotEncoder` class covering image-to-bitmap conversion, JPEG encoding, base64 encoding, quality parameter handling, and the full pipeline.

**Acceptance Criteria**:
- [ ] Tests use JUnit 5 (`@Test` annotations from `org.junit.jupiter.api`)
- [ ] Tests use MockK for mocking Android framework classes (`Image`, `Image.Plane`)
- [ ] Tests follow Arrange-Act-Assert pattern
- [ ] Test: `imageToBitmap` handles row padding (mocked `Image` with `rowStride > width * pixelStride`)
- [ ] Test: `imageToBitmap` handles no row padding (mocked `Image` with `rowStride == width * pixelStride`)
- [ ] Test: `encodeBitmapToJpeg` produces non-empty byte array
- [ ] Test: `encodeBitmapToJpeg` clamps quality to valid range (test with quality 0 and 101)
- [ ] Test: `encodeToBase64` produces valid base64 string
- [ ] Test: `bitmapToScreenshotData` returns correct format, dimensions, and non-empty data
- [ ] Test: quality parameter affects output size (low quality < high quality for same bitmap)
- [ ] All tests pass
- [ ] File passes ktlint and detekt

**Tests**: This IS the test task. All tests are in `ScreenshotEncoderTest.kt`.

> **Important Note on Android Framework Classes in Unit Tests**: `Bitmap`, `Base64`, `Image`, and `Bitmap.CompressFormat` are Android framework classes that are not available in JVM unit tests by default. There are several approaches:
> 1. **Robolectric**: If Robolectric is available and configured, it provides shadow implementations of Android classes. Check if the project has Robolectric configured (from Plan 1 dependencies).
> 2. **MockK**: Mock all Android classes. This works for testing logic flow but cannot verify actual bitmap content or JPEG compression.
> 3. **Abstraction**: Create an interface for Bitmap operations and test the interface. This is over-engineering for a utility class.
>
> At implementation time, determine the best approach. The plan below uses MockK-based mocking. If Robolectric is available, prefer it for more realistic tests.

#### Action 5.7.1: Create `ScreenshotEncoderTest.kt`

**What**: Create the unit test file for `ScreenshotEncoder`.

**Context**: These tests verify the encoding pipeline logic using MockK to mock Android framework classes (`Image`, `Image.Plane`, `Bitmap`, `Base64`). Since `Bitmap` and `Base64` are Android classes, the tests mock their behavior. The quality clamping logic is tested by verifying that the clamped value is passed to `Bitmap.compress()`. The row padding logic is tested by providing mock `Image` objects with different `rowStride` values.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoderTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoderTest.kt
@@ -0,0 +1,160 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import android.graphics.Bitmap
+import android.media.Image
+import android.util.Base64
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.mockkStatic
+import io.mockk.slot
+import io.mockk.unmockkStatic
+import io.mockk.verify
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertEquals
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+import java.io.ByteArrayOutputStream
+import java.io.OutputStream
+import java.nio.ByteBuffer
+
+@DisplayName("ScreenshotEncoder")
+class ScreenshotEncoderTest {
+
+    private lateinit var encoder: ScreenshotEncoder
+
+    @BeforeEach
+    fun setUp() {
+        encoder = ScreenshotEncoder()
+        mockkStatic(Bitmap::class)
+        mockkStatic(Base64::class)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkStatic(Bitmap::class)
+        unmockkStatic(Base64::class)
+    }
+
+    @Nested
+    @DisplayName("imageToBitmap")
+    inner class ImageToBitmapTests {
+
+        @Test
+        @DisplayName("converts image without row padding to bitmap of correct dimensions")
+        fun `converts image without row padding`() {
+            // Arrange
+            val width = 100
+            val height = 50
+            val pixelStride = 4
+            val rowStride = width * pixelStride // no padding
+
+            val buffer = ByteBuffer.allocate(rowStride * height)
+            val plane = mockk<Image.Plane> {
+                every { getBuffer() } returns buffer
+                every { getPixelStride() } returns pixelStride
+                every { getRowStride() } returns rowStride
+            }
+            val image = mockk<Image> {
+                every { planes } returns arrayOf(plane)
+                every { getWidth() } returns width
+                every { getHeight() } returns height
+            }
+
+            val bitmap = mockk<Bitmap>(relaxed = true) {
+                every { getWidth() } returns width
+                every { getHeight() } returns height
+            }
+            every { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } returns bitmap
+
+            // Act
+            val result = encoder.imageToBitmap(image)
+
+            // Assert
+            assertEquals(bitmap, result)
+            verify { bitmap.copyPixelsFromBuffer(buffer) }
+        }
+
+        @Test
+        @DisplayName("converts image with row padding by cropping to correct dimensions")
+        fun `converts image with row padding`() {
+            // Arrange
+            val width = 100
+            val height = 50
+            val pixelStride = 4
+            val rowPadding = 16
+            val rowStride = width * pixelStride + rowPadding
+
+            val buffer = ByteBuffer.allocate(rowStride * height)
+            val plane = mockk<Image.Plane> {
+                every { getBuffer() } returns buffer
+                every { getPixelStride() } returns pixelStride
+                every { getRowStride() } returns rowStride
+            }
+            val image = mockk<Image> {
+                every { planes } returns arrayOf(plane)
+                every { getWidth() } returns width
+                every { getHeight() } returns height
+            }
+
+            val bitmapWidth = width + rowPadding / pixelStride
+            val paddedBitmap = mockk<Bitmap>(relaxed = true)
+            val croppedBitmap = mockk<Bitmap>(relaxed = true) {
+                every { getWidth() } returns width
+                every { getHeight() } returns height
+            }
+            every { Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888) } returns paddedBitmap
+            every { Bitmap.createBitmap(paddedBitmap, 0, 0, width, height) } returns croppedBitmap
+
+            // Act
+            val result = encoder.imageToBitmap(image)
+
+            // Assert
+            assertEquals(croppedBitmap, result)
+            verify { paddedBitmap.recycle() }
+        }
+    }
+
+    @Nested
+    @DisplayName("encodeBitmapToJpeg")
+    inner class EncodeBitmapToJpegTests {
+
+        @Test
+        @DisplayName("compresses bitmap to JPEG with specified quality")
+        fun `compresses bitmap to JPEG`() {
+            // Arrange
+            val bitmap = mockk<Bitmap>(relaxed = true)
+            val qualitySlot = slot<Int>()
+            every {
+                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
+            } returns true
+
+            // Act
+            encoder.encodeBitmapToJpeg(bitmap, 80)
+
+            // Assert
+            assertEquals(80, qualitySlot.captured)
+        }
+
+        @Test
+        @DisplayName("clamps quality below minimum to 1")
+        fun `clamps quality below minimum`() {
+            // Arrange
+            val bitmap = mockk<Bitmap>(relaxed = true)
+            val qualitySlot = slot<Int>()
+            every {
+                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
+            } returns true
+
+            // Act
+            encoder.encodeBitmapToJpeg(bitmap, 0)
+
+            // Assert
+            assertEquals(1, qualitySlot.captured)
+        }
+
+        @Test
+        @DisplayName("clamps quality above maximum to 100")
+        fun `clamps quality above maximum`() {
+            // Arrange
+            val bitmap = mockk<Bitmap>(relaxed = true)
+            val qualitySlot = slot<Int>()
+            every {
+                bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any<OutputStream>())
+            } returns true
+
+            // Act
+            encoder.encodeBitmapToJpeg(bitmap, 150)
+
+            // Assert
+            assertEquals(100, qualitySlot.captured)
+        }
+    }
+
+    @Nested
+    @DisplayName("encodeToBase64")
+    inner class EncodeToBase64Tests {
+
+        @Test
+        @DisplayName("encodes byte array to base64 string with NO_WRAP flag")
+        fun `encodes to base64 with NO_WRAP`() {
+            // Arrange
+            val inputBytes = byteArrayOf(1, 2, 3, 4, 5)
+            val expectedBase64 = "AQIDBAU="
+            every { Base64.encodeToString(inputBytes, Base64.NO_WRAP) } returns expectedBase64
+
+            // Act
+            val result = encoder.encodeToBase64(inputBytes)
+
+            // Assert
+            assertEquals(expectedBase64, result)
+        }
+    }
+
+    @Nested
+    @DisplayName("bitmapToScreenshotData")
+    inner class BitmapToScreenshotDataTests {
+
+        @Test
+        @DisplayName("returns ScreenshotData with correct format, dimensions, and non-empty data")
+        fun `returns complete ScreenshotData`() {
+            // Arrange
+            val width = 1080
+            val height = 2400
+            val quality = 80
+            val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
+            val base64Data = "/9j/"
+
+            val bitmap = mockk<Bitmap>(relaxed = true) {
+                every { getWidth() } returns width
+                every { getHeight() } returns height
+                every {
+                    compress(Bitmap.CompressFormat.JPEG, quality, any<OutputStream>())
+                } answers {
+                    val stream = thirdArg<OutputStream>()
+                    stream.write(jpegBytes)
+                    true
+                }
+            }
+            every { Base64.encodeToString(any<ByteArray>(), Base64.NO_WRAP) } returns base64Data
+
+            // Act
+            val result = encoder.bitmapToScreenshotData(bitmap, quality)
+
+            // Assert
+            assertEquals(ScreenshotData.FORMAT_JPEG, result.format)
+            assertEquals(base64Data, result.data)
+            assertEquals(width, result.width)
+            assertEquals(height, result.height)
+        }
+    }
+}
```

---

### Task 5.8: Create ScreenCaptureService Unit Tests

**Description**: Create unit tests for the `ScreenCaptureService` class covering MediaProjection state, capture failure handling, and mutex concurrency behavior.

**Acceptance Criteria**:
- [ ] Tests use JUnit 5 (`@Test` annotations from `org.junit.jupiter.api`)
- [ ] Tests use MockK for mocking Android framework classes (`MediaProjection`, `MediaProjectionManager`, `Context`)
- [ ] Tests follow Arrange-Act-Assert pattern
- [ ] Test: `isMediaProjectionActive` returns `false` when not set up
- [ ] Test: `isMediaProjectionActive` returns `true` after `setupMediaProjection` is called
- [ ] Test: `captureScreenshot` returns failure when projection is not active
- [ ] Test: `captureScreenshot` failure message is descriptive
- [ ] Test: concurrent `captureScreenshot` calls are serialized (mutex test)
- [ ] All tests pass
- [ ] File passes ktlint and detekt

> **Important Note on Service Testing**: Testing an Android `Service` in JVM unit tests requires careful mocking. The service inherits from `android.app.Service` which has many framework dependencies. Options:
> 1. **Robolectric**: Provides a full shadow implementation of Service. Preferred if available.
> 2. **MockK with relaxed mocking**: Mock the Service's Android methods. This tests logic flow but not actual service behavior.
> 3. **Extract logic into testable classes**: Move the capture logic into a separate non-Android class and test that instead.
>
> The plan below uses a pragmatic approach: test the public API contract by creating the service instance and mocking the required Android internals. At implementation time, adjust based on what the test infrastructure supports.

#### Action 5.8.1: Create `ScreenCaptureServiceTest.kt`

**What**: Create the unit test file for `ScreenCaptureService`.

**Context**: These tests verify the service's public API behavior. Since `ScreenCaptureService` extends `android.app.Service`, creating it in JVM tests requires Robolectric or extensive mocking. The tests below focus on the logical contract: (1) projection state tracking, (2) capture failure when projection is inactive, and (3) mutex serialization. MediaProjection setup is tested by verifying state changes. The actual capture pipeline (ImageReader, VirtualDisplay) is integration-test territory and is not covered here.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureServiceTest.kt`

```diff
--- /dev/null
+++ b/app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureServiceTest.kt
@@ -0,0 +1,132 @@
+package com.danielealbano.androidremotecontrolmcp.services.screencapture
+
+import android.media.projection.MediaProjection
+import io.mockk.every
+import io.mockk.mockk
+import io.mockk.unmockkAll
+import kotlinx.coroutines.test.runTest
+import org.junit.jupiter.api.AfterEach
+import org.junit.jupiter.api.Assertions.assertFalse
+import org.junit.jupiter.api.Assertions.assertNotNull
+import org.junit.jupiter.api.Assertions.assertTrue
+import org.junit.jupiter.api.BeforeEach
+import org.junit.jupiter.api.DisplayName
+import org.junit.jupiter.api.Nested
+import org.junit.jupiter.api.Test
+
+/**
+ * Unit tests for [ScreenCaptureService].
+ *
+ * Since ScreenCaptureService extends android.app.Service, direct instantiation
+ * in JVM tests requires Robolectric or extensive reflection. These tests use
+ * reflection to manipulate internal state and verify the service's logical contract.
+ *
+ * Integration tests (Plan 10) will cover the full service lifecycle on a real
+ * Android environment.
+ */
+@DisplayName("ScreenCaptureService")
+class ScreenCaptureServiceTest {
+
+// > **IMPORTANT — JVM test limitation**: `ScreenCaptureService` extends `android.app.Service` and uses `@AndroidEntryPoint`. Direct instantiation via `ScreenCaptureService()` in JVM unit tests will fail. At implementation time, use Robolectric or restructure to test the logic through extracted helper classes.
+
+    private lateinit var service: ScreenCaptureService
+    private lateinit var mockProjection: MediaProjection
+
+    @BeforeEach
+    fun setUp() {
+        service = ScreenCaptureService()
+        mockProjection = mockk<MediaProjection>(relaxed = true)
+        mockMediaProjectionManager = mockk<MediaProjectionManager>(relaxed = true)
+        injectMediaProjectionManager(mockMediaProjectionManager)
+    }
+
+    @AfterEach
+    fun tearDown() {
+        unmockkAll()
+    }
+
+    private lateinit var mockMediaProjectionManager: MediaProjectionManager
+
+    /**
+     * Helper to set the injected mediaProjectionManager field via reflection.
+     * Since the service is not started via Hilt in unit tests, we inject manually.
+     */
+    private fun injectMediaProjectionManager(manager: MediaProjectionManager) {
+        val field = ScreenCaptureService::class.java.getDeclaredField("mediaProjectionManager")
+        field.isAccessible = true
+        field.set(service, manager)
+    }
+
+    @Nested
+    @DisplayName("isMediaProjectionActive")
+    inner class IsMediaProjectionActiveTests {
+
+        @Test
+        @DisplayName("returns false when MediaProjection is not set up")
+        fun `returns false when not set up`() {
+            // Arrange
+            every { mockMediaProjectionManager.isProjectionActive() } returns false
+
+            // Act
+            val result = service.isMediaProjectionActive()
+
+            // Assert
+            assertFalse(result)
+        }
+
+        @Test
+        @DisplayName("returns true when MediaProjection is set up")
+        fun `returns true when set up`() {
+            // Arrange
+            every { mockMediaProjectionManager.isProjectionActive() } returns true
+
+            // Act
+            val result = service.isMediaProjectionActive()
+
+            // Assert
+            assertTrue(result)
+        }
+
+        @Test
+        @DisplayName("returns false after MediaProjection is cleared")
+        fun `returns false after cleared`() {
+            // Arrange
+            every { mockMediaProjectionManager.isProjectionActive() } returns true
+            // Simulate projection being stopped
+            every { mockMediaProjectionManager.isProjectionActive() } returns false
+
+            // Act
+            val result = service.isMediaProjectionActive()
+
+            // Assert
+            assertFalse(result)
+        }

> **Implementation Note — Test logic**: The first `every` mock is immediately overwritten by the second, so this test does not actually verify a state transition. At implementation time, rewrite to test actual state change (e.g., call setup, then simulate projection stop via callback).
+    }
+
+    @Nested
+    @DisplayName("captureScreenshot")
+    inner class CaptureScreenshotTests {
+
+        @Test
+        @DisplayName("returns failure when MediaProjection is not active")
+        fun `returns failure when projection not active`() = runTest {
+            // Arrange
+            every { mockMediaProjectionManager.isProjectionActive() } returns false
+
+            // Act
+            val result = service.captureScreenshot()
+
+            // Assert
+            assertTrue(result.isFailure)
+            val exception = result.exceptionOrNull()
+            assertNotNull(exception)
+            assertTrue(exception is IllegalStateException)
+            assertTrue(
+                exception!!.message!!.contains("MediaProjection is not active"),
+                "Error message should indicate projection is not active, got: ${exception.message}",
+            )
+        }
+
+        @Test
+        @DisplayName("failure message includes guidance to grant permission")
+        fun `failure message includes guidance`() = runTest {
+            // Arrange
+            every { mockMediaProjectionManager.isProjectionActive() } returns false
+
+            // Act
+            val result = service.captureScreenshot()
+
+            // Assert
+            val message = result.exceptionOrNull()?.message ?: ""
+            assertTrue(
+                message.contains("permission", ignoreCase = true),
+                "Error message should mention permission, got: $message",
+            )
+        }
+    }
+}
```

> **Implementation Note on Robolectric**: If Robolectric is configured in the project (check `app/build.gradle.kts` for `testImplementation("org.robolectric:robolectric:...")`), these tests can be converted to use `@RunWith(RobolectricTestRunner::class)` (JUnit 4) or `@ExtendWith(RobolectricExtension::class)` (JUnit 5). With Robolectric, you can test `setupMediaProjection()` more realistically. Without Robolectric, the reflection-based approach above tests the logical contract.

> **Implementation Note on Mutex Test**: The concurrent capture test was intentionally simplified. A proper mutex test would require two coroutines attempting to call `captureScreenshot()` simultaneously and verifying that only one executes at a time. This is complex to test reliably in a unit test because the capture involves Android framework calls (ImageReader, VirtualDisplay) that cannot be easily mocked in a way that introduces controlled delays. The mutex behavior is better verified in integration/E2E tests where actual capture operations take measurable time. If a mutex test is desired in unit tests, it can be added by mocking the internal capture method with a controllable delay.

---

### Task 5.9: Verification and Commit

**Description**: Run all verification commands to ensure the implementation is correct, then create the commits.

**Acceptance Criteria**:
- [ ] `make build` succeeds (debug APK is generated without errors or warnings)
- [ ] `make lint` succeeds (no ktlint or detekt violations)
- [ ] `make test-unit` succeeds (all tests pass, including new and existing tests)
- [ ] All 4 commits are created on the `feat/screen-capture-service` branch

**Tests**: These are the verification steps, not unit tests.

#### Action 5.9.1: Run `make lint`

**What**: Verify no linting errors or warnings.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make lint
```

**Expected outcome**: BUILD SUCCESSFUL with no ktlint or detekt violations.

**If it fails**: Run `make lint-fix` for auto-fixable issues, then re-run `make lint`. For non-auto-fixable issues, manually fix the code.

---

#### Action 5.9.2: Run `make test-unit`

**What**: Verify all unit tests pass (both existing tests from Plans 2-4 and new tests from this plan).

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make test-unit
```

**Expected outcome**: BUILD SUCCESSFUL. All tests pass including `ScreenshotEncoderTest` and `ScreenCaptureServiceTest`.

**If tests fail**: Investigate the failures, fix the root cause (in the implementation or test code), and re-run. Do not delete tests to make the suite pass.

---

#### Action 5.9.3: Run `make build`

**What**: Verify the project compiles successfully with all changes.

**Command**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp
make build
```

**Expected outcome**: BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.

**If it fails**: Inspect the last 150 lines of Gradle output, identify the root cause, and fix it. Common issues:
- Unresolved `R` class references (add explicit import)
- Hilt injection issues with `@Inject` on Service (see implementation note in Task 5.3)
- `currentWindowMetrics` API level issue (see implementation note in Task 5.3)
- Missing imports for new classes

---

#### Action 5.9.4: Create the feature branch and commits

**What**: Create the `feat/screen-capture-service` branch and make all 4 commits as defined in the commit strategy.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Create and switch to feature branch
git checkout -b feat/screen-capture-service

# Commit 1: ScreenshotData model and ScreenshotEncoder utility
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotData.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoder.kt
git commit -m "$(cat <<'EOF'
feat: add ScreenshotData model and ScreenshotEncoder utility

Add serializable ScreenshotData data class for MCP screenshot responses
and ScreenshotEncoder utility handling the full encoding pipeline:
Image-to-Bitmap conversion (with row padding handling), JPEG compression
with configurable quality, and base64 encoding.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 2: MediaProjectionManager and ScreenCaptureService
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/MediaProjectionManager.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureService.kt
git commit -m "$(cat <<'EOF'
feat: add MediaProjectionManager and ScreenCaptureService with screenshot capture

Add MediaProjectionManager class for MediaProjection lifecycle
management (SRP). Replace the Plan 1 stub with a full bound foreground
service implementation that delegates projection to MediaProjectionManager,
provides thread-safe screenshot capture via Mutex, and uses @AndroidEntryPoint
for Hilt injection. Notification channel created centrally in McpApplication.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 3: Screen info and MediaProjection UI handling
git add app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/ScreenInfo.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/McpAccessibilityService.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/MainActivity.kt \
       app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt \
       app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat: add screen info to AccessibilityService and MediaProjection handling to UI

Add ScreenInfo data class and getScreenInfo() method to
McpAccessibilityService. Update MainActivity with ActivityResultLauncher
for MediaProjection permission flow. Add MediaProjection state tracking
to MainViewModel (isMediaProjectionGranted, result storage).

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"

# Commit 4: Unit tests
git add app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenshotEncoderTest.kt \
       app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/screencapture/ScreenCaptureServiceTest.kt
git commit -m "$(cat <<'EOF'
test: add unit tests for screenshot encoder and capture service

Add ScreenshotEncoderTest covering image-to-bitmap conversion (with
and without row padding), JPEG encoding with quality clamping, base64
encoding, and the full bitmapToScreenshotData pipeline. Add
ScreenCaptureServiceTest covering projection state tracking and
capture failure when projection is not active.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

#### Action 5.9.5: Push and create PR

**What**: Push the branch and create the pull request.

**Commands**:
```bash
cd /home/daalbano/dev/android-remote-control-mcp

# Push feature branch
git push -u origin feat/screen-capture-service

# Create PR
gh pr create --base main --title "Plan 5: ScreenCaptureService and screenshot capture" --body "$(cat <<'EOF'
## Summary

- Add `ScreenshotData` serializable data class for MCP screenshot responses
- Add `ScreenshotEncoder` utility with full Image-to-base64 encoding pipeline (handles row padding, JPEG quality, base64 encoding)
- Replace `ScreenCaptureService` stub with complete bound foreground service implementing MediaProjection management, thread-safe screenshot capture, and proper lifecycle handling
- Add `ScreenInfo` data class and `getScreenInfo()` method to `McpAccessibilityService`
- Add MediaProjection permission flow to `MainActivity` (ActivityResultLauncher) and `MainViewModel` (state tracking)
- Add comprehensive unit tests for `ScreenshotEncoder` and `ScreenCaptureService`

## Plan Reference

Implementation of Plan 5: ScreenCaptureService & Screenshot Capture from `docs/plans/5_screen-capture-service_20260211163214.md`.

## Test plan

- [ ] `make test-unit` passes with all new and existing tests green
- [ ] `make lint` passes with no warnings or errors
- [ ] `make build` succeeds without errors or warnings
- [ ] Verify `ScreenshotEncoderTest` covers: image conversion (with/without padding), JPEG quality clamping, base64 encoding, full pipeline
- [ ] Verify `ScreenCaptureServiceTest` covers: projection state tracking, capture failure when inactive
- [ ] Manual: Install on device, grant MediaProjection permission via UI, verify notification appears

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Performance, Security, and QA Review

### Performance

- **Screenshot Capture Efficiency**: The capture pipeline creates and disposes ImageReader/VirtualDisplay for each capture call (via `setupImageReader()` and `setupVirtualDisplay()` called within `captureScreenshotInternal()`). This is a safe approach that avoids stale resources but has overhead. If performance becomes a concern (e.g., MCP clients requesting rapid sequential screenshots), a future optimization could keep the ImageReader/VirtualDisplay alive between captures and only recreate them on screen size changes. This is noted as a potential future improvement, not a current concern.

- **Bitmap Memory**: Bitmaps are allocated on the Java heap and can be large (e.g., 1080x2400x4 bytes = ~10MB for a full-screen RGBA image). The implementation recycles bitmaps immediately after encoding (`bitmap.recycle()` in `captureScreenshotInternal()`). The `imageToBitmap()` method creates a temporary padded bitmap that is also recycled when cropping is needed. Memory pressure is mitigated by the Mutex (only one capture at a time) and by the immediate recycle pattern.

- **Base64 Encoding**: Base64 encoding increases data size by ~33%. For a 1080x2400 JPEG at quality 80, the output is typically 200-500KB, resulting in ~270-670KB of base64 data. This is acceptable for MCP protocol responses. If bandwidth becomes a concern, consider adding support for binary responses or lower quality defaults.

- **Mutex Serialization**: The Mutex ensures only one screenshot capture happens at a time. This is necessary because ImageReader and VirtualDisplay are not thread-safe. The trade-off is that concurrent MCP screenshot requests are serialized, which adds latency for the second request. This is acceptable given the nature of screenshot operations (they capture the current screen state, so concurrent captures would produce nearly identical results).

### Security

- **MediaProjection Token**: The `resultCode` and `data` Intent from the MediaProjection permission dialog contain a session token. These are stored in `MainViewModel` (in-memory) and passed to `ScreenCaptureService.setupMediaProjection()`. They are NOT persisted to DataStore or any other storage. The token is invalidated when the MediaProjection stops or the service is destroyed. This is the correct security behavior -- screen capture permission should not survive app restarts.

- **Screenshot Data**: Base64-encoded screenshots are returned in MCP responses. They are not cached, stored on disk, or persisted in any way. Each capture is a single-use operation. The data passes through the MCP server (Plan 6) and is protected by bearer token authentication at the transport layer.

- **Foreground Service Notification**: The ScreenCaptureService shows a persistent notification while active, as required by Android for MediaProjection services. This provides transparency to the user that screen capture is active. The notification cannot be dismissed by the user (it is ongoing), which prevents silent screen capture.

- **No Root Required**: All operations use standard Android APIs (MediaProjection, ImageReader, VirtualDisplay). No root access, hidden APIs, or reflection on system classes is used.

### QA

- **API Level Compatibility**: The `WindowManager.currentWindowMetrics` API is available from API 30 (Android 11). Since `minSdk = 26`, the implementation notes include a version check with fallback to the deprecated `defaultDisplay.getRealMetrics()` for API 26-29. At implementation time, this MUST be verified and the fallback implemented.

- **Hilt Injection in Service**: `ScreenCaptureService` is annotated with `@AndroidEntryPoint`, enabling field injection of `ScreenshotEncoder` and `MediaProjectionManager`. Hilt supports `@AndroidEntryPoint` on Service subclasses since Hilt 2.28+. Both injected classes have `@Inject constructor()`, making them directly injectable. If Hilt injection fails at runtime (unlikely with Hilt 2.28+), fall back to `EntryPointAccessors.fromApplication()` pattern.

- **Row Padding in ImageReader**: The `imageToBitmap()` method handles the case where `rowStride > width * pixelStride`. This is a known issue with some devices where the buffer has padding bytes at the end of each row. The test covers both padded and non-padded cases. However, the implementation assumes `pixelStride == 4` (RGBA_8888). If a device reports a different pixel stride, the calculation `rowPadding / pixelStride` could produce incorrect results. At implementation time, verify this assumption or add a guard check.

- **Screenshot Timeout**: The `captureScreenshot()` method uses a 5-second timeout (`CAPTURE_TIMEOUT_MS`). If the ImageReader does not produce an image within this window, the capture fails with a descriptive error. This prevents indefinite hangs. The timeout value is appropriate for normal operation but may need adjustment for slow emulators or devices under heavy load.

- **Notification Channel Creation**: The notification channel is created centrally in `McpApplication.onCreate()` (Plan 6, Task 6.6.3), NOT in the service itself. This ensures both `McpServerService` and `ScreenCaptureService` share centralized channel management and avoids duplicate channel creation. The channel MUST exist before `startForeground()` is called. The channel ID `screen_capture_channel` matches the string resource defined in Plan 1.

- **Singleton Pattern Safety**: The `instance` companion object variable is `@Volatile` to ensure visibility across threads. It is set in `onStartCommand()` and cleared in `onDestroy()`. There is a small window between `startForeground()` and `instance = this` where the instance is not yet available. This is acceptable because the service is not usable until `setupMediaProjection()` is called, which happens later.

- **Test Coverage**: The unit tests cover the core logic (encoding pipeline, projection state, capture failure). Integration/E2E tests (Plans 7-10) will cover the full service lifecycle, actual screen capture, and inter-service communication.

---

## File Inventory

All files created or modified in this plan:

| # | File Path | Action | Task |
|---|-----------|--------|------|
| 1 | `app/src/main/kotlin/.../services/screencapture/ScreenshotData.kt` | CREATE | 5.1 |
| 2 | `app/src/main/kotlin/.../services/screencapture/ScreenshotEncoder.kt` | CREATE | 5.2 |
| 3 | `app/src/main/kotlin/.../services/screencapture/MediaProjectionManager.kt` | CREATE | 5.2b |
| 4 | `app/src/main/kotlin/.../services/screencapture/ScreenCaptureService.kt` | REPLACE (was stub from Plan 1) | 5.3 |
| 5 | `app/src/main/kotlin/.../services/accessibility/ScreenInfo.kt` | CREATE | 5.4 |
| 6 | `app/src/main/kotlin/.../services/accessibility/McpAccessibilityService.kt` | MODIFY (add getScreenInfo method) | 5.5 |
| 7 | `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt` | MODIFY (add MediaProjection state) | 5.6 |
| 8 | `app/src/main/kotlin/.../ui/MainActivity.kt` | MODIFY (add ActivityResultLauncher) | 5.6 |
| 9 | `app/src/test/kotlin/.../services/screencapture/ScreenshotEncoderTest.kt` | CREATE | 5.7 |
| 10 | `app/src/test/kotlin/.../services/screencapture/ScreenCaptureServiceTest.kt` | CREATE | 5.8 |

**Total**: 10 files (5 new, 1 replaced, 2 modified source, 2 new test)

---

**End of Plan 5**
