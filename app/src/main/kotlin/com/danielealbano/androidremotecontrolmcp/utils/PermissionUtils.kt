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
    private const val COMPONENT_SEPARATOR = '/'
    private const val SHORT_FORM_CLASS_PREFIX = '.'

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
        val enabledServices =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        return isServiceListed(enabledServices, context.packageName, serviceClass)
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
     * Checks whether the `POST_NOTIFICATIONS` runtime permission is granted.
     *
     * Android 12 (API 31/32) port: POST_NOTIFICATIONS only exists from API 33 on;
     * below that, notifications are posted without a runtime grant, so this returns
     * `true` unconditionally on API < 33.
     *
     * @param context Application context.
     * @return `true` if notification permission is granted, `false` otherwise.
     */
    fun isNotificationPermissionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Checks whether the `CAMERA` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if camera permission is granted, `false` otherwise.
     */
    fun isCameraPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the `RECORD_AUDIO` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if microphone permission is granted, `false` otherwise.
     */
    fun isMicrophonePermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the `ACCESS_FINE_LOCATION` runtime permission is granted.
     *
     * @param context Application context.
     * @return `true` if location permission is granted, `false` otherwise.
     */
    fun isLocationPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether a specific notification listener service is currently enabled.
     *
     * Reads the `Settings.Secure` `enabled_notification_listeners` system setting
     * and checks if the given service class is listed.
     *
     * @param context Application context.
     * @param serviceClass The notification listener service class to check.
     * @return `true` if the service is enabled, `false` otherwise.
     */
    fun isNotificationListenerEnabled(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val enabledListeners =
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false

        return isServiceListed(enabledListeners, context.packageName, serviceClass)
    }

    /**
     * Checks whether [serviceClass] appears in a colon-separated list of flattened
     * component names ([Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] or
     * `enabled_notification_listeners`).
     *
     * Android stores component names in either the fully-qualified form
     * (`package/package.path.ServiceClass`) or the leading-dot short form
     * (`package/.path.ServiceClass`), expanding the short form against the package
     * internally. This matcher mirrors `ComponentName.unflattenFromString`: a class
     * part beginning with `.` is resolved against its own package part before the
     * comparison, so both encodings of the same component match. Entries with no
     * separator or an empty class part are rejected; an entry with an empty package
     * part is parsed but can never equal a non-empty package, so none of these match.
     *
     * @param enabledEntries Raw colon-separated setting value.
     * @param expectedPackage The package the service belongs to (`context.packageName`).
     * @param serviceClass The service class to look for.
     * @return `true` if any entry resolves to [expectedPackage] + [serviceClass], `false` otherwise.
     */
    private fun isServiceListed(
        enabledEntries: String,
        expectedPackage: String,
        serviceClass: Class<*>,
    ): Boolean {
        val expectedClass = serviceClass.name

        return enabledEntries
            .split(ENABLED_SERVICES_SEPARATOR)
            .any { entry -> componentMatches(entry, expectedPackage, expectedClass) }
    }

    /**
     * Returns `true` when [flattenedComponent] (a single `package/class` entry) resolves
     * to [expectedPackage] and [expectedClass].
     *
     * The separator validation and leading-dot expansion mirror
     * [android.content.ComponentName.unflattenFromString]: an entry with no `/`, or with
     * an empty class part, is rejected; a class part beginning with `.` is prefixed with
     * its own package part. An empty package part is parsed like AOSP but can never equal
     * [expectedPackage] (`context.packageName` is never empty), so such entries never match.
     */
    private fun componentMatches(
        flattenedComponent: String,
        expectedPackage: String,
        expectedClass: String,
    ): Boolean {
        val separatorIndex = flattenedComponent.indexOf(COMPONENT_SEPARATOR)
        if (separatorIndex < 0 || separatorIndex >= flattenedComponent.length - 1) {
            return false
        }

        val packagePart = flattenedComponent.substring(0, separatorIndex)
        val classPart = flattenedComponent.substring(separatorIndex + 1)
        val resolvedClass =
            if (classPart[0] == SHORT_FORM_CLASS_PREFIX) {
                packagePart + classPart
            } else {
                classPart
            }

        return packagePart == expectedPackage && resolvedClass == expectedClass
    }

    /**
     * Opens the Android Notification Listener Settings screen.
     *
     * @param context Application context. Uses [Intent.FLAG_ACTIVITY_NEW_TASK]
     *   so this can be called from non-Activity contexts.
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
