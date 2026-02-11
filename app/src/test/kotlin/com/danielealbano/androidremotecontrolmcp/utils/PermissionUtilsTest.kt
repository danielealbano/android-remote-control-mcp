package com.danielealbano.androidremotecontrolmcp.utils

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionUtils")
class PermissionUtilsTest {
    private val mockContext: Context = mockk(relaxed = true)
    private val mockContentResolver: ContentResolver = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.packageName } returns "com.danielealbano.androidremotecontrolmcp"
        mockkStatic(Settings.Secure::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Settings.Secure::class)
    }

    @Nested
    @DisplayName("isAccessibilityServiceEnabled")
    inner class IsAccessibilityServiceEnabled {
        @Test
        fun `returns true when service is in enabled list`() {
            val serviceName =
                "com.danielealbano.androidremotecontrolmcp/" +
                    "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService"
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns serviceName

            assertTrue(
                PermissionUtils.isAccessibilityServiceEnabled(
                    mockContext,
                    Class.forName(
                        "com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService",
                    ),
                ),
            )
        }

        @Test
        fun `returns false when enabled services is null`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns null

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
            )
        }

        @Test
        fun `returns false when service is not in enabled list`() {
            every {
                Settings.Secure.getString(mockContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } returns "com.other.package/com.other.Service"

            assertFalse(
                PermissionUtils.isAccessibilityServiceEnabled(mockContext, Any::class.java),
            )
        }
    }

    @Nested
    @DisplayName("openAccessibilitySettings")
    inner class OpenAccessibilitySettings {
        @Test
        fun `starts activity with accessibility settings intent`() {
            PermissionUtils.openAccessibilitySettings(mockContext)

            verify { mockContext.startActivity(any()) }
        }
    }

    @Nested
    @DisplayName("isNotificationPermissionGranted")
    inner class IsNotificationPermissionGranted {
        @Test
        fun `returns true on API below 33`() {
            // On API < 33, notification permission is always granted.
            // In unit test environment, Build.VERSION.SDK_INT is 0, which is < 33.
            assertTrue(PermissionUtils.isNotificationPermissionGranted(mockContext))
        }
    }
}
