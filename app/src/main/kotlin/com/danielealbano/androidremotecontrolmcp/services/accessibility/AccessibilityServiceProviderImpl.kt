package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import javax.inject.Inject

/**
 * Production implementation of [AccessibilityServiceProvider] that delegates
 * to [McpAccessibilityService.instance] (the system-managed singleton).
 *
 * This class is Hilt-injectable and stateless.
 */
class AccessibilityServiceProviderImpl
    @Inject
    constructor() : AccessibilityServiceProvider {
        override fun getRootNode(): AccessibilityNodeInfo? = McpAccessibilityService.instance?.getRootNode()

        override fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
            McpAccessibilityService.instance?.getAccessibilityWindows() ?: emptyList()

        override fun getCurrentPackageName(): String? = McpAccessibilityService.instance?.getCurrentPackageName()

        override fun getCurrentActivityName(): String? = McpAccessibilityService.instance?.getCurrentActivityName()

        override fun getScreenInfo(): ScreenInfo =
            McpAccessibilityService.instance?.getScreenInfo()
                ?: throw McpToolException.PermissionDenied("Accessibility service not available")

        override fun isReady(): Boolean = McpAccessibilityService.instance?.isReady() == true

        override fun getContext(): Context? = McpAccessibilityService.instance
    }
