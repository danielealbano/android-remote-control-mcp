package com.danielealbano.androidremotecontrolmcp.services.shell.approval

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ElevationApprovalNotifierImpl")
class ElevationApprovalNotifierImplTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<NotificationManagerCompat>()
    private val channel = mockk<NotificationChannel>()
    private val notifier = ElevationApprovalNotifierImpl(context)

    @BeforeEach
    fun setUp() {
        mockkObject(PermissionUtils)
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { PermissionUtils.isNotificationPermissionGranted(any()) } returns true
        every { manager.areNotificationsEnabled() } returns true
        every { manager.getNotificationChannel(McpApplication.ELEVATION_APPROVAL_CHANNEL_ID) } returns channel
        every { channel.importance } returns NotificationManager.IMPORTANCE_HIGH
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(NotificationManagerCompat::class)
        unmockkObject(PermissionUtils)
    }

    @Test
    fun `can notify when everything is on`() {
        assertEquals(true, notifier.canNotify())
    }

    @Test
    fun `permission missing`() {
        every { PermissionUtils.isNotificationPermissionGranted(any()) } returns false

        assertEquals(false, notifier.canNotify())
    }

    @Test
    fun `app notifications off`() {
        every { manager.areNotificationsEnabled() } returns false

        assertEquals(false, notifier.canNotify())
    }

    @Test
    fun `channel missing`() {
        every { manager.getNotificationChannel(McpApplication.ELEVATION_APPROVAL_CHANNEL_ID) } returns null

        assertEquals(false, notifier.canNotify())
    }

    @Test
    fun `channel turned off`() {
        every { channel.importance } returns NotificationManager.IMPORTANCE_NONE

        assertEquals(false, notifier.canNotify())
    }
}
