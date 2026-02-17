package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Abstracts access to the Android accessibility service singleton.
 *
 * Tools use this interface instead of accessing [McpAccessibilityService.instance] directly,
 * enabling JVM-based testing with mock implementations.
 */
interface AccessibilityServiceProvider {
    fun getRootNode(): AccessibilityNodeInfo?

    fun getAccessibilityWindows(): List<AccessibilityWindowInfo>

    fun getCurrentPackageName(): String?

    fun getCurrentActivityName(): String?

    fun getScreenInfo(): ScreenInfo

    fun isReady(): Boolean

    fun getContext(): Context?
}
