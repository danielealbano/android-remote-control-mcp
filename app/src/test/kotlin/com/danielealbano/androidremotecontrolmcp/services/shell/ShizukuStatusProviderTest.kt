package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShizukuStatusProviderImpl")
class ShizukuStatusProviderTest {
    private val pm = mockk<PackageManager>()
    private val context = mockk<Context> { every { packageManager } returns pm }
    private val api = mockk<ShizukuApi>()
    private val providerSwitch = mockk<ShizukuProviderSwitch> { every { enabledAtProcessStart() } returns true }
    private val provider = ShizukuStatusProviderImpl(context, api, providerSwitch)

    private fun installed(present: Boolean) {
        if (present) {
            every { pm.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } returns
                PackageInfo()
        } else {
            every { pm.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } throws
                PackageManager.NameNotFoundException()
        }
    }

    private fun running(
        version: Int = 13,
        granted: Boolean = true,
        uid: Int = 2000,
    ) {
        every { api.pingBinder() } returns true
        every { api.version() } returns version
        every { api.isPermissionGranted() } returns granted
        every { api.uid() } returns uid
    }

    @Test
    fun `not installed`() {
        every { api.pingBinder() } returns false
        installed(false)

        assertEquals(ShizukuState.NotInstalled, provider.state())
    }

    @Test
    fun `not running`() {
        every { api.pingBinder() } returns false
        installed(true)

        assertEquals(ShizukuState.NotRunning, provider.state())
    }

    @Test
    fun `needs restart when the provider was off at process start`() {
        every { api.pingBinder() } returns false
        every { providerSwitch.enabledAtProcessStart() } returns false
        installed(true)

        assertEquals(ShizukuState.NeedsRestart, provider.state())

        installed(false)

        assertEquals(ShizukuState.NotInstalled, provider.state())
    }

    @Test
    fun `a connected Shizuku is read even when the provider was off at process start`() {
        every { providerSwitch.enabledAtProcessStart() } returns false
        running(uid = 2000)

        assertEquals(ShizukuState.Ready(2000), provider.state())
    }

    @Test
    fun `ping throwing reads as not alive`() {
        every { api.pingBinder() } throws IllegalStateException("no binder")

        installed(true)
        assertEquals(ShizukuState.NotRunning, provider.state())

        installed(false)
        assertEquals(ShizukuState.NotInstalled, provider.state())
    }

    @Test
    fun `outdated`() {
        running(version = 12)

        assertEquals(ShizukuState.Outdated, provider.state())

        running(version = 13)

        assertNotEquals(ShizukuState.Outdated, provider.state())
    }

    @Test
    fun `not granted`() {
        running(granted = false)

        assertEquals(ShizukuState.NotGranted, provider.state())
    }

    @Test
    fun `ready with uid`() {
        running(uid = 2000)

        assertEquals(ShizukuState.Ready(2000), provider.state())
    }

    @Test
    fun `binder dying mid-check reads as not running`() {
        running()
        every { api.isPermissionGranted() } throws IllegalStateException("binder died")

        assertEquals(ShizukuState.NotRunning, provider.state())
    }

    @Test
    fun `shizukuUnavailableReason`() {
        val states =
            listOf(
                ShizukuState.NotInstalled,
                ShizukuState.NotRunning,
                ShizukuState.NeedsRestart,
                ShizukuState.Outdated,
                ShizukuState.NotGranted,
                ShizukuState.Ready(2000),
            )

        val reasons = states.map { shizukuUnavailableReason(it) }

        assertEquals("Shizuku is not installed", reasons[0])
        assertEquals("Shizuku is not running, or has not connected to the app yet", reasons[1])
        assertEquals(
            "Shizuku cannot connect until the app restarts, because elevation was turned on after the app started " +
                "(tap Restart app in the app's Shell settings)",
            reasons[2],
        )
        assertEquals("Shizuku is older than version 13 and must be updated", reasons[3])
        assertEquals(
            "Shizuku has not granted access to the app (grant it in the app's Shell settings)",
            reasons[4],
        )
        assertEquals("Shizuku is ready", reasons[5])
        assertEquals(states.size, reasons.distinct().size)
    }
}
