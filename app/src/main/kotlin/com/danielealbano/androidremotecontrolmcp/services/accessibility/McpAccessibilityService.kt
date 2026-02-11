package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class McpAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub: initial scaffolding — implementation provided in a later plan (approved by user)
    }

    @Suppress("EmptyFunctionBlock")
    override fun onInterrupt() {
    }
}
