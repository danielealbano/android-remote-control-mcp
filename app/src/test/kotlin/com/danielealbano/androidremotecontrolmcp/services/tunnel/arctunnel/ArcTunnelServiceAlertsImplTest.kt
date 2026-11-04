package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.app.Notification
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
    private val alerts = ArcTunnelServiceAlertsImpl(context)

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class, NotificationManagerCompat::class, PendingIntent::class, Uri::class)
        mockkConstructor(NotificationCompat.Builder::class, Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } returns mockk(relaxed = true)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { PendingIntent.getActivity(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { anyConstructed<NotificationCompat.Builder>().build() } returns notification
        every { context.getString(R.string.server_arc_tunnel_status_body, *anyVararg()) } returns "Generic"
        every { context.getString(R.string.server_arc_tunnel_status_body_affected, *anyVararg()) } returns "Affected"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun permission(granted: Boolean) {
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { ContextCompat.checkSelfPermission(any(), any()) } returns result
    }

    /** The builder created for the status channel; MockK routes a construction only to matchers known beforehand. */
    private fun MockKMatcherScope.statusChannelBuilder(): NotificationCompat.Builder =
        constructedWith(
            OfTypeMatcher<Context>(Context::class),
            EqMatcher(McpApplication.ARC_TUNNEL_STATUS_CHANNEL_ID),
        )

    @Test
    fun `show posts 1007 on the status channel with the affected services`() {
        permission(granted = true)
        every { statusChannelBuilder().build() } returns notification

        val posted = alerts.show(ArcTunnelServiceProblem("degraded", listOf("Tunnels", "Database")))

        assertTrue(posted)
        verify { statusChannelBuilder().setContentText("Affected") }
        verify { context.getString(R.string.server_arc_tunnel_status_body_affected, "degraded", "Tunnels, Database") }
        verify { manager.notify(ArcTunnelServiceAlertsImpl.NOTIFICATION_ID, notification) }
    }

    @Test
    fun `show with no affected services uses the generic text`() {
        permission(granted = true)
        every { statusChannelBuilder().build() } returns notification

        val posted = alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        assertTrue(posted)
        verify { statusChannelBuilder().setContentText("Generic") }
        verify { context.getString(R.string.server_arc_tunnel_status_body, "degraded") }
        verify { manager.notify(ArcTunnelServiceAlertsImpl.NOTIFICATION_ID, notification) }
    }

    @Test
    fun `show alerts once and a tap opens the status page`() {
        permission(granted = true)
        every { statusChannelBuilder().build() } returns notification

        alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        verify { statusChannelBuilder().setOnlyAlertOnce(true) }
        verify { statusChannelBuilder().setAutoCancel(true) }
        verify { Uri.parse(ArcTunnelStatusPage.PAGE_URL) }
        verify { anyConstructed<Intent>().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    @Test
    fun `show without POST_NOTIFICATIONS posts nothing and says so`() {
        permission(granted = false)

        val posted = alerts.show(ArcTunnelServiceProblem("degraded", emptyList()))

        assertFalse(posted)
        verify(exactly = 0) { manager.notify(any<Int>(), any()) }
    }

    @Test
    fun `clear cancels 1007`() {
        alerts.clear()

        verify { manager.cancel(ArcTunnelServiceAlertsImpl.NOTIFICATION_ID) }
    }

    @Test
    fun `clearLeftover cancels 1007`() {
        ArcTunnelServiceAlertsImpl.clearLeftover(context)

        verify { manager.cancel(ArcTunnelServiceAlertsImpl.NOTIFICATION_ID) }
    }
}
