package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager

/** A keep-awake window with no input or accessibility content. Owned by one service instance. */
internal class ScreenAwakeOverlay(
    private val windowManager: WindowManager,
    private val powerManager: PowerManager,
    private val keyguardManager: KeyguardManager,
    private val createView: () -> View,
) {
    constructor(context: Context) : this(
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
        context.getSystemService(Context.POWER_SERVICE) as PowerManager,
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager,
        { View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } },
    )

    private var requested = false
    private var view: View? = null

    fun setRequested(awake: Boolean): Boolean {
        requested = awake
        return refresh()
    }

    /** Returns whether the request is still valid; sleep or lock permanently cancels this hold. */
    fun refresh(): Boolean {
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) requested = false
        if (!requested) {
            remove()
        } else if (view == null) {
            val candidate = createView()
            val params =
                WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT,
                )
            params.title = "MCP screen awake"
            runCatching { windowManager.addView(candidate, params) }
                .onSuccess { view = candidate }
                .onFailure { Log.w(TAG, "Could not keep the unlocked screen awake", it) }
        }
        return requested
    }

    fun close() {
        requested = false
        remove()
    }

    private fun remove() {
        view?.let { current ->
            runCatching { windowManager.removeView(current) }
                .onSuccess { view = null }
                .onFailure { Log.w(TAG, "Could not remove screen-awake overlay", it) }
        }
    }

    private companion object {
        const val TAG = "MCP:ScreenAwakeOverlay"
    }
}
