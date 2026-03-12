package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermissionCheckerImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PermissionChecker {
        override fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }
