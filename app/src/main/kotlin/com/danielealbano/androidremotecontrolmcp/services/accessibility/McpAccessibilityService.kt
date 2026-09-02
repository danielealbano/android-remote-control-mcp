package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

@Suppress("TooManyFunctions")
class McpAccessibilityService : AccessibilityService() {
    // AccessibilityNodeCache is in the same package — no import needed
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NodeCacheEntryPoint {
        fun nodeCache(): AccessibilityNodeCache
    }

    private var serviceScope: CoroutineScope? = null

    private var nodeCache: AccessibilityNodeCache? = null

    private var cacheInvalidationDebouncer: CacheInvalidationDebouncer? = null

    private var toolCallIndicatorView: View? = null

    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var currentActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        nodeCache = resolveNodeCache()
        cacheInvalidationDebouncer =
            CacheInvalidationDebouncer(
                scope = scope,
                debounceMillis = CACHE_INVALIDATION_DEBOUNCE_MS,
                onSettled = { invalidateCache(nodeCache) },
            )

        configureServiceInfo()

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { packageName ->
                    currentPackageName = packageName
                }
                event.className?.toString()?.let { className ->
                    currentActivityName = className
                }
                Log.d(
                    TAG,
                    "Window state changed: package=$currentPackageName, " +
                        "activity=$currentActivityName",
                )
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Soft-keyboard show/hide and other window add/remove/bounds changes arrive here.
                Log.d(TAG, "Windows changed (e.g. soft-keyboard show/hide)")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "Window content changed: package=${event.packageName}")
            }

            else -> {
                // Ignored event types
            }
        }

        // A structural window change (keyboard show/hide, rotation, activity/dialog transition)
        // shifts element bounds. Because cached node ids are derived from bounds, and which
        // elements are present/actionable also changes, the cached id->node entries become stale.
        // Schedule a debounced invalidation so the cache is dropped once — and only once — AFTER
        // the transition settles, so the next node lookup resolves against the live tree.
        scheduleCacheInvalidationIfNeeded(event.eventType, cacheInvalidationDebouncer)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroying")

        // Stop any pending debounced invalidation before tearing down the scope it runs on.
        cacheInvalidationDebouncer?.cancel()
        cacheInvalidationDebouncer = null

        // Flush the node cache — all AccessibilityNodeInfo references become invalid.
        nodeCache?.clear()
        nodeCache = null

        serviceScope?.cancel()
        serviceScope = null
        currentPackageName = null
        currentActivityName = null
        inputMethodInstance = null
        removeToolCallIndicator()
        instance = null

        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                else -> "UNKNOWN($level)"
            }
        Log.w(TAG, "Trim memory: level=$levelName")
    }

    /**
     * Returns the root [AccessibilityNodeInfo] of the currently active window,
     * or null if no window is available.
     */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Returns all on-screen windows via [AccessibilityService.getWindows].
     * Requires [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS].
     *
     * @return List of [AccessibilityWindowInfo], or empty list if unavailable.
     */
    fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
        try {
            windows ?: emptyList()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "getWindows() failed: ${e.message}")
            emptyList()
        }

    /**
     * Returns the package name of the currently focused application,
     * or null if unknown.
     */
    fun getCurrentPackageName(): String? = currentPackageName

    /**
     * Returns the class name (activity name) of the currently focused window,
     * or null if unknown.
     */
    fun getCurrentActivityName(): String? = currentActivityName

    /**
     * Returns true if the service is connected and ready to process requests.
     * Does NOT check for an active window — multi-window support handles
     * window availability at tree-parsing time.
     */
    fun isReady(): Boolean = instance != null

    /**
     * Returns the [CoroutineScope] for this service, or null if not connected.
     */
    fun getServiceScope(): CoroutineScope? = serviceScope

    /**
     * Returns the current screen dimensions, density, and orientation.
     *
     * @return [ScreenInfo] with width, height, densityDpi, and orientation.
     */
    fun getScreenInfo(): ScreenInfo {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val width = bounds.width()
        val height = bounds.height()

        val displayMetrics = resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        val orientation =
            when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ScreenInfo.ORIENTATION_LANDSCAPE
                else -> ScreenInfo.ORIENTATION_PORTRAIT
            }

        return ScreenInfo(
            width = width,
            height = height,
            densityDpi = densityDpi,
            orientation = orientation,
        )
    }

    /**
     * Android 12 (API 31/32) port: the framework only invokes this override on API 33+
     * (the base AccessibilityService.onCreateInputMethod did not exist before 33), so the
     * whole method is @RequiresApi(TIRAMISU) — no guard needed, and never called below 33.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        val method = McpInputMethod(this)
        inputMethodInstance = method
        return method
    }

    private fun showToolCallIndicatorInternal(toolName: String) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val textView = (toolCallIndicatorView as? TextView) ?: createToolCallIndicatorView()
        textView.text = "MCP controlling · ${formatToolName(toolName)}"
        if (toolCallIndicatorView == null) {
            runCatching {
                windowManager.addView(textView, createToolCallIndicatorLayoutParams())
                toolCallIndicatorView = textView
            }.onFailure { Log.w(TAG, "Could not show MCP tool-call indicator", it) }
        }
    }

    private fun removeToolCallIndicator() {
        val view = toolCallIndicatorView ?: return
        toolCallIndicatorView = null
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "Could not remove MCP tool-call indicator", it) }
    }

    private fun createToolCallIndicatorView(): TextView {
        val horizontalPadding = TOOL_INDICATOR_HORIZONTAL_PADDING_DP.dpToPx()
        val verticalPadding = TOOL_INDICATOR_VERTICAL_PADDING_DP.dpToPx()
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = TOOL_INDICATOR_TEXT_SIZE_SP
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background =
                GradientDrawable().apply {
                    setColor(TOOL_INDICATOR_BACKGROUND_COLOR)
                    cornerRadius = TOOL_INDICATOR_CORNER_RADIUS_DP.dpToPx().toFloat()
                }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun createToolCallIndicatorLayoutParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = TOOL_INDICATOR_TOP_MARGIN_DP.dpToPx()
            }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun configureServiceInfo() {
        serviceInfo =
            serviceInfo?.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
            }
        if (serviceInfo == null) {
            Log.w(TAG, "serviceInfo is null, cannot configure accessibility service settings")
        }
    }

    /**
     * Resolves the singleton [AccessibilityNodeCache] via Hilt's application entry point.
     * Returns null (and logs) if Hilt is not initialized, in which case cache invalidation
     * becomes a no-op rather than crashing the service.
     */
    private fun resolveNodeCache(): AccessibilityNodeCache? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .nodeCache()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve node cache", e)
            null
        }

    /**
     * Takes a screenshot using AccessibilityService.takeScreenshot() API.
     * Does NOT require user consent.
     *
     * @param timeoutMs Maximum time to wait for screenshot capture.
     * @return Bitmap of the screenshot, or null if capture failed or timed out.
     */
    suspend fun takeScreenshotBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val executor = Executor { it.run() }
                val callback =
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap =
                                Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace,
                                )
                            screenshot.hardwareBuffer.close()
                            if (continuation.isActive) {
                                continuation.resume(bitmap)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            }
        }

    /**
     * Returns true if screenshot capability is available. Always true on minSdk 33+.
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun canTakeScreenshot(): Boolean = true

    /**
     * Drops the framework's accessibility node cache for this service via [clearCache] (public
     * since API 33). See [AccessibilityServiceProvider.clearFrameworkNodeCache] for
     * why this is needed to defeat stale WebView reads after JavaScript DOM changes.
     *
     * Android 12 (API 31/32) port: [AccessibilityService.clearCache] does not exist below
     * API 33 — guard the call. On 31/32 the framework cache cannot be flushed this way; the
     * caller's own [invalidateCache] (our id→node cache) still works, so this is a best-effort
     * no-op rather than a crash.
     *
     * This is distinct from [invalidateCache], which flushes our own id→node [nodeCache]; this
     * clears the framework-side cache that backs [rootInActiveWindow]/[getWindows] traversal.
     */
    fun clearFrameworkNodeCache() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearCache()
        }
    }

    /**
     * API 33+ only: android.accessibilityservice.InputMethod (and its
     * AccessibilityInputConnection) do not exist below API 33, and
     * [McpAccessibilityService.onCreateInputMethod] never creates [McpInputMethod] below
     * 33, so this class can never be instantiated on API < 33.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class McpInputMethod(
        service: AccessibilityService,
    ) : InputMethod(service)

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L
        private const val TOOL_INDICATOR_HORIZONTAL_PADDING_DP = 16
        private const val TOOL_INDICATOR_VERTICAL_PADDING_DP = 8
        private const val TOOL_INDICATOR_CORNER_RADIUS_DP = 18
        private const val TOOL_INDICATOR_TOP_MARGIN_DP = 12
        private const val TOOL_INDICATOR_TEXT_SIZE_SP = 13f
        private const val TOOL_INDICATOR_BACKGROUND_COLOR = 0xE6212121.toInt()

        /**
         * Length of the QUIET GAP (no further window-structure events) that must elapse before the
         * node cache is invalidated. The debounce timer resets on every event during a transition,
         * so this value is the silence required *after the last event*, not the total transition
         * duration. 250ms is an empirically chosen heuristic — long enough that a settling
         * keyboard/rotation has stopped emitting events, short enough to keep the post-transition
         * cache fresh quickly. Single tunable constant; validate/adjust against real-device traces.
         */
        private const val CACHE_INVALIDATION_DEBOUNCE_MS = 250L

        /**
         * Singleton instance of the accessibility service.
         * Set when the service connects, cleared when it is destroyed.
         * Access from other components to interact with the accessibility tree.
         */
        @Volatile
        var instance: McpAccessibilityService? = null
            private set

        @Volatile
        var inputMethodInstance: McpInputMethod? = null
            private set

        fun showToolCallIndicator(toolName: String) {
            if (instance == null) return
            Handler(Looper.getMainLooper()).post {
                instance?.showToolCallIndicatorInternal(toolName)
            }
        }

        fun hideToolCallIndicator() {
            val service = instance ?: return
            Handler(Looper.getMainLooper()).post {
                if (instance === service) {
                    service.removeToolCallIndicator()
                }
            }
        }
    }
}

internal fun formatToolName(toolName: String): String = toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * Returns true if [eventType] is a structural window change after which cached node bounds may be
 * stale and the node cache should be invalidated: [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]
 * (rotation, activity/dialog transition) and [AccessibilityEvent.TYPE_WINDOWS_CHANGED]
 * (soft-keyboard show/hide, window add/remove).
 *
 * Deliberately EXCLUDES [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED], which fires far too
 * frequently (e.g. live text, progress bars) to drive cache invalidation without thrashing.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun triggersCacheInvalidation(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

/**
 * Schedules a debounced cache invalidation iff [eventType] is a structural window change (see
 * [triggersCacheInvalidation]). No-op when [debouncer] is null (service not fully connected).
 *
 * Top-level and `internal` so the event-to-schedule wiring can be unit-tested without
 * instantiating the service.
 */
internal fun scheduleCacheInvalidationIfNeeded(
    eventType: Int,
    debouncer: CacheInvalidationDebouncer?,
) {
    if (triggersCacheInvalidation(eventType)) {
        debouncer?.schedule()
    }
}

/**
 * Clears [cache] if present. The whole-cache drop is the invalidation applied after a settled
 * window transition; a null [cache] (Hilt entry point unavailable) is a safe no-op.
 *
 * Top-level and `internal` so the invalidation behavior can be unit-tested without instantiating
 * the service.
 */
internal fun invalidateCache(cache: AccessibilityNodeCache?) {
    cache?.clear()
}
