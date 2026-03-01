package com.danielealbano.androidremotecontrolmcp.utils

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
        @BeforeEach
        fun setUpPermission() {
            mockkStatic(ContextCompat::class)
        }

        @AfterEach
        fun tearDownPermission() {
            unmockkStatic(ContextCompat::class)
        }

        @Test
        fun `returns true when permission is granted`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_GRANTED

            assertTrue(PermissionUtils.isNotificationPermissionGranted(mockContext))
        }

        @Test
        fun `returns false when permission is denied`() {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_DENIED

            assertFalse(PermissionUtils.isNotificationPermissionGranted(mockContext))
        }
    }

    @Nested
    @DisplayName("openNotificationListenerSettings")
    inner class OpenNotificationListenerSettings {
        @Test
        fun `starts activity with notification listener settings intent`() {
            PermissionUtils.openNotificationListenerSettings(mockContext)

            verify { mockContext.startActivity(any()) }
        }
    }

    @Nested
    @DisplayName("isNotificationListenerEnabled")
    inner class IsNotificationListenerEnabled {
        @Test
        fun `returns true when service is in enabled_notification_listeners`() {
            val serviceName =
                "com.danielealbano.androidremotecontrolmcp/" +
                    "com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService"
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns serviceName

            val serviceClass =
                Class.forName(
                    "com.danielealbano.androidremotecontrolmcp" +
                        ".services.notifications.McpNotificationListenerService",
                )
            assertTrue(
                PermissionUtils.isNotificationListenerEnabled(mockContext, serviceClass),
            )
        }

        @Test
        fun `returns false when service is not in list`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns "com.other.package/com.other.Service"

            assertFalse(
                PermissionUtils.isNotificationListenerEnabled(mockContext, Any::class.java),
            )
        }

        @Test
        fun `returns false when setting is null`() {
            every {
                Settings.Secure.getString(mockContentResolver, "enabled_notification_listeners")
            } returns null

            assertFalse(
                PermissionUtils.isNotificationListenerEnabled(mockContext, Any::class.java),
            )
        }
    }
}
