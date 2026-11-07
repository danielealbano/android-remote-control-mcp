package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShizukuProviderSwitchImpl")
class ShizukuProviderSwitchTest {
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val provider = mockk<ComponentName>()
    private val switch = ShizukuProviderSwitchImpl(packageManager, provider)

    @Test
    fun `enable sets the provider enabled without killing the app`() {
        switch.setEnabled(true)

        verify(exactly = 1) {
            packageManager.setComponentEnabledSetting(
                provider,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun `disable sets it disabled`() {
        switch.setEnabled(false)

        verify(exactly = 1) {
            packageManager.setComponentEnabledSetting(
                provider,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun `the provider state is recorded at process start`() {
        val states =
            listOf(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED to true,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED to false,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT to false,
            )

        states.forEach { (state, expected) ->
            val pm = mockk<PackageManager> { every { getComponentEnabledSetting(any()) } returns state }
            val context =
                mockk<Context> {
                    every { packageManager } returns pm
                    every { packageName } returns "app"
                }

            ShizukuProviderSwitchImpl.recordProcessStart(context)

            assertEquals(expected, switch.enabledAtProcessStart(), "state $state")
        }
    }
}
