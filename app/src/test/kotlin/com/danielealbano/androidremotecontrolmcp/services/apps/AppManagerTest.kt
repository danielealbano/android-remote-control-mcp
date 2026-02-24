package com.danielealbano.androidremotecontrolmcp.services.apps

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.danielealbano.androidremotecontrolmcp.data.model.AppFilter
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
@DisplayName("AppManagerImpl")
class AppManagerTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockPackageManager: PackageManager

    @MockK
    private lateinit var mockActivityManager: ActivityManager

    private lateinit var appManager: AppManagerImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(PackageInfoCompat::class)

        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.getSystemService(any<String>()) } returns mockActivityManager

        appManager = AppManagerImpl(mockContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        unmockkStatic(PackageInfoCompat::class)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /**
     * Creates an [ApplicationInfo] with the given package name and flags.
     * Uses [ApplicationInfo.FLAG_SYSTEM] for system apps; 0 for user apps.
     */
    private fun createApplicationInfo(
        packageName: String,
        isSystem: Boolean,
    ): ApplicationInfo {
        val info = ApplicationInfo()
        info.packageName = packageName
        info.flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
        return info
    }

    /**
     * Sets up the standard set of three installed apps (2 user + 1 system)
     * and configures [PackageManager] mocks for application labels, package
     * info, and version codes.
     */
    private fun setupThreeInstalledApps() {
        val userApp1 = createApplicationInfo("com.user.alpha", isSystem = false)
        val userApp2 = createApplicationInfo("com.user.beta", isSystem = false)
        val systemApp = createApplicationInfo("com.system.core", isSystem = true)

        every {
            mockPackageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        } returns listOf(userApp1, userApp2, systemApp)

        every { mockPackageManager.getApplicationLabel(userApp1) } returns
            "Alpha App" as CharSequence
        every { mockPackageManager.getApplicationLabel(userApp2) } returns
            "Beta App" as CharSequence
        every { mockPackageManager.getApplicationLabel(systemApp) } returns
            "System App" as CharSequence

        val pkgInfo1 = PackageInfo()
        pkgInfo1.versionName = "1.0.0"
        val pkgInfo2 = PackageInfo()
        pkgInfo2.versionName = "2.0.0"
        val pkgInfo3 = PackageInfo()
        pkgInfo3.versionName = "10.0.0"

        every { mockPackageManager.getPackageInfo("com.user.alpha", 0) } returns pkgInfo1
        every { mockPackageManager.getPackageInfo("com.user.beta", 0) } returns pkgInfo2
        every { mockPackageManager.getPackageInfo("com.system.core", 0) } returns pkgInfo3

        every { PackageInfoCompat.getLongVersionCode(pkgInfo1) } returns 1L
        every { PackageInfoCompat.getLongVersionCode(pkgInfo2) } returns 2L
        every { PackageInfoCompat.getLongVersionCode(pkgInfo3) } returns 100L
    }

    // ─────────────────────────────────────────────────────────────────────
    // listInstalledApps
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listInstalledApps")
    inner class ListInstalledApps {
        @Test
        fun `listInstalledApps returns all apps when filter is ALL`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act
                val result = appManager.listInstalledApps(AppFilter.ALL)

                // Assert
                assertEquals(3, result.size)
            }

        @Test
        fun `listInstalledApps returns only user apps when filter is USER`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act
                val result = appManager.listInstalledApps(AppFilter.USER)

                // Assert
                assertEquals(2, result.size)
                assertTrue(result.all { !it.isSystemApp })
                assertTrue(result.any { it.name == "Alpha App" })
                assertTrue(result.any { it.name == "Beta App" })
            }

        @Test
        fun `listInstalledApps returns only system apps when filter is SYSTEM`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act
                val result = appManager.listInstalledApps(AppFilter.SYSTEM)

                // Assert
                assertEquals(1, result.size)
                assertTrue(result.all { it.isSystemApp })
                assertEquals("System App", result[0].name)
                assertEquals("com.system.core", result[0].packageId)
            }

        @Test
        fun `listInstalledApps filters by name query case-insensitive`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act — "alpha" matches "Alpha App" case-insensitively
                val result = appManager.listInstalledApps(AppFilter.ALL, "alpha")

                // Assert
                assertEquals(1, result.size)
                assertEquals("Alpha App", result[0].name)
            }

        @Test
        fun `listInstalledApps returns empty list when no matches`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act
                val result = appManager.listInstalledApps(AppFilter.ALL, "nonexistent")

                // Assert
                assertTrue(result.isEmpty())
            }

        @Test
        fun `listInstalledApps results are sorted by name`() =
            runTest {
                // Arrange
                setupThreeInstalledApps()

                // Act
                val result = appManager.listInstalledApps(AppFilter.ALL)

                // Assert — alphabetical by name (case-insensitive)
                assertEquals("Alpha App", result[0].name)
                assertEquals("Beta App", result[1].name)
                assertEquals("System App", result[2].name)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // openApp
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("openApp")
    inner class OpenApp {
        @Test
        fun `openApp launches app with correct intent flags`() =
            runTest {
                // Arrange
                val mockIntent = mockk<Intent>(relaxed = true)
                every {
                    mockPackageManager.getLaunchIntentForPackage("com.test.app")
                } returns mockIntent
                every { mockContext.startActivity(any()) } just Runs

                // Act
                val result = appManager.openApp("com.test.app")

                // Assert
                assertTrue(result.isSuccess)
                verify { mockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                verify { mockContext.startActivity(mockIntent) }
            }

        @Test
        fun `openApp returns failure when no launchable activity`() =
            runTest {
                // Arrange
                every {
                    mockPackageManager.getLaunchIntentForPackage("com.test.app")
                } returns null

                // Act
                val result = appManager.openApp("com.test.app")

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("No launchable activity") == true,
                )
            }

        @Test
        fun `openApp returns failure when package not found`() =
            runTest {
                // Arrange
                val mockIntent = mockk<Intent>(relaxed = true)
                every {
                    mockPackageManager.getLaunchIntentForPackage("com.nonexistent.app")
                } returns mockIntent
                every { mockIntent.addFlags(any()) } returns mockIntent
                every { mockContext.startActivity(any()) } throws ActivityNotFoundException("Activity not found")

                // Act
                val result = appManager.openApp("com.nonexistent.app")

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is ActivityNotFoundException)
            }

        @Test
        fun `openApp returns failure on SecurityException`() =
            runTest {
                // Arrange
                val mockIntent = mockk<Intent>(relaxed = true)
                every {
                    mockPackageManager.getLaunchIntentForPackage("com.restricted.app")
                } returns mockIntent
                every { mockIntent.addFlags(any()) } returns mockIntent
                every { mockContext.startActivity(any()) } throws SecurityException("Permission denied")

                // Act
                val result = appManager.openApp("com.restricted.app")

                // Assert
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is SecurityException)
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // closeApp
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("closeApp")
    inner class CloseApp {
        @Test
        fun `closeApp calls killBackgroundProcesses`() =
            runTest {
                // Arrange
                every { mockActivityManager.killBackgroundProcesses(any()) } just Runs

                // Act
                val result = appManager.closeApp("com.test.app")

                // Assert
                assertTrue(result.isSuccess)
                verify { mockActivityManager.killBackgroundProcesses("com.test.app") }
            }
    }
}
