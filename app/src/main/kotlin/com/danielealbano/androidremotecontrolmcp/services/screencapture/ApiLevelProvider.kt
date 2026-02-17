package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.os.Build
import javax.inject.Inject

/**
 * Provides the device API level for testability.
 *
 * Production code uses [DefaultApiLevelProvider] which reads [Build.VERSION.SDK_INT].
 * Tests inject a mock to avoid JDK 17+ reflection issues with static final fields.
 */
interface ApiLevelProvider {
    fun getSdkInt(): Int
}

/**
 * Default implementation that delegates to [Build.VERSION.SDK_INT].
 */
class DefaultApiLevelProvider
    @Inject
    constructor() : ApiLevelProvider {
        override fun getSdkInt(): Int = Build.VERSION.SDK_INT
    }
