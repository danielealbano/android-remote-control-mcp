package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import io.mockk.EqMatcher
import io.mockk.MockKMatcherScope
import io.mockk.OfTypeMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ArcTunnelServiceAlertsImpl")
class ArcTunnelServiceAlertsImplTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<NotificationManagerCompat>(relaxed = true)
    private val notification = mockk<Notification>(relaxed = true)
    private val channel = mockk<NotificationChannel>()
    private val statusPageIntent = mockk<Intent>(relaxed = true)
    private val tap = mockk<PendingIntent>(relaxed = true)
    private val alerts = ArcTunnelServiceAlertsImpl(context)

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class, NotificationManagerCompat::class, PendingIntent::class, Uri::class)
        mockkConstructor(NotificationCompat.Builder::class, NotificationCompat.BigTextStyle::class, Intent::class)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { manager.getNotificationChannel(McpApplication.ARC_TUNNEL_STATUS_CHANNEL_ID) } returns channel
        every { channel.importance } returns NotificationManager.IMPORTANCE_DEFAULT
        every { Uri.parse(ArcTunnelStatusPage.PAGE_URL) } returns mockk(relaxed = true)
        every { viewStatusPage().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } returns statusPageIntent
        every { PendingIntent.getActivity(context, any(), statusPageIntent, IMMUTABLE_UPDATE) } returns tap
        every { anyConstructed<NotificationCompat.Builder>().build() } returns notification
        every { statusChannelBuilder().build() } returns notification
        every { context.getString(R.string.server_arc_tunnel_status_title) } returns "Title"
        every { context.getString(R.string.server_arc_tunnel_status_body, *anyVararg()) } returns "Generic"
        every { context.getString(R.string.server_arc_tunnel_status_body_affected, *anyVararg()) } returns "Affected"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun permission(granted: Boolean) {
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) } returns result
    }

    /** The builder for the status channel; MockK routes a construction only to matchers known beforehand. */
    private fun MockKMatcherScope.statusChannelBuilder(): NotificationCompat.Builder =
        constructedWith(
            OfTypeMatcher<Context>(Context::class),
            EqMatcher(McpApplication.ARC_TUNNEL_STATUS_CHANNEL_ID),
        )

    /** Only the status page's address parses to a [Uri] here, so any other one does not reach this intent. */
    private fun MockKMatcherScope.viewStatusPage(): Intent =
        constructedWith(
            EqMatcher(Intent.ACTION_VIEW),
            OfTypeMatcher<Uri>(Uri::class),
        )

    @Test
    fun `show posts 1007 on the status channel with the affected services`() {
        permission(granted = true)

        alerts.show(ArcTunnelServiceProblem("degraded", listOf("Tunnels", "Database")))

        verify { statusChannelBuilder().setContentTitle("Title") }
        verify { statusChannelBuilder().setContentText("Affected") }
        verify { anyConstructed<NotificationCompat.BigTextStyle>().bigText("Affected") }
        verify { statusChannelBuilder().setStyle(ofType<NotificationCompat.BigTextStyle>()) }
        verify { statusChannelBuilder().setSmallIcon(R.drawable.ic_notification) }
        verify { statusChannelBuilder().setCategory(NotificationCompat.CATEGORY_STATUS) }
        verify { context.getString(R.string.server_arc_tunnel_status_body_affected, "degraded", "Tunnels, Database") }
        verify { manager.notify(NOTIFICATION_ID, notification) }
    }

    @Test
    fun `show with no affected services uses the generic text`() {
        permission(granted = true)

        alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        verify { statusChannelBuilder().setContentText("Generic") }
        verify { context.getString(R.string.server_arc_tunnel_status_body, "degraded") }
        verify { manager.notify(NOTIFICATION_ID, notification) }
    }

    @Test
    fun `show alerts once and a tap opens the status page`() {
        permission(granted = true)

        alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        verify { statusChannelBuilder().setOnlyAlertOnce(true) }
        verify { statusChannelBuilder().setAutoCancel(true) }
        verify { statusChannelBuilder().setContentIntent(tap) }
    }

    @Test
    fun `show without POST_NOTIFICATIONS posts nothing`() {
        permission(granted = false)

        alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        verify(exactly = 0) { manager.notify(any<Int>(), any()) }
    }

    @Test
    fun `canShow with the permission and the channel on`() {
        permission(granted = true)

        assertTrue(alerts.canShow())
    }

    @Test
    fun `canShow is false without POST_NOTIFICATIONS`() {
        permission(granted = false)

        assertFalse(alerts.canShow())
    }

    @Test
    fun `canShow is false with the channel turned off`() {
        permission(granted = true)
        every { channel.importance } returns NotificationManager.IMPORTANCE_NONE

        assertFalse(alerts.canShow())
    }

    @Test
    fun `canShow does not block on a channel the system does not have`() {
        permission(granted = true)
        every { manager.getNotificationChannel(McpApplication.ARC_TUNNEL_STATUS_CHANNEL_ID) } returns null

        assertTrue(alerts.canShow())
    }

    @Test
    fun `clear cancels 1007`() {
        alerts.clear()

        verify { manager.cancel(NOTIFICATION_ID) }
    }

    @Test
    fun `clearLeftover cancels 1007`() {
        ArcTunnelServiceAlertsImpl.clearLeftover(context)

        verify { manager.cancel(NOTIFICATION_ID) }
    }

    private companion object {
        const val NOTIFICATION_ID = 1007
        const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
