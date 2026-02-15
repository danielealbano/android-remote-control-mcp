package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Represents an installed application on the device.
 *
 * @property packageId The application package name (e.g., "com.example.app").
 * @property name The user-visible application label.
 * @property versionName The version name string (e.g., "1.2.3"), or null if unavailable.
 * @property versionCode The numeric version code.
 * @property isSystemApp True if this is a pre-installed system application.
 */
data class AppInfo(
    val packageId: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean,
)
