package com.danielealbano.androidremotecontrolmcp.services.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermissionCheckerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PermissionChecker {
        /**
         * Android 12 (API 31/32) port: READ_MEDIA_IMAGES/VIDEO/AUDIO do not exist below
         * API 33, so checking them directly on 31/32 is meaningless (and MediaStore would
         * still deny non-owned reads without a declared/granted READ_EXTERNAL_STORAGE).
         * Map the media permissions to READ_EXTERNAL_STORAGE on API < 33 so the
         * built-in storage "All files" mode reports and enforces the correct grant.
         */
        override fun hasPermission(permission: String): Boolean {
            val effectivePermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    when (permission) {
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        -> Manifest.permission.READ_EXTERNAL_STORAGE

                        else -> permission
                    }
                } else {
                    permission
                }
            return ContextCompat.checkSelfPermission(context, effectivePermission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
