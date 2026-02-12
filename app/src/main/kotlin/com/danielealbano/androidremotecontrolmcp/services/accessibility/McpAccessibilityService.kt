package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentCallbacks2
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * Returns the package name of the currently focused application,
     * or null if unknown.
     */
    fun getCurrentPackageName(): String? {
        return currentPackageName
    }

    /**
     * Returns the class name (activity name) of the currently focused window,
     * or null if unknown.
     */
    fun getCurrentActivityName(): String? {
        return currentActivityName
    }

    /**
     * Returns true if the service is connected and has an active root node available.
     */
    fun isReady(): Boolean {
        return instance != null && rootInActiveWindow != null
    }

    /**
     * Returns the [CoroutineScope] for this service, or null if not connected.
     */
    fun getServiceScope(): CoroutineScope? {
        return serviceScope
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

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L

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
