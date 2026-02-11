package com.danielealbano.androidremotecontrolmcp.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Utility functions for checking and requesting Android permissions.
 */
object PermissionUtils {
    private const val ENABLED_SERVICES_SEPARATOR = ':'

    /**
     * Checks whether a specific accessibility service is currently enabled.
     *
     * Reads the `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The accessibility service class to check (e.g., `McpAccessibilityService::class.java`).
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedComponentName =
            "${context.packageName}/${serviceClass.canonicalName}"

        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        return enabledServices
            .split(ENABLED_SERVICES_SEPARATOR)
            .any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    /**
     * Opens the Android Accessibility Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /**
     * Checks whether the `POST_NOTIFICATIONS` permission is granted.
     *
     * On Android 12 (API 32) and below, notification permission is always
     * granted. On Android 13 (API 33) and above, runtime permission is required.
     *
     * @param context Application context.
     * @return `true` if notification permission is granted, `false` otherwise.
     */
    fun isNotificationPermissionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
