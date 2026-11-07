package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.ShizukuProvider
import javax.inject.Inject

interface ShizukuProviderSwitch {
    fun setEnabled(enabled: Boolean)

    /** Shizuku reaches the app only through a provider that was enabled when the app process started. */
    fun enabledAtProcessStart(): Boolean
}

class ShizukuProviderSwitchImpl internal constructor(
    private val packageManager: PackageManager,
    private val provider: ComponentName,
) : ShizukuProviderSwitch {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.packageManager, ComponentName(context, ShizukuProvider::class.java))

    override fun setEnabled(enabled: Boolean) {
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        packageManager.setComponentEnabledSetting(provider, state, PackageManager.DONT_KILL_APP)
    }

    override fun enabledAtProcessStart(): Boolean = enabledAtStart

    companion object {
        @Volatile
        private var enabledAtStart = false

        /** Called from `Application.attachBaseContext`, before the app's providers start. */
        fun recordProcessStart(context: Context) {
            val provider = ComponentName(context, ShizukuProvider::class.java)
            enabledAtStart =
                context.packageManager.getComponentEnabledSetting(provider) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }
}
