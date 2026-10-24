package com.danielealbano.androidremotecontrolmcp.services.apps

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AppIconCache")
class AppIconCacheTest {
    private lateinit var pm: PackageManager
    private lateinit var cache: AppIconCache

    @BeforeEach
    fun setUp() {
        pm = mockk()
        val context = mockk<Context> { every { packageManager } returns pm }
        cache = AppIconCache(context)
    }

    @Test
    fun `getLaunchableApps maps resolve infos to package and label sorted case-insensitively`() {
        // "Beta" < "alpha" in a case-sensitive sort and the package order is reversed, so this order only holds for a
        // case-insensitive sort by label.
        stubLaunchableApps(resolveInfo("com.a", "Beta"), resolveInfo("com.b", "alpha"))

        val apps = cache.getLaunchableApps()

        assertEquals(listOf("com.b" to "alpha", "com.a" to "Beta"), apps)
    }

    @Test
    fun `getLaunchableApps skips blank labels and labels equal to the package name`() {
        stubLaunchableApps(resolveInfo("com.x", "  "), resolveInfo("com.y", "com.y"), resolveInfo("com.z", "Zed"))

        val apps = cache.getLaunchableApps()

        assertEquals(listOf("com.z" to "Zed"), apps)
    }

    @Test
    fun `getLaunchableApps returns the cached list on the second call`() {
        stubLaunchableApps(resolveInfo("com.a", "Alpha"))

        val first = cache.getLaunchableApps()
        val second = cache.getLaunchableApps()

        assertEquals(first, second)
        verify(exactly = 1) { pm.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>()) }
    }

    /** `ResolveInfoFlags.of(0)` returns null in JVM unit tests, so the flags matcher must accept null. */
    private fun stubLaunchableApps(vararg infos: ResolveInfo) {
        every { pm.queryIntentActivities(any(), any<PackageManager.ResolveInfoFlags>()) } returns infos.toList()
    }

    private fun resolveInfo(
        pkg: String,
        label: String,
    ): ResolveInfo {
        val info = spyk(ResolveInfo())
        info.activityInfo = ActivityInfo().apply { packageName = pkg }
        every { info.loadLabel(pm) } returns label
        return info
    }
}
