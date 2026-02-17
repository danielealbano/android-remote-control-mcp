package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var serviceScope: CoroutineScope? = null
    private var currentPackageName: String? = null
    private var currentActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "Window content changed: package=${event.packageName}")
            }
            else -> {
                // Ignored event types
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroying")

        serviceScope?.cancel()
        serviceScope = null
        currentPackageName = null
        currentActivityName = null
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
     * Returns true if the service is connected and has an active root node available.
     */
    fun isReady(): Boolean = instance != null && rootInActiveWindow != null

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

    private fun configureServiceInfo() {
        serviceInfo =
            serviceInfo?.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
            }
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

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L

        /**
         * Singleton instance of the accessibility service.
         * Set when the service connects, cleared when it is destroyed.
         * Access from other components to interact with the accessibility tree.
         */
        @Volatile
        var instance: McpAccessibilityService? = null
            private set
    }
}
